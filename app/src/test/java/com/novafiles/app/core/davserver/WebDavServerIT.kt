package com.novafiles.app.core.davserver

import com.novafiles.app.core.network.DavParser
import com.novafiles.app.core.netlog.ConnectionLog
import com.novafiles.app.core.netlog.NetEventType
import com.novafiles.app.core.netlog.SpeedLimiter
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * اختبارات تكامل حقيقية — خادم WebDAV حي على 127.0.0.1 مقابل عميل
 * OkHttp (يدعم أفعال DAV المخصصة) + عميل مقابس خام + المحلل الإنتاجي
 * نفسه (DavParser.parseMultistatus) في تحقق متبادل خادم↔عميل.
 */
class WebDavServerIT {

    private lateinit var root: File
    private lateinit var server: WebDavServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "nova-davit-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        server = WebDavServer(root, port = freePort(), username = "nova", password = "secret")
        assertTrue(server.start())
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun base(): String = "http://127.0.0.1:${server.port}"

    private val octet = "application/octet-stream".toMediaTypeOrNull()

    private fun request(
        path: String,
        method: String,
        auth: Boolean = true,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): okhttp3.Response {
        val builder = Request.Builder().url(base() + path)
        if (auth) {
            builder.header(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString("nova:secret".toByteArray()),
            )
        }
        for ((k, v) in headers) builder.header(k, v)
        val reqBody: RequestBody? = when {
            body != null -> RequestBody.create(octet, body)
            method == "GET" || method == "HEAD" -> null
            else -> RequestBody.create(octet, ByteArray(0))
        }
        builder.method(method, reqBody)
        return client.newCall(builder.build()).execute()
    }

    // ═══════════════ التكامل الكامل ═══════════════

    @Test
    fun `options over the wire`() {
        request("/", "OPTIONS").use { resp ->
            assertEquals(200, resp.code)
            assertEquals("1, 2", resp.header("DAV"))
        }
    }

    @Test
    fun `upload list and download roundtrip with arabic name`() {
        val payload = ByteArray(300_000) { (it % 251).toByte() }

        // 0) MKCOL للمجلد العربي أولًا
        request("/" + DavPath.encodeSegment("تقارير"), "MKCOL").use {
            assertEquals(201, it.code)
        }

        // 1) PUT
        request("/" + DavPath.encodeSegment("تقارير") + "%2Fbig.bin", "PUT", body = payload).use {
            assertEquals(201, it.code)
        }

        // 2) PROPFIND على الجذر → نحلل بالمحلل الإنتاجي نفسه
        request("/", "PROPFIND", headers = mapOf("Depth" to "1")).use { find ->
            assertEquals(207, find.code)
            val resources = DavParser.parseMultistatus(find.body!!.byteStream())
            assertTrue(resources.any { it.displayName == "تقارير" && it.isCollection })
        }

        // 2-ب) PROPFIND المجلد الفرعي يظهر الملف
        request("/" + DavPath.encodeSegment("تقارير"), "PROPFIND", headers = mapOf("Depth" to "1")).use { find ->
            assertEquals(207, find.code)
            val inner = DavParser.parseMultistatus(find.body!!.byteStream())
            assertTrue(inner.any { it.displayName == "big.bin" && !it.isCollection })
        }

        // 3) GET يعيد البايتات نفسها
        request("/" + DavPath.encodeSegment("تقارير") + "%2Fbig.bin", "GET").use {
            assertEquals(200, it.code)
            assertArrayEquals(payload, it.body!!.bytes())
        }

        // 4) DELETE ينظف
        request("/" + DavPath.encodeSegment("تقارير") + "%2Fbig.bin", "DELETE").use {
            assertEquals(204, it.code)
        }
    }

    @Test
    fun `range request over the wire returns 206 slice`() {
        val data = ByteArray(1000) { (it and 0xFF).toByte() }
        File(root, "range.bin").writeBytes(data)

        request("/range.bin", "GET", headers = mapOf("Range" to "bytes=100-199")).use { resp ->
            assertEquals(206, resp.code)
            assertEquals("bytes 100-199/1000", resp.header("Content-Range"))
            val got = resp.body!!.bytes()
            assertEquals(100, got.size)
            assertEquals(data[100].toInt(), got[0].toInt())
        }
    }

    @Test
    fun `mkcol move copy delete over the wire`() {
        request("/dirA", "MKCOL").use { assertEquals(201, it.code) }
        request("/dirA", "MKCOL").use { assertEquals(405, it.code) }

        request("/dirA/inner.txt", "PUT", body = "hello dav".toByteArray()).use {
            assertEquals(201, it.code)
        }

        request(
            "/dirA/inner.txt", "MOVE",
            headers = mapOf("Destination" to "${base()}/dirA/renamed.txt"),
        ).use { assertEquals(201, it.code) }
        assertTrue(File(root, "dirA/renamed.txt").exists())

        request(
            "/dirA/renamed.txt", "COPY",
            headers = mapOf("Destination" to "${base()}/dirA/copied.txt"),
        ).use { assertEquals(201, it.code) }
        assertEquals("hello dav", File(root, "dirA/copied.txt").readText())

        request("/dirA", "DELETE").use { assertEquals(204, it.code) }
        assertTrue(!File(root, "dirA").exists())
    }

    @Test
    fun `html index served for browser clients`() {
        File(root, "web").mkdir()
        File(root, "web/صفحة.html").writeText("<h1>أهلا</h1>")
        request("/web", "GET").use { resp ->
            assertEquals(200, resp.code)
            assertTrue(resp.header("Content-Type")!!.startsWith("text/html"))
            assertTrue(String(resp.body!!.bytes(), StandardCharsets.UTF_8).contains("صفحة.html"))
        }
    }

