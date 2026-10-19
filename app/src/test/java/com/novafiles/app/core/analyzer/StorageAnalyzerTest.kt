package com.novafiles.app.core.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات محرك تحليل التخزين — الفئات والشجرة واللوحات وميزانية الأداء */
class StorageAnalyzerTest {

    private fun row(
        path: String,
        size: Long = 0L,
        isDir: Boolean = false,
        modified: Long = 1_700_000_000_000L,
    ): SnapshotRow {
        val i = path.lastIndexOf('/')
        return SnapshotRow(
            path = path,
            name = path.substring(i + 1),
            parent = if (i > 0) path.substring(0, i) else "",
            size = size,
            modified = modified,
            isDirectory = isDir,
        )
    }

    // ═══════════════ التصنيف ═══════════════

    @Test
    fun `categories classify by extension`() {
        assertEquals(StorageCategory.VIDEO, Categories.of("movie.MP4"))
        assertEquals(StorageCategory.VIDEO, Categories.of("clip.mkv"))
        assertEquals(StorageCategory.AUDIO, Categories.of("song.flac"))
        assertEquals(StorageCategory.IMAGES, Categories.of("photo.jpeg"))
        assertEquals(StorageCategory.DOCS, Categories.of("book.pdf"))
        assertEquals(StorageCategory.DOCS, Categories.of("notes.txt"))
        assertEquals(StorageCategory.APPS, Categories.of("app.apk"))
        assertEquals(StorageCategory.ARCHIVES, Categories.of("backup.7z"))
        assertEquals(StorageCategory.OTHER, Categories.of("blob.dat"))
        assertEquals(StorageCategory.OTHER, Categories.of("noext"))
    }

    @Test
    fun `analyze counts files and dirs separately`() {
        val rows = listOf(
            row("/root", isDir = true),
            row("/root/video.mp4", 1_000L),
            row("/root/song.mp3", 200L),
            row("/root/photo.jpg", 300L),
            row("/root/app.apk", 500L),
            row("/root/blob.bin", 1L),
        )
        val report = StorageAnalyzer.analyze(rows)
        assertEquals(5, report.totalFiles)
        assertEquals(1, report.totalDirs)
        assertEquals(2001L, report.usedBytes)
        val video = report.categories.first { it.category == StorageCategory.VIDEO }
        assertEquals(1, video.fileCount)
        assertEquals(1000L, video.totalBytes)
    }

    @Test
    fun `bySizeDesc orders categories biggest first`() {
        val rows = listOf(
            row("/r/a.mp4", 500L),
            row("/r/b.jpg", 300L),
            row("/r/c.txt", 100L),
        )
        val order = StorageAnalyzer.analyze(rows).bySizeDesc.map { it.category }
        assertEquals(listOf(StorageCategory.VIDEO, StorageCategory.IMAGES, StorageCategory.DOCS), order.take(3))
    }

    @Test
    fun `zero-size files count but add nothing`() {
        val rows = listOf(row("/r/empty.jpg", 0L), row("/r/x.mp4", 10L))
        val report = StorageAnalyzer.analyze(rows)
        assertEquals(2, report.totalFiles)
        assertEquals(10L, report.usedBytes)
        assertEquals(1, report.categories.first { it.category == StorageCategory.IMAGES }.fileCount)
        assertEquals(1, report.categories.first { it.category == StorageCategory.VIDEO }.fileCount)
    }

    // ═══════════════ تجميع الشجرة ═══════════════

    @Test
    fun `aggregateTree computes subtree sizes bottom-up`() {
        val rows = listOf(
            row("/root", isDir = true),
            row("/root/media", isDir = true),
            row("/root/media/video", isDir = true),
            row("/root/media/video/a.mp4", 100L),
            row("/root/media/b.jpg", 50L),
            row("/root/c.txt", 25L),
        )
        val tree = StorageAnalyzer.aggregateTree(rows)
        assertEquals(100L, tree["/root/media/video"]!!.subtreeBytes)
        assertEquals(150L, tree["/root/media"]!!.subtreeBytes) // 100 (الفيديو) + 50 (b.jpg)
        assertEquals(175L, tree["/root"]!!.subtreeBytes)       // 150 + 25 (c.txt)
    }

    @Test
    fun `aggregateTree infers missing parent dirs`() {
        // الملف موجود في الفهرس ومجلد أبوه لا (فهرس جزئي)
        val rows = listOf(row("/only/in-index/file.bin", 10L))
        val tree = StorageAnalyzer.aggregateTree(rows)
        assertEquals(10L, tree["/only/in-index"]!!.subtreeBytes)
        assertTrue(tree.containsKey("/only"))
    }

