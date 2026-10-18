package com.novafiles.app.ui.ios

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * رموز التصميم iOS الأصيلة (Cupertino Design Tokens).
 * الألوان مأخوذة من لوحة نظام iOS الرسمية (Light / Dark).
 */
data class IOSPalette(
    val accent: Color,          // systemBlue
    val groupedBg: Color,       // خلفية الشاشات المجمّعة
    val cardBg: Color,          // خلفية البطاقات/القوائم المجمّعة
    val label: Color,           // النص الأساسي
    val secondaryLabel: Color,  // النص الثانوي
    val tertiaryLabel: Color,   // النص الثالث (تلميحات)
    val separator: Color,       // خط الفصل الشعري Hairline
    val fill: Color,            // حقول البحث والخلفيات الرمادية
    val barBg: Color,           // خلفية الشريط العلوي
    val tabBarBg: Color,        // خلفية شريط التبويبات السفلي
    val destructive: Color,     // أحمر الحذف
    val green: Color,           // أخضر المفاتيح
    val orange: Color,          // برتقالي (المفضلة)
)

/** لوحة iOS الفاتحة */
val IOSLight = IOSPalette(
    accent = Color(0xFF007AFF),
    groupedBg = Color(0xFFF2F2F7),
    cardBg = Color(0xFFFFFFFF),
    label = Color(0xFF000000),
    secondaryLabel = Color(0xFF6C6C70),
    tertiaryLabel = Color(0xB33C3C43),
    separator = Color(0x4D3C3C43),
    fill = Color(0x1F787880),
    barBg = Color(0xFFF9F9F9),
    tabBarBg = Color(0xFFF8F8F8),
    destructive = Color(0xFFFF3B30),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
)

/** لوحة iOS الداكنة */
val IOSDark = IOSPalette(
    accent = Color(0xFF0A84FF),
    groupedBg = Color(0xFF000000),
    cardBg = Color(0xFF1C1C1E),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0xFF98989E),
    tertiaryLabel = Color(0x99EBEBF5),
    separator = Color(0x59545458),
    fill = Color(0x2E787880),
    barBg = Color(0xFF1B1B1D),
    tabBarBg = Color(0xFF1B1B1D),
    destructive = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
)

/** تُزوَّد من NovaTheme حسب وضع السمة (نظام/فاتح/داكن) */
val LocalIOSPalette = staticCompositionLocalOf { IOSLight }
