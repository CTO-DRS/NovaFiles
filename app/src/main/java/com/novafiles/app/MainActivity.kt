package com.novafiles.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import com.novafiles.app.core.wifip2p.DirectPayloadCodec
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
        captureDirectPayload(intent, container)
        captureOAuthRedirect(intent, container)

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureDirectPayload(intent, novaContainer)
        captureOAuthRedirect(intent, novaContainer)
    }

    /**
     * v0.12 — مصافحة Wi-Fi Direct: مسح رمز nova-direct:// من كاميرا النظام
     * يحفظ الحمولة؛ NovaRoot تستقبلها وتنشئ اتصالاً وتفتح المتصفح البعيد.
     */
    private fun captureDirectPayload(intent: Intent?, container: AppContainer) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "nova-direct") return
        val payload = DirectPayloadCodec.decode(data.toString()) ?: return
        container.pendingDirectPayload = payload
    }

    /**
     * v0.16 — عودة OAuth: المتصفح يعيد التوجيه إلى com.novafiles.app:/oauth?...
     * نحفظ الرابط؛ شاشة الحسابات السحابية تستقبله وتكمل تبادل الرمز بـ PKCE.
     */
    private fun captureOAuthRedirect(intent: Intent?, container: AppContainer) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "com.novafiles.app" || !data.toString().contains("/oauth")) return
        container.pendingOAuthRedirect = data.toString()
    }
}
