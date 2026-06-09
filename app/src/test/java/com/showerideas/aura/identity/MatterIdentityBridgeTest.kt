package com.showerideas.aura.identity

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

/**
 * Unit tests for [MatterIdentityBridge].
 *
 * Covers:
 * - Deterministic key derivation (same inputs → same key)
 * - Key isolation (different fabric IDs → different keys)
 * - Epoch isolation (different epochs → different keys)
 * - NOC issuance and validation
 * - Key rotation produces different key and NOC
 * - NOC DER is non-empty and parseable
 */
class MatterIdentityBridgeTest {

    private lateinit var bridge: MatterIdentityBridge
    private lateinit var caKeyPair: java.security.KeyPair

    @Before
    fun setUp() {
        bridge = MatterIdentityBridge()
        // Generate a test CA key pair (P-256) for NOC signing
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        caKeyPair = kpg.generateKeyPair()
    }

    // ── Key derivation ────────────────────────────────────────────────────────

    @Test
    fun `same identity key and fabric ID produces same Matter key pair`() {
        val identityKeyBytes = ByteArray(32) { it.toByte() }
        val fabricId = 0xDEADBEEF12345678L

        val kp1 = bridge.deriveMatterKeyPair(identityKeyBytes, fabricId)
        val kp2 = bridge.deriveMatterKeyPair(identityKeyBytes, fabricId)

        assertArrayEquals(
            "Same inputs must yield same public key",
            kp1.public.encoded,
            kp2.public.encoded
        )
        assertArrayEquals(
            "Same inputs must yield same private key",
            kp1.private.encoded,
            kp2.private.encoded
        )
    }

    @Test
    fun `different fabric IDs produce different Matter keys`() {
        val identityKeyBytes = ByteArray(32) { it.toByte() }

        val kp1 = bridge.deriveMatterKeyPair(identityKeyBytes, fabricId = 0x0000000000000001L)
        val kp2 = bridge.deriveMatterKeyPair(identityKeyBytes, fabricId = 0x0000000000000002L)

        assertFalse(
            "Different fabric IDs must produce different keys",
            kp1.public.encoded.contentEquals(kp2.public.encoded)
        )
    }

    @Test
    fun `different identity keys produce different Matter keys for same fabric`() {
        val ikm1 = ByteArray(32) { it.toByte() }
        val ikm2 = ByteArray(32) { (it + 1).toByte() }
        val fabricId = 0xCAFEBABE00000001L

        val kp1 = bridge.deriveMatterKeyPair(ikm1, fabricId)
        val kp2 = bridge.deriveMatterKeyPair(ikm2, fabricId)

        assertFalse(
            "Different identity keys must produce different Matter keys",
            kp1.public.encoded.contentEquals(kp2.public.encoded)
        )
    }

    @Test
    fun `derived key pair is P-256`() {
        val identityKeyBytes = ByteArray(32) { 0x42.toByte() }
        val kp = bridge.deriveMatterKeyPair(identityKeyBytes, fabricId = 1L)

        val algo = kp.public.algorithm
        assertEquals("Derived key must be EC (P-256)", "EC", algo)
    }

    // ── NOC issuance ──────────────────────────────────────────────────────────

    @Test
    fun `issueNoc returns non-empty DER bytes`() {
        val ikm = ByteArray(32) { 0xAB.toByte() }
        val matterKp = bridge.deriveMatterKeyPair(ikm, fabricId = 42L)
        val nocDer = bridge.issueNoc(matterKp, caKeyPair, nodeId = 1001L, fabricId = 42L)

        assertTrue("NOC DER must be non-empty", nocDer.isNotEmpty())
        assertTrue("NOC DER must be at least 100 bytes", nocDer.size >= 100)
    }

    @Test
    fun `NOC DER starts with X509 SEQUENCE marker`() {
        val ikm = ByteArray(32) { 0xCC.toByte() }
        val matterKp = bridge.deriveMatterKeyPair(ikm, fabricId = 99L)
        val nocDer = bridge.issueNoc(matterKp, caKeyPair, nodeId = 5L, fabricId = 99L)

        // X.509 DER always starts with 0x30 (SEQUENCE tag)
        assertEquals("NOC DER must start with SEQUENCE tag 0x30", 0x30.toByte(), nocDer[0])
    }

