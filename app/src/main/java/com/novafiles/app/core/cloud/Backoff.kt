package com.novafiles.app.core.cloud

/**
 * v0.16 — سياسة إعادة المحاولة بتراجع أُسّي وارتجال (exponential backoff + jitter)
 * وفق توصيات المزوّدين الثلاثة لأخطاء 403/429 (rate limit) و 500/503.
 *
 * الارتجال «كامل» (full jitter): التأخير عشوائي في [0, min(cap, base*2^attempt)]
 * — يمنع تزامن عملاء متعددين ضرب الخادم في اللحظة نفسها (thundering herd).
 *
 * كل الزمن محقون: [sleep] يُستدعى بالتأخير المقترح (الاختبار يسجله بلا انتظار)،
 * وrandom يُحقن لتحديدية الاختبارات.
 */
class Backoff(
    /** التأخير الأول بالمللي ثانية */
    private val baseMs: Long = 500,
    /** سقف التأخير بالمللي ثانية */
    private val capMs: Long = 32_000,
    /** عدد المحاولات القصوى (شاملة الأولى) */
    private val maxAttempts: Int = 5,
    private val random: kotlin.random.Random = kotlin.random.Random,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    /** هل الكود قابل لإعادة المحاولة أصلاً؟ */
    fun retryable(code: Int): Boolean =
        code == 429 || code == 408 || code in 500..599

    /**
     * قرار إعادة المحاولة لاستجابة HTTP.
     * Retry-After (بثوانٍ أو تاريخ HTTP) يتجاوز حساب التراجع.
     */
    fun decision(code: Int, attempt: Int, retryAfterHeader: String? = null): Decision {
        if (!retryable(code)) return Decision(stop = true, delayMs = 0)
        if (attempt >= maxAttempts) return Decision(stop = true, delayMs = 0)
        val retryAfterMs = parseRetryAfter(retryAfterHeader)
        val delay = retryAfterMs ?: expFullJitter(attempt)
        return Decision(stop = false, delayMs = delay)
    }

    /** أجّل وفق قرار — يُستدعى فقط عند decision.stop == false */
    suspend fun wait(decision: Decision) {
        if (!decision.stop) sleep(decision.delayMs)
    }

    /** تأخير التراجع الأُسّي بارتجال كامل في [0, min(cap, base*2^attempt)] */
    private fun expFullJitter(attempt: Int): Long {
        val exp = if (attempt <= 0) baseMs else baseMs * (1L shl attempt.coerceAtMost(20))
        val upper = exp.coerceAtMost(capMs)
        return if (upper <= 0) 0 else random.nextLong(0, upper + 1)
    }

    /** Retry-After: ثوانٍ رقمية أو تاريخ HTTP (Sun, 06 Nov 1994 08:49:37 GMT) */
    private fun parseRetryAfter(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { return it.coerceAtLeast(0) * 1000 }
        return runCatching {
            val fmt = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            val t = java.time.ZonedDateTime.parse(value.trim(), fmt)
            val secs = java.time.Duration.between(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC), t).seconds
            (secs.coerceAtLeast(0)) * 1000
        }.getOrNull()
    }

    /** نتيجة قرار إعادة المحاولة */
    data class Decision(val stop: Boolean, val delayMs: Long)
}

/**
 * ينفّذ عملاً مع إعادة محاولة تلقائية وفق سياسة — الاستخدام الموحد في المهايئات:
 * `withRetries(backoff) { transport.execute(...) }`
 */
suspend fun <T> withRetries(
    backoff: Backoff,
    block: suspend (attempt: Int) -> T,
    classify: (T) -> Int? = { null }, // يعيد كود HTTP إن كان الطلب فاشلاً قابلاً للتصنيف
): T {
    var attempt = 0
    while (true) {
        val result = block(attempt)
        val code = classify(result)
        if (code == null) return result
        val decision = backoff.decision(code, attempt)
        if (decision.stop) return result
        backoff.wait(decision)
        attempt++
    }
}
