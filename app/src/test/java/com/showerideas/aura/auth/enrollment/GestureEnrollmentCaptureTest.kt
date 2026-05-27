package com.showerideas.aura.auth.enrollment

import com.showerideas.aura.auth.CameraHandEmbedder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Task 75 — Unit tests for [GestureEnrollmentCapture].
 *
 * All tests use synthetic [TestLandmarkFactory] data.
 * No camera hardware or MediaPipe model required.
 */
class GestureEnrollmentCaptureTest {

    private lateinit var capture: GestureEnrollmentCapture

    @Before fun setUp() {
        capture = GestureEnrollmentCapture()
    }

    @Test fun `anchor present for 3 frames then 60 frames collected`() {
        // Feed 3 anchor frames
        repeat(3) {
            val state = capture.processFrame(
                "Open_Palm", 0.95f, TestLandmarkFactory.openPalmFrame()
            )
            // First 2 return WaitingForPalm, 3rd triggers Capturing(0f)
            assertNotNull(state)
        }
        // Feed 60 capture frames
        var lastState: EnrollmentCaptureState? = null
        repeat(60) {
            lastState = capture.processFrame(
                "Open_Palm", 0.95f, TestLandmarkFactory.openPalmFrame()
            )
        }
        assertTrue("Expected CaptureComplete", lastState is EnrollmentCaptureState.CaptureComplete)
        val complete = lastState as EnrollmentCaptureState.CaptureComplete
        assertEquals(60, complete.frames.size)
    }

    @Test fun `anchor absent at frame 0 emits AnchorFailed`() {
        // Non-palm gesture, should fail immediately
        val state = capture.processFrame("Closed_Fist", 0.9f, TestLandmarkFactory.nonPalmFrame())
        assertEquals(EnrollmentCaptureState.AnchorFailed, state)
    }

    @Test fun `anchor low confidence emits AnchorFailed`() {
        val state = capture.processFrame("Open_Palm", 0.50f, TestLandmarkFactory.openPalmFrame())
        assertEquals(EnrollmentCaptureState.AnchorFailed, state)
    }

    @Test fun `anchor null landmarks emits AnchorFailed`() {
        val state = capture.processFrame("Open_Palm", 0.95f, null)
        assertEquals(EnrollmentCaptureState.AnchorFailed, state)
    }

    @Test fun `dropped frame at position 30 duplicates previous, count still 60`() {
        // Feed 3 anchor frames
        repeat(3) {
            capture.processFrame("Open_Palm", 0.95f, TestLandmarkFactory.openPalmFrame())
        }
        // Feed 29 normal frames
        repeat(29) {
            capture.processFrame("Open_Palm", 0.95f, TestLandmarkFactory.openPalmFrame())
        }
        // Feed 1 dropped frame (null embedding)
        capture.processFrame(null, 0f, null)
        // Feed remaining 30 frames
        var lastState: EnrollmentCaptureState? = null
        repeat(30) {
            lastState = capture.processFrame("Open_Palm", 0.95f, TestLandmarkFactory.openPalmFrame())
        }
        assertTrue("Expected CaptureComplete after dropped frame", lastState is EnrollmentCaptureState.CaptureComplete)
        val complete = lastState as EnrollmentCaptureState.CaptureComplete
        assertEquals(60, complete.frames.size)
    }

    @Test fun `progress fraction is monotonically increasing during capture`() {
        // Feed 3 anchor frames
        repeat(3) {
            capture.processFrame("Open_Palm", 0.95f, TestLandmarkFactory.openPalmFrame())
        }
        var lastProgress = -1f
        for (i in 0 until 59) {
            val state = capture.processFrame("Open_Palm", 0.95f, TestLandmarkFactory.openPalmFrame())
            if (state is EnrollmentCaptureState.Capturing) {
                assertTrue("Progress should increase", state.progressFraction > lastProgress)
                lastProgress = state.progressFraction
            }
        }
    }

    @Test fun `reset clears state for fresh attempt`() {
        capture.processFrame("Open_Palm", 0.95f, TestLandmarkFactory.openPalmFrame())
        capture.reset()
        // After reset, non-palm should immediately AnchorFail (not continue capture)
        val state = capture.processFrame("Closed_Fist", 0.9f, TestLandmarkFactory.nonPalmFrame())
        assertEquals(EnrollmentCaptureState.AnchorFailed, state)
    }
}
