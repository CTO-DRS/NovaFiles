package com.novafiles.app.core.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun writeFile(dir: File, name: String, bytes: ByteArray): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { it.write(bytes) }
        return f
    }

    // ═══ إنشاء ← سرد ← استخراج: رحلة كاملة بأسماء عربية متداخلة ═══

    @Test
    fun roundTripArabicNestedStructure() {
        val src = tmp.newFolder("src")
        val text = "مرحبا بالعالم — ملف نصي عربي".toByteArray(Charsets.UTF_8)
        val bin = ByteArray(150 * 1024) { i -> (i * 7 % 251).toByte() }
        writeFile(src, "ملف عربي.txt", text)
        writeFile(src, "مجلد فرعي/داخلي/ثنائي.bin", bin)
        writeFile(src, "plain.txt", "hello".toByteArray())

        val zip = File(tmp.root, "archive.zip")
        val added = ZipArchive.createZip(listOf(src), zip)
        assertTrue("zip should exist", zip.exists() && zip.length() > 0)

        // السرد يحتوي المجلد الجذري + مجلد + 3 ملفات (مسار المدخل سِلَمي)
        val names = ZipArchive.list(zip).map { it.name }.toSet()
        assertTrue(names.contains("src/plain.txt"))
        assertTrue(names.contains("src/ملف عربي.txt"))
        assertTrue(names.contains("src/مجلد فرعي/داخلي/ثنائي.bin"))
        assertTrue(names.any { it.startsWith("src/") && it.endsWith("/") })

        // الاستخراج
        val dest = tmp.newFolder("out")
        val extracted = ZipArchive.extractAll(zip, dest)
        assertEquals(3, extracted)

        assertEquals(text.size.toLong(), File(dest, "src/ملف عربي.txt").length())
        assertTrue(text.contentEquals(File(dest, "src/ملف عربي.txt").readBytes()))
        assertTrue(bin.contentEquals(File(dest, "src/مجلد فرعي/داخلي/ثنائي.bin").readBytes()))
        assertEquals("hello", File(dest, "src/plain.txt").readText())
    }

    // ═══ السرد: أعلام المجلدات والأحجام ═══

    @Test
    fun listReportsDirectoriesAndSizes() {
        val src = tmp.newFolder("data")
        val content = ByteArray(4096) { 42 }
        writeFile(src, "a.bin", content)
        writeFile(src, "sub/b.txt", ByteArray(10))

        val zip = File(tmp.root, "data.zip")
        ZipArchive.createZip(listOf(src), zip)

        val entries = ZipArchive.list(zip)
        val dirEntries = entries.filter { it.isDirectory }
        val fileEntries = entries.filter { !it.isDirectory }
        // مجلد الجذر + sub = مدخلان على الأقل (قد يضيف walkTopDown مجلدات وسيطة)
        assertTrue(dirEntries.any { it.name.startsWith("data/") })
        assertTrue(dirEntries.none { !it.name.endsWith("/") })
        assertEquals(2, fileEntries.size)
        assertEquals(4096L, fileEntries.first { it.name == "data/a.bin" }.size)
        assertTrue(fileEntries.all { it.size > 0L })

        // المجموع الكلي للسوق
        assertEquals(4106L, ZipArchive.totalUncompressed(zip))
    }

    // ═══ الأمان: Zip-Slip مرفوض ═══

    @Test
    fun zipSlipEntryIsRejected() {
        val zip = File(tmp.root, "evil.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("../evil.txt"))
            zos.write("pwn".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("a/../../evil2.txt"))
            zos.write("pwn2".toByteArray())
            zos.closeEntry()
        }
        val dest = tmp.newFolder("victim")
        var thrown: Exception? = null
        try {
            ZipArchive.extractAll(zip, dest)
        } catch (e: SecurityException) {
            thrown = e
        }
        assertNotNull("SecurityException expected", thrown)
        // لا ملفات خارج الوجهة
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(tmp.root, "evil2.txt").exists())
        assertEquals(0, dest.listFiles()?.size ?: -1)
    }

    // ═══ الأمان: المدخلات المطلقة وبادئات محركات الأقراص تُطبَّع داخل الوجهة ═══

    @Test
    fun absoluteAndDriveEntriesAreNormalizedInsideDest() {
        val zip = File(tmp.root, "abs.zip")
        ZipOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putNextEntry(ZipEntry("/abs/inner.txt"))
            zos.write("ok".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("C:\\drv\\f.txt"))
            zos.write("ok2".toByteArray())
            zos.closeEntry()
        }
        val dest = tmp.newFolder("dest")
        ZipArchive.extractAll(zip, dest)
        // تُستخرج داخل الوجهة حصراً
        assertTrue(File(dest, "abs/inner.txt").exists())
        assertTrue(File(dest, "drv/f.txt").exists())
        assertFalse(File(tmp.root, "abs").exists())
    }

    // ═══ التقدم: الرقائق والاكتمال ═══

    @Test
    fun progressCallbacksFireForExtractAndCreate() {
        val src = tmp.newFolder("p")
        val big = ByteArray(300 * 1024) { (it % 13).toByte() }
        writeFile(src, "big.bin", big)

        var createDone = 0L
        var createTotal = 0L
        val zip = File(tmp.root, "p.zip")
        ZipArchive.createZip(listOf(src), zip) { d, t -> createDone = d; createTotal = t }
        // مدخلان: مجلد الجذر "p/" + الملف "p/big.bin"
        assertEquals(2L, createTotal)
        assertEquals(2L, createDone)

        var extractDone = 0L
        var extractTotal = 0L
        val dest = tmp.newFolder("pout")
        ZipArchive.extractAll(zip, dest) { d, t -> extractDone = d; extractTotal = t }
        assertEquals(big.size.toLong(), extractTotal)
        assertEquals(big.size.toLong(), extractDone)
    }

    // ═══ الرفض: مصادر فارغة ═══

    @Test
    fun emptySourcesRejected() {
        val out = File(tmp.root, "empty.zip")
        var thrown: Exception? = null
        try {
            ZipArchive.createZip(emptyList(), out)
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertNotNull("IllegalArgumentException expected", thrown)
    }

    // ═══ إعادة الضغط: الأرشيف لا يضم نفسه ═══

    @Test
    fun createZipSkipsItself() {
        val src = tmp.newFolder("self")
        writeFile(src, "x.txt", "data".toByteArray())
        val out = File(src, "self.zip")
        ZipArchive.createZip(listOf(src), out)
        assertTrue(out.exists() && out.length() > 0)
        val names = ZipArchive.list(out).map { it.name }
        assertTrue(names.none { it.contains("self.zip") })
    }

    // ═══ الاستخراج فوق وجهة موجودة (كتابة فوقية) ═══

    @Test
    fun extractOverExistingDestSucceeds() {
        val src = tmp.newFolder("o")
        val first = "first".toByteArray()
        val second = "second".toByteArray()
        writeFile(src, "f.txt", first)

        val zip = File(tmp.root, "o.zip")
        ZipArchive.createZip(listOf(src), zip)

        val dest = File(tmp.root, "same_out")
        dest.mkdirs()
        writeFile(dest, "f.txt", second) // موجود مسبقاً

        ZipArchive.extractAll(zip, dest)
        assertTrue(first.contentEquals(File(dest, "o/f.txt").readBytes()))
    }
}
