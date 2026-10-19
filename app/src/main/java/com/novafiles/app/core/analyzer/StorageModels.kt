package com.novafiles.app.core.analyzer

/**
 * v0.14.0 — نماذج تحليل التخزين (pure Kotlin — بلا أي اعتماد أندرويد)
 *
 * المحلل يعمل على «لقطة فهرس» (صفوف file_index) بدل مسح ثانٍ للقرص،
 * وفق ميزانية خارطة الطريق: تحليل 100 ألف ملف < 30 ثانية.
 */

/** صف لقطة الفهرس — نظير خفيف لـ FileIndexEntity دون Room */
data class SnapshotRow(
    val path: String,
    val name: String,
    val parent: String,
    val size: Long,
    val modified: Long,
    val isDirectory: Boolean,
)

/** فئات التحليل — نفس تجميع خارطة الطريق (فيديو/صوت/صور/مستندات/تطبيقات/أخرى) */
enum class StorageCategory {
    VIDEO, AUDIO, IMAGES, DOCS, APPS, ARCHIVES, OTHER;

    /** مفتاح مستقر يُستخدم في الروابط والاختبارات */
    val key: String get() = name.lowercase()
}

/** ملخص فئة: عدد الملفات وإجمالي الحجم بالبايت */
data class CategorySummary(
    val category: StorageCategory,
    val fileCount: Int,
    val totalBytes: Long,
)

/** تقرير التحليل الكامل للقطة */
data class StorageReport(
    val categories: List<CategorySummary>,
    val totalFiles: Int,
    val totalDirs: Int,
    val usedBytes: Long,
) {
    /** ترتيب الفئات تنازلياً بالحجم — لعرض «ما الذي يأكل هاتفي؟» */
    val bySizeDesc: List<CategorySummary>
        get() = categories.sortedByDescending { it.totalBytes }
}

/** عقدة في شجرة الأدلة المجمّعة (حجم الشجرة الفرعية محسوب bottom-up) */
data class DirNode(
    val path: String,
    val name: String,
    val subtreeBytes: Long,
    val directFileBytes: Long,
    val childDirs: Int,
    val fileCount: Int,
)

/** عنصر في لوحة الملفات الكبيرة/القديمة */
data class NotableFile(
    val path: String,
    val name: String,
    val size: Long,
    val modified: Long,
)

/**
 * محرك التصنيف — امتدادات كل فئة (صغيرة الحروف).
 * منفصل عن FileType لأن تجميع المحلل مختلف عن مرشحات البحث:
 * المجلدات تُستبعد هنا (تُجمع في الشجرة)، وARCHIVE فئة مستقلة، إلخ.
 */
object Categories {
    val video = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "wmv", "flv", "mpg", "mpeg")
    val audio = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma", "mid", "amr")
    val images = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tiff", "raw", "dng", "svg")
    val docs = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp",
        "txt", "md", "rtf", "csv", "epub", "mobi", "log", "json", "xml")
    val apps = setOf("apk", "apks", "xapk", "aab")
    val archives = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso")

    /** امتداد آمن من الاسم (بعد آخر نقطة، صغير الحروف) */
    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** تصنيف من الاسم فقط — لا IO إطلاقاً (يعمل على صفوف الفهرس) */
    fun of(name: String): StorageCategory {
        val ext = extensionOf(name)
        return when {
            ext in video -> StorageCategory.VIDEO
            ext in audio -> StorageCategory.AUDIO
            ext in images -> StorageCategory.IMAGES
            ext in docs -> StorageCategory.DOCS
            ext in apps -> StorageCategory.APPS
            ext in archives -> StorageCategory.ARCHIVES
            else -> StorageCategory.OTHER
        }
    }
}
