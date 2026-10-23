package com.novafiles.core.report

import com.novafiles.app.core.analyzer.CategorySummary
import com.novafiles.app.core.analyzer.DupItem
import com.novafiles.app.core.analyzer.DuplicateGroup
import com.novafiles.app.core.analyzer.DuplicateReport
import com.novafiles.app.core.analyzer.DirNode
import com.novafiles.app.core.analyzer.JunkCategory
import com.novafiles.app.core.analyzer.JunkItem
import com.novafiles.app.core.analyzer.JunkReport
import com.novafiles.app.core.analyzer.NotableFile
import com.novafiles.app.core.analyzer.StorageCategory
import com.novafiles.app.core.analyzer.StorageReport
import com.novafiles.app.core.cloud.MiniJson
import com.novafiles.app.core.report.CsvWriter
import com.novafiles.app.core.report.ReportExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات مصدّر التقارير — حتمية كاملة: نفس الإدخال → نفس البايتات.
 * يغطي: التخزين CSV/JSON، الأدلة، البارزة، المكررات، المخلفات، قبل/بعد، الأسماء.
 */
class ReportExporterTest {

    private fun sampleStorage() = StorageReport(
        categories = listOf(
            CategorySummary(StorageCategory.VIDEO, fileCount = 12, totalBytes = 5000),
            CategorySummary(StorageCategory.IMAGES, fileCount = 40, totalBytes = 2000),
            CategorySummary(StorageCategory.OTHER, fileCount = 3, totalBytes = 100),
        ),
        totalFiles = 55, totalDirs = 8, usedBytes = 7100,
    )

    // ═══════════ التخزين ═══════════

    @Test
    fun `storage csv sorted by size desc with total row`() {
        val csv = ReportExporter.storageCsv(sampleStorage())
        val lines = csv.split("\r\n")
        assertEquals("category,files,bytes", lines[0].removePrefix(CsvWriter.BOM))
        assertEquals("video,12,5000", lines[1])
        assertEquals("images,40,2000", lines[2])
        assertEquals("other,3,100", lines[3])
        assertEquals("total,55,7100", lines[4])
    }

    @Test
    fun `storage json parses back with MiniJson and keeps values`() {
        val json = ReportExporter.storageJson(sampleStorage())
        val obj = MiniJson.parseObject(json)
        assertEquals("storage", MiniJson.asString(obj["nvreport"]))
        assertEquals(55L, MiniJson.asLong(obj["totalFiles"]))
        assertEquals(7100L, MiniJson.asLong(obj["usedBytes"]))
        val cats = MiniJson.asArray(obj["categories"]!!)
        assertEquals(3, cats.size)
        val first = MiniJson.asObject(cats[0])
        assertEquals("video", MiniJson.asString(first["category"]))
        assertEquals(5000L, MiniJson.asLong(first["bytes"]))
    }

    @Test
    fun `storage outputs are deterministic`() {
        val a = ReportExporter.storageCsv(sampleStorage())
        val b = ReportExporter.storageCsv(sampleStorage())
        assertEquals(a, b)
    }

    // ═══════════ الأدلة والبارزة ═══════════

    @Test
    fun `dirs csv sorted by subtree desc then path`() {
        val csv = ReportExporter.dirsCsv(listOf(
            DirNode("/b", "b", 100, 0, 1, 2),
            DirNode("/a", "a", 500, 10, 3, 4),
            DirNode("/c", "c", 500, 20, 0, 1),
        ))
        val lines = csv.split("\r\n")
        assertEquals("path,subtree_bytes,direct_file_bytes,child_dirs,file_count", lines[0].removePrefix(CsvWriter.BOM))
        assertEquals("/a,500,10,3,4", lines[1]) // نفس الحجم → مسار تصاعدي
        assertEquals("/c,500,20,0,1", lines[2])
        assertEquals("/b,100,0,1,2", lines[3])
    }

    @Test
    fun `notable csv sorted by size desc`() {
        val csv = ReportExporter.notableCsv(listOf(
            NotableFile("/small", "small", 10, 100),
            NotableFile("/big", "big", 999, 200),
        ))
        val lines = csv.split("\r\n")
        assertEquals("path,size,modified_epoch", lines[0].removePrefix(CsvWriter.BOM))
        assertEquals("/big,999,200", lines[1])
        assertEquals("/small,10,100", lines[2])
    }

    // ═══════════ المكررات والمخلفات ═══════════

