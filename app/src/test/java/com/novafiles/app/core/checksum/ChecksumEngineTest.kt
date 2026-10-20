package com.novafiles.app.core.checksum

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** اختبارات محرك البصمات — متجهات معروفة، تقدم، إلغاء، وجمع حتمي */
class ChecksumEngineTest {

    private val engine = ChecksumEngine()

    @org.junit.Before
    fun setup() {
        dir = createTempDir("hash")
    }

    @org.junit.After
    fun teardown() {
        dir.deleteRecursively()
    }

    private lateinit var dir: File

    private fun file(name: String, content: String): File =
        File(dir, name).apply { writeText(content) }

    // ═══════════════ متجهات معروفة ═══════════════

    @Test
    fun `md5 known vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", engine.hashFile(file("empty.txt", ""), ChecksumAlgorithm.MD5)!!.hex)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", engine.hashFile(file("abc.txt", "abc"), ChecksumAlgorithm.MD5)!!.hex)
        assertEquals(
            "9e107d9d372bb6826bd81d3542a419d6",
            engine.hashFile(file("fox.txt", "The quick brown fox jumps over the lazy dog"), ChecksumAlgorithm.MD5)!!.hex,
        )
    }

    @Test
    fun `sha1 known vectors`() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", engine.hashFile(file("empty.txt", ""), ChecksumAlgorithm.SHA1)!!.hex)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", engine.hashFile(file("abc.txt", "abc"), ChecksumAlgorithm.SHA1)!!.hex)
    }

    @Test
    fun `sha256 known vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            engine.hashFile(file("empty.txt", ""), ChecksumAlgorithm.SHA256)!!.hex,
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            engine.hashFile(file("abc.txt", "abc"), ChecksumAlgorithm.SHA256)!!.hex,
        )
    }

    @Test
    fun `hex output lowercase with correct length`() {
        val h = engine.hashFile(file("f.txt", "data"), ChecksumAlgorithm.SHA256)!!
        val hex = h.hex!!
        assertEquals(64, hex.length)
        assertEquals(hex, hex.lowercase())
        assertTrue(hex.all { it in "0123456789abcdef" })
    }

    // ═══════════════ حالات الفشل ═══════════════

    @Test
    fun `missing file returns null`() {
        assertNull(engine.hashFile(File(dir, "nope.bin"), ChecksumAlgorithm.MD5))
    }

    @Test
    fun `directory returns null`() {
        assertNull(engine.hashFile(dir, ChecksumAlgorithm.MD5))
    }

    @Test
    fun `batch order preserved with failures`() = runBlocking {
        val a = file("a.txt", "alpha")
        val b = file("b.txt", "beta")
        val res = engine.hashFiles(listOf(a, File(dir, "ghost.txt"), b), ChecksumAlgorithm.MD5)
        assertEquals(3, res.size)
        assertEquals(a, res[0]!!.file)
        assertNull(res[1])
        assertEquals(b, res[2]!!.file)
    }

    // ═══════════════ التقدم والإلغاء ═══════════════

    @Test
    fun `progress reports count and bytes`() = runBlocking {
        val a = file("a.txt", "12345") // 5 بايت
        val b = file("b.txt", "1234567890") // 10 بايت
        val seen = ArrayList<Pair<Int, Long>>()
        engine.hashFiles(listOf(a, b), ChecksumAlgorithm.MD5, onProgress = { d, by -> seen.add(d to by) })
        assertEquals(listOf(1 to 5L, 2 to 15L), seen)
    }

    @Test
    fun `cancellation stops between files`() = runBlocking {
        val files = (1..5).map { file("c$it.txt", "content$it") }
        var checks = 0
        // يُفحص الإلغاء قبل كل ملف: أول 3 فحوصات تمر، الرابع يوقف قبل الملف الرابع
        val res = engine.hashFiles(files, ChecksumAlgorithm.MD5, isCancelled = { ++checks > 3 })
        assertEquals(3, res.size)
        assertEquals(3, res.filterNotNull().size)
    }

    // ═══════════════ التدفق الكبير ═══════════════

    @Test
    fun `large file streaming matches direct digest`() {
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val f = File(dir, "big.bin").apply { writeBytes(big) }
        val expected = MessageDigest.getInstance("MD5").digest(big).let {
            ChecksumEngine.run { it.toHex() }
        }
        assertEquals(expected, engine.hashFile(f, ChecksumAlgorithm.MD5)!!.hex)
    }

    @Test
    fun `tiny buffer still correct on large file`() {
        val tinyEngine = ChecksumEngine(bufferSize = 1024)
        val big = ByteArray(50_000) { (it and 0x7F).toByte() }
        val f = File(dir, "mid.bin").apply { writeBytes(big) }
        val expected = MessageDigest.getInstance("SHA-256").digest(big).let {
            ChecksumEngine.run { it.toHex() }
        }
        assertEquals(expected, tinyEngine.hashFile(f, ChecksumAlgorithm.SHA256)!!.hex)
    }

    // ═══════════════ جمع المجلدات ═══════════════

    @Test
    fun `collect first level sorted hidden excluded`() {
        file("b.txt", "1")
        file("A.txt", "2")
        File(dir, ".secret").writeText("h")
        val sub = File(dir, "sub").apply { mkdirs() }
        File(sub, "inner.txt").writeText("i")
        val files = engine.collectFiles(dir, recursive = false)
        assertEquals(listOf("a.txt", "b.txt"), files.map { it.name.lowercase() })
        assertTrue(files.none { it.name == ".secret" || it.name == "inner.txt" })
    }

    @Test
    fun `collect recursive traverses all sorted`() {
        file("z.txt", "1")
        val sub = File(dir, "sub").apply { mkdirs() }
        val deep = File(sub, "deep").apply { mkdirs() }
        File(sub, "m.txt").writeText("2")
        File(deep, "k.txt").writeText("3")
        val files = engine.collectFiles(dir, recursive = true)
        assertEquals(listOf("z.txt", "m.txt", "k.txt"), files.map { it.name })
    }

    @Test
    fun `collect on non directory returns empty`() {
        assertTrue(engine.collectFiles(file("f.txt", "x"), recursive = true).isEmpty())
        assertTrue(engine.collectFiles(dir, recursive = true).isNotEmpty())
    }

    // ═══════════════ أدوات مساعدة ═══════════════

    @Test
    fun `algorithm detection by hex length`() {
        assertEquals(ChecksumAlgorithm.MD5, ChecksumAlgorithm.byHexLength("a".repeat(32)))
        assertEquals(ChecksumAlgorithm.SHA1, ChecksumAlgorithm.byHexLength("a".repeat(40)))
        assertEquals(ChecksumAlgorithm.SHA256, ChecksumAlgorithm.byHexLength("a".repeat(64)))
        assertNull(ChecksumAlgorithm.byHexLength("a".repeat(10)))
    }
}
