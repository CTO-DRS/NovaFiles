package com.novafiles.app.core.a11y

/**
 * v0.17 — مُراجع الإتاحة (accessibility auditor).
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * ثلاث أدوات صرافة:
 *  1. [wcagContrast] نسبة التباين WCAG 2.1 + تصنيف AA/AAA للنص العادي والكبير
 *  2. [typeScale] مقياس نوع ديناميكي (dynamic type) بخطوات iOS-like قابلة للتحقق
 *  3. [touchTarget] التحقق من هدف اللمس الأدنى 48dp (إرشاد Material/a11y)
 */
object ContrastAuditor {

    /** نسبة التباين WCAG بين لونين ARGB — 1.0 (أسوأ) حتى 21.0 (أفضل) */
    fun wcagContrast(a: Int, b: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        fun luminance(c: Int): Double {
            val r = channel((c shr 16) and 0xFF)
            val g = channel((c shr 8) and 0xFF)
            val b = channel(c and 0xFF)
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }
        val l1 = luminance(a); val l2 = luminance(b)
        val hi = maxOf(l1, l2); val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** مستوى المطابقة لزوج (لون نص، لون خلفية) */
    fun level(foreground: Int, background: Int, largeText: Boolean = false): ContrastLevel {
        val r = wcagContrast(foreground, background)
        return when {
            largeText && r >= 3.0 && r < 4.5 -> ContrastLevel.AA
            largeText && r >= 4.5 -> ContrastLevel.AAA
            !largeText && r >= 4.5 && r < 7.0 -> ContrastLevel.AA
            !largeText && r >= 7.0 -> ContrastLevel.AAA
            else -> ContrastLevel.FAIL
        }
    }

    /** مراجعة زوج لوني بسيط: هل يصل AA على الأقل؟ */
    fun passesAA(foreground: Int, background: Int, largeText: Boolean = false): Boolean =
        level(foreground, background, largeText) != ContrastLevel.FAIL

    /** v0.37 — مراجعة AAA صارمة: هل يبلغ الزوج 7:1 (نص عادي) أو 4.5:1 (نص كبير)؟ */
    fun passesAAA(foreground: Int, background: Int, largeText: Boolean = false): Boolean =
        level(foreground, background, largeText) == ContrastLevel.AAA

    /**
     * v0.37 — تقرير عائلة اللون المميز: اللون على خلفيته وزر/نص عليه،
     * مع تصنيف كل زوج — تُستخدم لضمان أن الـ 12 لوحة تبقى فوق أرضية AA
     * وتوثيق موقعها من AAA.
     */
    fun auditAccentFamily(family: AccentPairSpec): List<A11yFinding> = listOf(
        A11yFinding("onAccent/accent", wcagContrast(family.onAccent, family.accent), level(family.onAccent, family.accent)),
        A11yFinding("accentDeep/white", wcagContrast(family.accentDeep, 0xFFFFFFFF.toInt()), level(family.accentDeep, 0xFFFFFFFF.toInt(), largeText = true)),
    )

    /**
     * مقياس النوع الديناميكي: 8 خطوات من XS حتى XXXL مضاعفة معامل iOS-like.
     * القيم sp — كل خطوة ≥ السابقة بفارق لا يقل عن 1sp (ضمان قابلية التمييز).
     */
    fun typeScale(baseSp: Float = 16f): List<TypeStep> {
        val multipliers = listOf(0.82f, 0.90f, 1.0f, 1.12f, 1.24f, 1.36f, 1.56f, 1.76f)
        val names = listOf("XS", "S", "M", "L", "XL", "XXL", "XXXL", "AX")
        return names.mapIndexed { i, name ->
            TypeStep(name, Math.round(baseSp * multipliers[i] * 10) / 10f)
        }
    }

    /** هدف اللمس الأدنى الموصى به dp (Material: 48×48، iOS: 44×44) */
    const val MIN_TOUCH_TARGET_DP = 48

    /** فحص هدف لمس: يقبل أبعاداً بالـ dp ويعيد النتيجة + التصحيح المقترح */
    fun touchTarget(widthDp: Int, heightDp: Int): TouchTargetResult {
        val ok = widthDp >= MIN_TOUCH_TARGET_DP && heightDp >= MIN_TOUCH_TARGET_DP
        return TouchTargetResult(
            ok,
            if (ok) widthDp else maxOf(widthDp, MIN_TOUCH_TARGET_DP),
            if (ok) heightDp else maxOf(heightDp, MIN_TOUCH_TARGET_DP),
        )
    }

    /**
     * مراجعة لوحة لونية كاملة: زوجان أساسيان (نص/خلفية، نص ثانوي/خلفية)
     * وaccent/خلفية للنصوص الكبيرة — يُستخدم في اختبارات جودة الثيمات.
     */
    fun auditPalette(
        label: Int, secondaryLabel: Int, background: Int,
    ): List<A11yFinding> = listOf(
        A11yFinding("label/background", wcagContrast(label, background), level(label, background)),
        A11yFinding("secondaryLabel/background", wcagContrast(secondaryLabel, background), level(secondaryLabel, background, largeText = true)),
    )
}

enum class ContrastLevel { FAIL, AA, AAA }

data class TypeStep(val name: String, val sp: Float)

data class TouchTargetResult(
    val ok: Boolean,
    val suggestedWidthDp: Int,
    val suggestedHeightDp: Int,
)

data class A11yFinding(val pair: String, val ratio: Double, val level: ContrastLevel)

/** v0.37 — زوج لوني لعائلة accent يُدقَّق: نص فوق اللون + نسخة نصية أعمق على أبيض */
data class AccentPairSpec(val accent: Int, val onAccent: Int, val accentDeep: Int)
