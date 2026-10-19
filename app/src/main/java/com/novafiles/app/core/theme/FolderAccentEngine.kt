package com.novafiles.app.core.theme

/**
 * v0.17 — لون مميز لكل مجلد (per-folder accent).
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * قاعدة الوراثة: كل مجلد يرث لون أقرب سلف له لهوية لونية.
 * إن لم يكن لأي سلف لون، يعود التطبيق للإعداد العام.
 *
 * التخزين: مسارات مطلقة → معرّف إعداد لوني. التنسيق المتسلسل
 * سطر لكل إدخال: "path=accentId" — متسامح مع الأسطر التالفة.
 */
class FolderAccentEngine(initial: Map<String, String> = emptyMap()) {

    private val map = initial
        .filterKeys { it.startsWith("/") }
        .filterValues { PaletteEngine.isKnown(it) }
        .toMutableMap()

    /** عدد الإدخالات النشطة */
    val size: Int get() = map.size

    /** لون أقرب سلف — يبدأ من المسار نفسه ثم يصعد حتى الجذر */
    fun resolve(path: String): String? {
        var cur = normalize(path)
        while (true) {
            map[cur]?.let { return it }
            if (cur == "/") return null
            cur = parentOf(cur)
        }
    }

    /** ضبط لون مجلد — يتجاهل المعرّفات غير المعروفة */
    fun set(path: String, accentId: String) {
        if (!PaletteEngine.isKnown(accentId)) return
        map[normalize(path)] = accentId
    }

    /** إزالة هوية مجلد (يرث من السلف بعد ذلك) */
    fun unset(path: String) {
        map.remove(normalize(path))
    }

    /** كل الإدخالات — لإدارتها من الإعدادات */
    fun entries(): Map<String, String> = map.toMap()

    /** تسلسل للحفظ في DataStore (سطر لكل إدخال) */
    fun serialize(): String =
        map.entries.joinToString("\n") { "${it.key}=${it.value}" }

    companion object {
        /** تحليل من الحفظ — يتجاهل الأسطر التالفة والمعرّفات المجهولة */
        fun deserialize(raw: String): FolderAccentEngine {
            val map = mutableMapOf<String, String>()
            raw.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val idx = trimmed.lastIndexOf('=')
                if (idx <= 0) return@forEach
                val path = normalize(trimmed.substring(0, idx).trim())
                val id = trimmed.substring(idx + 1).trim()
                if (path.startsWith("/") && path != "/" && PaletteEngine.isKnown(id)) map[path] = id
            }
            return FolderAccentEngine(map)
        }

        /** توحيد المسار: إزالة الشرطات المزدورة والشرطة الأخيرة (عدا الجذر) */
        fun normalize(path: String): String {
            var p = path.trim()
            if (p.isEmpty()) return "/"
            // طيّ الشرطات المتكررة
            while (p.contains("//")) p = p.replace("//", "/")
            if (p.length > 1 && p.endsWith("/")) p = p.trimEnd('/')
            if (p.isEmpty()) p = "/"
            return p
        }

        /** المسار الأب — "/" أب نفسه */
        fun parentOf(path: String): String {
            val p = normalize(path)
            if (p == "/") return "/"
            val idx = p.lastIndexOf('/')
            return if (idx <= 0) "/" else p.substring(0, idx)
        }
    }
}
