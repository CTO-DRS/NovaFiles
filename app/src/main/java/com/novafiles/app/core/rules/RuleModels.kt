package com.novafiles.app.core.rules

import com.novafiles.app.core.fs.FileType
import com.novafiles.app.core.checksum.ChecksumAlgorithm

/**
 * v0.35.0 — نماذج الأتمتة الذكية: قواعد «عند ظهور X في مجلد افعل Y».
 * كل النماذج خالصة JVM بلا Android — تُختبر 100% على أقراص مؤقتة.
 *
 * القاعدة: مجلد مراقب + شروط (نمط اسم/أنواع/حجم) + أفعال مرتبة.
 * الأفعال المبنية على المحركات القائمة: نقل (FileOps)، أرشفة ZIP (ZipArchive)،
 * بصمات (ChecksumEngine/Manifest من v0.29)، وسم (TagsDao)، رفع سحابي (CloudFs).
 * أفعال v0.30/v0.31/v0.32 (7z، تحويل وسائط، أزواج مزامنة) تُضاف عند إنجاز إصداراتها.
 */

/** شروط مطابقة الملف — تُقيَّم بـ AND بين الحقول المضبوطة، والمجلدات مستثناة دائماً */
data class RuleConditions(
    /** نمط اسم بنمط glob: * و ? — غير حساس لحالة الأحرف، null أو فارغ = أي اسم */
    val nameGlob: String? = null,
    /** أنواع الملفات المقبولة (تصنيف FileType من الامتداد) — فارغ = أي نوع */
    val types: Set<FileType> = emptySet(),
    /** أصغر حجم بالبايت (شامل) — null = بلا حد أدنى */
    val minSizeBytes: Long? = null,
    /** أكبر حجم بالبايت (شامل) — null = بلا حد أقصى */
    val maxSizeBytes: Long? = null,
) {
    /** هل يطابق الاسم نمط glob؟ (نمط فارغ يطابق الكل) */
    fun nameMatches(name: String): Boolean {
        val glob = nameGlob?.trim().orEmpty()
        if (glob.isEmpty()) return true
        return globToRegex(glob).matches(name)
    }

    /** هل يطابق النوع؟ (فارغ يطابق الكل) */
    fun typeMatches(name: String): Boolean =
        types.isEmpty() || FileType.ofName(name) in types

    /** هل يطابق الحجم؟ */
    fun sizeMatches(size: Long): Boolean {
        minSizeBytes?.let { if (size < it) return false }
        maxSizeBytes?.let { if (size > it) return false }
        return true
    }

    /** التقييم الكامل (AND) — للملفات فقط، المجلدات تُرشَّح قبل الاستدعاء */
    fun matches(name: String, size: Long): Boolean =
        nameMatches(name) && typeMatches(name) && sizeMatches(size)

    /** عدد الشروط المضبوطة فعلياً (لعرض ملخص القاعدة) */
    val activeCount: Int
        get() = listOf(
            !nameGlob.isNullOrBlank(),
            types.isNotEmpty(),
            minSizeBytes != null,
            maxSizeBytes != null,
        ).count { it }

    companion object {
        /**
         * تحويل glob إلى Regex مُرسّاة: * → .* و ? → . مع تهريب باقي المحارف
         * وحساسية حالة الأحرف معطلة (Screenshot* يطابق screenshot_1.jpg).
         */
        fun globToRegex(glob: String): Regex {
            val sb = StringBuilder()
            for (c in glob) when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}

/** أفعال القاعدة — كل فعل ينفذ على ملف واحد (تُطبَّق بالترتيب على كل ملف مطابق) */
sealed interface RuleAction {

    /** نقل الملف إلى مجلد هدف (مع تجنب تعارض الأسماء: name (1).ext) */
    data class MoveTo(val targetDir: String) : RuleAction

    /**
     * أرشفة الملفات المطابقة في ZIP واحد (فوق محرك ZipArchive القائم).
     * @param namePattern اسم الأرشيف دون امتداد — يدعم الرمز {date} الذي يُستبدل بـ yyyy-MM
     *   (دلاء شهرية: كل ملفات الشهر في أرشيف واحد)
     * @param append عند true ووجود الأرشيف: يُستخرج مؤقتاً ويُعاد ضغطه مع الملفات الجديدة
     * @param deleteAfter حذف الأصول بعد الأرشفة الناجحة (غير قابل للتراجع — يُوثَّق)
     */
    data class ArchiveZip(
        val namePattern: String,
        val append: Boolean = true,
        val deleteAfter: Boolean = false,
    ) : RuleAction

    /**
     * توليد/دمج ملف بصمات بنسق GNU coreutils (checksums.md5/sha1/sha256)
     * في مجلد الملفات المطابقة — الدمج يحفظ الأسطر القديمة لملفات لم تُذكر.
     */
    data class Checksum(val algorithm: ChecksumAlgorithm) : RuleAction

    /** وسم المسار بوسم نصي (يُخزَّن في جدول tags الموجود منذ v0.16) */
    data class TagFile(val tag: String) : RuleAction

    /** رفع الملف إلى حساب سحابي (Drive/Dropbox/OneDrive عبر StorageProvider) */
    data class UploadCloud(val accountId: Long, val remoteDir: String) : RuleAction

    /** مفتاح النوع في تسلسل JSON */
    val kind: String
        get() = when (this) {
            is MoveTo -> "move"
            is ArchiveZip -> "zip"
            is Checksum -> "sum"
            is TagFile -> "tag"
            is UploadCloud -> "cloud"
        }
}

/** قاعدة أتمتة كاملة */
data class WatchRule(
    val id: String,
    val name: String,
    val watchFolder: String,
    val conditions: RuleConditions = RuleConditions(),
    val actions: List<RuleAction> = emptyList(),
    val enabled: Boolean = true,
    /** وضع التجربة الجافة: التقييم يخطط فقط دون أي أثر على الملفات */
    val dryRun: Boolean = false,
    val createdAt: Long = 0L,
    val lastRunAt: Long? = null,
    val runCount: Int = 0,
) {
    /** أخطاء التحقق قبل الحفظ/التشغيل — قائمة فارغة = قاعدة صالحة */
    fun validationErrors(): List<String> {
        val errs = ArrayList<String>()
        if (name.isBlank()) errs += "name_blank"
        if (watchFolder.isBlank()) errs += "folder_blank"
        if (actions.isEmpty()) errs += "no_actions"
        // حماية حلقة لا نهائية: نقل الملفات إلى داخل المجلد المراقب نفسه يعيد
        // توليد أحداث المراقبة إلى ما لا نهاية — يُرفض عند التحقق
        for (a in actions) {
            if (a is RuleAction.MoveTo) {
                val t = a.targetDir.trim().trimEnd('/')
                val w = watchFolder.trim().trimEnd('/')
                if (t.isNotEmpty() && t == w) errs += "move_into_watch"
            }
            if (a is RuleAction.UploadCloud && a.accountId <= 0L) errs += "cloud_account_missing"
            if (a is RuleAction.TagFile && a.tag.isBlank()) errs += "tag_blank"
            if (a is RuleAction.ArchiveZip && a.namePattern.isBlank()) errs += "zip_name_blank"
        }
        return errs.distinct()
    }
}
