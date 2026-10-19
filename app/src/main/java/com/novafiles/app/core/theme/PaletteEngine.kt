package com.novafiles.app.core.theme

/**
 * v0.17 — محرك الثيمات الموسّع.
 *
 * Kotlin خالص بلا تبعيات Android — يُختبر بالكامل على JVM.
 *
 * الفكرة: لوحة iOS المحايدة (الرماديات والخلفيات) تبقى ثابتة للحفاظ على الهوية،
 * بينما «عائلة اللون المميز» (accent + destructive + green + orange) تتبدل حسب
 * الإعداد المختار من 12 إعداداً جاهزاً — بنفس مبادئ Material You لكن بمخرجات
 * محكومة ومختبرة (لا ألوان عشوائية غير مناسبة للنص).
 *
 * كل إعداد يعرّف زوجين: الفاتح والداكن لكل لون من العائلة، مع تدرجات مشتقة
 * (حاوية/خلفية مميزة) تُحسب رياضياً من اللون الأساسي بتحويل HSL.
 */

/** إعداد لوني جاهز — يعرّف عائلة اللون المميز للوضعين الفاتح والداكن */
data class PalettePreset(
    val id: String,
    val accentLight: Int,
    val accentDark: Int,
    val greenLight: Int = 0xFF34C759.toInt(),
    val greenDark: Int = 0xFF30D158.toInt(),
    val orangeLight: Int = 0xFFFF9500.toInt(),
    val orangeDark: Int = 0xFFFF9F0A.toInt(),
    val destructiveLight: Int = 0xFFFF3B30.toInt(),
    val destructiveDark: Int = 0xFFFF453A.toInt(),
)

/** عائلة الألوان المميزة المشتقة — تُركَّب فوق اللوحة المحايدة الثابتة */
data class AccentFamily(
    val accent: Int,
    val accentSoft: Int,      // خلفية ملونة خفيفة (مزج ~14%)
    val accentDeep: Int,      // نسخة أعمق للنص فوق الخلفيات الفاتحة
    val onAccent: Int,        // لون النص فوق accent (أبيض/أسود حسب التباين)
    val green: Int,
    val orange: Int,
    val destructive: Int,
)

object PaletteEngine {

    /** الإعدادات الـ12 الجاهزة — NOVA هو الافتراضي التاريخي للتطبيق */
    val PRESETS: List<PalettePreset> = listOf(
        PalettePreset("nova", 0xFF5B57C2.toInt(), 0xFFBEC2FF.toInt()),
        PalettePreset("ios_blue", 0xFF007AFF.toInt(), 0xFF0A84FF.toInt()),
        PalettePreset("ocean", 0xFF0E7490.toInt(), 0xFF38BDF8.toInt()),
        PalettePreset("forest", 0xFF15803D.toInt(), 0xFF4ADE80.toInt()),
        PalettePreset("sunset", 0xFFEA580C.toInt(), 0xFFFB923C.toInt()),
        PalettePreset("rose", 0xFFE11D48.toInt(), 0xFFFB7185.toInt()),
        PalettePreset("graphite", 0xFF52525B.toInt(), 0xFFA1A1AA.toInt()),
        PalettePreset("amber", 0xFFB45309.toInt(), 0xFFFBBF24.toInt()),
        PalettePreset("teal", 0xFF0D9488.toInt(), 0xFF2DD4BF.toInt()),
        PalettePreset("indigo", 0xFF4338CA.toInt(), 0xFF818CF8.toInt()),
        PalettePreset("crimson", 0xFFB91C1C.toInt(), 0xFFF87171.toInt()),
        PalettePreset("mint", 0xFF047857.toInt(), 0xFF6EE7B7.toInt()),
    )

    private val byId = PRESETS.associateBy { it.id }

    fun preset(id: String?): PalettePreset = byId[id] ?: PRESETS.first()

    fun isKnown(id: String?): Boolean = id != null && byId.containsKey(id)

    // ---------- أدوات لونية خالصة ----------

