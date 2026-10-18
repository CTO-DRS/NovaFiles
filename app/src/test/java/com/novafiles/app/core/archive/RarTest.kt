package com.novafiles.app.core.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * اختبارات RAR4 + RAR5 عبر junrar 8 — عيّنات حقيقية من موارد الاختبار.
 * المحتوى في العيّنتين: FILE1.TXT = "file1\n" و FILE2.TXT = "file2\n".
 */
class RarTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val content1 = "file1\r\n".toByteArray(Charsets.UTF_8) // العيّنات بأسطر CRLF — 7 بايتات
    private val content2 = "file2\r\n".toByteArray(Charsets.UTF_8)

    /** نسخ مورد الاختبار إلى ملف مؤقت بامتداد .rar (junrar يفتح الملفات بالمسار) */
    private fun fixture(name: String): File {
        val stream = javaClass.getResourceAsStream("/com/novafiles/app/core/archive/$name")
            ?: throw IllegalStateException("fixture missing: $name")
        val out = File(tmp.root, name)
        stream.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    // ═══ كشف الإصدار بالبصمة ═══

    @Test
    fun rarVersionSignatureDetection() {
        val rar4 = fixture("rar4.rar")
        val rar5 = fixture("rar5.rar")
        assertFalse("rar4.rar should be RAR4", CommonArchive.isRar5(rar4))
        assertTrue("rar5.rar should be RAR5", CommonArchive.isRar5(rar5))
        assertEquals(Family.RAR, familyOf(rar4.name))
        assertEquals(Family.RAR, familyOf(rar5.name))
    }

    @Test
    fun nonRarFileIsNeverDetectedAsRar5() {
        val zip = File(tmp.root, "plain.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { }
        assertFalse(CommonArchive.isRar5(zip))
        val empty = File(tmp.root, "empty.rar")
        empty.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07))
        assertFalse(CommonArchive.isRar5(empty)) // RAR4 قصيرة دون بصمة RAR5
    }

    // ═══ RAR4 — رحلة كاملة عبر المحرك الموحد ═══

    @Test
    fun rar4ListAndExtractViaEngine() {
        val rar4 = fixture("rar4.rar")
        assertEquals(Family.RAR, familyOf(rar4.name))
        assertFalse(CommonArchive.isRar5(rar4))

        val entries = ArchiveEngine.list(rar4)
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.name == "FILE1.TXT" && !it.isDirectory && it.size == content1.size.toLong() })
        assertTrue(entries.any { it.name == "FILE2.TXT" && !it.isDirectory && it.size == content2.size.toLong() })
        assertEquals((content1.size + content2.size).toLong(), ArchiveEngine.totalUncompressed(rar4))

        val dest = tmp.newFolder("rar4out")
        val count = ArchiveEngine.extractAll(rar4, dest)
        assertEquals(2, count)
        assertTrue(content1.contentEquals(File(dest, "FILE1.TXT").readBytes()))
        assertTrue(content2.contentEquals(File(dest, "FILE2.TXT").readBytes()))
    }

    // ═══ RAR5 — الجديد في v0.11: نفس المسار الموحد تماماً ═══

    @Test
    fun rar5ListAndExtractViaEngine() {
        val rar5 = fixture("rar5.rar")
        assertEquals(Family.RAR, familyOf(rar5.name))
        assertTrue(CommonArchive.isRar5(rar5))

        val entries = CommonArchive.list(rar5)
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.name == "FILE1.TXT" && it.size == content1.size.toLong() })
        assertTrue(entries.any { it.name == "FILE2.TXT" && it.size == content2.size.toLong() })

        val dest = tmp.newFolder("rar5out")
        val count = ArchiveEngine.extractAll(rar5, dest)
        assertEquals(2, count)
        assertTrue(content1.contentEquals(File(dest, "FILE1.TXT").readBytes()))
        assertTrue(content2.contentEquals(File(dest, "FILE2.TXT").readBytes()))
    }

    @Test
    fun rar5ProgressReportingReachesFullSize() {
        val rar5 = fixture("rar5.rar")
        val total = ArchiveEngine.totalUncompressed(rar5)
        assertEquals((content1.size + content2.size).toLong(), total)
        var lastDone = 0L
        val dest = tmp.newFolder("rar5prog")
        ArchiveEngine.extractAll(rar5, dest) { done, t ->
            lastDone = done
            assertEquals(total, t)
        }
        assertEquals(total, lastDone)
    }
}
