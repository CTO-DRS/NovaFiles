package com.novafiles.app.core.shelves

/**
 * ══════════════════════════════════════════════════════════
 *  SuggestionsEngine — v0.33 «الاقتراحات فوق الفهرس الفوري»
 *  أساس «نبضة التخزين» في الإصدار التالي: اقتراحات حتمية
 *  قابلة للشرح، كل اقتراح يحمل عدّاده والوفر المتوقع بالبايت.
 *  خالص JVM — صفر شبكة، صفر تتبع.
 * ══════════════════════════════════════════════════════════
 */
object SuggestionsEngine {

    /** أنواع الاقتراحات (الترجمة في الواجهة) */
    enum class SuggestionId { OLD_SCREENSHOTS, OLD_INSTALLERS, STALE_LARGE }

    /** اقتراح واحد: نوعه + عدد الملفات + الحجم الكلي بالبايت */
    data class Suggestion(
        val id: SuggestionId,
        val count: Int,
        val bytes: Long,
    )

    const val INSTALLER_AGE_DAYS = 30L
    const val STALE_LARGE_MIN_BYTES = 50L * 1024 * 1024
    const val STALE_LARGE_AGE_DAYS = 90L

    private val DAY_MS = 86_400_000L

    /**
     * حساب الاقتراحات من لقطة فهرس، مرتبة بالوفر المتوقع تنازلياً،
     * والاقتراحات الفارغة مُسقطة. حتمي بالكامل.
     */
    fun compute(
        index: List<SmartShelvesEngine.IndexedFile>,
        now: Long,
        limit: Int = 3,
    ): List<Suggestion> {
        require(limit > 0) { "limit must be positive" }
        val files = index.filter { !it.isDirectory && !SmartShelvesEngine.isExcluded(it.path) }

        val screenshotCutoff = now - SmartShelvesEngine.SCREENSHOT_DAYS * DAY_MS
        val installerCutoff = now - INSTALLER_AGE_DAYS * DAY_MS
        val staleCutoff = now - STALE_LARGE_AGE_DAYS * DAY_MS

        val oldShots = files.filter {
            SmartShelvesEngine.isScreenshot(it.name) && it.modified < screenshotCutoff
        }
        val installers = files.filter {
            it.name.substringAfterLast('.', "").lowercase() == "apk" &&
                it.parent.substringAfterLast('/', "") in SmartShelvesEngine.DOWNLOAD_DIRS &&
                it.modified < installerCutoff
        }
        val staleLarge = files.filter {
            it.size >= STALE_LARGE_MIN_BYTES && it.modified < staleCutoff
        }

        return listOf(
            Suggestion(SuggestionId.OLD_SCREENSHOTS, oldShots.size, oldShots.sumOf { it.size }),
            Suggestion(SuggestionId.OLD_INSTALLERS, installers.size, installers.sumOf { it.size }),
            Suggestion(SuggestionId.STALE_LARGE, staleLarge.size, staleLarge.sumOf { it.size }),
        )
            .filter { it.count > 0 }
            .sortedWith(compareByDescending<Suggestion> { it.bytes }.thenBy { it.id })
            .take(limit)
    }
}
