package com.novafiles.app.core.archive

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** عائلات الأرشيفات المدعومة */
enum class Family { ZIP, TAR, SEVEN, RAR, COMPRESSED, NONE }

/** تصنيف من اسم الملف فقط (بلا IO) */
fun familyOf(fileName: String): Family {
    val n = fileName.lowercase()
    return when {
        n.endsWith(".zip") -> Family.ZIP
        n.endsWith(".tar") || n.endsWith(".tar.gz") || n.endsWith(".tgz") ||
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tar.xz") ||
            n.endsWith(".txz") -> Family.TAR
        n.endsWith(".7z") -> Family.SEVEN
        n.endsWith(".rar") -> Family.RAR
        n.endsWith(".gz") || n.endsWith(".bz2") || n.endsWith(".xz") -> Family.COMPRESSED
        else -> Family.NONE
    }
}

/** اسم الملف بعد نزع امتدادات الأرشيف المعروفة (لأسماء مجلدات الاستخراج والمدخل الواحد) */
fun stripArchiveExt(fileName: String): String {
    var n = fileName
    listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".txz", ".tar", ".7z", ".rar", ".zip", ".gz", ".bz2", ".xz")
        .forEach { ext -> if (n.lowercase().endsWith(ext)) { n = n.dropLast(ext.length); return n } }
    return n
}

/**
 * قراءة الأرشيفات غير-ZIP: TAR (وتغليفه gz/bz2/xz) و7z وRAR4+RAR5 (junrar) والضغط أحادي الملف (gz/bz2/xz).
 * كلها للقراءة/الاستخراج فقط — الإنشاء متاح لـ ZIP فقط (ZipArchive).
 * الحجم الأصلي غير معروف مسبقاً في الضغط أحادي الملف → size = -1 وtotal = -1 (تقدم غير محدد).
 */
object CommonArchive {

    // ─────────────────────────── السرد ───────────────────────────

    fun list(file: File): List<ZipEntryInfo> = when (familyOf(file.name)) {
        Family.TAR -> listTar(file)
        Family.SEVEN -> listSeven(file)
        Family.RAR -> listRar(file)
        Family.COMPRESSED -> listOf(singleEntry(file))
        else -> throw IllegalArgumentException("unsupported: ${file.name}")
    }

    /** مجموع الحجم المفكوك المتوقع، أو -1 إن كان مجهولاً (ضغط أحادي الملف) */
    fun totalUncompressed(file: File): Long = when (familyOf(file.name)) {
        Family.TAR, Family.SEVEN, Family.RAR -> list(file).sumOf { if (it.isDirectory) 0L else it.size }
        else -> -1L
    }

    // ─────────────────────────── الاستخراج ───────────────────────────

    fun extractAll(
        file: File,
        destDir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Int = when (familyOf(file.name)) {
        Family.TAR -> extractTar(file, destDir, onProgress)
        Family.SEVEN -> extractSeven(file, destDir, onProgress)
        Family.RAR -> extractRar(file, destDir, onProgress)
        Family.COMPRESSED -> extractSingle(file, destDir, onProgress)
        else -> throw IllegalArgumentException("unsupported: ${file.name}")
    }

    // ─────────────────────────── TAR ───────────────────────────

    private fun tarStream(file: File): TarArchiveInputStream {
        val fin = file.inputStream().buffered(64 * 1024)
        val n = file.name.lowercase()
        val inner: InputStream = when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> GzipCompressorInputStream(fin)
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> BZip2CompressorInputStream(fin)
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> XZCompressorInputStream(fin)
            else -> fin
        }
        return TarArchiveInputStream(inner)
    }

    private fun listTar(file: File): List<ZipEntryInfo> =
        tarStream(file).use { tin ->
            buildList {
                var e: TarArchiveEntry? = tin.nextTarEntry
                while (e != null) {
                    add(ZipEntryInfo(e.name, e.isDirectory, e.size, e.size, e.modTime.time))
                    e = tin.nextTarEntry
                }
            }
        }

