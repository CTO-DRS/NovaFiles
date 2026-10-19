package com.novafiles.app.core.rename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * اختبارات محرك إعادة التسمية الجماعية (v0.12) — JVM خالصة.
 */
class RenameEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeFiles(vararg names: String): List<File> = names.map { tmp.newFile(it) }

    // ----- توليد الأسماء -----

    @Test
    fun `prefix and suffix`() {
        val name = RenameEngine.generateName(
            "report.txt", 1,
            RenameOptions(prefix = "nova_", suffix = "_2026"),
        )
        assertEquals("nova_report_2026.txt", name)
    }

    @Test
    fun `counter with padding`() {
        val name = RenameEngine.generateName(
            "img.jpg", 7,
            RenameOptions(useCounter = true, counterPadding = 3),
        )
        assertEquals("img_007.jpg", name)
    }

    @Test
    fun `counter start and step`() {
        val name = RenameEngine.generateName(
            "a.txt", 10,
            RenameOptions(useCounter = true, counterStart = 10, counterStep = 5),
        )
        assertEquals("a_10.txt", name)
    }

    @Test
    fun `base name replacement keeps extension`() {
        val name = RenameEngine.generateName(
            "photo.jpg", 2,
            RenameOptions(baseName = "vacation", useCounter = true, counterPadding = 2),
        )
        assertEquals("vacation_02.jpg", name)
    }

    @Test
    fun `find and replace literal`() {
        val name = RenameEngine.generateName(
            "draft - chapter 1.txt", 1,
            RenameOptions(find = "draft", replacement = "final"),
        )
        assertEquals("final - chapter 1.txt", name)
    }

    @Test
    fun `find and replace regex`() {
        val name = RenameEngine.generateName(
            "scan 2026 01 15.pdf", 1,
            RenameOptions(find = Regex.escape(" "), replacement = "_", regex = true),
        )
        assertEquals("scan_2026_01_15.pdf", name)
    }

    @Test
    fun `regex digits collapse`() {
        val name = RenameEngine.generateName(
            "file000123.txt", 1,
            RenameOptions(find = "0+", replacement = "-", regex = true),
        )
        assertEquals("file-123.txt", name)
    }

    @Test
    fun `invalid regex falls back to original`() {
        val name = RenameEngine.generateName(
            "safe.txt", 1,
            RenameOptions(find = "[unclosed", replacement = "x", regex = true),
        )
        assertEquals("safe.txt", name)
    }

    @Test
    fun `case modes`() {
        assertEquals(
            "report.txt",
            RenameEngine.generateName("REPORT.TXT", 1, RenameOptions(caseMode = CaseMode.LOWER)),
        )
        assertEquals(
            "REPORT.TXT",
            RenameEngine.generateName("report.txt", 1, RenameOptions(caseMode = CaseMode.UPPER)),
        )
        assertEquals(
            "My document.txt",
            RenameEngine.generateName("MY DOCUMENT.txt", 1, RenameOptions(caseMode = CaseMode.CAPITALIZE)),
        )
    }

    @Test
    fun `date insertion uses file lastModified`() {
        val opts = RenameOptions(insertDate = true, dateFormat = "yyyy-MM-dd")
        val name = RenameEngine.generateName("notes.txt", 1, opts, lastModified = 1735689600000L) // 2025-01-01 UTC
        assertTrue("date in name: $name", Regex("""notes_\d{4}-\d{2}-\d{2}\.txt""").matches(name))
    }

    @Test
    fun `keepExtension false renames whole name`() {
        val name = RenameEngine.generateName(
            "archive.tar.gz", 1,
            RenameOptions(baseName = "backup", useCounter = true, keepExtension = false),
        )
        assertEquals("backup_1", name)
    }

    @Test
    fun `arabic filename roundtrip`() {
        val name = RenameEngine.generateName(
            "مستند مهم.pdf", 1,
            RenameOptions(prefix = "عمل_"),
            lastModified = 0L,
        )
        assertEquals("عمل_مستند مهم.pdf", name)
    }

    @Test
    fun `counter padding respects longer numbers`() {
        assertEquals("1000", RenameEngine.formatCounter(1000, 3))
        assertEquals("001", RenameEngine.formatCounter(1, 3))
    }

    // ----- التخطيط وكشف التعارضات -----

    @Test
    fun `plan detects duplicates within batch`() {
        val files = makeFiles("a.txt", "b.txt")
        val plan = RenameEngine.plan(files, RenameOptions(baseName = "same"))
        assertEquals(RenameStatus.OK, plan[0].status)
        assertEquals(RenameStatus.DUPLICATE, plan[1].status)
    }

    @Test
    fun `plan detects existing file on disk`() {
        makeFiles("a.txt")
        tmp.newFile("taken.txt")
        val plan = RenameEngine.plan(listOf(File(tmp.root, "a.txt")), RenameOptions(baseName = "taken"))
        assertEquals(RenameStatus.EXISTS, plan[0].status)
    }

    @Test
    fun `plan allows target occupied by another batch member`() {
        // a.txt → b.txt بينما b.txt نفسه سيعاد تسميته إلى c.txt — لا تعارض
        val files = makeFiles("a.txt", "b.txt")
        val plan = RenameEngine.plan(files, RenameOptions(find = "a", replacement = "b"))
        assertEquals("b.txt", plan[0].newName)
        assertEquals(RenameStatus.OK, plan[0].status)
        assertEquals(RenameStatus.UNCHANGED, plan[1].status)
    }

    @Test
    fun `plan marks unchanged when name identical`() {
        val files = makeFiles("same.txt")
        val plan = RenameEngine.plan(files, RenameOptions())
        assertEquals(RenameStatus.UNCHANGED, plan[0].status)
    }

    @Test
    fun `plan marks invalid names`() {
        val files = makeFiles("a.txt")
        val plan = RenameEngine.plan(files, RenameOptions(find = "a", replacement = "/", regex = true))
        assertEquals(RenameStatus.INVALID, plan[0].status)
    }

    // ----- التنفيذ والتراجع -----

    @Test
    fun `apply renames all ok files`() {
        val files = makeFiles("one.txt", "two.txt", "three.txt")
        val plan = RenameEngine.plan(files, RenameOptions(baseName = "doc", useCounter = true, counterPadding = 2))
        val result = RenameEngine.apply(plan)

        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrThrow())
        assertTrue(File(tmp.root, "doc_01.txt").exists())
        assertTrue(File(tmp.root, "doc_02.txt").exists())
        assertTrue(File(tmp.root, "doc_03.txt").exists())
        assertFalse(File(tmp.root, "one.txt").exists())
    }

    @Test
    fun `apply handles swap chain a-b to b-a via two phase rename`() {
        val files = makeFiles("a.txt", "b.txt")
        files[0].writeText("content-A")
        files[1].writeText("content-B")
        // a.txt → b.txt و b.txt → a.txt (تبديل كامل)
        val items = listOf(
            RenameItem(files[0], "b.txt", RenameStatus.OK),
            RenameItem(files[1], "a.txt", RenameStatus.OK),
        )
        val result = RenameEngine.apply(items)
        assertTrue(result.isSuccess)
        assertTrue(File(tmp.root, "b.txt").exists())
        assertTrue(File(tmp.root, "a.txt").exists())
        assertEquals("content-A", File(tmp.root, "b.txt").readText())
        assertEquals("content-B", File(tmp.root, "a.txt").readText())
    }

    @Test
    fun `apply rolls back everything on failure`() {
        val files = makeFiles("x1.txt", "x2.txt")
        val items = listOf(
            RenameItem(files[0], "ok1.txt", RenameStatus.OK),
            RenameItem(files[1], "bad/name.txt", RenameStatus.OK), // مسار غير صالح للنظام
        )
        val result = RenameEngine.apply(items)

        assertTrue(result.isFailure)
        // لا شيء تغيّر بعد التراجع
        assertTrue(File(tmp.root, "x1.txt").exists())
        assertTrue(File(tmp.root, "x2.txt").exists())
        assertFalse(File(tmp.root, "ok1.txt").exists())
        // لا بقايا ملفات مؤقتة
        assertEquals(0, tmp.root.listFiles()!!.count { it.name.startsWith(".nova-rename") })
    }

    @Test
    fun `apply skips non-ok items`() {
        val files = makeFiles("a.txt", "b.txt")
        val items = listOf(
            RenameItem(files[0], "a.txt", RenameStatus.UNCHANGED),
            RenameItem(files[1], "renamed.txt", RenameStatus.OK),
        )
        val result = RenameEngine.apply(items)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())
        assertTrue(File(tmp.root, "a.txt").exists())
        assertTrue(File(tmp.root, "renamed.txt").exists())
    }

    @Test
    fun `undo restores original names`() {
        val files = makeFiles("first.txt", "second.txt")
        val plan = RenameEngine.plan(files, RenameOptions(baseName = "renamed", useCounter = true))
        RenameEngine.apply(plan).getOrThrow()

        val undone = RenameEngine.undo(plan).getOrThrow()
        assertEquals(2, undone)
        assertTrue(File(tmp.root, "first.txt").exists())
        assertTrue(File(tmp.root, "second.txt").exists())
        assertFalse(File(tmp.root, "renamed_1.txt").exists())
    }

    @Test
    fun `apply empty plan returns zero`() {
        val result = RenameEngine.apply(emptyList())
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow())
    }
}
