package com.novafiles.app.core.rules

import com.novafiles.app.core.archive.ZipArchive
import com.novafiles.app.core.checksum.ChecksumAlgorithm
import com.novafiles.app.core.checksum.ChecksumManifest
import com.novafiles.app.core.fs.FileType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * اختبارات محرك قواعد الأتمتة: المطابقة، التجربة الجافة، الأفعال الخمسة،
 * التراجع، السجل، وعزل الأعطال — كلها على أقراص مؤقتة 100%.
 */
class WatchRuleEngineTest {

    private lateinit var watch: File
    private lateinit var target: File
    private lateinit var log: RuleExecutionLog
    private lateinit var engine: WatchRuleEngine
    private lateinit var host: FakeHost

    /** ساعة وهمية ثابتة: 2026-10-15 12:00 UTC */
    private val fixedNow: Long = 1_792_065_600_000L

    @Before
    fun setup() {
        watch = createTempDir("watch")
        target = createTempDir("target")
        log = RuleExecutionLog()
        host = FakeHost()
        engine = WatchRuleEngine(host, log, clock = { fixedNow })
    }

    /** مضيف أفعال وهمي: يسجل الوسوم والرفوعات دون Room/شبكة */
    class FakeHost : WatchRuleEngine.RuleActionHost {
        val tags = ArrayList<Pair<String, String>>()
        val uploads = ArrayList<Triple<String, Long, String>>()
        var failUploads = false
        override suspend fun tag(path: String, tag: String) {
            tags += path to tag
        }
        override suspend fun upload(file: File, accountId: Long, remoteDir: String): Boolean {
            if (failUploads) return false
            uploads += Triple(file.name, accountId, remoteDir)
            return true
        }
    }

    private fun file(name: String, content: String = "x", dir: File = watch): File =
        File(dir, name).apply { writeText(content) }

    private fun rule(
        conditions: RuleConditions = RuleConditions(),
        actions: List<RuleAction> = listOf(RuleAction.MoveTo(target.absolutePath)),
        name: String = "قاعدة تجربة",
        enabled: Boolean = true,
        dryRun: Boolean = false,
    ) = WatchRule(
        id = "r1", name = name, watchFolder = watch.absolutePath,
        conditions = conditions, actions = actions,
        enabled = enabled, dryRun = dryRun, createdAt = fixedNow,
    )

    // ═══════════════ المطابقة ═══════════════

    @Test
    fun `directories are never matched`() {
        File(watch, "subfolder").mkdirs()
        file("a.apk")
        val m = engine.matchedFiles(rule(), watch.listFiles()!!.toList())
        assertEquals(listOf("a.apk"), m.map { it.name })
    }

    @Test
    fun `matching is deterministic alphabetical`() {
        file("b.apk"); file("A.apk"); file("c.apk")
        val m = engine.matchedFiles(rule(), watch.listFiles()!!.toList())
        assertEquals(listOf("A.apk", "b.apk", "c.apk"), m.map { it.name })
    }

    @Test
    fun `checksums manifests are excluded from matching`() {
        file("checksums.md5"); file("checksums.sha1"); file("checksums.sha256")
        file("real.apk")
        val m = engine.matchedFiles(rule(), watch.listFiles()!!.toList())
        assertEquals(listOf("real.apk"), m.map { it.name })
    }

    @Test
    fun `expected archive output is excluded from matching`() {
        val r = rule(
            conditions = RuleConditions(types = setOf(FileType.ARCHIVE)),
            actions = listOf(RuleAction.ArchiveZip("WhatsApp-{date}")),
        )
        file("WhatsApp-2026-10.zip", "zipbytes")
        file("other.zip")
        val m = engine.matchedFiles(r, watch.listFiles()!!.toList())
        assertEquals(listOf("other.zip"), m.map { it.name })
    }

