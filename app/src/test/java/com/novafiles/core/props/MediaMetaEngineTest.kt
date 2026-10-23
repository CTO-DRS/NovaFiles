package com.novafiles.core.props

import com.novafiles.app.core.props.MediaMetaEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * اختبارات MediaMetaEngine — صناديق MP4 وID3v2 اصطناعية بايت-ببايت.
 * يغطي: mvhd v0/v1، الأبعاد 16.16، stsd للترميز، mp4a sample rate،
 * ID3v2.3/2.4 بترميزات لاتيني/UTF-16/UTF-8 مع نصوص عربية، وتقدير مدة MP3.
 */
class MediaMetaEngineTest {

    // ═══════════ أدوات بناء MP4 ═══════════

    private fun box(type: String, body: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        val size = 8 + body.size
        bos.write(byteArrayOf(
            ((size shr 24) and 0xFF).toByte(), ((size shr 16) and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(), (size and 0xFF).toByte(),
        ))
        bos.write(type.toByteArray(Charsets.US_ASCII))
        bos.write(body)
        return bos.toByteArray()
    }

    private fun ftyp(brand: String = "isom"): ByteArray =
        box("ftyp", brand.toByteArray(Charsets.US_ASCII) + int32(0x200) + brand.toByteArray(Charsets.US_ASCII))

    private fun int32(v: Int): ByteArray = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
    )

    private fun int64(v: Long): ByteArray {
        val bos = ByteArrayOutputStream()
        bos.write(int32((v shr 32).toInt()))
        bos.write(int32(v.toInt()))
        return bos.toByteArray()
    }

    /** mvhd v0: version/flags(4) + created/modified(8) + timescale(4) + duration(4) */
    private fun mvhdV0(timescale: Int, duration: Int): ByteArray =
        box("mvhd", byteArrayOf(0, 0, 0, 0) + int32(0) + int32(0) + int32(timescale) + int32(duration) + ByteArray(80))

    /** mvhd v1: version/flags(4) + created/modified(16) + timescale(4) + duration(8) */
    private fun mvhdV1(timescale: Int, duration: Long): ByteArray =
        box("mvhd", byteArrayOf(1, 0, 0, 0) + int64(0) + int64(0) + int32(timescale) + int64(duration) + ByteArray(80))

    /** hdlr: version/flags(4) + pre_defined(4) + handler(4) */
    private fun hdlr(handler: String): ByteArray =
        box("hdlr", byteArrayOf(0, 0, 0, 0) + int32(0) + handler.toByteArray(Charsets.US_ASCII))

    /** tkhd بأبعاد 16.16 في آخر 8 بايتات (v0 كامل الأساس) */
    private fun tkhd(width: Int, height: Int): ByteArray {
        val body = byteArrayOf(0, 0, 0, 0) + int32(0) + int32(0) + int32(1) + int32(0) +
            int32(0) + int32(0) + short16(0) + short16(0) + int32(0) + int32(0) + int32(0) +
            int32(0x10000) + int32(0) + int32(0) + int32(0) + int32(0x10000) + int32(0) + int32(0) +
            int32(0) + int32(0) + int32(0x10000) +
            // width/height كنقطة ثابتة 16.16
            int32(width shl 16) + int32(height shl 16)
        return box("tkhd", body)
    }

    private fun short16(v: Int): ByteArray = byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

    /** stsd بمدخل واحد: fourcc الترميز + body اختياري (مثلاً mp4a) */
    private fun stsd(codec: String, entryBody: ByteArray = ByteArray(20)): ByteArray {
        val entry = box(codec, entryBody)
        val body = byteArrayOf(0, 0, 0, 0) + int32(1) + entry
        return box("stsd", body)
    }