    @Test
    fun `NOC validates against CA cert`() {
        val ikm = ByteArray(32) { 0x55.toByte() }
        val nodeId = 12345678L
        val fabricId = 0xFABFAB0000001L
        val matterKp = bridge.deriveMatterKeyPair(ikm, fabricId)
        val nocDer = bridge.issueNoc(matterKp, caKeyPair, nodeId, fabricId)

        // Generate self-signed CA cert for validation
        val caCert = generateSelfSignedCert(caKeyPair)
        val valid = bridge.validateNoc(nocDer, caCert, nodeId)

        assertTrue("NOC must validate against the CA that signed it", valid)
    }

    @Test
    fun `NOC with wrong nodeId fails validation`() {
        val ikm = ByteArray(32) { 0x77.toByte() }
        val nodeId = 999999L
        val fabricId = 0x1234567890ABL
        val matterKp = bridge.deriveMatterKeyPair(ikm, fabricId)
        val nocDer = bridge.issueNoc(matterKp, caKeyPair, nodeId, fabricId)

        val caCert = generateSelfSignedCert(caKeyPair)
        val valid = bridge.validateNoc(nocDer, caCert, nodeId = 888888L)  // wrong nodeId

        assertFalse("NOC validation must fail when nodeId does not match subject", valid)
    }

    @Test
    fun `different epochs produce different NOCs`() {
        val ikm = ByteArray(32) { 0x33.toByte() }
        val nodeId = 7777L
        val fabricId = 0xF00DF00D00000001L

        val kp0 = bridge.deriveMatterKeyPair(ikm, fabricId)
        val noc0 = bridge.issueNoc(kp0, caKeyPair, nodeId, fabricId, epoch = 0)
        val noc1 = bridge.issueNoc(kp0, caKeyPair, nodeId, fabricId, epoch = 1)

        assertFalse("NOCs with different epochs must differ", noc0.contentEquals(noc1))
    }

    // ── Key rotation ──────────────────────────────────────────────────────────

    @Test
    fun `rotateMatterKey returns different key pair than initial derivation`() {
        val ikm = ByteArray(32) { 0x11.toByte() }
        val nodeId = 42L
        val fabricId = 0xABCDEF0123456789L

        val initialKp = bridge.deriveMatterKeyPair(ikm, fabricId)
        val rotation = bridge.rotateMatterKey(ikm, caKeyPair, nodeId, fabricId, newEpoch = 1)

        assertFalse(
            "Rotated key must differ from initial key",
            initialKp.public.encoded.contentEquals(rotation.newKeyPair.public.encoded)
        )
    }

    @Test
    fun `rotateMatterKey returns non-empty NOC`() {
        val ikm = ByteArray(32) { 0x22.toByte() }
        val rotation = bridge.rotateMatterKey(ikm, caKeyPair, nodeId = 1L, fabricId = 2L, newEpoch = 1)

        assertTrue("Rotation NOC must be non-empty", rotation.newNocDer.isNotEmpty())
        assertEquals("Rotation epoch must be 1", 1, rotation.epoch)
    }

    @Test
    fun `rotateMatterKey epoch 1 and epoch 2 produce different keys`() {
        val ikm = ByteArray(32) { 0x44.toByte() }
        val r1 = bridge.rotateMatterKey(ikm, caKeyPair, nodeId = 1L, fabricId = 1L, newEpoch = 1)
        val r2 = bridge.rotateMatterKey(ikm, caKeyPair, nodeId = 1L, fabricId = 1L, newEpoch = 2)

        assertFalse(
            "Different epochs must produce different rotated keys",
            r1.newKeyPair.public.encoded.contentEquals(r2.newKeyPair.public.encoded)
        )
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Generate a minimal self-signed X.509 certificate for [keyPair] to use as
     * a CA cert in NOC validation tests.
     */
    private fun generateSelfSignedCert(keyPair: java.security.KeyPair): java.security.cert.X509Certificate {
        val now = System.currentTimeMillis()
        val subject = org.bouncycastle.asn1.x500.X500Name("CN=AURARootCA, O=AURA, OU=Matter-RootCA")
        val spki = org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)

        val certBuilder = org.bouncycastle.cert.X509v3CertificateBuilder(
            subject,
            java.math.BigInteger.valueOf(now),
            java.util.Date(now - 1000),
            java.util.Date(now + 365L * 24 * 60 * 60 * 1000),
            subject,
            spki
        )

        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA")
            .build(keyPair.private)

        return org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .getCertificate(certBuilder.build(signer))
    }
}
