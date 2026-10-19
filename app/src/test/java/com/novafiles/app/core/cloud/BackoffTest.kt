package com.novafiles.app.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    // ---------- التصنيف ----------

    @Test
    fun `rate limit and server errors retryable`() {
        val b = Backoff()
        for (code in listOf(429, 408, 500, 502, 503, 504)) assertTrue(b.retryable(code))
        for (code in listOf(400, 401, 403, 404, 409)) assertFalse(b.retryable(code))
    }

    // ---------- القرارات ----------

    @Test
    fun `non retryable code stops immediately`() {
        val b = Backoff()
        val d = b.decision(404, attempt = 0)
        assertTrue(d.stop)
        assertEquals(0, d.delayMs)
    }

    @Test
    fun `max attempts exhausted stops`() {
        val b = Backoff(maxAttempts = 3)
        for (a in 0..2) assertFalse(b.decision(429, attempt = a).stop)
        assertTrue(b.decision(429, attempt = 3).stop)
    }

    @Test
    fun `full jitter bounds base doubling and cap`() {
        var seq = 0
        val b = Backoff(baseMs = 100, capMs = 10_000, random = object : kotlin.random.Random() {
            override fun nextBits(bitCount: Int): Int {
                seq = (seq + 1) % 4
                // 0 ثم أقصى ثم متوسط — لتغطية الحدود
                return when (seq) { 1 -> Int.MAX_VALUE; else -> 0 }
            }
            override fun nextLong(until: Long): Long = if (seq == 1) until - 1 else 0
        })
        // محاولة 0: الحد 100 → jitter في [0,100]
        repeat(20) {
            val d = b.decision(429, attempt = 0)
            assertFalse(d.stop)
            assertTrue("delay ${d.delayMs}", d.delayMs in 0..100)
        }
        // محاولة 3: 100*2^3 = 800 → [0,800]
        repeat(20) {
            assertTrue(b.decision(429, attempt = 3).delayMs in 0..800)
        }
        // محاولة 10: يلمس السقف 10_000
        repeat(20) {
            assertTrue(b.decision(429, attempt = 10).delayMs in 0..10_000)
        }
    }

    @Test
    fun `retry-after seconds overrides exponent`() {
        val b = Backoff(baseMs = 100)
        val d = b.decision(429, attempt = 0, retryAfterHeader = "30")
        assertFalse(d.stop)
        assertEquals(30_000, d.delayMs)
    }

    @Test
    fun `retry-after http date parsed`() {
        val b = Backoff()
        // تاريخ بعد عام — إيجابي دائماً
        val future = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusYears(1)
        val header = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(future)
        val d = b.decision(503, attempt = 0, retryAfterHeader = header)
        assertFalse(d.stop)
        assertTrue(d.delayMs > 1000)
    }

    @Test
    fun `malformed retry-after ignored`() {
        val b = Backoff(baseMs = 100, random = kotlin.random.Random(42))
        val d = b.decision(429, attempt = 0, retryAfterHeader = "not-a-date")
        assertFalse(d.stop)
        assertTrue(d.delayMs in 0..100)
    }

    // ---------- withRetries ----------

    @Test
    fun `withRetries returns first success without waiting`() = cloudRun {
        val waits = ArrayList<Long>()
        val b = Backoff(sleep = { waits.add(it) })
        var calls = 0
        val result = withRetries(
            b,
            block = { calls++; "ok" },
            classify = { null },
        )
        assertEquals("ok", result)
        assertEquals(1, calls)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun `withRetries loops on retryable classification then succeeds`() = cloudRun {
        val waits = ArrayList<Long>()
        val b = Backoff(baseMs = 10, sleep = { waits.add(it) })
        var calls = 0
        val result = withRetries(
            b,
            block = { calls++; "res$calls" },
            classify = { if (it == "res1") 503 else null },
        )
        assertEquals("res2", result)
        assertEquals(2, calls)
        assertEquals(1, waits.size)
    }

    @Test
    fun `withRetries stops after exhausted attempts`() = cloudRun {
        // maxAttempts = عدد قرارات إعادة المحاولة؛ الاستدعاء الأول + 3 إعادات = 4 استدعاءات
        val b = Backoff(maxAttempts = 3, baseMs = 1, sleep = {})
        var calls = 0
        val result = withRetries(
            b,
            block = { calls++; "still-${calls}" },
            classify = { 429 },
        )
        assertEquals("still-4", result)
        assertEquals(4, calls)
    }
}
