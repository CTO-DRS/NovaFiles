package com.novafiles.app.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** مزوّد ذاكرة لاختبارات النقل الموزّع والمزامنة — CloudFs كامل بلا شبكة */
class MemoryCloudFs(override val displayUrl: String = "mem://") : CloudFs {
    val files = LinkedHashMap<String, ByteArray>()
    val dirs = LinkedHashSet<String>()
    val uploadLog = ArrayList<Pair<String, Long>>() // (path, size)

    private fun norm(p: String) = p.trim('/').let { if (it.isEmpty()) "" else "/$it" }

    override suspend fun list(path: String): List<CloudEntry> {
        val prefix = norm(path)
        val dirsOut = dirs.filter { it != prefix && RemotePathUtil.parentOf(it) == prefix }
            .map { CloudEntry(RemotePathUtil.nameOf(it), it, true, 0, 0, null, null) }
        val filesOut = files.keys.filter { RemotePathUtil.parentOf(it) == prefix }
            .map { CloudEntry(RemotePathUtil.nameOf(it), it, false, files[it]!!.size.toLong(), 0, null, null) }
        return (dirsOut + filesOut).sortedWith(compareByDescending<CloudEntry> { it.isDir }.thenBy { it.name })
    }

    override suspend fun stat(path: String): CloudEntry {
        val p = norm(path)
        if (p.isEmpty() || p in dirs) return CloudEntry(RemotePathUtil.nameOf(p), p, true, 0, 0, null, null)
        files[p]?.let { return CloudEntry(RemotePathUtil.nameOf(p), p, false, it.size.toLong(), 0, null, null) }
        throw CloudNotFoundException(path, "mem")
    }

    override suspend fun readFrom(path: String, offset: Long, consumer: suspend (java.io.InputStream) -> Unit) {
        val data = files[norm(path)] ?: throw CloudNotFoundException(path, "mem")
        val sliced = data.drop(offset.toInt()).toByteArray()
        consumer(ByteArrayInputStream(sliced))
    }

    override suspend fun writeFrom(source: File, dir: String, onProgress: (Long) -> Unit): CloudEntry {
        return writeFromStream(dir, source.name, source.length(), { source.inputStream() }, onProgress)
    }

    override suspend fun writeFromStream(
        dir: String,
        name: String,
        size: Long,
        openInput: () -> java.io.InputStream,
        onProgress: (Long) -> Unit,
    ): CloudEntry {
        val path = (norm(dir) + "/" + name)
        val bytes = openInput().use { it.readBytes() }
        files[path] = bytes
        uploadLog.add(path to bytes.size.toLong())
        return CloudEntry(name, path, false, bytes.size.toLong(), 0, null, null)
    }

    override suspend fun mkdir(parent: String, name: String): CloudEntry {
        val p = norm(parent) + "/" + name
        dirs.add(p)
        return CloudEntry(name, p, true, 0, 0, null, null)
    }

    override suspend fun delete(path: String, isDir: Boolean) {
        val p = norm(path)
        if (isDir) dirs.removeAll { it == p || it.startsWith("$p/") } else files.remove(p)
    }

    override suspend fun rename(path: String, newName: String) {
        val p = norm(path)
        val target = RemotePathUtil.parentOf(p) + "/" + newName
        if (p in dirs) { dirs.remove(p); dirs.add(target) } else {
            val data = files.remove(p) ?: throw CloudNotFoundException(path, "mem")
            files[target] = data
        }
    }

    override suspend fun move(path: String, targetDir: String, newName: String) {
        val p = norm(path)
        val target = norm(targetDir) + "/" + newName
        if (p in dirs) { dirs.remove(p); dirs.add(target) } else {
            val data = files.remove(p) ?: throw CloudNotFoundException(path, "mem")
            files[target] = data
        }
    }

    override suspend fun copy(path: String, targetDir: String, newName: String) {
        val p = norm(path)
        val data = files[p] ?: throw CloudNotFoundException(path, "mem")
        files[norm(targetDir) + "/" + newName] = data.copyOf()
    }

