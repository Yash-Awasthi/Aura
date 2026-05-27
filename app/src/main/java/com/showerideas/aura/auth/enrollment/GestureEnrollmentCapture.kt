package com.showerideas.aura.auth.enrollment

import com.showerideas.aura.auth.CameraHandEmbedder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject

/**
 * Task 67 — 2-second temporal gesture enrollment capture pipeline.
 *
 * Replaces the 12-frame consecutive-stability gate in [CameraHandEmbedder] for
 * enrollment flows. The capture window is fixed at exactly 2 seconds (60 frames
 * at 30 fps), preceded by an open-palm anchor validation.
 *
 * ## Capture sequence
 * 1. First 3 frames (anchor phase): MediaPipe must report Open_Palm with
 *    confidence ≥ [MIN_ANCHOR_CONFIDENCE] AND landmarks must be present.
 *    If either fails for any anchor frame → emit [EnrollmentCaptureState.AnchorFailed].
 * 2. On anchor confirmation, start the 60-frame capture window.
 * 3. Frames are accumulated. Dropped frames (null landmarks) are replaced with the
 *    previous frame's embedding (temporal continuity policy — no interpolation).
 * 4. At frame 60, emit [EnrollmentCaptureState.CaptureComplete] with the buffer.
 *
 * ## Integration
 * [GestureEnrollmentCapture] is not tied to the CameraX pipeline directly. It
 * consumes embeddings via [processFrame], which is driven by [CameraHandEmbedder]'s
 * [CameraHandEmbedder.GestureState] emissions. [GestureEnrollmentViewModel] wires
 * these together.
 *
 * See: ROADMAP §Task 67, GESTURE_AUTH.md §4
 */
class GestureEnrollmentCapture @Inject constructor() {

    companion object {
        /** Total frames in the 2-second capture window at 30 fps. */
        const val CAPTURE_FRAMES = 60
        /** First N frames used to validate the open-palm anchor. */
        private const val ANCHOR_FRAMES = 3
        /** Minimum MediaPipe gesture confidence to accept as Open_Palm anchor. */
        private const val MIN_ANCHOR_CONFIDENCE = 0.72f
        /** MediaPipe label for open-palm. */
        private const val OPEN_PALM_LABEL = "Open_Palm"
    }

    /** Tracks state across a single enrollment attempt. Reset via [reset]. */
    private enum class Phase { ANCHORING, CAPTURING, DONE }
    private var phase = Phase.ANCHORING
    private var anchorFramesChecked = 0
    private val frameBuffer = mutableListOf<FloatArray>()
    private var lastEmbedding: FloatArray? = null

    /**
     * Reset state for a fresh enrollment attempt (called after AnchorFailed retry).
     */
    fun reset() {
        phase = Phase.ANCHORING
        anchorFramesChecked = 0
        frameBuffer.clear()
        lastEmbedding = null
    }

    /**
     * Process a single camera frame. Returns the updated [EnrollmentCaptureState]
     * or null if the frame is a no-op (camera still searching, capture already done).
     *
     * @param gestureLabel  MediaPipe top gesture category name (e.g. "Open_Palm"). Null if no hand.
     * @param confidence    MediaPipe gesture confidence score. 0f if no gesture.
     * @param embedding     Normalised 63-float landmark embedding. Null if no hand detected.
     */
    fun processFrame(
        gestureLabel: String?,
        confidence: Float,
        embedding: FloatArray?
    ): EnrollmentCaptureState? {
        if (phase == Phase.DONE) return null

        return when (phase) {
            Phase.ANCHORING -> handleAnchorFrame(gestureLabel, confidence, embedding)
            Phase.CAPTURING -> handleCaptureFrame(embedding)
            Phase.DONE -> null
        }
    }

    private fun handleAnchorFrame(
        gestureLabel: String?,
        confidence: Float,
        embedding: FloatArray?
    ): EnrollmentCaptureState {
        val isOpenPalm = gestureLabel == OPEN_PALM_LABEL && confidence >= MIN_ANCHOR_CONFIDENCE
        val hasLandmarks = embedding != null && embedding.size == CameraHandEmbedder.EMBEDDING_SIZE

        if (!isOpenPalm || !hasLandmarks) {
            // Hard fail — do not partial-capture
            Timber.w("GestureEnrollmentCapture: anchor failed at frame $anchorFramesChecked " +
                     "(gesture=$gestureLabel confidence=%.2f hasLandmarks=$hasLandmarks)".format(confidence))
            phase = Phase.ANCHORING
            anchorFramesChecked = 0
            return EnrollmentCaptureState.AnchorFailed
        }

        anchorFramesChecked++
        Timber.d("GestureEnrollmentCapture: anchor frame $anchorFramesChecked/$ANCHOR_FRAMES OK")

        if (anchorFramesChecked >= ANCHOR_FRAMES) {
            // Anchor confirmed — start capture window
            phase = Phase.CAPTURING
            frameBuffer.clear()
            lastEmbedding = embedding
            Timber.d("GestureEnrollmentCapture: anchor confirmed, starting 2s capture")
            return EnrollmentCaptureState.Capturing(0f)
        }

        return EnrollmentCaptureState.WaitingForPalm
    }

    private fun handleCaptureFrame(embedding: FloatArray?): EnrollmentCaptureState {
        // Dropped frame policy: duplicate previous embedding
        val frame = if (embedding != null && embedding.size == CameraHandEmbedder.EMBEDDING_SIZE) {
            embedding.also { lastEmbedding = it }
        } else {
            lastEmbedding?.copyOf() ?: FloatArray(CameraHandEmbedder.EMBEDDING_SIZE)
        }

        frameBuffer.add(frame)
        val progress = frameBuffer.size.toFloat() / CAPTURE_FRAMES
        Timber.v("GestureEnrollmentCapture: frame ${frameBuffer.size}/$CAPTURE_FRAMES")

        return if (frameBuffer.size >= CAPTURE_FRAMES) {
            phase = Phase.DONE
            Timber.d("GestureEnrollmentCapture: capture complete (${frameBuffer.size} frames)")
            EnrollmentCaptureState.CaptureComplete(frameBuffer.toList())
        } else {
            EnrollmentCaptureState.Capturing(progress)
        }
    }

    /** Expose current frame count for monitoring. */
    fun capturedFrameCount(): Int = frameBuffer.size
}
