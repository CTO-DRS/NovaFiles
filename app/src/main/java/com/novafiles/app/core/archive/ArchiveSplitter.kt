package com.novafiles.app.core.archive

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * v0.30.0 — فاصل/مُجمِّع الأحجام الموحد (format-agnostic)
 *
 * أي أرشيف (أو أي ملف) يُقسَّم إلى أجزاء مرقّمة name.ext.001 / 002 / …
 * مع فهرس نصي name.ext.nvasplit يحمل كل ما يلزم لإعادة التجميع الآمن:
 * الاسم الأصلي، الحجم الكلي، حجم الجزء، عدد الأجزاء، وبصمة SHA-256 الكلية
 * (محسوبة عبر محرك v0.29). إعادة التجميع تتحقق من الوجود والعدد والبصمة
 * قبل إعلان النجاح — وبلا فهرس يعمل على تسمية الأجزاء وحدها (دون تحقق بصمة).
 */

/** سجل فهرس التقسيم — تنسيق نصي key=value للقراءة عبر الأدوات */
data class SplitIndex(
    val originalName: String,
    val originalSize: Long,
    val volumeSize: Long,
    val volumeCount: Int,
    val sha256: String,
    val createdAt: Long,
) {
    fun serialize(): String = buildString {
        appendLine("NovaSplit v1")
        appendLine("original=$originalName")
        appendLine("size=$originalSize")
        appendLine("volume=$volumeSize")
        appendLine("count=$volumeCount")
        appendLine("sha256=$sha256")
        appendLine("created=$createdAt")
    }.trimEnd() + "\n"

    companion object {
        const val INDEX_EXT = ".nvasplit"

        /** تحليل متسامح: يتجاهل الأسطر الفارغة و# التعليقات، يتطلب المفاتيح الجوهرية */
        fun parse(text: String): SplitIndex {
            var original: String? = null
            var size = -1L
            var volume = -1L
            var count = -1
            var sha = ""
            var created = 0L
            text.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val eq = line.indexOf('=')
                if (eq <= 0) return@forEach
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                when (key) {
                    "original" -> original = value
                    "size" -> size = value.toLongOrNull() ?: -1L
                    "volume" -> volume = value.toLongOrNull() ?: -1L
                    "count" -> count = value.toIntOrNull() ?: -1
                    "sha256" -> sha = value
                    "created" -> created = value.toLongOrNull() ?: 0L
                }
            }
            require(!original.isNullOrEmpty()) { "split_index_missing_original" }
            require(size >= 0) { "split_index_bad_size" }
            require(volume >= 1024) { "split_index_bad_volume" }
            require(count >= 1) { "split_index_bad_count" }
            return SplitIndex(original!!, size, volume, count, sha, created)
        }
    }
}

/** استثناء فشل إعادة تجميع مع رمز قابل للعرض */
class SplitException(val code: String, message: String) : IOException(message)

object ArchiveSplitter {

    /** أصغر جزء مسموح — يمنع انفجار عدد الأجزاء */
    const val MIN_VOLUME_BYTES = 1024L

