package com.novafiles.app

import android.app.Application
import android.content.Context
import com.novafiles.app.data.db.NovaDb
import com.novafiles.app.data.prefs.ClipboardState
import com.novafiles.app.data.prefs.PrefsKnownHostStore
import com.novafiles.app.data.prefs.SettingsStore
import com.novafiles.app.core.network.KnownHostStore
import com.novafiles.app.core.vault.VaultManager
import androidx.room.Room

/**
 * حاوية التبعيات البسيطة (Manual DI) — تُنشئ كائنات الطبقات المختلفة مرة واحدة.
 */
class AppContainer(appContext: Context) {
    private val ctx = appContext.applicationContext

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
