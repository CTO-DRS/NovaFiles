package com.novafiles.app.core.netlog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * ══════════════════════════════════════════════════════════
 *  v0.27 — بنية سجل مشتركة لخوادم الشبكة (FTP وWebDAV)
 *  ─ ConnectionLog: حلقة ذاكرة ثابتة السعة لأحداث الخادم
 *  ─ SpeedLimiter: حادّ سرعة Token-Bucket خالص بساعة قابلة للحقن
 *  كلها خالصة JVM 100% وقابلة للاختبار دون شبكة ولا Android.
 * ══════════════════════════════════════════════════════════
 */

/** نوع حدث الخادم — يُعرَض محلياً بسلاسل مترجمة في الواجهة */
enum class NetEventType { CONNECT, LOGIN, LOGIN_FAILED, DISCONNECT, DOWNLOAD, UPLOAD, MKDIR, DELETE, MOVE, ERROR }

/** حدث واحد في سجل الخادم */
data class NetEvent(
    val time: Long,
    val type: NetEventType,
    val detail: String,
    val bytes: Long = 0,
)

/**
 * سجل اتصالات الخادم — حلقة ثابتة السعة (الأحدث أولاً).
 * يستخدمه FtpServer وWebDavServer لتوثيق الجلسات والنقل،
 * وتعرضه الواجهة عبر StateFlow ليحدّث Compose تلقائياً.
 */
class ConnectionLog(private val capacity: Int = 200) {

    private val buf = ArrayDeque<NetEvent>(capacity)
    private val _events = MutableStateFlow<List<NetEvent>>(emptyList())

    /** الأحداث مرتبة من الأحدث إلى الأقدم */
    val events: StateFlow<List<NetEvent>> = _events.asStateFlow()

    fun append(type: NetEventType, detail: String, bytes: Long = 0, time: Long = System.currentTimeMillis()) {
        synchronized(buf) {
            buf.addFirst(NetEvent(time, type, detail, bytes))
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

/**
 * حادّ سرعة Token-Bucket مشترك بين كل اتصالات الخادم.
 * ─ bytesPerSecond = 0 يعني «بلا حد» (المستهلكون يمرّون فوراً).
 * ─ الساعة والنوم حقنان للاختبار بساعة وهمية دون انتظار حقيقي.
 *
 * الخوارزمية: ميزانية متجددة تُحتسب من فارق الزمن، ومن يستهلك أكثر من
 * الميزانية ينام حتى تتجدد — بالتالي المعدل الكلي لا يتجاوز الحد.
 */
class SpeedLimiter(
    /** الحد بالبايت/ثانية — 0 أو سالب = بلا تحديد */
    val bytesPerSecond: Long,
    private val now: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val lock = Object()
    /** الميزانية تبدأ بثانية كاملة (دفعة أولى) ثم تتجدد بالمعدل */
    private var budget: Double = bytesPerSecond.toDouble().coerceAtLeast(0.0)
    private var last: Long = now()

    /** يستهلك n بايت؛ ينام عند الحاجة (بالساعة الحقيقية) ويعيد المدة بالنانية للاختبار */
    fun consume(n: Int): Long {
        if (bytesPerSecond <= 0 || n <= 0) return 0
        var waited = 0L
        synchronized(lock) {
            // تجديد الخامل: سقف الميزانية = ثانية واحدة (دفعة)
            val t = now()
            val elapsed = (t - last).coerceAtLeast(0)
            last = t
            budget = (budget + elapsed / 1_000_000_000.0 * bytesPerSecond)
                .coerceAtMost(bytesPerSecond.toDouble())
            // سداد العجز: نم بالنسبة للعجز ونُجمّع بلا سقف حتى يُسدَّد
            var need = n - budget
            while (need > 0) {
                val ms = (need / bytesPerSecond * 1000.0).toLong().coerceAtLeast(1)
                sleep(ms)
                waited += ms
                val t2 = now()
                val el2 = (t2 - last).coerceAtLeast(0)
                last = t2
                budget += el2 / 1_000_000_000.0 * bytesPerSecond
                need = n - budget
            }
            budget = (budget - n).coerceAtLeast(0.0)
        }
        return waited
    }
}

/** مجرى إخراج مُقيَّد: يمرر كل كتابة عبر حادّ السرعة (يغلف مجرى النقل) */
class ThrottledOutputStream(out: OutputStream, private val limiter: SpeedLimiter) :
    FilterOutputStream(out) {

    override fun write(b: Int) {
        limiter.consume(1)
        out.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        limiter.consume(len)
        out.write(b, off, len)
    }
}
