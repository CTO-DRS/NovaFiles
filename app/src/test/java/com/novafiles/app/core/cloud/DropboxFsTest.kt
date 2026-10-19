package com.novafiles.app.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class DropboxFsTest {

    private fun fs(sim: DropboxSim) = DropboxFs(
        transport = sim.transport,
        tokenProvider = { "dbx-tok" },
        sessions = InMemorySessionStore(),
        backoff = Backoff(maxAttempts = 1, sleep = {}),
    )

    @Test
    fun `list uses rpc list_folder with root path and maps tags`() = cloudRun {
        val sim = DropboxSim()
        val entries = fs(sim).list("")
        assertEquals(2, entries.size)
        assertTrue(entries[0].isDir)
        assertEquals("/Docs", entries[0].path)
        assertEquals("id:d1", entries[0].remoteId)
        assertEquals("/photo.jpg", entries[1].path)
        assertEquals("hash-photo", entries[1].etag) // content_hash يُستخدم etag
        val req = sim.transport.requests.first()
        assertEquals("POST", req.method)
        assertTrue("list_folder" in req.url)
        assertTrue("Bearer dbx-tok" == req.headers["Authorization"])
    }

    @Test
    fun `stat empty path is virtual root`() = cloudRun {
        val entry = fs(DropboxSim()).stat("")
        assertTrue(entry.isDir)
    }

    @Test
    fun `stat missing throws not found on 409 not_found`() = cloudRun {
        val f = fs(DropboxSim())
        try {
            f.stat("/ghost")
            fail("must throw")
        } catch (e: CloudNotFoundException) { /* متوقع */ }
    }

    @Test
    fun `download streams content with api-arg header`() = cloudRun {
        val sim = DropboxSim()
        val f = fs(sim)
        val buf = java.io.ByteArrayOutputStream()
        f.readFrom("/photo.jpg", 0) { stream -> stream.copyTo(buf) }
        assertEquals("dropbox-content", buf.toString("UTF-8"))
        val req = sim.transport.requests.first()
        assertTrue("files/download" in req.url)
        assertTrue("""{"path":"/photo.jpg"}""" == req.headers["Dropbox-API-Arg"])
    }

    @Test
    fun `resume download adds range header`() = cloudRun {
        val sim = DropboxSim()
        val buf = java.io.ByteArrayOutputStream()
        fs(sim).readFrom("/photo.jpg", 500) { stream -> stream.copyTo(buf) }
        assertEquals("bytes=500-", sim.transport.requests.first().headers["Range"])
    }

    @Test
    fun `small upload posts content with api-arg and overwrite mode`() = cloudRun {
        val sim = DropboxSim()
        val tmp = File.createTempFile("note1", ".txt")
        tmp.writeText("مرحبا دروبوكس")
        try {
            val entry = fs(sim).writeFrom(tmp, "/Docs", onProgress = {})
            assertEquals("/Docs/" + tmp.name, entry.path)
            val req = sim.transport.requests.first()
            assertTrue("files/upload" in req.url)
            val arg = req.headers["Dropbox-API-Arg"]!!
            assertTrue(""""path":"/Docs/${tmp.name}"""" in arg)
            assertTrue("overwrite" in arg)
            assertEquals("مرحبا دروبوكس", String(req.body!!, Charsets.UTF_8))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `large upload uses session start append finish`() = cloudRun {
        val sim = DropboxSim()
        val store = InMemorySessionStore()
        val f = DropboxFs(sim.transport, { "t" }, store, Backoff(maxAttempts = 1, sleep = {}))
        val tmp = File.createTempFile("bigfile", ".bin")
        val payload = ByteArray(DropboxFs.SIMPLE_UPLOAD_LIMIT.toInt() + 1) { (it % 13).toByte() }
        tmp.writeBytes(payload)
        try {
            val entry = f.writeFrom(tmp, "", onProgress = {})
            assertEquals("/" + tmp.name, entry.path)

            val urls = sim.transport.requests.map { it.url }
            assertTrue(urls.any { it.contains("upload_session/start") })
            assertEquals(12, urls.count { it.contains("upload_session/append_v2") }) // 13 قطعة: 12 append + finish
            assertTrue(urls.any { it.contains("upload_session/finish") })
            // جلسة مُزالة بعد الاكتمال
            assertNull(store.load("dropbox:/" + tmp.name))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `session upload resumes from stored offset on second instance`() = cloudRun {
        val store = InMemorySessionStore()
        val sim = DropboxSim()

        // حد رفع بسيط صغير يُجبر مسار الجلسة لملف 8 بايت
        val payload = ByteArray(8) { it.toByte() }
        val tmp = File.createTempFile("res", ".bin")
        store.save("dropbox:/${tmp.name}", UploadSessionStore.UploadSession("sid-saved", offset = 4, createdAtMs = System.currentTimeMillis(), totalBytes = 8))
        tmp.writeBytes(payload)
        try {
            val f = DropboxFs(sim.transport, { "dbx-tok" }, store, Backoff(maxAttempts = 1, sleep = {}), simpleUploadLimit = 4)
            f.writeFrom(tmp, "", {})
            // المهايئ استأنف من 4 — لا appends، وfinish مباشرة بالباقي (طول 4)
            val appends = sim.transport.requests.filter { it.url.contains("append_v2") }
            assertEquals(0, appends.size)
            assertTrue(sim.transport.requests.any { it.url.contains("upload_session/finish") })
            val finishBody = sim.transport.streamedBodies.last()
            assertEquals(4, finishBody.size) // من الإزاحة إلى النهاية
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `mkdir posts create_folder_v2`() = cloudRun {
        val sim = DropboxSim()
        val entry = fs(sim).mkdir("", "مجلد جديد")
        assertTrue(entry.isDir)
        assertEquals("/مجلد جديد", entry.path)
        val req = sim.transport.requests.first()
        assertTrue("create_folder_v2" in req.url)
    }

    @Test
    fun `delete posts delete_v2 and missing maps not found`() = cloudRun {
        val sim = DropboxSim()
        fs(sim).delete("/photo.jpg", isDir = false)
        assertTrue(sim.transport.requests.first().url.contains("delete_v2"))
        try {
            fs(sim).delete("/ghost", isDir = false)
            fail("must throw")
        } catch (e: CloudNotFoundException) { /* متوقع */ }
    }

    @Test
    fun `move posts move_v2 with from and to`() = cloudRun {
        val sim = DropboxSim()
        fs(sim).move("/photo.jpg", "/Docs", "photo.jpg")
        val req = sim.transport.requests.first()
        assertTrue("move_v2" in req.url)
        val body = String(req.body!!, Charsets.UTF_8)
        assertTrue("""/photo.jpg""" in body)
        assertTrue("""/Docs/photo.jpg""" in body)
    }

    @Test
    fun `copy posts copy_v2`() = cloudRun {
        val sim = DropboxSim()
        fs(sim).copy("/photo.jpg", "/Docs", "copy.jpg")
        assertTrue(sim.transport.requests.first().url.contains("copy_v2"))
    }

    @Test
    fun `test succeeds against root metadata`() = cloudRun {
        // Dropbox يعيد للجذر name فارغاً — العقد: الاستدعاء ينجح بلا رمي
        val name = cloudRun { fs(DropboxSim()).test() }
        assertEquals("", name)
    }

    @Test
    fun `rate limit 429 with retry-after then success`() = cloudRun {
        val sim = DropboxSim()
        sim.transport.queue.add(Resp.code(429, """{"error":"ratelimit"}""", mapOf("Retry-After" to "0")))
        val entries = fs(sim).list("")
        assertEquals(2, entries.size)
        assertEquals(2, sim.transport.requests.size) // الأولى 429 والثانية نجحت
    }
}
