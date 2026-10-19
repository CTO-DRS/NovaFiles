package com.novafiles.app.core.assoc

/**
 * v0.17 — حلال «فتح باستخدام»: ترتيب المعالجات وتذكّر الافتراضي.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * قواعد الترتيب (مطابقة روح أندرويد):
 *  1. مطابقة النوع الكامل (مثل text/markdown) أعلى من wildcard
 *  2. ثم type/ANY (مثل image/ANY)
 *  3. ثم ANY/ANY (يقبل كل شيء)
 *  التعادل: أبجدياً بالتسمية ثم بالحزمة — حتمية كاملة للاختبار.
 *
 * الافتراضيات تُحفظ لكل «مجموعة» (type/ANY) لا لكل MIME — كما يفعل النظام،
 * لذا تعيين Word افتراضياً لـ docx يشمل doc.
 */
class OpenWithResolver {

    /** معالج مرشح — يأتي من PackageManager في UI */
    data class Handler(
        val packageName: String,
        val label: String,
        /** أنماط يدعمها: أنواع كاملة أو wildcards (مثل image/ANY أو ANY/ANY) */
        val mimePatterns: Set<String>,
    )

    private val defaults = mutableMapOf<String, String>()

    /** الافتراضي الحالي لمجموعة MIME (مثل image/ANY → com.app.viewer) */
    fun defaultFor(mime: String): String? = defaults[groupOf(mime)]

    /** تعيين افتراضي — يُطبق على المجموعة كاملة */
    fun setDefault(mime: String, packageName: String) {
        defaults[groupOf(mime)] = packageName
    }

    /** مسح الافتراضي لمجموعة MIME */
    fun clearDefault(mime: String): Boolean = defaults.remove(groupOf(mime)) != null

    /** كل الافتراضيات المحفوظة — للعرض في الإعدادات */
    fun allDefaults(): Map<String, String> = defaults.toMap()

    /**
     * قائمة المعالجات مرتبة: الافتراضي أولاً ثم بترتيب جودة المطابقة.
     * المعالجات غير المطابقة تُستبعد تماماً.
     */
    fun ranked(mime: String, handlers: List<Handler>): List<Handler> {
        val matching = handlers.filter { h -> h.mimePatterns.any { matches(it, mime) } }
        val def = defaultFor(mime)
        val sorted = matching.sortedWith(
            compareByDescending<Handler> { h -> bestMatchScore(h, mime) }
                .thenBy { it.label.lowercase() }
                .thenBy { it.packageName }
        )
        if (def == null) return sorted
        val chosen = sorted.firstOrNull { it.packageName == def } ?: return sorted
        return listOf(chosen) + sorted.filter { it != chosen }
    }

    /** هل يوجد افتراضي صالح في القائمة؟ (لتخطي حوار الاختيار) */
    fun hasUsableDefault(mime: String, handlers: List<Handler>): Boolean {
        val def = defaultFor(mime) ?: return false
        return handlers.any { it.packageName == def && it.mimePatterns.any { p -> matches(p, mime) } }
    }

    /** مجموعة MIME: type/ANY أو نوع كامل للأنواع الخاصة */
    fun groupOf(mime: String): String {
        val t = mime.substringBefore('/')
        return when {
            mime.endsWith("/*") || mime == "*/*" -> mime
            // أنواع تغطي عائلة كاملة كنظام
            t in setOf("text", "image", "audio", "video", "font") -> "$t/*"
            else -> mime // application/pdf تُعامل كنوع كامل
        }
    }

    /** مطابقة نمط MIME مع قيمة */
    fun matches(pattern: String, mime: String): Boolean = when {
        pattern == "*/*" -> true
        pattern.endsWith("/*") -> mime.substringBefore('/') == pattern.substringBefore('/')
        else -> pattern.equals(mime, ignoreCase = true)
    }

    /** أفضل درجة مطابقة: 2 كامل، 1 wildcard نوعي، 0 للقبول الكامل فقط، -1 لا مطابقة */
    fun bestMatchScore(handler: Handler, mime: String): Int = when {
        handler.mimePatterns.any { it.equals(mime, ignoreCase = true) } -> 2
        handler.mimePatterns.any { it.endsWith("/*") && it != "*/*" && matches(it, mime) } -> 1
        handler.mimePatterns.any { it == "*/*" } -> 0
        else -> -1
    }

    // التسلسل: image/STAR=com.a;text/markdown=com.b
    fun serialize(): String =
        defaults.entries.joinToString(";") { "${it.key}=${it.value}" }

    fun load(raw: String?) {
        raw?.split(';')?.forEach { part ->
            val i = part.indexOf('=')
            if (i > 0) {
                val group = part.substring(0, i).trim()
                val pkg = part.substring(i + 1).trim()
                if (group.contains('/') && pkg.isNotEmpty()) defaults[group] = pkg
            }
        }
    }
}
