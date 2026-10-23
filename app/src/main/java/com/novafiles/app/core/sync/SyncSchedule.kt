package com.novafiles.app.core.sync

/**
 * v0.32.0 — جدولة أزواج المزامنة (خالص).
 *
 * فترات محددة مسبقاً (يدوي/ساعة/٦/١٢/يوم/أسبوع) + قيود تشغيل صريحة
 * (Wi-Fi فقط / الشحن فقط) بنمط قيود مزامنة الكاميرا v0.16 — وحساب
 * موعد الجولة القادمة للعرض. الجدولة الفعلية عبر WorkManager في
 * [com.novafiles.app.core.sync.SyncWorker]، وهنا منطقها الخالص فقط.
 */

/** فترات الجدولة — null = يدوي فقط (لا جدولة) */
enum class SyncInterval(val millis: Long?) {
    MANUAL(null),
    HOURLY(60L * 60L * 1000L),
    EVERY_6_HOURS(6L * 60L * 60L * 1000L),
    EVERY_12_HOURS(12L * 60L * 60L * 1000L),
    DAILY(24L * 60L * 60L * 1000L),
    WEEKLY(7L * 24L * 60L * 60L * 1000L),
    ;

    companion object {
        /** تحليل آمن من اسم مخزن — أي قيمة غريبة تعود يدوي */
        fun fromName(name: String?): SyncInterval =
            entries.firstOrNull { it.name == name } ?: MANUAL
    }
}

/** بيئة الجهاز لحظة التقييم — تُجمع من طبقة أندرويد */
data class PairRunEnvironment(
    val networkMetered: Boolean,
    val charging: Boolean,
)

/** قيود تشغيل الزوج */
data class PairConstraints(
    val wifiOnly: Boolean = true,
    val requireCharging: Boolean = false,
) {
    /** هل البيئة تسمح بالتشغيل الآن؟ */
    fun satisfied(env: PairRunEnvironment): Boolean {
        if (wifiOnly && env.networkMetered) return false
        if (requireCharging && !env.charging) return false
        return true
    }
}

/** حالة جولة مزامنة */
enum class SyncRunStatus {
    RUNNING,
    SUCCESS,
    PARTIAL,   // نجح جزئياً — بعض العناصر فشلت
    FAILED,    // فشل كامل
    CANCELLED, // أُلغي بال مستخدم
    BLOCKED,   // منعته القيود (شبكة مقيسة / لا شحن)
    ;

    companion object {
        fun fromName(name: String?): SyncRunStatus =
            entries.firstOrNull { it.name == name } ?: FAILED
    }
}

/** تقرير جولة زوج واحد — يحسبه PairSyncRunner ويخزنه في sync_runs */
data class PairRunReport(
    val copied: Int = 0,
    val updated: Int = 0,
    val deleted: Int = 0,
    val skipped: Int = 0,
    val conflicts: Int = 0,
    val failed: Int = 0,
    val bytesTransferred: Long = 0L,
    /** ملفات تحقق البصمة بعد النسخ: سليمة / فاشلة */
    val verifiedOk: Int = 0,
    val verifyFailed: Int = 0,
    val cancelled: Boolean = false,
) {
    /** تصنيف الجولة حسب نتيجتها — قاعدة صريحة قابلة للاختبار */
    fun status(): SyncRunStatus = when {
        cancelled -> SyncRunStatus.CANCELLED
        failed == 0 -> SyncRunStatus.SUCCESS
        copied + updated + deleted > 0 -> SyncRunStatus.PARTIAL
        else -> SyncRunStatus.FAILED
    }

    companion object {
        val EMPTY = PairRunReport()
    }
}

object SyncSchedule {

    /**
     * موعد الجولة القادمة للعرض — الجدولة الفعلية بيد WorkManager.
     * لم تُشغَّل قط: من الآن + الفترة. شُغِّلت: من آخر تشغيل + الفترة
     * (قد يكون في الماضي = متأخرة، والعرض يوضح ذلك).
     *
     * @return null لليدوي — لا موعد.
     */
    fun nextRunAt(nowMs: Long, lastRunMs: Long?, interval: SyncInterval): Long? {
        val base = interval.millis ?: return null
        return (lastRunMs ?: nowMs) + base
    }

    /** هل موعد الجولة القادمة متأخر عن اللحظة الحالية؟ */
    fun isOverdue(nowMs: Long, nextRunMs: Long?): Boolean =
        nextRunMs != null && nextRunMs < nowMs
}
