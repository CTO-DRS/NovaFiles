package com.novafiles.app.core.smart

/**
 * v0.34.0 — تسلسل أحجام الأدلة العليا في عمود Room واحد
 *
 * النسق: سطر لكل دليل بصيغة «size\tname» (الترتيب تنازلي بالحجم للاستقرار).
 * TAB ممنوع في أسماء ملفات أندرويد عملياً — والتحليل متسامح: الأسطر التالفة تُتخطى.
 */
object PerDirBlob {

    const val SEPARATOR = '\t'

    fun encode(perDir: Map<String, Long>): String =
        perDir.entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .joinToString("\n") { (name, size) -> "$size$SEPARATOR$name" }

    fun decode(blob: String): Map<String, Long> {
        if (blob.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, Long>()
        for (line in blob.split('\n')) {
            val idx = line.indexOf(SEPARATOR)
            if (idx <= 0) continue // بلا فاصل أو حجم فارغ — سطر تالف
            val size = line.substring(0, idx).toLongOrNull() ?: continue
            val name = line.substring(idx + 1)
            out[name] = size // الاسم الفارغ مسموح (ملفات الجذر مباشرة)؛ التكرار: الأخير يفوز
        }
        return out
    }
}
