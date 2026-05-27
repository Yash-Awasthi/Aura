package com.showerideas.aura.auth.enrollment

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 69 — Encrypted dual-descriptor persistence.
 *
 * Persists both [GestureDescriptor] objects from [DualBoneGraphTracker] into
 * [EncryptedSharedPreferences] under the existing `aura_gesture_prefs` file.
 *
 * ## Storage schema (version 2)
 * | Key                           | Type   | Content                         |
 * |-------------------------------|--------|---------------------------------|
 * | gesture_descriptor_a_centroid | String | Window A centroid (63 floats)   |
 * | gesture_descriptor_a_motion   | String | Window A motionProfile (44 floats)|
 * | gesture_descriptor_b_centroid | String | Window B centroid (63 floats)   |
 * | gesture_descriptor_b_motion   | String | Window B motionProfile (44 floats)|
 * | gesture_descriptor_version    | Int    | Schema version (2)              |
 * | gesture_pattern_id            | String | UUID per enrollment             |
 *
 * ## Backward compatibility
 * If version < 2 (or absent), [hasLegacyEnrollment] returns true.
 * The user must re-enroll — no automatic migration from single embedding.
 *
 * See: ROADMAP §Task 69
 */
@Singleton
class GestureDescriptorStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        // New schema (v2) keys
        private const val KEY_A_CENTROID = "gesture_descriptor_a_centroid"
        private const val KEY_A_MOTION   = "gesture_descriptor_a_motion"
        private const val KEY_B_CENTROID = "gesture_descriptor_b_centroid"
        private const val KEY_B_MOTION   = "gesture_descriptor_b_motion"
        private const val KEY_VERSION    = "gesture_descriptor_version"
        private const val KEY_PATTERN_ID = "gesture_pattern_id"

        // Legacy key (read-only for detection)
        private const val LEGACY_KEY_FEATURE_VECTOR = "gesture_feature_vector"

        private const val CURRENT_VERSION = 2
        private const val EXPECTED_CENTROID_SIZE     = 63
        private const val EXPECTED_MOTION_SIZE       = 44
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "aura_gesture_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Persist both descriptors. Overwrites any existing v2 enrollment.
     * Generates a new UUID pattern ID for this enrollment.
     */
    fun save(descriptorA: GestureDescriptor, descriptorB: GestureDescriptor) {
        val patternId = UUID.randomUUID().toString()
        prefs.edit()
            .putString(KEY_A_CENTROID, descriptorA.centroid.joinToString(","))
            .putString(KEY_A_MOTION,   descriptorA.motionProfile.joinToString(","))
            .putString(KEY_B_CENTROID, descriptorB.centroid.joinToString(","))
            .putString(KEY_B_MOTION,   descriptorB.motionProfile.joinToString(","))
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .putString(KEY_PATTERN_ID, patternId)
            .apply()
        Timber.d("GestureDescriptorStore: saved v2 enrollment (id=$patternId)")
    }

    /**
     * Load both stored descriptors. Returns null if the store is empty or corrupt.
     * Callers should check [hasValidEnrollment] first.
     */
    fun load(): Pair<GestureDescriptor, GestureDescriptor>? {
        val version = prefs.getInt(KEY_VERSION, -1)
        if (version < CURRENT_VERSION) {
            Timber.w("GestureDescriptorStore: schema v$version < current v$CURRENT_VERSION — re-enrollment required")
            return null
        }
        return try {
            val aCentroid = parseFloats(prefs.getString(KEY_A_CENTROID, null), EXPECTED_CENTROID_SIZE) ?: return null
            val aMotion   = parseFloats(prefs.getString(KEY_A_MOTION,   null), EXPECTED_MOTION_SIZE)   ?: return null
            val bCentroid = parseFloats(prefs.getString(KEY_B_CENTROID, null), EXPECTED_CENTROID_SIZE) ?: return null
            val bMotion   = parseFloats(prefs.getString(KEY_B_MOTION,   null), EXPECTED_MOTION_SIZE)   ?: return null

            val descriptorA = GestureDescriptor(aCentroid, aMotion, GestureDescriptor.WindowTag.A)
            val descriptorB = GestureDescriptor(bCentroid, bMotion, GestureDescriptor.WindowTag.B)
            Pair(descriptorA, descriptorB)
        } catch (e: Exception) {
            Timber.e(e, "GestureDescriptorStore: load failed — store corrupt?")
            null
        }
    }

    /**
     * Returns true when all four descriptor keys are present and parse cleanly.
     */
    fun hasValidEnrollment(): Boolean {
        val version = prefs.getInt(KEY_VERSION, -1)
        if (version < CURRENT_VERSION) return false
        return parseFloats(prefs.getString(KEY_A_CENTROID, null), EXPECTED_CENTROID_SIZE) != null &&
               parseFloats(prefs.getString(KEY_A_MOTION,   null), EXPECTED_MOTION_SIZE)   != null &&
               parseFloats(prefs.getString(KEY_B_CENTROID, null), EXPECTED_CENTROID_SIZE) != null &&
               parseFloats(prefs.getString(KEY_B_MOTION,   null), EXPECTED_MOTION_SIZE)   != null
    }

    /**
     * Returns true if the old single-embedding schema is present (version < 2 or legacy key set).
     * When true, the user should be prompted to re-enroll.
     */
    fun hasLegacyEnrollment(): Boolean {
        val version = prefs.getInt(KEY_VERSION, -1)
        val hasLegacyKey = prefs.getString(LEGACY_KEY_FEATURE_VECTOR, null) != null
        return hasLegacyKey && version < CURRENT_VERSION
    }

    /** Clear all gesture enrollment data (v2 + legacy). */
    fun clear() {
        prefs.edit()
            .remove(KEY_A_CENTROID)
            .remove(KEY_A_MOTION)
            .remove(KEY_B_CENTROID)
            .remove(KEY_B_MOTION)
            .remove(KEY_VERSION)
            .remove(KEY_PATTERN_ID)
            .apply()
        Timber.d("GestureDescriptorStore: cleared")
    }

    /** Retrieve the per-enrollment UUID. Null if no enrollment exists. */
    fun patternId(): String? = prefs.getString(KEY_PATTERN_ID, null)

    private fun parseFloats(raw: String?, expectedSize: Int): FloatArray? {
        if (raw == null) return null
        val floats = raw.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        return if (floats.size == expectedSize) floats else null
    }
}
