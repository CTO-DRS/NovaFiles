package com.novafiles.app.core.shelves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات SmartShelvesEngine — الرفوف الذكية الأربعة (JVM خالص)
 */
class SmartShelvesEngineTest {

    private val NOW = 1_800_000_000_000L
    private val DAY = 86_400_000L
    private val MB = 1024L * 1024

    private fun file(
        path: String,
        size: Long = 10 * MB,
        modified: Long = NOW - DAY,
        isDirectory: Boolean = false,
    ): SmartShelvesEngine.IndexedFile {
        val idx = path.lastIndexOf('/')
        val parent = if (idx <= 0) "/" else path.substring(0, idx)
        val name = path.substring(idx + 1)
        return SmartShelvesEngine.IndexedFile(path, name, parent, size, modified, isDirectory)
    }

    private fun compute(vararg files: SmartShelvesEngine.IndexedFile) =
        SmartShelvesEngine.compute(files.toList(), now = NOW)

    // ═══════════ كشف لقطات الشاشة ═══════════

    @Test
    fun `screenshot detection covers english arabic and case variants`() {
        assertTrue(SmartShelvesEngine.isScreenshot("Screenshot_20260101-120000.jpg"))
        assertTrue(SmartShelvesEngine.isScreenshot("screenshot-2026-01-01.png"))
        assertTrue(SmartShelvesEngine.isScreenshot("SCREENSHOT folder backup.jpg"))
        assertTrue(SmartShelvesEngine.isScreenshot("لقطة شاشة 2026-01-01.png"))
        assertTrue(SmartShelvesEngine.isScreenshot("لقطة الشاشة 45.png"))
        assertFalse(SmartShelvesEngine.isScreenshot("photo001.jpg"))
        assertFalse(SmartShelvesEngine.isScreenshot("shot.png"))
    }

    @Test
    fun `excluded paths cover hidden and system segments`() {
        assertTrue(SmartShelvesEngine.isExcluded("/storage/emulated/0/Android/data/x.pdf"))
        assertTrue(SmartShelvesEngine.isExcluded("/storage/emulated/0/.NovaFilesTrash/x.pdf"))
        assertTrue(SmartShelvesEngine.isExcluded("/storage/emulated/0/vault/x.pdf"))
        assertTrue(SmartShelvesEngine.isExcluded("/storage/emulated/0/.hidden/x.pdf"))
        assertTrue(SmartShelvesEngine.isExcluded("/storage/emulated/0/LOST.DIR/x.pdf"))
        assertFalse(SmartShelvesEngine.isExcluded("/storage/emulated/0/Download/x.pdf"))
        assertFalse(SmartShelvesEngine.isExcluded("/storage/emulated/0/مستندات/x.pdf"))
    }

    // ═══════════ رف مستنداتي الأخيرة ═══════════

    @Test
    fun `recent docs keeps docs within 7 days sorted newest first`() {
        val shelf = compute(
            file("/Download/report.pdf", modified = NOW - 3 * DAY),
            file("/docs/plan.docx", modified = NOW - 1 * DAY),
            file("/docs/old.txt", modified = NOW - 8 * DAY),
            file("/img/photo.jpg", modified = NOW - 1 * DAY), // ليست مستنداً
        )[SmartShelvesEngine.ShelfId.RECENT_DOCS]!!
        assertEquals(listOf("plan.docx", "report.pdf"), shelf.map { it.name })
    }

    @Test
    fun `recent docs includes all supported doc extensions`() {
        val exts = listOf("pdf", "docx", "xlsx", "pptx", "odt", "txt", "md", "csv")
        val files = exts.mapIndexed { i, ext ->
            file("/d/f$i.$ext", modified = NOW - 1 * DAY)
        }
        val shelf = compute(*files.toTypedArray())[SmartShelvesEngine.ShelfId.RECENT_DOCS]!!
        assertEquals(exts.size, shelf.size)
    }

    // ═══════════ رف التنزيلات الكبيرة ═══════════

    @Test
    fun `large downloads requires download dir and 100MB threshold sorted by size`() {
        val shelf = compute(
            file("/Download/big.iso", size = 300 * MB),
            file("/Download/mid.zip", size = 100 * MB),
            file("/Download/small.apk", size = 99 * MB),      // أقل من العتبة
            file("/Movies/huge.mkv", size = 900 * MB),        // خارج التنزيلات
            file("/storage/emulated/0/Download/hidden.iso", size = 250 * MB).let {
                it.copy(path = "/storage/emulated/0/.hidden/iso.img", name = "iso.img",
                    parent = "/storage/emulated/0/.hidden")
            },
        )[SmartShelvesEngine.ShelfId.LARGE_DOWNLOADS]!!
        assertEquals(listOf("big.iso", "mid.zip"), shelf.map { it.name })
    }

    @Test
    fun `large downloads accepts Downloads plural dir too`() {
        val shelf = compute(
            file("/Downloads/x.img", size = 150 * MB),
        )[SmartShelvesEngine.ShelfId.LARGE_DOWNLOADS]!!
        assertEquals(1, shelf.size)
    }

    // ═══════════ رف المهملة 90 يوماً ═══════════

    @Test
    fun `stale shelf needs 90d age and 1MB size sorted oldest first`() {
        val shelf = compute(
            file("/a/oldest.zip", size = 5 * MB, modified = NOW - 200 * DAY),
            file("/b/old.mkv", size = 2 * MB, modified = NOW - 100 * DAY),
            file("/c/recent.pdf", size = 5 * MB, modified = NOW - 10 * DAY), // حديث
            file("/d/tiny.old", size = 512 * 1024, modified = NOW - 300 * DAY), // صغير
        )[SmartShelvesEngine.ShelfId.STALE_90D]!!
        assertEquals(listOf("oldest.zip", "old.mkv"), shelf.map { it.name })
    }