    /** mp4a: SampleEntry(reserved6+dataref2=8) + AudioSampleEntry reserved(8) + قنوات/حجم/معدل */
    private fun mp4aEntry(sampleRate: Int): ByteArray {
        val audioEntry = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1) + // reserved(6) + data_reference_index=1
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0) +              // reserved(8) حسب المواصفة
            short16(2) + short16(16) + short16(0) + short16(0) +
            int32(sampleRate shl 16) +
            ByteArray(20) // esds شكلي
        return stsd("mp4a", audioEntry)
    }

    /** مسار كامل: trak(tkhd + mdia(hdlr + minf(stbl(stsd)))) ) */
    private fun trak(handler: String, width: Int, height: Int, codec: String, entry: ByteArray? = null): ByteArray {
        val stbl = box("stbl", entry ?: stsd(codec))
        val minf = box("minf", stbl + box("smhd", ByteArray(8)))
        val mdia = box("mdia", hdlr(handler) + minf)
        val head = if (handler == "vide") tkhd(width, height) else tkhd(0, 0)
        return box("trak", head + mdia)
    }

    private fun mp4(vararg topBoxes: ByteArray): ByteArray = topBoxes.reduce { a, b -> a + b }

    // ═══════════ أدوات بناء MP3/ID3 ═══════════

    private fun syncsafe(v: Int): ByteArray = byteArrayOf(
        ((v shr 21) and 0x7F).toByte(), ((v shr 14) and 0x7F).toByte(),
        ((v shr 7) and 0x7F).toByte(), (v and 0x7F).toByte(),
    )

    private fun id3FrameV24(id: String, text: String, encoding: Int = 3): ByteArray {
        val body = byteArrayOf(encoding.toByte()) + text.toByteArray(Charsets.UTF_8)
        return id.toByteArray(Charsets.US_ASCII) + syncsafe(body.size) + byteArrayOf(0, 0) + body
    }

    private fun id3FrameV23(id: String, text: String, encoding: Int = 3): ByteArray {
        val body = byteArrayOf(encoding.toByte()) + text.toByteArray(Charsets.UTF_8)
        return id.toByteArray(Charsets.US_ASCII) + int32(body.size) + byteArrayOf(0, 0) + body
    }

    private fun id3Header(size: Int, major: Int): ByteArray =
        "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(major.toByte(), 0, 0) + syncsafe(size)

    /** إطار MPEG-1 Layer III صالح: 0xFF ثم 111(sync)+11(MPEG1)+01(Layer3)+1 */
    private fun mpeg1Frame(bitrateIdx: Int = 9, srIdx: Int = 0): ByteArray = byteArrayOf(
        0xFF.toByte(), ((7 shl 5) or (3 shl 3) or (1 shl 1) or 1).toByte(), // 0xFB
        ((bitrateIdx shl 4) or (srIdx shl 2) or 1).toByte(), 0x00.toByte(),
    )

    // ═══════════ MP4 ═══════════

    @Test
    fun `mp4 duration dims codec from v0 mvhd`() {
        val videoTrak = trak("vide", 1920, 1080, "avc1")
        val audioTrak = trak("soun", 0, 0, "mp4a", mp4aEntry(48000))
        val moov = box("moov", mvhdV0(timescale = 1000, duration = 95_500) + videoTrak + audioTrak)
        val meta = MediaMetaEngine.parse(mp4(ftyp(), moov))
        assertEquals("mp4", meta.container)
        assertEquals("isom", meta.majorBrand)
        assertEquals(95.5, meta.durationSec!!, 0.001)
        assertEquals(1920, meta.width)
        assertEquals(1080, meta.height)
        assertEquals("avc1", meta.codec)
        assertFalse(meta.durationEstimated)
    }

    @Test
    fun `mp4 v1 mvhd with 64-bit duration`() {
        val videoTrak = trak("vide", 3840, 2160, "hvc1")
        val moov = box("moov", mvhdV1(timescale = 24, duration = 240_000L) + videoTrak)
        val meta = MediaMetaEngine.parse(mp4(ftyp("mp42"), moov))
        assertEquals("mp42", meta.majorBrand)
        assertEquals(10_000.0, meta.durationSec!!, 0.5)
        assertEquals(3840, meta.width)
        assertEquals(2160, meta.height)
        assertEquals("hvc1", meta.codec)
    }

    @Test
    fun `audio-only mp4 takes mp4a sample rate and skips video dims`() {
        val audioTrak = trak("soun", 0, 0, "mp4a", mp4aEntry(44100))
        val moov = box("moov", mvhdV0(44100, 44100 * 30) + audioTrak)
        val meta = MediaMetaEngine.parse(mp4(ftyp("M4A "), moov))
        assertNull(meta.width)
        assertNull(meta.height)
        assertEquals("mp4a", meta.codec)
        assertEquals(44100, meta.sampleRateHz)
        assertEquals(30.0, meta.durationSec!!, 0.001)
    }

    @Test
    fun `mp4 with two tracks prefers video for dims and audio for rate`() {
        val v = trak("vide", 1280, 720, "avc1")
        val a = trak("soun", 0, 0, "mp4a", mp4aEntry(48000))
        val moov = box("moov", mvhdV0(1000, 60_000) + v + a)
        val meta = MediaMetaEngine.parse(mp4(ftyp(), moov))
        assertEquals(1280, meta.width)
        assertEquals(720, meta.height)
        assertEquals(48000, meta.sampleRateHz)
    }

    @Test
    fun `video trak alone reports video codec not audio`() {
        val v = trak("vide", 640, 480, "avc1")
        val moov = box("moov", mvhdV0(1000, 5_000) + v)
        val meta = MediaMetaEngine.parse(mp4(ftyp(), moov))
        assertEquals("avc1", meta.codec)
        assertNull(meta.sampleRateHz)
    }

    @Test
    fun `corrupted box size inside moov stops safely`() {
        val moov = box("moov", mvhdV0(1000, 4_000) + trak("vide", 640, 480, "avc1") +
            int32(0) + "junk".toByteArray(Charsets.US_ASCII))
        val meta = MediaMetaEngine.parse(mp4(ftyp(), moov))
        assertEquals(4.0, meta.durationSec!!, 0.001)
        assertEquals(640, meta.width)
    }

    @Test
    fun `non mp4 bytes give empty meta`() {
        val meta = MediaMetaEngine.parse("not a media file at all...".toByteArray())
        assertNull(meta.container)
        assertFalse(meta.hasAny)
    }

    @Test
    fun `tiny buffer is safe`() {
        assertFalse(MediaMetaEngine.parse(ByteArray(4)).hasAny)
        assertFalse(MediaMetaEngine.parse(ByteArray(0)).hasAny)
    }

    // ═══════════ MP3 / ID3v2 ═══════════

    @Test
    fun `mp3 id3v24 utf8 arabic tags read correctly`() {
        val title = "تقرير الصباح"
        val artist = "فنان عربي"
        val frames = id3FrameV24("TIT2", title) + id3FrameV24("TPE1", artist)
        val tag = id3Header(frames.size, major = 4) + frames
        // ~320kbps → تقدير مدة من حجم الملف
        val audio = mpeg1Frame(bitrateIdx = 14) + ByteArray(4000)
        val meta = MediaMetaEngine.parse(tag + audio)
        assertEquals("mp3", meta.container)
        assertEquals(title, meta.title)
        assertEquals(artist, meta.artist)
        assertTrue(meta.durationEstimated)
        assertTrue(meta.durationSec!! > 0)
        assertEquals(320, meta.bitrateKbps)
        assertEquals(44100, meta.sampleRateHz)
    }

    @Test
    fun `mp3 id3v23 plain size and album year`() {
        val frames = id3FrameV23("TALB", "ألبوم 2026") + id3FrameV23("TYER", "2026") +
            id3FrameV23("TIT2", "Song")
        val tag = id3Header(frames.size, major = 3) + frames
        val meta = MediaMetaEngine.parse(tag + mpeg1Frame(bitrateIdx = 6) + ByteArray(100))
        assertEquals("ألبوم 2026", meta.album)
        assertEquals("2026", meta.year)
        assertEquals("Song", meta.title)
        assertEquals(80, meta.bitrateKbps) // MPEG-1 idx6 = 80kbps
    }

    @Test
    fun `utf16 with bom encoding works`() {
        // TIT2 بترميز UTF-16 مع BOM: نص "Hello"
        val text = "Hello"
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val frame = "TIT2".toByteArray(Charsets.US_ASCII) + syncsafe(utf16.size + 1) +
            byteArrayOf(0, 0) + byteArrayOf(1) + utf16
        val tag = id3Header(frame.size, major = 4) + frame
        val meta = MediaMetaEngine.parse(tag + mpeg1Frame())
        assertEquals("Hello", meta.title)
    }

    @Test
    fun `mp3 without id3 still estimates duration`() {
        val audio = mpeg1Frame() + ByteArray(32_000)
        val meta = MediaMetaEngine.parse(audio)
        assertEquals("mp3", meta.container)
        assertNull(meta.title)
        assertEquals(128, meta.bitrateKbps) // idx9 = 128
        assertTrue(meta.durationSec!! > 0.5)
    }

    @Test
    fun `corrupted id3 size does not crash and falls back`() {
        // رأس ID3 يدّعي حجماً أكبر من الملف — يجب ألا يرمي
        val tag = "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(4, 0, 0) + syncsafe(1_000_000)
        val meta = MediaMetaEngine.parse(tag + mpeg1Frame())
        assertEquals("mp3", meta.container)
    }

    // ═══════════ النصوص الودّية ═══════════

    @Test
    fun `friendly codec mapping`() {
        assertEquals("H.264", MediaMetaEngine.friendlyCodec("avc1"))
        assertEquals("H.265", MediaMetaEngine.friendlyCodec("hvc1"))
        assertEquals("H.265", MediaMetaEngine.friendlyCodec("hev1"))
        assertEquals("AAC", MediaMetaEngine.friendlyCodec("mp4a"))
        assertEquals("AV1", MediaMetaEngine.friendlyCodec("av01"))
        assertEquals("VP9", MediaMetaEngine.friendlyCodec("vp09"))
        assertEquals("mp3s", MediaMetaEngine.friendlyCodec("mp3s"))
        assertNull(MediaMetaEngine.friendlyCodec(null))
    }

    @Test
    fun `friendly duration formats`() {
        assertEquals("0:59", MediaMetaEngine.friendlyDuration(59.4))
        assertEquals("1:35", MediaMetaEngine.friendlyDuration(95.5))
        assertEquals("1:00:00", MediaMetaEngine.friendlyDuration(3600.0))
        assertEquals("1:02:03", MediaMetaEngine.friendlyDuration(3723.0))
        assertNull(MediaMetaEngine.friendlyDuration(null))
        assertNull(MediaMetaEngine.friendlyDuration(-1.0))
        assertNull(MediaMetaEngine.friendlyDuration(Double.NaN))
    }

    @Test
    fun `media meta hasAny flags`() {
        assertFalse(MediaMetaEngine.MediaMeta().hasAny)
        assertTrue(MediaMetaEngine.MediaMeta(container = "mp4").hasAny)
        assertTrue(MediaMetaEngine.MediaMeta(title = "x").hasAny)
    }
}
