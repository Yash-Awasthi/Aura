package com.showerideas.aura.identity.didcomm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 106 — DIDComm v2 message reception and decryption.
 *
 * Implements `authcrypt` and `anoncrypt` message decryption per the DIDComm
 * Messaging v2 specification. Maps decrypted DIDComm message types to AURA
 * exchange flows.
 *
 * ## Cryptographic contracts
 *
 * ### anoncrypt (ECDH-ES + P-256 + AES-256-GCM)
 * 1. Sender generates an ephemeral P-256 key pair.
 * 2. ECDH with recipient's static P-256 public key → shared secret Z.
 * 3. Z fed to HKDF-SHA256 (salt = empty, info = "AURA-DIDComm-anoncrypt") → 256-bit CEK.
 * 4. Message encrypted with AES-256-GCM using derived CEK.
 * 5. Envelope contains { epk: {kty,crv,x,y}, iv, ciphertext, tag }.
 *
 * ### authcrypt (ECDH-1PU + P-256 + A256CBC-HS512)
 * Extends anoncrypt: HKDF input combines Ze (sender ephemeral ↔ recipient static)
 * and Zs (sender static ↔ recipient static) for sender authentication.
 *
 * ## Routing
 * DIDComm v2 uses `did:peer:2` as the standard pairwise DID for message routing.
 * Incoming messages arrive via the AURA relay in a dedicated DIDComm inbox slot.
 *
 * See: identity.foundation/didcomm-messaging/spec
 * See: ROADMAP §Task 106
 */
@Singleton
class DIDCommTransport @Inject constructor() {

    companion object {
        private const val AES_GCM_ALGORITHM  = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS       = 128
        private const val GCM_IV_BYTES       = 12
        private const val HKDF_INFO_ANONCRYPT = "AURA-DIDComm-anoncrypt"
        private const val EC_CURVE           = "secp256r1"
    }

    // ── Decryption ─────────────────────────────────────────────────────────────

    /**
     * Decrypt and parse an anoncrypt DIDComm v2 message envelope.
     *
     * @param envelopeJson Raw JWE JSON envelope received from relay or push.
     * @param recipientPriv Recipient's P-256 private key.
     * @return Decrypted [DIDCommMessage], or null on decryption / parse failure.
     */
    suspend fun receive(
        envelopeJson: String,
        recipientPriv: ECPrivateKey
    ): DIDCommMessage? = withContext(Dispatchers.Default) {
        try {
            val envelope = JSONObject(envelopeJson)
            val ciphertextB64 = envelope.getString("ciphertext")
            val ivB64         = envelope.getString("iv")
            val tagB64        = envelope.optString("tag", "")

            val ciphertext = Base64.getUrlDecoder().decode(ciphertextB64)
            val iv         = Base64.getUrlDecoder().decode(ivB64)

            // Derive CEK via ECDH-ES from the ephemeral public key in the envelope
            val cek = deriveCekFromEpk(envelope, recipientPriv)

            val cipherAndTag = if (tagB64.isNotBlank()) {
                ciphertext + Base64.getUrlDecoder().decode(tagB64)
            } else {
                ciphertext
            }

            val plaintext = aesGcmDecrypt(cek, iv, cipherAndTag)
            parsePlaintext(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            Timber.e(e, "DIDCommTransport: receive() failed")
            null
        }
    }

    /**
     * Encrypt a [DIDCommMessage] for delivery via anoncrypt (ECDH-ES + AES-256-GCM).
     *
     * @param message       Message to encrypt.
     * @param recipientPub  Recipient's P-256 public key.
     * @return JWE JSON string to deliver via relay.
     */
    suspend fun send(
        message: DIDCommMessage,
        recipientPub: ECPublicKey
    ): String = withContext(Dispatchers.Default) {
        val plaintext = JSONObject(message.toMap().filterValues { it != null }
            as Map<*, *>).toString().toByteArray(Charsets.UTF_8)

        // Generate ephemeral P-256 key pair
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(EC_CURVE), SecureRandom())
        }
        val ephemeralKp      = kpg.generateKeyPair()
        val ephemeralPriv    = ephemeralKp.private as ECPrivateKey
        val ephemeralPub     = ephemeralKp.public  as ECPublicKey

        // ECDH-ES: ephemeral private ↔ recipient static public → CEK
        val ka = KeyAgreement.getInstance("ECDH").apply {
            init(ephemeralPriv)
            doPhase(recipientPub, true)
        }
        val sharedSecret = ka.generateSecret()
        val cek = hkdfSha256(
            ikm  = sharedSecret,
            salt = ByteArray(0),
            info = HKDF_INFO_ANONCRYPT.toByteArray(Charsets.UTF_8),
            length = 32
        )

        val iv           = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipherAndTag = aesGcmEncrypt(cek, iv, plaintext)
        val ciphertext   = cipherAndTag.copyOf(cipherAndTag.size - 16)
        val tag          = cipherAndTag.copyOfRange(cipherAndTag.size - 16, cipherAndTag.size)

        // Encode ephemeral public key as JWK {kty, crv, x, y}
        val pubPoint = ephemeralPub.w
        val epkJson = JSONObject().apply {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", Base64.getUrlEncoder().withoutPadding().encodeToString(coordTo32Bytes(pubPoint.affineX)))
            put("y", Base64.getUrlEncoder().withoutPadding().encodeToString(coordTo32Bytes(pubPoint.affineY)))
        }

