package com.novafiles.app.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات محرك التشغيل — السرعات، التكرار، الاستئناف، والوقت */
class MediaPlaybackEngineTest {

    private val engine = MediaPlaybackEngine

    // ═══════════════ السرعات ═══════════════

    @Test
    fun `speed cycle forward from normal`() {
        assertEquals(PlaybackSpeed.S125, engine.nextSpeed(PlaybackSpeed.S100))
    }

    @Test
    fun `speed cycle forward wraps from max to min`() {
        assertEquals(PlaybackSpeed.S050, engine.nextSpeed(PlaybackSpeed.S200))
    }

    @Test
    fun `speed cycle backward wraps from min to max`() {
        assertEquals(PlaybackSpeed.S200, engine.prevSpeed(PlaybackSpeed.S050))
    }

    @Test
    fun `speed full cycle returns to start`() {
        var s = PlaybackSpeed.S100
        repeat(PlaybackSpeed.entries.size) { s = engine.nextSpeed(s) }
        assertEquals(PlaybackSpeed.S100, s)
    }

    @Test
    fun `speed nearest snaps to allowed value`() {
        assertEquals(PlaybackSpeed.S150, PlaybackSpeed.nearest(1.55f))
        assertEquals(PlaybackSpeed.S050, PlaybackSpeed.nearest(0.1f))
        assertEquals(PlaybackSpeed.S200, PlaybackSpeed.nearest(9.9f))
    }

    @Test
    fun `speed range covers 0_5 to 2_0`() {
        assertEquals(0.5f, PlaybackSpeed.entries.first().x)
        assertEquals(2.0f, PlaybackSpeed.entries.last().x)
    }

    // ═══════════════ التكرار والتنقل بين المقاطع ═══════════════

    @Test
    fun `repeat one stays on same index`() {
        assertEquals(3, engine.nextIndex(3, 10, MediaRepeatMode.ONE))
        assertEquals(3, engine.prevIndex(3, 10, MediaRepeatMode.ONE))
    }

    @Test
    fun `repeat all wraps around`() {
        assertEquals(0, engine.nextIndex(9, 10, MediaRepeatMode.ALL))
        assertEquals(9, engine.prevIndex(0, 10, MediaRepeatMode.ALL))
    }

    @Test
    fun `repeat off stops at ends`() {
        assertNull(engine.nextIndex(9, 10, MediaRepeatMode.OFF))
        assertNull(engine.prevIndex(0, 10, MediaRepeatMode.OFF))
    }

    @Test
    fun `repeat off advances normally in middle`() {
        assertEquals(4, engine.nextIndex(3, 10, MediaRepeatMode.OFF))
        assertEquals(2, engine.prevIndex(3, 10, MediaRepeatMode.OFF))
    }

    @Test
    fun `empty playlist returns null`() {
        assertNull(engine.nextIndex(0, 0, MediaRepeatMode.ALL))
        assertNull(engine.prevIndex(0, 0, MediaRepeatMode.ALL))
    }

    @Test
    fun `single item playlist with repeat all stays at zero`() {
        assertEquals(0, engine.nextIndex(0, 1, MediaRepeatMode.ALL))
        assertEquals(0, engine.prevIndex(0, 1, MediaRepeatMode.ALL))
    }

    // ═══════════════ التنقل الدقيق ═══════════════

    @Test
    fun `seek forward clamps to duration`() {
        assertEquals(60_000L, engine.seekBy(58_000L, 10_000L, 60_000L))
    }

    @Test
    fun `seek backward clamps to zero`() {
        assertEquals(0L, engine.seekBy(3_000L, -10_000L, 60_000L))
    }

    @Test
    fun `seek within bounds applies delta`() {
        assertEquals(15_000L, engine.seekBy(10_000L, 5_000L, 60_000L))
    }

    @Test
    fun `seek with unknown duration never negative`() {
        assertEquals(0L, engine.seekBy(1_000L, -5_000L, 0L))
    }

