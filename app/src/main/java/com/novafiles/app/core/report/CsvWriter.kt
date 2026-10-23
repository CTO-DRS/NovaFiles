package com.novafiles.app.core.report

/**
 * v0.36.0 — كاتب CSV خالص وفق RFC 4180: تهريب كامل للحقل المعقّد.
 *
 * القاعدة: حقل يحوي فاصلة أو اقتباساً أو سطراً جديداً أو CR → يُغلَّف باقتباسات
 * ويُضاعف كل اقتباس داخلي. الأسطر تنتهي CRLF (المعيار، وExcel يحترمه) — لكن
 * التهريب يجعل أي نهاية سطر مقبولة لدى القارئ.
 *
 * BOM: افتراضياً تُسبق الملفات بـ UTF-8 BOM لأن Excel يحتاجه لفهم UTF-8 —
 * وإلا تظهر الأسماء العربية مشوّهة. الاختبارات تتحقق من الحالتين.
 */
object CsvWriter {

    const val BOM = "\uFEFF"

    /** تهريب حقل واحد فقط — مكشوف للاختبار المباشر */
    fun escapeField(field: String): String {
        val needsQuoting = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuoting) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }

    /**
     * تجميع صفوف كاملة: الصف الأول رؤوس عادةً (مناديها يقرر).
     * bom = true يضيف BOM في البداية — للتوافق مع Excel العربي.
     */
    fun write(rows: List<List<String>>, bom: Boolean = true): String {
        val sb = StringBuilder()
        if (bom) sb.append(BOM)
        for ((i, row) in rows.withIndex()) {
            if (i > 0) sb.append("\r\n")
            row.forEachIndexed { j, field ->
                if (j > 0) sb.append(',')
                sb.append(escapeField(field))
            }
        }
        return sb.toString()
    }
}
