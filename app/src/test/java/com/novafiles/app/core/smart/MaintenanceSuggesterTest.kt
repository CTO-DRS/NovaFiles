package com.novafiles.app.core.smart

import com.novafiles.app.core.analyzer.SnapshotRow
import com.novafiles.app.core.smart.MaintenanceSuggester.DuplicateCluster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات اقتراحات الصيانة — كل اقتراح بسببه ووفره ومفتاح تجاهل نهائي */
class MaintenanceSuggesterTest {

    private val mb = 1024L * 1024
    private val now = 1_800_000_000_000L
    private val root = "/storage/emulated/0"

    private fun file(parent: String, name: String, size: Long, ageDays: Long = 1) = SnapshotRow(
        path = "$parent/$name",
        name = name,
        parent = parent,
        size = size,
        modified = now - ageDays * DownloadClassifier.DAY_MS,
        isDirectory = false,
    )

    private fun dir(path: String) = SnapshotRow(
        path = path,
        name = path.substringAfterLast('/'),
        parent = path.substringBeforeLast('/'),
        size = 0L,
        modified = now,
        isDirectory = true,
    )

    // ═══════════════ 1) المحتوى المكرر ═══════════════

    @Test
    fun `duplicate cluster savings is size times extras`() {
        val s = MaintenanceSuggester.suggest(
            rows = emptyList(),
            clusters = listOf(DuplicateCluster(listOf("$root/D/b.jpg", "$root/D/a.jpg", "$root/D/c.jpg"), mb)),
            nowMs = now,
        )
        assertEquals(1, s.size)
        val sug = s.single()
        assertEquals(SuggestionKind.DUPLICATE_CONTENT, sug.kind)
        assertEquals(2 * mb, sug.savingsBytes)
        // المرجعية = أول مسار مرتب؛ الزوائد = البقية
        assertEquals("$root/D/a.jpg", sug.path)
        assertEquals(listOf("$root/D/b.jpg", "$root/D/c.jpg"), sug.itemPaths)
        assertEquals("2", sug.reasonArgs[0])
        assertEquals("1.0 MB", sug.reasonArgs[1])
        assertEquals("DUP:$mb:$root/D/a.jpg", sug.key)
    }

    @Test
    fun `single and zero size clusters are skipped`() {
        val s = MaintenanceSuggester.suggest(
            rows = emptyList(),
            clusters = listOf(
                DuplicateCluster(listOf("$root/a.jpg"), mb),
                DuplicateCluster(listOf("$root/a.jpg", "$root/b.jpg"), 0L),
            ),
            nowMs = now,
        )
        assertTrue(s.isEmpty())
    }

    @Test
    fun `dismissed duplicate key never comes back`() {
        val key = "DUP:$mb:$root/D/a.jpg"
        val s = MaintenanceSuggester.suggest(
            rows = emptyList(),
            clusters = listOf(DuplicateCluster(listOf("$root/D/b.jpg", "$root/D/a.jpg"), mb)),
            nowMs = now,
            dismissedKeys = setOf(key),
        )
        assertTrue(s.isEmpty())
    }

    // ═══════════════ 2) الكاش المضخم ═══════════════

    @Test
    fun `cache bloat above threshold detected with subtree sum`() {
        val cache = "$root/Android/data/x/cache"
        val rows = listOf(
            dir(cache),
            file(cache, "blob1", 90 * mb),
            file(cache, "blob2", 60 * mb),
        )
        val s = MaintenanceSuggester.suggest(rows, emptyList(), now)
        val sug = s.single { it.kind == SuggestionKind.CACHE_BLOAT }
        assertEquals(150 * mb, sug.savingsBytes)
        assertEquals(cache, sug.path)
        assertEquals("CACHE:$cache", sug.key)
        assertEquals("150.0 MB", sug.reasonArgs[0])
    }

    @Test
    fun `cache below threshold is not suggested`() {
        val cache = "$root/thumbs"
        val rows = listOf(dir(cache), file(cache, "t.jpg", 50 * mb))
        val s = MaintenanceSuggester.suggest(rows, emptyList(), now)
        assertTrue(s.none { it.kind == SuggestionKind.CACHE_BLOAT })
    }

    @Test
    fun `nested cache inside cache counted once via outer only`() {
        val outer = "$root/cache"
        val inner = "$root/cache/.thumbnails"
        val rows = listOf(
            dir(outer),
            dir(inner),
            file(outer, "a", 120 * mb),
            file(inner, "b", 90 * mb), // محسوب ضمن شجرة الخارجي
        )
        val s = MaintenanceSuggester.suggest(rows, emptyList(), now)
        val caches = s.filter { it.kind == SuggestionKind.CACHE_BLOAT }
        assertEquals(1, caches.size)
        assertEquals(outer, caches.single().path)
        assertEquals(210 * mb, caches.single().savingsBytes)
    }

