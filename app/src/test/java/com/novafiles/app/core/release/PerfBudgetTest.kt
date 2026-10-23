package com.novafiles.app.core.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات v0.38 — ميزانية الأداء بلا تراجع — JVM خالصة.
 *
 * تغطي: التقييم ضد العتبات بالاتجاهين، قاعدة لا-تراجع (زيادة سقف/
 * نقصان أرضية/مقياس غائب)، الحكم المركّب، والسجل الحلقي المتسامح.
 */
class PerfBudgetTest {

    private val budgets = listOf(
        Budget("apk_debug_mb", Direction.AT_MOST, 35),
        Budget("cold_start_ms", Direction.AT_MOST, 2000),
        Budget("jvm_tests", Direction.AT_LEAST, 1648),
    )

    // ---------- التقييم ضد العتبات ----------

    @Test
    fun `check passes within limits and at the exact boundary`() {
        val st = PerfBudget.check(
            budgets,
            BuildMeasurement("v0.38.0", mapOf("apk_debug_mb" to 35L, "cold_start_ms" to 1800L, "jvm_tests" to 1648L)),
        )
        assertEquals(3, st.size)
        assertTrue(st.all { it is MetricStatus.Pass })
    }

    @Test
    fun `check fails above ceiling and below floor with arabic reasons`() {
        val st = PerfBudget.check(
            budgets,
            BuildMeasurement("v0.38.0", mapOf("apk_debug_mb" to 36L, "jvm_tests" to 1600L)),
        )
        val fails = st.filterIsInstance<MetricStatus.Fail>()
        assertEquals(2, fails.size)
        assertTrue(fails.any { it.metric == "apk_debug_mb" && it.reason.contains("السقف") })
        assertTrue(fails.any { it.metric == "jvm_tests" && it.reason.contains("الأرضية") })
    }

    @Test
    fun `check reports missing measurement as blocking`() {
        val st = PerfBudget.check(budgets, BuildMeasurement("v0.38.0", mapOf("jvm_tests" to 1700L)))
        val missing = st.filterIsInstance<MetricStatus.Missing>()
        assertEquals(setOf("apk_debug_mb", "cold_start_ms"), missing.map { it.metric }.toSet())
    }

    @Test
    fun `check output is deterministic regardless of budget order`() {
        val m = BuildMeasurement("v0.38.0", mapOf("apk_debug_mb" to 30L, "cold_start_ms" to 1500L, "jvm_tests" to 1650L))
        val a = PerfBudget.check(budgets, m)
        val b = PerfBudget.check(budgets.reversed(), m)
        assertEquals(a.map { it.metric }, b.map { it.metric })
        assertEquals(a.map { it.metric }, a.map { it.metric }.sorted())
    }

    // ---------- قاعدة لا-تراجع ----------

    @Test
    fun `regression when at-most metric increases even under ceiling`() {
        val prev = BuildMeasurement("v0.37.0", mapOf("cold_start_ms" to 1500L))
        val curr = BuildMeasurement("v0.38.0", mapOf("cold_start_ms" to 1600L)) // تحت السقف لكنه تراجع
        val regs = PerfBudget.regressions(budgets, prev, curr)
        assertEquals(1, regs.size)
        assertTrue(regs[0].contains("انحدار cold_start_ms"))
        assertTrue(regs[0].contains("على الأكثر"))
    }

    @Test
    fun `regression when at-least metric decreases even above floor`() {
        val prev = BuildMeasurement("v0.37.0", mapOf("jvm_tests" to 1700L))
        val curr = BuildMeasurement("v0.38.0", mapOf("jvm_tests" to 1690L)) // فوق الأرضية لكنه تراجع
        val regs = PerfBudget.regressions(budgets, prev, curr)
        assertEquals(1, regs.size)
        assertTrue(regs[0].contains("على الأقل"))
    }

    @Test
    fun `metric disappearing from current build is a suspected regression`() {
        val prev = BuildMeasurement("v0.37.0", mapOf("cold_start_ms" to 1500L, "jvm_tests" to 1700L))
        val curr = BuildMeasurement("v0.38.0", mapOf("jvm_tests" to 1700L))
        val regs = PerfBudget.regressions(budgets, prev, curr)
        assertEquals(1, regs.size)
        assertTrue(regs[0].contains("غاب عن الدفعة v0.38.0"))
    }

