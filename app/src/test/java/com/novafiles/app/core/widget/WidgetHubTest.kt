package com.novafiles.app.core.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مركز بيانات الودجات (v0.17) — JVM خالصة.
 */
class WidgetHubTest {

    // ---------- RecentFilesPayload ----------

    @Test
    fun `recent files sorted by newest and capped at four`() {
        val p = RecentFilesPayload.of(
            listOf(
                RecentFileEntry("old", "/a", 1, 1000, false),
                RecentFileEntry("newest", "/b", 2, 4000, false),
                RecentFileEntry("mid", "/c", 3, 3000, false),
                RecentFileEntry("mid2", "/d", 4, 2000, false),
                RecentFileEntry("extra", "/e", 5, 3500, false),
            ),
        )
        assertEquals(4, p.entries.size)
        assertEquals("newest", p.entries[0].name)
        assertEquals("extra", p.entries[1].name)
        assertEquals("mid", p.entries[2].name)
        assertEquals("mid2", p.entries[3].name)
    }

    @Test
    fun `empty recent stays empty`() {
        assertEquals(0, RecentFilesPayload.of(emptyList()).entries.size)
    }

    // ---------- TransferStatusPayload ----------

    private fun brief(name: String, state: TransferBriefState, pct: Int, speed: Long = 0) =
        TransferBrief(name, state, pct, speed)

    @Test
    fun `transfer summary counts and average percent`() {
        val p = TransferStatusPayload.of(
            listOf(
                brief("a", TransferBriefState.RUNNING, 40, 2048),
                brief("b", TransferBriefState.QUEUED, 0),
                brief("c", TransferBriefState.DONE, 100),
                brief("d", TransferBriefState.FAILED, 10),
            ),
        )
        assertEquals(2, p.active)
        assertEquals(1, p.done)
        assertEquals(1, p.failed)
        assertEquals(37, p.totalPercent) // (40+0+100+10)/4
        assertEquals("a", p.currentName)
        assertEquals(2048, p.speedBytesPerSec)
    }

    @Test
    fun `paused counts as active and is chosen as current after running`() {
        val p = TransferStatusPayload.of(
            listOf(
                brief("paused", TransferBriefState.PAUSED, 50),
                brief("q", TransferBriefState.QUEUED, 0),
            ),
        )
        assertEquals(2, p.active)
        assertEquals("paused", p.currentName)
    }

    @Test
    fun `empty transfers give neutral payload`() {
        val p = TransferStatusPayload.of(emptyList())
        assertEquals(0, p.active)
        assertEquals(0, p.totalPercent)
        assertEquals(null, p.currentName)
    }

    @Test
    fun `total percent clamped to hundred`() {
        val p = TransferStatusPayload.of(listOf(brief("x", TransferBriefState.DONE, 150)))
        assertEquals(100, p.totalPercent)
    }

    @Test
    fun `speed sums only running`() {
        val p = TransferStatusPayload.of(
            listOf(
                brief("a", TransferBriefState.RUNNING, 10, 100),
                brief("b", TransferBriefState.RUNNING, 20, 250),
                brief("c", TransferBriefState.PAUSED, 30, 999),
            ),
        )
        assertEquals(350, p.speedBytesPerSec)
    }

    // ---------- StorageShortcutPayload ----------

    @Test
    fun `usage percent normal`() {
        val s = StorageShortcutPayload("الصور", "/sdcard/Pics", 250, 1000)
        assertEquals(25, s.usagePercent)
    }

    @Test
    fun `usage percent guards zero total and clamps`() {
        assertEquals(0, StorageShortcutPayload("x", "/x", 5, 0).usagePercent)
        assertEquals(100, StorageShortcutPayload("x", "/x", 2000, 1000).usagePercent)
        assertEquals(0, StorageShortcutPayload("x", "/x", -5, 1000).usagePercent)
    }

    // ---------- WidgetFormatters ----------

    @Test
    fun `size labels human readable`() {
        assertEquals("512 B", WidgetFormatters.sizeLabel(512))
        assertEquals("1 KB", WidgetFormatters.sizeLabel(1024))
        assertEquals("1.5 KB", WidgetFormatters.sizeLabel(1536))
        assertEquals("2 MB", WidgetFormatters.sizeLabel(2L * 1024 * 1024))
        assertEquals("1.25 GB", WidgetFormatters.sizeLabel((1.25 * 1024 * 1024 * 1024).toLong()))
        assertEquals("0 B", WidgetFormatters.sizeLabel(-1))
    }

    @Test
    fun `speed labels with unit switch`() {
        assertEquals("—", WidgetFormatters.speedLabel(0))
        assertEquals("800 B/s", WidgetFormatters.speedLabel(800))
        assertEquals("2 KB/s", WidgetFormatters.speedLabel(2048))
        assertEquals("1.5 MB/s", WidgetFormatters.speedLabel((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `relative time buckets`() {
        val now = 10_000_000L
        assertEquals("•", WidgetFormatters.relativeTime(now, now, "د", "س", "ي"))
        assertEquals("5 د", WidgetFormatters.relativeTime(now, now - 5 * 60_000, "د", "س", "ي"))
        assertEquals("3 س", WidgetFormatters.relativeTime(now, now - 3 * 3_600_000, "د", "س", "ي"))
        assertEquals("2 ي", WidgetFormatters.relativeTime(now, now - 2 * 86_400_000, "د", "س", "ي"))
    }

    @Test
    fun `recent subtitle combines size and time`() {
        val now = 10_000_000L
        val e = RecentFileEntry("doc.pdf", "/d/doc.pdf", 2048, now - 5 * 60_000, false)
        val s = WidgetFormatters.recentSubtitle(e, now, "د", "س", "ي")
        assertTrue(s.startsWith("2 KB • "))
        assertTrue(s.endsWith("د"))
    }
}
