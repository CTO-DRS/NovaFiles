package com.novafiles.app.core.rules

import com.novafiles.app.core.fs.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات شروط القواعد: مطابقة glob والأنواع والأحجام AND ببينها */
class RuleConditionsTest {

    // ═══════════════ glob → regex ═══════════════

    @Test
    fun `star matches any sequence`() {
        val c = RuleConditions(nameGlob = "*.apk")
        assertTrue(c.nameMatches("app.apk"))
        assertTrue(c.nameMatches("my.long.name.apk"))
        assertFalse(c.nameMatches("app.zip"))
        assertFalse(c.nameMatches("app.apk.bak"))
    }

    @Test
    fun `star matches empty sequence`() {
        val c = RuleConditions(nameGlob = "IMG_*.jpg")
        assertTrue(c.nameMatches("IMG_.jpg"))
        assertTrue(c.nameMatches("IMG_1234.jpg"))
    }

    @Test
    fun `question matches exactly one char`() {
        val c = RuleConditions(nameGlob = "file??.txt")
        assertTrue(c.nameMatches("file01.txt"))
        assertFalse(c.nameMatches("file1.txt"))
        assertFalse(c.nameMatches("file001.txt"))
    }

    @Test
    fun `matching is case insensitive`() {
        val c = RuleConditions(nameGlob = "screenshot*")
        assertTrue(c.nameMatches("Screenshot_20260101.jpg"))
        assertTrue(c.nameMatches("SCREENSHOT_x.png"))
    }

    @Test
    fun `arabic names match glob`() {
        val c = RuleConditions(nameGlob = "تقرير*")
        assertTrue(c.nameMatches("تقرير 2026.xlsx"))
        assertFalse(c.nameMatches("مستند.docx"))
    }

    @Test
    fun `glob anchors fully - no substring matches`() {
        val c = RuleConditions(nameGlob = "cat")
        assertTrue(c.nameMatches("cat"))
        assertFalse(c.nameMatches("category"))
        assertFalse(c.nameMatches("tomcat"))
    }

    @Test
    fun `regex special chars are escaped`() {
        val c = RuleConditions(nameGlob = "v1.0 (2).zip")
        assertTrue(c.nameMatches("v1.0 (2).zip"))
        assertFalse(c.nameMatches("v1x0 (2).zip"))
        assertFalse(c.nameMatches("v1.0 (22).zip"))
    }

    @Test
    fun `multiple stars and mixed wildcards`() {
        val c = RuleConditions(nameGlob = "*report*.doc?")
        assertTrue(c.nameMatches("monthly-report-final.docx"))
        assertTrue(c.nameMatches("report.docx"))
        assertFalse(c.nameMatches("report.xlsx"))
    }

    @Test
    fun `blank or null glob matches everything`() {
        assertTrue(RuleConditions().nameMatches("anything.bin"))
        assertTrue(RuleConditions(nameGlob = "  ").nameMatches("anything.bin"))
        assertTrue(RuleConditions(nameGlob = "").nameMatches("تقرير.pdf"))
    }

    // ═══════════════ الأنواع ═══════════════

    @Test
    fun `empty type set matches all types`() {
        val c = RuleConditions()
        assertTrue(c.typeMatches("video.mp4"))
        assertTrue(c.typeMatches("unknown.xyz"))
    }

    @Test
    fun `type matches by extension classification`() {
        val c = RuleConditions(types = setOf(FileType.APK))
        assertTrue(c.typeMatches("app.apk"))
        assertFalse(c.typeMatches("app.zip"))
    }

    @Test
    fun `multiple accepted types`() {
        val c = RuleConditions(types = setOf(FileType.DOC, FileType.PDF))
        assertTrue(c.typeMatches("a.docx"))
        assertTrue(c.typeMatches("b.pdf"))
        assertFalse(c.typeMatches("c.jpg"))
    }

    // ═══════════════ الأحجام ═══════════════

    @Test
    fun `min size is inclusive`() {
        val c = RuleConditions(minSizeBytes = 100L)
        assertTrue(c.sizeMatches(100L))
        assertFalse(c.sizeMatches(99L))
    }

    @Test
    fun `max size is inclusive`() {
        val c = RuleConditions(maxSizeBytes = 500L)
        assertTrue(c.sizeMatches(500L))
        assertFalse(c.sizeMatches(501L))
    }

