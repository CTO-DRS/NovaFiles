package com.novafiles.app.core.assoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات ارتباطات الملفات (v0.17) — MIME وحلال «فتح باستخدام». JVM خالصة.
 */
class AssocTest {

    // ---------- MimeResolver ----------

    @Test
    fun `common extensions map correctly`() {
        assertEquals("image/jpeg", MimeResolver.fromExtension("photo.jpg"))
        assertEquals("image/png", MimeResolver.fromExtension("shot.PNG"))
        assertEquals("video/mp4", MimeResolver.fromExtension("clip.mp4"))
        assertEquals("audio/mpeg", MimeResolver.fromExtension("song.mp3"))
        assertEquals("application/pdf", MimeResolver.fromExtension("cv.pdf"))
        assertEquals("application/zip", MimeResolver.fromExtension("pack.zip"))
        assertEquals("application/vnd.android.package-archive", MimeResolver.fromExtension("app.apk"))
    }

    @Test
    fun `markdown and code types`() {
        assertEquals("text/markdown", MimeResolver.fromExtension("readme.md"))
        assertEquals("text/x-kotlin", MimeResolver.fromExtension("Main.kt"))
        assertEquals("application/json", MimeResolver.fromExtension("data.json"))
    }

    @Test
    fun `unknown extension falls back to octet stream`() {
        assertEquals("application/octet-stream", MimeResolver.fromExtension("mystery.xyz"))
        assertEquals("application/octet-stream", MimeResolver.fromExtension("noext"))
    }

    @Test
    fun `category classification`() {
        assertEquals("image", MimeResolver.category("image/webp"))
        assertEquals("audio", MimeResolver.category("audio/flac"))
        assertEquals("video", MimeResolver.category("video/x-matroska"))
        assertEquals("archive", MimeResolver.category("application/x-7z-compressed"))
        assertEquals("app", MimeResolver.category("application/vnd.android.package-archive"))
        assertEquals("document", MimeResolver.category("application/pdf"))
        assertEquals("text", MimeResolver.category("text/plain"))
        assertEquals("font", MimeResolver.category("font/ttf"))
        assertEquals("other", MimeResolver.category("application/octet-stream"))
    }

    @Test
    fun `friendly names in both languages`() {
        assertEquals("صورة", MimeResolver.friendly("image/png"))
        assertEquals("Image", MimeResolver.friendly("image/png", english = true))
        assertEquals("ملف تطبيق", MimeResolver.friendly("application/octet-stream"))
        assertEquals("Application file", MimeResolver.friendly("application/octet-stream", english = true))
    }

    @Test
    fun `extensions of prefix feeds intent filters`() {
        val images = MimeResolver.extensionsOf("image/")
        assertTrue(images.contains("jpg") && images.contains("png") && images.contains("webp"))
        assertFalse(images.contains("mp3"))
        val audio = MimeResolver.extensionsOf("audio/")
        assertTrue(audio.contains("mp3") && audio.contains("flac"))
    }

    // ---------- OpenWithResolver ----------

    private fun h(pkg: String, label: String, vararg patterns: String) =
        OpenWithResolver.Handler(pkg, label, patterns.toSet())

    @Test
    fun `exact match ranks above wildcard`() {
        val r = OpenWithResolver()
        val list = r.ranked(
            "image/png",
            listOf(h("a", "Generic", "image/*"), h("b", "Exact", "image/png")),
        )
        assertEquals("b", list[0].packageName)
        assertEquals("a", list[1].packageName)
    }

    @Test
    fun `wildcard type ranks above accept-all`() {
        val r = OpenWithResolver()
        val list = r.ranked(
            "text/markdown",
            listOf(h("c", "All", "*/*"), h("d", "Type", "text/*")),
        )
        assertEquals("d", list[0].packageName)
        assertEquals("c", list[1].packageName)
    }

    @Test
    fun `non matching handlers excluded`() {
        val r = OpenWithResolver()
        val list = r.ranked(
            "video/mp4",
            listOf(h("v", "Video", "video/*"), h("i", "Image", "image/*")),
        )
        assertEquals(1, list.size)
        assertEquals("v", list[0].packageName)
    }

    @Test
    fun `default handler jumps to front`() {
        val r = OpenWithResolver()
        r.setDefault("image/png", "viewer")
        val list = r.ranked(
            "image/png",
            listOf(h("b", "Exact", "image/png"), h("viewer", "Viewer", "image/*")),
        )
        assertEquals("viewer", list[0].packageName)
    }

    @Test
    fun `default applies to whole group`() {
        val r = OpenWithResolver()
        r.setDefault("image/png", "viewer")
        assertEquals("viewer", r.defaultFor("image/jpeg"))
        assertTrue(r.hasUsableDefault("image/webp", listOf(h("viewer", "V", "image/*"))))
    }

    @Test
    fun `clear default removes it`() {
        val r = OpenWithResolver()
        r.setDefault("audio/*", "music")
        assertTrue(r.clearDefault("audio/flac"))
        assertFalse(r.clearDefault("audio/flac"))
        assertEquals(null, r.defaultFor("audio/ogg"))
    }

    @Test
    fun `stale default does not break ranking`() {
        val r = OpenWithResolver()
        r.setDefault("image/png", "gone_app")
        val list = r.ranked("image/png", listOf(h("a", "A", "image/*")))
        assertEquals("a", list[0].packageName)
        assertFalse(r.hasUsableDefault("image/png", listOf(h("a", "A", "image/*"))))
    }

    @Test
    fun `group of special types stays full`() {
        val r = OpenWithResolver()
        assertEquals("image/*", r.groupOf("image/png"))
        assertEquals("application/pdf", r.groupOf("application/pdf"))
        assertEquals("*/*", r.groupOf("*/*"))
    }

    @Test
    fun `serialize load roundtrip`() {
        val r = OpenWithResolver()
        r.setDefault("image/*", "com.a")
        r.setDefault("application/pdf", "com.b")
        val r2 = OpenWithResolver()
        r2.load(r.serialize())
        assertEquals("com.a", r2.defaultFor("image/jpeg"))
        assertEquals("com.b", r2.defaultFor("application/pdf"))
    }

    @Test
    fun `ties break alphabetically by label`() {
        val r = OpenWithResolver()
        val list = r.ranked(
            "text/plain",
            listOf(h("z", "Zeta", "text/*"), h("a", "Alpha", "text/*")),
        )
        assertEquals("a", list[0].packageName)
        assertEquals("z", list[1].packageName)
    }
}