    @Test
    fun `cache dir name matcher covers known patterns`() {
        assertTrue(MaintenanceSuggester.isCacheDirName("cache"))
        assertTrue(MaintenanceSuggester.isCacheDirName("Cache"))
        assertTrue(MaintenanceSuggester.isCacheDirName(".thumbnails"))
        assertTrue(MaintenanceSuggester.isCacheDirName("thumbs"))
        assertTrue(MaintenanceSuggester.isCacheDirName("imagecache"))
        assertTrue(MaintenanceSuggester.isCacheDirName(".thumbs"))
    }

    // ═══════════════ 3) المجلدات الفوضوية ═══════════════

    @Test
    fun `cluttered folder detected when majority clutter and count met`() {
        val messy = "$root/Download/messy"
        val rows = buildList {
            repeat(30) { i -> add(file(messy, "f$i.dat", 1000L + i)) }
            repeat(30) { i -> add(file(messy, "k$i.jpg", 10L)) }
        }
        val s = MaintenanceSuggester.suggest(rows, emptyList(), now)
        val sug = s.single { it.kind == SuggestionKind.CLUTTERED_FOLDER }
        assertEquals(messy, sug.path)
        val expected = (0 until 30).sumOf { 1000L + it }
        assertEquals(expected, sug.savingsBytes)
        assertEquals("30", sug.reasonArgs[0])
        assertEquals("60", sug.reasonArgs[1])
        assertEquals(30, sug.itemPaths.size)
        assertEquals("CLUTTER:$messy", sug.key)
    }

    @Test
    fun `few clutter files do not trigger folder suggestion`() {
        val messy = "$root/Download/few"
        val rows = buildList {
            repeat(16) { i -> add(file(messy, "f$i.dat", 500L)) }
            repeat(15) { i -> add(file(messy, "k$i.jpg", 10L)) }
        }
        val s = MaintenanceSuggester.suggest(rows, emptyList(), now)
        assertTrue(s.none { it.kind == SuggestionKind.CLUTTERED_FOLDER })
    }

    // ═══════════════ 4) المثبّتات القديمة ═══════════════

    @Test
    fun `old installers grouped by parent with savings`() {
        val d = "$root/Download"
        val rows = listOf(
            file(d, "old1.apk", 20 * mb, ageDays = 40),
            file(d, "old2.apk", 10 * mb, ageDays = 31),
            file(d, "fresh.apk", 5 * mb, ageDays = 2),
        )
        val s = MaintenanceSuggester.suggest(rows, emptyList(), now)
        val sug = s.single { it.kind == SuggestionKind.OLD_INSTALLERS }
        assertEquals(30 * mb, sug.savingsBytes)
        assertEquals("2", sug.reasonArgs[0])
        assertEquals(listOf("$d/old1.apk", "$d/old2.apk"), sug.itemPaths)
        assertEquals("APK:$d", sug.key)
    }

    @Test
    fun `fresh installers are not old`() {
        val d = "$root/Download"
        val rows = listOf(file(d, "fresh.apk", 20 * mb, ageDays = 29))
        val s = MaintenanceSuggester.suggest(rows, emptyList(), now)
        assertTrue(s.isEmpty())
    }

    // ═══════════════ الترتيب والحد والتجاهل العام ═══════════════

    @Test
    fun `suggestions sorted by savings descending then path`() {
        val d = "$root/Download"
        val cache = "$root/cache"
        val rows = listOf(
            dir(cache),
            file(cache, "blob", 150 * mb),
            file(d, "old.apk", 10 * mb, ageDays = 60),
        )
        val s = MaintenanceSuggester.suggest(
            rows,
            listOf(DuplicateCluster(listOf("$d/x.jpg", "$d/y.jpg"), 200 * mb)),
            now,
        )
        assertEquals(listOf(200 * mb, 150 * mb, 10 * mb), s.map { it.savingsBytes })
    }

    @Test
    fun `limit caps the output`() {
        val d = "$root/Download"
        val rows = listOf(
            dir("$root/cache1"),
            dir("$root/cache2"),
            file("$root/cache1", "b1", 150 * mb),
            file("$root/cache2", "b2", 150 * mb),
            file(d, "old.apk", 10 * mb, ageDays = 60),
        )
        val s = MaintenanceSuggester.suggest(rows, emptyList(), now, limit = 2)
        assertEquals(2, s.size)
        assertEquals(150 * mb, s[0].savingsBytes)
        assertEquals(150 * mb, s[1].savingsBytes)
    }

    @Test
    fun `zero savings never suggested`() {
        val s = MaintenanceSuggester.suggest(
            rows = listOf(file("$root/Download", "old.apk", 0L, ageDays = 60)),
            clusters = emptyList(),
            nowMs = now,
        )
        assertTrue(s.isEmpty())
    }

    @Test
    fun `empty inputs yield empty suggestions`() {
        assertTrue(MaintenanceSuggester.suggest(emptyList(), emptyList(), now).isEmpty())
    }
}
