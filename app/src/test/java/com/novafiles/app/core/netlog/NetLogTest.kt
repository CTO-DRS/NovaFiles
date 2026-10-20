package com.novafiles.app.core.netlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * اختبارات سجل الاتصال وحادّ السرعة — ساعة وهمية بالكامل
 * فلا انتظار حقيقي ولا عشوائية.
 */
class NetLogTest {

    // ═══════════════ ConnectionLog ═══════════════

    @Test
    fun `log appends newest first`() {
        val log = ConnectionLog(10)
        log.append(NetEventType.CONNECT, "a", time = 1)
        log.append(NetEventType.LOGIN, "b", time = 2)
        log.append(NetEventType.DOWNLOAD, "c", bytes = 5, time = 3)
        val events = log.events.value
        assertEquals(3, events.size)
        assertEquals(NetEventType.DOWNLOAD, events[0].type)
        assertEquals("c", events[0].detail)
        assertEquals(5L, events[0].bytes)
        assertEquals(1L, events[2].time)
    }

    @Test
    fun `log respects capacity and drops oldest`() {
        val log = ConnectionLog(3)
        repeat(7) { log.append(NetEventType.CONNECT, "e$it", time = it.toLong()) }
        val events = log.events.value
        assertEquals(3, events.size)
        assertEquals("e6", events[0].detail)
        assertEquals("e4", events[2].detail)
    }

    @Test
    fun `log clear empties the buffer`() {
        val log = ConnectionLog(5)
        repeat(3) { log.append(NetEventType.LOGIN, "x") }
        assertEquals(3, log.size)
        log.clear()
        assertEquals(0, log.size)
        assertTrue(log.events.value.isEmpty())
    }

    // ═══════════════ SpeedLimiter ═══════════════

    /** ساعة وهمية بالنانية تتحرك فقط عند sleep الوهمي */
    private class FakeClock {
        var nowNanos = 0L
        val sleeps = mutableListOf<Long>()
        val now: () -> Long = { nowNanos }
        val sleep: (Long) -> Unit = { ms ->
            sleeps.add(ms)
            nowNanos += ms * 1_000_000
        }
    }

    @Test
    fun `unlimited limiter passes instantly`() {
        val clock = FakeClock()
        val limiter = SpeedLimiter(0, clock.now, clock.sleep)
        assertEquals(0L, limiter.consume(10_000_000))
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun `first bytes within budget pass without sleep`() {
        val clock = FakeClock()
        val limiter = SpeedLimiter(1000, clock.now, clock.sleep) // 1000 بايت/ث
        assertEquals(0L, limiter.consume(500))
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun `excess bytes force sleep proportional to deficit`() {
        val clock = FakeClock()
        val limiter = SpeedLimiter(1000, clock.now, clock.sleep)
        limiter.consume(500)                 // ميزانية: 500 متبقية
        val waited = limiter.consume(1000)   // عجز 500 → نحو 500ms
        assertTrue("expected sleep, got $waited", waited > 0)
        assertEquals(500L, clock.sleeps.sum())
        assertEquals(1, clock.sleeps.size)
    }

    @Test
    fun `budget refills as fake time advances`() {
        val clock = FakeClock()
        val limiter = SpeedLimiter(1000, clock.now, clock.sleep)
        limiter.consume(1000)                       // استُنفدت الميزانية كاملة
        clock.nowNanos += 2_000_000_000             // مرّت ثانيتان وهمياً
        limiter.consume(0) // لا شيء — لكن نحتاج استدعاء يحدّث الزمن
        val waited = limiter.consume(1000)
        // الميزانية تجددت ثانية كاملة (1000) + حد أعلى للميزانية = 1000
        assertEquals(0L, waited)
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun `total consumption respects cap over time`() {
        val clock = FakeClock()
        val limiter = SpeedLimiter(100, clock.now, clock.sleep)
        // محاولات متتالية لسحب 1MB — يجب أن تُجبر على النوم (الساعة الوهمية تسير)
        repeat(10) { limiter.consume(100_000) }
        assertTrue(clock.sleeps.isNotEmpty())
        // إجمالي الوقت الوهمي المنقضي لسحب 1MB عند 100B/s ≈ 10,000 ثانية
        val totalSleepMs = clock.sleeps.sum()
        assertTrue("total sleep too small: $totalSleepMs", totalSleepMs >= 9_000_000)
    }

    @Test
    fun `throttled stream forwards bytes and counts them`() {
        val clock = FakeClock()
        val limiter = SpeedLimiter(0, clock.now, clock.sleep) // بلا حد
        val sink = ByteArrayOutputStream()
        val out = ThrottledOutputStream(sink, limiter)
        out.write(byteArrayOf(1, 2, 3, 4))
        out.write(5)
        out.flush()
        assertEquals(5, sink.size())
        assertTrue(clock.sleeps.isEmpty())
    }
}
