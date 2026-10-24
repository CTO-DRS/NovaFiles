package com.novafiles.app.core.ai

/**
 * v0.41 — تطبيع النص العربي للمطابقة الحتمية (نفس منهجية UserGuideEngine v0.40):
 *  - إزالة التشكيل والتطويل
 *  - توحيد الألف: أ/إ/آ/ٱ → ا
 *  - التاء المربوطة ة → ه
 *  - الألف المقصورة ى → ي
 *  - الهمزة المستقلة ؤ → و و ئ → ي
 *  - تصغير الأحرف اللاتينية وضغط المسافات
 *
 * هذا تطبيع حرفي حتمي للكلمات المفتاحية — لا يُدّعى أنه نموذج لغوي.
 */
object ArabicText {

    private val diacritics = Regex("[\\u064B-\\u065F\\u0670\\u0640]") // حركات + تطويل

    /** التطبيع الكامل للمطابقة */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val c = when (ch) {
                // توحيد صور الألف — بأحرف محددة لا بنطاق (النطاق يبتلع بقية الحروف!)
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ة' -> 'ه'
                'ى' -> 'ي'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                else -> ch
            }
            sb.append(c)
        }
        return diacritics.replace(sb.toString(), "")
            .lowercase()
            .trim()
            .replace(Regex("\\s+"), " ")
            .map { if (it in '٠'..'٩') ('٠'..'٩').indexOf(it).digitToChar() else it }
            .joinToString("")
    }

    /** هل يحتوي النص المطبَّع الكلمة المفتاحية المطبَّعة ككلمة مستقلة؟ */
    fun containsWord(normalizedText: String, keyword: String): Boolean {
        val k = normalize(keyword)
        if (k.isEmpty()) return false
        return normalizedText.split(' ').any { it == k }
    }

    /** هل يبدأ النص المطبَّع بأي من الكلمات؟ */
    fun startsWithAny(normalizedText: String, keywords: List<String>): Boolean =
        keywords.any { normalizedText.startsWith(normalize(it)) }

    /** هل يحتوي النص المطبَّع أي من العبارات (كمطابقة جزئية)؟ */
    fun containsAny(normalizedText: String, phrases: List<String>): Boolean =
        phrases.any { normalizedText.contains(normalize(it)) }

    /** استخراج أول رقم يظهر في النص (يدعم الأرقام العربية الشرقية ٠-٩) */
    fun firstNumber(raw: String): Int? {
        val western = StringBuilder(raw.length)
        for (ch in raw) {
            western.append(
                when (ch) {
                    in '٠'..'٩' -> ('٠'..'٩').indexOf(ch).toString()[0]
                    else -> ch
                },
            )
        }
        val m = Regex("(\\d+)").find(western.toString()) ?: return null
        return m.groupValues[1].toIntOrNull()
    }
}
