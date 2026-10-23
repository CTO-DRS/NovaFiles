package com.novafiles.core.props

import com.novafiles.app.core.props.ExifEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات ExifEngine — بناء JPEG/EXIF اصطناعي بايت-ببايت والتحقق من الاستخراج.
 * يغطي: الترتيبان II/MM، القيم المضمّنة والمؤشَّرة، IFD0+Exif+GPS، التلف المتسامح.
 */
class ExifEngineTest {

    // ═══════════ مُنشئ TIFF اصطناعي ═══════════

    /** كل القيم > 4 بايتات تذهب لكومة في النهاية بإزاحات محسوبة بدقة */
    private class Tiff(val big: Boolean) {
        val ifd0 = mutableListOf<E>()
        val exif = mutableListOf<E>()
        val gps = mutableListOf<E>()

        class E(val tag: Int, val type: Int, val count: Int, val payload: ByteArray)

        fun ascii(tag: Int, value: String) = E(tag, 2, value.length + 1, (value + "\u0000").toByteArray(Charsets.US_ASCII))
        fun short(tag: Int, value: Int) = E(tag, 3, 1, shortBytes(value) + ByteArray(2))
        fun long(tag: Int, value: Int) = E(tag, 4, 1, intBytes(value))
        fun rational(tag: Int, num: Long, den: Long) = E(tag, 5, 1, intBytes(num.toInt()) + intBytes(den.toInt()))
        fun rationals(tag: Int, values: List<Pair<Long, Long>>) =
            E(tag, 5, values.size, values.flatMap { (n, d) -> intBytes(n.toInt()).toList() + intBytes(d.toInt()).toList() }.toByteArray())

        private fun shortBytes(v: Int): ByteArray {
            val b = ByteArray(2)
            if (big) { b[0] = ((v shr 8) and 0xFF).toByte(); b[1] = (v and 0xFF).toByte() }
            else { b[1] = ((v shr 8) and 0xFF).toByte(); b[0] = (v and 0xFF).toByte() }
            return b
        }

        private fun intBytes(v: Int): ByteArray {
            val b = ByteArray(4)
            if (big) {
                b[0] = ((v shr 24) and 0xFF).toByte(); b[1] = ((v shr 16) and 0xFF).toByte()
                b[2] = ((v shr 8) and 0xFF).toByte(); b[3] = (v and 0xFF).toByte()
            } else {
                b[3] = ((v shr 24) and 0xFF).toByte(); b[2] = ((v shr 16) and 0xFF).toByte()
                b[1] = ((v shr 8) and 0xFF).toByte(); b[0] = (v and 0xFF).toByte()
            }
            return b
        }

        /**
         * يبني TIFF كاملاً: header عند 0، ثم IFD0، ثم Exif IFD، ثم GPS IFD، ثم الكومة.
         * المؤشران 0x8769/0x8825 يُحسبان تلقائياً — لا حاجة لإضافتهما يدوياً.
         */
        fun buildTiff(): ByteArray {
            val headerSize = 8
            val ifd0Size = 2 + (ifd0.size + pointerCount) * 12 + 4
            var cursor = headerSize + ifd0Size
            val exifOffset = if (exif.isEmpty()) 0 else cursor.also { cursor += 2 + exif.size * 12 + 4 }
            val gpsOffset = if (gps.isEmpty()) 0 else cursor.also { cursor += 2 + gps.size * 12 + 4 }
            val heapStart = cursor

            val heap = java.io.ByteArrayOutputStream()
            val positions = mutableMapOf<E, Int>()
            (ifd0 + exif + gps).forEach { e ->
                if (e.payload.size > 4) {
                    positions[e] = heapStart + heap.size()
                    heap.write(e.payload)
                }
            }

            val out = java.io.ByteArrayOutputStream()
            out.write(if (big) byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42)
            else byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0))
            out.write(intBytes(8))

            fun writeIfd(entries: List<E>, withPointers: Boolean, nextIfd: Int) {
                val ptrs = mutableListOf<E>()
                if (withPointers && exif.isNotEmpty()) ptrs.add(long(0x8769, exifOffset))
                if (withPointers && gps.isNotEmpty()) ptrs.add(long(0x8825, gpsOffset))
                out.write(shortBytes(entries.size + ptrs.size))
                (entries + ptrs).forEach { e ->
                    out.write(shortBytes(e.tag))
                    out.write(shortBytes(e.type))
                    out.write(intBytes(e.count))
                    if (e.payload.size <= 4) {
                        out.write(e.payload)
                        repeat(4 - e.payload.size) { out.write(0) }
                    } else {
                        out.write(intBytes(positions[e]!!))
                    }
                }
                out.write(intBytes(nextIfd))
            }

