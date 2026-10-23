package com.novafiles.app.core.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات مصنّف التنزيلات — قواعد الامتداد الحتمية وقاعدة السلوك الدفعة */
class DownloadClassifierTest {

    // ═══════════════ قواعد الامتداد الأساسية ═══════════════

    @Test
    fun `documents classified by extension`() {
        assertEquals(DownloadClass.DOCUMENT, DownloadClassifier.classify("report.pdf"))
        assertEquals(DownloadClass.DOCUMENT, DownloadClassifier.classify("خطة العمل.docx"))
        assertEquals(DownloadClass.DOCUMENT, DownloadClassifier.classify("notes.TXT"))
    }

    @Test
    fun `media classified by extension`() {
        assertEquals(DownloadClass.MEDIA, DownloadClassifier.classify("photo.jpg"))
        assertEquals(DownloadClass.MEDIA, DownloadClassifier.classify("صورة رمزية.png"))
        assertEquals(DownloadClass.MEDIA, DownloadClassifier.classify("song.MP3"))
        assertEquals(DownloadClass.MEDIA, DownloadClassifier.classify("clip.mkv"))
    }

    @Test
    fun `installers classified by extension`() {
        assertEquals(DownloadClass.INSTALLER, DownloadClassifier.classify("app.apk"))
        assertEquals(DownloadClass.INSTALLER, DownloadClassifier.classify("bundle.xapk"))
        assertEquals(DownloadClass.INSTALLER, DownloadClassifier.classify("Store.APK"))
    }

    @Test
    fun `archives classified by extension`() {
        assertEquals(DownloadClass.ARCHIVE, DownloadClassifier.classify("backup.zip"))
        assertEquals(DownloadClass.ARCHIVE, DownloadClassifier.classify("dump.tar.gz"))
        assertEquals(DownloadClass.ARCHIVE, DownloadClassifier.classify("data.7z"))
    }

    // ═══════════════ الفوضى: مجهول وبلا امتداد وقطعيات ═══════════════

    @Test
    fun `unknown extensions are clutter`() {
        assertEquals(DownloadClass.CLUTTER, DownloadClassifier.classify("setup.exe"))
        assertEquals(DownloadClass.CLUTTER, DownloadClassifier.classify("blob.xyz"))
    }

    @Test
    fun `no extension is clutter`() {
        assertEquals(DownloadClass.CLUTTER, DownloadClassifier.classify("README"))
        assertEquals(DownloadClass.CLUTTER, DownloadClassifier.classify("data"))
    }

    @Test
    fun `partial download leftovers are clutter regardless of inner type`() {
        assertEquals(DownloadClass.CLUTTER, DownloadClassifier.classify("movie.mkv.nfpart"))
        assertEquals(DownloadClass.CLUTTER, DownloadClassifier.classify("doc.pdf.crdownload"))
        assertEquals(DownloadClass.CLUTTER, DownloadClassifier.classify("song.mp3.part"))
        assertEquals(DownloadClass.CLUTTER, DownloadClassifier.classify("x.nfjournal"))
        assertEquals(DownloadClass.CLUTTER, DownloadClassifier.classify("y.tmp"))
        assertEquals(DownloadClass.CLUTTER, DownloadClassifier.classify("z.partial"))
    }

    @Test
    fun `uppercase partial extension still clutter`() {
        assertEquals(DownloadClass.CLUTTER, DownloadClassifier.classify("big.ISO.PART"))
    }

    @Test
    fun `dotfile with unknown extension is clutter`() {
        assertEquals(DownloadClass.CLUTTER, DownloadClassifier.classify(".gitignore"))
    }

    @Test
    fun `tar gz ends as archive through final extension`() {
        // آخر امتداد هو الفيصل — نفس سلوك مجموعة المحلل
        assertEquals("gz", DownloadClassifier.extensionOf("dump.tar.gz"))
        assertEquals(DownloadClass.ARCHIVE, DownloadClassifier.classify("dump.tar.gz"))
    }

    // ═══════════════ قاعدة السلوك: كومة نسخ إعادة التنزيل (دفعة) ═══════════════

