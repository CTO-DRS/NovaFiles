package com.novafiles.app.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات محرك أزواج المزامنة الخالص — كل القرارات حتمية موثقة:
 * اتجاه واحد، مرآة، ثنائي الاتجاه فوق أساس، تعارض بالسياسات، بصمات
 * التعميق، تطبيع المسارات، وإحصاءات الخطة.
 */
class SyncPairEngineTest {

    // ═══════════════ أدوات بناء اللقطات ═══════════════

    private fun f(path: String, size: Long = 100L, mtime: Long = 1000L) =
        SyncNode(path, size, mtime, isDir = false)

    private fun d(path: String, mtime: Long = 1000L) =
        SyncNode(path, 0L, mtime, isDir = true)

    private fun actions(plan: SyncPlan): Map<String, SyncAction> =
        plan.items.associate {
            if (it.action == SyncAction.SKIP) "@${it.relativePath}" to SyncAction.SKIP
            else it.relativePath to it.action
        }

    private fun item(plan: SyncPlan, path: String): SyncPlanItem =
        plan.items.first { it.relativePath == path }

    // ═══════════════ التطبيع ومسار التعارض ═══════════════

    @Test
    fun `normalize strips leading trailing slashes and empties`() {
        assertEquals("a/b/c", SyncPairEngine.normalizePath("/a//b/c/"))
        assertEquals("a/b", SyncPairEngine.normalizePath("\\a\\b"))
    }

    @Test
    fun `normalize drops dot-dot segments without popping parents`() {
        // الإسقاط أأمن من الانبثاق — مسارات اللقطات لا تحوي .. أصلاً،
        // وحارس التسلل الصريح في LocalSide يرفضها قبل التطبيع
        assertEquals("a/b/c", SyncPairEngine.normalizePath("a/../b/../c"))
        assertEquals("", SyncPairEngine.normalizePath(".."))
        assertEquals("", SyncPairEngine.normalizePath("///"))
    }

    @Test
    fun `conflict path inserts label before extension`() {
        assertEquals("docs/report (conflict).pdf", SyncPairEngine.conflictPathFor("docs/report.pdf"))
        assertEquals("ملاحظات (conflict).txt", SyncPairEngine.conflictPathFor("ملاحظات.txt"))
    }

    @Test
    fun `conflict path handles extensionless and dotfiles`() {
        assertEquals("README (conflict)", SyncPairEngine.conflictPathFor("README"))
        assertEquals(".gitignore (conflict)", SyncPairEngine.conflictPathFor(".gitignore"))
        assertEquals("", SyncPairEngine.conflictPathFor(""))
    }

    @Test
    fun `conflict path honors custom arabic label`() {
        assertEquals("a (نسخة).jpg", SyncPairEngine.conflictPathFor("a.jpg", "نسخة"))
    }

    // ═══════════════ الاتجاه الواحد ═══════════════

    @Test
    fun `one-way copies new source files`() {
        val plan = SyncPairEngine.plan(listOf(f("a.txt")), emptyList(), SyncDirection.ONE_WAY)
        assertEquals(SyncAction.COPY_S2D, actions(plan)["a.txt"])
        assertEquals(SyncReason.NEW_IN_SOURCE, item(plan, "a.txt").reason)
        assertEquals(100L, plan.stats.bytesToTransfer)
    }

    @Test
    fun `one-way skips identical metadata`() {
        val plan = SyncPairEngine.plan(listOf(f("a.txt")), listOf(f("a.txt")), SyncDirection.ONE_WAY)
        assertEquals(SyncAction.SKIP, actions(plan)["@a.txt"])
        assertEquals(1, plan.stats.skipped)
        assertEquals(0L, plan.stats.bytesToTransfer)
    }

    @Test
    fun `one-way updates on size change with changed-size reason`() {
        val plan = SyncPairEngine.plan(listOf(f("a.txt", size = 200L)), listOf(f("a.txt", size = 100L)), SyncDirection.ONE_WAY)
        assertEquals(SyncAction.UPDATE_S2D, actions(plan)["a.txt"])
        assertEquals(SyncReason.CHANGED_SIZE, item(plan, "a.txt").reason)
    }

    @Test
    fun `one-way updates on mtime change with changed-time reason`() {
        val plan = SyncPairEngine.plan(listOf(f("a.txt", mtime = 2000L)), listOf(f("a.txt", mtime = 1000L)), SyncDirection.ONE_WAY)
        assertEquals(SyncAction.UPDATE_S2D, actions(plan)["a.txt"])
        assertEquals(SyncReason.CHANGED_TIME, item(plan, "a.txt").reason)
    }

