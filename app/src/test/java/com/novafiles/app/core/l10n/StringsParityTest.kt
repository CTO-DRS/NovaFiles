package com.novafiles.app.core.l10n

import com.novafiles.app.core.l10n.LanguageCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * اختبارات v0.37 — محرك تكافؤ السلاسل — JVM خالصة.
 *
 * أهم اختبار هنا هو «اختبار المستودع الحقيقي»: يقرأ ملفات strings.xml
 * العشر الفعلية من res/ ويفحص التكافؤ الكامل 605×10 — هذا هو بند
 * «بنية العشر لغات» من الخارطة مُحمياً بالاختبار.
 */
class StringsParityTest {

    // ---------- أدوات: إيجاد مجلد الموارد الحقيقي ----------

    private fun resolveResDir(): File {
        val rel = listOf("src/main/res", "app/src/main/res")
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val d = dir ?: return@repeat
            for (r in rel) {
                val f = File(d, r)
                if (f.isDirectory) return f
            }
            dir = d.parentFile
        }
        error("مجلد res غير موجود من ${System.getProperty("user.dir")}")
    }

    /** محتوى strings.xml لكل لغة من المجلد الحقيقي */
    private fun loadRealLocaleFiles(): Map<String, String> {
        val res = resolveResDir()
        val out = mutableMapOf<String, String>()
        for (d in res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()) {
            val f = File(d, "strings.xml")
            if (!f.exists()) continue
            val code = if (d.name == "values") LanguageCatalog.BASE_CODE
            else d.name.removePrefix("values-").substringBefore('-')
            out[code] = f.readText()
        }
        return out
    }

    // ---------- التحليل ----------

    @Test
    fun `parse extracts names and values`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <!-- تعليق يُتجاهل -->
                <string name="a">مرحبا</string>
                <string name="b">Hello %1${'$'}d files</string>
            </resources>
        """.trimIndent()
        val parsed = StringsParity.parse(xml)
        assertEquals(2, parsed.entries.size)
        assertEquals("مرحبا", parsed.byName.getValue("a").value)
        assertEquals(listOf("%1\$d"), StringsParity.extractPlaceholders(parsed.byName.getValue("b").value))
        assertTrue(parsed.duplicateNames.isEmpty())
    }

    @Test
    fun `parse detects duplicate names`() {
        val parsed = StringsParity.parse(
            """<resources><string name="x">1</string><string name="x">2</string></resources>""",
        )
        assertEquals(listOf("x"), parsed.duplicateNames)
        assertEquals(1, parsed.entries.size)
    }

    @Test
    fun `placeholder extraction handles positional plain literal and escaped`() {
        assertEquals(listOf("%1\$s", "%2\$d"), StringsParity.extractPlaceholders("نقل: %1\$s إلى %2\$d"))
        assertEquals(listOf("%s"), StringsParity.extractPlaceholders("plain %s form"))
        assertEquals(listOf("%d"), StringsParity.extractPlaceholders("100%% then %d"))
        assertEquals(listOf("%d"), StringsParity.extractPlaceholders("discount 50\\% and %d"))
        assertTrue(StringsParity.extractPlaceholders("no placeholders here").isEmpty())
    }

    @Test
    fun `mixed positional placeholders are flagged`() {
        assertTrue(StringsParity.hasMixedPositional("from %1\$s to %d"))
        assertFalse(StringsParity.hasMixedPositional("from %1\$s to %2\$d"))
        assertFalse(StringsParity.hasMixedPositional("from %s to %d"))
        assertFalse(StringsParity.hasMixedPositional("single %1\$s only"))
    }

    // ---------- التدقيق ----------

    @Test
    fun `audit finds missing extra and placeholder mismatch`() {
        val base = StringsParity.parse(
            """<resources>
                <string name="hello">مرحبا %1${'$'}s</string>
                <string name="bye">وداعا</string>
            </resources>""",
        )
        val other = StringsParity.parse(
            """<resources>
                <string name="hello">Hello</string>
                <string name="extra">زائد</string>
            </resources>""",
        )
        val issues = StringsParity.audit(mapOf("ar" to base, "en" to other))
        assertTrue(issues.any { it is ParityIssue.MissingKey && it.locale == "en" && it.key == "bye" })
        assertTrue(issues.any { it is ParityIssue.ExtraKey && it.locale == "en" && it.key == "extra" })
        assertTrue(issues.any { it is ParityIssue.PlaceholderMismatch && it.locale == "en" && it.key == "hello" })
    }

    @Test
    fun `audit flags empty values duplicates and mixed positional`() {
        val base = StringsParity.parse(
            """<resources>
                <string name="a">ok %1${'$'}d</string>
                <string name="b">   </string>
                <string name="c">x %1${'$'}s y %d</string>
            </resources>""",
        )
        val dup = StringsParity.parse(
            """<resources><string name="a">ok</string><string name="a">ok2</string></resources>""",
        )
        val issues = StringsParity.audit(mapOf("ar" to base, "en" to dup))
        assertTrue(issues.any { it is ParityIssue.EmptyValue && it.locale == "ar" && it.key == "b" })
        assertTrue(issues.any { it is ParityIssue.MixedPositional && it.locale == "ar" && it.key == "c" })
        assertTrue(issues.any { it is ParityIssue.DuplicateName && it.locale == "en" && it.key == "a" })
    }

    @Test
    fun `audit passes for consistent locales`() {
        val base = StringsParity.parse("""<resources><string name="a">واحد %1${'$'}d</string></resources>""")
        val en = StringsParity.parse("""<resources><string name="a">one %1${'$'}d</string></resources>""")
        val de = StringsParity.parse("""<resources><string name="a">eins %1${'$'}d</string></resources>""")
        val issues = StringsParity.audit(mapOf("ar" to base, "en" to en, "de" to de))
        assertTrue("تكافؤ نظيف بلا مشاكل: $issues", issues.isEmpty())
        assertEquals("locales=3 keys=1 issues=0", StringsParity.summary(mapOf("ar" to base, "en" to en, "de" to de), issues))
    }

    // ---------- اختبار المستودع الحقيقي: العشر لغات × 605 ----------

    @Test
    fun `repo has string resources for all ten launch languages`() {
        val files = loadRealLocaleFiles()
        assertEquals(
            "لغات الموارد الفعلية: ${files.keys}",
            LanguageCatalog.codes(),
            files.keys,
        )
    }

    @Test
    fun `repo strings are fully parity-matched across ten languages`() {
        val parsed = loadRealLocaleFiles().mapValues { (_, xml) -> StringsParity.parse(xml) }
        val issues = StringsParity.audit(parsed)
        assertTrue(
            "انتهاكات التكافؤ (${issues.size}): ${issues.take(10)}",
            issues.isEmpty(),
        )
        // أرضية السلاسل: 1026 سلسلة في كل لغة من العشر (بعد دليل المستخدم v0.40)
        parsed.forEach { (locale, p) ->
            assertEquals("عدد سلاسل $locale", 1026, p.entries.size)
        }
    }
}
