package com.novafiles.app.core.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات TagsEngine — منطق الوسوم الخالص (JVM)
 * إنشاء/تطبيع/تكرار/تسمية/تلوين/إسناد/فلترة/عدّ
 */
class TagsEngineTest {

    private val NOW = 1_700_000_000_000L

    private fun stateOf(vararg names: String): TagsEngine.TagsState {
        var s = TagsEngine.TagsState()
        names.forEach { s = TagsEngine.create(s, it, now = NOW).state }
        return s
    }

    private fun TagsEngine.TagsState.idOf(name: String): Long =
        defs.first { it.name == name }.id

    // ═══════════ التطبيع ═══════════

    @Test
    fun `normalize trims and collapses whitespace`() {
        assertEquals("العمل", TagsEngine.normalize("  العمل  "))
        assertEquals("a b c", TagsEngine.normalize("a \t\n b   c"))
    }

    @Test
    fun `normalize strips control and invisible marks but keeps ZWJ emoji intact`() {
        // BOM + LRM + ZWSP تُحذف
        assertEquals("تقرير", TagsEngine.normalize("\uFEFFتقرير\u200F\u200B"))
        // ZWJ في إيموجي مركّب (عائلة) يبقى — لا يتفكك الإيموجي
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals("$family مهم", TagsEngine.normalize("$family مهم"))
        assertTrue(TagsEngine.normalize("$family مهم")!!.contains("\u200D"))
    }

    @Test
    fun `normalize truncates at code point boundary for arabic and emoji`() {
        val arabic = "أبجدهوزحطيكلمنسعفصقرشتثخذضظغ".repeat(2)
        val cut = TagsEngine.normalize(arabic)!!
        assertEquals(TagsEngine.MAX_NAME_CODE_POINTS, cut.codePointCount(0, cut.length))
        // لا نقطة ترميز نصفية: إعادة التطبيع لا تغيّر الناتج
        assertEquals(cut, TagsEngine.normalize(cut))

        val emoji = "🔥".repeat(30)
        val cutEmoji = TagsEngine.normalize(emoji)!!
        assertEquals(TagsEngine.MAX_NAME_CODE_POINTS, cutEmoji.codePointCount(0, cutEmoji.length))
        // كل نقاط الترميز هي رمز النار 0x1F525 فقط — لا نصفيات
        assertTrue(cutEmoji.codePoints().allMatch { cp -> cp == 0x1F525 })
    }

    @Test
    fun `normalize returns null for blank input`() {
        assertNull(TagsEngine.normalize(""))
        assertNull(TagsEngine.normalize("   "))
        assertNull(TagsEngine.normalize("\u200B\uFEFF"))
    }

    @Test
    fun `canonical is case-insensitive for latin and stable for arabic`() {
        assertEquals(TagsEngine.canonical("Work"), TagsEngine.canonical("work"))
        assertEquals(TagsEngine.canonical("العمل"), TagsEngine.canonical("العمل"))
    }

    // ═══════════ الإنشاء ═══════════

    @Test
    fun `create assigns sequential ids and deterministic default color`() {
        val m = TagsEngine.create(TagsEngine.TagsState(), "العمل", now = NOW)
        assertTrue(m.ok)
        val def = m.def!!
        assertEquals(1L, def.id)
        assertEquals("العمل", def.name)
        assertEquals(NOW, def.createdAt)
        assertEquals(TagPalette.colorFor(TagsEngine.canonical("العمل")), def.colorIndex)
    }

    @Test
    fun `create empty name fails with EMPTY`() {
        val m = TagsEngine.create(TagsEngine.TagsState(), "   ", now = NOW)
        assertEquals(TagsEngine.TagError.EMPTY, m.error)
    }

    @Test
    fun `create duplicate fails case-insensitively`() {
        val s = stateOf("work")
        val m = TagsEngine.create(s, "WORK", now = NOW)
        assertEquals(TagsEngine.TagError.DUPLICATE, m.error)
        // الحالة الأصلية لم تُمس
        assertEquals(1, m.state.defs.size)
    }

    @Test
    fun `create accepts explicit valid color and rejects out of range`() {
        val ok = TagsEngine.create(TagsEngine.TagsState(), "x", colorIndex = 7, now = NOW)
        assertEquals(7, ok.def!!.colorIndex)
        val bad = TagsEngine.create(TagsEngine.TagsState(), "y", colorIndex = 10, now = NOW)
        assertEquals(TagsEngine.TagError.BAD_COLOR, bad.error)
        val neg = TagsEngine.create(TagsEngine.TagsState(), "y", colorIndex = -1, now = NOW)
        assertEquals(TagsEngine.TagError.BAD_COLOR, neg.error)
    }

    @Test
    fun `create with arabic and emoji names works`() {
        val s = stateOf("مشروع التخرج 🎓", "فاتورة-2026")
        assertEquals(2, s.defs.size)
        assertTrue(s.defs.any { it.name == "مشروع التخرج 🎓" })
    }

    // ═══════════ التسمية والتلوين ═══════════

