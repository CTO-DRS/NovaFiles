package com.novafiles.app.core.network

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * اختبارات عميل WebDAV ضد خادم مدمج — تغطي كل الأفعال والمصادقة
 * والترميز والتعارضات والمدى البايتي، إضافة لاختبارات أداء المحلل.
 */
class WebDavFsTest {

    private lateinit var server: FakeDavServer
    private lateinit var fs: WebDavFs
    private lateinit var tmp: File

    @Before
    fun setup() {
        server = FakeDavServer(username = "admin", password = "secret")
        server.start()
        server.addDir("/photos")
        server.addFile("/photos/تقرير السنة.txt", "مرحبا بالعالم".toByteArray(Charsets.UTF_8))
        server.addFile("/photos/emoji 📁.bin", byteArrayOf(1, 2, 3, 4, 5))
        server.addFile("/photos/sp ace.bin", byteArrayOf(9, 9))
        fs = WebDavFs(
            host = "127.0.0.1",
            port = server.port,
            username = "admin",
            password = "secret",
        )
        tmp = createTempDir("webdavtest")
    }

    @After
    fun teardown() {
        runCatching { fs.close() }
        runCatching { server.stop() }
        tmp.deleteRecursively()
    }

    private fun localFile(name: String) = File(tmp, name)

    // ═══════════════ الاتصال والسرد ═══════════════

    @Test
    fun testHandshakeReturnsRootName() = runBlocking {
        assertEquals("WebDAV", fs.test())
    }

    @Test
    fun listReturnsEntriesWithArabicEmojiAndSpaces() = runBlocking {
        val root = fs.list("")
        assertTrue(root.any { it.name == "photos" && it.isDir })
        val entries = fs.list("/photos")
        val names = entries.map { it.name }.toSet()
        assertTrue("تقرير السنة.txt" in names)
        assertTrue("emoji 📁.bin" in names)
        assertTrue("sp ace.bin" in names)
    }

    @Test
    fun listDecodesPercentEncodedHrefs() = runBlocking {
        // الخادم يرمّز href فعلياً — نتحقق أن المسارات فُكّت سليمة
        val entries = fs.list("/photos")
        val expectedSize = "مرحبا بالعالم".toByteArray(Charsets.UTF_8).size.toLong()
        assertTrue(entries.any { it.path == "/photos/تقرير السنة.txt" && it.size == expectedSize })
        assertTrue(entries.any { it.path == "/photos/emoji 📁.bin" && it.size == 5L })
    }

    @Test
    fun listMissingDirThrows() = runBlocking {
        try {
            fs.list("/nope")
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue("not found" in (e.message ?: ""))
        }
    }

    @Test
    fun wrongPasswordThrowsUnauthorized() = runBlocking {
        val bad = WebDavFs("127.0.0.1", server.port, username = "admin", password = "wrong")
        try {
            bad.test()
            fail("expected DavUnauthorizedException")
        } catch (e: DavUnauthorizedException) {
            assertTrue("401" in (e.message ?: ""))
        } finally {
            bad.close()
        }
    }

    // ═══════════════ الكتابة والحذف ═══════════════

    @Test
    fun mkdirCreatesVisibleDirectory() = runBlocking {
        fs.mkdir("", "أرشيف جديد")
        val entries = fs.list("")
        assertTrue(entries.any { it.name == "أرشيف جديد" && it.isDir })
    }

    @Test
    fun uploadThenDownloadRoundtripPreservesBytes() = runBlocking {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val src = localFile("upload.bin").apply { writeBytes(payload) }
        var reported = 0L
        fs.upload(src, "/photos") { reported = it }
        assertEquals(payload.size.toLong(), reported)

        val target = localFile("download.bin")
        var dlProgress = 0L
        fs.download("/photos/upload.bin", target) { dlProgress = it }
        assertEquals(payload.size.toLong(), dlProgress)
        assertTrue(payload.contentEquals(target.readBytes()))
    }

    @Test
    fun uploadForbidOverwriteThrowsConflict() = runBlocking {
        server.addFile("/photos/exists.txt", "old".toByteArray())
        val src = localFile("new.txt").apply { writeText("new") }
        try {
            fs.uploadGuarded(src, "/photos/exists.txt", forbidOverwrite = true)
            fail("expected DavConflictException")
        } catch (e: DavConflictException) {
            assertEquals(1, server.conflictCount.get())
        }
        assertEquals("old", server.fileContent("/photos/exists.txt").toString(Charsets.UTF_8))
    }

