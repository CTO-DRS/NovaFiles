package com.novafiles.app.core.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * اختبارات v0.39 — كتالوج تراخيص المصدر المفتوح — JVM خالصة.
 *
 * تُختبر القواعد البنيوية أولاً، ثم **بوابة التقاطع** مع
 * libs.versions.toml الحقيقي: إصدار مكتبة في الكتالوج يخالف
 * الكتالوج الحقيقي → البناء أحمر (نمط BuildMaturityTest).
 */
class LicenseCatalogTest {

    // ---------- التحقق البنيوي ----------

    @Test
    fun `catalog is structurally valid`() {
        assertTrue(LicenseCatalog.validate().isEmpty())
    }

    @Test
    fun `ids are unique and catalog covers real dependencies`() {
        val ids = LicenseCatalog.LIBS.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(LicenseCatalog.LIBS.size >= 14)
    }

    @Test
    fun `all licenses are from known permissive set`() {
        val known = setOf("Apache-2.0", "MIT", "Unlicense", "Public-Domain")
        val unknown = LicenseCatalog.LIBS.filter { it.license !in known }
        assertTrue("رخص غير متوقعة: ${unknown.map { it.id }}", unknown.isEmpty())
    }

    @Test
    fun `all sources are https`() {
        assertTrue(LicenseCatalog.LIBS.all { it.source.startsWith("https://") })
    }

    // ---------- الملخصات الحتمية ----------

    @Test
    fun `counts by license are sorted and accurate`() {
        val counts = LicenseCatalog.countsByLicense()
        assertEquals(counts, counts.sortedBy { it.first })
        val total = counts.sumOf { it.second }
        assertEquals(LicenseCatalog.LIBS.size, total)
        // أباتشي هي الغالبة بلا منازع في هذا المشروع
        val apache = counts.first { it.first == "Apache-2.0" }.second
        assertTrue(apache >= 10)
    }

    @Test
    fun `sorted by name is stable and different id order`() {
        val names = LicenseCatalog.sortedByName().map { it.name.lowercase() }
        assertEquals(names, names.sorted())
        assertEquals(LicenseCatalog.LIBS.size, names.size)
    }

    @Test
    fun `byId resolves and unknown returns null`() {
        assertEquals("Zip4j", LicenseCatalog.byId("zip4j")?.name)
        assertEquals(null, LicenseCatalog.byId("ghost"))
    }

    // ---------- بوابة التقاطع مع libs.versions.toml الحقيقي ----------

    private fun repoFile(relFromModule: String, relFromRepo: String): String {
        val candidates = listOf(File(relFromModule), File(relFromRepo))
        for (f in candidates) if (f.exists()) return f.readText()
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

    private val toml by lazy { repoFile("gradle/libs.versions.toml", "gradle/libs.versions.toml") }

    /** مستخرج [versions] من toml الحقيقي — مفتاح → قيمة */
    private fun tomlVersions(): Map<String, String> {
        var section = ""
        val out = mutableMapOf<String, String>()
        for (raw in toml.lines()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[") && line.endsWith("]")) { section = line; continue }
            if (section == "[versions]" && line.contains('=')) {
                val k = line.substringBefore('=').trim()
                val v = line.substringAfter('=').trim().trim('"')
                out[k] = v
            }
        }
        return out
    }

    @Test
    fun `catalog versions match the real version catalog`() {
        val real = tomlVersions()
        // خريطة معرف الكتالوج → مفتاح الإصدار في toml
        val mapping = mapOf(
            "coil" to "coil",
            "commons-compress" to "commonsCompress",
            "commons-net" to "commonsNet",
            "coroutines" to "coroutines",
            "junrar" to "junrar",
            "kotlin" to "kotlin",
            "okhttp" to "okhttp",
            "smbj" to "smbj",
            "sshd" to "sshd",
            "slf4j" to "slf4jAndroid",
            "xzd" to "xz",
            "zip4j" to "zip4j",
            "zxing" to "zxing",
        )
        val drift = mutableListOf<String>()
        for ((catId, tomlKey) in mapping) {
            val expected = real[tomlKey]
            val actual = LicenseCatalog.versionsById()[catId]
            if (expected == null) {
                drift += "مفتاح toml $tomlKey غير موجود أصلاً"
            } else if (actual != expected) {
                drift += "$catId: الكتالوج $actual مقابل toml $expected"
            }
        }
        assertTrue("انزياح إصدارات: $drift", drift.isEmpty())
    }

    @Test
    fun `androidx version is pinned reasonably`() {
        // AndroidX مدخل مجمّع — الإصدار المعلن يطابق مكتبة Room المحورية
        val real = tomlVersions()
        assertEquals(real["room"], LicenseCatalog.byId("androidx")?.version)
    }
}
