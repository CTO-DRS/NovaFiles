package com.novafiles.app.core.ai

/**
 * v0.41 — محلل النوايا المحلي الحتمي (IntentParser).
 *
 * قواعد صريحة بالعربية والإنجليزية فوق تطبيع حرفي — بلا شبكة عصبية
 * ولا نموذج لغوي ولا اتصال: يُدّعى فقط ما يُختبر.
 *
 * المنهج:
 *  1) تطبيع المدخل (ArabicText)
 *  2) مطابقة عائلات النوايا بترتيب أولوية ثابت (الأخطاء ← المكررات ← التحليل…)
 *  3) استخراج معاملات محددة النوع (نوع الملف، الحداثة، الحجم، المجلد الهدف)
 *  4) غير المفهوم → unknown مع اقتراحات — لا تخمين صامت
 */
object IntentParser {

    /** معرفات النوايا الثابتة */
    object Ids {
        const val FIND_FILES = "find_files"
        const val STORAGE_REPORT = "storage_report"
        const val FIND_DUPLICATES = "find_duplicates"
        const val ORGANIZE_SUGGEST = "organize_suggest"
        const val CREATE_RULE = "create_rule"
        const val EXPLAIN_ERROR = "explain_error"
        const val HELP = "help"
        const val UNKNOWN = "unknown"
    }

    /** مفاتيح المعاملات القياسية */
    object Keys {
        const val TEXT = "text"             // نص بحث بالاسم (فارغ = الكل)
        const val FILE_KIND = "file_kind"   // AiFileKind.name
        const val MIN_SIZE_MB = "min_size_mb"
        const val WITHIN_DAYS = "within_days"   // عدلت خلال آخر N يوم
        const val OLDER_THAN_DAYS = "older_than_days" // لم تُفتح/لم تتغير منذ N يوم
        const val TARGET_DIR = "target_dir"     // مجلد هدف قاعدة النقل
        const val QUERY = "query"               // نص الخطأ الخام للشرح
    }

    /** نتيجة التحليل */
    data class Parsed(
        val intentId: String,
        val params: Map<String, String>,
    )

    // ═══════════ جداول الكلمات المفتاحية (عربي + إنجليزي) ═══════════

    private val WHY = listOf("لماذا", "why")
    private val FAILED = listOf("فشل", "فشلت", "اخفق", "خطا", "مشكله", "خطا اتصال", "failed", "fail", "error", "broken", "مشكلة")
    private val DUPLICATES = listOf("متطابق", "مكرر", "متكرر", "نسخ متطابقه", "duplicate", "duplicates", "identical", "dupes")
    private val STORAGE = listOf("حلل", "تحليل", "استهلاك", "المساحه", "تخزين", "المخزن", "analyze", "storage", "disk usage", "space usage", "disk")
    private val RULE = listOf("قاعده", "قواعد", "rule", "automation")
    private val ORGANIZE = listOf("نظم", "تنظيم", "رتب", "ترتيب", "organize", "tidy", "sort my")
    private val FIND = listOf("اعثر", "ابحث", "اعرض", "وجد", "دوّر", "find", "search", "show", "list", "locate")
    private val HELP = listOf("مساعده", "ساعدني", "ايش تقدر", "وش تقدر", "help", "what can you", "متى تستخدم")

    /** أنواع الملفات: كلمة مفتاحية (تطَّبعت) → نوع */
    private val FILE_KINDS: List<Pair<String, String>> = listOf(
        "pdf" to "PDF",
        "صوره" to "IMAGE", "صور" to "IMAGE", "image" to "IMAGE", "images" to "IMAGE",
        "photo" to "IMAGE", "photos" to "IMAGE", "jpg" to "IMAGE", "png" to "IMAGE",
        "فيديو" to "VIDEO", "videos" to "VIDEO", "video" to "VIDEO", "mp4" to "VIDEO",
        "صوت" to "AUDIO", "اغاني" to "AUDIO", "موسيقى" to "AUDIO", "audio" to "AUDIO",
        "music" to "AUDIO", "mp3" to "AUDIO",
        "ارشيف" to "ARCHIVE", "مضغوطه" to "ARCHIVE", "مضغوط" to "ARCHIVE",
        "archive" to "ARCHIVE", "zip" to "ARCHIVE", "rar" to "ARCHIVE", "7z" to "ARCHIVE",
        "مستندات" to "DOC", "مستند" to "DOC", "documents" to "DOC", "document" to "DOC",
        "docx" to "DOC", "word" to "DOC", "excel" to "DOC",
        "تطبيقات" to "APK", "تطبيق" to "APK", "apk" to "APK",
        "كود" to "CODE", "source code" to "CODE", "code" to "CODE",
        "نصوص" to "TEXT", "نص" to "TEXT", "text" to "TEXT", "txt" to "TEXT",
    )

