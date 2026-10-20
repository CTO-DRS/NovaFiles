package com.novafiles.app.core.ftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات سجل جلسات FTP — تسجيل/لمس/مصادقة/قطع — دون أي مقابس */
class FtpSessionsTest {

    @Test
    fun `register assigns sequential ids and counts active`() {
        val reg = FtpSessionRegistry()
        assertEquals(0, reg.activeCount)
        val a = reg.register("192.168.1.5", 1000) {}
        val b = reg.register("192.168.1.6", 1001) {}
        assertEquals(2, reg.activeCount)
        assertTrue(a != b)
        assertEquals(2, b)
    }

    @Test
    fun `snapshot shows host times and auth state`() {
        val reg = FtpSessionRegistry()
        val id = reg.register("10.0.0.2", 5000) {}
        reg.markAuthenticated(id)
        reg.touch(id, 7000)
        val snap = reg.snapshot()
        assertEquals(1, snap.size)
        val s = snap[0]
        assertEquals("10.0.0.2", s.host)
        assertEquals(5000L, s.connectedAt)
        assertEquals(7000L, s.lastActiveAt)
        assertTrue(s.authenticated)
    }

    @Test
    fun `unregister removes the session`() {
        val reg = FtpSessionRegistry()
        val id = reg.register("h1", 1) {}
        reg.unregister(id)
        assertEquals(0, reg.activeCount)
        assertTrue(reg.snapshot().isEmpty())
    }

    @Test
    fun `kick closes socket via callback and removes`() {
        val reg = FtpSessionRegistry()
        var closed = 0
        val id = reg.register("h2", 1) { closed++ }
        assertTrue(reg.kick(id))
        assertEquals(1, closed)
        assertEquals(0, reg.activeCount)
        assertFalse(reg.kick(id)) // غير موجود الآن
    }

    @Test
    fun `kickAll closes everyone once`() {
        val reg = FtpSessionRegistry()
        var closedA = 0
        var closedB = 0
        reg.register("a", 1) { closedA++ }
        reg.register("b", 2) { closedB++ }
        reg.kickAll()
        assertEquals(1, closedA)
        assertEquals(1, closedB)
        assertEquals(0, reg.activeCount)
    }

    @Test
    fun `snapshot is oldest first`() {
        val reg = FtpSessionRegistry()
        reg.register("first", 1) {}
        reg.register("second", 2) {}
        val snap = reg.snapshot()
        assertEquals("first", snap[0].host)
        assertEquals("second", snap[1].host)
    }
}
