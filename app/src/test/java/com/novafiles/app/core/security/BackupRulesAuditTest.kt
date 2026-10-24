package com.novafiles.app.core.security

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * v0.41 — بوابة أمنية لحماية النسخ الاحتياطي ونقل الأجهزة (اختبار مستودع حقيقي).
 *
 * يقرأ ملفات XML الفعلية من res/xml ويفشل فوراً إذا:
 *  1) سُرّب أي مخزن أسرار معروف إلى النسخ الاحتياطي السحابي أو نقل الجهاز-للجهاز
 *     أو النسخ الاحتياطي الكامل القديم (API<=30).
 *  2) عاد عنصر <root-path> إلى file_paths.xml (كشف جذر نظام الملفات عبر FileProvider).
 *
 * المخازن المحمية (مثبتة بتدقيق 2026-10-24):
 *  - vault_key_notes.txt : عبارة استعادة BIP39 نصاً صريحاً (root)
 *  - nova_vault.xml      : رموز OAuth المشفرة (CredentialVault) (sharedpref)
 *  - nova_vault2.xml     : المفتاح الجذر المغلف + مفتاح التنكّر (Vault2Manager) (sharedpref)
 *  - nova_known_hosts.xml: بصمات مضيفي SFTP TOFU (sharedpref)
 *  - nova_settings.preferences_pb : تجزئة PIN + الملح + كلمات مرور FTP/WebDAV (datastore)
 */
class BackupRulesAuditTest {

    // ---------- تحديد جذور المستودع الحقيقية (نفس أسلوب اختبارات المستودع الأخرى) ----------

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            if (File(dir, "app/src/main/res/xml/data_extraction_rules.xml").exists()) return dir
            dir = dir.parentFile ?: dir
        }
        return dir
    }

    private fun xml(name: String): String {
        val f = File(repoRoot(), "app/src/main/res/xml/$name")
        assertTrue("الملف المطلوب غير موجود: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    private fun section(raw: String, tag: String): String {
        val open = "<$tag"
        val start = raw.indexOf(open)
        if (start < 0) return ""
        val bodyStart = raw.indexOf('>', start) + 1
        val close = "</$tag>"
        val end = raw.indexOf(close, bodyStart)
        if (end < 0) return ""
        return raw.substring(bodyStart, end)
    }

    private fun excludes(body: String): List<Pair<String, String>> {
        val re = Regex("""<exclude\s+domain="([^"]+)"\s+path="([^"]+)"""")
        return re.findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList()
    }

    // ---------- قائمة الأسرار المحمية — مصدر الحقيقة الواحد ----------

    private val secretExcludes = listOf(
        "root" to "vault_key_notes.txt",
        "sharedpref" to "nova_vault.xml",
        "sharedpref" to "nova_vault2.xml",
        "sharedpref" to "nova_known_hosts.xml",
        "file" to "datastore/nova_settings.preferences_pb",
    )

    // ---------- 1) قواعد Android 12+ ----------

    @Test
    fun `cloud-backup excludes every secret store`() {
        val body = section(xml("data_extraction_rules.xml"), "cloud-backup")
        val rules = excludes(body)
        for ((domain, path) in secretExcludes) {
            assertTrue(
                "غياب استثناء النسخ السحابي: domain=$domain path=$path",
                rules.contains(domain to path),
            )
        }
    }

    @Test
    fun `device-transfer section exists and excludes every secret store`() {
        val raw = xml("data_extraction_rules.xml")
        val body = section(raw, "device-transfer")
        assertTrue("قسم device-transfer غائب كلياً — نقل الأجهزة ينسخ كل شيء", body.isNotBlank())
        val rules = excludes(body)
        for ((domain, path) in secretExcludes) {
            assertTrue(
                "غياب استثناء نقل الأجهزة: domain=$domain path=$path",
                rules.contains(domain to path),
            )
        }
    }

    // ---------- 2) قواعد API <= 30 ----------

    @Test
    fun `legacy full-backup-content excludes every secret store`() {
        val rules = excludes(xml("backup_rules.xml"))
        for ((domain, path) in secretExcludes) {
            assertTrue(
                "غياب استثناء النسخ الكامل القديم: domain=$domain path=$path",
                rules.contains(domain to path),
            )
        }
    }

    // ---------- 3) نطاق FileProvider ----------

    @Test
    fun `file_paths must not expose filesystem root`() {
        val raw = xml("file_paths.xml")
        assertFalse(
            "root-path=\".\" يعيد فتح جذر النظام عبر FileProvider — محظور",
            raw.contains("<root-path"),
        )
        assertTrue("external-path مطلوب لمشاركة ملفات المستخدم", raw.contains("<external-path"))
        assertTrue("files-path مطلوب للمخرجات الداخلية", raw.contains("<files-path"))
        assertTrue("cache-path مطلوب للمؤقتات", raw.contains("<cache-path"))
    }

    // ---------- 4) صحة بنية XML نفسها (منع علامة غير مقفلة أو نسخ ناقص) ----------

    @Test
    fun `extraction rules are well formed with both sections`() {
        val raw = xml("data_extraction_rules.xml")
        assertTrue(section(raw, "cloud-backup").isNotBlank())
        assertTrue(section(raw, "device-transfer").isNotBlank())
        // لا يسمح بأي include سرّي مستقبلاً بالخطأ داخل هذه الأقسام
        val includes = Regex("""<include\s+domain="([^"]+)"\s+path="([^"]+)"""")
            .findAll(raw).map { it.groupValues[1] to it.groupValues[2] }.toList()
        for (inc in includes) {
            assertTrue(
                "يُمنع تضمين مخزن أسرار صراحة داخل قواعد الاستخراج: $inc",
                inc !in secretExcludes,
            )
        }
    }
}