    @Test
    fun `clamp position respects duration`() {
        assertEquals(0L, engine.clampPosition(-5L, 100L))
        assertEquals(100L, engine.clampPosition(500L, 100L))
        assertEquals(50L, engine.clampPosition(50L, 100L))
    }

    // ═══════════════ تنسيق الوقت ═══════════════

    @Test
    fun `time format minutes seconds`() {
        assertEquals("0:00", engine.formatTime(0))
        assertEquals("0:05", engine.formatTime(5_000))
        assertEquals("1:05", engine.formatTime(65_000))
        assertEquals("12:59", engine.formatTime(779_000))
    }

    @Test
    fun `time format hours`() {
        assertEquals("1:00:00", engine.formatTime(3_600_000))
        assertEquals("2:05:09", engine.formatTime(7_509_000))
    }

    @Test
    fun `time format negative clamps to zero`() {
        assertEquals("0:00", engine.formatTime(-1_000))
    }

    // ═══════════════ سياسة ومخزن الاستئناف ═══════════════

    @Test
    fun `resume policy rejects tiny positions`() {
        assertFalse(MediaResumePolicy.shouldResume(MediaResumePoint(2_000, 300_000, 0)))
    }

    @Test
    fun `resume policy rejects near-end positions`() {
        // أقل من 10 ثوانٍ من النهاية
        assertFalse(MediaResumePolicy.shouldResume(MediaResumePoint(295_000, 300_000, 0)))
    }

    @Test
    fun `resume policy accepts middle positions`() {
        assertTrue(MediaResumePolicy.shouldResume(MediaResumePoint(120_000, 300_000, 0)))
    }

    @Test
    fun `resume policy rejects unknown duration`() {
        assertFalse(MediaResumePolicy.shouldResume(MediaResumePoint(120_000, 0, 0)))
    }

    @Test
    fun `resume store round trip`() {
        val store = InMemoryMediaResumeStore()
        store.save("a.mp4", 42_000, 300_000)
        val p = store.load("a.mp4")
        assertEquals(42_000L, p!!.positionMs)
        assertEquals(300_000L, p.durationMs)
    }

    @Test
    fun `resume store missing key returns null`() {
        val store = InMemoryMediaResumeStore()
        assertNull(store.load("missing.mp4"))
    }

    @Test
    fun `resume store save overwrites same key`() {
        val store = InMemoryMediaResumeStore()
        store.save("a.mp4", 10_000, 300_000)
        store.save("a.mp4", 20_000, 300_000)
        assertEquals(20_000L, store.load("a.mp4")!!.positionMs)
        assertEquals(1, store.size)
    }

    @Test
    fun `resume store clear removes entry`() {
        val store = InMemoryMediaResumeStore()
        store.save("a.mp4", 10_000, 300_000)
        store.clear("a.mp4")
        assertNull(store.load("a.mp4"))
    }

    @Test
    fun `resume store LRU evicts eldest beyond capacity`() {
        val store = InMemoryMediaResumeStore(maxEntries = 3)
        store.save("a", 1_000, 100_000)
        store.save("b", 1_000, 100_000)
        store.save("c", 1_000, 100_000)
        store.save("d", 1_000, 100_000) // يطرد a
        assertNull(store.load("a"))
        assertEquals(3, store.size)
    }

    @Test
    fun `resume store read refreshes recency`() {
        var clock = 0L
        val store = InMemoryMediaResumeStore(maxEntries = 2, now = { clock })
        store.save("a", 1_000, 100_000); clock = 1
        store.save("b", 1_000, 100_000); clock = 2
        store.load("a") // a صار الأحدث — b يُطرَد لاحقاً
        clock = 3
        store.save("c", 1_000, 100_000)
        assertNull(store.load("b"))
        assertEquals(1_000L, store.load("a")!!.positionMs)
    }
}