    private fun extractTar(file: File, destDir: File, onProgress: (Long, Long) -> Unit): Int {
        if (!destDir.exists()) destDir.mkdirs()
        val dest = destDir.canonicalFile
        var extracted = 0
        var done = 0L
        val total = runCatching { totalUncompressed(file) }.getOrDefault(-1L)
        onProgress(0L, total)
        tarStream(file).use { tin ->
            var e: TarArchiveEntry? = tin.nextTarEntry
            while (e != null) {
                val target = ArchiveSafety.safeDest(dest, e.name)
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    FileOutputStream(target).use { out ->
                        done = tin.copyTo(out, done, onProgress)
                    }
                    target.setLastModified(if (e.modTime.time > 0) e.modTime.time else System.currentTimeMillis())
                    extracted++
                }
                e = tin.nextTarEntry
            }
        }
        onProgress(done, total)
        return extracted
    }

    // ─────────────────────────── 7z ───────────────────────────

    private fun listSeven(file: File): List<ZipEntryInfo> =
        SevenZFile(file).use { sf ->
            buildList {
                var e: SevenZArchiveEntry? = sf.nextEntry
                while (e != null) {
                    // compressedSize للمدخل غير متاح عاماً في SevenZArchiveEntry — نكتفي بالحجم الأصلي
                    add(ZipEntryInfo(e.name, e.isDirectory, e.size, e.size, e.lastModifiedTime?.toMillis() ?: 0L))
                    e = sf.nextEntry
                }
            }
        }

    private fun extractSeven(file: File, destDir: File, onProgress: (Long, Long) -> Unit): Int {
        if (!destDir.exists()) destDir.mkdirs()
        val dest = destDir.canonicalFile
        var extracted = 0
        var done = 0L
        val total = runCatching { totalUncompressed(file) }.getOrDefault(-1L)
        onProgress(0L, total)
        SevenZFile(file).use { sf ->
            var e: SevenZArchiveEntry? = sf.nextEntry
            while (e != null) {
                val target = ArchiveSafety.safeDest(dest, e.name)
                if (e.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    sf.getInputStream(e).use { input ->
                        FileOutputStream(target).use { out ->
                            done = input.copyTo(out, done, onProgress)
                        }
                    }
                    val m = e.lastModifiedTime?.toMillis() ?: 0L
                    target.setLastModified(if (m > 0) m else System.currentTimeMillis())
                    extracted++
                }
                e = sf.nextEntry
            }
        }
        onProgress(done, total)
        return extracted
    }

    // ─────────────────────────── RAR (junrar — RAR4 + RAR5) ───────────────────────────

    /** بصمة RAR5: Rar!\x1A\x07\x01\x00 — خلاف ذلك RAR4 القديم أو ليس RAR أصلاً */
    fun isRar5(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(8)
            var read = 0
            while (read < 8) {
                val n = input.read(head, read, 8 - read)
                if (n < 0) break
                read += n
            }
            read >= 8 && head.contentEquals(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A.toByte(), 0x07, 0x01, 0x00))
        }
    }.getOrDefault(false)

    private fun rarHeadersName(h: FileHeader): String =
        h.fileName.takeIf { it.isNotEmpty() }
            ?: String(h.fileNameByteArray, Charsets.UTF_8).takeIf { it.isNotEmpty() }
            ?: "unnamed"

    private fun listRar(file: File): List<ZipEntryInfo> {
        val arch = Archive(file)
        arch.use { a ->
            if (a.isEncrypted) throw IllegalStateException("rar_encrypted")
            return a.fileHeaders.map { h ->
                ZipEntryInfo(rarHeadersName(h), h.isDirectory, h.unpSize, h.fullUnpackSize, h.mTime?.time ?: 0L)
            }
        }
    }

    private fun extractRar(file: File, destDir: File, onProgress: (Long, Long) -> Unit): Int {
        if (!destDir.exists()) destDir.mkdirs()
        val dest = destDir.canonicalFile
        var extracted = 0
        var done = 0L
        val total = runCatching { totalUncompressed(file) }.getOrDefault(-1L)
        onProgress(0L, total)
        Archive(file).use { a ->
            if (a.isEncrypted) throw IllegalStateException("rar_encrypted")
            for (h in a.fileHeaders) {
                val target = ArchiveSafety.safeDest(dest, rarHeadersName(h))
                if (h.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    // عدّاد كل ملف مستقل → نضيفه إلى ما قبله بدل الكتابة المطلقة
                    val before = done
                    CountingOutputStream(FileOutputStream(target)) { d, _ ->
                        done = before + d
                        onProgress(done, total)
                    }.use { out ->
                        a.extractFile(h, out)
                    }
                    val m = h.mTime?.time ?: 0L
                    target.setLastModified(if (m > 0) m else System.currentTimeMillis())
                    extracted++
                }
            }
        }
        onProgress(done, total)
        return extracted
    }

    // ─────────────────────── ضغط أحادي الملف (gz/bz2/xz) ───────────────────────

    private fun singleEntry(file: File): ZipEntryInfo =
        ZipEntryInfo(
            name = stripArchiveExt(file.name).ifEmpty { file.name },
            isDirectory = false,
            size = -1L, // مجهول دون فك الضغط
            compressedSize = file.length(),
            lastModified = file.lastModified(),
        )

    private fun compressorStream(file: File): InputStream {
        val fin = file.inputStream().buffered(64 * 1024)
        return when (familyOf(file.name)) {
            Family.COMPRESSED -> when {
                file.name.lowercase().endsWith(".gz") -> GzipCompressorInputStream(fin)
                file.name.lowercase().endsWith(".bz2") -> BZip2CompressorInputStream(fin)
                else -> XZCompressorInputStream(fin)
            }
            else -> throw IllegalArgumentException("not a compressor: ${file.name}")
        }
    }

    private fun extractSingle(file: File, destDir: File, onProgress: (Long, Long) -> Unit): Int {
        if (!destDir.exists()) destDir.mkdirs()
        val dest = destDir.canonicalFile
        val target = ArchiveSafety.safeDest(dest, stripArchiveExt(file.name).ifEmpty { file.name })
        target.parentFile?.let { if (!it.exists()) it.mkdirs() }
        onProgress(0L, -1L)
        compressorStream(file).use { input ->
            FileOutputStream(target).use { out ->
                input.copyTo(out, 0L) { d, t -> onProgress(d, t) }
            }
        }
        return 1
    }

    // ─────────────────────────── أدوات ───────────────────────────

    /** نسخ مع عدّ بايتات وتقديم تقدم حي */
    private fun InputStream.copyTo(out: OutputStream, startDone: Long, onProgress: (Long, Long) -> Unit): Long {
        var done = startDone
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            onProgress(done, -2L) // -2: التقدم الكلي يُدار خارجاً
        }
        return done
    }

    /** OutputStream يحسب البايتات المكتوبة (لتقدم RAR الذي لا يوفر بايتات مباشرة) */
    private class CountingOutputStream(
        out: OutputStream,
        private val onCount: (Long, Long) -> Unit,
    ) : java.io.FilterOutputStream(out) {

        private var count = 0L
        private val chunk = 64 * 1024

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
            if (count % chunk < len) onCount(count, 0L)
        }

        override fun close() {
            // إبلاغ نهائي بالحجم الفعلي — الملفات الأصغر من 64KB لا تُطلق نداء أثناء الكتابة
            onCount(count, 0L)
            out.close()
        }
    }
}

/** غلاف موحد يوزّع على ZipArchive (إنشاء+قراءة) أو CommonArchive (قراءة فقط) */
object ArchiveEngine {

    /** هل يمكن فتح هذا الملف كأرشيف داخل التطبيق؟ */
    fun supports(fileName: String): Boolean = familyOf(fileName) != Family.NONE

    fun list(file: File): List<ZipEntryInfo> = when (familyOf(file.name)) {
        Family.ZIP -> ZipArchive.list(file)
        Family.NONE -> throw IllegalArgumentException("unsupported: ${file.name}")
        else -> CommonArchive.list(file)
    }

    fun totalUncompressed(file: File): Long = when (familyOf(file.name)) {
        Family.ZIP -> ZipArchive.totalUncompressed(file)
        Family.NONE -> throw IllegalArgumentException("unsupported: ${file.name}")
        else -> CommonArchive.totalUncompressed(file)
    }

    fun extractAll(
        file: File,
        destDir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Int = when (familyOf(file.name)) {
        Family.ZIP -> ZipArchive.extractAll(file, destDir, onProgress)
        Family.NONE -> throw IllegalArgumentException("unsupported: ${file.name}")
        else -> CommonArchive.extractAll(file, destDir, onProgress)
    }
}
