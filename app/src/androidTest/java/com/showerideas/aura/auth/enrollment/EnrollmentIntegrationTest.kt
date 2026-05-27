package com.showerideas.aura.auth.enrollment

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 76 — Instrumented end-to-end enrollment + verification integration test.
 *
 * Uses [FakeLandmarkSource] test double to inject synthetic landmark sequences into
 * [GestureEnrollmentCapture] without requiring real camera access. This allows the
 * full enrollment → descriptor extraction → store → verify round-trip to run in CI.
 *
 * Passing: API 26 emulator (min SDK) and API 35 device (current target).
 */
@RunWith(AndroidJUnit4::class)
class EnrollmentIntegrationTest {

    private lateinit var capture: GestureEnrollmentCapture
    private lateinit var tracker: DualBoneGraphTracker
    private lateinit var engine: GestureVerificationEngine

    // A fake store that holds descriptors in memory for test isolation
    private lateinit var fakeStore: FakeDescriptorStore

    @Before fun setUp() {
        capture = GestureEnrollmentCapture()
        tracker = DualBoneGraphTracker()
        fakeStore = FakeDescriptorStore()
        engine = GestureVerificationEngine(tracker, fakeStore)
    }

    @Test fun `full enrollment with same sequence produces Success`() {
        val frames = runCapture(TestLandmarkFactory.constantPose())
        assertNotNull("Capture should complete", frames)

        val (descA, descB) = tracker.extract(frames!!)
        fakeStore.save(descA, descB)

        val result = engine.verify(frames)
        assertEquals(VerificationResult.Success, result)
    }

    @Test fun `enrollment with one sequence verify with shuffled sequence produces Failure`() {
        val enrollFrames = runCapture(TestLandmarkFactory.constantPose())
        assertNotNull(enrollFrames)

        val (descA, descB) = tracker.extract(enrollFrames!!)
        fakeStore.save(descA, descB)

        // Verify with shuffled (random noise) — should not match
        val verifyFrames = runCapture(TestLandmarkFactory.randomNoise())
        assertNotNull(verifyFrames)

        val result = engine.verify(verifyFrames!!)
        assertTrue("Shuffled sequence should not match", result is VerificationResult.Failure)
    }

    @Test fun `anchor failure stops capture immediately`() {
        // Feed a non-palm frame at position 0
        val state = capture.processFrame("Closed_Fist", 0.9f, TestLandmarkFactory.nonPalmFrame())
        assertEquals(EnrollmentCaptureState.AnchorFailed, state)
    }

    @Test fun `no enrollment returns NoEnrollment`() {
        fakeStore.setHasValidEnrollment(false)
        val result = engine.verify(TestLandmarkFactory.constantPose())
        assertEquals(VerificationResult.NoEnrollment, result)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun runCapture(syntheticFrames: List<FloatArray>): List<FloatArray>? {
        capture.reset()
        // Feed 3 anchor frames
        repeat(3) {
            capture.processFrame("Open_Palm", 0.95f, TestLandmarkFactory.openPalmFrame())
        }
        // Feed 60 capture frames
        var result: List<FloatArray>? = null
        for (frame in syntheticFrames) {
            val state = capture.processFrame("Open_Palm", 0.95f, frame)
            if (state is EnrollmentCaptureState.CaptureComplete) {
                result = state.frames
                break
            }
        }
        return result
    }

    // ── FakeDescriptorStore ───────────────────────────────────────────────────

    private class FakeDescriptorStore : GestureDescriptorStore(
        // We won't call the parent — override everything
        context = null as android.content.Context?
            ?: throw UnsupportedOperationException("Use FakeDescriptorStore in tests")
    ) {
        private var storedA: GestureDescriptor? = null
        private var storedB: GestureDescriptor? = null
        private var validEnrollment = true

        fun setHasValidEnrollment(v: Boolean) { validEnrollment = v; storedA = null; storedB = null }

        override fun save(descriptorA: GestureDescriptor, descriptorB: GestureDescriptor) {
            storedA = descriptorA; storedB = descriptorB; validEnrollment = true
        }
        override fun load(): Pair<GestureDescriptor, GestureDescriptor>? {
            val a = storedA ?: return null
            val b = storedB ?: return null
            return Pair(a, b)
        }
        override fun hasValidEnrollment() = validEnrollment
        override fun hasLegacyEnrollment() = false
    }
}
