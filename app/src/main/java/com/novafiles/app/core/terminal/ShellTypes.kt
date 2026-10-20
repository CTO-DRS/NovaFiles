package com.novafiles.app.core.terminal

/**
 * v0.28 — الطرفية المدمجة: أنواع البيانات المشتركة لشل NovaFiles.
 *
 * الكلاسات هنا خالصة تماماً (JVM-pure) — بلا أي اعتماد على أندرويد —
 * وفق قاعدة الحوكمة: محرك منطق خالص مُختبَر + طبقة Compose رقيقة فوقه.
 *
 * شل NovaFiles يعمل فوق طبقة الملفات مباشرة (لا يطلق أي عملية خارجية)،
 * لذا كل الأوامر قابلة للاختبار 100% على آلة جافا الافتراضية.
 */

/** ألوان دلالية لأسطر الناتج — تحوّلها الواجهة إلى لوحتها الداكنة */
enum class ShellColor {
    DEFAULT,
    BLUE,   // المجلدات
    GREEN,  // نجاح / أرشيفات
    RED,    // أخطاء
    YELLOW, // تحذيرات / ملاحظات
    DIM,    // ثانوي (تواريخ، فواصل)
    CYAN,   // روابط وأنواع مميزة
}

/** سطر واحد في ناتج الطرفية */
data class ShellLine(
    val text: String,
    val color: ShellColor = ShellColor.DEFAULT,
) {
    companion object {
        fun ok(text: String) = ShellLine(text, ShellColor.DEFAULT)
        fun err(text: String) = ShellLine(text, ShellColor.RED)
        fun dir(text: String) = ShellLine(text, ShellColor.BLUE)
        fun dim(text: String) = ShellLine(text, ShellColor.DIM)
        fun warn(text: String) = ShellLine(text, ShellColor.YELLOW)
        fun good(text: String) = ShellLine(text, ShellColor.GREEN)
    }
}

/** ناتج تنفيذ سطر أوامر كامل */
data class ShellOutcome(
    val lines: List<ShellLine>,
    /** طلب أمر clear مسح مخزن الشاشة */
    val clearRequested: Boolean = false,
    /** هل نجح آخر مقطع؟ (يوجّه && ولاحظة محث الطلب) */
    val ok: Boolean = true,
)

// ─────────────────────────── المحلل ───────────────────────────

/** رابط بين مقاطع السطر الواحد */
enum class Joiner {
    /** أول مقطع — لا رابط قبله */
    FIRST,

    /** «;» نفّذ دائماً */
    THEN,

    /** «&&» نفّذ فقط إذا نجح السابق */
    AND_THEN,
}

/** توجيه الإخراج: ‎> أو ‎>> */
data class ShellRedirect(
    val path: String,
    val append: Boolean,
)

/** مقطع أمر واحد: اسم + وسائط + توجيه */
data class ShellSegment(
    val tokens: List<String>,
    val redirect: ShellRedirect? = null,
    val joiner: Joiner = Joiner.FIRST,
)

/** سطر مُحلَّل كاملاً (قد يضم عدة مقاطع) */
data class ParsedLine(val segments: List<ShellSegment>) {
    val isEmpty: Boolean get() = segments.isEmpty()
}

/** نتيجة التحليل */
sealed interface ParseResult {
    data class Ok(val line: ParsedLine) : ParseResult
    data class Err(val message: String) : ParseResult

    /** تحليل مريح للاستخدام الداخلي — يرمي عند خطأ (المحرك يلتقطه ويعرضه) */
    fun orThrow(): ParsedLine = when (this) {
        is Ok -> line
        is Err -> throw ShellParseException(message)
    }
}

/** خطأ تحليل — يُعرض كسطر أحمر في الطرفية */
class ShellParseException(message: String) : Exception(message)
