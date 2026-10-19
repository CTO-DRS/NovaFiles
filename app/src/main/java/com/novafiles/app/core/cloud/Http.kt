package com.novafiles.app.core.cloud

import java.io.IOException
import java.io.InputStream

/**
 * v0.16 — طبقة HTTP التجريدية فوق المهايئات السحابية وOAuth.
 *
 * مساران:
 *  - [execute]: أجسام صغيرة (JSON/form) — طلب واستجابة في الذاكرة.
 *  - [executeStreaming]: رفع/تنزيل كبير — جسم الطلب يُفتح مصنّعاً لكل محاولة
 *    (قابل للإعادة في إعادة المحاولة)، وجسم الاستجابة يُستهلك تدفقيًا داخل
 *    الاستدعاء (الناقل يغلقه) فتبقى الذاكرة ثابتة مهما كان حجم الملف.
 *
 * الإنتاج: OkHttpTransport — والاختبار: MockTransport يسجل الطلبات ويعيد
 * fixtures ثابتة («اختبارات عقد على fixtures مسجلة» وفق خارطة v0.16).
 */

/** طلب HTTP بسيط — الجسم بايتات (نص JSON أو ثنائي صغير) */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is HttpRequest && other.method == method && other.url == url &&
            other.headers == headers && (other.body?.contentEquals(body) ?: (body == null))

    override fun hashCode(): Int = method.hashCode() * 31 + url.hashCode()

    fun newBuilder(): Builder = Builder(method, url, LinkedHashMap(headers), body)

    class Builder(
        var method: String,
        var url: String,
        var headers: LinkedHashMap<String, String>,
        var body: ByteArray?,
    ) {
        fun header(k: String, v: String) = apply { headers[k] = v }
        fun body(b: ByteArray?) = apply { this.body = b }
        fun build() = HttpRequest(method, url, headers, body)
    }
}

/** استجابة HTTP صغيرة — النص يُفك كسلسلة فقط عند الطلب */
class HttpResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val text: String get() = String(body, Charsets.UTF_8)
    val isSuccessful: Boolean get() = code in 200..299

    /** قيمة ترويسة بلا حساسية حالة */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** استثناء HTTP مع الكود والجسم — المهايئات تميّز منه أخطاء الرموز/التخزين */
class HttpException(
    val code: Int,
    val url: String,
    val bodySnippet: String,
) : IOException("HTTP $code for $url: $bodySnippet".take(240))

/** ناقل HTTP — الاستدعاءان الوحيدان، المعلّقان ليصمدا في التزامن */
interface HttpTransport {
    /** طلب صغير (JSON/form) — استجابة كاملة في الذاكرة */
    suspend fun execute(request: HttpRequest): HttpResponse

    /**
     * طلب متدفق: [openBody] مصنع الجسم (يُفتح من جديد كل محاولة)، و
     * [onResponse] يستهلك جسم الاستجابة داخل النطاق (الناقل يغلق التدفق).
     * يعيد كود الاستجابة للتقييم في طبقة إعادة المحاولة.
     */
    suspend fun executeStreaming(
        request: HttpRequest,
        bodyLength: Long? = null,
        openBody: (() -> InputStream)? = null,
        onResponse: suspend (code: Int, headers: Map<String, String>, body: InputStream) -> Unit,
    ): Int
}

/** تنفيذ OkHttp الإنتاجي — بلا إعادة محاولة تلقائية (الـBackoff طبقة أعلى) */
class OkHttpTransport(client: okhttp3.OkHttpClient = defaultClient()) : HttpTransport {

    private val client: okhttp3.OkHttpClient = client

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val builder = toOkRequest(request)
        val call = client.newCall(builder.build())
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use { r ->
                        val bytes = r.body?.bytes() ?: ByteArray(0)
                        val resp = HttpResponse(r.code, r.headers.toMap(), bytes)
                        if (cont.isActive) cont.resumeWith(Result.success(resp))
                    }
                }
            })
            cont.invokeOnCancellation { call.cancel() }
        }
    }

    override suspend fun executeStreaming(
        request: HttpRequest,
        bodyLength: Long?,
        openBody: (() -> InputStream)?,
        onResponse: suspend (code: Int, headers: Map<String, String>, body: InputStream) -> Unit,
    ): Int {
        val builder = toOkRequest(request)
        if (openBody != null) {
            val requestBody = object : okhttp3.RequestBody() {
                // Content-Type يُمرَّر عبر ترويسة الطلب — الجسم ثنائي خالص
                override fun contentType(): okhttp3.MediaType? = null

                override fun contentLength(): Long = bodyLength ?: -1L

                override fun writeTo(sink: okio.BufferedSink) {
                    openBody().use { it.copyTo(sink.outputStream(), 64 * 1024) }
                }
            }
            when (request.method) {
                "PUT" -> builder.put(requestBody)
                "POST" -> builder.post(requestBody)
                "PATCH" -> builder.patch(requestBody)
                else -> builder.method(request.method, requestBody)
            }
        }
        val call = client.newCall(builder.build())
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val code = response.code
                    val headers = response.headers.toMap()
                    val body = response.body
                    // نستهلك الجسم داخل الاستدعاء — الناقل يغلق الرد كاملاً بعده
                    val done = runCatching {
                        kotlinx.coroutines.runBlocking {
                            if (body != null) {
                                body.byteStream().use { stream ->
                                    onResponse(code, headers, stream)
                                }
                            } else {
                                onResponse(code, headers, java.io.ByteArrayInputStream(ByteArray(0)))
                            }
                        }
                    }
                    if (cont.isActive) {
                        done.fold(
                            onSuccess = { cont.resumeWith(Result.success(code)) },
                            onFailure = { cont.resumeWith(Result.failure(it)) },
                        )
                    }
                }
            })
            cont.invokeOnCancellation { call.cancel() }
        }
    }

    private fun toOkRequest(request: HttpRequest): okhttp3.Request.Builder {
        val builder = okhttp3.Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        val body = request.body?.let { okhttp3.RequestBody.create(null, it) }
        when (request.method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> builder.delete(body)
            "PATCH" -> builder.patch(body ?: okhttp3.RequestBody.create(null, ByteArray(0)))
            "POST" -> builder.post(body ?: okhttp3.RequestBody.create(null, ByteArray(0)))
            "PUT" -> builder.put(body ?: okhttp3.RequestBody.create(null, ByteArray(0)))
            else -> builder.method(request.method, body)
        }
        return builder
    }

    companion object {
        fun defaultClient(): okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .writeTimeout(java.time.Duration.ofSeconds(60))
            .followRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/** مُساعد بناء طلبات JSON/form — يوحّد الترويسات الشائعة */
object HttpJson {
    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()) = HttpRequest(
        method = "POST",
        url = url,
        headers = headers + mapOf("Content-Type" to "application/json; charset=utf-8"),
        body = json.toByteArray(Charsets.UTF_8),
    )

    fun postForm(url: String, form: Map<String, String>) = HttpRequest(
        method = "POST",
        url = url,
        headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        body = formUrlEncode(form).toByteArray(Charsets.UTF_8),
    )

    fun get(url: String, headers: Map<String, String> = emptyMap()) =
        HttpRequest("GET", url, headers)

    fun delete(url: String, headers: Map<String, String> = emptyMap()) =
        HttpRequest("DELETE", url, headers)

    /** ترميز نموذج form-urlencoded (RFC 3986 — المسافة %20 لا +) */
    fun formUrlEncode(form: Map<String, String>): String =
        form.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}
