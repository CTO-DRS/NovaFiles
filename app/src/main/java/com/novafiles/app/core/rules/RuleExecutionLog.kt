package com.novafiles.app.core.rules

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v0.35.0 — سجل تنفيذ قواعد الأتمتة (نمط ConnectionLog من v0.27).
 * حلقة ذاكرة ثابتة السعة لأحداث التشغيل — الأحدث أولاً — عبر StateFlow
 * لتحدّث الواجهة تلقائياً. خالص JVM 100%.
 */

/** نوع حدث سجل القواعد */
enum class RuleEventType {
    /** بدء تشغيل قاعدة */
    RUN_START,

    /** تشغيل تجربة جافة — تخطيط بلا تنفيذ */
    DRY_RUN,

    /** فعل نُفذ بنجاح */
    ACTION_OK,

    /** فعل فشل — القاعدة تكمل الأفعال التالية (عزل الأعطال) */
    ACTION_FAILED,

    /** تراجع ناجح عن نقل */
    UNDO,

    /** تراجع فاشل (الملف الأصلي مشغول مثلاً) */
    UNDO_FAILED,

    /** القاعدة معطلة أو تخطت التشغيل */
    SKIPPED,
}

/** حدث واحد في سجل القواعد */
data class RuleEvent(
    val time: Long,
    val ruleId: String,
    val ruleName: String,
    val type: RuleEventType,
    val detail: String,
    val bytes: Long = 0,
)

/** مرجع تشغيل مكتمل — صف في تاريخ التشغيل (الأحدث أولاً) */
data class RuleRunRef(
    val runId: Long,
    val ruleId: String,
    val ruleName: String,
    val startedAt: Long,
    val dryRun: Boolean,
    val ok: Int,
    val failed: Int,
    /** هل يحتوي التشغيل عمليات نقل قابلة للتراجع؟ */
    val undoable: Boolean,
)

class RuleExecutionLog(private val capacity: Int = 200) {

    private val buf = ArrayDeque<RuleEvent>(capacity)
    private val _events = MutableStateFlow<List<RuleEvent>>(emptyList())

    /** الأحداث مرتبة من الأحدث إلى الأقدم */
    val events: StateFlow<List<RuleEvent>> = _events.asStateFlow()

    fun append(
        type: RuleEventType,
        ruleId: String,
        ruleName: String,
        detail: String,
        bytes: Long = 0,
        time: Long = System.currentTimeMillis(),
    ) {
        synchronized(buf) {
            buf.addFirst(RuleEvent(time, ruleId, ruleName, type, detail, bytes))
            while (buf.size > capacity) buf.removeLast()
            _events.value = buf.toList()
        }
    }

    fun clear() {
        synchronized(buf) {
            buf.clear()
            _events.value = emptyList()
        }
    }

    val size: Int get() = synchronized(buf) { buf.size }
}
