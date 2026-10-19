package com.novafiles.app

import android.app.Application
import android.content.Context
import com.novafiles.app.data.db.NovaDb
import com.novafiles.app.data.prefs.ClipboardState
import com.novafiles.app.data.prefs.PrefsKnownHostStore
import com.novafiles.app.data.prefs.SettingsStore
import com.novafiles.app.core.network.KnownHostStore
import com.novafiles.app.core.ops.OpNotifier
import com.novafiles.app.core.ops.OpQueue
import com.novafiles.app.core.wifip2p.WifiDirectManager
import com.novafiles.app.core.ops.OpDirs
import com.novafiles.app.core.vault.VaultManager
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * حاوية التبعيات البسيطة (Manual DI) — تُنشئ كائنات الطبقات المختلفة مرة واحدة.
 */
class AppContainer(appContext: Context) {
    private val ctx = appContext.applicationContext

    /** نطاق التطبيق — يعيش طوال عمر العملية (قائمة العمليات الخلفية) */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val db: NovaDb by lazy {
        Room.databaseBuilder(ctx, NovaDb::class.java, "nova.db")
            .fallbackToDestructiveMigration()
            .build()
    }

    val settings: SettingsStore by lazy { SettingsStore(ctx) }

    val vault: VaultManager by lazy { VaultManager(ctx, settings) }

    /** بصمات مفاتيح مضيفي SFTP (نمط الثقة بأول استخدام) */
    val knownHosts: KnownHostStore by lazy { PrefsKnownHostStore(ctx) }

    /** حافظة الملفات (نسخ/نقل) — حالة عالمية في التطبيق */
    val clipboard = ClipboardState()

    /** v0.12 — قائمة العمليات الخلفية: نسخ/نقل/حذف/ضغط/استخراج */
    val opsNotifier: OpNotifier by lazy { OpNotifier(ctx) }
    val ops: OpQueue by lazy {
        OpQueue(
            scope = appScope,
            trashDir = OpDirs.trashDir(ctx),
            listener = opsNotifier,
        )
    }

    /** v0.12 — مدير Wi-Fi Direct (اكتشاف + اقتران + استضافة FTP عبر P2P) */
    val direct: WifiDirectManager by lazy { WifiDirectManager(ctx, appScope) }

    /** حمولة nova-direct:// قادمة من مسح QR — تُستهلك مرة واحدة في NovaRoot */
    var pendingDirectPayload: com.novafiles.app.core.wifip2p.DirectPayload? = null
}

class NovaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** وصول سريع لحاوية التبعيات من داخل Composables */
val Context.novaContainer: AppContainer
    get() = (applicationContext as NovaApp).container
