package com.novafiles.app.core.buildqa

import com.novafiles.app.core.l10n.LanguageCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * اختبارات v0.37 — نضج البناء والبناء القابل لإعادة الإنتاج — JVM خالصة.
 *
 * تُختبر القواعد على مثالات اصطناعية أولاً، ثم على الملفات الحقيقية
 * للمستودع (libs.versions.toml + gradle.properties + locales_config.xml
 * + إصدار التطبيق) — فتصبح بوابة الجودة نشطة في كل بناء.
 */
class BuildMaturityTest {

    // ---------- أدوات: ملفات المستودع الحقيقية ----------

    private fun repoFile(relFromModule: String, relFromRepo: String): String {
        val candidates = listOf(File(relFromModule), File(relFromRepo))
        for (f in candidates) if (f.exists()) return f.readText()
        // تجربة الصعود من دليل العمل
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val d = dir ?: return@repeat
            for (rel in listOf(relFromModule, relFromRepo)) {
                val f = File(d, rel)
                if (f.exists()) return f.readText()
            }
            dir = d.parentFile
        }
        error("الملف غير موجود: $relFromModule")
    }

    private fun resDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val d = dir ?: return@repeat
            for (rel in listOf("src/main/res", "app/src/main/res")) {
                val f = File(d, rel)
                if (f.isDirectory) return f
            }
            dir = d.parentFile
        }
        error("res غير موجود")
    }

    private val toml get() = repoFile("gradle/libs.versions.toml", "gradle/libs.versions.toml")
    private val gradleProps get() = repoFile("gradle.properties", "gradle.properties")
    private val localeConfig get() = repoFile(
        "src/main/res/xml/locales_config.xml",
        "app/src/main/res/xml/locales_config.xml",
    )

    // ---------- كتالوج الإصدارات ----------

    @Test
    fun `dynamic versions are flagged`() {
        val tomlSample = """
            [versions]
            ok = "1.2.3"
            bad1 = "1.2.+"
            bad2 = "2.0-SNAPSHOT"
            bad3 = "[1.0,2.0)"
            bad4 = "latest"
            [libraries]
            a = { group = "g", name = "n", version.ref = "ok" }
            b = { group = "g", name = "n", version = "3.+" }
            c = { group = "g", name = "n", version.ref = "missing" }
            [plugins]
            p = { id = "x", version.ref = "ok" }
        """.trimIndent()
        val issues = BuildMaturityEngine.auditVersionCatalog(tomlSample)
        val rules = issues.map { it.rule }.toSet()
        assertTrue("dynamic-version" in rules)
        assertTrue("undefined-version-ref" in rules)
        assertEquals(5, issues.count { it.rule == "dynamic-version" })
        assertEquals(1, issues.count { it.rule == "undefined-version-ref" })
    }

    @Test
    fun `repo version catalog is fully pinned`() {
        val issues = BuildMaturityEngine.auditVersionCatalog(toml)
        assertTrue("كتالوج المستودع غير مثبّت: $issues", issues.isEmpty())
    }

    // ---------- أعلام gradle.properties ----------

    @Test
    fun `gradle properties flags are enforced`() {
        val issues = BuildMaturityEngine.auditGradleProperties("org.gradle.caching=false\nandroid.useAndroidX=true\n")
        assertTrue(issues.any { it.rule == "bad-flag-value" && it.detail.contains("org.gradle.caching") })
        val missing = BuildMaturityEngine.auditGradleProperties("android.useAndroidX=true\n")
        assertTrue(missing.any { it.rule == "missing-flag" && it.detail.contains("org.gradle.caching") })
    }

    @Test
    fun `repo gradle properties pass the flag audit`() {
        val issues = BuildMaturityEngine.auditGradleProperties(gradleProps)
        assertTrue("أعلام المستودع مخالفة: $issues", issues.isEmpty())
    }

    // ---------- إصدار التطبيق ----------

    @Test
    fun `app version must be semver and positive`() {
        assertTrue(BuildMaturityEngine.auditAppVersion("0.38.0", 38).isEmpty())
        assertTrue(BuildMaturityEngine.auditAppVersion("0.38", 38).isNotEmpty())
        assertTrue(BuildMaturityEngine.auditAppVersion("1.0.0-rc1", 38).isNotEmpty())
        assertTrue(BuildMaturityEngine.auditAppVersion("0.38.0", 0).isNotEmpty())
        assertTrue(BuildMaturityEngine.auditAppVersion(null, null).size == 2)
    }

    // ---------- locales_config والموارد ----------

    @Test
    fun `locale config audit reports missing locales`() {
        val xml = """<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
            <locale android:name="ar" />
            <locale android:name="en" />
        </locale-config>"""
        val required = setOf("ar", "en", "fr")
        val issues = BuildMaturityEngine.auditLocaleConfig(xml, required)
        assertEquals(listOf(BuildIssue("missing-locale", "locales_config ينقصه الوسم fr")), issues)
        assertTrue(BuildMaturityEngine.auditLocaleConfig(xml, setOf("ar", "en")).isEmpty())
    }

    @Test
    fun `repo locale config declares all ten languages`() {
        val issues = BuildMaturityEngine.auditLocaleConfig(localeConfig, LanguageCatalog.codes())
        assertTrue("locales_config ناقص: $issues", issues.isEmpty())
    }

    @Test
    fun `res dirs audit flags missing translation folders`() {
        val dirs = listOf("values", "values-en", "values-fr")
        val issues = BuildMaturityEngine.auditResLocaleDirs(dirs, setOf("ar", "en", "fr", "de"), "ar")
        assertEquals(1, issues.size)
        assertTrue(issues[0].detail.contains("values-de"))
    }

    @Test
    fun `repo has res folders for every non base language`() {
        val dirNames = resDir().listFiles { f -> f.isDirectory }.orEmpty().map { it.name }
        val issues = BuildMaturityEngine.auditResLocaleDirs(dirNames, LanguageCatalog.codes(), "ar")
        assertTrue("مجلدات موارد ناقصة: $issues", issues.isEmpty())
    }

    // ---------- إصدار المستودع الفعلي ----------

    @Test
    fun `repo app version is semver and matches v0_41`() {
        // يقرأ app/build.gradle.kts ويتحقق من ثنائي الإصدار الحالي
        val text = repoFile("app/build.gradle.kts", "app/build.gradle.kts")
        val name = Regex("""versionName\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        val code = Regex("""versionCode\s*=\s*(\d+)""").find(text)?.groupValues?.get(1)?.toInt()
        val issues = BuildMaturityEngine.auditAppVersion(name, code)
        assertTrue("إصدار المستودع مخالف: $issues", issues.isEmpty())
        assertEquals("0.41.0", name)
        assertEquals(41, code)
    }
}
