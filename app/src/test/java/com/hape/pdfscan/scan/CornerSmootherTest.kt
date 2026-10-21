package com.hape.pdfscan.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CornerSmootherTest {

    private val width = 1000
    private val height = 1000

    private fun corners(dx: Float = 0f, dy: Float = 0f) = DocumentCorners(
        topLeft = FloatPoint(100f + dx, 100f + dy),
        topRight = FloatPoint(900f + dx, 100f + dy),
        bottomRight = FloatPoint(900f + dx, 900f + dy),
        bottomLeft = FloatPoint(100f + dx, 900f + dy),
    )

    private fun smoother(strength: Int) = CornerSmoother().apply { this.strength = strength }

    @Test
    fun `strength 0 passes raw values through`() {
        val s = smoother(0)
        s.smooth(corners(), width, height)
        val raw = corners(dx = 10f, dy = -5f)
        assertEquals(raw, s.smooth(raw, width, height))
    }

    @Test
    fun `first value is returned unchanged`() {
        val s = smoother(10)
        val raw = corners(dx = 7f)
        assertEquals(raw, s.smooth(raw, width, height))
    }

    @Test
    fun `converges to constant input`() {
        val s = smoother(5)
        s.smooth(corners(), width, height)
        val target = corners(dx = 50f, dy = 30f)
        var result = target
        repeat(100) { result = s.smooth(target, width, height) }
        assertEquals(target.topLeft.x, result.topLeft.x, 0.01f)
        assertEquals(target.topLeft.y, result.topLeft.y, 0.01f)
        assertEquals(target.bottomRight.x, result.bottomRight.x, 0.01f)
        assertEquals(target.bottomRight.y, result.bottomRight.y, 0.01f)
    }

    @Test
    fun `single step uses alpha 1 over 1 plus strength`() {
        val s = smoother(4) // alpha = 0.2
        s.smooth(corners(), width, height)
        val result = s.smooth(corners(dx = 50f), width, height)
        assertEquals(110f, result.topLeft.x, 0.001f)
        assertEquals(100f, result.topLeft.y, 0.001f)
    }

    @Test
    fun `smoothing dampens jitter`() {
        val raw = CornerSmoother().apply { strength = 0 }
        val smooth = CornerSmoother().apply { strength = 8 }
        var rawMin = Float.MAX_VALUE
        var rawMax = -Float.MAX_VALUE
        var smoothMin = Float.MAX_VALUE
        var smoothMax = -Float.MAX_VALUE
        for (i in 0 until 60) {
            val jitter = if (i % 2 == 0) 6f else -6f
            val r = raw.smooth(corners(dx = jitter), width, height).topLeft.x
            val m = smooth.smooth(corners(dx = jitter), width, height).topLeft.x
            if (i >= 20) { // ignore the warm-up phase
                rawMin = minOf(rawMin, r); rawMax = maxOf(rawMax, r)
                smoothMin = minOf(smoothMin, m); smoothMax = maxOf(smoothMax, m)
            }
        }
        val rawRange = rawMax - rawMin
        val smoothRange = smoothMax - smoothMin
        assertEquals(12f, rawRange, 0.001f)
        assertTrue("smoothed range $smoothRange should be well below raw range $rawRange", smoothRange < rawRange / 3f)
    }

    @Test
    fun `large jump resets to raw value`() {
        val s = smoother(10)
        s.smooth(corners(), width, height)
        // 400 px shift > 15 % of the ~1414 px diagonal (212 px)
        val jumped = corners(dx = 400f)
        assertEquals(jumped, s.smooth(jumped, width, height))
    }

    @Test
    fun `shift below threshold is smoothed`() {
        val s = smoother(10)
        s.smooth(corners(), width, height)
        // 150 px shift < 212 px threshold
        val result = s.smooth(corners(dx = 150f), width, height)
        assertTrue(result.topLeft.x > 100f && result.topLeft.x < 250f)
        assertTrue(abs(result.topLeft.x - 250f) > 1f)
    }

    @Test
    fun `reset forgets previous state`() {
        val s = smoother(10)
        s.smooth(corners(), width, height)
        s.reset()
        val raw = corners(dx = 20f)
        assertEquals(raw, s.smooth(raw, width, height))
    }

    @Test
    fun `image size change resets state`() {
        val s = smoother(10)
        s.smooth(corners(), width, height)
        val raw = corners(dx = 20f)
        assertEquals(raw, s.smooth(raw, 640, 480))
    }

    @Test
    fun `strength is coerced into range`() {
        val s = CornerSmoother()
        s.strength = 99
        assertEquals(CornerSmoother.MAX_STRENGTH, s.strength)
        s.strength = -3
        assertEquals(CornerSmoother.MIN_STRENGTH, s.strength)
    }
}
