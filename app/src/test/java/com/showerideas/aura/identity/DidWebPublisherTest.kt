package com.showerideas.aura.identity

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DidWebPublisher.buildDidDocument].
 * Tests the DID Document structure, field correctness, and domain validation logic.
 */
class DidWebPublisherTest {

    private lateinit var publisher: DidWebPublisher

    // Uncompressed P-256 public key (04 || 32-byte x || 32-byte y) — test vector
    private val testPubKeyHex = "04" +
        "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
        "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"

    @Before
    fun setUp() {
        publisher = DidWebPublisher()
    }

    @Test
    fun `buildDidDocument produces correct context array`() {
        val doc = publisher.buildDidDocument("yash.dev", testPubKeyHex)
        val json = JSONObject(doc)
        val ctx = json.getJSONArray("@context")
        assertEquals("https://www.w3.org/ns/did/v1", ctx.getString(0))
        assertTrue(ctx.length() >= 1)
    }

    @Test
    fun `buildDidDocument produces correct DID id for domain`() {
        val doc = JSONObject(publisher.buildDidDocument("yash.dev", testPubKeyHex))
        assertEquals("did:web:yash.dev", doc.getString("id"))
    }

    @Test
    fun `buildDidDocument contains verificationMethod with JsonWebKey2020`() {
        val doc = JSONObject(publisher.buildDidDocument("yash.dev", testPubKeyHex))
        val vms  = doc.getJSONArray("verificationMethod")
        assertTrue("verificationMethod must have at least one entry", vms.length() >= 1)
        val vm = vms.getJSONObject(0)
        assertEquals("JsonWebKey2020",      vm.getString("type"))
        assertEquals("did:web:yash.dev",    vm.getString("controller"))
        assertEquals("did:web:yash.dev#key-1", vm.getString("id"))
        val jwk = vm.getJSONObject("publicKeyJwk")
        assertEquals("EC",    jwk.getString("kty"))
        assertEquals("P-256", jwk.getString("crv"))
        assertTrue("JWK x coordinate must be non-blank", jwk.getString("x").isNotBlank())
        assertTrue("JWK y coordinate must be non-blank", jwk.getString("y").isNotBlank())
    }

    @Test
    fun `buildDidDocument has authentication and assertionMethod referencing key-1`() {
        val doc  = JSONObject(publisher.buildDidDocument("example.com", testPubKeyHex))
        val auth = doc.getJSONArray("authentication")
        val assr = doc.getJSONArray("assertionMethod")
        assertEquals("did:web:example.com#key-1", auth.getString(0))
        assertEquals("did:web:example.com#key-1", assr.getString(0))
    }

    @Test
    fun `buildDidDocument strips trailing slash from domain`() {
        val doc = JSONObject(publisher.buildDidDocument("yash.dev/", testPubKeyHex))
        assertEquals("did:web:yash.dev", doc.getString("id"))
    }

    @Test
    fun `buildDidDocument handles 64-byte raw public key format`() {
        // 64 bytes = x||y without 04 prefix
        val raw64 = testPubKeyHex.removePrefix("04")
        val doc   = JSONObject(publisher.buildDidDocument("yash.dev", raw64))
        val vm    = doc.getJSONArray("verificationMethod").getJSONObject(0)
        val jwk   = vm.getJSONObject("publicKeyJwk")
        assertTrue(jwk.getString("x").isNotBlank())
        assertTrue(jwk.getString("y").isNotBlank())
    }
}
