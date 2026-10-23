package com.novafiles.app.core.smart

import com.novafiles.app.core.analyzer.SnapshotRow
import com.novafiles.app.core.smart.MaintenanceSuggester.DuplicateCluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات نبضة التخزين وعمود التجميع perDirBlob */
class StoragePulseTest {

    private val mb = 1024L * 1024
    private val gb = 1024L * 1024 * 1024
    private val week = StorageForecaster.WEEK_MS
    private val t0 = 1_800_000_000_000L
    private val root = "/storage/emulated/0"

    // ═══════════════ PerDirBlob ═══════════════

    @Test
    fun `perDirBlob roundtrip with arabic and special names`() {
        val src = mapOf("Download" to 500L, "التنزيلات" to 300L, "weird=name;ok" to 100L, "" to 50L)
        val decoded = PerDirBlob.decode(PerDirBlob.encode(src))
        assertEquals(src, decoded)
    }

    @Test
    fun `perDirBlob encodes sizes descending for stability`() {
        val enc = PerDirBlob.encode(mapOf("a" to 10L, "b" to 99L, "c" to 50L))
        val sizes = enc.split('\n').map { it.substringBefore('\t').toLong() }
        assertEquals(listOf(99L, 50L, 10L), sizes)
    }

    @Test
    fun `perDirBlob decode skips damaged lines and blanks`() {
        val decoded = PerDirBlob.decode("badline\n\n12\tgood\n\tx\nerr\t\n99\tlast")
        assertEquals(mapOf("good" to 12L, "last" to 99L), decoded)
    }

    @Test
    fun `perDirBlob empty blob decodes to empty map`() {
        assertTrue(PerDirBlob.decode("").isEmpty())
        assertTrue(PerDirBlob.decode("   ").isEmpty())
        assertTrue(PerDirBlob.encode(emptyMap()).isEmpty())
    }

    // ═══════════════ نبضة التخزين — التجميع الكامل ═══════════════

    private fun point(weekIndex: Long, used: Long, perDir: Map<String, Long> = emptyMap()) =
        StoragePoint(t0 + weekIndex * week, used, perDir)

    @Test
    fun `pulse combines trend growth and suggestions`() {
        val points = listOf(
            point(0, 1 * gb, mapOf("Download" to 200 * mb)),
            point(1, 2 * gb, mapOf("Download" to 900 * mb)),
            point(2, 3 * gb, mapOf("Download" to 1800 * mb)),
        )
        val d = "$root/Download"
        val rows = listOf(
            SnapshotRow("$d/old.apk", "old.apk", d, 10 * mb, t0 - 40 * DownloadClassifier.DAY_MS, false),
        )
        val report = StoragePulse.report(
            points = points,
            capacityBytes = 6 * gb,
            rows = rows,
            clusters = listOf(DuplicateCluster(listOf("$d/a.jpg", "$d/b.jpg"), 50 * mb)),
            nowMs = t0 + 2 * week,
        )
        // الاتجاه
        assertEquals(ForecastState.GROWING, report.trend.state)
        assertEquals(gb, report.trend.weeklyRateBytes)
        assertEquals(3.0, report.trend.weeksToFull!!, 1e-6)
        // أكبر المصادر النامية بين آخر نقطتين
        assertEquals(listOf("Download"), report.growingDirs.map { it.path })
        assertEquals(900 * mb, report.growingDirs.single().growthBytes)
        // الاقتراحات مرتبة بالوفر: المكررات (50MB) ثم المثبّت (10MB)
        assertEquals(2, report.suggestions.size)
        assertEquals(SuggestionKind.DUPLICATE_CONTENT, report.suggestions[0].kind)
        assertEquals(SuggestionKind.OLD_INSTALLERS, report.suggestions[1].kind)
    }

    @Test
    fun `pulse with fewer than two points has no growing dirs`() {
        val report = StoragePulse.report(
            points = listOf(point(0, 1 * gb)),
            capacityBytes = 6 * gb,
            rows = emptyList(),
            clusters = emptyList(),
            nowMs = t0,
        )
        assertEquals(ForecastState.INSUFFICIENT_DATA, report.trend.state)
        assertTrue(report.growingDirs.isEmpty())
        assertTrue(report.suggestions.isEmpty())
    }

    @Test
    fun `pulse respects dismissed keys end to end`() {
        val points = listOf(
            point(0, 1 * gb),
            point(1, 2 * gb),
        )
        val d = "$root/Download"
        val rows = listOf(
            SnapshotRow("$d/old.apk", "old.apk", d, 10 * mb, t0 - 40 * DownloadClassifier.DAY_MS, false),
        )
        val report = StoragePulse.report(
            points = points,
            capacityBytes = 6 * gb,
            rows = rows,
            clusters = emptyList(),
            nowMs = t0 + week,
            dismissedKeys = setOf("APK:$d"),
        )
        assertTrue(report.suggestions.isEmpty())
    }

    @Test
    fun `pulse daily points are aggregated weekly before forecast`() {
        // 14 نقطة يومية نمو 100MB/يوم → أسبوعياً نمو ~700MB/أسبوع
        val daily = (0 until 14).map { day ->
            StoragePoint(
                capturedAt = t0 + day * StorageForecaster.DAY_MS,
                usedBytes = (day + 1) * 100 * mb,
            )
        }
        val report = StoragePulse.report(
            points = daily,
            capacityBytes = 10 * gb,
            rows = emptyList(),
            clusters = emptyList(),
            nowMs = t0 + 13 * StorageForecaster.DAY_MS,
        )
        assertEquals(ForecastState.GROWING, report.trend.state)
        assertEquals(700 * mb, report.trend.weeklyRateBytes)
    }
}
