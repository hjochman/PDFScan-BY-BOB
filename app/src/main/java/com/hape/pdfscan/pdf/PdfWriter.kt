package com.hape.pdfscan.pdf

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max

private const val TAG = "PdfWriter"

/** Longest page side in PDF points (A4 height = 842 pt); the image is scaled to fit. */
private const val PAGE_LONG_SIDE_PT = 842.0

/** Pixel size and colour component count of a JPEG file. */
internal data class JpegInfo(val width: Int, val height: Int, val components: Int)

/**
 * Reads width, height and component count from the first SOF marker of a JPEG.
 * Throws [IllegalArgumentException] if [bytes] is not a usable JPEG.
 */
internal fun readJpegInfo(bytes: ByteArray): JpegInfo {
    require(bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) { "not a JPEG" }
    var i = 2
    while (i + 3 < bytes.size) {
        if (bytes[i] != 0xFF.toByte()) {
            i++
            continue
        }
        val marker = bytes[i + 1].toInt() and 0xFF
        when {
            marker == 0xFF -> i++ // fill byte
            marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD8 -> i += 2 // no length field
            marker == 0xD9 -> break // EOI
            else -> {
                val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isSof) {
                    require(i + 9 < bytes.size) { "truncated JPEG" }
                    val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                    val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                    val components = bytes[i + 9].toInt() and 0xFF
                    require(width > 0 && height > 0) { "invalid JPEG size" }
                    require(components == 1 || components == 3) { "unsupported JPEG colour model" }
                    return JpegInfo(width, height, components)
                }
                i += 2 + length
            }
        }
    }
    throw IllegalArgumentException("no SOF marker found")
}

/**
 * Writes JPEG files into a multi-page PDF file.
 *
 * The JPEG bytes are embedded unchanged (`/DCTDecode`), so the PDF size is roughly the sum of
 * the JPEG file sizes. Size and quality are therefore decided when the JPEG is created, not here.
 * Each page is sized so that the image's longer side is [PAGE_LONG_SIDE_PT] points.
 *
 * Writing is atomic: the PDF is first written to [outputFile].tmp, then renamed to [outputFile].
 * If the temp file exists already it is deleted before writing begins.
 */
object PdfWriter {

    /**
     * Writes [jpegFiles] into [outputFile] atomically, one page per file.
     *
     * @param jpegFiles Non-empty list of JPEG files (baseline or progressive, grey or RGB).
     * @param outputFile Target .pdf file (need not exist yet; its parent must exist).
     * @return `true` on success, `false` if any I/O error occurred or a file is not a usable
     *         JPEG (the target file is not created or left in a partial state).
     */
    fun write(jpegFiles: List<File>, outputFile: File): Boolean {
        require(jpegFiles.isNotEmpty()) { "jpegFiles must not be empty" }

        val tmpFile = File(outputFile.parent, outputFile.name + ".tmp")
        tmpFile.delete()

        return try {
            BufferedOutputStream(FileOutputStream(tmpFile)).use { out ->
                writePdf(jpegFiles, out)
            }

            // Atomic rename
            if (!tmpFile.renameTo(outputFile)) {
                // renameTo may fail across file systems; fall back to copy + delete
                tmpFile.copyTo(outputFile, overwrite = true)
                tmpFile.delete()
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "PDF write failed: ${t.javaClass.simpleName}")
            tmpFile.delete()
            outputFile.delete()
            false
        }
    }

    // Object numbering: 1 = catalog, 2 = pages, then per page i: 3+3i page, 4+3i contents, 5+3i image.
    private fun writePdf(jpegFiles: List<File>, out: BufferedOutputStream) {
        var position = 0L
        val offsets = mutableListOf<Long>() // offsets[n-1] = byte offset of object n

        fun raw(bytes: ByteArray) {
            out.write(bytes)
            position += bytes.size
        }

        fun text(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))

        fun beginObject() {
            offsets.add(position)
            text("${offsets.size} 0 obj\n")
        }

        fun num(v: Double) = String.format(Locale.US, "%.2f", v)

        text("%PDF-1.4\n")
        raw(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        beginObject()
        text("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        beginObject()
        val kids = jpegFiles.indices.joinToString(" ") { "${3 + 3 * it} 0 R" }
        text("<< /Type /Pages /Kids [$kids] /Count ${jpegFiles.size} >>\nendobj\n")

        for ((index, file) in jpegFiles.withIndex()) {
            val bytes = file.readBytes()
            val info = readJpegInfo(bytes)
            val scale = PAGE_LONG_SIDE_PT / max(info.width, info.height)
            val pageW = num(info.width * scale)
            val pageH = num(info.height * scale)
            val contentsRef = 4 + 3 * index
            val imageRef = 5 + 3 * index

            beginObject() // page
            text(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageW $pageH] " +
                    "/Resources << /XObject << /Im0 $imageRef 0 R >> >> /Contents $contentsRef 0 R >>\nendobj\n"
            )

            val content = "q $pageW 0 0 $pageH 0 0 cm /Im0 Do Q\n".toByteArray(Charsets.ISO_8859_1)
            beginObject() // contents
            text("<< /Length ${content.size} >>\nstream\n")
            raw(content)
            text("endstream\nendobj\n")

            val colorSpace = if (info.components == 1) "/DeviceGray" else "/DeviceRGB"
            beginObject() // image
            text(
                "<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                    "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>\nstream\n"
            )
            raw(bytes)
            text("\nendstream\nendobj\n")
        }

        val xrefOffset = position
        text("xref\n0 ${offsets.size + 1}\n")
        text("0000000000 65535 f \n")
        for (offset in offsets) {
            text(String.format(Locale.US, "%010d 00000 n \n", offset))
        }
        text("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
    }
}
