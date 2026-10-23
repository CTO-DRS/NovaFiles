package com.novafiles.app.core.release

/**
 * v0.38 — توطيد ما قبل الإطلاق: ميزانية الأداء بلا تراجع.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * بند الخارطة §5: «لا انحدار في بدء التشغيل ولا تمرير». هذا المحرك
 * يحوّل الوعد إلى قاعدة مُختبَرة:
 *
 *  1. **ميزانيات معلنة**: كل مقياس له اتجاه (على الأكثر/على الأقل) وعتبة.
 *  2. **تقييم قياس فعلي**: PASS/FAIL/MISSING لكل مقياس بأسباب عربية.
 *  3. **قاعدة لا-تراجع**: القياس الجديد يُقارن بالسابق مباشرة —
 *     مقياس «على الأكثر» لا يجوز أن يزيد، و«على الأقل» لا يجوز أن ينقص،
 *     والمقياس المفقود من الدفعة الجديدة انحدارٌ مشتبه به.
 *  4. **سجل تاريخي حلقي** (12 دفعة) بترميز JSON متسامح — نمط سجل v0.35.
 */

/** اتجاه العتبة */
enum class Direction { AT_MOST, AT_LEAST }

/** ميزانية مقياس واحد */
data class Budget(
    val metric: String,
    val direction: Direction,
    val limit: Long,
)

/** حالة مقياس واحد بعد التقييم */
sealed class MetricStatus {
    abstract val metric: String

    data class Pass(override val metric: String, val value: Long) : MetricStatus()
    data class Fail(override val metric: String, val value: Long, val reason: String) : MetricStatus()
    data class Missing(override val metric: String) : MetricStatus()
}

/** قياس دفعة واحدة — قيم المقاييس المتاحة في هذه الدفعة */
data class BuildMeasurement(
    val buildTag: String, // مثال: v0.38.0
    val values: Map<String, Long>,
)

object PerfBudget {

    /** الميزانيات المعلنة لإصدار ما قبل الإطلاق (v0.38) */
    val V038: List<Budget> = listOf(
        Budget("cold_start_ms", Direction.AT_MOST, 2000),
        Budget("apk_debug_mb", Direction.AT_MOST, 35),
        Budget("jvm_tests", Direction.AT_LEAST, 1648),
        Budget("strings_keys", Direction.AT_LEAST, 950),
        Budget("locales", Direction.AT_LEAST, 10),
    )

    // ---------- التقييم ضد العتبات ----------

    /** تقييم قياس دفعة ضد ميزانيات — النتيجة مرتبة بالمقياس حتماً */
    fun check(budgets: List<Budget>, measurement: BuildMeasurement): List<MetricStatus> =
        budgets.sortedBy { it.metric }.map { b ->
            val v = measurement.values[b.metric]
            when {
                v == null -> MetricStatus.Missing(b.metric)
                b.direction == Direction.AT_MOST && v > b.limit -> MetricStatus.Fail(
                    b.metric, v,
                    "المقياس ${b.metric} = $v يتجاوز السقف ${b.limit}",
                )
                b.direction == Direction.AT_LEAST && v < b.limit -> MetricStatus.Fail(
                    b.metric, v,
                    "المقياس ${b.metric} = $v جاء دون الأرضية ${b.limit}",
                )
                else -> MetricStatus.Pass(b.metric, v)
            }
        }

    // ---------- قاعدة لا-تراجع ----------

