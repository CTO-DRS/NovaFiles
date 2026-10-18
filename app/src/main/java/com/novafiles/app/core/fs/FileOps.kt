package com.novafiles.app.core.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * محرك عمليات الملفات: نسخ، نقل، حذف إلى السلة، إعادة تسمية.
 * كل عمليات الإدخال/الإخراج تعمل على Dispatchers.IO مع تتبع تقدم مبسّط.
 */
object FileOps {

    data class Progress(val bytesDone: Long, val bytesTotal: Long, val currentName: String)

    private fun totalBytes(files: List<File>): Long =
        files.sumOf { if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } else it.length() }

    /**
     * يمنع نسخ/نقل مجلد إلى نفسه أو إلى داخل أحد مجلداته الفرعية.
     * بدونه يسقط المسار الاحتياطي (نسخ ثم حذف) في عودية لا نهائية تلتهم القرص.
     */
    private fun validateNotSelfTarget(sources: List<File>, targetDir: File) {
        val target = targetDir.canonicalFile.path
        for (src in sources) {
            val s = src.canonicalFile
            if (!s.isDirectory) continue
            val sp = s.path
            require(target != sp && !target.startsWith(sp + File.separator)) {
                "cannot copy/move directory \"${s.name}\" into itself or a subdirectory of itself"
            }
        }
    }

    /** نسخ قائمة عناصر إلى مجلد هدف مع تقرير تقدم */
    suspend fun copyAll(sources: List<File>, targetDir: File): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            validateNotSelfTarget(sources, targetDir)
            var done = 0L
            for (src in sources) {
                copyRec(src, uniqueChild(targetDir, src.name)) { n -> done += n }
            }
            sources.size
        }
    }

    /** نقل = نسخ ثم حذف المصدر (مع مسار سريع عبر renameTo داخل القرص نفسه) */
    suspend fun moveAll(sources: List<File>, targetDir: File): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            validateNotSelfTarget(sources, targetDir)
            for (src in sources) {
                val dst = uniqueChild(targetDir, src.name)
                if (!src.renameTo(dst)) {
                    copyRec(src, dst) { /* progress ignored in fallback */ }
                    src.deleteRecursively()
                }
            }
            sources.size
        }
    }

    /** سلة المحذوفات: نقل إلى مجلد مخفي مع طابع زمني لتفادي تعارض الأسماء */
    suspend fun moveToTrash(sources: List<File>, trashDir: File): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            trashDir.mkdirs()
            for (src in sources) {
                val stamped = File(trashDir, "${System.currentTimeMillis()}_${src.name}")
                if (!src.renameTo(stamped)) {
                    copyRec(src, stamped) {}
                    src.deleteRecursively()
                }
            }
            sources.size
        }
    }

    fun rename(src: File, newName: String): Result<File> = runCatching {
        val target = File(src.parentFile, newName)
        require(!target.exists()) { "name exists" }
        check(src.renameTo(target)) { "rename failed" }
        target
    }

    fun newFolder(parent: File, name: String): Result<File> = runCatching {
        val dir = File(parent, name)
        check(dir.mkdirs() || dir.isDirectory) { "mkdir failed" }
        dir
    }

    /** توليد اسم غير مُستخدم: file.txt → file (1).txt */
    fun uniqueChild(dir: File, desired: String): File {
        var candidate = File(dir, desired)
        if (!candidate.exists()) return candidate
        val base = desired.substringBeforeLast('.', missingDelimiterValue = desired)
        val ext = if (desired.contains('.')) ".${desired.substringAfterLast('.')}" else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    private fun copyRec(src: File, dst: File, onBytes: (Long) -> Unit) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyRec(it, File(dst, it.name), onBytes) }
        } else {
            dst.outputStream().use { out ->
                src.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var r = input.read(buf)
                    while (r >= 0) {
                        out.write(buf, 0, r)
                        onBytes(r.toLong())
                        r = input.read(buf)
                    }
                }
            }
        }
    }
}
