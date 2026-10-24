package com.novafiles.app.core.ai

/**
 * v0.41 — سجل الأدوات المسموحة (CapabilityRegistry).
 *
 * المصدر الوحيد لما يُسمح للمساعد بفعله. كل مواصفة وسيط مضبوطة النوع،
 * وأي أداة خارج هذا السجل تُرفض قبل الوصول إلى أي محرك.
 */
object AiToolRegistry {

    private val specs: Map<AiTool, ToolSpec> = buildMap {
        put(
            AiTool.SEARCH_FILES,
            ToolSpec(
                AiTool.SEARCH_FILES,
                listOf(
                    ArgSpec(IntentParser.Keys.TEXT, ArgKind.TEXT, required = false, max = 200),
                    ArgSpec(IntentParser.Keys.FILE_KIND, ArgKind.ENUM, required = false, allowed = AiFileKind.entries.map { it.name }.toSet()),
                    ArgSpec(IntentParser.Keys.MIN_SIZE_MB, ArgKind.INT, required = false, min = 0, max = 1_048_576),
                    ArgSpec(IntentParser.Keys.WITHIN_DAYS, ArgKind.INT, required = false, min = 1, max = 3650),
                    ArgSpec(IntentParser.Keys.OLDER_THAN_DAYS, ArgKind.INT, required = false, min = 1, max = 3650),
                    ArgSpec("limit", ArgKind.INT, required = false, min = 1, max = 500),
                ),
            ),
        )
        put(AiTool.STORAGE_REPORT, ToolSpec(AiTool.STORAGE_REPORT, emptyList()))
        put(
            AiTool.DUPLICATES_REPORT,
            ToolSpec(
                AiTool.DUPLICATES_REPORT,
                listOf(ArgSpec("limit", ArgKind.INT, required = false, min = 1, max = 200)),
            ),
        )
        put(
            AiTool.LARGE_OLD_FILES,
            ToolSpec(
                AiTool.LARGE_OLD_FILES,
                listOf(
                    ArgSpec(IntentParser.Keys.MIN_SIZE_MB, ArgKind.INT, required = false, min = 0, max = 1_048_576),
                    ArgSpec(IntentParser.Keys.OLDER_THAN_DAYS, ArgKind.INT, required = false, min = 1, max = 3650),
                    ArgSpec("limit", ArgKind.INT, required = false, min = 1, max = 500),
                ),
            ),
        )
        put(AiTool.ORGANIZE_PLAN, ToolSpec(AiTool.ORGANIZE_PLAN, emptyList()))
        put(
            AiTool.RULE_DRAFT,
            ToolSpec(
                AiTool.RULE_DRAFT,
                listOf(
                    ArgSpec(IntentParser.Keys.FILE_KIND, ArgKind.ENUM, required = true, allowed = AiFileKind.entries.map { it.name }.toSet()),
                    ArgSpec("action", ArgKind.ENUM, required = true, allowed = setOf("move")),
                    ArgSpec(IntentParser.Keys.TARGET_DIR, ArgKind.PATH, required = true),
                ),
            ),
        )
        // نفس وسائط المسودة — الأداة الوحيدة الكاتبة وتتطلب تأكيداً
        put(
            AiTool.RULE_CREATE,
            ToolSpec(
                AiTool.RULE_CREATE,
                listOf(
                    ArgSpec(IntentParser.Keys.FILE_KIND, ArgKind.ENUM, required = true, allowed = AiFileKind.entries.map { it.name }.toSet()),
                    ArgSpec("action", ArgKind.ENUM, required = true, allowed = setOf("move")),
                    ArgSpec(IntentParser.Keys.TARGET_DIR, ArgKind.PATH, required = true),
                ),
            ),
        )
    }

    /** هل الأداة مسموحة إطلاقاً؟ */
    fun isAllowed(tool: AiTool): Boolean = specs.containsKey(tool)

    /** مواصفة الأداة — null إن لم تكن مسموحة */
    fun spec(tool: AiTool): ToolSpec? = specs[tool]

    /** كل الأدوات المسموحة (لشاشة المساعدة) */
    fun all(): List<ToolSpec> = specs.values.toList()

    /**
     * التحقق من وسيطات أداة ضد مواصفتها — يعيد قائمة أخطاء (فارغة = سليم).
     * أي مفتاح غير معروف في المواصفة يُرفض فوراً.
     */
    fun validateArgs(tool: AiTool, args: Map<String, String>): List<String> {
        val spec = specs[tool] ?: return listOf("tool_not_allowed:${tool.id}")
        val errors = ArrayList<String>()
        val knownKeys = spec.args.map { it.key }.toSet()
        for (k in args.keys) {
            if (k !in knownKeys) errors += "unknown_arg:$k"
        }
        for (a in spec.args) {
            val v = args[a.key]
            if (v == null || v.isEmpty()) {
                if (a.required) errors += "missing_arg:${a.key}"
                continue
            }
            when (a.kind) {
                ArgKind.INT -> {
                    val n = v.toLongOrNull()
                    if (n == null) errors += "not_int:${a.key}"
                    else if (n < a.min || n > a.max) errors += "out_of_range:${a.key}"
                }
                ArgKind.ENUM -> if (v !in a.allowed) errors += "bad_enum:${a.key}"
                ArgKind.PATH -> if (v.isBlank() || !v.startsWith('/')) errors += "bad_path:${a.key}"
                ArgKind.TEXT -> if (v.length > a.max) errors += "too_long:${a.key}"
            }
        }
        return errors
    }
}