    @Test
    fun `size range both ends`() {
        val c = RuleConditions(minSizeBytes = 10L, maxSizeBytes = 20L)
        assertTrue(c.sizeMatches(10L))
        assertTrue(c.sizeMatches(20L))
        assertTrue(c.sizeMatches(15L))
        assertFalse(c.sizeMatches(9L))
        assertFalse(c.sizeMatches(21L))
    }

    @Test
    fun `no size limits matches any size`() {
        val c = RuleConditions()
        assertTrue(c.sizeMatches(0L))
        assertTrue(c.sizeMatches(Long.MAX_VALUE))
    }

    // ═══════════════ AND الكامل ═══════════════

    @Test
    fun `all conditions must hold together`() {
        val c = RuleConditions(
            nameGlob = "IMG_*",
            types = setOf(FileType.IMAGE),
            minSizeBytes = 1000L,
        )
        assertTrue(c.matches("IMG_1.jpg", 2000))
        assertFalse(c.matches("IMG_1.jpg", 999)) // صغير
        assertFalse(c.matches("doc_1.pdf", 2000)) // نوع مخالف
        assertFalse(c.matches("photo_1.jpg", 2000)) // اسم مخالف
    }

    @Test
    fun `empty conditions match any file`() {
        assertTrue(RuleConditions().matches("x", 0))
    }

    @Test
    fun `activeCount reflects set fields`() {
        assertEquals(0, RuleConditions().activeCount)
        assertEquals(1, RuleConditions(nameGlob = "*").activeCount)
        assertEquals(2, RuleConditions(types = setOf(FileType.IMAGE), minSizeBytes = 1L).activeCount)
        assertEquals(
            4,
            RuleConditions(nameGlob = "*", types = setOf(FileType.IMAGE), minSizeBytes = 1L, maxSizeBytes = 2L).activeCount,
        )
    }

    // ═══════════════ تحقق القاعدة ═══════════════

    @Test
    fun `valid rule has no errors`() {
        val r = WatchRule(
            id = "r1", name = "n", watchFolder = "/tmp/x",
            actions = listOf(RuleAction.MoveTo("/tmp/y")),
        )
        assertTrue(r.validationErrors().isEmpty())
    }

    @Test
    fun `blank name folder or empty actions flagged`() {
        val base = WatchRule(id = "r", name = "n", watchFolder = "/w", actions = listOf(RuleAction.TagFile("t")))
        assertTrue(WatchRule(id = "r", name = " ", watchFolder = "/w", actions = base.actions)
            .validationErrors().contains("name_blank"))
        assertTrue(WatchRule(id = "r", name = "n", watchFolder = "", actions = base.actions)
            .validationErrors().contains("folder_blank"))
        assertTrue(WatchRule(id = "r", name = "n", watchFolder = "/w", actions = emptyList())
            .validationErrors().contains("no_actions"))
    }

    @Test
    fun `move into watch folder is rejected to prevent loops`() {
        val r = WatchRule(
            id = "r", name = "n", watchFolder = "/storage/Download",
            actions = listOf(RuleAction.MoveTo("/storage/Download/")),
        )
        assertTrue(r.validationErrors().contains("move_into_watch"))
    }

    @Test
    fun `move to subfolder of watch folder is allowed`() {
        val r = WatchRule(
            id = "r", name = "n", watchFolder = "/storage/Download",
            actions = listOf(RuleAction.MoveTo("/storage/Download/Screenshots")),
        )
        assertTrue(r.validationErrors().isEmpty())
    }

    @Test
    fun `cloud action without account and blank tag and zip name flagged`() {
        val w = "/w"
        assertTrue(
            WatchRule("r", "n", w, actions = listOf(RuleAction.UploadCloud(0L, "/x")))
                .validationErrors().contains("cloud_account_missing"),
        )
        assertTrue(
            WatchRule("r", "n", w, actions = listOf(RuleAction.TagFile(" ")))
                .validationErrors().contains("tag_blank"),
        )
        assertTrue(
            WatchRule("r", "n", w, actions = listOf(RuleAction.ArchiveZip(" ")))
                .validationErrors().contains("zip_name_blank"),
        )
    }
}