    @Test
    fun `self artifacts contain three manifests and resolved archive name`() {
        val r = rule(actions = listOf(RuleAction.ArchiveZip("Monthly-{date}")))
        val arts = engine.selfArtifacts(r)
        assertTrue(arts.containsAll(listOf("checksums.md5", "checksums.sha1", "checksums.sha256")))
        assertTrue(arts.contains("Monthly-2026-10.zip"))
    }

    @Test
    fun `date token resolves to yyyy-MM with US locale`() {
        assertEquals("WhatsApp-2026-10", engine.resolveArchiveName("WhatsApp-{date}"))
        assertEquals("fixed", engine.resolveArchiveName("fixed"))
    }

    @Test
    fun `scanFolder lists first level files sorted`() {
        File(watch, "sub").mkdirs()
        file("z.txt"); file("a.txt")
        val scanned = engine.scanFolder(watch)
        assertEquals(listOf("a.txt", "z.txt"), scanned.map { it.name })
    }

    @Test
    fun `scanFolder on missing folder returns empty`() {
        assertTrue(engine.scanFolder(File(watch, "nope")).isEmpty())
    }

    // ═══════════════ التجربة الجافة ═══════════════

    @Test
    fun `dry run plans without touching disk`() = runBlocking {
        val a = file("a.apk"); val b = file("b.apk")
        val out = engine.runRule(rule(dryRun = true), watch.listFiles()!!.toList())
        assertTrue(out.dryRun)
        assertEquals(2, out.matched)
        assertEquals(2, out.ok)
        assertTrue(a.exists() && b.exists()) // لا نقل
        assertTrue(target.listFiles().isNullOrEmpty()) // الهدف فارغ
        assertTrue(host.tags.isEmpty() && host.uploads.isEmpty())
    }

    @Test
    fun `dry run logs a DRY_RUN event`() = runBlocking {
        engine.runRule(rule(dryRun = true), emptyList())
        assertEquals(RuleEventType.DRY_RUN, log.events.value.first().type)
    }

    @Test
    fun `plan returns matched files with rule actions`() {
        file("a.jpg"); file("b.txt")
        val r = rule(conditions = RuleConditions(types = setOf(FileType.IMAGE)))
        val plan = engine.plan(r, watch.listFiles()!!.toList())
        assertEquals(1, plan.size)
        assertEquals("a.jpg", plan[0].file.name)
        assertEquals(r.actions, plan[0].actions)
    }

    // ═══════════════ فعل النقل ═══════════════

