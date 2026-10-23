package com.novafiles.app.core.shelves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات SuggestionsEngine — اقتراحات حتمية قابلة للشرح (JVM خالص)
 */
class SuggestionsEngineTest {

    private val NOW = 1_800_000_000_000L
    private val DAY = 86_400_000L
    private val MB = 1024L * 1024

    private fun file(
        path: String,
        size: Long = 10 * MB,
        modified: Long = NOW - DAY,
    ): SmartShelvesEngine.IndexedFile {
        val idx = path.lastIndexOf('/')
        return SmartShelvesEngine.IndexedFile(
            path = path,
            name = path.substring(idx + 1),
            parent = if (idx <= 0) "/" else path.substring(0, idx),
            size = size,
            modified = modified,
            isDirectory = false,
        )
    }

    private fun byId(suggestions: List<SuggestionsEngine.Suggestion>) =
        suggestions.associateBy { it.id }

    @Test
    fun `old screenshots counted with total bytes`() {
        val s = byId(SuggestionsEngine.compute(
            listOf(
                file("/P/Screenshot_old1.png", size = 3 * MB, modified = NOW - 40 * DAY),
                file("/P/Screenshot_old2.png", size = 2 * MB, modified = NOW - 31 * DAY),
                file("/P/Screenshot_fresh.png", size = 9 * MB, modified = NOW - 1 * DAY),
            ),
            now = NOW,
        ))
        assertEquals(2, s[SuggestionsEngine.SuggestionId.OLD_SCREENSHOTS]!!.count)
        assertEquals(5 * MB, s[SuggestionsEngine.SuggestionId.OLD_SCREENSHOTS]!!.bytes)
    }

    @Test
    fun `old installers only inside Download and beyond 30 days`() {
        val s = byId(SuggestionsEngine.compute(
            listOf(
                file("/Download/app1.apk", size = 80 * MB, modified = NOW - 31 * DAY),
                file("/Download/app2.apk", size = 20 * MB, modified = NOW - 90 * DAY),
                file("/Download/new.apk", size = 50 * MB, modified = NOW - 3 * DAY),  // حديث
                file("/Movies/old.apk", size = 70 * MB, modified = NOW - 60 * DAY),   // خارج التنزيلات
            ),
            now = NOW,
        ))
        val inst = s[SuggestionsEngine.SuggestionId.OLD_INSTALLERS]!!
        assertEquals(2, inst.count)
        assertEquals(100 * MB, inst.bytes)
    }

    @Test
    fun `stale large needs 50MB and 90 days`() {
        val s = byId(SuggestionsEngine.compute(
            listOf(
                file("/a/huge.iso", size = 900 * MB, modified = NOW - 100 * DAY),
                file("/b/mid.zip", size = 50 * MB, modified = NOW - 91 * DAY),
                file("/c/small.bin", size = 49 * MB, modified = NOW - 200 * DAY), // أصغر
                file("/d/fresh.mkv", size = 800 * MB, modified = NOW - 10 * DAY), // أحدث
            ),
            now = NOW,
        ))
        val stale = s[SuggestionsEngine.SuggestionId.STALE_LARGE]!!
        assertEquals(2, stale.count)
        assertEquals(950 * MB, stale.bytes)
    }

    @Test
    fun `suggestions sorted by potential savings descending`() {
        val list = SuggestionsEngine.compute(
            listOf(
                // لقطات قديمة: 10MB (الأصغر)
                file("/P/Screenshot_a.png", size = 10 * MB, modified = NOW - 40 * DAY),
                // مثبّتات قديمة: 100MB
                file("/Download/x.apk", size = 100 * MB, modified = NOW - 40 * DAY),
                // مهملة كبيرة: 1GB (الأكبر)
                file("/y/giant.img", size = 1024 * MB, modified = NOW - 100 * DAY),
            ),
            now = NOW,
        )
        assertEquals(
            listOf(
                SuggestionsEngine.SuggestionId.STALE_LARGE,
                SuggestionsEngine.SuggestionId.OLD_INSTALLERS,
                SuggestionsEngine.SuggestionId.OLD_SCREENSHOTS,
            ),
            list.map { it.id },
        )
    }

    @Test
    fun `zero-count suggestions are dropped and list may be empty`() {
        assertEquals(
            emptyList<SuggestionsEngine.Suggestion>(),
            SuggestionsEngine.compute(
                listOf(file("/a/fresh.pdf", modified = NOW - DAY)),
                now = NOW,
            ),
        )
    }

    @Test
    fun `directories and system paths never suggested`() {
        val idx = listOf(
            SmartShelvesEngine.IndexedFile(
                "/Download/hugedir", "hugedir", "/Download",
                900 * MB, NOW - 100 * DAY, isDirectory = true,
            ),
            file("/Android/data/old.bin", size = 900 * MB, modified = NOW - 100 * DAY),
        )
        assertTrue(SuggestionsEngine.compute(idx, now = NOW).isEmpty())
    }

    @Test
    fun `compute is deterministic across runs`() {
        val idx = listOf(
            file("/P/Screenshot_o.png", size = 5 * MB, modified = NOW - 50 * DAY),
            file("/Download/a.apk", size = 90 * MB, modified = NOW - 60 * DAY),
            file("/z/old.iso", size = 300 * MB, modified = NOW - 120 * DAY),
        )
        assertEquals(SuggestionsEngine.compute(idx, NOW), SuggestionsEngine.compute(idx, NOW))
    }

    @Test
    fun `limit parameter caps the list`() {
        val idx = listOf(
            file("/P/Screenshot_o.png", size = 5 * MB, modified = NOW - 50 * DAY),
            file("/Download/a.apk", size = 90 * MB, modified = NOW - 60 * DAY),
            file("/z/old.iso", size = 300 * MB, modified = NOW - 120 * DAY),
        )
        assertEquals(1, SuggestionsEngine.compute(idx, NOW, limit = 1).size)
        assertEquals(3, SuggestionsEngine.compute(idx, NOW, limit = 5).size)
    }
}

/** ═══════════ v0.33 — اختبارات حدودية إضافية ═══════════ */
class SuggestionsEdgeTest {

    private val NOW = 1_800_000_000_000L
    private val DAY = 86_400_000L
    private val MB = 1024L * 1024

    private fun file(path: String, size: Long = 10 * MB, modified: Long = NOW - DAY) =
        SmartShelvesEngine.IndexedFile(
            path, path.substringAfterLast('/'),
            path.substringBeforeLast('/', "/"), size, modified, false,
        )

    @Test
    fun `installer exactly at 30d boundary is excluded`() {
        val at = file("/Download/edge.apk", size = 80 * MB, modified = NOW - 30 * DAY)
        assertTrue(SuggestionsEngine.compute(listOf(at), NOW).isEmpty())
    }

    @Test
    fun `stale large exactly at 90d boundary is excluded`() {
        val at = file("/d/edge.iso", size = 500 * MB, modified = NOW - 90 * DAY)
        assertTrue(SuggestionsEngine.compute(listOf(at), NOW).isEmpty())
    }
}
