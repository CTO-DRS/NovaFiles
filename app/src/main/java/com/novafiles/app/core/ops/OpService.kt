package com.novafiles.app.core.ops

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.novafiles.app.novaContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * v0.12 — خدمة أمامية تحافظ على استمرار العمليات الخلفية خارج الشاشة.
 *
 * المحرك (OpQueue) يعيش في AppContainer على مستوى التطبيق؛ هذه الخدمة فقط:
 *  — ترفع أولوية العملية أثناء تنفيذ العمليات (foreground notification).
 *  — تُحدّث الإشعار من تدفق القائمة.
 *  — تستقبل أوامر الإلغاء من إشعار النظام.
 * تتوقف تلقائياً عندما تفرغ القائمة.
 */
class OpService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = novaContainer
        startForeground(OpNotifier.NOTIF_ID, container.opsNotifier.summary(0))
        scope.launch {
            container.ops.ops.collect { list ->
                val active = list.count {
                    it.status == OpStatus.RUNNING || it.status == OpStatus.QUEUED || it.status == OpStatus.PAUSED
                }
                if (active == 0) {
                    stopSelf()
                } else {
                    val running = list.firstOrNull {
                        it.status == OpStatus.RUNNING || it.status == OpStatus.PAUSED
                    }
                    if (running != null) container.opsNotifier.onRecord(running)
                    else startForeground(OpNotifier.NOTIF_ID, container.opsNotifier.summary(active))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val id = intent.getStringExtra(EXTRA_ID)
                if (id != null) novaContainer.ops.cancel(id)
            }
        }
        // START_REDELIVER_INTENT يضمن إعادة تشغيل الخدمة بعد قتل النظام أثناء العمليات
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.novafiles.app.ops.ACTION_CANCEL"
        const val EXTRA_ID = "op_id"

        /** تشغيل الخدمة عندما تصبح القائمة نشطة (Idempotent) */
        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, OpService::class.java))
        }
    }
}
