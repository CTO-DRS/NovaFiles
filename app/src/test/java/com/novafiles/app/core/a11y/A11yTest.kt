package com.novafiles.app.core.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مُراجع الإتاحة (v0.17) — JVM خالصة.
 */
class A11yTest {

    // ---------- WCAG ----------

    @Test
    fun `black on white is maximum contrast`() {
        assertEquals(21.0, ContrastAuditor.wcagContrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test
    fun `identical colors fail`() {
        assertEquals(1.0, ContrastAuditor.wcagContrast(0xFF777777.toInt(), 0xFF777777.toInt()), 0.001)
        assertEquals(ContrastLevel.FAIL, ContrastAuditor.level(0xFF777777.toInt(), 0xFF777777.toInt()))
    }

    @Test
    fun `ios light palette label passes AAA`() {
        // نص أساسي أسود على خلفية iOS الفاتحة
        val r = ContrastAuditor.level(0xFF000000.toInt(), 0xFFF2F2F7.toInt())
        assertEquals(ContrastLevel.AAA, r)
    }

    @Test
    fun `secondary label passes AA as large text on white`() {
        // 0x6C6C70 على أبيض ≈ 4.6 — AA عادي، لكن نضمنه للنص الكبير على الأقل
        val level = ContrastAuditor.level(0xFF6C6C70.toInt(), 0xFFFFFFFF.toInt())
        assertTrue(level == ContrastLevel.AA || level == ContrastLevel.AAA)
    }

    @Test
    fun `large text threshold is 3`() {
        // لون بتباين ~3.5 يجتاز AA للنص الكبير لكن يرسب للنص العادي
        val gray = 0xFF949494.toInt() // مقابل أبيض ≈ 3.4
        assertEquals(ContrastLevel.AA, ContrastAuditor.level(gray, 0xFFFFFFFF.toInt(), largeText = true))
        assertEquals(ContrastLevel.FAIL, ContrastAuditor.level(gray, 0xFFFFFFFF.toInt(), largeText = false))
    }

    @Test
    fun `audit palette of ios light has no failures`() {
        val findings = ContrastAuditor.auditPalette(
            label = 0xFF000000.toInt(),
            secondaryLabel = 0xFF6C6C70.toInt(),
            background = 0xFFF2F2F7.toInt(),
        )
        assertTrue(findings.none { it.level == ContrastLevel.FAIL })
    }

    // ---------- v0.37 — تدقيق عائلات الألوان الـ12 (وضعا فاتح/داكن) ----------

    @Test
    fun `onAccent passes AA over accent for all presets and both modes`() {
        val presets = com.novafiles.app.core.theme.PaletteEngine.PRESETS
        assertEquals(12, presets.size)
        for (preset in presets) for (dark in listOf(false, true)) {
            val family = com.novafiles.app.core.theme.PaletteEngine.family(preset, dark)
            val findings = ContrastAuditor.auditAccentFamily(
                com.novafiles.app.core.a11y.AccentPairSpec(family.accent, family.onAccent, family.accentDeep),
            )
            val onAccent = findings.first { it.pair == "onAccent/accent" }
            assertTrue(
                "${preset.id} dark=$dark: onAccent نسبة ${onAccent.ratio}",
                onAccent.level == ContrastLevel.AA || onAccent.level == ContrastLevel.AAA,
            )
        }
    }

    @Test
    fun `accentDeep keeps or improves contrast over white in light mode`() {
        val engine = com.novafiles.app.core.theme.PaletteEngine
        for (preset in engine.PRESETS) {
            val family = engine.family(preset, dark = false)
            val accentRatio = engine.contrastRatio(family.accent, 0xFFFFFFFF.toInt())
            val deepRatio = engine.contrastRatio(family.accentDeep, 0xFFFFFFFF.toInt())
            assertTrue(
                "${preset.id}: النسخة الأعمق (${deepRatio}) يجب ألا تقل عن الأصل (${accentRatio})",
                deepRatio >= accentRatio - 0.01,
            )
        }
    }

    @Test
    fun `black label on grouped background reaches AAA`() {
        // النص الأساسي الأسود على خلفية iOS المجمّعة — يبلغ AAA في الوضعين
        assertTrue(ContrastAuditor.passesAAA(0xFF000000.toInt(), 0xFFF2F2F7.toInt()))
        assertTrue(ContrastAuditor.passesAA(0xFF000000.toInt(), 0xFFF2F2F7.toInt()))
    }

    @Test
    fun `onAccent construction always picks the passing side`() {
        // محرك onColor يختار الأبيض أو الأسود بحيث يحقق الزوج 4.5+ حتماً
        val engine = com.novafiles.app.core.theme.PaletteEngine
        for (preset in engine.PRESETS) for (dark in listOf(false, true)) {
            val family = engine.family(preset, dark)
            val ratio = engine.contrastRatio(family.onAccent, family.accent)
            assertTrue("${preset.id} dark=$dark نسبة $ratio", ratio >= 4.5)
        }
    }

    // ---------- Dynamic type scale ----------

    @Test
    fun `type scale monotonic increasing`() {
        val steps = ContrastAuditor.typeScale(16f)
        assertEquals(8, steps.size)
        assertEquals("XS", steps.first().name)
        steps.zipWithNext().forEach { (a, b) ->
            assertTrue("خطوة ${b.name} يجب أن تتجاوز ${a.name}", b.sp > a.sp)
        }
    }

    @Test
    fun `type scale base step equals base size`() {
        val steps = ContrastAuditor.typeScale(17f)
        assertEquals(17f, steps[2].sp, 0.01f)
        assertEquals("M", steps[2].name)
    }

    // ---------- Touch targets ----------

    @Test
    fun `touch target pass and suggest`() {
        assertTrue(ContrastAuditor.touchTarget(48, 48).ok)
        assertFalse(ContrastAuditor.touchTarget(32, 32).ok)
        val r = ContrastAuditor.touchTarget(64, 30)
        assertFalse(r.ok)
        assertEquals(64, r.suggestedWidthDp)
        assertEquals(48, r.suggestedHeightDp)
    }

    @Test
    fun `min touch target constant matches material guidance`() {
        assertEquals(48, ContrastAuditor.MIN_TOUCH_TARGET_DP)
    }
}