    @Test
    fun `one-way leaves dest-only files untouched and counts them`() {
        val plan = SyncPairEngine.plan(emptyList(), listOf(f("extra.txt")), SyncDirection.ONE_WAY)
        assertTrue(plan.items.none { it.relativePath == "extra.txt" })
        assertEquals(1, plan.stats.destOnlyKept)
        assertEquals(0, plan.stats.toDelete)
    }

    @Test
    fun `one-way skips directories with identical metadata`() {
        val plan = SyncPairEngine.plan(listOf(d("docs")), listOf(d("docs")), SyncDirection.ONE_WAY)
        assertEquals(SyncAction.SKIP, actions(plan)["@docs"])
        assertTrue(item(plan, "docs").isDir)
    }

    @Test
    fun `one-way mkdir for new source dirs with zero bytes`() {
        val plan = SyncPairEngine.plan(listOf(d("docs")), emptyList(), SyncDirection.ONE_WAY)
        assertEquals(SyncAction.MKDIR_S2D, actions(plan)["docs"])
        assertTrue(item(plan, "docs").isDir)
        assertEquals(0L, plan.stats.bytesToTransfer)
    }

    @Test
    fun `type mismatch deletes then rebuilds from source`() {
        // مصدر ملف مقابل وجهة مجلد بنفس الاسم
        val plan = SyncPairEngine.plan(listOf(f("x")), listOf(d("x")), SyncDirection.ONE_WAY)
        val xs = plan.items.filter { it.relativePath == "x" }
        assertEquals(2, xs.size)
        assertEquals(SyncAction.DELETE_D, xs[0].action)
        assertEquals(SyncAction.COPY_S2D, xs[1].action)
        assertEquals(SyncReason.TYPE_CHANGED, xs[0].reason)
        assertEquals(SyncReason.TYPE_CHANGED, xs[1].reason)
    }

    // ═══════════════ البصمات الاختيارية (تعميق v0.29) ═══════════════

    @Test
    fun `identical metadata with differing hashes flips to update`() {
        val plan = SyncPairEngine.plan(
            source = listOf(f("a.txt")),
            dest = listOf(f("a.txt")),
            direction = SyncDirection.ONE_WAY,
            sourceHashes = mapOf("a.txt" to "h1"),
            destHashes = mapOf("a.txt" to "h2"),
        )
        assertEquals(SyncAction.UPDATE_S2D, actions(plan)["a.txt"])
        assertEquals(SyncReason.CHANGED_CONTENT, item(plan, "a.txt").reason)
    }

    @Test
    fun `identical metadata with matching hashes stays skip`() {
        val plan = SyncPairEngine.plan(
            source = listOf(f("a.txt")),
            dest = listOf(f("a.txt")),
            direction = SyncDirection.ONE_WAY,
            sourceHashes = mapOf("a.txt" to "h1"),
            destHashes = mapOf("a.txt" to "h1"),
        )
        assertEquals(SyncAction.SKIP, actions(plan)["@a.txt"])
    }

    @Test
    fun `hashes ignored for files whose metadata differs`() {
        val plan = SyncPairEngine.plan(
            source = listOf(f("a.txt", size = 200L)),
            dest = listOf(f("a.txt", size = 100L)),
            direction = SyncDirection.ONE_WAY,
            sourceHashes = mapOf("a.txt" to "h1"),
            destHashes = mapOf("a.txt" to "h1"),
        )
        assertEquals(SyncAction.UPDATE_S2D, actions(plan)["a.txt"])
        assertEquals(SyncReason.CHANGED_SIZE, item(plan, "a.txt").reason)
    }

    // ═══════════════ المرآة ═══════════════

    @Test
    fun `mirror deletes dest-only files`() {
        val plan = SyncPairEngine.plan(emptyList(), listOf(f("extra.txt")), SyncDirection.MIRROR)
        assertEquals(SyncAction.DELETE_D, actions(plan)["extra.txt"])
        assertEquals(SyncReason.MISSING_IN_SOURCE_MIRROR, item(plan, "extra.txt").reason)
        assertEquals(1, plan.stats.toDelete)
    }

