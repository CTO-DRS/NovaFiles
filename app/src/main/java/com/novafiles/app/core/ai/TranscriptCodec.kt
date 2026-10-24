package com.novafiles.app.core.ai

/**
 * v0.41 — مرمّز سجل المحادثة المحلي الاختياري.
 *
 * سياسة الخصوصية:
 *  - السجل معطّل افتراضياً — لا يُكتب أي حرف من المحادثة إلى القرص إلا
 *    بعد أن يفعّله المستخدم صراحةً من إعدادات المساعد.
 *  - الحد الأقصى 50 رسالة — الأقدم يُحذف أولاً (FIFO).
 *  - ترميز CSV متسامح مُحصّن (نفس فلسفة مرمّزات v0.35): سطر تالف → يُتخطى
 *    ولا يُبطل السجل كله، وأسماء الملفات الحقنة لا تفسّر بنية السجل.
 *
 * الصيغة: ts ميلّي,role,text (نص مع تهريب الاقتباس واستبدال الأسطر الجديدة بـ \n حرفي)
 */
object TranscriptCodec {

    const val MAX_ENTRIES = 50

    /** تهريب حقل واحد: اقتباس مضاعف، أسطر جديدة/رجوع محرفية */
    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace(",", "\\,")

    /** فك تهريب حقل واحد */
    private fun unescape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    ',' -> { sb.append(','); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c); i += 1
            }
        }
        return sb.toString()
    }

    /** ترميز رسائل إلى أسطر متعددة (سطر لكل رسالة، بترتيب زمني تصاعدي) */
    fun encode(messages: List<ChatMessage>): String {
        val kept = messages.takeLast(MAX_ENTRIES)
        return kept.joinToString("\n") { m ->
            val role = when (m.role) {
                ChatRole.USER -> "u"
                ChatRole.ASSISTANT -> "a"
            }
            "${m.tsMillis},$role,${escape(m.text)}"
        }
    }

    /**
     * فك متسامح: الأسطر التالفة تُتخطى بصمت (تحمل قدرة تعافٍ مثل بقية المرمّزات) —
     * الرسائل غير المعروفة الدور تُتخطى، والطوابع غير العددية تُتخطى.
     */
    fun decode(raw: String): List<ChatMessage> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<ChatMessage>(raw.length.coerceAtMost(64))
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            // أول فاصلتين فقط هي الفواصل البنيوية — النص يُفك تهريبه ككتلة واحدة
            val first = line.indexOf(',')
            val second = line.indexOf(',', first + 1)
            if (first <= 0 || second < 0) continue
            val ts = line.substring(0, first).toLongOrNull() ?: continue
            val role = when (line.substring(first + 1, second)) {
                "u" -> ChatRole.USER
                "a" -> ChatRole.ASSISTANT
                else -> continue
            }
            val text = unescape(line.substring(second + 1))
            if (text.isEmpty()) continue
            out += ChatMessage(role, text, ts)
        }
        return out.takeLast(MAX_ENTRIES)
    }
}
