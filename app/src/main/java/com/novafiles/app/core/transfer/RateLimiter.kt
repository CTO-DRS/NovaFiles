package com.novafiles.app.core.transfer

import kotlinx.coroutines.delay

/**
 * v0.13 — محدّد معدل النقل (دلو الرموز Token Bucket).
 *
 * هدف الخارطة: «حدود عرض نطاق لكل اتصال» — يُحقن في مدير النقل لكل عنصر
 * منفرداً، فلا يخنق النقل الشبكة/البطارية. يدعم 0 = بلا حد.
 *
 * دلو رموز بسيط: يتجمع رمز لكل بايت سماح، وacquire تنتظر بالكوروتين
 * (delay افتراضيّ الزمن — يعمل في الاختبارات مع testScheduler).
 */
class RateLimiter(
    /** السماحية بالبايت/ثانية — 0 أو سالب يعني بلا حد */
    private val bytesPerSec: Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var tokens: Double = 0.0
    private var last = nowMs()

    /** يستهلك n بايتاً من الدلو — يعلّق كوروتين المتصل حتى يتوفر الرصيد */
    suspend fun acquire(n: Long) {
        if (bytesPerSec <= 0) return
        var remaining = n.toDouble()
        while (remaining > 0) {
            refill()
            if (tokens >= remaining) {
                tokens -= remaining
                return
            }
            remaining -= tokens
            tokens = 0.0
            // الانتظار اللازم لتجمع بايتات «remaining»
            val waitMs = (remaining / bytesPerSec * 1000.0).toLong().coerceAtLeast(1)
            delay(waitMs)
        }
    }

    private fun refill() {
        val now = nowMs()
        val elapsed = now - last
        if (elapsed > 0) {
            tokens = (tokens + elapsed * bytesPerSec / 1000.0).coerceAtMost(bytesPerSec.toDouble())
            last = now
        }
    }
}
