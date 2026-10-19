package com.novafiles.app.core.cloud

import java.io.ByteArrayInputStream

/**
 * محاكيات حالة للمزوّدين الثلاثة — تعيد fixtures بالشكل الذي تعيد به
 * الخوادم الفعلية (اختبارات عقد على fixtures مسجلة وفق الخارطة)، مع
 * تتبع الترحيلات لإثبات العقد (ترتيب الطلبات، ترويسات، أجسام).
 */

/** محاكي Drive — يدعم سرد/ملفات/جلسة رفع resumable بمطابقة Content-Range */
class DriveSim {
    val transport = MockTransport()
    val fileJson = LinkedHashMap<String, MutableMap<String, Any?>>()
    var sessionUrl: String? = null
    var sessionName: String = "up"
    val sessionReceived = StringBuilder()

    init {
        fileJson["d1"] = mutableMapOf(
            "id" to "d1", "name" to "Docs", "mimeType" to DriveFs.FOLDER_MIME,
            "modifiedTime" to "2027-06-01T10:00:00.000Z",
        )
        fileJson["f1"] = mutableMapOf(
            "id" to "f1", "name" to "photo.jpg", "mimeType" to "image/jpeg",
            "size" to "1024", "modifiedTime" to "2027-06-02T11:30:00.000Z",
            "md5Checksum" to "md5-photo",
        )
        fileJson["root"] = mutableMapOf("id" to "root", "name" to "My Drive", "mimeType" to DriveFs.FOLDER_MIME)

        transport.handler = { req -> handle(req) }
    }

    private fun jsonOf(m: Map<String, Any?>): String {
        val fields = m.entries.joinToString(",") { (k, v) ->
            val value = when (v) {
                null -> "null"
                is Map<*, *> -> jsonOf(v as Map<String, Any?>)
                else -> if (k == "size" || k == "childCount") "\"$v\"" else "\"$v\""
            }
            "\"$k\":$value"
        }
        return "{$fields}"
    }

