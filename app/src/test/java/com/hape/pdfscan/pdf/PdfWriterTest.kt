package com.hape.pdfscan.pdf

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun jpegBytes(width: Int, height: Int, quality: Float = 0.8f, gray: Boolean = false): ByteArray {
        val image = BufferedImage(
            width, height,
            if (gray) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB,
        )
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality
        }
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    private fun jpegFile(width: Int, height: Int, gray: Boolean = false): File =
        tmp.newFile().also { it.writeBytes(jpegBytes(width, height, gray = gray)) }

    // --- readJpegInfo -------------------------------------------------------

    @Test
    fun readJpegInfo_rgb() {
        assertEquals(JpegInfo(120, 80, 3), readJpegInfo(jpegBytes(120, 80)))
    }

    @Test
    fun readJpegInfo_gray() {
        assertEquals(JpegInfo(64, 96, 1), readJpegInfo(jpegBytes(64, 96, gray = true)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun readJpegInfo_notAJpeg_throws() {
        readJpegInfo("not a jpeg at all".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun readJpegInfo_truncatedBeforeSof_throws() {
        readJpegInfo(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte(), 0, 0))
    }

    // --- write --------------------------------------------------------------

    @Test
    fun write_producesValidStructure() {
        val files = listOf(jpegFile(300, 400), jpegFile(500, 200, gray = true))
        val target = File(tmp.root, "out.pdf")

        assertTrue(PdfWriter.write(files, target))

        val bytes = target.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Count 2"))
        assertEquals(2, Regex("/Type /Page ").findAll(text).count())
        assertEquals(2, Regex("/Filter /DCTDecode").findAll(text).count())
        assertTrue(text.contains("/DeviceRGB"))
        assertTrue(text.contains("/DeviceGray"))
        assertFalse(File(tmp.root, "out.pdf.tmp").exists())
    }

    @Test
    fun write_xrefOffsetsPointToObjects() {
        val target = File(tmp.root, "out.pdf")
        assertTrue(PdfWriter.write(listOf(jpegFile(300, 400), jpegFile(200, 200)), target))

        val text = String(target.readBytes(), Charsets.ISO_8859_1)
        val startxref = Regex("startxref\\n(\\d+)\\n").find(text)!!.groupValues[1].toInt()
        assertTrue(text.substring(startxref).startsWith("xref\n"))

        val entries = Regex("(\\d{10}) 00000 n \\n").findAll(text.substring(startxref)).toList()
        assertEquals(2 + 3 * 2, entries.size)
        entries.forEachIndexed { index, match ->
            val offset = match.groupValues[1].toInt()
            assertTrue(
                "object ${index + 1} at $offset",
                text.substring(offset).startsWith("${index + 1} 0 obj\n"),
            )
        }
        assertTrue(text.contains("/Size ${entries.size + 1}"))
    }

    @Test
    fun write_embedsJpegBytesUnchanged() {
        val source = jpegBytes(250, 350)
        val file = tmp.newFile().also { it.writeBytes(source) }
        val target = File(tmp.root, "out.pdf")
        assertTrue(PdfWriter.write(listOf(file), target))

        val pdf = target.readBytes()
        val start = indexOf(pdf, source)
        assertTrue("JPEG bytes not found in PDF", start >= 0)
        assertArrayEquals(source, pdf.copyOfRange(start, start + source.size))
    }

    @Test
    fun write_pageLongSideIs842pt_keepingAspectRatio() {
        val target = File(tmp.root, "out.pdf")
        assertTrue(PdfWriter.write(listOf(jpegFile(1000, 2000)), target))

        val text = String(target.readBytes(), Charsets.ISO_8859_1)
        assertTrue(text.contains("/MediaBox [0 0 421.00 842.00]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun write_emptyList_throws() {
        PdfWriter.write(emptyList(), File(tmp.root, "out.pdf"))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
