package com.novafiles.app.core.archive

import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.tukaani.xz.LZMA2Options
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater

/**
 * v0.30.0 — مصنع الأرشيفات الخالص (pure JVM)
 *
 * قاعدة الخارطة: محرك إنشاء أرشيفات كامل بكل الصيغ المستهدفة —
 * 7z (LZMA2) وTAR.GZ/BZ2/XZ وZIP بمستويات ضغط وكلمات مرور،
 * مع تقسيم أحجام موحد وفهرس إعادة تجميع وبصمة SHA-256 تلقائية
 * عبر محرك v0.29. كل الدوال خالصة فوق java.io/commons-compress —
 * قابلة للاختبار على أقراص مؤقتة 100%.
 */

/** صيغ الإنشاء المدعومة مع الامتداد القياسي */
enum class CreateFormat(val ext: String) {
    ZIP(".zip"),
    SEVEN_Z(".7z"),
    TAR(".tar"),
    TAR_GZ(".tar.gz"),
    TAR_BZ2(".tar.bz2"),
    TAR_XZ(".tar.xz"),
    ;

    companion object {
        /** استنتاج الصيغة من امتداد اسم الملف — null إن لم تُعرف (لن يقرر الطرفية والمستعرض) */
        fun forFileName(name: String): CreateFormat? {
            val n = name.lowercase()
            return entries.firstOrNull { n.endsWith(it.ext) }
        }
    }
}

/** مستوى الضغط الموحد عبر جميع الصيغ */
enum class CreateLevel(
    val zipLevel: Int,
    val lzmaPreset: Int,
    val gzipLevel: Int,
    val bzipBlock: Int,
    val xzPreset: Int,
) {
    /** تخزين بلا ضغط (ZIP/7z/GZ فقط — الباقي يرجع لأسرع مستوى متاح) */
    STORE(Deflater.NO_COMPRESSION, -1, Deflater.NO_COMPRESSION, 1, 0),
    FASTEST(Deflater.BEST_SPEED, 1, Deflater.BEST_SPEED, 1, 1),
    BALANCED(Deflater.DEFAULT_COMPRESSION, 6, Deflater.DEFAULT_COMPRESSION, 6, 6),
    MAXIMUM(Deflater.BEST_COMPRESSION, 9, Deflater.BEST_COMPRESSION, 9, 9),
}

/** طلب إنشاء أرشيف — كلمة المرور مفعلة للـ ZIP (AES-256) والـ 7z (AES-256 + SHA-256) */
data class CreateSpec(
    val sources: List<File>,
    val out: File,
    val format: CreateFormat,
    val level: CreateLevel = CreateLevel.BALANCED,
    val password: CharArray? = null,
    /** حجم الجزء بالبايت (≥1024) لتفعيل التقسيم — 0 يعني بلا تقسيم */
    val splitBytes: Long = 0L,
) {
    override fun equals(other: Any?): Boolean = other is CreateSpec &&
        other.sources == sources && other.out == out && other.format == format &&
        other.level == level && (other.password?.contentEquals(password) ?: (password == null)) &&
        other.splitBytes == splitBytes

    override fun hashCode(): Int = listOfNotNull(
        sources, out, format, level, password?.contentHashCode(), splitBytes,
    ).hashCode()
}

/** نتيجة إنشاء ناجحة */
data class CreateResult(
    val format: CreateFormat,
    val level: CreateLevel,
    /** عدد الملفات (لا المجلدات) المدرجة */
    val fileCount: Int,
    /** مجموع بايتات المدخلات الفعلية */
    val inputBytes: Long,
    val output: File,
    val outputSize: Long,
    /** بصمة SHA-256 للنتيجة النهائية (محسوبة عبر محرك v0.29 — للأرشيف كاملاً قبل التقسيم) */
    val sha256: String,
    /** الأجزاء الناتجة عن التقسيم — فارغة إن لم يُقسّم */
    val volumes: List<File> = emptyList(),
    /** ملف فهرس إعادة التجميع — null إن لم يُقسّم */
    val volumeIndex: File? = null,
    /** هل حُذف الأرشيف الكامل بعد تقسيمه بنجاح؟ */
    val wholeRemovedAfterSplit: Boolean = false,
)

