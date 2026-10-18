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
import com.novafiles.app.data.prefs.ThemeMode
import com.novafiles.app.ui.ios.IOSDark
import com.novafiles.app.ui.ios.IOSLight
import com.novafiles.app.ui.ios.LocalIOSPalette

/**
 * ثيم NovaFiles بلوحة iOS الرسمية — يحوّل ألوان كوبرتينو إلى
 * MaterialTheme بحيث تظهر كل العناصر المتبقية بمظهر iOS موحّد.
 * ملاحظة: ألوان النظام الديناميكية معطّلة عمداً للحفاظ على الهوية.
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

@Composable
fun NovaTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkScheme else LightScheme

    CompositionLocalProvider(
        LocalIOSPalette provides if (darkTheme) IOSDark else IOSLight,
    ) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
