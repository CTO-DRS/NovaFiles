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
        captureWidgetTap(intent, container)
        captureViewIntent(intent, container)

        setContent {
            val theme by container.settings.themeMode
                .map { runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM) }
                .collectAsState(ThemeMode.SYSTEM)

            // v0.17 — الإعداد اللوني المختار (12 لوحة)
            val paletteId by container.settings.paletteId.collectAsState("nova")

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

            NovaTheme(themeMode = theme, paletteId = paletteId) {
                NovaRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureDirectPayload(intent, novaContainer)
        captureOAuthRedirect(intent, novaContainer)
        captureWidgetTap(intent, novaContainer)
        captureViewIntent(intent, novaContainer)
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

    /**
     * v0.17 — نقرة ودجت: نحفظ الهدف وتستهلكه NovaRoot بالتنقل المناسب.
     */
    private fun captureWidgetTap(intent: Intent?, container: AppContainer) {
        if (intent?.action != com.novafiles.app.widget.WidgetBridge.ACTION_WIDGET) return
        val target = intent.getStringExtra(com.novafiles.app.widget.WidgetBridge.EXTRA_TARGET) ?: return
        container.pendingWidgetTarget = target
    }

    /**
     * v0.17 — «فتح باستخدام NovaFiles»: مسار ملف قادم عبر ACTION_VIEW.
     * نحفظه وتستهلكه NovaRoot بفتح العارض المناسب حسب نوع الملف.
     */
    private fun captureViewIntent(intent: Intent?, container: AppContainer) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data: Uri = intent.data ?: return
        if (data.scheme == "nova-direct" || data.scheme == "com.novafiles.app") return
        // نمرر المسار كما هو — محتوى المحلي فقط (file:// أو content:// عبر المسار المباشر)
        container.pendingViewPath = data.toString()
    }
}
