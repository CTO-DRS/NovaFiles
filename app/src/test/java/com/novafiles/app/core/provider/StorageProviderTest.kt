package com.novafiles.app.core.provider

import com.novafiles.app.core.network.DavConflictException
import com.novafiles.app.core.network.FakeDavServer
import com.novafiles.app.core.network.RemoteEntry
import com.novafiles.app.core.network.WebDavFs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/** اختبارات تجريد StorageProvider — المحلي والمهايئ الشبكي وذاكرة السرد */
class StorageProviderTest {

    private lateinit var tmp: File

    @Before
    fun setup() {
        tmp = createTempDir("provider")
    }

    @After
    fun teardown() {
        tmp.deleteRecursively()
    }

    // ═══════════════ LocalStorageProvider ═══════════════

    @Test
    fun localListSortsDirsFirstThenNames() = runBlocking {
        File(tmp, "b-dir").mkdirs()
        File(tmp, "a-dir").mkdirs()
        File(tmp, "zeta.txt").writeText("z")
        File(tmp, "alpha.txt").writeText("a")
        val p = LocalStorageProvider("local-1", tmp)
        val entries = p.list("")
        assertEquals(listOf("a-dir", "b-dir", "alpha.txt", "zeta.txt"), entries.map { it.name })
        assertTrue(entries[0].isDir && !entries[2].isDir)
        p.close()
    }

    @Test
    fun localRoundtripReadWriteProgress() = runBlocking {
        val p = LocalStorageProvider("local-1", tmp)
        // المصدر خارج جذر المزوّد — الكتابة على نفس المكان ستقتطع الملف
        val outside = createTempDir("outside")
        val src = File(outside, "input.bin").apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        var progress = 0L
        p.writeFrom(src, "") { progress = it }
        assertEquals(5000L, progress)

        val target = File(tmp, "copy.bin")
        var readProgress = 0L
        p.readTo("/input.bin", target) { readProgress = it }
        assertEquals(5000L, readProgress)
        assertTrue(target.readBytes().contentEquals(src.readBytes()))
        outside.deleteRecursively()
        p.close()
    }

    @Test
    fun localMkdirRenameDeleteLifecycle() = runBlocking {
        val p = LocalStorageProvider("local-1", tmp)
        p.mkdir("", "مجلد عربي")
        val stat = p.stat("/مجلد عربي")
        assertTrue(stat.isDir)

        p.rename("/مجلد عربي", "renamed")
        assertEquals("renamed", p.stat("/renamed").name)

        p.delete("/renamed", isDir = true)
        try {
            p.stat("/renamed")
            fail("expected IOException")
        } catch (e: java.io.IOException) { }
        p.close()
    }

    @Test
    fun localRenameOntoExistingThrowsConflict() = runBlocking {
        val p = LocalStorageProvider("local-1", tmp)
        File(tmp, "a.txt").writeText("A")
        File(tmp, "b.txt").writeText("B")
        try {
            p.rename("/a.txt", "b.txt")
            fail("expected DavConflictException")
        } catch (e: DavConflictException) { }
        p.close()
    }

    @Test
    fun localPathEscapeBlocked() = runBlocking {
        val root = File(tmp, "root").apply { mkdirs() }
        val outside = File(tmp, "outside.txt").apply { writeText("secret") }
        val p = LocalStorageProvider("local-1", root)
        try {
            p.readTo("/../outside.txt", File(tmp, "leaked.bin"))
            fail("expected SecurityException")
        } catch (e: SecurityException) { }
        assertFalse(File(root, "outside.txt").exists())
        assertTrue(outside.exists())
        p.close()
    }

    @Test
    fun localCopyAndMoveAcrossDirs() = runBlocking {
        val p = LocalStorageProvider("local-1", tmp)
        File(tmp, "d1").mkdirs(); File(tmp, "d2").mkdirs()
        File(tmp, "d1/doc.txt").writeText("DATA")

        p.copy("/d1/doc.txt", "/d2", "doc-copy.txt")
        assertEquals("DATA", File(tmp, "d2/doc-copy.txt").readText())
        assertTrue(File(tmp, "d1/doc.txt").exists())

        p.move("/d1/doc.txt", "/d2", "doc-moved.txt")
        assertEquals("DATA", File(tmp, "d2/doc-moved.txt").readText())
        assertFalse(File(tmp, "d1/doc.txt").exists())
        p.close()
    }

    // ═══════════════ RemoteFsProvider فوق WebDavFs ═══════════════

    private lateinit var server: FakeDavServer

    private fun openDavProvider(id: String = "conn-7"): RemoteFsProvider {
        server = FakeDavServer(username = "u", password = "p")
        server.start()
        val fs = WebDavFs("127.0.0.1", server.port, username = "u", password = "p")
        return RemoteFsProvider(id, fs)
    }

    @Test
    fun davProviderAnnouncesWebDavCapabilities() = runBlocking {
        val p = openDavProvider()
        assertEquals("webdav", p.scheme)
        assertEquals("conn-7", p.id)
        assertTrue(p.can(ProviderCapability.SERVER_COPY))
        assertTrue(p.can(ProviderCapability.RANGE_READ))
        assertTrue(p.can(ProviderCapability.LIST))
        assertTrue(p.displayUrl.startsWith("webdav://"))
        p.close(); server.stop()
    }

