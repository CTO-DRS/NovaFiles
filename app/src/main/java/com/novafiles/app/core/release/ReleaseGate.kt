package com.novafiles.app.core.release

import com.novafiles.app.core.l10n.LanguageCatalog

/**
 * v0.38 — توطيد ما قبل الإطلاق: بوابة جاهزية الإطلاق.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * جزآن مستقلان في ملف واحد:
 *
 *  1. **سجل مراجعة اللغات العشر** — لكل لغة مرحلة مراجعة تصاعدية:
 *     مسودة آلية ← مراجعة أصيلة ← اعتماد موقّع. المحرك يمنع التراجع،
 *     ويشترط المراجع لما بعد المسودة، والتاريخ الصالح للاعتماد.
 *     هذا هو التنفيذ القابل للتدقيق لوعد v0.37: «مراجعة أصيلة مجدولة قبل v1.0».
 *
 *  2. **بوابات الجودة المرقّمة** — عتبات عددية صريحة (اختبارات، سلاسل
 *     متطابقة، حجم APK، بدء بارد…) تُقيَّم فعلياً ضد قياسات حقيقية:
 *     PASS/FAIL/MISSING لكل بوابة وحُكم إجمالي حتمي.
 *
 * الترميز JSON متسامح: ملف تالف → قائمة فارغة، مراحل/لغات مجهولة
 * تُتخطّى للأمام (توافق مستقبلي) — بنفس فلسفة مرمّزات v0.35.
 */

// ═════════════════ 1) سجل مراجعة اللغات ═════════════════

/** مراحل المراجعة التصاعدية — الرتبة تزيد مع النضج ولا تنقص أبداً */
enum class ReviewStage(val rank: Int) {
    MACHINE_DRAFT(0),
    NATIVE_REVIEW(1),
    SIGNED_OFF(2),
}

/** سجل مراجعة اللغة الواحدة */
data class LangReview(
    val code: String,
    val stage: ReviewStage,
    val reviewer: String = "",
    val reviewedAt: String = "", // ISO yyyy-MM-dd — يشترط عند SIGNED_OFF
    val notes: String = "",
)

/** نتيجة دمج سجل — نجاح أو رسالة خطأ عربية محددة */
sealed class MergeResult {
    data class Ok(val reviews: List<LangReview>) : MergeResult()
    data class Rejected(val reason: String) : MergeResult()
}

/** ملخص جاهزية اللغات */
data class L10nReadiness(
    val signedOff: Int,
    val total: Int,
    val pendingCodes: List<String>, // لغات لم تصل الاعتماد — مرتبة حتماً
) {
    val complete: Boolean get() = pendingCodes.isEmpty()
}

object ReleaseGate {

    // ---------- التحقق من سجل واحد ----------

    private val DATE_RE = Regex("""^\d{4}-\d{2}-\d{2}$""")

    /** كل مخالفات سجل مراجعة — قائمة فارغة = سجل صالح */
    fun validate(review: LangReview): List<String> {
        val errors = mutableListOf<String>()
        if (LanguageCatalog.byCode(review.code) == null) {
            errors += "لغة غير معروفة في الكتالوج: ${review.code}"
        }
        if (review.stage.rank >= ReviewStage.NATIVE_REVIEW.rank && review.reviewer.isBlank()) {
            errors += "المراجعة الأصلية فأعلى تشترط اسماً للمراجع (${review.code})"
        }
        if (review.stage == ReviewStage.SIGNED_OFF) {
            if (review.reviewedAt.isBlank()) {
                errors += "الاعتماد الموقّع يشترط تاريخ المراجعة (${review.code})"
            } else if (!DATE_RE.matches(review.reviewedAt)) {
                errors += "التاريخ يجب أن يكون بصيغة yyyy-MM-dd (${review.code}): ${review.reviewedAt}"
            }
        }
        return errors
    }

    /** هل الانتقال من مرحلة إلى أخرى مسموح؟ — التراجع ممنوع دائماً */
    fun canAdvance(from: ReviewStage, to: ReviewStage): Boolean = to.rank >= from.rank

