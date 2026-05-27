package com.showerideas.aura.auth.enrollment

import com.showerideas.aura.auth.CameraHandEmbedder
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 70 — Dual-descriptor verification engine.
 *
 * During verification, runs the same capture pipeline ([GestureEnrollmentCapture])
 * and descriptor extraction ([DualBoneGraphTracker]) as enrollment, then matches
 * both live descriptors against both stored descriptors independently.
 *
 * ## Matching algorithm
 * 1. Live capture: 60 frames via [GestureEnrollmentCapture]
 * 2. Extract liveA and liveB via [DualBoneGraphTracker]
 * 3. Load storedA and storedB from [GestureDescriptorStore]
 * 4. Match A: cosine_sim([liveA.centroid || liveA.motionProfile],
 *                        [storedA.centroid || storedA.motionProfile]) ≥ 0.85
 * 5. Match B: cosine_sim([liveB.centroid || liveB.motionProfile],
 *                        [storedB.centroid || storedB.motionProfile]) ≥ 0.85
 * 6. BOTH must pass — AND logic only. This is a hard architectural constraint.
 *    Do not change to OR without explicit sign-off.
 *
 * Threshold note: 0.85 vs legacy 0.88 because the compound 107-float vector
 * inherently introduces temporal variance (motionProfile). Tune via HandEmbeddingEntropyTest.
 *
 * See: ROADMAP §Task 70, Phase 5 ADR constraint 4
 */
@Singleton
class GestureVerificationEngine @Inject constructor(
    private val tracker: DualBoneGraphTracker,
    private val store: GestureDescriptorStore
) {

    companion object {
        /** Cosine similarity threshold for compound 107-float vector matching. */
        const val MATCH_THRESHOLD = 0.85f
    }

    /**
     * Verify a live gesture capture against stored descriptors.
     *
     * @param liveFrames 60-frame buffer from a completed [GestureEnrollmentCapture].
     * @return [VerificationResult] indicating outcome.
     */
    fun verify(liveFrames: List<FloatArray>): VerificationResult {
        // Check enrollment state
        if (store.hasLegacyEnrollment()) {
            Timber.w("GestureVerificationEngine: legacy enrollment detected — re-enrollment required")
            return VerificationResult.LegacyEnrollment
        }
        if (!store.hasValidEnrollment()) {
            Timber.w("GestureVerificationEngine: no enrollment found")
            return VerificationResult.NoEnrollment
        }

        // Validate frame count (anchor failure upstream)
        if (liveFrames.isEmpty()) {
            return VerificationResult.AnchorFailed
        }

        // Extract live descriptors
        val (liveA, liveB) = try {
            tracker.extract(liveFrames)
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "GestureVerificationEngine: descriptor extraction failed")
            return VerificationResult.AnchorFailed
        }

        // Load stored descriptors
        val (storedA, storedB) = store.load() ?: run {
            Timber.e("GestureVerificationEngine: store.load() returned null despite hasValidEnrollment=true")
            return VerificationResult.NoEnrollment
        }

        // Match both windows independently
        val simA = CameraHandEmbedder.cosineSimilarity(
            liveA.compoundVector(), storedA.compoundVector()
        )
        val simB = CameraHandEmbedder.cosineSimilarity(
            liveB.compoundVector(), storedB.compoundVector()
        )

        val passA = simA >= MATCH_THRESHOLD
        val passB = simB >= MATCH_THRESHOLD

        Timber.d("GestureVerificationEngine: simA=%.4f passA=$passA | simB=%.4f passB=$passB".format(simA, simB))

        return when {
            passA && passB  -> VerificationResult.Success
            !passA && passB -> VerificationResult.Failure(GestureDescriptor.WindowTag.A)
            passA && !passB -> VerificationResult.Failure(GestureDescriptor.WindowTag.B)
            else            -> VerificationResult.Failure(null)   // both failed
        }
    }
}

/**
 * Result of [GestureVerificationEngine.verify].
 * Hard constraint: [Success] requires BOTH windows to pass. There is no partial-match path.
 */
sealed class VerificationResult {
    /** Both window A and window B matched the stored descriptors at threshold ≥ 0.85. */
    object Success : VerificationResult()

    /**
     * One or both windows failed.
     * [failedWindow] identifies which window failed; null means both failed simultaneously.
     */
    data class Failure(val failedWindow: GestureDescriptor.WindowTag?) : VerificationResult()

    /** Open-palm anchor not detected at capture start — live capture aborted. */
    object AnchorFailed : VerificationResult()

    /** No dual-descriptor enrollment found in [GestureDescriptorStore]. */
    object NoEnrollment : VerificationResult()

    /**
     * Old single-embedding schema detected. User must re-enroll via
     * [GestureEnrollmentFragment] before verification is possible.
     */
    object LegacyEnrollment : VerificationResult()
}