    /**
     * مقارنة دفعتين متتاليتين: يُعاد كل انحدار حتمي.
     *  - AT_MOST: الزيادة انحدار (حتى لو ما تزال تحت السقف)
     *  - AT_LEAST: النقصان انحدار (حتى لو ما يزال فوق الأرضية)
     *  - مقياس معلن غاب عن الدفعة الجديدة = انحدار مشتبه به
     *  - مقاييس غير معلنة تُتجاهل (حرية قياسات تجريبية)
     * النتيجة مرتبة بالمقياس حتماً.
     */
    fun regressions(budgets: List<Budget>, previous: BuildMeasurement?, current: BuildMeasurement): List<String> {
        if (previous == null) return emptyList()
        val out = mutableListOf<String>()
        for (b in budgets.sortedBy { it.metric }) {
            val prev = previous.values[b.metric]
            val curr = current.values[b.metric]
            when {
                prev == null -> Unit // لا مرجع سابق للحكم — البوابات تتكفل به
                curr == null ->
                    out += "المقياس ${b.metric} غاب عن الدفعة ${current.buildTag} (كان $prev)"
                b.direction == Direction.AT_MOST && curr > prev ->
                    out += "انحدار ${b.metric}: $prev ← $curr (على الأكثر)"
                b.direction == Direction.AT_LEAST && curr < prev ->
                    out += "انحدار ${b.metric}: $prev ← $curr (على الأقل)"
            }
        }
        return out
    }

    /** حكم مركّب: عتبات + لا-تراجع معاً — قائمة الحجب النهائية */
    fun gate(
        budgets: List<Budget>,
        previous: BuildMeasurement?,
        current: BuildMeasurement,
    ): List<String> {
        val thresholdBlockers = check(budgets, current).mapNotNull { st ->
            when (st) {
                is MetricStatus.Fail -> st.reason
                is MetricStatus.Missing -> "المقياس ${st.metric} بلا قياس في ${current.buildTag}"
                is MetricStatus.Pass -> null
            }
        }
        return (thresholdBlockers + regressions(budgets, previous, current)).sorted()
    }
}

// ═════════════════ السجل التاريخي الحلقي ═════════════════

object MeasurementCodec {

    const val CAP = 12

    /**
     * ترميز سجل الدفعات: مصفوفة كائنات، الأقدم أولاً.
     * الإخراج حتمي لنفس الدخل (ترتيب الاستيفاء للمفاتيح يُثبّت بالفرز).
     */
    fun encode(history: List<BuildMeasurement>): String {
        fun esc(s: String): String = buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        return history.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { m ->
            val body = m.values.toSortedMap().entries.joinToString(",") { (k, v) ->
                """"${esc(k)}":$v"""
            }
            """{"build":"${esc(m.buildTag)}","values":{$body}}"""
        }
    }

    /** فك متسامح: تالف → فارغ، قيم غير عددية تُتخطّى، والقوس الختامي المفقود (كتابة جزئية) لا يمنع فك البادئة السليمة */
    fun decode(raw: String): List<BuildMeasurement> {
        val text = raw.trim()
        if (!text.startsWith("[")) return emptyList()
        val body = (if (text.endsWith("]")) text.substring(1, text.length - 1) else text.substring(1)).trim()
        if (body.isEmpty()) return emptyList()
        val out = mutableListOf<BuildMeasurement>()
        var i = 0
        while (i < body.length) {
            val start = body.indexOf('{', i)
            if (start == -1) break
            var depth = 0
            var j = start
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
            if (j > body.length) break
            parseObject(body.substring(start, j))?.let { out += it }
            i = j
        }
        return out.takeLast(CAP)
    }

    /** إلحاق دفعة مع تطبيق السقف الحلقي (الأقدم يُحذف أولاً) */
    fun append(history: List<BuildMeasurement>, measurement: BuildMeasurement): List<BuildMeasurement> =
        (history + measurement).takeLast(CAP)

    private fun parseObject(obj: String): BuildMeasurement? {
        val buildMatch = Regex(""""build"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(obj) ?: return null
        val build = unescape(buildMatch.groupValues[1])
        val values = mutableMapOf<String, Long>()
        val pairRe = Regex(""""((?:[^"\\]|\\.)*)"\s*:\s*(-?\d+)""")
        for (m in pairRe.findAll(obj)) {
            val key = unescape(m.groupValues[1])
            if (key == "build") continue
            values[key] = m.groupValues[2].toLong()
        }
        return BuildMeasurement(buildTag = build, values = values)
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> append('\n')
                    'u' -> {
                        s.substring(i + 2, (i + 6).coerceAtMost(s.length))
                            .toIntOrNull(16)?.let { append(it.toChar()) }
                        i += 4
                    }
                    else -> append(n)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}
