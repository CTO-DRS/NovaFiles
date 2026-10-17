package com.novafiles.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

            NovaTheme(themeMode = theme) {
                NovaRoot()
            }
        }
    }
}
