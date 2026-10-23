package com.novafiles.app.core.tags

/**
 * ══════════════════════════════════════════════════════════
 *  TagPalette — v0.33 «الوسوم»
 *  لوحة 10 ألوان مُدقَّقة التباين لوسوم الملفات.
 *  ─ كل لون يجتاز WCAG AA (نسبة ≥ 4.5) مع نص أبيض فوقه،
 *    مُتحقَّق منها في الاختبارات عبر ContrastAuditor.
 *  ─ colorFor: تخصيص لون حتمي من الاسم (FNV-1a) — نفس الاسم
 *    يعطي نفس اللون دائماً على كل الأجهزة.
 *  ─ خالص JVM بالكامل (بلا أندرويد).
 * ══════════════════════════════════════════════════════════
 */
object TagPalette {

    /** عدد ألوان اللوحة */
    const val COUNT = 10

    /** لون النص فوق كل بلاطات الوسوم */
    const val ON_COLOR = 0xFFFFFFFF.toInt()

    /** لون الوسم من الفهرس (0..9) */
    fun colorAt(index: Int): Int = COLORS[index.coerceIn(0, COUNT - 1)]

    /** كل ألوان اللوحة بترتيبها */
    val all: List<Int> get() = COLORS.toList()

    /**
     * لون حتمي من الاسم: هاش FNV-1a (32-بت) فوق UTF-8 ثم mod COUNT.
     * حتمي عبر الأجهزة والمنصات (بلا hash الافتراضي المتغير).
     */
    fun colorFor(name: String): Int {
        var hash = 0x811C9DC5L
        for (b in name.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xFF)
            hash = hash * 0x01000193L and 0xFFFFFFFFL
        }
        return (hash % COUNT).toInt()
    }

    /** أحمر */
    private const val C0 = 0xFFB3261E.toInt()
    /** برتقالي */
    private const val C1 = 0xFFA04000.toInt()
    /** عنبري */
    private const val C2 = 0xFF8A6D00.toInt()
    /** أخضر */
    private const val C3 = 0xFF1E7E34.toInt()
    /** تركوازي */
    private const val C4 = 0xFF00695C.toInt()
    /** سماوي */
    private const val C5 = 0xFF006978.toInt()
    /** أزرق */
    private const val C6 = 0xFF1565C0.toInt()
    /** نيلي */
    private const val C7 = 0xFF3949AB.toInt()
    /** بنفسجي */
    private const val C8 = 0xFF6A1B9A.toInt()
    /** وردي */
    private const val C9 = 0xFFAD1457.toInt()

    private val COLORS = listOf(C0, C1, C2, C3, C4, C5, C6, C7, C8, C9)
}
