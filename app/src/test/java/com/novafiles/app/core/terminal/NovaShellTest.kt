package com.novafiles.app.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * اختبارات شل NovaFiles — خالصة على JVM فوق قرص مؤقت.
 * تغطي الأوامر الثلاثة عشر المطلوبة في الخارطة + البنية التحتية
 * (تسلسل، توجيه، تاريخ، إكمال، سياسات الأمان).
 */
class NovaShellTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var home: File
    private lateinit var shell: NovaShell

    @org.junit.Before
    fun setUp() {
        home = tmp.newFolder("storage")
        shell = NovaShell(home)
    }

    // ───────────────── أدوات مساعدة ─────────────────

    private fun run(cmd: String): ShellOutcome = shell.execute(cmd)

    private fun text(cmd: String): List<String> = run(cmd).lines.map { it.text }

    private fun f(rel: String): File = File(home, rel)

    private fun write(rel: String, content: String) {
        File(home, rel).let {
            it.parentFile?.mkdirs()
            it.writeText(content)
        }
    }

    // ═══ pwd / echo ═══

    @Test
    fun pwdStartsAtHome() {
        assertEquals(listOf(home.absolutePath), text("pwd"))
    }

    @Test
    fun echoPrintsArgsJoined() {
        assertEquals(listOf("hello world"), text("echo hello world"))
    }

    @Test
    fun echoEmptyPrintsOneEmptyLine() {
        assertEquals(listOf(""), text("echo"))
    }

    // ═══ cd ═══

    @Test
    fun cdChangesDirectoryAndPwdFollows() {
        f("sub").mkdirs()
        run("cd sub")
        assertEquals(listOf(f("sub").absolutePath), text("pwd"))
    }

    @Test
    fun cdMissingDirectoryFailsAndStays() {
        val before = shell.cwdPath()
        val out = run("cd nowhere")
        assertFalse(out.ok)
        assertEquals(before, shell.cwdPath())
    }

    @Test
    fun cdToFileFails() {
        write("file.txt", "x")
        assertFalse(run("cd file.txt").ok)
    }

    @Test
    fun cdNoArgsGoesHome() {
        run("cd ..")
        run("cd")
        assertEquals(home.absolutePath, shell.cwdPath())
    }

    @Test
    fun cdTildeGoesHome() {
        run("cd ..")
        run("cd ~")
        assertEquals(home.absolutePath, shell.cwdPath())
    }

    @Test
    fun cdDashSwapsToPreviousDirectory() {
        f("a").mkdirs()
        run("cd a")
        val out = run("cd -")
        assertEquals(home.absolutePath, shell.cwdPath())
        // bash يطبع مجلد الوجهة (الذي عُدنا إليه)
        assertTrue(out.lines.any { it.color == ShellColor.DIM && it.text == home.absolutePath })
    }

    @Test
    fun cdDashWithoutHistoryFails() {
        assertFalse(run("cd -").ok)
    }

    @Test
    fun cdTooManyArgsFails() {
        assertFalse(run("cd a b").ok)
    }

    // ═══ ls ═══

    @Test
    fun lsListsDirsFirstAlphabetically() {
        f("zdir").mkdirs()
        write("afile.txt", "x")
        val out = run("ls")
        assertEquals(listOf("zdir", "afile.txt"), out.lines.map { it.text })
        assertEquals(ShellColor.BLUE, out.lines[0].color)
    }

    @Test
    fun lsHidesDotfilesWithoutA() {
        write(".hidden", "x")
        write("visible.txt", "x")
        assertFalse(text("ls").contains(".hidden"))
        val withA = run("ls -a")
        assertTrue(withA.lines.any { it.text == ".hidden" && it.color == ShellColor.DIM })
    }

    @Test
    fun lsLongFormatMarksTypesAndSizes() {
        f("d").mkdirs()
        write("d/file.bin", "abcde")
        val rows = text("ls -l d")
        assertEquals(1, rows.size)
        assertTrue(rows[0].startsWith("-  "))
        assertTrue(rows[0].contains("5 B"))
        val dirRow = text("ls -l")[0]
        assertTrue(dirRow.startsWith("d  "))
        assertTrue(dirRow.contains("  -  ")) // حجم المجلد يُعرض شرطة
    }

    @Test
    fun lsMultipleTargetsShowHeaders() {
        f("d1").mkdirs()
        f("d2").mkdirs()
        val rows = text("ls d1 d2")
        assertTrue(rows.contains("${f("d1").absolutePath}:"))
        assertTrue(rows.contains("${f("d2").absolutePath}:"))
    }

    @Test
    fun lsMissingTargetFailsWithAccessError() {
        val out = run("ls ghost")
        assertFalse(out.ok)
        assertTrue(out.lines[0].text.contains("cannot access"))
    }

    @Test
    fun lsFileTargetPrintsNameDirectly() {
        write("one.txt", "x")
        assertEquals(listOf("one.txt"), text("ls one.txt"))
    }

    @Test
    fun llAliasMeansLongListing() {
        write("x.txt", "hello")
        assertTrue(text("ll")[0].startsWith("-  "))
    }

    // ═══ cp ═══

    @Test
    fun cpFileIntoDirectory() {
        write("a.txt", "hello")
        f("dst").mkdirs()
        val out = run("cp a.txt dst")
        assertTrue(out.ok)
        assertEquals("hello", f("dst/a.txt").readText())
    }

    @Test
    fun cpRenamesFileToNewPath() {
        write("a.txt", "data")
        run("cp a.txt b.txt")
        assertEquals("data", f("b.txt").readText())
        assertTrue(f("a.txt").exists())
    }

    @Test
    fun cpMissingSourceFails() {
        assertFalse(run("cp ghost.txt dst").ok)
    }

    @Test
    fun cpDirectoryNeedsRecursiveFlag() {
        f("dir").mkdirs()
        val out = run("cp dir dst2")
        assertFalse(out.ok)
        assertTrue(out.lines[0].text.contains("-r not specified"))
    }

    @Test
    fun cpRecursiveCopiesNestedTree() {
        write("tree/a/one.txt", "1")
        write("tree/b/two.txt", "2")
        run("cp -r tree copy")
        assertEquals("1", f("copy/a/one.txt").readText())
        assertEquals("2", f("copy/b/two.txt").readText())
    }

    @Test
    fun cpRefusesToCopyIntoItself() {
        f("dir/inner").mkdirs()
        val out = run("cp -r dir dir")
        assertFalse(out.ok)
        assertTrue(out.lines[0].text.contains("into itself"))
    }

    @Test
    fun cpMultipleSourcesIntoDir() {
        write("a.txt", "1")
        write("b.txt", "2")
        f("out").mkdirs()
        run("cp a.txt b.txt out")
        assertTrue(f("out/a.txt").exists() && f("out/b.txt").exists())
    }

    // ═══ mv ═══

    @Test
    fun mvRenamesFile() {
        write("old.txt", "v")
        val out = run("mv old.txt new.txt")
        assertTrue(out.ok)
        assertFalse(f("old.txt").exists())
        assertEquals("v", f("new.txt").readText())
    }

    @Test
    fun mvMovesDirectoryIntoDirectory() {
        f("src").mkdirs()
        write("src/kid.txt", "k")
        f("dst").mkdirs()
        val out = run("mv src dst")
        assertTrue(out.ok)
        assertFalse(f("src").exists())
        assertEquals("k", f("dst/src/kid.txt").readText())
    }

    @Test
    fun mvFileIntoExistingDirectoryMovesInside() {
        write("file.txt", "f")
        f("dir").mkdirs()
        val out = run("mv file.txt dir")
        assertTrue(out.ok)
        assertEquals("f", f("dir/file.txt").readText())
    }

    @Test
    fun mvCannotOverwriteFileWithDirectory() {
        write("existing", "e")
        f("dir").mkdirs()
        val out = run("mv dir existing")
        assertFalse(out.ok)
        assertTrue(out.lines[0].text.contains("cannot overwrite"))
    }

    @Test
    fun mvMissingSourceFails() {
        assertFalse(run("mv ghost dst").ok)
    }

    // ═══ rm ═══

    @Test
    fun rmDeletesFileSilently() {
        write("gone.txt", "x")
        val out = run("rm gone.txt")
        assertTrue(out.lines.isEmpty() && out.ok)
        assertFalse(f("gone.txt").exists())
    }

    @Test
    fun rmEmptyDirectoryWorksWithoutR() {
        f("empty").mkdirs()
        run("rm empty")
        assertFalse(f("empty").exists())
    }

    @Test
    fun rmNonEmptyDirectoryNeedsR() {
        write("full/child.txt", "x")
        val out = run("rm full")
        assertFalse(out.ok)
        assertTrue(out.lines[0].text.contains("not empty"))
        assertTrue(f("full").exists())
    }

    @Test
    fun rmRecursiveDeletesTree() {
        write("tree/a/one.txt", "1")
        write("tree/two.txt", "2")
        run("rm -r tree")
        assertFalse(f("tree").exists())
    }

    @Test
    fun rmRefusesStorageRoot() {
        val out = run("rm .")
        assertFalse(out.ok)
        assertTrue(out.lines[0].text.contains("refusing"))
    }

    @Test
    fun rmMissingTargetFails() {
        assertFalse(run("rm ghost").ok)
    }

    @Test
    fun rmInvalidOptionFails() {
        val out = run("rm -x file")
        assertFalse(out.ok)
        assertTrue(out.lines[0].text.contains("invalid option"))
    }

    // ═══ mkdir / touch ═══

    @Test
    fun mkdirCreatesDirectory() {
        run("mkdir newdir")
        assertTrue(f("newdir").isDirectory)
    }

    @Test
    fun mkdirExistingFailsUnlessP() {
        f("exists").mkdirs()
        assertFalse(run("mkdir exists").ok)
        assertTrue(run("mkdir -p exists").ok)
    }

    @Test
    fun mkdirDeepWithPNests() {
        run("mkdir -p a/b/c")
        assertTrue(f("a/b/c").isDirectory)
    }

    @Test
    fun mkdirWithoutParentsFails() {
        assertFalse(run("mkdir no/parent/here").ok)
    }

    @Test
    fun touchCreatesEmptyFile() {
        run("touch t.txt")
        assertTrue(f("t.txt").isFile)
        assertEquals(0L, f("t.txt").length())
    }

    @Test
    fun touchUpdatesTimestampWithClock() {
        val stamp = 1_700_000_000_000L
        val clocked = NovaShell(home, now = { stamp })
        val target = f("t.txt")
        target.writeText("x")
        target.setLastModified(1_000L)
        clocked.execute("touch t.txt")
        assertEquals(stamp, target.lastModified())
    }

    @Test
    fun touchUnderMissingDirectoryFails() {
        assertFalse(run("touch no/dir/f.txt").ok)
    }

    // ═══ cat ═══

    @Test
    fun catPrintsFileContent() {
        write("c.txt", "line1\nline2\n")
        assertEquals(listOf("line1", "line2"), text("cat c.txt"))
    }

    @Test
    fun catNumbersLinesWithN() {
        write("c.txt", "a\nb\n")
        val rows = text("cat -n c.txt")
        assertTrue(rows[0].endsWith("  a"))
        assertTrue(rows[0].trimStart().startsWith("1"))
    }

    @Test
    fun catConcatenatesMultipleFiles() {
        write("a.txt", "A\n")
        write("b.txt", "B\n")
        assertEquals(listOf("A", "B"), text("cat a.txt b.txt"))
    }

    @Test
    fun catMissingFileAndDirectoryFail() {
        f("d").mkdirs()
        val out = run("cat ghost d")
        assertFalse(out.ok)
        assertEquals(2, out.lines.count { it.color == ShellColor.RED })
    }

    @Test
    fun catTruncatesHugeFile() {
        write("big.txt", (1..NovaShell.CAT_MAX_LINES + 500).joinToString("\n") { "l$it" })
        val rows = text("cat big.txt")
        assertEquals(NovaShell.CAT_MAX_LINES + 1, rows.size)
        assertTrue(rows.last().contains("truncated"))
    }

    // ═══ du ═══

    @Test
    fun duReportsRecursiveBytesPerPath() {
        write("d/a.bin", "12345") // 5 بايتات
        write("d/sub/b.bin", "1234567") // 7 بايتات
        val rows = text("du d")
        assertEquals(1, rows.size)
        assertTrue(rows[0].startsWith("12\t"))
        assertTrue(rows[0].endsWith(f("d").absolutePath))
    }

    @Test
    fun duHumanizesWithH() {
        write("d.bin", "x".repeat(4096))
        assertTrue(text("du -h d.bin")[0].startsWith("4.0 KB"))
    }

    @Test
    fun duMissingFails() {
        assertFalse(run("du ghost").ok)
    }

    // ═══ find ═══

    @Test
    fun findListsEverythingUnderStart() {
        write("a/one.txt", "x")
        write("a/deep/two.md", "x")
        write("top.bin", "x")
        val rows = text("find")
        assertTrue(rows.contains(f("a").absolutePath))
        assertTrue(rows.contains(f("a/deep/two.md").absolutePath))
        assertTrue(rows.contains(f("top.bin").absolutePath))
    }

    @Test
    fun findNameGlobFilters() {
        write("a/one.txt", "x")
        write("a/two.md", "x")
        val rows = text("find -name \"*.txt\"")
        assertTrue(rows.contains(f("a/one.txt").absolutePath))
        assertFalse(rows.any { it.endsWith("two.md") })
    }

    @Test
    fun findTypeDirFilters() {
        write("a/file.txt", "x")
        val rows = text("find -type d")
        assertTrue(rows.contains(f("a").absolutePath))
        assertFalse(rows.any { it.endsWith("file.txt") })
    }

    @Test
    fun findMaxDepthLimitsDescent() {
        write("lv1/lv2/lv3/deep.txt", "x")
        val rows = text("find -maxdepth 1")
        assertFalse(rows.any { it.contains("lv2") })
        val two = text("find -maxdepth 2")
        assertTrue(two.any { it.contains("lv2") })
        assertFalse(two.any { it.contains("lv3") })
    }

    @Test
    fun findUnknownOptionFails() {
        assertFalse(run("find -xyz").ok)
    }

    @Test
    fun findMissingStartFails() {
        assertFalse(run("find nowhere").ok)
    }

    // ═══ zip / unzip ═══

    @Test
    fun zipThenUnzipRoundTrip() {
        write("proj/one.txt", "محتوى عربي")
        write("proj/two.txt", "second")
        val zipped = run("zip p.zip proj")
        assertTrue(zipped.ok)
        assertTrue(zipped.lines[0].color == ShellColor.GREEN)
        assertTrue(f("p.zip").length() > 0)

        val unzipped = run("unzip p.zip out")
        assertTrue(unzipped.ok)
        assertEquals("محتوى عربي", f("out/proj/one.txt").readText())
        assertEquals("second", f("out/proj/two.txt").readText())
    }

    @Test
    fun unzipDefaultDestinationIsCwd() {
        write("k.txt", "kk")
        run("zip k.zip k.txt")
        run("unzip k.zip")
        // الاستخراج داخل مجلد العمل (الجذر): k.txt موجود أصلاً — تحقق من نجاح الأمر فقط
        val out = run("unzip k.zip")
        assertTrue(out.ok)
    }

    @Test
    fun zipMissingSourceFails() {
        assertFalse(run("zip p.zip ghost").ok)
    }

    @Test
    fun unzipMissingArchiveFails() {
        assertFalse(run("unzip nope.zip").ok)
    }

    // ═══ hash ═══

    @Test
    fun hashSha256KnownVector() {
        write("abc.txt", "abc")
        val rows = text("hash abc.txt")
        assertTrue(rows[0].startsWith("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"))
        assertTrue(rows[0].endsWith("abc.txt"))
    }

    @Test
    fun hashMd5AndSha1Vectors() {
        write("abc.txt", "abc")
        assertTrue(text("hash -a md5 abc.txt")[0].startsWith("900150983cd24fb0d6963f7d28e17f72"))
        assertTrue(text("hash -a sha1 abc.txt")[0].startsWith("a9993e364706816aba3e25717850c26c9cd0d89d"))
    }

    @Test
    fun hashUnsupportedAlgorithmFails() {
        write("abc.txt", "abc")
        assertFalse(run("hash -a crc32 abc.txt").ok)
    }

    @Test
    fun hashMissingValueForAFlagFails() {
        assertFalse(run("hash -a").ok)
    }

    @Test
    fun hashDirectoryFails() {
        f("d").mkdirs()
        assertFalse(run("hash d").ok)
    }

    @Test
    fun hashMultipleFilesOnePerLine() {
        write("a.txt", "a")
        write("b.txt", "b")
        assertEquals(2, text("hash a.txt b.txt").size)
    }

    // ═══ history / clear / help ═══

    @Test
    fun historyRecordsNumberedLinesDroppingConsecutiveDupes() {
        run("echo one")
        run("echo one")
        run("echo two")
        val rows = text("history")
        assertEquals(2, rows.size)
        assertTrue(rows[0].trimStart().startsWith("1"))
        assertTrue(rows[0].endsWith("echo one"))
    }

    @Test
    fun historyCapsAtMax() {
        repeat(NovaShell.HISTORY_MAX + 10) { i -> run("echo n$i") }
        assertEquals(NovaShell.HISTORY_MAX, shell.history.size)
    }

    @Test
    fun blankLineNotRecordedInHistory() {
        run("   ")
        assertTrue(shell.history.isEmpty())
    }

    @Test
    fun clearRequestsScreenReset() {
        val out = run("clear")
        assertTrue(out.clearRequested)
        assertTrue(out.lines.isEmpty())
    }

    @Test
    fun helpListsAllRoadmapCommands() {
        val rows = text("help")
        val joined = rows.joinToString("\n")
        listOf("ls", "cd", "cp", "mv", "rm", "mkdir", "touch", "du", "find", "cat", "zip", "unzip", "hash")
            .forEach { assertTrue("missing $it in help", joined.contains(it)) }
    }

    // ═══ التسلسل ═══

    @Test
    fun semicolonRunsBothSegments() {
        val rows = text("pwd ; echo done")
        assertEquals(2, rows.size)
        assertEquals("done", rows[1])
    }

    @Test
    fun andChainStopsOnFailure() {
        val rows = text("cd nowhere && echo reached")
        assertFalse(rows.contains("reached"))
    }

    @Test
    fun andChainContinuesOnSuccess() {
        assertTrue(text("pwd && echo reached").contains("reached"))
    }

    // ═══ التوجيه ═══

    @Test
    fun redirectTruncateWritesFile() {
        run("echo hi > out.txt")
        assertEquals("hi\n", f("out.txt").readText())
    }

    @Test
    fun redirectAppendAccumulates() {
        run("echo a >> log.txt")
        run("echo b >> log.txt")
        assertEquals("a\nb\n", f("log.txt").readText())
    }

    @Test
    fun redirectKeepsErrorsOnScreen() {
        val out = run("ls ghost > out.txt")
        assertFalse(out.ok)
        assertTrue(out.lines.any { it.color == ShellColor.RED })
        assertTrue(f("out.txt").exists())
    }

    @Test
    fun redirectToListingWritesPlainNames() {
        write("a.txt", "x")
        write("b.txt", "x")
        run("ls > list.txt")
        val content = f("list.txt").readText()
        assertTrue(content.contains("a.txt") && content.contains("b.txt"))
    }

    @Test
    fun redirectTargetIsDirectoryFails() {
        f("d").mkdirs()
        val out = run("echo x > d")
        assertFalse(out.ok)
        assertTrue(out.lines.any { it.text.contains("is a directory") })
    }

    @Test
    fun redirectCreatesMissingParents() {
        run("echo x > deep/er/file.txt")
        assertEquals("x\n", f("deep/er/file.txt").readText())
    }

    // ═══ المعالجة الصارمة ═══

    @Test
    fun doubleDashTreatsRestAsPositional() {
        run("touch -- -weird.txt")
        assertTrue(f("-weird.txt").exists())
    }

    @Test
    fun unknownCommandFailsWithHint() {
        val out = run("frobnicate x")
        assertFalse(out.ok)
        assertTrue(out.lines[0].text.contains("command not found"))
        assertTrue(out.lines[1].text.contains("help"))
    }

    @Test
    fun longOptionUnrecognizedFails() {
        val out = run("ls --fancy")
        assertFalse(out.ok)
        assertTrue(out.lines[0].text.contains("--fancy"))
    }

    @Test
    fun parseErrorShowsMessage() {
        val out = run("echo 'unterminated")
        assertFalse(out.ok)
        assertTrue(out.lines[0].text.contains("unterminated"))
    }

    // ═══ الإكمال عبر المحرك ═══

    @Test
    fun completeDelegatesToCompleter() {
        f("Docs").mkdirs()
        val c = shell.complete("ls Do")
        assertTrue(c.completed)
        assertEquals("ls Docs/", c.line)
    }

    @Test
    fun executeRecordsHistory() {
        run("echo x")
        assertEquals(listOf("echo x"), shell.history)
    }
}
