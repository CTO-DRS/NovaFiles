package com.novafiles.app.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مصادر مزامنة الكاميرا المتعددة (v0.32): التصنيف من RELATIVE_PATH،
 * تحويل CSV، والمجلد الهدف بمجلد فرعي لكل مصدر.
 */
class CameraSourcesTest {

    // ═══════════════ التصنيف ═══════════════

    @Test
    fun `camera bucket classifies as camera`() {
        assertEquals(
            CameraSync.CameraSource.CAMERA,
            CameraSync.CameraSource.classify("DCIM/Camera/"),
        )
    }

    @Test
    fun `unknown paths default to camera`() {
        assertEquals(
            CameraSync.CameraSource.CAMERA,
            CameraSync.CameraSource.classify("Pictures/misc/"),
        )
        assertEquals(
            CameraSync.CameraSource.CAMERA,
            CameraSync.CameraSource.classify(""),
        )
    }

    @Test
    fun `whatsapp media classified case-insensitively`() {
        assertEquals(
            CameraSync.CameraSource.WHATSAPP,
            CameraSync.CameraSource.classify("WhatsApp/Media/WhatsApp Images/"),
        )
        assertEquals(
            CameraSync.CameraSource.WHATSAPP,
            CameraSync.CameraSource.classify("Android/media/com.whatsapp/Media/"),
        )
    }

    @Test
    fun `screenshots classified including arabic-less variants`() {
        assertEquals(
            CameraSync.CameraSource.SCREENSHOTS,
            CameraSync.CameraSource.classify("Pictures/Screenshots/"),
        )
        assertEquals(
            CameraSync.CameraSource.SCREENSHOTS,
            CameraSync.CameraSource.classify("DCIM/Screencasts/"),
        )
    }

    @Test
    fun `windows-style separators normalized before classify`() {
        assertEquals(
            CameraSync.CameraSource.SCREENSHOTS,
            CameraSync.CameraSource.classify("Pictures\\Screenshots\\"),
        )
    }

    // ═══════════════ CSV ═══════════════

    @Test
    fun `fromCsv parses known ids and ignores junk`() {
        val sources = CameraSync.CameraSource.fromCsv("CAMERA,WHATSAPP,junk, SCREENSHOTS ")
        assertEquals(
            setOf(CameraSync.CameraSource.CAMERA, CameraSync.CameraSource.WHATSAPP, CameraSync.CameraSource.SCREENSHOTS),
            sources,
        )
    }

    @Test
    fun `fromCsv empty or null falls back to camera only`() {
        assertEquals(setOf(CameraSync.CameraSource.CAMERA), CameraSync.CameraSource.fromCsv(null))
        assertEquals(setOf(CameraSync.CameraSource.CAMERA), CameraSync.CameraSource.fromCsv(""))
        assertEquals(setOf(CameraSync.CameraSource.CAMERA), CameraSync.CameraSource.fromCsv("bogus"))
    }

    @Test
    fun `csv round trip preserves set`() {
        val sources = setOf(CameraSync.CameraSource.WHATSAPP, CameraSync.CameraSource.SCREENSHOTS)
        assertEquals(sources, CameraSync.CameraSource.fromCsv(CameraSync.CameraSource.toCsv(sources)))
    }

    // ═══════════════ المجلد الهدف متعدد المصادر ═══════════════

    private fun item(source: CameraSync.CameraSource, mtime: Long = 0L): CameraSync.MediaItem =
        CameraSync.MediaItem(
            key = "k", name = "n.jpg", size = 1L, lastModified = mtime,
            isVideo = false, open = { throw UnsupportedOperationException() }, source = source,
        )

    @Test
    fun `multi-source target gets source subfolder with year-month`() {
        val policy = CameraSync.SyncPolicy(
            targetFolder = "/NovaFiles-Camera",
            multiSource = true,
        )
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(2026, java.util.Calendar.MARCH, 15, 10, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val item = item(CameraSync.CameraSource.WHATSAPP, cal.timeInMillis)
        assertEquals("/NovaFiles-Camera/WhatsApp/2026/03", CameraSync.targetFolderFor(policy, item))
    }

    @Test
    fun `single-source target keeps legacy flat behavior`() {
        val policy = CameraSync.SyncPolicy(
            targetFolder = "/NovaFiles-Camera",
            multiSource = false,
            naming = CameraSync.Naming.FLAT,
        )
        assertEquals("/NovaFiles-Camera", CameraSync.targetFolderFor(policy, item(CameraSync.CameraSource.WHATSAPP)))
    }

    @Test
    fun `multi-source flat naming still nests by source only`() {
        val policy = CameraSync.SyncPolicy(
            targetFolder = "/Target",
            naming = CameraSync.Naming.FLAT,
            multiSource = true,
        )
        assertEquals("/Target/Screenshots", CameraSync.targetFolderFor(policy, item(CameraSync.CameraSource.SCREENSHOTS)))
    }

    @Test
    fun `folder labels are ascii stable across providers`() {
        assertEquals("Camera", CameraSync.CameraSource.CAMERA.folderLabel)
        assertEquals("WhatsApp", CameraSync.CameraSource.WHATSAPP.folderLabel)
        assertEquals("Screenshots", CameraSync.CameraSource.SCREENSHOTS.folderLabel)
        CameraSync.CameraSource.entries.forEach {
            assertTrue(it.folderLabel.all { c -> c.code < 128 })
        }
    }
}
