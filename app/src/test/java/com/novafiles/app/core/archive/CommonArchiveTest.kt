package com.novafiles.app.core.archive

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class CommonArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeFile(dir: File, name: String, bytes: ByteArray): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { it.write(bytes) }
        return f
    }

    private val text = "محتوى عربي داخل أرشيف TAR/7z — اختبار شامل".toByteArray(Charsets.UTF_8)
    private val bin = ByteArray(120 * 1024) { i -> (i * 11 % 249).toByte() }

    // ═══ TAR: رحلة كاملة بأسماء عربية متداخلة ═══

    @Test
    fun tarRoundTripArabicNested() {
        val zip = File(tmp.root, "a.tar")
        TarArchiveOutputStream(FileOutputStream(zip)).use { tos ->
            fun add(name: String, bytes: ByteArray?) {
                val e = TarArchiveEntry(name)
                if (bytes == null) {
                    tos.putArchiveEntry(e)
                } else {
                    e.setSize(bytes.size.toLong())
                    tos.putArchiveEntry(e)
                    tos.write(bytes)
                }
                tos.closeArchiveEntry()
            }
            add("مجلد/", null)
            add("مجلد/ملف عربي.txt", text)
            add("مجلد/فرعي/ثنائي.bin", bin)
        }

        assertEquals(Family.TAR, familyOf(zip.name))
        val entries = CommonArchive.list(zip)
        assertEquals(3, entries.size)
        assertTrue(entries.any { it.isDirectory && it.name.startsWith("مجلد") })
        assertEquals(text.size.toLong(), entries.first { it.name == "مجلد/ملف عربي.txt" }.size)

        val dest = tmp.newFolder("tarout")
        val count = CommonArchive.extractAll(zip, dest)
        assertEquals(2, count)
        assertTrue(text.contentEquals(File(dest, "مجلد/ملف عربي.txt").readBytes()))
        assertTrue(bin.contentEquals(File(dest, "مجلد/فرعي/ثنائي.bin").readBytes()))
    }

    // ═══ TAR.GZ: تغليف gzip فوق tar ═══

    @Test
    fun tarGzRoundTrip() {
        val tgz = File(tmp.root, "a.tar.gz")
        TarArchiveOutputStream(GzipCompressorOutputStream(FileOutputStream(tgz))).use { tos ->
            val e = TarArchiveEntry("data/ملف.txt")
            e.setSize(text.size.toLong())
            tos.putArchiveEntry(e)
            tos.write(text)
            tos.closeArchiveEntry()
        }

        assertEquals(Family.TAR, familyOf(tgz.name))
        assertTrue(tgz.length() < text.size + 512) // مضغوط فعلاً

        val dest = tmp.newFolder("tgzout")
        CommonArchive.extractAll(tgz, dest)
        assertTrue(text.contentEquals(File(dest, "data/ملف.txt").readBytes()))
    }

    // ═══ 7z: رحلة كاملة (إنشاء عبر SevenZOutputFile ثم قراءة) ═══

    @Test
    fun sevenZRoundTripArabic() {
        val srcFile = writeFile(tmp.root, "src.txt", text)
        val srcBin = writeFile(tmp.root, "مجلد بيانات/ثنائي.bin", bin)

        val sz = File(tmp.root, "a.7z")
        SevenZOutputFile(sz).use { out ->
            out.putArchiveEntry(out.createArchiveEntry(srcFile, "src.txt"))
            out.write(text)
            out.closeArchiveEntry()
            out.putArchiveEntry(out.createArchiveEntry(srcBin, "مجلد بيانات/ثنائي.bin"))
            out.write(bin)
            out.closeArchiveEntry()
        }

        assertEquals(Family.SEVEN, familyOf(sz.name))
        val entries = CommonArchive.list(sz)
        assertEquals(2, entries.size)
        assertEquals(bin.size.toLong(), entries.first { it.name == "مجلد بيانات/ثنائي.bin" }.size)

        val dest = tmp.newFolder("7zout")
        val count = CommonArchive.extractAll(sz, dest)
        assertEquals(2, count)
        assertTrue(text.contentEquals(File(dest, "src.txt").readBytes()))
        assertTrue(bin.contentEquals(File(dest, "مجلد بيانات/ثنائي.bin").readBytes()))
    }

    // ═══ الأمان: Tar-Slip مرفوض ═══

    @Test
    fun tarSlipEntryIsRejected() {
        val tar = File(tmp.root, "evil.tar")
        TarArchiveOutputStream(FileOutputStream(tar)).use { tos ->
            val e = TarArchiveEntry("../evil.txt")
            val bytes = "pwn".toByteArray()
            e.setSize(bytes.size.toLong())
            tos.putArchiveEntry(e)
            tos.write(bytes)
            tos.closeArchiveEntry()
        }
        val dest = tmp.newFolder("victim")
        var thrown: Exception? = null
        try {
            CommonArchive.extractAll(tar, dest)
        } catch (e: SecurityException) {
            thrown = e
        }
        assertNotNull("SecurityException expected", thrown)
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertEquals(0, dest.listFiles()?.size ?: -1)
    }

    // ═══ ضغط أحادي الملف: gz ═══

    @Test
    fun singleGzExtract() {
        val gz = File(tmp.root, "تقرير.txt.gz")
        GzipCompressorOutputStream(FileOutputStream(gz)).use { g ->
            g.write(text)
        }
        assertEquals(Family.COMPRESSED, familyOf(gz.name))

        val entries = CommonArchive.list(gz)
        assertEquals(1, entries.size)
        assertEquals("تقرير.txt", entries[0].name)
        assertEquals(-1L, entries[0].size) // الحجم الأصلي مجهول قبل الفك
        assertEquals(-1L, CommonArchive.totalUncompressed(gz))

        val dest = tmp.newFolder("gzout")
        val count = CommonArchive.extractAll(gz, dest)
        assertEquals(1, count)
        assertTrue(text.contentEquals(File(dest, "تقرير.txt").readBytes()))
    }

    // ═══ الموزع ArchiveEngine ═══

    @Test
    fun engineDispatchAndSupports() {
        assertTrue(ArchiveEngine.supports("x.zip"))
        assertTrue(ArchiveEngine.supports("x.ZIP"))
        assertTrue(ArchiveEngine.supports("x.7z"))
        assertTrue(ArchiveEngine.supports("x.rar"))
        assertTrue(ArchiveEngine.supports("x.tar"))
        assertTrue(ArchiveEngine.supports("x.tar.gz"))
        assertTrue(ArchiveEngine.supports("x.tgz"))
        assertTrue(ArchiveEngine.supports("x.tar.bz2"))
        assertTrue(ArchiveEngine.supports("x.tbz2"))
        assertTrue(ArchiveEngine.supports("x.tar.xz"))
        assertTrue(ArchiveEngine.supports("x.txz"))
        assertTrue(ArchiveEngine.supports("x.gz"))
        assertTrue(ArchiveEngine.supports("x.bz2"))
        assertTrue(ArchiveEngine.supports("x.xz"))
        assertFalse(ArchiveEngine.supports("x.zipx"))
        assertFalse(ArchiveEngine.supports("x.gzip")) // امتداد غير معروف
        assertFalse(ArchiveEngine.supports("x.rar5"))

        assertEquals(Family.ZIP, familyOf("x.zip"))
        assertEquals(Family.TAR, familyOf("x.txz"))
        assertEquals(Family.SEVEN, familyOf("x.7z"))
        assertEquals(Family.RAR, familyOf("x.rar"))
        assertEquals(Family.COMPRESSED, familyOf("x.xz"))
        assertEquals(Family.NONE, familyOf("x.gzip"))
    }

    // ═══ نزع الامتدادات المركبة ═══

    @Test
    fun stripExtHandlesCompoundExtensions() {
        assertEquals("backup", stripArchiveExt("backup.tar.gz"))
        assertEquals("backup", stripArchiveExt("backup.TGZ"))
        assertEquals("backup", stripArchiveExt("backup.tar"))
        assertEquals("backup", stripArchiveExt("backup.7z"))
        assertEquals("backup", stripArchiveExt("backup.rar"))
        assertEquals("backup", stripArchiveExt("backup.zip"))
        assertEquals("report", stripArchiveExt("report.xz"))
        assertEquals("noext", stripArchiveExt("noext"))
    }

    // ═══ المحرك يوزع zip على ZipArchive (رحلة موجودة) ═══

    @Test
    fun engineZipPathStillWorks() {
        val src = tmp.newFolder("s")
        writeFile(src, "f.txt", "hi".toByteArray())
        val zip = File(tmp.root, "s.zip")
        ZipArchive.createZip(listOf(src), zip)

        assertEquals(Family.ZIP, familyOf(zip.name))
        val entries = ArchiveEngine.list(zip)
        assertTrue(entries.any { it.name == "s/f.txt" })

        val dest = tmp.newFolder("eout")
        ArchiveEngine.extractAll(zip, dest)
        assertEquals("hi", File(dest, "s/f.txt").readText())
    }
}
