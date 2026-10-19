package com.novafiles.app.core.ops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.novafiles.app.MainActivity
import com.novafiles.app.R

/**
 * v0.12 — مُشعِر إشعارات العمليات الخلفية (طبقة أندرويد).
 * يستقبل تحديثات السجلات من OpQueue ويُظهر إشعاراً واحداً بتقدّق
 * العملية الحالية مع زر إلغاء. لا يعيد أي شيء إلى القائمة.
 */
class OpNotifier(private val ctx: Context) : OpListener {

    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.ops_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = ctx.getString(R.string.ops_notif_channel_desc)
                setShowBadge(false)
            },
        )
    }

    /** يُستدعى من OpQueue عند كل تحديث — نعرض حالة العملية الجارية فقط */
    override fun onRecord(record: OpRecord) {
        if (record.status != OpStatus.RUNNING && record.status != OpStatus.PAUSED) {
            // انتهت العملية الجارية — إن لم يبق شيء نشط أخفِ الإشعار
            if (!record.status.isActiveStatus()) nm.cancel(NOTIF_ID)
            return
        }
        nm.notify(NOTIF_ID, build(record))
    }

    /** إشعار القائمة الفارغة/النشطة المُجمّع — تُستخدم من الخدمة */
    fun summary(activeCount: Int): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return baseBuilder()
            .setContentTitle(ctx.getString(R.string.ops_notif_title))
            .setContentText(ctx.getString(R.string.ops_active_count, activeCount))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun build(record: OpRecord): Notification {
        val cancel = PendingIntent.getService(
            ctx, record.id.hashCode(),
            Intent(ctx, OpService::class.java).apply { action = OpService.ACTION_CANCEL; putExtra(OpService.EXTRA_ID, record.id) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = baseBuilder()
            .setContentTitle(record.title)
            .setOngoing(true)
        val p = record.progress
        if (p != null && p.bytesTotal > 0) {
            val percent = ((p.bytesDone * 100) / p.bytesTotal).toInt().coerceIn(0, 100)
            b.setProgress(100, percent, false)
                .setContentText("$percent% — ${p.currentName}")
        } else {
            b.setProgress(0, 0, true)
                .setContentText(ctx.getString(R.string.op_running_generic))
        }
        b.addAction(0, ctx.getString(R.string.action_cancel), cancel)
        return b.build()
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setSilent(true)

    private fun OpStatus.isActiveStatus(): Boolean =
        this == OpStatus.RUNNING || this == OpStatus.QUEUED || this == OpStatus.PAUSED

    companion object {
        const val CHANNEL_ID = "nova_ops"
        const val NOTIF_ID = 4713
    }
}