    @Test
    fun `rename updates name and rejects duplicates of other tags`() {
        val s = stateOf("work", "home")
        val ok = TagsEngine.rename(s, s.idOf("work"), "office")
        assertTrue(ok.ok)
        assertEquals("office", ok.def!!.name)

        val dup = TagsEngine.rename(s, s.idOf("work"), "home")
        assertEquals(TagsEngine.TagError.DUPLICATE, dup.error)

        val missing = TagsEngine.rename(s, 999L, "x")
        assertEquals(TagsEngine.TagError.NOT_FOUND, missing.error)

        val empty = TagsEngine.rename(s, s.idOf("work"), "  ")
        assertEquals(TagsEngine.TagError.EMPTY, empty.error)
    }

    @Test
    fun `rename keeps the tag color`() {
        val s = stateOf("work")
        val color = s.defs[0].colorIndex
        val m = TagsEngine.rename(s, s.idOf("work"), "labor")
        assertEquals(color, m.def!!.colorIndex)
    }

    @Test
    fun `setColor validates range and target`() {
        val s = stateOf("work")
        val ok = TagsEngine.setColor(s, s.idOf("work"), 3)
        assertTrue(ok.ok)
        assertEquals(3, ok.def!!.colorIndex)

        assertEquals(TagsEngine.TagError.BAD_COLOR, TagsEngine.setColor(s, s.idOf("work"), 10).error)
        assertEquals(TagsEngine.TagError.NOT_FOUND, TagsEngine.setColor(s, 42L, 3).error)
    }

    // ═══════════ الحذف والإسناد ═══════════

    @Test
    fun `remove cascades links`() {
        var s = stateOf("work")
        val id = s.idOf("work")
        s = TagsEngine.assign(s, id, listOf("/a.pdf", "/b.pdf")).state
        val m = TagsEngine.remove(s, id)
        assertTrue(m.ok)
        assertTrue(m.state.defs.isEmpty())
        assertTrue(m.state.links.isEmpty())
        assertEquals(TagsEngine.TagError.NOT_FOUND, TagsEngine.remove(s, 77L).error)
    }

    @Test
    fun `assign is idempotent and ignores blank paths`() {
        val s0 = stateOf("work")
        val id = s0.idOf("work")
        val s1 = TagsEngine.assign(s0, id, listOf("/a.pdf", "/a.pdf", "  ")).state
        assertEquals(setOf("/a.pdf"), s1.links[id])
        val s2 = TagsEngine.assign(s1, id, listOf("/a.pdf", "/b.txt")).state
        assertEquals(setOf("/a.pdf", "/b.txt"), s2.links[id])
    }

    @Test
    fun `assign unknown tag fails and unassign removes one path`() {
        var s = stateOf("work")
        val id = s.idOf("work")
        assertEquals(TagsEngine.TagError.NOT_FOUND, TagsEngine.assign(s, 31L, listOf("/x")).error)

        s = TagsEngine.assign(s, id, listOf("/a", "/b")).state
        val m = TagsEngine.unassign(s, id, "/a")
        assertEquals(setOf("/b"), m.state.links[id])
        // إزالة غير موجودة بلا أثر
        assertEquals(setOf("/b"), TagsEngine.unassign(m.state, id, "/zzz").state.links[id])
    }

    @Test
    fun `untagPath removes the path from every tag`() {
        var s = stateOf("work", "urgent")
        s = TagsEngine.assign(s, s.idOf("work"), listOf("/keep", "/gone")).state
        s = TagsEngine.assign(s, s.idOf("urgent"), listOf("/gone", "/x")).state
        val m = TagsEngine.untagPath(s, "/gone")
        assertFalse(m.state.links.values.any { "/gone" in it })
        assertTrue("/keep" in m.state.links[s.idOf("work")]!!)
        assertTrue("/x" in m.state.links[s.idOf("urgent")]!!)
    }

    // ═══════════ القراءات ═══════════

    @Test
    fun `tagPaths returns sorted paths`() {
        var s = stateOf("work")
        s = TagsEngine.assign(s, s.idOf("work"), listOf("/z.txt", "/a.txt", "/m.txt")).state
        assertEquals(listOf("/a.txt", "/m.txt", "/z.txt"), TagsEngine.tagPaths(s, s.idOf("work")))
    }

    @Test
    fun `tagsForPath returns name-sorted defs`() {
        var s = stateOf("bbb", "aaa", "ccc")
        s = TagsEngine.assign(s, s.idOf("bbb"), listOf("/f")).state
        s = TagsEngine.assign(s, s.idOf("ccc"), listOf("/f")).state
        s = TagsEngine.assign(s, s.idOf("aaa"), listOf("/other")).state
        assertEquals(listOf("bbb", "ccc"), TagsEngine.tagsForPath(s, "/f").map { it.name })
    }

    @Test
    fun `counts reflect link sizes with zero for unassigned`() {
        var s = stateOf("a", "b")
        s = TagsEngine.assign(s, s.idOf("a"), listOf("/1", "/2", "/3")).state
        val counts = TagsEngine.counts(s)
        assertEquals(3, counts[s.idOf("a")])
        assertEquals(0, counts[s.idOf("b")])
    }

