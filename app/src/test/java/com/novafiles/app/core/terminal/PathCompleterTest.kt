package com.novafiles.app.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** اختبارات مكمّل المسارات والأوامر — خالصة على JVM */
class PathCompleterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var home: File
    private lateinit var completer: PathCompleter

    @org.junit.Before
    fun setUp() {
        home = tmp.newFolder("storage")
        completer = PathCompleter(home)
    }

    private fun complete(input: String, cwd: File = home, cursor: Int = input.length) =
        completer.complete(input, cursor, NovaShell.COMMAND_NAMES, cwd)

    private fun mkdir(rel: String): File = File(home, rel).apply { mkdirs() }

    private fun write(rel: String, content: String = "x") =
        File(home, rel).apply { parentFile?.mkdirs(); writeText(content) }

    // ═══ إكمال الأوامر ═══

    @Test
    fun commandUniqueCompletesWithSpace() {
        val c = complete("zip")
        assertTrue(c.completed)
        assertEquals("zip ", c.line)
        assertEquals(4, c.cursor)
    }

    @Test
    fun commandMultipleShowsCandidates() {
        val c = complete("c")
        assertFalse(c.completed)
        assertEquals(listOf("cat", "cd", "clear", "cp"), c.candidates)
    }

    @Test
    fun commandCommonPrefixExtends() {
        val custom = setOf("get1", "get2", "get3")
        val c = completer.complete("ge", 2, custom, home)
        assertTrue(c.completed)
        assertEquals("get", c.line)
    }

    @Test
    fun commandUnknownPrefixKeepsLine() {
        val c = complete("zzz")
        assertFalse(c.completed)
        assertTrue(c.candidates.isEmpty())
        assertEquals("zzz", c.line)
    }

    @Test
    fun emptyInputOffersAllCommands() {
        val c = complete("")
        assertFalse(c.completed)
        assertEquals(NovaShell.COMMAND_NAMES.size, c.candidates.size + c.moreCount)
    }

    @Test
    fun secondTokenIsNotCommandCompleted() {
        mkdir("docs")
        val c = complete("cd ")
        // بعد مسافة: رمز مسار جديد فارغ في مجلد العمل — لا إكمال أمر
        assertFalse(c.candidates.contains("cd"))
    }

    // ═══ إكمال المسارات ═══

    @Test
    fun pathUniqueFileCompletes() {
        write("notes.txt")
        val c = complete("cat note")
        assertTrue(c.completed)
        assertEquals("cat notes.txt", c.line)
    }

    @Test
    fun pathMultipleCandidatesListsAfterCommonPrefix() {
        mkdir("Docs")
        mkdir("Downloads")
        val c = complete("ls D")
        // البادئة المشتركة «Do» تُكتمل مع قائمة المرشحين
        assertTrue(c.completed)
        assertEquals("ls Do/", c.line)
        assertEquals(listOf("Docs", "Downloads"), c.candidates)
    }

    @Test
    fun pathCommonPrefixExtendsWithSlashForDirs() {
        mkdir("Photos")
        mkdir("PhotosBackup")
        val c = complete("ls Pho")
        assertTrue(c.completed)
        assertEquals("ls Photos/", c.line)
        assertEquals(2, c.candidates.size + c.moreCount)
    }

    @Test
    fun pathUniqueDirectoryAppendsSlash() {
        mkdir("unique")
        val c = complete("cd uniq")
        assertTrue(c.completed)
        assertEquals("cd unique/", c.line)
    }

    @Test
    fun trailingSlashListsChildren() {
        mkdir("Docs/inner")
        write("Docs/a.txt")
        val c = complete("ls Docs/")
        assertFalse(c.completed)
        assertEquals(listOf("a.txt", "inner"), c.candidates)
    }

    @Test
    fun noMatchKeepsLineIntact() {
        write("notes.txt")
        val c = complete("ls zzz")
        assertFalse(c.completed)
        assertEquals("ls zzz", c.line)
        assertTrue(c.candidates.isEmpty())
    }

    @Test
    fun missingBaseDirectoryKeepsLine() {
        val c = complete("ls nowhere/x")
        assertFalse(c.completed)
        assertEquals("ls nowhere/x", c.line)
    }

    @Test
    fun hiddenFilesOnlyWithDotPrefix() {
        write(".alpha", "x")
        write("alpha2", "x")
        val visible = complete("ls a")
        assertTrue(visible.completed)
        assertEquals("ls alpha2", visible.line)
        val hidden = complete("ls .a")
        assertTrue(hidden.completed)
        assertEquals("ls .alpha", hidden.line)
    }

    @Test
    fun escapedSpaceCompletesAndReEscapes() {
        mkdir("my dir")
        write("my dir/inner file.txt")
        val c = complete("ls my\\ di")
        assertTrue(c.completed)
        assertEquals("ls my\\ dir/", c.line)
    }

    @Test
    fun quotedTokenRebuildsQuoted() {
        mkdir("my dir")
        write("my dir/inner file.txt")
        val c = complete("ls \"my dir/in")
        assertTrue(c.completed)
        assertEquals("ls \"my dir/inner file.txt\"", c.line)
    }

    @Test
    fun tildeCompletesUnderHome() {
        mkdir("Docs")
        val c = complete("ls ~/D")
        assertTrue(c.completed)
        assertEquals("ls ~/Docs/", c.line)
    }

    @Test
    fun absolutePrefixCompletes() {
        val prefix = home.absolutePath.dropLast(1) // بلا الحرف الأخير
        val c = complete("ls $prefix")
        assertTrue(c.completed)
        assertEquals("ls ${home.absolutePath}/", c.line)
    }

    @Test
    fun relativeCompletionUsesCwdNotHome() {
        val sub = File(home, "sub").apply { mkdirs() }
        mkdir("sub/target")
        File(home, "other").apply { mkdirs() }
        val c = complete("ls ta", cwd = sub)
        assertTrue(c.completed)
        assertEquals("ls target/", c.line)
    }

    @Test
    fun moreCountCountsBeyondDisplayLimit() {
        for (i in 1..10) mkdir("d$i")
        val c = complete("ls d")
        assertFalse(c.completed)
        assertEquals(PathCompleter.DISPLAY_LIMIT, c.candidates.size)
        assertEquals(2, c.moreCount)
    }

    @Test
    fun midTokenCursorReplacesWholeToken() {
        write("notes.txt")
        // المؤشر بين "no" و "TE" — الرمز الكامل "noTE" هو ما يُستبدل
        val c = complete("ls noTE", cursor = 5)
        assertTrue(c.completed)
        assertEquals("ls notes.txt", c.line)
    }

    @Test
    fun cursorInsideQuotesHandled() {
        mkdir("Docs")
        val c = complete("cd \"Do")
        assertTrue(c.completed)
        assertEquals("cd \"Docs/\"", c.line)
    }

    @Test
    fun caseInsensitiveMatching() {
        mkdir("Docs")
        val c = complete("ls do")
        assertTrue(c.candidates.contains("Docs") || c.completed)
        if (c.completed) assertEquals("ls Docs/", c.line)
    }
}
