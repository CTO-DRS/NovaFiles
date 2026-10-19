package com.novafiles.app.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class OneDriveFsTest {

    private fun fs(sim: GraphSim) = OneDriveFs(
        transport = sim.transport,
        tokenProvider = { "ms-tok" },
        sessions = InMemorySessionStore(),
        backoff = Backoff(maxAttempts = 1, sleep = {}),
    )

    @Test
    fun `list hits children endpoint and maps folder facet`() = cloudRun {
        val sim = GraphSim()
        val entries = fs(sim).list("")
        assertEquals(2, entries.size)
        assertTrue(entries[0].isDir)
        assertEquals("/Docs", entries[0].path)
        assertEquals("id-docs", entries[0].remoteId)
        assertEquals("etag-id-photo", entries[1].etag)
        val req = sim.transport.requests.first()
        assertTrue("/me/drive/root/children" in req.url)
        assertEquals("Bearer ms-tok", req.headers["Authorization"])
    }

    @Test
    fun `stat path-encoded item`() = cloudRun {
        val sim = GraphSim()
        val entry = fs(sim).stat("/photo.jpg")
        assertEquals("id-photo", entry.remoteId)
        assertFalse(entry.isDir)
        assertEquals(1024L, entry.size)
        val req = sim.transport.requests.first()
        assertTrue("root:/photo.jpg:/" in req.url)
    }

    @Test
    fun `stat missing throws not found`() = cloudRun {
        try {
            fs(GraphSim()).stat("/ghost.png")
            fail("must throw")
        } catch (e: CloudNotFoundException) { /* متوقع */ }
    }

    @Test
    fun `download from content endpoint`() = cloudRun {
        val sim = GraphSim()
        val buf = java.io.ByteArrayOutputStream()
        fs(sim).readFrom("/photo.jpg", 0) { stream -> stream.copyTo(buf) }
        assertEquals("graph-content", buf.toString("UTF-8"))
        assertTrue(sim.transport.requests.last().url.contains("/items/id-photo/content"))
    }

    @Test
    fun `small upload PUTs to path content`() = cloudRun {
        val sim = GraphSim()
        val tmp = File.createTempFile("doc1", ".txt")
        tmp.writeText("مرحبا ون درايف")
        try {
            val entry = fs(sim).writeFrom(tmp, "/Docs", onProgress = {})
            assertEquals("/Docs/" + tmp.name, entry.path)
            val req = sim.transport.requests.first()
            assertEquals("PUT", req.method)
            assertTrue(":/content" in req.url)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `large upload creates session and chunks multiples of 320KiB`() = cloudRun {
        val sim = GraphSim()
        val store = InMemorySessionStore()
        val f = OneDriveFs(sim.transport, { "t" }, store, Backoff(maxAttempts = 1, sleep = {}))
        val tmp = File.createTempFile("bigfile", ".bin")
        val payload = ByteArray(OneDriveFs.SESSION_CHUNK.toInt() + 10) { (it % 31).toByte() }
        tmp.writeBytes(payload)
        try {
            val entry = f.writeFrom(tmp, "", onProgress = {})
            assertTrue("remoteId: ${entry.remoteId}", entry.remoteId!!.startsWith("id-upl"))
            assertTrue(sim.transport.requests.first().url.contains("createUploadSession"))
            val puts = sim.transport.requests.filter { it.method == "PUT" && it.url == sim.uploadUrl }
            assertEquals(2, puts.size)
            assertEquals("bytes 0-${OneDriveFs.SESSION_CHUNK - 1}/${payload.size}", puts[0].headers["Content-Range"])
            assertEquals("bytes ${OneDriveFs.SESSION_CHUNK}-${payload.size - 1}/${payload.size}", puts[1].headers["Content-Range"])
            assertNull(store.load("onedrive:/" + tmp.name))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `session resumes via nextExpectedRanges when server lags`() = cloudRun {
        val sim = GraphSim()
        val tmp = File.createTempFile("res2", ".bin")
        val payload = ByteArray(OneDriveFs.SESSION_CHUNK.toInt()) { 5 }
        tmp.writeBytes(payload)
        // القطعة الأولى تعيد 202، ثم نُجبر استقصاء بإزاحة خادمية مختلفة
        sim.uploadUrl = "https://u"
        var puts = 0
        sim.transport.handler = { req ->
            when {
                req.url.contains("createUploadSession") ->
                    Resp.ok("""{"uploadUrl":"https://u"}""")
                req.method == "GET" && req.url.contains("root:") ->
                    Resp.ok("""{"id":"id-x","name":"x","size":"${payload.size}","lastModifiedDateTime":"2027-06-02T11:30:00.000Z","eTag":"e1","folder":null}""")
                req.url == "https://u" && req.method == "PUT" -> {
                    val cr = req.headers["Content-Range"] ?: ""
                    if (cr.startsWith("bytes */")) {
                        Resp.code(200, """{"nextExpectedRanges":["0-"]}""")
                    } else {
                        puts++
                        if (puts == 1) Resp.code(500, "boom") // إخفاق عابر
                        else Resp.code(202, "{}")
                    }
                }
                else -> Resp.code(500, "{}")
            }
        }
        try {
            val f = OneDriveFs(sim.transport, { "t" }, InMemorySessionStore(), Backoff(maxAttempts = 2, sleep = {}))
            f.writeFrom(tmp, "", {})
            // بعد 500: استقصاء nextExpectedRanges ثم إعادة القطعة من 0
            assertTrue(sim.transport.requests.any { it.headers["Content-Range"] == "bytes */${payload.size}" })
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `mkdir posts folder facet`() = cloudRun {
        val sim = GraphSim()
        val entry = fs(sim).mkdir("", "Screenshots")
        assertTrue(entry.isDir)
        val req = sim.transport.requests.first()
        assertTrue(req.url.contains("/children"))
        assertTrue("folder" in String(req.body!!, Charsets.UTF_8))
    }

    @Test
    fun `delete hits items id`() = cloudRun {
        val sim = GraphSim()
        fs(sim).delete("/photo.jpg", isDir = false)
        val req = sim.transport.requests.last()
        assertEquals("DELETE", req.method)
        assertTrue("/items/id-photo" in req.url)
    }

    @Test
    fun `rename patches name`() = cloudRun {
        val sim = GraphSim()
        fs(sim).rename("/photo.jpg", "صورة.jpg")
        val req = sim.transport.requests.last()
        assertEquals("PATCH", req.method)
        assertTrue(""""name":"صورة.jpg"""" in String(req.body!!, Charsets.UTF_8))
    }

    @Test
    fun `move patches parentReference id`() = cloudRun {
        val sim = GraphSim()
        fs(sim).move("/photo.jpg", "/Docs", "photo.jpg")
        val req = sim.transport.requests.last()
        assertEquals("PATCH", req.method)
        val body = String(req.body!!, Charsets.UTF_8)
        assertTrue("parentReference" in body)
        assertTrue("id-docs" in body)
    }

    @Test
    fun `copy is async 202 then monitor completed`() = cloudRun {
        val sim = GraphSim()
        fs(sim).copy("/photo.jpg", "/Docs", "copy.jpg")
        val post = sim.transport.requests.first { it.method == "POST" }
        assertTrue("/copy" in post.url)
        val monitor = sim.transport.requests.last()
        assertTrue(monitor.url.startsWith("https://monitor.graph.test"))
    }

    @Test
    fun `test returns root name`() = cloudRun {
        assertEquals("root", cloudRun { fs(GraphSim()).test() })
    }

    @Test
    fun `iso date parser handles Z and offset`() {
        assertEquals(1811935800000L, IsoDate.toMs("2027-06-02T11:30:00.000Z"))
        assertEquals(1811935800000L, IsoDate.toMs("2027-06-02T11:30:00.0000000+00:00"))
        assertEquals(0L, IsoDate.toMs("garbage"))
    }
}
