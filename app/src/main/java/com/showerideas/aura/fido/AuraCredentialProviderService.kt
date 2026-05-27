package com.showerideas.aura.fido

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.showerideas.aura.ui.fido.PasskeyGestureGateActivity
import timber.log.Timber

/**
 * Task 83 — AURA CredentialManager provider service.
 *
 * Registers AURA as a passkey CredentialProvider on Android 14+ (API 34).
 * When the Android system queries for passkey credentials:
 *   - [onBeginGetCredentialRequest]: returns matching AURA passkeys from [PasskeyRepository].
 *   - [onBeginCreateCredentialRequest]: returns a CreateEntry that routes through gesture verification.
 *
 * AURA appears in Android Settings → Passwords & Accounts → Credential providers.
 *
 * See: developer.android.com/training/sign-in/passkeys
 * See: ROADMAP §Task 83
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)  // API 34 — CredentialProviderService
class AuraCredentialProviderService : CredentialProviderService() {

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        Timber.d("AuraCredentialProviderService: onBeginGetCredentialRequest")
        try {
            val entries = buildCredentialEntries(request)
            callback.onResult(BeginGetCredentialResponse(entries))
        } catch (e: Exception) {
            Timber.e(e, "AuraCredentialProviderService: get credential failed")
            callback.onResult(BeginGetCredentialResponse(emptyList()))
        }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        Timber.d("AuraCredentialProviderService: onBeginCreateCredentialRequest")
        try {
            val pendingIntent = buildGestureGatePendingIntent(
                PasskeyGestureGateActivity.ACTION_CREATE_PASSKEY
            )
            val createEntry = CreateEntry(
                accountName = "AURA",
                pendingIntent = pendingIntent
            )
            callback.onResult(BeginCreateCredentialResponse(listOf(createEntry)))
        } catch (e: Exception) {
            Timber.e(e, "AuraCredentialProviderService: create credential failed")
            callback.onResult(BeginCreateCredentialResponse(emptyList()))
        }
    }

    override fun onClearCredentialStateRequest(
        request: androidx.credentials.provider.ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Unit, ClearCredentialException>
    ) {
        // No session state to clear — AURA passkeys are per-relying-party
        callback.onResult(Unit)
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun buildCredentialEntries(
        request: BeginGetCredentialRequest
    ): List<CredentialEntry> {
        // The passkey repository lookup is deferred to the PendingIntent activity
        // to keep the service lightweight. Filter by RP IDs from the request options.
        val pendingIntent = buildGestureGatePendingIntent(
            PasskeyGestureGateActivity.ACTION_GET_PASSKEY
        )
        return request.beginGetCredentialOptions.mapNotNull { option ->
            when (option) {
                is androidx.credentials.provider.BeginGetPublicKeyCredentialOption -> {
                    PublicKeyCredentialEntry(
                        context = this,
                        username = "AURA Passkey",
                        pendingIntent = pendingIntent,
                        beginGetPublicKeyCredentialOption = option
                    )
                }
                else -> null
            }
        }
    }

    private fun buildGestureGatePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PasskeyGestureGateActivity::class.java).apply {
            this.action = action
        }
        return PendingIntent.getActivity(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
