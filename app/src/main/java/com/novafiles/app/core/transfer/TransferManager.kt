package com.novafiles.app.core.transfer

import com.novafiles.app.core.provider.ProviderCapability
import com.novafiles.app.core.provider.StorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * v0.13 — مدير النقل v2: الأساس الذي سيحمل كل تنقلات السحابة القادمة.
 *
 * ما يضاف عن قائمة عمليات v0.12:
 *  - تجمّع عمال متوازٍ (parallelism) بدل عامل واحد — سرعة حقيقية على الشبكات العالية
 *  - استئناف تنزيلات مقاطَعة عبر Range + مجلة .nfpart (حجم + ETag من spec)
 *  - سرعة لحظية لكل عنصر (نافذة انزلاقية) وتحدّد سماحية RateLimiter
 *  - قيود نوع الشبكة: Wi-Fi فقط افتراضياً — وسم WAITING_NETWORK واستيقاظ
 *    مدفوع بالأحداث عبر notifyNetworkChanged (بلا اقتراع)
 *  - واجهة قائمة انتظار: إيقاف/استئناف/إلغاء/إعادة لكل عنصر منفرداً
 *
 * خالص Kotlin — يُختبر كلياً على JVM عبر مزوّدين وهميين وزمن افتراضي.
 * الرفع: يبدأ من جديد عند الفشل (الخوادم لا تدعم استئناف PUT قياسياً) —
 * التنزيل هو المسار القابل للاستئناف الكامل.
 */

enum class TransferDirection { DOWNLOAD, UPLOAD }

enum class TransferState { QUEUED, WAITING_NETWORK, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

/** مواصفة نقل واحدة */
data class TransferSpec(
    val direction: TransferDirection,
    /** المسار البعيد الكامل (تنزيل) أو مجلد الهدف (رفع) */
    val remotePath: String,
    /** اسم العنصر البعيد — للرفع */
    val remoteName: String = "",
    /** الملف المحلي: هدف التنزيل أو مصدر الرفع */
    val local: File,
    /** الحجم الكلي المعروف مسبقاً — 0 يعني غير معروف (تقدم غير محدد في الواجهة) */
    val totalBytes: Long = 0L,
    /** ETag البعيد عند وقت الجدولة — يُبطِل الاستئناف إن تغير */
    val etag: String? = null,
)

/** لقطة حالة عنصر في القائمة */
data class TransferProgress(
    val id: Long,
    val direction: TransferDirection,
    val name: String,
    val state: TransferState,
    val bytesDone: Long = 0,
    val totalBytes: Long = 0,
    val speedBps: Long = 0,
    val error: String? = null,
)

/** منفّذ النقل الفعلي — الطبقة العليا تبنيه فوق StorageProvider (انظر TransferSources) */
fun interface TransferSource {
    /**
     * ينفذ النقل من إزاحة معينة (تنزيل) أو من الصفر (رفع).
     * onProgress تستقبل الدلتا المتنقلة بين الاستدعاءات. يعيد البايتات المنقولة كلياً.
     */
    suspend fun run(spec: TransferSpec, offset: Long, onProgress: (Long) -> Unit): Long
}

/** مصادر نقل قياسية فوق مزوّد — هذا الاستخدام الإنتاجي الوحيد للمدير */
object TransferSources {

    /** تنزيل عبر مزوّد: من جديد عبر readTo أو استئناف عبر readRangeTo على ملف الجزء */
    fun download(provider: StorageProvider) = TransferSource { spec, offset, onProgress ->
        val part = ResumeJournal.partFile(spec.local)
        if (offset > 0 && provider.can(ProviderCapability.RANGE_READ)) {
            FileOutputStream(part, true).use { out ->
                provider.readRangeTo(spec.remotePath, offset, out) { delta -> onProgress(delta) }
            }
        } else {
            provider.readTo(spec.remotePath, part) { delta -> onProgress(delta) }
        }
        part.length()
    }

