package com.novafiles.app.core.editor

/**
 * v0.17 — عارض Markdown خالص (المجموعة الشائعة من CommonMark).
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * يدعم: العناوين 1-6، الفقرات، القوائم المرقمة وغير المرقمة (بثلاث مستويات
 * مسافة بادئة)، الاقتباسات، كتل الشيفرة المسوّرة، الفاصل الأفقي،
 * وداخل السطر: **عريض**، *مائل*، `شيفرة`، ~~مشطوب~~، [رابط](url).
 *
 * المخرجات نموذج كتل بامتدادات سطرية — تُحوَّل في UI إلى AnnotatedString.
 */

// ---------- النموذج ----------

data class InlineSpan(val text: String, val styles: Set<InlineStyle>) {
    constructor(text: String, vararg styles: InlineStyle) : this(text, styles.toSet())
}

enum class InlineStyle { BOLD, ITALIC, CODE, STRIKE, LINK }

data class LinkSpan(val span: InlineSpan, val url: String)

sealed class MdBlock {
    data class Heading(val level: Int, val spans: List<InlineSpan>) : MdBlock()
    data class Paragraph(val spans: List<InlineSpan>) : MdBlock()
    data class CodeFence(val lang: String, val lines: List<String>) : MdBlock()
    data class ListItem(val ordered: Boolean, val indent: Int, val number: Int?, val spans: List<InlineSpan>) : MdBlock()
    data class Quote(val spans: List<InlineSpan>) : MdBlock()
    object Rule : MdBlock()
}

object MarkdownRenderer {

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val UL_ITEM = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val OL_ITEM = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val QUOTE = Regex("^>\\s?(.*)$")
    private val FENCE = Regex("^```\\s*([A-Za-z0-9+#-]*)\\s*$")
    private val RULE = Regex("^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$")

