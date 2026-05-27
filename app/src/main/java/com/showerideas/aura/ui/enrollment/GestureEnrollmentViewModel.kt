package com.showerideas.aura.ui.enrollment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.showerideas.aura.auth.CameraHandEmbedder
import com.showerideas.aura.auth.enrollment.DualBoneGraphTracker
import com.showerideas.aura.auth.enrollment.EnrollmentCaptureState
import com.showerideas.aura.auth.enrollment.GestureDescriptorStore
import com.showerideas.aura.auth.enrollment.GestureEnrollmentCapture
import com.showerideas.aura.auth.enrollment.GestureVerificationEngine
import com.showerideas.aura.auth.enrollment.VerificationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Task 72 — ViewModel bridging [GestureEnrollmentFragment] with the enrollment pipeline.
 *
 * Owns the enrollment coroutine scope. Exposes three StateFlows:
 * - [captureState]: current [EnrollmentCaptureState] for UI feedback
 * - [enrollmentStatus]: overall enrollment lifecycle
 * - [verificationResult]: optional confirmation verification result
 *
 * Survives configuration changes — capture continues in background if the
 * fragment is recreated mid-capture.
 *
 * See: ROADMAP §Task 72
 */
@HiltViewModel
class GestureEnrollmentViewModel @Inject constructor(
    private val capture: GestureEnrollmentCapture,
    private val tracker: DualBoneGraphTracker,
    private val store: GestureDescriptorStore,
    private val engine: GestureVerificationEngine,
    private val cameraEmbedder: CameraHandEmbedder
) : ViewModel() {

    // ── State flows ──────────────────────────────────────────────────────────

    private val _captureState = MutableStateFlow<EnrollmentCaptureState>(
        EnrollmentCaptureState.WaitingForPalm
    )
    val captureState: StateFlow<EnrollmentCaptureState> = _captureState.asStateFlow()

    private val _enrollmentStatus = MutableStateFlow<EnrollmentStatus>(EnrollmentStatus.Idle)
    val enrollmentStatus: StateFlow<EnrollmentStatus> = _enrollmentStatus.asStateFlow()

    private val _verificationResult = MutableStateFlow<VerificationResult?>(null)
    val verificationResult: StateFlow<VerificationResult?> = _verificationResult.asStateFlow()

    // ── Enrollment orchestration ─────────────────────────────────────────────

    /**
     * Start the enrollment capture sequence.
     * Safe to call multiple times — resets the capture state each time.
     * Called from fragment in response to user tapping "Start".
     */
    fun startEnrollment() {
        capture.reset()
        _captureState.value = EnrollmentCaptureState.WaitingForPalm
        _enrollmentStatus.value = EnrollmentStatus.Enrolling
        _verificationResult.value = null
        viewModelScope.launch { collectCameraFrames() }
    }

    /**
     * Called by the fragment each camera frame during enrollment.
     * Passes the frame to [GestureEnrollmentCapture] and propagates state updates.
     */
    fun onCameraFrame(gestureLabel: String?, confidence: Float, embedding: FloatArray?) {
        if (_enrollmentStatus.value != EnrollmentStatus.Enrolling) return
        val state = capture.processFrame(gestureLabel, confidence, embedding) ?: return
        _captureState.value = state
        if (state is EnrollmentCaptureState.CaptureComplete) {
            handleCaptureComplete(state.frames)
        }
    }

    /**
     * Trigger a re-verification to let the user confirm their gesture was saved correctly.
     * Called from the success state when user taps "Try it now".
     */
    fun startVerification() {
        capture.reset()
        _captureState.value = EnrollmentCaptureState.WaitingForPalm
        _enrollmentStatus.value = EnrollmentStatus.Verifying
        _verificationResult.value = null
    }

    fun onVerificationFrame(gestureLabel: String?, confidence: Float, embedding: FloatArray?) {
        if (_enrollmentStatus.value != EnrollmentStatus.Verifying) return
        val state = capture.processFrame(gestureLabel, confidence, embedding) ?: return
        _captureState.value = state
        if (state is EnrollmentCaptureState.CaptureComplete) {
            handleVerificationComplete(state.frames)
        }
    }

    /**
     * Reset to idle — called when user navigates away or explicitly cancels.
     */
    fun reset() {
        capture.reset()
        _captureState.value = EnrollmentCaptureState.WaitingForPalm
        _enrollmentStatus.value = EnrollmentStatus.Idle
        _verificationResult.value = null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun collectCameraFrames() {
        viewModelScope.launch {
            cameraEmbedder.gestureState.collect { state ->
                if (_enrollmentStatus.value != EnrollmentStatus.Enrolling &&
                    _enrollmentStatus.value != EnrollmentStatus.Verifying) return@collect
                val (label, confidence, embedding) = when (state) {
                    is CameraHandEmbedder.GestureState.Detecting ->
                        Triple(state.gesture.name, state.stability, state.embedding)
                    is CameraHandEmbedder.GestureState.Stable ->
                        Triple(state.gesture.name, 1f, state.embedding)
                    else -> Triple(null, 0f, null)
                }
                if (_enrollmentStatus.value == EnrollmentStatus.Enrolling) {
                    onCameraFrame(label, confidence, embedding)
                } else if (_enrollmentStatus.value == EnrollmentStatus.Verifying) {
                    onVerificationFrame(label, confidence, embedding)
                }
            }
        }
    }

    private fun handleCaptureComplete(frames: List<FloatArray>) {
        viewModelScope.launch {
            try {
                val (descA, descB) = tracker.extract(frames)
                store.save(descA, descB)
                _enrollmentStatus.value = EnrollmentStatus.Success
                Timber.d("GestureEnrollmentViewModel: enrollment saved successfully")
            } catch (e: Exception) {
                Timber.e(e, "GestureEnrollmentViewModel: extraction/save failed")
                _enrollmentStatus.value = EnrollmentStatus.Failed("Extraction error — please try again")
            }
        }
    }

    private fun handleVerificationComplete(frames: List<FloatArray>) {
        viewModelScope.launch {
            val result = engine.verify(frames)
            _verificationResult.value = result
            _enrollmentStatus.value = if (result is VerificationResult.Success) {
                EnrollmentStatus.Success
            } else {
                EnrollmentStatus.Failed("Gesture not recognised — try again")
            }
            Timber.d("GestureEnrollmentViewModel: verification result = $result")
        }
    }
}

/** Lifecycle state for the overall enrollment flow. */
sealed class EnrollmentStatus {
    object Idle : EnrollmentStatus()
    object Enrolling : EnrollmentStatus()
    object Verifying : EnrollmentStatus()
    object Success : EnrollmentStatus()
    data class Failed(val reason: String) : EnrollmentStatus()
}
