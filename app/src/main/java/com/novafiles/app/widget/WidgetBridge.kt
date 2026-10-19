package com.novafiles.app.widget

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import com.novafiles.app.AppContainer
import com.novafiles.app.core.ops.OpStatus
import com.novafiles.app.core.widget.RecentFileEntry
import com.novafiles.app.core.widget.RecentFilesPayload
import com.novafiles.app.core.widget.StorageShortcutPayload
import com.novafiles.app.core.widget.TransferBrief
import com.novafiles.app.core.widget.TransferBriefState
import com.novafiles.app.core.widget.TransferStatusPayload
import com.novafiles.app.novaContainer

/**
 * v0.17 — جسر الودجات: يجمع البيانات من الحاوية إلى حزم العرض الصرفة.
 *
 * كل القراءات هنا آمنة على الخيط الرئيس (لا شبكة)، والثقيلة (قراءة الفهرس)
 * تُنفَّذ في كوروتين goAsync من المزوّد.
 */
object WidgetBridge {

    // أفعال النقر من الودجات — يستقبلها MainActivity ثم يوجّه NovaRoot
    const val ACTION_WIDGET = "com.novafiles.app.WIDGET"
    const val EXTRA_TARGET = "nova_widget_target"
    const val TARGET_VAULT = "vault"
    const val TARGET_TRANSFERS = "ops"
    const val TARGET_OPEN_PATH = "open:"

    /** الملفات الأخيرة من فهرس البحث (يستثني المجلدات) — أحدث 4 */
    suspend fun recentFiles(container: AppContainer): RecentFilesPayload =
        runCatching {
            RecentFilesPayload.of(
                container.db.fileIndexDao().recent(24)
                    .filter { !it.isDirectory }
                    .map { RecentFileEntry(it.name, it.path, it.size, it.modified, it.isDirectory) },
            )
        }.getOrDefault(RecentFilesPayload(emptyList()))

    /** ملخص الطابور الحالي */
    fun transferStatus(container: AppContainer): TransferStatusPayload =
        TransferStatusPayload.of(
            container.ops.ops.value.map { rec ->
                val state = when (rec.status) {
                    OpStatus.QUEUED -> TransferBriefState.QUEUED
                    OpStatus.RUNNING -> TransferBriefState.RUNNING
                    OpStatus.PAUSED -> TransferBriefState.PAUSED
                    OpStatus.DONE, OpStatus.CANCELLED -> TransferBriefState.DONE
                    OpStatus.FAILED -> TransferBriefState.FAILED
                }
                val pct = rec.progress?.let { p ->
                    if (p.bytesTotal > 0) ((p.bytesDone.toDouble() / p.bytesTotal) * 100).toInt() else 0
                } ?: if (state == TransferBriefState.DONE) 100 else 0
                TransferBrief(rec.title, state, pct.coerceIn(0, 100), 0)
            },
        )

    /** حالة الخزنة 2.0 — مهيأة أو لا (الفتح نفسه يستوجب PIN داخل التطبيق) */
    fun vaultState(container: AppContainer): Boolean = container.vault2.isInitialized()

    /** اختصار التخزين: الجذر الأساسي مع نسبة الاستخدام */
    fun storageShortcut(): StorageShortcutPayload {
        val root = Environment.getExternalStorageDirectory()
        val stat = runCatching { StatFs(root.absolutePath) }.getOrNull()
        val total = stat?.totalBytes ?: 0L
        val free = stat?.availableBytes ?: 0L
        val name = root.absolutePath.trimEnd('/').substringAfterLast('/')
        return StorageShortcutPayload(
            label = name.ifEmpty { root.absolutePath },
            targetPath = root.absolutePath,
            usedBytes = (total - free).coerceAtLeast(0),
            totalBytes = total,
        )
    }

    /** بناء Intent النقر الموحّد للودجات */
    fun tapIntent(context: Context, target: String): Intent =
        Intent(context, com.novafiles.app.MainActivity::class.java).apply {
            action = ACTION_WIDGET
            putExtra(EXTRA_TARGET, target)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
}
