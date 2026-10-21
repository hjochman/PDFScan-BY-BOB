package com.hape.pdfscan.scan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * State machine for the auto-capture countdown.
 *
 * Transitions:
 *   Idle  ──► Stable(3) ──► Stable(2) ──► Stable(1) ──► Fire
 *              │ (instability detected)
 *              └──► Idle
 */
sealed interface AutoCaptureState {
    /** No document detected or image is moving – countdown not running. */
    data object Idle : AutoCaptureState

    /**
     * Document found and image is stable; [remainingSeconds] counts down 3 → 1.
     * When it reaches 0 the controller calls [onFire] and transitions to [Idle].
     */
    data class Stable(val remainingSeconds: Int) : AutoCaptureState
}

/**
 * Determines whether successive detection results are stable enough for auto-capture.
 *
 * Stability criterion: the maximum displacement of any corner between the two most recent
 * non-fallback frames is below [maxDriftPx] pixels (in analysis-frame coordinates).
 *
 * The countdown duration and tick interval are injected so they can be replaced in tests.
 *
 * Thread-safety: [onDetectionResult] may be called from any thread.
 * [onFire] is called from a coroutine launched in [scope].
 */
class AutoCaptureController(
    private val scope: CoroutineScope,
    /** Total seconds before capture is triggered. 0 disables auto-capture entirely. */
    var countdownSeconds: Int = 3,
    /** How often the countdown ticks, in milliseconds. */
    val tickMs: Long = 1_000L,
    /**
     * Maximum allowed displacement (in pixels, analysis-frame space) for any single
     * corner between two consecutive stable frames.
     */
    val maxDriftPx: Float = 15f,
    /** Called on the coroutine scope when the countdown completes. */
    val onFire: () -> Unit,
) {
    private val _state = MutableStateFlow<AutoCaptureState>(AutoCaptureState.Idle)
    val state: StateFlow<AutoCaptureState> = _state.asStateFlow()

    @Volatile private var lastStableCorners: DocumentCorners? = null
    @Volatile private var hasFired = false
    private var countdownJob: Job? = null

    /**
     * Should be called for every new [DetectionResult] from the analysis pipeline.
     * Fallback detections (full-frame) never start the countdown.
     */
    fun onDetectionResult(result: DetectionResult) {
        if (hasFired) return
        // Auto-capture disabled when countdownSeconds == 0
        if (countdownSeconds <= 0) {
            reset()
            return
        }
        if (result.isFallback) {
            reset()
            return
        }

        val prev = lastStableCorners
        val curr = result.corners
        lastStableCorners = curr

        val isStable = prev == null || maxCornerDrift(prev, curr) <= maxDriftPx

        if (isStable) {
            if (countdownJob == null || countdownJob?.isActive == false) {
                startCountdown()
            }
        } else {
            reset()
        }
    }

    /**
     * Resets the state machine to [AutoCaptureState.Idle].
     * Call this when the camera is stopped or a capture is triggered manually.
     */
    fun reset() {
        countdownJob?.cancel()
        countdownJob = null
        lastStableCorners = null
        hasFired = false
        _state.value = AutoCaptureState.Idle
    }

    // -------------------------------------------------------------------------

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            for (remaining in countdownSeconds downTo 1) {
                _state.value = AutoCaptureState.Stable(remaining)
                delay(tickMs)
            }
            hasFired = true
            _state.value = AutoCaptureState.Idle
            onFire()
        }
    }

    companion object {
        /** Maximum displacement of any corner between two frames (Euclidean distance). */
        fun maxCornerDrift(a: DocumentCorners, b: DocumentCorners): Float {
            return listOf(
                drift(a.topLeft, b.topLeft),
                drift(a.topRight, b.topRight),
                drift(a.bottomRight, b.bottomRight),
                drift(a.bottomLeft, b.bottomLeft),
            ).max()
        }

        private fun drift(p: FloatPoint, q: FloatPoint): Float =
            hypot((q.x - p.x).toDouble(), (q.y - p.y).toDouble()).toFloat()
    }
}
