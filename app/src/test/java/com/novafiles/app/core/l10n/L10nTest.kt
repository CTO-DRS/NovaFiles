package com.novafiles.app.core.l10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات v0.37 — كتالوج اللغات العشر والقاموس الموحد — JVM خالصة.
 */
class L10nTest {

    // ---------- كتالوج اللغات ----------

    @Test
    fun `catalog has exactly the ten launch languages`() {
        assertEquals(10, LanguageCatalog.ALL.size)
        assertEquals(
            setOf("ar", "en", "fr", "es", "de", "tr", "id", "ur", "fa", "ru"),
            LanguageCatalog.codes(),
        )
    }

    @Test
    fun `codes are unique and valid bcp47 primaries`() {
        val codes = LanguageCatalog.ALL.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        codes.forEach { code ->
            assertTrue("وسم غير صالح: $code", Regex("""^[a-z]{2}$""").matches(code))
        }
    }

    @Test
    fun `rtl languages are exactly arabic urdu persian`() {
        assertEquals(setOf("ar", "ur", "fa"), LanguageCatalog.rtlCodes())
    }

    @Test
    fun `base is arabic and secondary is english`() {
        assertEquals("ar", LanguageCatalog.BASE_CODE)
        assertEquals("en", LanguageCatalog.SECONDARY_CODE)
        assertTrue(LanguageCatalog.ALL.first().code == "ar")
        assertTrue(LanguageCatalog.ALL[1].code == "en")
    }

    @Test
    fun `script assignment matches locale families`() {
        assertEquals(NovaLocale.Script.ARABIC, LanguageCatalog.ARABIC.script)
        assertEquals(NovaLocale.Script.ARABIC, LanguageCatalog.URDU.script)
        assertEquals(NovaLocale.Script.ARABIC, LanguageCatalog.PERSIAN.script)
        assertEquals(NovaLocale.Script.CYRILLIC, LanguageCatalog.RUSSIAN.script)
        listOf(
            LanguageCatalog.ENGLISH, LanguageCatalog.FRENCH, LanguageCatalog.SPANISH,
            LanguageCatalog.GERMAN, LanguageCatalog.TURKISH, LanguageCatalog.INDONESIAN,
        ).forEach { assertEquals(NovaLocale.Script.LATIN, it.script) }
    }

    @Test
    fun `byCode resolves region tags and case`() {
        assertEquals("ar", LanguageCatalog.byCode("ar-EG")?.code)
        assertEquals("en", LanguageCatalog.byCode("EN-us")?.code)
        assertNull(LanguageCatalog.byCode("pt-BR"))
        assertNull(LanguageCatalog.byCode(null))
        assertNotNull(LanguageCatalog.byCode("  fa "))
    }

    @Test
    fun `native names are non blank and in correct script`() {
        for (locale in LanguageCatalog.ALL) {
            assertTrue("${locale.code} اسماً أصلياً", locale.nativeName.isNotBlank())
            when (locale.script) {
                NovaLocale.Script.ARABIC -> assertTrue(
                    "${locale.code} بالحروف العربية",
                    locale.nativeName.any { it.code in 0x0600..0x06FF },
                )
                NovaLocale.Script.CYRILLIC -> assertTrue(
                    "${locale.code} بالسيريلية",
                    locale.nativeName.any { it.code in 0x0400..0x04FF },
                )
                NovaLocale.Script.LATIN -> assertTrue(
                    "${locale.code} باللاتينية",
                    locale.nativeName.any { it in 'A'..'Z' || it in 'a'..'z' },
                )
            }
        }
    }

    // ---------- القاموس الموحد ----------

    @Test
    fun `glossary has thirty core terms`() {
        assertEquals(30, Glossary.TERMS.size)
        assertEquals(Glossary.TERMS.size, Glossary.TERMS.map { it.key }.toSet().size)
    }

    @Test
    fun `glossary audit is clean for the ten languages`() {
        val issues = Glossary.audit()
        assertTrue(
            "قاموس غير سليم: ${issues.take(8)}",
            issues.isEmpty(),
        )
    }

    @Test
    fun `glossary term lookup resolves region tags`() {
        assertEquals("الأرشيف", Glossary.term("archive", "ar"))
        assertEquals("the archive", Glossary.term("archive", "en-US"))
        assertEquals("архив", Glossary.term("archive", "ru"))
        assertNull(Glossary.term("nonexistent", "ar"))
        assertNull(Glossary.term("archive", "xx"))
    }

    @Test
    fun `glossary audit is clean for a subset of complete locales`() {
        val issues = Glossary.audit(setOf("ar", "en"))
        assertTrue("اللغتان الأساسيتان مكتملتان بلا مشاكل: $issues", issues.isEmpty())
    }

    @Test
    fun `glossary audit flags unknown locale as missing terms only`() {
        val issues = Glossary.audit(setOf("zz"))
        // لغة خارج الكتالوج: كل مصطلح مفقود — وفحص النظام لا يعمل على قيمة غير موجودة (قصور مقصود)
        assertEquals(Glossary.TERMS.size, issues.count { it is GlossaryIssue.MissingTerm })
        assertEquals(0, issues.count { it is GlossaryIssue.WrongScript })
    }

    @Test
    fun `termsFor returns all terms for a locale`() {
        val de = Glossary.termsFor("de")
        assertEquals(Glossary.TERMS.size, de.size)
        assertEquals("die Prüfsumme", de["checksum"])
        val fa = Glossary.termsFor("fa")
        assertEquals("گنجینه", fa["vault"])
    }
}
