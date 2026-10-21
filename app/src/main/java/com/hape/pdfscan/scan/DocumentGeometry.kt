package com.hape.pdfscan.scan

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure geometry helpers: no OpenCV, no Android dependencies.
 * All functions are testable on the JVM.
 */
object DocumentGeometry {

    /**
     * Orders four unordered points as top-left, top-right, bottom-right, bottom-left.
     *
     * Algorithm:
     * 1. Sort by y (ascending). The two with the smallest y are the "top" pair;
     *    the two with the largest y are the "bottom" pair.
     * 2. Within each pair the one with the smaller x is left.
     *
     * This works for any convex quadrilateral that has a clear top/bottom
     * separation.  For perfectly horizontal edges (same y) the x-sort resolves ties.
     */
    fun orderCorners(points: List<FloatPoint>): DocumentCorners {
        require(points.size == 4) { "Exactly 4 points required, got ${points.size}" }
        val sorted = points.sortedWith(compareBy({ it.y }, { it.x }))
        // Top two: sorted[0], sorted[1]  — order left/right by x
        val (topLeft, topRight) = if (sorted[0].x <= sorted[1].x) {
            sorted[0] to sorted[1]
        } else {
            sorted[1] to sorted[0]
        }
        // Bottom two: sorted[2], sorted[3] — order left/right by x
        val (bottomLeft, bottomRight) = if (sorted[2].x <= sorted[3].x) {
            sorted[2] to sorted[3]
        } else {
            sorted[3] to sorted[2]
        }
        return DocumentCorners(topLeft, topRight, bottomRight, bottomLeft)
    }

    /**
     * Estimates the physical aspect ratio (width / height) of a planar rectangular
     * document observed under perspective projection as [corners].
     *
     * Uses vanishing point homography analysis to account for foreshortening and perspective distortion.
     * Falls back to opposite edge averages when perspective cues are weak or near-frontal.
     */
    fun estimateAspectRatio(corners: DocumentCorners): Float {
        val p1 = corners.topLeft
        val p2 = corners.topRight
        val p3 = corners.bottomRight
        val p4 = corners.bottomLeft

        // 2D fallback: average edge lengths
        val topWidth = dist(p1, p2).toDouble()
        val botWidth = dist(p4, p3).toDouble()
        val leftHeight = dist(p1, p4).toDouble()
        val rightHeight = dist(p2, p3).toDouble()
        val avgWidth = (topWidth + botWidth) / 2.0
        val avgHeight = (leftHeight + rightHeight) / 2.0
        val fallbackRatio = if (avgHeight > 0.0) (avgWidth / avgHeight).toFloat() else 1f

        // Center coordinates on the quad centroid
        val cx = (p1.x + p2.x + p3.x + p4.x) / 4.0
        val cy = (p1.y + p2.y + p3.y + p4.y) / 4.0

        val x1 = p1.x.toDouble() - cx; val y1 = p1.y.toDouble() - cy
        val x2 = p2.x.toDouble() - cx; val y2 = p2.y.toDouble() - cy
        val x3 = p3.x.toDouble() - cx; val y3 = p3.y.toDouble() - cy
        val x4 = p4.x.toDouble() - cx; val y4 = p4.y.toDouble() - cy

        // Cross product scalar for 2D points: (a x b)_z = a.x * b.y - a.y * b.x
        fun cross2d(ax: Double, ay: Double, bx: Double, by: Double): Double = ax * by - ay * bx

        // Determinant for 3 homogeneous points [a, b, c]
        fun det3(
            ax: Double, ay: Double,
            bx: Double, by: Double,
            cx: Double, cy: Double,
        ): Double = ax * (by - cy) - ay * (bx - cx) + cross2d(bx, by, cx, cy)

        val d143 = det3(x1, y1, x4, y4, x3, y3)
        val d243 = det3(x2, y2, x4, y4, x3, y3)
        val d124 = det3(x1, y1, x2, y2, x4, y4)
        val d324 = det3(x3, y3, x2, y2, x4, y4)

        if (abs(d243) < 1e-7 || abs(d324) < 1e-7) {
            return fallbackRatio
        }

        val k2 = abs(d143 / d243)
        val k3 = abs(d124 / d324)

        val d123 = det3(x1, y1, x2, y2, x3, y3)
        val d423 = det3(x4, y4, x2, y2, x3, y3)
        val k4 = if (abs(d423) > 1e-7) abs(d123 / d423) else (k2 + k3 - 1.0).coerceAtLeast(0.1)

        val quadDiag = hypot(x3 - x1, y3 - y1)
        val defaultF = max(quadDiag * 1.2, 500.0)

        // Numerator and denominator for focal length from orthogonality condition
        val num = (k2 * x2 - x1) * (k4 * x4 - x1) + (k2 * y2 - y1) * (k4 * y4 - y1)
        val den = (k2 - 1.0) * (k4 - 1.0)

        val f2 = if (abs(den) > 1e-7) -num / den else -1.0
        val minF = 0.5 * quadDiag
        val maxF = 4.0 * quadDiag

        val f = if (f2 > minF * minF && f2 < maxF * maxF) {
            kotlin.math.sqrt(f2)
        } else {
            defaultF
        }

        val w3d = kotlin.math.sqrt(
            ((k2 * x2 - x1) * (k2 * x2 - x1) + (k2 * y2 - y1) * (k2 * y2 - y1)) / (f * f) +
                (k2 - 1.0) * (k2 - 1.0)
        )
        val h3d = kotlin.math.sqrt(
            ((k4 * x4 - x1) * (k4 * x4 - x1) + (k4 * y4 - y1) * (k4 * y4 - y1)) / (f * f) +
                (k4 - 1.0) * (k4 - 1.0)
        )

        if (h3d <= 1e-7) return fallbackRatio
        val ratio = (w3d / h3d).toFloat()
        return if (ratio.isFinite() && ratio in 0.1f..10.0f) ratio else fallbackRatio
    }

