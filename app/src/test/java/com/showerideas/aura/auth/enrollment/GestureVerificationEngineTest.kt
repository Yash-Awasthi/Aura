package com.showerideas.aura.auth.enrollment

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*

/**
 * Task 75 — Unit tests for [GestureVerificationEngine].
 *
 * Covers all six [VerificationResult] paths:
 * Success, Failure(A), Failure(B), AnchorFailed, NoEnrollment, LegacyEnrollment.
 */
class GestureVerificationEngineTest {

    private lateinit var tracker: DualBoneGraphTracker
    private lateinit var store: GestureDescriptorStore
    private lateinit var engine: GestureVerificationEngine

    @Before fun setUp() {
        tracker = DualBoneGraphTracker()
        store = mock(GestureDescriptorStore::class.java)
        engine = GestureVerificationEngine(tracker, store)
    }

    @Test fun `both windows match returns Success`() {
        val frames = TestLandmarkFactory.constantPose()
        val (descA, descB) = tracker.extract(frames)

        `when`(store.hasLegacyEnrollment()).thenReturn(false)
        `when`(store.hasValidEnrollment()).thenReturn(true)
        `when`(store.load()).thenReturn(Pair(descA, descB))

        val result = engine.verify(frames)
        assertEquals(VerificationResult.Success, result)
    }

    @Test fun `window A fails returns Failure(A)`() {
        val frames = TestLandmarkFactory.constantPose()
        val (descA, descB) = tracker.extract(frames)

        // Create a very different stored descriptor for A (random noise)
        val noisyFrames = TestLandmarkFactory.randomNoise()
        val (storedA, _) = tracker.extract(noisyFrames)

        `when`(store.hasLegacyEnrollment()).thenReturn(false)
        `when`(store.hasValidEnrollment()).thenReturn(true)
        `when`(store.load()).thenReturn(Pair(storedA, descB))  // B matches, A doesn't

        val result = engine.verify(frames)
        assertTrue("Expected Failure", result is VerificationResult.Failure)
        val failure = result as VerificationResult.Failure
        assertEquals(GestureDescriptor.WindowTag.A, failure.failedWindow)
    }

    @Test fun `window B fails returns Failure(B)`() {
        val frames = TestLandmarkFactory.constantPose()
        val (descA, descB) = tracker.extract(frames)

        val noisyFrames = TestLandmarkFactory.randomNoise()
        val (_, storedB) = tracker.extract(noisyFrames)

        `when`(store.hasLegacyEnrollment()).thenReturn(false)
        `when`(store.hasValidEnrollment()).thenReturn(true)
        `when`(store.load()).thenReturn(Pair(descA, storedB))  // A matches, B doesn't

        val result = engine.verify(frames)
        assertTrue("Expected Failure", result is VerificationResult.Failure)
        val failure = result as VerificationResult.Failure
        assertEquals(GestureDescriptor.WindowTag.B, failure.failedWindow)
    }

    @Test fun `empty frames returns AnchorFailed`() {
        `when`(store.hasLegacyEnrollment()).thenReturn(false)
        `when`(store.hasValidEnrollment()).thenReturn(true)

        val result = engine.verify(emptyList())
        assertEquals(VerificationResult.AnchorFailed, result)
    }

    @Test fun `no enrollment returns NoEnrollment`() {
        `when`(store.hasLegacyEnrollment()).thenReturn(false)
        `when`(store.hasValidEnrollment()).thenReturn(false)

        val result = engine.verify(TestLandmarkFactory.constantPose())
        assertEquals(VerificationResult.NoEnrollment, result)
    }

    @Test fun `legacy enrollment returns LegacyEnrollment`() {
        `when`(store.hasLegacyEnrollment()).thenReturn(true)

        val result = engine.verify(TestLandmarkFactory.constantPose())
        assertEquals(VerificationResult.LegacyEnrollment, result)
    }

    @Test fun `AND logic enforced — A-pass B-fail is not Success`() {
        val frames = TestLandmarkFactory.constantPose()
        val (descA, _) = tracker.extract(frames)
        val noisyFrames = TestLandmarkFactory.randomNoise()
        val (_, badB) = tracker.extract(noisyFrames)

        `when`(store.hasLegacyEnrollment()).thenReturn(false)
        `when`(store.hasValidEnrollment()).thenReturn(true)
        `when`(store.load()).thenReturn(Pair(descA, badB))

        val result = engine.verify(frames)
        assertNotEquals(VerificationResult.Success, result)
    }
}
