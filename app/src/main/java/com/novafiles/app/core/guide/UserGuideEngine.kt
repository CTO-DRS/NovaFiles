package com.novafiles.app.core.guide

/**
 * v0.40 — دليل المستخدم المدمج: بند v1.0 «التوثيق: Wiki كامل لكل محرك»
 * في صيغة تعمل داخل التطبيق بلا شبكة وبلا سحابة.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM (نمط SecurityGuide v0.38).
 *
 *  - عشرة فصول مرتبة حتماً فوق كل محركات التطبيق، مصنفة إلى سبع
 *    فئات، ولكل فصل مفاتيح سلاسل ثابتة تُحصَّد بالاختبار ضد
 *    strings.xml الحقيقي — لا مفتاح مفقود ممكن عند البناء.
 *  - بحث حتماً داخل الفصول: تطبيع عربي (الهمزات والتاء المربوطة
 *    والألف المقصورة والتشكيل) + حالة إنجليزية غير حساسة، والنتائج
 *    بترتيب العرض دائماً.
 *  - تقدّم قراءة لكل فصل (مقروء/غير مقروء) محفوظ CSV حتماً عبر
 *    SettingsStore — والمنطق كله هنا خالص.
 *
 * المحرك بلا اعتماديات أندرويد: مفاتيح السلاسل نصوص، والشاشة
 * (ui/guide/UserGuideScreen.kt) تحلها إلى R.string.
 */

/** فئة الفصل — تتطابق مع رؤوس المجموعات في الشاشة */
enum class GuideCategory {
    BASICS,    // البدء والتصفح
    FILES,     // تنظيم الملفات
    NETWORK,   // الشبكات والمشاركة
    CLOUD,     // السحابة والمزامنة
    SECURITY,  // الخزنة والأمان
    SMART,     // الذكاء والأتمتة
    TOOLS,     // الأدوات والإنتاجية
}

/** فصل دليل واحد — المفاتيح حلُّها في الشاشة إلى موارد السلاسل */
data class GuideChapter(
    val id: String,
    val order: Int, // 1..n تسلسل صارم
    val category: GuideCategory,
    val titleKey: String,        // مفتاح strings.xml للعنوان
    val bodyKey: String,         // مفتاح strings.xml للشرح
    val keywords: List<String>,  // مفاتيح بحث (عربي + لاتيني) — ليست سلاسل واجهة
)

/** حالة تقدّم القراءة — مجموعة فصول مقروءة فقط */
data class UserGuideProgress(val readIds: Set<String> = emptySet()) {
    val readCount: Int get() = readIds.size
}

object UserGuide {

    // ---------- الفصول العشرة — الترتيب حتمي ثابت ----------

    val CHAPTERS: List<GuideChapter> = listOf(
        GuideChapter(
            "basics", 1, GuideCategory.BASICS, "ug_c_basics_t", "ug_c_basics_b",
            listOf("تصفح", "إذن", "تخزين", "مجلد", "تحديد", "browse", "storage", "permission"),
        ),
        GuideChapter(
            "search_tags", 2, GuideCategory.FILES, "ug_c_search_t", "ug_c_search_b",
            listOf("بحث", "فهرس", "وسم", "فلتر", "search", "index", "tag", "filter"),
        ),
        GuideChapter(
            "archives", 3, GuideCategory.FILES, "ug_c_archives_t", "ug_c_archives_b",
            listOf("أرشيف", "ضغط", "zip", "7z", "rar", "تقسيم", "archive", "compress"),
        ),
        GuideChapter(
            "network", 4, GuideCategory.NETWORK, "ug_c_network_t", "ug_c_network_b",
            listOf("شبكة", "smb", "sftp", "ftp", "webdav", "مشاركة", "خادم", "network", "server"),
        ),
        GuideChapter(
            "cloud_sync", 5, GuideCategory.CLOUD, "ug_c_cloud_t", "ug_c_cloud_b",
            listOf("سحابة", "مزامنة", "drive", "dropbox", "onedrive", "احتياطي", "cloud", "sync", "backup"),
        ),
        GuideChapter(
            "vault", 6, GuideCategory.SECURITY, "ug_c_vault_t", "ug_c_vault_b",
            listOf("خزنة", "تشفير", "bip39", "استرجاع", "حاسبة", "vault", "encryption", "recovery"),
        ),
        GuideChapter(
            "smart", 7, GuideCategory.SMART, "ug_c_smart_t", "ug_c_smart_b",
            listOf("ذكي", "تحليل", "treemap", "نبضة", "اقتراح", "مكررات", "تنظيف", "smart", "analyzer"),
        ),
        GuideChapter(
            "automation", 8, GuideCategory.SMART, "ug_c_automation_t", "ug_c_automation_b",
            listOf("أتمتة", "قاعدة", "مراقبة", "نقل تلقائي", "automation", "rule", "watch"),
        ),
        GuideChapter(
            "tools", 9, GuideCategory.TOOLS, "ug_c_tools_t", "ug_c_tools_b",
            listOf("محرر", "طرفية", "وسائط", "pdf", "صور", "تطبيقات", "editor", "terminal", "media"),
        ),
        GuideChapter(
            "settings_transfer", 10, GuideCategory.TOOLS, "ug_c_settings_t", "ug_c_settings_b",
            listOf("إعدادات", "تصدير", "نقل", "نسخة", "settings", "export", "migrate"),
        ),
    )

