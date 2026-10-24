package com.novafiles.app.core.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * اختبارات v0.40 — دليل المستخدم المدمج — JVM خالصة.
 *
 * أهم اختبار هنا «اختبار السلاسل الحقيقية»: كل مفتاح عنوان/شرح في
 * الفصول العشرة وكل مفتاح غلاف يجب أن يوجد فعلاً في values/strings.xml
 * الأساس (العربية) — فلا يمكن أن يُبنى التطبيق بدليل ينقصه نص.
 */
class UserGuideEngineTest {

    // ---------- البنية ----------

    @Test
    fun `structure is valid by construction`() {
        assertEquals(emptyList<String>(), UserGuide.validate())
    }

    @Test
    fun `ten chapters with strict 1 to 10 ordering and unique ids`() {
        assertEquals(10, UserGuide.CHAPTERS.size)
        assertEquals((1..10).toList(), UserGuide.CHAPTERS.map { it.order })
        assertEquals(10, UserGuide.CHAPTERS.map { it.id }.distinct().size)
    }

    @Test
    fun `every category has at least one chapter with expected counts`() {
        assertEquals(1, UserGuide.chaptersIn(GuideCategory.BASICS).size)
        assertEquals(2, UserGuide.chaptersIn(GuideCategory.FILES).size)
        assertEquals(1, UserGuide.chaptersIn(GuideCategory.NETWORK).size)
        assertEquals(1, UserGuide.chaptersIn(GuideCategory.CLOUD).size)
        assertEquals(1, UserGuide.chaptersIn(GuideCategory.SECURITY).size)
        assertEquals(2, UserGuide.chaptersIn(GuideCategory.SMART).size)
        assertEquals(2, UserGuide.chaptersIn(GuideCategory.TOOLS).size)
        assertEquals(UserGuide.CHAPTERS.size, GuideCategory.entries.sumOf { UserGuide.chaptersIn(it).size })
    }

    @Test
    fun `title and body keys are distinct and consistently prefixed`() {
        UserGuide.CHAPTERS.forEach { ch ->
            assertTrue("بادئة مفتاح العنوان في ${ch.id}", ch.titleKey.startsWith("ug_c_"))
            assertTrue("بادئة مفتاح الشرح في ${ch.id}", ch.bodyKey.startsWith("ug_c_"))
            assertTrue("مفتاحا العنوان والشرح متطابقان في ${ch.id}", ch.titleKey != ch.bodyKey)
            assertTrue("مفتاح العنوان يجب أن ينتهي بـ t في ${ch.id}", ch.titleKey.endsWith("_t"))
            assertTrue("مفتاح الشرح يجب أن ينتهي بـ b في ${ch.id}", ch.bodyKey.endsWith("_b"))
        }
    }

    @Test
    fun `keywords are meaningful bilingual and unique across catalog`() {
        UserGuide.CHAPTERS.forEach { ch ->
            assertTrue("الفصل ${ch.id} بكلمات بحث أقل من اثنتين", ch.keywords.size >= 2)
            assertTrue("الفصل ${ch.id} بلا كلمة عربية", ch.keywords.any { it.any { c -> c in '\u0600'..'\u06FF' } })
            assertTrue("الفصل ${ch.id} بلا كلمة لاتينية", ch.keywords.any { it.any { c -> c in 'a'..'z' || c in 'A'..'Z' } })
        }
        val all = UserGuide.CHAPTERS.flatMap { it.keywords }
        assertEquals("كلمات بحث مكررة عبر الفصول", all.size, all.toSet().size)
    }

    @Test
    fun `byId resolves known and rejects unknown`() {
        assertEquals("basics", UserGuide.byId("basics")?.id)
        assertNull(UserGuide.byId("ghost"))
        assertNull(UserGuide.byId(""))
    }

    // ---------- البحث ----------

    @Test
    fun `blank search returns all chapters in display order`() {
        assertEquals(UserGuide.CHAPTERS, UserGuide.search(""))
        assertEquals(UserGuide.CHAPTERS, UserGuide.search("   "))
    }

