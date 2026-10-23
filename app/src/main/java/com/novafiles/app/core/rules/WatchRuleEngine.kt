package com.novafiles.app.core.rules

import com.novafiles.app.core.archive.ZipArchive
import com.novafiles.app.core.checksum.ChecksumAlgorithm
import com.novafiles.app.core.checksum.ChecksumEngine
import com.novafiles.app.core.checksum.ChecksumManifest
import com.novafiles.app.core.fs.FileOps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * v0.35.0 — محرك قواعد الأتمتة الخالص: «عند ظهور ملف يطابق الشروط في المجلد
 * المراقب، نفّذ الأفعال بالترتيب».
 *
 * خالص JVM 100%:
 * - المطابقة والتخطيط دوال حتمية بلا أي IO جانبي.
 * - التنفيذ يعمل فوق java.io مباشرة (أقراص مؤقتة في الاختبارات)، والأفعال التي
 *   تحتاج Android (وسم Room / رفع سحابي) تُسلَّم عبر واجهة [RuleActionHost].
 * - وضع التجربة الجافة (dryRun) يخطط دون أي أثر على القرص — مُختبر بالكامل.
 * - كل نقل يُسجَّل في دفترة تراجع — [undoRun] يعيد الملفات لمصدرها بأسمائها.
 * - المخرجات الذاتية للمحرك (أرشيفات القاعدة وملفات البصمات) مستثناة من
 *   المطابقة دائماً — لا حلقات تغذية راجعة.
 */
