package com.novafiles.app.core.ops

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * اختبارات قائمة العمليات الخلفية (v0.12) — حتمية بالكامل عبر
 * مُرسِل اختبار واحد يمرر العامل والعمليات على الوقت الافتراضي.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun testDispatcher(dispatcher: TestDispatcher): CoroutineDispatcher = dispatcher

    private val srcDir by lazy { tmp.newFolder("src") }

    private fun makeFile(name: String, size: Int = 1024): File =
        File(srcDir, name).apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }

    /**
     * نطاق مخصص على نفس جدوّل الاختبار — backgroundScope لا تُنفّذ مهامه
     * عبر testScheduler.advanceUntilIdle() في coroutines-test 1.9، لذا
     * نستخدم نطاقاً مستقلاً يعمل حتماً على الجدوّل الافتراضي.
     */
    private fun newScope(dispatcher: TestDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    private fun newQueue(scope: kotlinx.coroutines.CoroutineScope, io: CoroutineDispatcher): OpQueue =
        OpQueue(scope = scope, trashDir = File(tmp.root, ".trash"), listener = null, ioDispatcher = io)

    // ----- النسخ والنقل -----

    @Test
    fun `copy op completes and writes bytes`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val src = makeFile("a.bin", 4096)
        val dest = tmp.newFolder("dest")

        q.enqueue(OpSpec(OpKind.COPY, title = "نسخ", sources = listOf(src), target = dest))
        testScheduler.advanceUntilIdle()

        val rec = q.ops.value.single()
        assertEquals(OpStatus.DONE, rec.status)
        assertTrue(File(dest, "a.bin").exists())
        assertEquals(4096, File(dest, "a.bin").length())
        assertTrue(rec.endedAt!! >= rec.startedAt!!)
    }

    @Test
    fun `progress bytes reported monotonically toward total`() = runTest {
        val snapshots = mutableListOf<Long>()
        val listener = OpListener { rec ->
            rec.progress?.let { if (it.bytesTotal > 0) snapshots += it.bytesDone }
        }
        val d = StandardTestDispatcher(testScheduler)
        val q = OpQueue(
            scope = newScope(d),
            trashDir = File(tmp.root, ".t2"),
            listener = listener,
            ioDispatcher = d,
        )
        val src = makeFile("big.bin", 300 * 1024) // أكبر من المخزن 128KB — دفعات متعددة
        val dest = tmp.newFolder("d2")

        q.enqueue(OpSpec(OpKind.COPY, title = "نسخ", sources = listOf(src), target = dest))
        testScheduler.advanceUntilIdle()

        assertTrue(snapshots.isNotEmpty())
        assertEquals(snapshots, snapshots.sorted())
        assertEquals(300 * 1024L, snapshots.last())
    }

    @Test
    fun `move op removes source`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val src = makeFile("m.bin")
        val dest = tmp.newFolder("d3")

        q.enqueue(OpSpec(OpKind.MOVE, title = "نقل", sources = listOf(src), target = dest))
        testScheduler.advanceUntilIdle()

        assertTrue(File(dest, "m.bin").exists())
        assertFalse(src.exists())
    }

    @Test
    fun `copy recurses into directories applying policy per file`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val root = tmp.newFolder("tree")
        File(root, "top.txt").writeText("T")
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "inner.txt").writeText("I")
        val dest = tmp.newFolder("d4")

        q.enqueue(OpSpec(OpKind.COPY, title = "شجرة", sources = listOf(root), target = dest))
        testScheduler.advanceUntilIdle()

        assertTrue(File(dest, "tree/top.txt").exists())
        assertTrue(File(dest, "tree/sub/inner.txt").exists())
        assertEquals("I", File(dest, "tree/sub/inner.txt").readText())
    }

    // ----- السلة -----

    @Test
    fun `trash op moves to trash dir`() = runTest {
        val trash = File(tmp.root, ".trash-dir")
        val d = StandardTestDispatcher(testScheduler)
        val q = OpQueue(
            scope = newScope(d),
            trashDir = trash,
            ioDispatcher = d,
        )
        val src = makeFile("gone.txt")

        q.enqueue(OpSpec(OpKind.TRASH, title = "حذف", sources = listOf(src)))
        testScheduler.advanceUntilIdle()

        assertEquals(OpStatus.DONE, q.ops.value.single().status)
        assertFalse(src.exists())
        assertEquals(1, trash.listFiles()!!.size)
    }

    // ----- سياسات التعارض -----

    @Test
    fun `rename policy creates unique sibling`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val src = makeFile("dup.txt")
        val dest = tmp.newFolder("d5")
        File(dest, "dup.txt").writeText("existing")

        q.enqueue(OpSpec(OpKind.COPY, title = "نسخ", sources = listOf(src), target = dest, policy = ConflictPolicy.RENAME))
        testScheduler.advanceUntilIdle()

        assertEquals("existing", File(dest, "dup.txt").readText())
        assertTrue(File(dest, "dup (1).txt").exists())
    }

    @Test
    fun `skip policy leaves existing file untouched`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val src = makeFile("keep.txt")
        val dest = tmp.newFolder("d6")
        File(dest, "keep.txt").writeText("original")

        q.enqueue(OpSpec(OpKind.COPY, title = "نسخ", sources = listOf(src), target = dest, policy = ConflictPolicy.SKIP))
        testScheduler.advanceUntilIdle()

        assertEquals("original", File(dest, "keep.txt").readText())
    }

    @Test
    fun `overwrite policy replaces existing file`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val src = makeFile("ow.txt", 2048)
        val dest = tmp.newFolder("d7")
        File(dest, "ow.txt").writeText("old")

        q.enqueue(OpSpec(OpKind.COPY, title = "نسخ", sources = listOf(src), target = dest, policy = ConflictPolicy.OVERWRITE))
        testScheduler.advanceUntilIdle()

        assertEquals(2048, File(dest, "ow.txt").length())
    }

    // ----- التسلسل والإلغاء والإيقاف -----

    @Test
    fun `ops execute sequentially in fifo order`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val a = makeFile("1.txt")
        val b = makeFile("2.txt")
        val dest = tmp.newFolder("d8")

        q.enqueue(OpSpec(OpKind.COPY, title = "الأولى", sources = listOf(a), target = dest))
        q.enqueue(OpSpec(OpKind.COPY, title = "الثانية", sources = listOf(b), target = dest))
        testScheduler.advanceUntilIdle()

        val order = q.ops.value.sortedBy { it.id }
        assertEquals(2, order.size)
        order.forEach { assertEquals(OpStatus.DONE, it.status) }
        // الأول انتهى قبل أن يبدأ الثاني (عامل واحد متسلسل)
        assertTrue(order[0].endedAt!! <= order[1].startedAt!!)
    }

    @Test
    fun `cancel while queued marks cancelled and never runs`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val src = makeFile("c.txt")
        val dest = tmp.newFolder("d9")

        val big = makeFile("heavy.bin", 2 * 1024 * 1024)
        val id1 = q.enqueue(OpSpec(OpKind.COPY, title = "طويلة", sources = listOf(big), target = dest))
        val id2 = q.enqueue(OpSpec(OpKind.COPY, title = "منتظرة", sources = listOf(src), target = dest))

        q.cancel(id2)
        testScheduler.advanceUntilIdle()

        assertEquals(OpStatus.CANCELLED, q.ops.value.single { it.id == id2 }.status)
        assertEquals(OpStatus.DONE, q.ops.value.single { it.id == id1 }.status)
        assertFalse(File(dest, "c.txt").exists())
    }

    @Test
    fun `cancel while paused at gate stops the op`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val src = makeFile("p.bin", 1024)
        val dest = tmp.newFolder("d10")

        val id = q.enqueue(OpSpec(OpKind.COPY, title = "موقوفة", sources = listOf(src), target = dest))
        q.pause(id)
        runCurrent() // العامل يسحب العملية ويصطدم ببوابة الإيقاف

        assertEquals(OpStatus.PAUSED, q.ops.value.single().status)
        assertFalse(File(dest, "p.bin").exists())

        q.cancel(id)
        testScheduler.advanceUntilIdle()

        assertEquals(OpStatus.CANCELLED, q.ops.value.single().status)
        assertFalse(File(dest, "p.bin").exists())
    }

    @Test
    fun `pause halts then resume completes`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val src = makeFile("r.bin")
        val dest = tmp.newFolder("d11")

        val id = q.enqueue(OpSpec(OpKind.COPY, title = "قابلة للاستئناف", sources = listOf(src), target = dest))
        q.pause(id)
        runCurrent()

        assertEquals(OpStatus.PAUSED, q.ops.value.single().status)
        assertFalse(File(dest, "r.bin").exists())

        q.resume(id)
        testScheduler.advanceUntilIdle()

        assertEquals(OpStatus.DONE, q.ops.value.single().status)
        assertTrue(File(dest, "r.bin").exists())
    }

    // ----- الفشل والتنظيف -----

    @Test
    fun `failed op carries error message`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val src = makeFile("e.txt")

        // هدف مفقود → فشل متوقع
        q.enqueue(OpSpec(OpKind.COPY, title = "فاشلة", sources = listOf(src), target = null))
        testScheduler.advanceUntilIdle()

        val rec = q.ops.value.single()
        assertEquals(OpStatus.FAILED, rec.status)
        assertEquals("missing_target", rec.error)
        assertTrue(rec.endedAt!! > 0)
    }

    @Test
    fun `clear finished keeps only active records`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val src = makeFile("x.txt")
        val dest = tmp.newFolder("d12")

        q.enqueue(OpSpec(OpKind.COPY, title = "أ", sources = listOf(src), target = dest))
        testScheduler.advanceUntilIdle()
        q.clearFinished()

        assertTrue(q.ops.value.isEmpty())
        assertFalse(q.hasPending)
    }

    // ----- أدوات مساعدة -----

    @Test
    fun `uniqueSibling generates incremented names`() {
        val dir = tmp.newFolder("u")
        val first = File(dir, "file.txt")
        assertFalse(first.exists())
        assertEquals(first, OpQueue.uniqueSibling(first))
        first.writeText("x")
        assertEquals("file (1).txt", OpQueue.uniqueSibling(first).name)
        File(dir, "file (1).txt").writeText("x")
        assertEquals("file (2).txt", OpQueue.uniqueSibling(first).name)
    }

    @Test
    fun `totalBytes counts recursively in directories`() {
        val dir = tmp.newFolder("sizes")
        File(dir, "a").writeBytes(ByteArray(100))
        val sub = File(dir, "sub").apply { mkdirs() }
        File(sub, "b").writeBytes(ByteArray(250))
        val solo = File(tmp.root, "solo").apply { writeBytes(ByteArray(50)) }

        assertEquals(400, OpQueue.totalBytes(listOf(dir, solo)))
    }

    // ----- v0.30 — الضغط المتقدم عبر ArchiveRequest -----

    private fun advancedZipTest(
        request: com.novafiles.app.core.archive.ArchiveRequest,
        outName: String,
        expectWhole: Boolean = true,
        sourceBytes: ByteArray = ByteArray(8192) { (it % 251).toByte() },
        verify: (File, OpRecord) -> Unit,
    ) = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val srcDir2 = tmp.newFolder("adv-src")
        val src = File(srcDir2, "doc.bin").apply { writeBytes(sourceBytes) }
        val out = File(tmp.newFolder("adv-out"), outName)

        q.enqueue(OpSpec(OpKind.ZIP, title = "ضغط", sources = listOf(src), target = out, archive = request))
        testScheduler.advanceUntilIdle()

        val rec = q.ops.value.single()
        assertEquals(OpStatus.DONE, rec.status)
        if (expectWhole) {
            assertTrue("الناتج يجب أن يكون موجوداً: ${out.path}", out.exists() && out.length() > 0)
        }
        verify(out, rec)
    }

    @Test
    fun `advanced zip creates 7z with sha result info`() = advancedZipTest(
        com.novafiles.app.core.archive.ArchiveRequest(com.novafiles.app.core.archive.CreateFormat.SEVEN_Z),
        "doc.7z",
    ) { out, rec ->
        // فك 7z عبر محرك القراءة الموحد للتأكد من الصلاحية
        com.novafiles.app.core.archive.ArchiveEngine.extractAll(out, File(out.parentFile, "x"))
        assertTrue(rec.resultInfo!!.startsWith("sha256:"))
        assertTrue(rec.resultInfo!!.length >= "sha256:".length + 12)
    }

    @Test
    fun `advanced zip tar gz extractable via engine`() = advancedZipTest(
        com.novafiles.app.core.archive.ArchiveRequest(com.novafiles.app.core.archive.CreateFormat.TAR_GZ),
        "doc.tar.gz",
    ) { out, _ ->
        val dest = File(out.parentFile, "tgz")
        val count = com.novafiles.app.core.archive.ArchiveEngine.extractAll(out, dest)
        // مصدر ملف مفرد → المدخل باسمه مباشرة (لا مسار مجلد)
        assertEquals(1, count)
        assertEquals(8192, File(dest, "doc.bin").length())
    }

    @Test
    fun `advanced zip with password produces encrypted archive`() = advancedZipTest(
        com.novafiles.app.core.archive.ArchiveRequest(
            com.novafiles.app.core.archive.CreateFormat.ZIP,
            password = "سر القائمة".toCharArray(),
        ),
        "sec.zip",
    ) { out, rec ->
        val headers = net.lingala.zip4j.ZipFile(out, "سر القائمة".toCharArray()).fileHeaders
        assertTrue(headers.any { !it.isDirectory && it.isEncrypted })
        assertTrue(rec.resultInfo!!.startsWith("sha256:"))
    }

    @Test
    fun `advanced zip with split produces volumes and index`() = advancedZipTest(
        com.novafiles.app.core.archive.ArchiveRequest(
            com.novafiles.app.core.archive.CreateFormat.ZIP,
            splitBytes = 4L * 1024,
        ),
        "vol.zip",
        expectWhole = false,
        // بيانات عشوائية ضعيفة الانضغاط حتى يتجاوز الناتج حجم الجزء الواحد
        sourceBytes = ByteArray(20 * 1024).also { java.util.Random(99L).nextBytes(it) },
    ) { out, rec ->
        // الأجزاء تحمل امتداد الترقيم والأصل حُذف بعد التقسيم
        val dir = out.parentFile!!
        assertTrue(File(dir, "vol.zip.001").isFile)
        assertFalse(out.exists())
        val parts = dir.listFiles { f -> f.name.startsWith("vol.zip.") && f.name != "vol.zip.nvasplit" }!!
        assertTrue("يجب أن تكون هناك أجزاء متعددة", parts.size >= 2)
        assertTrue(File(dir, "vol.zip.nvasplit").isFile)
        assertTrue(rec.resultInfo!!.contains("volumes:"))
    }

    @Test
    fun `legacy zip path unchanged without archive request`() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val q = newQueue(newScope(d), d)
        val src = makeFile("legacy.bin", 2048)
        val out = File(tmp.newFolder("lg-out"), "legacy.zip")

        q.enqueue(OpSpec(OpKind.ZIP, title = "ضغط", sources = listOf(src), target = out))
        testScheduler.advanceUntilIdle()

        val rec = q.ops.value.single()
        assertEquals(OpStatus.DONE, rec.status)
        assertTrue(out.exists())
        // المسار التقليدي لا يضيف معلومات نتيجة
        assertEquals(null, rec.resultInfo)
        // صالح للقراءة بـ java.util.zip
        java.util.zip.ZipFile(out, java.util.zip.ZipFile.OPEN_READ, Charsets.UTF_8).use { zf ->
            assertTrue(zf.entries().asSequence().any { it.name == "legacy.bin" })
        }
    }
}
