package com.hape.pdfscan.pdf

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Log
import java.io.File
import java.io.FileOutputStream

private const val TAG = "PdfWriter"

/**
 * Writes a list of [Bitmap] pages into a PDF file.
 *
 * Each page is sized to the bitmap's pixel dimensions (1 pt = 1 px, so DPI is implicit).
 * The bitmap is drawn with a [Paint] instance that enables JPEG compression (via
 * [Paint.isFilterBitmap]) – the actual bytes in a [PdfDocument] are uncompressed per the
 * Android API, so size is dominated by the bitmap resolution. Use reasonable source resolutions
 * (the crop bitmaps from [DocumentDetector] are bounded by the photo resolution).
 *
 * Writing is atomic: the PDF is first written to [outputFile].tmp, then renamed to [outputFile].
 * If the temp file exists already it is deleted before writing begins.
 */
object PdfWriter {

    /**
     * Writes [pages] into [outputFile] atomically.
     *
     * @param pages Non-empty list of bitmaps to embed, one per page.
     * @param outputFile Target .pdf file (need not exist yet; its parent must exist).
     * @return `true` on success, `false` if any I/O error occurred (the target file is not
     *         created or left in a partial state).
     */
    fun write(pages: List<Bitmap>, outputFile: File): Boolean {
        require(pages.isNotEmpty()) { "pages must not be empty" }

        val tmpFile = File(outputFile.parent, outputFile.name + ".tmp")
        tmpFile.delete()

        val document = PdfDocument()
        return try {
            for ((index, bitmap) in pages.withIndex()) {
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                val page = document.startPage(pageInfo)
                val paint = Paint().apply { isFilterBitmap = true }
                page.canvas.drawBitmap(bitmap, 0f, 0f, paint)
                document.finishPage(page)
            }

            FileOutputStream(tmpFile).use { out ->
                document.writeTo(out)
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
        } finally {
            document.close()
        }
    }
}
