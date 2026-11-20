package com.hape.pdfscan.scan

/**
 * Measures the stability of successive detection results over a sliding window of frames.
 * Pure Kotlin, no Android dependencies; used for diagnostics only and has no influence on
 * detection.
 *
 * Per frame:
 * - **Dropout:** the frame is a fallback (no document found).
 * - **Drift:** maximum corner displacement to the previous frame, only if both frames are
 *   non-fallback (see [AutoCaptureController.maxCornerDrift]).
 * - **Jump:** drift greater than [jumpThresholdPx].
 *
 * Not thread-safe; call from a single thread (the analysis executor).
 */
class DetectionStats(
    /** Number of most recent frames the counters refer to. */
    val windowSize: Int = DEFAULT_WINDOW,
    /** Drift in pixels above which a frame counts as a jump. */
    val jumpThresholdPx: Float = DEFAULT_JUMP_THRESHOLD_PX,
) {
    /** Counters over the current window. [meanDrift] and [maxDrift] are 0 if no drift was measured. */
    data class Snapshot(
        val frames: Int,
        val jumps: Int,
        val dropouts: Int,
        val meanDrift: Float,
        val maxDrift: Float,
    )

    private class Sample(val drift: Float?, val dropout: Boolean)

    private val window = ArrayDeque<Sample>()
    private var previous: DetectionResult? = null

    init {
        require(windowSize > 0) { "windowSize must be positive" }
    }

    /** Adds [result] and returns the counters over the last [windowSize] frames. */
    fun add(result: DetectionResult): Snapshot {
        val prev = previous
        val drift = if (prev != null && !prev.isFallback && !result.isFallback) {
            AutoCaptureController.maxCornerDrift(prev.corners, result.corners)
        } else {
            null
        }
        window.addLast(Sample(drift, result.isFallback))
        if (window.size > windowSize) window.removeFirst()
        previous = result
        return snapshot()
    }

    /** Forgets all frames, e.g. when the camera is restarted. */
    fun reset() {
        window.clear()
        previous = null
    }

    private fun snapshot(): Snapshot {
        var jumps = 0
        var dropouts = 0
        var driftSum = 0f
        var driftCount = 0
        var driftMax = 0f
        for (s in window) {
            if (s.dropout) dropouts++
            val d = s.drift ?: continue
            driftSum += d
            driftCount++
            if (d > driftMax) driftMax = d
            if (d > jumpThresholdPx) jumps++
        }
        val mean = if (driftCount > 0) driftSum / driftCount else 0f
        return Snapshot(window.size, jumps, dropouts, mean, driftMax)
    }

    companion object {
        const val DEFAULT_WINDOW = 30
        const val DEFAULT_JUMP_THRESHOLD_PX = 15f
    }
}
