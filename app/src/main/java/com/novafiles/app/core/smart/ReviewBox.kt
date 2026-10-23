package com.novafiles.app.core.smart

/**
 * v0.34.0 — صندوق المراجعة الأسبوعي (pure Kotlin)
 *
 * بدل الحذف الصامت: مرة كل أسبوع تُعرض تنزيلات الأسبوع مصنّفة،
 * والمستخدم يقرر (احتفاظ نهائي / حذف / تأجيل أسبوع).
 * التجاهل = «احتفاظ» يُسجَّل بمسار الملف ولا يعود للعرض أبداً.
 */
object ReviewBox {

    /** هل حان موعد المراجعة؟ (لا مراجعة سابقة = مستحقة فوراً) */
    fun isDue(lastReviewedAt: Long?, nowMs: Long, intervalDays: Int = 7): Boolean {
        if (lastReviewedAt == null) return true
        return nowMs - lastReviewedAt >= intervalDays * DownloadClassifier.DAY_MS
    }

    /**
     * بناء الحزمة: مستبعد المسارات المتجاهَلة نهائياً،
     * الفئات بترتيب التعريف (مستند ← وسائط ← مثبّت ← أرشيف ← فوضى)،
     * والعناصر داخل الفئة بالحجم تنازلياً ثم بالاسم تصاعدياً — حتمي كامل.
     */
    fun build(
        items: List<ReviewItem>,
        lastReviewedAt: Long?,
        nowMs: Long,
        intervalDays: Int = 7,
        dismissedPaths: Set<String> = emptySet(),
    ): ReviewBundle {
        val kept = items.filter { it.path !in dismissedPaths }
        val groups = DownloadClass.entries.mapNotNull { c ->
            val list = kept.filter { it.clazz == c }
                .sortedWith(compareByDescending<ReviewItem> { it.size }.thenBy { it.name })
            if (list.isEmpty()) null else c to list
        }
        val days = lastReviewedAt?.let { (nowMs - it).coerceAtLeast(0L) / DownloadClassifier.DAY_MS }
        return ReviewBundle(
            due = isDue(lastReviewedAt, nowMs, intervalDays),
            daysSinceLastReview = days,
            groups = groups,
        )
    }

    /** تحويل الملفات المصنفة لعناصر مراجعة */
    fun itemsOf(classified: List<ClassifiedFile>): List<ReviewItem> = classified.map {
        ReviewItem(it.path, it.name, it.size, it.ageDays, it.clazz)
    }
}
