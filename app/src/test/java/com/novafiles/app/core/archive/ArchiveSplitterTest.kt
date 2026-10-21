package com.novafiles.app.core.archive

import com.novafiles.app.core.checksum.CompareEngine
import com.novafiles.app.core.checksum.CompareResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * v0.30.0 — اختبارات فاصل/مُجمِّع الأحجام الموحد.
 * تغطي: التقسيم بأحجام دقيقة، الفهرس، التجميع بالفهرس وبدونه،
 * فشل التلاعب/الغياب/الحجم، والحالات الحدية.
 */
class ArchiveSplitterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeFile(name: String, size: Int, fill: Int = 7): File {
        val f = File(tmp.root, name)
        FileOutputStream(f).use { out ->
            val buf = ByteArray(64 * 1024) { i -> (i + fill).toByte() }
            var left = size
            while (left > 0) {
                val n = minOf(buf.size, left)
                out.write(buf, 0, n)
                left -= n
            }
        }
        return f
    }

    // ═══ الفهرس: تسلسل وتحليل متسامح ═══

    @Test
    fun indexSerializeParseRoundTrip() {
        val idx = SplitIndex("a.zip", 123456L, 65536L, 2, "ab".repeat(32), 1700000000000L)
        val parsed = SplitIndex.parse(idx.serialize())
        assertEquals(idx, parsed)
    }

    @Test
    fun indexParseToleratesCommentsBlankLinesAndWhitespace() {
        val text = """
            # NovaSplit — فهرس إعادة تجميع

            original = file.7z
            size=2048
            volume = 1024

            count = 2
            sha256=abcd
            created=123
        """.trimIndent()
        val idx = SplitIndex.parse(text)
        assertEquals("file.7z", idx.originalName)
        assertEquals(2048L, idx.originalSize)
        assertEquals(1024L, idx.volumeSize)
        assertEquals(2, idx.volumeCount)
        assertEquals("abcd", idx.sha256)
    }

    @Test
    fun indexParseRejectsMissingEssentialKeys() {
        val cases = listOf(
            "NovaSplit v1\nsize=10\nvolume=1024\ncount=1\n",      // original مفقود
            "original=a.zip\nvolume=1024\ncount=1\n",             // size مفقود
            "original=a.zip\nsize=10\ncount=1\n",                 // volume مفقود
            "original=a.zip\nsize=10\nvolume=1024\n",             // count مفقود
        )
        for (c in cases) {
            try {
                SplitIndex.parse(c)
                fail("كان يجب رفض: $c")
            } catch (_: IllegalArgumentException) {
                // متوقع
            }
        }
    }

    @Test
    fun indexParseRejectsTinyVolumeFromTamperedIndex() {
        try {
            SplitIndex.parse("original=a.zip\nsize=10\nvolume=512\ncount=1\n")
            fail("حجم جزء أقل من الحد الأدنى يجب أن يُرفض")
        } catch (_: IllegalArgumentException) {
            // متوقع
        }
    }

    // ═══ التقسيم ═══

    @Test
    fun splitProducesExactVolumeCountAndSizes() {
        val src = makeFile("data.zip", 250 * 1024)
        val parts = ArchiveSplitter.split(src, 100 * 1024L)

        assertEquals(3, parts.size)
        assertEquals(100 * 1024L, parts[0].length())
        assertEquals(100 * 1024L, parts[1].length())
        assertEquals(50 * 1024L, parts[2].length())
        assertTrue(parts.all { it.name.startsWith("data.zip.") && !it.name.endsWith(SplitIndex.INDEX_EXT) })
        assertEquals(File(tmp.root, "data.zip.001"), parts[0])
        assertEquals(File(tmp.root, "data.zip.003"), parts[2])
    }

    @Test
    fun splitWritesCompleteIndexFile() {
        val src = makeFile("idx.zip", 3 * 1024)
        ArchiveSplitter.split(src, 1024L)

        val indexFile = File(tmp.root, "idx.zip.nvasplit")
        assertTrue(indexFile.isFile)
        val idx = SplitIndex.parse(indexFile.readText())
        assertEquals("idx.zip", idx.originalName)
        assertEquals(3 * 1024L, idx.originalSize)
        assertEquals(1024L, idx.volumeSize)
        assertEquals(3, idx.volumeCount)
        assertEquals(64, idx.sha256.length)
    }

    @Test
    fun splitWithVolumeLargerThanFileYieldsSinglePart() {
        val src = makeFile("one.zip", 500)
        val parts = ArchiveSplitter.split(src, 100 * 1024L)
        assertEquals(1, parts.size)
        assertEquals(500L, parts[0].length())
    }

    @Test
    fun splitRejectsTooSmallVolumeAndMissingSource() {
        val src = makeFile("x.bin", 2048)
        try {
            ArchiveSplitter.split(src, 512L)
            fail("حجم جزء صغير جداً يجب أن يُرفض")
        } catch (_: IllegalArgumentException) { /* متوقع */ }
        try {
            ArchiveSplitter.split(File(tmp.root, "ghost.bin"), 1024L)
            fail("مصدر مفقود يجب أن يُرفض")
        } catch (_: IllegalArgumentException) { /* متوقع */ }
    }

    @Test
    fun splitProgressReachesTotal() {
        val src = makeFile("p.zip", 5 * 1024)
        var lastDone = -1L
        var lastTotal = -1L
        ArchiveSplitter.split(src, 1024L) { d, t -> lastDone = d; lastTotal = t }
        assertEquals(5 * 1024L, lastDone)
        assertEquals(5 * 1024L, lastTotal)
    }

    // ═══ إعادة التجميع بالفهرس ═══

    @Test
    fun joinFromIndexReassemblesByteIdenticalFile() {
        val src = makeFile("big.zip", 300 * 1024, fill = 3)
        val originalSha = com.novafiles.app.core.checksum.ChecksumEngine()
            .hashFile(src, com.novafiles.app.core.checksum.ChecksumAlgorithm.SHA256)!!.hex

        val parts = ArchiveSplitter.split(src, 120 * 1024L)
        assertTrue("الأصل يبقى قائماً حتى يدويًا", src.exists())

        val destDir = tmp.newFolder("joined")
        val joined = ArchiveSplitter.join(File(tmp.root, "big.zip.nvasplit"), destDir)

        assertEquals("big.zip", joined.name)
        assertEquals(src.length(), joined.length())
        val joinedSha = com.novafiles.app.core.checksum.ChecksumEngine()
            .hashFile(joined, com.novafiles.app.core.checksum.ChecksumAlgorithm.SHA256)!!.hex
        assertEquals(originalSha, joinedSha)
        // تطابق بايت-ببايت عبر محرك v0.29
        assertEquals(CompareResult.Same, CompareEngine.compare(src, joined))
    }

    @Test
    fun joinFailsAndCleansUpOnTamperedVolume() {
        val src = makeFile("tam.zip", 4 * 1024)
        ArchiveSplitter.split(src, 1024L)
        // العبث بالجزء الثاني: استبدال بايت في منتصفه (الحجم ثابت → كشف بالبصمة)
        val part2 = File(tmp.root, "tam.zip.002")
        val bytes = part2.readBytes().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        part2.writeBytes(bytes)

        try {
            val destDir = tmp.newFolder("t-out")
            ArchiveSplitter.join(File(tmp.root, "tam.zip.nvasplit"), destDir)
            fail("التلاعب يجب أن يكشفه تحقق البصمة")
        } catch (e: SplitException) {
            assertEquals("split_hash_mismatch", e.code)
        }
    }

    @Test
    fun joinFailsOnMissingVolume() {
        val src = makeFile("miss.zip", 3 * 1024)
        ArchiveSplitter.split(src, 1024L)
        assertTrue(File(tmp.root, "miss.zip.002").delete())

        try {
            ArchiveSplitter.join(File(tmp.root, "miss.zip.nvasplit"), tmp.newFolder("m-out"))
            fail("جزء مفقود يجب أن يفشل التجميع")
        } catch (e: SplitException) {
            assertEquals("split_part_missing", e.code)
        }
    }

    @Test
    fun joinFailsOnTamperedSizeInIndex() {
        val src = makeFile("sz.zip", 2 * 1024)
        ArchiveSplitter.split(src, 1024L)
        val idx = SplitIndex.parse(File(tmp.root, "sz.zip.nvasplit").readText())
        File(tmp.root, "sz.zip.nvasplit").writeText(idx.copy(originalSize = 99999L).serialize())

        try {
            ArchiveSplitter.join(File(tmp.root, "sz.zip.nvasplit"), tmp.newFolder("sz-out"))
            fail("حجم مفهرس خاطئ يجب أن يفشل")
        } catch (e: SplitException) {
            assertEquals("split_size_mismatch", e.code)
        }
    }

    // ═══ إعادة التجميع بلا فهرس ═══

    @Test
    fun joinFromFirstVolumeWorksWithoutIndex() {
        val src = makeFile("noix.zip", 6 * 1024, fill = 11)
        val sha = com.novafiles.app.core.checksum.ChecksumEngine()
            .hashFile(src, com.novafiles.app.core.checksum.ChecksumAlgorithm.SHA256)!!.hex

        ArchiveSplitter.split(src, 2 * 1024L)
        // احذف الفهرس عمداً — المستخدم لديه الأجزاء فقط
        assertTrue(File(tmp.root, "noix.zip.nvasplit").delete())

        val joined = ArchiveSplitter.joinFromFirstVolume(File(tmp.root, "noix.zip.001"), tmp.newFolder("n-out"))
        val joinedSha = com.novafiles.app.core.checksum.ChecksumEngine()
            .hashFile(joined, com.novafiles.app.core.checksum.ChecksumAlgorithm.SHA256)!!.hex
        assertEquals(sha, joinedSha)
    }

    @Test
    fun joinFromFirstVolumeUsesIndexWhenPresent() {
        val src = makeFile("withix.zip", 3 * 1024)
        ArchiveSplitter.split(src, 1024L)
        val joined = ArchiveSplitter.joinFromFirstVolume(File(tmp.root, "withix.zip.001"), tmp.newFolder("w-out"))
        assertEquals(src.length(), joined.length())
    }

    @Test
    fun joinFromNonVolumeNameRejected() {
        try {
            ArchiveSplitter.joinFromFirstVolume(makeFile("plain.txt", 10), tmp.newFolder("plain-out"))
            fail("اسم بلا رقم جزء يجب أن يُرفض")
        } catch (e: SplitException) {
            assertEquals("split_not_volume", e.code)
        }
    }

    // ═══ تكامل كامل: إنشاء ← تقسيم ← تجميع ← استخراج ═══

    @Test
    fun fullCycleCreateSplitJoinExtract() {
        val engine = ArchiveCreateEngine()
        val src = tmp.newFolder("cycle-src")
        val arabic = File(src, "مجلد عربي").apply { mkdirs() }
        FileOutputStream(File(arabic, "بيانات.txt")).use { it.write("محتوى للدورة الكاملة".repeat(2000).toByteArray()) }

        val result = engine.create(
            CreateSpec(
                sources = listOf(src),
                out = File(tmp.root, "cycle.7z"),
                format = CreateFormat.SEVEN_Z,
                level = CreateLevel.BALANCED,
                splitBytes = 40 * 1024L,
            ),
        )
        // بعد التقسيم: الأرشيف الكامل حُذف والأجزاء + الفهرس قائمة
        assertTrue("الأرشيف الكامل يجب أن يُحذف بعد التقسيم", result.wholeRemovedAfterSplit)
        assertTrue(result.volumes.isNotEmpty())
        assertNotNull(result.volumeIndex)

        val joined = ArchiveSplitter.join(result.volumeIndex!!, tmp.newFolder("cycle-joined"))
        assertEquals(result.sha256, com.novafiles.app.core.checksum.ChecksumEngine()
            .hashFile(joined, com.novafiles.app.core.checksum.ChecksumAlgorithm.SHA256)!!.hex)

        val dest = tmp.newFolder("cycle-out")
        assertEquals(1, CommonArchive.extractAll(joined, dest))
        assertEquals("محتوى للدورة الكاملة".repeat(2000), File(dest, "cycle-src/مجلد عربي/بيانات.txt").readText())
    }
}