    /** التحقق البنيوي: معرفات فريدة، تسلسل 1..n، مفاتيح سليمة، كلمات بحث ذات معنى */
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        val ids = CHAPTERS.map { it.id }
        if (ids.distinct().size != ids.size) errors += "معرفات فصول مكررة"
        CHAPTERS.forEachIndexed { i, ch ->
            if (ch.order != i + 1) errors += "ترتيب الفصل ${ch.id} مخالف: ${ch.order} والموضع ${i + 1}"
            if (ch.titleKey.isBlank() || ch.bodyKey.isBlank()) errors += "مفاتيح فارغة في ${ch.id}"
            if (ch.titleKey == ch.bodyKey) errors += "مفتاحا العنوان والشرح متطابقان في ${ch.id}"
            if (ch.keywords.size < 2) errors += "الفصل ${ch.id} بكلمات بحث أقل من اثنتين"
            if (ch.keywords.any { it.isBlank() }) errors += "كلمة بحث فارغة في ${ch.id}"
        }
        CHAPTERS.groupBy { it.titleKey }.forEach { (k, group) ->
            if (group.size > 1) errors += "مفتاح عنوان مكرر: $k"
        }
        CHAPTERS.groupBy { it.bodyKey }.forEach { (k, group) ->
            if (group.size > 1) errors += "مفتاح شرح مكرر: $k"
        }
        // كل فئة على الأقل فصل واحد — لا فئة يتيمة في الواجهة
        GuideCategory.entries.forEach { cat ->
            if (CHAPTERS.none { it.category == cat }) errors += "فئة بلا فصول: $cat"
        }
        return errors
    }

    fun byId(id: String): GuideChapter? = CHAPTERS.firstOrNull { it.id == id }

    /** فصول فئة واحدة بترتيب العرض */
    fun chaptersIn(category: GuideCategory): List<GuideChapter> =
        CHAPTERS.filter { it.category == category }

    // ---------- البحث ----------

    /**
     * تطبيع حتمي للبحث: حالة إنجليزية غير حساسة + تطبيع عربي
     * (أ/إ/آ → ا، ة → ه، ى → ي، وإزالة التشكيل والتطويل).
     */
    fun normalize(raw: String): String {
        val lowered = raw.trim().lowercase()
        val noDiacritics = lowered.filter { c ->
            val code = c.code
            code !in 0x064B..0x0652 && c != '\u0640' // التشكيل + التطويل
        }
        return buildString(noDiacritics.length) {
            for (c in noDiacritics) {
                when (c) {
                    'أ', 'إ', 'آ' -> append('ا')
                    'ة' -> append('ه')
                    'ى' -> append('ي')
                    else -> append(c)
                }
            }
        }
    }

    /**
     * بحث الفصول: سؤال فارغ → كل الفصول بترتيب العرض.
     * المطابقة: المعرف أو أي كلمة بحث (بعد التطبيع) — تطابق تام
     * دائماً، واحتواءً متبادلاً حين يبلغ السؤال حرفين فأكثر.
     * النتائج بترتيب العرض حتماً بلا تكرار.
     */
    fun search(query: String): List<GuideChapter> {
        val q = normalize(query)
        if (q.isEmpty()) return CHAPTERS
        return CHAPTERS.filter { ch ->
            normalize(ch.id).contains(q) ||
                ch.keywords.any { k ->
                    val nk = normalize(k)
                    nk == q || (q.length >= 2 && (nk.contains(q) || q.contains(nk)))
                }
        }
    }

    // ---------- تقدّم القراءة ----------

    /** تحديد فصل كمقروء — معرف مجهول يُرفض بأمان */
    fun markRead(progress: UserGuideProgress, id: String): UserGuideProgress? =
        if (byId(id) == null) null else UserGuideProgress(progress.readIds + id)

    /** إلغاء قراءة فصل — معرف مجهول يُرفض بأمان */
    fun markUnread(progress: UserGuideProgress, id: String): UserGuideProgress? =
        if (byId(id) == null) null else UserGuideProgress(progress.readIds - id)

    /** نسبة الإنجاز 0..100 حتماً — تعبر المجموعة بمعرفات معروفة فقط */
    fun percent(progress: UserGuideProgress): Int =
        if (CHAPTERS.isEmpty()) 0
        else progress.readIds.intersect(CHAPTERS.map { it.id }.toSet()).size * 100 / CHAPTERS.size

    /** هل الدليل مُتم؟ */
    fun isComplete(progress: UserGuideProgress): Boolean = percent(progress) == 100

    /** أول فصل غير مقروء بترتيب العرض — null عند الإتمام */
    fun nextUnread(progress: UserGuideProgress): GuideChapter? =
        CHAPTERS.firstOrNull { it.id !in progress.readIds }

    // ---------- ترميز CSV للتقدّم ----------

    /**
     * ترميز حتمي: مرتب بترتيب الفصول المعروضة (لا بترتيب الإدخال)،
     * والمعرفات المجهولة تُسقَط عند الترميز نفسه.
     */
    fun serialize(progress: UserGuideProgress): String =
        CHAPTERS.filter { it.id in progress.readIds }.joinToString(",") { it.id }

    /** فك متسامح: فارغ/تالف → تقدّم صفر، والمعرفات المجهولة تُتخطّى */
    fun parse(raw: String): UserGuideProgress {
        if (raw.isBlank()) return UserGuideProgress()
        val known = CHAPTERS.map { it.id }.toSet()
        return UserGuideProgress(
            raw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it in known }
                .toSet(),
        )
    }
}