    /** ARGB → (R,G,B) 0..255 */
    fun rgb(color: Int): Triple<Int, Int, Int> =
        Triple((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)

    fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r.coerceIn(0, 255)) shl 16) or ((g.coerceIn(0, 255)) shl 8) or b.coerceIn(0, 255)

    /** RGB → HSL (h 0..360، s وl 0..1) */
    fun toHsl(color: Int): Triple<Double, Double, Double> {
        val (r0, g0, b0) = rgb(color)
        val r = r0 / 255.0; val g = g0 / 255.0; val b = b0 / 255.0
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val l = (max + min) / 2.0
        if (max == min) return Triple(0.0, 0.0, l)
        val d = max - min
        val s = if (l > 0.5) d / (2.0 - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + if (g < b) 6.0 else 0.0)
            g -> (b - r) / d + 2.0
            else -> (r - g) / d + 4.0
        } * 60.0
        return Triple(h, s, l)
    }

    /** HSL → ARGB */
    fun fromHsl(h: Double, s: Double, l: Double): Int {
        if (s <= 0.0) {
            val v = (l * 255).toInt()
            return argb(v, v, v)
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2.0 * l - q
        fun hue2rgb(t0: Double): Double {
            var t = t0
            if (t < 0) t += 1.0
            if (t > 1) t -= 1.0
            return when {
                t < 1.0 / 6.0 -> p + (q - p) * 6.0 * t
                t < 1.0 / 2.0 -> q
                t < 2.0 / 3.0 -> p + (q - p) * (2.0 / 3.0 - t) * 6.0
                else -> p
            }
        }
        val t = h / 360.0
        return argb(
            (hue2rgb(t + 1.0 / 3.0) * 255.0).toInt().coerceIn(0, 255),
            (hue2rgb(t) * 255.0).toInt().coerceIn(0, 255),
            (hue2rgb(t - 1.0 / 3.0) * 255.0).toInt().coerceIn(0, 255),
        )
    }

    /**
     * نسبة التباين WCAG 2.1 بين لونين — أساس اختبارات جودة اللوحات.
     * الحد الأدنى AA للنص العادي 4.5، وللنص الكبير/الأيقونات 3.0.
     */
    fun contrastRatio(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun lin(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            val (r, g, b) = rgb(c)
            return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        }
        val l1 = lum(a); val l2 = lum(b)
        val hi = maxOf(l1, l2); val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** أفتح/أغمق بمقدار معين مع الحفاظ على التدرج والصبغة */
    fun adjustLightness(color: Int, delta: Double): Int {
        val (h, s, l) = toHsl(color)
        return fromHsl(h, s, (l + delta).coerceIn(0.0, 1.0))
    }

    /** اختيار لون النص المناسب فوق خلفية ملونة (أبيض أو أسود) حسب WCAG */
    fun onColor(background: Int): Int =
        if (contrastRatio(background, 0xFFFFFFFF.toInt()) >= 4.5) 0xFFFFFFFF.toInt() else 0xFF111111.toInt()

    /**
     * توليد عائلة اللون المميز من إعداد — مع حماية الحواف:
     * إن كان accent فاتحاً جداً في الوضع الفاتح نعمّقه قليلاً ليقرأ كنص.
     */
    fun family(preset: PalettePreset, dark: Boolean): AccentFamily {
        val accent = if (dark) preset.accentDark else preset.accentLight
        val green = if (dark) preset.greenDark else preset.greenLight
        val orange = if (dark) preset.orangeDark else preset.orangeLight
        val destructive = if (dark) preset.destructiveDark else preset.destructiveLight
        // خلفية ملونة خفيفة: مزج 14% من اللون مع خلفية البطاقة
        val soft = mixOver(accent, if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt(), 0.14)
        // نسخة أعمق للنص في الوضع الفاتح إن كان اللون فاتحاً جداً
        var deep = accent
        if (!dark && contrastRatio(accent, 0xFFFFFFFF.toInt()) < 3.0) {
            deep = adjustLightness(accent, -0.12)
        }
        return AccentFamily(
            accent = accent,
            accentSoft = soft,
            accentDeep = deep,
            onAccent = onColor(accent),
            green = green,
            orange = orange,
            destructive = destructive,
        )
    }

    /** مزج لون فوق خلفية بشفافية معينة (alpha 0..1) وإرجاع اللون المصمت الناتج */
    fun mixOver(fore: Int, back: Int, alpha: Double): Int {
        val (fr, fg, fb) = rgb(fore)
        val (br, bg, bb) = rgb(back)
        val a = alpha.coerceIn(0.0, 1.0)
        return argb(
            (fr * a + br * (1 - a)).toInt(),
            (fg * a + bg * (1 - a)).toInt(),
            (fb * a + bb * (1 - a)).toInt(),
        )
    }
}
