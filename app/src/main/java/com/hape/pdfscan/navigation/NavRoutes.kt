package com.hape.pdfscan.navigation

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Route definitions for the app navigation.
 */
object NavRoutes {
    const val MAIN = "main"
    const val SCAN = "scan?targetDirectory={targetDirectory}"
    const val ABOUT = "about"
    const val SETTINGS = "settings"
    const val PDF_VIEWER = "pdfviewer?path={path}"

    /** Builds the route to the PDF viewer for [pdfFile]. */
    fun pdfViewerRoute(pdfFile: File): String {
        val encoded = java.net.URLEncoder.encode(pdfFile.absolutePath, java.nio.charset.StandardCharsets.UTF_8.toString())
        return "pdfviewer?path=$encoded"
    }

    /** Decodes the PDF file path from the viewer route argument. */
    fun parsePdfFile(encodedPath: String?): File? {
        if (encodedPath.isNullOrBlank()) return null
        return try {
            File(java.net.URLDecoder.decode(encodedPath, java.nio.charset.StandardCharsets.UTF_8.toString()))
        } catch (_: Exception) { null }
    }

    /** Builds the route to the scan screen with the optional target directory path. */
    fun scanRoute(targetDirectory: File? = null): String {
        return if (targetDirectory != null) {
            val encoded = URLEncoder.encode(targetDirectory.absolutePath, StandardCharsets.UTF_8.toString())
            "scan?targetDirectory=$encoded"
        } else {
            "scan"
        }
    }

    /** Decodes the target directory from the scan route argument, or `null` if not provided. */
    fun parseTargetDirectory(encodedPath: String?): File? {
        if (encodedPath.isNullOrBlank()) return null
        return try {
            val decoded = URLDecoder.decode(encodedPath, StandardCharsets.UTF_8.toString())
            File(decoded)
        } catch (_: Exception) {
            null
        }
    }
}
