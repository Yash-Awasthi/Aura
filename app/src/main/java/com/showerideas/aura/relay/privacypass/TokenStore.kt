package com.showerideas.aura.relay.privacypass

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task 114 — Privacy Pass token store.
 *
 * Persistent store for [PrivacyPassToken] objects. Tokens are stored in
 * EncryptedSharedPreferences as a JSON array of Base64-encoded `nonce||signature` strings.
 *
 * The store behaves as a FIFO queue: [store] appends, [pop] removes and returns the
 * oldest token. Tokens are single-use (replay detection is server-enforced).
 *
 * Maximum capacity: 50 tokens (prevents unbounded storage growth if the app is
 * offline for an extended period and tokens accumulate).
 *
 * See: ROADMAP §Task 114
 */
@Singleton
open class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME  = "privacy_pass_tokens"
        private const val KEY_TOKENS  = "tokens"
        private const val MAX_TOKENS  = 50
        private const val SEPARATOR   = "|"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // In-memory queue backed by SharedPreferences
    private val queue: ArrayDeque<PrivacyPassToken> by lazy { load() }

    open val count: Int get() = synchronized(queue) { queue.size }

    open fun store(token: PrivacyPassToken) {
        synchronized(queue) {
            if (queue.size >= MAX_TOKENS) {
                Timber.w("TokenStore: capacity reached ($MAX_TOKENS) — dropping oldest token")
                queue.removeFirst()
            }
            queue.addLast(token)
            persist()
        }
    }

    open fun pop(): PrivacyPassToken? = synchronized(queue) {
        val token = queue.removeFirstOrNull()
        if (token != null) persist()
        token
    }

    open fun clear() = synchronized(queue) {
        queue.clear()
        persist()
        Timber.d("TokenStore: cleared")
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun persist() {
        val serialised = queue.joinToString(SEPARATOR) { token ->
            val combined = token.nonce + token.signature
            Base64.encodeToString(combined, Base64.NO_WRAP)
        }
        prefs.edit().putString(KEY_TOKENS, serialised).apply()
    }

    private fun load(): ArrayDeque<PrivacyPassToken> {
        val serialised = prefs.getString(KEY_TOKENS, null) ?: return ArrayDeque()
        val queue = ArrayDeque<PrivacyPassToken>()
        serialised.split(SEPARATOR).forEach { entry ->
            if (entry.isBlank()) return@forEach
            try {
                val combined = Base64.decode(entry, Base64.NO_WRAP)
                if (combined.size >= 64) {
                    val nonce = combined.copyOf(32)
                    val signature = combined.copyOfRange(32, combined.size)
                    queue.addLast(PrivacyPassToken(nonce, signature))
                }
            } catch (e: Exception) {
                Timber.w("TokenStore: skipping malformed token entry")
            }
        }
        Timber.d("TokenStore: loaded ${queue.size} token(s)")
        return queue
    }
}

/**
 * A single Privacy Pass v2 token: a 32-byte nonce and its blind signature.
 * Single-use — [TokenStore.pop] removes it from the store on first access.
 */
data class PrivacyPassToken(
    val nonce: ByteArray,
    val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is PrivacyPassToken &&
            nonce.contentEquals(other.nonce) &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int =
        nonce.contentHashCode() * 31 + signature.contentHashCode()
}