    /** مجلدات الهدف لقواعد النقل: كلمة → مجلد عام قياسي */
    private val TARGET_DIRS: List<Pair<String, String>> = listOf(
        "المستندات" to "Documents", "documents" to "Documents",
        "التنزيلات" to "Download", "التحميلات" to "Download", "downloads" to "Download",
        "الصور" to "Pictures", "pictures" to "Pictures", "photos" to "Pictures",
        "الافلام" to "Movies", "الفيديوهات" to "Movies", "movies" to "Movies",
        "الموسيقى" to "Music", "music" to "Music",
        "الكاميرا" to "DCIM",
    )

    // ═══════════ التحليل ═══════════

    /** التحليل الرئيسي — حتمي بالكامل وقابل للاختبار */
    fun parse(raw: String): Parsed {
        val t = ArabicText.normalize(raw)
        if (t.isEmpty()) return Parsed(Ids.UNKNOWN, emptyMap())

        // 1) شرح خطأ: لماذا … / فشل … / error …
        if (ArabicText.containsAny(t, WHY) || ArabicText.containsAny(t, FAILED)) {
            return Parsed(Ids.EXPLAIN_ERROR, mapOf(Keys.QUERY to raw.trim()))
        }

        // 2) الملفات المتطابقة/المكررة — قبل «اعرض» العامة
        if (ArabicText.containsAny(t, DUPLICATES)) {
            return Parsed(Ids.FIND_DUPLICATES, emptyMap())
        }

        // 3) تقرير التخزين
        if (ArabicText.containsAny(t, STORAGE)) {
            return Parsed(Ids.STORAGE_REPORT, emptyMap())
        }

        // 4) قاعدة أتمتة — يشترط فعل إنشاء صريحاً كي لا يلتقط ذكر «قاعدة» في نص بحث
        val ruleVerb = ArabicText.startsWithAny(
            t,
            listOf("أنشئ", "انشئ", "أضف", "اضف", "اعمل لي", "سجل", "create", "make", "add", "new automation"),
        )
        if (ruleVerb && ArabicText.containsAny(t, listOf("قاعده", "قواعد", "rule", "automation"))) {
            return parseRule(raw, t)
        }

        // 5) اقتراح تنظيم
        if (ArabicText.containsAny(t, ORGANIZE)) {
            return Parsed(Ids.ORGANIZE_SUGGEST, emptyMap())
        }

        // 6) بحث ملفات
        if (ArabicText.containsAny(t, FIND)) {
            return parseFind(raw, t)
        }

        // 7) مساعدة
        if (ArabicText.containsAny(t, HELP)) {
            return Parsed(Ids.HELP, emptyMap())
        }

        return Parsed(Ids.UNKNOWN, emptyMap())
    }

    // ---------- استخراج معاملات البحث ----------

