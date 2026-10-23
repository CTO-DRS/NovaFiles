package com.novafiles.app.core.rules

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.novafiles.app.MainActivity
import com.novafiles.app.R
import com.novafiles.app.novaContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** حالة المراقبة الحية كما تراها الواجهة */
data class RuleWatchState(
    val running: Boolean = false,
    /** عدد المجلدات المراقَبة فعلياً (قواعد مفعلة وبمجلد موجود) */
    val watching: Int = 0,
)

/**
 * v0.35.0 — خدمة أمامية تراقب مجلدات القواعد المفعلة عبر FileObserver:
 * ظهور ملف (CREATE / CLOSE_WRITE / MOVED_TO) → مسح مؤجَّل للمجلد → تنفيذ القاعدة.
 *
 * حمايات الحلقة اللانهائية:
 * - مهلة تهدئة 5 ثوانٍ لكل قاعدة بعد كل تنفيذ.
 * - مخرجات المحرك الذاتية (أرشيفات القاعدة وملفات checksums.*) مستثناة من
 *   المطابقة داخل المحرك نفسه.
 * - قواعد التجربة الجافة لا تُراقب هنا — تُجرَّب يدوياً من زر المعاينة.
 */
class RuleWatcherService : Service() {

    companion object {
        const val ACTION_START = "com.novafiles.app.rules.ACTION_START"
        const val ACTION_STOP = "com.novafiles.app.rules.ACTION_STOP"

        private const val CHANNEL_ID = "rules_watch"
        private const val NOTIF_ID = 4719

        /** مهلة تهدئة بعد تنفيذ قاعدة — تمنع تفاعل أحداثنا الذاتية */
        private const val COOLDOWN_MS = 5_000L
        /** تأجيل المسح بعد الحدث — يجمع الدفعات القصيرة (تنزيل متعدد مثلاً) */
        private const val DEBOUNCE_MS = 1_500L

        private val _state = MutableStateFlow(RuleWatchState())
        val state: StateFlow<RuleWatchState> = _state.asStateFlow()

        fun start(context: Context) {
            val i = Intent(context, RuleWatcherService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, RuleWatcherService::class.java).apply { action = ACTION_STOP },
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val observers = HashMap<String, FileObserver>()
    private val pending = HashMap<String, Runnable>()
    private val lastRun = HashMap<String, Long>()
    private var currentRules: List<WatchRule> = emptyList()
    private var rulesCollected = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.rules_notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.rules_notif_channel_desc)
                    setShowBadge(false)
                },
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                clearObservers()
                _state.value = RuleWatchState(running = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START, null -> {
                startAsForeground()
                collectRules()
                rebuildObservers()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.rules_notification_title))
            .setContentText(getString(R.string.rules_notification_text))
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    /** جمع قائمة القواعد حياً — يعيد بناء المراقبين عند أي تغيير */
    private fun collectRules() {
        if (rulesCollected) return
        rulesCollected = true
        novaContainer.appScope.launch {
            novaContainer.rulesRepo.rules.collect { list ->
                currentRules = list
                if (_state.value.running) rebuildObservers()
            }
        }
    }

    /** إعادة بناء المراقبين من القواعد الحالية — مراقب واحد للمجلد يكفي */
    private fun rebuildObservers() {
        clearObservers()
        var watching = 0
        for (rule in currentRules) {
            if (!rule.enabled || rule.dryRun) continue
            val dir = File(rule.watchFolder)
            if (!dir.isDirectory) continue
            if (observers.containsKey(rule.watchFolder)) continue
            watching++
            @Suppress("DEPRECATION")
            val obs = object : FileObserver(rule.watchFolder, CREATE or CLOSE_WRITE or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    scheduleRun(rule.id)
                }
            }
            observers[rule.watchFolder] = obs
            obs.startWatching()
        }
        _state.value = RuleWatchState(running = true, watching = watching)
    }

    /** حدث مراقبة: أجّل المسح (debounce) لكل قاعدة */
    private fun scheduleRun(ruleId: String) {
        pending[ruleId]?.let { handler.removeCallbacks(it) }
        val r = Runnable { runPending(ruleId) }
        pending[ruleId] = r
        handler.postDelayed(r, DEBOUNCE_MS)
    }

    private fun runPending(ruleId: String) {
        pending.remove(ruleId)
        val now = System.currentTimeMillis()
        if (now - (lastRun[ruleId] ?: 0) < COOLDOWN_MS) return
        val rule = currentRules.firstOrNull { it.id == ruleId } ?: return
        if (!rule.enabled || rule.dryRun) return
        val engine = novaContainer.ruleEngine
        lastRun[ruleId] = now
        novaContainer.appScope.launch {
            val folder = File(rule.watchFolder)
            if (!folder.isDirectory) return@launch
            engine.runRule(rule, engine.scanFolder(folder))
            lastRun[ruleId] = System.currentTimeMillis()
        }
    }

    private fun clearObservers() {
        for (o in observers.values) runCatching { o.stopWatching() }
        observers.clear()
        for (r in pending.values) handler.removeCallbacks(r)
        pending.clear()
    }

    override fun onDestroy() {
        clearObservers()
        super.onDestroy()
    }
}
