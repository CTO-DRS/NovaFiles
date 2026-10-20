package com.novafiles.app.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** اختبارات أدوات المسارات — خالصة على JVM */
class ShellPathsTest {

    private val home = "/storage/emulated/0"

    // ═══ resolve ═══

    @Test
    fun tildeResolvesToHome() {
        assertEquals(home, ShellPaths.resolve("/x", home, "~"))
    }

    @Test
    fun tildeSlashResolvesUnderHome() {
        assertEquals("$home/DCIM", ShellPaths.resolve("/x", home, "~/DCIM"))
    }

    @Test
    fun absoluteStaysAbsolute() {
        assertEquals("/a/b", ShellPaths.resolve("/x", home, "/a/b"))
    }

    @Test
    fun relativeJoinsCwd() {
        assertEquals("/x/y/z", ShellPaths.resolve("/x/y", home, "z"))
    }

    @Test
    fun dotSegmentsCollapse() {
        assertEquals("/x/z", ShellPaths.resolve("/x/y", home, "../z"))
    }

    @Test
    fun dotAloneStays() {
        assertEquals("/x/y", ShellPaths.resolve("/x/y", home, "."))
    }

    @Test
    fun doubleDotAboveRootStaysAtRoot() {
        assertEquals("/", ShellPaths.resolve("/", home, "../.."))
    }

    @Test
    fun duplicateSlashesCollapse() {
        assertEquals("/a/b", ShellPaths.resolve("/", home, "//a///b"))
    }

    @Test
    fun arabicAndSpacesSurvive() {
        assertEquals("/x/مجلد صور", ShellPaths.resolve("/x", home, "مجلد صور"))
    }

    // ═══ splitDirPrefix ═══

    @Test
    fun splitSplitsOnLastSlash() {
        assertEquals("a/b" to "c", ShellPaths.splitDirPrefix("a/b/c"))
    }

    @Test
    fun splitTrailingSlashGivesEmptyPrefix() {
        assertEquals("a/b" to "", ShellPaths.splitDirPrefix("a/b/"))
    }

    @Test
    fun splitBareNameHasNoDir() {
        assertEquals("" to "doc", ShellPaths.splitDirPrefix("doc"))
    }

    @Test
    fun splitUnescapesSpaces() {
        assertEquals("my file" to "x", ShellPaths.splitDirPrefix("my\\ file/x"))
    }

    // ═══ escape / unescape ═══

    @Test
    fun escapeEscapesSpaceQuoteBackslash() {
        assertEquals("a\\ b\\\"c\\\\", ShellPaths.escape("a b\"c\\"))
    }

    @Test
    fun unescapeReversesEscape() {
        assertEquals("a b\"c\\", ShellPaths.unescape("a\\ b\\\"c\\\\"))
    }

    @Test
    fun escapeUnescapeRoundTripArabic() {
        val p = "مجلد صور/عيد 2026"
        assertEquals(p, ShellPaths.unescape(ShellPaths.escape(p)))
    }

    // ═══ globToRegex ═══

    @Test
    fun globStarMatchesAnything() {
        assertTrue(ShellPaths.globToRegex("*.txt").matches("report.txt"))
        assertFalse(ShellPaths.globToRegex("*.txt").matches("report.md"))
    }

    @Test
    fun globQuestionMatchesOneChar() {
        assertTrue(ShellPaths.globToRegex("a?c").matches("abc"))
        assertFalse(ShellPaths.globToRegex("a?c").matches("ac"))
        assertFalse(ShellPaths.globToRegex("a?c").matches("abbc"))
    }

    @Test
    fun globLiteralDotIsLiteral() {
        assertTrue(ShellPaths.globToRegex("a.b").matches("a.b"))
        assertFalse(ShellPaths.globToRegex("a.b").matches("axb"))
    }

    @Test
    fun globArabicNames() {
        assertTrue(ShellPaths.globToRegex("صور*").matches("صور العيد"))
    }

    // ═══ isWithinOrEqual ═══

    @Test
    fun withinGuardAcceptsSelfAndChildren() {
        val root = File("/tmp/r")
        assertTrue(ShellPaths.isWithinOrEqual(File("/tmp/r"), root))
        assertTrue(ShellPaths.isWithinOrEqual(File("/tmp/r/sub"), root))
    }

    @Test
    fun withinGuardRejectsSiblingsAndParents() {
        val root = File("/tmp/r")
        assertFalse(ShellPaths.isWithinOrEqual(File("/tmp/s"), root))
        assertFalse(ShellPaths.isWithinOrEqual(File("/tmp"), root))
    }

    @Test
    fun withinGuardTrailingSlashTolerant() {
        assertTrue(ShellPaths.isWithinOrEqual(File("/tmp/r/"), File("/tmp/r")))
    }
}