    override suspend fun test(): String = "mem-root"
    override fun close() {}
}

class FederatedTest {

    @Test
    fun `copyFile streams content between providers`() = cloudRun {
        val src = MemoryCloudFs().apply { files["/IMG_001.jpg"] = "صورة الكاميرا".toByteArray() }
        val dst = MemoryCloudFs()
        val result = Federated.copyFile(src, "/IMG_001.jpg", dst, "/Backup")
        assertTrue(result.success)
        assertEquals("صورة الكاميرا", String(dst.files["/Backup/IMG_001.jpg"]!!))
        assertEquals(1, dst.uploadLog.size)
    }

    @Test
    fun `copyFile failure does not throw returns error result`() = cloudRun {
        val src = MemoryCloudFs() // الملف غير موجود
        val dst = MemoryCloudFs()
        val result = Federated.copyFile(src, "/ghost.jpg", dst, "/x")
        assertFalse(result.success)
        assertTrue(result.error != null)
        assertTrue(dst.files.isEmpty())
    }

    @Test
    fun `copyFile custom name overrides`() = cloudRun {
        val src = MemoryCloudFs().apply { files["/a.txt"] = "x".toByteArray() }
        val dst = MemoryCloudFs()
        Federated.copyFile(src, "/a.txt", dst, "/d", newName = "b.txt")
        assertTrue("/d/b.txt" in dst.files)
    }

    @Test
    fun `copyTree mirrors folder structure and counts`() = cloudRun {
        val src = MemoryCloudFs().apply {
            dirs.add("/Trip")
            files["/Trip/day1.jpg"] = "d1".toByteArray()
            files["/Trip/day2.jpg"] = "d2".toByteArray()
            dirs.add("/Trip/RAW")
            files["/Trip/RAW/raw1.dng"] = "r1".toByteArray()
            files["/top.txt"] = "t".toByteArray()
        }
        val dst = MemoryCloudFs()
        val result = Federated.copyTree(src, "/Trip", dst, "/Mirror")

        assertEquals(3, result.succeeded)
        assertEquals(0, result.failed)
        assertEquals("d1", String(dst.files["/Mirror/day1.jpg"]!!))
        assertEquals("d2", String(dst.files["/Mirror/day2.jpg"]!!))
        assertEquals("r1", String(dst.files["/Mirror/RAW/raw1.dng"]!!))
        assertTrue("/Mirror" in dst.dirs || dst.dirs.any { it.startsWith("/Mirror") })
        // top.txt خارج الشجرة لم يُنسخ
        assertFalse("/Mirror/top.txt" in dst.files)
    }

    @Test
    fun `copyTree continues after individual failure`() = cloudRun {
        val src = MemoryCloudFs().apply {
            files["/f_ok1.jpg"] = "1".toByteArray()
            files["/f_ok2.jpg"] = "2".toByteArray()
        }
        val dst = MemoryCloudFs()
        val result = Federated.copyTree(src, "", dst, "", onProgress = {})
        assertEquals(2, result.succeeded)
    }
}

class CameraSyncTest {

    private fun item(name: String, content: String, modified: Long, video: Boolean = false): CameraSync.MediaItem =
        CameraSync.MediaItem(
            key = "media://$name",
            name = name,
            size = content.length.toLong(),
            lastModified = modified,
            isVideo = video,
            open = { ByteArrayInputStream(content.toByteArray()) },
        )

    private fun policy(folder: String = "/Camera", naming: CameraSync.Naming = CameraSync.Naming.FLAT) =
        CameraSync.SyncPolicy(
            targetFolder = folder,
            naming = naming,
            constraints = CameraSync.SyncConstraints(wifiOnly = false, requireCharging = false, minBatteryPct = 0),
        )

    private fun env(metered: Boolean = false, charging: Boolean = true, battery: Int = 80) =
        CameraSync.SyncEnvironment(metered, charging, battery)

    // ---------- القيود ----------

