package com.novafiles.app.core.cloud

import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * v0.16 — مهايئ Google Drive (REST v3) فوق [HttpTransport].
 *
 * Drive يعرّف الملفات بمعرّفات لا مسارات — لذا يحتفظ المهايئ بذاكرة
 * path→id (LRU صغيرة) ويحلّ المسار بالسير من الجذر. الجذر "root" اسم مستعار
 * يقبله v3 مباشرة. القيود المعتمدة:
 *  - سرد: q='<id>' in parents + trashed=false، صفحات 200
 *  - مجلد: mimeType = application/vnd.google-apps.folder
 *  - رفع صغير (< 5MB): multipart/related؛ كبير: جلسة resumable بقطع 8MB
 *    مع استمرار الجلسة عبر [sessions] (تصمد بعد موت العملية)
 *  - تنزيل: alt=media يدعم Range للاستئناف
 *  - نقل خادمي: PATCH addParents/removeParents، ونسخ عبر /copy
 */
class DriveFs(
    private val transport: HttpTransport,
    private val tokenProvider: suspend () -> String,
    private val sessions: UploadSessionStore = InMemorySessionStore(),
    private val backoff: Backoff = Backoff(),
    private val accountId: String = "drive",
) : CloudFs {

    override val displayUrl: String = "drive://"

    /** ذاكرة مسار→معرّف — تجنّب السير من الجذر في كل عملية */
    private val pathIds = java.util.concurrent.ConcurrentHashMap<String, String>()

    companion object {
        const val BASE = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"

        /** حد الرفع البسيط (multipart) — فوقه جلسة resumable */
        const val SIMPLE_UPLOAD_LIMIT = 4L * 1024 * 1024
        /** حجم قطعة جلسة الرفع — مضاعف 256KiB كما يتطلب Drive */
        const val RESUMABLE_CHUNK = 8L * 1024 * 1024

        /** جلسات الرفع تنتهي بعد أسبوع (سياسة Drive) — نتجاوزها بعدها */
        const val SESSION_TTL_MS = 7L * 24 * 3600 * 1000

        fun fieldsParam(): String =
            "id,name,mimeType,size,modifiedTime,md5Checksum"

        fun isIso8601Date(t: String): Long = try {
            Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(t)).toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    // ---------- الترويسات المشتركة ----------

    private suspend fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer ${tokenProvider()}")

    private suspend fun authJson(contentType: String? = null): MutableMap<String, String> {
        val m = HashMap<String, String>()
        m["Authorization"] = "Bearer ${tokenProvider()}"
        if (contentType != null) m["Content-Type"] = contentType
        return m
    }

    // ---------- حل المسارات ----------

    private suspend fun resolveId(path: String): String {
        val clean = path.trim('/')
        if (clean.isEmpty()) {
            pathIds.putIfAbsent("", "root")
            return "root"
        }
        pathIds[clean]?.let { return it }
        // سِر من الأب — قابلة للاستدعاء المتكرر بلا دورة
        val parentPath = clean.substringBeforeLast('/', "")
        val name = clean.substringAfterLast('/')
        val parentId = resolveId(parentPath)
        val found = listFolderChildren(parentId).firstOrNull { it.name == name }
            ?: throw CloudNotFoundException("/$clean", "drive")
        pathIds[clean] = found.id
        return found.id
    }

    private suspend fun idOf(path: String): String = resolveId(path)

    private fun joinPath(parent: String, name: String): String = RemotePathUtil.join(parent, name)

    // ---------- السرد ----------

    private data class RawFile(
        val id: String,
        val name: String,
        val mimeType: String,
        val size: Long,
        val modifiedTime: Long,
        val md5: String?,
    )

    private suspend fun listFolderChildren(parentId: String): List<RawFile> {
        val out = ArrayList<RawFile>()
        var pageToken: String? = null
        do {
            val q = "'$parentId' in parents and trashed = false"
            val url = buildString {
                append("$BASE/files?q=")
                append(java.net.URLEncoder.encode(q, "UTF-8"))
                append("&fields=")
                append(java.net.URLEncoder.encode("nextPageToken,files(${fieldsParam()})", "UTF-8"))
                append("&pageSize=200&supportsAllDrives=true")
                if (pageToken != null) {
                    append("&pageToken=")
                    append(java.net.URLEncoder.encode(pageToken!!, "UTF-8"))
                }
            }
            val req = HttpJson.get(url, authHeaders())
            val resp = executeWithRetry(req)
            val fields = MiniJson.parseObject(resp.text)
            for (f in MiniJson.asArray(fields["files"])) {
                val o = MiniJson.asObject(f)
                out.add(
                    RawFile(
                        id = MiniJson.asString(o["id"]) ?: continue,
                        name = MiniJson.asString(o["name"]) ?: "",
                        mimeType = MiniJson.asString(o["mimeType"]) ?: "",
                        size = MiniJson.asLong(o["size"]) ?: 0L,
                        modifiedTime = MiniJson.asString(o["modifiedTime"])?.let { isIso8601Date(it) } ?: 0L,
                        md5 = MiniJson.asString(o["md5Checksum"]),
                    ),
                )
            }
            pageToken = MiniJson.asString(fields["nextPageToken"])
        } while (pageToken != null)
        return out
    }

    private fun RawFile.toEntry(path: String) = CloudEntry(
        name = name,
        path = path,
        isDir = mimeType == FOLDER_MIME,
        size = size,
        lastModified = modifiedTime,
        remoteId = id,
        etag = md5,
    )

    override suspend fun list(path: String): List<CloudEntry> {
        val parentId = idOf(path)
        val children = listFolderChildren(parentId)
        val prefix = if (path.trim('/').isEmpty()) "" else path.trimEnd('/')
        return children
            .map { it.toEntry("$prefix/${it.name}") }
            .sortedWith(compareByDescending<CloudEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun stat(path: String): CloudEntry {
        val clean = path.trim('/')
        if (clean.isEmpty()) {
            return CloudEntry(name = "Drive", path = "", isDir = true, size = 0, lastModified = 0, remoteId = "root")
        }
        val id = idOf(path)
        val url = "$BASE/files/$id?fields=${java.net.URLEncoder.encode(fieldsParam(), "UTF-8")}&supportsAllDrives=true"
        val resp = executeWithRetry(HttpJson.get(url, authHeaders()))
        val o = MiniJson.parseObject(resp.text)
        return CloudEntry(
            name = MiniJson.asString(o["name"]) ?: clean.substringAfterLast('/'),
            path = "/$clean",
            isDir = MiniJson.asString(o["mimeType"]) == FOLDER_MIME,
            size = MiniJson.asLong(o["size"]) ?: 0L,
            lastModified = MiniJson.asString(o["modifiedTime"])?.let { isIso8601Date(it) } ?: 0L,
            remoteId = MiniJson.asString(o["id"]),
            etag = MiniJson.asString(o["md5Checksum"]),
        )
    }

    // ---------- القراءة ----------

    override suspend fun readFrom(path: String, offset: Long, consumer: suspend (InputStream) -> Unit) {
        val id = idOf(path)
        val headers = HashMap(authHeaders())
        if (offset > 0) headers["Range"] = "bytes=$offset-"
        val url = "$BASE/files/$id?alt=media&supportsAllDrives=true"
        val req = HttpRequest("GET", url, headers)
        val backoff = this.backoff
        var attempt = 0
        while (true) {
            try {
                transport.executeStreaming(req) { code, _, body ->
                    if (code !in 200..299 && code != 206) {
                        throw HttpException(code, url, "(stream)")
                    }
                    consumer(body)
                }
                return
            } catch (e: HttpException) {
                val decision = backoff.decision(e.code, attempt)
                if (decision.stop) throw e
                backoff.wait(decision)
                attempt++
            }
        }
    }

    // ---------- الرفع ----------

    override suspend fun writeFrom(source: File, dir: String, onProgress: (Long) -> Unit): CloudEntry {
        val parentId = idOf(dir)
        val path = joinPath(dir, source.name)
        val key = "$accountId:$path"
        if (source.length() <= SIMPLE_UPLOAD_LIMIT) {
            sessions.remove(key)
            val bytes = source.readBytes()
            val entry = simpleUploadBytes(bytes, source.name, parentId, path)
            onProgress(bytes.size.toLong())
            return entry
        }
        return resumableUpload(
            path = path,
            name = source.name,
            parentId = parentId,
            size = source.length(),
            sessionKey = key,
            openInput = { source.inputStream() },
            onProgress = onProgress,
        )
    }

    override suspend fun writeFromStream(
        dir: String,
        name: String,
        size: Long,
        openInput: () -> InputStream,
        onProgress: (Long) -> Unit,
    ): CloudEntry {
        val parentId = idOf(dir)
        val path = joinPath(dir, name)
        if (size in 1..SIMPLE_UPLOAD_LIMIT) {
            // صغير: multipart كامل من الذاكرة
            val bytes = openInput().use { it.readBytes() }
            return simpleUploadBytes(bytes, name, parentId, path)
        }
        return resumableUpload(
            path = path,
            name = name,
            parentId = parentId,
            size = size,
            sessionKey = "$accountId:$path",
            openInput = openInput,
            onProgress = onProgress,
        )
    }

    private suspend fun simpleUploadBytes(
        content: ByteArray,
        name: String,
        parentId: String,
        path: String,
    ): CloudEntry {
        val metadata = """{"name":${jsonString(name)},"parents":["$parentId"]}"""
        val boundary = "nova_${System.nanoTime().toString(36)}"
        val pre = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val post = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val body = pre + content + post

        val url = "$UPLOAD_BASE/files?uploadType=multipart&fields=" +
            java.net.URLEncoder.encode(fieldsParam(), "UTF-8") + "&supportsAllDrives=true"
        val req = HttpRequest(
            method = "POST",
            url = url,
            headers = authJson("multipart/related; boundary=$boundary"),
            body = body,
        )
        val resp = executeWithRetry(req)
        return entryFromJson(MiniJson.parseObject(resp.text), path)
    }

    /**
     * جلسة رفع قابلة للاستئناف:
     *  1. POST بـ uploadType=resumable ← رابط الجلسة (يُخزَّن)
     *  2. PUT قطع 8MB بـ Content-Range؛ عند 308 نقرأ Range=0-N ونكمل
     *  3. عند موت العملية: نستأنف من sessions بمفتاح مسار الحساب
     */
    private suspend fun resumableUpload(
        path: String,
        name: String,
        parentId: String,
        size: Long,
        sessionKey: String,
        openInput: () -> InputStream,
        onProgress: (Long) -> Unit,
    ): CloudEntry {
        var session = sessions.load(sessionKey)
        if (session != null && System.currentTimeMillis() - session.createdAtMs > SESSION_TTL_MS) {
            session = null
            sessions.remove(sessionKey)
        }
        var uploadUrl = session?.url ?: createResumableSession(name, parentId, size)
        var offset = session?.offset ?: 0L
        if (session == null) sessions.save(sessionKey, UploadSessionStore.UploadSession(uploadUrl, 0, System.currentTimeMillis(), size))

        var attempt = 0
        while (true) {
            val chunkEnd = minOf(offset + RESUMABLE_CHUNK, size) - 1
            if (offset >= size) {
                // كل البايتات أُرسلت — نستفسر عن الحالة النهائية
                val status = querySession(uploadUrl, path)
                if (status != null) {
                    sessions.remove(sessionKey)
                    return status
                }
                backoff.wait(backoff.decision(500, attempt++))
                continue
            }
            val code = transport.executeStreaming(
                request = HttpRequest(
                    method = "PUT",
                    url = uploadUrl,
                    headers = mapOf(
                        "Authorization" to "Bearer ${tokenProvider()}",
                        "Content-Range" to "bytes $offset-$chunkEnd/$size",
                        "Content-Type" to "application/octet-stream",
                    ),
                ),
                bodyLength = chunkEnd - offset + 1,
                openBody = {
                    val stream = openInput()
                    stream.skip(offset)
                    LimitedStream(stream, chunkEnd - offset + 1)
                },
                onResponse = { _, _, _ -> /* الجسم صغير (308/200 مع JSON) */ },
            )
            when (code) {
                308 -> {
                    // نطلب من الخادم كم استلم فعلاً — ثم نحدّث الإزاحة والجلسة
                    val range = probeSessionRange(uploadUrl)
                    offset = (range?.second?.plus(1)) ?: (chunkEnd + 1)
                    sessions.save(sessionKey, UploadSessionStore.UploadSession(uploadUrl, offset, System.currentTimeMillis(), size))
                    onProgress(offset)
                    attempt = 0
                }
                in 200..299 -> {
                    sessions.remove(sessionKey)
                    onProgress(size)
                    // نقرأ بيانات الملف الناتج — إعادة PUT لجلب JSON غير عملي؛
                    // نستفسر stat مباشرة بالمسار
                    return stat(path)
                }
                404 -> {
                    // الجلسة انتهت/فُقدت — نبدأ من جديد
                    sessions.remove(sessionKey)
                    uploadUrl = createResumableSession(name, parentId, size)
                    offset = 0
                    attempt = 0
                }
                else -> {
                    val decision = backoff.decision(code, attempt)
                    if (decision.stop) throw HttpException(code, uploadUrl, "(resumable)")
                    backoff.wait(decision)
                    // بعد خطأ مطلوب استقصاء الإزاحة الفعلية من الخادم
                    probeSessionRange(uploadUrl)?.let { offset = it.second + 1 }
                    attempt++
                }
            }
        }
    }

    private suspend fun createResumableSession(name: String, parentId: String, size: Long): String {
        val metadata = """{"name":${jsonString(name)},"parents":["$parentId"]}"""
        val url = "$UPLOAD_BASE/files?uploadType=resumable&fields=" +
            java.net.URLEncoder.encode(fieldsParam(), "UTF-8") + "&supportsAllDrives=true"
        val req = HttpRequest(
            method = "POST",
            url = url,
            headers = authJson("application/json; charset=UTF-8") +
                mapOf("X-Upload-Content-Length" to size.toString()),
            body = metadata.toByteArray(Charsets.UTF_8),
        )
        val resp = executeWithRetry(req)
        if (resp.code !in 200..299) throw HttpException(resp.code, url, resp.text.take(120))
        return resp.header("Location")
            ?: throw java.io.IOException("drive: resumable session missing Location")
    }

    /**
     * استقصاء حالة الجلسة عندما أُرسلت كل البايتات: PUT فارغ بـ ترويسة Content-Range النجمية
     * — إن كانت الجلسة مكتملة يعيد الخادم 200 مع JSON الملف الناتج.
     */
    private suspend fun querySession(uploadUrl: String, path: String): CloudEntry? {
        var entryJson: String? = null
        val code = transport.executeStreaming(
            request = HttpRequest(
                method = "PUT",
                url = uploadUrl,
                headers = mapOf(
                    "Authorization" to "Bearer ${tokenProvider()}",
                    "Content-Range" to "bytes */*",
                ),
            ),
            onResponse = { _, _, body ->
                entryJson = body.readBytes().toString(Charsets.UTF_8)
            },
        )
        if (code in 200..299 && entryJson != null) {
            return runCatching { entryFromJson(MiniJson.parseObject(entryJson!!), path) }.getOrNull()
        }
        return null
    }

    /** استعلام الإزاحة المستلمة — يعيد (start,end) من ترويسة Range في 308 */
    private suspend fun probeSessionRange(uploadUrl: String): Pair<Long, Long>? {
        var rangeHeader: String? = null
        val code = transport.executeStreaming(
            request = HttpRequest(
                method = "PUT",
                url = uploadUrl,
                headers = mapOf(
                    "Authorization" to "Bearer ${tokenProvider()}",
                    "Content-Range" to "bytes */*",
                ),
            ),
            onResponse = { _, headers, _ -> rangeHeader = headers.entries.firstOrNull { it.key.equals("Range", true) }?.value },
        )
        if (code != 308) return null
        val range = rangeHeader ?: return null
        // Range: bytes=0-123456
        val dash = range.indexOf('-')
        if (dash < 0) return null
        val end = range.substring(dash + 1).trim().trimEnd('*').toLongOrNull() ?: return null
        val start = range.substring(6, dash).trim().toLongOrNull() ?: 0L
        return start to end
    }

    // ---------- الكتابة البنيوية ----------

    override suspend fun mkdir(parent: String, name: String): CloudEntry {
        val parentId = idOf(parent)
        val body = """{"name":${jsonString(name)},"mimeType":"$FOLDER_MIME","parents":["$parentId"]}"""
        val url = "$BASE/files?fields=${java.net.URLEncoder.encode(fieldsParam(), "UTF-8")}&supportsAllDrives=true"
        val resp = executeWithRetry(HttpJson.postJson(url, body, authHeaders()))
        val o = MiniJson.parseObject(resp.text)
        val id = MiniJson.asString(o["id"])!!
        val path = joinPath(parent, name)
        pathIds[path.trim('/')] = id
        return CloudEntry(name, path, true, 0, System.currentTimeMillis(), id, null)
    }

    override suspend fun delete(path: String, isDir: Boolean) {
        val id = idOf(path)
        val resp = executeWithRetry(HttpJson.delete("$BASE/files/$id?supportsAllDrives=true", authHeaders()))
        if (resp.code !in 200..299 && resp.code != 204) throw HttpException(resp.code, path, resp.text.take(120))
        pathIds.remove(path.trim('/'))
        invalidateChildren(path)
    }

    override suspend fun rename(path: String, newName: String) {
        val id = idOf(path)
        val body = """{"name":${jsonString(newName)}}"""
        val resp = retryablePatch(
            "$BASE/files/$id?fields=${java.net.URLEncoder.encode(fieldsParam(), "UTF-8")}&supportsAllDrives=true",
            body,
        )
        throwIfFailed(resp, path)
        val newPath = joinPath(RemotePathUtil.parentOf(path), newName)
        pathIds.remove(path.trim('/'))
        pathIds[newPath.trim('/')] = id
    }

    override suspend fun move(path: String, targetDir: String, newName: String) {
        val id = idOf(path)
        val oldParentId = idOf(RemotePathUtil.parentOf(path))
        val newParentId = idOf(targetDir)
        val body = buildString {
            append("{")
            append("\"name\":").append(jsonString(newName))
            append(",\"addParents\":[\"$newParentId\"]")
            append(",\"removeParents\":[\"$oldParentId\"]")
            append("}")
        }
        val resp = retryablePatch("$BASE/files/$id?supportsAllDrives=true", body)
        throwIfFailed(resp, path)
        pathIds.remove(path.trim('/'))
        pathIds[joinPath(targetDir, newName).trim('/')] = id
    }

    override suspend fun copy(path: String, targetDir: String, newName: String) {
        val id = idOf(path)
        val newParentId = idOf(targetDir)
        val body = """{"name":${jsonString(newName)},"parents":["$newParentId"]}"""
        val resp = executeWithRetry(
            HttpJson.postJson(
                "$BASE/files/$id/copy?supportsAllDrives=true",
                body,
                authJson("application/json; charset=UTF-8"),
            ),
        )
        throwIfFailed(resp, path)
    }

    override suspend fun test(): String {
        val resp = executeWithRetry(
            HttpJson.get("$BASE/files/root?fields=${java.net.URLEncoder.encode("id,name", "UTF-8")}", authHeaders()),
        )
        if (resp.code == 401) throw HttpException(401, "drive", "unauthorized")
        throwIfFailed(resp, "root")
        return MiniJson.asString(MiniJson.parseObject(resp.text)["name"]) ?: "My Drive"
    }

    // ---------- أدوات ----------

    private suspend fun executeWithRetry(req: HttpRequest): HttpResponse {
        var attempt = 0
        while (true) {
            val resp = transport.execute(req.newBuilder().header("Authorization", "Bearer ${tokenProvider()}").build())
            if (resp.isSuccessful) return resp
            val decision = backoff.decision(resp.code, attempt)
            if (decision.stop) throw HttpException(resp.code, req.url, resp.text.take(160))
            backoff.wait(decision)
            attempt++
        }
    }

    /** PATCH مع إعادة محاولة — Drive يتطلبه للتعديل (rename/move) */
    private suspend fun retryablePatch(url: String, json: String): HttpResponse {
        var attempt = 0
        while (true) {
            val resp = transport.execute(
                HttpRequest(
                    method = "PATCH",
                    url = url,
                    headers = authJson("application/json; charset=UTF-8"),
                    body = json.toByteArray(Charsets.UTF_8),
                ),
            )
            if (resp.isSuccessful) return resp
            val decision = backoff.decision(resp.code, attempt)
            if (decision.stop) throw HttpException(resp.code, url, resp.text.take(160))
            backoff.wait(decision)
            attempt++
        }
    }

    private fun throwIfFailed(resp: HttpResponse, where: String) {
        if (!resp.isSuccessful) {
            if (resp.code == 404) throw CloudNotFoundException(where, "drive")
            if (resp.code == 409) throw CloudConflictException(where, "drive")
            throw HttpException(resp.code, where, resp.text.take(160))
        }
    }

    private fun entryFromJson(o: Map<String, MiniJson.Value>, path: String): CloudEntry =
        CloudEntry(
            name = MiniJson.asString(o["name"]) ?: path.substringAfterLast('/'),
            path = path,
            isDir = MiniJson.asString(o["mimeType"]) == FOLDER_MIME,
            size = MiniJson.asLong(o["size"]) ?: 0L,
            lastModified = MiniJson.asString(o["modifiedTime"])?.let { isIso8601Date(it) } ?: 0L,
            remoteId = MiniJson.asString(o["id"]),
            etag = MiniJson.asString(o["md5Checksum"]),
        )

    private fun invalidateChildren(path: String) {
        val prefix = "${path.trim('/')}/"
        pathIds.keys.removeAll { it.startsWith(prefix) }
    }

    override fun close() {
        pathIds.clear()
    }
}

/** تدفق محدود الطول — يقرأ حتى n بايتاً ثم EOF (قطعة الجلسة) */
internal class LimitedStream(private val inner: InputStream, private val limit: Long) : InputStream() {
    private var remaining = limit
    override fun read(): Int {
        if (remaining <= 0) return -1
        val v = inner.read()
        if (v >= 0) remaining--
        return v
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = inner.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }
}

/** أدوات مسارات مشتركة بين المهايئات */
object RemotePathUtil {
    fun join(parent: String, name: String): String =
        if (parent.isBlank() || parent == "/") "/$name"
        else "${parent.trimEnd('/')}/$name"

    fun parentOf(path: String): String {
        val p = path.trimEnd('/')
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "" else p.substring(0, idx)
    }

    fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')
}

/** تهريب سلسلة JSON يدوي — الأسماء قد تحوي اقتباسات/سطور */
internal fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 8)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
