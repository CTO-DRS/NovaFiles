package com.novafiles.app.core.theme

/**
 * v0.17 — حِزم الأيقونات (icon packs).
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * الحزمة نص بسيط (قابل للمشاركة كملف .novapack):
 *   # تعليق
 *   [icons]
 *   pdf=badge_pdf
 *   docx=badge_word
 *   *=badge_default
 *
 * القواعد:
 *  - الامتداد بأحرف صغيرة، أول إدخال للمفتاح نفسه يفوز.
 *  - "*" هو الافتراضي للحالات غير المطابقة.
 *  - مفتاح الأيقونة: أحرف صغيرة/أرقام/شرطة سفلية، حتى 24 حرفاً.
 *  - حد أقصى 512 إدخالاً لمنع استهلاك الذاكرة بملف فاسد.
 */
class IconPackEngine(manifest: String) {

    data class ParseResult(
        val entries: Map<String, String>,
        val defaultIcon: String?,
        val skippedLines: Int,
    )

    val pack: ParseResult

    init {
        pack = parse(manifest)
    }

    /** هل الحزمة صالحة للاستخدام (عليها إدخال واحد على الأقل)؟ */
    val isValid: Boolean get() = pack.entries.isNotEmpty() || pack.defaultIcon != null

    /**
     * حل أيقونة اسم ملف:
     * 1) مطابقة الامتداد في الحزمة
     * 2) الافتراضي "*" في الحزمة
     * 3) fallback دالة الأيقونة المدمجة (TypeIcons في UI)
     */
    fun resolve(fileName: String, builtin: (String) -> String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        pack.entries[ext]?.let { return it }
        pack.defaultIcon?.let { return it }
        return builtin(ext)
    }

    companion object {
        const val MAX_ENTRIES = 512
        private val KEY_RE = Regex("^[a-z0-9_]{1,24}$")

        fun parse(manifest: String): ParseResult {
            val entries = LinkedHashMap<String, String>()
            var defaultIcon: String? = null
            var skipped = 0
            var inIconsSection = true // نسمح بالملفات غير المقسّمة — الكل يُعامل كقسم icons
            manifest.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach
                if (line.startsWith("[")) {
                    // قسم — نعترف فقط بـ [icons] ونحيد عن غيره
                    inIconsSection = line.equals("[icons]", ignoreCase = true)
                    if (!inIconsSection) skipped++
                    return@forEach
                }
                if (!inIconsSection) { skipped++; return@forEach }
                val idx = line.indexOf('=')
                if (idx <= 0) { skipped++; return@forEach }
                val key = line.substring(0, idx).trim().lowercase()
                val icon = line.substring(idx + 1).trim()
                if (icon.isEmpty() || !KEY_RE.matches(icon)) { skipped++; return@forEach }
                if (entries.size >= MAX_ENTRIES) { skipped++; return@forEach }
                if (key == "*") {
                    if (defaultIcon == null) defaultIcon = icon
                } else {
                    if (!entries.containsKey(key)) entries[key] = icon
                }
            }
            return ParseResult(entries, defaultIcon, skipped)
        }
    }
}