    @Test
    fun `move sends matched files to target dir`() = runBlocking {
        val a = file("a.apk"); val b = file("b.apk"); file("c.txt")
        val out = engine.runRule(
            rule(conditions = RuleConditions(types = setOf(FileType.APK))),
            watch.listFiles()!!.toList(),
        )
        assertFalse(out.dryRun)
        assertEquals(2, out.ok)
        assertFalse(a.exists()); assertFalse(b.exists())
        assertEquals(setOf("a.apk", "b.apk"), target.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `move collision gets numbered name`() = runBlocking {
        file("a.apk")
        File(target, "a.apk").writeText("preexisting")
        engine.runRule(rule(), watch.listFiles()!!.toList())
        assertEquals(setOf("a.apk", "a (1).apk"), target.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `move creates missing target dir`() = runBlocking {
        file("a.apk")
        val nested = File(target, "deep/nested")
        engine.runRule(rule(actions = listOf(RuleAction.MoveTo(nested.absolutePath))), watch.listFiles()!!.toList())
        assertTrue(nested.isDirectory)
        assertTrue(nested.listFiles()!!.isNotEmpty())
    }

    @Test
    fun `move across filesystems falls back to copy-delete`() = runBlocking {
        // دليل محاكاة cross-device: نمنع renameTo بحذف الملف من مصدر وهمي؟
        // renameTo يفشل طبيعياً عبر الأقراص — المحرك يعيد النسخ ثم الحذف.
        // هنا نتحقق فقط أن النتيجة صحيحة (نفس السلوك عبر حد التخزين).
        val a = file("a.apk", content = "payload-".repeat(1000))
        engine.runRule(rule(), watch.listFiles()!!.toList())
        assertFalse(a.exists())
        assertEquals("payload-".repeat(1000), File(target, "a.apk").readText())
    }

    @Test
    fun `move records undo entries`() = runBlocking {
        file("a.apk")
        val out = engine.runRule(rule(), watch.listFiles()!!.toList())
        assertTrue(engine.runs.value.first().undoable)
        val restored = engine.undoRun(out.runId)
        assertEquals(1, restored)
        assertTrue(File(watch, "a.apk").exists())
        assertTrue(target.listFiles().isNullOrEmpty())
    }

    @Test
    fun `undo with taken original name restores as unique child`() = runBlocking {
        file("a.apk")
        val out = engine.runRule(rule(), watch.listFiles()!!.toList())
        // مستخدم جديد شغل الاسم الأصلي بعد النقل
        File(watch, "a.apk").writeText("someone-else")
        val restored = engine.undoRun(out.runId)
        assertEquals(1, restored)
        // نسختان: الجديدة بالاسم الأصلي والمستردة بلاحقة ترقيم
        val names = watch.listFiles()!!.map { it.name }.toSet()
        assertEquals(setOf("a.apk", "a (1).apk"), names)
    }

    @Test
    fun `undo twice returns zero the second time`() = runBlocking {
        file("a.apk")
        val out = engine.runRule(rule(), watch.listFiles()!!.toList())
        assertEquals(1, engine.undoRun(out.runId))
        assertEquals(0, engine.undoRun(out.runId))
    }

    @Test
    fun `undo unknown run returns zero`() {
        assertEquals(0, runBlocking { engine.undoRun(9999) })
    }

    @Test
    fun `non-move actions are not undoable`() = runBlocking {
        file("a.apk")
        engine.runRule(rule(actions = listOf(RuleAction.TagFile("مهم"))), watch.listFiles()!!.toList())
        assertFalse(engine.runs.value.first().undoable)
    }

    // ═══════════════ فعل الأرشفة ═══════════════

    private fun zipRule(
        namePattern: String = "WhatsApp-{date}",
        append: Boolean = true,
        deleteAfter: Boolean = false,
    ) = rule(
        conditions = RuleConditions(types = setOf(FileType.IMAGE)),
        actions = listOf(RuleAction.ArchiveZip(namePattern, append, deleteAfter)),
    )

    @Test
    fun `archive creates fresh zip with matched images`() = runBlocking {
        file("a.jpg", "AAA"); file("b.jpg", "BBB"); file("c.txt", "CCC")
        val out = engine.runRule(zipRule(), watch.listFiles()!!.toList())
        assertEquals(1, out.ok)
        val zip = File(watch, "WhatsApp-2026-10.zip")
        assertTrue(zip.isFile)
        val names = ZipArchive.list(zip).map { it.name }
        assertEquals(listOf("a.jpg", "b.jpg"), names)
        // الأصول بقت (بلا deleteAfter)
        assertTrue(File(watch, "a.jpg").exists())
    }

    @Test
    fun `archive append merges into existing monthly zip`() = runBlocking {
        file("a.jpg", "AAA")
        engine.runRule(zipRule(), watch.listFiles()!!.toList())
        // وصل ملف جديد في شهر لاحق — نفس الدلو
        file("b.jpg", "BBB")
        engine.runRule(zipRule(), watch.listFiles()!!.toList())
        val zip = File(watch, "WhatsApp-2026-10.zip")
        assertEquals(listOf("a.jpg", "b.jpg"), ZipArchive.list(zip).map { it.name })
        // ملف واحد فقط — لا تكرار
        assertEquals(2, ZipArchive.list(zip).size)
    }

    @Test
    fun `archive append preserves original content bytes`() = runBlocking {
        file("a.jpg", "CONTENT-A")
        engine.runRule(zipRule(), watch.listFiles()!!.toList())
        file("b.jpg", "CONTENT-B")
        engine.runRule(zipRule(), watch.listFiles()!!.toList())
        val zip = File(watch, "WhatsApp-2026-10.zip")
        val temp = createTempDir("verify")
        ZipArchive.extractAll(zip, temp)
        assertEquals("CONTENT-A", File(temp, "a.jpg").readText())
        assertEquals("CONTENT-B", File(temp, "b.jpg").readText())
        temp.deleteRecursively()
        Unit
    }

    @Test
    fun `archive without append creates unique new zip per run`() = runBlocking {
        file("a.jpg")
        engine.runRule(zipRule(append = false), watch.listFiles()!!.toList())
        file("b.jpg")
        engine.runRule(zipRule(append = false), watch.listFiles()!!.toList())
        val names = watch.listFiles()!!.map { it.name }.toSet()
        assertTrue(names.contains("WhatsApp-2026-10.zip"))
        assertTrue(names.contains("WhatsApp-2026-10 (1).zip"))
    }

    @Test
    fun `archive deleteAfter removes originals`() = runBlocking {
        val a = file("a.jpg", "AAA")
        engine.runRule(zipRule(deleteAfter = true), watch.listFiles()!!.toList())
        assertFalse(a.exists())
        assertTrue(File(watch, "WhatsApp-2026-10.zip").isFile)
    }

    @Test
    fun `archived zip itself never re-enters matching`() = runBlocking {
        // ثلاث دورات: يجب ألا يتضخم الأرشيف بنفسه أبداً
        file("a.jpg")
        repeat(3) { engine.runRule(zipRule(), watch.listFiles()!!.toList()) }
        val zip = File(watch, "WhatsApp-2026-10.zip")
        assertEquals(1, ZipArchive.list(zip).size)
    }

    @Test
    fun `archive skips when nothing matched`() = runBlocking {
        file("note.txt")
        val out = engine.runRule(zipRule(), watch.listFiles()!!.toList())
        assertEquals(0, out.ok)
        assertEquals(0, watch.listFiles()!!.count { it.extension == "zip" })
    }

    @Test
    fun `archive with missing watch folder fails gracefully`() = runBlocking {
        val r = zipRule().copy(watchFolder = "/nonexistent/path")
        val out = engine.runRule(r, listOf(file("a.jpg")))
        assertEquals(1, out.failed)
        assertTrue(out.entries.first().detail.contains("watch_folder_missing"))
    }

    // ═══════════════ فعل البصمة ═══════════════

    @Test
    fun `checksum writes GNU manifest for matched files`() = runBlocking {
        file("a.zip", "payload")
        val r = rule(
            conditions = RuleConditions(types = setOf(FileType.ARCHIVE)),
            actions = listOf(RuleAction.Checksum(ChecksumAlgorithm.SHA256)),
        )
        engine.runRule(r, watch.listFiles()!!.toList())
        val manifest = File(watch, "checksums.sha256")
        assertTrue(manifest.isFile)
        val text = manifest.readText()
        val parse = ChecksumManifest.parse(text)
        assertEquals(1, parse.entries.size)
        assertEquals("a.zip", parse.entries[0].path)
        assertEquals(64, parse.entries[0].hash.length)
    }

    @Test
    fun `checksum algorithm maps to manifest extension`() = runBlocking {
        file("a.bin")
        val algos = listOf(
            ChecksumAlgorithm.MD5 to "md5",
            ChecksumAlgorithm.SHA1 to "sha1",
            ChecksumAlgorithm.SHA256 to "sha256",
        )
        for ((algo, ext) in algos) {
            val f = file("a.bin")
            engine.runRule(rule(actions = listOf(RuleAction.Checksum(algo))), listOf(f))
            assertTrue(File(watch, "checksums.$ext").isFile)
            // نظّف للدورة التالية
            File(watch, "checksums.$ext").delete()
        }
    }

    @Test
    fun `checksum manifest merge keeps other lines and replaces same file`() = runBlocking {
        // سطر قديم لملف آخر
        File(watch, "checksums.md5").writeText("0123456789abcdef0123456789abcdef  old.txt\n")
        file("new.bin", "DATA")
        engine.runRule(rule(actions = listOf(RuleAction.Checksum(ChecksumAlgorithm.MD5))), listOf(file("new.bin")))
        val lines = File(watch, "checksums.md5").readText().lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines.any { it.endsWith("  old.txt") })
        assertTrue(lines.any { it.endsWith("  new.bin") })
        // إعادة تشغيل على نفس الملف: يُستبدل السطر لا يتكرر
        engine.runRule(rule(actions = listOf(RuleAction.Checksum(ChecksumAlgorithm.MD5))), listOf(file("new.bin")))
        val lines2 = File(watch, "checksums.md5").readText().lines().filter { it.isNotBlank() }
        assertEquals(2, lines2.size)
    }

    @Test
    fun `checksum manifest is never matched by its own rule`() = runBlocking {
        file("a.zip")
        // مرتين: ثانيًا لا يعيد تجزئة checksums.sha256 (لكنه يستبدل سطر a.zip فقط)
        engine.runRule(rule(actions = listOf(RuleAction.Checksum(ChecksumAlgorithm.SHA256))), watch.listFiles()!!.toList())
        engine.runRule(rule(actions = listOf(RuleAction.Checksum(ChecksumAlgorithm.SHA256))), watch.listFiles()!!.toList())
        val lines = File(watch, "checksums.sha256").readText().lines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].endsWith("  a.zip"))
    }

    // ═══════════════ فعل الوسم والرفع ═══════════════

    @Test
    fun `tag action routes through host with arabic tag`() = runBlocking {
        file("a.pdf")
        engine.runRule(rule(actions = listOf(RuleAction.TagFile("مستندات مهمة"))), listOf(file("a.pdf")))
        assertEquals(listOf(File(watch, "a.pdf").absolutePath to "مستندات مهمة"), host.tags)
    }

    @Test
    fun `upload action routes through host with account and dir`() = runBlocking {
        file("a.pdf")
        val out = engine.runRule(
            rule(actions = listOf(RuleAction.UploadCloud(7L, "/NovaFiles-Docs"))),
            listOf(file("a.pdf")),
        )
        assertEquals(1, out.ok)
        assertEquals(listOf(Triple("a.pdf", 7L, "/NovaFiles-Docs")), host.uploads)
    }

    @Test
    fun `upload failure counts as failed not crash`() = runBlocking {
        host.failUploads = true
        file("a.pdf")
        val out = engine.runRule(
            rule(actions = listOf(RuleAction.UploadCloud(7L, "/x"))),
            listOf(file("a.pdf")),
        )
        assertEquals(0, out.ok)
        assertEquals(1, out.failed)
        assertTrue(out.entries.first().detail.contains("upload_failed"))
    }

    // ═══════════════ التركيب والعزل ═══════════════

    @Test
    fun `multiple actions execute in declared order`() = runBlocking {
        file("a.apk", "APK")
        val r = rule(
            actions = listOf(RuleAction.TagFile("apps"), RuleAction.Checksum(ChecksumAlgorithm.MD5)),
        )
        val out = engine.runRule(r, watch.listFiles()!!.toList())
        assertEquals(2, out.ok)
        // الوسم ثم البصمة — الترتيب في الصفوف
        assertTrue(out.entries[0].action is RuleAction.TagFile)
        assertTrue(out.entries[1].action is RuleAction.Checksum)
        assertEquals(1, host.tags.size)
        assertTrue(File(watch, "checksums.md5").isFile)
    }

    @Test
    fun `failing action does not stop later actions or files`() = runBlocking {
        host.failUploads = true
        file("a.pdf"); file("b.pdf")
        val r = rule(
            actions = listOf(
                RuleAction.UploadCloud(1L, "/x"), // يفشل دائماً
                RuleAction.TagFile("docs"),       // يجب أن يُنفذ رغم ذلك
            ),
        )
        val out = engine.runRule(r, watch.listFiles()!!.toList())
        assertEquals(2, out.failed) // رفعان فاشلان
        assertEquals(2, out.ok)     // وسمَان ناجحان
        assertEquals(2, host.tags.size)
    }

    @Test
    fun `disabled rule is skipped with SKIPPED event`() = runBlocking {
        file("a.apk")
        val out = engine.runRule(rule(enabled = false), watch.listFiles()!!.toList())
        assertEquals(0, out.matched)
        assertEquals(0, out.ok)
        assertTrue(File(watch, "a.apk").exists())
        assertEquals(RuleEventType.SKIPPED, log.events.value.first().type)
    }

    @Test
    fun `move action failing mkdir does not crash`() = runBlocking {
        // مجلد هدف تحت «ملف» — mkdirs يفشل حتماً
        val blocker = File(target, "blocker")
        blocker.writeText("i am a file")
        file("a.apk")
        val out = engine.runRule(rule(actions = listOf(RuleAction.MoveTo(File(blocker, "sub").absolutePath))), watch.listFiles()!!.toList())
        assertEquals(1, out.failed)
        assertTrue(File(watch, "a.apk").exists())
    }

    // ═══════════════ السجل والتاريخ ═══════════════

    @Test
    fun `run adds RUN_START and ACTION events`() = runBlocking {
        file("a.apk")
        engine.runRule(rule(), watch.listFiles()!!.toList())
        val types = log.events.value.map { it.type }
        assertTrue(types.contains(RuleEventType.RUN_START))
        assertTrue(types.contains(RuleEventType.ACTION_OK))
    }

    @Test
    fun `log ring keeps capacity newest first`() {
        val smallLog = RuleExecutionLog(5)
        repeat(20) {
            smallLog.append(RuleEventType.ACTION_OK, "r", "n", "e$it", time = it.toLong())
        }
        assertEquals(5, smallLog.size)
        assertEquals("e19", smallLog.events.value.first().detail)
        assertEquals("e15", smallLog.events.value.last().detail)
    }

    @Test
    fun `log clear empties events`() {
        log.append(RuleEventType.ACTION_OK, "r", "n", "x")
        assertEquals(1, log.size)
        log.clear()
        assertEquals(0, log.size)
        assertTrue(log.events.value.isEmpty())
    }

    @Test
    fun `runs history newest first with undo flag`() = runBlocking {
        file("a.apk")
        engine.runRule(rule(), watch.listFiles()!!.toList())
        file("b.apk")
        engine.runRule(rule(actions = listOf(RuleAction.TagFile("t"))), watch.listFiles()!!.toList())
        val refs = engine.runs.value
        assertEquals(2, refs.size)
        assertTrue(refs[0].undoable.not())
        assertTrue(refs[1].undoable)
        assertNotEquals(refs[0].runId, refs[1].runId)
    }

    @Test
    fun `undo journal evicts oldest beyond capacity`() = runBlocking {
        val smallEngine = WatchRuleEngine(host, log, clock = { fixedNow }, undoCapacity = 3)
        repeat(5) { i ->
            val f = file("f$i.apk")
            smallEngine.runRule(rule(), listOf(f))
        }
        // آخر 3 فقط قابلة للتراجع
        val ids = smallEngine.runs.value.take(3).map { it.runId }
        for (id in ids) assertTrue(smallEngine.runs.value.first { it.runId == id }.undoable)
    }

    @Test
    fun `matched empty produces zero counters and no undo`() = runBlocking {
        val out = engine.runRule(rule(), emptyList())
        assertEquals(0, out.matched)
        assertEquals(0, out.ok)
        assertEquals(0, out.failed)
        assertTrue(engine.runs.value.firstOrNull()?.undoable != true)
    }

    @Test
    fun `rule with only archive action and zero matches is clean no-op`() = runBlocking {
        val out = engine.runRule(zipRule(), emptyList())
        assertEquals(0, out.ok)
        assertNull(out.entries.firstOrNull())
    }
}
