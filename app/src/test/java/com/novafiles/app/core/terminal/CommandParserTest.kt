package com.novafiles.app.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات محلل الأوامر — خالصة على JVM، تغطي الاقتباس والتهريب
 * والتسلسل وتوجيه الإخراج والتعليقات والرفض الواضح.
 */
class CommandParserTest {

    private fun ok(raw: String): ParsedLine =
        (CommandParser.parse(raw) as ParseResult.Ok).line

    private fun err(raw: String): String =
        (CommandParser.parse(raw) as ParseResult.Err).message

    // ═══ الأساسيات ═══

    @Test
    fun blankLineIsEmpty() {
        assertTrue(ok("").isEmpty)
        assertTrue(ok("   ").isEmpty)
        assertTrue(ok("\t  \t").isEmpty)
    }

    @Test
    fun simpleCommand() {
        val line = ok("ls -la /tmp")
        assertEquals(1, line.segments.size)
        val seg = line.segments[0]
        assertEquals(listOf("ls", "-la", "/tmp"), seg.tokens)
        assertNull(seg.redirect)
        assertEquals(Joiner.FIRST, seg.joiner)
    }

    @Test
    fun multipleSpacesAndTabsCollapse() {
        val seg = ok("  echo   a\t\tb  ").segments[0]
        assertEquals(listOf("echo", "a", "b"), seg.tokens)
    }

    // ═══ الاقتباس ═══

    @Test
    fun singleQuotesAreLiteral() {
        val seg = ok("echo 'a  b   c'").segments[0]
        assertEquals(listOf("echo", "a  b   c"), seg.tokens)
    }

    @Test
    fun doubleQuotesCollapseSpaces() {
        val seg = ok("echo \"my file.txt\"").segments[0]
        assertEquals(listOf("echo", "my file.txt"), seg.tokens)
    }

    @Test
    fun doubleQuoteEscapes() {
        val seg = ok("echo \"a\\\"b\\\\c\"").segments[0]
        assertEquals(listOf("echo", "a\"b\\c"), seg.tokens)
    }

    @Test
    fun quotedAndBarePartsJoinIntoOneToken() {
        val seg = ok("echo ab\"cd ef\"gh").segments[0]
        // المسافة داخل الاقتباس تُحفظ — كل المقاطع تلتحق بوسيط واحد
        assertEquals(listOf("echo", "abcd efgh"), seg.tokens)
    }

    @Test
    fun escapeOutsideQuotes() {
        val seg = ok("echo my\\ file.txt").segments[0]
        assertEquals(listOf("echo", "my file.txt"), seg.tokens)
    }

    @Test
    fun dollarInsideQuotesStaysLiteral() {
        val seg = ok("echo \"\$HOME\"").segments[0]
        assertEquals(listOf("echo", "\$HOME"), seg.tokens)
    }

    @Test
    fun unterminatedSingleQuoteErrors() {
        assertTrue(err("echo 'abc").contains("unterminated"))
    }

    @Test
    fun unterminatedDoubleQuoteErrors() {
        assertTrue(err("echo \"abc").contains("unterminated"))
    }

    @Test
    fun trailingBackslashErrors() {
        assertTrue(err("echo abc\\").contains("backslash"))
    }

    // ═══ التسلسل ═══

    @Test
    fun semicolonChainsTwoCommands() {
        val line = ok("pwd ; ls /tmp")
        assertEquals(2, line.segments.size)
        assertEquals(listOf("pwd"), line.segments[0].tokens)
        assertEquals(Joiner.FIRST, line.segments[0].joiner)
        assertEquals(listOf("ls", "/tmp"), line.segments[1].tokens)
        assertEquals(Joiner.THEN, line.segments[1].joiner)
    }

    @Test
    fun andChainMarksJoiner() {
        val line = ok("mkdir d && cd d")
        assertEquals(2, line.segments.size)
        assertEquals(Joiner.AND_THEN, line.segments[1].joiner)
    }

    @Test
    fun chainWithoutSpaces() {
        val line = ok("pwd&&ls;echo x")
        assertEquals(3, line.segments.size)
        assertEquals(listOf("pwd"), line.segments[0].tokens)
        assertEquals(Joiner.AND_THEN, line.segments[1].joiner)
        assertEquals(listOf("echo", "x"), line.segments[2].tokens)
    }

    @Test
    fun mixedQuotesInsideChain() {
        val line = ok("touch 'my file' && cat \"my file\"")
        assertEquals(listOf("touch", "my file"), line.segments[0].tokens)
        assertEquals(listOf("cat", "my file"), line.segments[1].tokens)
    }

    // ═══ توجيه الإخراج ═══

    @Test
    fun redirectTruncate() {
        val seg = ok("ls > out.txt").segments[0]
        assertEquals(listOf("ls"), seg.tokens)
        assertEquals(ShellRedirect("out.txt", false), seg.redirect)
    }

    @Test
    fun redirectAppend() {
        val seg = ok("echo hi >> log.txt").segments[0]
        assertEquals(ShellRedirect("log.txt", true), seg.redirect)
    }

    @Test
    fun redirectTargetMayBeQuoted() {
        val seg = ok("ls > \"my out.txt\"").segments[0]
        assertEquals(ShellRedirect("my out.txt", false), seg.redirect)
    }

    @Test
    fun redirectWithoutTargetErrors() {
        assertTrue(err("ls >").contains("expected file"))
        assertTrue(err("ls >   ").contains("expected file"))
    }

    @Test
    fun danglingRedirectAloneErrors() {
        assertTrue(err("> out.txt").contains("dangling"))
    }

    // ═══ التعليقات والرفض ═══

    @Test
    fun commentAtTokenStartIgnored() {
        val line = ok("ls # show files")
        assertEquals(1, line.segments.size)
        assertEquals(listOf("ls"), line.segments[0].tokens)
    }

    @Test
    fun hashInsideTokenIsLiteral() {
        val seg = ok("echo a#b").segments[0]
        assertEquals(listOf("echo", "a#b"), seg.tokens)
    }

    @Test
    fun leadingSemicolonIsLenient() {
        val line = ok("; ls")
        assertEquals(1, line.segments.size)
        assertEquals(listOf("ls"), line.segments[0].tokens)
    }

    @Test
    fun pipeIsRejected() {
        assertTrue(err("ls | grep x").contains("pipes"))
        assertFalse(err("ls | grep x").isEmpty())
    }

    @Test
    fun singleAmpersandRejected() {
        assertTrue(err("ls &").contains("background"))
    }

    // ═══ readToken المستقل ═══

    @Test
    fun readTokenStopsAtSeparator() {
        val (tok, next) = CommandParser.readToken("abc;def", 0)
        assertEquals("abc", tok)
        assertEquals(3, next)
    }

    @Test
    fun readTokenHandlesUnclosedQuote() {
        val (tok, next) = CommandParser.readToken("'abc def", 0)
        assertEquals("abc def", tok)
        assertEquals(8, next)
    }
}
