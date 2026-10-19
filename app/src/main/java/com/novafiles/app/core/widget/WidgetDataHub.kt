package com.novafiles.app.core.widget

/**
 * v0.17 — مركز بيانات الودجات (home-screen widgets).
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * أربع ودجات تخطط لها الخارطة:
 *  1. اختصار التخزين — يفتح مساراً محبباً مباشرة
 *  2. الملفات الأخيرة — أحدث الملفات المفهرسة
 *  3. مفتاح الخزنة — قفل/فتح سريع
 *  4. حالة النقل — ملخص الطابور النشط
 *
 * كل ودجت له «حزمة عرض» صرفة: الحقول النهائية الجاهزة للرسم في RemoteViews،
 * مع صياغة تسميات الحجم والزمن هنا كي تبقى الودجات بلا منطق.
 */

/** إدخال ملف أخير */
data class RecentFileEntry(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val isFolder: Boolean,
)

/** حزمة ودجت الملفات الأخيرة */
data class RecentFilesPayload(
    val entries: List<RecentFileEntry>,
) {
    companion object {
        const val MAX_ROWS = 4

        /** اقتطاع القائمة للحد الأقصى وترتيبها تنازلياً بالزمن */
        fun of(entries: List<RecentFileEntry>): RecentFilesPayload =
            RecentFilesPayload(entries.sortedByDescending { it.lastModifiedMs }.take(MAX_ROWS))
    }
}

/** حالة نقل واحدة كما تظهر للودجت */
data class TransferBrief(
    val name: String,
    val state: TransferBriefState,
    val percent: Int,
    val speedBytesPerSec: Long,
)

enum class TransferBriefState { QUEUED, RUNNING, PAUSED, DONE, FAILED }

/** حزمة ودجت حالة النقل */
data class TransferStatusPayload(
    val active: Int,
    val done: Int,
    val failed: Int,
    val totalPercent: Int,
    val currentName: String?,
    val speedBytesPerSec: Long,
) {
    companion object {
        /** تجميع من قائمة مختصرة — منطق العرض كله هنا ليُختبر */
        fun of(items: List<TransferBrief>): TransferStatusPayload {
            val active = items.count { it.state == TransferBriefState.RUNNING || it.state == TransferBriefState.QUEUED }
            val done = items.count { it.state == TransferBriefState.DONE }
            val failed = items.count { it.state == TransferBriefState.FAILED }
            val paused = items.count { it.state == TransferBriefState.PAUSED }
            val percent = if (items.isEmpty()) 0 else items.sumOf { it.percent } / items.size
            val current = items.firstOrNull { it.state == TransferBriefState.RUNNING }?.name
                ?: items.firstOrNull { it.state == TransferBriefState.PAUSED }?.name
                ?: items.firstOrNull { it.state == TransferBriefState.QUEUED }?.name
            val speed = items.filter { it.state == TransferBriefState.RUNNING }.sumOf { it.speedBytesPerSec }
            return TransferStatusPayload(active + paused, done, failed, percent.coerceIn(0, 100), current, speed)
        }
    }
}

/** حزمة ودجت الخزنة */
data class VaultStatePayload(
    val locked: Boolean,
    val decoyActive: Boolean,
    val itemCount: Int,
)

/** حزمة ودجت اختصار التخزين */
data class StorageShortcutPayload(
    val label: String,
    val targetPath: String,
    val usedBytes: Long,
    val totalBytes: Long,
) {
    /** نسبة الاستخدام 0..100 — محمية من القسمة على صفر والقيم الشاذة */
    val usagePercent: Int
        get() = if (totalBytes <= 0) 0 else ((usedBytes.toDouble() / totalBytes) * 100)
            .toInt().coerceIn(0, 100)
}

object WidgetFormatters {

    /** تسمية حجم مفهومة — B/KB/MB/GB بأرقام مقرّبة بلا أصفار زائدة */
    fun sizeLabel(bytes: Long): String = when {
        bytes < 0 -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> {
            val kb = bytes / 1024.0
            "${trim(kb, 1)} KB"
        }
        bytes < 1024L * 1024 * 1024 -> {
            val mb = bytes / (1024.0 * 1024)
            "${trim(mb, 1)} MB"
        }
        else -> {
            val gb = bytes / (1024.0 * 1024 * 1024)
            "${trim(gb, 2)} GB"
        }
    }

    /** تقريب إلى كسور محددة مع حذف الـ .0 الزائدة: 1.0 → "1"، 1.5 → "1.5"، 1.25 → "1.25" */
    private fun trim(value: Double, fractions: Int): String {
        val rounded = Math.round(value * Math.pow(10.0, fractions.toDouble())) /
            Math.pow(10.0, fractions.toDouble())
        val s = rounded.toString()
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /** تسمية سرعة نقل: وحدة مناسبة + /ث */
    fun speedLabel(bytesPerSec: Long): String = when {
        bytesPerSec <= 0 -> "—"
        bytesPerSec < 1024 -> "$bytesPerSec B/s"
        bytesPerSec < 1024 * 1024 -> "${Math.round(bytesPerSec / 1024.0)} KB/s"
        else -> "${Math.round(bytesPerSec / (1024.0 * 1024) * 10) / 10.0} MB/s"
    }

    /** زمن نسبي مختصر للودجت (بلا مكتبات): الآن/قبل X د/س/ي */
    fun relativeTime(nowMs: Long, thenMs: Long, minutesWord: String, hoursWord: String, daysWord: String): String {
        val diff = (nowMs - thenMs).coerceAtLeast(0)
        val minutes = diff / 60000
        return when {
            minutes < 1 -> "•"
            minutes < 60 -> "$minutes $minutesWord"
            minutes < 60 * 24 -> "${minutes / 60} $hoursWord"
            else -> "${minutes / (60 * 24)} $daysWord"
        }
    }

    /** سطر وصف جاهز لصف الودجت: «1.2 MB • قبل 5 د» */
    fun recentSubtitle(entry: RecentFileEntry, nowMs: Long, minutes: String, hours: String, days: String): String =
        "${sizeLabel(entry.sizeBytes)} • ${relativeTime(nowMs, entry.lastModifiedMs, minutes, hours, days)}"
}
