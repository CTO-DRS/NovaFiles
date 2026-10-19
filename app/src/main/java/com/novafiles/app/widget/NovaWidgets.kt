package com.novafiles.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.novafiles.app.R
import com.novafiles.app.core.widget.WidgetFormatters
import com.novafiles.app.novaContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.17 — ودجات سطح المكتب الأربع (RemoteViews محافظة).
 *
 * كما تنص خارطة الطريق: نستخدم تخطيطات بسيطة مضمونة بدل Glance لتقليل
 * المخاطر وحجم APK — كل الودجات تشترك في نمط بطاقة داكنة iOS-like.
 *
 *  - RecentFilesWidget: آخر 4 ملفات مفهرسة، النقر يفتح المسار.
 *  - TransferWidget: ملخص الطابور، النقر يفتح شاشة العمليات.
 *  - VaultWidget: حالة الخزنة، النقر يفتح تبويب الخزنة (يستوجب PIN داخله).
 *  - StorageWidget: نسبة استخدام الجذر، النقر يفتح المتصفح في الجذر.
 */

// ═══════════════ الملفات الأخيرة ═══════════════

class RecentFilesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val container = context.novaContainer
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val payload = WidgetBridge.recentFiles(container)
            withContext(Dispatchers.Main) {
                ids.forEach { id -> manager.updateAppWidget(id, buildViews(context, payload)) }
                pending.finish()
            }
        }
    }

    private fun buildViews(context: Context, payload: com.novafiles.app.core.widget.RecentFilesPayload): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_recent)
        val now = System.currentTimeMillis()
        val rows = listOf(
            Triple(R.id.row1_container, R.id.row1, R.id.row1_sub),
            Triple(R.id.row2_container, R.id.row2, R.id.row2_sub),
            Triple(R.id.row3_container, R.id.row3, R.id.row3_sub),
            Triple(R.id.row4_container, R.id.row4, R.id.row4_sub),
        )
        rows.forEachIndexed { idx, (containerId, titleId, subId) ->
            val e = payload.entries.getOrNull(idx)
            if (e == null) {
                v.setViewVisibility(containerId, android.view.View.GONE)
            } else {
                v.setViewVisibility(containerId, android.view.View.VISIBLE)
                v.setTextViewText(titleId, e.name)
                v.setTextViewText(
                    subId,
                    WidgetFormatters.recentSubtitle(e, now, "د", "س", "ي"),
                )
                v.setOnClickPendingIntent(
                    containerId,
                    PendingIntent.getActivity(
                        context, 100 + idx,
                        WidgetBridge.tapIntent(context, WidgetBridge.TARGET_OPEN_PATH + e.path),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
        return v
    }
}

// ═══════════════ حالة النقل ═══════════════

class TransferWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val container = context.novaContainer
        val payload = WidgetBridge.transferStatus(container)
        val v = RemoteViews(context.packageName, R.layout.widget_transfer)
        v.setTextViewText(
            R.id.widget_transfer_line,
            if (payload.active == 0 && payload.done == 0 && payload.failed == 0)
                context.getString(R.string.widget_transfer_idle)
            else
                context.getString(
                    R.string.widget_transfer_summary,
                    payload.active, payload.done, payload.failed,
                ),
        )
        v.setTextViewText(R.id.widget_transfer_current, payload.currentName ?: "—")
        v.setTextViewText(R.id.widget_transfer_speed, WidgetFormatters.speedLabel(payload.speedBytesPerSec))
        v.setTextViewText(R.id.widget_transfer_percent, "${payload.totalPercent}%")
        v.setOnClickPendingIntent(
            R.id.widget_transfer_root,
            PendingIntent.getActivity(
                context, 200,
                WidgetBridge.tapIntent(context, WidgetBridge.TARGET_TRANSFERS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        ids.forEach { id -> manager.updateAppWidget(id, v) }
    }
}

// ═══════════════ مفتاح الخزنة ═══════════════

class VaultWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val container = context.novaContainer
        val initialized = WidgetBridge.vaultState(container)
        val v = RemoteViews(context.packageName, R.layout.widget_vault)
        v.setTextViewText(
            R.id.widget_vault_status,
            context.getString(
                if (initialized) R.string.widget_vault_ready else R.string.widget_vault_not_setup,
            ),
        )
        v.setTextViewText(R.id.widget_vault_hint, context.getString(R.string.widget_vault_hint))
        v.setOnClickPendingIntent(
            R.id.widget_vault_root,
            PendingIntent.getActivity(
                context, 300,
                WidgetBridge.tapIntent(context, WidgetBridge.TARGET_VAULT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        ids.forEach { id -> manager.updateAppWidget(id, v) }
    }
}

// ═══════════════ اختصار التخزين ═══════════════

class StorageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val s = WidgetBridge.storageShortcut()
        val v = RemoteViews(context.packageName, R.layout.widget_storage)
        v.setTextViewText(R.id.widget_storage_label, s.label)
        v.setTextViewText(R.id.widget_storage_percent, "${s.usagePercent}%")
        v.setTextViewText(
            R.id.widget_storage_detail,
            "${WidgetFormatters.sizeLabel(s.usedBytes)} / ${WidgetFormatters.sizeLabel(s.totalBytes)}",
        )
        v.setInt(R.id.widget_storage_bar, "setMax", 100)
        v.setInt(R.id.widget_storage_bar, "setProgress", s.usagePercent)
        v.setOnClickPendingIntent(
            R.id.widget_storage_root,
            PendingIntent.getActivity(
                context, 400,
                WidgetBridge.tapIntent(context, WidgetBridge.TARGET_OPEN_PATH + s.targetPath),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        ids.forEach { id -> manager.updateAppWidget(id, v) }
    }
}

/** تحديث كل الودجات دفعة واحدة — يستدعيه التطبيق عند تغيّر البيانات */
fun updateAllWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    listOf(
        RecentFilesWidget::class.java, TransferWidget::class.java,
        VaultWidget::class.java, StorageWidget::class.java,
    ).forEach { cls ->
        val ids = manager.getAppWidgetIds(ComponentName(context, cls))
        if (ids.isNotEmpty()) {
            context.sendBroadcast(
                android.content.Intent(context, cls).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
