package com.novafiles.app.core.davserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات فك الترميز والتسوية وحماية الجذر — أول خط دفاع في الخادم:
 * أسماء عربية، مسافات، إيموجي، ومحاولات هروب بـ ../
 */
class DavPathTest {

    @Test
    fun `plain path passes through`() {
        val r = DavPath.decodeAndNormalize("/docs/reports/2026.pdf")
        assertTrue(r is DavPathResult.Ok)
        assertEquals("docs/reports/2026.pdf", (r as DavPathResult.Ok).path)
    }

    @Test
    fun `root normalizes to empty`() {
        val r = DavPath.decodeAndNormalize("/")
        assertTrue(r is DavPathResult.Ok)
        assertEquals("", (r as DavPathResult.Ok).path)
    }

    @Test
    fun `arabic and spaces percent-encoded decode`() {
        val encoded = "/" + DavPath.encodeSegment("مجلداتي المهمة") + "/تقرير سنوي.txt"
        val r = DavPath.decodeAndNormalize(encoded)
        assertTrue(r is DavPathResult.Ok)
        assertEquals("مجلداتي المهمة/تقرير سنوي.txt", (r as DavPathResult.Ok).path)
    }

    @Test
    fun `dot segments are dropped`() {
        val r = DavPath.decodeAndNormalize("/a/./b//c/")
        assertTrue(r is DavPathResult.Ok)
        assertEquals("a/b/c", (r as DavPathResult.Ok).path)
    }

    @Test
    fun `escape attempt with dotdot is rejected`() {
        val r = DavPath.decodeAndNormalize("/safe/../../etc/passwd")
        assertTrue(r is DavPathResult.Bad)
    }

    @Test
    fun `percent-encoded dotdot is also rejected`() {
        val r = DavPath.decodeAndNormalize("/a/%2E%2E/secret")
        assertTrue(r is DavPathResult.Bad)
    }

    @Test
    fun `truncated escape is rejected`() {
        assertTrue(DavPath.decodeAndNormalize("/a%2") is DavPathResult.Bad)
        assertTrue(DavPath.decodeAndNormalize("/a%") is DavPathResult.Bad)
    }

    @Test
    fun `invalid hex is rejected`() {
        assertTrue(DavPath.decodeAndNormalize("/a%ZZ") is DavPathResult.Bad)
    }

    @Test
    fun `query string is stripped`() {
        val r = DavPath.decodeAndNormalize("/file.txt?token=abc")
        assertTrue(r is DavPathResult.Ok)
        assertEquals("file.txt", (r as DavPathResult.Ok).path)
    }

    @Test
    fun `encodeHref keeps slashes and encodes segments`() {
        val href = DavPath.encodeHref("صور/عيد 2026")
        assertEquals("%D8%B5%D9%88%D8%B1/%D8%B9%D9%8A%D8%AF%202026", href)
    }

    @Test
    fun `displayName returns last segment`() {
        assertEquals("2026.pdf", DavPath.displayName("docs/reports/2026.pdf"))
        assertEquals("", DavPath.displayName(""))
    }
}