    /**
     * Derives an output size (width × height) from the corners of the detected
     * document, correcting for perspective distortion so the true aspect ratio is preserved.
     * The longer side is at most [maxDimension].
     */
    fun targetSize(corners: DocumentCorners, maxDimension: Int = 2048): Pair<Int, Int> {
        val topWidth = dist(corners.topLeft, corners.topRight)
        val bottomWidth = dist(corners.bottomLeft, corners.bottomRight)
        val leftHeight = dist(corners.topLeft, corners.bottomLeft)
        val rightHeight = dist(corners.topRight, corners.bottomRight)
        val maxObserved = max(max(topWidth, bottomWidth), max(leftHeight, rightHeight))

        val aspectRatio = estimateAspectRatio(corners)

        val rawWidth: Float
        val rawHeight: Float
        if (aspectRatio >= 1f) {
            rawWidth = maxObserved
            rawHeight = maxObserved / aspectRatio
        } else {
            rawHeight = maxObserved
            rawWidth = maxObserved * aspectRatio
        }

        val longest = max(rawWidth, rawHeight)
        val scale = if (longest > maxDimension) maxDimension / longest else 1f
        val width = (rawWidth * scale).roundToInt().coerceIn(1, maxDimension)
        val height = (rawHeight * scale).roundToInt().coerceIn(1, maxDimension)
        return width to height
    }

    /**
     * Checks if a four-corner polygon is strictly convex by verifying that the cross
     * products of all consecutive edges have the same non-zero sign.
     */
    fun isConvex(corners: DocumentCorners): Boolean {
        val pts = corners.toList()
        var pos = false
        var neg = false
        for (i in 0 until 4) {
            val p0 = pts[i]
            val p1 = pts[(i + 1) % 4]
            val p2 = pts[(i + 2) % 4]
            val cross = (p1.x - p0.x) * (p2.y - p1.y) - (p1.y - p0.y) * (p2.x - p1.x)
            if (cross > 0f) pos = true
            if (cross < 0f) neg = true
        }
        return pos xor neg
    }

    /**
     * Checks whether a quadrilateral defined by four ordered corners is plausible
     * as a document in an image of size [imageWidth] × [imageHeight].
     *
     * Criteria:
     * - Strictly convex quadrilateral
     * - Minimum area: [minAreaFraction] × image area (default 2 %)
     * - Aspect ratio between [minAspectRatio] and [maxAspectRatio] (shorter/longer side)
     * - All four points lie within the image bounds
     */
    fun isPlausibleQuad(
        corners: DocumentCorners,
        imageWidth: Int,
        imageHeight: Int,
        minAreaFraction: Float = 0.02f,
        minAspectRatio: Float = 0.1f,
        maxAspectRatio: Float = 1.0f,
    ): Boolean {
        if (!isConvex(corners)) return false
        val imageArea = imageWidth.toLong() * imageHeight.toLong()
        val area = quadArea(corners)
        if (area < imageArea * minAreaFraction) return false

        val (w, h) = targetSize(corners)
        if (w <= 0 || h <= 0) return false
        val shorter = min(w, h).toFloat()
        val longer = max(w, h).toFloat()
        val aspectRatio = shorter / longer
        if (aspectRatio < minAspectRatio || aspectRatio > maxAspectRatio) return false

        // All corners must be within the image (with a tiny subpixel tolerance)
        for (p in corners.toList()) {
            if (p.x < -1f || p.x > imageWidth + 1f || p.y < -1f || p.y > imageHeight + 1f) return false
        }
        return true
    }

    /**
     * Scales corners from analysis-frame coordinates to photo coordinates.
     *
     * [analysisWidth] × [analysisHeight]: size of the analysis frame.
     * [photoWidth] × [photoHeight]: size of the captured photo (after rotation).
     */
    fun scaleCorners(
        corners: DocumentCorners,
        analysisWidth: Int,
        analysisHeight: Int,
        photoWidth: Int,
        photoHeight: Int,
    ): DocumentCorners {
        val sx = photoWidth.toFloat() / analysisWidth.toFloat()
        val sy = photoHeight.toFloat() / analysisHeight.toFloat()
        return DocumentCorners(
            topLeft = FloatPoint(corners.topLeft.x * sx, corners.topLeft.y * sy),
            topRight = FloatPoint(corners.topRight.x * sx, corners.topRight.y * sy),
            bottomRight = FloatPoint(corners.bottomRight.x * sx, corners.bottomRight.y * sy),
            bottomLeft = FloatPoint(corners.bottomLeft.x * sx, corners.bottomLeft.y * sy),
        )
    }

