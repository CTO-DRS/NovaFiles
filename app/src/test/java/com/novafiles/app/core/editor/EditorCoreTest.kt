package com.novafiles.app.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات Editor 2.0 (v0.17) — Markdown، تلوين الشيفرة، والملفات الضخمة.
 * JVM خالصة.
 */
class EditorCoreTest {

    // ---------- MarkdownRenderer ----------

    @Test
    fun `headings levels parsed`() {
        val blocks = MarkdownRenderer.parse("# أولاً\n## ثانياً\n###### سادس")
        val hs = blocks.filterIsInstance<MdBlock.Heading>()
        assertEquals(3, hs.size)
        assertEquals(1, hs[0].level)
        assertEquals(2, hs[1].level)
        assertEquals(6, hs[2].level)
    }

    @Test
    fun `paragraph groups consecutive lines`() {
        val blocks = MarkdownRenderer.parse("سطر أول\nسطر ثانٍ\n\nفقرة جديدة")
        val ps = blocks.filterIsInstance<MdBlock.Paragraph>()
        assertEquals(2, ps.size)
        assertEquals("سطر أول سطر ثانٍ", MarkdownRenderer.plainText(listOf(ps[0])))
    }

    @Test
    fun `unordered and ordered lists`() {
        val blocks = MarkdownRenderer.parse("- تفاحة\n- موزة\n\n1. واحد\n2. اثنان")
        val items = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(4, items.size)
        assertFalse(items[0].ordered)
        assertEquals("تفاحة", items[0].spans[0].text)
        assertTrue(items[2].ordered)
        assertEquals(1, items[2].number)
        assertEquals(2, items[3].number)
    }

    @Test
    fun `nested list indent levels`() {
        val blocks = MarkdownRenderer.parse("- أ\n  - ب\n    - ج")
        val items = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(3, items.size)
        assertEquals(0, items[0].indent)
        assertEquals(1, items[1].indent)
        assertEquals(2, items[2].indent)
    }

    @Test
    fun `blockquote groups lines`() {
        val blocks = MarkdownRenderer.parse("> سطر 1\n> سطر 2")
        val q = blocks.filterIsInstance<MdBlock.Quote>()
        assertEquals(1, q.size)
        assertEquals("سطر 1 سطر 2", q[0].spans[0].text)
    }

    @Test
    fun `code fence captures lang and raw lines`() {
        val src = "```kotlin\nval x = 1\nval y = 2\n```"
        val blocks = MarkdownRenderer.parse(src)
        val code = blocks.filterIsInstance<MdBlock.CodeFence>().single()
        assertEquals("kotlin", code.lang)
        assertEquals(listOf("val x = 1", "val y = 2"), code.lines)
    }

    @Test
    fun `unclosed code fence consumes to end`() {
        val blocks = MarkdownRenderer.parse("```\nسطر1\nسطر2")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.CodeFence
        assertEquals(2, code.lines.size)
    }

    @Test
    fun `horizontal rule`() {
        val blocks = MarkdownRenderer.parse("أ\n\n---\n\nب")
        assertTrue(blocks.any { it == MdBlock.Rule })
    }

    @Test
    fun `inline bold italic code strike`() {
        val spans = MarkdownRenderer.parseInline("**عريض** *مائل* `شيفرة` ~~مشطوب~~")
        // الفواصل النصية تبقى امتدادات عادية — نرشح المُنسّقة فقط
        val styled = spans.filter { it.styles.isNotEmpty() }
        assertEquals(4, styled.size)
        assertEquals(setOf(InlineStyle.BOLD), styled[0].styles)
        assertEquals(setOf(InlineStyle.ITALIC), styled[1].styles)
        assertEquals(setOf(InlineStyle.CODE), styled[2].styles)
        assertEquals(setOf(InlineStyle.STRIKE), styled[3].styles)
    }

    @Test
    fun `inline link`() {
        val spans = MarkdownRenderer.parseInline("[الموقع](https://example.com)")
        assertEquals(1, spans.size)
        assertEquals(InlineStyle.LINK, spans[0].styles.single())
        assertEquals("الموقع", spans[0].text)
    }

    @Test
    fun `bold contains code nested`() {
        val spans = MarkdownRenderer.parseInline("**ت:`code`**")
        // «ت:» عريض عادي + «code» عريض وشيفرة معاً
        assertEquals(2, spans.size)
        assertEquals(setOf(InlineStyle.BOLD), spans[0].styles)
        assertEquals(setOf(InlineStyle.BOLD, InlineStyle.CODE), spans[1].styles)
        assertEquals("code", spans[1].text)
    }

