package com.novafiles.app.core.checksum

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** اختبارات ملفات البصمات — توليد GNU coreutils وتحليل متسامح وتحقق حقيقي */
class ChecksumManifestTest {

    private lateinit var dir: File

    @org.junit.Before
    fun setup() {
        dir = createTempDir("manifest")
    }

    @org.junit.After
    fun teardown() {
        dir.deleteRecursively()
    }

    private fun file(name: String, content: String): File =
        File(dir, name).apply { writeText(content) }

    private suspend fun hash(list: List<File>, algo: ChecksumAlgorithm = ChecksumAlgorithm.MD5) =
        ChecksumEngine().hashFiles(list, algo)

    // ═══════════════ التوليد ═══════════════

    @Test
    fun `generate uses two spaces and relative paths`() = runBlocking {
        val a = file("a.txt", "alpha")
        val text = ChecksumManifest.generate(hash(listOf(a)), dir)
        val line = text.trim()
        val hex = line.substringBefore("  ")
        assertEquals("a.txt", line.substringAfter("  "))
        assertEquals(32, hex.length)
        assertTrue(line.matches(Regex("^[0-9a-f]{32}  a\\.txt$")))
    }

    @Test
    fun `generate skips failed results`() = runBlocking {
        val a = file("a.txt", "alpha")
        val res = hash(listOf(a, File(dir, "ghost.bin")))
        val text = ChecksumManifest.generate(res, dir)
        assertEquals(1, text.lines().filter { it.isNotBlank() }.size)
    }

    // ═══════════════ التحليل المتسامح ═══════════════

    @Test
    fun `parse standard text lines`() {
        val p = ChecksumManifest.parse("abc123  one.txt\ndef456  two.txt\n")
        assertEquals(2, p.entries.size)
        assertEquals("abc123", p.entries[0].hash)
        assertEquals("one.txt", p.entries[0].path)
        assertFalse(p.entries[0].binaryMarker)
        assertTrue(p.badLines.isEmpty())
    }

    @Test
    fun `parse tolerates comments and blank lines`() {
        val p = ChecksumManifest.parse("# comment\n\n   \naaa  x.txt\n# another\n")
        assertEquals(1, p.entries.size)
        assertEquals("x.txt", p.entries[0].path)
    }

    @Test
    fun `parse tolerates CRLF endings`() {
        val p = ChecksumManifest.parse("aaa  x.txt\r\nbbb  y.txt\r\n")
        assertEquals(2, p.entries.size)
        assertEquals("y.txt", p.entries[1].path)
    }

    @Test
    fun `parse tolerates binary marker and mixed case`() {
        val p = ChecksumManifest.parse("AABBCC *bin.dat\nDDEEFF  txt.txt\n")
        assertEquals("bin.dat", p.entries[0].path)
        assertTrue(p.entries[0].binaryMarker)
        assertEquals("aabbcc", p.entries[0].hash) // يُطبَّع صغيراً
        assertFalse(p.entries[1].binaryMarker)
    }

    @Test
    fun `parse single space variant accepted`() {
        val p = ChecksumManifest.parse("aaa b.txt\n")
        assertEquals(1, p.entries.size)
        assertEquals("b.txt", p.entries[0].path)
    }

    @Test
    fun `parse reports bad lines with numbers`() {
        val p = ChecksumManifest.parse("abc123  a.txt\nthis is garbage\n\nnothash  b.txt\n")
        assertEquals(1, p.entries.size)
        assertEquals(2, p.badLines.size)
        assertEquals(2, p.badLines[0].line)
        assertEquals("this is garbage", p.badLines[0].text)
        assertEquals(4, p.badLines[1].line)
    }

    // ═══════════════ التحقق الحقيقي ═══════════════

    @Test
    fun `verify roundtrip all ok`() = runBlocking {
        val a = file("a.txt", "alpha")
        val b = file("b.txt", "beta")
        val text = ChecksumManifest.generate(hash(listOf(a, b)), dir)
        val parsed = ChecksumManifest.parse(text)
        val (outcomes, summary) = ChecksumManifest.verify(parsed, ChecksumAlgorithm.MD5, dir, ChecksumEngine())
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it is VerifyOutcome.Ok })
        assertTrue(summary.allOk)
        assertEquals(2, summary.ok)
        assertEquals(0, summary.mismatch)
        assertEquals(0, summary.missing)
    }

    @Test
    fun `verify detects mismatch with actual hash`() = runBlocking {
        val a = file("a.txt", "original")
        val text = ChecksumManifest.generate(hash(listOf(a)), dir)
        a.writeText("tampered!") // تغيير المحتوى بعد التوليد
        val (outcomes, summary) = ChecksumManifest.verify(ChecksumManifest.parse(text), ChecksumAlgorithm.MD5, dir, ChecksumEngine())
        val mismatch = outcomes.single() as VerifyOutcome.Mismatch
        assertEquals(32, mismatch.actual.length)
        assertEquals(1, summary.mismatch)
        assertFalse(summary.allOk)
    }

    @Test
    fun `verify detects missing file and summary counts`() = runBlocking {
        val a = file("a.txt", "alpha")
        val text = ChecksumManifest.generate(hash(listOf(a)), dir)
        val tampered = text.replace("a.txt", "ghost.txt") // مدخل لملف غير موجود
        val (outcomes, summary) = ChecksumManifest.verify(ChecksumManifest.parse(tampered), ChecksumAlgorithm.MD5, dir, ChecksumEngine())
        assertTrue(outcomes.single() is VerifyOutcome.Missing)
        assertEquals(1, summary.missing)
        assertEquals(1, summary.total)
    }

    // ═══════════════ كشف الخوارزمية ═══════════════

    @Test
    fun `detect algorithm from hash length`() {
        val sha256 = "a".repeat(64) + "  big.bin"
        assertEquals(ChecksumAlgorithm.SHA256, ChecksumManifest.detectAlgorithm(sha256))
        assertNull(ChecksumManifest.detectAlgorithm("garbage text"))
    }
}
