package com.novafiles.app.core.sync

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * اختبارات منفّذ خطة المزامنة فوق [LocalSide] على أقراص مؤقتة — جولة
 * كاملة من اللقطات إلى الملفات الفعلية: نسخ، تحديث، حذف مرآة، ثنائي
 * اتجاه مع أساس، تعارض بالسياسات، تحقق البصمة، الإلغاء التعاوني، وحارس
 * هروب الجذر.
 */
class PairSyncRunnerTest {

    private lateinit var sourceRoot: File
    private lateinit var destRoot: File

    @Before
    fun setup() {
        sourceRoot = createTempDir("nova-sync-src")
        destRoot = createTempDir("nova-sync-dst")
    }

    private fun srcFile(rel: String, content: String, mtime: Long? = null): File =
        File(sourceRoot, rel).apply {
            parentFile.mkdirs()
            writeText(content)
            mtime?.let { setLastModified(it) }
        }

    private fun dstFile(rel: String, content: String, mtime: Long? = null): File =
        File(destRoot, rel).apply {
            parentFile.mkdirs()
            writeText(content)
            mtime?.let { setLastModified(it) }
        }

    private fun readFile(root: File, rel: String): String = File(root, rel).readText()

    private fun runner(cancel: () -> Boolean = { false }) =
        PairSyncRunner(LocalSide(sourceRoot), LocalSide(destRoot), isCancelled = cancel)

    // ═══════════════ اتجاه واحد ═══════════════

    @Test
    fun `one-way round trip copies files and creates nested dirs`() = runBlocking {
        srcFile("docs/تقارير/q1.txt", "Q1 data")
        srcFile("note.txt", "hello")
        val outcome = runner().run(SyncDirection.ONE_WAY, ConflictPolicy.NEWER_WINS)
        assertNull(outcome.error)
        assertEquals(0, outcome.report.failed)
        assertEquals(2, outcome.report.copied)
        assertEquals("Q1 data", readFile(destRoot, "docs/تقارير/q1.txt"))
        assertEquals("hello", readFile(destRoot, "note.txt"))
        assertTrue(File(destRoot, "docs/تقارير").isDirectory)
    }