    @Test
    fun `unclosed markers stay literal`() {
        val spans = MarkdownRenderer.parseInline("نص ** بلا إغلاق و ` شيفرة مفتوحة")
        // لا استثناءات — كل شيء نص عادي
        spans.forEach { assertTrue(it.styles.isEmpty()) }
    }

    @Test
    fun `plain text strips formatting`() {
        val blocks = MarkdownRenderer.parse("# عنوان\nفقرة **عريض** و`شيفرة`\n```py\nprint(1)\n```")
        val plain = MarkdownRenderer.plainText(blocks)
        assertTrue(plain.contains("عنوان"))
        assertTrue(plain.contains("عريض"))
        assertTrue(plain.contains("print(1)"))
        assertFalse(plain.contains("**"))
    }

    @Test
    fun `arabic and emoji content survives parse`() {
        val blocks = MarkdownRenderer.parse("## ملاحظة 📝\nملف عربي — مائلة *حرف*")
        val h = blocks[0] as MdBlock.Heading
        assertEquals("ملاحظة 📝", h.spans[0].text)
    }

    // ---------- SyntaxHighlighter ----------

    @Test
    fun `language detection from extension`() {
        assertEquals("kotlin", SyntaxHighlighter.languageOf("Main.kt"))
        assertEquals("python", SyntaxHighlighter.languageOf("app.py"))
        assertEquals("xml", SyntaxHighlighter.languageOf("AndroidManifest.xml"))
        assertNull(SyntaxHighlighter.languageOf("photo.jpg"))
        assertNull(SyntaxHighlighter.languageOf("noext"))
    }

    @Test
    fun `kotlin line keywords strings comments`() {
        val (tokens, _) = SyntaxHighlighter.highlightLine("val x = \"hi\" // ملاحظة", "kotlin")
        val kinds = tokens.map { it.kind }
        assertTrue(TokenKind.KEYWORD in kinds)
        assertTrue(TokenKind.STRING in kinds)
        assertTrue(TokenKind.COMMENT in kinds)
        // الكلمة المفتاحية val في البداية
        assertEquals("val", "val x = \"hi\" // ملاحظة".substring(tokens.first().start, tokens.first().end))
    }

    @Test
    fun `multiline kotlin block comment state carried`() {
        val lines = listOf("/* بداية", "وسط", "نهاية */ val x = 1")
        val hl = SyntaxHighlighter.highlight(lines, "kotlin")
        assertTrue(hl[0].any { it.kind == TokenKind.COMMENT })
        assertTrue(hl[1].all { it.kind == TokenKind.COMMENT })
        assertTrue(hl[2].any { it.kind == TokenKind.COMMENT })
        assertTrue(hl[2].any { it.kind == TokenKind.KEYWORD })
    }

    @Test
    fun `python hash comment`() {
        val (tokens, _) = SyntaxHighlighter.highlightLine("x = 5  # تعليق", "python")
        assertTrue(tokens.any { it.kind == TokenKind.COMMENT })
        assertTrue(tokens.any { it.kind == TokenKind.NUMBER })
    }

    @Test
    fun `json property keys`() {
        val (tokens, _) = SyntaxHighlighter.highlightLine("\"name\": \"nova\",", "json")
        assertTrue(tokens.any { it.kind == TokenKind.PROPERTY })
        assertTrue(tokens.any { it.kind == TokenKind.STRING })
    }

    @Test
    fun `xml tags and comments`() {
        val (t1, _) = SyntaxHighlighter.highlightLine("<activity android:name=\".Main\">", "xml")
        assertTrue(t1.any { it.kind == TokenKind.TAG })
        assertTrue(t1.any { it.kind == TokenKind.STRING })
        val (t2, inBlock) = SyntaxHighlighter.highlightLine("<!-- تعليق", "xml")
        assertTrue(t2.any { it.kind == TokenKind.COMMENT })
        assertTrue(inBlock)
    }

    @Test
    fun `escaped quote inside string does not end early`() {
        val (tokens, _) = SyntaxHighlighter.highlightLine("val s = \"a\\\"b\" // x", "kotlin")
        val s = tokens.filter { it.kind == TokenKind.STRING }.single()
        // السلسلة تشمل الاقتباسين والمهرِب: " a \ " b " = 6 محارف
        assertEquals(6, s.length)
    }

    @Test
    fun `numbers with suffixes`() {
        val (tokens, _) = SyntaxHighlighter.highlightLine("x = 0xFF + 1_000 + 3.14f", "kotlin")
        assertEquals(3, tokens.filter { it.kind == TokenKind.NUMBER }.size)
    }

