package com.novafiles.app.core.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات متوقع التخزين — انحدار خطي بمتجهات مثبتة وسياسة الالتقاط الأسبوعي */
class StorageForecasterTest {

    private val gb = 1024L * 1024 * 1024
    private val week = StorageForecaster.WEEK_MS
    private val t0 = 1_800_000_000_000L

    private fun point(weekIndex: Long, used: Long) = StoragePoint(
        capturedAt = t0 + weekIndex * week,
        usedBytes = used,
    )

    // ═══════════════ الانحدار الخطي ═══════════════

    @Test
    fun `regress perfect line exact slope intercept and r2`() {
        val (slope, intercept, r2) = StorageForecaster.regress(listOf(0.0, 1.0, 2.0), listOf(0.0, 10.0, 20.0))
        assertEquals(10.0, slope, 1e-9)
        assertEquals(0.0, intercept, 1e-9)
        assertEquals(1.0, r2, 1e-9)
    }

    @Test
    fun `regress noisy data gives r2 below one`() {
        val (_, _, r2) = StorageForecaster.regress(listOf(0.0, 1.0, 2.0), listOf(0.0, 10.0, 15.0))
        assertTrue("R² لبيانات مزعجة يجب أن يكون < 1", r2 < 1.0)
        assertTrue(r2 > 0.5)
    }

