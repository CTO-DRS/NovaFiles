package com.novafiles.app.core.l10n

/**
 * v0.37 — جسر الإطلاق: محرك تكافؤ السلاسل بين ملفات strings.xml للغات العشر.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM. يأخذ محتوى XML نصياً فقط (لا موارد
 * أندرويد ولا سياق) فيعمل في اختبارات الوحدة مباشرة على ملفات المستودع الحقيقية.
 *
 * ما يضمنه:
 *  1. نفس مفاتيح السلاسل بالضبط في كل لغة (لا مفتاح ناقص ولا زائد)
 *  2. سلامة العناصر النائبة: %1$s/%2$d… موجودة في كل ترجمة إن وُجدت في الأساس
 *  3. لا قيم فارغة ولا أسماء مكررة داخل الملف الواحد
 *  4. لا خلط بين العناصر النائبة الموضعية وغير الموضعية داخل السلسلة الواحدة
 */
data class StringEntry(val name: String, val value: String)

data class ParsedStrings(
    val entries: List<StringEntry>,
    val duplicateNames: List<String>,
) {
    val byName: Map<String, StringEntry> = entries.associateBy { it.name }
    val names: Set<String> get() = byName.keys
}

sealed class ParityIssue {
    abstract val locale: String
    abstract val key: String

    data class MissingKey(override val locale: String, override val key: String) : ParityIssue()
    data class ExtraKey(override val locale: String, override val key: String) : ParityIssue()
    data class PlaceholderMismatch(
        override val locale: String,
        override val key: String,
        val base: List<String>,
        val actual: List<String>,
    ) : ParityIssue()
    data class EmptyValue(override val locale: String, override val key: String) : ParityIssue()
    data class DuplicateName(override val locale: String, override val key: String) : ParityIssue()
    data class MixedPositional(override val locale: String, override val key: String) : ParityIssue()
}

object StringsParity {

    /** سطر/كتلة سلسلة واحدة: <string name="key">القيمة</string> */
    private val STRING_RE =
        Regex("""<string\s+name="([^"]+)"\s*>(.*?)</string>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))

    private val COMMENT_RE = Regex("""<!--.*?-->""", setOf(RegexOption.DOT_MATCHES_ALL))

    /** عنصر نائب موضعي (%1$s) أو عادي (%s) — s/d فقط كما يستخدمها المشروع */
    private val PLACEHOLDER_RE = Regex("""%(?:\d+\$)?[sdf]""")

    /** عنصر نائب موضعي صريح */
    private val POSITIONAL_RE = Regex("""%\d+\$[sdf]""")

    /** تحليل محتوى strings.xml إلى مدخلات + قائمة الأسماء المكررة */
    fun parse(xmlContent: String): ParsedStrings {
        val cleaned = xmlContent.replace(COMMENT_RE, " ")
        val entries = LinkedHashMap<String, StringEntry>()
        val duplicates = mutableListOf<String>()
        for (m in STRING_RE.findAll(cleaned)) {
            val name = m.groupValues[1].trim()
            val value = m.groupValues[2].trim()
            if (entries.containsKey(name)) {
                duplicates += name
            } else {
                entries[name] = StringEntry(name, value)
            }
        }
        return ParsedStrings(entries.values.toList(), duplicates)
    }

    /**
     * استخراج العناصر النائبة بترتيب الظهور — مع تجاهل `%%` (نسبة حرفية)
     * و`\%` (نسبة مهرّبة) كما تحددها قواعد موارد أندرويد.
     */
    fun extractPlaceholders(value: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < value.length) {
            if (value[i] != '%') { i++; continue }
            // %% حرفية — تخطّها
            if (i + 1 < value.length && value[i + 1] == '%') { i += 2; continue }
            // \% مهرّبة — تخطّ العلامة
            if (i > 0 && value[i - 1] == '\\') { i++; continue }
            val m = PLACEHOLDER_RE.find(value, i)
            if (m != null && m.range.first == i) {
                out += m.value; i += m.value.length
            } else i++
        }
        return out
    }

    /** هل السلسلة تخلط موضعياً وغير موضعي؟ خطأ تنسيق لا يُقبل */
    fun hasMixedPositional(value: String): Boolean {
        val ps = extractPlaceholders(value)
        if (ps.size < 2) return false
        val positional = ps.count { POSITIONAL_RE.matches(it) }
        return positional != 0 && positional != ps.size
    }

    /**
     * تدقيق التكافؤ الكامل: الأساس (ar افتراضياً) يحدد المفاتيح والعناصر
     * النائبة، وكل لغة أخرى يجب أن تطابقه مفتاحاً-مفتاحاً.
     */
    fun audit(
        locales: Map<String, ParsedStrings>,
        baseLocale: String = LanguageCatalog.BASE_CODE,
    ): List<ParityIssue> {
        val issues = mutableListOf<ParityIssue>()
        val base = locales[baseLocale]
            ?: throw IllegalArgumentException("اللغة الأساس $baseLocale غير موجودة في المدخلات")

        // مشاكل الأساس نفسه: تكرار/فارغ/خلط موضعي
        for (dup in base.duplicateNames) issues += ParityIssue.DuplicateName(baseLocale, dup)
        for (e in base.entries) {
            if (e.value.isBlank()) issues += ParityIssue.EmptyValue(baseLocale, e.name)
            if (hasMixedPositional(e.value)) issues += ParityIssue.MixedPositional(baseLocale, e.name)
        }

        for ((locale, parsed) in locales) {
            if (locale == baseLocale) continue
            for (dup in parsed.duplicateNames) issues += ParityIssue.DuplicateName(locale, dup)

            for (e in parsed.entries) {
                if (e.value.isBlank()) issues += ParityIssue.EmptyValue(locale, e.name)
                if (hasMixedPositional(e.value)) issues += ParityIssue.MixedPositional(locale, e.name)
            }

            // مفاتيح ناقصة وزائدة نسبةً للأساس
            for (key in base.names - parsed.names) issues += ParityIssue.MissingKey(locale, key)
            for (key in parsed.names - base.names) issues += ParityIssue.ExtraKey(locale, key)

            // العناصر النائبة يجب أن تطابق الأساس (نفس المجموعة) للمفاتيح المشتركة
            for (key in base.names intersect parsed.names) {
                val basePh = extractPlaceholders(base.byName.getValue(key).value)
                val actualPh = extractPlaceholders(parsed.byName.getValue(key).value)
                if (basePh.sorted() != actualPh.sorted()) {
                    issues += ParityIssue.PlaceholderMismatch(locale, key, basePh, actualPh)
                }
            }
        }
        return issues
    }

    /** تقرير مختصر: (عدد اللغات، عدد المفاتيح، المشاكل) — لسطر ملخص CI */
    fun summary(locales: Map<String, ParsedStrings>, issues: List<ParityIssue>): String =
        "locales=%d keys=%d issues=%d".format(locales.size, locales.values.firstOrNull()?.names?.size ?: 0, issues.size)
}