    @Test
    fun `copy pile pattern detected by name`() {
        assertTrue(DownloadClassifier.isCopyPile("photo (1).jpg".substringBeforeLast('.', "photo (1)")))
        assertTrue(DownloadClassifier.isCopyPile("photo (12)"))
        assertFalse(DownloadClassifier.isCopyPile("season 1"))
        assertFalse(DownloadClassifier.isCopyPile("photo"))
    }

    @Test
    fun `copy pile becomes clutter only when base exists in same folder`() {
        val now = 1_800_000_000_000L
        fun row(parent: String, name: String, size: Long = 10L) = com.novafiles.app.core.analyzer.SnapshotRow(
            path = "$parent/$name", name = name, parent = parent, size = size, modified = now, isDirectory = false,
        )
        val d = "/storage/emulated/0/Download"
        // الأساس موجود → النسخ (1) و(2) فوضى سلوكية، والأساس يبقى وسائط
        val out = DownloadClassifier.classifyAll(
            listOf(row(d, "photo.jpg"), row(d, "photo (1).jpg"), row(d, "photo (2).jpg")),
            nowMs = now,
        ).associateBy { it.name }
        assertEquals(DownloadClass.MEDIA, out["photo.jpg"]!!.clazz)
        assertEquals(DownloadClass.CLUTTER, out["photo (1).jpg"]!!.clazz)
        assertEquals(DownloadClass.CLUTTER, out["photo (2).jpg"]!!.clazz)
    }

    @Test
    fun `standalone numbered name is not clutter`() {
        val now = 1_800_000_000_000L
        val d = "/storage/emulated/0/Download"
        // «اسم (2020)» وحيد بلا أساس — اسم مشروع حقيقي لا كومة تنزيل
        val out = DownloadClassifier.classifyAll(
            listOf(
                com.novafiles.app.core.analyzer.SnapshotRow(
                    "$d/season 1 (2020).mkv", "season 1 (2020).mkv", d, 100L, now, false,
                ),
            ),
            nowMs = now,
        )
        assertEquals(DownloadClass.MEDIA, out.single().clazz)
    }

    // ═══════════════ classifyAll: عمر الأيام والمجلدات ═══════════════

    @Test
    fun `directories are filtered and age days computed`() {
        val now = 1_800_000_000_000L
        val d = "/storage/emulated/0/Download"
        val rows = listOf(
            com.novafiles.app.core.analyzer.SnapshotRow("$d/x.pdf", "x.pdf", d, 5L, now - 3 * DownloadClassifier.DAY_MS, false),
            com.novafiles.app.core.analyzer.SnapshotRow(d, "Download", "/storage/emulated/0", 0L, now, true),
        )
        val out = DownloadClassifier.classifyAll(rows, now)
        assertEquals(1, out.size)
        assertEquals(3, out.single().ageDays)
        assertEquals(DownloadClass.DOCUMENT, out.single().clazz)
    }

    @Test
    fun `future modified date yields zero age not negative`() {
        val now = 1_800_000_000_000L
        val d = "/storage/emulated/0/Download"
        val out = DownloadClassifier.classifyAll(
            listOf(
                com.novafiles.app.core.analyzer.SnapshotRow("$d/x.pdf", "x.pdf", d, 5L, now + 86_400_000L, false),
            ),
            now,
        )
        assertEquals(0, out.single().ageDays)
    }

    // ═══════════════ نطاق التنزيلات ═══════════════

    @Test
    fun `download path detection is case insensitive`() {
        val root = "/storage/emulated/0"
        assertTrue(DownloadClassifier.isDownloadPath("$root/Download/a.pdf", root))
        assertTrue(DownloadClassifier.isDownloadPath("$root/Downloads/a.pdf", root))
        assertTrue(DownloadClassifier.isDownloadPath("$root/download/deep/a.pdf", root))
        assertFalse(DownloadClassifier.isDownloadPath("$root/DCIM/a.jpg", root))
        assertFalse(DownloadClassifier.isDownloadPath("$root/MyDownloads/a.pdf", root))
    }

    @Test
    fun `extension helper is lowercase and empty-safe`() {
        assertEquals("pdf", DownloadClassifier.extensionOf("A.PDF"))
        assertEquals("", DownloadClassifier.extensionOf("noext"))
        assertEquals("", DownloadClassifier.extensionOf(""))
    }
}
