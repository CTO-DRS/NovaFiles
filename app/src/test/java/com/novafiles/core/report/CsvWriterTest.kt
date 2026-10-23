package com.novafiles.core.report

import com.novafiles.app.core.report.CsvWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات كاتب CSV — RFC 4180: تهريب الفواصل والاقتباسات والأسطر، BOM، CRLF.
 */
class CsvWriterTest {

    @Test
    fun `plain fields need no quoting`() {
        assertEquals("abc", CsvWriter.escapeField("abc"))
        assertEquals("", CsvWriter.escapeField(""))
        assertEquals("تقرير 2026", CsvWriter.escapeField("تقرير 2026"))
        assertEquals("a b/c-d", CsvWriter.escapeField("a b/c-d"))
    }

    @Test
    fun `comma quote and newlines force quoting`() {
        assertEquals("\"a,b\"", CsvWriter.escapeField("a,b"))
        assertEquals("\"a\"\"b\"", CsvWriter.escapeField("a\"b"))
        assertEquals("\"a\nb\"", CsvWriter.escapeField("a\nb"))
        assertEquals("\"a\rb\"", CsvWriter.escapeField("a\rb"))
    }

    @Test
    fun `quote doubling inside quoted field`() {
        assertEquals("\"he said \"\"hi\"\"\"", CsvWriter.escapeField("he said \"hi\""))
    }

    @Test
    fun `write joins rows with CRLF`() {
        val out = CsvWriter.write(
            listOf(listOf("name", "size"), listOf("a.mp4", "123")),
            bom = false,
        )
        assertEquals("name,size\r\na.mp4,123", out)
    }

    @Test
    fun `bom prepended when requested`() {
        val out = CsvWriter.write(listOf(listOf("x")), bom = true)
        assertTrue(out.startsWith("\uFEFF"))
        assertEquals("\uFEFFx", out)
        val noBom = CsvWriter.write(listOf(listOf("x")), bom = false)
        assertFalse(noBom.startsWith("\uFEFF"))
    }

    @Test
    fun `arabic content survives unharmed`() {
        val out = CsvWriter.write(
            listOf(listOf("الاسم", "الحجم"), listOf("تقرير 2026.pdf", "2048")),
            bom = true,
        )
        assertTrue(out.contains("الاسم"))
        assertTrue(out.contains("تقرير 2026.pdf"))
        assertFalse(out.contains("\"تقرير 2026.pdf\"")) // لا اقتباسات غير ضرورية
    }

    @Test
    fun `path with comma gets quoted in row`() {
        val out = CsvWriter.write(listOf(listOf("/sd/a,b.mp4", "5")), bom = false)
        assertEquals("\"/sd/a,b.mp4\",5", out)
    }

    @Test
    fun `empty rows produce empty output`() {
        assertEquals("\uFEFF", CsvWriter.write(emptyList(), bom = true))
        assertEquals("", CsvWriter.write(emptyList(), bom = false))
    }
}