    @Test
    fun davProviderListAndStatCarryEtag() = runBlocking {
        val p = openDavProvider()
        server.addFile("/doc.txt", "hello".toByteArray())
        val entries = p.list("")
        assertTrue(entries.any { it.name == "doc.txt" })
        val stat = p.stat("/doc.txt")
        assertNotNull(stat.etag)
        assertTrue(stat.etag!!.startsWith("\"e"))
        p.close(); server.stop()
    }

    @Test
    fun davProviderCopyAndMoveGoServerSide() = runBlocking {
        val p = openDavProvider()
        server.addDir("/a"); server.addDir("/b")
        server.addFile("/a/file.txt", "DATA".toByteArray())

        p.copy("/a/file.txt", "/b", "copied.txt")
        assertEquals("DATA", server.fileContent("/b/copied.txt").toString(Charsets.UTF_8))

        p.move("/a/file.txt", "/b", "moved.txt")
        assertEquals("DATA", server.fileContent("/b/moved.txt").toString(Charsets.UTF_8))
        p.close(); server.stop()
    }

    @Test
    fun davProviderRangeReadDelegates() = runBlocking {
        val p = openDavProvider()
        val payload = ByteArray(1000) { it.toByte() }
        server.addFile("/r.bin", payload)
        val out = java.io.ByteArrayOutputStream()
        p.readRangeTo("/r.bin", 800, out)
        val rest = out.toByteArray()
        assertEquals(200, rest.size)
        assertTrue(payload.copyOfRange(800, 1000).contentEquals(rest))
        p.close(); server.stop()
    }

    // ═══════════════ RemoteFsProvider فوق Stub عام ═══════════════

    /** Stub بروتوكول افتراضي — يكشف أنه بلا نسخ خادمي */
    private class StubFs : com.novafiles.app.core.network.RemoteFs {
        override val displayUrl = "stub://x"
        override suspend fun list(path: String): List<RemoteEntry> =
            listOf(RemoteEntry("f.txt", "/f.txt", false, 3, 0))
        override suspend fun download(remotePath: String, target: File, onProgress: (Long) -> Unit) {
            target.writeText("abc")
        }
        override suspend fun upload(source: File, remoteDir: String, onProgress: (Long) -> Unit) { }
        override suspend fun delete(path: String, isDir: Boolean) { }
        override suspend fun mkdir(parent: String, name: String) { }
        override suspend fun rename(path: String, newName: String) { }
        override suspend fun test(): String = "stub"
        override fun close() { }
    }

    @Test
    fun stubProviderHasNoServerCopyAndRenameInPlace() = runBlocking {
        val p = RemoteFsProvider("stub-1", StubFs())
        assertFalse(p.can(ProviderCapability.SERVER_COPY))
        assertFalse(p.can(ProviderCapability.RANGE_READ))
        assertEquals("remote", p.scheme)

        // النقل داخل المجلد نفسه يتحول لإعادة تسمية عبر الواجهة الافتراضية
        p.move("/f.txt", "", "renamed.txt")
        assertEquals("stub", p.test())
        p.close()
    }

    // ═══════════════ DavListingCache ═══════════════

    @Test
    fun cacheMissThenHitWithSameEtag() = runBlocking {
        val cache = DavListingCache()
        var loads = 0
        val entries1 = listOf(ProviderEntry("a.txt", "/a.txt", false, 1))
        val (list1, cached1) = cache.getOrLoad(
            "/dir",
            etagOf = { "\"e1\"" },
            loader = { loads++; "\"e1\"" to entries1 },
        )
        assertFalse(cached1)
        assertEquals(1, loads)

        val (list2, cached2) = cache.getOrLoad(
            "/dir",
            etagOf = { "\"e1\"" },
            loader = { error("loader must not run on cache hit") },
        )
        assertTrue(cached2)
        assertEquals(1, loads)
        assertEquals(list1, list2)
    }

    @Test
    fun cacheReloadsWhenEtagChanges() = runBlocking {
        val cache = DavListingCache()
        var etag = "\"v1\""
        val (l1, c1) = cache.getOrLoad("/d", { etag }, { etag to listOf(ProviderEntry("x", "/x", false)) })
        assertFalse(c1)
        etag = "\"v2\""
        val (l2, c2) = cache.getOrLoad("/d", { etag }, { etag to listOf(ProviderEntry("y", "/y", false)) })
        assertFalse(c2)
        assertEquals("y", l2.first().name)
    }

    @Test
    fun cacheInvalidateForcesReload() = runBlocking {
        val cache = DavListingCache()
        var loads = 0
        cache.getOrLoad("/d", { "\"e\"" }, { loads++; "\"e\"" to emptyList() })
        cache.invalidate("/d")
        cache.getOrLoad("/d", { "\"e\"" }, { loads++; "\"e\"" to emptyList() })
        assertEquals(2, loads)
    }

    @Test
    fun cacheEvictsBeyondCapacity() = runBlocking {
        val cache = DavListingCache(maxEntries = 2)
        listOf("/1", "/2", "/3").forEach { path ->
            cache.put(path, "\"e\"", emptyList())
        }
        assertEquals(2, cache.size)
    }
}
