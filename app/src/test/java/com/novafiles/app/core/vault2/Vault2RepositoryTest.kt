package com.novafiles.app.core.vault2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** المستودع: إنشاء مجلدات، استيراد/تصدير، نقل بإعادة تشفير، حذف شجري، ثبات عبر إعادة الفتح */
class Vault2RepositoryTest {

    private fun newRepo(fs: VaultFs = InMemoryFs(), master: ByteArray = ByteArray(32) { 7 }) =
        Vault2Repository(fs, master)

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun importExport_roundTrip() {
        val repo = newRepo()
        val data = bytes("سر كلي — confidential 2026")
        val entry = repo.importFile("", "secret.txt", ByteArrayInputStream(data))
        assertEquals("secret.txt", entry.name)
        assertEquals(data.size.toLong(), entry.size)
        val out = ByteArrayOutputStream()
        repo.exportFile(entry.id, out)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun importFile_uniqueNames() {
        val repo = newRepo()
        val a = repo.importFile("", "doc.pdf", ByteArrayInputStream(bytes("1")))
        val b = repo.importFile("", "doc.pdf", ByteArrayInputStream(bytes("22")))
        val c = repo.importFile("", "doc.pdf", ByteArrayInputStream(bytes("333")))
        assertEquals(listOf("doc.pdf", "doc (1).pdf", "doc (2).pdf"), listOf(a.name, b.name, c.name))
    }

    @Test
    fun folders_nestedTree() {
        val repo = newRepo()
        val docs = repo.createFolder("", "مستندات")
        val work = repo.createFolder(docs.id, "عمل")
        val file = repo.importFile(work.id, "عقد.docx", ByteArrayInputStream(bytes("بنود العقد")))
        assertEquals("مستندات/عمل", repo.index.displayPath(file.id))
        assertEquals(1, repo.index.children(work.id).size)
        assertEquals(1, repo.index.children(docs.id).size)
    }

    @Test
    fun duplicateFolderName_rejected() {
        val repo = newRepo()
        repo.createFolder("", "صور")
        assertThrows(IllegalStateException::class.java) { repo.createFolder("", "صور") }
    }

    @Test
    fun persistence_acrossReopen() {
        val fs = InMemoryFs()
        val master = ByteArray(32) { 42 }
        val repo = Vault2Repository(fs, master)
        val folder = repo.createFolder("", "أرشيف")
        val file = repo.importFile(folder.id, "سجل.csv", ByteArrayInputStream(bytes("a,b,c\n1,2,3")))
        repo.close()

        val reopened = Vault2Repository(fs, master)
        assertEquals(1, reopened.fileCount())
        assertEquals(folder.id, reopened.index.find(file.id)!!.parentId)
        val out = ByteArrayOutputStream()
        reopened.exportFile(file.id, out)
        assertArrayEquals(bytes("a,b,c\n1,2,3"), out.toByteArray())
    }

    @Test
    fun reopen_differentMaster_fails() {
        val fs = InMemoryFs()
        val repo = Vault2Repository(fs, ByteArray(32) { 1 })
        repo.importFile("", "x.txt", ByteArrayInputStream(bytes("data")))
        repo.close()

        // مفتاح مختلف → الفهرس المشفر لا يُفك — نرمي بدل الكتابة فوق بيانات حقيقية
        assertThrows(ChunkedCipher.CorruptedVaultFile::class.java) {
            Vault2Repository(fs, ByteArray(32) { 2 })
        }
    }

    @Test
    fun move_betweenFolders_reEncrypts() {
        val fs = InMemoryFs()
        val repo = newRepo(fs)
        val src = repo.createFolder("", "المصدر")
        val dst = repo.createFolder("", "الوجهة")
        val file = repo.importFile(src.id, "confidential.bin", ByteArrayInputStream(bytes("0123456789".repeat(100))))

        repo.move(file.id, dst.id)
        assertEquals(dst.id, repo.index.find(file.id)!!.parentId)
        val out = ByteArrayOutputStream()
        repo.exportFile(file.id, out)
        assertArrayEquals(bytes("0123456789".repeat(100)), out.toByteArray())

        // لا مخلفات ملف مؤقت .mv
        assertTrue(fs.files.keys.none { it.endsWith(".mv") })
    }

    @Test
    fun move_folderWithChildren() {
        val repo = newRepo()
        val a = repo.createFolder("", "A")
        val b = repo.createFolder("", "B")
        val sub = repo.createFolder(a.id, "فرعي")
        val file = repo.importFile(sub.id, "f.txt", ByteArrayInputStream(bytes("مغلف")))
        repo.move(a.id, b.id)
        assertEquals(b.id, repo.index.find(a.id)!!.parentId)
        // الأبناء يبقون داخل أبيهم المنقول — الشجرة تنتقل ككتلة واحدة
        assertEquals(a.id, repo.index.find(sub.id)!!.parentId)
        assertEquals("B/A", repo.index.displayPath(sub.id))
        val out = ByteArrayOutputStream()
        repo.exportFile(file.id, out)
        assertArrayEquals(bytes("مغلف"), out.toByteArray())
    }

    @Test
    fun move_intoOwnSubtree_rejected() {
        val repo = newRepo()
        val a = repo.createFolder("", "A")
        val sub = repo.createFolder(a.id, "فرعي")
        assertThrows(IllegalArgumentException::class.java) { repo.move(a.id, sub.id) }
    }

    @Test
    fun rename_entry() {
        val repo = newRepo()
        val file = repo.importFile("", "old.txt", ByteArrayInputStream(bytes("1")))
        repo.rename(file.id, "new.txt")
        assertEquals("new.txt", repo.index.find(file.id)!!.name)
        assertThrows(IllegalArgumentException::class.java) { repo.rename(file.id, "") }
    }

    @Test
    fun deleteRecursive_folderAndContents() {
        val repo = newRepo()
        val folder = repo.createFolder("", "سطح المكتب")
        val sub = repo.createFolder(folder.id, "عمل")
        repo.importFile(sub.id, "a.txt", ByteArrayInputStream(bytes("a")))
        repo.importFile(folder.id, "b.txt", ByteArrayInputStream(bytes("b")))
        repo.importFile("", "c.txt", ByteArrayInputStream(bytes("c")))

        val deletedFiles = repo.deleteRecursive(folder.id)
        assertEquals(2, deletedFiles)
        assertEquals(1, repo.fileCount())
        assertEquals("c.txt", repo.index.children("").first().name)
    }

    @Test
    fun largeFile_streamed_chunked() {
        val repo = newRepo()
        val big = ByteArray(300 * 1024) { (it % 251).toByte() } // 300KB → 5 أجزاء 64KiB
        val entry = repo.importFile("", "big.bin", ByteArrayInputStream(big))
        assertEquals(big.size.toLong(), entry.size)
        val out = ByteArrayOutputStream()
        repo.exportFile(entry.id, out)
        assertArrayEquals(big, out.toByteArray())
        // معاينة جزء واحد فقط
        val firstChunk = repo.readChunk(entry.id, 0)
        assertArrayEquals(big.copyOfRange(0, 64 * 1024), firstChunk)
        val thirdChunk = repo.readChunk(entry.id, 2)
        assertArrayEquals(big.copyOfRange(2 * 64 * 1024, 3 * 64 * 1024), thirdChunk)
    }

    @Test
    fun export_missingEntry_fails() {
        val repo = newRepo()
        assertThrows(IllegalStateException::class.java) {
            repo.exportFile("nope", ByteArrayOutputStream())
        }
    }

    @Test
    fun emptyRepository_stats() {
        val repo = newRepo()
        assertEquals(0, repo.fileCount())
        assertEquals(0L, repo.totalSize())
        assertNotNull(repo.index)
    }

    @Test
    fun import_rejectsInvalidNames() {
        val repo = newRepo()
        assertThrows(IllegalArgumentException::class.java) {
            repo.importFile("", "", ByteArrayInputStream(bytes("x")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repo.importFile("", "a/b", ByteArrayInputStream(bytes("x")))
        }
    }

    @Test
    fun multipleFiles_sameContent_differentCiphertext() {
        val fs = InMemoryFs()
        val repo = newRepo(fs)
        val data = bytes("نفس المحتوى تماماً")
        val a = repo.importFile("", "a.txt", ByteArrayInputStream(data))
        val b = repo.importFile("", "b.txt", ByteArrayInputStream(data))
        // ملاح متسرب لا يستنتج تطابق المحتوى من تطابق النص المشفر
        val fa = fs.files["data/${a.id}.b"]!!
        val fb = fs.files["data/${b.id}.b"]!!
        assertFalse(fa.contentEquals(fb))
    }
}
