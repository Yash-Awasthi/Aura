package com.showerideas.aura.auth.enrollment

import com.showerideas.aura.auth.CameraHandEmbedder
import javax.inject.Inject

/**
 * Task 68 — Dual overlapping temporal bone-graph descriptor extractor.
 *
 * Takes a 60-frame buffer from [GestureEnrollmentCapture] and produces two
 * independent [GestureDescriptor] objects:
 *
 * - Window A: frames 0–44 (0s → 1.5s)   — captures gesture initiation
 * - Window B: frames 15–59 (0.5s → 2.0s) — captures gesture completion
 *
 * Both windows are 45 frames, overlapping by 30 frames. Each produces one
 * descriptor independently — no cross-window averaging ever occurs.
 *
 * ## Descriptor computation per window
 * 1. centroid = element-wise mean of all 45 frame embeddings (63 floats)
 * 2. motionProfile = cosine similarity between each consecutive frame pair
 *    → 44 values (N-1 deltas for N=45 frames)
 *
 * The compound matching vector is [centroid (63) || motionProfile (44)] = 107 floats.
 * Matching in [GestureVerificationEngine] uses cosine similarity on this compound vector.
 *
 * ## Why two windows
 * Window A captures the initiation; window B captures the completion. Together they
 * bracket the full gesture arc. Replay or impersonation attacks that match one window
 * are statistically unlikely to match both independently (different temporal phases).
 *
 * See: kinivi/hand-gesture-recognition-mediapipe (descriptor design reference)
 * See: ROADMAP §Task 68
 */
class DualBoneGraphTracker @Inject constructor() {

    companion object {
        private const val WINDOW_A_START = 0
        private const val WINDOW_A_END = 45     // exclusive → frames 0..44 (45 frames)
        private const val WINDOW_B_START = 15
        private const val WINDOW_B_END = 60     // exclusive → frames 15..59 (45 frames)
        private const val WINDOW_SIZE = 45
        const val MOTION_PROFILE_SIZE = WINDOW_SIZE - 1  // 44 deltas
        const val EMBEDDING_SIZE = CameraHandEmbedder.EMBEDDING_SIZE  // 63
    }

    /**
     * Extract dual descriptors from a 60-frame buffer.
     *
     * @param frames Exactly 60 normalised 63-float embeddings from [GestureEnrollmentCapture].
     * @return Pair of (descriptorA, descriptorB). Both are fully independent extractions.
     * @throws IllegalArgumentException if frames.size != 60.
     */
    fun extract(frames: List<FloatArray>): Pair<GestureDescriptor, GestureDescriptor> {
        require(frames.size == GestureEnrollmentCapture.CAPTURE_FRAMES) {
            "DualBoneGraphTracker: expected ${GestureEnrollmentCapture.CAPTURE_FRAMES} frames, " +
            "got ${frames.size}"
        }
        val descriptorA = extractWindow(
            frames.subList(WINDOW_A_START, WINDOW_A_END),
            GestureDescriptor.WindowTag.A
        )
        val descriptorB = extractWindow(
            frames.subList(WINDOW_B_START, WINDOW_B_END),
            GestureDescriptor.WindowTag.B
        )
        return Pair(descriptorA, descriptorB)
    }

    private fun extractWindow(
        windowFrames: List<FloatArray>,
        tag: GestureDescriptor.WindowTag
    ): GestureDescriptor {
        require(windowFrames.size == WINDOW_SIZE) {
            "Window $tag: expected $WINDOW_SIZE frames, got ${windowFrames.size}"
        }

        // centroid: element-wise mean across all 45 frames
        val centroid = FloatArray(EMBEDDING_SIZE)
        for (frame in windowFrames) {
            for (i in centroid.indices) {
                centroid[i] += if (i < frame.size) frame[i] else 0f
            }
        }
        val n = windowFrames.size.toFloat()
        for (i in centroid.indices) centroid[i] /= n

        // motionProfile: cosine similarity between consecutive frame pairs → 44 values
        val motionProfile = FloatArray(MOTION_PROFILE_SIZE) { idx ->
            CameraHandEmbedder.cosineSimilarity(windowFrames[idx], windowFrames[idx + 1])
        }

        return GestureDescriptor(
            centroid = centroid,
            motionProfile = motionProfile,
            windowTag = tag
        )
    }
}
