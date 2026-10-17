package com.novafiles.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.novafiles.app.data.prefs.ThemeMode

private val LightScheme = lightColorScheme(
    primary = NovaPrimary,
    onPrimary = NovaSurfaceLight,
    secondary = NovaSecondary,
    tertiary = NovaTertiary,
    error = NovaError,
    background = NovaBackgroundLight,
    surface = NovaSurfaceLight,
    surfaceVariant = NovaSurfaceVariantLight,
)

// لون نص داكن إضافي
private val Color_DarkOnPrimary = androidx.compose.ui.graphics.Color(0xFF2A2760)

private val DarkScheme = darkColorScheme(
    primary = NovaPrimaryDark,
    onPrimary = Color_DarkOnPrimary,
    secondary = NovaSecondary,
    tertiary = NovaTertiary,
    error = NovaError,
    background = NovaBackgroundDark,
    surface = NovaSurfaceDark,
    surfaceVariant = NovaSurfaceVariantDark,
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
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
