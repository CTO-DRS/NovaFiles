package com.novafiles.app.ui.assistant

import com.novafiles.app.core.ai.AiTool
import com.novafiles.app.core.ai.ExecutionStep
import com.novafiles.app.core.ai.IntentParser
import com.novafiles.app.core.ai.ToolOutcome
import com.novafiles.app.core.analyzer.DuplicateEngine
import com.novafiles.app.core.analyzer.DuplicateOptions
import com.novafiles.app.core.analyzer.SnapshotRow
import com.novafiles.app.core.analyzer.StorageAnalyzer
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.core.rules.RuleAction
import com.novafiles.app.core.rules.RuleConditions
import com.novafiles.app.core.rules.RulesRepository
import com.novafiles.app.core.rules.WatchRule
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.41 — منفّذ أدوات المساعد فوق المحركات الحقيقية (طبقة أندرويد).
 *
 * مبدأ صارم: كل سطر يعود للمستخدم مصدره محرك حقيقي (فهرس Room، DuplicateEngine،
 * StorageAnalyzer، RulesRepository) — لا يوجد سطر مختلق في هذا الملف.
 * الأدوات الكاتبة تُستدعى فقط بعد تأكيد المستخدم من الواجهة.
 */
class AssistantExecutor(
    private val db: com.novafiles.app.data.db.NovaDb,
    private val rules: RulesRepository,
) {

    /** تنفيذ خطوة واحدة — READ/PLAN تُنفَّذ مباشرة، WRITE فقط بعد تأكيد */
    suspend fun execute(step: ExecutionStep, confirmed: Boolean): ToolOutcome {
        if (!AiToolRegistry_isAllowed(step.tool)) {
            return ToolOutcome.Failed("أداة غير مسموحة: ${step.tool.id}")
        }
        if (step.tool.requiresConfirmation && !confirmed) {
            return ToolOutcome.Failed("هذه الخطوة تحتاج تأكيدك الصريح قبل التنفيذ")
        }
        return when (step.tool) {
            AiTool.SEARCH_FILES -> searchFiles(step)
            AiTool.STORAGE_REPORT -> storageReport()
            AiTool.DUPLICATES_REPORT -> duplicates(limit = step.args["limit"]?.toIntOrNull() ?: 50)
            AiTool.LARGE_OLD_FILES -> largeOld(
                minMb = step.args[IntentParser.Keys.MIN_SIZE_MB]?.toLongOrNull() ?: 100L,
                olderDays = step.args[IntentParser.Keys.OLDER_THAN_DAYS]?.toIntOrNull() ?: 30,
                limit = step.args["limit"]?.toIntOrNull() ?: 30,
            )
            AiTool.ORGANIZE_PLAN -> organizePreview()
            AiTool.RULE_DRAFT -> rulePreview(step)
            AiTool.RULE_CREATE -> ruleCreate(step)
        }
    }

    private fun AiToolRegistry_isAllowed(tool: AiTool): Boolean =
        com.novafiles.app.core.ai.AiToolRegistry.isAllowed(tool)

    // ---------- الأدوات القرائية ----------

    private suspend fun searchFiles(step: ExecutionStep): ToolOutcome {
        val text = step.args[IntentParser.Keys.TEXT].orEmpty()
        val kind = com.novafiles.app.core.ai.AiFileKind.fromKey(
            step.args[IntentParser.Keys.FILE_KIND] ?: "ANY",
        )
        val minSizeMb = step.args[IntentParser.Keys.MIN_SIZE_MB]?.toLongOrNull() ?: 0L
        val withinDays = step.args[IntentParser.Keys.WITHIN_DAYS]?.toIntOrNull()
        val olderThan = step.args[IntentParser.Keys.OLDER_THAN_DAYS]?.toIntOrNull()
        val limit = step.args["limit"]?.toIntOrNull() ?: 100

        val rows = db.fileIndexDao().all()
        val now = System.currentTimeMillis()
        val cutoffMax = withinDays?.let { now - it * 86_400_000L }
        val cutoffMin = olderThan?.let { now - it * 86_400_000L }
        val needle = text.trim().lowercase()

        val hits = rows.asSequence()
            .filter { !it.isDirectory }
            .filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
            .filter { kind == com.novafiles.app.core.ai.AiFileKind.ANY || FileType.ofName(it.name) == kind.toFileType() }
            .filter { it.size >= minSizeMb * 1_048_576L }
            .filter { cutoffMax == null || it.modified >= cutoffMax }
            .filter { cutoffMin == null || it.modified < cutoffMin }
            .sortedByDescending { it.modified }
            .take(limit)
            .toList()

        if (hits.isEmpty()) return ToolOutcome.Ok(listOf("لا نتائج مطابقة في الفهرس الحالي."))
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return ToolOutcome.Ok(
            listOf("النتائج (${hits.size}):") + hits.map {
                "• ${it.name} — ${humanSize(it.size)} — ${fmt.format(Date(it.modified))}"
            },
        )
    }

    private suspend fun storageReport(): ToolOutcome {
        val rows = db.fileIndexDao().all().map {
            SnapshotRow(it.path, it.name, it.parent, it.size, it.modified, it.isDirectory)
        }
        if (rows.isEmpty()) {
            return ToolOutcome.Ok(listOf("الفهرس فارغ بعد — افتح شاشة التحليل لبناء لقطة أولاً."))
        }
        val report = StorageAnalyzer.analyze(rows)
        val top = report.bySizeDesc.filter { it.totalBytes > 0 }.take(6)
        val topDirs = StorageAnalyzer.aggregateTree(rows)
            .entries.asSequence()
            .sortedByDescending { it.value.subtreeBytes }
            .take(5)
            .toList()
        return ToolOutcome.Ok(
            listOf(
                "الملفات: ${report.totalFiles} — المجلدات: ${report.totalDirs} — المستخدم: ${humanSize(report.usedBytes)}",
            ) +
                top.map { "• ${it.category.name}: ${it.fileCount} ملف — ${humanSize(it.totalBytes)}" } +
                listOf("أكبر المجلدات:") +
                topDirs.map { "• ${it.value.name}: ${humanSize(it.value.subtreeBytes)}" },
        )
    }

    private suspend fun duplicates(limit: Int): ToolOutcome {
        val rows = db.fileIndexDao().all().filter { !it.isDirectory && it.size >= 1_048_576L }
        if (rows.isEmpty()) return ToolOutcome.Ok(listOf("لا ملفات ≥ 1MB في الفهرس — لا شيء لفحصه."))
        val report = DuplicateEngine(null).scan(
            rows.map { it.path },
            DuplicateOptions(minSizeBytes = 1_048_576L),
        )
        if (report.groups.isEmpty()) return ToolOutcome.Ok(listOf("لا توجد ملفات متطابقة بالمحتوى بين الملفات ≥ 1MB."))
        return ToolOutcome.Ok(
            listOf(
                "مجموعات متطابقة: ${report.groups.size} — يمكن توفير ${humanSize(report.totalWastedBytes)} (عرض فقط — الحذف من شاشة المكررات)",
            ) + report.groups.take(limit).map { g ->
                "• ${g.items.size} نسخ × ${humanSize(g.representative.size)}: ${g.representative.path.substringAfterLast('/')}"
            },
        )
    }

    private suspend fun largeOld(minMb: Long, olderDays: Int, limit: Int): ToolOutcome {
        val rows = db.fileIndexDao().all()
        val cutoff = System.currentTimeMillis() - olderDays * 86_400_000L
        val hits = rows.asSequence()
            .filter { !it.isDirectory && it.size >= minMb * 1_048_576L && it.modified < cutoff }
            .sortedByDescending { it.size }
            .take(limit)
            .toList()
        if (hits.isEmpty()) return ToolOutcome.Ok(listOf("لا ملفات كبيرة قديمة بهذه المعايير."))
        return ToolOutcome.Ok(
            listOf("مرشّحة للمراجعة (${hits.size}) — لن يُحذف أي شيء تلقائياً:") +
                hits.map { "• ${it.name} — ${humanSize(it.size)}" },
        )
    }

    private suspend fun organizePreview(): ToolOutcome {
        val dup = duplicates(limit = 20)
        val old = largeOld(minMb = 100, olderDays = 30, limit = 20)
        val lines = ArrayList<String>()
        lines += "اقتراح تنظيم قابل للتفسير — مراجعتك شرط، لا تنفيذ آلي:"
        when (dup) {
            is ToolOutcome.Ok -> lines += dup.lines
            is ToolOutcome.Failed -> lines += dup.reasonAr
        }
        when (old) {
            is ToolOutcome.Ok -> lines += old.lines
            is ToolOutcome.Failed -> lines += old.reasonAr
        }
        return ToolOutcome.Ok(lines)
    }

    // ---------- مسودة/حفظ القاعدة ----------

    private fun rulePreview(step: ExecutionStep): ToolOutcome {
        val kind = step.args[IntentParser.Keys.FILE_KIND] ?: "ANY"
        val target = step.args[IntentParser.Keys.TARGET_DIR].orEmpty()
        val rule = buildRule(kind, target)
        val errs = rule.validationErrors()
        return if (errs.isNotEmpty()) {
            ToolOutcome.Failed("مسودة القاعدة غير صالحة: $errs")
        } else {
            ToolOutcome.Ok(
                listOf(
                    "مسودة قاعدة جاهزة (لن تُحفظ حتى تأكّد):",
                    "• المراقبة: مجلد التنزيلات",
                    "• النوع: $kind",
                    "• الفعل: النقل إلى $target",
                    "• التحقق: سليم — الحفظ يتطلب تأكيدك الآن",
                ),
            )
        }
    }

    private suspend fun ruleCreate(step: ExecutionStep): ToolOutcome {
        val kind = step.args[IntentParser.Keys.FILE_KIND] ?: "ANY"
        val target = step.args[IntentParser.Keys.TARGET_DIR].orEmpty()
        val rule = buildRule(kind, target)
        val errs = rule.validationErrors()
        if (errs.isNotEmpty()) return ToolOutcome.Failed("القاعدة مرفوضة من التحقق: $errs")
        val current = rules.rules.first()
        rules.save(current + rule)
        return ToolOutcome.Ok(listOf("حُفظت القاعدة «${rule.name}» — تظهر في شاشة قواعد الأتمتة (وضع التجربة الجافة مفعّل)."))
    }

    private fun buildRule(fileKind: String, targetDir: String): WatchRule {
        val types: Set<FileType> =
            if (fileKind == "ANY") emptySet() else setOf(fileKindFromAi(fileKind))
        val now = System.currentTimeMillis()
        return WatchRule(
            id = "ai_${now}",
            name = "مساعد: نقل $fileKind إلى ${targetDir.substringAfterLast('/')}",
            watchFolder = "/storage/emulated/0/Download",
            conditions = RuleConditions(types = types),
            actions = listOf(RuleAction.MoveTo(targetDir)),
            enabled = true,
            dryRun = true, // الأمان أولاً: القاعدة تبدأ تجربة جافة يفعّلها المستخدم
            createdAt = now,
        )
    }

    private fun fileKindFromAi(key: String): FileType = when (key) {
        "PDF" -> FileType.PDF
        "IMAGE" -> FileType.IMAGE
        "VIDEO" -> FileType.VIDEO
        "AUDIO" -> FileType.AUDIO
        "ARCHIVE" -> FileType.ARCHIVE
        "DOC" -> FileType.DOC
        "APK" -> FileType.APK
        "CODE" -> FileType.CODE
        "TEXT" -> FileType.TEXT
        else -> FileType.OTHER
    }

    // ---------- أدوات مساعدة ----------

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun com.novafiles.app.core.ai.AiFileKind.toFileType(): FileType = fileKindFromAi(name)
}
