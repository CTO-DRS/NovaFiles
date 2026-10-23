package com.novafiles.app.core.rules

import com.novafiles.app.core.checksum.ChecksumAlgorithm
import com.novafiles.app.core.fs.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات مكتبة قوالب الأتمتة: كل قالب ينتج قاعدة صالحة قابلة للتشغيل */
class RuleTemplatesTest {

    private val now: Long = 1_792_065_600_000L // 2026-10-15 12:00 UTC
    private val folder = "/storage/emulated/0/Download"

    private fun template(id: String) = RuleTemplates.ALL.first { it.id == id }

    @Test
    fun `library exposes exactly five templates with unique ids`() {
        assertEquals(5, RuleTemplates.ALL.size)
        assertEquals(RuleTemplates.ALL.map { it.id }.distinct(), RuleTemplates.ALL.map { it.id })
    }

    @Test
    fun `every template builds a structurally valid rule`() {
        for (t in RuleTemplates.ALL) {
            val r = t.build(folder, now)
            assertEquals(folder, r.watchFolder)
            assertTrue("قالب ${t.id} بلا أفعال", r.actions.isNotEmpty())
            // قالب الحساب السحابي يُنشأ معطلاً حتى يُختار الحساب — البقية صالحة تماماً
            if (!t.needsAccount) {
                assertTrue("قالب ${t.id}: أخطاء ${r.validationErrors()}", r.validationErrors().isEmpty())
                assertTrue(r.enabled)
            }
        }
    }

    @Test
    fun `whatsapp monthly archives images into dated zip`() {
        val r = template("whatsapp_monthly").build(folder, now)
        assertEquals(setOf(FileType.IMAGE), r.conditions.types)
        val a = r.actions.single() as RuleAction.ArchiveZip
        assertEquals("WhatsApp-{date}", a.namePattern)
        assertTrue(a.append)
        assertFalse(a.deleteAfter)
        // المحرك يحل {date} → مستثنى من المطابقة (لا حلقة إعادة أرشفة)
        val e = WatchRuleEngine(
            object : WatchRuleEngine.RuleActionHost {
                override suspend fun tag(path: String, tag: String) {}
                override suspend fun upload(file: java.io.File, accountId: Long, remoteDir: String) = true
            },
            clock = { now },
        ).selfArtifacts(r)
        assertTrue(e.contains("WhatsApp-2026-10.zip"))
    }

    @Test
    fun `apk collect moves to NovaFiles-APKs subfolder`() {
        val r = template("apk_collect").build(folder, now)
        val a = r.actions.single() as RuleAction.MoveTo
        assertEquals("$folder/NovaFiles-APKs", a.targetDir)
        assertEquals(setOf(FileType.APK), r.conditions.types)
        assertTrue(r.validationErrors().isEmpty())
    }

    @Test
    fun `screenshots sort matches glob case-insensitively`() {
        val r = template("screenshots_sort").build(folder, now)
        val a = r.actions.single() as RuleAction.MoveTo
        assertEquals("$folder/Screenshots", a.targetDir)
        val c = r.conditions
        assertTrue(c.nameMatches("Screenshot_2026-10-15.jpg"))
        assertTrue(c.nameMatches("screenshot_x.png"))
        assertFalse(c.nameMatches("photo.jpg"))
    }

    @Test
    fun `docs cloud starts disabled until account chosen`() {
        val r = template("docs_cloud").build(folder, now)
        assertFalse(r.enabled)
        assertTrue(template("docs_cloud").needsAccount)
        assertTrue(r.validationErrors().contains("cloud_account_missing"))
        // بعد اختيار حساب: تُصبح صالحة
        val fixed = r.copy(enabled = true, actions = listOf((r.actions.single() as RuleAction.UploadCloud).copy(accountId = 9L)))
        assertTrue(fixed.validationErrors().isEmpty())
    }

    @Test
    fun `archives checksum uses SHA-256 on archive type`() {
        val r = template("archives_checksum").build(folder, now)
        val a = r.actions.single() as RuleAction.Checksum
        assertEquals(ChecksumAlgorithm.SHA256, a.algorithm)
        assertEquals(setOf(FileType.ARCHIVE), r.conditions.types)
    }

    @Test
    fun `templates produce distinct rule instances each call`() {
        val t = template("apk_collect")
        val r1 = t.build(folder, now)
        val r2 = t.build(folder, now)
        assertTrue(r1 !== r2)
        // والمعرّفات فريدة (idFactory)
        assertTrue(r1.id != r2.id)
    }

    @Test
    fun `deterministic id factory yields unique ids in bulk`() {
        val ids = (1..200).map { RuleTemplates.idFactory() }
        assertEquals(ids.size, ids.distinct().size)
    }
}
