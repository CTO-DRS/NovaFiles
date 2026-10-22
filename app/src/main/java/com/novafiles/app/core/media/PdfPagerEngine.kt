package com.novafiles.app.core.media

/**
 * v0.31.0 — محرك ترقيم PDF الخالص (pure JVM)
 *
 * منطق التنقل والتكبير لعارض PDF (PdfRenderer النظام) — خالص بالكامل:
 * حدود الصفحات، القفز المباشر، درجات التكبير، وتذكر آخر صفحة قراءة.
 * عارض أندرويد يستهلك هذه القرارات ويرسم البتات فقط.
 */

/** نقطة توقف قراءة PDF لملف */
data class PdfReadingPoint(
    val page: Int,
    val pageCount: Int,
    val savedAt: Long,
)

/** واجهة مخزن آخر صفحة — InMemory للاختبارات وSharedPrefs لأندرويد */
interface PdfReadingStore {
    fun save(key: String, page: Int, pageCount: Int)
    fun load(key: String): PdfReadingPoint?
    fun clear(key: String)
}

/** مخزن قراءة في الذاكرة بسياسة LRU (نمط مخزن الاستئناف) */
class InMemoryPdfReadingStore(
    private val maxEntries: Int = 100,
    private val now: () -> Long = System::currentTimeMillis,
) : PdfReadingStore {

    private val map = LinkedHashMap<String, PdfReadingPoint>()

    override fun save(key: String, page: Int, pageCount: Int) {
        map.remove(key)
        map[key] = PdfReadingPoint(page, pageCount, now())
        while (map.size > maxEntries) {
            map.remove(map.keys.first())
        }
    }

    override fun load(key: String): PdfReadingPoint? {
        val point = map[key] ?: return null
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
 * قرارات ترقيم PDF — كلها خالصة.
 * الصفحات مفهرسة من 0 داخلياً ومن 1 في العرض (تسمية المستخدم).
 */
object PdfPagerEngine {

    // درجات التكبير المسموحة — مسار محدود وثابت (لا قيم حرة غامضة)
    val ZOOM_STEPS = floatArrayOf(1.0f, 1.5f, 2.0f, 3.0f, 4.0f)
    const val MIN_ZOOM = 1.0f
    const val MAX_ZOOM = 4.0f

    /** تثبيت طلب صفحة داخل [0, pageCount-1] — دالة حارسة لكل المدخلات */
    fun clampPage(requested: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        return requested.coerceIn(0, pageCount - 1)
    }

    /** الصفحة التالية — بلا تجاوز النهاية (تبقى في آخر صفحة) */
    fun nextPage(current: Int, pageCount: Int): Int = clampPage(current + 1, pageCount)

    /** الصفحة السابقة — تبقى في أول صفحة عند الحد */
    fun prevPage(current: Int, pageCount: Int): Int = clampPage(current - 1, pageCount)

    /** قفزة مباشرة لرقم صفحة كما يكتبه المستخدم (من 1) — تُثبَّت داخل الحدود */
    fun jumpTo(displayNumber: Int, pageCount: Int): Int = clampPage(displayNumber - 1, pageCount)

    /** هل رقم العرض صالح للقفز؟ (يجب أن يكون صفحة موجودة أصلاً) */
    fun isValidJump(displayNumber: Int, pageCount: Int): Boolean =
        pageCount > 0 && displayNumber in 1..pageCount

    /** تسمية الموضع «3 / 12» بالأرقام المعروضة (من 1) — مستند فارغ يعرض «0 / 0» */
    fun pageLabel(current: Int, pageCount: Int): String {
        if (pageCount <= 0) return "0 / 0"
        return "${clampPage(current, pageCount) + 1} / $pageCount"
    }

    // ----- التكبير -----

    /** الدرجة التالية من درجات التكبير — ثابتة عند MAX */
    fun zoomIn(current: Float): Float {
        val next = ZOOM_STEPS.firstOrNull { it > current + 0.01f }
        return next ?: MAX_ZOOM
    }

    /** الدرجة السابقة — ثابتة عند MIN */
    fun zoomOut(current: Float): Float {
        val prev = ZOOM_STEPS.lastOrNull { it < current - 0.01f }
        return prev ?: MIN_ZOOM
    }

    /** تثبيت تكبير حر (قرصة أصابع) داخل الحدود مع لصق الدرجة القريبة عند الحواف */
    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
}
