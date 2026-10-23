package com.novafiles.app.core.smart

import com.novafiles.app.core.analyzer.SnapshotRow
import kotlin.math.abs
import kotlin.math.max

/**
 * v0.34.0 — متوقع التخزين بانحدار خطي (pure Kotlin — حساب حتمي 100%)
 *
 * «بهذا الوتيرة يمتلئ التخزين خلال ~N أسابيع» مع تقدير أمان (R²).
 * المدخل نقاط استهلاك أسبوعية من فهرس Room — الميل بالمربعات الصغرى.
 *
 * ترتيب القرار (حتمي، يُختبر بمتجهات مثبتة):
 *  1) أقل من نقطتين أو سعة غير صالحة → INSUFFICIENT_DATA
 *  2) الحالي ≥ السعة → FULL
 *  3) |المعدل الأسبوعي| < 1MB → STABLE
 *  4) ميل موجب → GROWING + أسابيع حتى الامتلاء
 *  5) ميل سالب → FREEING
 */
object StorageForecaster {

    const val DAY_MS = 86_400_000L
    const val WEEK_MS = 7 * DAY_MS

    /** عتبة الاستقرار: أقل من ميغابايت أسبوعياً يعتبر ثباتاً */
    const val STABLE_WEEKLY_BYTES = 1L * 1024 * 1024

    /** سقف نقاط التاريخ المحفوظة (سنة من الأسابيع) */
    const val MAX_POINTS = 52

    /** أسابيع افتراضية بين نقاط الالتقاط */
    const val CAPTURE_INTERVAL_DAYS = 7

    /** الانحدار الخطي بالمربعات الصغرى — يعيد (الميل، التقاطع، R²) */
    fun regress(xs: List<Double>, ys: List<Double>): Triple<Double, Double, Double> {
        val n = xs.size
        require(n == ys.size && n >= 2) { "انحدار يحتاج نقطتين فأكثر متساويتين" }
        val xBar = xs.sum() / n
        val yBar = ys.sum() / n
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            sxy += (xs[i] - xBar) * (ys[i] - yBar)
            sxx += (xs[i] - xBar) * (xs[i] - xBar)
        }
        if (sxx <= 1e-12) return Triple(0.0, yBar, 1.0) // كل x متطابق — خط أفقي مثالي
        val slope = sxy / sxx
        val intercept = yBar - slope * xBar
        var ssRes = 0.0
        var ssTot = 0.0
        for (i in 0 until n) {
            val pred = intercept + slope * xs[i]
            ssRes += (ys[i] - pred) * (ys[i] - pred)
            ssTot += (ys[i] - yBar) * (ys[i] - yBar)
        }
        val r2 = if (ssTot <= 1e-12) 1.0 else (1.0 - ssRes / ssTot).coerceIn(0.0, 1.0)
        return Triple(slope, intercept, r2)
    }

    fun forecast(
        points: List<StoragePoint>,
        capacityBytes: Long,
        nowMs: Long = 0L,
    ): ForecastResult {
        val current = points.maxByOrNull { it.capturedAt }?.usedBytes ?: 0L
        if (points.size < 2 || capacityBytes <= 0L) {
            return ForecastResult(ForecastState.INSUFFICIENT_DATA, 0L, null, 0.0, current)
        }
        val sorted = points.sortedBy { it.capturedAt }
        val t0 = sorted.first().capturedAt
        val xs = sorted.map { (it.capturedAt - t0).toDouble() / DAY_MS }
        val ys = sorted.map { it.usedBytes.toDouble() }
        val (slope, _, r2) = regress(xs, ys)
        val weekly = (slope * 7.0).toLong()

        return when {
            current >= capacityBytes ->
                ForecastResult(ForecastState.FULL, weekly, null, r2, current)
            abs(weekly) < STABLE_WEEKLY_BYTES ->
                ForecastResult(ForecastState.STABLE, weekly, null, r2, current)
            slope > 0 -> {
                val remaining = (capacityBytes - current).toDouble()
                val weeks = remaining / (slope * 7.0)
                ForecastResult(ForecastState.GROWING, weekly, weeks, r2, current)
            }
            else -> ForecastResult(ForecastState.FREEING, weekly, null, r2, current)
        }
    }

    /** سياسة الالتقاط الأسبوعي: التقط الآن إن لم توجد نقطة أو مضى ≥ الفاصل */
    fun shouldCapture(
        points: List<StoragePoint>,
        nowMs: Long,
        intervalDays: Int = CAPTURE_INTERVAL_DAYS,
    ): Boolean {
        val last = points.maxOfOrNull { it.capturedAt } ?: return true
        return nowMs - last >= intervalDays * DAY_MS
    }

    /**
     * بناء نقطة من لقطة الفهرس: الإجمالي = مجموع أحجام الملفات،
     * وperDir تجميع الأدلة العليا (أول مقطع بعد الجذر؛ ملفات الجذر مباشرة بمفتاح "").
     */
    fun capturePoint(
        rows: List<SnapshotRow>,
        nowMs: Long,
        rootPrefix: String,
    ): StoragePoint {
        var total = 0L
        val perDir = HashMap<String, Long>()
        for (r in rows) {
            if (r.isDirectory) continue
            total += r.size
            val rel = r.path.removePrefix(rootPrefix).trimStart('/')
            val seg = if (rel.contains('/')) rel.substringBefore('/') else ""
            perDir[seg] = (perDir[seg] ?: 0L) + r.size
        }
        return StoragePoint(capturedAt = nowMs, usedBytes = total, perDir = perDir)
    }

    /** نمو الأدلة العليا بين نقطتين — موجب فقط، تنازلي، مقصوص على topN */
    fun growthBetween(prev: StoragePoint, curr: StoragePoint, topN: Int = 5): List<DirGrowth> {
        val keys = curr.perDir.keys + prev.perDir.keys
        return keys.mapNotNull { k ->
            val g = (curr.perDir[k] ?: 0L) - (prev.perDir[k] ?: 0L)
            if (g > 0) DirGrowth(k, g) else null
        }
            .sortedWith(compareByDescending<DirGrowth> { it.growthBytes }.thenBy { it.path })
            .take(topN)
    }

    /** تجميع أسبوعي: نقطة واحدة لكل أسبوع (الأحدث في الأسبوع)، ثم قصّ التاريخ الأقدم */
    fun weeklyAggregate(points: List<StoragePoint>): List<StoragePoint> {
        if (points.size <= 1) return points.sortedBy { it.capturedAt }
        val byWeek = HashMap<Long, StoragePoint>()
        for (p in points) {
            val bucket = p.capturedAt / WEEK_MS
            val kept = byWeek[bucket]
            if (kept == null || p.capturedAt >= kept.capturedAt) byWeek[bucket] = p
        }
        return byWeek.values.sortedBy { it.capturedAt }.takeLast(MAX_POINTS)
    }

    /** الحفاظ على اتجاه متسق عند عرض نقاط قليلة — أقدم أولاً */
    fun historyFor(points: List<StoragePoint>, max: Int = MAX_POINTS): List<StoragePoint> =
        points.sortedBy { it.capturedAt }.takeLast(max(max, 0))
}
