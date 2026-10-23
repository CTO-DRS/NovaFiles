package com.novafiles.app.core.shelves

/**
 * ══════════════════════════════════════════════════════════
 *  SmartShelvesEngine — v0.33 «الرفوف الذكية التلقائية»
 *  أرفف حتمية تُبنى فوق لقطة الفهرس الفوري (Room-FTS):
 *   ─ RECENT_DOCS       مستنداتي الأخيرة (7 أيام)
 *   ─ LARGE_DOWNLOADS   تنزيلات كبيرة (≥100MB داخل Download)
 *   ─ STALE_90D         لم تُفتح منذ 90 يوماً (≥1MB، مستثناة المفتوحة حديثاً)
 *   ─ OLD_SCREENSHOTS   لقطات شاشة قديمة (أقدم من 30 يوماً)
 *  كل رف مُرتَّب حتمياً مع كسر تعادل بالمسار — خالص JVM.
 * ══════════════════════════════════════════════════════════
 */
object SmartShelvesEngine {

    /** معرفات الرفوف الأربعة */
    enum class ShelfId { RECENT_DOCS, LARGE_DOWNLOADS, STALE_90D, OLD_SCREENSHOTS }

    /** صف فهرس خالص (مرآة FileIndexEntity بلا Room) */
    data class IndexedFile(
        val path: String,
        val name: String,
        val parent: String,
        val size: Long,
        val modified: Long,
        val isDirectory: Boolean,
    )

    const val RECENT_DOCS_DAYS = 7L
    const val LARGE_MIN_BYTES = 100L * 1024 * 1024
    const val STALE_DAYS = 90L
    const val STALE_MIN_BYTES = 1L * 1024 * 1024
    const val SCREENSHOT_DAYS = 30L

    /** امتدادات المستندات المؤهلة لرف «مستنداتي الأخيرة» */
    val DOC_EXTS = setOf(
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "odt", "ods", "txt", "md", "csv", "rtf",
    )

    /** أسماء مجلدات التنزيلات المعروفة */
    val DOWNLOAD_DIRS = setOf("Download", "Downloads")

    /** مقاطع نظام مستبعدة دائماً (محاذاة RecentsEngine.SYSTEM_EXCLUDE) */
    val SYSTEM_SEGMENTS = setOf(
        "Android", "data", "obb", ".NovaFilesTrash", "vault",
        "LOST.DIR", ".thumbnails", "Podcasts", "notifications", "alarms", "ringtones",
    )

    private val DAY_MS = 86_400_000L

    /** اسم لقطة شاشة؟ (إنجليزي/عربي، حساس للنمط الشائع لا للحالة) */
    fun isScreenshot(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("screenshot") ||
            name.contains("لقطة شاشة") ||
            name.contains("لقطة الشاشة")
    }

    /** مسار مخفي أو داخل مجلد نظام؟ */
    fun isExcluded(path: String): Boolean {
        val segments = path.split('/')
        return segments.any { seg -> seg.isNotEmpty() && (seg.startsWith(".") || seg in SYSTEM_SEGMENTS) }
    }

    private fun lastSegment(path: String): String = path.substringAfterLast('/', "")

    /**
     * حساب الأرفف الأربعة من لقطة فهرس.
     * @param recentlyOpened مسارات ظهرت في «الأخيرة» حديثاً — تُستثنى من رف المهملة
     */
    fun compute(
        index: List<IndexedFile>,
        now: Long,
        recentlyOpened: Set<String> = emptySet(),
        limitPerShelf: Int = 50,
    ): Map<ShelfId, List<IndexedFile>> {
        require(limitPerShelf > 0) { "limitPerShelf must be positive" }
        val candidates = index.filter { !it.isDirectory && !isExcluded(it.path) }

        val docCutoff = now - RECENT_DOCS_DAYS * DAY_MS
        val staleCutoff = now - STALE_DAYS * DAY_MS
        val screenshotCutoff = now - SCREENSHOT_DAYS * DAY_MS

        val recentDocs = candidates
            .filter {
                val ext = it.name.substringAfterLast('.', "").lowercase()
                ext in DOC_EXTS && it.modified >= docCutoff
            }
            .sortedWith(compareByDescending<IndexedFile> { it.modified }.thenBy { it.path })
            .take(limitPerShelf)

        val largeDownloads = candidates
            .filter {
                lastSegment(it.parent) in DOWNLOAD_DIRS && it.size >= LARGE_MIN_BYTES
            }
            .sortedWith(compareByDescending<IndexedFile> { it.size }.thenBy { it.path })
            .take(limitPerShelf)

        val stale = candidates
            .filter {
                it.modified < staleCutoff &&
                    it.size >= STALE_MIN_BYTES &&
                    it.path !in recentlyOpened
            }
            .sortedWith(compareBy<IndexedFile> { it.modified }.thenBy { it.path })
            .take(limitPerShelf)

        val oldScreenshots = candidates
            .filter { isScreenshot(it.name) && it.modified < screenshotCutoff }
            .sortedWith(compareBy<IndexedFile> { it.modified }.thenBy { it.path })
            .take(limitPerShelf)

        return mapOf(
            ShelfId.RECENT_DOCS to recentDocs,
            ShelfId.LARGE_DOWNLOADS to largeDownloads,
            ShelfId.STALE_90D to stale,
            ShelfId.OLD_SCREENSHOTS to oldScreenshots,
        )
    }
}