    @Test
    fun `search is case insensitive for latin queries`() {
        assertEquals(listOf("archives"), UserGuide.search("ZIP").map { it.id })
        assertEquals(listOf("network"), UserGuide.search("WebDav").map { it.id })
        assertEquals(listOf("vault"), UserGuide.search("ENCRYPTION").map { it.id })
    }

    @Test
    fun `search normalizes arabic hamza taa and diacritics`() {
        // الهمزات على الألف تُطبع إلى ا، والتاء المربوطة إلى ه، والتشكيل يُزال
        assertEquals(listOf("vault"), UserGuide.search("اَلتَّشْفِير").map { it.id })
        assertEquals(listOf("basics"), UserGuide.search("تخزين").map { it.id })
        assertEquals(listOf("basics"), UserGuide.search("إذن").map { it.id })
        assertEquals(listOf("smart"), UserGuide.search("مكررات").map { it.id })
    }

    @Test
    fun `search matches multiple chapters in display order`() {
        // «نقل» كلمة بحث في settings_transfer واحتواء في automation («نقل تلقائي»)
        val ids = UserGuide.search("نقل").map { it.id }
        assertTrue(ids.containsAll(listOf("automation", "settings_transfer")))
        assertEquals(ids, ids.sortedBy { UserGuide.byId(it)?.order })
        // «ضغط» في archives فقط، و«تخزين» في basics — تطابق تام يُبقي البحث حصراً
        assertEquals(listOf("archives"), UserGuide.search("ضغط").map { it.id })
    }

    @Test
    fun `search unknown query returns empty without crashing`() {
        assertTrue(UserGuide.search("zzzz").isEmpty())
        assertTrue(UserGuide.search("!!؟؟").isEmpty())
    }

    @Test
    fun `every chapter is reachable by each of its own keywords`() {
        UserGuide.CHAPTERS.forEach { ch ->
            ch.keywords.forEach { k ->
                val results = UserGuide.search(k)
                assertTrue(
                    "الفصل ${ch.id} لا يُعثر عليه بكلمة بحثه «$k» — النتائج: ${results.map { it.id }}",
                    ch in results,
                )
            }
        }
    }

    @Test
    fun `search can match by chapter id`() {
        assertEquals(listOf("vault"), UserGuide.search("vault").map { it.id })
        assertEquals(listOf("tools"), UserGuide.search("tools").map { it.id })
    }

    @Test
    fun `single character query requires exact keyword match only`() {
        // حرف واحد: لا احتواءً — تطابق تام فقط، فلا يطابق كل شيء
        assertTrue(UserGuide.search("z").isEmpty())
        // تطابق تام بكلمة قصيرة موجودة
        assertTrue(UserGuide.search("7z").map { it.id }.contains("archives"))
    }

    // ---------- تقدّم القراءة ----------

    @Test
    fun `mark read and unread only accept known ids`() {
        val p0 = UserGuideProgress()
        val p1 = UserGuide.markRead(p0, "basics")!!
        assertEquals(setOf("basics"), p1.readIds)
        assertNull(UserGuide.markRead(p0, "unknown_chapter"))
        val p2 = UserGuide.markUnread(p1, "basics")!!
        assertTrue(p2.readIds.isEmpty())
        assertNull(UserGuide.markUnread(p0, "nope"))
    }

    @Test
    fun `percent and completion follow read count`() {
        val all = UserGuide.CHAPTERS.map { it.id }.toSet()
        val half = UserGuideProgress(all.take(5).toSet())
        assertEquals(50, UserGuide.percent(half))
        assertFalse(UserGuide.isComplete(half))
        assertEquals(100, UserGuide.percent(UserGuideProgress(all)))
        assertTrue(UserGuide.isComplete(UserGuideProgress(all)))
        assertEquals(0, UserGuide.percent(UserGuideProgress()))
    }

    @Test
    fun `percent intersects known ids only`() {
        // مجموعة مبنية مباشرة بمعرفات مجهولة — النسبة تعبر المعروف فقط
        val p = UserGuideProgress(setOf("basics", "vault", "ghost", "phantom"))
        assertEquals(20, UserGuide.percent(p))
        assertFalse(UserGuide.isComplete(p))
    }