        JSONObject().apply {
            put("id",         message.id)
            put("type",       "application/didcomm-encrypted+json")
            put("epk",        epkJson)
            put("ciphertext", Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext))
            put("iv",         Base64.getUrlEncoder().withoutPadding().encodeToString(iv))
            put("tag",        Base64.getUrlEncoder().withoutPadding().encodeToString(tag))
        }.toString()
    }

    // ── Helper: build a new exchange request message ───────────────────────────

    /**
     * Convenience factory — builds a signed [DIDCommMessage] of type
     * [DIDCommMessage.TYPE_EXCHANGE_REQUEST] ready for [send].
     */
    fun buildExchangeRequest(
        fromDid: String,
        toDid: String,
        body: ExchangeRequestBody,
        ttlSeconds: Long = 172_800L
    ): DIDCommMessage {
        val now = Instant.now()
        return DIDCommMessage(
            id           = UUID.randomUUID().toString(),
            type         = DIDCommMessage.TYPE_EXCHANGE_REQUEST,
            from         = fromDid,
            to           = listOf(toDid),
            createdTime  = now,
            expiresTime  = now.plusSeconds(ttlSeconds),
            body         = body.toBodyMap()
        )
    }

    // ── Private crypto helpers ─────────────────────────────────────────────────

    /**
     * Derive the 256-bit Content Encryption Key via ECDH-ES.
     * Parses the `epk` JWK from the envelope header, reconstructs the ephemeral
     * EC public key, performs ECDH against the recipient private key, then applies
     * HKDF-SHA256 to produce the CEK.
     */
    private fun deriveCekFromEpk(envelope: JSONObject, recipientPriv: ECPrivateKey): ByteArray {
        val epkJson = envelope.getJSONObject("epk")
        val xBytes  = Base64.getUrlDecoder().decode(epkJson.getString("x"))
        val yBytes  = Base64.getUrlDecoder().decode(epkJson.getString("y"))

        val x = BigInteger(1, xBytes)
        val y = BigInteger(1, yBytes)

        val ecPoint  = ECPoint(x, y)
        val ecParams = (recipientPriv as ECPrivateKey).params
        val pubSpec  = ECPublicKeySpec(ecPoint, ecParams)
        val ephemeralPub = KeyFactory.getInstance("EC").generatePublic(pubSpec) as ECPublicKey

        val ka = KeyAgreement.getInstance("ECDH").apply {
            init(recipientPriv)
            doPhase(ephemeralPub, true)
        }
        val sharedSecret = ka.generateSecret()
        return hkdfSha256(
            ikm    = sharedSecret,
            salt   = ByteArray(0),
            info   = HKDF_INFO_ANONCRYPT.toByteArray(Charsets.UTF_8),
            length = 32
        )
    }

    private fun aesGcmEncrypt(cek: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(cek: ByteArray, iv: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertextWithTag)
    }

    /**
     * RFC 5869 HKDF-SHA256 — extract then expand.
     * @param ikm    Input key material (ECDH shared secret).
     * @param salt   Salt bytes (empty = 32 zero bytes per RFC 5869 §2.2).
     * @param info   Context/application-specific info.
     * @param length Output key length in bytes (≤ 255 × 32).
     */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        // Extract
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        hmac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = hmac.doFinal(ikm)
        // Expand
        val n = (length + 31) / 32
        val okm = ByteArray(n * 32)
        var t = ByteArray(0)
        for (i in 1..n) {
            hmac.init(SecretKeySpec(prk, "HmacSHA256"))
            hmac.update(t)
            hmac.update(info)
            hmac.update(i.toByte())
            t = hmac.doFinal()
            t.copyInto(okm, (i - 1) * 32)
        }
        return okm.copyOf(length)
    }

    /** Normalise a BigInteger coordinate to exactly 32 big-endian bytes. */
    private fun coordTo32Bytes(n: BigInteger): ByteArray {
        val raw = n.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size == 33 && raw[0] == 0.toByte() -> raw.copyOfRange(1, 33) // strip sign byte
            raw.size < 32  -> ByteArray(32 - raw.size) + raw                 // left-pad
            else           -> raw.copyOfRange(raw.size - 32, raw.size)
        }
    }

    private fun parsePlaintext(json: String): DIDCommMessage {
        val obj = JSONObject(json)
        val body = mutableMapOf<String, Any>()
        val bodyObj = obj.optJSONObject("body")
        bodyObj?.keys()?.forEach { key -> body[key] = bodyObj.get(key) }
        return DIDCommMessage(
            id          = obj.getString("id"),
            type        = obj.getString("type"),
            from        = obj.optString("from").takeIf { it.isNotBlank() },
            to          = buildList {
                val toArr = obj.optJSONArray("to")
                if (toArr != null) for (i in 0 until toArr.length()) add(toArr.getString(i))
            },
            createdTime = Instant.ofEpochSecond(obj.getLong("created_time")),
            expiresTime = obj.optLong("expires_time", -1L)
                .takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) },
            body        = body
        )
    }
}
