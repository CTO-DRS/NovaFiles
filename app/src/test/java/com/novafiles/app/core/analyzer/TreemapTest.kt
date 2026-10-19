package com.novafiles.app.core.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** اختبارات خوارزمية Squarified Treemap — النسب والأغطية والحدود */
class TreemapTest {

    private fun rect(node: TreemapNode, x: Float, y: Float, w: Float, h: Float) = TreemapRect(node, x, y, w, h)

    @Test
    fun `empty input returns empty output`() {
        assertTrue(Treemap.squarify(emptyList(), 100f, 100f).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive canvas rejected`() {
        Treemap.squarify(listOf(TreemapNode("a", 10L, "/a", false)), 0f, 100f)
    }

    @Test
    fun `single item covers whole canvas`() {
        val out = Treemap.squarify(listOf(TreemapNode("only", 100L, "/only", false)), 200f, 100f, padding = 0f)
        assertEquals(1, out.size)
        assertEquals(200f * 100f, out[0].area, 0.01f)
    }

    @Test
    fun `areas are proportional to values`() {
        val items = listOf(
            TreemapNode("a", 60L, "/a", false),
            TreemapNode("b", 30L, "/b", false),
            TreemapNode("c", 10L, "/c", false),
        )
        val out = Treemap.squarify(items, 100f, 100f, padding = 0f)
        assertEquals(3, out.size)
        val canvas = 100f * 100f
        assertEquals(0.6f * canvas, out.first { it.node.label == "a" }.area, canvas * 0.001f)
        assertEquals(0.3f * canvas, out.first { it.node.label == "b" }.area, canvas * 0.001f)
        assertEquals(0.1f * canvas, out.first { it.node.label == "c" }.area, canvas * 0.001f)
    }

    @Test
    fun `rects tile the canvas without gaps`() {
        val items = (1..12).map { TreemapNode("n$it", it * 37L, "/n$it", false) }
        val out = Treemap.squarify(items, 320f, 240f, padding = 0f)
        val sum = out.sumOf { it.area.toDouble() }.toFloat()
        assertEquals(320f * 240f, sum, 0.5f)
    }

    @Test
    fun `rects stay inside bounds`() {
        val items = (1..30).map { TreemapNode("f$it", (it * 1103515245L) % 1000 + 1L, "/f$it", false) }
        val out = Treemap.squarify(items, 1080f, 800f, padding = 1.5f)
        for (r in out) {
            assertTrue("x>=0: ${r.x}", r.x >= -0.01f)
            assertTrue("y>=0: ${r.y}", r.y >= -0.01f)
            assertTrue("right<=W: ${r.x + r.w}", r.x + r.w <= 1080f + 0.01f)
            assertTrue("bottom<=H: ${r.y + r.h}", r.y + r.h <= 800f + 0.01f)
        }
    }

    @Test
    fun `no rect overlaps another`() {
        val items = (1..20).map { TreemapNode("x$it", it * 13L, "/x$it", false) }
        val out = Treemap.squarify(items, 400f, 300f, padding = 2f)
        for (i in out.indices) {
            for (j in i + 1 until out.size) {
                val a = out[i]
                val b = out[j]
                val overlapX = a.x < b.x + b.w - 0.01f && b.x < a.x + a.w - 0.01f
                val overlapY = a.y < b.y + b.h - 0.01f && b.y < a.y + a.h - 0.01f
                assertTrue("تقاطع بين $i و $j", !(overlapX && overlapY))
            }
        }
    }

    @Test
    fun `aspect ratios stay reasonable for varied inputs`() {
        val items = (1..25).map { TreemapNode("d$it", (it * 7919L) % 500 + 5L, "/d$it", false) }
        val out = Treemap.squarify(items, 800f, 600f, padding = 1f)
        for (r in out) {
            if (r.w > 0.5f && r.h > 0.5f) {
                val ratio = if (r.w >= r.h) r.w / r.h else r.h / r.w
                assertTrue("نسبة أبعاد سيئة ${r.w}x${r.h}", ratio <= 8f)
            }
        }
    }

    @Test
    fun `all zero values still render evenly`() {
        val items = (1..4).map { TreemapNode("z$it", 0L, "/z$it", true) }
        val out = Treemap.squarify(items, 200f, 100f)
        assertEquals(4, out.size)
        // كلها بعرض متساوٍ تقريباً
        val widths = out.map { it.w }
        assertTrue(abs(widths[0] - widths[3]) < 0.5f)
    }

    @Test
    fun `unsorted input still yields proportional areas`() {
        val items = listOf(
            TreemapNode("small", 10L, "/s", false),
            TreemapNode("big", 90L, "/b", false),
        )
        val out = Treemap.squarify(items, 100f, 100f, padding = 0f)
        assertEquals(0.9f * 10000f, out.first { it.node.label == "big" }.area, 1f)
    }

    @Test
    fun `wide vs tall canvas both work`() {
        val items = (1..8).map { TreemapNode("w$it", it * 100L, "/w$it", false) }
        val wide = Treemap.squarify(items, 1000f, 200f, padding = 0f)
        val tall = Treemap.squarify(items, 200f, 1000f, padding = 0f)
        assertEquals(wide.sumOf { it.area.toDouble() }.toFloat(), tall.sumOf { it.area.toDouble() }.toFloat(), 0.01f)
        assertEquals(1000f * 200f, wide.sumOf { it.area.toDouble() }.toFloat(), 0.1f)
    }
}
