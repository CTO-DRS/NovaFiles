package com.novafiles.app.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات جدولة أزواج المزامنة — فترات، قيود، مواعيد قادمة، وحالات الجولات */
class SyncScheduleTest {

    // ═══════════════ الفترات ═══════════════

    @Test
    fun `manual has null interval`() {
        assertNull(SyncInterval.MANUAL.millis)
    }

    @Test
    fun `hourly interval is exactly one hour`() {
        assertEquals(3_600_000L, SyncInterval.HOURLY.millis)
    }

    @Test
    fun `preset intervals are correct millis`() {
        assertEquals(6L * 3_600_000L, SyncInterval.EVERY_6_HOURS.millis)
        assertEquals(12L * 3_600_000L, SyncInterval.EVERY_12_HOURS.millis)
        assertEquals(86_400_000L, SyncInterval.DAILY.millis)
        assertEquals(604_800_000L, SyncInterval.WEEKLY.millis)
    }

    @Test
    fun `all preset intervals exceed workmanager 15min floor`() {
        SyncInterval.entries.filter { it != SyncInterval.MANUAL }.forEach {
            assertTrue("$it must be >= 15min", it.millis!! >= 15L * 60L * 1000L)
        }
    }

    @Test
    fun `fromName falls back to manual for unknown values`() {
        assertEquals(SyncInterval.MANUAL, SyncInterval.fromName(null))
        assertEquals(SyncInterval.MANUAL, SyncInterval.fromName("never-heard-of"))
        assertEquals(SyncInterval.WEEKLY, SyncInterval.fromName("WEEKLY"))
    }

    // ═══════════════ القيود ═══════════════

    @Test
    fun `wifi-only blocks metered networks`() {
        val c = PairConstraints(wifiOnly = true, requireCharging = false)
        assertFalse(c.satisfied(PairRunEnvironment(networkMetered = true, charging = false)))
        assertTrue(c.satisfied(PairRunEnvironment(networkMetered = false, charging = false)))
    }

    @Test
    fun `charging requirement blocks on battery`() {
        val c = PairConstraints(wifiOnly = false, requireCharging = true)
        assertFalse(c.satisfied(PairRunEnvironment(networkMetered = false, charging = false)))
        assertTrue(c.satisfied(PairRunEnvironment(networkMetered = false, charging = true)))
    }

    @Test
    fun `combined constraints must all pass`() {
        val c = PairConstraints(wifiOnly = true, requireCharging = true)
        assertTrue(c.satisfied(PairRunEnvironment(networkMetered = false, charging = true)))
        assertFalse(c.satisfied(PairRunEnvironment(networkMetered = true, charging = true)))
        assertFalse(c.satisfied(PairRunEnvironment(networkMetered = false, charging = false)))
    }

    @Test
    fun `no constraints always pass`() {
        val c = PairConstraints(wifiOnly = false, requireCharging = false)
        assertTrue(c.satisfied(PairRunEnvironment(networkMetered = true, charging = false)))
    }

    // ═══════════════ موعد الجولة القادمة ═══════════════

    @Test
    fun `manual has no next run`() {
        assertNull(SyncSchedule.nextRunAt(1000L, 500L, SyncInterval.MANUAL))
    }

    @Test
    fun `next run for never-synced pair is now plus interval`() {
        assertEquals(1000L + 3_600_000L, SyncSchedule.nextRunAt(1000L, null, SyncInterval.HOURLY))
    }

    @Test
    fun `next run is last run plus interval`() {
        assertEquals(500L + 86_400_000L, SyncSchedule.nextRunAt(1000L, 500L, SyncInterval.DAILY))
    }

    @Test
    fun `overdue detection`() {
        val next = SyncSchedule.nextRunAt(nowMs = 10_000L, lastRunMs = 0L, interval = SyncInterval.HOURLY)
        assertFalse(SyncSchedule.isOverdue(10_000L, next)) // موعد مستقبلي
        assertTrue(SyncSchedule.isOverdue(3_600_000L + 1L, next)) // تجاوزه بلحظة
        assertFalse(SyncSchedule.isOverdue(0L, null)) // اليدوي لا يتأخر
    }

    // ═══════════════ تقرير الجولة وحالتها ═══════════════

    @Test
    fun `clean run reports success`() {
        assertEquals(
            SyncRunStatus.SUCCESS,
            PairRunReport(copied = 3, updated = 1, skipped = 9).status(),
        )
    }

    @Test
    fun `run with some failures and progress reports partial`() {
        assertEquals(
            SyncRunStatus.PARTIAL,
            PairRunReport(copied = 2, failed = 1).status(),
        )
    }

    @Test
    fun `run with only failures reports failed`() {
        assertEquals(
            SyncRunStatus.FAILED,
            PairRunReport(failed = 4).status(),
        )
    }

    @Test
    fun `cancelled run reports cancelled regardless of progress`() {
        assertEquals(
            SyncRunStatus.CANCELLED,
            PairRunReport(copied = 2, cancelled = true).status(),
        )
        assertEquals(SyncRunStatus.CANCELLED, PairRunReport(cancelled = true).status())
    }

    @Test
    fun `deletion-only run counts as progress for partial`() {
        assertEquals(SyncRunStatus.PARTIAL, PairRunReport(deleted = 2, failed = 1).status())
        assertEquals(SyncRunStatus.SUCCESS, PairRunReport(deleted = 2).status())
    }

    @Test
    fun `fromName falls back to failed for unknown status`() {
        assertEquals(SyncRunStatus.FAILED, SyncRunStatus.fromName(null))
        assertEquals(SyncRunStatus.BLOCKED, SyncRunStatus.fromName("BLOCKED"))
        assertEquals(SyncRunStatus.FAILED, SyncRunStatus.fromName("bogus"))
    }
}
