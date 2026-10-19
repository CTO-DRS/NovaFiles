package com.novafiles.app.core.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * اختبارات RecentsEngine + RelativeTime — خالصة JVM
 */
class RecentsEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(base: File, rel: String, mtime: Long, size: Long = 10): File {
        val f = File(base, rel)
        f.parentFile.mkdirs()
        f.writeText("x".repeat(size.toInt().coerceAtLeast(1)))
        f.setLastModified(mtime)
        return f
    }

    // ═══════════ RecentsEngine.collect ═══════════

    @Test
    fun `collect returns files sorted newest first`() {
        val base = tmp.newFolder("root")
        touch(base, "a.txt", 1_000L)
        touch(base, "b.txt", 3_000L)
        touch(base, "c.txt", 2_000L)
        val out = RecentsEngine.collect(listOf(base), now = 10_000L)
        assertEquals(listOf("b.txt", "c.txt", "a.txt"), out.map { it.name })
    }

    @Test
    fun `equal timestamps tie-broken by path alphabetically`() {
        val base = tmp.newFolder("root")
        touch(base, "zeta.txt", 5_000L)
        touch(base, "alpha.txt", 5_000L)
        touch(base, "mid.txt", 5_000L)
        val out = RecentsEngine.collect(listOf(base), now = 9_000L)
        assertEquals(listOf("alpha.txt", "mid.txt", "zeta.txt"), out.map { it.name })
    }

    @Test
    fun `respects limit keeping newest`() {
        val base = tmp.newFolder("root")
        for (i in 1..10) touch(base, "f$i.txt", i * 1_000L)
        val out = RecentsEngine.collect(listOf(base), now = 100_000L, limit = 3)
        assertEquals(3, out.size)
        assertEquals("f10.txt", out[0].name)
        assertEquals("f8.txt", out[2].name)
    }

    @Test
    fun `skips hidden files and directories`() {
        val base = tmp.newFolder("root")
        touch(base, "visible.txt", 2_000L)
        touch(base, ".hidden.txt", 9_000L)
        touch(base, ".secret/inside.txt", 9_000L)
        val out = RecentsEngine.collect(listOf(base), now = 10_000L)
        assertEquals(listOf("visible.txt"), out.map { it.name })
    }

    @Test
    fun `skips system excluded directories`() {
        val base = tmp.newFolder("root")
        touch(base, "keep.txt", 1_000L)
        touch(base, "Android/blob.bin", 9_000L)
        touch(base, ".NovaFilesTrash/old.bin", 9_000L)
        touch(base, "LOST.DIR/junk", 9_000L)
        val out = RecentsEngine.collect(listOf(base), now = 10_000L)
        assertEquals(listOf("keep.txt"), out.map { it.name })
    }

    @Test
    fun `extra excludeDirs merged with system set`() {
        val base = tmp.newFolder("root")
        touch(base, "keep.txt", 1_000L)
        touch(base, "Private/notes.txt", 9_000L)
        val out = RecentsEngine.collect(listOf(base), now = 10_000L, excludeDirs = setOf("Private"))
        assertEquals(listOf("keep.txt"), out.map { it.name })
    }

    @Test
    fun `maxDepth limits traversal - depth 1 means only direct children`() {
        val base = tmp.newFolder("root")
        touch(base, "top.txt", 3_000L)
        touch(base, "lvl1/inner.txt", 9_000L)
        val out = RecentsEngine.collect(listOf(base), now = 10_000L, maxDepth = 1)
        assertEquals(listOf("top.txt"), out.map { it.name })
    }

    @Test
    fun `depth 2 includes one nested level`() {
        val base = tmp.newFolder("root")
        touch(base, "top.txt", 3_000L)
        touch(base, "lvl1/inner.txt", 9_000L)
        touch(base, "lvl1/lvl2/deep.txt", 9_500L)
        val out = RecentsEngine.collect(listOf(base), now = 10_000L, maxDepth = 2)
        assertEquals(listOf("inner.txt", "top.txt"), out.map { it.name })
    }

    @Test
    fun `multiple roots merged into one sorted list`() {
        val r1 = tmp.newFolder("r1")
        val r2 = tmp.newFolder("r2")
        touch(r1, "old.txt", 1_000L)
        touch(r2, "new.txt", 8_000L)
        val out = RecentsEngine.collect(listOf(r1, r2), now = 10_000L)
        assertEquals(listOf("new.txt", "old.txt"), out.map { it.name })
    }

    @Test
    fun `directories never appear in results`() {
        val base = tmp.newFolder("root")
        tmp.newFolder("root", "somedir")
        touch(base, "file.txt", 1_000L)
        val out = RecentsEngine.collect(listOf(base), now = 10_000L)
        assertTrue(out.all { !File(it.path).isDirectory || it.name == "file.txt" })
        assertEquals(1, out.size)
    }

    @Test
    fun `snapshot carries size and modifiedAt`() {
        val base = tmp.newFolder("root")
        touch(base, "sized.bin", 4_000L, size = 123)
        val out = RecentsEngine.collect(listOf(base), now = 10_000L)
        assertEquals(1, out.size)
        assertEquals(123L, out[0].size)
        assertEquals(4_000L, out[0].modifiedAt)
        assertEquals(File(base, "sized.bin").absolutePath, out[0].path)
    }

    @Test
    fun `empty root yields empty list`() {
        val base = tmp.newFolder("empty")
        assertTrue(RecentsEngine.collect(listOf(base), now = 10_000L).isEmpty())
    }

    @Test
    fun `unreadable directory is skipped silently`() {
        val missing = File(tmp.root, "does-not-exist")
        val base = tmp.newFolder("root")
        touch(base, "f.txt", 1_000L)
        val out = RecentsEngine.collect(listOf(missing, base), now = 10_000L)
        assertEquals(1, out.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero limit rejected`() {
        RecentsEngine.collect(listOf(tmp.root), now = 1L, limit = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero depth rejected`() {
        RecentsEngine.collect(listOf(tmp.root), now = 1L, maxDepth = 0)
    }

    // ═══════════ RelativeTime.of ═══════════

    private val NOW = 1_000_000_000L

    @Test
    fun `under a minute is Now`() {
        assertEquals(RelativeTime.Label.Now, RelativeTime.of(NOW, NOW - 59_999))
        assertEquals(RelativeTime.Label.Now, RelativeTime.of(NOW, NOW))
    }

    @Test
    fun `future timestamp clamps to Now`() {
        assertEquals(RelativeTime.Label.Now, RelativeTime.of(NOW, NOW + 500_000))
    }

    @Test
    fun `one minute is Minutes(1)`() {
        assertEquals(RelativeTime.Label.Minutes(1), RelativeTime.of(NOW, NOW - RelativeTime.MINUTE_MS))
    }

    @Test
    fun `59 minutes stays Minutes`() {
        assertEquals(RelativeTime.Label.Minutes(59), RelativeTime.of(NOW, NOW - 59 * RelativeTime.MINUTE_MS))
    }

    @Test
    fun `one hour is Hours(1)`() {
        assertEquals(RelativeTime.Label.Hours(1), RelativeTime.of(NOW, NOW - RelativeTime.HOUR_MS))
    }

    @Test
    fun `23 hours stays Hours`() {
        assertEquals(RelativeTime.Label.Hours(23), RelativeTime.of(NOW, NOW - 23 * RelativeTime.HOUR_MS))
    }

    @Test
    fun `24 to 47 hours is Yesterday`() {
        assertEquals(RelativeTime.Label.Yesterday, RelativeTime.of(NOW, NOW - RelativeTime.DAY_MS))
        assertEquals(RelativeTime.Label.Yesterday, RelativeTime.of(NOW, NOW - 47 * RelativeTime.HOUR_MS))
    }

    @Test
    fun `2 to 6 days is Days(n)`() {
        assertEquals(RelativeTime.Label.Days(2), RelativeTime.of(NOW, NOW - 2 * RelativeTime.DAY_MS))
        assertEquals(RelativeTime.Label.Days(6), RelativeTime.of(NOW, NOW - 6 * RelativeTime.DAY_MS))
    }

    @Test
    fun `7 days and beyond is Older`() {
        assertEquals(RelativeTime.Label.Older, RelativeTime.of(NOW, NOW - 7 * RelativeTime.DAY_MS))
        assertEquals(RelativeTime.Label.Older, RelativeTime.of(NOW, NOW - 400 * RelativeTime.DAY_MS))
    }
}