    private fun parseFind(raw: String, t: String): Parsed {
        val params = HashMap<String, String>()

        // نوع الملف
        val kind = detectFileKind(t)
        params[Keys.FILE_KIND] = kind

        // نص بحث بالاسم: «اسمه X…» / "named X…" — حتى نهاية الجملة (أسماء بمسافات)
        val named = Regex("(?:اسمه|باسم|اسم الملف)\\s+(.+)$|(?:named|called)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(raw)
        params[Keys.TEXT] = (named?.groupValues?.get(1) ?: named?.groupValues?.get(2))
            ?.trim()
            ?.trim('"', '“', '”', '«', '»')
            ?: ""

        // الحداثة: عدّلته خلال … / modified this …
        val within = detectWithinDays(t)
        if (within != null) params[Keys.WITHIN_DAYS] = within.toString()

        // القِدم: لم أفتحها منذ … / not opened since …
        val older = detectOlderThanDays(t)
        if (older != null) params[Keys.OLDER_THAN_DAYS] = older.toString()

        // الحجم: أكبر من N mb/gb أو «كبيرة»
        val size = detectMinSizeMb(t)
        if (size != null) params[Keys.MIN_SIZE_MB] = size.toString()

        return Parsed(Ids.FIND_FILES, params)
    }

    private fun detectFileKind(t: String): String {
        for ((kw, kind) in FILE_KINDS) {
            if (t.contains(kw)) return kind
        }
        return "ANY"
    }

    private fun detectWithinDays(t: String): Int? {
        if (ArabicText.containsAny(t, listOf("اليوم", "today"))) return 1
        if (ArabicText.containsAny(t, listOf("امس", "yesterday"))) return 2
        if (ArabicText.containsAny(t, listOf("هذا الاسبوع", "this week", "الاسبوع"))) return 7
        if (ArabicText.containsAny(t, listOf("هذا الشهر", "this month", "الشهر"))) return 30
        // «آخر N يوم/أيام» أو "in the last N days"
        val m = Regex("(?:ال?(?:اخير|اخيره)\\s+)?(\\d+)\\s*(?:يوم|ايام|days?|day)").find(t)
        if (m != null) return m.groupValues[1].toIntOrNull()?.coerceIn(1, 3650)
        return null
    }

    private fun detectOlderThanDays(t: String): Int? {
        val stalePhrases = listOf(
            "لم افتح", "لم اعرض", "لم ا used", "لم use",
            "منذ مده", "منذ فتره", "قديم", "not opened", "unused", "haven't opened", "havent opened", "not touched",
        )
        if (!ArabicText.containsAny(t, stalePhrases)) return null
        // منذ N يوم / N شهر
        Regex("(\\d+)\\s*(?:يوم|ايام|days?)").find(t)?.let {
            return it.groupValues[1].toIntOrNull()?.coerceIn(1, 3650)
        }
        Regex("(\\d+)\\s*(?:شهر|اشهر|months?)").find(t)?.let {
            return (it.groupValues[1].toIntOrNull() ?: 1) * 30
        }
        return 30 // «منذ مدة» بلا رقم — افتراض موثق
    }

    private fun detectMinSizeMb(t: String): Long? {
        // أكبر من N mb/gb
        Regex("(?:اكبر من|اكثر من|larger than|bigger than|over|>)\\s*(\\d+)\\s*(gb|g|mb|m)?").find(t)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@let
            val unit = m.groupValues[2]
            return if (unit == "gb" || unit == "g") n * 1024L else n
        }
        if (ArabicText.containsAny(t, listOf("كبير", "كبيرة", "large", "big", "heavy"))) return 100L
        return null
    }

    // ---------- استخراج معاملات القاعدة ----------

    private fun parseRule(raw: String, t: String): Parsed {
        val params = HashMap<String, String>()

        // نوع الملف في القاعدة: أول نوع يظهر
        params[Keys.FILE_KIND] = detectFileKind(t)

        // الفعل المدعوم حالياً: نقل فقط (أفعال أخرى تُعرض كغير مدعومة بصراحة)
        val move = ArabicText.containsAny(t, listOf("نقل", "انقل", "move", "transfer"))
        params["action"] = if (move) "move" else ""

        // المجلد الهدف
        var target: String? = null
        for ((kw, dir) in TARGET_DIRS) {
            if (t.contains(kw)) { target = dir; break }
        }
        if (target != null) params[Keys.TARGET_DIR] = "/storage/emulated/0/$target"

        return Parsed(Ids.CREATE_RULE, params)
    }
}

/** أنواع الملفات المدعومة في المساعد — ترجمة آمنة إلى FileType في طبقة التنفيذ */
enum class AiFileKind(val key: String) {
    ANY("any"), PDF("pdf"), IMAGE("image"), VIDEO("video"), AUDIO("audio"),
    ARCHIVE("archive"), DOC("doc"), APK("apk"), CODE("code"), TEXT("text"),
    ;

    companion object {
        fun fromKey(key: String): AiFileKind =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) || it.name.equals(key, ignoreCase = true) }
                ?: ANY
    }
}
