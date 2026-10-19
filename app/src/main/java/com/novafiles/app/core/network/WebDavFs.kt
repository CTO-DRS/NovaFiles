package com.novafiles.app.core.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * v0.13 — عميل WebDAV فوق OkHttp: بوابة السحابة مفتوحة البروتوكول
 * (Nextcloud / ownCloud / Apache / IIS / أي خادم DAV متوافق).
 *
 * يطبّق واجهة RemoteFs نفسها التي يستخدمها FTP/SMB/SFTP — فتتعامل الشاشات
 * مع WebDAV بلا أي تغيير، ويضيف قدرات خاصة بالنقل:
 *  - RangeReadable: قراءة مدى بايتي (bytes=N-) لاستئناف التنزيلات المقاطَعة
 *  - statDav/listDav: استرجاع ETag لإعادة التحقق من ذاكرة السرد
 *  - uploadGuarded: رفع مشروط (If-Match / If-None-Match) لكشف التعارض 412
 *  - copyServerSide / moveServerSide: النسخ والنقل على الخادم (COPY/MOVE)
 *
 * المصادقة: Basic استباقي بترميز UTF-8 (يدعم كلمات المرور العربية).
 * المسارات: كل مقطع يُرمَّز RFC 3986 — أسماء عربية/إيموجي/مسافات تعمل كما هي.
 */

/** رُفِع الهدف وقد وُجد مسبقاً بتغيّر (412) — الطبقة العليا تطبّق سياسة التعارض */
class DavConflictException(message: String) : IOException(message)

/** بيانات الدخول غير صحيحة أو مرفوضة (401) */
class DavUnauthorizedException(message: String) : IOException(message)

/** قدرة القراءة المدى البايتي — تعتمدها محركات النقل للاستئناف */
interface RangeReadable {
    /** يقرأ من إزاحة معينة حتى نهاية الملف إلى sink (للاستئناف) */
    suspend fun readRange(
        remotePath: String,
        offset: Long,
        sink: OutputStream,
        onProgress: (Long) -> Unit = {},
    )
}

