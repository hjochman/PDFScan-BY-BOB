package com.hape.pdfscan.scan

import kotlin.math.hypot
import kotlin.math.max

/**
 * Exponential smoothing of the four corners of a [DocumentCorners] so the live overlay
 * jitters less. Pure logic, no Android dependencies.
 *
 * Per corner: `smoothed = previous + alpha * (raw - previous)` with `alpha = 1 / (1 + strength)`.
 * Strength 0 therefore passes the raw value through (alpha = 1); strength 10 gives
 * alpha = 1/11, i.e. strong smoothing.
 *
 * If the largest corner displacement between the previous smoothed corners and the new raw
 * corners exceeds [JUMP_FRACTION] of the image diagonal (15 %: far more than hand jitter,
 * which is a few percent at most, but clearly a different document or a new position), the
 * state is reset to the raw value so the overlay does not drag behind. The same happens
 * when the image size changes.
 *
 * Called from the analysis thread and reset from the main thread, hence synchronized.
 */
class CornerSmoother {

    private val lock = Any()

    private var previous: DocumentCorners? = null
    private var previousWidth = 0
    private var previousHeight = 0

    /** Smoothing strength 0..10 (0 = off). Out-of-range values are coerced. */
    @Volatile
    var strength: Int = DEFAULT_STRENGTH
        set(value) {
            field = value.coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        }

    /**
     * Returns the smoothed corners for [raw], taken from an image of [imageWidth] × [imageHeight].
     * The returned value is also stored as the new state.
     */
    fun smooth(raw: DocumentCorners, imageWidth: Int, imageHeight: Int): DocumentCorners {
        val currentStrength = strength
        synchronized(lock) {
            val prev = previous
            val sameImage = imageWidth == previousWidth && imageHeight == previousHeight
            val result = if (
                currentStrength <= 0 || prev == null || !sameImage ||
                maxDisplacement(prev, raw) > JUMP_FRACTION * hypot(imageWidth.toFloat(), imageHeight.toFloat())
            ) {
                raw
            } else {
                val alpha = 1f / (1f + currentStrength)
                DocumentCorners(
                    topLeft = blend(prev.topLeft, raw.topLeft, alpha),
                    topRight = blend(prev.topRight, raw.topRight, alpha),
                    bottomRight = blend(prev.bottomRight, raw.bottomRight, alpha),
                    bottomLeft = blend(prev.bottomLeft, raw.bottomLeft, alpha),
                )
            }
            previous = result
            previousWidth = imageWidth
            previousHeight = imageHeight
            return result
        }
    }

    /** Forgets the state; the next [smooth] call returns its input unchanged. */
    fun reset() {
        synchronized(lock) {
            previous = null
            previousWidth = 0
            previousHeight = 0
        }
    }

    private fun blend(prev: FloatPoint, raw: FloatPoint, alpha: Float) = FloatPoint(
        x = prev.x + alpha * (raw.x - prev.x),
        y = prev.y + alpha * (raw.y - prev.y),
    )

    private fun maxDisplacement(a: DocumentCorners, b: DocumentCorners): Float {
        var maxDistance = 0f
        val pa = a.toList()
        val pb = b.toList()
        for (i in pa.indices) {
            maxDistance = max(maxDistance, hypot(pa[i].x - pb[i].x, pa[i].y - pb[i].y))
        }
        return maxDistance
    }

    companion object {
        const val MIN_STRENGTH = 0
        const val MAX_STRENGTH = 10
        const val DEFAULT_STRENGTH = 5

        /** Maximum corner displacement (fraction of the image diagonal) that is still smoothed. */
        const val JUMP_FRACTION = 0.15f
    }
}
