package com.novafiles.app.core.davserver

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * الاختبار الشامل لمحرك WebDAV الخالص — مباشرة فوق مجلد مؤقت
 * دون أي شبكة: كل الأفعال، الشروط القبلية، المدى (Range)،
 * المصادقة، الأقفال، وحماية الحالات الشاذة.
 */
class WebDavEngineTest {

    private lateinit var root: File
    private lateinit var engine: WebDavEngine

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "nova-dav-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        engine = WebDavEngine(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ═══════════════ أدوات ═══════════════

    private fun req(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): DavRequest = DavRequest(
        method = method,
        path = path,
        headers = headers.mapKeys { it.key.lowercase() },
        body = ByteArrayInputStream(body),
        contentLength = body.size.toLong(),
    )

    private fun bytes(resp: DavResponse): ByteArray {
        resp.bodyBytes?.let { return it }
        resp.bodyStream?.use { return it.readBytes() }
        return ByteArray(0)
    }

    private fun seed(name: String, content: ByteArray): File {
        val f = File(root, name)
        f.parentFile?.mkdirs()
        f.writeBytes(content)
        return f
    }

    // ═══════════════ OPTIONS ═══════════════

    @Test
    fun `options advertises dav class 1 and 2`() {
        val r = engine.handle(req("OPTIONS", "/"))
        assertEquals(200, r.status)
        assertEquals("1, 2", r.header("DAV"))
        assertTrue(r.header("Allow")!!.contains("PROPFIND"))
        assertTrue(r.header("Allow")!!.contains("LOCK"))
        assertEquals("DAV", r.header("MS-Author-Via"))
    }

    // ═══════════════ PROPFIND ═══════════════

    @Test
    fun `propfind depth1 lists root children with hrefs`() {
        seed("b.txt", "bbb".toByteArray())
        seed("a.txt", "aaaa".toByteArray())
        File(root, "sub").mkdir()
        val r = engine.handle(req("PROPFIND", "/", mapOf("Depth" to "1")))
        assertEquals(207, r.status)
        val xml = String(bytes(r), Charsets.UTF_8)
        assertTrue(xml.contains("/a.txt"))
        assertTrue(xml.contains("/b.txt"))
        assertTrue(xml.contains("/sub/")) // المجلد يحمل شرطة مائلة في href
        assertTrue(xml.contains("multistatus"))
    }

    @Test
    fun `propfind depth0 returns self only`() {
        File(root, "sub").mkdir()
        seed("sub/inner.txt", "x".toByteArray())
        val r = engine.handle(req("PROPFIND", "/sub", mapOf("Depth" to "0")))
        assertEquals(207, r.status)
        val xml = String(bytes(r), Charsets.UTF_8)
        assertTrue(xml.contains("/sub"))
        assertTrue(!xml.contains("/sub/inner.txt"))
    }

    @Test
    fun `propfind on missing returns 404`() {
        assertEquals(404, engine.handle(req("PROPFIND", "/ghost")).status)
    }

    // ═══════════════ GET وHEAD والمدى ═══════════════

