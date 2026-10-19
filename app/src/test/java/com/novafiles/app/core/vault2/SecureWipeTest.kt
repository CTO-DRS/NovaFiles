package com.novafiles.app.core.vault2

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** المسح الآمن: ممرات، تحقق، حذف، شجري */
class SecureWipeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun wipeFile_deletedAndGone() {
        val file = tmp.newFile("secret.txt")
        file.writeBytes(ByteArray(5000) { 42 })
        val report = SecureWipe.wipe(file)
        assertTrue(report.deleted)
        assertEquals(3, report.passes) // أصفار + آحاد + عشوائي
        assertEquals(5000L, report.bytes)
        assertFalse(file.exists())
    }

    @Test
    fun wipeFile_multiRounds() {
        val file = tmp.newFile("multi.bin")
        file.writeBytes(ByteArray(2000) { 1 })
        val report = SecureWipe.wipe(file, rounds = 2)
        assertEquals(6, report.passes)
        assertFalse(file.exists())
    }

    @Test
    fun wipe_missingFile_noCrash() {
        val report = SecureWipe.wipe(File(tmp.root, "غير موجود"))
        assertFalse(report.deleted)
        assertEquals(0L, report.bytes)
    }

    @Test
    fun wipeTree_allFilesAndDirsGone() {
        val root = tmp.newFolder("vault")
        val sub = File(root, "sub").apply { mkdirs() }
        File(root, "a.bin").writeBytes(ByteArray(3000) { 9 })
        File(sub, "b.bin").writeBytes(ByteArray(1500) { 8 })
        File(sub, "c.bin").writeBytes(ByteArray(700) { 7 })

        val reports = SecureWipe.wipeTree(root)
        assertEquals(3, reports.size)
        assertTrue(reports.all { it.deleted })
        assertFalse(root.exists())
        assertFalse(sub.exists())
    }

    @Test
    fun wipe_rejectsInvalidRounds() {
        val file = tmp.newFile("x")
        file.writeBytes(ByteArray(10))
        assertThrows(IllegalArgumentException::class.java) { SecureWipe.wipe(file, rounds = 0) }
        assertThrows(IllegalArgumentException::class.java) { SecureWipe.wipe(file, rounds = 8) }
        file.delete()
    }

    @Test
    fun wipe_rejectsDirectory() {
        val dir = tmp.newFolder("dir")
        assertThrows(IllegalArgumentException::class.java) { SecureWipe.wipe(dir) }
    }
}
