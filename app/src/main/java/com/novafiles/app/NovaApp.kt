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
import com.novafiles.app.core.vault2.AutoHidePolicy
import com.novafiles.app.core.vault2.Vault2Manager
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
            .addMigrations(NovaDb.MIGRATION_4_5, NovaDb.MIGRATION_5_6, NovaDb.MIGRATION_6_7)
            .fallbackToDestructiveMigration()
            .build()
    }

    val settings: SettingsStore by lazy { SettingsStore(ctx) }

    /** v0.33 — الوسوم الملوّنة والرفوف الذكية: مستودع الوسوم */
    val tags: com.novafiles.app.data.tags.TagsRepository by lazy {
        com.novafiles.app.data.tags.TagsRepository(db)
    }

    /** v0.32 — أزواج المزامنة المجدولة: مستودع الأزواج والجولات */
    val sync: com.novafiles.app.data.sync.SyncRepository by lazy {
        com.novafiles.app.data.sync.SyncRepository(ctx, db)
    }

    val vault: VaultManager by lazy { VaultManager(ctx, settings) }

    /** v0.15 — الخزنة 2.0: مجلدات مشفرة + استرجاع + تنكّر + سجل تسلل + مسح آمن */
    val vault2: Vault2Manager by lazy { Vault2Manager(ctx, settings) }

    /** v0.15 — سياسة الإخفاء التلقائي (30 ثانية بعد مغادرة التطبيق) */
    val autoHide = AutoHidePolicy()

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

    /** v0.16 — الحسابات السحابية: OAuth PKCE + توكنات مشفرة + مزوّدات موحدة */
    val cloudAccounts: com.novafiles.app.core.cloud.CloudAccountManager by lazy {
        com.novafiles.app.core.cloud.CloudAccountManager(ctx, db)
    }

    /** v0.16 — مزامنة الكاميرا: MediaStore + قيود Wi-Fi/شحن + دفترة exact-once */
    val cameraSync: com.novafiles.app.core.cloud.CameraSyncManager by lazy {
        com.novafiles.app.core.cloud.CameraSyncManager(ctx, db, cloudAccounts, ::cloudClientId)
    }

    /** v0.35 — قواعد الأتمتة: مستودع الحفظ + سجل التنفيذ + المحرك الخالص */
    val rulesRepo: com.novafiles.app.core.rules.RulesRepository by lazy {
        com.novafiles.app.core.rules.RulesRepository(ctx)
    }

    /** v0.41 — Nova Intelligence: المساعد المحلي الحتمي (نوايا/أدوات/تحقق) */
    val assistant: com.novafiles.app.core.ai.AssistantEngine by lazy {
        com.novafiles.app.core.ai.AssistantEngine(
            validator = com.novafiles.app.core.ai.PlanValidator(
                listOf("/storage/emulated/0", "/sdcard"),
            ),
        )
    }

    /** v0.41 — مخزن المساعد: سجل اختياري محلي (معطّل افتراضياً) */
    val assistantStore: com.novafiles.app.data.prefs.AssistantStore by lazy {
        com.novafiles.app.data.prefs.AssistantStore(ctx)
    }

    /** v0.41 — منفّذ أدوات المساعد فوق المحركات الحقيقية (فهرس/تحليل/مكررات/قواعد) */
    val assistantExecutor: com.novafiles.app.ui.assistant.AssistantExecutor by lazy {
        com.novafiles.app.ui.assistant.AssistantExecutor(db, rulesRepo)
    }

    /** v0.36 — إشارات مرجعية للمسارات: مجلدات العمل اليومية في الشجرة الجانبية */
    val bookmarks: com.novafiles.app.data.prefs.BookmarksStore by lazy {
        com.novafiles.app.data.prefs.BookmarksStore(ctx)
    }
    val ruleLog: com.novafiles.app.core.rules.RuleExecutionLog by lazy {
        com.novafiles.app.core.rules.RuleExecutionLog(200)
    }
    val ruleEngine: com.novafiles.app.core.rules.WatchRuleEngine by lazy {
        com.novafiles.app.core.rules.WatchRuleEngine(
            host = com.novafiles.app.core.rules.RuleActionHostImpl(ctx, db, cloudAccounts, ::cloudClientId),
            log = ruleLog,
        )
    }

    /** معرّف عميل OAuth لكل مزوّد — يضبطه المُطوّر عبر gradle.properties */
    fun cloudClientId(provider: String): String = when (provider) {
        "drive" -> com.novafiles.app.BuildConfig.DRIVE_CLIENT_ID
        "dropbox" -> com.novafiles.app.BuildConfig.DROPBOX_CLIENT_ID
        "onedrive" -> com.novafiles.app.BuildConfig.ONEDRIVE_CLIENT_ID
        else -> ""
    }

    /** v0.16 — جلسة تفويض OAuth قيد الدوران (بين شاشة الإضافة ونشاط العودة) */
    var pendingCloudAuth: com.novafiles.app.core.cloud.CloudAccountManager.PendingAuth? = null

    /** v0.16 — رابط عودة OAuth من المتصفح — تستهلكه شاشة الحسابات */
    var pendingOAuthRedirect: String? = null

    /** حمولة nova-direct:// قادمة من مسح QR — تُستهلك مرة واحدة في NovaRoot */
    var pendingDirectPayload: com.novafiles.app.core.wifip2p.DirectPayload? = null

    /** v0.17 — هدف نقرة ودجت (vault / ops / open:<path>) — تستهلكها NovaRoot */
    var pendingWidgetTarget: String? = null

    /** v0.17 — مسار قادم من «فتح باستخدام NovaFiles» (ACTION_VIEW) — تستهلكه NovaRoot */
    var pendingViewPath: String? = null
}

class NovaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // v0.41.1 — ترحيل كلمات مرور الخوادم من نص صريح إلى ختم Keystore:
        // يعمل مرة واحدة ثم يصبح بلا فعل؛ فشله غير قاتل ويُعاد المحاولة كل إقلاع.
        container.appScope.launch {
            runCatching { container.settings.migrateLegacyServerPasswords() }
        }
    }
}

/** وصول سريع لحاوية التبعيات من داخل Composables */
val Context.novaContainer: AppContainer
    get() = (applicationContext as NovaApp).container

/** v0.16 — معرّف عميل OAuth من أي سياق */
fun Context.cloudClientId(provider: String): String = novaContainer.cloudClientId(provider)
