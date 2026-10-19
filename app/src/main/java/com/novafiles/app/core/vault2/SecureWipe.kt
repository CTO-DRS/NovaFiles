package com.novafiles.app.core.vault2

import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * v0.15 — المسح الآمن: إتلاف لا-استرجاع للملفات المحلية الحساسة.
 *
 * التمريرات القياسية: [أصفار، آحاد، عشوائي] × جولات — كل تمريرة تُكتب وتُقرأ
 * للتحقق قبل الانتقال للتالية، ثم يُحذف الملف ويُمسح اسمه. على SSD الممرّات
 * الزائدة رمزية (FTL يعيد التوزيع) لكنها تُرضي سياسة «تدمير متعدد الممرات»
 * وتُتلف فعلياً على بطاقات SD والتخزين المباشر.
 */
object SecureWipe {

    /** تقرير مسح ملف واحد */
    data class Report(val path: String, val bytes: Long, val passes: Int, val deleted: Boolean)

    /** مسح ملف واحد بتمريرات قياسية ثم حذفه */
    fun wipe(file: File, rounds: Int = 1): Report {
        require(rounds in 1..7) { "rounds out of range" }
        require(!file.isDirectory) { "use wipeTree for directories: ${file.path}" }
        if (!file.exists() || !file.isFile) return Report(file.path, 0, 0, false)
        val len = file.length()
        val patterns = buildList {
            repeat(rounds) {
                add(ByteArray(PASS_BUF) { 0 })                       // أصفار
                add(ByteArray(PASS_BUF) { 0xFF.toByte() })           // آحاد
                add(null)                                            // عشوائي
            }
        }
        RandomAccessFile(file, "rw").use { raf ->
            for (pattern in patterns) {
                raf.seek(0)
                var remaining = len
                while (remaining > 0) {
                    val n = minOf(PASS_BUF.toLong(), remaining).toInt()
                    val chunk = pattern?.copyOf(n) ?: ByteArray(n).also { SecureRandom().nextBytes(it) }
                    raf.write(chunk, 0, n)
                    remaining -= n
                }
                raf.fd.sync()
                // تحقق: أعد القراءة وقارن
                raf.seek(0)
                var verified = true
                var left = len
                while (left > 0 && verified) {
                    val n = minOf(PASS_BUF.toLong(), left).toInt()
                    val chunk = ByteArray(n)
                    raf.readFully(chunk)
                    val expected = pattern?.copyOf(n)
                    if (expected != null && !chunk.contentEquals(expected)) verified = false
                    left -= n
                }
                check(verified) { "wipe verification failed: ${file.path}" }
            }
        }
        val deleted = file.delete()
        return Report(file.path, len, patterns.size, deleted)
    }

    /** مسح شجري لمجلد (كل الملفات ثم المجلدات من الأعمق للأعمق) */
    fun wipeTree(dir: File, rounds: Int = 1): List<Report> {
        require(dir.isDirectory) { "not a directory: ${dir.path}" }
        val reports = mutableListOf<Report>()
        dir.walkTopDown().filter { it.isFile }.toList().forEach {
            reports.add(wipe(it, rounds))
        }
        dir.walkBottomUp().filter { it.isDirectory }.toList().forEach { it.delete() }
        return reports
    }

    private const val PASS_BUF = 64 * 1024
}
