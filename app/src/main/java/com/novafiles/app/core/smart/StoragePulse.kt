package com.novafiles.app.core.smart

import com.novafiles.app.core.analyzer.SnapshotRow

/**
 * v0.34.0 — لوحة «نبضة التخزين» (pure Kotlin)
 *
 * تجميع واحد يغذي الـ UI بكل ما تحتاجه:
 *  - اتجاه الاستهلاك (انحدار خطي على النقاط الأسبوعية)
 *  - أكبر المصادر النامية (نمو الأدلة العليا بين آخر نقطتين)
 *  - اقتراحات الصيانة مرتبة بالوفر المتوقع
 */
object StoragePulse {

    const val TOP_GROWING = 5

    fun report(
        points: List<StoragePoint>,
        capacityBytes: Long,
        rows: List<SnapshotRow>,
        clusters: List<MaintenanceSuggester.DuplicateCluster>,
        nowMs: Long,
        dismissedKeys: Set<String> = emptySet(),
        suggestionLimit: Int = 24,
    ): PulseReport {
        val weekly = StorageForecaster.weeklyAggregate(points)
        val trend = StorageForecaster.forecast(weekly, capacityBytes, nowMs)
        val growing = if (weekly.size >= 2) {
            StorageForecaster.growthBetween(weekly[weekly.size - 2], weekly.last(), TOP_GROWING)
        } else {
            emptyList()
        }
        val suggestions = MaintenanceSuggester.suggest(
            rows = rows,
            clusters = clusters,
            nowMs = nowMs,
            dismissedKeys = dismissedKeys,
            limit = suggestionLimit,
        )
        return PulseReport(trend, growing, suggestions)
    }
}
