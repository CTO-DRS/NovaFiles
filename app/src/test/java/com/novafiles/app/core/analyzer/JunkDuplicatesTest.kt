package com.novafiles.app.core.analyzer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * اختبارات مكررات المحتوى (v0.29) — بصمة المحتوى لا الاسم عبر الأنواع:
 * حجم ← هاش جزئي رأس/ذيل 4KB ← MD5 كامل، النسخ الزائدة فقط، المرجعية محفوظة.
 */
class JunkDuplicatesTest {

    private lateinit var root: File
    private val now = 1_800_000_000_000L

    @org.junit.Before
    fun setup() {
        root = createTempDir("dups")
    }

    @org.junit.After
    fun teardown() {
        root.deleteRecursively()
    }

    private fun file(path: String, content: ByteArray): File {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        f.writeBytes(content)
        return f
    }

    private fun payload(size: Int, seed: Int = 11): ByteArray =
        ByteArray(size) { ((it * seed + it / 53) and 0xFF).toByte() }

    private fun scan(): JunkReport = runBlocking {
        JunkScanner().scan(root, nowMs = now)
    }

    private fun dups(report: JunkReport) = report.byCategory(JunkCategory.CONTENT_DUPLICATES)

    @Test
    fun `identical contents grouped extras only`() {
        file("x1.txt", payload(1_000))
        file("x2.txt", payload(1_000))
        file("x3.txt", payload(1_000))
        val d = dups(scan())
        assertEquals(2, d.size) // 3 نسخ → نسختان زائدتان فقط
        assertTrue(d.all { it.copies == 3 })
    }

    @Test
    fun `representative smallest path kept`() {
        file("c.bin", payload(500))
        file("a.bin", payload(500))
        file("b.bin", payload(500))
        val extras = dups(scan()).map { it.path.substringAfterLast('/') }.toSet()
        assertEquals(setOf("b.bin", "c.bin"), extras) // a.bin المرجعية محفوظة
    }

    @Test
    fun `same size different content never grouped`() {
        file("p.bin", payload(800, seed = 1))
        file("q.bin", payload(800, seed = 2))
        assertTrue(dups(scan()).isEmpty())
    }

    @Test
    fun `unique sizes never flagged`() {
        file("unique.bin", payload(777))
        file("d1.bin", payload(300))
        file("d2.bin", payload(300))
        val d = dups(scan())
        assertEquals(1, d.size)
        assertTrue(d.single().path.endsWith("d2.bin"))
    }

    @Test
    fun `zero size files excluded`() {
        file("e1.txt", ByteArray(0))
        file("e2.txt", ByteArray(0))
        assertTrue(dups(scan()).isEmpty())
    }

    @Test
    fun `same head different tail filtered by partial hash`() {
        // رأس 4KB متطابق وذيل مختلف — يُسقط في المرحلة الجزئية دون قراءة كاملة
        val head = payload(4 * 1024, seed = 3)
        val tailA = payload(4 * 1024, seed = 4)
        val tailB = payload(4 * 1024, seed = 5)
        file("big_a.bin", head + tailA)
        file("big_b.bin", head + tailB)
        assertTrue(dups(scan()).isEmpty())
    }

    @Test
    fun `cross type duplicates detected by content fingerprint`() {
        file("photo.jpg", payload(2_000))
        file("archive.dat", payload(2_000)) // اسم ونوع مختلفان — المحتوى واحد
        val d = dups(scan())
        assertEquals(1, d.size)
        assertEquals(2, d.single().copies)
    }

    @Test
    fun `other junk categories never double counted`() {
        val old = now - 30L * 86_400_000L
        val j1 = File(root, "same.nfpart").apply { writeBytes(payload(600)); setLastModified(old) }
        val j2 = File(root, "copy.nfpart").apply { writeBytes(payload(600)); setLastModified(old) }
        val report = scan()
        // كلتاهما مصنّفتان بقايا تنزيلات — لا تدخلان مكررات المحتوى
        assertEquals(2, report.byCategory(JunkCategory.LEFTOVER_PARTIALS).size)
        assertTrue(dups(report).isEmpty())
    }

    @Test
    fun `groups sorted by wasted bytes descending`() {
        file("big1.bin", payload(2_000))
        file("big2.bin", payload(2_000)) // هدر 2000
        file("small1.bin", payload(400))
        file("small2.bin", payload(400)) // هدر 400
        val d = dups(scan())
        assertEquals(2, d.size)
        assertEquals(2_000L, d[0].size) // الأكبر هدراً أولاً
        assertEquals(400L, d[1].size)
    }

    @Test
    fun `copies counter reflects full group size`() {
        file("g1.bin", payload(150))
        file("g2.bin", payload(150))
        file("g3.bin", payload(150))
        file("g4.bin", payload(150))
        val d = dups(scan())
        assertEquals(3, d.size)
        assertTrue(d.all { it.copies == 4 }) // ×N = حجم المجموعة كاملة
        assertTrue(d.all { it.size == 150L })
    }
}
