package com.novafiles.app.core.davserver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * اختبارات طبقة بروتوكول HTTP/1.1: تحليل الطلبات (Length وChunked)،
 * كتابة الاستجابات، وهروب المسار الفاسد — كلها بالبايتات دون مقابس.
 */
class DavHttpTest {

    private fun streamOf(raw: String): BufferedInputStream =
        BufferedInputStream(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

    private fun bodyOf(req: DavRequest): ByteArray = req.body.readBytes()

    @Test
    fun `parses request line headers and body`() {
        val raw = "PROPFIND /docs HTTP/1.1\r\n" +
            "Depth: 1\r\n" +
            "Content-Length: 5\r\n" +
            "\r\n" +
            "hello"
        val req = DavHttp.readRequest(streamOf(raw))!!
        assertEquals("PROPFIND", req.method)
        assertEquals("docs", req.path)
        assertEquals("1", req.header("depth"))
        assertArrayEquals("hello".toByteArray(), bodyOf(req))
        assertEquals(5L, req.contentLength)
    }

    @Test
    fun `decodes path with arabic percent encoding`() {
        val encoded = "/" + DavPath.encodeSegment("مجلد") + "/f.txt"
        val raw = "GET $encoded HTTP/1.1\r\nHost: x\r\n\r\n"
        val req = DavHttp.readRequest(streamOf(raw))!!
        assertEquals("مجلد/f.txt", req.path)
    }

    @Test
    fun `parses chunked body`() {
        val raw = "PUT /up.bin HTTP/1.1\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "5\r\nhello\r\n" +
            "3\r\nabc\r\n" +
            "0\r\n" +
            "\r\n"
        val req = DavHttp.readRequest(streamOf(raw))!!
        assertEquals("PUT", req.method)
        assertEquals(-1L, req.contentLength)
        assertArrayEquals("helloabc".toByteArray(), bodyOf(req))
    }

    @Test
    fun `chunked with extensions and trailer headers`() {
        val raw = "PUT /c2.bin HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "2;ext=1\r\nhi\r\n" +
            "0\r\n" +
            "X-Trailer: v\r\n" +
            "\r\n"
        val req = DavHttp.readRequest(streamOf(raw))!!
        assertArrayEquals("hi".toByteArray(), bodyOf(req))
    }

    @Test
    fun `bad chunk size is rejected`() {
        val raw = "PUT /c3.bin HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n"
        try {
            val req = DavHttp.readRequest(streamOf(raw))!!
            req.body.readBytes() // القراءة تفجّر خطأ حجم القطعة
            throw AssertionError("expected BadRequest")
        } catch (e: DavHttp.BadRequest) {
            // متوقع
        }
    }

    @Test
    fun `escape path in request is rejected with bad request`() {
        val raw = "GET /a/../../etc HTTP/1.1\r\nHost: x\r\n\r\n"
        try {
            DavHttp.readRequest(streamOf(raw))
            throw AssertionError("expected BadRequest")
        } catch (e: DavHttp.BadRequest) {
            // متوقع
        }
    }

    @Test
    fun `null on clean eof`() {
        assertNull(DavHttp.readRequest(streamOf("")))
    }

    @Test
    fun `skips leading blank line before request`() {
        val raw = "\r\nGET /ok.txt HTTP/1.1\r\nHost: x\r\n\r\n"
        val req = DavHttp.readRequest(streamOf(raw))!!
        assertEquals("ok.txt", req.path)
    }

    @Test
    fun `writes response with byte body and content length`() {
        val out = ByteArrayOutputStream()
        val resp = DavResponse(
            207, "Multi-Status",
            mutableListOf("Content-Type" to "application/xml; charset=utf-8"),
            bodyBytes = "<xml/>".toByteArray(),
        )
        DavHttp.writeResponse(out, resp, keepAlive = true)
        val text = out.toString("ISO-8859-1")
        assertTrue(text.startsWith("HTTP/1.1 207 Multi-Status\r\n"))
        assertTrue(text.contains("Content-Length: 6"))
        assertTrue(text.contains("Connection: keep-alive"))
        assertTrue(text.endsWith("<xml/>"))
    }

    @Test
    fun `streams large body without buffering whole content`() {
        val out = ByteArrayOutputStream()
        val payload = ByteArray(200_000) { (it % 7).toByte() }
        val resp = DavResponse(
            200, "OK",
            mutableListOf("Content-Type" to "application/octet-stream"),
            bodyStream = payload.inputStream(),
            bodyLength = payload.size.toLong(),
        )
        DavHttp.writeResponse(out, resp, keepAlive = false)
        val all = out.toByteArray()
        val headEnd = String(all, Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
        assertEquals(payload.size + headEnd, all.size)
        assertArrayEquals(payload, all.copyOfRange(headEnd, all.size))
        assertTrue(String(all, 0, headEnd, Charsets.ISO_8859_1).contains("Connection: close"))
    }

    @Test
    fun `empty response carries zero length`() {
        val out = ByteArrayOutputStream()
        DavHttp.writeResponse(out, DavResponse(204, "No Content"), keepAlive = true)
        val text = out.toString("ISO-8859-1")
        assertTrue(text.contains("Content-Length: 0"))
    }

    @Test
    fun `length input drain semantics`() {
        val src = BufferedInputStream(ByteArrayInputStream("hello world".toByteArray()))
        val len = DavHttp.LengthInputStream(src, 11)
        val first = ByteArray(5)
        assertEquals(5, len.read(first))
        assertEquals(6L, len.remaining())
        assertTrue(!len.consumedFully)
        assertTrue(len.drain(64))
        assertTrue(len.consumedFully)
    }

    @Test
    fun `length input drain caps out`() {
        val src = BufferedInputStream(ByteArrayInputStream(ByteArray(100)))
        val len = DavHttp.LengthInputStream(src, 100)
        len.read() // بايت واحد
        assertTrue(!len.drain(10)) // السقف أصغر من الباقي
    }
}
