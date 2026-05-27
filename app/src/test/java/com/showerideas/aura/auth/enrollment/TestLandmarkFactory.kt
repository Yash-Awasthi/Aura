package com.showerideas.aura.auth.enrollment

import com.showerideas.aura.auth.CameraHandEmbedder

/**
 * Task 75 — Synthetic landmark frame factory for enrollment pipeline unit tests.
 *
 * All test scenarios use deterministic synthetic data — never real camera frames.
 * This ensures tests are hermetic, fast, and non-flaky.
 */
object TestLandmarkFactory {

    /** Constant pose: all frames identical — simulates a perfectly still hand. */
    fun constantPose(frameCount: Int = GestureEnrollmentCapture.CAPTURE_FRAMES): List<FloatArray> {
        val frame = FloatArray(CameraHandEmbedder.EMBEDDING_SIZE) { i -> (i + 1) * 0.01f }
        return List(frameCount) { frame.copyOf() }
    }

    /**
     * Slow rotation: each frame differs by a small delta — simulates natural hand drift.
     * [deltaPerFrame] is added to each element; kept small to stay within threshold.
     */
    fun slowRotation(
        frameCount: Int = GestureEnrollmentCapture.CAPTURE_FRAMES,
        deltaPerFrame: Float = 0.003f
    ): List<FloatArray> {
        val base = FloatArray(CameraHandEmbedder.EMBEDDING_SIZE) { i -> (i + 1) * 0.01f }
        return List(frameCount) { idx ->
            FloatArray(CameraHandEmbedder.EMBEDDING_SIZE) { i -> base[i] + idx * deltaPerFrame }
        }
    }

    /**
     * Fast flick: large per-frame delta — simulates a rapid gesture.
     * [deltaPerFrame] is large enough that consecutive cosine similarities are low.
     */
    fun fastFlick(
        frameCount: Int = GestureEnrollmentCapture.CAPTURE_FRAMES,
        deltaPerFrame: Float = 0.1f
    ): List<FloatArray> {
        val base = FloatArray(CameraHandEmbedder.EMBEDDING_SIZE) { i -> (i + 1) * 0.02f }
        return List(frameCount) { idx ->
            FloatArray(CameraHandEmbedder.EMBEDDING_SIZE) { i ->
                base[i] + idx * deltaPerFrame * (if (i % 2 == 0) 1f else -1f)
            }
        }
    }

    /** Random noise: each frame is independently random — no temporal coherence. */
    fun randomNoise(
        frameCount: Int = GestureEnrollmentCapture.CAPTURE_FRAMES,
        seed: Long = 42L
    ): List<FloatArray> {
        val rng = java.util.Random(seed)
        return List(frameCount) {
            FloatArray(CameraHandEmbedder.EMBEDDING_SIZE) { rng.nextFloat() }
        }
    }

    /**
     * Single frame that looks like an Open_Palm — used for anchor validation tests.
     * The actual embedding content is arbitrary; anchor validation tests the gesture
     * label/confidence path, not the embedding content.
     */
    fun openPalmFrame(): FloatArray =
        FloatArray(CameraHandEmbedder.EMBEDDING_SIZE) { i -> 0.5f - i * 0.001f }

    /** Frame that looks like a non-palm gesture (e.g. Closed_Fist). */
    fun nonPalmFrame(): FloatArray =
        FloatArray(CameraHandEmbedder.EMBEDDING_SIZE) { i -> i * 0.002f }
}