    @Test
    fun `duplicates csv marks representative and rows per item`() {
        val report = DuplicateReport(
            groups = listOf(
                DuplicateGroup(
                    items = listOf(
                        DupItem("/keep/a.jpg", 100, "hash1"),
                        DupItem("/dup/b.jpg", 100, "hash1"),
                    ),
                    exact = true,
                ),
            ),
            scannedFiles = 50, hashedFiles = 30,
        )
        val csv = ReportExporter.duplicatesCsv(report)
        val lines = csv.split("\r\n")
        assertEquals("group,exact,size,hash,path,role", lines[0].removePrefix(CsvWriter.BOM))
        assertEquals("1,true,100,hash1,/keep/a.jpg,representative", lines[1])
        assertEquals("1,true,100,hash1,/dup/b.jpg,duplicate", lines[2])
    }

    @Test
    fun `junk csv includes category and copies sorted by size`() {
        val report = JunkReport(items = listOf(
            JunkItem("/a.tmp", 10, JunkCategory.LEFTOVER_PARTIALS),
            JunkItem("/big.apk", 5000, JunkCategory.ORPHAN_APKS),
            JunkItem("/dupe1.jpg", 800, JunkCategory.CONTENT_DUPLICATES, copies = 3),
        ))
        val csv = ReportExporter.junkCsv(report)
        val lines = csv.split("\r\n")
        assertEquals("path,size,category,copies", lines[0].removePrefix(CsvWriter.BOM))
        assertEquals("/big.apk,5000,ORPHAN_APKS,1", lines[1])
        assertEquals("/dupe1.jpg,800,CONTENT_DUPLICATES,3", lines[2])
        assertEquals("/a.tmp,10,LEFTOVER_PARTIALS,1", lines[3])
    }

    // ═══════════ قبل / بعد ═══════════

    @Test
    fun `cleanup json computes freed bytes`() {
        val json = ReportExporter.cleanupJson(
            before = ReportExporter.CleanupSnapshot(100, 50_000, atEpoch = 1000),
            after = ReportExporter.CleanupSnapshot(96, 45_000, atEpoch = 2000),
            removedFiles = listOf("/x.tmp", "/a.part", "/m.mkv"),
        )
        val obj = MiniJson.parseObject(json)
        assertEquals("cleanup", MiniJson.asString(obj["nvreport"]))
        assertEquals(5000L, MiniJson.asLong(obj["freedBytes"]))
        assertEquals(3L, MiniJson.asLong(obj["removedCount"]))
        val removed = MiniJson.asArray(obj["removed"]!!)
        assertEquals(listOf("/a.part", "/m.mkv", "/x.tmp"), removed.map { MiniJson.asString(it) }) // مرتبة
    }

    @Test
    fun `cleanup freed never negative when bytes grew`() {
        val json = ReportExporter.cleanupJson(
            before = ReportExporter.CleanupSnapshot(10, 100, atEpoch = 1),
            after = ReportExporter.CleanupSnapshot(11, 900, atEpoch = 2),
            removedFiles = emptyList(),
        )
        assertEquals(0L, MiniJson.asLong(MiniJson.parseObject(json)["freedBytes"]))
    }

    @Test
    fun `cleanup csv summary rows`() {
        val csv = ReportExporter.cleanupCsv(
            before = ReportExporter.CleanupSnapshot(100, 50_000, atEpoch = 1),
            after = ReportExporter.CleanupSnapshot(96, 45_000, atEpoch = 2),
            removedFiles = listOf("/a", "/b"),
        )
        val text = csv.removePrefix(CsvWriter.BOM)
        assertTrue(text.contains("metric,value"))
        assertTrue(text.contains("freed_bytes,5000"))
        assertTrue(text.contains("removed_count,2"))
        assertTrue(text.contains("before_bytes,50000"))
    }

    // ═══════════ أسماء الملفات ═══════════

    @Test
    fun `suggest name is deterministic utc stamp`() {
        // 2026-10-24 14:30 UTC
        val epoch = 1792245000000L // قيمة اختبار ثابتة
        val name = ReportExporter.suggestName("storage", "csv", epoch)
        assertTrue(name.startsWith("nova-storage-"))
        assertTrue(name.endsWith(".csv"))
        val again = ReportExporter.suggestName("storage", "csv", epoch)
        assertEquals(name, again)
        // نفس الدقيقة والنوع = نفس الاسم (منع التصادم)، ونوع مختلف = اسم مختلف
        assertTrue(ReportExporter.suggestName("duplicates", "csv", epoch) != name)
    }

    @Test
    fun `suggest name sanitizes kind and ext`() {
        val name = ReportExporter.suggestName("Sto rage!", ".CSV", 0)
        assertTrue(name.startsWith("nova-storage"))
        assertTrue(name.endsWith(".csv"))
        assertEquals(ReportExporter.suggestName("###", "???", 0), ReportExporter.suggestName("report", "txt", 0))
    }
}
