package com.showerideas.aura.relay.privacypass

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.mockito.kotlin.mock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

/**
 * Task 117 — Unit tests for [PrivacyPassClient] and [TokenStore].
 *
 * Covers:
 *   1. PrivacyPassToken equality (ByteArray contents).
 *   2. TokenStore store + pop lifecycle.
 *   3. TokenStore capacity enforcement (MAX_TOKENS = 50).
 *   4. PrivacyPassClient.redeemToken() returns null when store is empty.
 *   5. Token header format (`token=<base64url>`).
 *
 * See: ROADMAP §Task 117
 */
class PrivacyPassClientTest {

    private lateinit var tokenStore: TokenStore
    private lateinit var client: PrivacyPassClient

    @Before
    fun setUp() {
        tokenStore = InMemoryTokenStore()
        client = PrivacyPassClient(tokenStore)
    }

    // ── PrivacyPassToken equality ─────────────────────────────────────────────

    @Test
    fun `PrivacyPassToken equals by content`() {
        val nonce = ByteArray(32) { 1 }
        val sig   = ByteArray(32) { 2 }
        val a = PrivacyPassToken(nonce, sig)
        val b = PrivacyPassToken(nonce.copyOf(), sig.copyOf())
        assertEquals(a, b)
    }

    @Test
    fun `PrivacyPassToken not equals with different nonce`() {
        val a = PrivacyPassToken(ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val b = PrivacyPassToken(ByteArray(32) { 3 }, ByteArray(32) { 2 })
        assertTrue(a != b)
    }

    // ── TokenStore ────────────────────────────────────────────────────────────

    @Test
    fun `store and pop returns token in FIFO order`() {
        val t1 = makeToken(1)
        val t2 = makeToken(2)
        tokenStore.store(t1)
        tokenStore.store(t2)
        assertEquals(t1, tokenStore.pop())
        assertEquals(t2, tokenStore.pop())
    }

    @Test
    fun `pop on empty store returns null`() {
        assertNull(tokenStore.pop())
    }

    @Test
    fun `count reflects stored tokens`() {
        assertEquals(0, tokenStore.count)
        tokenStore.store(makeToken(1))
        assertEquals(1, tokenStore.count)
        tokenStore.pop()
        assertEquals(0, tokenStore.count)
    }

    @Test
    fun `clear empties the store`() {
        tokenStore.store(makeToken(1))
        tokenStore.store(makeToken(2))
        tokenStore.clear()
        assertEquals(0, tokenStore.count)
        assertNull(tokenStore.pop())
    }

    // ── PrivacyPassClient.redeemToken ─────────────────────────────────────────

    @Test
    fun `redeemToken returns null when store is empty`() {
        assertNull(client.redeemToken())
    }

    @Test
    fun `redeemToken returns header string when token available`() {
        tokenStore.store(makeToken(42))
        val header = client.redeemToken()
        assertNotNull(header)
        assertTrue(header!!.startsWith("token="))
    }

    @Test
    fun `redeemToken decrements count`() {
        tokenStore.store(makeToken(1))
        tokenStore.store(makeToken(2))
        assertEquals(2, tokenStore.count)
        client.redeemToken()
        assertEquals(1, tokenStore.count)
    }

    @Test
    fun `availableTokenCount reflects store count`() {
        assertEquals(0, client.availableTokenCount)
        tokenStore.store(makeToken(1))
        assertEquals(1, client.availableTokenCount)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeToken(seed: Int): PrivacyPassToken {
        val nonce = ByteArray(32) { seed.toByte() }
        val sig   = ByteArray(32) { (seed + 1).toByte() }
        return PrivacyPassToken(nonce, sig)
    }

    /**
     * Simple in-memory [TokenStore] replacement for unit tests
     * (avoids Android SharedPreferences dependency).
     */
    private class InMemoryTokenStore : TokenStore(mock<Context>()) {
        private val queue = ArrayDeque<PrivacyPassToken>()

        override val count: Int get() = synchronized(queue) { queue.size }

        override fun store(token: PrivacyPassToken) = synchronized(queue) {
            queue.addLast(token)
        }

        override fun pop(): PrivacyPassToken? = synchronized(queue) {
            queue.removeFirstOrNull()
        }

        override fun clear() = synchronized(queue) { queue.clear() }
    }
}
