package com.novafiles.app.core.checksum

import java.io.File
import java.io.FileInputStream

/**
 * v0.29.0 — المقارنة بايت-ببايت (pure JVM)
 *
 * فحص الحجم أولاً (رفض فوري دون قراءة أي بايت)، ثم تدفق 64KB على
 * الملفين يتوقف عند أول بايت مختلف بإزاحته المطلقة وقيمتي البايتين سداسياً.
 */

/** نتيجة المقارنة */
sealed class CompareResult {
    /** الملفان متطابقان تماماً (حجم ومحتوى) */
    data object Same : CompareResult()

    /** مختلفان بالحجم — لا قراءة للمحتوى إطلاقاً */
    data class DifferentSize(val sizeA: Long, val sizeB: Long) : CompareResult()

    /** نفس الحجم ومحتوى مختلف — أول إزاحة اختلاف وقيمتا البايتين سداسياً */
    data class DifferentBytes(
        val firstOffset: Long,
        val byteA: Int,
        val byteB: Int,
        val hexA: String,
        val hexB: String,
    ) : CompareResult()

    /** أحد الملفين غير موجود أو غير قابل للقراءة */
    data class Unreadable(val path: String) : CompareResult()
}

object CompareEngine {

    /**
     * مقارنة ملفين بايت-ببايت بتدفق 64KB:
     * 1) الحجم — اختلافه يحسم فوراً دون فتح الملفات
     * 2) كتل متتابعة تتوقف عند أول بايت مختلف (إزاحة مطلقة + سداسي)
     */
    fun compare(fileA: File, fileB: File, bufferSize: Int = 64 * 1024): CompareResult {
        if (!fileA.isFile) return CompareResult.Unreadable(fileA.absolutePath)
        if (!fileB.isFile) return CompareResult.Unreadable(fileB.absolutePath)

        val sizeA = fileA.length()
        val sizeB = fileB.length()
        if (sizeA != sizeB) return CompareResult.DifferentSize(sizeA, sizeB)

        val bufA = ByteArray(bufferSize)
        val bufB = ByteArray(bufferSize)
        var offset = 0L
        try {
            FileInputStream(fileA).use { inA ->
                FileInputStream(fileB).use { inB ->
                    while (true) {
                        val nA = readFully(inA, bufA)
                        val nB = readFully(inB, bufB)
                        if (nA == -1 && nB == -1) return CompareResult.Same
                        val n = minOf(nA, nB)
                        for (i in 0 until n) {
                            if (bufA[i] != bufB[i]) {
                                val a = bufA[i].toInt() and 0xFF
                                val b = bufB[i].toInt() and 0xFF
                                return CompareResult.DifferentBytes(
                                    firstOffset = offset + i,
                                    byteA = a,
                                    byteB = b,
                                    hexA = byteToHex(a),
                                    hexB = byteToHex(b),
                                )
                            }
                        }
                        offset += n
                    }
                }
            }
        } catch (_: Exception) {
            return CompareResult.Unreadable("${fileA.absolutePath} / ${fileB.absolutePath}")
        }
    }

    /** يقرأ حتى امتلاء المخزن أو نهاية الملف — يعود -1 عند النهاية الفورية */
    private fun readFully(input: FileInputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) return if (total == 0) -1 else total
            total += n
        }
        return total
    }

    private fun byteToHex(v: Int): String {
        val HEX = "0123456789abcdef"
        return "${HEX[(v ushr 4) and 0x0F]}${HEX[v and 0x0F]}"
    }
}
