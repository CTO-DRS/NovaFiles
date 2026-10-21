package com.novafiles.app.core.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

/**
 * v0.30 — إضافات الفاصل: تحليل متسامح، أجزاء مفردة، أدلة مختلفة، وأحجام حدية.
 */
class ArchiveSplitterExtrasTest {

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

// ═══════════ v0.30 — إضافات تغطية حافة ═══════════

@Test
fun indexParseAcceptsCrlfAndJunkLines() {
    val text = "NovaSplit v1\r\n# تعليق\r\n\r\n--- ديكور ---\r\noriginal=ok.zip\r\nsize=4096\r\nvolume=1024\r\ncount=4\r\nsha256=ff\r\ncreated=1\r\n"
    val idx = SplitIndex.parse(text)
    assertEquals("ok.zip", idx.originalName)
    assertEquals(4096L, idx.originalSize)
    assertEquals(4, idx.volumeCount)
}

@Test
fun joinFromFirstVolumeSinglePartWorks() {
    val src = makeFile("single.zip", 700)
    ArchiveSplitter.split(src, 100 * 1024L)
    assertTrue(File(tmp.root, "single.zip.nvasplit").delete())
    val joined = ArchiveSplitter.joinFromFirstVolume(File(tmp.root, "single.zip.001"), tmp.newFolder("s-out"))
    assertEquals(700L, joined.length())
}

@Test
fun splitThenJoinIntoDifferentDirectory() {
    val src = makeFile("mv.zip", 5 * 1024)
    val sha = com.novafiles.app.core.checksum.ChecksumEngine()
        .hashFile(src, com.novafiles.app.core.checksum.ChecksumAlgorithm.SHA256)!!.hex
    ArchiveSplitter.split(src, 2 * 1024L)
    val elsewhere = tmp.newFolder("elsewhere")
    val joined = ArchiveSplitter.join(File(tmp.root, "mv.zip.nvasplit"), elsewhere)
    assertEquals(File(elsewhere, "mv.zip"), joined)
    assertEquals(sha, com.novafiles.app.core.checksum.ChecksumEngine()
        .hashFile(joined, com.novafiles.app.core.checksum.ChecksumAlgorithm.SHA256)!!.hex)
}

@Test
fun splitVolumeExactFileSizeYieldsOnePart() {
    val src = makeFile("exact.bin", 4096)
    val parts = ArchiveSplitter.split(src, 4096L)
    assertEquals(1, parts.size)
    assertEquals(4096L, parts[0].length())
}

@Test
fun splitIndexRecordsOriginalNameVerbatim() {
    val src = makeFile("اسم-عربي.tar.gz", 2048)
    ArchiveSplitter.split(src, 1024L)
    val idx = SplitIndex.parse(File(tmp.root, "اسم-عربي.tar.gz.nvasplit").readText())
    assertEquals("اسم-عربي.tar.gz", idx.originalName)
    assertEquals(2, idx.volumeCount)
}
}
