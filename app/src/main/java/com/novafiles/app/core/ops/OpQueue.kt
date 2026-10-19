package com.novafiles.app.core.ops

import android.content.Context
import com.novafiles.app.core.archive.ArchiveEngine
import com.novafiles.app.core.archive.ZipArchive
import com.novafiles.app.core.archive.stripArchiveExt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v0.12 — قائمة العمليات الخلفية.
 *
 * Kotlin خالص + كوروتينات — يُختبر بالكامل على JVM عبر مجلدات مؤقتة.
 * ميزات: تنفيذ متسلسل FIFO (عامل واحد)، تقدّق بايت-ببايت لكل الأنواع،
 * إيقاف/استئناف/إلغاء، سياسات تعارض (إعادة تسمية/تخطي/استبدال) تُطبَّق على
 * كل الدفعة، ومستمع خارجي اختياري لتغذية الإشعارات في الطبقة الأندرويد.
 */

/** نوع العملية */
enum class OpKind { COPY, MOVE, TRASH, ZIP, EXTRACT }

/** حالة العملية */
enum class OpStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

/** سياسة التعارض عند وجود الهدف مسبقاً */
enum class ConflictPolicy { RENAME, SKIP, OVERWRITE }

/** تقدّق العملية */
data class OpProgress(
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
    val currentName: String = "",
)

/** سجل عملية في القائمة */
data class OpRecord(
    val id: String,
    val kind: OpKind,
    val title: String,
    val status: OpStatus = OpStatus.QUEUED,
    val progress: OpProgress? = null,
    val error: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
)

/** مواصفة عملية تُنفَّذ */
data class OpSpec(
    val kind: OpKind,
    val title: String,
    val sources: List<File>,
    /** مجلد الهدف (نسخ/نقل) أو ملف zip الهدف (ضغط) أو مجلد الاستخراج (اختياري) */
    val target: File? = null,
    val policy: ConflictPolicy = ConflictPolicy.RENAME,
)

/** مستمع تحديثات خارجي (إشعارات أندرويد) */
fun interface OpListener {
    fun onRecord(record: OpRecord)
}

/** مسارات ثابتة للعمليات — طبقة أندرويد فقط */
object OpDirs {
    /** مجلد سلة المحذوفات — نفس المسار المستخدم في المتصفح منذ v0.2 */
    fun trashDir(context: Context): File =
        File(android.os.Environment.getExternalStorageDirectory(), ".NovaFilesTrash")
}

/** استثناء داخلي للإلغاء التعاوني */
private class OpCancelled : Exception()

/**
 * محرك القائمة — مثيل واحد لكل تطبيق. عامل واحد يستهلك قناة FIFO.
 * @param scope نطاق الكوروتينات (يُمرَّر للاختبار عبر runTest)
 * @param trashDir مجلد سلة المحذوفات لعمليات TRASH
 * @param listener مستمع اختياري لكل تحديث سجل
 */
