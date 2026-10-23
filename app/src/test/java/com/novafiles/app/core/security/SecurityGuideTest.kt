package com.novafiles.app.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * اختبارات v0.38 — الدليل الأمني — JVM خالصة.
 *
 * أهم اختبار هنا «اختبار السلاسل الحقيقية»: كل مفتاح عنوان/شرح في
 * الأقسام الثمانية يجب أن يوجد فعلاً في values/strings.xml الأساس
 * (العربية) — فلا يمكن أن يُبنى التطبيق بدليل ينقصه نص.
 */
class SecurityGuideTest {

    // ---------- البنية ----------

    @Test
    fun `structure is valid by construction`() {
        assertEquals(emptyList<String>(), SecurityGuide.validate())
    }

    @Test
    fun `eight sections with strict 1 to 8 ordering and unique ids`() {
        assertEquals(8, SecurityGuide.SECTIONS.size)
        assertEquals((1..8).toList(), SecurityGuide.SECTIONS.map { it.order })
        assertEquals(8, SecurityGuide.SECTIONS.map { it.id }.distinct().size)
    }

    @Test
    fun `severity ladder has essentials first`() {
        val first = SecurityGuide.SECTIONS.first()
        assertEquals(GuideSeverity.ESSENTIAL, first.severity)
        // الأقسام الجوهرية الثلاثة تسبق المستحسنة والمتقدمة
        val ranks = SecurityGuide.SECTIONS.map { it.severity.ordinal }
        assertEquals(ranks, ranks.sorted())
    }

    // ---------- تقدّم القراءة ----------

    @Test
    fun `mark read and unread only accept known ids`() {
        val p0 = GuideProgress()
        val p1 = SecurityGuide.markRead(p0, "vault_pin")!!
        assertEquals(setOf("vault_pin"), p1.readIds)
        assertNull(SecurityGuide.markRead(p0, "unknown_id"))
        val p2 = SecurityGuide.markUnread(p1, "vault_pin")!!
        assertTrue(p2.readIds.isEmpty())
        assertNull(SecurityGuide.markUnread(p0, "nope"))
    }

    @Test
    fun `percent and completion follow read count`() {
        val all = SecurityGuide.SECTIONS.map { it.id }.toSet()
        val half = GuideProgress(all.take(4).toSet())
        assertEquals(50, SecurityGuide.percent(half))
        assertFalse(SecurityGuide.isComplete(half))
        assertEquals(100, SecurityGuide.percent(GuideProgress(all)))
        assertTrue(SecurityGuide.isComplete(GuideProgress(all)))
        assertEquals(0, SecurityGuide.percent(GuideProgress()))
    }

    @Test
    fun `next unread follows display order and nulls at completion`() {
        val p = SecurityGuide.markRead(GuideProgress(), "vault_pin")!!
        assertEquals("encryption", SecurityGuide.nextUnread(p)?.id)
        val all = GuideProgress(SecurityGuide.SECTIONS.map { it.id }.toSet())
        assertNull(SecurityGuide.nextUnread(all))
    }

    // ---------- ترميز CSV ----------

    @Test
    fun `csv serialize is deterministic in display order`() {
        // القراءة بترتيب معكوس — الإخراج بترتيب العرض حتماً
        var p = GuideProgress()
        for (id in SecurityGuide.SECTIONS.map { it.id }.reversed()) {
            p = SecurityGuide.markRead(p, id)!!
        }
        assertEquals(
            SecurityGuide.SECTIONS.joinToString(",") { it.id },
            SecurityGuide.serialize(p),
        )
    }

    @Test
    fun `csv round trip and tolerant parse`() {
        val p = SecurityGuide.markRead(SecurityGuide.markRead(GuideProgress(), "recovery")!!, "updates")!!
        assertEquals(p, SecurityGuide.parse(SecurityGuide.serialize(p)))

        // تالف/فارغ/مجهول — تسامح كامل
        assertEquals(GuideProgress(), SecurityGuide.parse(""))
        assertEquals(GuideProgress(), SecurityGuide.parse("   "))
        assertEquals(GuideProgress(), SecurityGuide.parse("ghost,nonsense"))
        // مختلط: معروف + مجهول + فراغات
        val mixed = SecurityGuide.parse(" vault_pin , ghost ,encryption")
        assertEquals(setOf("vault_pin", "encryption"), mixed.readIds)
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
    fun `every guide key exists in the real base strings`() {
        val xml = loadBaseStringsXml()
        val missing = mutableListOf<String>()
        for (s in SecurityGuide.SECTIONS) {
            for (key in listOf(s.titleKey, s.bodyKey)) {
                if (!xml.contains("""name="$key"""")) missing += key
            }
        }
        assertTrue(
            "مفاتيح الدليل الأمني ناقصة في values/strings.xml: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun `guide shell keys exist in the real base strings`() {
        val xml = loadBaseStringsXml()
        val required = listOf(
            "guide_title",
            "guide_footer_hint",
            "guide_progress",
            "guide_complete",
            "severity_essential",
            "severity_recommended",
            "severity_advanced",
            "settings_security_guide",
            "settings_security_guide_hint",
        )
        val missing = required.filter { !xml.contains("""name="$it"""") }
        assertTrue("مفاتيح غلاف الدليل ناقصة: $missing", missing.isEmpty())
    }
}