    @Test
    fun `filter ANY matches union and ALL matches intersection`() {
        var s = stateOf("red", "blue", "green")
        s = TagsEngine.assign(s, s.idOf("red"), listOf("/f1", "/f2")).state
        s = TagsEngine.assign(s, s.idOf("blue"), listOf("/f2", "/f3")).state
        s = TagsEngine.assign(s, s.idOf("green"), listOf("/f4")).state

        val paths = listOf("/f1", "/f2", "/f3", "/f4", "/f5")
        val any = TagsEngine.filterPaths(
            s, paths,
            listOf(s.idOf("red"), s.idOf("blue")),
            TagsEngine.FilterMode.ANY,
        )
        assertEquals(listOf("/f1", "/f2", "/f3"), any)

        val all = TagsEngine.filterPaths(
            s, paths,
            listOf(s.idOf("red"), s.idOf("blue")),
            TagsEngine.FilterMode.ALL,
        )
        assertEquals(listOf("/f2"), all)

        // وسوم غير موجودة في الحالة = مجموعات فارغة → فلترة تشدّد النتيجة
        val unknown = TagsEngine.filterPaths(s, paths, listOf(99L))
        assertTrue(unknown.isEmpty())
    }

    @Test
    fun `filter with empty tag ids is a no-op`() {
        val s = stateOf("a")
        val paths = listOf("/1", "/2")
        assertEquals(paths, TagsEngine.filterPaths(s, paths, emptyList()))
    }

    @Test
    fun `sortedDefs orders by canonical name deterministically`() {
        val s = stateOf("banana", "Apple", "تفاح")
        assertEquals(
            listOf("Apple", "banana", "تفاح"),
            TagsEngine.sortedDefs(s).map { it.name },
        )
    }

    @Test
    fun `full lifecycle create-assign-rename-filter-untag-remove`() {
        var s = TagsEngine.TagsState()
        s = TagsEngine.create(s, "مشاريع", now = NOW).state
        val id = s.idOf("مشاريع")
        s = TagsEngine.assign(s, id, listOf("/docs/خطة.pdf", "/docs/تقرير.docx")).state
        s = TagsEngine.rename(s, id, "شغل 📁").state

        assertEquals(2, TagsEngine.counts(s)[id])
        assertEquals(listOf("/docs/تقرير.docx", "/docs/خطة.pdf").sorted(),
            TagsEngine.tagPaths(s, id))

        s = TagsEngine.untagPath(s, "/docs/خطة.pdf").state
        assertEquals(1, TagsEngine.counts(s)[id])

        s = TagsEngine.remove(s, id).state
        assertTrue(s.defs.isEmpty() && s.links.isEmpty())
        assertNotNull(s)
    }
}

/** ═══════════ v0.33 — اختبارات حدودية إضافية ═══════════ */
class TagsEngineEdgeTest {

    private val NOW = 1_700_000_000_000L

    private fun stateOf(vararg names: String): TagsEngine.TagsState {
        var s = TagsEngine.TagsState()
        names.forEach { s = TagsEngine.create(s, it, now = NOW).state }
        return s
    }

    private fun TagsEngine.TagsState.idOf(name: String): Long =
        defs.first { it.name == name }.id

    @Test
    fun `normalize keeps Persian ZWNJ intact`() {
        // نیم‌فاصله — ZWNJ جزء من الكلمات الفارسية ولا يُحذف
        val persian = "می\u200Cخواهم"
        assertEquals(persian, TagsEngine.normalize(persian))
    }

    @Test
    fun `create with differing inner spacing is a duplicate`() {
        val s = stateOf("work x")
        assertEquals(TagsEngine.TagError.DUPLICATE, TagsEngine.create(s, "work  x", now = NOW).error)
    }

    @Test
    fun `filter single tag returns only its paths`() {
        var s = stateOf("t1")
        s = TagsEngine.assign(s, s.idOf("t1"), listOf("/a", "/b")).state
        val out = TagsEngine.filterPaths(s, listOf("/a", "/b", "/c"), listOf(s.idOf("t1")))
        assertEquals(listOf("/a", "/b"), out)
    }

    @Test
    fun `counts on empty state is empty map`() {
        assertTrue(TagsEngine.counts(TagsEngine.TagsState()).isEmpty())
        assertTrue(TagsEngine.tagPaths(TagsEngine.TagsState(), 1L).isEmpty())
        assertTrue(TagsEngine.tagsForPath(TagsEngine.TagsState(), "/x").isEmpty())
    }

    @Test
    fun `rename reorders sortedDefs deterministically`() {
        val s = stateOf("aaa", "bbb")
        val renamed = TagsEngine.rename(s, s.idOf("aaa"), "zzz").state
        assertEquals(listOf("bbb", "zzz"), TagsEngine.sortedDefs(renamed).map { it.name })
    }

    @Test
    fun `colorFor handles long multibyte strings in range`() {
        val long = "🚀".repeat(200) + "عربي".repeat(50)
        val idx = TagPalette.colorFor(long)
        assertTrue(idx in 0 until TagPalette.COUNT)
        assertEquals(idx, TagPalette.colorFor(long))
    }
}
