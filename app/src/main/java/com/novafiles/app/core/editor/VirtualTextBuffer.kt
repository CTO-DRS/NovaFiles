package com.novafiles.app.core.editor

/**
 * v0.17 — قراءة الملفات النصية الضخمة بنوافذ (virtualized mode).
 *
 * Kotlin خالص — يُختبر بالكامل على JVM عبر واجهة [PageReader].
 *
 * المبدأ: لا يُحمَّل الملف كاملاً أبداً.
 *  - فهرس خطّي نادر: نخزّن إزاحة بداية كل [INDEX_STEP] سطراً فقط (O(1) للذاكرة).
 *  - الوصول العشوائي لسطر: بحث ثنائي في الفهرس ثم سير فكّ ترميز من أقرب نقطة.
 *  - كشف ثنائي: بايت NUL في أول 8KB ⇒ ليس نصاً.
 *  - كشف الترميز: BOM (UTF-8/UTF-16LE/UTF-16BE) وإلا UTF-8.
 */
class VirtualTextBuffer(
    private val reader: PageReader,
    private val charset: String = detectCharset(reader),
    private val indexStep: Int = INDEX_STEP,
) {

    /** واجهة القراءة العشوائية — يغلّفها UI بـ RandomAccessFile */
    interface PageReader {
        val length: Long
        fun readAt(offset: Long, len: Int): ByteArray
    }

    /** ليست ملفاً ثنائياً (نص) — الكشف: غياب بايت NUL في أول 8KB */
    val isText: Boolean = !detectBinary(reader)

    /** عدد الأسطر — يُبنى عند أول طلب (مسح كامل واحد) */
    val lineCount: Long by lazy {
        if (!isText) 0L else scanIndex().totalLines
    }

    @Volatile
    private var index: SparseIndex? = null

    /**
     * نافذة أسطر حول سطر محدد.
     * @return قائمة أسطر مفكوكة الترميز (بلا أحرف نهاية السطر)
     */
    fun readWindow(centerLine: Long, contextLines: Int = 50, maxLines: Int = 400): List<String> {
        check(isText) { "ملف ثنائي — لا يمكن عرضه كنص" }
        if (reader.length == 0L) return listOf("")
        val idx = scanIndex()
        val start = (centerLine - contextLines).coerceAtLeast(0)
        val end = (centerLine + contextLines).coerceAtMost(idx.totalLines - 1).coerceAtLeast(start)
        if (end < start) return emptyList()
        val bounded = if (end - start + 1 > maxLines) start + maxLines - 1 else end

        // أقرب نقطة فهرسة قبل start
        val (checkpointLine, checkpointOffset) = idx.floor(start)
        var offset = checkpointOffset
        var lineNo = checkpointLine
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var current = StringBuilder()
        val chunk = ByteArray(CHUNK)

        // ابدأ: إن كنا بعد checkpoint مباشرة، لا حاجة لفك الأسطر السابقة إلا للوصول إلى start
        while (lineNo <= bounded && offset < reader.length) {
            val toRead = CHUNK.toLong().coerceAtMost(reader.length - offset).toInt()
            reader.readAt(offset, toRead).forEach { byte ->
                if (lineNo > bounded) return@forEach
                if (byte == '\n'.code.toByte()) {
                    if (lineNo >= start) {
                        buf.append(decode(current)); out.add(buf.toString()); buf.setLength(0)
                    }
                    current.setLength(0)
                    lineNo++
                } else {
                    current.append(byte.toInt().and(0xFF).toChar())
                }
            }
            offset += toRead
            if (toRead < CHUNK) break
        }
        if (lineNo == bounded && current.isNotEmpty()) {
            buf.append(decode(current)); out.add(buf.toString())
        }
        return out
    }

    /** سطر واحد بالرقم — ملاءمة للقفز السريع */
    fun readLine(line: Long): String? =
        readWindow(line, 0, 1).firstOrNull()

    /** فهرس نادر: إزاحة كل N سطر */
    private fun scanIndex(): SparseIndex {
        index?.let { return it }
        val offsets = mutableListOf<Long>(0L)
        var total = 0L
        var offset = 0L
        val chunk = ByteArray(CHUNK)
        while (offset < reader.length) {
            val toRead = CHUNK.toLong().coerceAtMost(reader.length - offset).toInt()
            val bytes = reader.readAt(offset, toRead)
            bytes.forEachIndexed { bi, b ->
                if (b == '\n'.code.toByte()) {
                    total++
                    if (total % indexStep == 0L) offsets.add(offset + bi + 1)
                }
            }
            offset += toRead
            if (toRead < CHUNK) break
        }
        // إن لم ينته الملف بسطر جديد فالسطر الأخير غير محسوب
        val endsWithNewline = reader.length > 0 &&
            reader.readAt(reader.length - 1, 1)[0] == '\n'.code.toByte()
        val lines = if (endsWithNewline) total else total + 1
        val built = SparseIndex(offsets, indexStep, lines)
        index = built
        return built
    }

    private fun decode(chars: StringBuilder): String {
        // نجمّع البايتات كما هي ثم نفك الترميز بالترميز المكتشف
        val bytes = ByteArray(chars.length) { chars[it].code.and(0xFF).toByte() }
        return String(bytes, charset(charset))
    }

    private class SparseIndex(
        val offsets: List<Long>,
        val step: Int,
        val totalLines: Long,
    ) {
        /** أقرب نقطة (سطر، إزاحة) عند أو قبل سطر معين */
        fun floor(line: Long): Pair<Long, Long> {
            val pos = (line / step).toInt().coerceIn(0, offsets.size - 1)
            return Pair(pos.toLong() * step, offsets[pos])
        }
    }

    companion object {
        private const val CHUNK = 64 * 1024
        const val INDEX_STEP = 512

        /** كشف ثنائي: NUL في أول 8KB */
        fun detectBinary(reader: PageReader): Boolean {
            if (reader.length == 0L) return false
            val head = reader.readAt(0, 8192.toLong().coerceAtMost(reader.length).toInt())
            return head.any { it.toInt() == 0 }
        }

        /** كشف الترميز من BOM */
        fun detectCharset(reader: PageReader): String {
            if (reader.length < 2L) return "UTF-8"
            val b = reader.readAt(0, 3)
            return when {
                b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> "UTF-8"
                b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> "UTF-16LE"
                b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> "UTF-16BE"
                else -> "UTF-8"
            }
        }
    }
}
