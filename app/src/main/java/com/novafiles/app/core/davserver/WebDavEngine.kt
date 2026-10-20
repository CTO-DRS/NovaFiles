package com.novafiles.app.core.davserver

import com.novafiles.app.core.netlog.ConnectionLog
import com.novafiles.app.core.netlog.NetEventType
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * ══════════════════════════════════════════════════════════
 *  v0.27 — محرك WebDAV الخالص: يقبض طلباً محلولاً ويعيد استجابة
 *  ─ صفر مقابس وصفر Android: يختبر مباشرة على JVM بالبايتات والملفات
 *  ─ يخدم ملفات الجذر الحقيقية (قراءة/كتابة/حذف/نقل) بحماية صارمة:
 *    كل مسار يمر عبر DavPath.decodeAndNormalize فلا هروب بـ ../
 *  ─ الأفعال: OPTIONS/PROPFIND/GET/HEAD/PUT/MKCOL/DELETE/MOVE/COPY/LOCK/UNLOCK
 *  ─ Range مقطوع لطلبة واحدة (bytes=a-b / a- / -n) → 206/416
 *  ─ ETag مشتق من (الحجم-زمن التعديل) بلا سجل حالة
 *  ─ أقفال حصرية write في الذاكرة لعمالئة Windows/Finder
 *  ─ فهرس HTML للمجلدات ليفتح المتصفح العادي الرابط مباشرة
 * ══════════════════════════════════════════════════════════
 */