class OpQueue(
    private val scope: CoroutineScope,
    private val trashDir: File,
    private val listener: OpListener? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _ops = MutableStateFlow<List<OpRecord>>(emptyList())
    val ops: StateFlow<List<OpRecord>> = _ops.asStateFlow()

    private val channel = Channel<QueuedOp>(Channel.UNLIMITED)
    private val paused = MutableStateFlow<Set<String>>(emptySet())
    private val cancelled = mutableSetOf<String>()
    private var nextId = 1
    private var worker: Job? = null

    private class QueuedOp(val id: String, val spec: OpSpec)

    init {
        worker = scope.launch {
            for (q in channel) {
                if (q.id in cancelled) continue // أُلغي وهو في الطابور
                runOne(q.id, q.spec)
            }
        }
    }

    /** هل توجد عمليات لم تكتمل بعد؟ */
    val hasPending: Boolean
        get() = _ops.value.any {
            it.status == OpStatus.RUNNING || it.status == OpStatus.QUEUED || it.status == OpStatus.PAUSED
        }

    // ----- الإدخال -----

    /** إضافة عملية إلى القائمة — تُنفَّذ عند وفاء الدور */
    fun enqueue(spec: OpSpec): String {
        val id = "op-${nextId++}"
        update { it + OpRecord(id = id, kind = spec.kind, title = spec.title, progress = OpProgress()) }
        scope.launch { channel.send(QueuedOp(id, spec)) }
        notify(id)
        return id
    }

    // ----- التحكم -----

    fun pause(id: String) {
        paused.value = paused.value + id
        patch(id) {
            if (it.status == OpStatus.RUNNING || it.status == OpStatus.QUEUED) it.copy(status = OpStatus.PAUSED) else it
        }
    }

    fun resume(id: String) {
        paused.value = paused.value - id
        patch(id) {
            when {
                it.status != OpStatus.PAUSED -> it
                it.startedAt == null -> it.copy(status = OpStatus.QUEUED)
                else -> it.copy(status = OpStatus.RUNNING)
            }
        }
    }

    fun cancel(id: String) {
        cancelled += id
        paused.value = paused.value - id
        patch(id) {
            if (it.status in listOf(OpStatus.RUNNING, OpStatus.PAUSED, OpStatus.QUEUED)) {
                it.copy(status = OpStatus.CANCELLED, endedAt = System.currentTimeMillis())
            } else it
        }
    }

    /** حذف السجلات المنتهية من القائمة */
    fun clearFinished() {
        update { list -> list.filterNot { it.status in listOf(OpStatus.DONE, OpStatus.FAILED, OpStatus.CANCELLED) } }
    }

    // ----- التنفيذ -----

    private suspend fun runOne(id: String, spec: OpSpec) {
        patch(id) {
            it.copy(
                status = if (id in paused.value) OpStatus.PAUSED else OpStatus.RUNNING,
                startedAt = System.currentTimeMillis(),
            )
        }
        notify(id)
        try {
            withContext(ioDispatcher) {
                when (spec.kind) {
                    OpKind.COPY -> executeCopyMove(id, spec, move = false)
                    OpKind.MOVE -> executeCopyMove(id, spec, move = true)
                    OpKind.TRASH -> executeTrash(id, spec)
                    OpKind.ZIP -> executeZip(id, spec)
                    OpKind.EXTRACT -> executeExtract(id, spec)
                }
            }
            finish(id, OpStatus.DONE)
        } catch (e: OpCancelled) {
            finish(id, OpStatus.CANCELLED)
        } catch (e: Exception) {
            patch(id) {
                it.copy(
                    status = OpStatus.FAILED,
                    error = e.message ?: e.javaClass.simpleName,
                    endedAt = System.currentTimeMillis(),
                )
            }
            notify(id)
        }
    }

    private suspend fun executeCopyMove(id: String, spec: OpSpec, move: Boolean): Int = withContext(ioDispatcher) {
        val targetDir = spec.target ?: error("missing_target")
        targetDir.mkdirs()
        val total = totalBytes(spec.sources)
        var done = 0L
        var items = 0

        for (src in spec.sources) {
            checkpoint(id)
            val dstBase = File(targetDir, src.name)
            val dst = if (dstBase.exists()) applyPolicy(dstBase, spec.policy) ?: continue else dstBase
            copyRec(id, src, dst, spec.policy, total) { delta, current ->
                if (delta > 0) done += delta
                patch(id) { r ->
                    r.copy(progress = r.progress?.copy(bytesDone = done, bytesTotal = total, currentName = current))
                }
            }
            if (move) src.deleteRecursively()
            items++
        }
        items
    }

    private suspend fun executeTrash(id: String, spec: OpSpec): Int = withContext(ioDispatcher) {
        trashDir.mkdirs()
        var count = 0
        for (src in spec.sources) {
            checkpoint(id)
            val stamped = File(trashDir, "${System.currentTimeMillis()}_${src.name}")
            if (!src.renameTo(stamped)) {
                copyFlat(src, stamped)
                src.deleteRecursively()
            }
            count++
            patch(id) { it.copy(progress = it.progress?.copy(currentName = src.name)) }
        }
        count
    }

    private suspend fun executeZip(id: String, spec: OpSpec): Int = withContext(ioDispatcher) {
        val out = spec.target ?: error("missing_zip_target")
        out.parentFile?.mkdirs()
        ZipArchive.createZip(spec.sources, out) { done, total ->
            patch(id) { it.copy(progress = it.progress?.copy(bytesDone = done, bytesTotal = total)) }
        }
        spec.sources.size
    }

    private suspend fun executeExtract(id: String, spec: OpSpec): Int = withContext(ioDispatcher) {
        val archive = spec.sources.firstOrNull() ?: error("no_archive")
        val dest = spec.target ?: File(archive.parentFile, stripArchiveExt(archive.name).ifEmpty { "extracted" })
        ArchiveEngine.extractAll(archive, dest) { done, total ->
            patch(id) { it.copy(progress = it.progress?.copy(bytesDone = done, bytesTotal = total)) }
        }
        1
    }

    // ----- نسخ بتقدّق ودعم إيقاف/إلغاء تعاوني -----

    private suspend fun copyRec(
        id: String,
        src: File,
        dst: File,
        policy: ConflictPolicy,
        total: Long,
        onUpdate: (deltaBytes: Long, currentName: String) -> Unit,
    ) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child ->
                checkpoint(id)
                val childDst = File(dst, child.name)
                val resolved = if (childDst.exists()) applyPolicy(childDst, policy) ?: return@forEach else childDst
                copyRec(id, child, resolved, policy, total, onUpdate)
            }
        } else {
            onUpdate(0, src.name)
            dst.parentFile?.mkdirs()
            dst.outputStream().use { out ->
                src.inputStream().use { input ->
                    val buf = ByteArray(BUFFER)
                    var r = input.read(buf)
                    while (r >= 0) {
                        checkpoint(id)
                        out.write(buf, 0, r)
                        onUpdate(r.toLong(), src.name)
                        r = input.read(buf)
                    }
                }
            }
        }
    }

    /** نسخ مسطّح بدون تقدّق — مسار احتياطي للسلة */
    private suspend fun copyFlat(src: File, dst: File) {
        withContext(ioDispatcher) {
            if (src.isDirectory) {
                dst.mkdirs()
                src.listFiles()?.forEach { copyFlat(it, File(dst, it.name)) }
            } else {
                dst.outputStream().use { out ->
                    src.inputStream().use { input ->
                        val buf = ByteArray(BUFFER)
                        var r = input.read(buf)
                        while (r >= 0) { out.write(buf, 0, r); r = input.read(buf) }
                    }
                }
            }
        }
    }

    /** يعيد الهدف المعدَّل أو null إذا فرضت السياسة SKIP */
    private fun applyPolicy(dst: File, policy: ConflictPolicy): File? = when (policy) {
        ConflictPolicy.OVERWRITE -> dst
        ConflictPolicy.SKIP -> null
        ConflictPolicy.RENAME -> uniqueSibling(dst)
    }

    private suspend fun checkpoint(id: String) {
        if (id in cancelled) throw OpCancelled()
        while (id in paused.value && kotlinx.coroutines.currentCoroutineContext().isActive) {
            delay(PAUSE_POLL_MS)
        }
        if (id in cancelled) throw OpCancelled()
    }

    // ----- أدوات الحالة -----

    private fun finish(id: String, status: OpStatus) {
        patch(id) { if (it.status != OpStatus.CANCELLED) it.copy(status = status, endedAt = System.currentTimeMillis()) else it }
        notify(id)
    }

    private fun patch(id: String, transform: (OpRecord) -> OpRecord) {
        update { list -> list.map { if (it.id == id) transform(it) else it } }
        notify(id)
    }

    private fun update(transform: (List<OpRecord>) -> List<OpRecord>) {
        _ops.value = transform(_ops.value)
    }

    private fun notify(id: String) {
        val record = _ops.value.firstOrNull { it.id == id } ?: return
        listener?.onRecord(record)
    }

    companion object {
        private const val BUFFER = 128 * 1024
        private const val PAUSE_POLL_MS = 50L

        /** توليد اسم شقيق غير مستخدم: file.txt → file (1).txt */
        fun uniqueSibling(desired: File): File {
            var candidate = desired
            if (!candidate.exists()) return candidate
            val name = desired.name
            val base = name.substringBeforeLast('.', missingDelimiterValue = name)
            val ext = if (name.contains('.')) ".${name.substringAfterLast('.')}" else ""
            var i = 1
            while (candidate.exists()) {
                candidate = File(desired.parentFile, "$base ($i)$ext")
                i++
            }
            return candidate
        }

        fun totalBytes(files: List<File>): Long =
            files.sumOf { f ->
                if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
            }
    }
}
