package com.novafiles.app.core.smart

import com.novafiles.app.core.analyzer.SnapshotRow
import com.novafiles.app.util.Format

/**
 * v0.34.0 — مولّد اقتراحات الصيانة المحسوبة (pure Kotlin)
 *
 * أربع قواعد حتمية، كل اقتراح يعرض سببه والوفر المتوقع بالبايت:
 *  1) DUPLICATE_CONTENT — مجموعات محتوى مكرر (كاشف v0.14/v0.29): النسخ الزائدة فقط
 *  2) CACHE_BLOAT — أدلة كاش تعلو حدياً معيناً (الأدلّة العليا فقط بلا ازدواج)
 *  3) CLUTTERED_FOLDER — مجلد بأغلبية ملفات فوضى (تصنيف v0.34)
 *  4) OLD_INSTALLERS — مثبّتات قديمة عمرها ≥ 30 يوماً مجمّعة بمجلد الأب
 *
 * التجاهل نهائي: أي مفتاح ضمن dismissedKeys لا يعود يُقترح أبداً.
 */
object MaintenanceSuggester {

    /** مجموعة تكرار مدخل خفيف (من DuplicateReport أو ماسح المخلفات) */
    data class DuplicateCluster(
        val paths: List<String>,
        val sizeBytes: Long,
    )

    /** حد تضخم الكاش */
    const val CACHE_MIN_BYTES = 100L * 1024 * 1024

    /** حد عدد الملفات لتشخيص «مجلد فوضوي» */
    const val CLUTTER_MIN_FILES = 30

    /** نسبة الفوضى المطلوبة داخل المجلد */
    const val CLUTTER_MIN_RATIO = 0.5

    /** عمر المثبّتات القديمة بالأيام */
    const val OLD_INSTALLER_DAYS = 30

    /** أسماء أدلة الكاش المعروفة (بصيغة صغيرة الحروف) */
    private val CACHE_NAMES = setOf("thumbnails", ".thumbnails", "thumbs", ".thumbs")

    fun isCacheDirName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("cache") || lower in CACHE_NAMES
    }

    fun suggest(
        rows: List<SnapshotRow>,
        clusters: List<DuplicateCluster>,
        nowMs: Long,
        dismissedKeys: Set<String> = emptySet(),
        limit: Int = 24,
    ): List<MaintenanceSuggestion> {
        val files = rows.filter { !it.isDirectory }
        val dirPaths = rows.filter { it.isDirectory }.map { it.path }.toSet()
        val out = ArrayList<MaintenanceSuggestion>()

        // ─── 1) محتوى مكرر: النسخ الزائدة فقط — المرجعية (أول مسار) محفوظة ───
        for (c in clusters) {
            if (c.paths.size < 2 || c.sizeBytes <= 0) continue
            val sorted = c.paths.sorted()
            val extras = sorted.drop(1)
            val savings = c.sizeBytes * extras.size
            if (savings <= 0) continue
            out += MaintenanceSuggestion(
                kind = SuggestionKind.DUPLICATE_CONTENT,
                title = sorted.first().substringAfterLast('/'),
                path = sorted.first(),
                savingsBytes = savings,
                reasonKey = "sugg_reason_duplicates",
                reasonArgs = listOf(extras.size.toString(), Format.bytes(c.sizeBytes)),
                itemPaths = extras,
                key = "DUP:${c.sizeBytes}:${sorted.first()}",
            )
        }

        // ─── 2) كاش يضخم: الأدلة العليا فقط (دليل كاش داخل كاش آخر لا يُحسب مرتين) ───
        val cacheDirs = rows.filter { it.isDirectory && isCacheDirName(it.name) }.map { it.path }
        val subtrees = subtreeSums(files, dirPaths)
        for (cd in cacheDirs) {
            if (hasCacheAncestor(cd, cacheDirs)) continue
            val size = subtrees[cd] ?: 0L
            if (size < CACHE_MIN_BYTES) continue
            out += MaintenanceSuggestion(
                kind = SuggestionKind.CACHE_BLOAT,
                title = cd.substringAfterLast('/'),
                path = cd,
                savingsBytes = size,
                reasonKey = "sugg_reason_cache",
                reasonArgs = listOf(Format.bytes(size)),
                itemPaths = emptyList(),
                key = "CACHE:$cd",
            )
        }

        // ─── 3) مجلدات فوضوية: عدد كافٍ من الملفات وأغلبية فوضى ───
        val byParent = files.groupBy { it.parent }
        for ((parent, list) in byParent) {
            if (list.size < CLUTTER_MIN_FILES) continue
            val clutter = list.filter {
                DownloadClassifier.classify(it.name, it.parent) == DownloadClass.CLUTTER
            }
            if (clutter.size < CLUTTER_MIN_FILES) continue
            val ratio = clutter.size.toDouble() / list.size
            if (ratio < CLUTTER_MIN_RATIO) continue
            val savings = clutter.sumOf { it.size }
            if (savings <= 0) continue
            out += MaintenanceSuggestion(
                kind = SuggestionKind.CLUTTERED_FOLDER,
                title = parent.substringAfterLast('/'),
                path = parent,
                savingsBytes = savings,
                reasonKey = "sugg_reason_clutter",
                reasonArgs = listOf(clutter.size.toString(), list.size.toString()),
                itemPaths = clutter.map { it.path }.sorted(),
                key = "CLUTTER:$parent",
            )
        }

        // ─── 4) مثبّتات قديمة مجمّعة بمجلد الأب ───
        val oldApks = files.filter {
            val cf = DownloadClassifier.classify(it.name, it.parent)
            cf == DownloadClass.INSTALLER &&
                (nowMs - it.modified).coerceAtLeast(0L) >= OLD_INSTALLER_DAYS * DownloadClassifier.DAY_MS
        }
        for ((parent, list) in oldApks.groupBy { it.parent }) {
            val savings = list.sumOf { it.size }
            if (savings <= 0) continue
            out += MaintenanceSuggestion(
                kind = SuggestionKind.OLD_INSTALLERS,
                title = parent.substringAfterLast('/'),
                path = parent,
                savingsBytes = savings,
                reasonKey = "sugg_reason_apks",
                reasonArgs = listOf(list.size.toString(), Format.bytes(savings)),
                itemPaths = list.map { it.path }.sorted(),
                key = "APK:$parent",
            )
        }

        return out
            .filter { it.savingsBytes > 0 && it.key !in dismissedKeys }
            .sortedWith(
                compareByDescending<MaintenanceSuggestion> { it.savingsBytes }
                    .thenBy { it.kind }
                    .thenBy { it.path },
            )
            .take(limit)
    }

    /** مجموع الشجرة الفرعية لكل دليل معروف (من صفوف الفهرس فقط) */
    fun subtreeSums(files: List<SnapshotRow>, dirPaths: Set<String>): Map<String, Long> {
        val sums = HashMap<String, Long>()
        for (f in files) {
            var dir = f.parent
            while (dir.isNotEmpty()) {
                if (dir in dirPaths) sums[dir] = (sums[dir] ?: 0L) + f.size
                if (!dir.contains('/')) break
                dir = dir.substringBeforeLast('/')
            }
        }
        return sums
    }

    /** هل يقع المسار داخل دليل كاش آخر؟ (يمنع الازدواج) */
    fun hasCacheAncestor(path: String, cacheDirs: Collection<String>): Boolean {
        val set = cacheDirs.toSet()
        var dir = path.substringBeforeLast('/', "")
        while (dir.isNotEmpty()) {
            if (dir in set) return true
            if (!dir.contains('/')) break
            dir = dir.substringBeforeLast('/')
        }
        return false
    }
}