    @Test
    fun `stale shelf excludes recently opened paths`() {
        val target = file("/x/old.bin", size = 10 * MB, modified = NOW - 120 * DAY)
        val withOpened = SmartShelvesEngine.compute(
            listOf(target), now = NOW,
            recentlyOpened = setOf(target.path),
        )[SmartShelvesEngine.ShelfId.STALE_90D]!!
        assertTrue(withOpened.isEmpty())

        val withoutOpened = SmartShelvesEngine.compute(
            listOf(target), now = NOW,
        )[SmartShelvesEngine.ShelfId.STALE_90D]!!
        assertEquals(1, withoutOpened.size)
    }

    // ═══════════ رف لقطات الشاشة القديمة ═══════════

    @Test
    fun `old screenshots only beyond 30 days sorted oldest first`() {
        val shelf = compute(
            file("/Pictures/Screenshot_20250101-100000.png", modified = NOW - 90 * DAY),
            file("/Pictures/Screenshot_20250201-100000.png", modified = NOW - 45 * DAY),
            file("/Pictures/Screenshot_fresh.png", modified = NOW - 2 * DAY), // حديثة
            file("/Pictures/vacation.png", modified = NOW - 90 * DAY),        // ليست لقطة
        )[SmartShelvesEngine.ShelfId.OLD_SCREENSHOTS]!!
        assertEquals(2, shelf.size)
        assertEquals("Screenshot_20250101-100000.png", shelf[0].name)
    }

    // ═══════════ قواعد عامة ═══════════

    @Test
    fun `directories and excluded paths never reach any shelf`() {
        val shelves = compute(
            file("/Download/folder", isDirectory = true, size = 500 * MB),
            file("/Android/data/report.pdf", modified = NOW),
            file("/.trash/Screenshot_old.png", modified = NOW - 100 * DAY),
        )
        assertTrue(shelves.values.all { it.isEmpty() })
    }

    @Test
    fun `limit per shelf is respected`() {
        val files = (1..10).map {
            file("/d/old$it.zip", size = 5 * MB, modified = NOW - (100 + it) * DAY)
        }
        val shelves = SmartShelvesEngine.compute(files, now = NOW, limitPerShelf = 3)
        assertEquals(3, shelves[SmartShelvesEngine.ShelfId.STALE_90D]!!.size)
    }

    @Test
    fun `empty index yields all four empty shelves`() {
        val shelves = compute()
        assertEquals(setOf(
            SmartShelvesEngine.ShelfId.RECENT_DOCS,
            SmartShelvesEngine.ShelfId.LARGE_DOWNLOADS,
            SmartShelvesEngine.ShelfId.STALE_90D,
            SmartShelvesEngine.ShelfId.OLD_SCREENSHOTS,
        ), shelves.keys)
        assertTrue(shelves.values.all { it.isEmpty() })
    }

    @Test
    fun `compute is deterministic across runs`() {
        val files = listOf(
            file("/Download/a.iso", size = 200 * MB),
            file("/docs/b.pdf", modified = NOW - DAY),
            file("/Pictures/Screenshot_old.png", modified = NOW - 60 * DAY),
            file("/x/c.zip", size = 5 * MB, modified = NOW - 120 * DAY),
        )
        val a = SmartShelvesEngine.compute(files, now = NOW)
        val b = SmartShelvesEngine.compute(files, now = NOW)
        assertEquals(a, b)
    }
}

/** ═══════════ v0.33 — اختبارات حدودية إضافية ═══════════ */
class SmartShelvesEdgeTest {

    private val NOW = 1_800_000_000_000L
    private val DAY = 86_400_000L
    private val MB = 1024L * 1024

    private fun file(path: String, size: Long = 10 * MB, modified: Long = NOW - DAY) =
        SmartShelvesEngine.IndexedFile(
            path, path.substringAfterLast('/'),
            path.substringBeforeLast('/', "/"), size, modified, false,
        )

    @Test
    fun `recent docs includes doc exactly at 7d boundary`() {
        val at = file("/d/edge.pdf", modified = NOW - 7 * DAY)
        val inside = SmartShelvesEngine.compute(listOf(at), NOW)[SmartShelvesEngine.ShelfId.RECENT_DOCS]!!
        assertEquals(1, inside.size)
    }

    @Test
    fun `stale excludes file exactly at 90d boundary (strictly older)`() {
        val at = file("/d/edge.zip", size = 5 * MB, modified = NOW - 90 * DAY)
        assertTrue(SmartShelvesEngine.compute(listOf(at), NOW)[SmartShelvesEngine.ShelfId.STALE_90D]!!.isEmpty())
    }

    @Test
    fun `old screenshots exclude shot exactly at 30d boundary`() {
        val at = file("/P/Screenshot_edge.png", modified = NOW - 30 * DAY)
        assertTrue(SmartShelvesEngine.compute(listOf(at), NOW)[SmartShelvesEngine.ShelfId.OLD_SCREENSHOTS]!!.isEmpty())
    }

    @Test
    fun `large downloads uses direct parent segment only`() {
        // مجلد يشبه التنزيلات اسمياً لكنه ليس «Download» المباشر لا يُحتسب
        val lookalike = file("/storage/emulated/0/MyDownloads/x.iso", size = 200 * MB)
        assertTrue(SmartShelvesEngine.compute(listOf(lookalike), NOW)[SmartShelvesEngine.ShelfId.LARGE_DOWNLOADS]!!.isEmpty())
    }
}
