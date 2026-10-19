package com.novafiles.app.core.fs

import java.io.File

/**
 * ══════════════════════════════════════════════════════════
 *  RecentsEngine — v0.19 «الأخيرة»
 *  ─ مسح موجَّه للجذر الداخلي بعمق محدود لالتقاط الملفات
 *    الأحدث تعديلاً (lastModified تنازلياً)
 *  ─ يتجاهل المجلدات المخفية ومجلدات النظام والسلة
 *  ─ حتمي تماماً: كسر التعادل بالمسار أبجدياً، وقص عند الحد
 *  ─ خالص JVM بالكامل (قابل للاختبار بلا أندرويد)
 * ══════════════════════════════════════════════════════════
 */
object RecentsEngine {

    /** مجلدات نظام مستبعدة دائماً من المسح (حجم ضخم أو محتوى غير ملائم) */
    val SYSTEM_EXCLUDE = setOf(
        "Android", "data", "obb", ".NovaFilesTrash", "vault",
        "LOST.DIR", ".thumbnails", "Podcasts", "notifications", "alarms", "ringtones",
    )

    /**
     * جمع أحدث الملفات من الجذور المحددة.
     * @param roots مجلدات البدء (عادةً الجذر الداخلي)
     * @param now الطابع الزمني المرجعي (للاختبارات الحتمية؛ الإنتاج = System.currentTimeMillis)
     * @param limit أقصى عدد للنتائج
     * @param maxDepth أقصى عمق مجلدات من الجذر (1 = مباشرة داخل الجذر)
     * @param excludeDirs أسماء مجلدات إضافية مستبعدة (تُدمج مع SYSTEM_EXCLUDE)
     * @return قائمة ملفات مرتبة من الأحدث للأقدم (كسر التعادل: المسار أبجدياً)
     */
    fun collect(
        roots: List<File>,
        now: Long = System.currentTimeMillis(),
        limit: Int = 150,
        maxDepth: Int = 3,
        excludeDirs: Set<String> = emptySet(),
    ): List<RecentFile> {
        require(limit > 0) { "limit must be positive" }
        require(maxDepth >= 1) { "maxDepth must be >= 1" }
        val excluded = SYSTEM_EXCLUDE + excludeDirs
        val found = ArrayList<RecentFile>()
        roots.forEach { root -> walk(root, depth = 1, maxDepth = maxDepth, excluded = excluded, found = found) }
        // الأحدث أولاً؛ عند تساوي الطابع الزمني: المسار أبجدياً لضمان الحتمية
        found.sortWith(compareByDescending<RecentFile> { it.modifiedAt }.thenBy { it.path })
        return found.take(limit)
    }

    private fun walk(dir: File, depth: Int, maxDepth: Int, excluded: Set<String>, found: MutableList<RecentFile>) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        // فرز الاسم يجعل المسح حتمياً حتى قبل الترتيب النهائي
        children.sortedBy { it.name }.forEach { child ->
            if (child.isDirectory) {
                if (depth >= maxDepth) return@forEach
                if (child.isHidden) return@forEach
                if (child.name in excluded) return@forEach
                walk(child, depth + 1, maxDepth, excluded, found)
            } else if (child.isFile && !child.isHidden) {
                found += RecentFile(
                    path = child.absolutePath,
                    name = child.name,
                    size = child.length(),
                    modifiedAt = child.lastModified(),
                )
            }
        }
    }
}

/** لقطة خفيفة عن ملف حديث — بلا أي اعتماد على أندرويد */
data class RecentFile(
    val path: String,
    val name: String,
    val size: Long,
    val modifiedAt: Long,
)

/**
 * ══════════════════════════════════════════════════════════
 *  RelativeTime — تصنيف حتمي للوقت النسبي (خالص JVM)
 *  الواجهة تُترجم النتيجة إلى نصوص مترجَمة (ar/en)
 * ══════════════════════════════════════════════════════════
 */
object RelativeTime {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L

    /** فئات الوقت النسبي بالمدة الصرفة (بلا مناطق زمنية = قابل للاختبار حتمياً) */
    sealed interface Label {
        /** أقل من دقيقة */
        data object Now : Label

        /** 1..59 دقيقة */
        data class Minutes(val count: Int) : Label

        /** 1..23 ساعة */
        data class Hours(val count: Int) : Label

        /** 24..47 ساعة */
        data object Yesterday : Label

        /** 2..6 أيام */
        data class Days(val count: Int) : Label

        /** أقدم من أسبوع — تعرضه الواجهة كتاريخ قصير */
        data object Older : Label
    }

    fun of(now: Long, then: Long): Label {
        val delta = (now - then).coerceAtLeast(0L)
        return when {
            delta < MINUTE_MS -> Label.Now
            delta < HOUR_MS -> Label.Minutes((delta / MINUTE_MS).toInt().coerceIn(1, 59))
            delta < DAY_MS -> Label.Hours((delta / HOUR_MS).toInt().coerceIn(1, 23))
            delta < 2 * DAY_MS -> Label.Yesterday
            delta < 7 * DAY_MS -> Label.Days((delta / DAY_MS).toInt().coerceIn(2, 6))
            else -> Label.Older
        }
    }
}
