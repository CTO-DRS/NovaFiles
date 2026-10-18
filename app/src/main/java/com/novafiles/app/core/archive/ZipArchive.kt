package com.novafiles.app.core.archive

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** صف في سرد الأرشيف */
data class ZipEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long,
    val lastModified: Long,
)

/**
 * محرك أرشيفات ZIP خالص من الاعتماديات (java.util.zip):
 * - إنشاء أرشيف من عناصر متعددة (ملفات ومجلدات متداخلة) بأسماء UTF-8 عربية سليمة
 * - سرد محتويات الأرشيف بأحجامها
 * - استخراج كامل مع حماية من هجوم Zip-Slip (مسارات ../ خارج مجلد الوجهة)
 * - تقديم تقدم بالبايتات للواجهة
 */
object ZipArchive {

    // ─────────────────────────── السرد ───────────────────────────

    /** سرد كل المدخلات كما هي في الأرشيف (بدون التفريغ) */
    fun list(zip: File): List<ZipEntryInfo> {
        ZipFile(zip, ZipFile.OPEN_READ, Charsets.UTF_8).use { zf ->
            return zf.entries().asSequence()
                .filterNot { it.name.isEmpty() }
                .map {
                    ZipEntryInfo(
                        name = it.name,
                        isDirectory = it.isDirectory,
                        size = it.size,
                        compressedSize = it.compressedSize,
                        lastModified = it.time,
                    )
                }
                .toList()
        }
    }

    /** مجموع الحجم المفكوك المتوقع (لسَوْق شريط التقدم) */
    fun totalUncompressed(zip: File): Long =
        list(zip).sumOf { it.size }

    // ─────────────────────────── الاستخراج ───────────────────────────

    /**
     * استخراج الأرشيف كاملاً داخل [destDir] (يُنشأ إن لم يوجد).
     * يعيد عدد الملفات المستخرجة (المجلدات لا تُحسب).
     * onProgress(bytesDone, totalBytes) — قد يكون totalBytes = 0 لأرشيف فارغ.
     */
    fun extractAll(
        zip: File,
        destDir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Int {
        require(zip.isFile && zip.length() > 0) { "zip_not_found" }
        if (!destDir.exists()) destDir.mkdirs()
        require(destDir.isDirectory) { "dest_not_dir" }
        val destCanonical = destDir.canonicalFile
        var extracted = 0
        val total = totalUncompressed(zip)
        var done = 0L
        onProgress(0L, total)

        ZipFile(zip, ZipFile.OPEN_READ, Charsets.UTF_8).use { zf ->
            val entries = zf.entries().asSequence().filterNot { it.name.isEmpty() }.toList()
            for (entry in entries) {
                val target = safeDest(destCanonical, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                // تأكد من وجود المجلدات الأبوية (بعض الأرشيفات لا تضم مدخل مجلد صريح)
                target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                zf.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                target.setLastModified(if (entry.time > 0) entry.time else System.currentTimeMillis())
                extracted++
            }
        }
        onProgress(done, total)
        return extracted
    }

    /**
     * الوجهة الآمنة لمدخل: يمنع Zip-Slip (مثل ../../etc) وأسماء الأقراص المطلقة.
     * يعيد الملف داخل مجلد الوجهة حصراً. (مندوب إلى الحاجز المشترك ArchiveSafety)
     */
    internal fun safeDest(destCanonical: File, entryName: String): File =
        ArchiveSafety.safeDest(destCanonical, entryName)

    // ─────────────────────────── الإنشاء ───────────────────────────

    /**
     * ضغط قائمة عناصر (ملفات/مجلدات) إلى أرشيف واحد.
     * كل عنصر يصبح مجلداً جذرياً باسمه داخل الأرشيف.
     * يعيد عدد الملفات المضافة. onProgress(entriesDone, totalEntries).
     */
    fun createZip(
        sources: List<File>,
        out: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Int {
        val roots = sources.filter { it.exists() }.map { it.canonicalFile }
        require(roots.isNotEmpty()) { "no_sources" }
        if (!out.exists()) out.createNewFile()
        require(out.isFile) { "out_not_file" }
        val outCanonical = out.canonicalFile

        // اجمع (الملف المطلق، اسم المدخل النسبي) أولًا لنعرف العدد الكلي مقدمًا
        data class Item(val file: File, val entryName: String)
        val items = ArrayList<Item>()
        for (root in roots) {
            if (root.isFile) {
                items += Item(root, root.name)
            } else {
                items += Item(root, "${root.name}/")
                root.walkTopDown()
                    .filter { it != root }
                    .forEach { child ->
                        val rel = child.toRelativeString(root).replace('\\', '/')
                        val entry = "${root.name}/$rel" + (if (child.isDirectory) "/" else "")
                        items += Item(child, entry)
                    }
            }
        }
        val total = items.size.toLong()
        var done = 0L
        onProgress(0L, total)

        ZipOutputStream(FileOutputStream(out), Charsets.UTF_8).use { zos ->
            // الأرشيف لا يضم نفسه (حالة إعادة الضغط على نفس الاسم)
            for (item in items) {
                if (item.file == outCanonical) continue
                val entry = ZipEntry(item.entryName).apply {
                    time = item.file.lastModified()
                }
                if (item.file.isDirectory) {
                    zos.putNextEntry(entry)
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(entry)
                    item.file.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            zos.write(buf, 0, n)
                        }
                    }
                    zos.closeEntry()
                }
                done++
                onProgress(done, total)
            }
        }
        return items.count { it.file != outCanonical && !it.file.isDirectory }
    }
}