    @Test
    fun `sql case insensitive keywords`() {
        val (tokens, _) = SyntaxHighlighter.highlightLine("SELECT name FROM users;", "sql")
        assertEquals(2, tokens.filter { it.kind == TokenKind.KEYWORD }.size)
    }

    @Test
    fun `unknown language yields no tokens`() {
        val (tokens, _) = SyntaxHighlighter.highlightLine("anything here", "unknown")
        assertTrue(tokens.isEmpty())
    }

    // ---------- VirtualTextBuffer ----------

    private class FakeReader(private val data: ByteArray) : VirtualTextBuffer.PageReader {
        override val length: Long = data.size.toLong()
        override fun readAt(offset: Long, len: Int): ByteArray {
            val start = offset.toInt()
            val end = minOf(start + len, data.size)
            return data.copyOfRange(start, end)
        }
    }

    private fun buf(content: String, step: Int = 512) =
        VirtualTextBuffer(FakeReader(content.toByteArray()), indexStep = step)

    @Test
    fun `line count with and without trailing newline`() {
        assertEquals(3, buf("أ\nب\nج").lineCount)
        assertEquals(3, buf("أ\nب\nج\n").lineCount)
        assertEquals(1, buf("").lineCount)
    }

    @Test
    fun `window around center line`() {
        val content = (1..100).joinToString("\n") { "سطر$it" }
        val v = buf(content, step = 4)
        // الفهرسة صفرية: centerLine=49 → السطران قبله وبعده = سطر48..سطر52
        val window = v.readWindow(centerLine = 49, contextLines = 2)
        assertEquals(listOf("سطر48", "سطر49", "سطر50", "سطر51", "سطر52"), window)
    }

    @Test
    fun `window at file start clamps`() {
        val content = (1..10).joinToString("\n") { "L$it" }
        val v = buf(content, step = 3)
        val w = v.readWindow(0, 5)
        assertEquals("L1", w.first())
    }

    @Test
    fun `window at file end clamps`() {
        val content = (1..10).joinToString("\n") { "L$it" }
        val v = buf(content, step = 3)
        val w = v.readWindow(9, 5)
        assertEquals("L10", w.last())
    }

    @Test
    fun `read single line exact`() {
        val content = (1..50).joinToString("\n") { "X$it" }
        val v = buf(content, step = 7)
        assertEquals("X33", v.readLine(32))
        assertEquals("X1", v.readLine(0))
        assertEquals("X50", v.readLine(49))
    }

    @Test
    fun `binary file detected and refused`() {
        val binary = byteArrayOf(0x50, 0x4B, 0x00, 0x00, 0x01)
        val v = VirtualTextBuffer(FakeReader(binary))
        assertFalse(v.isText)
        assertEquals(0, v.lineCount)
        try {
            v.readWindow(0)
            org.junit.Assert.fail("كان يجب أن يرمي")
        } catch (e: IllegalStateException) {
            assertEquals("ملف ثنائي — لا يمكن عرضه كنص", e.message)
        }
    }

    @Test
    fun `empty file is text with one empty line`() {
        val v = buf("")
        assertTrue(v.isText)
        assertEquals(1, v.lineCount)
        assertEquals(listOf(""), v.readWindow(0))
    }

    @Test
    fun `utf8 bom detected`() {
        val data = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "سلام".toByteArray()
        val v = VirtualTextBuffer(FakeReader(data))
        // BOM يبقى محرف U+FEFF في بداية السطر (سلوك فك الترميز القياسي)
        assertTrue(v.readLine(0)!!.endsWith("سلام"))
        assertEquals("UTF-8", VirtualTextBuffer.detectCharset(FakeReader(data)))
    }

    @Test
    fun `large simulated file with sparse index stays accurate`() {
        // 20k سطر مع فهرس نادر كل 991 (رقم أولي كسر الأنماط)
        val content = (1..20000).joinToString("\n") { "N$it" }
        val v = buf(content, step = 991)
        assertEquals(20000, v.lineCount)
        assertEquals("N1", v.readLine(0))
        assertEquals("N991", v.readLine(990))
        assertEquals("N10000", v.readLine(9999))
        assertEquals("N20000", v.readLine(19999))
        // نافذة تعبر حدود الفهرسة
        val w = v.readWindow(5000, 3)
        assertEquals(listOf("N4998", "N4999", "N5000", "N5001", "N5002", "N5003", "N5004"), w)
    }
}
