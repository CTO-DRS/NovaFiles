package com.novafiles.app.core.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات صندوق المراجعة الأسبوعي — لا حذف صامت، والتجاهل يُحترم */
class ReviewBoxTest {

    private val now = 1_800_000_000_000L
    private val day = DownloadClassifier.DAY_MS

    private fun item(path: String, size: Long, clazz: DownloadClass, ageDays: Int = 10) =
        ReviewItem(path = path, name = path.substringAfterLast('/'), size = size, ageDays = ageDays, clazz = clazz)

    private val sample = listOf(
        item("/D/a.pdf", 500L, DownloadClass.DOCUMENT),
        item("/D/photo.jpg", 1000L, DownloadClass.MEDIA),
        item("/D/app.apk", 2000L, DownloadClass.INSTALLER),
        item("/D/bk.zip", 300L, DownloadClass.ARCHIVE),
        item("/D/b (1).dat", 100L, DownloadClass.CLUTTER),
        item("/D/a (1).dat", 100L, DownloadClass.CLUTTER),
    )

    // ═══════════════ الموعد الأسبوعي ═══════════════

    @Test
    fun `due immediately when never reviewed`() {
        val b = ReviewBox.build(sample, lastReviewedAt = null, nowMs = now)
        assertTrue(b.due)
        assertNull(b.daysSinceLastReview)
    }

    @Test
    fun `not due within six days`() {
        val b = ReviewBox.build(sample, lastReviewedAt = now - 6 * day, nowMs = now)
        assertFalse(b.due)
        assertEquals(6L, b.daysSinceLastReview)
    }

    @Test
    fun `due at exactly seven days`() {
        val b = ReviewBox.build(sample, lastReviewedAt = now - 7 * day, nowMs = now)
        assertTrue(b.due)
        assertEquals(7L, b.daysSinceLastReview)
    }

    @Test
    fun `custom interval respected`() {
        val b = ReviewBox.build(sample, lastReviewedAt = now - 2 * day, nowMs = now, intervalDays = 2)
        assertTrue(b.due)
    }

    // ═══════════════ التجميع والترتيب الحتمي ═══════════════

    @Test
    fun `groups follow enum order`() {
        val b = ReviewBox.build(sample, lastReviewedAt = null, nowMs = now)
        assertEquals(
            listOf(
                DownloadClass.DOCUMENT,
                DownloadClass.MEDIA,
                DownloadClass.INSTALLER,
                DownloadClass.ARCHIVE,
                DownloadClass.CLUTTER,
            ),
            b.groups.map { it.first },
        )
    }

    @Test
    fun `items sorted by size desc then name asc`() {
        val b = ReviewBox.build(sample, lastReviewedAt = null, nowMs = now)
        val clutter = b.groups.first { it.first == DownloadClass.CLUTTER }.second
        assertEquals(listOf("a (1).dat", "b (1).dat"), clutter.map { it.name })

        val bigger = listOf(
            item("/D/x.jpg", 50L, DownloadClass.MEDIA),
            item("/D/y.jpg", 500L, DownloadClass.MEDIA),
            item("/D/z.jpg", 500L, DownloadClass.MEDIA),
        )
        val b2 = ReviewBox.build(bigger, lastReviewedAt = null, nowMs = now)
        val media = b2.groups.single().second
        assertEquals(listOf("/D/y.jpg", "/D/z.jpg", "/D/x.jpg"), media.map { it.path })
    }

    @Test
    fun `empty classes are omitted`() {
        val onlyMedia = listOf(item("/D/x.jpg", 10L, DownloadClass.MEDIA))
        val b = ReviewBox.build(onlyMedia, lastReviewedAt = null, nowMs = now)
        assertEquals(listOf(DownloadClass.MEDIA), b.groups.map { it.first })
    }

    // ═══════════════ التجاهل النهائي ═══════════════

    @Test
    fun `dismissed paths are excluded permanently`() {
        val dismissed = setOf("/D/a.pdf", "/D/app.apk")
        val b = ReviewBox.build(sample, lastReviewedAt = null, nowMs = now, dismissedPaths = dismissed)
        val all = b.groups.flatMap { it.second }.map { it.path }
        assertFalse("/D/a.pdf" in all)
        assertFalse("/D/app.apk" in all)
        assertEquals(4, all.size)
    }

    @Test
    fun `all dismissed yields empty groups but due state preserved`() {
        val dismissed = sample.map { it.path }.toSet()
        val b = ReviewBox.build(sample, lastReviewedAt = now - 7 * day, nowMs = now, dismissedPaths = dismissed)
        assertTrue(b.groups.isEmpty())
        assertTrue(b.due)
    }

    // ═══════════════ التحويل من المصنّف ═══════════════

    @Test
    fun `itemsOf maps classified files field by field`() {
        val cf = ClassifiedFile("/D/x.pdf", "x.pdf", 42L, modified = now - 3 * day, ageDays = 3, clazz = DownloadClass.DOCUMENT)
        val ri = ReviewBox.itemsOf(listOf(cf)).single()
        assertEquals("/D/x.pdf", ri.path)
        assertEquals("x.pdf", ri.name)
        assertEquals(42L, ri.size)
        assertEquals(3, ri.ageDays)
        assertEquals(DownloadClass.DOCUMENT, ri.clazz)
    }

    @Test
    fun `empty input yields empty bundle`() {
        val b = ReviewBox.build(emptyList(), lastReviewedAt = now, nowMs = now)
        assertTrue(b.groups.isEmpty())
        assertFalse(b.due)
        assertEquals(0L, b.daysSinceLastReview)
    }
}
