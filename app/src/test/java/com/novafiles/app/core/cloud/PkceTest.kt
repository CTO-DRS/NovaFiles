package com.novafiles.app.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

class PkceTest {

    /** المتجه المرجعي الرسمي من RFC 7636 Appendix B */
    @Test
    fun `rfc7636 appendix b vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = Pkce.s256(verifier)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }

    @Test
    fun `generated verifier respects rfc constraints`() {
        val p = Pkce.generate(SecureRandom())
        assertEquals(128, p.verifier.length)
        assertTrue(p.verifier.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~" })
        // challenge بلا حشو '=' وطول SHA-256 القياسي 43 حرفاً
        assertEquals(43, p.challenge.length)
        assertTrue('+' !in p.challenge && '/' !in p.challenge && '=' !in p.challenge)
    }

    @Test
    fun `unique pairs on repeated generation`() {
        val a = Pkce.generate(SecureRandom())
        val b = Pkce.generate(SecureRandom())
        assertNotEquals(a.verifier, b.verifier)
        assertNotEquals(a.challenge, b.challenge)
    }

    @Test
    fun `deterministic challenge for same verifier`() {
        val v = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 43 حرفاً
        assertEquals(Pkce.s256(v), Pkce.s256(v))
    }

    @Test
    fun `rejects out of range verifier lengths`() {
        for (bad in listOf("short", "x".repeat(129))) {
            try {
                Pkce.s256(bad)
                fail("should reject length ${bad.length}")
            } catch (e: IllegalArgumentException) {
                // متوقع
            }
        }
    }

    @Test
    fun `base64url no padding helper`() {
        assertEquals("", Pkce.base64UrlNoPadding(ByteArray(0)))
        // 1 بايت = 2 محارف بلا حشو
        assertEquals(2, Pkce.base64UrlNoPadding(byteArrayOf(0xFF.toByte())).length)
        // 2 بايت = 3 محارف بلا حشو
        assertEquals(3, Pkce.base64UrlNoPadding(byteArrayOf(0xFF.toByte(), 0xAB.toByte())).length)
    }
}
