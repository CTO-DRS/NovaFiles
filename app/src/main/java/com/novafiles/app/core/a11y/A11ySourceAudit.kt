package com.novafiles.app.core.a11y

/**
 * v0.38 — توطيد ما قبل الإطلاق: بوابة تدقيق الإتاحة على مصادر الواجهة.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * الخلفية: أيقونة بلا وصف دلالي (`contentDescription = null`) مقبولة
 * **فقط** حين تكون زخرفية مقترنة بنص مرئي بجوارها (صف قائمة له عنوان
 * يكفي قارئ الشاشة). القائمة البيضاء أدناه جردٌ مُراجَع لكل المواضع
 * الزخرفية الحالية مع تبريدها — وأي موضع جديد غير مُدرج **يُفشل
 * الاختبار** حتى يُراجَع ويُبرَّر. هكذا لا ينزلق انحدار إتاحة صامت
 * إلى الشيفرة: كل `contentDescription = null` جديد مرئي ومطالَب بالتبرير.
 *
 * الفحص نصي حتمي (نمط BuildMaturityEngine v0.37): عدد التطابقات الحرفية
 * `contentDescription = null` لكل ملف — بلا تحليل AST ولا هشاشة أرقام
 * الأسطر.
 */

/** موضع مُبرَّر في القائمة البيضاء */
data class A11yAllow(
    val path: String, // مسار نسبي من جذر com/novafiles/app/ui
    val allowed: Int, // العدد المُراجَع والمسموح
    val reason: String,
)

/** مخالفة تدقيق واحدة */
data class SourceFinding(
    val path: String,
    val kind: Kind,
    val detail: String,
) {
    enum class Kind {
        /** ملف فيه مواضع بلا إدخال في القائمة البيضاء إطلاقاً */
        UNLISTED,

        /** العدد الفعلي تجاوز المسموح — موضع جديد غير مُبرَّر */
        EXCEEDS,

        /** العدد الفعلي أقل من المسموح — الإدخال يجب تنظيفه أو تصحيحه */
        STALE,
    }
}

object A11ySourceAudit {

    /** النمط الحرفي المجرود — صيغة Kotlin القياسية المستخدمة في المستودع */
    const val PATTERN = "contentDescription = null"

    /** عدد التطابقات الحرفية في نص مصدر واحد */
    fun countOccurrences(source: String): Int =
        PATTERN.toRegex().findAll(source).count()

    /**
     * التدقيق: يُعاد كل مخالفة مرتبة حتماً (المسار ثم النوع).
     *  - ملف فيه مواضع بلا إدخال → UNLISTED بعدد المواضع كله
     *  - ملف تجاوز المسموح → EXCEEDS بالفارق
     *  - ملف نقص عن المسموح → STALE (تنظيف القائمة البيضاء)
     * قائمة فارغة = القائمة البيضاء مطابقة تماماً.
     */
    fun audit(sources: Map<String, String>, allowList: List<A11yAllow>): List<SourceFinding> {
        val byPath = allowList.associateBy { it.path }
        val findings = mutableListOf<SourceFinding>()

        for ((path, source) in sources) {
            val count = countOccurrences(source)
            if (count == 0) continue // ملف نظيف — لا علاقة له بالبوابة
            val entry = byPath[path]
            when {
                entry == null -> findings += SourceFinding(
                    path, SourceFinding.Kind.UNLISTED,
                    "عدد المواضع $count بلا تبرير مُراجَع — أضف A11yAllow أو صف الدلالة الفعلية",
                )
                count > entry.allowed -> findings += SourceFinding(
                    path, SourceFinding.Kind.EXCEEDS,
                    "الفعلي $count > المسموح ${entry.allowed} — وصف أيقونة جديد بلا تبرير",
                )
                count < entry.allowed -> findings += SourceFinding(
                    path, SourceFinding.Kind.STALE,
                    "الفعلي $count < المسموح ${entry.allowed} — حدّث القائمة البيضاء",
                )
            }
        }

        // إدخالات القائمة البيضاء لملفات لم يعد فيها أي موضع — تنظيف
        for (entry in allowList) {
            val count = sources[entry.path]?.let { countOccurrences(it) } ?: 0
            if (count == 0) {
                findings += SourceFinding(
                    entry.path, SourceFinding.Kind.STALE,
                    "لا مواضع في الملف بعد — احذف الإدخال (${entry.reason})",
                )
            }
        }

        return findings.sortedWith(compareBy({ it.path }, { it.kind }))
    }

