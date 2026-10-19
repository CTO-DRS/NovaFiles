package com.novafiles.app.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات محرك الإيماءات (v0.17) — سحب الصفوف، السحب والإفلات بين اللوحتين،
 * وسياسة النقر المزدوج. JVM خالصة.
 */
class GestureTest {

    // ---------- SwipeActionResolver ----------

    private fun resolver(
        enabled: Boolean = true,
        right: SwipeAction = SwipeAction.FAVORITE,
        left: SwipeAction = SwipeAction.DETAILS,
        ratio: Float = 0.42f,
    ) = SwipeActionResolver(GestureConfig(enabled = enabled, rightAction = right, leftAction = left, commitRatio = ratio))

    @Test
    fun `swipe right beyond threshold commits favorite`() {
        val d = resolver().resolve(0f, 200f, rowWidth = 300f)
        assertTrue(d is SwipeDecision.Committed)
        assertEquals(SwipeAction.FAVORITE, (d as SwipeDecision.Committed).action)
        assertEquals(SwipeDirection.RIGHT, d.direction)
    }

    @Test
    fun `swipe left beyond threshold commits details`() {
        val d = resolver().resolve(200f, 0f, rowWidth = 300f)
        assertTrue(d is SwipeDecision.Committed)
        assertEquals(SwipeAction.DETAILS, (d as SwipeDecision.Committed).action)
        assertEquals(SwipeDirection.LEFT, d.direction)
    }

    @Test
    fun `swipe below threshold cancels`() {
        val d = resolver().resolve(100f, 130f, rowWidth = 300f)
        assertTrue(d is SwipeDecision.Cancelled)
    }

    @Test
    fun `tiny movement below min distance is none`() {
        val d = resolver().resolve(100f, 112f, rowWidth = 300f)
        assertEquals(SwipeDecision.None, d)
    }

    @Test
    fun `disabled config ignores everything`() {
        val d = resolver(enabled = false).resolve(100f, 300f, rowWidth = 300f)
        assertEquals(SwipeDecision.None, d)
    }

    @Test
    fun `none action disables that direction only`() {
        val r = resolver(right = SwipeAction.NONE)
        assertEquals(SwipeDecision.None, r.resolve(100f, 300f, 300f))
        assertTrue(r.resolve(300f, 100f, 300f) is SwipeDecision.Committed)
    }

    @Test
    fun `zero row width is safe none`() {
        assertEquals(SwipeDecision.None, resolver().resolve(0f, 100f, 0f))
    }

    @Test
    fun `config serialization roundtrip`() {
        val cfg = GestureConfig(enabled = false, rightAction = SwipeAction.SHARE, leftAction = SwipeAction.TRASH, commitRatio = 0.6f)
        val parsed = GestureConfig.parse(cfg.serialize())
        assertEquals(cfg, parsed)
    }

    @Test
    fun `config parse of garbage falls back to defaults`() {
        val cfg = GestureConfig.parse("garbage=;right=NOPE;;;left=share")
        assertEquals(true, cfg.enabled)
        assertEquals(SwipeAction.DEFAULT_RIGHT, cfg.rightAction)
        assertEquals(SwipeAction.SHARE, cfg.leftAction)
    }

    @Test
    fun `config parse null or blank gives defaults`() {
        assertEquals(GestureConfig(), GestureConfig.parse(null))
        assertEquals(GestureConfig(), GestureConfig.parse(""))
    }

    @Test
    fun `destructive actions flagged`() {
        assertTrue(SwipeAction.DELETE.destructive)
        assertTrue(SwipeAction.TRASH.destructive)
        assertFalse(SwipeAction.FAVORITE.destructive)
        assertFalse(SwipeAction.DETAILS.destructive)
    }

    @Test
    fun `defaults are non destructive`() {
        assertFalse(GestureConfig().rightAction.destructive)
        assertFalse(GestureConfig().leftAction.destructive)
    }

    // ---------- DragDropResolver ----------

    @Test
    fun `cross pane drop defaults to copy`() {
        val d = DragDropResolver.resolve(
            DragPayload(listOf("/a/f.txt"), PaneId.PRIMARY),
            DropTarget("/b", PaneId.SECONDARY),
            sourceDir = "/a",
        )
        assertEquals(DropOp.COPY, d.op)
        assertEquals(listOf("/a/f.txt"), d.paths)
    }

