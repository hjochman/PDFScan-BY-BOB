package com.hape.pdfscan.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentGeometryTest {

    private val tl = FloatPoint(10f, 10f)
    private val tr = FloatPoint(110f, 20f)
    private val br = FloatPoint(100f, 160f)
    private val bl = FloatPoint(0f, 150f)
    private val expected = DocumentCorners(tl, tr, br, bl)

    // --- orderCorners -------------------------------------------------------

    @Test
    fun orderCorners_allPermutations_giveSameResult() {
        val pts = listOf(tl, tr, br, bl)
        permutations(pts).forEach { perm ->
            assertEquals("permutation $perm", expected, DocumentGeometry.orderCorners(perm))
        }
    }

    @Test
    fun orderCorners_axisAlignedRectangle() {
        val c = DocumentGeometry.orderCorners(
            listOf(FloatPoint(100f, 200f), FloatPoint(0f, 0f), FloatPoint(100f, 0f), FloatPoint(0f, 200f))
        )
        assertEquals(FloatPoint(0f, 0f), c.topLeft)
        assertEquals(FloatPoint(100f, 0f), c.topRight)
        assertEquals(FloatPoint(100f, 200f), c.bottomRight)
        assertEquals(FloatPoint(0f, 200f), c.bottomLeft)
    }

    @Test(expected = IllegalArgumentException::class)
    fun orderCorners_wrongPointCount_throws() {
        DocumentGeometry.orderCorners(listOf(tl, tr, br))
    }

    // --- targetSize ---------------------------------------------------------

    @Test
    fun targetSize_rectangle() {
        val c = rect(300f, 400f)
        assertEquals(300 to 400, DocumentGeometry.targetSize(c))
    }

    @Test
    fun estimateAspectRatio_axisAlignedRectangle() {
        assertEquals(0.75f, DocumentGeometry.estimateAspectRatio(rect(300f, 400f)), 0.01f)
        assertEquals(1.5f, DocumentGeometry.estimateAspectRatio(rect(600f, 400f)), 0.01f)
    }

    @Test
    fun estimateAspectRatio_perspectiveTrapezoid_recoversTrueRatio() {
        // Perspective view of a square (1:1) tilted backwards: top edge is shorter than bottom edge
        val trapezoid = DocumentCorners(
            topLeft = FloatPoint(200f, 100f),
            topRight = FloatPoint(600f, 100f),    // top width = 400
            bottomRight = FloatPoint(700f, 500f),
            bottomLeft = FloatPoint(100f, 500f),   // bottom width = 600, height = 400
        )
        val ratio = DocumentGeometry.estimateAspectRatio(trapezoid)
        // Perspective unprojection recovers close to 1.0 (square), whereas 2D average would be 500/400 = 1.25
        assertTrue("Estimated ratio was $ratio, expected near 1.0", ratio in 0.85f..1.15f)
    }

    @Test
    fun targetSize_isCappedAtMaxDimension_keepingAspectRatio() {
        val (w, h) = DocumentGeometry.targetSize(rect(5000f, 3000f), maxDimension = 2048)
        assertEquals(2048, w)
        assertEquals(1229, h) // 3000 * 2048 / 5000 = 1228.8
    }

    @Test
    fun targetSize_onlyOneSideAboveMax_keepsAspectRatio() {
        val (w, h) = DocumentGeometry.targetSize(rect(1500f, 4096f), maxDimension = 2048)
        assertEquals(750, w)
        assertEquals(2048, h)
    }

    @Test
    fun targetSize_belowMax_isUnchanged() {
        assertEquals(1000 to 2000, DocumentGeometry.targetSize(rect(1000f, 2000f), maxDimension = 2048))
    }

    // --- isConvex / isPlausibleQuad -----------------------------------------

    @Test
    fun isConvex_regularRectangle_isTrue() {
        assertTrue(DocumentGeometry.isConvex(rect(600f, 800f)))
    }

    @Test
    fun isConvex_trapezoid_isTrue() {
        val c = DocumentCorners(
            FloatPoint(100f, 100f),
            FloatPoint(500f, 100f),
            FloatPoint(450f, 700f),
            FloatPoint(150f, 700f),
        )
        assertTrue(DocumentGeometry.isConvex(c))
    }

    @Test
    fun isConvex_concaveChevron_isFalse() {
        // Concave quad (arrowhead/chevron): one corner pushed inwards past diagonal
        val c = DocumentCorners(
            FloatPoint(0f, 0f),
            FloatPoint(200f, 0f),
            FloatPoint(200f, 200f),
            FloatPoint(150f, 50f), // pushed inside
        )
        assertFalse(DocumentGeometry.isConvex(c))
    }

    @Test
    fun plausible_normalDocument() {
        assertTrue(DocumentGeometry.isPlausibleQuad(rect(600f, 800f), 1000, 1000))
    }

    @Test
    fun plausible_tooSmall_isRejected() {
        assertFalse(DocumentGeometry.isPlausibleQuad(rect(50f, 50f), 1000, 1000))
    }

    @Test
    fun plausible_extremeAspectRatio_isRejected() {
        assertFalse(DocumentGeometry.isPlausibleQuad(rect(900f, 40f), 1000, 1000))
    }

    @Test
    fun plausible_outsideImage_isRejected() {
        val c = DocumentCorners(
            FloatPoint(-10f, 0f), FloatPoint(600f, 0f), FloatPoint(600f, 800f), FloatPoint(-10f, 800f)
        )
        assertFalse(DocumentGeometry.isPlausibleQuad(c, 1000, 1000))
    }

    @Test
    fun plausible_wholeImage_isAccepted() {
        assertTrue(DocumentGeometry.isPlausibleQuad(rect(1000f, 1000f), 1000, 1000))
    }

    @Test
    fun plausible_nonConvexQuad_isRejected() {
        val c = DocumentCorners(
            FloatPoint(100f, 100f),
            FloatPoint(500f, 100f),
            FloatPoint(500f, 500f),
            FloatPoint(400f, 200f),
        )
        assertFalse(DocumentGeometry.isPlausibleQuad(c, 1000, 1000))
    }

    // --- scaleCorners -------------------------------------------------------

    @Test
    fun scaleCorners_scalesEachAxisIndependently() {
        val scaled = DocumentGeometry.scaleCorners(rect(100f, 50f), 200, 100, 400, 400)
        assertEquals(FloatPoint(200f, 200f), scaled.bottomRight)
        assertEquals(FloatPoint(0f, 0f), scaled.topLeft)
    }

    // --- rotatePoint --------------------------------------------------------

    @Test
    fun rotatePoint_zero_isIdentity() {
        assertEquals(FloatPoint(3f, 4f), DocumentGeometry.rotatePoint(FloatPoint(3f, 4f), 10, 20, 0))
    }

    @Test
    fun rotatePoint_90_movesTopLeftToTopRight() {
        // 10 wide x 20 high image rotated 90 deg clockwise becomes 20 wide x 10 high.
        assertEquals(FloatPoint(20f, 0f), DocumentGeometry.rotatePoint(FloatPoint(0f, 0f), 10, 20, 90))
        assertEquals(FloatPoint(20f, 10f), DocumentGeometry.rotatePoint(FloatPoint(10f, 0f), 10, 20, 90))
    }

    @Test
    fun rotatePoint_180_mirrorsThroughCentre() {
        assertEquals(FloatPoint(7f, 16f), DocumentGeometry.rotatePoint(FloatPoint(3f, 4f), 10, 20, 180))
    }

    @Test
    fun rotatePoint_270_movesTopLeftToBottomLeft() {
        assertEquals(FloatPoint(0f, 10f), DocumentGeometry.rotatePoint(FloatPoint(0f, 0f), 10, 20, 270))
    }

    @Test
    fun rotatePoint_normalisesNegativeAndLargeAngles() {
        val p = FloatPoint(3f, 4f)
        assertEquals(DocumentGeometry.rotatePoint(p, 10, 20, 270), DocumentGeometry.rotatePoint(p, 10, 20, -90))
        assertEquals(DocumentGeometry.rotatePoint(p, 10, 20, 90), DocumentGeometry.rotatePoint(p, 10, 20, 450))
    }

    @Test
    fun rotatePoint_fourQuarterTurnsReturnToStart() {
        var p = FloatPoint(3f, 4f)
        var w = 10
        var h = 20
        repeat(4) {
            p = DocumentGeometry.rotatePoint(p, w, h, 90)
            val t = w; w = h; h = t
        }
        assertEquals(FloatPoint(3f, 4f), p)
    }

    @Test
    fun rotateCornersAndReorder_90_keepsOrderedCornersConsistent() {
        val rotated = DocumentGeometry.rotateCornersAndReorder(rect(10f, 20f), 10, 20, 90)
        // Result is a 20 x 10 rectangle with corners in top-left, top-right, bottom-right, bottom-left order
        assertEquals(FloatPoint(0f, 0f), rotated.topLeft)
        assertEquals(FloatPoint(20f, 0f), rotated.topRight)
        assertEquals(FloatPoint(20f, 10f), rotated.bottomRight)
        assertEquals(FloatPoint(0f, 10f), rotated.bottomLeft)
    }

    // --- mapFillCenter ------------------------------------------------------

    @Test
    fun mapFillCenter_sameAspectRatio_scalesOnly() {
        val p = DocumentGeometry.mapFillCenter(FloatPoint(100f, 50f), 200, 100, 400f, 200f)
        assertEquals(FloatPoint(200f, 100f), p)
    }

    @Test
    fun mapFillCenter_tallerView_cropsSidesAndCentres() {
        // 100x100 image in a 100x200 view: scale 2, image becomes 200 wide, 50 px cropped each side.
        val centre = DocumentGeometry.mapFillCenter(FloatPoint(50f, 50f), 100, 100, 100f, 200f)
        assertEquals(FloatPoint(50f, 100f), centre)
        val left = DocumentGeometry.mapFillCenter(FloatPoint(0f, 0f), 100, 100, 100f, 200f)
        assertEquals(FloatPoint(-50f, 0f), left)
    }

    @Test
    fun mapFillCenter_widerView_cropsTopAndBottom() {
        // 100x100 image in a 200x100 view: scale 2, image becomes 200 tall, 50 px cropped top/bottom.
        val top = DocumentGeometry.mapFillCenter(FloatPoint(0f, 0f), 100, 100, 200f, 100f)
        assertEquals(FloatPoint(0f, -50f), top)
    }
    // --- area / edgeSupport / quadScore -------------------------------------

    @Test
    fun area_ofRectangle_isWidthTimesHeight() {
        assertEquals(20000.0, DocumentGeometry.area(rect(200f, 100f)), 0.001)
    }

    @Test
    fun edgeSupport_allEdges_isOne() {
        val s = DocumentGeometry.edgeSupport(rect(100f, 100f)) { _, _ -> true }
        assertEquals(1f, s, 0.0001f)
    }

    @Test
    fun edgeSupport_noEdges_isZero() {
        val s = DocumentGeometry.edgeSupport(rect(100f, 100f)) { _, _ -> false }
        assertEquals(0f, s, 0.0001f)
    }

    @Test
    fun edgeSupport_onlyTopSideCovered_isAboutOneQuarter() {
        // Samples on the top side have y == 0; the other sides have y > 0 almost everywhere.
        val s = DocumentGeometry.edgeSupport(rect(100f, 100f)) { _, y -> y == 0 }
        assertTrue("was $s", s in 0.2f..0.3f)
    }

    @Test
    fun quadScore_degenerateInput_isZero() {
        assertEquals(0.0, DocumentGeometry.quadScore(0.0, 100.0, 50.0, 1f), 0.0)
        assertEquals(0.0, DocumentGeometry.quadScore(50.0, 0.0, 50.0, 1f), 0.0)
    }

    @Test
    fun quadScore_noEdgeSupport_isZero() {
        assertEquals(0.0, DocumentGeometry.quadScore(50.0, 100.0, 50.0, 0f), 0.0)
    }

    @Test
    fun quadScore_largerAndBetterFilledQuad_scoresHigher() {
        val big = DocumentGeometry.quadScore(60.0, 100.0, 58.0, 0.9f)
        val small = DocumentGeometry.quadScore(20.0, 100.0, 19.0, 0.9f)
        val ragged = DocumentGeometry.quadScore(60.0, 100.0, 30.0, 0.9f)
        assertTrue(big > small)
        assertTrue(big > ragged)
    }



    // --- helpers ------------------------------------------------------------

    private fun rect(w: Float, h: Float) = DocumentCorners(
        FloatPoint(0f, 0f), FloatPoint(w, 0f), FloatPoint(w, h), FloatPoint(0f, h)
    )

    private fun <T> permutations(list: List<T>): List<List<T>> {
        if (list.size <= 1) return listOf(list)
        return list.flatMap { head ->
            permutations(list - head).map { listOf(head) + it }
        }
    }
}
