package com.novafiles.app.core.analyzer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** اختبارات ماسح المخلفات — القواعد الأربع و«لا حذف صامت» */
class JunkScannerTest {

    private lateinit var root: File
    private val now = 1_800_000_000_000L

    @org.junit.Before
    fun setup() {
        root = createTempDir("junk")
    }

    @org.junit.After
    fun teardown() {
        root.deleteRecursively()
    }

    private fun old(file: File, daysAgo: Int = 30): File {
        file.setLastModified(now - daysAgo * 86_400_000L)
        return file
    }

    private suspend fun scan(rootDir: File = root, minAgeDays: Int = 7) = JunkScanner().scan(
        root = rootDir,
        nowMs = now,
        minAgeDays = minAgeDays,
    )

    // ═══════════════ بقايا التنزيلات ═══════════════

    @Test
    fun `nfpart and nfjournal leftovers detected`() = runBlocking {
        val p = old(File(root, "movie.mkv.nfpart").apply { writeBytes(ByteArray(100)) })
        val j = old(File(root, "movie.mkv.nfjournal").apply { writeBytes(ByteArray(10)) })
        val fresh = File(root, "fresh.nfpart").apply { writeBytes(ByteArray(5)); setLastModified(now - 86_400_000L) } // يوم واحد — يُحفظ
        val report = scan()
        val leftovers = report.byCategory(JunkCategory.LEFTOVER_PARTIALS).map { it.path }
        assertTrue(p.path in leftovers)
        assertTrue(j.path in leftovers)
        assertTrue(fresh.path !in leftovers)
    }

    @Test
    fun `crdownload and tmp detected when old enough`() = runBlocking {
        old(File(root, "doc.pdf.crdownload").apply { writeBytes(ByteArray(50)) })
        old(File(root, "temp.tmp").apply { writeBytes(ByteArray(20)) }, daysAgo = 3)
        val report = scan(minAgeDays = 7)
        val names = report.byCategory(JunkCategory.LEFTOVER_PARTIALS).map { it.path.substringAfterLast('/') }
        assertTrue("doc.pdf.crdownload" in names)
        assertTrue("temp.tmp أحدث من الحد — يجب إسقاطه", "temp.tmp" !in names)
    }

    @Test
    fun `regular files never flagged`() = runBlocking {
        // v0.29: المحتوى يجب أن يختلف — مكررات المحتوى (بصمة لا اسم) تُكتشف في المنظّف
        File(root, "photo.jpg").writeText("jpeg-content-alpha")
        File(root, "notes.txt").writeText("text-content-beta")
        val report = scan()
        assertTrue(report.items.isEmpty())
    }

    // ═══════════════ الأدلة الفارغة ═══════════════

    @Test
    fun `empty leaf dirs detected`() = runBlocking {
        File(root, "empty-a").mkdirs()
        File(root, "empty-b/deeper").mkdirs()
        File(root, "full").mkdirs()
        File(root, "full/x.txt").writeText("x")
        val report = scan()
        val empty = report.byCategory(JunkCategory.EMPTY_DIRS).map { it.path }
        assertEquals(2, empty.size)
        assertTrue(File(root, "empty-a").absolutePath in empty)
        assertTrue(File(root, "empty-b/deeper").absolutePath in empty)
    }

    @Test
    fun `root itself never flagged as empty dir`() = runBlocking {
        val report = scan(root)
        assertTrue(report.byCategory(JunkCategory.EMPTY_DIRS).isEmpty())
    }

    // ═══════════════ المصغرات ═══════════════

    @Test
    fun `thumbnails dir contents all flagged`() = runBlocking {
        val thumbs = File(root, "Android/data/.thumbnails").apply { mkdirs() }
        val t1 = File(thumbs, "12345.jpg").apply { writeBytes(ByteArray(300)) }
        val t2 = File(thumbs, "67890.png").apply { writeBytes(ByteArray(200)) }
        val report = scan()
        val thumbItems = report.byCategory(JunkCategory.STALE_THUMBS).map { it.path }
        assertEquals(setOf(t1.path, t2.path), thumbItems.toSet())
        assertEquals(500L, report.bytes(JunkCategory.STALE_THUMBS))
    }

    @Test
    fun `stray thumb files outside dirs flagged`() = runBlocking {
        val stray = old(File(root, "img_20240101.thumb").apply { writeBytes(ByteArray(64)) })
        val report = scan()
        assertTrue(stray.path in report.byCategory(JunkCategory.STALE_THUMBS).map { it.path })
    }

    // ═══════════════ حزم APK ═══════════════

    @Test
    fun `old apks flagged fresh ones kept`() = runBlocking {
        val dl = File(root, "Download").apply { mkdirs() }
        val stale = old(File(dl, "oldgame.apk").apply { writeBytes(ByteArray(1000)) }, daysAgo = 30)
        val fresh = File(dl, "newgame.apk").apply { writeBytes(ByteArray(1000)) }
        fresh.setLastModified(now - 86_400_000L) // يوم واحد
        val report = scan(minAgeDays = 7)
        val apks = report.byCategory(JunkCategory.ORPHAN_APKS).map { it.path }
        assertEquals(listOf(stale.path), apks)
        assertEquals(1000L, report.bytes(JunkCategory.ORPHAN_APKS))
    }

    // ═══════════════ القواعد العامة ═══════════════

    @Test
    fun `scanner never deletes anything`() = runBlocking {
        val keep1 = old(File(root, "a.nfpart").apply { writeBytes(ByteArray(10)) })
        val keep2 = old(File(root, "old.apk").apply { writeBytes(ByteArray(10)) })
        scan()
        assertTrue(keep1.exists() && keep2.exists())
    }

    @Test
    fun `skipPaths honored`() = runBlocking {
        val keep = old(File(root, "keepme.nfpart").apply { writeBytes(ByteArray(10)) })
        val report = JunkScanner(skipPaths = setOf(keep.absolutePath)).scan(root, nowMs = now)
        assertTrue(report.items.isEmpty())
    }

    @Test
    fun `total bytes sum correctly`() = runBlocking {
        val a = old(File(root, "x.nfpart").apply { writeBytes(ByteArray(111)) })
        val b = old(File(root, "y.apk").apply { writeBytes(ByteArray(222)) })
        val report = scan()
        assertEquals(333L, report.totalBytes)
    }
}