    @Test
    fun `mirror deletes dest-only dirs recursively as one item`() {
        val plan = SyncPairEngine.plan(emptyList(), listOf(d("oldDir")), SyncDirection.MIRROR)
        assertEquals(SyncAction.DELETE_D, actions(plan)["oldDir"])
        assertTrue(item(plan, "oldDir").isDir)
    }

    @Test
    fun `mirror full round trip copy update delete skip`() {
        val plan = SyncPairEngine.plan(
            source = listOf(d("docs"), f("docs/new.txt"), f("docs/changed.txt", size = 300L), f("docs/same.txt")),
            dest = listOf(d("docs"), f("docs/changed.txt", size = 100L), f("docs/same.txt"), f("docs/gone.txt")),
            direction = SyncDirection.MIRROR,
        )
        val a = actions(plan)
        assertEquals(SyncAction.SKIP, a["@docs"])
        assertEquals(SyncAction.COPY_S2D, a["docs/new.txt"])
        assertEquals(SyncAction.UPDATE_S2D, a["docs/changed.txt"])
        assertEquals(SyncAction.SKIP, a["@docs/same.txt"])
        assertEquals(SyncAction.DELETE_D, a["docs/gone.txt"])
        assertEquals(2, plan.stats.toTransfer)
        assertEquals(1, plan.stats.toDelete)
        assertEquals(2, plan.stats.skipped)
        // new.txt (100) + changed.txt (300)
        assertEquals(400L, plan.stats.bytesToTransfer)
    }

    // ═══════════════ ثنائي الاتجاه فوق أساس ═══════════════

    @Test
    fun `two-way first run without baseline copies both new sides`() {
        val plan = SyncPairEngine.plan(
            source = listOf(f("s.txt")),
            dest = listOf(f("d.txt")),
            direction = SyncDirection.TWO_WAY,
        )
        val a = actions(plan)
        assertEquals(SyncAction.COPY_S2D, a["s.txt"])
        assertEquals(SyncAction.COPY_D2S, a["d.txt"])
    }

    @Test
    fun `two-way propagates source-only change to dest`() {
        val base = listOf(f("a.txt", size = 100L))
        val plan = SyncPairEngine.plan(
            source = listOf(f("a.txt", size = 200L, mtime = 2000L)),
            dest = base,
            direction = SyncDirection.TWO_WAY,
            baseline = base,
        )
        assertEquals(SyncAction.UPDATE_S2D, actions(plan)["a.txt"])
        assertEquals(SyncReason.CHANGED_SIZE, item(plan, "a.txt").reason)
    }

    @Test
    fun `two-way propagates dest-only change to source`() {
        val base = listOf(f("a.txt", size = 100L))
        val plan = SyncPairEngine.plan(
            source = base,
            dest = listOf(f("a.txt", size = 500L, mtime = 3000L)),
            direction = SyncDirection.TWO_WAY,
            baseline = base,
        )
        assertEquals(SyncAction.UPDATE_D2S, actions(plan)["a.txt"])
        assertEquals(SyncReason.CHANGED_SIZE, item(plan, "a.txt").reason)
    }

    @Test
    fun `two-way propagates source-side deletion to dest`() {
        val base = listOf(f("a.txt"))
        val plan = SyncPairEngine.plan(
            source = emptyList(),
            dest = base,
            direction = SyncDirection.TWO_WAY,
            baseline = base,
        )
        assertEquals(SyncAction.DELETE_D, actions(plan)["a.txt"])
        assertEquals(SyncReason.MISSING_IN_SOURCE, item(plan, "a.txt").reason)
    }

    @Test
    fun `two-way propagates dest-side deletion to source`() {
        val base = listOf(f("a.txt"))
        val plan = SyncPairEngine.plan(
            source = base,
            dest = emptyList(),
            direction = SyncDirection.TWO_WAY,
            baseline = base,
        )
        assertEquals(SyncAction.DELETE_S, actions(plan)["a.txt"])
        assertEquals(SyncReason.MISSING_IN_DEST, item(plan, "a.txt").reason)
    }

    @Test
    fun `two-way identical unchanged files emit nothing`() {
        val base = listOf(f("a.txt"), f("b.txt"))
        val plan = SyncPairEngine.plan(base, base, SyncDirection.TWO_WAY, baseline = base)
        assertEquals(0, plan.items.size)
        assertEquals(0, plan.stats.toTransfer)
    }