    @Test
    fun `unauthenticated request is challenged`() {
        request("/", "OPTIONS", auth = false).use { resp ->
            assertEquals(401, resp.code)
            assertTrue(resp.header("WWW-Authenticate")!!.contains("Basic"))
        }
    }

    @Test
    fun `wrong password is rejected`() {
        request(
            "/", "OPTIONS",
            auth = false,
            headers = mapOf(
                "Authorization" to
                    "Basic " + Base64.getEncoder().encodeToString("nova:bad".toByteArray()),
            ),
        ).use { assertEquals(401, it.code) }
    }

    @Test
    fun `root escape attempt answers 400`() {
        val socket = Socket("127.0.0.1", server.port)
        socket.soTimeout = 5000
        val out = socket.getOutputStream()
        out.write("GET /a/../../etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        out.flush()
        val status = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
            .readLine()
        assertTrue("expected 400, got: $status", status!!.contains(" 400 "))
        socket.close()
    }

    @Test
    fun `keep alive serves two requests on one connection`() {
        File(root, "ka.txt").writeText("ka")
        val auth = "Basic " + Base64.getEncoder().encodeToString("nova:secret".toByteArray())
        val socket = Socket("127.0.0.1", server.port)
        socket.soTimeout = 8000
        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1)
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))

        // الطلب الأول: OPTIONS بمصادقة
        writer.write("OPTIONS / HTTP/1.1\r\nHost: x\r\nAuthorization: $auth\r\n\r\n")
        writer.flush()
        assertEquals("HTTP/1.1 200 OK", reader.readLine())
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:")) contentLength = line.substringAfter(':').trim().toInt()
        }
        if (contentLength > 0) {
            val body = CharArray(contentLength)
            var read = 0
            while (read < contentLength) read += reader.read(body, read, contentLength - read)
        }

        // الطلب الثاني على نفس الاتصال: GET ملف بمصادقة
        writer.write("GET /ka.txt HTTP/1.1\r\nHost: x\r\nAuthorization: $auth\r\n\r\n")
        writer.flush()
        assertEquals("HTTP/1.1 200 OK", reader.readLine())

        socket.close()
    }

    @Test
    fun `chunked upload over the wire lands on disk`() {
        val socket = Socket("127.0.0.1", server.port)
        socket.soTimeout = 8000
        val out = socket.getOutputStream()
        val auth = Base64.getEncoder().encodeToString("nova:secret".toByteArray())
        out.write(
            ("PUT /chunk.txt HTTP/1.1\r\n" +
                "Authorization: Basic $auth\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n").toByteArray(),
        )
        out.write("5\r\nchunk\r\n8\r\ned-hello\r\n0\r\n\r\n".toByteArray())
        out.flush()
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
        val status = reader.readLine()
        assertTrue("expected 201, got: $status", status!!.contains(" 201 "))
        socket.close()
        assertEquals("chunked-hello", File(root, "chunk.txt").readText())
    }

    @Test
    fun `connection log records connect and download events`() {
        val log = ConnectionLog(50)
        val loggedServer = WebDavServer(root, port = freePort(), username = "nova", password = "secret", log = log)
        assertTrue(loggedServer.start())
        try {
            File(root, "dl.bin").writeBytes(ByteArray(2048))
            val direct = Request.Builder()
                .url("http://127.0.0.1:${loggedServer.port}/dl.bin")
                .header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString("nova:secret".toByteArray()),
                )
                .get().build()
            client.newCall(direct).execute().use { assertEquals(2048, it.body!!.bytes().size) }
        } finally {
            loggedServer.stop()
        }

        // انتظار قصير حتى تُسجَّل الأحداث (خيوط الشبكة)
        var sawConnect = false
        var sawDownload = false
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 3000 && !(sawConnect && sawDownload)) {
            val events = log.events.value
            sawConnect = sawConnect || events.any { it.type == NetEventType.CONNECT }
            sawDownload = sawDownload || events.any { it.type == NetEventType.DOWNLOAD && it.bytes == 2048L }
            if (!sawConnect || !sawDownload) Thread.sleep(50)
        }
        assertTrue("CONNECT event missing", sawConnect)
        assertTrue("DOWNLOAD event missing", sawDownload)
    }

    @Test
    fun `server refuses start when root is a file`() {
        val fileRoot = File(System.getProperty("java.io.tmpdir"), "nova-davfile-${System.nanoTime()}")
        fileRoot.writeText("not a dir")
        try {
            val s = WebDavServer(fileRoot, port = freePort(), username = "n", password = "p")
            assertTrue(!s.start())
        } finally {
            fileRoot.delete()
        }
    }

    @Test
    fun `throttled download works over the wire`() {
        val limiter = SpeedLimiter(0) // بلا حد — مسار التمرير فقط
        val s = WebDavServer(root, port = freePort(), username = "nova", password = "secret", limiter = limiter)
        assertTrue(s.start())
        try {
            File(root, "th.bin").writeBytes(ByteArray(100_000))
            val direct = Request.Builder()
                .url("http://127.0.0.1:${s.port}/th.bin")
                .header(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString("nova:secret".toByteArray()),
                )
                .get().build()
            client.newCall(direct).execute().use { assertEquals(100_000, it.body!!.bytes().size) }
        } finally {
            s.stop()
        }
    }
}
