package com.novafiles.app.core.ai

/**
 * v0.41 — Nova Intelligence: نماذج المساعد المحلي الحتمي.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM بلا أي اعتماد أندرويد.
 *
 * الثوابت الأمنية (قابلة للإثبات بالاختبار):
 *  1. لا توجد أداة حذف أو استبدال أو رفع في القائمة المسموحة إطلاقاً —
 *     لا يستطيع أي مدخل (بما فيه أسماء ملفات حقينة) توليد خطوة حذف.
 *  2. كل أداة WRITE تتطلب تأكيداً صريحاً من المستخدم قبل أي أثر.
 *  3. كل مسار يمر عبر PlanValidator (تطبيع canonical + جذور مسموحة).
 *  4. النتائج المعروضة تأتي من محركات التطبيق الحقيقية فقط — لا نتائج مختلقة.
 */

/** مستوى خطورة خطوة المساعد */
enum class RiskLevel {
    /** قراءة فقط — بحث/تحليل/تقرير */
    READ,

    /** تخطيط ومعاينة جافة — لا أثر على الملفات */
    PLAN,

    /** كتابة حقيقية — تتطلب تأكيداً صريحاً من المستخدم */
    WRITE,
}

/** أدوات المساعد المسموح بها — هذه هي القائمة المسموحة الوحيدة (allowlist) */
enum class AiTool(
    val id: String,
    val risk: RiskLevel,
    val requiresConfirmation: Boolean,
) {
    /** بحث ملفات بفلاتر النوع/الحجم/الحداثة عبر SearchEngine/FileIndexer */
    SEARCH_FILES("search_files", RiskLevel.READ, false),

    /** تقرير استهلاك التخزين عبر StorageAnalyzer على لقطة الفهرس */
    STORAGE_REPORT("storage_report", RiskLevel.READ, false),

    /** تقرير الملفات المتطابقة (حجم + هاش جزئي + MD5) — عرض فقط بلا حذف */
    DUPLICATES_REPORT("duplicates_report", RiskLevel.READ, false),

    /** الملفات الكبيرة القديمة غير المفتوحة منذ مدة — ترشيح للمراجعة فقط */
    LARGE_OLD_FILES("large_old_files", RiskLevel.READ, false),

    /** اقتراح تنظيم معاين (قرارات قابلة للتفسير + عدد الملفات) بلا تنفيذ */
    ORGANIZE_PLAN("organize_plan", RiskLevel.PLAN, false),

    /** مسودة قاعدة أتمتة من خيارات مدعومة فعلياً — عرض قبل الحفظ */
    RULE_DRAFT("rule_draft", RiskLevel.PLAN, false),

    /** حفظ قاعدة الأتمتة بعد المعاينة — يتطلب تأكيد المستخدم صراحة */
    RULE_CREATE("rule_create", RiskLevel.WRITE, true),
    ;

    companion object {
        /** البحث بالمعرف النصي — يُستخدم في التسلسل والاختبارات */
        fun ofId(id: String): AiTool? = entries.firstOrNull { it.id == id }
    }
}

/** أنواع وسيطات الأدوات — وسيطات محددة النوع لا نص حُرّ */
enum class ArgKind { TEXT, INT, PATH, ENUM }

/** مواصفة وسيط واحد */
data class ArgSpec(
    val key: String,
    val kind: ArgKind,
    val required: Boolean,
    val min: Long = Long.MIN_VALUE,
    val max: Long = Long.MAX_VALUE,
    val allowed: Set<String> = emptySet(), // لـ ENUM
)

/** مواصفة الأداة الكاملة */
data class ToolSpec(
    val tool: AiTool,
    val args: List<ArgSpec>,
)

/** خطوة تنفيذ واحدة داخل خطة */
data class ExecutionStep(
    val tool: AiTool,
    val args: Map<String, String>,
    /** وصف عربي قصير يُعرض للمستخدم قبل التنفيذ */
    val summaryAr: String,
    val summaryEn: String,
)

/** خطة تنفيذ كاملة */
data class ExecutionPlan(
    val steps: List<ExecutionStep>,
) {
    /** هل تحتاج الخطة تأكيداً صريحاً قبل التنفيذ؟ */
    val needsConfirmation: Boolean get() = steps.any { it.tool.requiresConfirmation }

    /** أعلى خطورة في الخطة */
    val maxRisk: RiskLevel
        get() = when {
            steps.any { it.tool.risk == RiskLevel.WRITE } -> RiskLevel.WRITE
            steps.any { it.tool.risk == RiskLevel.PLAN } -> RiskLevel.PLAN
            else -> RiskLevel.READ
        }
}

/** نتيجة تحقق الخطة */
data class PlanCheck(
    val ok: Boolean,
    val errors: List<String>,
) {
    companion object {
        val VALID = PlanCheck(true, emptyList())
    }
}

/** نتيجة تنفيذ أداة حقيقية على محركات التطبيق */
sealed interface ToolOutcome {
    /** نجاح — الأسطر الناتجة من المحرك الحقيقي (تُعرض كما هي) */
    data class Ok(val lines: List<String>) : ToolOutcome

    /** فشل — سبب مفهوم بالعربية (يشرحه المساعد للمستخدم) */
    data class Failed(val reasonAr: String) : ToolOutcome
}

/** دور رسالة المحادثة */
enum class ChatRole { USER, ASSISTANT }

/** رسالة محادثة (لسجل اختياري محلي) */
data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val tsMillis: Long,
)

/** الإجابة النهائية للمساعد على مدخل واحد */
data class AssistantTurn(
    val input: String,
    /** هل فُهم المدخل كمَعْروف؟ (غير مفهوم → اقتراحات) */
    val understood: Boolean,
    /** معرف النية الثابت — للمقارنات والاختبارات */
    val intentId: String,
    val replyAr: String,
    val replyEn: String,
    /** الخطة المقترحة — null عند عدم وجود عمل (مساعدة/غير مفهوم/مرفوض) */
    val plan: ExecutionPlan? = null,
    /** نتيجة التحقق — null حين لا توجد خطة */
    val validation: PlanCheck? = null,
    /** اقتراحات أوامر عند عدم الفهم */
    val suggestionsAr: List<String> = emptyList(),
) {
    /** هل يمكن تنفيذ الخطة آلياً (قراءة فقط + تحقق ناجح)؟ */
    val autoExecutable: Boolean
        get() = plan != null && !plan.needsConfirmation &&
            validation?.ok == true && plan.steps.isNotEmpty()
}