    @Test
    fun `no regressions on improvement or first measurement`() {
        val first = PerfBudget.regressions(budgets, null, BuildMeasurement("v0.38.0", mapOf("cold_start_ms" to 9999L)))
        assertTrue(first.isEmpty()) // لا مرجع سابق — البوابات تتكفل

        val improved = PerfBudget.regressions(
            budgets,
            BuildMeasurement("v0.37.0", mapOf("cold_start_ms" to 1600L, "jvm_tests" to 1650L)),
            BuildMeasurement("v0.38.0", mapOf("cold_start_ms" to 1400L, "jvm_tests" to 1700L)),
        )
        assertTrue(improved.isEmpty())
    }

    @Test
    fun `unbudgeted metrics are ignored by regression rule`() {
        val prev = BuildMeasurement("v0.37.0", mapOf("experimental_latency" to 10L))
        val curr = BuildMeasurement("v0.38.0", mapOf("experimental_latency" to 500L))
        assertTrue(PerfBudget.regressions(budgets, prev, curr).isEmpty())
    }

    @Test
    fun `compound gate merges threshold blockers and regressions sorted`() {
        val budgets2 = listOf(
            Budget("apk_debug_mb", Direction.AT_MOST, 35),
            Budget("jvm_tests", Direction.AT_LEAST, 1648),
        )
        val prev = BuildMeasurement("v0.37.0", mapOf("apk_debug_mb" to 30L, "jvm_tests" to 1700L))
        val curr = BuildMeasurement("v0.38.0", mapOf("apk_debug_mb" to 40L, "jvm_tests" to 1690L))
        val blockers = PerfBudget.gate(budgets2, prev, curr)
        // تجاوز سقف + انحدار سقف + انحدار أرضية = 3 حواجز مرتبة حتماً
        assertEquals(3, blockers.size)
        assertEquals(blockers, blockers.sorted())
    }

    // ---------- ميزانية v0.38 المعلنة ----------

    @Test
    fun `v038 declared budgets cover the roadmap guarantees`() {
        val byMetric = PerfBudget.V038.associateBy { it.metric }
        assertEquals(2000L, byMetric.getValue("cold_start_ms").limit)
        assertEquals(35L, byMetric.getValue("apk_debug_mb").limit)
        assertEquals(10L, byMetric.getValue("locales").limit)
        assertTrue(byMetric.getValue("jvm_tests").limit >= 1648)
    }

    // ---------- السجل الحلقي ----------

    @Test
    fun `codec round trips builds with arabic tags`() {
        val history = listOf(
            BuildMeasurement("v0.37.0", mapOf("jvm_tests" to 1615L, "apk_debug_mb" to 32L)),
            BuildMeasurement("v0.38.0-rc1", mapOf("jvm_tests" to 1648L, "apk_debug_mb" to 33L, "cold_start_ms" to 1750L)),
        )
        val decoded = MeasurementCodec.decode(MeasurementCodec.encode(history))
        assertEquals(history, decoded)
    }

    @Test
    fun `codec is deterministic with sorted keys`() {
        val a = MeasurementCodec.encode(listOf(BuildMeasurement("v1", mapOf("b" to 2L, "a" to 1L))))
        val b = MeasurementCodec.encode(listOf(BuildMeasurement("v1", mapOf("a" to 1L, "b" to 2L))))
        assertEquals(a, b)
    }

    @Test
    fun `codec tolerates corrupt input and non numeric values`() {
        assertEquals(emptyList<BuildMeasurement>(), MeasurementCodec.decode("garbage"))
        assertEquals(emptyList<BuildMeasurement>(), MeasurementCodec.decode(""))
        assertEquals(emptyList<BuildMeasurement>(), MeasurementCodec.decode("[]"))
        val mixed = """[{"build":"v1","values":{"a":5,"bad":"xx","b":7}}]"""
        val decoded = MeasurementCodec.decode(mixed)
        assertEquals(1, decoded.size)
        assertEquals(mapOf("a" to 5L, "b" to 7L), decoded[0].values)
    }

    @Test
    fun `append applies ring cap keeping newest`() {
        var h = emptyList<BuildMeasurement>()
        for (i in 1..15) h = MeasurementCodec.append(h, BuildMeasurement("b$i", mapOf("x" to i.toLong())))
        assertEquals(MeasurementCodec.CAP, h.size)
        assertEquals("b15", h.last().buildTag)
        assertEquals("b4", h.first().buildTag) // 15-12+1
        // جولة ترميز/فك تحفظ السجل المقتطع كاملاً (بعد السقف)
        assertEquals(12, MeasurementCodec.decode(MeasurementCodec.encode(h)).size)
        assertEquals(h, MeasurementCodec.decode(MeasurementCodec.encode(h)))
    }
}
