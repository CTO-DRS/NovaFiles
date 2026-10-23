package com.novafiles.app.core.l10n

/**
 * v0.37 — جسر الإطلاق: كتالوج اللغات العشر.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * العشر لغات المستهدفة عند الإطلاق v1.0 (خارطة v2 — بند v0.37):
 * العربية (الأساس RTL) + الإنجليزية + الفرنسية + الإسبانية + الألمانية
 * + التركية + الإندونيسية + الأردية + الفارسية + الروسية.
 *
 * كل لغة تحمل وسم BCP-47 أساسياً واسمها الأصلي واتجاهها ونظام كتابتها —
 * والكتالوج هو المصدر الوحيد للحقيقة: قائمة الإعدادات وlocales_config
 * واختبارات التكافؤ تُبنى كلها فوقه.
 */
data class NovaLocale(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val rtl: Boolean,
    val script: Script,
) {
    enum class Script { ARABIC, LATIN, CYRILLIC }
}

object LanguageCatalog {

    val ARABIC = NovaLocale("ar", "العربية", "Arabic", rtl = true, script = NovaLocale.Script.ARABIC)
    val ENGLISH = NovaLocale("en", "English", "English", rtl = false, script = NovaLocale.Script.LATIN)
    val FRENCH = NovaLocale("fr", "Français", "French", rtl = false, script = NovaLocale.Script.LATIN)
    val SPANISH = NovaLocale("es", "Español", "Spanish", rtl = false, script = NovaLocale.Script.LATIN)
    val GERMAN = NovaLocale("de", "Deutsch", "German", rtl = false, script = NovaLocale.Script.LATIN)
    val TURKISH = NovaLocale("tr", "Türkçe", "Turkish", rtl = false, script = NovaLocale.Script.LATIN)
    val INDONESIAN = NovaLocale("id", "Bahasa Indonesia", "Indonesian", rtl = false, script = NovaLocale.Script.LATIN)
    val URDU = NovaLocale("ur", "اردو", "Urdu", rtl = true, script = NovaLocale.Script.ARABIC)
    val PERSIAN = NovaLocale("fa", "فارسی", "Persian", rtl = true, script = NovaLocale.Script.ARABIC)
    val RUSSIAN = NovaLocale("ru", "Русский", "Russian", rtl = false, script = NovaLocale.Script.CYRILLIC)

    /** العشر لغات بترتيب ثابت: الأساس أولاً (ar) ثم الإنجليزية ثم البقية بالوسم */
    val ALL: List<NovaLocale> = listOf(
        ARABIC, ENGLISH, FRENCH, SPANISH, GERMAN, TURKISH, INDONESIAN, URDU, PERSIAN, RUSSIAN,
    )

    /** اللغة الأساس للتطبيق — العربية RTL أولاً كما في كامل تاريخ NovaFiles */
    const val BASE_CODE = "ar"

    /** اللغة الثانية الدائمة — الإنجليزية (عدّاد ar/en التكافؤ التاريخي) */
    const val SECONDARY_CODE = "en"

    private val byCode: Map<String, NovaLocale> = ALL.associateBy { it.code }

    /** حلّ وسم لغة (قد يحمل منطقة مثل «ar-EG») إلى لغة الكتالوج */
    fun byCode(tag: String?): NovaLocale? =
        tag?.trim()?.substringBefore('-')?.lowercase()?.let { byCode[it] }

    /** هل الوسم مدعوم؟ */
    fun isSupported(tag: String?): Boolean = byCode(tag) != null

    /** لغات RTL بالضبط */
    fun rtlCodes(): Set<String> = ALL.filter { it.rtl }.map { it.code }.toSet()

    /** أوسمة العشر لغات — يُبنى منها locales_config.xml واختبارات التكافؤ */
    fun codes(): Set<String> = byCode.keys
}
