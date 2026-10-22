package com.novafiles.app.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات محرك ترقيم PDF — الحدود، القفز، التكبير، ومخزن القراءة */
class PdfPagerEngineTest {

    private val engine = PdfPagerEngine

    // ═══════════════ التثبيت والحدود ═══════════════

    @Test
    fun `clamp page keeps valid pages`() {
        assertEquals(0, engine.clampPage(0, 10))
        assertEquals(9, engine.clampPage(9, 10))
        assertEquals(4, engine.clampPage(4, 10))
    }

    @Test
    fun `clamp page fixes negative and overflow`() {
        assertEquals(0, engine.clampPage(-3, 10))
        assertEquals(9, engine.clampPage(99, 10))
    }

    @Test
    fun `clamp page with empty document returns zero`() {
        assertEquals(0, engine.clampPage(5, 0))
        assertEquals(0, engine.clampPage(5, -1))
    }

    @Test
    fun `next page stops at last`() {
        assertEquals(1, engine.nextPage(0, 10))
        assertEquals(9, engine.nextPage(8, 10))
        assertEquals(9, engine.nextPage(9, 10))
    }

    @Test
    fun `prev page stops at first`() {
        assertEquals(0, engine.prevPage(1, 10))
        assertEquals(0, engine.prevPage(0, 10))
    }

    // ═══════════════ القفز المباشر ═══════════════

    @Test
    fun `jump uses display numbering from one`() {
        assertEquals(0, engine.jumpTo(1, 10))
        assertEquals(4, engine.jumpTo(5, 10))
        assertEquals(9, engine.jumpTo(10, 10))
    }

    @Test
    fun `jump out of range clamps instead of throwing`() {
        assertEquals(0, engine.jumpTo(0, 10))
        assertEquals(0, engine.jumpTo(-5, 10))
        assertEquals(9, engine.jumpTo(50, 10))
    }

    @Test
    fun `valid jump range check`() {
        assertTrue(engine.isValidJump(1, 10))
        assertTrue(engine.isValidJump(10, 10))
        assertFalse(engine.isValidJump(0, 10))
        assertFalse(engine.isValidJump(11, 10))
        assertFalse(engine.isValidJump(1, 0))
    }

    @Test
    fun `page label is display numbering over total`() {
        assertEquals("1 / 10", engine.pageLabel(0, 10))
        assertEquals("5 / 10", engine.pageLabel(4, 10))
        assertEquals("0 / 0", engine.pageLabel(0, 0))
    }

    // ═══════════════ التكبير ═══════════════

    @Test
    fun `zoom in follows fixed steps`() {
        assertEquals(1.5f, engine.zoomIn(1.0f))
        assertEquals(2.0f, engine.zoomIn(1.5f))
        assertEquals(3.0f, engine.zoomIn(2.0f))
        assertEquals(4.0f, engine.zoomIn(3.0f))
    }

    @Test
    fun `zoom in saturates at max`() {
        assertEquals(4.0f, engine.zoomIn(4.0f))
    }

    @Test
    fun `zoom out follows fixed steps backward`() {
        assertEquals(1.0f, engine.zoomOut(1.5f))
        assertEquals(1.5f, engine.zoomOut(2.0f))
        assertEquals(3.0f, engine.zoomOut(4.0f))
    }

    @Test
    fun `zoom out saturates at min`() {
        assertEquals(1.0f, engine.zoomOut(1.0f))
    }

    @Test
    fun `clamp zoom bounds free pinch values`() {
        assertEquals(1.0f, engine.clampZoom(0.2f))
        assertEquals(4.0f, engine.clampZoom(9.0f))
        assertEquals(2.5f, engine.clampZoom(2.5f))
    }

    // ═══════════════ مخزن القراءة ═══════════════

    @Test
    fun `reading store round trip`() {
        val store = InMemoryPdfReadingStore()
        store.save("doc.pdf", 4, 12)
        val p = store.load("doc.pdf")!!
        assertEquals(4, p.page)
        assertEquals(12, p.pageCount)
    }

    @Test
    fun `reading store missing key returns null`() {
        val store = InMemoryPdfReadingStore()
        assertNull(store.load("missing.pdf"))
    }

    @Test
    fun `reading store overwrite and clear`() {
        val store = InMemoryPdfReadingStore()
        store.save("d.pdf", 1, 12)
        store.save("d.pdf", 7, 12)
        assertEquals(7, store.load("d.pdf")!!.page)
        store.clear("d.pdf")
        assertNull(store.load("d.pdf"))
    }

    @Test
    fun `reading store LRU eviction`() {
        val store = InMemoryPdfReadingStore(maxEntries = 2)
        store.save("a.pdf", 0, 5)
        store.save("b.pdf", 0, 5)
        store.save("c.pdf", 0, 5)
        assertNull(store.load("a.pdf"))
        assertEquals(2, store.size)
    }
}