    private fun handle(req: HttpRequest): HttpResponse {
        val url = req.url
        return when {
            // سرد مجلد
            url.contains("/files?q=") -> {
                val children = fileJson.values.filter { it["id"] != "root" }
                Resp.ok("""{"files":[${children.joinToString(",") { jsonOf(it) }}]}""")
            }
            // إنشاء جلسة رفع
            url.contains("uploadType=resumable") -> {
                sessionUrl = "https://session.drive.test/${System.nanoTime()}"
                // اسم الملف من metadata الجسم — نستخدمه عند التسجيل النهائي
                sessionName = Regex("\"name\":\"([^\"]+)\"").find(String(req.body!!))?.groupValues?.get(1) ?: "up"
                HttpResponse(200, mapOf("Location" to sessionUrl!!), ByteArray(0))
            }
            // قطع الجلسة
            url == sessionUrl && req.method == "PUT" -> {
                val cr = req.headers["Content-Range"] ?: ""
                if (cr.startsWith("bytes */")) {
                    // استعلام حالة — الكل أُرسل
                    Resp.ok(jsonOf(fileJson["upl"] ?: mutableMapOf<String, Any?>("id" to "upl", "name" to "up")))
                } else {
                    val range = cr.substringAfter("bytes ").substringBefore("/")
                    val end = range.substringAfter("-").toLong()
                    val total = cr.substringAfter("/").toLong()
                    String(req.body ?: ByteArray(0)).takeIf { it.isNotEmpty() }?.let { sessionReceived.append(it) }
                    if (end + 1 >= total) {
                        // إن كان العنصر مسجلاً مسبقاً (استئناف عبر نسخة ثانية) نحفظ اسمه
                        val existing = fileJson["upl"]
                        if (existing != null) {
                            existing["size"] = "$total"
                        } else {
                            fileJson["upl"] = mutableMapOf(
                                "id" to "upl", "name" to sessionName, "mimeType" to "application/octet-stream",
                                "size" to "$total", "modifiedTime" to "2027-06-03T09:00:00.000Z",
                            )
                        }
                        Resp.ok(jsonOf(fileJson["upl"]!!))
                    } else {
                        Resp.partial308(end)
                    }
                }
            }
            // رفع multipart بسيط
            url.contains("uploadType=multipart") -> {
                val body = String(req.body!!, Charsets.UTF_8)
                val name = Regex("\"name\":\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "file"
                val id = "up-${System.nanoTime().toString(36)}"
                val contentStart = body.indexOf("application/octet-stream\r\n\r\n")
                val contentLen = if (contentStart >= 0) {
                    val raw = body.substring(contentStart + 29)
                    val lastBoundary = raw.lastIndexOf("\r\n--")
                    (if (lastBoundary >= 0) raw.substring(0, lastBoundary).length else raw.length).toLong()
                } else 0L
                fileJson[id] = mutableMapOf(
                    "id" to id, "name" to name, "mimeType" to "application/octet-stream",
                    "size" to "$contentLen", "modifiedTime" to "2027-06-03T09:00:00.000Z",
                    "md5Checksum" to "md5-$name",
                )
                Resp.ok(jsonOf(fileJson[id]!!))
            }
            // mkdir
            url.endsWith("&supportsAllDrives=true") && req.method == "POST" && url.contains("/files?") -> {
                val body = String(req.body!!, Charsets.UTF_8)
                val name = Regex("\"name\":\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "new"
                val id = "n-${System.nanoTime().toString(36)}"
                fileJson[id] = mutableMapOf(
                    "id" to id, "name" to name, "mimeType" to DriveFs.FOLDER_MIME,
                    "modifiedTime" to "2027-06-03T09:00:00.000Z",
                )
                Resp.ok(jsonOf(fileJson[id]!!))
            }
            // stat/تنزيل
            url.matches(Regex(".*/files/[^?]+\\?alt=media.*")) -> {
                val id = Regex("/files/([^?]+)").find(url)!!.groupValues[1]
                val content = "content-of-$id".toByteArray()
                Resp.ok(content)
            }
            url.matches(Regex(".*/files/[^/?]+\\?.*")) -> {
                val id = Regex("/files/([^/?]+)").find(url)!!.groupValues[1]
                val f = fileJson[id] ?: return Resp.code(404, """{"error":{"code":404}}""")
                Resp.ok(jsonOf(f))
            }
            // DELETE
            req.method == "DELETE" -> {
                val id = Regex("/files/([^?]+)").find(url)?.groupValues?.get(1)
                fileJson.remove(id)
                Resp.code(204)
            }
            // PATCH rename/move
            req.method == "PATCH" -> {
                val id = Regex("/files/([^?]+)").find(url)!!.groupValues[1]
                val body = String(req.body!!, Charsets.UTF_8)
                Regex("\"name\":\"([^\"]+)\"").find(body)?.let {
                    fileJson[id]?.put("name", it.groupValues[1])
                }
                val f = fileJson[id] ?: return Resp.code(404, "{}")
                Resp.ok(jsonOf(f))
            }
            // copy
            url.contains("/copy") && req.method == "POST" -> {
                val body = String(req.body!!, Charsets.UTF_8)
                val name = Regex("\"name\":\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "copy"
                val id = "c-${System.nanoTime().toString(36)}"
                fileJson[id] = mutableMapOf(
                    "id" to id, "name" to name, "mimeType" to "application/octet-stream",
                    "size" to "1024", "modifiedTime" to "2027-06-03T09:00:00.000Z",
                )
                Resp.ok(jsonOf(fileJson[id]!!))
            }
            // test
            url.contains("/files/root?fields=id") -> Resp.ok("""{"id":"root","name":"My Drive"}""")
            else -> Resp.code(500, "{}")
        }
    }

    /** مسار فيدو محتوى التنزيل الافتراضي */
    fun downloadContent(id: String): ByteArray = "content-of-$id".toByteArray()
}

/** محاكي Dropbox — RPC بـ POST + جلسة رفع متعددة */
class DropboxSim {
    val transport = MockTransport()
    private val files = LinkedHashMap<String, MutableMap<String, Any?>>()
    private var sessionId = "sid-1"
    private val sessionBuf = StringBuilder()

    init {
        // Dropbox يستخدم .tag
        files["/Docs"] = mutableMapOf(
            ".tag" to "folder", "name" to "Docs", "id" to "id:d1",
            "path_display" to "/Docs", "path_lower" to "/docs",
        )
        files["/photo.jpg"] = mutableMapOf(
            ".tag" to "file", "name" to "photo.jpg", "id" to "id:f1",
            "path_display" to "/photo.jpg", "size" to "1024",
            "server_modified" to "2027-06-02T11:30:00.000Z",
            "content_hash" to "hash-photo",
        )

        transport.handler = { req -> handle(req) }
    }

    private fun handle(req: HttpRequest): HttpResponse {
        val url = req.url
        return when {
            url.contains("/files/list_folder/continue") -> Resp.ok("""{"entries":[],"cursor":"c2","has_more":false}""")
            url.contains("/files/list_folder") -> {
                val entries = files.values.joinToString(",") { jsonOf(it) }
                Resp.ok("""{"entries":[$entries],"cursor":"c1","has_more":false}""")
            }
            url.contains("/files/get_metadata") -> {
                val path = Regex("\"path\":\"([^\"]*)\"").find(String(req.body!!))?.groupValues?.get(1) ?: ""
                if (path.isEmpty() || path == "/") {
                    // الجذر في Dropbox: مجلد باسم فارغ
                    return Resp.ok(jsonOf(mapOf(".tag" to "folder", "name" to "", "id" to "id:root", "path_display" to "")))
                }
                val f = files[path] ?: return Resp.code(409, """{"error_summary":"path/not_found/..","error":{}}""")
                Resp.ok(jsonOf(f))
            }
            url.contains("/files/download") -> {
                // الجسم ثنائي — المحاكي يعيد ثابتاً
                Resp.ok("dropbox-content".toByteArray())
            }
            url.contains("/files/upload_session/start") -> Resp.ok("""{"session_id":"$sessionId"}""")
            url.contains("/files/upload_session/append_v2") -> {
                String(req.body ?: ByteArray(0)).let { sessionBuf.append(it) }
                Resp.code(200, "{}")
            }
            url.contains("/files/upload_session/finish") -> {
                sessionBuf.append(String(req.body ?: ByteArray(0)))
                // commit.path من Dropbox-API-Arg — المصدر الرسمي لمسار الوجهة
                val arg = req.headers["Dropbox-API-Arg"] ?: ""
                val commitPath = Regex("\"path\":\"([^\"]+)\"").find(arg)?.groupValues?.get(1) ?: "/up"
                val commitOffset = Regex("\"offset\":(\\d+)").find(arg)?.groupValues?.get(1)?.toLong() ?: sessionBuf.length.toLong()
                files[commitPath] = mutableMapOf(
                    ".tag" to "file", "name" to commitPath.trim('/').substringAfterLast('/'),
                    "id" to "id:upl-${System.nanoTime().toString(36)}",
                    "path_display" to commitPath, "size" to "$commitOffset",
                    "server_modified" to "2027-06-03T09:00:00.000Z",
                )
                Resp.ok(jsonOf(files[commitPath]!!))
            }
            url.contains("/files/upload") -> {
                val arg = req.headers["Dropbox-API-Arg"] ?: ""
                val path = Regex("\"path\":\"([^\"]+)\"").find(arg)?.groupValues?.get(1) ?: "/up"
                files[path] = mutableMapOf(
                    ".tag" to "file", "name" to path.substringAfterLast('/'), "id" to "id:up-${System.nanoTime().toString(36)}",
                    "path_display" to path, "size" to "${req.body?.size ?: 0}",
                    "server_modified" to "2027-06-03T09:00:00.000Z",
                    "content_hash" to "hash-up",
                )
                Resp.ok(jsonOf(files[path]!!))
            }
            url.contains("/files/create_folder_v2") -> {
                val path = Regex("\"path\":\"([^\"]+)\"").find(String(req.body!!))?.groupValues?.get(1) ?: "/x"
                val name = path.trim('/')
                files[path] = mutableMapOf(
                    ".tag" to "folder", "name" to name, "id" to "id:${System.nanoTime().toString(36)}",
                    "path_display" to path,
                )
                Resp.ok("""{"metadata":${jsonOf(files[path]!!)}}""")
            }
            url.contains("/files/delete_v2") -> {
                val path = Regex("\"path\":\"([^\"]+)\"").find(String(req.body!!))?.groupValues?.get(1) ?: ""
                if (files.remove(path) == null) Resp.code(409, """{"error_summary":"path_lookup/not_found"}""")
                else Resp.ok("""{"metadata":{}}""")
            }
            url.contains("/files/move_v2") || url.contains("/files/copy_v2") -> {
                val body = String(req.body!!, Charsets.UTF_8)
                val from = Regex("\"from_path\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                val to = Regex("\"to_path\":\"([^\"]+)\"").find(body)!!.groupValues[1]
                val src = files[from] ?: return Resp.code(409, """{"error_summary":"from_lookup/not_found"}""")
                files[to] = src.toMutableMap().apply {
                    put("path_display", to)
                    put("name", to.trim('/'))
                }
                Resp.ok("""{"metadata":${jsonOf(files[to]!!)}}""")
            }
            url.contains("/files/get_metadata") -> Resp.ok(jsonOf(files["/"] ?: mutableMapOf(".tag" to "folder", "name" to "")))
            else -> Resp.code(500, "{}")
        }
    }

    private fun jsonOf(m: Map<String, Any?>): String {
        val fields = m.entries.filter { it.value != null && !it.key.startsWith("child-") }.joinToString(",") { (k, v) ->
            "\"$k\":\"$v\""
        }
        return "{$fields}"
    }
}

/** محاكي OneDrive/Graph */
class GraphSim {
    val transport = MockTransport()
    private val items = LinkedHashMap<String, MutableMap<String, Any?>>() // id → item
    private val pathToId = LinkedHashMap<String, String>()
    var uploadUrl: String? = null
    private var received = 0L
    private var sessionPath: String = "big.bin" // مسار جلسة الرفع من createUploadSession
    var lastUploadSize = 0L

    init {
        val docs = item("id-docs", "Docs", isFolder = true)
        val photo = item("id-photo", "photo.jpg", isFolder = false, size = 1024)
        pathToId["Docs"] = "id-docs"
        pathToId["photo.jpg"] = "id-photo"
        items["id-docs"] = docs
        items["id-photo"] = photo

        transport.handler = { req -> handle(req) }
    }

    private fun item(id: String, name: String, isFolder: Boolean, size: Long = 0): MutableMap<String, Any?> {
        val m = mutableMapOf<String, Any?>(
            "id" to id, "name" to name, "size" to "$size",
            "lastModifiedDateTime" to "2027-06-02T11:30:00.000Z",
            "eTag" to "etag-$id",
        )
        if (isFolder) m["folder"] = mutableMapOf<String, Any?>("childCount" to "0")
        return m
    }

    private fun handle(req: HttpRequest): HttpResponse {
        val url = req.url
        return when {
            // سرد children
            url.contains("/children") && req.method == "GET" -> {
                val values = items.values.filter { it["id"] != "id-root" }.joinToString(",") { jsonOf(it) }
                Resp.ok("""{"value":[$values]}""")
            }
            // عنصر بالمسار: root:/{path}:/ — نستخرج حتى النقطتين الختاميتين
            url.contains("root:") && req.method == "GET" && !url.contains("content") -> {
                val raw = url.substringAfter("root:/", "").substringBefore(":/")
                val path = java.net.URLDecoder.decode(raw, "UTF-8")
                val id = pathToId[path] ?: return Resp.code(404, "{}")
                Resp.ok(jsonOf(items[id]!!))
            }
            url.endsWith("/root") && req.method == "GET" -> Resp.ok(
                jsonOf(mutableMapOf("id" to "id-root", "name" to "root", "folder" to mapOf<String, Any?>("childCount" to "2"))),
            )
            // تنزيل
            url.contains("/content") && req.method == "GET" -> Resp.ok("graph-content".toByteArray())
            // جلسة رفع — نحفظ المسار من الرابط للتسجيل النهائي
            url.contains("createUploadSession") -> {
                uploadUrl = "https://upload.graph.test/${System.nanoTime()}"
                sessionPath = java.net.URLDecoder.decode(
                    url.substringAfter("root:/", "").substringBefore(":/createUploadSession"), "UTF-8",
                ).ifEmpty { "big.bin" }
                received = 0
                Resp.ok("""{"uploadUrl":"$uploadUrl"}""")
            }
            url == uploadUrl && req.method == "PUT" -> {
                val cr = req.headers["Content-Range"] ?: ""
                if (cr.startsWith("bytes */")) {
                    val next = received
                    Resp.code(200, """{"nextExpectedRanges":["$next-"]}""")
                } else {
                    val end = cr.substringAfter("bytes ").substringBefore("/").substringAfter("-").toLong()
                    val total = cr.substringAfter("/").toLong()
                    received = end + 1
                    lastUploadSize += (req.body ?: ByteArray(0)).size
                    if (end + 1 >= total) {
                        val id = "id-upl-${System.nanoTime().toString(36)}"
                        items[id] = item(id, sessionPath.substringAfterLast('/'), isFolder = false, size = total)
                        pathToId[sessionPath] = id
                        Resp.ok(jsonOf(items[id]!!))
                    } else {
                        Resp.code(202, "{}")
                    }
                }
            }
            // رفع بسيط PUT: root:/{path}:/content
            url.contains(":/content") && req.method == "PUT" -> {
                val path = java.net.URLDecoder.decode(
                    url.substringAfter("root:/", "").substringBefore(":/content"), "UTF-8",
                ).ifEmpty { "up.bin" }
                val id = "id-up-${System.nanoTime().toString(36)}"
                items[id] = item(id, path.substringAfterLast('/'), isFolder = false, size = (req.body?.size ?: 0).toLong())
                pathToId[path] = id
                Resp.ok(jsonOf(items[id]!!))
            }
            // إنشاء مجلد
            url.contains("/children") && req.method == "POST" -> {
                val name = Regex("\"name\":\"([^\"]+)\"").find(String(req.body!!))?.groupValues?.get(1) ?: "new"
                val id = "id-${System.nanoTime().toString(36)}"
                items[id] = item(id, name, isFolder = true)
                Resp.ok(jsonOf(items[id]!!))
            }
            // PATCH rename/move
            req.method == "PATCH" -> {
                val id = Regex("/items/([^/]+)").find(url)!!.groupValues[1]
                val body = String(req.body!!, Charsets.UTF_8)
                Regex("\"name\":\"([^\"]+)\"").find(body)?.let { items[id]?.put("name", it.groupValues[1]) }
                Resp.ok(jsonOf(items[id] ?: return Resp.code(404, "{}")))
            }
            // DELETE
            req.method == "DELETE" -> {
                val id = Regex("/items/([^/?]+)").find(url)!!.groupValues[1]
                items.remove(id)
                Resp.code(204)
            }
            // copy — غير متزامن
            url.contains("/copy") && req.method == "POST" ->
                HttpResponse(202, mapOf("Location" to "https://monitor.graph.test/xyz"), ByteArray(0))
            url.contains("monitor.graph.test") ->
                Resp.ok("""{"status":"completed"}""")
            else -> Resp.code(500, "{}")
        }
    }

    private fun jsonOf(m: Map<String, Any?>): String {
        val parts = m.entries.filter { it.value != null }.map { (k, v) ->
            if (v is Map<*, *>) {
                val inner = (v as Map<String, Any?>).entries.joinToString(",") { (ik, iv) -> "\"$ik\":\"$iv\"" }
                "\"$k\":{$inner}"
            } else {
                "\"$k\":\"$v\""
            }
        }
        return "{${parts.joinToString(",")}}"
    }
}