    @Test
    fun `two-way deleted on both sides emits nothing`() {
        val base = listOf(f("a.txt"), f("b.txt"))
        val plan = SyncPairEngine.plan(
            source = listOf(f("b.txt")),
            dest = listOf(f("b.txt")),
            direction = SyncDirection.TWO_WAY,
            baseline = base,
        )
        assertTrue(plan.items.none { it.relativePath == "a.txt" })
    }

    @Test
    fun `two-way converged to same state emits nothing`() {
        val base = listOf(f("a.txt", size = 100L))
        val edited = listOf(f("a.txt", size = 300L, mtime = 5000L))
        val plan = SyncPairEngine.plan(edited, edited, SyncDirection.TWO_WAY, baseline = base)
        assertTrue(plan.items.none { it.relativePath == "a.txt" })
    }

    @Test
    fun `two-way new dirs on both sides mkdir in opposite direction`() {
        val plan = SyncPairEngine.plan(
            source = listOf(d("sd"), f("sd/x.txt")),
            dest = listOf(d("dd"), f("dd/y.txt")),
            direction = SyncDirection.TWO_WAY,
        )
        val a = actions(plan)
        assertEquals(SyncAction.MKDIR_D2S, a["dd"])
        assertEquals(SyncAction.MKDIR_S2D, a["sd"])
        assertEquals(SyncAction.COPY_S2D, a["sd/x.txt"])
        assertEquals(SyncAction.COPY_D2S, a["dd/y.txt"])
    }

    @Test
    fun `two-way source deletion vs dest modification keeps modification`() {
        val base = listOf(f("a.txt", size = 100L, mtime = 1000L))
        val plan = SyncPairEngine.plan(
            source = emptyList(),
            dest = listOf(f("a.txt", size = 100L, mtime = 9000L)),
            direction = SyncDirection.TWO_WAY,
            baseline = base,
        )
        assertEquals(SyncAction.COPY_D2S, actions(plan)["a.txt"])
        assertEquals(SyncReason.CONFLICT_MODIFIED_KEPT, item(plan, "a.txt").reason)
        assertEquals(1, plan.stats.conflicts)
    }

    @Test
    fun `two-way dest deletion vs source modification keeps modification`() {
        val base = listOf(f("a.txt", size = 100L, mtime = 1000L))
        val plan = SyncPairEngine.plan(
            source = listOf(f("a.txt", size = 100L, mtime = 9000L)),
            dest = emptyList(),
            direction = SyncDirection.TWO_WAY,
            baseline = base,
        )
        assertEquals(SyncAction.COPY_S2D, actions(plan)["a.txt"])
        assertEquals(SyncReason.CONFLICT_MODIFIED_KEPT, item(plan, "a.txt").reason)
    }

    // ═══════════════ سياسة الأحدث يفوز ═══════════════

    @Test
    fun `newer-wins source newer overwrites dest`() {
        val base = listOf(f("a.txt", size = 100L))
        val plan = SyncPairEngine.plan(
            source = listOf(f("a.txt", size = 111L, mtime = 9000L)),
            dest = listOf(f("a.txt", size = 222L, mtime = 3000L)),
            direction = SyncDirection.TWO_WAY,
            policy = ConflictPolicy.NEWER_WINS,
            baseline = base,
        )
        assertEquals(SyncAction.UPDATE_S2D, actions(plan)["a.txt"])
        assertEquals(SyncReason.CONFLICT_SOURCE_NEWER, item(plan, "a.txt").reason)
        assertEquals(1, plan.stats.conflicts)
    }

    @Test
    fun `newer-wins dest newer overwrites source`() {
        val base = listOf(f("a.txt", size = 100L))
        val plan = SyncPairEngine.plan(
            source = listOf(f("a.txt", size = 111L, mtime = 2000L)),
            dest = listOf(f("a.txt", size = 222L, mtime = 8000L)),
            direction = SyncDirection.TWO_WAY,
            policy = ConflictPolicy.NEWER_WINS,
            baseline = base,
        )
        assertEquals(SyncAction.UPDATE_D2S, actions(plan)["a.txt"])
        assertEquals(SyncReason.CONFLICT_DEST_NEWER, item(plan, "a.txt").reason)
    }

