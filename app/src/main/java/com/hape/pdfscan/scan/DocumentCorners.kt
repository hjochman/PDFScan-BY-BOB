package com.hape.pdfscan.scan

/**
 * Four corners of a detected document in image coordinates.
 * Order: top-left, top-right, bottom-right, bottom-left.
 */
data class DocumentCorners(
    val topLeft: FloatPoint,
    val topRight: FloatPoint,
    val bottomRight: FloatPoint,
    val bottomLeft: FloatPoint,
) {
    fun toList(): List<FloatPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

/** A 2-D point with float coordinates. */
data class FloatPoint(val x: Float, val y: Float)
