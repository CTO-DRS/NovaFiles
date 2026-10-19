package com.novafiles.app.core.analyzer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** اختبارات كاشف التكرارات — الأنبوب الثلاثي و dHash والحدود */
class DuplicateEngineTest {

    private lateinit var tmp: File

    @org.junit.Before
    fun setup() {
        tmp = createTempDir("dups")
    }

    @org.junit.After
    fun teardown() {
        tmp.deleteRecursively()
    }

    private fun write(name: String, bytes: ByteArray): File = File(tmp, name).apply { writeBytes(bytes) }

    // ═══════════════ التكرارات الدقيقة ═══════════════

    @Test
    fun `identical files grouped as exact duplicates`() = runBlocking {
        val payload = ByteArray(10_000) { (it % 251).toByte() }
        val a = write("a.bin", payload)
        val b = write("b.bin", payload)
        val c = write("c.bin", payload)
        val report = DuplicateEngine().scan(listOf(a.path, b.path, c.path))
        assertEquals(1, report.groups.size)
        val g = report.groups[0]
        assertTrue(g.exact)
        assertEquals(3, g.items.size)
        assertEquals(10_000L * 2, g.wastedBytes)
        // كل العناصر بنفس الهاش
        assertEquals(1, g.items.map { it.hash }.toSet().size)
    }

    @Test
    fun `same size different content not grouped`() = runBlocking {
        val a = write("x.bin", ByteArray(5000) { 1 })
        val b = write("y.bin", ByteArray(5000) { 2 })
        val report = DuplicateEngine().scan(listOf(a.path, b.path))
        assertTrue("المحتوى المختلف يجب ألا يتجمع", report.groups.isEmpty())
        assertEquals(0L, report.totalWastedBytes)
    }

    @Test
    fun `partial hash separates files differing only in tail`() = runBlocking {
        // نفس الرأس (8KB أصفار) وذيل مختلف — يفشل الترشيح الجزئي ويصححه MD5
        val head = ByteArray(8192) { 7 }
        val a = write("tail1.bin", head + ByteArray(2048) { 1 })
        val b = write("tail2.bin", head + ByteArray(2048) { 2 })
        val report = DuplicateEngine().scan(listOf(a.path, b.path))
        assertTrue(report.groups.isEmpty())
    }

    @Test
    fun `unique files produce no groups`() = runBlocking {
        val files = (1..5).map { write("u$it.bin", ByteArray(it * 100) { it.toByte() }) }
        val report = DuplicateEngine().scan(files.map { it.path })
        assertTrue(report.groups.isEmpty())
        assertEquals(5, report.scannedFiles)
    }

    @Test
    fun `zero-byte and missing files are ignored`() = runBlocking {
        val empty = write("empty.bin", ByteArray(0))
        val report = DuplicateEngine().scan(listOf(empty.path, "/nonexistent/x.bin"))
        assertEquals(0, report.scannedFiles)
    }

    @Test
    fun `minSize option filters small files`() = runBlocking {
        val payload = ByteArray(100) { 9 }
        val a = write("s1.bin", payload)
        val b = write("s2.bin", payload)
        val report = DuplicateEngine().scan(listOf(a.path, b.path), DuplicateOptions(minSizeBytes = 1000L))
        assertTrue(report.groups.isEmpty())
    }

    @Test
    fun `groups sorted by wasted bytes desc`() = runBlocking {
        val big = ByteArray(20_000) { 1 }
        val small = ByteArray(2_000) { 1 }
        val files = listOf(
            write("b1.bin", big), write("b2.bin", big),
            write("s1.bin", small), write("s2.bin", small),
        )
        val report = DuplicateEngine().scan(files.map { it.path })
        assertEquals(2, report.groups.size)
        assertTrue(report.groups[0].wastedBytes >= report.groups[1].wastedBytes)
        assertEquals(20_000L, report.groups[0].wastedBytes)
    }

    @Test
    fun `progress callback fires`() = runBlocking {
        val payload = ByteArray(1000) { 3 }
        val files = (1..4).map { write("p$it.bin", payload) }
        var calls = 0
        DuplicateEngine().scan(files.map { it.path }, onProgress = { calls++ })
        assertTrue("لم يُطلق onProgress", calls > 0)
    }

    // ═══════════════ dHash الإدراكي ═══════════════

    @Test
    fun `dHash is stable for identical pixels`() {
        val gray = IntArray(72) { (it * 3) % 256 }
        assertEquals(DuplicateEngine.dHash(gray), DuplicateEngine.dHash(gray.copyOf()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dHash rejects wrong pixel count`() {
        DuplicateEngine.dHash(IntArray(64))
    }

    @Test
    fun `hamming distance behaves`() {
        assertEquals(0, DuplicateEngine.hamming(0L, 0L))
        assertEquals(64, DuplicateEngine.hamming(0L, -1L))
        assertEquals(1, DuplicateEngine.hamming(0b1000L, 0b1001L))
    }

    @Test
    fun `near-duplicate images cluster with decoder`() = runBlocking {
        // صورتان بتدرج رمادي متشابه جداً + واحدة مختلفة تماماً
        fun gradient(offset: Int): IntArray = IntArray(72) { ((it * 4 + offset) % 256) }
        val simA = gradient(0)
        val simB = gradient(1)   // فرق طفيف — dHash متطابق غالباً
        val diff = IntArray(72) { if (it % 2 == 0) 255 else 0 } // نمط متضاد

        val decoder = ImageDecoder { key ->
            when {
                key.name.contains("a") -> simA
                key.name.contains("b") -> simB
                key.name.contains("z") -> diff
                else -> null
            }
        }
        val fa = write("a.png", ByteArray(10))
        val fb = write("b.png", ByteArray(10))
        val fz = write("z.png", ByteArray(10))
        val report = DuplicateEngine(decoder)
            .scan(listOf(fa.path, fb.path, fz.path), DuplicateOptions(nearDuplicateImages = true))
        assertTrue(report.groups.any { !it.exact })
        val near = report.groups.first { !it.exact }
        assertEquals(setOf("a.png", "b.png"), near.items.map { it.path.substringAfterLast('/') }.toSet())
    }
}
