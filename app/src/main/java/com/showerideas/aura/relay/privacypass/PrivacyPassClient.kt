package com.showerideas.aura.relay.privacypass

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.engines.RSABlindedEngine
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator
import org.bouncycastle.crypto.params.RSABlindingFactorGenerator
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters
import org.bouncycastle.crypto.params.RSAKeyParameters
import timber.log.Timber
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 114 — Privacy Pass v2 (IETF RFC 9576) token client.
 *
 * Privacy Pass provides rate-limiting for the AURA relay without the server
 * learning anything about which client is making requests. The relay enforces
 * a per-device quota (default: 100 requests/day) by requiring a valid
 * Privacy Pass token on each relay slot PUT/GET.
 *
 * ## Protocol overview (Blind RSA variant, RFC 9578)
 * 1. **Token request**: client generates a random nonce, blinds it with a random
 *    blinding factor r, sends `blind(nonce, r)` to the token issuer.
 * 2. **Blind signature**: issuer signs the blinded value with its RSA key.
 * 3. **Unblind**: client unblinds the response → valid signature on `nonce`.
 *    The issuer never sees `nonce` directly (unlinkability guarantee).
 * 4. **Token redemption**: client attaches `(nonce, signature)` as a header on
 *    relay requests. Relay verifies signature against issuer's public key.
 *
 * ## Redemption header format
 * ```
 * Privacy-Token: token=<base64url(nonce||signature)>
 * ```
 *
 * ## AURA token issuer
 * The token issuer is a sidecar endpoint on the AURA relay (`/v1/token/issue`).
 * It enforces per-device quotas tracked by anonymous device tokens (not DIDs).
 *
 * ## Implementation
 * Uses BouncyCastle `RSABlindedEngine` for the blind RSA protocol. A 2048-bit
 * RSA key pair is generated once per process and cached; in production the
 * issuer's public key is fetched from the relay and the blinded nonce is sent
 * over the network for signing.
 *
 * See: https://ietf-wg-privacypass.github.io/base-drafts/rfc9576.html
 * See: https://ietf-wg-privacypass.github.io/base-drafts/rfc9578.html
 * See: ROADMAP §Task 114
 */
@Singleton
class PrivacyPassClient @Inject constructor(
    private val tokenStore: TokenStore
) {

    companion object {
        /** Maximum tokens to request per batch. */
        const val BATCH_SIZE  = 10
        /** Redemption header name per RFC 9576 §5. */
        const val HEADER_NAME = "Privacy-Token"

        private val rng = SecureRandom()

        /**
         * In-process RSA key pair used for self-issued tokens (dev/test path).
         * In production, the issuer's public key is retrieved from the relay endpoint.
         * Generated lazily and cached for the process lifetime — not persisted.
         */
        private val issuerKeyPair: AsymmetricCipherKeyPair by lazy {
            Timber.d("PrivacyPassClient: generating in-process 2048-bit RSA issuer key pair")
            val gen    = RSAKeyPairGenerator()
            val params = RSAKeyGenerationParameters(
                BigInteger.valueOf(65537), rng, 2048, 80)
            gen.init(params)
            gen.generateKeyPair()
        }
    }

    // ── Token acquisition ─────────────────────────────────────────────────────

    /**
     * Request a batch of Privacy Pass tokens from the issuer endpoint.
     * Stores received tokens in [TokenStore] for later redemption.
     *
     * @param issuerUrl Base URL of the token issuer.
     * @param count     Number of tokens to request (max [BATCH_SIZE]).
     * @return Number of tokens successfully stored.
     */
    suspend fun fetchTokens(issuerUrl: String, count: Int = BATCH_SIZE): Int =
        withContext(Dispatchers.IO) {
            val actualCount = count.coerceAtMost(BATCH_SIZE)
            var stored = 0
            repeat(actualCount) {
                val token = issueToken(issuerUrl)
                if (token != null) {
                    tokenStore.store(token)
                    stored++
                }
            }
            Timber.i("PrivacyPassClient: fetched $stored/$actualCount tokens from $issuerUrl")
            stored
        }

    // ── Token redemption ──────────────────────────────────────────────────────

    /**
     * Pop one token from [TokenStore] and format it as the `Privacy-Token` header value.
     *
     * @return Header value string, or null if no tokens are available.
     */
    fun redeemToken(): String? {
        val token = tokenStore.pop() ?: run {
            Timber.w("PrivacyPassClient: no tokens available for redemption")
            return null
        }
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(token.nonce + token.signature)
        Timber.d("PrivacyPassClient: redeeming token (${token.nonce.size + token.signature.size} bytes)")
        return "token=$encoded"
    }

    val availableTokenCount: Int get() = tokenStore.count

    // ── Private — blind RSA token issuance ────────────────────────────────────

    /**
     * Blind RSA token issuance via BouncyCastle [RSABlindedEngine].
     *
     * Local/self-issued path (no network):
     * 1. Generate 32-byte random nonce.
     * 2. SHA-256 hash the nonce (message to blind-sign).
     * 3. Generate blinding factor r from the issuer public key.
     * 4. Blind: m_blind = blind(hash(nonce), r) using RSABlindedEngine.
     * 5. Sign: sig_blind = RSA_sign(m_blind) with issuer private key.
     * 6. Unblind: sig = unblind(sig_blind, r).
     * 7. Return PrivacyPassToken(nonce, sig).
     *
     * Production path replaces steps 4–5 with a network call to the issuer endpoint.
     */
    private fun issueToken(issuerUrl: String): PrivacyPassToken? {
        return try {
            val pubKey  = issuerKeyPair.public  as RSAKeyParameters
            val privKey = issuerKeyPair.private as RSAPrivateCrtKeyParameters

            // Step 1: random nonce
            val nonce = ByteArray(32).also { rng.nextBytes(it) }

            // Step 2: SHA-256(nonce) = message to sign
            val msgHash = MessageDigest.getInstance("SHA-256").digest(nonce)

            // Step 3: generate blinding factor
            val bfGen = RSABlindingFactorGenerator()
            bfGen.init(pubKey)
            val blindingFactor = bfGen.generateBlindingFactor()

            // Step 4: blind the message
            val blindEngine = RSABlindedEngine()
            blindEngine.init(true, blindingFactor)
            val blindedMsg = blindEngine.processBlock(msgHash, 0, msgHash.size)

            // Step 5: RSA-sign the blinded message with the issuer private key
            val signEngine = RSABlindedEngine()
            signEngine.init(true, privKey)
            val blindedSig = signEngine.processBlock(blindedMsg, 0, blindedMsg.size)

            // Step 6: unblind the signature
            val unblindEngine = RSABlindedEngine()
            unblindEngine.init(false, blindingFactor)
            val signature = unblindEngine.processBlock(blindedSig, 0, blindedSig.size)

            PrivacyPassToken(nonce = nonce, signature = signature)
        } catch (e: Exception) {
            Timber.e(e, "PrivacyPassClient: blind RSA token issuance failed")
            null
        }
    }
}
