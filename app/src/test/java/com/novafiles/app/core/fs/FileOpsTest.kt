package com.novafiles.app.core.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * اختبارات محرك عمليات الملفات — خاصة حماية «النقل الذاتي»
 * التي تكتسب أهميتها مع وضع اللوحتين (نقل بين اللوحات قد يستهدف فرعاً من المصدر).
 */
class FileOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String, vararg children: String): File {
        val d = tmp.newFolder(name)
        children.forEach { File(d, it).writeText("content-of-$it") }
        return d
    }

    // ─── حماية النقل الذاتي ───

    @Test
    fun `copy directory into its own subdirectory is rejected`() = runBlocking(Dispatchers.IO) {
        val parent = dir("parent", "a.txt", "b.txt")
        val sub = File(parent, "sub").apply { mkdirs() }

        val result = FileOps.copyAll(listOf(parent), sub)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        // المصدر سليم لم يمسّ (a.txt + b.txt + sub)
        assertEquals(3, parent.listFiles()?.size)
    }

    @Test
    fun `move directory into its own subdirectory is rejected`() = runBlocking(Dispatchers.IO) {
        val parent = dir("parent", "a.txt")
        val sub = File(parent, "sub").apply { mkdirs() }

        val result = FileOps.moveAll(listOf(parent), sub)

        assertTrue(result.isFailure)
        assertTrue(parent.exists())
        assertTrue(File(parent, "a.txt").exists())
    }

    @Test
    fun `move directory into itself is rejected`() = runBlocking(Dispatchers.IO) {
        val d = dir("selftarget", "x.txt")

        val result = FileOps.moveAll(listOf(d), d)

        assertTrue(result.isFailure)
        assertTrue(File(d, "x.txt").exists())
    }

    @Test
    fun `deep descendant target is rejected via canonical path`() = runBlocking(Dispatchers.IO) {
        val root = dir("root")
        val deep = File(root, "l1/l2/l3").apply { mkdirs() }

        val result = FileOps.moveAll(listOf(root), deep)

        assertTrue(result.isFailure)
        assertTrue(root.exists())
    }

    @Test
    fun `copying a file onto itself is allowed and auto-renamed`() = runBlocking(Dispatchers.IO) {
        val d = dir("samename", "f.txt")

        val result = FileOps.copyAll(listOf(File(d, "f.txt")), d)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
        assertTrue(File(d, "f.txt").exists())
        assertTrue(File(d, "f (1).txt").exists())
    }

    // ─── العمليات السليمة تظل تعمل ───

    @Test
    fun `copy directory to sibling preserves contents`() = runBlocking(Dispatchers.IO) {
        val src = dir("src", "one.txt", "two.txt")
        File(src, "nested").mkdirs()
        File(src, "nested/deep.txt").writeText("deep")
        val target = tmp.newFolder("target")

        val result = FileOps.copyAll(listOf(src), target)

        assertEquals(1, result.getOrNull())
        val copied = File(target, "src")
        assertTrue(copied.isDirectory)
        assertEquals("content-of-one.txt", File(copied, "one.txt").readText())
        assertEquals("deep", File(copied, "nested/deep.txt").readText())
        assertTrue(src.exists())
    }

    @Test
    fun `move to sibling uses fast rename and removes source`() = runBlocking(Dispatchers.IO) {
        val src = dir("moveme", "data.txt")
        val target = tmp.newFolder("destination")

        val result = FileOps.moveAll(listOf(src), target)

        assertEquals(1, result.getOrNull())
        assertFalse(src.exists())
        assertEquals("content-of-data.txt", File(File(target, "moveme"), "data.txt").readText())
    }

    // ─── أسماء فريدة ───

    @Test
    fun `uniqueChild returns original name when free`() {
        val dir = tmp.newFolder("unique")
        val picked = FileOps.uniqueChild(dir, "new.txt")
        assertEquals("new.txt", picked.name)
    }

    @Test
    fun `uniqueChild skips taken names sequentially`() {
        val dir = tmp.newFolder("unique2")
        File(dir, "doc.txt").writeText("a")
        File(dir, "doc (1).txt").writeText("b")
        val picked = FileOps.uniqueChild(dir, "doc.txt")
        assertEquals("doc (2).txt", picked.name)
    }

    @Test
    fun `uniqueChild handles extensionless files`() {
        val dir = tmp.newFolder("unique3")
        File(dir, "README").writeText("a")
        val picked = FileOps.uniqueChild(dir, "README")
        assertEquals("README (1)", picked.name)
    }
}