    @Test
    fun `newer-wins tie resolves to source deterministically`() {
        val base = listOf(f("a.txt", size = 100L))
        val plan = SyncPairEngine.plan(
            source = listOf(f("a.txt", size = 111L, mtime = 5000L)),
            dest = listOf(f("a.txt", size = 222L, mtime = 5000L)),
            direction = SyncDirection.TWO_WAY,
            policy = ConflictPolicy.NEWER_WINS,
            baseline = base,
        )
        assertEquals(SyncAction.UPDATE_S2D, actions(plan)["a.txt"])
        assertEquals(SyncReason.CONFLICT_TIE_SOURCE_WINS, item(plan, "a.txt").reason)
    }

    // ═══════════════ سياسة الاحتفاظ بالنسختين ═══════════════

    @Test
    fun `keep-both saves loser copy within dest then overwrites`() {
        val base = listOf(f("docs/a.txt", size = 100L))
        val plan = SyncPairEngine.plan(
            source = listOf(f("docs/a.txt", size = 111L, mtime = 9000L)),
            dest = listOf(f("docs/a.txt", size = 222L, mtime = 3000L)),
            direction = SyncDirection.TWO_WAY,
            policy = ConflictPolicy.KEEP_BOTH,
            baseline = base,
        )
        val xs = plan.items.filter { it.relativePath == "docs/a.txt" }
        assertEquals(2, xs.size)
        assertEquals(SyncAction.COPY_WITHIN_D, xs[0].action)
        assertEquals("docs/a (conflict).txt", xs[0].conflictCopyPath)
        assertEquals(SyncAction.UPDATE_S2D, xs[1].action)
        assertEquals(SyncReason.CONFLICT_SOURCE_NEWER, xs[1].reason)
        assertEquals(1, plan.stats.conflicts)
    }

    @Test
    fun `keep-both dest winner saves loser copy within source`() {
        val base = listOf(f("a.txt", size = 100L))
        val plan = SyncPairEngine.plan(
            source = listOf(f("a.txt", size = 111L, mtime = 2000L)),
            dest = listOf(f("a.txt", size = 222L, mtime = 8000L)),
            direction = SyncDirection.TWO_WAY,
            policy = ConflictPolicy.KEEP_BOTH,
            baseline = base,
        )
        val xs = plan.items.filter { it.relativePath == "a.txt" }
        assertEquals(2, xs.size)
        assertEquals(SyncAction.COPY_WITHIN_S, xs[0].action)
        assertEquals("a (conflict).txt", xs[0].conflictCopyPath)
        assertEquals(SyncAction.UPDATE_D2S, xs[1].action)
        assertEquals(SyncReason.CONFLICT_DEST_NEWER, xs[1].reason)
    }

    @Test
    fun `keep-both uses custom arabic label`() {
        val base = listOf(f("a.txt"))
        val plan = SyncPairEngine.plan(
            source = listOf(f("a.txt", size = 111L, mtime = 9000L)),
            dest = listOf(f("a.txt", size = 222L, mtime = 3000L)),
            direction = SyncDirection.TWO_WAY,
            policy = ConflictPolicy.KEEP_BOTH,
            baseline = base,
            conflictLabel = "نسخة",
        )
        assertEquals("a (نسخة).txt", plan.items.first { it.action == SyncAction.COPY_WITHIN_D }.conflictCopyPath)
    }

    // ═══════════════ بصمات التعميق في ثنائي الاتجاه ═══════════════

    @Test
    fun `two-way unchanged metadata with differing hashes conflicts via policy`() {
        val base = listOf(f("a.txt", size = 100L, mtime = 1000L))
        val plan = SyncPairEngine.plan(
            source = listOf(f("a.txt", size = 100L, mtime = 1000L)),
            dest = listOf(f("a.txt", size = 100L, mtime = 1000L)),
            direction = SyncDirection.TWO_WAY,
            policy = ConflictPolicy.KEEP_BOTH,
            baseline = base,
            sourceHashes = mapOf("a.txt" to "h1"),
            destHashes = mapOf("a.txt" to "h2"),
        )
        // التعادل الزمني: المصدر يفوز + نسخة الوجهة محفوظة
        val xs = plan.items.filter { it.relativePath == "a.txt" }
        assertEquals(2, xs.size)
        assertEquals(SyncAction.COPY_WITHIN_D, xs[0].action)
        assertEquals(SyncAction.UPDATE_S2D, xs[1].action)
        assertEquals(SyncReason.CONFLICT_TIE_SOURCE_WINS, xs[1].reason)
    }

