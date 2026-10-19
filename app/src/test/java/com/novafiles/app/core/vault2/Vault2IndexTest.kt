package com.novafiles.app.core.vault2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** الفهرس: تسلسل ثنائي بالتدوير + منطق الشجرة */
class Vault2IndexTest {

    private fun sample(): VaultIndex {
        val root = VaultIndex(rootSalt = ByteArray(32) { 9 })
        val docs = VaultEntry("d1", "", "مستندات", true, createdAt = 100, keySalt = ByteArray(16) { 1 })
        val photos = VaultEntry("d2", "d1", "صور", true, createdAt = 200, keySalt = ByteArray(16) { 2 })
        val file1 = VaultEntry("f1", "d2", "تقرير.pdf", false, size = 2048, createdAt = 300, keySalt = ByteArray(16) { 3 })
        val file2 = VaultEntry("f2", "", "مذكرة.txt", false, size = 128, createdAt = 400, keySalt = ByteArray(16) { 4 })
        return root.copy(entries = listOf(docs, photos, file1, file2))
    }

    @Test
    fun serialize_roundTrip() {
        val index = sample()
        val restored = VaultIndex.deserialize(index.serialize())
        assertEquals(index.version, restored.version)
        assertArrayEquals(index.rootSalt, restored.rootSalt)
        assertEquals(index.entries.size, restored.entries.size)
        index.entries.forEach { original ->
            val back = restored.find(original.id)!!
            assertEquals(original.name, back.name)
            assertEquals(original.parentId, back.parentId)
            assertEquals(original.isFolder, back.isFolder)
            assertEquals(original.size, back.size)
            assertEquals(original.createdAt, back.createdAt)
            assertArrayEquals(original.keySalt, back.keySalt)
        }
    }

    @Test
    fun children_sorted_foldersFirst() {
        val index = sample()
        val rootChildren = index.children("")
        assertEquals(listOf("مستندات", "مذكرة.txt"), rootChildren.map { it.name })
        val docsChildren = index.children("d1")
        assertEquals(listOf("صور"), docsChildren.map { it.name })
    }

    @Test
    fun pathOf_fullChain() {
        val index = sample()
        val path = index.pathOf("f1")
        assertEquals(listOf("مستندات", "صور"), path.map { it.name })
        assertEquals("مستندات/صور", index.displayPath("f1"))
        assertTrue(index.pathOf("f2").isEmpty())
    }

    @Test
    fun isDescendantOf_detectsSubtree() {
        val index = sample()
        assertTrue(index.isDescendantOf("d2", "d1"))
        assertTrue(index.isDescendantOf("f1", "d1"))
        assertFalse(index.isDescendantOf("d1", "d2"))
        assertFalse(index.isDescendantOf("f2", "d1"))
    }

    @Test
    fun deserialize_rejectsGarbage() {
        assertThrows(Exception::class.java) { VaultIndex.deserialize(ByteArray(10)) }
        val index = sample()
        val bytes = index.serialize()
        val corrupted = bytes.copyOf().also { it[4] = 0x7F } // saltLen قفزة
        assertThrows(Exception::class.java) { VaultIndex.deserialize(corrupted) }
    }

    @Test
    fun find_missing_returnsNull() {
        val index = sample()
        assertFalse(index.find("nope") != null)
    }
}
