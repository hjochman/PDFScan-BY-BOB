package com.hape.pdfscan.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionStatsTest {

    private fun corners(dx: Float = 0f, dy: Float = 0f) = DocumentCorners(
        topLeft = FloatPoint(100f + dx, 100f + dy),
        topRight = FloatPoint(900f + dx, 100f + dy),
        bottomRight = FloatPoint(900f + dx, 900f + dy),
        bottomLeft = FloatPoint(100f + dx, 900f + dy),
    )

    private fun result(c: DocumentCorners, fallback: Boolean = false) = DetectionResult(
        corners = c,
        isFallback = fallback,
        timestampMs = 0L,
        imageWidth = 1000,
        imageHeight = 1000,
    )

    @Test
    fun `first frame has no drift`() {
        val stats = DetectionStats()
        val s = stats.add(result(corners()))
        assertEquals(1, s.frames)
        assertEquals(0, s.jumps)
        assertEquals(0, s.dropouts)
        assertEquals(0f, s.meanDrift, 0f)
        assertEquals(0f, s.maxDrift, 0f)
    }

    @Test
    fun `identical frames give zero drift and no jumps`() {
        val stats = DetectionStats()
        repeat(5) { stats.add(result(corners())) }
        val s = stats.add(result(corners()))
        assertEquals(0, s.jumps)
        assertEquals(0f, s.meanDrift, 0f)
        assertEquals(0f, s.maxDrift, 0f)
    }

    @Test
    fun `small drift is measured but is not a jump`() {
        val stats = DetectionStats(jumpThresholdPx = 15f)
        stats.add(result(corners()))
        val s = stats.add(result(corners(dx = 3f, dy = 4f)))
        assertEquals(0, s.jumps)
        assertEquals(5f, s.maxDrift, 0.001f)
        assertEquals(5f, s.meanDrift, 0.001f)
    }

    @Test
    fun `drift above threshold counts as jump`() {
        val stats = DetectionStats(jumpThresholdPx = 15f)
        stats.add(result(corners()))
        val s = stats.add(result(corners(dx = 30f)))
        assertEquals(1, s.jumps)
        assertEquals(30f, s.maxDrift, 0.001f)
    }

    @Test
    fun `drift exactly at threshold is not a jump`() {
        val stats = DetectionStats(jumpThresholdPx = 15f)
        stats.add(result(corners()))
        val s = stats.add(result(corners(dx = 15f)))
        assertEquals(0, s.jumps)
    }

    @Test
    fun `mean and max drift over several frames`() {
        val stats = DetectionStats()
        stats.add(result(corners(0f)))
        stats.add(result(corners(2f)))   // drift 2
        stats.add(result(corners(8f)))   // drift 6
        val s = stats.add(result(corners(8f))) // drift 0
        assertEquals((2f + 6f + 0f) / 3f, s.meanDrift, 0.001f)
        assertEquals(6f, s.maxDrift, 0.001f)
    }

    @Test
    fun `fallback frame counts as dropout and yields no drift`() {
        val stats = DetectionStats()
        stats.add(result(corners()))
        val s = stats.add(result(corners(dx = 500f), fallback = true))
        assertEquals(1, s.dropouts)
        assertEquals(0, s.jumps)
        assertEquals(0f, s.maxDrift, 0f)
    }

    @Test
    fun `drift is not measured across a dropout`() {
        val stats = DetectionStats()
        stats.add(result(corners()))
        stats.add(result(corners(), fallback = true))
        val s = stats.add(result(corners(dx = 200f)))
        assertEquals(1, s.dropouts)
        assertEquals(0, s.jumps)
        assertEquals(0f, s.maxDrift, 0f)
    }

    @Test
    fun `window drops oldest frames`() {
        val stats = DetectionStats(windowSize = 3, jumpThresholdPx = 15f)
        stats.add(result(corners(0f)))
        stats.add(result(corners(100f))) // jump, will leave the window
        stats.add(result(corners(100f)))
        stats.add(result(corners(100f)))
        val s = stats.add(result(corners(100f)))
        assertEquals(3, s.frames)
        assertEquals(0, s.jumps)
        assertEquals(0f, s.maxDrift, 0f)
    }

    @Test
    fun `dropouts leave the window too`() {
        val stats = DetectionStats(windowSize = 2)
        stats.add(result(corners(), fallback = true))
        stats.add(result(corners()))
        val s = stats.add(result(corners()))
        assertEquals(0, s.dropouts)
    }

    @Test
    fun `reset clears counters and previous frame`() {
        val stats = DetectionStats()
        stats.add(result(corners()))
        stats.add(result(corners(dx = 50f)))
        stats.reset()
        val s = stats.add(result(corners(dx = 400f)))
        assertEquals(1, s.frames)
        assertEquals(0, s.jumps)
        assertEquals(0f, s.maxDrift, 0f)
    }
}
