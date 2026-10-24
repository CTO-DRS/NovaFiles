package com.novafiles.app.core.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات v0.39 — محرك الترحيب — JVM خالصة.
 *
 * تغطي: التحقق البنيوي للخطوات الأربع، تقدّم الإنهاء الحتمي،
 * قرار العرض، والمرمّز المتسامح — إضافة إلى حصاد مفاتيح السلاسل
 * ضد strings.xml الحقيقية (بوابة نشطة في كل بناء).
 */
class OnboardingEngineTest {

    // ---------- التحقق البنيوي ----------

    @Test
    fun `structure is valid`() {
        assertTrue(OnboardingEngine.validate().isEmpty())
    }

    @Test
    fun `four steps in strict order`() {
        assertEquals(4, OnboardingEngine.STEPS.size)
        assertEquals(
            listOf("welcome", "storage", "vault", "smart"),
            OnboardingEngine.STEPS.map { it.id },
        )
        assertEquals(listOf(1, 2, 3, 4), OnboardingEngine.STEPS.map { it.order })
    }

    // ---------- التقدّم ----------

    @Test
    fun `mark done records steps`() {
        var st = OnboardingState()
        st = OnboardingEngine.markDone(st, "welcome")!!
        assertEquals(1, st.doneCount)
        assertEquals(25, OnboardingEngine.percent(st))
        st = OnboardingEngine.markDone(st, "storage")!!
        st = OnboardingEngine.markDone(st, "vault")!!
        st = OnboardingEngine.markDone(st, "smart")!!
        assertTrue(OnboardingEngine.isComplete(st))
    }

    @Test
    fun `unknown step id is rejected`() {
        assertNull(OnboardingEngine.markDone(OnboardingState(), "ghost"))
        assertNull(OnboardingEngine.markDone(OnboardingState(), ""))
    }

    @Test
    fun `percent and next step are deterministic`() {
        var st = OnboardingState()
        assertEquals(0, OnboardingEngine.percent(st))
        assertEquals("welcome", OnboardingEngine.nextStep(st)?.id)
        st = OnboardingEngine.markDone(st, "welcome")!!
        st = OnboardingEngine.markDone(st, "storage")!!
        assertEquals(50, OnboardingEngine.percent(st))
        assertEquals("vault", OnboardingEngine.nextStep(st)?.id)
        st = OnboardingEngine.markDone(st, "vault")!!
        st = OnboardingEngine.markDone(st, "smart")!!
        assertEquals(100, OnboardingEngine.percent(st))
        assertNull(OnboardingEngine.nextStep(st))
    }

    // ---------- قرار العرض ----------

    @Test
    fun `should show only when incomplete`() {
        assertTrue(OnboardingEngine.shouldShow(""))
        assertTrue(OnboardingEngine.shouldShow("garbage,junk"))
        var st = OnboardingState()
        for (id in listOf("welcome", "storage", "vault")) {
            st = OnboardingEngine.markDone(st, id)!!
        }
        assertTrue(OnboardingEngine.shouldShow(OnboardingEngine.serialize(st)))
        st = OnboardingEngine.markDone(st, "smart")!!
        assertFalse(OnboardingEngine.shouldShow(OnboardingEngine.serialize(st)))
    }

    // ---------- المرمّز ----------

    @Test
    fun `serialize is deterministic and ordered by display order`() {
        var st = OnboardingState()
        for (id in listOf("smart", "vault", "welcome", "storage")) {
            st = OnboardingEngine.markDone(st, id)!!
        }
        assertEquals("welcome,storage,vault,smart", OnboardingEngine.serialize(st))
    }

    @Test
    fun `round trip keeps done steps`() {
        var st = OnboardingState()
        st = OnboardingEngine.markDone(st, "welcome")!!
        st = OnboardingEngine.markDone(st, "smart")!!
        val decoded = OnboardingEngine.parse(OnboardingEngine.serialize(st))
        assertEquals(st, decoded)
    }

    @Test
    fun `parse is tolerant to corrupt and unknown ids`() {
        assertEquals(OnboardingState(), OnboardingEngine.parse(""))
        assertEquals(OnboardingState(), OnboardingEngine.parse("   "))
        // معرف مجهول من إصدار أحدث يُتخطّى والبقية تُفك
        val decoded = OnboardingEngine.parse("welcome,quantum_step,smart")
        assertEquals(setOf("welcome", "smart"), decoded.doneIds)
    }

    // ---------- بوابة السلاسل ضد strings.xml الحقيقي ----------

    private fun resDir(): java.io.File {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val d = dir ?: return@repeat
            for (rel in listOf("src/main/res", "app/src/main/res")) {
                val f = java.io.File(d, rel)
                if (f.isDirectory) return f
            }
            dir = d.parentFile
        }
        error("res غير موجود")
    }

    private val baseStrings: String by lazy {
        java.io.File(resDir(), "values/strings.xml").readText()
    }

    @Test
    fun `every step key exists in real base strings`() {
        val missing = mutableListOf<String>()
        for (s in OnboardingEngine.STEPS) {
            if (!baseStrings.contains("""name="${s.titleKey}"""")) missing += s.titleKey
            if (!baseStrings.contains("""name="${s.bodyKey}"""")) missing += s.bodyKey
        }
        assertTrue("مفاتيح مفقودة في strings.xml: $missing", missing.isEmpty())
    }
}
