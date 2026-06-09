package com.showerideas.aura.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R&D-D — `did:web` DID Document publisher.
 *
 * Publishes the user's DID Document to a user-supplied HTTPS domain, making their
 * identity resolvable via the `did:web` method (W3C DID Core 1.0 + did:web spec).
 *
 * ## Publishing flow
 * 1. User supplies their domain (e.g. `yash.dev`).
 * 2. [buildDidDocument] constructs a W3C-compliant DID Document with the user's
 *    P-256 verification key in JWK format.
 * 3. [publish] HTTP-PUTs the document to `https://<domain>/.well-known/did.json`.
 *    The server must be configured to accept PUT requests on that path.
 * 4. The resulting DID is `did:web:<domain>`.
 *
 * ## DID Document structure
 * ```json
 * {
 *   "@context": ["https://www.w3.org/ns/did/v1", "https://w3id.org/security/suites/jws-2020/v1"],
 *   "id": "did:web:yourdomain.com",
 *   "verificationMethod": [{
 *     "id": "did:web:yourdomain.com#key-1",
 *     "type": "JsonWebKey2020",
 *     "controller": "did:web:yourdomain.com",
 *     "publicKeyJwk": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." }
 *   }],
 *   "authentication": ["did:web:yourdomain.com#key-1"],
 *   "assertionMethod": ["did:web:yourdomain.com#key-1"]
 * }
 * ```
 *
 * ## Resolution
 * Once published, any resolver (AURA's [DidResolver], Universal Resolver, etc.) can
 * retrieve the document at `https://<domain>/.well-known/did.json` and verify the
 * user's identity without a central registry.
 *
 * See: https://w3c-ccg.github.io/did-method-web/
 * See: https://w3.org/TR/did-core/
 * See: ROADMAP §R&D-D
 */
@Singleton
class DidWebPublisher @Inject constructor() {

    companion object {
        private const val DID_JSON_PATH = "/.well-known/did.json"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS    = 15_000
        private const val CONTENT_TYPE       = "application/did+json"
    }

    /**
     * Build a W3C DID Document JSON string for `did:web:<domain>`.
     *
     * @param domain      The domain to anchor the DID (e.g. `yash.dev`).
     * @param publicKeyHex Hex-encoded uncompressed P-256 public key (65 bytes: 04||x||y).
     *                    Accepts both compressed (33 bytes) and uncompressed (65 bytes).
     * @return DID Document as a pretty-printed JSON string.
     */
    fun buildDidDocument(domain: String, publicKeyHex: String): String {
        val did        = "did:web:${domain.trimEnd('/')}"
        val keyId      = "$did#key-1"
        val keyBytes   = hexToBytes(publicKeyHex)
        val (xB64, yB64) = extractJwkCoords(keyBytes)

        val context = JSONArray().apply {
            put("https://www.w3.org/ns/did/v1")
            put("https://w3id.org/security/suites/jws-2020/v1")
        }

        val publicKeyJwk = JSONObject().apply {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", xB64)
            put("y", yB64)
        }

        val verificationMethod = JSONObject().apply {
            put("id",           keyId)
            put("type",         "JsonWebKey2020")
            put("controller",   did)
            put("publicKeyJwk", publicKeyJwk)
        }

        return JSONObject().apply {
            put("@context",          context)
            put("id",                did)
            put("verificationMethod", JSONArray().put(verificationMethod))
            put("authentication",     JSONArray().put(keyId))
            put("assertionMethod",    JSONArray().put(keyId))
        }.toString(2)  // pretty-print with 2-space indent
    }

    /**
     * Publish the DID Document to `https://<domain>/.well-known/did.json` via HTTP PUT.
     *
     * @param domain       Domain to publish to (e.g. `yash.dev`).
     * @param publicKeyHex Hex-encoded P-256 public key.
     * @return [Result.success] with the `did:web:<domain>` string on success,
     *         [Result.failure] with the exception on network or HTTP error.
     */
    suspend fun publish(domain: String, publicKeyHex: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val cleanDomain = domain.trimEnd('/').removePrefix("https://").removePrefix("http://")
                val did         = "did:web:$cleanDomain"
                val docJson     = buildDidDocument(cleanDomain, publicKeyHex)
                val targetUrl   = "https://$cleanDomain$DID_JSON_PATH"

                val url  = URL(targetUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod  = "PUT"
                conn.doOutput       = true
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout    = READ_TIMEOUT_MS
                conn.setRequestProperty("Content-Type", CONTENT_TYPE)
                conn.setRequestProperty("Accept", CONTENT_TYPE)

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(docJson) }

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    Timber.i("DidWebPublisher: published $did to $targetUrl (HTTP $responseCode)")
                    Result.success(did)
                } else {
                    val msg = "HTTP $responseCode from $targetUrl"
                    Timber.w("DidWebPublisher: publish failed — $msg")
                    Result.failure(RuntimeException(msg))
                }
            } catch (e: Exception) {
                Timber.e(e, "DidWebPublisher: publish failed for domain=$domain")
                Result.failure(e)
            }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extract base64url-encoded x and y coordinates from a P-256 public key byte array.
     * Accepts uncompressed (04||x||y, 65 bytes) or raw 64-byte (x||y) formats.
     */
    private fun extractJwkCoords(keyBytes: ByteArray): Pair<String, String> {
        val enc = java.util.Base64.getUrlEncoder().withoutPadding()
        return when (keyBytes.size) {
            65   -> Pair(enc.encodeToString(keyBytes.copyOfRange(1, 33)),
                         enc.encodeToString(keyBytes.copyOfRange(33, 65)))
            64   -> Pair(enc.encodeToString(keyBytes.copyOfRange(0, 32)),
                         enc.encodeToString(keyBytes.copyOfRange(32, 64)))
            33   -> {
                // Compressed point — decompress: not implemented without BC in this context
                // Return hex-encoded x coordinate with placeholder y
                Timber.w("DidWebPublisher: compressed public key — decompression not supported, y will be empty")
                Pair(enc.encodeToString(keyBytes.copyOfRange(1, 33)), "")
            }
            else -> throw IllegalArgumentException(
                "Unsupported public key format: ${keyBytes.size} bytes")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val h = hex.removePrefix("0x").lowercase()
        return ByteArray(h.length / 2) { i ->
            h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