    /** تحليل نص Markdown إلى كتل — لا يرمي استثناءات أبداً */
    fun parse(source: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = source.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                FENCE.matches(line) -> {
                    val lang = FENCE.matchEntire(line)!!.groupValues[1]
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !FENCE.matches(lines[i])) {
                        code.add(lines[i]); i++
                    }
                    i++ // تخطي سطر الإغلاق (أو نهاية النص)
                    blocks.add(MdBlock.CodeFence(lang, code))
                }
                HEADING.matches(line) -> {
                    val m = HEADING.matchEntire(line)!!
                    blocks.add(MdBlock.Heading(m.groupValues[1].length, parseInline(m.groupValues[2])))
                    i++
                }
                RULE.matches(line) -> { blocks.add(MdBlock.Rule); i++ }
                UL_ITEM.matches(line) -> {
                    val m = UL_ITEM.matchEntire(line)!!
                    blocks.add(
                        MdBlock.ListItem(false, indentLevel(m.groupValues[1]), null, parseInline(m.groupValues[2]))
                    )
                    i++
                }
                OL_ITEM.matches(line) -> {
                    val m = OL_ITEM.matchEntire(line)!!
                    blocks.add(
                        MdBlock.ListItem(true, indentLevel(m.groupValues[1]), m.groupValues[2].toIntOrNull(), parseInline(m.groupValues[3]))
                    )
                    i++
                }
                QUOTE.matches(line) -> {
                    // تجميع أسطر الاقتباس المتتالية في كتلة واحدة
                    val parts = mutableListOf<String>()
                    while (i < lines.size && QUOTE.matches(lines[i])) {
                        parts.add(QUOTE.matchEntire(lines[i])!!.groupValues[1]); i++
                    }
                    blocks.add(MdBlock.Quote(parseInline(parts.joinToString(" ").trim())))
                }
                line.isBlank() -> i++
                else -> {
                    // فقرة: تجميع الأسطر المتتالية غير الفارغة
                    val parts = mutableListOf<String>()
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !FENCE.matches(lines[i]) && !HEADING.matches(lines[i]) &&
                        !RULE.matches(lines[i]) && !UL_ITEM.matches(lines[i]) &&
                        !OL_ITEM.matches(lines[i]) && !QUOTE.matches(lines[i])
                    ) {
                        parts.add(lines[i]); i++
                    }
                    blocks.add(MdBlock.Paragraph(parseInline(parts.joinToString(" ").trim())))
                }
            }
        }
        return blocks
    }

    private fun indentLevel(ws: String): Int = ws.length / 2 + if (ws.length % 2 == 1) 1 else 0

    // ---------- الامتدادات السطرية ----------

    /** تحليل امتدادات السطر: عريض/مائل/شيفرة/مشطوب/رابط — متداخل جزئياً */
    fun parseInline(text: String): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        var buf = StringBuilder()
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            when {
                // شيفرة سطرية — أولوية قصوى، لا تحليل داخلها
                rest.startsWith("`") -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush(buf, spans); buf = StringBuilder()
                        spans.add(InlineSpan(text.substring(i + 1, end), InlineStyle.CODE))
                        i = end + 1
                    } else { buf.append('`'); i++ }
                }
                rest.startsWith("**") -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        flush(buf, spans); buf = StringBuilder()
                        // قد تحوي مائلاً أو شيفرة داخلية — تحليل متكرر بدمج الأنماط
                        parseNested(text.substring(i + 2, end), InlineStyle.BOLD, spans)
                        i = end + 2
                    } else { buf.append("**"); i += 2 }
                }
                rest.startsWith("~~") -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i) {
                        flush(buf, spans); buf = StringBuilder()
                        parseNested(text.substring(i + 2, end), InlineStyle.STRIKE, spans)
                        i = end + 2
                    } else { buf.append("~~"); i += 2 }
                }
                rest.startsWith("[") -> {
                    val close = text.indexOf(']', i + 1)
                    if (close > i + 1 && close + 1 < text.length && text[close + 1] == '(') {
                        val urlEnd = text.indexOf(')', close + 2)
                        if (urlEnd > close + 1) {
                            flush(buf, spans); buf = StringBuilder()
                            val label = text.substring(i + 1, close)
                            val url = text.substring(close + 2, urlEnd)
                            spans.add(InlineSpan(label, InlineStyle.LINK))
                            i = urlEnd + 1
                        } else { buf.append('['); i++ }
                    } else { buf.append('['); i++ }
                }
                rest.startsWith("*") -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i) {
                        flush(buf, spans); buf = StringBuilder()
                        parseNested(text.substring(i + 1, end), InlineStyle.ITALIC, spans)
                        i = end + 1
                    } else { buf.append('*'); i++ }
                }
                else -> { buf.append(text[i]); i++ }
            }
        }
        flush(buf, spans)
        return spans.filter { it.text.isNotEmpty() }
    }

    /** تحليل محتوى داخل امتداد (عريض يحوي مائلاً إلخ) — شيفرة فقط كامتداد ثانٍ */
    private fun parseNested(text: String, outer: InlineStyle, out: MutableList<InlineSpan>) {
        val inner = parseInlineNoBold(text)
        if (inner.size == 1 && inner[0].styles == setOf(InlineStyle.CODE)) {
            out.add(InlineSpan(inner[0].text, outer, InlineStyle.CODE))
        } else {
            inner.forEach { out.add(InlineSpan(it.text, it.styles + outer)) }
        }
    }

    /** نسخة مبسطة لا تعالج ** (لتجنب العودية اللانهائية) */
    private fun parseInlineNoBold(text: String): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        var buf = StringBuilder()
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            when {
                rest.startsWith("`") -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush(buf, spans); buf = StringBuilder()
                        spans.add(InlineSpan(text.substring(i + 1, end), InlineStyle.CODE))
                        i = end + 1
                    } else { buf.append('`'); i++ }
                }
                else -> { buf.append(text[i]); i++ }
            }
        }
        flush(buf, spans)
        return spans
    }

    private fun flush(buf: StringBuilder, out: MutableList<InlineSpan>) {
        if (buf.isNotEmpty()) {
            out.add(InlineSpan(buf.toString()))
            buf.setLength(0)
        }
    }

    /** نص مجرد من كل تنسيق — للبحث والفهرسة داخل المعاينة */
    fun plainText(blocks: List<MdBlock>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is MdBlock.Heading -> b.spans.joinToString("") { it.text }
            is MdBlock.Paragraph -> b.spans.joinToString("") { it.text }
            is MdBlock.CodeFence -> b.lines.joinToString("\n")
            is MdBlock.ListItem -> b.spans.joinToString("") { it.text }
            is MdBlock.Quote -> b.spans.joinToString("") { it.text }
            MdBlock.Rule -> "---"
        }
    }
}
