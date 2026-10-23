package com.novafiles.core.fs

import com.novafiles.app.core.fs.Bookmark
import com.novafiles.app.core.fs.BookmarksCodec
import com.novafiles.app.core.fs.BookmarksEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات الإشارات المرجعية — المنطق الخالص والترميز المتسامح.
 */
class BookmarksTest {

    private val NOW = 1_700_000_000_000L

    private fun state(vararg paths: String): List<Bookmark> {
        var list = emptyList<Bookmark>()
        paths.forEachIndexed { i, p ->
            list = BookmarksEngine.add(list, p, p.substringAfterLast('/'), NOW + i)!!
        }
        return list
    }

    // ═══════════ المنطق ═══════════

    @Test
    fun `add appends with derived name`() {
        val list = BookmarksEngine.add(emptyList(), "/sdcard/المشاريع", "", NOW)!!
        assertEquals(1, list.size)
        assertEquals("المشاريع", list[0].name)
        assertEquals("/sdcard/المشاريع", list[0].path)
        assertEquals(NOW, list[0].addedAt)
    }

    @Test
    fun `add duplicate path keeps original position`() {
        val list = state("/a", "/b")
        val again = BookmarksEngine.add(list, "/a", "a", NOW + 999)!!
        assertEquals(list, again)
        assertEquals("/a", again[0].path) // لم يقفز للنهاية
    }

    @Test
    fun `add empty path is no-op`() {
        assertEquals(emptyList<Bookmark>(), BookmarksEngine.add(emptyList(), "  ", "x", NOW)!!)
    }

    @Test
    fun `add to full list rejected with null`() {
        val full = state(*Array(BookmarksEngine.MAX_BOOKMARKS) { "/p$it" })
        assertNull(BookmarksEngine.add(full, "/new", "new", NOW))
        // وسقف مخصص أصغر يعمل بنفس المنطق
        val small = state("/a", "/b")
        assertNull(BookmarksEngine.add(small, "/c", "c", NOW, max = 2))
    }

    @Test
    fun `remove by path ignores unknown`() {
        val list = state("/a", "/b")
        assertEquals(list, BookmarksEngine.remove(list, "/missing"))
        val after = BookmarksEngine.remove(list, "/a")
        assertEquals(listOf("/b"), after.map { it.path })
    }

    @Test
    fun `contains checks by exact path`() {
        val list = state("/sdcard/Docs")
        assertTrue(BookmarksEngine.contains(list, "/sdcard/Docs"))
        assertFalse(BookmarksEngine.contains(list, "/sdcard/docs"))
        assertFalse(BookmarksEngine.contains(list, "/other"))
    }

    @Test
    fun `move reorders by one step with clamped edges`() {
        val list = state("/a", "/b", "/c")
        // /b لأعلى → أول القائمة
        assertEquals(listOf("/b", "/a", "/c"), BookmarksEngine.move(list, "/b", -1).map { it.path })
        // /b لأسفل → آخر القائمة
        assertEquals(listOf("/a", "/c", "/b"), BookmarksEngine.move(list, "/b", +1).map { it.path })
        // الحواف: الأول لأعلى لا يتحرك، والأخير لأسفل لا يتحرك
        assertEquals(list, BookmarksEngine.move(list, "/a", -1))
        assertEquals(list, BookmarksEngine.move(list, "/c", +1))
        // غائب = لا تغيير
        assertEquals(list, BookmarksEngine.move(list, "/zzz", -1))
    }

    @Test
    fun `sortedForDisplay is deterministic oldest first`() {
        var list = emptyList<Bookmark>()
        list = BookmarksEngine.add(list, "/z", "z", 300)!!
        list = BookmarksEngine.add(list, "/a", "a", 100)!!
        list = BookmarksEngine.add(list, "/m", "m", 200)!!
        assertEquals(listOf("/a", "/m", "/z"), BookmarksEngine.sortedForDisplay(list).map { it.path })
    }

    // ═══════════ الترميز ═══════════

    @Test
    fun `codec roundtrip preserves all fields`() {
        val list = listOf(
            Bookmark("/sdcard/أعمال الشركة", "أعمال الشركة", 111),
            Bookmark("/sdcard/مشاريع 👨‍👩‍👧", "مشاريع 👨‍👩‍👧", 222),
        )
        val decoded = BookmarksCodec.decode(BookmarksCodec.encode(list))
        assertEquals(list, decoded)
    }

    @Test
    fun `decode tolerant to corrupt json`() {
        assertEquals(emptyList<Bookmark>(), BookmarksCodec.decode(""))
        assertEquals(emptyList<Bookmark>(), BookmarksCodec.decode(null))
        assertEquals(emptyList<Bookmark>(), BookmarksCodec.decode("{{{برoken"))
        assertEquals(emptyList<Bookmark>(), BookmarksCodec.decode("\"just a string\""))
    }

    @Test
    fun `decode skips entries without path and fills name from path`() {
        val json = """[{"n":"بلا مسار","t":5},{"p":"/sdcard/Docs","t":42}]"""
        val decoded = BookmarksCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("/sdcard/Docs", decoded[0].path)
        assertEquals("Docs", decoded[0].name) // الاسم مشتق
        assertEquals(42, decoded[0].addedAt)
    }

    @Test
    fun `decode dedupes by path keeping first`() {
        val json = """[{"p":"/a","n":"one","t":1},{"p":"/a","n":"two","t":2},{"p":"/b","n":"b","t":3}]"""
        val decoded = BookmarksCodec.decode(json)
        assertEquals(2, decoded.size)
        assertEquals("one", decoded[0].name)
    }

    @Test
    fun `codec escapes control chars and quotes`() {
        val tricky = Bookmark("/sd/quote\"back\\slash\nnew", "tab\there", 7)
        val encoded = BookmarksCodec.encode(listOf(tricky))
        val decoded = BookmarksCodec.decode(encoded)
        assertEquals(listOf(tricky), decoded)
    }
}
