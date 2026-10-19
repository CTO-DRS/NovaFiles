package com.novafiles.app.core.cloud

import java.io.File
import java.io.InputStream

/**
 * v0.16 — مهايئ Dropbox (HTTP API v2) فوق [HttpTransport].
 *
 * Dropbox مساري أصلاً (الجذر ""). الواجهة RPC بـ POST:
 *  - سرد: /2/files/list_folder (+ list_folder/continue بالكورسر)
 *  - تنزيل: content.dropboxapi.com/2/files/download بـ Dropbox-API-Arg
 *    يدعم Range عبر الترويسة القياسية للاستئناف
 *  - رفع صغير (< 150MB): /2/files/upload؛ كبير: upload_session
 *    start/append_v2/finish — القطع نصف إغلاق مع cursor، والاستئناف عبر
 *    حفظ session_id والإزاحة في [sessions]
 *  - بنية: create_folder_v2/delete_v2/move_v2/copy_v2
 *  - حد 429 يأتي مع Retry-After — الـBackoff يفهمه
 */
class DropboxFs(
    private val transport: HttpTransport,
    private val tokenProvider: suspend () -> String,
    private val sessions: UploadSessionStore = InMemorySessionStore(),
    private val backoff: Backoff = Backoff(),
    private val accountId: String = "dropbox",
    /** حد الرفع البسيط الفعلي — يُخفَّض في الاختبارات لإجبار مسار الجلسة */
    private val simpleUploadLimit: Long = SIMPLE_UPLOAD_LIMIT,
) : CloudFs {

    override val displayUrl: String = "dropbox://"

    companion object {
        const val API = "https://api.dropboxapi.com/2/files"
        const val CONTENT = "https://content.dropboxapi.com/2/files"

        /** حد الرفع البسيط — فوقه جلسة upload_session */
        const val SIMPLE_UPLOAD_LIMIT = 100L * 1024 * 1024

        /** قطعة جلسة الرفع — Dropbox يسمح حتى 150MB لكل append */
        const val SESSION_CHUNK = 8L * 1024 * 1024

        /** جلسات الرفع صالحة 48 ساعة (سياسة Dropbox) */
        const val SESSION_TTL_MS = 48L * 3600 * 1000
    }

    private suspend fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to "Bearer ${tokenProvider()}")

    private fun joinPath(parent: String, name: String): String = RemotePathUtil.join(parent, name)

    // ---------- السرد ----------

    /** استجابة list_folder مُجرّدة */
    private data class ListPage(val entries: List<CloudEntry>, val cursor: String?, val hasMore: Boolean)

    private suspend fun listPage(path: String, cursor: String? = null): ListPage {
        val url = if (cursor != null) "$API/list_folder/continue" else "$API/list_folder"
        val body = if (cursor != null) {
            """{"cursor":${jsonString(cursor)}}"""
        } else {
            """{"path":${jsonString(dboxPath(path))},"limit":500,"recursive":false,"include_deleted":false}"""
        }
        val resp = retryJson(HttpJson.postJson(url, body, authHeaders()))
        val o = MiniJson.parseObject(resp.text)
        val entries = MiniJson.asArray(o["entries"]).mapNotNull { v ->
            val e = MiniJson.asObject(v)
            val tag = MiniJson.asString(e[".tag"]) ?: return@mapNotNull null
            val name = MiniJson.asString(e["name"]) ?: return@mapNotNull null
            val full = MiniJson.asString(e["path_display"]) ?: dboxPath(joinPath(path, name))
            CloudEntry(
                name = name,
                path = if (full.isBlank()) "/$name" else full,
                isDir = tag == "folder",
                size = MiniJson.asLong(e["size"]) ?: 0L,
                lastModified = MiniJson.asString(e["server_modified"])?.let { IsoDate.toMs(it) } ?: 0L,
                remoteId = MiniJson.asString(e["id"]),
                etag = MiniJson.asString(e["content_hash"]),
            )
        }
        return ListPage(
            entries = entries,
            cursor = MiniJson.asString(o["cursor"]),
            hasMore = MiniJson.asBool(o["has_more"]) ?: false,
        )
    }

    override suspend fun list(path: String): List<CloudEntry> {
        val out = ArrayList<CloudEntry>()
        var page = listPage(path)
        out.addAll(page.entries)
        while (page.hasMore && page.cursor != null) {
            page = listPage(path, page.cursor)
            out.addAll(page.entries)
        }
        return out.sortedWith(compareByDescending<CloudEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun stat(path: String): CloudEntry {
        val clean = path.trim('/')
        if (clean.isEmpty()) {
            return CloudEntry("Dropbox", "", true, 0, 0, null, null)
        }
        val body = """{"path":${jsonString(dboxPath(path))},"include_media_info":false}"""
        val resp = retryJson(HttpJson.postJson("$API/get_metadata", body, authHeaders()))
        val e = MiniJson.parseObject(resp.text)
        val tag = MiniJson.asString(e[".tag"])
        if (tag == null || tag == "deleted") throw CloudNotFoundException(path, "dropbox")
        return CloudEntry(
            name = MiniJson.asString(e["name"]) ?: clean.substringAfterLast('/'),
            path = "/$clean",
            isDir = tag == "folder",
            size = MiniJson.asLong(e["size"]) ?: 0L,
            lastModified = MiniJson.asString(e["server_modified"])?.let { IsoDate.toMs(it) } ?: 0L,
            remoteId = MiniJson.asString(e["id"]),
            etag = MiniJson.asString(e["content_hash"]),
        )
    }

    // ---------- القراءة ----------

    override suspend fun readFrom(path: String, offset: Long, consumer: suspend (InputStream) -> Unit) {
        val arg = """{"path":${jsonString(dboxPath(path))}}"""
        val headers = HashMap(authHeaders())
        headers["Dropbox-API-Arg"] = arg
        if (offset > 0) headers["Range"] = "bytes=$offset-"
        val url = "$CONTENT/download"
        var attempt = 0
        while (true) {
            try {
                transport.executeStreaming(HttpRequest("GET", url, headers)) { code, _, body ->
                    if (code !in 200..299 && code != 206) {
                        val err = body.readBytes().toString(Charsets.UTF_8)
                        throw HttpException(code, url, err.take(160))
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
        if (source.length() <= simpleUploadLimit) {
            sessions.remove(key)
            return simpleUpload(source, path, onProgress)
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
        if (size <= simpleUploadLimit) {
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

    private suspend fun simpleUpload(source: File, path: String, onProgress: (Long) -> Unit): CloudEntry {
        val bytes = source.readBytes()
        val entry = simpleUploadBytes(bytes, path)
        onProgress(bytes.size.toLong())
        return entry
    }

    private suspend fun simpleUploadBytes(content: ByteArray, path: String): CloudEntry {
        val arg = """{"path":${jsonString(dboxPath(path))},"mode":"overwrite","mute":true}"""
        val url = "$CONTENT/upload"
        var attempt = 0
        while (true) {
            val resp = transport.execute(
                HttpRequest(
                    method = "POST",
                    url = url,
                    headers = authHeaders() + mapOf(
                        "Dropbox-API-Arg" to arg,
                        "Content-Type" to "application/octet-stream",
                    ),
                    body = content,
                ),
            )
            if (resp.isSuccessful) {
                return entryFromJson(MiniJson.parseObject(resp.text), path)
            }
            val decision = backoff.decision(resp.code, attempt)
            if (decision.stop) throw HttpException(resp.code, url, resp.text.take(160))
            backoff.wait(decision)
            attempt++
        }
    }

    /**
     * جلسة رفع قابلة للاستئناف:
     *  start → session_id، append_v2 (cursor مع offset، close=true للقطعة الأخيرة)،
     *  finish (cursor + commit) ← الملف الناتج. الاستئناف بعد الموت من [sessions].
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
        var sessionId = saved?.url ?: startSession()
        var offset = saved?.offset ?: 0L
        if (saved == null) {
            sessions.save(sessionKey, UploadSessionStore.UploadSession(sessionId, 0, System.currentTimeMillis(), size))
        }

        while (offset < size) {
            val chunkEnd = minOf(offset + SESSION_CHUNK, size)
            val isLast = chunkEnd >= size
            // عقد Dropbox: append_v2 يحتاج cursor{session_id,offset}، وfinish
            // يحتاج cursor + commit{path,mode} — والاستجابة هي metadata الملف
            val arg = if (isLast) {
                """{"cursor":{"session_id":${jsonString(sessionId)},"offset":$offset},"commit":{"path":${jsonString(dboxPath(path))},"mode":"overwrite","autorename":false,"mute":true}}"""
            } else {
                """{"cursor":{"session_id":${jsonString(sessionId)},"offset":$offset},"close":false}"""
            }
            var finishMetadata: String? = null
            val code = transport.executeStreaming(
                request = HttpRequest(
                    method = "POST",
                    url = if (isLast) "$CONTENT/upload_session/finish" else "$CONTENT/upload_session/append_v2",
                    headers = authHeaders() + mapOf(
                        "Content-Type" to "application/octet-stream",
                        "Dropbox-API-Arg" to arg,
                    ),
                ),
                bodyLength = chunkEnd - offset,
                openBody = {
                    val stream = openInput()
                    stream.skip(offset)
                    LimitedStream(stream, chunkEnd - offset)
                },
                onResponse = { respCode, _, body ->
                    if (respCode in 400..499) {
                        val text = body.readBytes().toString(Charsets.UTF_8)
                        throw HttpException(respCode, path, text.take(160))
                    }
                    if (isLast) finishMetadata = body.readBytes().toString(Charsets.UTF_8)
                },
            )
            if (code in 200..299) {
                offset = chunkEnd
                if (isLast) {
                    sessions.remove(sessionKey)
                    onProgress(size)
                    // finish يعيد metadata الملف مباشرة — بلا استعلام إضافي
                    return runCatching { entryFromJson(MiniJson.parseObject(finishMetadata ?: ""), path) }
                        .getOrElse { stat(path) }
                }
                sessions.save(sessionKey, UploadSessionStore.UploadSession(sessionId, offset, System.currentTimeMillis(), size))
                onProgress(offset)
            } else if (code in 400..499) {
                // cursor فاسد/جلسة منتهية — نبدأ جلسة جديدة من الصفر
                sessionId = startSession()
                offset = 0
                sessions.save(sessionKey, UploadSessionStore.UploadSession(sessionId, 0, System.currentTimeMillis(), size))
            } else {
                val decision = backoff.decision(code, 0)
                if (decision.stop) throw HttpException(code, path, "(session)")
                backoff.wait(decision)
            }
        }
        return stat(path)
    }

    private suspend fun startSession(): String {
        val resp = transport.execute(
            HttpRequest(
                method = "POST",
                url = "$CONTENT/upload_session/start",
                headers = authHeaders() + mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Dropbox-API-Arg" to """{"close":false}""",
                ),
                body = ByteArray(0),
            ),
        )
        if (!resp.isSuccessful) throw HttpException(resp.code, "start", resp.text.take(120))
        return MiniJson.asString(MiniJson.parseObject(resp.text)["session_id"])
            ?: throw java.io.IOException("dropbox: session_id missing")
    }

    // ---------- البنية ----------

    override suspend fun mkdir(parent: String, name: String): CloudEntry {
        val path = joinPath(parent, name)
        val body = """{"path":${jsonString(dboxPath(path))},"autorename":false}"""
        val resp = retryJson(HttpJson.postJson("$API/create_folder_v2", body, authHeaders()))
        val o = MiniJson.parseObject(resp.text)
        val md = MiniJson.asObject(o["metadata"])
        return CloudEntry(
            name = name, path = path, isDir = true, size = 0,
            lastModified = System.currentTimeMillis(),
            remoteId = MiniJson.asString(md["id"]), etag = null,
        )
    }

    override suspend fun delete(path: String, isDir: Boolean) {
        val body = """{"path":${jsonString(dboxPath(path))}}"""
        val resp = retryJsonRaw(HttpJson.postJson("$API/delete_v2", body, authHeaders()))
        if (resp.code == 409) throw CloudNotFoundException(path, "dropbox")
        if (!resp.isSuccessful) throw HttpException(resp.code, path, resp.text.take(160))
    }

    override suspend fun rename(path: String, newName: String) {
        val parent = RemotePathUtil.parentOf(path)
        move(path, parent, newName)
    }

    override suspend fun move(path: String, targetDir: String, newName: String) {
        val body = """{
            "from_path":${jsonString(dboxPath(path))},
            "to_path":${jsonString(dboxPath(joinPath(targetDir, newName)))},
            "autorename":false,"allow_ownership_transfer":false
        }"""
        val resp = retryJsonRaw(HttpJson.postJson("$API/move_v2", body, authHeaders()))
        if (resp.code == 409) throw CloudConflictException(newName, "dropbox")
        if (!resp.isSuccessful) throw HttpException(resp.code, path, resp.text.take(160))
    }

    override suspend fun copy(path: String, targetDir: String, newName: String) {
        val body = """{
            "from_path":${jsonString(dboxPath(path))},
            "to_path":${jsonString(dboxPath(joinPath(targetDir, newName)))},
            "autorename":false
        }"""
        val resp = retryJsonRaw(HttpJson.postJson("$API/copy_v2", body, authHeaders()))
        if (resp.code == 409) throw CloudConflictException(newName, "dropbox")
        if (!resp.isSuccessful) throw HttpException(resp.code, path, resp.text.take(160))
    }

    override suspend fun test(): String {
        val body = """{"path":""}"""
        val resp = retryJson(HttpJson.postJson("$API/get_metadata", body, authHeaders()))
        return MiniJson.asString(MiniJson.parseObject(resp.text)["name"]) ?: "Dropbox"
    }

    // ---------- أدوات ----------

    private fun dboxPath(path: String): String = if (path.trim('/').isEmpty()) "" else "/${path.trim('/')}"

    private suspend fun retryJson(req: HttpRequest): HttpResponse {
        var attempt = 0
        while (true) {
            val resp = transport.execute(req)
            if (resp.isSuccessful) return resp
            val decision = backoff.decision(resp.code, attempt)
            if (decision.stop) {
                if (resp.code == 409 && resp.text.contains("not_found")) throw CloudNotFoundException(req.url, "dropbox")
                throw HttpException(resp.code, req.url, resp.text.take(160))
            }
            backoff.wait(decision)
            attempt++
        }
    }

    /** نسخة لا تترجم 409 إلى استثناء — للعمليات التي يميز فيها المُستدعي */
    private suspend fun retryJsonRaw(req: HttpRequest): HttpResponse {
        var attempt = 0
        while (true) {
            val resp = transport.execute(req)
            if (resp.isSuccessful) return resp
            val decision = backoff.decision(resp.code, attempt)
            if (decision.stop) return resp
            backoff.wait(decision)
            attempt++
        }
    }

    private fun entryFromJson(e: Map<String, MiniJson.Value>, path: String): CloudEntry =
        CloudEntry(
            name = MiniJson.asString(e["name"]) ?: path.substringAfterLast('/'),
            path = path,
            isDir = MiniJson.asString(e[".tag"]) == "folder",
            size = MiniJson.asLong(e["size"]) ?: 0L,
            lastModified = MiniJson.asString(e["server_modified"])?.let { IsoDate.toMs(it) } ?: 0L,
            remoteId = MiniJson.asString(e["id"]),
            etag = MiniJson.asString(e["content_hash"]),
        )

    override fun close() { /* بلا موارد */ }
}

/** تحليل تواريخ ISO-8601 للمزوّدين — 0 عند الفشل (بلا رمي) */
object IsoDate {
    fun toMs(iso: String): Long = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (e: Exception) {
        try {
            // Dropbox/Graph قد يعيدان +00:00 بدل Z
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (e2: Exception) {
            0L
        }
    }
}