    /**
     * تقسيم ملف إلى أجزاء بالحجم المطلوب + كتابة الفهرس.
     * يعيد قائمة الأجزاء بترتيبها (بدون الفهرس). الفهرس يُكتب آخراً
     * حتى لا يُقرأ نصف مكتمل عند انقطاع أثناء التقسيم.
     */
    fun split(
        file: File,
        volumeBytes: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): List<File> {
        require(file.isFile && file.length() > 0) { "split_source_missing" }
        require(volumeBytes >= MIN_VOLUME_BYTES) { "split_too_small" }
        val parent = file.parentFile ?: throw SplitException("split_no_parent", "no parent")
        val total = file.length()
        val count = ((total + volumeBytes - 1) / volumeBytes).toInt()
        val volumes = ArrayList<File>(count)
        val buf = ByteArray(64 * 1024)
        var done = 0L
        file.inputStream().use { input ->
            for (i in 1..count) {
                val part = File(parent, "${file.name}.%03d".format(i))
                BufferedOutputStream(FileOutputStream(part), 64 * 1024).use { out ->
                    var written = 0L
                    while (written < volumeBytes) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), volumeBytes - written).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        done += n
                        onProgress(done, total)
                    }
                }
                volumes += part
            }
        }
        val sha = com.novafiles.app.core.checksum.ChecksumEngine()
            .hashFile(file, com.novafiles.app.core.checksum.ChecksumAlgorithm.SHA256)?.hex ?: ""
        val index = SplitIndex(
            originalName = file.name,
            originalSize = total,
            volumeSize = volumeBytes,
            volumeCount = count,
            sha256 = sha,
            createdAt = System.currentTimeMillis(),
        )
        File(parent, file.name + SplitIndex.INDEX_EXT).writeText(index.serialize(), Charsets.UTF_8)
        onProgress(total, total)
        return volumes
    }

    /**
     * إعادة التجميع من ملف الفهرس — تحقق كامل: وجود كل الأجزاء، عددها،
     * الحجم الكلي، وبصمة SHA-256 للمجموع. عند أي خلل يُحذف الناتج الجزئي.
     */
    fun join(indexFile: File, destDir: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        require(indexFile.isFile) { "split_index_missing" }
        val index = SplitIndex.parse(indexFile.readText(Charsets.UTF_8))
        val parent = indexFile.parentFile
        val parts = (1..index.volumeCount).map { i ->
            val p = File(parent, "%s.%03d".format(index.originalName, i))
            if (!p.isFile) throw SplitException("split_part_missing", "missing part ${"%03d".format(i)}")
            p
        }
        return assemble(parts, File(destDir, index.originalName), index.originalSize, index.sha256, onProgress)
    }

    /**
     * إعادة تجميع بلا فهرس (لدينا الأجزاء فقط): يشتق الاسم الأصلي من
     * الجزء الأول (x.zip.001 → x.zip) ويكتشف بقية الأجزاء بالتسلسل.
     * بلا فهرس لا توجد بصمة للتحقق — نجاح يعني عدد وحجم متطابقين مع الأجزاء.
     */
    fun joinFromFirstVolume(firstVolume: File, destDir: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        require(firstVolume.isFile) { "split_part_missing" }
        val m = Regex("^(.*)\\.(\\d{3})$").matchEntire(firstVolume.name)
            ?: throw SplitException("split_not_volume", "not a volume: ${firstVolume.name}")
        val originalName = m.groupValues[1]
        val parent = firstVolume.parentFile
        val parts = ArrayList<File>()
        var i = 1
        while (true) {
            val p = File(parent, "%s.%03d".format(originalName, i))
            if (!p.isFile) break
            parts += p
            i++
        }
        require(parts.size >= 1) { "split_part_missing" }
        // فهرس اختياري موجود بجانب الأجزاء؟ استخدمه للتحقق الكامل
        val maybeIndex = File(parent, originalName + SplitIndex.INDEX_EXT)
        if (maybeIndex.isFile) return join(maybeIndex, destDir, onProgress)
        return assemble(parts, File(destDir, originalName), -1L, "", onProgress)
    }

    // ─────────────────────── التجميع المشترك ───────────────────────

    private fun assemble(
        parts: List<File>,
        dest: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: (Long, Long) -> Unit,
    ): File {
        if (!dest.parentFile!!.exists()) dest.parentFile.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".assembling.tmp")
        tmp.delete()
        val buf = ByteArray(64 * 1024)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            BufferedOutputStream(FileOutputStream(tmp), 64 * 1024).use { out ->
                for (part in parts) {
                    part.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            total += n
                            onProgress(total, -1L)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        if (expectedSize >= 0 && total != expectedSize) {
            tmp.delete()
            throw SplitException("split_size_mismatch", "assembled $total != expected $expectedSize")
        }
        if (expectedSha256.isNotBlank()) {
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(expectedSha256, ignoreCase = true)) {
                tmp.delete()
                throw SplitException("split_hash_mismatch", "sha256 mismatch after reassembly")
            }
        }
        // الوجهة موجودة؟ الناتج موثوق بعد كل التحققات — الاستبدال مقصود (فهرس أعلى سلطة)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            throw SplitException("split_rename_failed", "cannot finalize ${dest.name}")
        }
        onProgress(total, total)
        return dest
    }
}
