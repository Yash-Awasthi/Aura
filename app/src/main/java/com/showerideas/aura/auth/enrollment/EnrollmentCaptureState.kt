package com.showerideas.aura.auth.enrollment

/**
 * Task 67 — State machine for the 2-second temporal gesture enrollment capture.
 *
 * States are emitted sequentially:
 *  WaitingForPalm → (anchor confirmed) → Capturing(0.0→1.0) → CaptureComplete
 *  WaitingForPalm → (anchor absent at frame 0-2) → AnchorFailed → WaitingForPalm (retry)
 *
 * The 2-second capture clock starts at anchor confirmation, not at camera start.
 * See ROADMAP §Task 67 and GESTURE_AUTH.md §4 for architecture constraints.
 */
sealed class EnrollmentCaptureState {
    /** Anchor not yet confirmed — waiting for open-palm landmark in the first 3 frames. */
    object WaitingForPalm : EnrollmentCaptureState()

    /**
     * Anchor confirmed, capture window is running.
     * [progressFraction] is [frameCount / 60f], monotonically increasing from 0.0 to 1.0.
     */
    data class Capturing(val progressFraction: Float) : EnrollmentCaptureState()

    /**
     * Open-palm landmark was absent in one of the first 3 anchor frames.
     * The UI should show a retry prompt: "Start with your palm open and flat."
     * After 1.5 s the capture resets to [WaitingForPalm] automatically.
     */
    object AnchorFailed : EnrollmentCaptureState()

    /**
     * All 60 frames collected successfully.
     * [frames] contains exactly 60 normalised 63-float landmark embeddings.
     * Downstream: hand to [DualBoneGraphTracker.extract].
     */
    data class CaptureComplete(val frames: List<FloatArray>) : EnrollmentCaptureState()
}
