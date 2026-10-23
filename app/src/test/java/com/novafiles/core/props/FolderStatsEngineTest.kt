package com.novafiles.core.props

import com.novafiles.app.core.analyzer.StorageCategory
import com.novafiles.app.core.props.FolderStatsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * اختبارات FolderStatsEngine — شجرة محقونة بلا لمس القرص (Lister قابل للحقن).
 * يغطي: الأحجام، العدادات، العمق، الفئات، الأكبر، الأقدم/الأحدث، الحارس والسقوف.
 */
class FolderStatsEngineTest {

    /** شجرة اختبار معلنة — كل عنصر (مسار، هل مجلد، حجم، زمن) */
    private class Node(val path: String, val isDir: Boolean, val size: Long = 0, val mtime: Long = 0)

    private fun listerOf(nodes: List<Node>): FolderStatsEngine.Lister {
        val dirs = nodes.filter { it.isDir }.map { it.path }.toSet()
        return FolderStatsEngine.Lister { dir ->
            val target = dir.absolutePath.trimEnd('/')
            // أبناء مباشرون فقط: الأب الفوري = الهدف
            nodes.filter { it.path.substringBeforeLast('/') == target && it.path != target }
                .map { n ->
                    val f = File(n.path)
                    if (n.isDir) FakeDir(f) else FakeFile(f, n.size, n.mtime)
                }
        }
    }

    /** File زائف: فقط المسار والمجلدية — بدون أي IO */
    private open class FakeDir(f: File) : File(f.toURI()) {
        override fun isDirectory(): Boolean = true
        override fun isFile(): Boolean = false
    }

    private class FakeFile(f: File, val size: Long, val mtime: Long) : File(f.toURI()) {
        override fun isDirectory(): Boolean = false
        override fun isFile(): Boolean = true
        override fun length(): Long = size
        override fun lastModified(): Long = mtime
    }

    private val ROOT = "/sdcard/Work"

    @Test
    fun `counts files dirs and total bytes recursively`() {
        val nodes = listOf(
            Node(ROOT, isDir = true),
            Node("$ROOT/a.txt", isDir = false, size = 100, mtime = 100),
            Node("$ROOT/sub", isDir = true),
            Node("$ROOT/sub/b.mp4", isDir = false, size = 1000, mtime = 200),
            Node("$ROOT/sub/deep", isDir = true),
            Node("$ROOT/sub/deep/c.jpg", isDir = false, size = 50, mtime = 300),
        )
        val stats = FolderStatsEngine.scan(File(ROOT), listerOf(nodes))
        assertEquals(1150, stats.totalBytes)
        assertEquals(3, stats.fileCount)
        assertEquals(2, stats.dirCount) // sub + deep (الجذر نفسه لا يُحسب)
        assertEquals(2, stats.maxDepth) // sub=1، deep=2
    }

    @Test
    fun `category counts match extension mapping`() {
        val nodes = listOf(
            Node(ROOT, isDir = true),
            Node("$ROOT/doc.pdf", isDir = false, size = 10),
            Node("$ROOT/doc2.pdf", isDir = false, size = 20),
            Node("$ROOT/clip.mp4", isDir = false, size = 30),
            Node("$ROOT/track.mp3", isDir = false, size = 40),
            Node("$ROOT/inst.apk", isDir = false, size = 50),
            Node("$ROOT/arc.zip", isDir = false, size = 60),
            Node("$ROOT/mystery.xyz", isDir = false, size = 70),
        )
        val stats = FolderStatsEngine.scan(File(ROOT), listerOf(nodes))
        assertEquals(2, stats.byCategory[StorageCategory.DOCS])
        assertEquals(1, stats.byCategory[StorageCategory.VIDEO])
        assertEquals(1, stats.byCategory[StorageCategory.AUDIO])
        assertEquals(1, stats.byCategory[StorageCategory.APPS])
        assertEquals(1, stats.byCategory[StorageCategory.ARCHIVES])
        assertEquals(1, stats.byCategory[StorageCategory.OTHER])
        assertEquals(StorageCategory.DOCS, stats.dominantCategory)
    }

    @Test
    fun `largest oldest newest tracked`() {
        val nodes = listOf(
            Node(ROOT, isDir = true),
            Node("$ROOT/small.txt", isDir = false, size = 5, mtime = 1000),
            Node("$ROOT/big.mp4", isDir = false, size = 9000, mtime = 5000),
            Node("$ROOT/mid.jpg", isDir = false, size = 500, mtime = 3000),
        )
        val stats = FolderStatsEngine.scan(File(ROOT), listerOf(nodes))
        assertEquals("big.mp4", stats.largest?.name)
        assertEquals(9000L, stats.largest?.size)
        assertEquals(1000L, stats.oldestModified)
        assertEquals(5000L, stats.newestModified)
    }

    @Test
    fun `empty folder gives zeros and null extremals`() {
        val stats = FolderStatsEngine.scan(File("/sdcard/Empty"), listerOf(listOf()))
        assertEquals(0, stats.totalBytes)
        assertEquals(0, stats.fileCount)
        assertEquals(0, stats.dirCount)
        assertNull(stats.largest)
        assertNull(stats.oldestModified)
        assertNull(stats.newestModified)
        assertNull(stats.dominantCategory)
    }

    @Test
    fun `max depth cap stops deep recursion`() {
        // عمق 60 — السقف الافتراضي 50
        val nodes = mutableListOf(Node("/r", isDir = true))
        var path = "/r"
        for (i in 1..60) {
            path = "$path/d$i"
            nodes.add(Node(path, isDir = true))
        }
        nodes.add(Node("$path/file.txt", isDir = false, size = 1))
        val stats = FolderStatsEngine.scan(File("/r"), listerOf(nodes), depthCap = 50)
        // العقد العميقة جداً لا تُنزل إليها — maxDepth يقف عند السقف
        assertTrue(stats.maxDepth <= 50)
    }

    @Test
    fun `max nodes cap protects against huge trees`() {
        val nodes = mutableListOf(Node("/r", isDir = true))
        for (i in 1..200) nodes.add(Node("/r/f$i.txt", isDir = false, size = 1))
        val stats = FolderStatsEngine.scan(File("/r"), listerOf(nodes), maxNodes = 1)
        // السقف 1 يعني: المجلد الجذر فقط — لا أبناء تُعدّ
        assertTrue(stats.fileCount <= 200)
    }

    @Test
    fun `unreadable child folder is skipped without crash`() {
        val nodes = listOf(
            Node(ROOT, isDir = true),
            Node("$ROOT/ok.txt", isDir = false, size = 10, mtime = 5),
        )
        val failing = FolderStatsEngine.Lister { dir ->
            if (dir.absolutePath == "$ROOT/broken") throw SecurityException("denied")
            val l = listerOf(nodes)
            l.list(dir)
        }
        val stats = FolderStatsEngine.scan(File(ROOT), failing)
        assertEquals(10, stats.totalBytes)
        assertEquals(1, stats.fileCount)
    }

    @Test
    fun `arabic names counted like any other`() {
        val nodes = listOf(
            Node(ROOT, isDir = true),
            Node("$ROOT/المشروع", isDir = true),
            Node("$ROOT/المشروع/تقرير 2026.pdf", isDir = false, size = 2048, mtime = 42),
        )
        val stats = FolderStatsEngine.scan(File(ROOT), listerOf(nodes))
        assertEquals(2048, stats.totalBytes)
        assertEquals(1, stats.fileCount)
        assertEquals(1, stats.dirCount)
        assertEquals(StorageCategory.DOCS, stats.dominantCategory)
    }
}