    /** Quadrilateral frames above this fraction of the image are the image border, not a page. */
    const val MAX_FRAME_FRACTION = 0.90

    /** Minimum edge support for a quad candidate to be considered a document. */
    const val MIN_EDGE_SUPPORT = 0.20f

    /** Minimum [quadScore] for detection to report a page. */
    const val MIN_SCORE = 0.10

    /**
     * Fraction of points sampled along the four sides of [corners] that lie on an edge pixel
     * according to [isEdge]. A real page border is covered by edges, a random quad is not.
     */
    fun edgeSupport(
        corners: DocumentCorners,
        samplesPerSide: Int = 24,
        isEdge: (x: Int, y: Int) -> Boolean,
    ): Float {
        val pts = corners.toList()
        var hit = 0
        var total = 0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            for (k in 0 until samplesPerSide) {
                val t = 0.05f + 0.9f * k / (samplesPerSide - 1)
                val x = (a.x + (b.x - a.x) * t).roundToInt()
                val y = (a.y + (b.y - a.y) * t).roundToInt()
                total++
                if (isEdge(x, y)) hit++
            }
        }
        return hit.toFloat() / total
    }

    /**
     * Ranks a quadrilateral candidate: prefers clear edge support and plausible document size,
     * with a bonus for filled contour blobs when available.
     */
    fun quadScore(quadArea: Double, frameArea: Double, contourArea: Double = 0.0, edgeSupport: Float): Double {
        if (quadArea <= 0.0 || frameArea <= 0.0 || edgeSupport < MIN_EDGE_SUPPORT) return 0.0
        val sizeFactor = kotlin.math.sqrt(quadArea / frameArea).coerceIn(0.1, 1.0)
        val fillFactor = if (contourArea > 0.0) {
            (0.6 + 0.4 * min(1.0, contourArea / quadArea)).toFloat()
        } else {
            0.8f
        }
        return sizeFactor * fillFactor * edgeSupport
    }

    /** Area of the quadrilateral (shoelace formula). */
    fun area(corners: DocumentCorners): Double = quadArea(corners)

    /**
     * Maps a point from image coordinates to view coordinates for a view that
     * shows the image with "fill and centre" scaling (CameraX `FILL_CENTER`):
     * uniform scale so that the image covers the view, centred, overflow cropped.
     */
    fun mapFillCenter(
        point: FloatPoint,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
    ): FloatPoint {
        val scale = max(viewWidth / imageWidth, viewHeight / imageHeight)
        val dx = (viewWidth - imageWidth * scale) / 2f
        val dy = (viewHeight - imageHeight * scale) / 2f
        return FloatPoint(point.x * scale + dx, point.y * scale + dy)
    }

    /**
     * Rotates a single point in an image of size [srcWidth] × [srcHeight] by
     * [rotationDegrees] (0, 90, 180, or 270, clockwise).  Returns the point in the
     * rotated image's coordinate system.
     *
     * Used to convert analysis-frame coordinates (which may be in a rotated sensor
     * space) to display/photo coordinates.
     */
    fun rotatePoint(
        point: FloatPoint,
        srcWidth: Int,
        srcHeight: Int,
        rotationDegrees: Int,
    ): FloatPoint {
        return when (((rotationDegrees % 360) + 360) % 360) {
            0 -> point
            90 -> FloatPoint(srcHeight - point.y, point.x)
            180 -> FloatPoint(srcWidth - point.x, srcHeight - point.y)
            270 -> FloatPoint(point.y, srcWidth - point.x)
            else -> point
        }
    }

    /**
     * Applies [rotatePoint] to all four corners and then re-orders them, because
     * rotation can change which corner is top-left.
     */
    fun rotateCornersAndReorder(
        corners: DocumentCorners,
        srcWidth: Int,
        srcHeight: Int,
        rotationDegrees: Int,
    ): DocumentCorners {
        val rotated = corners.toList().map { rotatePoint(it, srcWidth, srcHeight, rotationDegrees) }
        return orderCorners(rotated)
    }

    // --- private helpers ---

    private fun dist(a: FloatPoint, b: FloatPoint): Float =
        hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()

    /** Shoelace formula for a convex quadrilateral (corners in order). */
    private fun quadArea(c: DocumentCorners): Double {
        val pts = c.toList()
        var area = 0.0
        for (i in pts.indices) {
            val j = (i + 1) % pts.size
            area += pts[i].x.toDouble() * pts[j].y.toDouble()
            area -= pts[j].x.toDouble() * pts[i].y.toDouble()
        }
        return abs(area) / 2.0
    }
}
