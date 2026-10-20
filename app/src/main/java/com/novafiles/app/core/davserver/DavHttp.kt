package com.novafiles.app.core.davserver

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * v0.27 — طبقة بروتوكول HTTP/1.1 لخادم WebDAV.
 * ─ القراءة: سطر الطلب + الترويسات + الجسم (Content-Length أو Chunked)
 * ─ الكتابة: دائماً Content-Length مع دعم keep-alive للمتصفحات ومستكشف Windows
 * خالصة JVM: تعمل فوق أي InputStream/OutputStream، فتختبر بالبايتات مباشرة.
 */
object DavHttp {

    private const val MAX_HEADER_BYTES = 32 * 1024

    /** فشل تحليل الطلب — يغلق المضيف الاتصال */
    class BadRequest(message: String) : IOException(message)

    /**
     * يقرأ طلباً واحداً من مجرى الاتصال.
     * يعيد null عند نهاية الاتصال النظيفة (العميل أغلق).
     * الجسم يعيد كمجرى محدود الطول (Length/Chunked) — لا يُحمّل للذاكرة.
     */
    fun readRequest(input: BufferedInputStream): DavRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) {
            // سطر فراغ زائد (CRLF بعد جسم سابق) — حاول مرة واحدة إضافية
            val again = readLine(input) ?: return null
            if (again.isBlank()) return null
            return parseWithLine(again, input)
        }
        return parseWithLine(requestLine, input)
    }

    private fun parseWithLine(requestLine: String, input: BufferedInputStream): DavRequest? {
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw BadRequest("bad request line")
        val method = parts[0].trim().uppercase()
        val rawPath = parts[1].trim()

        val headers = HashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine(input) ?: throw BadRequest("eof in headers")
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAX_HEADER_BYTES) throw BadRequest("headers too large")
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                // الترويسات المتكررة (أخرى غير Cookie) تدمج بفاصلة
                headers[name] = headers[name]?.let { "$it, $value" } ?: value
            }
        }

        val norm = when (val r = DavPath.decodeAndNormalize(rawPath)) {
            is DavPathResult.Ok -> r.path
            is DavPathResult.Bad -> throw BadRequest("bad path")
        }

        val te = headers["transfer-encoding"]?.lowercase() ?: ""
        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        val body: InputStream = if (te.contains("chunked")) {
            ChunkedInputStream(input)
        } else if (contentLength > 0) {
            LengthInputStream(input, contentLength)
        } else {
            LengthInputStream(input, 0)
        }

        return DavRequest(method, norm, headers, body, if (te.contains("chunked")) -1 else contentLength)
    }

    /** يكتب استجابة كاملة؛ يمرّر مجاري الملفات الكبيرة بدفعات دون تحميل للذاكرة */
    fun writeResponse(
        output: OutputStream,
        resp: DavResponse,
        keepAlive: Boolean,
        serverName: String = "NovaFiles-Dav",
    ) {
        val head = StringBuilder(192)
        head.append("HTTP/1.1 ").append(resp.status).append(' ').append(resp.reason).append("\r\n")
        head.append("Server: ").append(serverName).append("\r\n")
        for ((k, v) in resp.headers) head.append(k).append(": ").append(v).append("\r\n")
        val close = !keepAlive || resp.header("connection")?.lowercase() == "close"
        head.append("Connection: ").append(if (close) "close" else "keep-alive").append("\r\n")

        val bytes = resp.bodyBytes
        when {
            bytes != null -> {
                if (resp.header("content-type") == null && bytes.isNotEmpty()) {
                    head.append("Content-Type: text/plain; charset=utf-8\r\n")
                }
                head.append("Content-Length: ").append(bytes.size).append("\r\n\r\n")
                output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                if (bytes.isNotEmpty()) output.write(bytes)
            }
            resp.bodyStream != null -> {
                head.append("Content-Length: ").append(resp.bodyLength).append("\r\n\r\n")
                output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                resp.bodyStream.use { src ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            else -> {
                head.append("Content-Length: 0\r\n\r\n")
                output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            }
        }
        output.flush()
    }

    // ═══════════════ أدوات القراءة ═══════════════

    /** يقرأ سطراً حتى CRLF — null عند EOF قبل أي محرف */
    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder(96)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /**
     * مجرى محدود بطول Content-Length.
     * لا يغلق مجرى الاتصال الأساسي أبداً (المضيف يملكه) —
     * والتفريغ للطلب التالي صريح عبر drain() بسقف أمان.
     */
    class LengthInputStream(private val src: InputStream, private val length: Long) : InputStream() {
        private var remaining = length

        val consumedFully: Boolean get() = remaining <= 0

        override fun read(): Int {
            if (remaining <= 0) return -1
            val v = src.read()
            if (v >= 0) remaining--
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        fun remaining(): Long = remaining

        override fun close() {
            // لا إغلاق — مجرى الاتصال يملكه المضيف
        }

        /** يفرّغ الباقي حتى سقف؛ يعيد true إن اكتمل التفريغ (يصلح لطلب تالٍ) */
        fun drain(maxBytes: Long = 1 shl 20): Boolean {
            val buf = ByteArray(8192)
            var drained = 0L
            while (remaining > 0) {
                if (drained >= maxBytes) return false
                val cap = minOf(buf.size.toLong(), remaining, maxBytes - drained).toInt()
                val n = src.read(buf, 0, cap)
                if (n < 0) return remaining <= 0
                drained += n
                remaining -= n
            }
            return true
        }
    }

    /** مفكوك Chunked (RFC 7230 §4.1) — يقرأ القطع متتالية حتى قطعة الصفر */
    class ChunkedInputStream(private val src: InputStream) : InputStream() {
        private var remaining = 0L
        private var done = false

        val consumedFully: Boolean get() = done

        private fun nextChunkHeader(): Long {
            val line = readRawLine(src) ?: throw EOFException("eof in chunk size")
            val sizePart = line.substringBefore(';').trim()
            val size = sizePart.toLongOrNull(16) ?: throw BadRequest("bad chunk size")
            if (size == 0L) {
                // ترويسات لاحقة حتى سطر فارغ
                while (true) {
                    val t = readRawLine(src) ?: break
                    if (t.isEmpty()) break
                }
                done = true
            }
            return size
        }

        override fun read(): Int {
            if (done) return -1
            if (remaining == 0L) {
                remaining = nextChunkHeader()
                if (done) return -1
            }
            val v = src.read()
            if (v >= 0) {
                remaining--
                if (remaining == 0L) readCrlf(src)
            }
            return v
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (remaining == 0L) {
                remaining = nextChunkHeader()
                if (done) return -1
            }
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) {
                remaining -= n
                if (remaining == 0L) readCrlf(src)
            }
            return n
        }

        override fun close() {
            // لا إغلاق — مجرى الاتصال يملكه المضيف
        }

        /** يفرّغ قطع متبقية حتى سقف؛ يعيد true إن اكتمل الجسم */
        fun drain(maxBytes: Long = 1 shl 20): Boolean {
            if (done) return true
            val buf = ByteArray(8192)
            var drained = 0L
            while (true) {
                if (drained >= maxBytes) return false
                val v = read()
                if (v < 0) return done
                drained++
            }
        }

        private fun readCrlf(s: InputStream) {
            val cr = s.read()
            val lf = s.read()
            if (cr != '\r'.code || lf != '\n'.code) throw BadRequest("bad chunk terminator")
        }
    }

    private fun readRawLine(input: InputStream): String? {
        val sb = StringBuilder(24)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }
}
