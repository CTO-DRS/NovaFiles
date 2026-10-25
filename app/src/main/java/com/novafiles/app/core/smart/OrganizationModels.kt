package com.novafiles.app.core.smart

/**
 * v0.41.0 — Nova Intelligence المرحلة B: نماذج اقتراحات التنظيم القابلة للتفسير.
 *
 * الفكرة: تحويل الملفات المبعثرة في أدلة التنزيل (وأمثالها) إلى مجلدات مرتّبة
 * (وسائط/مستندات/أرشيفات/مثبّتات قديمة) — كل اقتراح بسببه ودرجة ثقته،
 * ومعاينة كاملة قبل أي نقل، وامتناع موثّق حين تغيب البيانات.
 *
 * قواعد التنظيم بأولوية ثابتة 1→4 (الأولوية تُفسَّر في الاختبارات):
 *  1) OLD_INSTALLERS  — مثبّتات قديمة (≥ 30 يوماً) → Installers/
 *  2) LOOSE_ARCHIVES  — أرشيفات مبعثرة (≥ 2)     → Archives/
 *  3) LOOSE_DOCUMENTS — مستندات مبعثرة (≥ 3)     → Documents/
 *  4) LOOSE_MEDIA     — وسائط مبعثرة (≥ 3)       → Media/
 */
enum class OrgRule(
    val priority: Int,
    val targetDirName: String,
    val reasonKey: String,
) {
    OLD_INSTALLERS(1, "Installers", "org_reason_installers"),
    LOOSE_ARCHIVES(2, "Archives", "org_reason_archives"),
    LOOSE_DOCUMENTS(3, "Documents", "org_reason_documents"),
    LOOSE_MEDIA(4, "Media", "org_reason_media");

    companion object {
        /** القواعد بترتيب الأولوية الصارم — الترتيب الحتمي للنواتج يبدأ من هنا */
        val BY_PRIORITY: List<OrgRule> = entries.sortedBy { it.priority }

        /** أسماء الأهداف القياسية — الأدلة المسمّاة بها تُعتبر «منظّمة أصلاً» */
        val TARGET_NAMES: Set<String> = entries.map { it.targetDirName }.toSet()
    }
}

/**
 * نقل مقترح واحد — **الذيل محفوظ دائماً**: اسم الملف يبقى كما هو حرفياً،
 * النقل فقط قابل للعكس (لا إعادة تسمية إطلاقاً في المرحلة B).
 */
data class SuggestedMove(
    /** المسار الكامل الحالي للملف */
    val sourcePath: String,
    /** الدليل الهدف الكامل بعد النقل */
    val targetDir: String,
    /** ذيل المسار (اسم الملف) — يُنقل كما هو */
    val fileName: String,
) {
    /** المسار الكامل بعد التنفيذ — دليل الهدف + الذيل الأصلي */
    val destinationPath: String get() = "$targetDir/$fileName"
}

/** اقتراح تنظيم واحد: دليل مصدر + قاعدة + نقلاته + ثقة حتمية */
data class OrganizationSuggestion(
    val rule: OrgRule,
    /** الدليل الذي ستُنظَّم ملفاته المبعثرة */
    val parentDir: String,
    /** الدليل الهدف الكامل (داخل دليل المصدر) */
    val targetDir: String,
    /** النقلات المقترحة — معاينة كاملة قبل أي تنفيذ */
    val moves: List<SuggestedMove>,
    /** مفتاح سلسلة السبب في strings.xml */
    val reasonKey: String,
    /** وسائط السبب (أعداد وأحجام خام — ينسقها UI) */
    val reasonArgs: List<String>,
    /** ثقة حتمية ضمن [0.50, 0.95] تتصاعد مع حجم المجموعة */
    val confidence: Double,
    /** مفتاح التجاهل النهائي المستقر: ORG:RULE:parent:target */
    val key: String,
)

/** امتناع موثّق — لا اقتراح لهذا الدليل مع سبب صريح قابل للعرض */
data class OrgAbstention(
    val parentDir: String,
    /** org_abstain_conflict / org_abstain_marker / org_abstain_already */
    val code: String,
    /** تفصيل قابل للعرض (اسم ملف العلامة أو اسم الاصطدام أو اسم الدليل) */
    val detail: String,
)

/** ناتج المرحلة B: اقتراحات + امتناعات — كلاهما حتمي بالكامل */
data class OrganizationReport(
    val suggestions: List<OrganizationSuggestion>,
    val abstentions: List<OrgAbstention>,
)
