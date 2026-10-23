package com.novafiles.app.core.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.novafiles.app.MainActivity
import com.novafiles.app.NovaApp
import com.novafiles.app.R
import com.novafiles.app.core.cloud.CloudFsProvider
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v0.32.0 — طبقة أندرويد للمزامنة المجدولة:
 *  - [SyncScheduler]: جدولة دورية لكل زوج عبر WorkManager بقيود Wi-Fi/شحن
 *    (نمط الخارطة: نوافذ مرونة واقعية + زر «شغّل الآن» اليدوي دائماً متاح)
 *  - [SyncWorker]: جولة واحدة لزوج واحد — لقطات، خطة، تنفيذ، سجل حلقي في
 *    Room، أساس ثنائي الاتجاه، وإشعار نتيجة ملخص
 *  - [SyncNotifier]: إشعار نتيجة الجولة (أفضل جهد — يحترم صلاحية الإشعارات)
 *
 * العامل يشغّل زوجاً واحداً في كل مهمة (unique work باسم الزوج) — لا تداخل
 * بين الأزواج، وإلغاء زوج واحد لا يمس الآخرين.
 */
object SyncScheduler {

    private fun workerName(pairId: Long) = "nova-sync-pair-$pairId"
    private fun nowName(pairId: Long) = "nova-sync-now-$pairId"

    /** جدولة دورية لزوج — تستبدل أي جدولة سابقة له */
    fun schedulePeriodic(
        context: Context,
        pairId: Long,
        interval: SyncInterval,
        wifiOnly: Boolean,
        requireCharging: Boolean,
    ) {
        val millis = interval.millis ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(requireCharging)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(millis, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pairId))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workerName(pairId),
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** إلغاء الجدولة الدورية لزوج */
    fun cancelPeriodic(context: Context, pairId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workerName(pairId))
    }

    /** تشغيل فوري يدوي — «شغّل الآن» المضمون حتى مع الجدولة المعطلة */
    fun runNow(context: Context, pairId: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(SyncWorker.KEY_PAIR_ID to pairId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            nowName(pairId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

/** بيئة الجهاز — تُقيَّم ضدها قيود الزوج قبل أي جولة */
fun syncEnvironment(context: Context): PairRunEnvironment {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    val metered = caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    val charging = bm.isCharging
    return PairRunEnvironment(networkMetered = metered, charging = charging)
}

/** إشعارات نتيجة مزامنة الأزواج — قناة منفصلة هادئة */
object SyncNotifier {
    private const val CHANNEL_ID = "nova_sync"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sync_notif_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.sync_notif_channel_desc)
                setShowBadge(false)
            },
        )
    }

    /** إشعار نتيجة جولة — يتجاهل بهدوء إن كانت الإشعارات غير مسموحة */
    fun notifyResult(context: Context, pairName: String, report: PairRunReport) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = context.getString(R.string.sync_notif_title)
        val text = context.getString(
            when (report.status()) {
                SyncRunStatus.SUCCESS -> R.string.sync_notif_success
                SyncRunStatus.PARTIAL -> R.string.sync_notif_partial
                SyncRunStatus.FAILED -> R.string.sync_notif_failed
                SyncRunStatus.CANCELLED -> R.string.sync_notif_cancelled
                else -> R.string.sync_notif_success
            },
            pairName,
        )
        val detail = context.getString(
            R.string.sync_notif_detail,
            report.copied, report.updated, report.deleted, report.failed,
        )
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText("$text — $detail")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$detail"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(("sync-$pairName").hashCode(), notification) }
    }
}

/** عامل جولة زوج واحد */
class SyncWorker(
    private val appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_PAIR_ID = "pairId"
    }

    override suspend fun doWork(): WorkResult {
        val container = (appContext.applicationContext as NovaApp).container
        val repo = container.sync
        val pairId = inputData.getLong(KEY_PAIR_ID, -1L)
        if (pairId <= 0) return WorkResult.success()

        val pair = repo.pair(pairId) ?: return WorkResult.success() // حُذف الزوج
        if (!pair.enabled) {
            // زوج معطّل وصلته جدولة قديمة — نظّفها
            SyncScheduler.cancelPeriodic(appContext, pairId)
            return WorkResult.success()
        }

        // ═══ قيود التشغيل — المحجوب يُسجَّل BLOCKED لا FAILED ═══
        val constraints = PairConstraints(wifiOnly = pair.wifiOnly, requireCharging = pair.requireCharging)
        if (!constraints.satisfied(syncEnvironment(appContext))) {
            val run = repo.startRun(pairId)
            repo.finishRun(pairId, run, PairRunReport.EMPTY, baseline = null)
            return WorkResult.success() // الفترة القادمة تعيد المحاولة
        }

        // ═══ بناء الجانبين فوق تجريد المخازن ═══
        val source = runCatching {
            buildSide(pair.sourceType, pair.sourcePath, pair.sourceAccountId)
        }.getOrNull() ?: return fatalRun(repo, pairId)
        val dest = runCatching {
            buildSide(pair.destType, pair.destPath, pair.destAccountId)
        }.getOrNull() ?: return fatalRun(repo, pairId)

        source.use {
            dest.use {
                val baseline = SnapshotCodec.decode(pair.baselineJson)
                val run = repo.startRun(pairId)
                val runner = PairSyncRunner(source, dest)
                val outcome = runner.run(
                    direction = SyncDirection.fromName(pair.direction),
                    policy = ConflictPolicy.fromName(pair.conflictPolicy),
                    baseline = baseline,
                    verifyChecksums = pair.verifyChecksums,
                )
                repo.finishRun(pairId, run, outcome.report, outcome.baselineSnapshot)
                SyncNotifier.notifyResult(appContext, pair.name, outcome.report)
            }
        }
        return WorkResult.success()
    }

    /** جانب زوج من نوعه المخزن: local أو cloud فوق أي مزود */
    private suspend fun buildSide(type: String, path: String, accountId: Long): SyncSide {
        val sideType = com.novafiles.app.data.sync.SyncRepository.SideType.fromName(type)
        return when (sideType) {
            com.novafiles.app.data.sync.SyncRepository.SideType.LOCAL ->
                LocalSide(File(path))

            com.novafiles.app.data.sync.SyncRepository.SideType.CLOUD -> {
                val container = (appContext.applicationContext as NovaApp).container
                val account = container.db.cloudAccountsDao().byId(accountId)
                    ?: throw IllegalStateException("cloud account $accountId missing")
                val clientId = container.cloudClientId(account.provider)
                val provider = container.cloudAccounts.providerFor(account, clientId)
                val fs = (provider as CloudFsProvider).unwrap()
                CloudSide(fs, path, label = "${account.displayName}:$path")
            }
        }
    }

    /** فشل قاتل قبل التنفيذ — يُسجَّل ويُنهى بهدوء (الجدولة تكمل دورتها) */
    private suspend fun fatalRun(repo: com.novafiles.app.data.sync.SyncRepository, pairId: Long): WorkResult {
        val run = repo.startRun(pairId)
        repo.finishRun(pairId, run, PairRunReport(failed = 1), baseline = null)
        return WorkResult.success()
    }
}
