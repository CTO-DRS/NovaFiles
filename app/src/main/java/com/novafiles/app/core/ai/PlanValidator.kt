package com.novafiles.app.core.ai

import java.io.File

/**
 * v0.41 — مدقق الخطط (PlanValidator).
 *
 * يفحص كل مسار قبل أي تنفيذ:
 *  1) تطبيع canonical — يفكك .. والروابط الرمزية ويحل الاسم الحقيقي
 *  2) الحتمية داخل جذور مسموحة فقط (بحدود المسار الفعلية بعد الحل)
 *  3) رفض المسارات الفارغة والنسبية
 *
 * اختبارات سلبية إلزامية: ../ و /data/ و /system/ والروابط الرمزية الخارجة.
 */
class PlanValidator(
    /** الجذور المسموحة — تُطبَّع canonical مرة واحدة عند البناء */
    allowedRoots: List<String>,
) {

    private val roots: List<String> = allowedRoots
        .map { File(it).canonicalFile.absolutePath }
        .sortedByDescending { it.length } // الأطول أولاً كي لا يبتلع prefix جزئي

    /** فحص مسار واحد */
    fun checkPath(raw: String): List<String> {
        if (raw.isBlank()) return listOf("empty_path")
        if (!raw.startsWith('/')) return listOf("relative_path")
        if (raw.contains('\u0000')) return listOf("null_byte")
        val canonical = try {
            File(raw).canonicalFile.absolutePath
        } catch (_: Exception) {
            return listOf("unresolvable_path")
        }
        val inside = roots.any { root ->
            canonical == root || canonical.startsWith(root + "/")
        }
        if (!inside) return listOf("outside_allowed_roots")
        return emptyList()
    }

    /** الجذر الأول المحتوي لمسار (لعرض النطاق في الواجهة) */
    fun containingRoot(raw: String): String? {
        if (checkPath(raw).isNotEmpty()) return null
        val canonical = File(raw).canonicalFile.absolutePath
        return roots.firstOrNull { canonical == it || canonical.startsWith(it + "/") }
    }

    /** فحص خطة كاملة: وسيطات كل أداة ضد السجل + كل مسار ضد الجذور */
    fun validate(plan: ExecutionPlan): PlanCheck {
        val errors = ArrayList<String>()
        for (step in plan.steps) {
            if (!AiToolRegistry.isAllowed(step.tool)) {
                errors += "tool_not_allowed:${step.tool.id}"
                continue
            }
            errors += AiToolRegistry.validateArgs(step.tool, step.args)
            for ((k, v) in step.args) {
                val spec = AiToolRegistry.spec(step.tool) ?: continue
                val argSpec = spec.args.firstOrNull { it.key == k } ?: continue
                if (argSpec.kind == ArgKind.PATH) {
                    errors += checkPath(v).map { "$k:$it" }
                }
            }
        }
        return if (errors.isEmpty()) PlanCheck.VALID else PlanCheck(false, errors.distinct())
    }
}