    @Test
    fun `one-way second run skips everything`() = runBlocking {
        srcFile("a.txt", "content")
        val r = runner()
        r.run(SyncDirection.ONE_WAY, ConflictPolicy.NEWER_WINS)
        val second = r.run(SyncDirection.ONE_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(0, second.report.copied)
        assertEquals(0, second.report.failed)
        assertTrue(second.plan.stats.skipped >= 1)
    }

    @Test
    fun `one-way updates changed content and leaves dest-only files`() = runBlocking {
        srcFile("a.txt", "v1")
        runner().run(SyncDirection.ONE_WAY, ConflictPolicy.NEWER_WINS)
        // تعديل في المصدر + ملف غريب في الوجهة
        srcFile("a.txt", "v2-longer-content")
        dstFile("stranger.txt", "not mine")
        val outcome = runner().run(SyncDirection.ONE_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(1, outcome.report.updated)
        assertEquals(1, outcome.plan.stats.destOnlyKept)
        assertEquals("v2-longer-content", readFile(destRoot, "a.txt"))
        assertEquals("not mine", readFile(destRoot, "stranger.txt"))
    }

    // ═══════════════ المرآة ═══════════════

    @Test
    fun `mirror removes dest-only files and dirs`() = runBlocking {
        srcFile("keep.txt", "kept")
        dstFile("stale.txt", "old")
        File(destRoot, "oldDir").apply { mkdirs(); File(this, "x.txt").writeText("x") }
        val outcome = runner().run(SyncDirection.MIRROR, ConflictPolicy.NEWER_WINS)
        assertEquals(0, outcome.report.failed)
        assertTrue(File(destRoot, "stale.txt").let { !it.exists() })
        assertTrue(File(destRoot, "oldDir").let { !it.exists() })
        assertEquals("kept", readFile(destRoot, "keep.txt"))
        assertEquals(3, outcome.report.deleted) // stale.txt + oldDir + oldDir/x.txt
    }

    @Test
    fun `mirror never touches source side`() = runBlocking {
        srcFile("s1.txt", "a")
        dstFile("d1.txt", "b")
        runner().run(SyncDirection.MIRROR, ConflictPolicy.NEWER_WINS)
        // المصدر ما زال فيه ملفه فقط
        assertEquals(listOf("s1.txt"), sourceRoot.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    // ═══════════════ ثنائي الاتجاه ═══════════════

    @Test
    fun `two-way first run merges both sides`() = runBlocking {
        srcFile("from-src.txt", "S")
        dstFile("from-dst.txt", "D")
        val outcome = runner().run(SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(0, outcome.report.failed)
        assertEquals("D", readFile(sourceRoot, "from-dst.txt"))
        assertEquals("S", readFile(destRoot, "from-src.txt"))
        // نجاح كامل → أساس للجولة القادمة
        assertNotNull(outcome.baselineSnapshot)
        assertTrue(outcome.baselineSnapshot!!.isNotEmpty())
    }

    @Test
    fun `two-way with baseline syncs changes in both directions`() = runBlocking {
        srcFile("shared.txt", "base")
        val first = runner().run(SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS)
        assertNull(first.error)
        val baseline = requireNotNull(first.baselineSnapshot)

        // تغيير على الجانبين في ملفات مختلفة
        srcFile("shared.txt", "edited-on-source")
        dstFile("other.txt", "new-on-dest")

        val second = runner().run(SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS, baseline)
        assertEquals(0, second.report.failed)
        assertEquals("edited-on-source", readFile(destRoot, "shared.txt"))
        assertEquals("new-on-dest", readFile(sourceRoot, "other.txt"))
    }

    @Test
    fun `two-way propagates source deletion to dest`() = runBlocking {
        srcFile("doomed.txt", "x")
        val first = runner().run(SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS)
        val baseline = requireNotNull(first.baselineSnapshot)

        File(sourceRoot, "doomed.txt").delete()
        val second = runner().run(SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS, baseline)
        assertEquals(1, second.report.deleted)
        assertTrue(!File(destRoot, "doomed.txt").exists())
    }

    @Test
    fun `two-way newer-wins conflict picks newest`() = runBlocking {
        srcFile("c.txt", "base")
        val first = runner().run(SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS)
        val baseline = requireNotNull(first.baselineSnapshot)

        val old = System.currentTimeMillis() - 100_000L
        srcFile("c.txt", "old-source-edit", old)
        dstFile("c.txt", "fresh-dest-edit")

        val second = runner().run(SyncDirection.TWO_WAY, ConflictPolicy.NEWER_WINS, baseline)
        assertEquals(1, second.report.conflicts)
        // الوجهة أحدث زمنياً (الافتراضي now) → نسختها تفوز على المصدر
        assertEquals("fresh-dest-edit", readFile(sourceRoot, "c.txt"))
    }

    @Test
    fun `two-way keep-both preserves both versions`() = runBlocking {
        srcFile("c.txt", "base")
        val first = runner().run(SyncDirection.TWO_WAY, ConflictPolicy.KEEP_BOTH)
        val baseline = requireNotNull(first.baselineSnapshot)

        // أزمنة صريحة متباعدة — حسم الفائز حتماً (الوجهة أحدث)
        val old = System.currentTimeMillis() - 600_000L
        srcFile("c.txt", "source-edit-long", old)
        dstFile("c.txt", "dest-edit-longer!")

        val second = runner().run(SyncDirection.TWO_WAY, ConflictPolicy.KEEP_BOTH, baseline)
        assertEquals(1, second.report.conflicts)
        // الفائز (الوجهة الأحدث) باسمه في المصدر، والخاسر محفوظ بنسخة داخل المصدر
        assertEquals("dest-edit-longer!", readFile(sourceRoot, "c.txt"))
        assertEquals("source-edit-long", File(sourceRoot, "c (conflict).txt").readText())
        // الوجهة: الملف الفائز كما هو (نسخة التعارض داخل الوجهة فقط حين يفوز المصدر)
        assertEquals("dest-edit-longer!", readFile(destRoot, "c.txt"))
        assertTrue(!File(destRoot, "c (conflict).txt").exists())
    }

    // ═══════════════ التحقق بالبصمة ═══════════════

    @Test
    fun `verify checksums confirms copies and flags mismatches`() = runBlocking {
        srcFile("v.txt", "verified content")
        val outcome = runner().run(
            SyncDirection.ONE_WAY, ConflictPolicy.NEWER_WINS,
            verifyChecksums = true,
        )
        assertEquals(1, outcome.report.verifiedOk)
        assertEquals(0, outcome.report.verifyFailed)
        assertEquals("verified content", readFile(destRoot, "v.txt"))
    }

    @Test
    fun `verify deep check catches same metadata different content`() = runBlocking {
        // محتوى مختلف بنفس الحجم وزمن تعديل مطبوع
        srcFile("twin.txt", "AAA")
        val twin = dstFile("twin.txt", "BBB")
        val past = System.currentTimeMillis() - 200_000L
        twin.setLastModified(past)
        File(sourceRoot, "twin.txt").setLastModified(past)

        val outcome = runner().run(
            SyncDirection.ONE_WAY, ConflictPolicy.NEWER_WINS,
            verifyChecksums = true,
        )
        assertEquals(1, outcome.report.updated)
        assertEquals("AAA", readFile(destRoot, "twin.txt"))
    }

    @Test
    fun `without verify identical metadata skips despite differing content`() = runBlocking {
        srcFile("twin.txt", "AAA")
        val twin = dstFile("twin.txt", "BBB")
        val past = System.currentTimeMillis() - 200_000L
        twin.setLastModified(past)
        File(sourceRoot, "twin.txt").setLastModified(past)

        val outcome = runner().run(SyncDirection.ONE_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(0, outcome.report.updated)
        assertEquals("BBB", readFile(destRoot, "twin.txt"))
    }

    // ═══════════════ الإلغاء والحالات القاتلة ═══════════════

    @Test
    fun `cancellation between items stops with cancelled status`() = runBlocking {
        srcFile("a1.txt", "1")
        srcFile("a2.txt", "2")
        srcFile("a3.txt", "3")
        var counter = 0
        val outcome = runner(cancel = { ++counter > 1 }).run(SyncDirection.ONE_WAY, ConflictPolicy.NEWER_WINS)
        assertTrue(outcome.report.cancelled)
        assertEquals(SyncRunStatus.CANCELLED, outcome.report.status())
        assertNull(outcome.baselineSnapshot) // مُلغاة → لا أساس
        // نُفِّذ عنصر واحد على الأقل قبل الإلغاء
        assertTrue(outcome.report.copied >= 1 && outcome.report.copied < 3)
    }

    @Test
    fun `item failure counts failed and status becomes failed not fatal`() = runBlocking<Unit> {
        srcFile("good.txt", "ok")
        val outcome = runner().run(SyncDirection.ONE_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(SyncRunStatus.SUCCESS, outcome.report.status())

        // فشل لكل عنصر: جذر الوجهة ملف لا مجلد — الكتابة تفشل لكل عنصر
        val fileAsRoot = File(destRoot.parentFile, "nova-sync-file-${System.nanoTime()}")
        fileAsRoot.writeText("not a dir")
        val badRunner = PairSyncRunner(LocalSide(sourceRoot), LocalSide(fileAsRoot))
        srcFile("blocked.txt", "b")
        val bad = badRunner.run(SyncDirection.ONE_WAY, ConflictPolicy.NEWER_WINS)
        assertTrue(!bad.fatal) // الفشل لكل عنصر ليس قاتلاً
        assertEquals(2, bad.report.failed) // good.txt وblocked.txt كلاهما يفشل
        assertEquals(SyncRunStatus.FAILED, bad.report.status())
        fileAsRoot.delete()
    }

    @Test
    fun `local side refuses paths escaping root`() = runBlocking {
        val side = LocalSide(sourceRoot)
        val escaped = try {
            side.write("../outside.txt", 5, "x".byteInputStream())
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("path traversal must be rejected", escaped)
        assertTrue(!File(sourceRoot.parentFile, "outside.txt").exists())
    }

    @Test
    fun `snapshot is deterministic sorted and complete`() = runBlocking {
        srcFile("b.txt", "b")
        srcFile("a.txt", "a")
        srcFile("sub/z.txt", "z")
        srcFile("sub/a2.txt", "a2")
        val snap = LocalSide(sourceRoot).snapshot()
        assertEquals(
            listOf("a.txt", "b.txt", "sub", "sub/a2.txt", "sub/z.txt"),
            snap.map { it.relativePath },
        )
        assertTrue(snap.first { it.relativePath == "sub" }.isDir)
        assertEquals(1L, snap.first { it.relativePath == "a.txt" }.size)
    }

    @Test
    fun `runner reports bytes transferred matching file sizes`() = runBlocking {
        srcFile("x.txt", "12345")
        srcFile("y.txt", "1234567890")
        val outcome = runner().run(SyncDirection.ONE_WAY, ConflictPolicy.NEWER_WINS)
        assertEquals(15L, outcome.report.bytesTransferred)
    }
}
