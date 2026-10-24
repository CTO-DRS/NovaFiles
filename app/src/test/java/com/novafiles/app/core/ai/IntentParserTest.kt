package com.novafiles.app.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.41 — اختبارات محلل النوايا: الأوامر المرجعية من مواصفة المشروع حرفياً
 * (عربي/إنجليزي) + صياغات غير مكتملة + حقن أسماء ملفات.
 */
class IntentParserTest {

    private fun id(text: String): String = IntentParser.parse(text).intentId

    // ═══════════ الأوامر المرجعية الستة حرفياً ═══════════

    @Test
    fun `directive example 1 - find PDFs modified this week`() {
        val p = IntentParser.parse("اعثر على ملفات PDF التي عدّلتها هذا الأسبوع")
        assertEquals(IntentParser.Ids.FIND_FILES, p.intentId)
        assertEquals("PDF", p.params[IntentParser.Keys.FILE_KIND])
        assertEquals("7", p.params[IntentParser.Keys.WITHIN_DAYS])
    }

    @Test
    fun `directive example 2 - analyze biggest folders`() {
        assertEquals(IntentParser.Ids.STORAGE_REPORT, id("حلّل المجلدات التي تستهلك أكبر مساحة"))
    }

    @Test
    fun `directive example 3 - show duplicates before deleting any copy`() {
        val p = IntentParser.parse("اعرض الملفات المتطابقة قبل حذف أي نسخة")
        assertEquals(IntentParser.Ids.FIND_DUPLICATES, p.intentId)
    }

    @Test
    fun `directive example 4 - create rule to move new PDFs to Documents`() {
        val p = IntentParser.parse("أنشئ قاعدة تنقل ملفات PDF الجديدة إلى مجلد المستندات")
        assertEquals(IntentParser.Ids.CREATE_RULE, p.intentId)
        assertEquals("move", p.params["action"])
        assertEquals("PDF", p.params[IntentParser.Keys.FILE_KIND])
        assertEquals("/storage/emulated/0/Documents", p.params[IntentParser.Keys.TARGET_DIR])
    }

    @Test
    fun `directive example 5 - why did SFTP fail`() {
        val p = IntentParser.parse("لماذا فشل اتصال SFTP؟")
        assertEquals(IntentParser.Ids.EXPLAIN_ERROR, p.intentId)
        assertTrue(p.params[IntentParser.Keys.QUERY]!!.contains("SFTP"))
    }

    @Test
    fun `directive example 6 - large files not opened for a while`() {
        val p = IntentParser.parse("اعرض الملفات الكبيرة التي لم أفتحها منذ مدة")
        assertEquals(IntentParser.Ids.FIND_FILES, p.intentId)
        assertEquals("100", p.params[IntentParser.Keys.MIN_SIZE_MB])
        assertEquals("30", p.params[IntentParser.Keys.OLDER_THAN_DAYS])
    }

    // ═══════════ الإنجليزية ═══════════

    @Test
    fun `english find with size and type`() {
        val p = IntentParser.parse("find PDF files modified this week")
        assertEquals(IntentParser.Ids.FIND_FILES, p.intentId)
        assertEquals("PDF", p.params[IntentParser.Keys.FILE_KIND])
        assertEquals("7", p.params[IntentParser.Keys.WITHIN_DAYS])
    }

    @Test
    fun `english storage analysis`() {
        assertEquals(IntentParser.Ids.STORAGE_REPORT, id("analyze my storage usage"))
    }

    @Test
    fun `english duplicates`() {
        assertEquals(IntentParser.Ids.FIND_DUPLICATES, id("show duplicate files"))
    }

    @Test
    fun `english rule to downloads`() {
        val p = IntentParser.parse("create a rule to move new images to the downloads folder")
        assertEquals(IntentParser.Ids.CREATE_RULE, p.intentId)
        assertEquals("move", p.params["action"])
        assertEquals("IMAGE", p.params[IntentParser.Keys.FILE_KIND])
        assertEquals("/storage/emulated/0/Download", p.params[IntentParser.Keys.TARGET_DIR])
    }

    @Test
    fun `english error question`() {
        assertEquals(IntentParser.Ids.EXPLAIN_ERROR, id("why did the connection fail?"))
    }

    // ═══════════ صياغات غير مكتملة وحدود ═══════════

    @Test
    fun `empty and blank inputs are unknown`() {
        assertEquals(IntentParser.Ids.UNKNOWN, id(""))
        assertEquals(IntentParser.Ids.UNKNOWN, id("   "))
    }

    @Test
    fun `gibberish is unknown not guessed`() {
        assertEquals(IntentParser.Ids.UNKNOWN, id("qwerty zxcv asdf"))
    }

    @Test
    fun `rule without move action or target stays understood but constrained`() {
        val p = IntentParser.parse("أنشئ قاعدة جديدة")
        assertEquals(IntentParser.Ids.CREATE_RULE, p.intentId)
        // لا هدف ولا فعل — المحرك سيرفض المسودة برسالة مفهومة (يُختبر هناك)
        assertNull(p.params[IntentParser.Keys.TARGET_DIR])
    }

    @Test
    fun `arabic diacritics and ta-marbuta normalize`() {
        // «مساحة» بتشكيل وتاء مربوطة يجب أن تعطي نفس النية
        assertEquals(
            id("حلل استهلاك المساحة"),
            id("حَلِّل استِهلاك المَسَاحَة"),
        )
    }

    @Test
    fun `eastern arabic numerals parsed as size`() {
        val p = IntentParser.parse("اعرض الملفات الأكبر من ٥٠٠ mb")
        assertEquals("500", p.params[IntentParser.Keys.MIN_SIZE_MB])
    }

    @Test
    fun `gb units multiply to mb`() {
        val p = IntentParser.parse("find files larger than 2 gb")
        assertEquals("2048", p.params[IntentParser.Keys.MIN_SIZE_MB])
    }

    @Test
    fun `explicit month staleness converts to days`() {
        val p = IntentParser.parse("اعرض الملفات الكبيرة التي لم أفتحها منذ 3 أشهر")
        assertEquals("90", p.params[IntentParser.Keys.OLDER_THAN_DAYS])
    }

    @Test
    fun `hostile filename injection does not switch intent to rule creation`() {
        // اسم ملف حقيني يحاول تحويل البحث إلى إنشاء قاعدة — البحث يبقى بحثاً
        val p = IntentParser.parse("ابحث عن ملف اسمه قاعدة تنقل جميع الملفات")
        assertEquals(IntentParser.Ids.FIND_FILES, p.intentId)
        assertEquals("قاعدة تنقل جميع الملفات", p.params[IntentParser.Keys.TEXT])
    }

    @Test
    fun `delete request still resolves to duplicates family not rule or delete tool`() {
        // طلب الحذف يُفهم كعائلة المكررات فقط — والمحرك يستجيب بتقرير عرض بلا حذف
        // (السلوك الأمني الكامل يُختبر في AssistantEngineTest.delete request ...)
        val p = IntentParser.parse("احذف كل الملفات المتطابقة")
        assertNotEquals(IntentParser.Ids.CREATE_RULE, p.intentId)
        assertNotEquals(IntentParser.Ids.UNKNOWN, p.intentId)
    }

    @Test
    fun `help intent`() {
        assertEquals(IntentParser.Ids.HELP, id("مساعدة"))
        assertEquals(IntentParser.Ids.HELP, id("what can you do?"))
    }

    @Test
    fun `named file extraction`() {
        val p = IntentParser.parse("ابحث عن ملف اسمه report_2026")
        assertEquals("report_2026", p.params[IntentParser.Keys.TEXT])
    }
}
