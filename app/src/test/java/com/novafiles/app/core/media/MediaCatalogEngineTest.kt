package com.novafiles.app.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات محرك فهرسة الوسائط — التصنيف، أهلية التصغير، ومصفوفة الدعم */
class MediaCatalogEngineTest {

    // عتبات صغيرة للاختبار الحتمي
    private val engine = MediaCatalogEngine(
        sizeThresholdBytes = 1000,
        pixelThreshold = 1000,
    )

    // ═══════════════ التصنيف ═══════════════

    @Test
    fun `convertible images by extension`() {
        assertTrue(engine.isConvertibleImage("a.jpg"))
        assertTrue(engine.isConvertibleImage("a.jpeg"))
        assertTrue(engine.isConvertibleImage("a.PNG"))
        assertTrue(engine.isConvertibleImage("a.webp"))
        assertTrue(engine.isConvertibleImage("a.bmp"))
    }

    @Test
    fun `non convertible images excluded`() {
        assertFalse(engine.isConvertibleImage("a.gif"))
        assertFalse(engine.isConvertibleImage("a.heic"))
        assertFalse(engine.isConvertibleImage("a.svg"))
        assertFalse(engine.isConvertibleImage("a.txt"))
        assertFalse(engine.isConvertibleImage("noext"))
    }

    @Test
    fun `playable audio and video`() {
        assertTrue(engine.isPlayable("song.mp3"))
        assertTrue(engine.isPlayable("clip.mkv"))
        assertTrue(engine.isPlayable("voice.m4a"))
        assertTrue(engine.isPlayable("movie.MP4"))
        assertFalse(engine.isPlayable("song.lrc"))
        assertFalse(engine.isPlayable("a.jpg"))
    }

    @Test
    fun `pdf detection`() {
        assertTrue(engine.isPdf("doc.pdf"))
        assertTrue(engine.isPdf("DOC.PDF"))
        assertFalse(engine.isPdf("doc.pdfx"))
        assertFalse(engine.isPdf("doc.txt"))
    }

    // ═══════════════ الإجراءات المتاحة ═══════════════

    @Test
    fun `actions for image are the three tools`() {
        val actions = engine.availableActions("photo.png")
        assertEquals(
            listOf(MediaAction.CONVERT, MediaAction.COMPRESS, MediaAction.DOWNSCALE),
            actions,
        )
    }

    @Test
    fun `actions for audio is play only`() {
        assertEquals(listOf(MediaAction.PLAY), engine.availableActions("song.mp3"))
    }

    @Test
    fun `actions for pdf is view only`() {
        assertEquals(listOf(MediaAction.VIEW_PDF), engine.availableActions("doc.pdf"))
    }

    @Test
    fun `actions for plain file are empty`() {
        assertTrue(engine.availableActions("notes.txt").isEmpty())
    }

    // ═══════════════ أهلية التصغير ═══════════════

    @Test
    fun `candidate by size over threshold`() {
        val f = MediaFile("big.jpg", sizeBytes = 2000)
        assertTrue(engine.isDownscaleCandidate(f))
    }

    @Test
    fun `candidate by pixels over threshold`() {
        val f = MediaFile("small.jpg", sizeBytes = 100, width = 3000, height = 2000)
        assertTrue(engine.isDownscaleCandidate(f))
    }

    @Test
    fun `not candidate below both thresholds`() {
        val f = MediaFile("tiny.jpg", sizeBytes = 100, width = 500, height = 500)
        assertFalse(engine.isDownscaleCandidate(f))
    }

    @Test
    fun `not candidate when unsupported format regardless of size`() {
        val f = MediaFile("huge.png.txt", sizeBytes = 999_999)
        assertFalse(engine.isDownscaleCandidate(f))
    }

    @Test
    fun `not candidate without dimensions and small size`() {
        val f = MediaFile("mid.jpg", sizeBytes = 100)
        assertFalse(engine.isDownscaleCandidate(f))
    }

    @Test
    fun `count and sorted candidates`() {
        val files = listOf(
            MediaFile("a.jpg", 5000),
            MediaFile("b.jpg", 10),
            MediaFile("c.png", 2000),
            MediaFile("d.txt", 9000),
        )
        val candidates = engine.downscaleCandidates(files)
        assertEquals(2, engine.countDownscaleCandidates(files))
        // الأكبر حجماً أولاً
        assertEquals("a.jpg", candidates[0].name)
        assertEquals("c.png", candidates[1].name)
    }

    // ═══════════════ مصفوفة الدعم ═══════════════

    @Test
    fun `target formats for convertible image are all three`() {
        assertEquals(ImageFormat.entries.toList(), engine.targetFormats("a.jpg"))
    }

    @Test
    fun `target formats empty for unsupported`() {
        assertTrue(engine.targetFormats("a.gif").isEmpty())
    }

    @Test
    fun `alpha loss matrix`() {
        assertTrue(engine.alphaLossIfConverted(ImageFormat.PNG, ImageFormat.JPEG))
        assertTrue(engine.alphaLossIfConverted(ImageFormat.WEBP, ImageFormat.JPEG))
        assertFalse(engine.alphaLossIfConverted(ImageFormat.PNG, ImageFormat.WEBP))
        assertFalse(engine.alphaLossIfConverted(ImageFormat.JPEG, ImageFormat.PNG))
        assertFalse(engine.alphaLossIfConverted(ImageFormat.JPEG, ImageFormat.JPEG))
    }

    // ═══════════════ العرض ═══════════════

    @Test
    fun `human bytes units`() {
        assertEquals("512 B", engine.humanBytes(512))
        assertEquals("1.0 KB", engine.humanBytes(1024))
        assertEquals("1.5 KB", engine.humanBytes(1536))
        assertEquals("1.0 MB", engine.humanBytes(1024 * 1024))
        assertEquals("1.0 GB", engine.humanBytes(1024L * 1024 * 1024))
    }
}