/** استثناء الإلغاء التعاوني */
class CreateCancelled : IOException("create_cancelled")

/**
 * طلب ضغط مُسطّح من طبقة الواجهة/العمليات — بلا مسارات.
 * يتحول إلى CreateSpec داخل OpQueue عند التنفيذ.
 */
data class ArchiveRequest(
    val format: CreateFormat,
    val level: CreateLevel = CreateLevel.BALANCED,
    val password: CharArray? = null,
    val splitBytes: Long = 0L,
) {
    override fun equals(other: Any?): Boolean = other is ArchiveRequest &&
        other.format == format && other.level == level &&
        (other.password?.contentEquals(password) ?: (password == null)) &&
        other.splitBytes == splitBytes

    override fun hashCode(): Int = listOfNotNull(
        format, level, password?.contentHashCode(), splitBytes,
    ).hashCode()
}

/** سقف قاموس LZMA2 بالبايت — أجهزة الجوال لا تحتمل قواميس preset 9 الافتراضية (64MB) */
private const val LZMA2_DICT_CAP = 8L * 1024 * 1024

/** سقف preset لمرزم XZ المستقل — نفس المنطق الذاكري */
private const val XZ_PRESET_CAP = 6

/**
 * المحرك: يوزّع على المسارات حسب الصيغة. الاستراتيجية:
 * - ZIP بكلمة مرور → Zip4j AES-256 (الطريق الوحيد القياسي للكتابة المشفرة)
 * - ZIP بلا كلمة مرور → ZipArchive.createZip المجرَّب (مع مستوى الضغط)
 * - 7z → commons-compress SevenZOutputFile (LZMA2 أو COPY لل STORE)
 * - TAR.* → TarArchiveOutputStream فوق مضغط GZ/BZ2/XZ المناسب
 * بعدها: بصمة SHA-256 تلقائية، ثم تقسيم موحد إن طُلب.
 */
