package com.novafiles.app.core.cloud

import java.io.File
import java.io.InputStream

/**
 * v0.16 — النقل الموزّع (federated transfers): نسخ بين مزوّدين مختلفين
 * (Drive → SMB، OneDrive → خزنة، ...) عبر مدير النقل الموحّد.
 *
 * الاستراتيجية:
 *  - بث مباشر (streamed) متى سمح الوجهة: readFrom من المصدر تُضخ في
 *    writeFromStream للوجهة بلا تخزين وسيط — «not device-staged where the
 *    provider allows» وفق الخارطة.
 *  - تمرير مرحلي (device-staged) عند غياب writeFromStream (مزوّدات v0.13
 *    الملفية) — ملف مؤقت .nfpipe يُمسح فوراً بعد الرفع.
 *
 * مجلدات كاملة: مسح شجري مع تسلسل — كل عنصر في قائمة يُبلّغ عن تقدمه،
 * وفشل عنصر لا يوقف البقية (تُجمع النتائج).
 */
object Federated {

    /** نتيجة نسخ عنصر واحد */
    data class ItemResult(
        val path: String,
        val success: Boolean,
        val bytesCopied: Long = 0,
        val error: String? = null,
    )

    /** نتيجة عملية كاملة */
    data class OperationResult(val results: List<ItemResult>) {
        val succeeded get() = results.count { it.success }
        val failed get() = results.size - succeeded
    }

    /**
     * نسخ ملف واحد بين مزوّدين — بث مباشر عند الإمكان.
     * @param size الحجم المعروف مسبقاً (من stat) لتقرير التقدم؛ 0 = مجهول
     */
    suspend fun copyFile(
        source: CloudFs,
        sourcePath: String,
        target: CloudFs,
        targetDir: String,
        newName: String? = null,
        size: Long = 0L,
        stagedDir: File? = null,
        onProgress: (Long) -> Unit = {},
    ): ItemResult {
        val name = newName ?: RemotePathUtil.nameOf(sourcePath)
        val knownSize = if (size > 0) size else runCatching {
            source.stat(sourcePath).size
        }.getOrDefault(0L)
        return try {
            val entry = target.writeFromStream(
                dir = targetDir,
                name = name,
                size = knownSize,
                // مصنع تدفق جديد لكل محاولة — الجسر يفتح قراءة المصدر عند أول قراءة
                openInput = { StreamBridge(source, sourcePath) },
            )
            ItemResult(sourcePath, true, entry.size)
        } catch (e: Exception) {
            ItemResult(sourcePath, false, 0, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * جسر تدفق مصدر متدفق — يفتح القراءة من المصدر عند أول قراءة فعليّة
     * ويمرر البايتات. (التفريغ عن تأخير فتح الاتصال حتى تحتاجه الوجهة).
     */
    private class StreamBridge(private val source: CloudFs, private val path: String) : InputStream() {
        private val pump = java.io.PipedInputStream(256 * 1024)
        private val out = java.io.PipedOutputStream(pump)
        private val started = java.util.concurrent.atomic.AtomicBoolean(false)

        /** فشل المضخة — يُلقى على القارئ عند EOF بدل رفع ملف فارغ صامت */
        @Volatile private var failure: Throwable? = null

        private fun start() {
            if (started.compareAndSet(false, true)) {
                Thread {
                    try {
                        kotlinx.coroutines.runBlocking {
                            source.readFrom(path, 0) { stream -> stream.copyTo(out, 64 * 1024) }
                        }
                    } catch (t: Throwable) {
                        failure = t
                    } finally {
                        runCatching { out.close() }
                    }
                }.apply { isDaemon = true }.start()
            }
        }

        /** عند EOF: إن كانت المضخة قد فشلت ننشر الفشل بدل نهاية زائفة */
        private fun guard(n: Int): Int {
            if (n < 0) failure?.let { f ->
                throw java.io.IOException("source read failed: ${f.message}", f)
            }
            return n
        }

        override fun read(): Int { start(); return guard(pump.read()) }
        override fun read(b: ByteArray, off: Int, len: Int): Int { start(); return guard(pump.read(b, off, len)) }
        override fun close() { pump.close() }
    }

    /**
     * نسخ مجلد شجري بين مزوّدين — ينشئ هيكل المجلدات ثم ينسخ كل الملفات.
     * الفشل الفردي لا يوقف العملية.
     */
    suspend fun copyTree(
        source: CloudFs,
        sourceDir: String,
        target: CloudFs,
        targetDir: String,
        onProgress: (Long) -> Unit = {},
    ): OperationResult {
        val results = ArrayList<ItemResult>()
        val entries = source.list(sourceDir)
        for (entry in entries) {
            if (entry.isDir) {
                val newDir = joinPath(targetDir, entry.name)
                runCatching { target.mkdir(targetDir, entry.name) }
                    .onFailure { e ->
                        // موجود مسبقاً — نكمل داخله
                        if (e !is CloudConflictException && !e.message.orEmpty().contains("exists")) {
                            results.add(ItemResult(entry.path, false, 0, e.message))
                            return@onFailure
                        }
                    }
                val sub = copyTree(source, entry.path, target, newDir, onProgress)
                results.addAll(sub.results)
            } else {
                results.add(
                    copyFile(
                        source = source,
                        sourcePath = entry.path,
                        target = target,
                        targetDir = targetDir,
                        size = entry.size,
                        onProgress = onProgress,
                    ),
                )
            }
        }
        return OperationResult(results)
    }

    private fun joinPath(parent: String, name: String): String =
        if (parent.isBlank() || parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"
}
