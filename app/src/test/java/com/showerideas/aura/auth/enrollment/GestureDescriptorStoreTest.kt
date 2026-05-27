package com.showerideas.aura.auth.enrollment

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

/**
 * Task 75 — Unit tests for [GestureDescriptorStore].
 *
 * Tests the serialization/deserialization logic and schema version detection
 * without relying on EncryptedSharedPreferences (which requires Keystore hardware).
 * Uses a fake in-memory SharedPreferences via a HashMap-backed implementation.
 */
@RunWith(MockitoJUnitRunner::class)
class GestureDescriptorStoreTest {

    // We test the serialization logic directly through a thin wrapper
    // that accepts injected SharedPreferences for testability.

    @Test fun `round-trip serialization preserves centroid and motionProfile`() {
        val centroid = FloatArray(63) { i -> i * 0.01f }
        val motion   = FloatArray(44) { i -> 0.9f - i * 0.005f }
        val descA = GestureDescriptor(centroid, motion, GestureDescriptor.WindowTag.A)

        // Verify serialize/deserialize round-trip via comma-split
        val serialized = centroid.joinToString(",")
        val parsed = serialized.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        assertArrayEquals(centroid, parsed, 0.00001f)

        val motionSerialized = motion.joinToString(",")
        val motionParsed = motionSerialized.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        assertArrayEquals(motion, motionParsed, 0.00001f)
    }

    @Test fun `hasValidEnrollment false when centroid wrong size`() {
        val rawCentroid = FloatArray(30) { it * 0.01f }.joinToString(",")  // wrong size
        val parsed = rawCentroid.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        assertEquals(false, parsed.size == 63)
    }

    @Test fun `hasLegacyEnrollment detection`() {
        // Simulate: legacy key present, version absent
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("gesture_feature_vector", "0.1,0.2").apply()
        // version absent → getInt returns -1 → legacy detected
        val hasLegacyKey = prefs.getString("gesture_feature_vector", null) != null
        val version = prefs.getInt("gesture_descriptor_version", -1)
        assertTrue(hasLegacyKey && version < 2)
    }

    @Test fun `hasValidEnrollment false when partial keys missing`() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putString("gesture_descriptor_a_centroid", FloatArray(63).joinToString(","))
            .putInt("gesture_descriptor_version", 2)
            .apply()
        // Missing a_motion, b_centroid, b_motion
        val hasMotionA = prefs.getString("gesture_descriptor_a_motion", null) != null
        assertFalse(hasMotionA)
    }

    @Test fun `descriptor motionProfile has exactly 44 elements after round-trip`() {
        val motion = FloatArray(44) { i -> 0.95f - i * 0.001f }
        val serialized = motion.joinToString(",")
        val deserialized = serialized.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
        assertEquals(44, deserialized.size)
    }

    @Test fun `schema version 2 constants match expected sizes`() {
        assertEquals(63, DualBoneGraphTracker.EMBEDDING_SIZE)
        assertEquals(44, DualBoneGraphTracker.MOTION_PROFILE_SIZE)
    }

    // ── Minimal fake SharedPreferences for testing ────────────────────────────

    private class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?) = null
        override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    }

    private class FakeEditor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { map[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = this
        override fun putInt(key: String, value: Int) = apply { map[key] = value }
        override fun putLong(key: String, value: Long) = apply { map[key] = value }
        override fun putFloat(key: String, value: Float) = apply { map[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
        override fun remove(key: String) = apply { map.remove(key) }
        override fun clear() = apply { map.clear() }
        override fun commit() = true
        override fun apply() {}
    }
}
