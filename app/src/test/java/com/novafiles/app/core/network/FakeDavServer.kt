package com.novafiles.app.core.network

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * خادم WebDAV مدمج للاختبارات — فوق ServerSocket من java.base خالص.
 *
 * (جُرِّب أولاً HttpServer من jdk.httpserver لكن K2 واجه مشكلة في تحليل
 * com.sun.net.httpserver داخل مجموعة الاختبار — ServerSocket أبسط وأضمن.)
 *
 * يغطي أفعال DAV التي يستهلكها WebDavFs: PROPFIND/GET(مع Range)/PUT(مع
 * If-Match/If-None-Match)/MKCOL/DELETE/MOVE/COPY، بمصادقة Basic اختيارية،
 * وhref مرمّزاً فعلياً (%XX) لاختبار فك الترميز بأسماء عربية وإيموجي.
 * كل طلب على اتصال منفصل (Connection: close) — بساطة فوق الأداء.
 *
 * هذا ملف اختبار فقط — لا يدخل في APK.
 */
class FakeDavServer(
    private val username: String = "",
    private val password: String = "",
) {
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val dirs = CopyOnWriteArraySet<String>()
    private val etags = ConcurrentHashMap<String, String>()
    private val etagSeq = AtomicInteger(0)

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private val pool = Executors.newCachedThreadPool()

    val port: Int get() = serverSocket?.localPort ?: 0

    /** آخر إجبار 412 (للتحقق من مسار التعارض) */
    val conflictCount = AtomicInteger(0)

    /** أفعال وصلت الخادم بالترتيب — لاختبارات المسار */
    val methodLog = ConcurrentLinkedQueue<String>()

    fun start() {
        val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        running = true
        pool.execute {
            while (running) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    break
                }
                pool.execute { runCatching { serveConnection(socket) } }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        pool.shutdownNow()
    }

    // ═══════════════ بذور المحتوى ═══════════════

    fun addFile(path: String, content: ByteArray) {
        val p = normalize(path)
        files[p] = content
        etags[p] = "\"e${etagSeq.incrementAndGet()}\""
        ensureParents(p)
    }

    fun addDir(path: String) {
        val p = normalize(path)
        dirs.add(p)
        ensureParents(p)
    }

    fun fileContent(path: String): ByteArray = files[normalize(path)]
        ?: throw IOException("fake server: missing file $path")

    fun etagOf(path: String): String = etags[normalize(path)] ?: ""

    private fun ensureParents(p: String) {
        var cur = p.trimEnd('/')
        while (cur.contains('/')) {
            cur = cur.substringBeforeLast('/')
            if (cur.isNotEmpty()) dirs.add(cur)
        }
    }

    private fun normalize(path: String): String {
        val p = path.trimEnd('/')
        return if (p.isEmpty() || p == "/") "" else p
    }

    private fun exists(p: String) = p.isEmpty() || p in dirs || files.containsKey(p)

    private fun parentOf(p: String): String = p.substringBeforeLast('/', "")

    private fun nameOf(p: String): String = p.trimEnd('/').substringAfterLast('/')

    private fun childrenOf(p: String): List<String> {
        val prefix = if (p.isEmpty()) "/" else "$p/"
        val all = files.keys.asSequence() + dirs.asSequence()
        return all.filter { it.startsWith(prefix) && it.trimEnd('/').substringAfterLast('/').isNotEmpty() }
            .filter { parentOf(it.trimEnd('/')) == p }
            .map { it.trimEnd('/') }
            .distinct()
            .sorted()
            .toList()
    }

    // ═══════════════ HTTP فوق المقابس ═══════════════

    /** طلب مُحلَّل */
    private class Req(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private fun serveConnection(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 20000
            val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            val req = try {
                readRequest(input)
            } catch (e: SocketTimeoutException) {
                null
            } ?: return
            methodLog.add(req.method)
            handle(req, output)
        }
    }

    private fun readRequest(input: BufferedInputStream): Req? {
        // سطر الطلب
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawPath = parts[1]

        // الترويسات
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }

        // فك ترميز المسار مقطعاً مقطعاً
        val path = normalize(
            rawPath.split('/').joinToString("/") { seg ->
                if (seg.isEmpty()) "" else DavParser.percentDecode(seg)
            },
        )

        // الجسم
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) ByteArray(contentLength).also { input.readFully(it) } else ByteArray(0)
        return Req(method, path, headers, body)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder(80)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun BufferedInputStream.readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n < 0) throw IOException("eof in body")
            off += n
        }
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        status: Int,
        reason: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ) {
        val sb = StringBuilder(128)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Server: FakeDav/1\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun error(output: BufferedOutputStream, code: Int, reason: String, msg: String = "") =
        writeResponse(output, code, reason, body = msg.toByteArray())

    // ═══════════════ المعالجة ═══════════════

    private fun handle(req: Req, output: BufferedOutputStream) {
        if (!checkAuth(req)) {
            writeResponse(
                output, 401, "Unauthorized",
                mapOf("WWW-Authenticate" to "Basic realm=\"nova\""),
            )
            return
        }
        when (req.method) {
            "PROPFIND" -> handlePropfind(req, output)
            "GET", "HEAD" -> handleGet(req, output)
            "PUT" -> handlePut(req, output)
            "MKCOL" -> handleMkcol(req, output)
            "DELETE" -> handleDelete(req, output)
            "MOVE" -> handleRelocate(req, output, copy = false)
            "COPY" -> handleRelocate(req, output, copy = true)
            else -> error(output, 405, "Method Not Allowed")
        }
    }

    private fun checkAuth(req: Req): Boolean {
        if (username.isEmpty()) return true
        val header = req.header("Authorization") ?: return false
        if (!header.startsWith("Basic ", true)) return false
        val decoded = runCatching {
            String(Base64.getDecoder().decode(header.removePrefix("Basic ").trim()), Charsets.UTF_8)
        }.getOrNull() ?: return false
        return decoded == "$username:$password"
    }

    private fun encodeHref(p: String): String =
        p.split('/').joinToString("/") { seg -> if (seg.isEmpty()) "" else urlEncodeSegment(seg) }

    private fun urlEncodeSegment(s: String): String {
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

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun httpDate(millis: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))

    private fun entryXml(p: String, isDir: Boolean, size: Long, modified: Long, etag: String?): String {
        val href = encodeHref(p) + if (isDir && p.isNotEmpty()) "/" else ""
        val rt = if (isDir) "<D:resourcetype><D:collection/></D:resourcetype>" else "<D:resourcetype/>"
        val len = if (isDir) "" else "<D:getcontentlength>$size</D:getcontentlength>"
        val et = etag?.let { "<D:getetag>${xmlEscape(it)}</D:getetag>" } ?: ""
        return "<D:response>" +
            "<D:href>$href</D:href>" +
            "<D:propstat><D:prop>" +
            "<D:displayname>${xmlEscape(nameOf(p))}</D:displayname>" +
            rt + len +
            "<D:getlastmodified>${httpDate(modified)}</D:getlastmodified>" +
            et +
            "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>" +
            "</D:response>"
    }

    private fun handlePropfind(req: Req, output: BufferedOutputStream) {
        val path = req.path
        if (!exists(path)) {
            error(output, 404, "Not Found", "not found")
            return
        }
        val depth = req.header("Depth")?.toIntOrNull() ?: 1
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<D:multistatus xmlns:D=\"DAV:\">")
        val etag = etags[path]
        sb.append(
            entryXml(
                path, isDir = path in dirs, size = (files[path]?.size ?: 0).toLong(),
                modified = 1730000000000L, etag = etag,
            ),
        )
        if (depth >= 1) {
            for (child in childrenOf(path)) {
                sb.append(
                    entryXml(
                        child, isDir = child in dirs, size = (files[child]?.size ?: 0).toLong(),
                        modified = 1730000000000L, etag = etags[child],
                    ),
                )
            }
        }
        sb.append("</D:multistatus>")
        writeResponse(
            output, 207, "Multi-Status",
            mapOf("Content-Type" to "application/xml; charset=utf-8"),
            sb.toString().toByteArray(Charsets.UTF_8),
        )
    }

    private fun handleGet(req: Req, output: BufferedOutputStream) {
        val path = req.path
        val data = files[path]
        if (data == null) {
            error(output, 404, "Not Found", "not found")
            return
        }
        val rangeHeader = req.header("Range")
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").trim()
            val start = spec.substringBefore('-').toLongOrNull() ?: 0L
            if (start >= data.size) {
                writeResponse(
                    output, 416, "Range Not Satisfiable",
                    mapOf("Content-Range" to "bytes */${data.size}"),
                )
                return
            }
            val slice = data.copyOfRange(start.toInt(), data.size)
            writeResponse(
                output, 206, "Partial Content",
                mapOf(
                    "Content-Range" to "bytes $start-${data.size - 1}/${data.size}",
                    "Content-Type" to "application/octet-stream",
                ),
                slice,
            )
            return
        }
        writeResponse(output, 200, "OK", mapOf("Content-Type" to "application/octet-stream"), data)
    }

    private fun handlePut(req: Req, output: BufferedOutputStream) {
        val path = req.path
        val inm = req.header("If-None-Match")
        val im = req.header("If-Match")
        if (inm == "*" && files.containsKey(path)) {
            conflictCount.incrementAndGet()
            error(output, 412, "Precondition Failed", "exists")
            return
        }
        if (im != null && im != "*" && files.containsKey(path) && etags[path] != im) {
            conflictCount.incrementAndGet()
            error(output, 412, "Precondition Failed", "etag mismatch")
            return
        }
        files[path] = req.body
        etags[path] = "\"e${etagSeq.incrementAndGet()}\""
        ensureParents(path)
        writeResponse(output, 201, "Created")
    }

    private fun handleMkcol(req: Req, output: BufferedOutputStream) {
        val path = req.path
        if (exists(path)) {
            error(output, 405, "Method Not Allowed", "exists")
            return
        }
        dirs.add(path)
        ensureParents(path)
        writeResponse(output, 201, "Created")
    }

    private fun handleDelete(req: Req, output: BufferedOutputStream) {
        val path = req.path
        if (!exists(path)) {
            error(output, 404, "Not Found", "not found")
            return
        }
        if (path in dirs) {
            val prefix = "$path/"
            files.keys.removeAll { it.startsWith(prefix) }
            etags.keys.removeAll { it.startsWith(prefix) }
            dirs.removeAll { it.startsWith(prefix) || it == path }
        } else {
            files.remove(path)
            etags.remove(path)
        }
        writeResponse(output, 204, "No Content")
    }

    private fun handleRelocate(req: Req, output: BufferedOutputStream, copy: Boolean) {
        val src = req.path
        if (!exists(src)) {
            error(output, 404, "Not Found", "source not found")
            return
        }
        val destHeader = req.header("Destination")
            ?: return error(output, 400, "Bad Request", "missing Destination")
        val destPath = normalize(
            extractPath(destHeader).split('/').joinToString("/") { seg ->
                if (seg.isEmpty()) "" else DavParser.percentDecode(seg)
            },
        )
        val overwrite = req.header("Overwrite")?.uppercase() != "F"
        if (exists(destPath) && !overwrite) {
            conflictCount.incrementAndGet()
            error(output, 412, "Precondition Failed", "destination exists")
            return
        }
        val isDir = src in dirs
        if (isDir) {
            val srcPrefix = "$src/"
            val destPrefix = "$destPath/"
            if (!copy) {
                files.keys.filter { it.startsWith(srcPrefix) }.forEach { key ->
                    files[destPrefix + key.removePrefix(srcPrefix)] = files.remove(key)!!
                    etags[destPrefix + key.removePrefix(srcPrefix)] = etags.remove(key)!!
                }
                dirs.removeAll { it.startsWith(srcPrefix) }
                dirs.remove(src)
            } else {
                files.keys.filter { it.startsWith(srcPrefix) }.forEach { key ->
                    files[destPrefix + key.removePrefix(srcPrefix)] = files[key]!!.copyOf()
                    etags[destPrefix + key.removePrefix(srcPrefix)] = "\"e${etagSeq.incrementAndGet()}\""
                }
            }
            dirs.add(destPath)
        } else {
            val data = files[src]!!
            if (!copy) {
                files.remove(src)
                etags.remove(src)
            }
            files[destPath] = data
            etags[destPath] = "\"e${etagSeq.incrementAndGet()}\""
            dirs.remove(destPath) // الهدف ملف لا مجلد
        }
        writeResponse(output, 201, "Created")
    }

    private fun extractPath(destination: String): String {
        val uri = URI(destination)
        return uri.rawPath ?: "/"
    }
}