    /**
     * القائمة البيضاء المراجعة عند v0.38 — كل موضع زخرفي مقترن بنص مرئي
     * أو بدور غير دلالي (فاصل/خلفية/أيقونة داخل صف له عنوان يكفيه
     * قارئ الشاشة). أي تغيير هنا يستوجب مراجعة إتاحة موثقة في رسالة
     * الالتزام.
     */
    val ALLOW_V038: List<A11yAllow> = listOf(
        A11yAllow("analyzer/AnalyzerScreen.kt", 2, "أيقونات زخرفية داخل بطاقات لها عناوين نصية"),
        A11yAllow("apps/AppsScreen.kt", 3, "أيقونات ترتيب/فلترة داخل صفوف مُسمّاة"),
        A11yAllow("archive/ArchiveBrowserScreen.kt", 2, "أيقونات مسار الأرشيف بجوار اسم الملف"),
        A11yAllow("browser/BatchRenameSheet.kt", 1, "أيقونة معاينة داخل صف له عنوان القاعدة"),
        A11yAllow("browser/BrowserParts.kt", 4, "أيقونات شارات الحالة بجوار أسماء الملفات"),
        A11yAllow("browser/BrowserScreen.kt", 2, "أيقونات حالة فارغ/تحميل بجوار نصوصها"),
        A11yAllow("browser/DirectoryTreePanel.kt", 4, "أسهم تدوير الشجرة — الحالة معلنة على الصف"),
        A11yAllow("browser/DualPane.kt", 1, "فاصل اللوحتين — عنصر زخرفي خالص"),
        A11yAllow("cloud/CloudBrowserScreen.kt", 2, "أيقونات مزود السحابة بجوار اسم الحساب"),
        A11yAllow("cloud/CloudScreen.kt", 3, "أيقونات حالة ربط بجوار نصوص الحالة"),
        A11yAllow("direct/DirectScreen.kt", 1, "أيقونة QR بجوار عنوان البطاقة"),
        A11yAllow("editor/EditorScreen.kt", 2, "أيقونات ترميز/حالة حفظ بجوار النص"),
        A11yAllow("favorites/FavoritesScreen.kt", 1, "أيقونة نجمة صف معلن بعنوان الصف"),
        A11yAllow("ios/IOSActionSheet.kt", 1, "أيقونة إجراء مُقدَّم بنص الإجراء نفسه"),
        A11yAllow("ios/IOSControls.kt", 3, "سهم تنقل/علامة ✓ — الحالة على الصف الحاوي"),
        A11yAllow("network/ConnectionFormScreen.kt", 3, "أيقونات بروتوكول بجوار حقل مُسمّى"),
        A11yAllow("network/NetworkScreen.kt", 2, "أيقونات حالة اتصال بجوار نص الحالة"),
        A11yAllow("network/RemoteBrowserScreen.kt", 1, "أيقونة خادم بجوار اسم الاتصال"),
        A11yAllow("ops/OpsScreen.kt", 1, "أيقونة نوع العملية بجوار عنوان العملية"),
        A11yAllow("recents/RecentScreen.kt", 3, "أيقونات رفوف بجوار عناوين الرفوف"),
        A11yAllow("settings/SettingsScreen.kt", 1, "أيقونة قفل الخزنة داخل صف له عنوان"),
        A11yAllow("tags/TagViews.kt", 2, "نقاط لون الوسم — اللون مُعلن نصياً على الصف"),
        A11yAllow("vault/Vault2Screen.kt", 2, "أيقونات قفل/درع بجوار عناوين الأقسام"),
        A11yAllow("viewer/ImageViewerScreen.kt", 1, "أيقونة معلومات شريط مخفي بعرض نصي بديل"),
    )
}