            writeIfd(ifd0, withPointers = true, nextIfd = 0)
            if (exif.isNotEmpty()) writeIfd(exif, withPointers = false, nextIfd = 0)
            if (gps.isNotEmpty()) writeIfd(gps, withPointers = false, nextIfd = 0)
            out.write(heap.toByteArray())
            return out.toByteArray()
        }

        private val pointerCount: Int
            get() = (if (exif.isEmpty()) 0 else 1) + (if (gps.isEmpty()) 0 else 1)

        /** JPEG كامل: SOI + APP1(Exif) + SOS شكلي + EOI */
        fun jpeg(): ByteArray {
            val tiff = buildTiff()
            val payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff
            val segLen = payload.size + 2
            val bos = java.io.ByteArrayOutputStream()
            bos.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
            bos.write(byteArrayOf(((segLen shr 8) and 0xFF).toByte(), (segLen and 0xFF).toByte()))
            bos.write(payload)
            return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + bos.toByteArray() +
                byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 0x11, 0x22) +
                byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        }
    }

    // ═══════════ IFD0 + Exif SubIFD ═══════════

    @Test
    fun `parses little-endian full exif with camera and exposure`() {
        val t = Tiff(big = false)
        t.ifd0.addAll(listOf(
            t.ascii(0x010F, "Sony"),
            t.ascii(0x0110, "ILCE-7M4"),
            t.short(0x0112, 1),
        ))
        t.exif.addAll(listOf(
            t.rational(0x829A, 1, 125),       // ExposureTime 1/125
            t.rational(0x829D, 28, 10),        // FNumber f/2.8
            t.short(0x8827, 400),              // ISO 400
            t.rational(0x920A, 35, 1),         // Focal 35mm
            t.ascii(0x9003, "2026:10:24 14:30:00"),
        ))
        val info = ExifEngine.parse(t.jpeg())!!
        assertEquals("Sony", info.cameraMake)
        assertEquals("ILCE-7M4", info.cameraModel)
        assertEquals(1, info.orientation)
        assertEquals(400, info.iso)
        assertEquals(35.0, info.focalLengthMm!!, 0.001)
        assertEquals("2026:10:24 14:30:00", info.takenAt)
        assertEquals(1.0 / 125.0, info.exposureTimeSec!!, 1e-9)
        assertEquals(2.8, info.fNumber!!, 0.001)
        assertTrue(info.hasAny)
    }

    @Test
    fun `parses big-endian exif`() {
        val t = Tiff(big = true)
        t.ifd0.addAll(listOf(
            t.ascii(0x010F, "Canon"),
            t.ascii(0x0110, "EOS R6"),
        ))
        val info = ExifEngine.parse(t.jpeg())!!
        assertEquals("Canon", info.cameraMake)
        assertEquals("EOS R6", info.cameraModel)
        assertNull(info.iso)
    }

    @Test
    fun `image dimensions from ifd0 and fallback from exif`() {
        val t = Tiff(big = false)
        t.ifd0.addAll(listOf(t.short(0x0100, 4000), t.short(0x0101, 3000)))
        t.exif.addAll(listOf(t.short(0xA002, 8000), t.short(0xA003, 6000)))
        val info = ExifEngine.parse(t.jpeg())!!
        assertEquals(4000, info.width)  // IFD0 أولاً
        assertEquals(3000, info.height)

        val t2 = Tiff(big = false)
        t2.exif.addAll(listOf(t2.short(0xA002, 8000), t2.short(0xA003, 6000)))
        val info2 = ExifEngine.parse(t2.jpeg())!!
        assertEquals(8000, info2.width) // بلا IFD0 → من Exif
        assertEquals(6000, info2.height)
    }

    @Test
    fun `white balance is read`() {
        val t = Tiff(big = false)
        t.exif.add(t.short(0xA403, 1))
        val info = ExifEngine.parse(t.jpeg())!!
        assertEquals(1, info.whiteBalance)
    }

    // ═══════════ GPS ═══════════

    @Test
    fun `gps dms coordinates with refs become signed decimal`() {
        val t = Tiff(big = false)
        t.gps.addAll(listOf(
            t.ascii(0x0001, "N"),
            t.rationals(0x0002, listOf(24L to 1L, 42L to 1L, 4896L to 100L)),
            t.ascii(0x0003, "E"),
            t.rationals(0x0004, listOf(46L to 1L, 40L to 1L, 3096L to 100L)),
            t.rational(0x0006, 612, 1),
        ))
        val info = ExifEngine.parse(t.jpeg())!!
        assertTrue(info.hasGps)
        assertEquals(24.0 + 42.0 / 60 + 48.96 / 3600, info.latitude!!, 1e-9)
        assertEquals(46.0 + 40.0 / 60 + 30.96 / 3600, info.longitude!!, 1e-9)
        assertEquals(612.0, info.altitudeM!!, 0.001)
    }

    @Test
    fun `south and west refs negate coordinates`() {
        val t = Tiff(big = false)
        t.gps.addAll(listOf(
            t.ascii(0x0001, "S"),
            t.rationals(0x0002, listOf(10L to 1L, 0L to 1L, 0L to 1L)),
            t.ascii(0x0003, "W"),
            t.rationals(0x0004, listOf(70L to 1L, 0L to 1L, 0L to 1L)),
        ))
        val info = ExifEngine.parse(t.jpeg())!!
        assertEquals(-10.0, info.latitude!!, 1e-9)
        assertEquals(-70.0, info.longitude!!, 1e-9)
    }

    @Test
    fun `altitude below sea level is negated`() {
        val t = Tiff(big = false)
        t.gps.addAll(listOf(
            t.short(0x0005, 1),           // altitudeRef = 1 (تحت البحر)
            t.rational(0x0006, 50, 1),
        ))
        val info = ExifEngine.parse(t.jpeg())!!
        assertEquals(-50.0, info.altitudeM!!, 0.001)
    }

    // ═══════════ القيم الكبيرة والتسامح ═══════════

    @Test
    fun `large ascii values go to heap via offset`() {
        val t = Tiff(big = false)
        t.ifd0.add(t.ascii(0x0110, "Canon EOS 5D Mark IV with battery grip"))
        val info = ExifEngine.parse(t.jpeg())!!
        assertEquals("Canon EOS 5D Mark IV with battery grip", info.cameraModel)
    }

    @Test
    fun `no exif app1 returns null`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 2) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertNull(ExifEngine.parse(jpeg))
    }

    @Test
    fun `non jpeg bytes return null`() {
        assertNull(ExifEngine.parse(ByteArray(20)))
        assertNull(ExifEngine.parse("PNG!!!".toByteArray() + ByteArray(20)))
        assertNull(ExifEngine.parse(ByteArray(0)))
    }

    @Test
    fun `corrupted tiff magic returns null safely`() {
        val t = Tiff(big = false)
        t.ifd0.add(t.ascii(0x010F, "Sony"))
        val app1Tiff = t.buildTiff()
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + app1Tiff
        val segLen = payload.size + 2
        var app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte()) +
            byteArrayOf(((segLen shr 8) and 0xFF).toByte(), (segLen and 0xFF).toByte()) + payload
        // كسر بايت endian (أول بايت في TIFF داخل payload عند الإزاحة 6)
        app1 = app1.copyOf().also { it[10] = 'X'.code.toByte() }
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertNull(ExifEngine.parse(jpeg))
    }

    @Test
    fun `truncated exif never throws`() {
        val t = Tiff(big = false)
        t.ifd0.add(t.ascii(0x010F, "Sony"))
        t.exif.add(t.rational(0x829A, 1, 125))
        val full = t.jpeg()
        runCatching { ExifEngine.parse(full.copyOf(full.size / 2)) }
        runCatching { ExifEngine.parse(full.copyOf(12)) }
        runCatching { ExifEngine.parse(full.copyOf(4)) }
    }

    @Test
    fun `empty ifd structure yields null`() {
        val t = Tiff(big = false)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + t.jpeg().copyOfRange(2, t.jpeg().size) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        // IFD0 بلا مدخلات وبلا مؤشرات → لا شيء يُقرأ → null
        assertNull(ExifEngine.parse(jpeg))
    }

    // ═══════════ النصوص الودّية ═══════════

    @Test
    fun `friendly exposure snaps to standard fractions`() {
        assertEquals("1/125 s", ExifEngine.friendlyExposure(1.0 / 125))
        assertEquals("1/8 s", ExifEngine.friendlyExposure(0.125))
        assertEquals("2 s", ExifEngine.friendlyExposure(2.0))
        assertEquals("2.5 s", ExifEngine.friendlyExposure(2.5))
        assertEquals("1/7 s", ExifEngine.friendlyExposure(1.0 / 7))
        assertNull(ExifEngine.friendlyExposure(0.0))
        assertNull(ExifEngine.friendlyExposure(-1.0))
    }

    @Test
    fun `friendly gps formats with direction`() {
        assertEquals("24.7136° N, 46.6753° E", ExifEngine.friendlyGps(24.7136, 46.6753))
        assertEquals("10.0000° S, 70.0000° W", ExifEngine.friendlyGps(-10.0, -70.0))
        assertNull(ExifEngine.friendlyGps(24.0, null))
        assertNull(ExifEngine.friendlyGps(null, null))
    }

    @Test
    fun `exif info hasAny flags`() {
        assertFalse(ExifEngine.ExifInfo().hasAny)
        assertTrue(ExifEngine.ExifInfo(iso = 100).hasAny)
        assertFalse(ExifEngine.ExifInfo().hasGps)
        assertTrue(ExifEngine.ExifInfo(latitude = 1.0, longitude = 2.0).hasGps)
    }
}
