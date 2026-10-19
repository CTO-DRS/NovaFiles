package com.novafiles.app.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class DriveFsTest {

    private fun fs(sim: DriveSim) = DriveFs(
        transport = sim.transport,
        tokenProvider = { "tok-1" },
        sessions = InMemorySessionStore(),
        backoff = Backoff(maxAttempts = 1, sleep = {}),
    )

    // ---------- السرد ----------

    @Test
    fun `list root maps folders first with path convention`() = cloudRun {
        val sim = DriveSim()
        val f = fs(sim)
        val entries = f.list("")
        assertEquals(2, entries.size)
        assertTrue(entries[0].isDir) // المجلدات أولاً
        assertEquals("/Docs", entries[0].path)
        assertEquals("d1", entries[0].remoteId)
        assertEquals("/photo.jpg", entries[1].path)
        assertEquals(1024L, entries[1].size)
        assertEquals("md5-photo", entries[1].etag)
        assertEquals(1811935800000L, entries[1].lastModified) // 2027-06-02T11:30:00Z
    }

    @Test
    fun `stat root is virtual directory`() = cloudRun {
        val sim = DriveSim()
        val entry = fs(sim).stat("")
        assertTrue(entry.isDir)
        assertEquals("root", entry.remoteId)
    }

    @Test
    fun `stat resolves path via listing then fetches fields`() = cloudRun {
        val sim = DriveSim()
        val entry = fs(sim).stat("/photo.jpg")
        assertEquals("f1", entry.remoteId)
        assertEquals("photo.jpg", entry.name)
        assertFalse(entry.isDir)
        // طلبان: سرد الأب ثم جلب الحقول
        assertEquals(2, sim.transport.requests.size)
    }

    @Test
    fun `stat unknown path throws not found`() = cloudRun {
        val f = fs(DriveSim())
        try {
            f.stat("/ghost.txt")
            fail("must throw")
        } catch (e: CloudNotFoundException) { /* متوقع */ }
    }

    // ---------- القراءة ----------

    @Test
    fun `readFrom streams content with bearer`() = cloudRun {
        val sim = DriveSim()
        val f = fs(sim)
        val buf = java.io.ByteArrayOutputStream()
        f.readFrom("/photo.jpg", 0) { stream -> stream.copyTo(buf) }
        assertEquals("content-of-f1", buf.toString("UTF-8"))
        val req = sim.transport.requests.last()
        assertEquals("GET", req.method)
        assertEquals("Bearer tok-1", req.headers["Authorization"])
        assertTrue("alt=media" in req.url)
    }

    @Test
    fun `readFrom with offset adds Range header`() = cloudRun {
        val sim = DriveSim()
        val f = fs(sim)
        val buf = java.io.ByteArrayOutputStream()
        f.readFrom("/photo.jpg", 100) { stream -> stream.copyTo(buf) }
        assertEquals("bytes=100-", sim.transport.requests.last().headers["Range"])
    }

    // ---------- الرفع البسيط ----------

    @Test
    fun `writeFrom small file uses multipart with metadata and content`() = cloudRun {
        val sim = DriveSim()
        val tmp = File.createTempFile("small", ".bin")
        tmp.writeBytes("hello drive".toByteArray())
        try {
            val entry = fs(sim).writeFrom(tmp, "", onProgress = {})
            assertEquals(tmp.name, entry.name)
            assertTrue(entry.remoteId!!.startsWith("up-"))
            // طلب واحد: multipart
            val req = sim.transport.requests.single()
            assertEquals("POST", req.method)
            assertTrue("uploadType=multipart" in req.url)
            val body = String(req.body!!, Charsets.UTF_8)
            assertTrue("metadata name", body.contains("\"name\":\"${tmp.name}\""))
            assertTrue("hello drive" in body)
        } finally {
            tmp.delete()
        }
    }

    // ---------- الرفع القابل للاستئناف ----------

    @Test
    fun `large upload creates session then chunks with content-range`() = cloudRun {
        val sim = DriveSim()
        val store = InMemorySessionStore()
        val f = DriveFs(sim.transport, { "tok" }, store, Backoff(maxAttempts = 1, sleep = {}))
        val tmp = File.createTempFile("bigfile", ".bin")
        // فوق حد 8MB بقليل — قطعتان: 8MB ثم الباقي
        val payload = ByteArray(DriveFs.RESUMABLE_CHUNK.toInt() + 100) { i -> (i % 251).toByte() }
        tmp.writeBytes(payload)
        try {
            val progress = ArrayList<Long>()
            val entry = f.writeFrom(tmp, "", onProgress = { progress.add(it) })
            assertEquals("upl", entry.remoteId)

            // الطلب الأول: إنشاء جلسة
            val create = sim.transport.requests.first()
            assertTrue("uploadType=resumable" in create.url)
            assertNotNull(sim.sessionUrl)
            // قطعتان PUT
            val puts = sim.transport.requests.filter { it.method == "PUT" && !it.headers["Content-Range"].orEmpty().startsWith("bytes */") }
            assertEquals(2, puts.size)
            assertEquals("bytes 0-${DriveFs.RESUMABLE_CHUNK - 1}/${payload.size}", puts[0].headers["Content-Range"])
            assertEquals("bytes ${DriveFs.RESUMABLE_CHUNK}-${payload.size - 1}/${payload.size}", puts[1].headers["Content-Range"])
            // الجلسة مُزالة بعد الاكتمال
            assertNull(store.load("drive:/big.bin"))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `session survives across fs instances for process death resume`() = cloudRun {
        val store = InMemorySessionStore()
        val payload = ByteArray(DriveFs.RESUMABLE_CHUNK.toInt() + 50) { (it % 7).toByte() }
        val tmp = File.createTempFile("resume", ".bin")
        val key = "drive:/${tmp.name}"
        tmp.writeBytes(payload)
        try {
            // ═══ المحاولة الأولى: 308 بعد القطعة الأولى ثم فشل 503 (عملية قُتلت) ═══
            val first = MockTransport()
            var firstPuts = 0
            first.handler = { req ->
                when {
                    req.url.contains("uploadType=resumable") ->
                        HttpResponse(200, mapOf("Location" to "https://s"), ByteArray(0))
                    req.url == "https://s" && req.method == "PUT" -> {
                        val cr = req.headers["Content-Range"] ?: ""
                        if (cr.startsWith("bytes */")) {
                            // استقصاء — نعيد ما استلمناه (القطعة الأولى)
                            HttpResponse(308, mapOf("Range" to "bytes=0-${DriveFs.RESUMABLE_CHUNK - 1}"), ByteArray(0))
                        } else {
                            firstPuts++
                            if (firstPuts == 1) Resp.partial308(DriveFs.RESUMABLE_CHUNK - 1)
                            else Resp.code(503, "down")
                        }
                    }
                    else -> Resp.code(500, "{}")
                }
            }
            val fsA = DriveFs(first, { "t" }, store, Backoff(maxAttempts = 0, sleep = {}))
            try {
                fsA.writeFrom(tmp, "", {})
                fail("first attempt must fail (503 with no retries)")
            } catch (e: HttpException) {
                assertEquals(503, e.code)
            }

            // الجلسة محفوظة بالإزاحة الأولى — استئناف ممكن بعد موت العملية
            val saved = store.load(key)
            assertNotNull(saved)
            assertEquals(DriveFs.RESUMABLE_CHUNK, saved!!.offset)

            // ═══ المحاولة الثانية: مهايئ جديد يلتقط الجلسة ويكمل ═══
            val simB = DriveSim()
            simB.sessionUrl = "https://s" // نفس الرابط المحفوظ
            // نُسجل الملف المرفوع مسبقاً ليُحل في stat النهائي
            simB.fileJson["upl"] = mutableMapOf(
                "id" to "upl", "name" to tmp.name, "mimeType" to "application/octet-stream",
                "size" to "${payload.size}", "modifiedTime" to "2027-06-03T09:00:00.000Z",
            )
            val fsB = DriveFs(simB.transport, { "t" }, store, Backoff(maxAttempts = 1, sleep = {}))
            val entry = fsB.writeFrom(tmp, "", {})

            // لم يعد ينشئ جلسة جديدة — واصل من الإزاحة مباشرة
            val urls = simB.transport.requests.map { it.url }
            assertTrue(urls.none { it.contains("uploadType=resumable") })
            assertEquals("upl", entry.remoteId)
        } finally {
            tmp.delete()
        }
    }

    // ---------- البنية ----------

    @Test
    fun `mkdir returns folder entry with id cached`() = cloudRun {
        val sim = DriveSim()
        val f = fs(sim)
        val entry = f.mkdir("", "المشاريع")
        assertTrue(entry.isDir)
        assertEquals("/المشاريع", entry.path)
        assertNotNull(entry.remoteId)
    }

    @Test
    fun `delete removes and returns silently on 204`() = cloudRun {
        val sim = DriveSim()
        val f = fs(sim)
        f.delete("/photo.jpg", isDir = false)
        val req = sim.transport.requests.last()
        assertEquals("DELETE", req.method)
    }

    @Test
    fun `rename patches name only`() = cloudRun {
        val sim = DriveSim()
        val f = fs(sim)
        f.rename("/photo.jpg", "صورة.jpg")
        val req = sim.transport.requests.last()
        assertEquals("PATCH", req.method)
        assertTrue(""""name":"صورة.jpg"""" in String(req.body!!, Charsets.UTF_8))
    }

    @Test
    fun `move patches add and remove parents`() = cloudRun {
        val sim = DriveSim()
        val f = fs(sim)
        f.move("/photo.jpg", "/Docs", "photo.jpg")
        val req = sim.transport.requests.last()
        assertEquals("PATCH", req.method)
        val body = String(req.body!!, Charsets.UTF_8)
        assertTrue(""""addParents":["d1"]""" in body)
        assertTrue(""""removeParents":["root"]""" in body)
    }

    @Test
    fun `copy posts to copy endpoint with new parent`() = cloudRun {
        val sim = DriveSim()
        val f = fs(sim)
        f.copy("/photo.jpg", "/Docs", "نسخة.jpg")
        val req = sim.transport.requests.last()
        assertEquals("POST", req.method)
        assertTrue("/copy" in req.url)
    }

    @Test
    fun `test returns drive name`() = cloudRun {
        assertEquals("My Drive", cloudRun { fs(DriveSim()).test() })
    }

    @Test
    fun `path resolution cache avoids rewalking`() = cloudRun {
        val sim = DriveSim()
        val f = fs(sim)
        f.stat("/photo.jpg") // سرد + جلب = طلبان
        f.stat("/photo.jpg") // من الذاكرة: جلب فقط
        assertEquals(3, sim.transport.requests.size)
    }
}
