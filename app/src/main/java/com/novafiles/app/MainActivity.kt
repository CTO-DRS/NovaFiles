package com.novafiles.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.novafiles.app.data.prefs.ThemeMode
import com.novafiles.app.ui.nav.NovaRoot
import com.novafiles.app.ui.theme.NovaTheme
import kotlinx.coroutines.flow.map

/**
 * النشاط الوحيد — يستضيف واجهة Compose كاملة.
 * AppCompatActivity مطلوب لدعم تبديل اللغة داخل التطبيق والقفل الحيوي.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = novaContainer

        setContent {
            val theme by container.settings.themeMode
                .map { runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM) }
                .collectAsState(ThemeMode.SYSTEM)

            val darkTheme = when (theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // تلوين أيقونات شريط الحالة مثل iOS: داكنة في الوضع الفاتح والعكس
            val view = LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val window = (view.context as Activity).window
                    WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                    WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
                }
            }

            NovaTheme(themeMode = theme) {
                NovaRoot()
            }
        }
    }
}