    // ---------- الدمج (upsert بحماية التراجع) ----------

    /**
     * يدمج سجلاً وارداً في القائمة القائمة: تحديث عند نفس الرمز،
     * إضافة عند رمز جديد، ورفض صريح عند تراجع المرحلة أو سجل غير صالح.
     * القائمة الناتجة مرتبة حتماً بالرمز.
     */
    fun merge(existing: List<LangReview>, incoming: LangReview): MergeResult {
        val invalid = validate(incoming)
        if (invalid.isNotEmpty()) return MergeResult.Rejected(invalid.first())
        val current = existing.firstOrNull { it.code == incoming.code }
        if (current != null && !canAdvance(current.stage, incoming.stage)) {
            return MergeResult.Rejected(
                "تراجع مرفوض للغة ${incoming.code}: " +
                    "من ${current.stage.name} إلى ${incoming.stage.name}",
            )
        }
        val next = (existing.filter { it.code != incoming.code } + incoming)
            .sortedBy { it.code }
        return MergeResult.Ok(next)
    }

    // ---------- ملخص الجاهزية ----------

    /** حساب جاهزية اللغات العشر — اللغات الناقصة من السجل تُعد مسودات آلية */
    fun readiness(reviews: List<LangReview>): L10nReadiness {
        val byCode = reviews.associateBy { it.code }
        val signed = LanguageCatalog.ALL.count { byCode[it.code]?.stage == ReviewStage.SIGNED_OFF }
        val pending = LanguageCatalog.ALL
            .filter { byCode[it.code]?.stage != ReviewStage.SIGNED_OFF }
            .map { it.code }
            .sorted()
        return L10nReadiness(signedOff = signed, total = LanguageCatalog.ALL.size, pendingCodes = pending)
    }
}

/** بوابة رقمية واحدة: عتبة «على الأقل» أو «على الأكثر» */
data class GateSpec(
    val id: String,
    val atLeast: Long? = null,
    val atMost: Long? = null,
) {
    init {
        require(atLeast != null || atMost != null) { "البوابة $id بلا عتبة" }
        require(atLeast == null || atMost == null) { "البوابة $id تجمع الاتجاهين" }
    }
}

/** حالة بوابة واحدة بعد التقييم */
sealed class GateStatus {
    abstract val id: String

    data class Pass(override val id: String, val actual: Long) : GateStatus()
    data class Fail(override val id: String, val actual: Long, val reason: String) : GateStatus()
    data class Missing(override val id: String) : GateStatus()
}

/** تقرير البوابات الكامل — الحُكم الإجمالي حتمي */
data class GateReport(
    val statuses: List<GateStatus>, // مرتبة بالمعرف حتماً
) {
    val passed: Boolean get() = statuses.all { it is GateStatus.Pass }

    /** أسباب الحجب — فارغة فقط عند الجاهزية الكاملة */
    val blockers: List<String>
        get() = statuses.mapNotNull {
            when (it) {
                is GateStatus.Fail -> it.reason
                is GateStatus.Missing -> "بوابة ${it.id} بلا قياس فعلي"
                is GateStatus.Pass -> null
            }
        }
}

/** محرك تقييم البوابات — فصل القياس عن الحكم */
object QualityGates {

    /**
     * تقييم العتبات ضد قياسات فعلية: أي بوابة بلا قياس = MISSING (حاجبة)،
     * والنتيجة مرتبة بالمعرف دائماً لنفس الدخل.
     */
    fun evaluate(specs: List<GateSpec>, actuals: Map<String, Long>): GateReport {
        val statuses = specs.sortedBy { it.id }.map { spec ->
            val actual = actuals[spec.id]
            when {
                actual == null -> GateStatus.Missing(spec.id)
                spec.atLeast != null && actual < spec.atLeast!! -> GateStatus.Fail(
                    spec.id, actual,
                    "بوابة ${spec.id}: المطلوب ≥ ${spec.atLeast} والفعلي $actual",
                )
                spec.atMost != null && actual > spec.atMost!! -> GateStatus.Fail(
                    spec.id, actual,
                    "بوابة ${spec.id}: المطلوب ≤ ${spec.atMost} والفعلي $actual",
                )
                else -> GateStatus.Pass(spec.id, actual)
            }
        }
        return GateReport(statuses)
    }
}

