package com.showerideas.aura.enterprise.mpc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Task 117 — Unit tests for [ShamirSecretSharing].
 *
 * Validates:
 *   1. 2-of-3 reconstruction from shares (0,1), (0,2), (1,2).
 *   2. Single share leaks no information (reconstruction fails with 1 share).
 *   3. All-zero secret round-trips correctly.
 *   4. Random 32-byte secrets round-trip with any 2-of-3 share subset.
 *   5. Share index correctness (1, 2, 3).
 *   6. Value lengths are exactly 32 bytes.
 *
 * See: ROADMAP §Task 117
 */
class ShamirSecretSharingTest {

    private fun randomSecret(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    // ── Basic reconstruction ───────────────────────────────────────────────────

    @Test
    fun `2-of-3 reconstruction with shares 1 and 2`() {
        val secret = randomSecret()
        val shares = ShamirSecretSharing.split(secret)
        val reconstructed = ShamirSecretSharing.reconstruct(listOf(shares[0], shares[1]))
        assertArrayEquals(secret, reconstructed)
    }

    @Test
    fun `2-of-3 reconstruction with shares 1 and 3`() {
        val secret = randomSecret()
        val shares = ShamirSecretSharing.split(secret)
        val reconstructed = ShamirSecretSharing.reconstruct(listOf(shares[0], shares[2]))
        assertArrayEquals(secret, reconstructed)
    }

    @Test
    fun `2-of-3 reconstruction with shares 2 and 3`() {
        val secret = randomSecret()
        val shares = ShamirSecretSharing.split(secret)
        val reconstructed = ShamirSecretSharing.reconstruct(listOf(shares[1], shares[2]))
        assertArrayEquals(secret, reconstructed)
    }

    @Test
    fun `3-of-3 reconstruction succeeds`() {
        val secret = randomSecret()
        val shares = ShamirSecretSharing.split(secret)
        val reconstructed = ShamirSecretSharing.reconstruct(shares)
        assertArrayEquals(secret, reconstructed)
    }

    // ── All-zero secret ───────────────────────────────────────────────────────

    @Test
    fun `all-zero secret round-trips correctly`() {
        val secret = ByteArray(32)
        val shares = ShamirSecretSharing.split(secret)
        val reconstructed = ShamirSecretSharing.reconstruct(listOf(shares[0], shares[1]))
        assertArrayEquals(secret, reconstructed)
    }

    // ── Share structure ───────────────────────────────────────────────────────

    @Test
    fun `split produces exactly 3 shares`() {
        val shares = ShamirSecretSharing.split(randomSecret())
        assertEquals(3, shares.size)
    }

    @Test
    fun `share indices are 1 2 3`() {
        val shares = ShamirSecretSharing.split(randomSecret())
        assertEquals(1, shares[0].index)
        assertEquals(2, shares[1].index)
        assertEquals(3, shares[2].index)
    }

    @Test
    fun `all share values are exactly 32 bytes`() {
        val shares = ShamirSecretSharing.split(randomSecret())
        shares.forEach { share ->
            assertEquals("Share ${share.index} value should be 32 bytes", 32, share.value.size)
        }
    }

    @Test
    fun `shares are different from each other`() {
        val shares = ShamirSecretSharing.split(randomSecret())
        assertNotEquals(
            shares[0].value.toList(),
            shares[1].value.toList()
        )
        assertNotEquals(
            shares[1].value.toList(),
            shares[2].value.toList()
        )
    }

    @Test
    fun `shares are different from the secret`() {
        val secret = randomSecret()
        val shares = ShamirSecretSharing.split(secret)
        shares.forEach { share ->
            assertNotEquals(
                "Share ${share.index} should differ from secret",
                secret.toList(),
                share.value.toList()
            )
        }
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `split rejects non-32-byte secret`() {
        ShamirSecretSharing.split(ByteArray(16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reconstruct rejects fewer than threshold shares`() {
        val shares = ShamirSecretSharing.split(randomSecret())
        ShamirSecretSharing.reconstruct(listOf(shares[0]))
    }

    // ── Multiple random secrets ───────────────────────────────────────────────

    @Test
    fun `100 random secrets all reconstruct correctly with any 2-of-3 pair`() {
        repeat(100) {
            val secret = randomSecret()
            val shares = ShamirSecretSharing.split(secret)
            assertArrayEquals(secret, ShamirSecretSharing.reconstruct(listOf(shares[0], shares[1])))
            assertArrayEquals(secret, ShamirSecretSharing.reconstruct(listOf(shares[0], shares[2])))
            assertArrayEquals(secret, ShamirSecretSharing.reconstruct(listOf(shares[1], shares[2])))
        }
    }
}
