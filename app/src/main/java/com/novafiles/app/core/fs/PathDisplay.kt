package com.novafiles.app.core.fs

/**
 * v0.18 — تنقيح الواجهة: عرض المسارات بأسماء ودّية بدل المعرفات التقنية.
 *
 * الكلاسات هنا خالصة تماماً (JVM-pure) — بلا أي اعتماد على أندرويد —
 * لتُختبر على آلة جافا الافتراضية ضمن جناح الاختبارات.
 *
 * الدافع: المتصفح كان يعرض عنوان المجلد كما هو، فيظهر «0» لعنوان
 * /storage/emulated/0 — وهي المجلد الجذري للذاكرة الداخلية على أندرويد.
 * [PathDisplay] يترجم الجذور المعروفة إلى أسماء مفهومة ويضغط المسار
 * الفرعي لعرضه كشارة قصيرة تحت العنوان.
 */

/** جذور التخزين المعروفة التي نمنحها اسماً ودّياً */
enum class StorageRoot(
    val prefix: String,
    val altPrefixes: List<String> = emptyList(),
) {
    /** الذاكرة الداخلية — الجذر الرئيسي لملفات المستخدم */
    INTERNAL(
        "/storage/emulated/0",
        listOf("/sdcard", "/storage/emulated/legacy"),
    ),

    /** جذر نظام الملفات كله */
    DEVICE("/"),
    ;

    /** هل يقع المسار (المطبع) تحت هذا الجذر؟ — يفترض استدعاء [PathDisplay.normalize] أولاً */
    fun contains(normalized: String): Boolean = when (this) {
        DEVICE -> normalized == prefix || normalized.startsWith(prefix)
        else -> normalized == prefix ||
            normalized.startsWith("$prefix/") ||
            altPrefixes.any { alt -> normalized == alt || normalized.startsWith("$alt/") }
    }

    companion object {
        /** أول جذر يضمّن المسار — DEVICE أخيراً لأنه يطابق أي مسار مطلق */
        fun of(path: String): StorageRoot? {
            val norm = PathDisplay.normalize(path)
            if (!norm.startsWith("/")) return null
            return entries.firstOrNull { it.contains(norm) }
        }
    }
}

/** أدوات عرض المسارات — دوال خالصة قابلة للاختبار */
object PathDisplay {

    /**
     * تطبيع المسار: طيّ الشرطات المكررة وإزالة الشرطة الختامية
     * (مع الإبقاء على «/» للجذر كما هي).
     */
    fun normalize(path: String): String {
        if (path.isEmpty()) return "/"
        val collapsed = path.replace(Regex("/+"), "/")
        return if (collapsed.length > 1 && collapsed.endsWith("/")) {
            collapsed.trimEnd('/')
        } else {
            collapsed
        }
    }

    /**
     * الجزء النسبي من المسار بعد استبعاد الجذر — يُعرض كشارة تحت العنوان.
     * يُرجع null عند الجذر نفسه (لا يوجد ما يُعرض).
     * مثال: ‎/storage/emulated/0/DCIM/Camera → "DCIM/Camera"
     */
    fun compactPath(path: String): String? {
        val norm = normalize(path)
        val root = StorageRoot.of(norm) ?: return stripFirstSlash(norm)
        return when (root) {
            StorageRoot.INTERNAL -> {
                val rel = stripKnownPrefix(norm, root.prefix) ?: return null
                // معالجة البدائل: ‎/sdcard/Download → Download
                rel.ifEmpty { null }
            }
            StorageRoot.DEVICE -> stripFirstSlash(norm).ifEmpty { null }
        }
    }

    /** مقاطع المسار النسبي — مفيدة للاختبار ولعرض مسار تفاعلي مستقبلاً */
    fun relativeSegments(path: String): List<String> {
        val compact = compactPath(path) ?: return emptyList()
        return compact.split('/').filter { it.isNotEmpty() }
    }

    /** عدد المقاطع النسبية — 0 يعني أن المسار جذر */
    fun depth(path: String): Int = relativeSegments(path).size

    private fun stripKnownPrefix(norm: String, prefix: String): String? = when {
        norm == prefix -> ""
        norm.startsWith("$prefix/") -> norm.removePrefix("$prefix/")
        else -> {
            // البدائل مثل /sdcard — نبحث عن أول بديل يطابق
            StorageRoot.INTERNAL.altPrefixes.firstOrNull { alt ->
                norm == alt || norm.startsWith("$alt/")
            }?.let { alt -> if (norm == alt) "" else norm.removePrefix("$alt/") }
                ?: stripFirstSlash(norm)
        }
    }

    private fun stripFirstSlash(norm: String): String =
        if (norm == "/") "" else norm.removePrefix("/")
}
