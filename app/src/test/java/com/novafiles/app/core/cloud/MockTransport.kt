package com.novafiles.app.core.cloud

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * ناقل اختبار — يسجل كل الطلبات ويعيد استجابات ثابتة من طابور أو معالج.
 * أساس «اختبارات العقد على fixtures مسجلة» للمهايئات الثلاثة.
 */
class MockTransport : HttpTransport {

    /** الطلبات بترتيب وصولها — للتحقق من العقد */
    val requests = ArrayList<HttpRequest>()

    /** أجسام الطلبات المتدفقة المسجلة (محتواها الفعلي) */
    val streamedBodies = ArrayList<ByteArray>()

    /** طابور استجابات — يُستهلك بالترتيب؛ يُعاد آخر عنصر عند النفاد */
    val queue = ArrayDeque<HttpResponse>()

    /** معالج بديل ديناميكي (يُستخدم وحده إن لم يوجد طابور) */
    var handler: ((HttpRequest) -> HttpResponse)? = null

    /** سجل استدعاءات executeStreaming لأغراض التحقق */
    var streamingCalls = 0

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        val next = if (queue.isEmpty()) handler?.invoke(request) else queue.removeFirst()
        return next ?: error("no canned response for ${request.method} ${request.url}")
    }

    override suspend fun executeStreaming(
        request: HttpRequest,
        bodyLength: Long?,
        openBody: (() -> InputStream)?,
        onResponse: suspend (code: Int, headers: Map<String, String>, body: InputStream) -> Unit,
    ): Int {
        requests.add(request)
        streamingCalls++
        openBody?.invoke()?.use { streamedBodies.add(it.readBytes()) }
        val next = (if (queue.isEmpty()) handler?.invoke(request) else queue.removeFirst())
            ?: error("no canned response")
        onResponse(next.code, next.headers, ByteArrayInputStream(next.body))
        return next.code
    }
}

/** مصنع استجابات مختصر */
object Resp {
    fun ok(json: String): HttpResponse = HttpResponse(200, mapOf("Content-Type" to "application/json"), json.toByteArray(Charsets.UTF_8))

    fun ok(body: ByteArray, headers: Map<String, String> = emptyMap()): HttpResponse =
        HttpResponse(200, headers, body)

    fun code(c: Int, json: String = "{}", headers: Map<String, String> = emptyMap()): HttpResponse =
        HttpResponse(c, headers, json.toByteArray(Charsets.UTF_8))

    /** 308 إكمال جزئي لـ Drive مع ترويسة Range */
    fun partial308(rangeEnd: Long): HttpResponse = HttpResponse(
        308,
        mapOf("Range" to "bytes=0-$rangeEnd"),
        ByteArray(0),
    )
}

/** تشغيل معلّق متزامن للاختبارات */
fun <T> cloudRun(block: suspend () -> T): T = runBlocking { block() }