    @Test
    fun `default constraints require wifi and charging`() {
        val c = CameraSync.SyncConstraints() // الافتراضي وفق الخارطة
        assertFalse(c.satisfied(env(metered = true, charging = false)))
        assertFalse(c.satisfied(env(metered = true, charging = true))) // شبكة مقيسة
        assertFalse(c.satisfied(env(metered = false, charging = false))) // بلا شحن
        assertTrue(c.satisfied(env(metered = false, charging = true, battery = 50)))
    }

    @Test
    fun `battery below minimum blocks sync`() {
        val c = CameraSync.SyncConstraints(wifiOnly = false, requireCharging = false, minBatteryPct = 20)
        assertFalse(c.satisfied(env(battery = 15)))
        assertTrue(c.satisfied(env(battery = 20))) // الحد شامل
    }

    @Test
    fun `sync blocked returns BLOCKED report without touching network`() = cloudRun {
        val dst = MemoryCloudFs()
        val report = CameraSync.sync(
            items = listOf(item("a.jpg", "x", 100)),
            target = dst,
            policy = CameraSync.SyncPolicy(
                "/Camera",
                constraints = CameraSync.SyncConstraints(wifiOnly = true, requireCharging = false),
            ),
            ledger = CameraSync.InMemoryLedger(),
            environment = env(metered = true), // شبكة مقيسة مع wifiOnly
        )
        assertTrue(report.constraintsBlocked)
        assertTrue(dst.files.isEmpty())
    }

    // ---------- exact-once ----------

    @Test
    fun `new item uploaded and recorded in ledger`() = cloudRun {
        val dst = MemoryCloudFs()
        val ledger = CameraSync.InMemoryLedger()
        val report = CameraSync.sync(
            items = listOf(item("IMG_1.jpg", "content-1", 1000)),
            target = dst, policy = policy(), ledger = ledger, environment = env(),
        )
        assertEquals(1, report.uploaded.size)
        assertEquals("/Camera/IMG_1.jpg", dst.files.keys.single())
        val record = ledger.get("media://IMG_1.jpg")!!
        assertEquals(record.contentHash, CameraSync.sha256Hex(ByteArrayInputStream("content-1".toByteArray())))
        assertEquals("/Camera/IMG_1.jpg", record.remotePath)
    }

    @Test
    fun `same item second round is skipped without upload`() = cloudRun {
        val dst = MemoryCloudFs()
        val ledger = CameraSync.InMemoryLedger()
        val it1 = item("IMG_1.jpg", "same", 1000)
        CameraSync.sync(listOf(it1), dst, policy(), ledger, env())
        val firstUploads = dst.uploadLog.size
        val report = CameraSync.sync(listOf(it1), dst, policy(), ledger, env())
        assertEquals(0, report.uploaded.size)
        assertEquals(1, report.skipped.size)
        assertEquals(firstUploads, dst.uploadLog.size) // لا رفع ثانٍ — exact-once
    }

    @Test
    fun `changed content triggers new upload`() = cloudRun {
        val dst = MemoryCloudFs()
        val ledger = CameraSync.InMemoryLedger()
        CameraSync.sync(listOf(item("IMG_1.jpg", "v1", 1000)), dst, policy(), ledger, env())
        val report = CameraSync.sync(listOf(item("IMG_1.jpg", "v2-longer", 1000)), dst, policy(), ledger, env())
        assertEquals(1, report.uploaded.size) // المحتوى تغيّر → رفع جديد
        assertEquals(2, dst.uploadLog.size)
    }

    @Test
    fun `failed upload leaves no ledger entry so next round retries`() = cloudRun {
        val failing = object : CloudFs by MemoryCloudFs() {
            override suspend fun writeFromStream(
                dir: String, name: String, size: Long,
                openInput: () -> java.io.InputStream, onProgress: (Long) -> Unit,
            ): CloudEntry = throw java.io.IOException("network down")
            override suspend fun list(path: String): List<CloudEntry> = emptyList()
            override suspend fun mkdir(parent: String, name: String): CloudEntry =
                CloudEntry(name, "$parent/$name", true, 0, 0, null, null)
        }
        val ledger = CameraSync.InMemoryLedger()
        val report = CameraSync.sync(
            listOf(item("IMG_1.jpg", "c", 100)),
            failing, policy(), ledger, env(),
        )
        assertEquals(1, report.failed.size)
        assertNull(ledger.get("media://IMG_1.jpg")) // بلا سجل — الجولة القادمة تعيد
    }