class WatchRuleEngine(
    private val host: RuleActionHost,
    val log: RuleExecutionLog = RuleExecutionLog(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** سعة دفترة التراجع (عدد التشغيلات القابلة للتراجع) */
    private val undoCapacity: Int = 30,
) {

    /** مقبض الأفعال التي تحتاج منصة: وسم (Room) ورفع سحابي (CloudFs) */
    interface RuleActionHost {
        /** وسم مسار — يُنفَّذ فوق TagsDao في التطبيق */
        suspend fun tag(path: String, tag: String)

        /** رفع ملف إلى حساب سحابي — يعيد true عند النجاح */
        suspend fun upload(file: File, accountId: Long, remoteDir: String): Boolean
    }

    // ─────────────────────────── المطابقة والتخطيط ───────────────────────────

    /**
     * ملفات مجلد المراقبة المطابقة لشروط القاعدة.
     * المجلدات مستثناة دائماً، ومخرجات المحرك الذاتية (checksums.* وأرشيف
     * القاعدة المتوقع باسم {date}) مستثناة لمنع حلقات إعادة الأرشفة.
     * الترتيب أبجدي حتمي — نفس المدخلات تعطي نفس الخطة.
     */
    fun matchedFiles(rule: WatchRule, candidates: List<File>): List<File> {
        val artifacts = selfArtifacts(rule)
        return candidates
            .filter { it.isFile }
            .filter { it.name !in artifacts }
            .filter { rule.conditions.matches(it.name, it.length()) }
            .sortedBy { it.name.lowercase() }
    }

    /** خطة تجربة جافة: لكل ملف مطابق الأفعال التي ستنفذ عليه بالترتيب */
    fun plan(rule: WatchRule, candidates: List<File>): List<PlannedFile> =
        matchedFiles(rule, candidates).map { PlannedFile(it, rule.actions) }

    data class PlannedFile(val file: File, val actions: List<RuleAction>)

    /**
     * أسماء المخرجات التي يولدها المحرك لهذه القاعدة (بالوقت الحالي):
     * أسماء أرشيفات ZIP المتوقعة (نمط {date} محلولاً) + ملفات البصمات الثلاثة.
     * تُستثنى من المطابقة منعاً للحلقات.
     */
    fun selfArtifacts(rule: WatchRule): Set<String> {
        val names = HashSet<String>()
        names += listOf("checksums.md5", "checksums.sha1", "checksums.sha256")
        for (a in rule.actions) {
            if (a is RuleAction.ArchiveZip) names += resolveArchiveName(a.namePattern) + ".zip"
        }
        return names
    }

    /** حل رمز {date} في اسم الأرشيف → yyyy-MM حسب ساعة المحرك (Locale.US حتمي) */
    fun resolveArchiveName(pattern: String, at: Long = clock()): String =
        pattern.replace("{date}", SimpleDateFormat("yyyy-MM", Locale.US).format(at))

    // ─────────────────────────── التنفيذ ───────────────────────────

    data class RunEntry(
        val file: String,
        val action: RuleAction,
        val ok: Boolean,
        val detail: String,
    )

    data class RuleRunOutcome(
        val runId: Long,
        val ruleId: String,
        val ruleName: String,
        val dryRun: Boolean,
        val matched: Int,
        val ok: Int,
        val failed: Int,
        val entries: List<RunEntry>,
    )

    /**
     * تشغيل قاعدة على قائمة مرشحين (تُرشَّح داخلياً بـ [matchedFiles]).
     *
     * ترتيب التنفيذ:
     * 1. الأفعال الفردية (نقل/بصمة/وسم/رفع) — لكل ملف مطابق بالترتيب.
     * 2. الأفعال الدفيعية (أرشفة ZIP) — **مرة واحدة** على كل الملفات المطابقة
     *    ما تزال موجودة وقت التنفيذ؛ لا يُعاد استخراج الأرشيف لكل ملف.
     *
     * - قاعدة معطلة: حدث SKIPPED وناتج فارغ.
     * - dryRun: تخطيط فقط + حدث DRY_RUN — صفر أثر على القرص.
     * - عزل الأعطال: فشل فعل لا يوقف بقية أفعال الملف ولا بقية الملفات.
     * - النقل يُسجَّل في دفترة التراجع.
     */
    suspend fun runRule(rule: WatchRule, candidates: List<File>): RuleRunOutcome {
        val runId = ++runSeq
        val now = clock()
        if (!rule.enabled) {
            log.append(RuleEventType.SKIPPED, rule.id, rule.name, "disabled", time = now)
            return RuleRunOutcome(runId, rule.id, rule.name, rule.dryRun, 0, 0, 0, emptyList())
        }
        val matched = matchedFiles(rule, candidates)
        val perFileActions = rule.actions.filterNot { it is RuleAction.ArchiveZip }
        val batchActions = rule.actions.filterIsInstance<RuleAction.ArchiveZip>()
        if (rule.dryRun) {
            log.append(
                RuleEventType.DRY_RUN, rule.id, rule.name,
                "planned=${matched.size}", time = now,
            )
            val entries = matched.flatMap { f ->
                perFileActions.map { a -> RunEntry(f.name, a, ok = true, detail = "dry_run") }
            } + batchActions.map { a ->
                RunEntry("${matched.size} file(s)", a, ok = true, detail = "dry_run")
            }
            return RuleRunOutcome(runId, rule.id, rule.name, true, matched.size, entries.size, 0, entries)
        }

        log.append(RuleEventType.RUN_START, rule.id, rule.name, "files=${matched.size}", time = now)
        val entries = ArrayList<RunEntry>()
        val undoMoves = ArrayList<Pair<File, File>>() // (المستهدف الجديد، المصدر الأصلي)
        var ok = 0
        var failed = 0
        // 1) الأفعال الفردية لكل ملف
        for (f in matched) {
            for (a in perFileActions) {
                val e = executeAction(a, f, rule, undoMoves)
                entries += e
                if (e.ok) ok++ else failed++
                log.append(
                    if (e.ok) RuleEventType.ACTION_OK else RuleEventType.ACTION_FAILED,
                    rule.id, rule.name, "${f.name}: ${e.detail}",
                )
            }
        }
        // 2) الأفعال الدفيعية مرة واحدة (على ما بقي موجوداً بعد الأفعال الفردية)
        val alive = matched.filter { it.isFile && it.exists() }
        for (a in batchActions) {
            if (alive.isEmpty()) break
            val e = doArchiveBatch(a, alive, rule)
            entries += e
            if (e.ok) ok++ else failed++
            log.append(
                if (e.ok) RuleEventType.ACTION_OK else RuleEventType.ACTION_FAILED,
                rule.id, rule.name, "${alive.size} file(s): ${e.detail}",
            )
        }
        recordUndo(runId, rule, undoMoves)
        val ref = RuleRunRef(
            runId = runId, ruleId = rule.id, ruleName = rule.name,
            startedAt = now, dryRun = false, ok = ok, failed = failed,
            undoable = undoMoves.isNotEmpty(),
        )
        synchronized(runsBuf) {
            runsBuf.addFirst(ref)
            while (runsBuf.size > undoCapacity) runsBuf.removeLast()
            _runs.value = runsBuf.toList()
        }
        return RuleRunOutcome(runId, rule.id, rule.name, false, matched.size, ok, failed, entries)
    }

    /** تنفيذ فعل واحد على ملف واحد — يعيد صف نتيجة */
    private suspend fun executeAction(
        action: RuleAction,
        file: File,
        rule: WatchRule,
        undoMoves: MutableList<Pair<File, File>>,
    ): RunEntry = try {
        when (action) {
            is RuleAction.MoveTo -> doMove(action, file, undoMoves)
            // الأرشفة دفيعية تُنفَّذ في runRule مباشرة — هذا مسار احتياطي لملف واحد
            is RuleAction.ArchiveZip -> doArchiveBatch(action, listOf(file), rule)
            is RuleAction.Checksum -> doChecksum(action, file)
            is RuleAction.TagFile -> {
                host.tag(file.absolutePath, action.tag)
                RunEntry(file.name, action, ok = true, detail = "tag=${action.tag}")
            }
            is RuleAction.UploadCloud -> {
                val done = host.upload(file, action.accountId, action.remoteDir)
                RunEntry(
                    file.name, action, ok = done,
                    detail = if (done) "uploaded→${action.remoteDir}" else "upload_failed",
                )
            }
        }
    } catch (e: Exception) {
        RunEntry(file.name, action, ok = false, detail = e.message ?: "error")
    }

    // ─────────────────────────── الأفعال ───────────────────────────

    private fun doMove(
        action: RuleAction.MoveTo,
        file: File,
        undoMoves: MutableList<Pair<File, File>>,
    ): RunEntry {
        val targetDir = File(action.targetDir)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return RunEntry(file.name, action, ok = false, detail = "mkdir_failed")
        }
        val dst = FileOps.uniqueChild(targetDir, file.name)
        // نقل سريع داخل القرص نفسه، وإلا نسخ ثم حذف (عبر حدود التخزين)
        val moved = if (file.renameTo(dst)) true else run {
            file.copyTo(dst, overwrite = false)
            file.delete()
        }
        if (!moved || !dst.exists()) {
            return RunEntry(file.name, action, ok = false, detail = "move_failed")
        }
        undoMoves += dst to file
        return RunEntry(file.name, action, ok = true, detail = "moved→${dst.name}")
    }

    /**
     * أرشفة دفيعية: كل الملفات المطابقة الموجودة في ZIP واحد.
     * - append=true والأرشيف موجود: استخراج مؤقت → إضافة الجديد → إعادة ضغط ذرّية.
     * - deleteAfter: حذف الأصول بعد نجاح الأرشفة (غير قابل للتراجع — يُوثَّق في التفاصيل).
     * يعيد صف نتيجة واحد للتشغيل كله.
     */
    private fun doArchiveBatch(
        action: RuleAction.ArchiveZip,
        files: List<File>,
        rule: WatchRule,
    ): RunEntry {
        val watchDir = File(rule.watchFolder)
        if (!watchDir.isDirectory) {
            return RunEntry("${files.size} file(s)", action, ok = false, detail = "watch_folder_missing")
        }
        val baseName = resolveArchiveName(action.namePattern)
        val added: Int
        val out: File
        val existing = File(watchDir, "$baseName.zip")
        if (action.append && existing.exists()) {
            out = existing
            added = appendToZip(existing, files, watchDir)
        } else {
            out = FileOps.uniqueChild(watchDir, "$baseName.zip")
            added = ZipArchive.createZip(files, out)
        }
        var deleted = 0
        if (action.deleteAfter && out.isFile && added > 0) {
            // احذف الأصول التي دخلت الأرشيف فعلاً (الموجودة قبل الأرشفة)
            for (f in files) if (f.isFile && f.exists()) deleted += if (f.delete()) 1 else 0
        }
        return RunEntry(
            "${files.size} file(s)", action,
            ok = out.isFile,
            detail = "zipped $added→${out.name}" + if (deleted > 0) " deleted=$deleted" else "",
        )
    }

    /**
     * إلحاق ملفات بأرشيف قائم: استخراج مؤقت كامل → إضافة الجديد → إعادة ضغط
     * ذرّية (كتابة في ملف مؤقت ثم rename) — كلها فوق ZipArchive الخالص.
     * الإلحاق متطابق الأثر (idempotent): ملف يوجد اسمه داخل الأرشيف يُتخطى
     * لا يُكرر — إعادة التشغيل على نفس المجلد لا تغير الأرشيف.
     * يعيد عدد الملفات الجديدة التي دخلت الأرشيف فعلاً.
     */
    private fun appendToZip(existing: File, newFiles: List<File>, watchDir: File): Int {
        val tempDir = File(watchDir, ".nova-append-${existing.nameWithoutExtension}-${clock()}")
        try {
            tempDir.mkdirs()
            ZipArchive.extractAll(existing, tempDir)
            var added = 0
            for (f in newFiles) {
                if (f.isFile && f.exists() && !File(tempDir, f.name).exists()) {
                    f.copyTo(FileOps.uniqueChild(tempDir, f.name), overwrite = false)
                    added++
                }
            }
            val atomicOut = File(watchDir, "${existing.nameWithoutExtension}.tmp-${clock()}")
            val items = (tempDir.listFiles()?.filter { it.isFile } ?: emptyList())
                .sortedBy { it.name.lowercase() }
            ZipArchive.createZip(items, atomicOut)
            if (!atomicOut.renameTo(existing)) {
                atomicOut.delete()
                return 0 // الأرشيف الأصلي بقي سليماً — يُبلَّغ كفشل بالعدّاد
            }
            return added
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun doChecksum(action: RuleAction.Checksum, file: File): RunEntry {
        val dir = file.parentFile ?: return RunEntry(file.name, action, false, "no_parent")
        val engine = ChecksumEngine()
        val result = engine.hashFile(file, action.algorithm)
        if (result?.hex == null) {
            return RunEntry(file.name, action, ok = false, detail = "hash_failed")
        }
        val ext = when (action.algorithm) {
            ChecksumAlgorithm.MD5 -> "md5"
            ChecksumAlgorithm.SHA1 -> "sha1"
            ChecksumAlgorithm.SHA256 -> "sha256"
        }
        val manifest = File(dir, "checksums.$ext")
        val newLine = "${result.hex}  ${file.name}\n"
        // دمج: أسطر الملفات الأخرى القديمة تبقى، وسطر هذا الملف يُستبدل إن وُجد
        val merged = if (manifest.exists()) {
            val old = manifest.readText().lines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .filterNot { it.substringAfterLast("  ", "") == file.name }
            (old + newLine.trimEnd('\n')).joinToString("\n") + "\n"
        } else newLine
        manifest.writeText(merged)
        return RunEntry(file.name, action, ok = true, detail = "sum→${manifest.name}")
    }

    // ─────────────────────────── التراجع والتاريخ ───────────────────────────

    /**
     * تراجع عن تشغيل كامل: كل ملف نُقل يُعاد إلى مساره الأصلي (مع تجنب تعارض
     * الأسماء إن شُغل المصدر بعد النقل). يعيد عدد ما استُرد فعلاً.
     */
    suspend fun undoRun(runId: Long): Int {
        val moves = synchronized(undoJournal) { undoJournal.remove(runId) } ?: return 0
        var restored = 0
        val ruleName = synchronized(runsBuf) { runsBuf.firstOrNull { it.runId == runId }?.ruleName ?: "?" }
        for ((dst, src) in moves) {
            val back = if (src.exists()) FileOps.uniqueChild(src.parentFile ?: continue, dst.name) else src
            val done = dst.renameTo(back) || run {
                dst.copyTo(back, overwrite = false)
                dst.delete()
            }
            if (done) {
                restored++
                log.append(RuleEventType.UNDO, "", ruleName, "${dst.name}→${back.name}")
            } else {
                log.append(RuleEventType.UNDO_FAILED, "", ruleName, dst.name)
            }
        }
        synchronized(runsBuf) {
            runsBuf.removeAll { it.runId == runId }
            _runs.value = runsBuf.toList()
        }
        return restored
    }

    private fun recordUndo(runId: Long, rule: WatchRule, moves: List<Pair<File, File>>) {
        if (moves.isEmpty()) return
        synchronized(undoJournal) {
            undoJournal[runId] = moves
            // نظّف تشغيلات تجاوزت السعة
            while (undoJournal.size > undoCapacity) {
                val oldest = undoJournal.keys.minOrNull() ?: break
                undoJournal.remove(oldest)
            }
        }
    }

    /** تاريخ التشغيل — الأحدث أولاً (نسخة حية عبر StateFlow) */
    val runs: StateFlow<List<RuleRunRef>> get() = _runs.asStateFlow()

    private val undoJournal = HashMap<Long, List<Pair<File, File>>>()
    private val runsBuf = ArrayDeque<RuleRunRef>()
    private val _runs = MutableStateFlow<List<RuleRunRef>>(emptyList())
    private var runSeq = 0L

    /** مسح مجلد المراقبة: ملفات المستوى الأول فقط، مرتبة أبجدياً */
    fun scanFolder(folder: File): List<File> =
        folder.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()
}