class ArchiveCreateEngine(
    /** التحقق التعاوني بين المدخلات (إلغاء من الواجهة) */
    private val isCancelled: () -> Boolean = { false },
) {

    fun create(spec: CreateSpec, onProgress: (Long, Long) -> Unit = { _, _ -> }): CreateResult {
        val roots = spec.sources.filter { it.exists() }.map { it.canonicalFile }
        require(roots.isNotEmpty()) { "no_sources" }
        val out = spec.out.canonicalFile
        if (!out.exists()) out.parentFile?.let { p -> if (!p.exists()) p.mkdirs() }
        require(out.parentFile?.isDirectory == true) { "out_parent_missing" }
        val items = collect(roots, out)
        val totalBytes = items.filter { !it.isDirectory }.sumOf { it.file.length() }
        onProgress(0L, totalBytes)

        val password = spec.password?.takeIf { it.isNotEmpty() }
        val fileCount = when (spec.format) {
            CreateFormat.ZIP -> if (password != null) {
                createEncryptedZip(items, out, spec.level, password, totalBytes, onProgress)
            } else {
                createPlainZip(items, out, spec.level, totalBytes, onProgress)
            }
            CreateFormat.SEVEN_Z -> createSeven(items, out, spec.level, password, totalBytes, onProgress)
            else -> createTarFamily(items, out, spec.format, spec.level, totalBytes, onProgress)
        }

        // بصمة SHA-256 تلقائية عبر محرك v0.29 — على الأرشيف الكامل قبل أي تقسيم
        val sha = com.novafiles.app.core.checksum.ChecksumEngine()
            .hashFile(out, com.novafiles.app.core.checksum.ChecksumAlgorithm.SHA256)?.hex
            ?: throw IOException("sha256_failed")

        // التقسيم الموحد إن طُلب
        val volumes = ArrayList<File>()
        var index: File? = null
        var wholeRemoved = false
        if (spec.splitBytes > 0) {
            require(spec.splitBytes >= 1024) { "split_too_small" }
            volumes += ArchiveSplitter.split(out, spec.splitBytes)
            index = File(out.parentFile, out.name + SplitIndex.INDEX_EXT)
            wholeRemoved = out.delete()
        }

        onProgress(totalBytes, totalBytes)
        val finalOut = if (volumes.isNotEmpty()) volumes.first() else out
        return CreateResult(
            format = spec.format,
            level = spec.level,
            fileCount = fileCount,
            inputBytes = totalBytes,
            output = finalOut,
            outputSize = finalOut.length(),
            sha256 = sha,
            volumes = volumes.toList(),
            volumeIndex = index,
            wholeRemovedAfterSplit = wholeRemoved,
        )
    }

    // ─────────────────────── جمع المدخلات (حتمي الترتيب) ───────────────────────

    /** عنصر مسطّح: الملف الفعلي + اسم المدخل داخل الأرشيف */
    data class Item(val file: File, val entryName: String) {
        val isDirectory: Boolean get() = file.isDirectory
    }

    /**
     * جمع شجرة المصادر إلى قائمة مسطّحة حتمية الترتيب (فرز أبجدي في كل مستوى).
     * كل مصدر جذر يحتفظ باسمه كمسار جذري داخل الأرشيف — نفس دلالات createZip.
     * الأرشيف الهدف نفسه يُستثنى إن كان ضمن الشجرة (إعادة الضغط على نفس الاسم).
     */
    internal fun collect(roots: List<File>, outCanonical: File): List<Item> {
        val items = ArrayList<Item>()
        for (root in roots) {
            if (root.isFile) {
                items += Item(root, root.name)
            } else {
                items += Item(root, "${root.name}/")
                root.walkTopDown()
                    .filter { it != root }
                    .sortedBy { it.path }
                    .forEach { child ->
                        val rel = child.toRelativeString(root).replace('\\', '/')
                        val entry = "${root.name}/$rel" + (if (child.isDirectory) "/" else "")
                        items += Item(child, entry)
                    }
            }
        }
        // استثناء الأرشيف الهدف نفسه وأي أجزاء/فهارس تقسيم قديمة تحمل اسمه (x.zip.001 / x.zip.nvasplit)
        val volRegex = Regex("^" + Regex.escape(outCanonical.name) + "\\.\\d{3}$")
        return items.filterNot { it.file == outCanonical }
            .filterNot { volRegex.matches(it.file.name) || it.file.name == outCanonical.name + SplitIndex.INDEX_EXT }
    }

    // ─────────────────────── ZIP بلا كلمة مرور (المسار المجرَّب) ───────────────────────

    private fun createPlainZip(
        items: List<Item>,
        out: File,
        level: CreateLevel,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Int {
        if (!out.exists()) out.createNewFile()
        var done = 0L
        var count = 0
        ZipArchiveOutputStream(BufferedOutputStream(FileOutputStream(out), 64 * 1024)).use { zos ->
            zos.setEncoding(Charsets.UTF_8.name())
            zos.setLevel(level.zipLevel)
            for (item in items) {
                throwIfCancelled()
                val entry = ZipArchiveEntry(item.file, item.entryName)
                zos.putArchiveEntry(entry)
                if (!item.isDirectory) {
                    item.file.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            zos.write(buf, 0, n)
                            done += n
                            onProgress(done, totalBytes)
                        }
                    }
                }
                zos.closeArchiveEntry()
                if (!item.isDirectory) count++
            }
        }
        return count
    }

    // ─────────────────────── ZIP بكلمة مرور (Zip4j AES-256) ───────────────────────

    private fun createEncryptedZip(
        items: List<Item>,
        out: File,
        level: CreateLevel,
        password: CharArray,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Int {
        val zip4j = net.lingala.zip4j.ZipFile(out, password)
        var done = 0L
        var count = 0
        // نطوف بنفس قائمة العناصر المسطحة ونمرر الأسماء يدوياً عبر addStream —
        // addFiles المضمنة تخطي المجلدات المتداخلة صامتة مع التشفير (سلوك 2.11.5)
        for (item in items) {
            throwIfCancelled()
            val params = net.lingala.zip4j.model.ZipParameters().apply {
                compressionMethod = net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE
                compressionLevel = when (level) {
                    CreateLevel.STORE -> net.lingala.zip4j.model.enums.CompressionLevel.NO_COMPRESSION
                    CreateLevel.FASTEST -> net.lingala.zip4j.model.enums.CompressionLevel.FASTER
                    CreateLevel.BALANCED -> net.lingala.zip4j.model.enums.CompressionLevel.NORMAL
                    CreateLevel.MAXIMUM -> net.lingala.zip4j.model.enums.CompressionLevel.ULTRA
                }
                isEncryptFiles = true
                encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                fileNameInZip = item.entryName
            }
            if (item.isDirectory) {
                zip4j.addStream(java.io.ByteArrayInputStream(ByteArray(0)), params)
            } else {
                item.file.inputStream().use { input ->
                    zip4j.addStream(input, params)
                }
                done += item.file.length()
                onProgress(done, totalBytes)
                count++
            }
        }
        onProgress(totalBytes, totalBytes)
        return count
    }

    // ─────────────────────── 7z (LZMA2 عبر commons-compress) ───────────────────────

    private fun createSeven(
        items: List<Item>,
        out: File,
        level: CreateLevel,
        password: CharArray?,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Int {
        var done = 0L
        var count = 0
        val seven = if (password != null) SevenZOutputFile(out, password) else SevenZOutputFile(out)
        seven.use { sz ->
            if (level == CreateLevel.STORE) {
                sz.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.COPY)))
            } else {
                val opts = LZMA2Options()
                opts.setPreset(level.lzmaPreset)
                // سقف القاموس 8MB — أجهزة الجوال لا تحتمل 64MB (preset 9 الافتراضي) من بنى BT4
                if (opts.dictSize > LZMA2_DICT_CAP) opts.setDictSize(LZMA2_DICT_CAP.toInt())
                sz.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.LZMA2, opts)))
            }
            for (item in items) {
                throwIfCancelled()
                val entry = sz.createArchiveEntry(item.file, item.entryName)
                sz.putArchiveEntry(entry)
                if (!item.isDirectory) {
                    item.file.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sz.write(buf, 0, n)
                            done += n
                            onProgress(done, totalBytes)
                        }
                    }
                    count++
                }
                sz.closeArchiveEntry()
            }
        }
        return count
    }

    // ─────────────────────── عائلة TAR (خام / GZ / BZ2 / XZ) ───────────────────────

    private fun createTarFamily(
        items: List<Item>,
        out: File,
        format: CreateFormat,
        level: CreateLevel,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): Int {
        if (!out.exists()) out.createNewFile()
        var done = 0L
        var count = 0
        BufferedOutputStream(FileOutputStream(out), 64 * 1024).use { raw ->
            val stream: java.io.OutputStream = when (format) {
                CreateFormat.TAR_GZ -> {
                    val p = GzipParameters().apply { compressionLevel = level.gzipLevel }
                    GzipCompressorOutputStream(raw, p)
                }
                CreateFormat.TAR_BZ2 -> BZip2CompressorOutputStream(raw, level.bzipBlock)
                CreateFormat.TAR_XZ -> XZCompressorOutputStream(raw, minOf(level.xzPreset, XZ_PRESET_CAP))
                else -> raw
            }
            TarArchiveOutputStream(BufferedOutputStream(stream, 64 * 1024), "UTF-8").use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                for (item in items) {
                    throwIfCancelled()
                    val entry = TarArchiveEntry(item.file, item.entryName)
                    tar.putArchiveEntry(entry)
                    if (!item.isDirectory) {
                        item.file.inputStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                tar.write(buf, 0, n)
                                done += n
                                onProgress(done, totalBytes)
                            }
                        }
                        count++
                    }
                    tar.closeArchiveEntry()
                }
            }
        }
        return count
    }

    private fun throwIfCancelled() {
        if (isCancelled()) throw CreateCancelled()
    }
}
