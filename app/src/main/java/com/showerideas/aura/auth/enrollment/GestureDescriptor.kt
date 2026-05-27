package com.showerideas.aura.auth.enrollment

/**
 * Task 68 — Compound temporal descriptor produced by [DualBoneGraphTracker].
 *
 * Each [GestureDescriptor] encapsulates the static shape component ([centroid],
 * 63 floats) and the temporal motion component ([motionProfile], 44 floats) of
 * one 45-frame window extracted from the 60-frame enrollment capture.
 *
 * Two descriptors are produced per enrollment: one for window A (frames 0–44)
 * and one for window B (frames 15–59). They are stored and matched independently.
 * Matching in [GestureVerificationEngine] operates on the concatenated 107-float
 * compound vector [centroid || motionProfile].
 *
 * @param centroid      Mean landmark vector across the window (63 floats).
 * @param motionProfile Frame-to-frame cosine delta sequence (44 floats for 45 frames).
 * @param windowTag     Which temporal window this descriptor represents.
 * @param capturedAtMs  Epoch ms when the enrollment capture occurred.
 */
data class GestureDescriptor(
    val centroid: FloatArray,          // 63 floats — static shape component
    val motionProfile: FloatArray,     // 44 floats — temporal motion component (N-1 deltas)
    val windowTag: WindowTag,
    val capturedAtMs: Long = System.currentTimeMillis()
) {
    enum class WindowTag { A, B }

    /** Compound 107-float vector used for cosine similarity matching. */
    fun compoundVector(): FloatArray = centroid + motionProfile

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GestureDescriptor) return false
        return centroid.contentEquals(other.centroid) &&
               motionProfile.contentEquals(other.motionProfile) &&
               windowTag == other.windowTag &&
               capturedAtMs == other.capturedAtMs
    }

    override fun hashCode(): Int {
        var result = centroid.contentHashCode()
        result = 31 * result + motionProfile.contentHashCode()
        result = 31 * result + windowTag.hashCode()
        result = 31 * result + capturedAtMs.hashCode()
        return result
    }
}

/** Kotlin operator for float array concatenation used in compoundVector(). */
private operator fun FloatArray.plus(other: FloatArray): FloatArray {
    val result = FloatArray(size + other.size)
    copyInto(result, 0)
    other.copyInto(result, size)
    return result
}
