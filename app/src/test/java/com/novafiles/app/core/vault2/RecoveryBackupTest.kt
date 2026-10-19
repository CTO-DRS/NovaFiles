package com.novafiles.app.core.vault2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** النسخة الاحتياطية + تدريب الاسترجاع الكامل (معيار نجاح الإصدار) */
class RecoveryBackupTest {

    @Test
    fun create_restore_roundTrip() {
        val words = RecoveryPhrase.generate()
        val master = ByteArray(32) { 5 }
        val index = VaultIndex(entries = listOf(
            VaultEntry("f1", "", "ملف.txt", false, size = 5, keySalt = ByteArray(16) { 1 }),
        ))
        val backup = RecoveryBackup.create(words, master, index.serialize())
        assertEquals("NVBR1", String(backup, 0, 5, Charsets.US_ASCII))

        val restored = RecoveryBackup.restore(backup, words)
        assertArrayEquals(master, restored.masterKey)
        assertEquals("ملف.txt", restored.index.find("f1")!!.name)
    }

    @Test
    fun restore_wrongPhrase_fails() {
        val words = RecoveryPhrase.generate()
        val backup = RecoveryBackup.create(words, ByteArray(32) { 5 }, VaultIndex().serialize())
        val other = RecoveryPhrase.generate()
        val e = runCatching { RecoveryBackup.restore(backup, other) }
        assertTrue(e.isFailure)
        assertTrue(e.exceptionOrNull() is RecoveryBackup.CorruptedBackup)
    }

    @Test
    fun restore_garbage_fails() {
        val words = RecoveryPhrase.generate()
        val e = runCatching { RecoveryBackup.restore("جunk data".toByteArray(), words) }
        assertTrue(e.isFailure)
    }

    @Test
    fun create_rejectsBadPhrase() {
        val e = runCatching {
            RecoveryBackup.create(List(12) { "notaword" }, ByteArray(32), ByteArray(10))
        }
        assertTrue(e.isFailure)
    }

    /**
     * تدريب الاسترجاع — معيار نجاح الخارطة: «recovery drill passes from cold restore».
     * خزنة كاملة بمجلدات → نسخة احتياطية → مستودع بكر جديد (fs نظيف) → استيراد → فك.
     */
    @Test
    fun fullRecoveryDrill_coldRestore() {
        // 1) خزنة أصلية بمحتوى
        val sourceFs = InMemoryFs()
        val source = Vault2Repository(sourceFs, ByteArray(32) { 11 })
        val docs = source.createFolder("", "مستندات")
        val photos = source.createFolder(docs.id, "صور")
        val f1 = source.importFile(docs.id, "عقد.pdf", ByteArrayInputStream("بنود العقد الكاملة".toByteArray()))
        val f2 = source.importFile(photos.id, "صورة.jpg", ByteArrayInputStream(ByteArray(1000) { (it % 199).toByte() }))

        // 2) نسخة احتياطية بعبارة
        val words = RecoveryPhrase.generate()
        val backup = RecoveryBackup.create(words, source.exportMasterKey(), source.index.serialize())
        source.close()

        // 3) سيناريو واقعي: التخزين سليم لكن المفاتيح ضاعت (Keystore مُصفّر) —
        //    ننسخ ملفات البيانات إلى fs جديد بلا فهرس، ومفتاح بكر عشوائي
        val coldFs = InMemoryFs()
        sourceFs.files.forEach { (path, bytes) ->
            if (path.startsWith("data/")) coldFs.files[path] = bytes
        }
        val broken = Vault2Repository(coldFs, ByteArray(32) { 99 }) // مفتاح بكر مختلف كلياً
        assertEquals(0, broken.fileCount())

        // 4) استيراد النسخة: المفتاح المستعاد يُتبنى (كما يفعل adoptRestoredMaster)
        //    ثم يُعاد بناء المستودع به ويُستبدل الفهرس
        val restored = RecoveryBackup.restore(backup, words)
        org.junit.Assert.assertArrayEquals(ByteArray(32) { 11 }, restored.masterKey)
        val cold = Vault2Repository(coldFs, restored.masterKey)
        cold.replaceIndex(restored.index)

        // 5) الملفات تُفك من الخزنة «الباردة»
        val out1 = ByteArrayOutputStream()
        cold.exportFile(f1.id, out1)
        assertEquals("بنود العقد الكاملة", out1.toString("UTF-8"))
        val out2 = ByteArrayOutputStream()
        cold.exportFile(f2.id, out2)
        assertArrayEquals(ByteArray(1000) { (it % 199).toByte() }, out2.toByteArray())
        assertEquals("مستندات/صور", cold.index.displayPath(f2.id))
    }
}
