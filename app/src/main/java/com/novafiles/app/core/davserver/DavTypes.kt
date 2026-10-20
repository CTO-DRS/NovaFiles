package com.novafiles.app.core.davserver

import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * ══════════════════════════════════════════════════════════
 *  v0.27 — أنواع محرك WebDAV الخالص
 *  الطلب/الاستجابة كقيم محايدة للمقبس، ليختبر المحرك مباشرة
 *  دون شبكة، ثم يغلفه DavHttp/WebDavServer فوق المقابس.
 * ══════════════════════════════════════════════════════════
 */

/** بيانات مصادقة Basic — null تعني «وصول مفتوح بلا كلمة مرور» */
data class DavAuth(val username: String, val password: String)

/**
 * طلب DAV مُحلَّل:
 * ─ method: PROPFIND/GET/HEAD/PUT/MKCOL/DELETE/MOVE/COPY/LOCK/UNLOCK/OPTIONS
 * ─ path: المسار مفكوك الترميز ومنسّط ("" أو "/" تعني الجذر)
 * ─ body: مجرى الجسم — قد يكون فارغاً (PROPFIND قد يحمل جسماً صغيراً، PUT كبيراً)
 */
class DavRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: InputStream,
    val contentLength: Long,
) {
    /** ترويسة بحرف كبير/صغير غير حساس */
    fun header(name: String): String? = headers[name.lowercase()]

    val isKeepAlive: Boolean
        get() = header("connection")?.lowercase() != "close"

    /** عمق PROPFIND — 0 جذر فقط، 1 جذر + أبناء مباشرون */
    val depth: Int
        get() = header("depth")?.trim()?.let {
            if (it.equals("infinity", true)) 1 else it.toIntOrNull() ?: 1
        } ?: 1
}

/** ذراع الاستجابة: إما بايتات جاهزة أو مولّد مجرى (لملفات كبيرة) */
class DavResponse(
    val status: Int,
    val reason: String,
    val headers: MutableList<Pair<String, String>> = mutableListOf(),
    val bodyBytes: ByteArray? = null,
    val bodyStream: InputStream? = null,
    val bodyLength: Long = when {
        bodyBytes != null -> bodyBytes.size.toLong()
        else -> -1L
    },
) {
    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, true) }?.second
}

/** نتيجة فك ترميز مسار: صالح داخل الجذر أو مرفوض */
sealed class DavPathResult {
    data class Ok(val path: String) : DavPathResult()
    data class Bad(val reason: String) : DavPathResult()
}

/**
 * أدوات المسارات — حماية الجذر أولوية:
 * ─ فك ترميز ٪XX مقطعاً مقطعاً (أسماء عربية/مسافات/إيموجي)
 * ─ منع الهروب بـ ../ أو المقاطع الشاذة قبل أي لمس للقرص
 */
object DavPath {

    /** يفك ترميز مساراً خاماً وينسّطه؛ يعيد Bad عند مقطع هروب أو ترميز فاسد */
    fun decodeAndNormalize(raw: String): DavPathResult {
        val noQuery = raw.substringBefore('?')
        // نبني البايتات: هروب %XX يعطي بايتاً خاماً، والمحارف الحرفية
        // تعطي بايتات UTF-8 الخاصة بها (يدعم مسارات بأحرف عربية خاملة)
        val bytes = ArrayList<Byte>(noQuery.length)
        var i = 0
        while (i < noQuery.length) {
            val c = noQuery[i]
            if (c == '%') {
                val hex = noQuery.substring(i + 1, (i + 3).coerceAtMost(noQuery.length))
                if (hex.length != 2) return DavPathResult.Bad("bad escape")
                val v = hex.toIntOrNull(16) ?: return DavPathResult.Bad("bad hex")
                bytes.add(v.toByte())
                i += 3
            } else {
                for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes.add(b)
                i++
            }
        }
        val decoded = String(bytes.toByteArray(), Charsets.UTF_8)

        // التسوية: تفكيك على / ومنع ..
        val parts = decoded.split('/')
        val out = ArrayDeque<String>()
        for (p in parts) {
            when (p) {
                "", "." -> {}
                ".." -> return DavPathResult.Bad("escape attempt")
                else -> out.addLast(p)
            }
        }
        val norm = out.joinToString("/")
        return DavPathResult.Ok(norm)
    }

    /** يرمّز مقطعاً لhref — يحفظ فواصل / ويقرّ كل ما عداه إلى ٪XX UTF-8 */
    fun encodeHref(path: String): String =
        path.split('/').joinToString("/") { seg -> if (seg.isEmpty()) "" else encodeSegment(seg) }

    fun encodeSegment(s: String): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (safe.indexOf(c) >= 0) sb.append(c)
            else sb.append('%')
                .append("0123456789ABCDEF"[(v shr 4) and 0xF])
                .append("0123456789ABCDEF"[v and 0xF])
        }
        return sb.toString()
    }

    /** اسم العرض الأخير من مسار منسّط ("" للجذر) */
    fun displayName(path: String): String =
        path.trimEnd('/').substringAfterLast('/')
}

/** هروب XML للمحتوى النصي والخصائص */
fun xmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/** تاريخ HTTP/1.1 بصيغة RFC 1123 (GMT) للترويسات */
fun httpDate(millis: Long): String =
    DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))

/**
 * خريطة MIME مضمّنة (بلا مكتبات) — تمتد لأنواع الوسائط الشائعة،
 * والافتراضي application/octet-stream يضمن تنزيل المجهول بدل إتلافه.
 */
object DavMime {
    private val map = mapOf(
        "html" to "text/html", "htm" to "text/html",
        "css" to "text/css", "csv" to "text/csv",
        "txt" to "text/plain", "md" to "text/markdown", "log" to "text/plain",
        "xml" to "application/xml", "json" to "application/json",
        "js" to "text/javascript", "ts" to "text/javascript",
        "pdf" to "application/pdf",
        "zip" to "application/zip", "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed", "tar" to "application/x-tar",
        "gz" to "application/gzip", "bz2" to "application/x-bzip2", "xz" to "application/x-xz",
        "apk" to "application/vnd.android.package-archive",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml",
        "bmp" to "image/bmp", "ico" to "image/x-icon", "heic" to "image/heic",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg",
        "m4a" to "audio/mp4", "flac" to "audio/flac", "aac" to "audio/aac",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "mov" to "video/quicktime", "avi" to "video/x-msvideo", "3gp" to "video/3gpp",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "epub" to "application/epub+zip",
    )

    fun of(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return map[ext] ?: "application/octet-stream"
    }
}
