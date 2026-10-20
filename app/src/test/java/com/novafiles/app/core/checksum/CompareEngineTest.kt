package com.novafiles.app.core.checksum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** اختبارات المقارنة بايت-ببايت — رفض الحجم الفوري وأول إزاحة اختلاف بالسداسي */
class CompareEngineTest {

    private lateinit var dir: File

    @org.junit.Before
    fun setup() {
        dir = createTempDir("cmp")
    }

    @org.junit.After
    fun teardown() {
        dir.deleteRecursively()
    }

    private fun file(name: String, bytes: ByteArray): File =
        File(dir, name).apply { writeBytes(bytes) }

    private fun pattern(size: Int, seed: Int = 7): ByteArray =
        ByteArray(size) { ((it * seed + it / 97) and 0xFF).toByte() }

    // ═══════════════ التطابق ═══════════════

    @Test
    fun `identical files are same`() {
        val a = file("a.bin", pattern(10_000))
        val b = file("b.bin", pattern(10_000))
        assertEquals(CompareResult.Same, CompareEngine.compare(a, b))
    }

    @Test
    fun `empty files are same`() {
        val a = file("e1", ByteArray(0))
        val b = file("e2", ByteArray(0))
        assertEquals(CompareResult.Same, CompareEngine.compare(a, b))
    }

    @Test
    fun `fingerprint not name - same content different extension`() {
        val a = file("photo.jpg", pattern(5_000))
        val b = file("copy.dat", pattern(5_000))
        assertEquals(CompareResult.Same, CompareEngine.compare(a, b))
    }

    // ═══════════════ اختلاف الحجم — رفض فوري ═══════════════

    @Test
    fun `size difference rejected instantly with sizes`() {
        val a = file("a.bin", pattern(100))
        val b = file("b.bin", pattern(250))
        val r = CompareEngine.compare(a, b) as CompareResult.DifferentSize
        assertEquals(100L, r.sizeA)
        assertEquals(250L, r.sizeB)
    }

    @Test
    fun `empty versus non empty is size difference`() {
        val a = file("empty", ByteArray(0))
        val b = file("full", byteArrayOf(1))
        assertTrue(CompareEngine.compare(a, b) is CompareResult.DifferentSize)
    }

    // ═══════════════ اختلاف المحتوى ═══════════════

    @Test
    fun `first byte difference at offset zero with hex`() {
        val a = file("a.bin", byteArrayOf(0x00, 0x11, 0x22))
        val b = file("b.bin", byteArrayOf(0x0F, 0x11, 0x22))
        val r = CompareEngine.compare(a, b) as CompareResult.DifferentBytes
        assertEquals(0L, r.firstOffset)
        assertEquals(0x00, r.byteA)
        assertEquals(0x0F, r.byteB)
        assertEquals("00", r.hexA)
        assertEquals("0f", r.hexB)
    }

    @Test
    fun `difference in the middle reports absolute offset`() {
        val pa = pattern(64_000)
        val pb = pa.copyOf().also { it[63_999] = (it[63_999].toInt() xor 0x01).toByte() }
        val a = file("a.bin", pa)
        val b = file("b.bin", pb)
        val r = CompareEngine.compare(a, b) as CompareResult.DifferentBytes
        assertEquals(63_999L, r.firstOffset)
    }

    @Test
    fun `difference at last byte detected`() {
        val pa = pattern(10_000)
        val pb = pa.copyOf().also { it[9_999] = 0x55 }
        val a = file("a.bin", pa)
        val b = file("b.bin", pb)
        val r = CompareEngine.compare(a, b) as CompareResult.DifferentBytes
        assertEquals(9_999L, r.firstOffset)
        assertEquals(0x55, r.byteB)
    }

    @Test
    fun `single differing byte near end of large files`() {
        val pa = pattern(300_000)
        val pb = pa.copyOf().also { it[299_500] = 0x7E }
        val a = file("a.bin", pa)
        val b = file("b.bin", pb)
        val r = CompareEngine.compare(a, b) as CompareResult.DifferentBytes
        assertEquals(299_500L, r.firstOffset)
    }

    @Test
    fun `first of many differences wins`() {
        val pa = ByteArray(1_000) { 0x41 } // 'A' كلها
        val pb = pa.copyOf().also {
            it[100] = 0x42
            it[900] = 0x43
        }
        val r = CompareEngine.compare(file("a.bin", pa), file("b.bin", pb)) as CompareResult.DifferentBytes
        assertEquals(100L, r.firstOffset)
    }

    @Test
    fun `tiny buffer same verdict`() {
        val pa = pattern(9_000)
        val pb = pa.copyOf().also { it[8_000] = 0x10 }
        val r = CompareEngine.compare(file("a.bin", pa), file("b.bin", pb), bufferSize = 1024)
            as CompareResult.DifferentBytes
        assertEquals(8_000L, r.firstOffset)
    }

    // ═══════════════ غير قابل للقراءة ═══════════════

    @Test
    fun `missing file unreadable`() {
        val a = file("a.bin", pattern(10))
        assertTrue(CompareEngine.compare(a, File(dir, "ghost.bin")) is CompareResult.Unreadable)
        assertTrue(CompareEngine.compare(File(dir, "ghost.bin"), a) is CompareResult.Unreadable)
    }

    @Test
    fun `directory unreadable`() {
        val a = file("a.bin", pattern(10))
        assertTrue(CompareEngine.compare(a, dir) is CompareResult.Unreadable)
    }
}
