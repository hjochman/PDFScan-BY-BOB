package com.hape.pdfscan.scan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoCaptureControllerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private var firedCount = 0
    private lateinit var controller: AutoCaptureController

    @Before
    fun setUp() {
        firedCount = 0
        controller = AutoCaptureController(
            scope = testScope,
            countdownSeconds = 3,
            tickMs = 1_000L,
            maxDriftPx = 15f,
            onFire = { firedCount++ },
        )
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeResult(
        corners: DocumentCorners,
        isFallback: Boolean = false,
    ) = DetectionResult(
        corners = corners,
        isFallback = isFallback,
        timestampMs = System.currentTimeMillis(),
        imageWidth = 1000,
        imageHeight = 1000,
    )

    private fun stableCorners() = DocumentCorners(
        topLeft = FloatPoint(100f, 100f),
        topRight = FloatPoint(900f, 100f),
        bottomRight = FloatPoint(900f, 900f),
        bottomLeft = FloatPoint(100f, 900f),
    )

    private fun shiftedCorners(dx: Float = 0f, dy: Float = 0f) = DocumentCorners(
        topLeft = FloatPoint(100f + dx, 100f + dy),
        topRight = FloatPoint(900f + dx, 100f + dy),
        bottomRight = FloatPoint(900f + dx, 900f + dy),
        bottomLeft = FloatPoint(100f + dx, 900f + dy),
    )

    // -------------------------------------------------------------------------
    // Initial state
    // -------------------------------------------------------------------------

    @Test
    fun `initial state is Idle`() {
        assertEquals(AutoCaptureState.Idle, controller.state.value)
    }

    // -------------------------------------------------------------------------
    // Countdown starts on stable detection
    // -------------------------------------------------------------------------

    @Test
    fun `first stable frame starts countdown at 3`() = testScope.runTest {
        controller.onDetectionResult(makeResult(stableCorners()))
        advanceTimeBy(1L) // let coroutine start
        assertEquals(AutoCaptureState.Stable(3), controller.state.value)
    }

    @Test
    fun `countdown progresses 3 - 2 - 1 then fires`() = testScope.runTest {
        controller.onDetectionResult(makeResult(stableCorners()))
        advanceTimeBy(1L)
        assertEquals(AutoCaptureState.Stable(3), controller.state.value)

        // After 1 s → 2
        advanceTimeBy(1_000L)
        assertEquals(AutoCaptureState.Stable(2), controller.state.value)

        // After 2 s → 1
        advanceTimeBy(1_000L)
        assertEquals(AutoCaptureState.Stable(1), controller.state.value)

        // After 3 s → fires, back to Idle
        advanceTimeBy(1_000L)
        assertEquals(AutoCaptureState.Idle, controller.state.value)
        assertEquals(1, firedCount)
    }

    // -------------------------------------------------------------------------
    // Fallback detection resets countdown
    // -------------------------------------------------------------------------

    @Test
    fun `fallback detection resets countdown to Idle`() = testScope.runTest {
        controller.onDetectionResult(makeResult(stableCorners()))
        advanceTimeBy(1_100L) // past first tick (now at Stable(2))

        controller.onDetectionResult(makeResult(stableCorners(), isFallback = true))
        advanceTimeBy(1L)

        assertEquals(AutoCaptureState.Idle, controller.state.value)
        assertEquals(0, firedCount)
    }

    // -------------------------------------------------------------------------
    // Large movement resets countdown
    // -------------------------------------------------------------------------

    @Test
    fun `corner movement above threshold resets countdown`() = testScope.runTest {
        controller.onDetectionResult(makeResult(stableCorners()))
        advanceTimeBy(1_100L) // past first tick

        // Move corners by 20 px (> maxDriftPx = 15)
        controller.onDetectionResult(makeResult(shiftedCorners(dx = 20f)))
        advanceTimeBy(1L)

        assertEquals(AutoCaptureState.Idle, controller.state.value)
        assertEquals(0, firedCount)
    }

    // -------------------------------------------------------------------------
    // Small movement keeps countdown running
    // -------------------------------------------------------------------------

    @Test
    fun `corner movement below threshold keeps countdown running`() = testScope.runTest {
        controller.onDetectionResult(makeResult(stableCorners()))
        advanceTimeBy(1_100L) // Stable(2)

        // Move corners by 5 px (< maxDriftPx = 15)
        controller.onDetectionResult(makeResult(shiftedCorners(dx = 5f)))
        advanceTimeBy(1L)

        assertEquals(AutoCaptureState.Stable(2), controller.state.value)
        assertEquals(0, firedCount)
    }

    // -------------------------------------------------------------------------
    // Manual reset
    // -------------------------------------------------------------------------

    @Test
    fun `reset() cancels countdown and returns to Idle`() = testScope.runTest {
        controller.onDetectionResult(makeResult(stableCorners()))
        advanceTimeBy(1_100L) // Stable(2)

        controller.reset()
        advanceTimeBy(1L)

        assertEquals(AutoCaptureState.Idle, controller.state.value)
        assertEquals(0, firedCount)
    }

    @Test
    fun `reset() prevents late fire after countdown was in progress`() = testScope.runTest {
        controller.onDetectionResult(makeResult(stableCorners()))
        advanceTimeBy(2_100L) // Stable(1)

        controller.reset()
        advanceTimeBy(2_000L) // would have fired

        assertEquals(0, firedCount)
    }

    // -------------------------------------------------------------------------
    // No double-fire
    // -------------------------------------------------------------------------

    @Test
    fun `second stable result while counting down does not restart countdown`() = testScope.runTest {
        controller.onDetectionResult(makeResult(stableCorners()))
        advanceTimeBy(1_100L) // now Stable(2)

        // Another stable result – should not restart the countdown
        controller.onDetectionResult(makeResult(stableCorners()))
        advanceTimeBy(1L)

        // Still Stable(2), not Stable(3) again
        assertEquals(AutoCaptureState.Stable(2), controller.state.value)
    }

    // -------------------------------------------------------------------------
    // maxCornerDrift
    // -------------------------------------------------------------------------

    @Test
    fun `maxCornerDrift is zero for identical corners`() {
        val c = stableCorners()
        assertEquals(0f, AutoCaptureController.maxCornerDrift(c, c))
    }

    @Test
    fun `maxCornerDrift returns maximum displacement`() {
        val a = stableCorners()
        // Move topLeft by 10, topRight by 5 – max should be ~10
        val b = DocumentCorners(
            topLeft = FloatPoint(110f, 100f),
            topRight = FloatPoint(905f, 100f),
            bottomRight = FloatPoint(900f, 900f),
            bottomLeft = FloatPoint(100f, 900f),
        )
        val drift = AutoCaptureController.maxCornerDrift(a, b)
        assertEquals(10f, drift, 0.01f)
    }
}
