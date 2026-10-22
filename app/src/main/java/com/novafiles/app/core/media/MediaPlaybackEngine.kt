package com.novafiles.app.core.media

/**
 * v0.31.0 — محرك التشغيل الخالص (pure JVM)
 *
 * كل منطق المشغل القابل للاختبار بدون وسائط حقيقية: سرعات ×0.5–×2،
 * أنماط التكرار، تخزين مواضع الاستئناف بسياسة LRU، تنسيق الوقت،
 * وقيود التنقل الدقيق. طبقة media3 تستهلك هذه القرارات فقط.
 */

/** سرعات التشغيل المسموحة ×0.5–×2 */
enum class PlaybackSpeed(val x: Float) {
    S050(0.5f),
    S075(0.75f),
    S100(1.0f),
    S125(1.25f),
    S150(1.5f),
    S175(1.75f),
    S200(2.0f),
    ;

    companion object {
        /** أقرب سرعة مسموحة لقيمة عائمة (لتفادي حالات حرة من الخارج) */
        fun nearest(x: Float): PlaybackSpeed =
            entries.minByOrNull { kotlin.math.abs(it.x - x) } ?: S100
    }
}

/** نمط التكرار */
enum class MediaRepeatMode {
    /** بلا تكرار — القائمة تتوقف عند نهايتها */
    OFF,

    /** تكرار المقطع الحالي */
    ONE,

    /** تكرار القائمة كاملة */
    ALL,
}

/** نقطة استئناف محفوظة لملف */
data class MediaResumePoint(
    val positionMs: Long,
    val durationMs: Long,
    val savedAt: Long,
)

/**
 * سياسة الاستئناف: متى يستحق الموضعُ الاستئناف؟
 * - أكثر من 5 ثوانٍ (تجاهل النقرات العابرة)
 * - أبعد من 10 ثوانٍ عن النهاية (المقطع شبه المنتهى يبدأ من جديد)
 */
object MediaResumePolicy {
    const val MIN_RESUME_MS = 5_000L
    const val END_MARGIN_MS = 10_000L

    fun shouldResume(point: MediaResumePoint): Boolean {
        if (point.durationMs <= 0) return false
        if (point.positionMs < MIN_RESUME_MS) return false
        return point.positionMs <= point.durationMs - END_MARGIN_MS
    }
}

/** واجهة مخزن الاستئناف — تطبيقان: InMemory للاختبارات وSharedPrefs لأندرويد */
interface MediaResumeStore {
    fun save(key: String, positionMs: Long, durationMs: Long)
    fun load(key: String): MediaResumePoint?
    fun clear(key: String)
}

/**
 * مخزن استئناف في الذاكرة بسياسة LRU — الأحدث يبقى والقديم يُطرد
 * عند تجاوز السعة. زمن الساعة يُحقن للاختبارات الحتمية.
 */
class InMemoryMediaResumeStore(
    private val maxEntries: Int = 100,
    private val now: () -> Long = System::currentTimeMillis,
) : MediaResumeStore {

    private val map = LinkedHashMap<String, MediaResumePoint>()

    override fun save(key: String, positionMs: Long, durationMs: Long) {
        map.remove(key) // أعد إدراجه في النهاية = الأحدث
        map[key] = MediaResumePoint(positionMs, durationMs, now())
        while (map.size > maxEntries) {
            val eldest = map.keys.first()
            map.remove(eldest)
        }
    }

    override fun load(key: String): MediaResumePoint? {
        val point = map[key] ?: return null
        // القراءة تُجدد الحداثة
        map.remove(key)
        map[key] = point
        return point
    }

    override fun clear(key: String) {
        map.remove(key)
    }

    val size: Int get() = map.size
}

/**
 * قرارات التشغيل: سرعات، تكرار، تنقل دقيق، وتنسيق وقت.
 * كل الدوال خالصة — بلا حالة.
 */
object MediaPlaybackEngine {

    // ----- السرعات -----

    /** السرعة التالية في الدورة (النهاية تعود للبداية) */
    fun nextSpeed(current: PlaybackSpeed): PlaybackSpeed {
        val i = current.ordinal
        return PlaybackSpeed.entries[(i + 1) % PlaybackSpeed.entries.size]
    }

    /** السرعة السابقة في الدورة (البداية تعود للنهاية) */
    fun prevSpeed(current: PlaybackSpeed): PlaybackSpeed {
        val i = current.ordinal
        return PlaybackSpeed.entries[(i - 1 + PlaybackSpeed.entries.size) % PlaybackSpeed.entries.size]
    }

    // ----- التكرار والتنقل بين المقاطع -----

    /**
     * مؤشر المقطع التالي في قائمة تشغيل.
     * @return null عند التوقف (نهاية القائمة بلا تكرار)
     */
    fun nextIndex(current: Int, count: Int, repeat: MediaRepeatMode): Int? {
        if (count <= 0) return null
        return when (repeat) {
            MediaRepeatMode.ONE -> current
            MediaRepeatMode.ALL -> (current + 1) % count
            MediaRepeatMode.OFF -> if (current + 1 < count) current + 1 else null
        }
    }

    /** مؤشر المقطع السابق — null عند بداية القائمة بلا تكرار */
    fun prevIndex(current: Int, count: Int, repeat: MediaRepeatMode): Int? {
        if (count <= 0) return null
        return when (repeat) {
            MediaRepeatMode.ONE -> current
            MediaRepeatMode.ALL -> (current - 1 + count) % count
            MediaRepeatMode.OFF -> if (current - 1 >= 0) current - 1 else null
        }
    }

    // ----- التنقل الدقيق -----

    /** إزاحة زمنية مع تثبيت ضمن [0, duration] */
    fun seekBy(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val d = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return (positionMs + deltaMs).coerceIn(0L, d)
    }

    /** تثبيت موضع مطلق ضمن مدة المقطع */
    fun clampPosition(positionMs: Long, durationMs: Long): Long =
        if (durationMs > 0) positionMs.coerceIn(0L, durationMs) else positionMs.coerceAtLeast(0L)

    // ----- تنسيق الوقت -----

    /** تنسيق موضع للتشغيل: m:ss أو h:mm:ss (سالب → 0:00) */
    fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%d:%02d".format(m, s)
        }
    }
}
