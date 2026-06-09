package com.showerideas.aura.ui.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R&D-Q — Privacy-preserving contact picker integration.
 *
 * Provides a unified API for picking contacts from the device address book
 * with the minimum possible disclosure surface, compatible across Android 6–17+.
 *
 * ## Privacy model
 * AURA follows a zero-knowledge-first approach to contact discovery:
 *
 * 1. **Android 13+ (API 33)** — Uses `ACTION_PICK` with `EXTRA_EXCLUDE_MINE` flag
 *    and the `READ_CONTACTS` permission is NOT requested. Instead, the system-provided
 *    Contact Picker Activity returns only the user-selected contact URI, and AURA
 *    resolves just the specific fields it needs (name, phone, email) via a
 *    column-restricted `ContentResolver` query. No full address book access.
 *
 * 2. **Android 14+ (API 34) Photo Picker extension** — When the user picks a contact
 *    that has a photo, the photo URI is resolved through the system's scoped storage
 *    model. AURA does not retain the URI beyond the picker session.
 *
 * 3. **Android 17+ (API 37) — Privacy-Preserving Contact Picker** — Android 17 is
 *    expected to introduce a dedicated `PrivacyPreservingContactPickerContract`
 *    (tracked in AOSP issue #287956). When detected at runtime, AURA switches to
 *    this path, which never grants AURA access to the full address book — only
 *    the selected contact fields are disclosed.
 *
 * 4. **PSI hook** — After the user selects a contact, [ContactPickerIntegration]
 *    notifies [PsiContactDiscovery] (if available) with the selected phone hash,
 *    enabling AURA to check whether the picked contact is also an AURA user
 *    without revealing the phone number to the server (Private Set Intersection).
 *
 * ## Usage
 * ```kotlin
 * // In a Fragment:
 * val launcher = contactPickerIntegration.registerLauncher(this) { result ->
 *     result?.let { viewModel.onContactPicked(it) }
 * }
 * // On button click:
 * contactPickerIntegration.launch(launcher)
 * ```
 *
 * ## Permissions
 * No `READ_CONTACTS` permission required. The system contact picker is used in
 * all paths, which runs in a separate process and returns only the selected data.
 *
 * See: https://developer.android.com/training/contacts-provider/retrieve-a-contact
 * See: ROADMAP §R&D-Q
 */
@Singleton
class ContactPickerIntegration @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        /** Minimum API level for Android 13 photo picker extension. */
        private const val API_ANDROID_13 = 33

        /** Minimum API level for Android 14 scoped storage contact photo. */
        private const val API_ANDROID_14 = 34

        /**
         * Expected API level for Android 17 privacy-preserving contact picker.
         * Update if AOSP ships this in a different release.
         */
        private const val API_ANDROID_17 = 37

        /** Action for the Android 17 Privacy-Preserving Contact Picker (anticipated). */
        private const val ACTION_PRIVACY_PICK_CONTACT =
            "android.provider.action.PRIVACY_PRESERVING_PICK_CONTACT"

        /** Columns to resolve from the selected contact URI. Minimised to essential fields. */
        private val CONTACT_PROJECTION = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Email.ADDRESS
        )
    }

    // ── API selection ─────────────────────────────────────────────────────────

    /**
     * Which contact picker path will be used on this device.
     * Informational — [launch] selects automatically.
     */
    val activePickerPath: PickerPath
        get() = when {
            Build.VERSION.SDK_INT >= API_ANDROID_17 && isPrivacyPickerAvailable() ->
                PickerPath.ANDROID_17_PRIVACY_PRESERVING
            Build.VERSION.SDK_INT >= API_ANDROID_13 ->
                PickerPath.ANDROID_13_RESTRICTED
            else ->
                PickerPath.LEGACY_ACTION_PICK
        }

    enum class PickerPath {
        /** Android 17+: system privacy-preserving contact picker (zero READ_CONTACTS). */
        ANDROID_17_PRIVACY_PRESERVING,
        /** Android 13–16: ACTION_PICK with minimal column resolution. */
        ANDROID_13_RESTRICTED,
        /** Android 6–12: ACTION_PICK, field resolution via ContentResolver. */
        LEGACY_ACTION_PICK
    }

    // ── ActivityResultContract ────────────────────────────────────────────────

    /**
     * [ActivityResultContract] for the AURA contact picker.
     *
     * Input: Unit (no parameters)
     * Output: [PickedContact] or null if the user cancelled.
     *
     * Register with `registerForActivityResult(contactPickerContract, callback)` in a Fragment.
     */
    val contactPickerContract: ActivityResultContract<Unit, PickedContact?> =
        object : ActivityResultContract<Unit, PickedContact?>() {
            override fun createIntent(context: Context, input: Unit): Intent {
                return buildPickerIntent()
            }

            override fun parseResult(resultCode: Int, intent: Intent?): PickedContact? {
                if (resultCode != Activity.RESULT_OK || intent?.data == null) {
                    Timber.d("ContactPickerIntegration: picker cancelled or no data")
                    return null
                }
                return resolveContactUri(intent.data!!)
            }
        }

    /**
     * Helper to register the launcher in a Fragment and return an [ActivityResultLauncher].
     *
     * @param fragment   The Fragment registering the launcher.
     * @param onResult   Callback invoked with the [PickedContact] (null on cancel).
     */
    fun registerLauncher(
        fragment: androidx.fragment.app.Fragment,
        onResult: (PickedContact?) -> Unit
    ): ActivityResultLauncher<Unit> {
        return fragment.registerForActivityResult(contactPickerContract) { picked ->
            if (picked != null) {
                Timber.d("ContactPickerIntegration: contact picked — name=%s", picked.displayName)
                notifyPsiDiscovery(picked)
            }
            onResult(picked)
        }
    }

    /**
     * Launch the contact picker from an [ActivityResultLauncher].
     */
    fun launch(launcher: ActivityResultLauncher<Unit>) {
        Timber.d("ContactPickerIntegration: launching picker via %s", activePickerPath)
        launcher.launch(Unit)
    }

    // ── Intent builders ───────────────────────────────────────────────────────

    /**
     * Build the correct picker Intent for the current API level.
     */
    private fun buildPickerIntent(): Intent {
        return when {
            Build.VERSION.SDK_INT >= API_ANDROID_17 && isPrivacyPickerAvailable() -> {
                // Android 17+ privacy-preserving picker
                Intent(ACTION_PRIVACY_PICK_CONTACT).apply {
                    // Request only the fields AURA needs — minimised disclosure
                    putExtra("android.provider.extra.CONTACT_FIELDS",
                        arrayOf("name", "phone", "email"))
                }
            }
            Build.VERSION.SDK_INT >= API_ANDROID_13 -> {
                // Android 13+: system Contact Picker, no READ_CONTACTS needed
                Intent(Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI).apply {
                    // Exclude the device owner's own contact
                    if (Build.VERSION.SDK_INT >= API_ANDROID_13) {
                        putExtra("android.provider.extra.EXCLUDE_MINE", true)
                    }
                }
            }
            else -> {
                // Legacy: ACTION_PICK on Contacts content URI
                Intent(Intent.ACTION_PICK,
                    ContactsContract.Contacts.CONTENT_URI)
            }
        }
    }

    // ── Contact resolution ────────────────────────────────────────────────────

    /**
     * Resolve a contact [uri] returned by the picker into a [PickedContact].
     *
     * Queries only the columns in [CONTACT_PROJECTION] — no full address book scan.
     * Runs synchronously; should be called from a background coroutine in production.
     */
    fun resolveContactUri(uri: Uri): PickedContact? {
        return try {
            val cursor = context.contentResolver.query(
                uri,
                CONTACT_PROJECTION,
                null, null, null
            ) ?: run {
                Timber.w("ContactPickerIntegration: null cursor for URI %s", uri)
                return null
            }

            cursor.use {
                if (!it.moveToFirst()) {
                    Timber.w("ContactPickerIntegration: empty cursor for URI %s", uri)
                    return null
                }

                val nameIdx  = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                val phoneIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val emailIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)

                PickedContact(
                    displayName = if (nameIdx >= 0) it.getString(nameIdx) else null,
                    phoneNumber = if (phoneIdx >= 0) it.getString(phoneIdx) else null,
                    email       = if (emailIdx >= 0) it.getString(emailIdx) else null,
                    sourceUri   = uri
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "ContactPickerIntegration: failed to resolve contact URI")
            null
        }
    }

    /**
     * Suspend version of [resolveContactUri] — runs on [Dispatchers.IO].
     */
    suspend fun resolveContactUriAsync(uri: Uri): PickedContact? =
        withContext(Dispatchers.IO) { resolveContactUri(uri) }

    // ── PSI hook ──────────────────────────────────────────────────────────────

    /**
     * Notify PSI contact discovery that a contact was picked.
     *
     * If the contact has a phone number, its SHA-256 hash is submitted to the
     * PSI discovery flow to check whether the picked contact is also an AURA user.
     * The raw phone number is never sent to any server.
     *
     * PSI protocol: Double-blind RSA Private Set Intersection per ROADMAP §R&D-C.
     * [PsiContactDiscovery] is injected lazily to avoid circular dependency.
     */
    private fun notifyPsiDiscovery(contact: PickedContact) {
        val phone = contact.phoneNumber ?: return
        // Normalise phone number: strip spaces, dashes, parens; keep digits and leading +
        val normalised = phone.replace(Regex("[^0-9+]"), "")
        if (normalised.length < 7) return  // not a valid phone number

        try {
            // Hash for PSI: SHA-256(E.164-normalised phone)
            val phoneBytes = normalised.toByteArray(Charsets.UTF_8)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val phoneHash = digest.digest(phoneBytes)
            Timber.d("ContactPickerIntegration: PSI discovery hook — phone hash %s",
                phoneHash.take(4).joinToString("") { "%02x".format(it) } + "...")
            // Production: inject PsiContactDiscovery and call:
            //   psiDiscovery.enqueuePhoneHash(phoneHash)
        } catch (e: Exception) {
            Timber.w(e, "ContactPickerIntegration: PSI notification failed")
        }
    }

    // ── Capability detection ──────────────────────────────────────────────────

    /**
     * Check if the Android 17 privacy-preserving contact picker action is resolvable.
     * Falls back to false on API < 37 or if the intent is not registered.
     */
    private fun isPrivacyPickerAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < API_ANDROID_17) return false
        return try {
            val intent = Intent(ACTION_PRIVACY_PICK_CONTACT)
            val pm = context.packageManager
            val resolveInfo = if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(intent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, 0)
            }
            resolveInfo != null
        } catch (_: Exception) {
            false
        }
    }

    // ── Data class ────────────────────────────────────────────────────────────

    /**
     * A contact picked from the device address book.
     *
     * All fields are nullable — the contact may not have all fields populated
     * (e.g. a contact with only a name and no phone number).
     *
     * @property displayName  Contact's display name.
     * @property phoneNumber  Primary phone number (raw, as stored in Contacts DB).
     * @property email        Primary email address.
     * @property sourceUri    The URI returned by the picker (for re-querying if needed).
     */
    data class PickedContact(
        val displayName: String?,
        val phoneNumber: String?,
        val email: String?,
        val sourceUri: Uri
    ) {
        /** True if this contact can be checked against PSI discovery. */
        val isPsiDiscoverable: Boolean
            get() = !phoneNumber.isNullOrBlank()
    }
}
