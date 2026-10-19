package com.novafiles.app.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات محرك الثيمات (v0.17) — JVM خالصة.
 */
class ThemeEngineTest {

    // ---------- PaletteEngine: أدوات لونية ----------

    @Test
    fun `rgb roundtrip`() {
        val c = 0xFF5B57C2.toInt()
        val (r, g, b) = PaletteEngine.rgb(c)
        assertEquals(0x5B, r)
        assertEquals(0x57, g)
        assertEquals(0xC2, b)
        assertEquals(c, PaletteEngine.argb(r, g, b) or 0)
    }

    @Test
    fun `hsl conversion keeps hue for pure red`() {
        val (h, s, l) = PaletteEngine.toHsl(0xFFFF0000.toInt())
        assertEquals(0.0, h, 0.5)
        assertEquals(1.0, s, 0.01)
        assertEquals(0.5, l, 0.01)
        // ذهاب وعودة
        assertEquals(0xFFFF0000.toInt(), PaletteEngine.fromHsl(h, s, l))
    }

    @Test
    fun `hsl gray has zero saturation`() {
        val (_, s, _) = PaletteEngine.toHsl(0xFF808080.toInt())
        assertEquals(0.0, s, 0.001)
    }

    @Test
    fun `contrast black vs white is 21`() {
        assertEquals(21.0, PaletteEngine.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test
    fun `contrast same color is 1`() {
        assertEquals(1.0, PaletteEngine.contrastRatio(0xFF123456.toInt(), 0xFF123456.toInt()), 0.001)
    }

    @Test
    fun `contrast is symmetric`() {
        val a = 0xFF5B57C2.toInt()
        val b = 0xFFF2F2F7.toInt()
        assertEquals(
            PaletteEngine.contrastRatio(a, b),
            PaletteEngine.contrastRatio(b, a),
            1e-9,
        )
    }

    @Test
    fun `onColor picks white on dark background and black on light`() {
        assertEquals(0xFFFFFFFF.toInt(), PaletteEngine.onColor(0xFF000000.toInt()))
        assertEquals(0xFF111111.toInt(), PaletteEngine.onColor(0xFFFFEB3B.toInt()))
    }

    @Test
    fun `mixOver with alpha 1 equals foreground`() {
        val f = 0xFF336699.toInt()
        assertEquals(f, PaletteEngine.mixOver(f, 0xFF000000.toInt(), 1.0))
    }

    @Test
    fun `mixOver with alpha 0 equals background`() {
        val b = 0xFF336699.toInt()
        assertEquals(b, PaletteEngine.mixOver(0xFFFF0000.toInt(), b, 0.0))
    }

    // ---------- جودة اللوحات الـ12: قيود جمالية صارمة ----------

    @Test
    fun `all 12 presets have unique ids`() {
        assertEquals(12, PaletteEngine.PRESETS.size)
        assertEquals(12, PaletteEngine.PRESETS.map { it.id }.toSet().size)
    }

    @Test
    fun `every preset accent reads on white card in light mode`() {
        // أيقونة/زر اللون المميز يجب أن يكون مرئياً على البطاقة الفاتحة (≥3.0)
        PaletteEngine.PRESETS.forEach { p ->
            val fam = PaletteEngine.family(p, dark = false)
            val ratio = PaletteEngine.contrastRatio(fam.accentDeep, 0xFFFFFFFF.toInt())
            assertTrue("preset ${p.id} accentDeep غير مقروء على الفاتح: $ratio", ratio >= 3.0)
        }
    }

    @Test
    fun `every preset accent readable in dark mode`() {
        // في الداكن: accent فوق بطاقة iOS الداكنة ≥3.0
        PaletteEngine.PRESETS.forEach { p ->
            val fam = PaletteEngine.family(p, dark = true)
            val ratio = PaletteEngine.contrastRatio(fam.accent, 0xFF1C1C1E.toInt())
            assertTrue("preset ${p.id} accent غير مقروء على الداكن: $ratio", ratio >= 3.0)
        }
    }

    @Test
    fun `nova preset is the default and unknown ids fall back to it`() {
        assertEquals("nova", PaletteEngine.preset(null).id)
        assertEquals("nova", PaletteEngine.preset("no_such").id)
        assertTrue(PaletteEngine.isKnown("ocean"))
        assertFalse(PaletteEngine.isKnown("nope"))
        assertFalse(PaletteEngine.isKnown(null))
    }

    @Test
    fun `light and dark variants differ`() {
        PaletteEngine.PRESETS.forEach { p ->
            val light = PaletteEngine.family(p, dark = false)
            val dark = PaletteEngine.family(p, dark = true)
            assertTrue("preset ${p.id}: الفاتح والداكن متطابقان", light.accent != dark.accent)
        }
    }

    // ---------- FolderAccentEngine ----------

    @Test
    fun `folder accent nearest ancestor wins`() {
        val engine = FolderAccentEngine(
            mapOf("/sdcard/Media" to "ocean", "/sdcard/Media/Photos" to "rose"),
        )
        assertEquals("rose", engine.resolve("/sdcard/Media/Photos/2026"))
        assertEquals("ocean", engine.resolve("/sdcard/Media/Music"))
        assertEquals(null, engine.resolve("/sdcard/Docs"))
    }

    @Test
    fun `folder accent path itself first`() {
        val engine = FolderAccentEngine(mapOf("/a/b" to "teal"))
        assertEquals("teal", engine.resolve("/a/b"))
    }

    @Test
    fun `set rejects unknown accent ids`() {
        val engine = FolderAccentEngine()
        engine.set("/x", "nope")
        assertEquals(0, engine.size)
        engine.set("/x", "mint")
        assertEquals("mint", engine.resolve("/x"))
    }

    @Test
    fun `unset makes inherit from parent`() {
        val engine = FolderAccentEngine()
        engine.set("/a", "forest")
        engine.set("/a/child", "amber")
        engine.unset("/a/child")
        assertEquals("forest", engine.resolve("/a/child/sub"))
    }

    @Test
    fun `serialize deserialize roundtrip`() {
        val engine = FolderAccentEngine()
        engine.set("/sdcard/Pics", "rose")
        engine.set("/sdcard/Work", "graphite")
        val restored = FolderAccentEngine.deserialize(engine.serialize())
        assertEquals("rose", restored.resolve("/sdcard/Pics/a.jpg"))
        assertEquals("graphite", restored.resolve("/sdcard/Work/x"))
        assertEquals(2, restored.size)
    }

    @Test
    fun `deserialize skips broken lines`() {
        val restored = FolderAccentEngine.deserialize(
            """
            /good=teal
            broken-line
            =no_path
            /unknown-accent=zzz
            //double/slash=amber
            """.trimIndent(),
        )
        assertEquals("teal", restored.resolve("/good/child"))
        assertEquals("amber", restored.resolve("/double/slash"))
        assertEquals(2, restored.size)
    }

    @Test
    fun `normalize folds double slashes and trailing slash`() {
        assertEquals("/a/b", FolderAccentEngine.normalize("/a//b/"))
        assertEquals("/", FolderAccentEngine.normalize("///"))
        assertEquals("/", FolderAccentEngine.parentOf("/"))
        assertEquals("/", FolderAccentEngine.parentOf("/a"))
        assertEquals("/a", FolderAccentEngine.parentOf("/a/b"))
    }

    // ---------- IconPackEngine ----------

    @Test
    fun `pack parsing honors first entry wins`() {
        val pack = IconPackEngine(
            """
            # my pack
            [icons]
            pdf=badge_pdf
            PDF=badge_again
            """.trimIndent(),
        )
        assertEquals("badge_pdf", pack.pack.entries["pdf"])
        assertEquals(1, pack.pack.entries.size)
    }

    @Test
    fun `pack default star resolves unknown extensions`() {
        val pack = IconPackEngine("*=badge_generic\npdf=badge_pdf")
        assertEquals("badge_pdf", pack.resolve("a.pdf") { "builtin" })
        assertEquals("badge_generic", pack.resolve("a.xyz") { "builtin" })
        assertEquals("badge_generic", pack.resolve("noext") { "builtin" })
    }

    @Test
    fun `pack falls back to builtin when no default`() {
        val pack = IconPackEngine("pdf=badge_pdf")
        assertEquals("builtin_doc", pack.resolve("a.docx") { "builtin_doc" })
    }

    @Test
    fun `pack invalid lines are skipped and counted`() {
        val pack = IconPackEngine(
            """
            no_equals_here
            bad ICON=has space
            ok=badge_ok
            #comment=ignored
            =empty_key
            """.trimIndent(),
        )
        assertEquals(mapOf("ok" to "badge_ok"), pack.pack.entries)
        assertEquals(3, pack.pack.skippedLines) // no_equals, bad ICON, =empty
    }

    @Test
    fun `pack caps entries at 512`() {
        val sb = StringBuilder()
        for (i in 0 until 600) sb.append("ext$i=icon$i\n")
        val pack = IconPackEngine(sb.toString())
        assertEquals(512, pack.pack.entries.size)
    }

    @Test
    fun `empty pack is invalid`() {
        assertFalse(IconPackEngine("# just a comment\n").isValid)
        assertTrue(IconPackEngine("*=fallback\n").isValid)
    }

    @Test
    fun `pack matches extension case-insensitively`() {
        val pack = IconPackEngine("jpg=badge_photo")
        assertEquals("badge_photo", pack.resolve("Holiday.JPG") { "builtin" })
    }
}