// ═════════════════ 3) مرمّز سجل المراجعة ═════════════════

object LangReviewCodec {

    /** ترميز JSON مضغوط — إخراج حتمي (مرتب بالرمز) */
    fun encode(reviews: List<LangReview>): String {
        fun esc(s: String): String = buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (c < ' ') append("\\u%04x".format(c.code))
                    else append(c)
            }
        }
        return reviews.sortedBy { it.code }.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { r ->
            """{"code":"${esc(r.code)}","stage":"${r.stage.name}",""" +
                """"reviewer":"${esc(r.reviewer)}","date":"${esc(r.reviewedAt)}",""" +
                """"notes":"${esc(r.notes)}"}"""
        }
    }

    /** فك متسامح: تالف → فارغ، مراحل/لغات مجهولة تُتخطّى، والقوس الختامي المفقود (كتابة جزئية) لا يمنع فك البادئة السليمة */
    fun decode(raw: String): List<LangReview> {
        val text = raw.trim()
        if (!text.startsWith("[")) return emptyList()
        val body = if (text.endsWith("]")) text.substring(1, text.length - 1) else text.substring(1)
        val out = mutableListOf<LangReview>()
        var i = 0
        while (i < body.length) {
            if (body[i] != '{') { i++; continue }
            var depth = 0
            var j = i
            var inStr = false
            while (j < body.length) {
                val c = body[j]
                if (c == '\\' && inStr) { j += 2; continue }
                if (c == '"') inStr = !inStr
                if (!inStr) {
                    if (c == '{') depth++
                    if (c == '}') { depth--; if (depth == 0) break }
                }
                j++
            }
            if (j >= body.length) break // مقطع تالف أخير — نُبقي ما فُسّر سابقاً
            val obj = body.substring(i + 1, j)
            parseObject(obj)?.let { out += it }
            i = j + 1
        }
        return out
    }

    private fun parseObject(obj: String): LangReview? {
        val fields = mutableMapOf<String, String>()
        var k = 0
        while (k < obj.length) {
            val keyStart = obj.indexOf('"', k)
            if (keyStart == -1) break
            val keyEnd = obj.indexOf('"', keyStart + 1)
            if (keyEnd == -1) break
            val key = obj.substring(keyStart + 1, keyEnd)
            val colon = obj.indexOf(':', keyEnd)
            if (colon == -1) break
            if (obj.getOrNull(colon + 1) != '"') {
                // قيمة غير نصية (توافق أمامي) — تجاوز حتى الفاصلة التالية
                k = obj.indexOf(',', colon).let { if (it == -1) obj.length else it + 1 }
                continue
            }
            val sb = StringBuilder()
            var m = colon + 2
            while (m < obj.length) {
                val c = obj[m]
                if (c == '\\' && m + 1 < obj.length) {
                    when (val n = obj[m + 1]) {
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = obj.substring(m + 2, (m + 6).coerceAtMost(obj.length))
                            hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            m += 4
                        }
                        else -> sb.append(n)
                    }
                    m += 2
                    continue
                }
                if (c == '"') break
                sb.append(c)
                m++
            }
            fields[key] = sb.toString()
            val comma = obj.indexOf(',', m)
            k = if (comma == -1) obj.length else comma + 1
        }
        val code = fields["code"] ?: return null
        val stage = fields["stage"]?.let { s ->
            ReviewStage.entries.firstOrNull { it.name == s }
        } ?: return null
        return LangReview(
            code = code,
            stage = stage,
            reviewer = fields["reviewer"].orEmpty(),
            reviewedAt = fields["date"].orEmpty(),
            notes = fields["notes"].orEmpty(),
        )
    }
}