    @Test
    fun `cross pane drop with move request moves`() {
        val d = DragDropResolver.resolve(
            DragPayload(listOf("/a/f.txt"), PaneId.PRIMARY, moveRequested = true),
            DropTarget("/b", PaneId.SECONDARY),
            sourceDir = "/a",
        )
        assertEquals(DropOp.MOVE, d.op)
    }

    @Test
    fun `same pane drop onto folder is internal move`() {
        val d = DragDropResolver.resolve(
            DragPayload(listOf("/a/f.txt"), PaneId.PRIMARY),
            DropTarget("/a/sub", PaneId.PRIMARY),
            sourceDir = "/a",
        )
        assertEquals(DropOp.MOVE, d.op)
    }

    @Test
    fun `drop on source dir rejected`() {
        val d = DragDropResolver.resolve(
            DragPayload(listOf("/a/f.txt"), PaneId.PRIMARY),
            DropTarget("/a", PaneId.PRIMARY),
            sourceDir = "/a",
        )
        assertEquals(DropOp.NONE, d.op)
        assertEquals(DropDenyReason.SAME_DIR, d.reason)
    }

    @Test
    fun `folder cannot drop into itself or subtree`() {
        val d = DragDropResolver.resolve(
            DragPayload(listOf("/a/media"), PaneId.PRIMARY),
            DropTarget("/a/media/2026", PaneId.SECONDARY),
            sourceDir = "/a",
        )
        assertEquals(DropOp.NONE, d.op)
        assertEquals(DropDenyReason.INTO_SELF, d.reason)
    }

    @Test
    fun `drop on dragged file itself excluded`() {
        val d = DragDropResolver.resolve(
            DragPayload(listOf("/a/f.txt", "/a/g.txt"), PaneId.PRIMARY),
            DropTarget("/a/f.txt", PaneId.SECONDARY),
            sourceDir = "/a",
        )
        assertEquals(DropOp.COPY, d.op)
        assertEquals(listOf("/a/g.txt"), d.paths)
    }

    @Test
    fun `no target is none`() {
        val d = DragDropResolver.resolve(
            DragPayload(listOf("/a/f.txt"), PaneId.PRIMARY),
            null,
            sourceDir = "/a",
        )
        assertEquals(DropOp.NONE, d.op)
        assertTrue(d.paths.isEmpty())
    }

    @Test
    fun `mixed batch keeps only acceptable paths`() {
        val d = DragDropResolver.resolve(
            DragPayload(listOf("/media", "/media/2026/x.jpg", "/docs"), PaneId.PRIMARY),
            DropTarget("/media/2026", PaneId.SECONDARY),
            sourceDir = "/",
        )
        assertEquals(DropOp.COPY, d.op)
        // /media سلف الهدف → مستبعد؛ /media/2026/x.jpg داخل نفس الهدف → مقبول؟ الهدف مجلد تُنسخ إليه الملفات — مقبول
        // لكن /docs مقبول
        assertEquals(listOf("/media/2026/x.jpg", "/docs"), d.paths)
    }

    // ---------- DoubleTapPolicy ----------

    @Test
    fun `second tap within window completes double tap`() {
        val p = DoubleTapPolicy(windowMs = 300)
        assertFalse(p.onTap(1000, "row1"))
        assertTrue(p.onTap(1150, "row1"))
    }

    @Test
    fun `tap outside window starts fresh`() {
        val p = DoubleTapPolicy(windowMs = 300)
        assertFalse(p.onTap(1000, "row1"))
        assertFalse(p.onTap(1400, "row1"))
        assertTrue(p.onTap(1500, "row1"))
    }

    @Test
    fun `different item resets the pending tap`() {
        val p = DoubleTapPolicy(windowMs = 300)
        assertFalse(p.onTap(1000, "row1"))
        assertFalse(p.onTap(1100, "row2"))
        assertFalse(p.onTap(1150, "row1"))
    }

    @Test
    fun `pending key exposed for selection highlight`() {
        val p = DoubleTapPolicy(windowMs = 300)
        assertNull(p.pendingKey())
        p.onTap(1000, "row1")
        assertEquals("row1", p.pendingKey())
    }

    @Test
    fun `reset clears pending state`() {
        val p = DoubleTapPolicy(windowMs = 300)
        p.onTap(1000, "row1")
        p.reset()
        assertNull(p.pendingKey())
        assertFalse(p.onTap(1100, "row1"))
    }
}
