package com.novafiles.app.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OAuthClientTest {

    private val def = CloudProviderDef.DRIVE

    private fun client(transport: HttpTransport, now: Long = 1000_000L) =
        OAuthClient(transport, clientId = "test-client", nowMs = { now })

    // ---------- رابط التفويض ----------

    @Test
    fun `authorization url contains pkce state and offline grant`() {
        val oauth = client(MockTransport())
        val pkce = Pkce.generate()
        val url = oauth.buildAuthorizationUrl(def, pkce, state = "st-123")
        assertTrue(url.startsWith(def.authEndpoint))
        assertTrue("response_type=code" in url)
        assertTrue("client_id=test-client" in url)
        assertTrue("code_challenge=${pkce.challenge}" in url)
        assertTrue("code_challenge_method=S256" in url)
        assertTrue("state=st-123" in url)
        assertTrue("access_type=offline" in url) // Google: refresh_token مضمون
        // النطاقات مرمزة بمسافة %20
        assertTrue("scope=" in url)
    }

    @Test
    fun `dropbox url has no offline prompt params`() {
        val oauth = client(MockTransport())
        val url = oauth.buildAuthorizationUrl(CloudProviderDef.DROPBOX, Pkce.generate(), "s")
        assertFalse("access_type" in url)
        assertFalse("prompt" in url)
    }

    // ---------- تحليل إعادة التوجيه ----------

    @Test
    fun `redirect parse extracts code and validates state`() {
        val oauth = client(MockTransport())
        val redirect = "com.novafiles.app:/oauth?state=abc-1&code=4%2F0xyz&scope=drive.file"
        assertEquals("4/0xyz", oauth.parseRedirect(def, redirect, "abc-1"))
    }

    @Test
    fun `redirect parse rejects state mismatch and errors`() {
        val oauth = client(MockTransport())
        try {
            oauth.parseRedirect(def, "com.novafiles.app:/oauth?state=other&code=x", "abc-1")
            fail("state mismatch must throw")
        } catch (e: OAuthException) { /* متوقع */ }

        try {
            oauth.parseRedirect(def, "com.novafiles.app:/oauth?state=abc-1&error=access_denied", "abc-1")
            fail("error param must throw")
        } catch (e: OAuthException) {
            assertTrue(e.message!!.contains("access_denied"))
        }

        try {
            oauth.parseRedirect(def, "com.novafiles.app:/oauth?state=abc-1", "abc-1")
            fail("missing code must throw")
        } catch (e: OAuthException) { /* متوقع */ }
    }

    @Test
    fun `query parser handles pluses and encoded values`() {
        val m = OAuthClient(MockTransport(), "x").parseQuery("a=b%20c&d=e+f&flag&empty=")
        assertEquals("b c", m["a"])
        assertEquals("e f", m["d"])
        assertEquals("", m["flag"])
        assertEquals("", m["empty"])
    }

    // ---------- تبادل الرمز ----------

    @Test
    fun `exchange code posts form with verifier and stores expiry skew`() {
        val t = MockTransport()
        t.queue.add(Resp.ok("""{"access_token":"AT1","expires_in":3600,"refresh_token":"RT1","scope":"drive.file"}"""))
        val now = 10_000_000L
        val oauth = OAuthClient(t, "test-client", nowMs = { now })
        val token = cloudRun { oauth.exchangeCode(def, "code-1", "verifier-1") }

        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals(def.tokenEndpoint, req.url)
        val form = String(req.body!!, Charsets.UTF_8)
        assertTrue("grant_type=authorization_code" in form)
        assertTrue("code=code-1" in form)
        assertTrue("code_verifier=verifier-1" in form)
        assertTrue("client_id=test-client" in form)

        assertEquals("AT1", token.token)
        assertEquals("RT1", token.refreshToken)
        // الانتهاء = الآن + 3600ث - هامش 60ث
        assertEquals(now + 3600_000 - 60_000, token.expiresAtMs)
    }

    @Test
    fun `exchange failure surfaces oauth error and invalid_grant flag`() {
        val t = MockTransport()
        t.queue.add(Resp.code(400, """{"error":"invalid_grant","error_description":"Code was already redeemed."}"""))
        val oauth = client(t)
        try {
            cloudRun { oauth.exchangeCode(def, "used", "v") }
            fail("must throw")
        } catch (e: OAuthException) {
            assertTrue(e.invalidGrant)
            assertTrue(e.message!!.contains("invalid_grant"))
        }
    }

    @Test
    fun `token response without access_token rejected`() {
        val t = MockTransport()
        t.queue.add(Resp.ok("""{"expires_in":3600}"""))
        val oauth = client(t)
        try {
            cloudRun { oauth.exchangeCode(def, "c", "v") }
            fail("must throw")
        } catch (e: OAuthException) {
            assertTrue(e.message!!.contains("access_token"))
        }
    }

    // ---------- التجديد ----------

    @Test
    fun `refresh posts grant refresh_token`() {
        val t = MockTransport()
        t.queue.add(Resp.ok("""{"access_token":"AT2","expires_in":1800}"""))
        val oauth = client(t)
        val token = cloudRun { oauth.refresh(def, "RT-OLD") }
        assertEquals("AT2", token.token)
        val form = String(t.requests.single().body!!, Charsets.UTF_8)
        assertTrue("grant_type=refresh_token" in form)
        assertTrue("refresh_token=RT-OLD" in form)
    }

    // ---------- ensureFresh ----------

    @Test
    fun `ensureFresh returns live token untouched`() {
        val t = MockTransport()
        val oauth = client(t)
        val store = InMemoryTokenStore()
        store.save("acc1", AccessToken("LIVE", expiresAtMs = 999_999_999_999L, refreshToken = "RT"))
        val got = cloudRun { oauth.ensureFresh(def, "acc1", store) }
        assertEquals("LIVE", got!!.token)
        assertTrue(t.requests.isEmpty()) // لا شبكة إطلاقاً
    }

    @Test
    fun `ensureFresh renews expired token via store`() {
        val t = MockTransport()
        t.queue.add(Resp.ok("""{"access_token":"FRESH","expires_in":3600,"refresh_token":"RT2"}"""))
        val now = 5_000_000L
        val oauth = OAuthClient(t, "c", nowMs = { now })
        val store = InMemoryTokenStore()
        store.save("acc1", AccessToken("OLD", expiresAtMs = now - 1, refreshToken = "RT1"))
        val got = cloudRun { oauth.ensureFresh(def, "acc1", store) }
        assertEquals("FRESH", got!!.token)
        assertEquals("FRESH", store.load("acc1")!!.token) // حُفظ بعد التجديد
        assertEquals("RT2", got.refreshToken)
    }

    @Test
    fun `ensureFresh keeps old refresh token when server omits it`() {
        val t = MockTransport()
        t.queue.add(Resp.ok("""{"access_token":"F2","expires_in":600}"""))
        val now = 5_000_000L
        val oauth = OAuthClient(t, "c", nowMs = { now })
        val store = InMemoryTokenStore()
        store.save("acc", AccessToken("OLD", expiresAtMs = now - 1, refreshToken = "RT-KEEP"))
        val got = cloudRun { oauth.ensureFresh(def, "acc", store) }
        assertEquals("RT-KEEP", got!!.refreshToken)
    }

    @Test
    fun `ensureFresh returns null for missing account`() {
        val oauth = client(MockTransport())
        val got = cloudRun { oauth.ensureFresh(def, "ghost", InMemoryTokenStore()) }
        assertNull(got)
    }

    @Test
    fun `ensureFresh propagates invalid_grant as failure`() {
        val t = MockTransport()
        t.queue.add(Resp.code(400, """{"error":"invalid_grant"}"""))
        val now = 5_000_000L
        val oauth = OAuthClient(t, "c", nowMs = { now })
        val store = InMemoryTokenStore()
        store.save("acc", AccessToken("OLD", expiresAtMs = now - 1, refreshToken = "RT"))
        try {
            cloudRun { oauth.ensureFresh(def, "acc", store) }
            fail("must throw")
        } catch (e: OAuthException) {
            assertTrue(e.invalidGrant)
        }
    }

    // ---------- مزوّدون ----------

    @Test
    fun `provider registry resolves by key and rejects unknown`() {
        assertEquals("drive", CloudProviderDef.byKey("drive").key)
        assertEquals("dropbox", CloudProviderDef.byKey("dropbox").key)
        assertEquals("onedrive", CloudProviderDef.byKey("onedrive").key)
        assertEquals(3, CloudProviderDef.ALL.size)
        try {
            CloudProviderDef.byKey("gdrive")
            fail("unknown must throw")
        } catch (e: IllegalStateException) { /* متوقع */ }
    }

    @Test
    fun `scopes are minimal per roadmap`() {
        // scope minimization: لا نطاقات حساسة (بدل drive الكامل مثلاً)
        assertTrue(CloudProviderDef.DRIVE.scopes.none { it == "https://www.googleapis.com/auth/drive" })
        assertTrue(CloudProviderDef.ONEDRIVE.scopes.none { it.contains("Files.Read.All") })
        assertNotNull(CloudProviderDef.DROPBOX.scopes.firstOrNull { it == "files.content.read" })
    }

    @Test
    fun `token store roundtrip`() {
        val store = InMemoryTokenStore()
        store.save("a", AccessToken("T1", 100L, "R1"))
        assertEquals("T1", store.load("a")!!.token)
        store.save("a", AccessToken("T2", 200L, "R2"))
        assertEquals("T2", store.load("a")!!.token)
        store.remove("a")
        assertNull(store.load("a"))
    }
}
