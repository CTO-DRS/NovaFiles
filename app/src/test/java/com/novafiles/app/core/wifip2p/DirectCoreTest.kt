package com.novafiles.app.core.wifip2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات نواة Wi-Fi Direct الخالصة (v0.12): ترميز الحمولة + مخفّض الحالة.
 */
class DirectCoreTest {

    // ----- ترميز الحمولة -----

    @Test
    fun `payload encode decode roundtrip`() {
        val p = DirectPayload(host = "192.168.49.1", port = 2121, deviceName = "Galaxy S24", filesReady = 3)
        val encoded = DirectPayloadCodec.encode(p)
        assertTrue(encoded.startsWith("nova-direct://192.168.49.1:2121?"))
        val decoded = DirectPayloadCodec.decode(encoded)
        assertEquals(p, decoded)
    }

    @Test
    fun `payload supports arabic device names`() {
        val p = DirectPayload(host = "192.168.49.1", port = 2121, deviceName = "هاتف أحمد", filesReady = 1)
        val decoded = DirectPayloadCodec.decode(DirectPayloadCodec.encode(p))
        assertEquals(p, decoded)
    }

    @Test
    fun `decode rejects foreign schemes`() {
        assertNull(DirectPayloadCodec.decode("https://192.168.49.1:2121?name=x"))
        assertNull(DirectPayloadCodec.decode("ftp://192.168.49.1:2121"))
        assertNull(DirectPayloadCodec.decode("just some text"))
    }

    @Test
    fun `decode rejects malformed host or port`() {
        assertNull(DirectPayloadCodec.decode("nova-direct://:2121?name=x")) // بدون مضيف
        assertNull(DirectPayloadCodec.decode("nova-direct://192.168.49.1?name=x")) // بدون منفذ
        assertNull(DirectPayloadCodec.decode("nova-direct://192.168.49.1:abc?name=x")) // منفذ غير رقمي
        assertNull(DirectPayloadCodec.decode("nova-direct://192.168.49.1:99999?name=x")) // منفذ خارج المدى
    }

    @Test
    fun `decode tolerates missing optional params`() {
        val p = DirectPayloadCodec.decode("nova-direct://192.168.49.1:2121")
        assertEquals("192.168.49.1", p?.host)
        assertEquals(2121, p?.port)
        assertEquals("", p?.deviceName)
        assertEquals(0, p?.filesReady)
    }

    @Test
    fun `encode escapes special characters`() {
        val encoded = DirectPayloadCodec.encode(
            DirectPayload("10.0.0.2", 8080, "a&b=c d", 2),
        )
        assertFalse(encoded.contains("a&b=c d"))
        assertEquals("a&b=c d", DirectPayloadCodec.decode(encoded)?.deviceName)
    }

    // ----- مخفّض الحالة -----

    @Test
    fun `reducer transitions idle to discovering to connected owner`() {
        var s = DirectState()
        s = DirectStateReducer.reduce(s, DirectEvent.PeersChanged(listOf(DirectPeer("ب", "aa:bb"))))
        assertEquals(DirectPhase.DISCOVERING, s.phase)
        assertEquals(1, s.peers.size)

        s = DirectStateReducer.reduce(s, DirectEvent.Connected(isGroupOwner = true, ownerAddress = "192.168.49.1"))
        assertEquals(DirectPhase.CONNECTED, s.phase)
        assertTrue(s.isGroupOwner)
        assertEquals("192.168.49.1", s.ownerAddress)
    }

    @Test
    fun `reducer keeps peers through disconnect and resets phase`() {
        var s = DirectState()
        s = DirectStateReducer.reduce(s, DirectEvent.PeersChanged(listOf(DirectPeer("A", "x"), DirectPeer("B", "y"))))
        s = DirectStateReducer.reduce(s, DirectEvent.Connected(false, "192.168.49.1"))
        s = DirectStateReducer.reduce(s, DirectEvent.Disconnected)

        assertEquals(DirectPhase.IDLE, s.phase)
        assertEquals(2, s.peers.size) // يبقى الجيران ظاهرين لإعادة المحاولة
        assertFalse(s.isGroupOwner)
        assertNull(s.ownerAddress)
    }

    @Test
    fun `reducer failure sets error and failed phase`() {
        var s = DirectState(phase = DirectPhase.DISCOVERING)
        s = DirectStateReducer.reduce(s, DirectEvent.Failed("discover:3"))

        assertEquals(DirectPhase.FAILED, s.phase)
        assertEquals("discover:3", s.error)
    }
}
