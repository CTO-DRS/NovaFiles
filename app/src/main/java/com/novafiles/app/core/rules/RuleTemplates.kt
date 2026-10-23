package com.novafiles.app.core.rules

import com.novafiles.app.core.checksum.ChecksumAlgorithm
import com.novafiles.app.core.fs.FileType

/**
 * v0.35.0 — مكتبة قوالب الأتمتة الجاهزة.
 *
 * كل قالب يبني قاعدة كاملة فوق المجلد المراقب المُمرَّر، بأفعال من المحركات
 * الموجودة فعلاً (ZIP، بصمات v0.29، نقل، وسم، رفع سحابي). القوالب التي
 * كانت تعتمد على v0.30/v0.31/v0.32 صيغت ببدائل متاحة:
 * - «صوّر WebP» (كانت تحتاج تحويل وسائط v0.31) → استُبدلت بنقل لقطات الشاشة.
 * - «زوج مزامنة سحابي» (كانت تحتاج v0.32) → استُبدلت برفع مباشر للسحابة.
 */
data class RuleTemplate(
    /** مفتاح سلاسل الواجهة: rules_tpl_<id>_title / _desc */
    val id: String,
    /** هل القالب يحتاج خطوة إضافية قبل التفعيل (اختيار حساب سحابي مثلاً)؟ */
    val needsAccount: Boolean = false,
    val build: (watchFolder: String, now: Long) -> WatchRule,
)

object RuleTemplates {

    /** معرّف قاعدة جديد فريد: عدّاد ذري + وقت — حتمي التفرد حتى في نفس الميلي ثانية */
    private val idCounter = java.util.concurrent.atomic.AtomicLong(0)
    var idFactory: () -> String = {
        "r" + idCounter.incrementAndGet().toString(36) + "-" + System.currentTimeMillis().toString(36)
    }

    fun newRule(
        name: String,
        watchFolder: String,
        conditions: RuleConditions,
        actions: List<RuleAction>,
        now: Long,
        dryRun: Boolean = false,
    ): WatchRule = WatchRule(
        id = idFactory(),
        name = name,
        watchFolder = watchFolder,
        conditions = conditions,
        actions = actions,
        dryRun = dryRun,
        createdAt = now,
    )

    /** القوالب الخمسة — بالترتيب المعروض في الواجهة */
    val ALL: List<RuleTemplate> = listOf(

        // 1) صور واتساب → أرشيف شهري (فوق ZipArchive القائم بدل 7z المؤجل v0.30)
        RuleTemplate("whatsapp_monthly") { folder, now ->
            newRule(
                name = "WhatsApp",
                watchFolder = folder,
                conditions = RuleConditions(types = setOf(FileType.IMAGE)),
                actions = listOf(
                    RuleAction.ArchiveZip("WhatsApp-{date}", append = true, deleteAfter = false),
                ),
                now = now,
            )
        },

        // 2) ملفات APK → مجلد حفظ النسخ (نمط مجلد النسخ الاحتياطي في مدير التطبيقات)
        RuleTemplate("apk_collect") { folder, now ->
            newRule(
                name = "APK",
                watchFolder = folder,
                conditions = RuleConditions(types = setOf(FileType.APK)),
                actions = listOf(RuleAction.MoveTo(child(folder, "NovaFiles-APKs"))),
                now = now,
            )
        },

        // 3) لقطات الشاشة → مجلد مخصص (بدل «صغّرها وحوّلها WebP» المؤجلة مع v0.31)
        RuleTemplate("screenshots_sort") { folder, now ->
            newRule(
                name = "Screenshots",
                watchFolder = folder,
                conditions = RuleConditions(nameGlob = "Screenshot*"),
                actions = listOf(RuleAction.MoveTo(child(folder, "Screenshots"))),
                now = now,
            )
        },

        // 4) المستندات المهمة → رفع سحابي (بدل «زوج مزامنة» المؤجل مع v0.32)
        //    تنشأ معطلة حتى يختار المستخدم الحساب من المحرر
        RuleTemplate("docs_cloud", needsAccount = true) { folder, now ->
            newRule(
                name = "Docs",
                watchFolder = folder,
                conditions = RuleConditions(types = setOf(FileType.DOC, FileType.PDF)),
                actions = listOf(RuleAction.UploadCloud(accountId = 0L, remoteDir = "/NovaFiles-Docs")),
                now = now,
            ).copy(enabled = false)
        },

        // 5) الأرشيفات الواردة → بصمة SHA-256 (فوق محرك v0.29 — تحقق سلامة تلقائي)
        RuleTemplate("archives_checksum") { folder, now ->
            newRule(
                name = "SHA-256",
                watchFolder = folder,
                conditions = RuleConditions(types = setOf(FileType.ARCHIVE)),
                actions = listOf(RuleAction.Checksum(ChecksumAlgorithm.SHA256)),
                now = now,
            )
        },
    )

    /** مجلد فرعي تحت مجلد المراقبة */
    private fun child(folder: String, name: String): String {
        val base = folder.trimEnd('/')
        return "$base/$name"
    }
}
