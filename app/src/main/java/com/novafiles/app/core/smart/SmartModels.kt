package com.novafiles.app.core.smart

/**
 * v0.34.0 — نماذج المحرك الذكي (pure Kotlin — بلا أي اعتماد أندرويد)
 *
 * مبادئ ملزمة من خارطة الطريق:
 *  - كل الحسابات حتمية وقابلة للاختبار JVM — لا ذكاء أسود.
 *  - كل اقتراح يحمل سببه (reasonKey + args) والوفر المتوقع بالبايت.
 *  - التجاهل نهائي: مفتاح مستقر لكل اقتراح يُحترم إلى الأبد.
 */

/** فئات تصنيف التنزيلات الخمس (مستند/وسائط/مثبّت/أرشيف/فوضى) */
enum class DownloadClass {
    DOCUMENT, MEDIA, INSTALLER, ARCHIVE, CLUTTER;
}

/** ملف مصنّف — مدخل صندوق المراجعة والاقتراحات */
data class ClassifiedFile(
    val path: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val ageDays: Int,
    val clazz: DownloadClass,
)

/** نقطة استهلاك أسبوعية: إجمالي المستخدم + أحجام الأدلة العليا (من فهرس Room) */
data class StoragePoint(
    val capturedAt: Long,
    val usedBytes: Long,
    val perDir: Map<String, Long> = emptyMap(),
)

/** حالة توقع الامتلاء */
enum class ForecastState {
    /** أقل من نقطتين — لا يمكن التوقع */
    INSUFFICIENT_DATA,
    /** الميل صفر عملياً — الاستقرار */
    STABLE,
    /** ينمو بمعدل قابل للقياس */
    GROWING,
    /** يتقلص — المستخدم يحرر مساحة */
    FREEING,
    /** ممتلئ أو أعلى من السعة */
    FULL,
}

/** نتيجة توقع التخزين بانحدار خطي */
data class ForecastResult(
    val state: ForecastState,
    /** المعدل الأسبوعي المقدَّر (بايت/أسبوع) — سالب عند التقلص */
    val weeklyRateBytes: Long,
    /** أسابيع حتى الامتلاء — null حين لا معنى له (تقلص/استقرار/بيانات ناقصة) */
    val weeksToFull: Double?,
    /** ثقة التوقع = R² مقصوص على [0,1] */
    val confidence: Double,
    /** الاستهلاك الحالي (آخر نقطة) */
    val currentBytes: Long,
)

/** نمو دليل عليا بين نقطتين متتاليتين */
data class DirGrowth(
    val path: String,
    val growthBytes: Long,
)

/** أنواع اقتراحات الصيانة */
enum class SuggestionKind {
    /** محتوى مكرر — النسخ الزائدة قابلة للحذف */
    DUPLICATE_CONTENT,
    /** كاش يضخم فوق الحد */
    CACHE_BLOAT,
    /** مجلد فوضوي بأغلبية ملفات فوضى */
    CLUTTERED_FOLDER,
    /** مثبّتات قديمة لم تعد بحاجة إليها */
    OLD_INSTALLERS,
}

/**
 * اقتراح صيانة محسوب — كل حقل معروض للمستخدم:
 * reasonKey/reasonArgs يترجمان في UI، والوفر بالبايت يُنسق عبر Format.bytes.
 */
data class MaintenanceSuggestion(
    val kind: SuggestionKind,
    /** اسم العرض (اسم الملف/المجلد) */
    val title: String,
    /** المسار الجذري للاقتراح */
    val path: String,
    val savingsBytes: Long,
    /** مفتاح سلسلة السبب في strings.xml */
    val reasonKey: String,
    /** وسائط السبب (أعداد وأحجام خام — ينسقها UI) */
    val reasonArgs: List<String>,
    /** المسارات التي يطالها الفعل عند القبول */
    val itemPaths: List<String>,
    /** مفتاح التجاهل النهائي المستقر */
    val key: String,
)

/** تقرير «نبضة التخزين» الكامل */
data class PulseReport(
    val trend: ForecastResult,
    val growingDirs: List<DirGrowth>,
    val suggestions: List<MaintenanceSuggestion>,
)

/** عنصر في صندوق المراجعة الأسبوعي */
data class ReviewItem(
    val path: String,
    val name: String,
    val size: Long,
    val ageDays: Int,
    val clazz: DownloadClass,
)

/** حزمة صندوق المراجعة — مستقرة ومرتبة حتمياً */
data class ReviewBundle(
    /** هل حان موعد المراجعة الأسبوعية؟ */
    val due: Boolean,
    /** أيام منذ آخر مراجعة — null إن لم تكن هناك مراجعة سابقة */
    val daysSinceLastReview: Long?,
    /** فئات غير فارغة بترتيب التعريف، وعناصر كل فئة بالحجم تنازلياً ثم الاسم */
    val groups: List<Pair<DownloadClass, List<ReviewItem>>>,
)
