package com.novafiles.app.core.report

import com.novafiles.app.core.analyzer.DuplicateGroup
import com.novafiles.app.core.analyzer.DuplicateReport
import com.novafiles.app.core.analyzer.DirNode
import com.novafiles.app.core.analyzer.JunkCategory
import com.novafiles.app.core.analyzer.JunkItem
import com.novafiles.app.core.analyzer.JunkReport
import com.novafiles.app.core.analyzer.NotableFile
import com.novafiles.app.core.analyzer.StorageReport

/**
 * v0.36.0 — مصدّر التقارير الخالص: كل نتائج التحليل إلى CSV/JSON بمشاركة واحدة.
 *
 * الترتيب حتمي دائماً (حجم تنازلي ثم مسار تصاعدي) فيعطي نفس الإدخال نفس الملف
 * بايت-ببايت — يسمح بالاختبار الصارم وبمقارنة تقارير بين أيامين.
 *
 * صيغة CSV: رؤوس إنجليزية ثابتة (قابلة للفتح في أي أداة) + BOM للعربي في Excel.
 * صيغة JSON: كاتب مصغّر خاص بتهريب كامل (نمط RuleCodec) — البنية وثيقة
 * ذاتية الوصف بحقل "nvreport" للتحقق من النوع.
 */
object ReportExporter {

    // ═══════════ تقرير فئات التخزين ═══════════

    fun storageCsv(report: StorageReport): String {
        val rows = mutableListOf(listOf("category", "files", "bytes"))
        report.bySizeDesc.forEach { c ->
            rows.add(listOf(c.category.key, c.fileCount.toString(), c.totalBytes.toString()))
        }
        rows.add(listOf("total", report.totalFiles.toString(), report.usedBytes.toString()))
        return CsvWriter.write(rows)
    }

