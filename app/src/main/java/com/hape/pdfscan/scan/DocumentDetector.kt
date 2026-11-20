package com.hape.pdfscan.scan

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val TAG = "OpenCvInit"

/** Longest image side used for edge detection; larger inputs are downscaled first. */
private const val DETECTION_MAX_SIDE = 1000

/** Plausibility threshold for the robust stage: minimum quad area as fraction of the image. */
private const val ROBUST_MIN_AREA_FRACTION = 0.05f

/** Number of largest contours per mask that the robust stage turns into quad candidates. */
private const val ROBUST_TOP_CONTOURS = 16

/** Number of distinct best candidates reported for diagnostics. */
private const val TOP_CANDIDATES = 3

/** Candidates below this area fraction count as "page-sized" in the diagnostics. */
private const val SMALL_CANDIDATE_FRACTION = 0.5f

/**
 * Initialises the OpenCV native library (libopencv_java5.so).
 *
 * Returns true if the library loaded successfully, false otherwise.
 * [OpenCVLoader.initLocal] loads the lib bundled in the AAR; no OpenCV Manager app is needed.
 */
fun initOpenCv(): Boolean {
    return try {
        val ok = OpenCVLoader.initLocal()
        if (!ok) Log.e(TAG, "OpenCV initLocal() returned false")
        ok
    } catch (e: Throwable) {
        // UnsatisfiedLinkError, ExceptionInInitializerError, ... - never crash the camera screen
        Log.e(TAG, "OpenCV init failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}

/**
 * Result of one detection pass on a single frame or photo.
 *
 * [corners] are in the pixel coordinates of the analysed image, whose size is
 * [imageWidth] × [imageHeight]. [isFallback] is true when no document was found and the
 * corners are the full image boundary. [timestampMs] is [System.currentTimeMillis] at
 * detection time (used by Sub-Task 5 for stability measurement).
 * [stage] tells which detection stage found the page: 1 = plain edges, 2 = robust stage,
 * 0 = nothing found. [durationMs] is the time the detection took.
 *
 * Diagnostics (measurement only, no influence on detection): [winnerMask] is the index of the
 * mask the winning candidate came from (0 closed edges, 1 closed sensitive edges, 2 Otsu,
 * 3 inverse Otsu, 4 dilated edges; -1 if nothing found), [winnerScore] its quad score and
 * [candidateCount] the number of candidates evaluated.
 */
data class DetectionResult(
    val corners: DocumentCorners,
    val isFallback: Boolean,
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val stage: Int = 0,
    val durationMs: Long = 0,
    val winnerMask: Int = -1,
    val winnerScore: Double = 0.0,
    val candidateCount: Int = 0,
    val topCandidates: List<CandidateInfo> = emptyList(),
)

/**
 * Diagnostics only: one of the best distinct quad candidates of a frame, [corners] in the
 * coordinates of the analysed image. [areaFraction] is quad area / image area, [edgeSupport]
 * the fraction of the quad border covered by edges.
 */
data class CandidateInfo(
    val corners: DocumentCorners,
    val maskIndex: Int,
    val score: Double,
    val areaFraction: Float,
    val edgeSupport: Float,
)

/**
 * Detects a document quadrilateral in a [Bitmap] using OpenCV 5.
 *
 * Two stages: (1) Canny edges + external contours, fast and precise on clean backgrounds;
 * (2) only if stage 1 finds nothing: closed auto-Canny edges and Otsu brightness masks,
 * candidates ranked by size, rectangularity and edge support. Stage 2 also copes with
 * textured surfaces and pages that touch the image border.
 *
 * Stateless and callable from any thread, but OpenCV must have been initialised via
 * [initOpenCv] first.
 *
 * OpenCV 5 API differences from 4.x used here: `approxPolyDP`, `arcLength`, `contourArea`,
 * `isContourConvex`, `convexHull` and `getPerspectiveTransform` live in
 * `org.opencv.geometry.Geometry` instead of `Imgproc`. Everything else used (cvtColor,
 * GaussianBlur, Canny, dilate, threshold, morphologyEx, findContours, warpPerspective,
 * resize) is still in `Imgproc`.
 */
object DocumentDetector {

    private class Candidate(val corners: DocumentCorners, val contourArea: Double, val maskIndex: Int)

    /** Winner of the robust stage plus diagnostics. */
    private class RobustResult(
        val corners: DocumentCorners?,
        val maskIndex: Int,
        val score: Double,
        val candidateCount: Int,
        val top: List<CandidateInfo> = emptyList(),
    )

    /**
     * Analyses [bitmap] and returns a [DetectionResult] whose corners are in the
     * coordinates of [bitmap]. The caller must not pass a recycled bitmap.
     */
    fun detect(bitmap: Bitmap): DetectionResult {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        return try {
            detectOnMat(src)
        } finally {
            src.release()
        }
    }

    /**
     * Applies perspective correction to [bitmap] using [corners] (in bitmap coordinates).
     * The longer side of the result is at most [maxDimension] px; the aspect ratio is kept.
     * Returns a new [Bitmap] or null on failure. The caller must recycle it.
     */
    fun crop(bitmap: Bitmap, corners: DocumentCorners, maxDimension: Int = 2048): Bitmap? {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        return try {
            cropMat(src, corners, maxDimension)
        } finally {
            src.release()
        }
    }

    // -------------------------------------------------------------------------

    private fun detectOnMat(src: Mat): DetectionResult {
        val startNs = System.nanoTime()
        val imageWidth = src.cols()
        val imageHeight = src.rows()
        val scale = max(imageWidth, imageHeight).let { side ->
            if (side > DETECTION_MAX_SIDE) DETECTION_MAX_SIDE.toDouble() / side else 1.0
        }

        val small = Mat()
        val gray = Mat()
        try {
            if (scale < 1.0) {
                Imgproc.resize(src, small, Size(), scale, scale, Imgproc.INTER_AREA)
            } else {
                src.copyTo(small)
            }
            val smallW = small.cols()
            val smallH = small.rows()

            // Utils.bitmapToMat yields RGBA
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)

            val stage = 1
            val robust = detectRobust(gray, smallW, smallH)
            val best = robust.corners

            val ts = System.currentTimeMillis()
            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            val top = robust.top.map {
                it.copy(corners = DocumentGeometry.scaleCorners(it.corners, smallW, smallH, imageWidth, imageHeight))
            }
            return if (best != null) {
                val inFullRes = DocumentGeometry.scaleCorners(best, smallW, smallH, imageWidth, imageHeight)
                DetectionResult(
                    inFullRes, false, ts, imageWidth, imageHeight, stage, durationMs,
                    robust.maskIndex, robust.score, robust.candidateCount, top,
                )
            } else {
                DetectionResult(
                    fallbackCorners(imageWidth, imageHeight), true, ts, imageWidth, imageHeight, 0, durationMs,
                    -1, robust.score, robust.candidateCount, top,
                )
            }
        } finally {
            small.release()
            gray.release()
        }
    }

    /** Stage 1: Canny + contours; picks the highest scoring plausible quad backed by edges. */
    private fun detectByEdges(gray: Mat, w: Int, h: Int): DocumentCorners? {
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        try {
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 50.0, 150.0)
            Imgproc.dilate(edges, edges, kernel)
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val frameArea = w.toDouble() * h
            val support = ByteArray(w * h)
            edges.get(0, 0, support)

            var bestScore = 0.0
            var bestCorners: DocumentCorners? = null
            val sorted = contours
                .map { it to Geometry.contourArea(it) }
                .sortedByDescending { it.second }
                .take(ROBUST_TOP_CONTOURS)
            for ((contour, contourArea) in sorted) {
                val checkCorners: (DocumentCorners) -> Unit = { corners ->
                    val area = DocumentGeometry.area(corners)
                    if (area / frameArea <= DocumentGeometry.MAX_FRAME_FRACTION) {
                        val sup = DocumentGeometry.edgeSupport(corners) { x, y ->
                            x in 0 until w && y in 0 until h && support[y * w + x].toInt() != 0
                        }
                        val score = DocumentGeometry.quadScore(area, frameArea, contourArea, sup)
                        if (score > bestScore) {
                            bestScore = score
                            bestCorners = corners
                        }
                    }
                }
                approxQuad(contour, w, h, minAreaFraction = 0.02f)?.let(checkCorners)
                val hull = convexHullOf(contour)
                if (hull != null) {
                    try {
                        approxQuad(hull, w, h, minAreaFraction = 0.02f)?.let(checkCorners)
                    } finally {
                        hull.release()
                    }
                }
            }
            return if (bestScore >= DocumentGeometry.MIN_SCORE) bestCorners else null
        } finally {
            kernel.release()
            blurred.release()
            edges.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /**
     * Stage 2: several masks (closed auto-Canny edges, Otsu bright/dark split, dilated edges),
     * candidates from the largest blobs and their convex hulls, ranked by [DocumentGeometry.quadScore].
     */
    private fun detectRobust(gray: Mat, w: Int, h: Int): RobustResult {
        val blur = Mat()
        val cAuto = Mat()
        val cSens = Mat()
        val both = Mat()
        val supportMat = Mat()
        val closedBoth = Mat()
        val closedSens = Mat()
        val edgesS1 = Mat()
        val otsu = Mat()
        val otsuInv = Mat()
        val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val k7 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
        val k9 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
        val anchor = Point(-1.0, -1.0)
        val masks = mutableListOf<Mat>()
        try {
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
            val median = medianOf(blur)
            Imgproc.Canny(blur, cAuto, max(10.0, 0.5 * median), min(255.0, max(30.0, 1.2 * median)))
            Imgproc.Canny(blur, cSens, 15.0, 50.0)

            // Edge map used to verify candidates: sensitive edges count as support.
            Core.bitwise_or(cAuto, cSens, both)
            Imgproc.dilate(both, supportMat, k5)
            val support = ByteArray(w * h)
            supportMat.get(0, 0, support)

            // Closed edge masks (connects subtle document boundaries)
            Imgproc.morphologyEx(both, closedBoth, Imgproc.MORPH_CLOSE, k7, anchor, 2)
            masks += closedBoth

            Imgproc.morphologyEx(cSens, closedSens, Imgproc.MORPH_CLOSE, k5, anchor, 2)
            masks += closedSens

            // Bright page on darker surface, and the inverse; opening removes thin texture bridges.
            Imgproc.threshold(blur, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Core.bitwise_not(otsu, otsuInv)
            for (m in listOf(otsu, otsuInv)) {
                Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, k9)
                Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, k9, anchor, 2)
                masks += m
            }

            // Dilated Canny edges
            Imgproc.dilate(both, edgesS1, k3)
            masks += edgesS1

            val candidates = mutableListOf<Candidate>()
            masks.forEachIndexed { index, mask -> collectQuads(mask, index, w, h, candidates) }

            val frameArea = w.toDouble() * h
            var bestScore = 0.0
            var best: DocumentCorners? = null
            var bestMask = -1
            val scored = mutableListOf<CandidateInfo>()
            for (c in candidates) {
                val qa = DocumentGeometry.area(c.corners)
                if (qa / frameArea > DocumentGeometry.MAX_FRAME_FRACTION) continue
                val sup = DocumentGeometry.edgeSupport(c.corners) { x, y ->
                    x in 0 until w && y in 0 until h && support[y * w + x].toInt() != 0
                }
                val onBorder = DocumentGeometry.cornersOnBorder(c.corners, w, h)
                val score = DocumentGeometry.quadScore(qa, frameArea, c.contourArea, sup, onBorder)
                if (score > 0.0) {
                    scored += CandidateInfo(c.corners, c.maskIndex, score, (qa / frameArea).toFloat(), sup)
                }
                if (score > bestScore) {
                    bestScore = score
                    best = c.corners
                    bestMask = c.maskIndex
                }
            }
            val top = distinctTop(scored, w, h)
            return if (bestScore >= DocumentGeometry.MIN_SCORE) {
                RobustResult(best, bestMask, bestScore, candidates.size, top)
            } else {
                RobustResult(null, -1, bestScore, candidates.size, top)
            }
        } finally {
            listOf(blur, cAuto, cSens, both, supportMat, closedBoth, closedSens, edgesS1, otsu, otsuInv, k3, k5, k7, k9).forEach { it.release() }
        }
    }

    /**
     * Diagnostics: the [TOP_CANDIDATES] best-scoring candidates that differ from each other
     * (largest corner distance above 3 % of the image diagonal). The sort is stable, so the
     * first entry is the winner.
     */
    private fun distinctTop(scored: List<CandidateInfo>, w: Int, h: Int): List<CandidateInfo> {
        val minDistance = 0.03f * hypot(w.toFloat(), h.toFloat())
        val sorted = scored.sortedByDescending { it.score }
        val result = mutableListOf<CandidateInfo>()
        for (c in sorted) {
            if (result.none { AutoCaptureController.maxCornerDrift(it.corners, c.corners) < minDistance }) {
                result += c
                if (result.size == TOP_CANDIDATES) break
            }
        }
        // Diagnostics: always show the best page-sized quad (< 50 % of the image) as the last
        // entry, so the log tells whether the real page is generated as a candidate at all.
        if (result.none { it.areaFraction < SMALL_CANDIDATE_FRACTION }) {
            sorted.firstOrNull { it.areaFraction < SMALL_CANDIDATE_FRACTION }?.let { result += it }
        }
        return result
    }

    /** Adds quad candidates for the largest contours of [mask] and their convex hulls. */
    private fun collectQuads(mask: Mat, maskIndex: Int, w: Int, h: Int, out: MutableList<Candidate>) {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val top = contours
                .map { it to Geometry.contourArea(it) }
                .sortedByDescending { it.second }
                .take(ROBUST_TOP_CONTOURS)
            for ((contour, area) in top) {
                approxQuad(contour, w, h, ROBUST_MIN_AREA_FRACTION)?.let { out += Candidate(it, area, maskIndex) }
                val hull = convexHullOf(contour)
                if (hull != null) {
                    try {
                        approxQuad(hull, w, h, ROBUST_MIN_AREA_FRACTION)?.let { out += Candidate(it, area, maskIndex) }
                    } finally {
                        hull.release()
                    }
                }
            }
        } finally {
            contours.forEach { it.release() }
            hierarchy.release()
        }
    }

    /** Approximates [contour] by a convex, plausible quadrilateral, or returns null. */
    private fun approxQuad(
        contour: MatOfPoint,
        w: Int,
        h: Int,
        minAreaFraction: Float,
        epsilons: FloatArray = floatArrayOf(0.02f, 0.03f, 0.04f, 0.05f, 0.015f, 0.06f, 0.08f),
    ): DocumentCorners? {
        val contour2f = MatOfPoint2f()
        val approx = MatOfPoint2f()
        try {
            contour.convertTo(contour2f, CvType.CV_32F)
            val arcLen = Geometry.arcLength(contour2f, true)
            if (arcLen <= 0.0) return null

            for (eps in epsilons) {
                Geometry.approxPolyDP(contour2f, approx, eps.toDouble() * arcLen, true)
                val total = approx.total()
                if (total in 4L..12L) {
                    val corners = DocumentGeometry.cornersFromPoints(
                        approx.toArray().map { FloatPoint(it.x.toFloat(), it.y.toFloat()) },
                    )
                    if (DocumentGeometry.isPlausibleQuad(corners, w, h, minAreaFraction)) {
                        return corners
                    }
                }
            }
            return null
        } finally {
            contour2f.release()
            approx.release()
        }
    }

    private fun convexHullOf(contour: MatOfPoint): MatOfPoint? {
        val hullIdx = MatOfInt()
        try {
            Geometry.convexHull(contour, hullIdx)
            val idxArray = hullIdx.toArray()
            if (idxArray.size < 3) return null
            val pts = contour.toArray()
            val hull = MatOfPoint()
            hull.fromList(idxArray.map { pts[it] })
            return hull
        } catch (e: Exception) {
            return null
        } finally {
            hullIdx.release()
        }
    }

    /** Median grey value (0..255) of an 8-bit single-channel image. */
    private fun medianOf(gray: Mat): Double {
        val data = ByteArray((gray.total() * gray.channels()).toInt())
        gray.get(0, 0, data)
        val hist = IntArray(256)
        for (b in data) hist[b.toInt() and 0xFF]++
        val half = data.size / 2
        var acc = 0
        for (i in 0..255) {
            acc += hist[i]
            if (acc > half) return i.toDouble()
        }
        return 128.0
    }

    private fun cropMat(src: Mat, corners: DocumentCorners, maxDimension: Int): Bitmap? {
        val (outW, outH) = DocumentGeometry.targetSize(corners, maxDimension)
        val srcPts = MatOfPoint2f(
            Point(corners.topLeft.x.toDouble(), corners.topLeft.y.toDouble()),
            Point(corners.topRight.x.toDouble(), corners.topRight.y.toDouble()),
            Point(corners.bottomRight.x.toDouble(), corners.bottomRight.y.toDouble()),
            Point(corners.bottomLeft.x.toDouble(), corners.bottomLeft.y.toDouble()),
        )
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW.toDouble() - 1, 0.0),
            Point(outW.toDouble() - 1, outH.toDouble() - 1),
            Point(0.0, outH.toDouble() - 1),
        )
        var transform: Mat? = null
        val warped = Mat()
        return try {
            transform = Geometry.getPerspectiveTransform(srcPts, dstPts)
            Imgproc.warpPerspective(src, warped, transform, Size(outW.toDouble(), outH.toDouble()))
            val result = createBitmap(outW, outH)
            Utils.matToBitmap(warped, result)
            result
        } catch (e: Exception) {
            Log.e(TAG, "warpPerspective failed: ${e.javaClass.simpleName}")
            null
        } finally {
            srcPts.release()
            dstPts.release()
            transform?.release()
            warped.release()
        }
    }

    private fun fallbackCorners(w: Int, h: Int): DocumentCorners = DocumentCorners(
        topLeft = FloatPoint(0f, 0f),
        topRight = FloatPoint(w.toFloat(), 0f),
        bottomRight = FloatPoint(w.toFloat(), h.toFloat()),
        bottomLeft = FloatPoint(0f, h.toFloat()),
    )
}
