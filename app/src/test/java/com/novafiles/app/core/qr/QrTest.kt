package com.novafiles.app.core.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات نقل QR على JVM — بناء الروابط وتوليد المصفوفة وفكها عكسياً.
 */
class QrTest {

    // ═══ الترميز المئوي ═══

    @Test
    fun percentEncodeKeepsUnreservedOnly() {
        assertEquals("nova", QrPayloads.percentEncode("nova"))
        assertEquals("a-b._~1", QrPayloads.percentEncode("a-b._~1"))
        assertEquals("a%20b", QrPayloads.percentEncode("a b"))
        assertEquals("user%2Fname", QrPayloads.percentEncode("user/name"))
        assertEquals("p%40ss%3F%23", QrPayloads.percentEncode("p@ss?#"))
        assertEquals("%D9%85%D8%B1%D9%88%D8%B1", QrPayloads.percentEncode("مرور")) // عربي UTF-8
        assertEquals("100%25", QrPayloads.percentEncode("100%"))
    }

    // ═══ روابط المشاركة ═══

    @Test
    fun ftpShareUrlStructure() {
        val url = QrPayloads.ftpShareUrl("192.168.1.7", 2121, "nova", "nova")
        assertEquals("ftp://nova:nova@192.168.1.7:2121/", url)
    }

    @Test
    fun ftpShareUrlEncodesCredentials() {
        val url = QrPayloads.ftpShareUrl("10.0.0.2", 2024, "u ser", "p@ss:1")
        assertEquals("ftp://u%20ser:p%40ss%3A1@10.0.0.2:2024/", url)
    }

    @Test
    fun ftpTargetUrlEncodesPathSegmentsKeepsSlashes() {
        val url = QrPayloads.ftpTargetUrl(
            ip = "192.168.1.7", port = 2121, user = "nova", pass = "nova",
            rootPath = "/storage/emulated/0",
            targetPath = "/storage/emulated/0/صور العائلة/صورة 1.png",
        )
        assertEquals(
            "ftp://nova:nova@192.168.1.7:2121/" +
                "%D8%B5%D9%88%D8%B1%20%D8%A7%D9%84%D8%B9%D8%A7%D8%A6%D9%84%D8%A9/" +
                "%D8%B5%D9%88%D8%B1%D8%A9%201.png/",
            url,
        )
    }

    @Test
    fun ftpTargetUrlOfRootItselfIsBareUrl() {
        val url = QrPayloads.ftpTargetUrl(
            "192.168.1.7", 2121, "nova", "nova",
            rootPath = "/storage/emulated/0",
            targetPath = "/storage/emulated/0",
        )
        assertEquals("ftp://nova:nova@192.168.1.7:2121/", url)
    }

    @Test
    fun ftpTargetUrlHandlesSpacesAndAscii() {
        val url = QrPayloads.ftpTargetUrl(
            "192.168.1.7", 2121, "nova", "nova",
            rootPath = "/sd",
            targetPath = "/sd/My Docs/report final.pdf",
        )
        assertEquals("ftp://nova:nova@192.168.1.7:2121/My%20Docs/report%20final.pdf/", url)
    }

    // ═══ توليد المصفوفة وفكها عكسياً (رحلة كاملة) ═══

    private fun decode(content: String): String {
        val m = QrCode.matrix(content, 400)
        assertTrue(m.width > 0 && m.height > 0)
        val w = m.width
        val h = m.height
        val pixels = IntArray(w * h) { i ->
            if (m.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val source = com.google.zxing.RGBLuminanceSource(w, h, pixels)
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))
        return result.text
    }

    @Test
    fun matrixRoundTripAscii() {
        val payload = "ftp://nova:nova@192.168.1.7:2121/"
        assertEquals(payload, decode(payload))
    }

    @Test
    fun matrixRoundTripUnicode() {
        val payload = "ftp://nova:nova@192.168.1.7:2121/%D8%B5%D9%88%D8%B1%D8%A9%201.png/"
        assertEquals(payload, decode(payload))
    }
}
