package com.novafiles.app.core.vault2

/**
 * v0.15 — سياسة الإخفاء التلقائي: تُخفي الخزنة بعد مهلة من مغادرة التطبيق
 * (افتراضياً 30 ثانية) — نسيان القفل لا يكشف بياناتك.
 *
 * خالصة وجدول-زمني: طبقة الواجهة تسجل onLeave/onEnter/now وتسأل shouldHide.
 */
class AutoHidePolicy(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    var enabled: Boolean = true
    private var lastLeaveAt: Long = Long.MIN_VALUE
    private var hidden: Boolean = false

    /** استدعِ عند مغادرة شاشة الخزنة (onPause / خروج نشاط) */
    fun onLeave(nowMs: Long) {
        lastLeaveAt = nowMs
    }

    /** استدعِ عند العودة — يقرر إن كان يجب الإخفاء فوراً */
    fun onEnter(nowMs: Long): Boolean {
        if (!enabled) {
            hidden = false
            return false
        }
        val should = lastLeaveAt != Long.MIN_VALUE && (nowMs - lastLeaveAt) >= timeoutMs
        hidden = should
        return should
    }

    /** هل الحالة الحالية مخفية (مقفلة) */
    fun isHidden(): Boolean = hidden

    /** قفل يدوي فوري */
    fun hideNow() { hidden = true }

    /** إعادة فتح (بعد تحقق ناجح) */
    fun unhide() { hidden = false; lastLeaveAt = Long.MIN_VALUE }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}