    @Test
    fun uploadWithStaleEtagThrowsConflict() = runBlocking {
        server.addFile("/photos/doc.txt", "v1".toByteArray())
        val staleEtag = server.etagOf("/photos/doc.txt")
        // يتغيّر الملف على الخادم من مصدر آخر
        server.addFile("/photos/doc.txt", "v2-changed".toByteArray())
        val src = localFile("local.txt").apply { writeText("mine") }
        try {
            fs.uploadGuarded(src, "/photos/doc.txt", ifMatchEtag = staleEtag)
            fail("expected DavConflictException")
        } catch (e: DavConflictException) {
            // متوقع — If-Match لم يعد مطابقاً
        }
    }

    @Test
    fun renameMovesWithinSameParent() = runBlocking {
        server.addFile("/photos/old name.txt", "x".toByteArray())
        fs.rename("/photos/old name.txt", "اسم جديد.txt")
        val names = fs.list("/photos").map { it.name }.toSet()
        assertTrue("اسم جديد.txt" in names)
        assertTrue("old name.txt" !in names)
    }

    @Test
    fun renameOntoExistingThrowsConflict() = runBlocking {
        server.addFile("/photos/a.txt", "A".toByteArray())
        server.addFile("/photos/b.txt", "B".toByteArray())
        try {
            fs.rename("/photos/a.txt", "b.txt")
            fail("expected DavConflictException")
        } catch (e: DavConflictException) {
            // متوقع
        }
    }

    @Test
    fun copyServerSideDuplicatesWithoutLocalTransfer() = runBlocking {
        server.addFile("/photos/orig.txt", "COPY ME".toByteArray())
        fs.copyServerSide("/photos/orig.txt", "", "orig-copy.txt")
        assertEquals("COPY ME", server.fileContent("/orig-copy.txt").toString(Charsets.UTF_8))
        assertEquals("COPY ME", server.fileContent("/photos/orig.txt").toString(Charsets.UTF_8))
    }

    @Test
    fun deleteRemovesAndIsIdempotent() = runBlocking {
        server.addFile("/photos/doomed.txt", "x".toByteArray())
        fs.delete("/photos/doomed.txt", isDir = false)
        try {
            server.fileContent("/photos/doomed.txt")
            fail("should be gone")
        } catch (_: IOException) { }
        // حذف غير موجود = نجاح (سلوك idempotent موثق)
        fs.delete("/photos/doomed.txt", isDir = false)
    }

    // ═══════════════ المدى البايتي والاستئناف ═══════════════

    @Test
    fun readRangeResumesFromOffset() = runBlocking {
        val payload = ByteArray(10_000) { (it and 0xFF).toByte() }
        server.addFile("/photos/big.bin", payload)

        val collected = java.io.ByteArrayOutputStream()
        fs.readRange("/photos/big.bin", offset = 4_000, sink = collected)
        val resumed = collected.toByteArray()
        assertEquals(payload.size - 4_000, resumed.size)
        assertTrue(payload.copyOfRange(4_000, payload.size).contentEquals(resumed))
    }

    @Test
    fun statDavReturnsEtagAndSize() = runBlocking {
        server.addFile("/photos/known.txt", "12345".toByteArray())
        val res = fs.statDav("/photos/known.txt")
        assertNotNull(res)
        assertEquals(5L, res!!.contentLength)
        assertTrue(res.etag?.startsWith("\"e") == true)
        assertNull(fs.statDav("/photos/missing.txt"))
    }

    // ═══════════════ الأداء — معيار الخارطة ═══════════════

    @Test
    fun tenThousandEntriesParseUnder1_5Seconds() {
        // بناء رد 207 بعشرة آلاف عنصر (نمط Nextcloud: href مرمز + etag)
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\"?><D:multistatus xmlns:D=\"DAV:\">")
        repeat(10_000) { i ->
            sb.append("<D:response><D:href>/dav/file_")
                .append(i)
                .append("_مجلد_بيانات_طويل_الاسم_عمداً.txt</D:href>")
                .append("<D:propstat><D:prop>")
                .append("<D:displayname>file_").append(i).append("مجلد_بيانات_طويل_الاسم_عمداً.txt</D:displayname>")
                .append("<D:resourcetype/>")
                .append("<D:getcontentlength>").append(1000 + i).append("</D:getcontentlength>")
                .append("<D:getlastmodified>Tue, 15 Nov 2094 12:45:26 GMT</D:getlastmodified>")
                .append("<D:getetag>\"etag-").append(i).append("\"</D:getetag>")
                .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        }
        sb.append("</D:multistatus>")
        val body = sb.toString().toByteArray(Charsets.UTF_8)

