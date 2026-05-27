package com.showerideas.aura.enterprise.mpc

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Task 112 — Pure-Kotlin Shamir Secret Sharing over GF(2^256).
 *
 * Implements 2-of-3 threshold secret sharing for the enterprise audit export
 * signing key. Two of three designated administrator AURA devices must co-sign
 * an audit export to produce a valid signature — a single compromised admin device
 * cannot unilaterally forge an audit log.
 *
 * ## Algorithm
 * Shamir's Secret Sharing over a prime field (AURA uses the 256-bit Mersenne-adjacent
 * prime P = 2^256 − 189 for field operations — same field as secp256k1 order).
 *
 *   1. Secret S is the 256-bit audit signing key.
 *   2. Choose random a₁ ∈ [1, P).
 *   3. Polynomial: f(x) = S + a₁·x  (mod P).
 *   4. Shares: (1, f(1)), (2, f(2)), (3, f(3)).
 *   5. Reconstruction: Lagrange interpolation on any 2 shares → f(0) = S.
 *
 * ## Security
 * - Any single share leaks no information about S (information-theoretic security).
 * - Shares are distributed to 3 admin devices during a key ceremony in
 *   [AuditSigningCoordinator.performKeyCeremony].
 * - The master key S is immediately destroyed after share generation.
 *
 * ## Limitations
 * - Threshold is fixed at (2, 3). Parameterised (t, n) is a follow-on.
 * - No verifiable secret sharing (VSS) — share consistency is not cryptographically
 *   verified during reconstruction. VSS requires Pedersen commitments (follow-on).
 *
 * See: Shamir (1979) "How to Share a Secret"
 * See: ROADMAP §Task 112
 */
object ShamirSecretSharing {

    /**
     * Prime field modulus: 2^256 − 189 (256-bit, prime).
     * Fits exactly in 32 bytes; larger than secp256k1 order.
     */
    private val PRIME: BigInteger = BigInteger.TWO.pow(256)
        .subtract(BigInteger.valueOf(189))

    const val THRESHOLD = 2
    const val TOTAL_SHARES = 3

    /**
     * Split a 256-bit [secret] into [TOTAL_SHARES] shares with a [THRESHOLD]-of-[TOTAL_SHARES]
     * reconstruction threshold.
     *
     * @param secret 32-byte secret to split.
     * @return List of [TOTAL_SHARES] [Share] objects. Indices are 1-based.
     * @throws IllegalArgumentException if secret is not exactly 32 bytes.
     */
    fun split(secret: ByteArray): List<Share> {
        require(secret.size == 32) { "ShamirSecretSharing: secret must be 32 bytes, got ${secret.size}" }
        val rng = SecureRandom()
        val s = BigInteger(1, secret).mod(PRIME)

        // Degree-1 polynomial: f(x) = s + a1*x (mod P)
        val a1 = BigInteger(256, rng).mod(PRIME)

        return (1..TOTAL_SHARES).map { x ->
            val xBig = BigInteger.valueOf(x.toLong())
            val y = s.add(a1.multiply(xBig)).mod(PRIME)
            Share(index = x, value = y.toByteArray().let { bytes ->
                // Normalise to exactly 32 bytes (BigInteger.toByteArray may add 0x00 prefix)
                if (bytes.size >= 32) bytes.takeLast(32).toByteArray()
                else ByteArray(32 - bytes.size) + bytes
            })
        }
    }

    /**
     * Reconstruct the secret from any [THRESHOLD] or more shares.
     *
     * @param shares At least [THRESHOLD] shares (more is fine; extras are ignored).
     * @return Reconstructed 32-byte secret.
     * @throws IllegalArgumentException if fewer than [THRESHOLD] shares are provided.
     */
    fun reconstruct(shares: List<Share>): ByteArray {
        require(shares.size >= THRESHOLD) {
            "ShamirSecretSharing: need ≥ $THRESHOLD shares for reconstruction, got ${shares.size}"
        }
        val subset = shares.take(THRESHOLD)
        var secret = BigInteger.ZERO

        for (i in subset.indices) {
            val xi = BigInteger.valueOf(subset[i].index.toLong())
            val yi = BigInteger(1, subset[i].value).mod(PRIME)
            var lagrangeNum = BigInteger.ONE
            var lagrangeDen = BigInteger.ONE
            for (j in subset.indices) {
                if (i == j) continue
                val xj = BigInteger.valueOf(subset[j].index.toLong())
                lagrangeNum = lagrangeNum.multiply(xj.negate()).mod(PRIME)
                lagrangeDen = lagrangeDen.multiply(xi.subtract(xj)).mod(PRIME)
            }
            val lagrange = lagrangeNum.multiply(lagrangeDen.modInverse(PRIME)).mod(PRIME)
            secret = secret.add(yi.multiply(lagrange)).mod(PRIME)
        }

        val bytes = secret.toByteArray()
        return if (bytes.size >= 32) bytes.takeLast(32).toByteArray()
        else ByteArray(32 - bytes.size) + bytes
    }

    /**
     * A single Shamir share: a (index, value) pair over [PRIME].
     * [index] is the x-coordinate (1..3); [value] is the y-coordinate as 32 bytes.
     */
    data class Share(val index: Int, val value: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Share && index == other.index && value.contentEquals(other.value)
        override fun hashCode(): Int = index * 31 + value.contentHashCode()
    }
}