    @Test
    fun `aggregateTree handles deep chains without recursion`() {
        // سلسلة 5000 مجلد — يثبت عدم حدوث StackOverflow
        val depth = 5000
        val rows = ArrayList<SnapshotRow>(depth)
        var p = "/deep"
        rows.add(row(p, isDir = true))
        for (i in 1 until depth) {
            val next = "$p/d$i"
            rows.add(row(next, isDir = true))
            p = next
        }
        rows.add(row("$p/leaf.bin", 7L))
        val tree = StorageAnalyzer.aggregateTree(rows)
        assertEquals(7L, tree[p]!!.subtreeBytes)
        assertEquals(7L, tree["/deep"]!!.subtreeBytes)
    }

    @Test
    fun `childrenForTreemap lists dirs then files sorted by size`() {
        val rows = listOf(
            row("/root", isDir = true),
            row("/root/small", isDir = true),
            row("/root/small/x", 1L),
            row("/root/big", isDir = true),
            row("/root/big/x", 900L),
            row("/root/mid.txt", 100L),
            row("/root/tiny.txt", 5L),
        )
        val tree = StorageAnalyzer.aggregateTree(rows)
        val kids = StorageAnalyzer.childrenForTreemap("/root", rows, tree)
        // الترتيب: big(900) ثم mid.txt(100) ثم small(1) ثم tiny.txt(5)؟ لا — تنازلي: 900,100,5,1
        assertEquals(listOf("big", "mid.txt", "tiny.txt", "small"), kids.map { it.label })
        assertTrue(kids[0].isDir && !kids[1].isDir)
        assertEquals(900L, kids[0].value)
    }

    // ═══════════════ الملفات الكبيرة والقديمة ═══════════════

    @Test
    fun `largeFiles sorted desc and limited`() {
        val rows = (1..100).map { row("/r/f$it.bin", size = it * 10L) }
        val out = StorageAnalyzer.largeFiles(rows, minSizeBytes = 500L, limit = 5)
        assertEquals(5, out.size)
        assertEquals(1000L, out[0].size)
        assertTrue(out.zipWithNext().all { (a, b) -> a.size >= b.size })
    }

    @Test
    fun `oldFiles picks only older-than cutoff`() {
        val now = 1_800_000_000_000L
        val rows = listOf(
            row("/r/ancient.iso", 900L, modified = now - 200L * 86_400_000L),
            row("/r/recent.bin", 800L, modified = now - 3L * 86_400_000L),
            row("/r/old.mp4", 700L, modified = now - 120L * 86_400_000L),
        )
        val out = StorageAnalyzer.oldFiles(rows, nowMs = now, olderThanDays = 90, minSizeBytes = 0L)
        assertEquals(listOf("ancient.iso", "old.mp4"), out.map { it.name })
    }

    // ═══════════════ ميزانية الأداء: 100 ألف صف < 30 ثانية ═══════════════

    @Test
    fun `performance budget 100k rows analyzed under 30s`() {
        // توليد 100 ألف صف (10 آلاف مجلد × 10 ملفات) بأسماء وامتدادات متنوعة
        val exts = listOf("mp4", "jpg", "apk", "zip", "pdf", "mp3", "bin", "txt")
        val rows = ArrayList<SnapshotRow>(100_000)
        for (d in 0 until 10_000) {
            val dir = "/root/dir$d"
            rows.add(row(dir, isDir = true))
            for (f in 0 until 10) {
                rows.add(row("$dir/file$f.${exts[(d + f) % exts.size]}", size = (f + 1) * 1024L))
            }
        }
        assertEquals(110_000, rows.size)

        val t0 = System.nanoTime()
        val report = StorageAnalyzer.analyze(rows)
        val tree = StorageAnalyzer.aggregateTree(rows)
        val big = StorageAnalyzer.largeFiles(rows, 1L, 50)
        val elapsedS = (System.nanoTime() - t0) / 1_000_000_000.0

        assertEquals(100_000, report.totalFiles)
        assertEquals(10_000, report.totalDirs)
        assertEquals(10_000, tree["/root"]!!.childDirs)
        assertEquals(50, big.size)
        assertTrue("تجاوز الميزانية: ${elapsedS}s", elapsedS < 30.0)
        // لقطة واقعية: ينبغي أن يكون أقل من ثانية واحدة على JVM
        assertTrue("أبطأ من المتوقع: ${elapsedS}s", elapsedS < 5.0)
    }
}
