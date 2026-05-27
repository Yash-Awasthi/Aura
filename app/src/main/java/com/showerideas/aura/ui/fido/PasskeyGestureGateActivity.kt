package com.showerideas.aura.ui.fido

import android.app.Activity
import android.os.Bundle
import androidx.activity.viewModels
import com.showerideas.aura.R
import com.showerideas.aura.auth.enrollment.VerificationResult
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * Task 83/84 — Gesture gate activity for FIDO2 passkey operations.
 *
 * Launched by [AuraCredentialProviderService] via PendingIntent when:
 *   - ACTION_CREATE_PASSKEY: user is creating a new passkey
 *   - ACTION_GET_PASSKEY: user is asserting an existing passkey
 *
 * Both paths require gesture verification via [GestureEnrollmentViewModel]
 * before the FIDO2 operation proceeds. On verification success:
 *   - CREATE: delegates to [PasskeyRepository.createPasskey]
 *   - GET: delegates to [PasskeyRepository.signAssertion]
 *
 * On failure or cancellation, RESULT_CANCELED is returned.
 */
@AndroidEntryPoint
class PasskeyGestureGateActivity : Activity() {

    companion object {
        const val ACTION_CREATE_PASSKEY = "com.showerideas.aura.fido.CREATE_PASSKEY"
        const val ACTION_GET_PASSKEY    = "com.showerideas.aura.fido.GET_PASSKEY"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("PasskeyGestureGateActivity: action=${intent.action}")
        // Route to gesture verification — on success, perform the FIDO2 operation
        // Implementation: embed GestureEnrollmentFragment for verification-only mode
        // then call the appropriate PasskeyRepository method.
        // For now, gate passes through; full wiring done in T84.
        when (intent.action) {
            ACTION_CREATE_PASSKEY -> handleCreatePasskey()
            ACTION_GET_PASSKEY    -> handleGetPasskey()
            else -> { setResult(RESULT_CANCELED); finish() }
        }
    }

    private fun handleCreatePasskey() {
        // TODO(T84): wire gesture verification → PasskeyRepository.createPasskey
        // Stub: set RESULT_OK after gesture verification
        Timber.d("PasskeyGestureGateActivity: handleCreatePasskey stub")
        setResult(RESULT_CANCELED)  // replaced by gesture verification in T84
        finish()
    }

    private fun handleGetPasskey() {
        // TODO(T84): wire gesture verification → PasskeyRepository.signAssertion
        Timber.d("PasskeyGestureGateActivity: handleGetPasskey stub")
        setResult(RESULT_CANCELED)
        finish()
    }
}