class WebDavEngine(
    /** الجذر المشترك — لا يخدم المحرك شيئاً خارجه */
    val root: File,
    private val auth: DavAuth? = null,
    private val log: ConnectionLog? = null,
) {

    private class LockEntry(val token: String, val depth: String, val owner: String?)

    /** أقفال حصرية بالمسار المنسّط */
    private val locks = ConcurrentHashMap<String, LockEntry>()

    // ═══════════════ التفريع الرئيسي ═══════════════

    fun handle(req: DavRequest): DavResponse {
        // تسوية موحدة: «/» أو «//» كلها تعني الجذر ("")، وبلا شرطة مائلة بادئة
        val norm = DavRequest(
            method = req.method,
            path = req.path.trim('/'),
            headers = req.headers,
            body = req.body,
            contentLength = req.contentLength,
        )
        if (auth != null && !authorized(norm)) {
            log?.append(NetEventType.LOGIN_FAILED, "401 ${norm.method} ${norm.path}")
            return DavResponse(
                401, "Unauthorized",
                mutableListOf("WWW-Authenticate" to "Basic realm=\"NovaFiles\", charset=\"UTF-8\""),
                bodyBytes = "unauthorized".toByteArray(Charsets.UTF_8),
            )
        }
        return try {
            when (norm.method.uppercase()) {
                "OPTIONS" -> options()
                "PROPFIND" -> propfind(norm)
                "GET" -> get(norm, withBody = true)
                "HEAD" -> get(norm, withBody = false)
                "PUT" -> put(norm)
                "MKCOL" -> mkcol(norm)
                "DELETE" -> delete(norm)
                "MOVE" -> relocate(norm, copy = false)
                "COPY" -> relocate(norm, copy = true)
                "LOCK" -> lock(norm)
                "UNLOCK" -> unlock(norm)
                else -> DavResponse(405, "Method Not Allowed", bodyBytes = "method not allowed".toByteArray())
            }
        } catch (e: DavException) {
            DavResponse(e.status, e.reason, bodyBytes = e.message?.toByteArray() ?: ByteArray(0))
        } catch (e: Exception) {
            log?.append(NetEventType.ERROR, "${norm.method} ${norm.path}: ${e.message ?: e.javaClass.simpleName}")
            DavResponse(500, "Internal Server Error", bodyBytes = "internal error".toByteArray())
        }
    }

    private fun authorized(req: DavRequest): Boolean {
        val header = req.header("authorization") ?: return false
        if (!header.startsWith("Basic ", true)) return false
        return runCatching {
            val decoded = String(Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8)
            val idx = decoded.indexOf(':')
            idx > 0 && decoded.substring(0, idx) == auth!!.username && decoded.substring(idx + 1) == auth!!.password
        }.getOrDefault(false)
    }

    // ═══════════════ الأفعال ═══════════════

    private fun options(): DavResponse = DavResponse(
        200, "OK",
        mutableListOf(
            "DAV" to "1, 2",
            "Allow" to "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, MKCOL, MOVE, COPY, LOCK, UNLOCK",
            "MS-Author-Via" to "DAV",
            "Accept-Ranges" to "bytes",
        ),
        bodyBytes = ByteArray(0),
    )

    private fun propfind(req: DavRequest): DavResponse {
        val file = fileFor(req.path)
        if (!file.exists()) return notFound()
        drain(req.body, req.contentLength)

        val entries = mutableListOf<DavXml.Entry>()
        entries.add(entryOf(req.path, file))
        if (req.depth >= 1 && file.isDirectory) {
            file.listFiles()
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                ?.forEach { entries.add(entryOf(childPath(req.path, it.name), it)) }
        }
        val xml = DavXml.multistatus(entries)
        return DavResponse(
            207, "Multi-Status",
            mutableListOf("Content-Type" to "application/xml; charset=utf-8"),
            bodyBytes = xml.toByteArray(Charsets.UTF_8),
        )
    }

    private fun get(req: DavRequest, withBody: Boolean): DavResponse {
        val file = fileFor(req.path)
        if (!file.exists()) return notFound()

        if (file.isDirectory) {
            val children = file.listFiles()
                ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                ?: emptyList()
            val items = children.map {
                HtmlIndex.Item(it.name, it.isDirectory, it.length(), it.lastModified())
            }
            val html = HtmlIndex.render(req.path, items)
            return DavResponse(
                200, "OK",
                mutableListOf("Content-Type" to "text/html; charset=utf-8"),
                bodyBytes = html.toByteArray(Charsets.UTF_8),
            )
        }

        // التحقق الشرطي If-None-Match → 304
        val etag = etagOf(file)
        val inm = req.header("if-none-match")
        if (inm != null && etagMatches(inm, etag)) {
            return DavResponse(304, "Not Modified")
        }

        val len = file.length()
        val range = req.header("range")?.trim()

        if (range != null && range.startsWith("bytes=")) {
            val (start, end) = parseRange(range, len)
                ?: return DavResponse(
                    416, "Range Not Satisfiable",
                    mutableListOf("Content-Range" to "bytes */$len"),
                    bodyBytes = "range error".toByteArray(),
                )
            val head = mutableListOf<Pair<String, String>>(
                "Content-Type" to DavMime.of(file.name),
                "Accept-Ranges" to "bytes",
                "ETag" to etag,
                "Last-Modified" to httpDate(file.lastModified()),
                "Content-Range" to "bytes $start-$end/$len",
            )
            log?.append(NetEventType.DOWNLOAD, req.path, end - start + 1)
            if (!withBody) return DavResponse(206, "Partial Content", head, bodyLength = end - start + 1)
            return DavResponse(
                206, "Partial Content", head,
                bodyStream = sliceStream(file, start, end),
                bodyLength = end - start + 1,
            )
        }

        val head = mutableListOf<Pair<String, String>>(
            "Content-Type" to DavMime.of(file.name),
            "Accept-Ranges" to "bytes",
            "ETag" to etag,
            "Last-Modified" to httpDate(file.lastModified()),
        )
        if (!withBody) return DavResponse(200, "OK", head, bodyLength = len)
        log?.append(NetEventType.DOWNLOAD, req.path, len)
        return DavResponse(
            200, "OK", head,
            bodyStream = FileInputStream(file),
            bodyLength = len,
        )
    }

    private fun put(req: DavRequest): DavResponse {
        if (req.path.isEmpty()) {
            return DavResponse(405, "Method Not Allowed", bodyBytes = "cannot PUT root".toByteArray())
        }
        val file = fileFor(req.path)
        if (file.isDirectory) return DavResponse(409, "Conflict", bodyBytes = "target is a directory".toByteArray())

        // شروط قبلية
        val exists = file.exists()
        val inm = req.header("if-none-match")
        if (inm == "*" && exists) {
            return DavResponse(412, "Precondition Failed", bodyBytes = "exists".toByteArray())
        }
        val im = req.header("if-match")
        if (im != null && exists && !etagMatches(im, etagOf(file))) {
            return DavResponse(412, "Precondition Failed", bodyBytes = "etag mismatch".toByteArray())
        }

        val parent = file.parentFile
        if (parent == null || !parent.isDirectory) {
            return DavResponse(409, "Conflict", bodyBytes = "missing parent".toByteArray())
        }

        // كتابة عبر ملف مؤقت ثم إعادة تسمية ذرّية
        val tmp = File(parent, ".nova-${System.nanoTime()}.tmp")
        var written = 0L
        try {
            tmp.outputStream().buffered(BUFFER).use { out -> req.body.copyTo(out, BUFFER) }
            written = tmp.length()
            if (file.exists() && !file.delete()) throw IOException("cannot replace")
            if (!tmp.renameTo(file)) throw IOException("rename failed")
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            throw e
        } finally {
            runCatching { req.body.close() }
        }
        log?.append(NetEventType.UPLOAD, req.path, written)
        return if (exists) {
            DavResponse(204, "No Content")
        } else {
            DavResponse(201, "Created", mutableListOf("ETag" to etagOf(file)))
        }
    }

    private fun mkcol(req: DavRequest): DavResponse {
        drain(req.body, req.contentLength)
        if (req.path.isEmpty()) {
            return DavResponse(405, "Method Not Allowed", bodyBytes = "root exists".toByteArray())
        }
        val file = fileFor(req.path)
        if (file.exists()) {
            return DavResponse(405, "Method Not Allowed", bodyBytes = "exists".toByteArray())
        }
        val parent = file.parentFile
        if (parent == null || !parent.isDirectory) {
            return DavResponse(409, "Conflict", bodyBytes = "missing parent".toByteArray())
        }
        return if (file.mkdir()) {
            log?.append(NetEventType.MKDIR, req.path)
            DavResponse(201, "Created")
        } else {
            DavResponse(500, "Internal Server Error", bodyBytes = "mkdir failed".toByteArray())
        }
    }

    private fun delete(req: DavRequest): DavResponse {
        drain(req.body, req.contentLength)
        val file = fileFor(req.path)
        if (!file.exists()) return notFound()
        if (req.path.isEmpty()) {
            return DavResponse(403, "Forbidden", bodyBytes = "cannot delete root".toByteArray())
        }
        locks.remove(req.path)
        return if (deleteRec(file)) {
            log?.append(NetEventType.DELETE, req.path)
            DavResponse(204, "No Content")
        } else {
            DavResponse(500, "Internal Server Error", bodyBytes = "delete failed".toByteArray())
        }
    }

    private fun relocate(req: DavRequest, copy: Boolean): DavResponse {
        drain(req.body, req.contentLength)
        val src = fileFor(req.path)
        if (!src.exists()) return notFound()

        val destHeader = req.header("destination")
            ?: return DavResponse(400, "Bad Request", bodyBytes = "missing Destination".toByteArray())
        val rawDest = extractRawPath(destHeader)
        val destNorm = when (val r = DavPath.decodeAndNormalize(rawDest)) {
            is DavPathResult.Ok -> r.path
            is DavPathResult.Bad -> return DavResponse(400, "Bad Request", bodyBytes = "bad destination".toByteArray())
        }
        if (destNorm == req.path) {
            return DavResponse(403, "Forbidden", bodyBytes = "same source and destination".toByteArray())
        }
        val dest = fileFor(destNorm)
        val overwrite = req.header("overwrite")?.uppercase() != "F"
        if (dest.exists() && !overwrite) {
            return DavResponse(412, "Precondition Failed", bodyBytes = "destination exists".toByteArray())
        }

        val destParent = dest.parentFile
        if (destParent == null || !destParent.isDirectory) {
            return DavResponse(409, "Conflict", bodyBytes = "missing destination parent".toByteArray())
        }

        val overwrote = dest.exists()
        if (overwrote && !deleteRec(dest)) {
            return DavResponse(500, "Internal Server Error", bodyBytes = "cannot replace destination".toByteArray())
        }

        val ok = if (!copy && src.renameTo(dest)) {
            locks.remove(req.path)
            true
        } else if (src.isDirectory) {
            // مجلد: نسخ متكرر، والحذف بعد النسخ عند MOVE فقط (عندما يفشل rename)
            copyRec(src, dest) && (copy || deleteRec(src))
        } else {
            // ملف: نسخ (نتحقق من الحجم) والحذف بعد النسخ عند MOVE فقط
            val copied = src.copyTo(dest, overwrite = true).length() == src.length()
            copied && (copy || deleteRec(src))
        }
        if (!ok) return DavResponse(500, "Internal Server Error", bodyBytes = "relocate failed".toByteArray())

        log?.append(NetEventType.MOVE, "${req.path} -> $destNorm")
        return if (overwrote) DavResponse(204, "No Content") else DavResponse(201, "Created")
    }

    private fun lock(req: DavRequest): DavResponse {
        if (req.path.isEmpty()) {
            return DavResponse(403, "Forbidden", bodyBytes = "cannot lock root".toByteArray())
        }
        val file = fileFor(req.path)
        if (!file.exists()) return notFound()
        drain(req.body, req.contentLength)

        val timeout = req.header("timeout")
            ?.split(',')?.firstOrNull()?.trim()
            ?.removePrefix("Second-")?.toLongOrNull() ?: 600L
        val depth = req.header("depth")?.trim() ?: "infinity"
        val owner = req.header("if")?.takeIf { it.isNotBlank() } ?: req.header("user-agent")

        val existing = locks[req.path]
        val token = if (existing != null) {
            existing.token // تجديد القفل نفسه
        } else {
            "opaquelocktoken:nova-${java.util.UUID.randomUUID()}"
        }
        if (existing == null) {
            locks[req.path] = LockEntry(token, depth, owner)
        }
        val xml = DavXml.lockResponse(token, owner, timeout, depth)
        return DavResponse(
            200, "OK",
            mutableListOf(
                "Content-Type" to "application/xml; charset=utf-8",
                "Lock-Token" to "<$token>",
            ),
            bodyBytes = xml.toByteArray(Charsets.UTF_8),
        )
    }

    private fun unlock(req: DavRequest): DavResponse {
        val tokenHeader = req.header("lock-token")
            ?: return DavResponse(400, "Bad Request", bodyBytes = "missing Lock-Token".toByteArray())
        val token = tokenHeader.trim().trim('<', '>')
        val entry = locks[req.path] ?: return DavResponse(403, "Forbidden", bodyBytes = "no lock".toByteArray())
        if (entry.token != token) {
            return DavResponse(403, "Forbidden", bodyBytes = "token mismatch".toByteArray())
        }
        locks.remove(req.path)
        return DavResponse(204, "No Content")
    }

    // ═══════════════ الأدوات ═══════════════

    private fun entryOf(path: String, f: File): DavXml.Entry = DavXml.Entry(
        path = path,
        isCollection = f.isDirectory,
        size = f.length(),
        modifiedMillis = f.lastModified(),
        etag = if (f.isDirectory) null else etagOf(f),
        contentType = if (f.isDirectory) null else DavMime.of(f.name),
    )

    private fun childPath(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"

    /** مسار منسّط → ملف داخل الجذر حصراً (التسوية رفضت .. مسبقاً) */
    private fun fileFor(path: String): File =
        if (path.isEmpty()) root else File(root, path)

    fun etagOf(f: File): String =
        "\"${java.lang.Long.toHexString(f.length())}-${java.lang.Long.toHexString(f.lastModified())}\""

    private fun notFound(): DavResponse =
        DavResponse(404, "Not Found", bodyBytes = "not found".toByteArray())

    /** تفريغ جسم الطلب المتبقي (لصحة keep-alive) */
    private fun drain(body: InputStream, contentLength: Long) {
        if (contentLength <= 0) return
        runCatching {
            var remaining = contentLength
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val n = body.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                remaining -= n
            }
        }
        runCatching { body.close() }
    }

    /** يفك bytes=a-b / a- / -n إلى (بداية، نهاية) شاملة — null إن كان غير قابل للإرضاء */
    private fun parseRange(spec: String, length: Long): Pair<Long, Long>? {
        val s = spec.removePrefix("bytes=").trim()
        val dash = s.indexOf('-')
        if (dash < 0) return null
        val a = s.substring(0, dash).trim()
        val b = s.substring(dash + 1).trim()
        return when {
            a.isEmpty() -> {
                // آخر n بايت
                val n = b.toLongOrNull() ?: return null
                if (n <= 0) null else Pair(maxOf(0, length - n), length - 1)
            }
            else -> {
                val start = a.toLongOrNull() ?: return null
                if (start >= length) return null
                val end = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return null)
                if (end < start) null else Pair(start, minOf(end, length - 1))
            }
        }
    }

    private fun sliceStream(file: File, start: Long, endInclusive: Long): InputStream {
        val fin = FileInputStream(file)
        var skipped = 0L
        while (skipped < start) {
            val n = fin.skip(start - skipped)
            if (n <= 0) {
                runCatching { fin.close() }
                throw IOException("cannot seek")
            }
            skipped += n
        }
        val len = endInclusive - start + 1
        return object : InputStream() {
            var remaining = len
            override fun read(): Int {
                if (remaining <= 0) return -1
                val v = fin.read()
                if (v >= 0) remaining--
                return v
            }

            override fun read(b: ByteArray, off: Int, count: Int): Int {
                if (remaining <= 0) return -1
                val n = fin.read(b, off, minOf(count.toLong(), remaining).toInt())
                if (n > 0) remaining -= n
                return n
            }

            override fun close() {
                runCatching { fin.close() }
            }
        }
    }

    private fun etagMatches(headerValue: String, etag: String): Boolean =
        headerValue.split(',').any {
            val candidate = it.trim()
            candidate == "*" || candidate == etag || candidate.removePrefix("W/") == etag
        }

    private fun extractRawPath(destination: String): String = try {
        if (destination.startsWith("http://", true) || destination.startsWith("https://", true)) {
            URI(destination).rawPath ?: "/"
        } else {
            destination
        }
    } catch (_: Exception) {
        destination
    }

    private fun deleteRec(f: File): Boolean {
        if (f.isDirectory) {
            f.listFiles()?.forEach { if (!deleteRec(it)) return false }
        }
        return f.delete() || !f.exists()
    }

    private fun copyRec(src: File, dest: File): Boolean {
        if (src.isDirectory) {
            if (!dest.exists() && !dest.mkdirs()) return false
            src.listFiles()?.forEach {
                if (!copyRec(it, File(dest, it.name))) return false
            }
            return true
        }
        dest.parentFile?.mkdirs()
        return src.copyTo(dest, overwrite = true).length() == src.length()
    }

    companion object {
        private const val BUFFER = 64 * 1024
    }
}

/** خطأ HTTP معروف داخل المحرك — يتحول لاستجابة مباشرة */
class DavException(val status: Int, val reason: String, override val message: String? = null) : Exception(message)
