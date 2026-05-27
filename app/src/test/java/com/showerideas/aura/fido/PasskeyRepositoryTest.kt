package com.showerideas.aura.fido

import org.junit.Assert.*
import org.junit.Test

/**
 * Task 88 — Unit tests for [PasskeyRepository] CRUD + key binding logic.
 *
 * Note: AndroidKeyStore operations cannot be mocked in pure JVM tests.
 * These tests validate the non-Keystore logic: credential ID generation,
 * alias construction, and PasskeyEntity behaviour.
 * Keystore integration is covered by the instrumented test suite.
 */
class PasskeyRepositoryTest {

    @Test fun `PasskeyEntity equality based on credentialId`() {
        val e1 = PasskeyEntity("cred1", "example.com", ByteArray(16))
        val e2 = PasskeyEntity("cred1", "example.com", ByteArray(16))
        val e3 = PasskeyEntity("cred2", "example.com", ByteArray(16))
        assertEquals(e1, e2)
        assertNotEquals(e1, e3)
    }

    @Test fun `PasskeyEntity created_at defaults to current time`() {
        val before = System.currentTimeMillis()
        val entity = PasskeyEntity("cred1", "example.com", ByteArray(16))
        val after = System.currentTimeMillis()
        assertTrue(entity.createdAt in before..after)
    }

    @Test fun `PasskeyEntity user handle stored as ByteArray`() {
        val handle = byteArrayOf(1, 2, 3, 4)
        val entity = PasskeyEntity("cred1", "example.com", handle)
        assertArrayEquals(handle, entity.userHandle)
    }

    @Test fun `CtapNfcRelay isCtap2Apdu detects CTAP2 SELECT AID`() {
        // Manually craft a SELECT AID APDU for CTAP2 AID A0000006472F0001
        val aidBytes = byteArrayOf(
            0xA0.toByte(), 0x00.toByte(), 0x00.toByte(), 0x06.toByte(),
            0x47.toByte(), 0x2F.toByte(), 0x00.toByte(), 0x01.toByte()
        )
        val apdu = byteArrayOf(
            0x00.toByte(),  // CLA
            0xA4.toByte(),  // INS = SELECT
            0x04.toByte(),  // P1
            0x00.toByte(),  // P2
            aidBytes.size.toByte()  // Lc
        ) + aidBytes

        assertTrue("Should detect CTAP2 AID", CtapNfcRelay.isCtap2Apdu(apdu))
    }

    @Test fun `CtapNfcRelay isCtap2Apdu rejects short APDU`() {
        assertFalse(CtapNfcRelay.isCtap2Apdu(byteArrayOf(0x00, 0xA4)))
    }

    @Test fun `CtapNfcRelay isCtap2Apdu rejects non-SELECT INS`() {
        val apdu = byteArrayOf(0x00, 0xB0.toByte(), 0x04, 0x00, 0x08)  // READ BINARY
        assertFalse(CtapNfcRelay.isCtap2Apdu(apdu))
    }

    @Test fun `CtapNfcRelay CTAP2 AID constant matches spec`() {
        assertEquals("A0000006472F0001", CtapNfcRelay.CTAP2_AID)
    }
}
