package com.novafiles.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.novafiles.app.core.theme.PaletteEngine
import com.novafiles.app.data.prefs.ThemeMode
import com.novafiles.app.ui.ios.IOSDark
import com.novafiles.app.ui.ios.IOSLight
import com.novafiles.app.ui.ios.LocalIOSPalette

/**
 * ثيم NovaFiles بلوحة iOS الرسمية — يحوّل ألوان كوبرتينو إلى
 * MaterialTheme بحيث تظهر كل العناصر المتبقية بمظهر iOS موحّد.
 *
 * v0.17 — الثيمات الموسّعة: اللوحة المحايدة (الرماديات) ثابتة، بينما
 * «عائلة اللون المميز» تتبدل حسب الإعداد المختار من PaletteEngine (12 لوحة).
 * الافتراضي nova يحافظ على الهوية التاريخية للتطبيق.
 */
private val LightScheme = lightColorScheme(
    primary = IOSLight.accent,
    onPrimary = Color.White,
    secondary = IOSLight.accent,
    tertiary = IOSLight.orange,
    error = IOSLight.destructive,
    background = IOSLight.groupedBg,
    onBackground = IOSLight.label,
    surface = IOSLight.cardBg,
    onSurface = IOSLight.label,
    surfaceVariant = IOSLight.fill,
    onSurfaceVariant = IOSLight.secondaryLabel,
    outline = IOSLight.separator,
)

private val DarkScheme = darkColorScheme(
    primary = IOSDark.accent,
    onPrimary = Color.White,
    secondary = IOSDark.accent,
    tertiary = IOSDark.orange,
    error = IOSDark.destructive,
    background = IOSDark.groupedBg,
    onBackground = IOSDark.label,
    surface = IOSDark.cardBg,
    onSurface = IOSDark.label,
    surfaceVariant = IOSDark.fill,
    onSurfaceVariant = IOSDark.secondaryLabel,
    outline = IOSDark.separator,
)

/** تركيب لوحة iOS من الإعداد اللوني — العناصر المحايدة تبقى، والعائلة تتغير */
internal fun iosPaletteFor(presetId: String?, dark: Boolean): com.novafiles.app.ui.ios.IOSPalette {
    val family = PaletteEngine.family(PaletteEngine.preset(presetId), dark)
    val base = if (dark) IOSDark else IOSLight
    return base.copy(
        accent = Color(family.accent),
        destructive = Color(family.destructive),
        green = Color(family.green),
        orange = Color(family.orange),
    )
}

@Composable
fun NovaTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    paletteId: String = "nova",
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val iosPalette = iosPaletteFor(paletteId, darkTheme)
    val colorScheme = if (darkTheme) {
        DarkScheme.copy(
            primary = iosPalette.accent,
            secondary = iosPalette.accent,
            tertiary = iosPalette.orange,
            error = iosPalette.destructive,
        )
    } else {
        LightScheme.copy(
            primary = iosPalette.accent,
            secondary = iosPalette.accent,
            tertiary = iosPalette.orange,
            error = iosPalette.destructive,
        )
    }

    CompositionLocalProvider(
        LocalIOSPalette provides iosPalette,
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
