package com.showerideas.aura.service

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for [SatelliteTransport] compression and encoding utilities.
 *
 * Tests run on JVM — satellite hardware/API is mocked or bypassed.
 * Validates:
 * - DEFLATE compress/decompress round-trip
 * - Base-91 encode/decode round-trip
 * - MTU checks for both backends
 * - Payload size budget compliance
 */
class SatelliteTransportTest {

    private lateinit var transport: SatelliteTransport

    @Before
    fun setUp() {
        transport = SatelliteTransport(mock<Context>())
    }

    // ── DEFLATE ───────────────────────────────────────────────────────────────

    @Test
    fun `DEFLATE round-trip preserves bytes`() {
        val original = "Hello AURA satellite".repeat(10).toByteArray()
        val compressed = transport.deflateCompress(original)
        val decompressed = transport.deflateDecompress(compressed)
        assertArrayEquals(original, decompressed)
    }

    @Test
    fun `DEFLATE compresses repetitive JSON payload`() {
        // Simulate an AURA contact card JSON (repetitive structure compresses well)
        val card = """{"did":"did:key:z6Mk...","name":"Alice Martin","phone":"+14155551001",
            |"email":"alice@example.com","sig":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}"""
            .trimMargin().repeat(1)
        val original = card.toByteArray()
        val compressed = transport.deflateCompress(original)
        assertTrue(
            "Compressed size ${compressed.size} should be < original ${original.size}",
            compressed.size < original.size
        )
    }

    @Test
    fun `DEFLATE compressed typical card fits in SAT_MTU`() {
        // Typical minimal AURA exchange card (~200 bytes JSON)
        val card = """{"did":"did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2","name":"Alice","phone":"+14155551001","email":"a@b.co"}"""
        val compressed = transport.deflateCompress(card.toByteArray())
        assertTrue(
            "Compressed card ${compressed.size} bytes should fit in SAT_MTU ${SatelliteTransport.SAT_MTU_BYTES}",
            compressed.size <= SatelliteTransport.SAT_MTU_BYTES
        )
    }

    @Test
    fun `DEFLATE empty input round-trips`() {
        val empty = ByteArray(0)
        val compressed = transport.deflateCompress(empty)
        val decompressed = transport.deflateDecompress(compressed)
        assertArrayEquals(empty, decompressed)
    }

    // ── Base-91 ───────────────────────────────────────────────────────────────

    @Test
    fun `Base91 round-trip preserves bytes for short payload`() {
        val original = "AURAexchange".toByteArray()
        val encoded = transport.base91Encode(original)
        val decoded = transport.base91Decode(encoded)
        assertArrayEquals(original, decoded)
    }

    @Test
    fun `Base91 round-trip preserves bytes for binary payload`() {
        val original = ByteArray(64) { it.toByte() }
        val encoded = transport.base91Encode(original)
        val decoded = transport.base91Decode(encoded)
        assertArrayEquals(original, decoded)
    }

    @Test
    fun `Base91 is more compact than Base64 for binary data`() {
        val data = ByteArray(90) { (it % 256).toByte() }
        val base91Len = transport.base91Encode(data).length
        val base64Len = android.util.Base64.encodeToString(data, android.util.Base64.DEFAULT).trimEnd().length
        assertTrue(
            "Base91 length $base91Len should be <= Base64 length $base64Len",
            base91Len <= base64Len
        )
    }

    @Test
    fun `Base91 encoded compressed card fits in Garmin MTU`() {
        val card = """{"did":"did:key:z6MkhaXgBZDvotDkL5257faiztiGiC2","name":"Alice","phone":"+14155551001","email":"a@b.co"}"""
        val compressed = transport.deflateCompress(card.toByteArray())
        val encoded = transport.base91Encode(compressed)
        assertTrue(
            "Base91 encoded card ${encoded.length} chars should fit in GARMIN_MTU ${SatelliteTransport.GARMIN_MTU_CHARS}",
            encoded.length <= SatelliteTransport.GARMIN_MTU_CHARS
        )
    }

    @Test
    fun `Base91 empty input encodes and decodes`() {
        val encoded = transport.base91Encode(ByteArray(0))
        val decoded = transport.base91Decode(encoded)
        assertArrayEquals(ByteArray(0), decoded)
    }

    // ── MTU constants ─────────────────────────────────────────────────────────

    @Test
    fun `SAT_MTU_BYTES is 140`() {
        assertEquals(140, SatelliteTransport.SAT_MTU_BYTES)
    }

    @Test
    fun `GARMIN_MTU_CHARS is 120`() {
        assertEquals(120, SatelliteTransport.GARMIN_MTU_CHARS)
    }

    // ── Availability (no satellite on JVM test host) ───────────────────────────

    @Test
    fun `isAvailable returns false without BuildConfig ENABLE_SATELLITE`() {
        // BuildConfig.ENABLE_SATELLITE defaults to false — transport should be unavailable
        assertFalse(transport.isAvailable)
    }
}