    @Test
    fun `two-way unchanged metadata with matching hashes emits nothing`() {
        val base = listOf(f("a.txt", size = 100L))
        val plan = SyncPairEngine.plan(
            source = base, dest = base, direction = SyncDirection.TWO_WAY, baseline = base,
            sourceHashes = mapOf("a.txt" to "same"),
            destHashes = mapOf("a.txt" to "same"),
        )
        assertEquals(0, plan.items.size)
    }

    // ═══════════════ الحتمية والإحصاءات ═══════════════

    @Test
    fun `plan ordering is deterministic lexicographic`() {
        val plan = SyncPairEngine.plan(
            source = listOf(f("c.txt"), f("a.txt"), f("b.txt")),
            dest = emptyList(),
            direction = SyncDirection.ONE_WAY,
        )
        assertEquals(listOf("a.txt", "b.txt", "c.txt"), plan.items.map { it.relativePath })
    }

    @Test
    fun `plan is stable across repeated calls`() {
        val s = listOf(d("docs"), f("docs/a.txt"), f("z.txt"))
        val dst = listOf(f("z.txt", size = 5L))
        val p1 = SyncPairEngine.plan(s, dst, SyncDirection.MIRROR)
        val p2 = SyncPairEngine.plan(s, dst, SyncDirection.MIRROR)
        assertEquals(p1, p2)
    }

    @Test
    fun `stats summarize transfer delete skip bytes`() {
        val plan = SyncPairEngine.plan(
            source = listOf(f("new.txt", size = 10L), f("upd.txt", size = 20L), f("same.txt")),
            dest = listOf(f("upd.txt", size = 5L), f("same.txt"), f("dead.txt")),
            direction = SyncDirection.MIRROR,
        )
        assertEquals(2, plan.stats.toTransfer)
        assertEquals(1, plan.stats.toDelete)
        assertEquals(1, plan.stats.skipped)
        assertEquals(30L, plan.stats.bytesToTransfer)
        assertEquals(0, plan.stats.destOnlyKept)
    }

    @Test
    fun `empty snapshots produce empty plan`() {
        val plan = SyncPairEngine.plan(emptyList(), emptyList(), SyncDirection.MIRROR)
        assertEquals(0, plan.items.size)
        assertEquals(0, plan.stats.toTransfer)
        assertEquals(0, plan.stats.destOnlyKept)
    }

    @Test
    fun `paths with arabic and spaces sort and compare correctly`() {
        val plan = SyncPairEngine.plan(
            source = listOf(f("المستندات/تقرير 2026.pdf", size = 7L)),
            dest = emptyList(),
            direction = SyncDirection.ONE_WAY,
        )
        assertEquals(SyncAction.COPY_S2D, actions(plan)["المستندات/تقرير 2026.pdf"])
        assertEquals(7L, plan.stats.bytesToTransfer)
    }

    @Test
    fun `unicode emoji filenames survive planning intact`() {
        val name = "صورة 🎉 نهائية.png"
        val plan = SyncPairEngine.plan(listOf(f(name)), emptyList(), SyncDirection.ONE_WAY)
        assertEquals(SyncAction.COPY_S2D, actions(plan)[name])
    }

    @Test
    fun `same relative path with leading slash normalizes identically`() {
        val plan = SyncPairEngine.plan(
            source = listOf(f("/a.txt")),
            dest = listOf(f("a.txt")),
            direction = SyncDirection.ONE_WAY,
        )
        assertEquals(SyncAction.SKIP, actions(plan)["@a.txt"])
        assertEquals(1, plan.items.size)
    }

    // ═══════════════ تحذير عدم مزامنة الذات ═══════════════

    @Test
    fun `nested dir tree plans parents before children for mirror delete`() {
        // حذف مجلد كامل كعنصر واحد — الأبناء غير الموجودين في المصدر لا
        // يحتاجون حذفاً فردياً حين يُحذف الأب تكرارياً
        val plan = SyncPairEngine.plan(
            source = emptyList(),
            dest = listOf(d("old"), f("old/x.txt"), f("old/y.txt")),
            direction = SyncDirection.MIRROR,
        )
        // old يُحذف تكرارياً؛ x وy داخل old تظهر أيضاً في الخطة (مسارات منفصلة)
        val a = actions(plan)
        assertEquals(SyncAction.DELETE_D, a["old"])
        assertEquals(SyncAction.DELETE_D, a["old/x.txt"])
        assertEquals(SyncAction.DELETE_D, a["old/y.txt"])
        assertEquals(3, plan.stats.toDelete)
    }
}