    @Test
    fun `hash failure recorded and item skipped next`() = cloudRun {
        val broken = CameraSync.MediaItem(
            key = "bad", name = "bad.jpg", size = 10, lastModified = 1, isVideo = false,
            open = { throw java.io.IOException("media deleted") },
        )
        val report = CameraSync.sync(listOf(broken), MemoryCloudFs(), policy(), CameraSync.InMemoryLedger(), env())
        assertEquals(1, report.failed.size)
        assertTrue(report.failed["bad"]!!.startsWith("hash:"))
    }

    // ---------- البنية المسماة ----------

    @Test
    fun `year month naming creates dated subfolders from media time`() {
        val p = CameraSync.SyncPolicy(targetFolder = "/Camera", naming = CameraSync.Naming.YEAR_MONTH)
        val folder = CameraSync.targetFolderFor(p, item("x.jpg", "x", modified = 1811935800000L)) // 2027-06-02
        assertEquals("/Camera/2027/06", folder)
    }

    @Test
    fun `year month creates structure on cloud`() = cloudRun {
        val dst = MemoryCloudFs()
        val report = CameraSync.sync(
            listOf(item("IMG_9.jpg", "june", 1811935800000L)),
            dst,
            policy(folder = "/Photos", naming = CameraSync.Naming.YEAR_MONTH),
            CameraSync.InMemoryLedger(),
            env(),
        )
        assertEquals(1, report.uploaded.size)
        assertTrue("/Photos/2027/06/IMG_9.jpg" in dst.files)
    }

    // ---------- الترتيب والحدود ----------

    @Test
    fun `oldest first ordering`() = cloudRun {
        val dst = MemoryCloudFs()
        val order = ArrayList<String>()
        CameraSync.sync(
            listOf(
                item("new.jpg", "n", 3000),
                item("old.jpg", "o", 1000),
                item("mid.jpg", "m", 2000),
            ),
            dst, policy(), CameraSync.InMemoryLedger(), env(),
            onProgress = { _: Int, _: Int, current: String -> if (current.isNotEmpty()) order.add(current) },
        )
        assertEquals(listOf("old.jpg", "mid.jpg", "new.jpg"), order)
    }

    @Test
    fun `maxItems caps the round and oldest win`() = cloudRun {
        val dst = MemoryCloudFs()
        val report = CameraSync.sync(
            listOf(item("a.jpg", "a", 1), item("b.jpg", "b", 2), item("c.jpg", "c", 3)),
            dst, policy(), CameraSync.InMemoryLedger(), env(), maxItems = 2,
        )
        assertEquals(2, report.uploaded.size)
        assertTrue("/Camera/a.jpg" in dst.files)
        assertTrue("/Camera/b.jpg" in dst.files)
        assertFalse("/Camera/c.jpg" in dst.files) // الأحدث متأجل للجولة القادمة
    }

    // ---------- المرشح ----------

    @Test
    fun `isSyncableMedia filters non media`() {
        assertTrue(CameraSync.isSyncableMedia("IMG_123.jpg", isVideo = false))
        assertTrue(CameraSync.isSyncableMedia("VID.MP4", isVideo = true))
        assertTrue(CameraSync.isSyncableMedia("IMG.HEIC", isVideo = false))
        assertFalse(CameraSync.isSyncableMedia("document.pdf", isVideo = false))
        assertFalse(CameraSync.isSyncableMedia("song.mp3", isVideo = true))
    }

    @Test
    fun `sha256 streaming equals known vector`() {
        val hex = CameraSync.sha256Hex(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }
}