    @Test
    fun `get returns file bytes with mime and etag`() {
        seed("doc.pdf", byteArrayOf(1, 2, 3, 4, 5))
        val r = engine.handle(req("GET", "/doc.pdf"))
        assertEquals(200, r.status)
        assertEquals("application/pdf", r.header("Content-Type"))
        assertEquals(5L, r.bodyLength)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), bytes(r))
        assertTrue(r.header("ETag")!!.startsWith("\""))
        assertTrue(r.header("Accept-Ranges") == "bytes")
    }

    @Test
    fun `get on missing returns 404`() {
        assertEquals(404, engine.handle(req("GET", "/nope.txt")).status)
    }

    @Test
    fun `head matches get headers without body`() {
        seed("x.bin", ByteArray(100))
        val r = engine.handle(req("HEAD", "/x.bin"))
        assertEquals(200, r.status)
        assertEquals(100L, r.bodyLength)
        assertEquals(0, bytes(r).size)
    }

    @Test
    fun `range middle slice returns 206`() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        seed("range.bin", data)
        val r = engine.handle(req("GET", "/range.bin", mapOf("Range" to "bytes=100-199")))
        assertEquals(206, r.status)
        assertEquals("bytes 100-199/1000", r.header("Content-Range"))
        val got = bytes(r)
        assertEquals(100, got.size)
        assertEquals(data[100], got[0])
        assertEquals(data[199], got[99])
    }

    @Test
    fun `range suffix returns last n bytes`() {
        seed("s.bin", ByteArray(300) { 7 })
        val r = engine.handle(req("GET", "/s.bin", mapOf("Range" to "bytes=-50")))
        assertEquals(206, r.status)
        assertEquals(50, bytes(r).size)
        assertEquals("bytes 250-299/300", r.header("Content-Range"))
    }

    @Test
    fun `range open end returns tail`() {
        seed("t.bin", ByteArray(10) { 3 })
        val r = engine.handle(req("GET", "/t.bin", mapOf("Range" to "bytes=8-")))
        assertEquals(206, r.status)
        assertEquals(2, bytes(r).size)
    }

    @Test
    fun `unsatisfiable range returns 416`() {
        seed("u.bin", ByteArray(100))
        val r = engine.handle(req("GET", "/u.bin", mapOf("Range" to "bytes=500-")))
        assertEquals(416, r.status)
        assertEquals("bytes */100", r.header("Content-Range"))
    }

    @Test
    fun `get on directory serves html index`() {
        File(root, "folder").mkdir()
        seed("folder/واحد.txt", "نص".toByteArray())
        val r = engine.handle(req("GET", "/folder"))
        assertEquals(200, r.status)
        assertTrue(r.header("Content-Type")!!.startsWith("text/html"))
        val html = String(bytes(r), Charsets.UTF_8)
        assertTrue(html.contains("واحد.txt"))
    }

    @Test
    fun `if-none-match hit returns 304`() {
        val f = seed("c.txt", "cc".toByteArray())
        val etag = engine.etagOf(f)
        val r = engine.handle(req("GET", "/c.txt", mapOf("If-None-Match" to etag)))
        assertEquals(304, r.status)
    }

    // ═══════════════ PUT ═══════════════

    @Test
    fun `put creates new file and returns 201 with etag`() {
        val r = engine.handle(req("PUT", "/hello.txt", body = "مرحبا".toByteArray()))
        assertEquals(201, r.status)
        assertNotNull(r.header("ETag"))
        assertEquals("مرحبا", File(root, "hello.txt").readText())
    }

    @Test
    fun `put overwrites and returns 204`() {
        seed("o.txt", "old".toByteArray())
        val r = engine.handle(req("PUT", "/o.txt", body = "new content".toByteArray()))
        assertEquals(204, r.status)
        assertEquals("new content", File(root, "o.txt").readText())
    }

    @Test
    fun `put with if-none-match star on existing returns 412`() {
        seed("p.txt", "exists".toByteArray())
        val r = engine.handle(req("PUT", "/p.txt", mapOf("If-None-Match" to "*"), "x".toByteArray()))
        assertEquals(412, r.status)
        assertEquals("exists", File(root, "p.txt").readText())
    }

    @Test
    fun `put with if-match stale etag returns 412`() {
        val f = seed("q.txt", "v1".toByteArray())
        val r = engine.handle(req("PUT", "/q.txt", mapOf("If-Match" to "\"stale-etag\""), "v2".toByteArray()))
        assertEquals(412, r.status)
        assertEquals("v1", f.readText())
    }

    @Test
    fun `put into missing parent returns 409`() {
        val r = engine.handle(req("PUT", "/missing/dir/f.txt", body = "x".toByteArray()))
        assertEquals(409, r.status)
    }

    @Test
    fun `put on root path returns 405`() {
        assertEquals(405, engine.handle(req("PUT", "/", body = "x".toByteArray())).status)
    }

    @Test
    fun `put on existing directory returns 409`() {
        File(root, "adir").mkdir()
        assertEquals(409, engine.handle(req("PUT", "/adir", body = "x".toByteArray())).status)
    }

    // ═══════════════ MKCOL ═══════════════

    @Test
    fun `mkcol creates directory`() {
        assertEquals(201, engine.handle(req("MKCOL", "/created")).status)
        assertTrue(File(root, "created").isDirectory)
    }

    @Test
    fun `mkcol on existing returns 405`() {
        File(root, "here").mkdir()
        assertEquals(405, engine.handle(req("MKCOL", "/here")).status)
    }

    @Test
    fun `mkcol with missing parent returns 409`() {
        assertEquals(409, engine.handle(req("MKCOL", "/a/b/c")).status)
    }

    // ═══════════════ DELETE ═══════════════

    @Test
    fun `delete file returns 204`() {
        seed("gone.txt", "z".toByteArray())
        assertEquals(204, engine.handle(req("DELETE", "/gone.txt")).status)
        assertTrue(!File(root, "gone.txt").exists())
    }

    @Test
    fun `delete directory recursively`() {
        File(root, "deep/nested").mkdirs()
        seed("deep/nested/leaf.txt", "leaf".toByteArray())
        assertEquals(204, engine.handle(req("DELETE", "/deep")).status)
        assertTrue(!File(root, "deep").exists())
    }

    @Test
    fun `delete missing returns 404`() {
        assertEquals(404, engine.handle(req("DELETE", "/nothing")).status)
    }

    @Test
    fun `delete root is forbidden`() {
        assertEquals(403, engine.handle(req("DELETE", "/")).status)
    }

    // ═══════════════ MOVE وCOPY ═══════════════

    @Test
    fun `move renames file`() {
        seed("src.txt", "moving".toByteArray())
        val r = engine.handle(
            req("MOVE", "/src.txt", mapOf("Destination" to "/dst.txt")),
        )
        assertEquals(201, r.status)
        assertTrue(!File(root, "src.txt").exists())
        assertEquals("moving", File(root, "dst.txt").readText())
    }

    @Test
    fun `move with absolute url destination works`() {
        seed("m1.txt", "v".toByteArray())
        val r = engine.handle(
            req("MOVE", "/m1.txt", mapOf("Destination" to "http://phone:8888/m2.txt")),
        )
        assertEquals(201, r.status)
        assertTrue(File(root, "m2.txt").exists())
    }

    @Test
    fun `move onto existing with overwrite F returns 412`() {
        seed("a1.txt", "A".toByteArray())
        seed("a2.txt", "B".toByteArray())
        val r = engine.handle(
            req("MOVE", "/a1.txt", mapOf("Destination" to "/a2.txt", "Overwrite" to "F")),
        )
        assertEquals(412, r.status)
        assertEquals("B", File(root, "a2.txt").readText())
    }

    @Test
    fun `move onto existing with overwrite replaces and returns 204`() {
        seed("b1.txt", "A".toByteArray())
        seed("b2.txt", "B".toByteArray())
        val r = engine.handle(
            req("MOVE", "/b1.txt", mapOf("Destination" to "/b2.txt", "Overwrite" to "T")),
        )
        assertEquals(204, r.status)
        assertEquals("A", File(root, "b2.txt").readText())
    }

    @Test
    fun `copy duplicates file and keeps source`() {
        seed("orig.txt", "keep me".toByteArray())
        val r = engine.handle(
            req("COPY", "/orig.txt", mapOf("Destination" to "/copy.txt")),
        )
        assertEquals(201, r.status)
        assertEquals("keep me", File(root, "orig.txt").readText())
        assertEquals("keep me", File(root, "copy.txt").readText())
    }

    @Test
    fun `copy directory recursively`() {
        File(root, "tree/sub").mkdirs()
        seed("tree/sub/leaf.txt", "L".toByteArray())
        val r = engine.handle(
            req("COPY", "/tree", mapOf("Destination" to "/tree-copy")),
        )
        assertEquals(201, r.status)
        assertEquals("L", File(root, "tree-copy/sub/leaf.txt").readText())
        assertTrue(File(root, "tree/sub/leaf.txt").exists()) // المصدر باقٍ
    }

    @Test
    fun `move missing source returns 404`() {
        assertEquals(404, engine.handle(req("MOVE", "/ghost", mapOf("Destination" to "/x"))).status)
    }

    @Test
    fun `move without destination returns 400`() {
        seed("nd.txt", "v".toByteArray())
        assertEquals(400, engine.handle(req("MOVE", "/nd.txt")).status)
    }

    @Test
    fun `move onto itself returns 403`() {
        seed("same.txt", "v".toByteArray())
        assertEquals(
            403,
            engine.handle(req("MOVE", "/same.txt", mapOf("Destination" to "/same.txt"))).status,
        )
    }

    // ═══════════════ الأقفال ═══════════════

    @Test
    fun `lock grants token and unlock releases`() {
        seed("l.txt", "v".toByteArray())
        val lock = engine.handle(req("LOCK", "/l.txt", mapOf("Timeout" to "Second-300")))
        assertEquals(200, lock.status)
        val token = lock.header("Lock-Token")!!.trim('<', '>')
        assertTrue(token.startsWith("opaquelocktoken:"))
        assertTrue(String(bytes(lock)).contains("lockdiscovery"))

        // تجديد بنفس القفل يبقي الرمز
        val refresh = engine.handle(req("LOCK", "/l.txt", mapOf("Timeout" to "Second-300")))
        assertEquals(token, refresh.header("Lock-Token")!!.trim('<', '>'))

        // رمز خاطئ يرفض
        assertEquals(
            403,
            engine.handle(req("UNLOCK", "/l.txt", mapOf("Lock-Token" to "<opaquelocktoken:wrong>"))).status,
        )
        // الرمز الصحيح يفرج
        assertEquals(204, engine.handle(req("UNLOCK", "/l.txt", mapOf("Lock-Token" to "<$token>"))).status)
    }

    @Test
    fun `unlock without token returns 400`() {
        seed("l2.txt", "v".toByteArray())
        assertEquals(400, engine.handle(req("UNLOCK", "/l2.txt")).status)
    }

    // ═══════════════ المصادقة ═══════════════

    @Test
    fun `auth required returns 401 with www-authenticate`() {
        val secured = WebDavEngine(root, auth = DavAuth("nova", "secret"))
        val r = secured.handle(req("GET", "/"))
        assertEquals(401, r.status)
        assertTrue(r.header("WWW-Authenticate")!!.contains("Basic"))
    }

    @Test
    fun `auth wrong credentials rejected`() {
        val secured = WebDavEngine(root, auth = DavAuth("nova", "secret"))
        val bad = secured.handle(
            req("GET", "/", mapOf("Authorization" to authHeader("nova", "wrong"))),
        )
        assertEquals(401, bad.status)
        val wrongUser = secured.handle(
            req("GET", "/", mapOf("Authorization" to authHeader("intruder", "secret"))),
        )
        assertEquals(401, wrongUser.status)
    }

    @Test
    fun `auth correct credentials pass through`() {
        val secured = WebDavEngine(root, auth = DavAuth("nova", "secret"))
        val ok = secured.handle(
            req("OPTIONS", "/", mapOf("Authorization" to authHeader("nova", "secret"))),
        )
        assertEquals(200, ok.status)
    }

    private fun authHeader(user: String, pass: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

    // ═══════════════ الحالات الشاذة ═══════════════

    @Test
    fun `unknown method returns 405`() {
        assertEquals(405, engine.handle(req("TELEPORT", "/x")).status)
    }

    @Test
    fun `mime map covers common types and falls back`() {
        assertEquals("image/png", DavMime.of("shot.png"))
        assertEquals("image/jpeg", DavMime.of("photo.JPG"))
        assertEquals("video/mp4", DavMime.of("clip.MP4"))
        assertEquals("application/zip", DavMime.of("bundle.zip"))
        assertEquals("application/octet-stream", DavMime.of("mystery.nova"))
        assertEquals("application/octet-stream", DavMime.of("noext"))
    }

    @Test
    fun `etag is stable and derives from size and mtime`() {
        val f = seed("e.txt", "data".toByteArray())
        val e1 = engine.etagOf(f)
        val e2 = engine.etagOf(f)
        assertEquals(e1, e2)
        f.writeBytes("different size".toByteArray())
        assertTrue(engine.etagOf(f) != e1)
    }

    @Test
    fun `http date format is rfc1123 gmt`() {
        val d = httpDate(0L)
        assertTrue(d.endsWith("GMT"))
        assertTrue(d.contains("Jan 1970") || d.contains("1 Jan 1970"))
    }
}
