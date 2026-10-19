package com.novafiles.app.core.cloud

import java.io.File
import java.io.InputStream

/**
 * v0.16 — مهايئ OneDrive (Microsoft Graph v1.0) فوق [HttpTransport].
 *
 * Graph مساري بعنونة root:/path:/ — العمليات:
 *  - سرد: GET /me/drive/root:{path}:/children (صفحات @odata.nextLink)
 *  - تنزيل: GET /me/drive/items/{id}/content (إعادة توجيه إلى CDN، يدعم Range)
 *  - رفع صغير (< 4MB): PUT /me/drive/root:{path}:/content
 *    كبير: createUploadSession ثم PUT قطع مضاعفات 320KiB بـ Content-Range
 *    (نستخدم قطع 8MB) — الرابط قابل للاستئناف عبر [sessions]
 *  - بنية: POST children (folder facet)، DELETE items/{id}، PATCH name/parentReference
 *  - النسخ الخادمي غير متزامن (202 + monitor URL) — ننتظر لا نهائياً لا، بل نجاح متعدد الاستقصاء
 */
class OneDriveFs(
    private val transport: HttpTransport,
    private val tokenProvider: suspend () -> String,
    private val sessions: UploadSessionStore = InMemorySessionStore(),
    private val backoff: Backoff = Backoff(),
    private val accountId: String = "onedrive",
) : CloudFs {

    override val displayUrl: String = "onedrive://"

    companion object {
        const val GRAPH = "https://graph.microsoft.com/v1.0"

        /** حد الرفع البسيط — Graph يقبل حتى 4MB عبر PUT المباشر */
        const val SIMPLE_UPLOAD_LIMIT = 4L * 1024 * 1024

        /** قطعة جلسة الرفع — مضاعف 320KiB (متطلب Graph الصارم): 327,680 × 25 */
        const val SESSION_CHUNK = 8_192_000L

        /** جلسات الرفع صالحة أياماً قليلة — نتجاوز بعد 24 ساعة احتياطاً */
        const val SESSION_TTL_MS = 24L * 3600 * 1000
    }

    private suspend fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer ${tokenProvider()}")

    private fun joinPath(parent: String, name: String): String = RemotePathUtil.join(parent, name)

    // ---------- عنونة المسارات ----------

    /** root:{path}:/ — والعنوان للجذر هو /me/drive/root مباشرة */
    private fun itemUrl(path: String): String {
        val clean = path.trim('/')
        return if (clean.isEmpty()) "$GRAPH/me/drive/root"
        else "$GRAPH/me/drive/root:/${encodePath(clean)}:/"
    }

    private fun childrenUrl(path: String): String {
        val clean = path.trim('/')
        return if (clean.isEmpty()) "$GRAPH/me/drive/root/children"
        else "$GRAPH/me/drive/root:/${encodePath(clean)}:/children"
    }

    /** ترميز كل مفصل مسار في عنونة Graph (الـ"/" بين المفاصل يبقى) */
    private fun encodePath(clean: String): String =
        clean.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }

    // ---------- السرد ----------

    private fun entryFromItem(o: Map<String, MiniJson.Value>, parentPath: String): CloudEntry {
        val name = MiniJson.asString(o["name"]) ?: ""
        // facet المجلد موجود للدلائل، وpackage للمجلدات الخاصة — كلاهما يُعامل مجلداً
        val isDir = (o["folder"] != null && o["folder"] !is MiniJson.Value.Null) ||
            (o["package"] != null && o["package"] !is MiniJson.Value.Null)
        return CloudEntry(
            name = name,
            path = joinPath(parentPath, name),
            isDir = isDir,
            size = MiniJson.asLong(o["size"]) ?: 0L,
            lastModified = MiniJson.asString(o["lastModifiedDateTime"])?.let { IsoDate.toMs(it) } ?: 0L,
            remoteId = MiniJson.asString(o["id"]),
            etag = MiniJson.asString(o["eTag"]),
        )
    }

    override suspend fun list(path: String): List<CloudEntry> {
        val out = ArrayList<CloudEntry>()
        var url: String? = childrenUrl(path)
        while (url != null) {
            val resp = retryGet(url)
            val o = MiniJson.parseObject(resp.text)
            out.addAll(MiniJson.asArray(o["value"]).map { entryFromItem(MiniJson.asObject(it), path) })
            url = MiniJson.asString(o["@odata.nextLink"])
        }
        return out.sortedWith(compareByDescending<CloudEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun stat(path: String): CloudEntry {
        val clean = path.trim('/')
        if (clean.isEmpty()) {
            return CloudEntry("OneDrive", "", true, 0, 0, null, null)
        }
        val resp = retryGet(itemUrl(path))
        return entryFromItem(MiniJson.parseObject(resp.text), RemotePathUtil.parentOf(path))
    }

    // ---------- القراءة ----------

    override suspend fun readFrom(path: String, offset: Long, consumer: suspend (InputStream) -> Unit) {
        val id = stat(path).remoteId ?: throw CloudNotFoundException(path, "onedrive")
        val headers = HashMap(authHeaders())
        if (offset > 0) headers["Range"] = "bytes=$offset-"
        val url = "$GRAPH/me/drive/items/$id/content"
        var attempt = 0
        while (true) {
            try {
                transport.executeStreaming(HttpRequest("GET", url, headers)) { code, _, body ->
                    // Graph يعيد 302 إلى CDN — OkHttp يتبعها داخلياً فيصل 200/206
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
        val path = joinPath(dir, source.name)
        val key = "$accountId:$path"
        if (source.length() <= SIMPLE_UPLOAD_LIMIT) {
            sessions.remove(key)
            val bytes = source.readBytes()
            val entry = simpleUploadBytes(bytes, path)
            onProgress(bytes.size.toLong())
            return entry
        }
        return sessionUpload(
            path = path,
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
        val path = joinPath(dir, name)
        if (size <= SIMPLE_UPLOAD_LIMIT) {
            val bytes = openInput().use { it.readBytes() }
            return simpleUploadBytes(bytes, path)
        }
        return sessionUpload(
            path = path,
            size = size,
            sessionKey = "$accountId:$path",
            openInput = openInput,
            onProgress = onProgress,
        )
    }

    private suspend fun simpleUploadBytes(content: ByteArray, path: String): CloudEntry {
        val url = "$GRAPH/me/drive/root:/${encodePath(path.trim('/'))}:/content"
        var attempt = 0
        while (true) {
            val resp = transport.execute(
                HttpRequest(
                    method = "PUT",
                    url = url,
                    headers = authHeaders() + mapOf("Content-Type" to "application/octet-stream"),
                    body = content,
                ),
            )
            if (resp.isSuccessful) {
                return entryFromItem(MiniJson.parseObject(resp.text), RemotePathUtil.parentOf(path))
            }
            val decision = backoff.decision(resp.code, attempt)
            if (decision.stop) throw HttpException(resp.code, url, resp.text.take(160))
            backoff.wait(decision)
            attempt++
        }
    }

    /**
     * جلسة Graph: POST createUploadSession ← uploadUrl، ثم PUT قطع بـ
     * Content-Range bytes X-Y/total — 201/202 للقطعة و200/201 النهائية مع
     * metadata الملف. الاستئناف: PUT فارغ بترويسة Content-Range النجمية يعيد 200 مع
     * nextExpectedRanges.
     */
    private suspend fun sessionUpload(
        path: String,
        size: Long,
        sessionKey: String,
        openInput: () -> InputStream,
        onProgress: (Long) -> Unit,
    ): CloudEntry {
        var saved = sessions.load(sessionKey)
        if (saved != null && System.currentTimeMillis() - saved.createdAtMs > SESSION_TTL_MS) {
            saved = null
            sessions.remove(sessionKey)
        }
        var uploadUrl = saved?.url ?: createUploadSession(path, size)
        var offset = saved?.offset ?: 0L
        if (saved == null) {
            sessions.save(sessionKey, UploadSessionStore.UploadSession(uploadUrl, 0, System.currentTimeMillis(), size))
        }

        while (offset < size) {
            val chunkEnd = minOf(offset + SESSION_CHUNK, size) - 1
            val code = transport.executeStreaming(
                request = HttpRequest(
                    method = "PUT",
                    url = uploadUrl,
                    headers = mapOf(
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
                onResponse = { _, _, _ -> },
            )
            when {
                code in 200..299 -> {
                    offset = chunkEnd + 1
                    if (offset >= size) {
                        sessions.remove(sessionKey)
                        onProgress(size)
                        return stat(path)
                    }
                    sessions.save(sessionKey, UploadSessionStore.UploadSession(uploadUrl, offset, System.currentTimeMillis(), size))
                    onProgress(offset)
                }
                code == 416 || code == 404 -> {
                    // الجلسة انتهت أو الفاصل فاسد — من جديد
                    sessions.remove(sessionKey)
                    uploadUrl = createUploadSession(path, size)
                    offset = 0
                }
                else -> {
                    val decision = backoff.decision(code, 0)
                    if (decision.stop) throw HttpException(code, uploadUrl, "(session)")
                    backoff.wait(decision)
                    // استقصاء الإزاحة المتوقعة من الخادم
                    nextExpectedOffset(uploadUrl, size)?.let { offset = it }
                }
            }
        }
        return stat(path)
    }

    private suspend fun createUploadSession(path: String, size: Long): String {
        val body = """{"item":{"@microsoft.graph.conflictBehavior":"replace"}}"""
        val resp = transport.execute(
            HttpJson.postJson(
                "$GRAPH/me/drive/root:/${encodePath(path.trim('/'))}:/createUploadSession",
                body,
                authHeaders(),
            ),
        )
        if (!resp.isSuccessful) throw HttpException(resp.code, path, resp.text.take(160))
        return MiniJson.asString(MiniJson.parseObject(resp.text)["uploadUrl"])
            ?: throw java.io.IOException("onedrive: uploadUrl missing")
    }

    /** nextExpectedRanges مثل ["123456-"] — يعيد الإزاحة التالية أو null */
    private suspend fun nextExpectedOffset(uploadUrl: String, size: Long): Long? {
        var ranges: List<String> = emptyList()
        val code = transport.executeStreaming(
            request = HttpRequest(
                method = "PUT",
                url = uploadUrl,
                headers = mapOf("Content-Range" to "bytes */$size"),
            ),
            onResponse = { _, _, body ->
                val text = body.readBytes().toString(Charsets.UTF_8)
                runCatching { MiniJson.parseObject(text) }.getOrNull()?.let { o ->
                    ranges = MiniJson.asArray(o["nextExpectedRanges"]).mapNotNull { MiniJson.asString(it) }
                }
            },
        )
        if (code !in 200..299 && code != 206) return null
        return ranges.firstOrNull()?.substringBefore('-')?.toLongOrNull()
    }

    // ---------- البنية ----------

    override suspend fun mkdir(parent: String, name: String): CloudEntry {
        val body = """{"name":${jsonString(name)},"folder":{},"@microsoft.graph.conflictBehavior":"fail"}"""
        val resp = retryJson(HttpJson.postJson(childrenUrl(parent), body, authHeaders()))
        return entryFromItem(MiniJson.parseObject(resp.text), parent)
    }

    override suspend fun delete(path: String, isDir: Boolean) {
        val id = stat(path).remoteId ?: throw CloudNotFoundException(path, "onedrive")
        var attempt = 0
        while (true) {
            val resp = transport.execute(HttpJson.delete("$GRAPH/me/drive/items/$id", authHeaders()))
            if (resp.isSuccessful || resp.code == 404) return
            val decision = backoff.decision(resp.code, attempt)
            if (decision.stop) throw HttpException(resp.code, path, resp.text.take(160))
            backoff.wait(decision)
            attempt++
        }
    }

    override suspend fun rename(path: String, newName: String) {
        val id = stat(path).remoteId ?: throw CloudNotFoundException(path, "onedrive")
        val body = """{"name":${jsonString(newName)}}"""
        val resp = retryJson(
            HttpRequest(
                method = "PATCH",
                url = "$GRAPH/me/drive/items/$id",
                headers = authJsonHeaders(),
                body = body.toByteArray(Charsets.UTF_8),
            ),
        )
        if (!resp.isSuccessful) throw HttpException(resp.code, path, resp.text.take(160))
    }

    override suspend fun move(path: String, targetDir: String, newName: String) {
        val id = stat(path).remoteId ?: throw CloudNotFoundException(path, "onedrive")
        val parentId = stat(targetDir).remoteId ?: throw CloudNotFoundException(targetDir, "onedrive")
        val body = """{"name":${jsonString(newName)},"parentReference":{"id":"$parentId"}}"""
        val resp = retryJson(
            HttpRequest(
                method = "PATCH",
                url = "$GRAPH/me/drive/items/$id",
                headers = authJsonHeaders(),
                body = body.toByteArray(Charsets.UTF_8),
            ),
        )
        if (!resp.isSuccessful) throw HttpException(resp.code, path, resp.text.take(160))
    }

    override suspend fun copy(path: String, targetDir: String, newName: String): Unit {
        val id = stat(path).remoteId ?: throw CloudNotFoundException(path, "onedrive")
        val parentId = stat(targetDir).remoteId ?: throw CloudNotFoundException(targetDir, "onedrive")
        val body = buildString {
            append("{")
            append("\"name\":").append(jsonString(newName))
            append(",\"parentReference\":{\"id\":\"$parentId\"}")
            append("}")
        }
        var attempt = 0
        var monitor: String? = null
        // 202 ← رابط مراقبة غير متزامن، نستقصائه حتى الاكتمال
        val createResp = transport.execute(
            HttpJson.postJson("$GRAPH/me/drive/items/$id/copy", body, authHeaders()),
        )
        when {
            // 202 أولاً — فهي ضمن 2xx أيضاً لكنها تعني نسخاً غير متزامن
            createResp.code == 202 -> monitor = createResp.header("Location")
            createResp.code in 200..299 -> return // نسخ متزامن (نادر)
            else -> throw HttpException(createResp.code, path, createResp.text.take(160))
        }
        while (monitor != null) {
            backoff.wait(Backoff.Decision(stop = false, delayMs = 500))
            val resp = transport.execute(HttpJson.get(monitor))
            when (resp.code) {
                200, 202 -> {
                    // لا يزال جارياً — Graph يعيد 200 مع {status:"inProgress"}
                    val status = runCatching { MiniJson.asString(MiniJson.parseObject(resp.text)["status"]) }.getOrNull()
                    if (status == "completed" || status == "failed") {
                        if (status == "failed") throw java.io.IOException("onedrive: async copy failed")
                        return
                    }
                }
                in 400..499 -> throw HttpException(resp.code, path, resp.text.take(160))
                else -> {
                    val decision = backoff.decision(resp.code, attempt++)
                    if (decision.stop) throw HttpException(resp.code, path, "(monitor)")
                    backoff.wait(decision)
                }
            }
        }
    }

    override suspend fun test(): String {
        val resp = retryGet("$GRAPH/me/drive/root")
        return MiniJson.asString(MiniJson.parseObject(resp.text)["name"]) ?: "OneDrive"
    }

    // ---------- أدوات ----------

    private suspend fun authJsonHeaders(): Map<String, String> =
        authHeaders() + mapOf("Content-Type" to "application/json; charset=utf-8")

    private suspend fun retryGet(url: String): HttpResponse {
        var attempt = 0
        while (true) {
            val resp = transport.execute(HttpJson.get(url, authHeaders()))
            if (resp.isSuccessful) return resp
            if (resp.code == 404) throw CloudNotFoundException(url.substringAfter("root:"), "onedrive")
            val decision = backoff.decision(resp.code, attempt)
            if (decision.stop) throw HttpException(resp.code, url, resp.text.take(160))
            backoff.wait(decision)
            attempt++
        }
    }

    private suspend fun retryJson(req: HttpRequest): HttpResponse {
        var attempt = 0
        while (true) {
            val resp = transport.execute(req)
            if (resp.isSuccessful) return resp
            val decision = backoff.decision(resp.code, attempt)
            if (decision.stop) throw HttpException(resp.code, req.url, resp.text.take(160))
            backoff.wait(decision)
            attempt++
        }
    }

    override fun close() { /* بلا موارد */ }
}