class WebDavFs(
    /** المضيف: اسم نطاق أو عنوان IP (بلا مخطط) */
    private val host: String,
    /** المنفذ — 0 أو سالب يعني الافتراضي (443 لو HTTPS وإلا 80) */
    port: Int,
    /** المسار الأساسي للجذر DAV مثل /remote.php/dav/files/admin — فارغ يعني جذر الخادم */
    basePath: String = "",
    private val username: String = "",
    private val password: String = "",
    /** إجبار HTTPS — null يستنتج من المنفذ (443) */
    https: Boolean? = null,
    /** عميل مُحقون للاختبارات — إن حُقن فلن يُغلق مع close() */
    private val injected: OkHttpClient? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RemoteFs, RangeReadable, AutoCloseable {

    private val isHttps: Boolean = https ?: (if (port == 443) true else false)
    private val effectivePort: Int = if (port > 0) port else if (isHttps) 443 else 80
    private val base: String = run {
        val b = basePath.trim().trimEnd('/')
        if (b.isEmpty() || b == "/") "" else if (b.startsWith("/")) b else "/$b"
    }

    override val displayUrl: String = "webdav://$host:$effectivePort$base"

    private val authHeader: String? =
        if (username.isNotEmpty()) Credentials.basic(username, password, Charsets.UTF_8) else null

    private val client: OkHttpClient = injected ?: OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val ownsClient: Boolean = injected == null

    // ═══════════════════════ بناء المسارات ═══════════════════════

    /** يرمّز مقطع مسار RFC 3986 (UTF-8) مع إبقاء الأحرف الآمنة في المقاطع */
    private fun encodeSegment(s: String): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@"
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size + 8)
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

    /** يبني HttpUrl كاملاً من مسار موحّد داخلي ("" للجذر) */
    private fun url(remotePath: String): HttpUrl {
        val raw = base + "/" + remotePath.trim('/')
        val full = if (raw == "/" || raw.isBlank()) "/" else raw.trimEnd('/')
        val encoded = full.split('/').joinToString("/") { seg -> if (seg.isEmpty()) "" else encodeSegment(seg) }
        val path = if (encoded.startsWith("/")) encoded else "/$encoded"
        val scheme = if (isHttps) "https" else "http"
        return HttpUrl.Builder()
            .scheme(scheme)
            .host(host.removePrefix("https://").removePrefix("http://"))
            .port(effectivePort)
            .encodedPath(path)
            .build()
    }

    /** يبني الرابط الهدف المطلق لترويسات Destination (MOVE/COPY) */
    private fun destinationUrl(remotePath: String): String = url(remotePath).toString()

    private fun Request.Builder.auth(): Request.Builder =
        if (authHeader != null) header("Authorization", authHeader) else this

    // ═══════════════════════ الأفعال الخام ═══════════════════════

    /** ينفذ طلباً ويعيد الرد — يرفع أخطاء المصادقة مباشرة */
    private suspend fun execute(request: Request): Response = withContext(io) {
        val resp = client.newCall(request).execute()
        if (resp.code == 401) {
            resp.close()
            throw DavUnauthorizedException("401 unauthorized: $host")
        }
        resp
    }

    /** PROPFIND موحّد — يعيد كل الموارد (أولها العنصر نفسه عند Depth ≥ 0) */
    private suspend fun propfind(remotePath: String, depth: Int): List<DavResource> {
        val body = PROPFIND_BODY
            .toRequestBody("application/xml; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(url(remotePath))
            .method("PROPFIND", body)
            .header("Depth", depth.toString())
            .auth()
            .build()
        return execute(req).use { resp ->
            when {
                resp.code == 207 -> resp.body?.byteStream()?.use { DavParser.parseMultistatus(it) }
                    ?: emptyList()
                resp.code == 404 -> throw IOException("not found: ${RemotePaths.nameOf(remotePath)}")
                else -> throw IOException("PROPFIND failed: HTTP ${resp.code}")
            }
        }
    }

    // ═══════════════════════ واجهة RemoteFs ═══════════════════════

    override suspend fun list(path: String): List<RemoteEntry> =
        listDav(path).entries

    /** سرد مع ETag المجموعة نفسها — تغذّي به ذاكرة السرد في طبقة المزوّد */
    suspend fun listDav(path: String): DavListing {
        val all = propfind(path, depth = 1)
        val selfPath = DavParser.hrefToPath(url(path).encodedPath)
        val self = all.firstOrNull { it.path == selfPath }
        val entries = all
            .filter { it.path != selfPath }
            .map { r ->
                RemoteEntry(
                    name = r.displayName.ifEmpty { r.path.substringAfterLast('/') },
                    path = r.path,
                    isDir = r.isCollection,
                    size = r.contentLength,
                    lastModified = r.lastModified,
                )
            }
        return DavListing(etag = self?.etag, entries = entries)
    }

    override suspend fun test(): String {
        val all = propfind("", depth = 0)
        return all.firstOrNull()?.displayName?.ifEmpty { null } ?: "WebDAV"
    }

    override suspend fun download(remotePath: String, target: File, onProgress: (Long) -> Unit) {
        withContext(io) {
            val req = Request.Builder().url(url(remotePath)).get().auth().build()
            execute(req).use { resp ->
                when {
                    resp.code == 200 || resp.code == 206 -> {
                        val tmp = File(target.parentFile, target.name + ".davpart")
                        var done = 0L
                        resp.body?.byteStream()?.use { input ->
                            FileOutputStream(tmp).use { out ->
                                val buf = ByteArray(DEFAULT_BUFFER)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    onProgress(done)
                                }
                            }
                        }
                        if (!tmp.renameTo(target)) {
                            tmp.delete()
                            throw IOException("rename failed: ${target.name}")
                        }
                    }
                    resp.code == 404 -> throw IOException("not found: ${RemotePaths.nameOf(remotePath)}")
                    else -> throw IOException("download failed: HTTP ${resp.code}")
                }
            }
        }
    }

    override suspend fun upload(source: File, remoteDir: String, onProgress: (Long) -> Unit) =
        putFile(source, remotePathOf(remoteDir, source.name), forbidOverwrite = false, ifMatchEtag = null, onProgress)

    /**
     * رفع مشروط لكشف التعارضات:
     *  - forbidOverwrite → If-None-Match: * (يرفع فقط إن لم يوجد الهدف)
     *  - ifMatchEtag ≠ null → If-Match (يرفع فقط إن لم يتغيّر الملف البعيد منذ آخر قراءة)
     * يرمي DavConflictException عند 412 — السياسة العليا تقرر (تخطي/إعادة تسمية).
     */
    suspend fun uploadGuarded(
        source: File,
        remotePath: String,
        forbidOverwrite: Boolean = false,
        ifMatchEtag: String? = null,
        onProgress: (Long) -> Unit = {},
    ) = putFile(source, remotePath, forbidOverwrite, ifMatchEtag, onProgress)

    private suspend fun putFile(
        source: File,
        remotePath: String,
        forbidOverwrite: Boolean,
        ifMatchEtag: String?,
        onProgress: (Long) -> Unit,
    ): Unit = withContext(io) {
        val media = "application/octet-stream".toMediaTypeOrNull()
        val req = Request.Builder()
            .url(url(remotePath))
            .put(object : RequestBody() {
                override fun contentType() = media
                override fun contentLength() = source.length()
                override fun writeTo(sink: BufferedSink) {
                    source.inputStream().use { input ->
                        val buf = ByteArray(DEFAULT_BUFFER)
                        var done = 0L
                        val total = source.length()
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            done += n
                            onProgress(done)
                        }
                    }
                }
            })
            .auth()
            .apply {
                if (forbidOverwrite) header("If-None-Match", "*")
                if (ifMatchEtag != null) header("If-Match", ifMatchEtag)
            }
            .build()
        execute(req).use { resp ->
            when {
                resp.code in 200..299 -> Unit
                resp.code == 412 -> throw DavConflictException("conflict: ${RemotePaths.nameOf(remotePath)}")
                resp.code == 507 -> throw IOException("insufficient storage (507)")
                else -> throw IOException("upload failed: HTTP ${resp.code}")
            }
        }
    }

    override suspend fun delete(path: String, isDir: Boolean) {
        val req = Request.Builder().url(url(path)).delete().auth().build()
        execute(req).use { resp ->
            // 404 يُعامَل كنجاح — الحذف على الخوادم المتعددة قد سبق عبر قائمة أخرى
            if (resp.code !in 200..299 && resp.code != 404)
                throw IOException("delete failed: HTTP ${resp.code}")
        }
    }

    override suspend fun mkdir(parent: String, name: String) {
        val req = Request.Builder()
            .url(url(RemotePaths.join(parent, name)))
            .method("MKCOL", null)
            .auth()
            .build()
        execute(req).use { resp ->
            if (resp.code !in 200..299)
                throw IOException("mkdir failed: HTTP ${resp.code}")
        }
    }

    override suspend fun rename(path: String, newName: String) =
        moveServerSide(path, RemotePaths.parentOf(path), newName)

    /** نقل على الخادم (MOVE) — يرفع DavConflictException عند وجود الهدف (Overwrite: F) */
    suspend fun moveServerSide(path: String, targetDir: String, newName: String) =
        davRelocate("MOVE", path, targetDir, newName)

    /** نسخ على الخادم (COPY) — بلا تنزيل أو رفع محلي */
    suspend fun copyServerSide(path: String, targetDir: String, newName: String) =
        davRelocate("COPY", path, targetDir, newName)

    private suspend fun davRelocate(verb: String, path: String, targetDir: String, newName: String) {
        val target = RemotePaths.join(targetDir, newName)
        val req = Request.Builder()
            .url(url(path))
            .method(verb, null)
            .header("Destination", destinationUrl(target))
            .header("Overwrite", "F")
            .auth()
            .build()
        execute(req).use { resp ->
            when {
                resp.code in 200..299 -> Unit
                resp.code == 412 -> throw DavConflictException("conflict: $newName")
                resp.code == 404 -> throw IOException("not found: ${RemotePaths.nameOf(path)}")
                else -> throw IOException("$verb failed: HTTP ${resp.code}")
            }
        }
    }

    // ═══════════════════════ قدرات النقل v2 ═══════════════════════

    override suspend fun readRange(
        remotePath: String,
        offset: Long,
        sink: OutputStream,
        onProgress: (Long) -> Unit,
    ) {
        require(offset >= 0) { "negative offset" }
        val req = Request.Builder()
            .url(url(remotePath))
            .get()
            .header("Range", "bytes=$offset-")
            .auth()
            .build()
        execute(req).use { resp ->
            when {
                resp.code == 206 -> copyBody(resp.body?.byteStream(), sink, offset, onProgress)
                resp.code == 200 -> {
                    // تجاهل الخادم للمدى — نتخطى الإزاحة يدوياً ثم ننسخ
                    copyBody(resp.body?.byteStream(), sink, 0L, onProgress, skip = offset)
                }
                resp.code == 404 -> throw IOException("not found: ${RemotePaths.nameOf(remotePath)}")
                resp.code == 416 -> {
                    // المدى خلف نهاية الملف — الملف مكتمل على الأرجح
                    throw IOException("range not satisfiable (416) at offset $offset")
                }
                else -> throw IOException("range read failed: HTTP ${resp.code}")
            }
        }
    }

    private fun copyBody(
        input: InputStream?,
        sink: OutputStream,
        baseDone: Long,
        onProgress: (Long) -> Unit,
        skip: Long = 0L,
    ) {
        if (input == null) return
        var skipped = 0L
        val buf = ByteArray(DEFAULT_BUFFER)
        var done = baseDone
        input.use { ins ->
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                if (skipped < skip) {
                    val remain = skip - skipped
                    if (n <= remain) { skipped += n; continue }
                    val from = remain.toInt()
                    val count = n - from
                    sink.write(buf, from, count)
                    done += count
                    onProgress(done)
                    skipped = skip
                    continue
                }
                sink.write(buf, 0, n)
                done += n
                onProgress(done)
            }
        }
    }

    /** خصائص عنصر واحد (PROPFIND depth 0) — يعيد null إن لم يوجد */
    suspend fun statDav(remotePath: String): DavResource? = try {
        val all = propfind(remotePath, depth = 0)
        val wanted = DavParser.hrefToPath(url(remotePath).encodedPath)
        all.firstOrNull { it.path == wanted } ?: all.firstOrNull()
    } catch (e: IOException) {
        if ("not found" in (e.message ?: "")) null else throw e
    }

    override fun close() {
        if (ownsClient) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun remotePathOf(dir: String, name: String): String = RemotePaths.join(dir, name)

    companion object {
        private const val DEFAULT_BUFFER = 64 * 1024

        /** طلب خصائص مقتصد — يكفي للسرد والتحقق */
        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:displayname/><d:resourcetype/><d:getcontentlength/>" +
                "<d:getlastmodified/><d:getetag/>" +
                "</d:prop></d:propfind>"
    }
}

/** نتيجة سرد DAV مع ETag المجموعة — لوحدة ذاكرة السرد */
data class DavListing(
    val etag: String?,
    val entries: List<RemoteEntry>,
)
