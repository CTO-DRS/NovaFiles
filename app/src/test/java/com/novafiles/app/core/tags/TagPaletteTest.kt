package com.novafiles.app.core.tags

import com.novafiles.app.core.a11y.ContrastAuditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات TagPalette — لوحة 10 ألوان مُدقَّقة التباين (JVM خالص)
 */
class TagPaletteTest {

    @Test
    fun `palette has exactly ten distinct colors`() {
        assertEquals(10, TagPalette.all.size)
        assertEquals(10, TagPalette.all.toSet().size)
    }

    @Test
    fun `every color passes WCAG AA with white text`() {
        TagPalette.all.forEachIndexed { index, color ->
            val ratio = ContrastAuditor.wcagContrast(color, TagPalette.ON_COLOR)
            assertTrue(
                "color #$index fails AA: ratio=$ratio",
                ratio >= 4.5,
            )
        }
    }

    @Test
    fun `colorFor is deterministic for the same name`() {
        val names = listOf("work", "العمل", "🔥 مهم", "invoices 2026", "صور العائلة")
        names.forEach { name ->
            assertEquals(TagPalette.colorFor(name), TagPalette.colorFor(name))
            assertEquals(TagPalette.colorFor(name), TagsEngine.defaultColor(name))
        }
    }

    @Test
    fun `colorFor spreads across the palette`() {
        val names = (1..40).map { "tag-$it" } + listOf("a", "b", "c", "d", "e", "f")
        val used = names.map { TagPalette.colorFor(it) }.toSet()
        assertTrue("only ${used.size} colors used", used.size >= 5)
    }

    @Test
    fun `colorAt coerces out of range indices`() {
        assertEquals(TagPalette.colorAt(0), TagPalette.colorAt(-5))
        assertEquals(TagPalette.colorAt(9), TagPalette.colorAt(99))
    }
}