    /** رفع عبر مزوّد — دائماً من الصفر (استئناف PUT غير قياسي) */
    fun upload(provider: StorageProvider) = TransferSource { spec, _, onProgress ->
        provider.writeFrom(spec.local, spec.remotePath) { delta -> onProgress(delta) }
        spec.local.length()
    }
}

class TransferManager(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** أقصى عدد عمليات نقل متزامنة */
    val parallelism: Int = 2,
    /** قيود الشبكة: Wi-Fi فقط افتراضياً — تُقيَّم عبر networkOnline */
    var wifiOnly: Boolean = true,
    /** فحص الشبكة المحقون — طبقة أندرويد تمرر ConnectivityManager */
    var networkOnline: suspend () -> Boolean = { true },
    /** حد السماحية لكل عنصر بالبايت/ثانية — 0 بلا حد */
    var perItemBytesPerSec: Long = 0,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val idSeq = AtomicLong(1)
    private val mutex = Mutex()
    private val items = LinkedHashMap<Long, Item>()

    /** إشارة استيقاظ انتظار الشبكة — CONFLATED يحفظ آخر حدث */
    private val netWake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow<List<TransferProgress>>(emptyList())

    /** مراقب القائمة للواجهة */
    val observe: StateFlow<List<TransferProgress>> = _state.asStateFlow()

    private class Item(
        val id: Long,
        val spec: TransferSpec,
        val source: TransferSource,
        var state: TransferState,
        var bytesDone: Long = 0,
        var total: Long = 0,
        var speedBps: Long = 0,
        var error: String? = null,
        var job: Job? = null,
        /** بوابة الإيقاف — العامل المتوقف ينتظرها */
        var gate: CompletableDeferred<Unit>? = null,
        var speedAcc: Long = 0,
        var speedSince: Long = 0,
    )

    // ═══════════════════════ واجهة القائمة ═══════════════════════

    /** يضيف عنصراً للقائمة ويعيد معرّفه */
    suspend fun enqueue(spec: TransferSpec, source: TransferSource): Long = mutex.withLock {
        val id = idSeq.getAndIncrement()
        items[id] = Item(id = id, spec = spec, source = source, state = TransferState.QUEUED, total = spec.totalBytes)
        publishLocked()
        kickLocked()
        id
    }

    suspend fun pause(id: Long) = mutex.withLock {
        items[id]?.let { it ->
            when (it.state) {
                TransferState.QUEUED -> it.state = TransferState.PAUSED
                TransferState.RUNNING -> {
                    it.state = TransferState.PAUSED
                    it.gate = CompletableDeferred()
                }
                else -> Unit
            }
        }
        publishLocked()
    }

    suspend fun resume(id: Long) = mutex.withLock {
        items[id]?.let { it ->
            if (it.state != TransferState.PAUSED) return@let
            if (it.job != null) {
                // العامل حي ومتوقف على البوابة — نقول له «واصل»
                it.state = TransferState.RUNNING
                it.gate?.complete(Unit)
                it.gate = null
            } else {
                // لم يبدأ بعد — أعد الطابور
                it.state = TransferState.QUEUED
                kickLocked()
            }
        }
        publishLocked()
    }

    suspend fun cancel(id: Long) = mutex.withLock {
        items[id]?.let {
            it.state = TransferState.CANCELLED
            it.gate?.cancel()
            it.gate = null
            it.job?.cancel()
            it.job = null
            it.speedBps = 0
            if (it.spec.direction == TransferDirection.DOWNLOAD) ResumeJournal.clear(it.spec.local)
        }
        publishLocked()
    }

    suspend fun retry(id: Long) = mutex.withLock {
        items[id]?.let {
            if (it.state == TransferState.FAILED || it.state == TransferState.CANCELLED) {
                it.state = TransferState.QUEUED
                it.error = null
                it.job = null
                kickLocked()
            }
        }
        publishLocked()
    }

    /** يمسح العناصر المنتهية من القائمة */
    suspend fun clearFinished() = mutex.withLock {
        items.entries.removeIf {
            it.value.state == TransferState.DONE || it.value.state == TransferState.CANCELLED
        }
        publishLocked()
    }

    /**
     * طبقة أندرويد تستدعيها عند تغير حالة الشبكة (ConnectivityManager callback) —
     * يستيقظ العمال المعلّقون على الشبكة بلا اقتراع.
     */
    suspend fun notifyNetworkChanged() = mutex.withLock {
        if (networkOnline()) {
            netWake.trySend(Unit)
            items.values.forEach { if (it.state == TransferState.WAITING_NETWORK) it.state = TransferState.QUEUED }
            kickLocked()
        }
        publishLocked()
    }

    // ═══════════════════════ المضخة والعمّال ═══════════════════════

    /** يشغّل عناصر الطابور حتى امتلاء التزامن — يُستدعى تحت mutex دائماً */
    private fun kickLocked() {
        // العدّ بالوظائف الحية لا بالحالة — الحالة تُضبط داخل العامل بشكل غير متزامن
        var running = items.values.count { it.job?.isActive == true }
        for (item in items.values) {
            if (running >= parallelism) break
            if (item.state != TransferState.QUEUED || item.job?.isActive == true) continue
            item.job = scope.launch { worker(item) }
            running++
        }
    }

    private suspend fun worker(item: Item) {
        try {
            // ═══ بوابة الشبكة ═══
            if (wifiOnly && !networkOnline()) {
                mutex.withLock {
                    if (item.state == TransferState.QUEUED) item.state = TransferState.WAITING_NETWORK
                    publishLocked()
                }
                while (wifiOnly && !networkOnline() && item.state == TransferState.WAITING_NETWORK) {
                    netWake.receive() // استيقاظ حدثي — بلا اقتراع
                }
                if (item.state != TransferState.WAITING_NETWORK && item.state != TransferState.QUEUED) return
            }

            // ═══ بدء التشغيل ═══
            mutex.withLock {
                if (item.state == TransferState.WAITING_NETWORK || item.state == TransferState.QUEUED)
                    item.state = TransferState.RUNNING
                if (item.state != TransferState.RUNNING) return
                publishLocked()
            }

            val spec = item.spec
            item.total = spec.totalBytes

            val limiter = RateLimiter(perItemBytesPerSec, nowMs)

            val startOffset: Long = if (spec.direction == TransferDirection.DOWNLOAD) {
                withContext(io) {
                    val offset = ResumeJournal.resumeOffset(spec.local, spec.totalBytes, spec.etag)
                    if (offset == 0L && spec.totalBytes > 0)
                        ResumeJournal.save(spec.local, ResumeJournal.State(spec.totalBytes, spec.etag, spec.remotePath))
                    offset
                }
            } else 0L

            /**
             * جسر التعليق: المصدر يحظر داخل كوروتين ابن ويدفع الدلتا عبر
             * قناة غير محدودة (trySend لا يعلّق)، بينما يستنزفها العامل
             * في سياقه المعلّق — فتعمل بوابة الإيقاف والحد والسرعة والإلغاء
             * بين القطع بلا توقيف النسخ الحاجب.
             */
            val finalBytes = kotlinx.coroutines.coroutineScope {
                val chunks = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val result = kotlinx.coroutines.CompletableDeferred<Long>()
                val runner = launch(io) {
                    try {
                        val r = item.source.run(spec, startOffset) { delta -> chunks.trySend(delta) }
                        chunks.close()
                        result.complete(r)
                    } catch (t: Throwable) {
                        chunks.close(t)
                        result.completeExceptionally(t)
                    }
                }
                var drained = 0L
                for (delta in chunks) {
                    onChunk(item, delta, limiter)
                    drained += delta
                }
                @Suppress("UNUSED_VARIABLE")
                val reported = result.await() // يرمي استثناء المصدر إن فشل
                runner.join()
                drained
            }

            // إتمام التنزيل: إعادة تسمية الجزء ذرياً + مسح المجلة
            if (spec.direction == TransferDirection.DOWNLOAD) {
                withContext(io) {
                    val part = ResumeJournal.partFile(spec.local)
                    if (part.exists() && !part.renameTo(spec.local)) {
                        part.delete()
                        throw IOException("rename failed: ${spec.local.name}")
                    }
                    ResumeJournal.clear(spec.local)
                }
            }

            mutex.withLock {
                if (item.state == TransferState.RUNNING || item.state == TransferState.PAUSED) {
                    item.state = TransferState.DONE
                    item.bytesDone = if (item.total > 0) item.total else finalBytes
                }
                publishLocked()
                kickLocked()
            }
        } catch (e: CancellationException) {
            mutex.withLock {
                if (item.state == TransferState.RUNNING || item.state == TransferState.PAUSED ||
                    item.state == TransferState.WAITING_NETWORK || item.state == TransferState.QUEUED
                ) item.state = TransferState.CANCELLED
                publishLocked()
                kickLocked()
            }
        } catch (e: Exception) {
            mutex.withLock {
                // الفشل يحفظ ملف الجزء — إعادة المحاولة تكمل من الإزاحة
                if (item.state == TransferState.RUNNING || item.state == TransferState.WAITING_NETWORK) {
                    item.state = TransferState.FAILED
                    item.error = e.message ?: e.javaClass.simpleName
                    item.speedBps = 0
                }
                publishLocked()
                kickLocked()
            }
        }
    }

    /** معالجة كل قطعة: فحص إلغاء + بوابة إيقاف + حد سماحية + تراكم + سرعة + نشر */
    private suspend fun onChunk(item: Item, delta: Long, limiter: RateLimiter) {
        // نقطة تعليق/إلغاء صريحة — بدلها لا يوصل الإلغاء أثناء نسخ غير محدود السرعة
        coroutineContext.ensureActive()
        item.gate?.let { gate ->
            gate.await()
            if (item.gate === gate) item.gate = null
        }
        limiter.acquire(delta)
        item.bytesDone += delta
        item.speedAcc += delta
        val now = nowMs()
        if (item.speedSince == 0L) item.speedSince = now
        val elapsed = now - item.speedSince
        if (elapsed >= SPEED_SAMPLE_MS) {
            item.speedBps = item.speedAcc * 1000 / elapsed.coerceAtLeast(1)
            item.speedAcc = 0
            item.speedSince = now
        }
        publishLocked()
    }

    // ═══════════════════════ النشر ═══════════════════════

    /**
     * يبني لقطة القائمة. يُستدعى أحياناً من عامل خارج mutex — لذا النشر
     * المتزامن محمي بمزامنة items، والقراء يرون لقطة منسوخة.
     */
    private fun publishLocked() = synchronized(items) {
        _state.value = items.values
            .sortedBy { it.id }
            .map {
                TransferProgress(
                    id = it.id,
                    direction = it.spec.direction,
                    name = it.spec.remoteName.ifEmpty { it.spec.local.name },
                    state = it.state,
                    bytesDone = it.bytesDone,
                    totalBytes = it.total,
                    speedBps = it.speedBps,
                    error = it.error,
                )
            }
    }

    private companion object {
        const val SPEED_SAMPLE_MS = 500L
    }
}
