package com.novafiles.app.core.ai

/**
 * v0.41 — محرك المساعد المنسق (AssistantEngine).
 *
 * المسار الحتمي الكامل: نص → نية → خطة (أدوات مسموحة فقط) → تحقق (سجل +
 * أنواع + مسارات) → دور جاهز. التنفيذ الفعلي يحدث في طبقة أندرويد عبر
 * محولات على المحركات الحقيقية، والكتابة لا تحدث أبداً دون تأكيد المستخدم.
 *
 * هذا الصنف Kotlin خالص — يُختبر بالكامل على JVM.
 */
class AssistantEngine(
    private val validator: PlanValidator,
    private val now: () -> Long = { 0L },
) {

    /** معرفات النوايا = معرفات IntentParser (مصدر واحد) */
    private val ids = IntentParser.Ids

    /** اقتراحات عدم الفهم — كلها أوامر مدعومة فعلاً */
    val suggestions: List<String> = listOf(
        "اعثر على ملفات PDF التي عدّلتها هذا الأسبوع",
        "حلّل المجلدات التي تستهلك أكبر مساحة",
        "اعرض الملفات المتطابقة قبل حذف أي نسخة",
        "أنشئ قاعدة تنقل ملفات PDF الجديدة إلى مجلد المستندات",
        "اعرض الملفات الكبيرة التي لم أفتحها منذ مدة",
        "لماذا فشل اتصال SFTP؟",
    )

    /** المعالجة الكاملة لمدخل المستخدم */
    fun handle(input: String): AssistantTurn {
        val parsed = IntentParser.parse(input)
        return when (parsed.intentId) {
            ids.FIND_FILES -> findFiles(parsed.params, input)
            ids.STORAGE_REPORT -> simple(
                input,
                IntentParser.Ids.STORAGE_REPORT,
                AiTool.STORAGE_REPORT,
                emptyMap(),
                "سأحلل استهلاك التخزين من لقطة الفهرس الحالية وأعرض أكبر الفئات والمجلدات.",
                "I will analyze storage usage from the current index snapshot.",
            )
            ids.FIND_DUPLICATES -> simple(
                input,
                IntentParser.Ids.FIND_DUPLICATES,
                AiTool.DUPLICATES_REPORT,
                mapOf("limit" to "50"),
                "سأبحث عن الملفات المتطابقة بالمحتوى (حجم + بصمة) للعرض فقط — لن أحذف أي شيء.",
                "I will find content-identical files for display only — nothing is deleted.",
            )
            ids.ORGANIZE_SUGGEST -> organizePlan(input)
            ids.CREATE_RULE -> ruleDraft(parsed.params, input)
            ids.EXPLAIN_ERROR -> explain(input)
            ids.HELP -> help()
            else -> AssistantTurn(
                input = input,
                understood = false,
                intentId = IntentParser.Ids.UNKNOWN,
                replyAr = "لم أفهم الطلب بصيغته هذه. جرّب أحد الأوامر المقترحة:",
                replyEn = "I did not understand this phrasing. Try one of the suggestions:",
                suggestionsAr = suggestions,
            )
        }
    }

    // ---------- بناء الخطط ----------

    private fun findFiles(params: Map<String, String>, input: String): AssistantTurn {
        val args = HashMap<String, String>()
        params[IntentParser.Keys.TEXT]?.let { if (it.isNotEmpty()) args[IntentParser.Keys.TEXT] = it }
        args[IntentParser.Keys.FILE_KIND] = params[IntentParser.Keys.FILE_KIND] ?: "ANY"
        params[IntentParser.Keys.MIN_SIZE_MB]?.let { args[IntentParser.Keys.MIN_SIZE_MB] = it }
        params[IntentParser.Keys.WITHIN_DAYS]?.let { args[IntentParser.Keys.WITHIN_DAYS] = it }
        params[IntentParser.Keys.OLDER_THAN_DAYS]?.let { args[IntentParser.Keys.OLDER_THAN_DAYS] = it }
        args["limit"] = "100"

        val kind = params[IntentParser.Keys.FILE_KIND] ?: "ANY"
        val parts = ArrayList<String>()
        if (kind != "ANY") parts += "من نوع $kind"
        params[IntentParser.Keys.WITHIN_DAYS]?.let { parts += "عُدّلت خلال آخر $it يوم" }
        params[IntentParser.Keys.OLDER_THAN_DAYS]?.let { parts += "لم تتغير منذ $it يوم" }
        params[IntentParser.Keys.MIN_SIZE_MB]?.let { parts += "بحجم $it MB أو أكثر" }

        val desc = if (parts.isEmpty()) "كل الملفات" else parts.joinToString("، ")
        val plan = ExecutionPlan(
            listOf(
                ExecutionStep(
                    tool = AiTool.SEARCH_FILES,
                    args = args,
                    summaryAr = "بحث قراءة فقط عن $desc",
                    summaryEn = "Read-only search for $desc",
                ),
            ),
        )
        return AssistantTurn(
            input = input,
            understood = true,
            intentId = IntentParser.Ids.FIND_FILES,
            replyAr = "سأبحث في الفهرس المحلي عن $desc. نتائج حقيقية من القرص فقط.",
            replyEn = "Searching the local index for $desc. Real disk results only.",
            plan = plan,
            validation = validator.validate(plan),
        )
    }

    private fun simple(
        input: String,
        intentId: String,
        tool: AiTool,
        args: Map<String, String>,
        replyAr: String,
        replyEn: String,
    ): AssistantTurn {
        val plan = ExecutionPlan(
            listOf(
                ExecutionStep(
                    tool = tool,
                    args = args,
                    summaryAr = when (tool) {
                        AiTool.STORAGE_REPORT -> "تحليل التخزين (قراءة فقط)"
                        AiTool.DUPLICATES_REPORT -> "فحص المكررات (قراءة فقط — بلا حذف)"
                        else -> "تنفيذ ${tool.id} (قراءة فقط)"
                    },
                    summaryEn = "Run ${tool.id} (read-only)",
                ),
            ),
        )
        return AssistantTurn(
            input = input,
            understood = true,
            intentId = intentId,
            replyAr = replyAr,
            replyEn = replyEn,
            plan = plan,
            validation = validator.validate(plan),
        )
    }

    private fun organizePlan(input: String): AssistantTurn {
        // خطتان قراءتان: المكررات + الكبيرة القديمة — اقتراح مراجعة بلا أي حذف
        val plan = ExecutionPlan(
            listOf(
                ExecutionStep(
                    tool = AiTool.DUPLICATES_REPORT,
                    args = mapOf("limit" to "30"),
                    summaryAr = "رصد المكررات المرشحة للمراجعة (بلا حذف)",
                    summaryEn = "Detect duplicate candidates for review (no deletion)",
                ),
                ExecutionStep(
                    tool = AiTool.LARGE_OLD_FILES,
                    args = mapOf("min_size_mb" to "100", "older_than_days" to "30", "limit" to "30"),
                    summaryAr = "رصد الملفات الكبيرة غير المفتوحة منذ شهر",
                    summaryEn = "Detect large files untouched for a month",
                ),
            ),
        )
        return AssistantTurn(
            input = input,
            understood = true,
            intentId = IntentParser.Ids.ORGANIZE_SUGGEST,
            replyAr = "سأجهّز اقتراح تنظيم قابل للتفسير: مكررات + ملفات كبيرة قديمة — عرض فقط ومراجعتك قرارك.",
            replyEn = "Preparing an explainable organization proposal: duplicates + large old files.",
            plan = plan,
            validation = validator.validate(plan),
        )
    }

    private fun ruleDraft(params: Map<String, String>, raw: String): AssistantTurn {
        val kind = params[IntentParser.Keys.FILE_KIND] ?: "ANY"
        val target = params[IntentParser.Keys.TARGET_DIR]
        val action = params["action"] ?: ""

        if (action != "move" || target.isNullOrBlank()) {
            return AssistantTurn(
                input = raw,
                understood = true,
                intentId = IntentParser.Ids.CREATE_RULE,
                replyAr = "أستطيع إنشاء قاعدة نقل فقط حالياً (من مجلد التنزيلات إلى مجلد عام معروف). " +
                    "مثال: «أنشئ قاعدة تنقل ملفات PDF الجديدة إلى مجلد المستندات».",
                replyEn = "Only move-rules to a known standard folder are supported for now.",
                suggestionsAr = listOf(
                    "أنشئ قاعدة تنقل ملفات PDF الجديدة إلى مجلد المستندات",
                    "أنشئ قاعدة تنقل الصور الجديدة إلى مجلد الصور",
                ),
            )
        }

        val args = mapOf(
            IntentParser.Keys.FILE_KIND to kind,
            "action" to "move",
            IntentParser.Keys.TARGET_DIR to target,
        )
        val plan = ExecutionPlan(
            listOf(
                ExecutionStep(
                    tool = AiTool.RULE_DRAFT,
                    args = args,
                    summaryAr = "مسودة قاعدة: نقل ملفات $kind الجديدة في التنزيلات إلى $target (معاينة قبل الحفظ)",
                    summaryEn = "Rule draft: move new $kind files from Downloads to $target",
                ),
            ),
        )
        return AssistantTurn(
            input = raw,
            understood = true,
            intentId = IntentParser.Ids.CREATE_RULE,
            replyAr = "جهّزت مسودة قاعدة أتمتة — راجعها في بطاقة المعاينة ثم أكّد الحفظ بنفسك.",
            replyEn = "Rule draft is ready — review the preview card and confirm saving yourself.",
            plan = plan,
            validation = validator.validate(plan),
        )
    }

    private fun explain(raw: String): AssistantTurn {
        val exp = ErrorExplainer.explain(raw)
        val hints = exp.hintsAr.joinToString("\n") { "• $it" }
        return AssistantTurn(
            input = raw,
            understood = true,
            intentId = IntentParser.Ids.EXPLAIN_ERROR,
            replyAr = "${exp.titleAr}\n$hints",
            replyEn = exp.titleAr, // الشرح عربي أصلاً؛ الإنجليزية للنقل مستقبلاً
            suggestionsAr = emptyList(),
        )
    }

    private fun help(): AssistantTurn = AssistantTurn(
        input = "",
        understood = true,
        intentId = IntentParser.Ids.HELP,
        replyAr = "أنا مساعد NovaFiles المحلي — أعمل بلا إنترنت نهائياً. أستطيع: " +
            "البحث عن الملفات بالنوع والحداثة والحجم، تحليل التخزين، عرض المكررات، " +
            "اقتراح التنظيم، إنشاء قواعد أتمتة بعد معاينتك، وشرح أخطاء الاتصال والعمليات. " +
            "لا أحذف ولا أرفع شيئاً أبداً دون تأكيدك الصريح.",
        replyEn = "I am the fully offline NovaFiles assistant: search, storage analysis, " +
            "duplicates, organize suggestions, reviewed rule creation, and error explanations.",
        suggestionsAr = suggestions,
    )
}
