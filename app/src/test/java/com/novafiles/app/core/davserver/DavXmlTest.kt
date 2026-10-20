package com.novafiles.app.core.davserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مولّد XML multistatus وصفحة فهرس HTML:
 * البنية، الهروب، الترميز الفعلي للأسماء العربية، وتمييز المجلدات.
 */
class DavXmlTest {

    @Test
    fun `multistatus contains collection marker for dirs`() {
        val xml = DavXml.multistatus(
            listOf(DavXml.Entry("docs", isCollection = true, 0, 1730000000000L, null, null)),
        )
        assertTrue(xml.contains("<D:multistatus xmlns:D=\"DAV:\">"))
        assertTrue(xml.contains("<D:resourcetype><D:collection/></D:resourcetype>"))
        assertTrue(xml.contains("<D:href>/docs/</D:href>"))
        assertFalse(xml.contains("getcontentlength"))
    }

    @Test
    fun `multistatus contains size and type for files`() {
        val xml = DavXml.multistatus(
            listOf(
                DavXml.Entry(
                    "a/b.txt", isCollection = false, size = 2048,
                    modifiedMillis = 1730000000000L, etag = "\"e1\"",
                    contentType = "text/plain",
                ),
            ),
        )
        assertTrue(xml.contains("<D:getcontentlength>2048</D:getcontentlength>"))
        assertTrue(xml.contains("<D:getcontenttype>text/plain</D:getcontenttype>"))
        assertTrue(xml.contains("<D:getetag>&quot;e1&quot;</D:getetag>"))
        assertTrue(xml.contains("<D:href>/a/b.txt</D:href>"))
    }

    @Test
    fun `arabic name is percent encoded in href and escaped in displayname`() {
        val xml = DavXml.multistatus(
            listOf(DavXml.Entry("مجلد", isCollection = false, 5, 1730000000000L, null, null)),
        )
        // href مُرمَّز فعلياً (UTF-8 → %XX)
        assertTrue(xml.contains("<D:href>/%D9%85%D8%AC%D9%84%D8%AF</D:href>"))
        // displayname يظهر بالنص الأصلي (مهروب XML إن لزم)
        assertTrue(xml.contains("<D:displayname>مجلد</D:displayname>"))
    }

    @Test
    fun `xml dangerous chars are escaped in displayname`() {
        val xml = DavXml.multistatus(
            listOf(DavXml.Entry("a&b<c>\"d\".txt", isCollection = false, 1, 1730000000000L, null, null)),
        )
        assertTrue(xml.contains("<D:displayname>a&amp;b&lt;c&gt;&quot;d&quot;.txt</D:displayname>"))
    }

    @Test
    fun `lock response carries token and timeout`() {
        val xml = DavXml.lockResponse(
            token = "opaquelocktoken:nova-xyz",
            owner = "Windows",
            timeoutSeconds = 600,
            depth = "infinity",
        )
        assertTrue(xml.contains("<D:lockdiscovery>"))
        assertTrue(xml.contains("<D:locktoken><D:href>opaquelocktoken:nova-xyz</D:href></D:locktoken>"))
        assertTrue(xml.contains("<D:timeout>Second-600</D:timeout>"))
        assertTrue(xml.contains("<D:depth>infinity</D:depth>"))
        assertTrue(xml.contains("<D:owner><D:href>Windows</D:href></D:owner>"))
    }

    @Test
    fun `html index escapes names and encodes links`() {
        val html = HtmlIndex.render(
            "docs",
            listOf(
                HtmlIndex.Item("تقرير.html", isDir = false, 10, 1730000000000L),
                HtmlIndex.Item("صور", isDir = true, 0, 1730000000000L),
            ),
        )
        assertTrue(html.contains("href=\"%D8%AA%D9%82%D8%B1%D9%8A%D8%B1.html\""))
        assertTrue(html.contains("href=\"%D8%B5%D9%88%D8%B1/\""))
        assertTrue(html.contains("تقرير.html"))
        assertTrue(html.contains("<!DOCTYPE html>"))
    }

    @Test
    fun `formatSize readable units`() {
        assertEquals("999 B", HtmlIndex.formatSize(999))
        assertEquals("1.0 KB", HtmlIndex.formatSize(1024))
        assertEquals("1.0 MB", HtmlIndex.formatSize(1024 * 1024))
        assertEquals("1.1 GB", HtmlIndex.formatSize(1L shl 30))
    }
}