        val start = System.nanoTime()
        val parsed = DavParser.parseMultistatus(body.inputStream())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertEquals(10_000, parsed.size)
        assertTrue(
            "parse took ${elapsedMs}ms — over the 1500ms budget",
            elapsedMs < 1500,
        )
    }
}

/** اختبارات محلل Multi-Status — تحمّل اختلافات الخوادم */
class DavParserTest {

    @Test
    fun parsesNoNamespaceVariant() {
        // بعض الخوادم تستخدم xmlns افتراضياً بلا بادئة
        val xml = """
            <?xml version="1.0"?>
            <multistatus xmlns="DAV:">
              <response>
                <href>/dav/مجلد/</href>
                <propstat><prop>
                  <resourcetype><collection/></resourcetype>
                  <getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</getlastmodified>
                </prop><status>HTTP/1.1 200 OK</status></propstat>
              </response>
              <response>
                <href>/dav/مجلد/ملف.txt</href>
                <propstat><prop>
                  <resourcetype/>
                  <getcontentlength>42</getcontentlength>
                </prop><status>HTTP/1.1 200 OK</status></propstat>
              </response>
            </multistatus>
        """.trimIndent()
        val parsed = DavParser.parseMultistatus(xml.byteInputStream())
        assertEquals(2, parsed.size)
        assertEquals("/dav/مجلد", parsed[0].path)
        assertTrue(parsed[0].isCollection)
        assertEquals("/dav/مجلد/ملف.txt", parsed[1].path)
        assertEquals(42L, parsed[1].contentLength)
    }

    @Test
    fun parsesAbsoluteUrlHref() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>https://cloud.example.com/remote.php/dav/files/admin/report.pdf</D:href>
                <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val parsed = DavParser.parseMultistatus(xml.byteInputStream())
        assertEquals("/remote.php/dav/files/admin/report.pdf", parsed[0].path)
        assertEquals("report.pdf", parsed[0].displayName)
    }

    @Test
    fun parsesIso8601ModificationDateFallback() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/x.txt</D:href>
                <D:propstat><D:prop>
                  <D:resourcetype/>
                  <D:modificationdate>2026-01-15T10:30:00Z</D:modificationdate>
                </D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val parsed = DavParser.parseMultistatus(xml.byteInputStream())
        assertTrue(parsed[0].lastModified > 1_700_000_000_000L)
    }

    @Test
    fun missingPropsGetSafeDefaults() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/bare.txt</D:href>
                <D:propstat><D:prop><D:resourcetype/></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val parsed = DavParser.parseMultistatus(xml.byteInputStream())
        assertEquals(0L, parsed[0].contentLength)
        assertEquals(0L, parsed[0].lastModified)
        assertNull(parsed[0].etag)
        assertEquals("bare.txt", parsed[0].displayName) // من href
    }

    @Test
    fun percentDecodeHandlesUtf8AndKeepsInvalid() {
        assertEquals("تقرير.txt", DavParser.percentDecode("%D8%AA%D9%82%D8%B1%D9%8A%D8%B1.txt"))
        assertEquals("sp ace", DavParser.percentDecode("sp%20ace"))
        assertEquals("100%done", DavParser.percentDecode("100%done")) // غير صالح يبقى
        assertEquals("/keep/slash", DavParser.percentDecode("/keep/slash"))
    }

    @Test
    fun hrefToPathDecodesPerSegmentSafely() {
        // الملفات المرّمزة داخل المقاطع تُفكّ بعد التقسيم — لا تفسد بقية المسار
        assertEquals("/dav/a/b.txt", DavParser.hrefToPath("/dav/a%2Fb.txt"))
        assertEquals("/dav", DavParser.hrefToPath("/dav/"))
        assertEquals("/", DavParser.hrefToPath("/"))
        assertEquals("/د/ص", DavParser.hrefToPath("/%D8%AF/%D8%B5"))
    }
}