    @Test
    fun `regress constant values have r2 one`() {
        val (slope, _, r2) = StorageForecaster.regress(listOf(0.0, 1.0, 2.0), listOf(5.0, 5.0, 5.0))
        assertEquals(0.0, slope, 1e-12)
        assertEquals(1.0, r2, 1e-12)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `regress rejects single point`() {
        StorageForecaster.regress(listOf(0.0), listOf(1.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `regress rejects mismatched sizes`() {
        StorageForecaster.regress(listOf(0.0, 1.0), listOf(1.0))
    }

    // ═══════════════ التوقع: حالات الخمس ═══════════════

    @Test
    fun `perfect weekly growth of 1gb forecasts exact weeks to full`() {
        val points = listOf(point(0, 1 * gb), point(1, 2 * gb), point(2, 3 * gb))
        val f = StorageForecaster.forecast(points, capacityBytes = 5 * gb)
        assertEquals(ForecastState.GROWING, f.state)
        assertEquals(gb, f.weeklyRateBytes)
        assertEquals(2.0, f.weeksToFull!!, 1e-6)
        assertEquals(1.0, f.confidence, 1e-9)
        assertEquals(3 * gb, f.currentBytes)
    }

    @Test
    fun `noisy growth keeps high confidence`() {
        val points = listOf(point(0, 1 * gb), point(1, 2 * gb + 200 * 1024 * 1024), point(2, 3 * gb))
        val f = StorageForecaster.forecast(points, capacityBytes = 6 * gb)
        assertEquals(ForecastState.GROWING, f.state)
        assertTrue("ثقة بيانات شبه خطية يجب أن تتجاوز 0.9", f.confidence > 0.9)
        assertTrue(f.confidence < 1.0)
    }

    @Test
    fun `shrinking usage is freeing with no weeks to full`() {
        val points = listOf(point(0, 3 * gb), point(1, 2 * gb), point(2, 1 * gb))
        val f = StorageForecaster.forecast(points, capacityBytes = 10 * gb)
        assertEquals(ForecastState.FREEING, f.state)
        assertEquals(-gb, f.weeklyRateBytes)
        assertNull(f.weeksToFull)
    }

    @Test
    fun `flat usage below threshold is stable`() {
        val points = listOf(point(0, 3 * gb), point(1, 3 * gb), point(2, 3 * gb))
        val f = StorageForecaster.forecast(points, capacityBytes = 10 * gb)
        assertEquals(ForecastState.STABLE, f.state)
        assertNull(f.weeksToFull)
    }

    @Test
    fun `tiny weekly rate is stable even if slightly positive`() {
        // 512KB أسبوعياً — تحت عتبة 1MB
        val step = 512L * 1024
        val points = listOf(point(0, gb), point(1, gb + step), point(2, gb + 2 * step))
        val f = StorageForecaster.forecast(points, capacityBytes = 10 * gb)
        assertEquals(ForecastState.STABLE, f.state)
    }

    @Test
    fun `current at or above capacity is full`() {
        val points = listOf(point(0, 4 * gb), point(1, 5 * gb))
        assertEquals(
            ForecastState.FULL,
            StorageForecaster.forecast(points, capacityBytes = 5 * gb).state,
        )
        assertEquals(
            ForecastState.FULL,
            StorageForecaster.forecast(points, capacityBytes = 4 * gb).state,
        )
    }

    @Test
    fun `insufficient data for zero or one point`() {
        assertEquals(
            ForecastState.INSUFFICIENT_DATA,
            StorageForecaster.forecast(emptyList(), 10 * gb).state,
        )
        assertEquals(
            ForecastState.INSUFFICIENT_DATA,
            StorageForecaster.forecast(listOf(point(0, gb)), 10 * gb).state,
        )
    }

    @Test
    fun `invalid capacity is insufficient data`() {
        val points = listOf(point(0, gb), point(1, 2 * gb))
        assertEquals(
            ForecastState.INSUFFICIENT_DATA,
            StorageForecaster.forecast(points, capacityBytes = 0).state,
        )
        assertEquals(
            ForecastState.INSUFFICIENT_DATA,
            StorageForecaster.forecast(points, capacityBytes = -1).state,
        )
    }

    @Test
    fun `unsorted input points are handled chronologically`() {
        val points = listOf(point(2, 3 * gb), point(0, 1 * gb), point(1, 2 * gb))
        val f = StorageForecaster.forecast(points, capacityBytes = 5 * gb)
        assertEquals(ForecastState.GROWING, f.state)
        assertEquals(gb, f.weeklyRateBytes)
    }

    // ═══════════════ سياسة الالتقاط الأسبوعي ═══════════════

    @Test
    fun `capture due when no points`() {
        assertTrue(StorageForecaster.shouldCapture(emptyList(), nowMs = t0))
    }

    @Test
    fun `capture not due before interval and due at it`() {
        val points = listOf(point(0, gb))
        val last = points.last().capturedAt
        assertFalse(StorageForecaster.shouldCapture(points, nowMs = last + 6 * StorageForecaster.DAY_MS))
        assertTrue(StorageForecaster.shouldCapture(points, nowMs = last + 7 * StorageForecaster.DAY_MS))
    }

    @Test
    fun `custom interval respected`() {
        val points = listOf(point(0, gb))
        val last = points.last().capturedAt
        assertFalse(StorageForecaster.shouldCapture(points, nowMs = last + 2 * StorageForecaster.DAY_MS, intervalDays = 3))
        assertTrue(StorageForecaster.shouldCapture(points, nowMs = last + 3 * StorageForecaster.DAY_MS, intervalDays = 3))
    }

    // ═══════════════ بناء النقطة من لقطة الفهرس ═══════════════

    @Test
    fun `capture point sums files and aggregates top dirs`() {
        val root = "/storage/emulated/0"
        fun file(path: String, size: Long) =
            com.novafiles.app.core.analyzer.SnapshotRow(path, path.substringAfterLast('/'), path.substringBeforeLast('/'), size, t0, false)
        fun dir(path: String) =
            com.novafiles.app.core.analyzer.SnapshotRow(path, path.substringAfterLast('/'), path.substringBeforeLast('/'), 0L, t0, true)
        val rows = listOf(
            file("$root/Download/a.pdf", 100),
            file("$root/Download/b.pdf", 50),
            file("$root/DCIM/c.jpg", 30),
            file("$root/rootfile.txt", 20), // ملف مباشرة في الجذر
            dir("$root/Download"),          // المجلدات تُستبعد من المجموع
        )
        val p = StorageForecaster.capturePoint(rows, nowMs = t0, rootPrefix = root)
        assertEquals(200L, p.usedBytes)
        assertEquals(150L, p.perDir["Download"])
        assertEquals(30L, p.perDir["DCIM"])
        assertEquals(20L, p.perDir[""])
        assertEquals(3, p.perDir.size)
    }

    // ═══════════════ نمو الأدلة بين نقطتين ═══════════════

    @Test
    fun `growth keeps positives sorted desc and drops shrinkage`() {
        val prev = StoragePoint(t0, 1000, mapOf("A" to 100L, "B" to 50L))
        val curr = StoragePoint(t0 + week, 1200, mapOf("A" to 150L, "B" to 40L, "C" to 70L))
        val g = StorageForecaster.growthBetween(prev, curr)
        assertEquals(listOf("C", "A"), g.map { it.path })
        assertEquals(70L, g[0].growthBytes)
        assertEquals(50L, g[1].growthBytes)
    }

    @Test
    fun `new dir counts as full growth`() {
        val prev = StoragePoint(t0, 0, emptyMap())
        val curr = StoragePoint(t0 + week, 500, mapOf("New" to 500L))
        val g = StorageForecaster.growthBetween(prev, curr)
        assertEquals(1, g.size)
        assertEquals("New", g[0].path)
    }

    @Test
    fun `topN caps the list with deterministic tie-break by path`() {
        val prev = StoragePoint(t0, 0, emptyMap())
        val curr = StoragePoint(t0 + week, 0, mapOf("b" to 10L, "a" to 10L, "c" to 10L))
        val g = StorageForecaster.growthBetween(prev, curr, topN = 2)
        assertEquals(listOf("a", "b"), g.map { it.path })
    }

    // ═══════════════ التجميع الأسبوعي ═══════════════

    @Test
    fun `daily points collapse to weekly keeping latest per bucket`() {
        // قاعدة محاذاة أسبوعية تامة: يوم 0-6 حاوية واحدة والأحدث فيها يوم 6، ثم 7-13
        val base = StorageForecaster.WEEK_MS * 3000
        val daily = (0 until 14).map { d ->
            StoragePoint(base + d * StorageForecaster.DAY_MS, 100L + d)
        }
        val weekly = StorageForecaster.weeklyAggregate(daily)
        assertEquals(2, weekly.size)
        assertEquals(base + 6 * StorageForecaster.DAY_MS, weekly[0].capturedAt)
        assertEquals(106L, weekly[0].usedBytes)
        assertEquals(113L, weekly[1].usedBytes)
    }

    @Test
    fun `history trimmed to max points keeping newest`() {
        val many = (0 until 60).map { w -> point(w.toLong(), w * 10L) }
        val trimmed = StorageForecaster.weeklyAggregate(many)
        assertEquals(StorageForecaster.MAX_POINTS, trimmed.size)
        assertEquals(59 * 10L, trimmed.last().usedBytes)
        assertEquals(8 * 10L, trimmed.first().usedBytes) // 60 - 52 = 8
    }

    @Test
    fun `single point passes through sorted`() {
        val p = listOf(point(5, 42))
        assertEquals(p, StorageForecaster.weeklyAggregate(p))
    }
}