    @Test
    fun `next unread follows display order and nulls at completion`() {
        val p = UserGuide.markRead(UserGuideProgress(), "basics")!!
        assertEquals("search_tags", UserGuide.nextUnread(p)?.id)
        // تخطي منتصف الدليل — التالي بترتيب العرض لا بترتيب القراءة
        val p2 = UserGuide.markRead(p, "search_tags")!!
        assertEquals("archives", UserGuide.nextUnread(p2)?.id)
        val all = UserGuideProgress(UserGuide.CHAPTERS.map { it.id }.toSet())
        assertNull(UserGuide.nextUnread(all))
    }

    // ---------- ترميز CSV ----------

    @Test
    fun `csv serialize is deterministic in display order`() {
        // القراءة بترتيب معكوس — الإخراج بترتيب العرض حتماً
        var p = UserGuideProgress()
        for (id in UserGuide.CHAPTERS.map { it.id }.reversed()) {
            p = UserGuide.markRead(p, id)!!
        }
        assertEquals(
            UserGuide.CHAPTERS.joinToString(",") { it.id },
            UserGuide.serialize(p),
        )
    }

    @Test
    fun `csv round trip and tolerant parse`() {
        val p = UserGuide.markRead(UserGuide.markRead(UserGuideProgress(), "vault")!!, "settings_transfer")!!
        assertEquals(p, UserGuide.parse(UserGuide.serialize(p)))

        // تالف/فارغ/مجهول — تسامح كامل
        assertEquals(UserGuideProgress(), UserGuide.parse(""))
        assertEquals(UserGuideProgress(), UserGuide.parse("   "))
        assertEquals(UserGuideProgress(), UserGuide.parse("ghost,nonsense"))
        // مختلط: معروف + مجهول + فراغات
        val mixed = UserGuide.parse(" vault , ghost ,tools")
        assertEquals(setOf("vault", "tools"), mixed.readIds)
    }

    @Test
    fun `serialize empty progress is empty string`() {
        assertEquals("", UserGuide.serialize(UserGuideProgress()))
    }

    // ---------- التطبيع ----------

    @Test
    fun `normalize is deterministic and idempotent`() {
        val once = UserGuide.normalize("الخَزَنَةُ")
        assertEquals("الخزنه", once)
        assertEquals(once, UserGuide.normalize(once))
        assertEquals("خزنه", UserGuide.normalize("  خزنة "))
        assertEquals("search", UserGuide.normalize("SEARCH"))
    }

    // ═════════ اختبار السلاسل الحقيقية ═════════

    /** نص values/strings.xml الأساس (العربية) من مجلد تشغيل الاختبار */
    private fun loadBaseStringsXml(): String {
        val rel = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val d = dir ?: return@repeat
            for (r in rel) {
                val f = File(d, r)
                if (f.isFile) return f.readText()
            }
            dir = d.parentFile
        }
        error("strings.xml الأساس غير موجود من ${System.getProperty("user.dir")}")
    }

    @Test
    fun `every chapter key exists in the real base strings`() {
        val xml = loadBaseStringsXml()
        val missing = mutableListOf<String>()
        for (ch in UserGuide.CHAPTERS) {
            for (key in listOf(ch.titleKey, ch.bodyKey)) {
                if (!xml.contains("""name="$key"""")) missing += key
            }
        }
        assertTrue(
            "مفاتيح فصول الدليل ناقصة في values/strings.xml: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `user guide shell keys exist in the real base strings`() {
        val xml = loadBaseStringsXml()
        val required = listOf(
            "ug_title",
            "ug_intro",
            "ug_search_hint",
            "ug_progress",
            "ug_complete",
            "ug_no_results",
            "ug_jump_next",
            "ug_cat_basics",
            "ug_cat_files",
            "ug_cat_network",
            "ug_cat_cloud",
            "ug_cat_security",
            "ug_cat_smart",
            "ug_cat_tools",
            "settings_user_guide",
            "settings_user_guide_hint",
        )
        val missing = required.filter { !xml.contains("""name="$it"""") }
        assertTrue("مفاتيح غلاف دليل المستخدم ناقصة: $missing", missing.isEmpty())
    }
}