    fun storageJson(report: StorageReport): String {
        val sb = StringBuilder()
        sb.append("{\"nvreport\":\"storage\",\"totalFiles\":").append(report.totalFiles)
            .append(",\"totalDirs\":").append(report.totalDirs)
            .append(",\"usedBytes\":").append(report.usedBytes)
            .append(",\"categories\":[")
        report.bySizeDesc.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            sb.append("{\"category\":").append(q(c.category.key))
                .append(",\"files\":").append(c.fileCount)
                .append(",\"bytes\":").append(c.totalBytes)
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    // ═══════════ شجرة الأدلة المجمّعة ═══════════

    fun dirsCsv(nodes: List<DirNode>): String {
        val rows = mutableListOf(listOf("path", "subtree_bytes", "direct_file_bytes", "child_dirs", "file_count"))
        // الحتمية: حجم الشجرة تنازلياً ثم المسار تصاعدياً
        nodes.sortedWith(compareByDescending<DirNode> { it.subtreeBytes }.thenBy { it.path })
            .forEach { n ->
                rows.add(
                    listOf(n.path, n.subtreeBytes.toString(), n.directFileBytes.toString(),
                        n.childDirs.toString(), n.fileCount.toString()),
                )
            }
        return CsvWriter.write(rows)
    }

    // ═══════════ الملفات البارزة (كبيرة/قديمة) ═══════════

    fun notableCsv(items: List<NotableFile>): String {
        val rows = mutableListOf(listOf("path", "size", "modified_epoch"))
        items.sortedWith(compareByDescending<NotableFile> { it.size }.thenBy { it.path })
            .forEach { n ->
                rows.add(listOf(n.path, n.size.toString(), n.modified.toString()))
            }
        return CsvWriter.write(rows)
    }

    // ═══════════ المكررات ═══════════

    fun duplicatesCsv(report: DuplicateReport): String {
        val rows = mutableListOf(listOf("group", "exact", "size", "hash", "path", "role"))
        report.groups.forEachIndexed { gi, g ->
            g.items.forEachIndexed { ii, item ->
                rows.add(
                    listOf(
                        (gi + 1).toString(),
                        g.exact.toString(),
                        item.size.toString(),
                        item.hash,
                        item.path,
                        if (ii == 0) "representative" else "duplicate",
                    ),
                )
            }
        }
        return CsvWriter.write(rows)
    }

    // ═══════════ المخلفات ═══════════

    fun junkCsv(report: JunkReport): String {
        val rows = mutableListOf(listOf("path", "size", "category", "copies"))
        report.items.sortedWith(compareByDescending<JunkItem> { it.size }.thenBy { it.path })
            .forEach { j ->
                rows.add(listOf(j.path, j.size.toString(), j.category.name, j.copies.toString()))
            }
        return CsvWriter.write(rows)
    }

    // ═══════════ تقرير قبل/بعد التنظيف ═══════════

    /** لقطة حالة قبل التنظيف وبعده — عدد الملفات والمجموع بالبايت */
    data class CleanupSnapshot(
        val fileCount: Int,
        val totalBytes: Long,
        val atEpoch: Long,
    )

    /**
     * تقرير قبل/بعد JSON: الوفر المحسوب (قبل−بعد) لا يقبل السالب — التنظيف
     * الذي زاد الحجم (نادر: أرشفة دون حذف) يعطي freed=0 مع البيانات الصادقة.
     */
    fun cleanupJson(before: CleanupSnapshot, after: CleanupSnapshot, removedFiles: List<String>): String {
        val freed = (before.totalBytes - after.totalBytes).coerceAtLeast(0)
        val sb = StringBuilder()
        sb.append("{\"nvreport\":\"cleanup\"")
            .append(",\"before\":{\"files\":").append(before.fileCount)
            .append(",\"bytes\":").append(before.totalBytes)
            .append(",\"at\":").append(before.atEpoch).append('}')
            .append(",\"after\":{\"files\":").append(after.fileCount)
            .append(",\"bytes\":").append(after.totalBytes)
            .append(",\"at\":").append(after.atEpoch).append('}')
            .append(",\"freedBytes\":").append(freed)
            .append(",\"removedCount\":").append(removedFiles.size)
            .append(",\"removed\":[")
        removedFiles.sorted().forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append(q(p))
        }
        sb.append("]}")
        return sb.toString()
    }

    /** نظير CSV للتقرير — سطر واحد ملخص (سهل اللصق في ملاحظة أو جدول بيانات) */
    fun cleanupCsv(before: CleanupSnapshot, after: CleanupSnapshot, removedFiles: List<String>): String {
        val freed = (before.totalBytes - after.totalBytes).coerceAtLeast(0)
        return CsvWriter.write(
            listOf(
                listOf("metric", "value"),
                listOf("before_files", before.fileCount.toString()),
                listOf("before_bytes", before.totalBytes.toString()),
                listOf("after_files", after.fileCount.toString()),
                listOf("after_bytes", after.totalBytes.toString()),
                listOf("freed_bytes", freed.toString()),
                listOf("removed_count", removedFiles.size.toString()),
            ),
        )
    }

    // ═══════════ أسماء ملفات التصدير ═══════════

    /**
     * اسم ملف مقترح: nova-<kind>-<yyyyMMdd-HHmm>.<ext> — حتمي من الوقت
     * يمنع التصادم في المجلد الهدف ويرتب التصديرات زمنياً في قوائم الملفات.
     */
    fun suggestName(kind: String, ext: String, epochMillis: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = epochMillis
        }
        val stamp = String.format(
            "%04d%02d%02d-%02d%02d",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
        val safeKind = kind.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }.ifEmpty { "report" }
        val safeExt = ext.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.ifEmpty { "txt" }
        return "nova-$safeKind-$stamp.$safeExt"
    }

    // ─────────────────────────── كاتب JSON ───────────────────────────

    /** اقتباس JSON كامل: اقتباس، خط مائل عكسي، محارف تحكم → \uXXXX (عربي/إيموجي سليمة) */
    private fun q(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
