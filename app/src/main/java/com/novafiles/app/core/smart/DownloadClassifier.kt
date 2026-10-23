package com.novafiles.app.core.smart

import com.novafiles.app.core.analyzer.Categories
import com.novafiles.app.core.analyzer.SnapshotRow

/**
 * v0.34.0 — مصنّف التنزيلات التلقائي (pure Kotlin — صفر IO)
 *
 * قواعد حتمية بترتيب أولوية صارم (الأولوية تفسَّر في الاختبارات):
 *  1) مخلفات تنزيل جزئي (.crdownload/.part/.tmp/.nfpart/.nfjournal/.partial) → CLUTTER
 *  2) نمط نسخ إعادة التنزيل «اسم (N)» → CLUTTER (قاعدة سلوك: كومة تنزيلات مكررة)
 *  3) امتداد مثبّت (apk/apks/xapk/aab) → INSTALLER
 *  4) امتداد أرشيف → ARCHIVE
 *  5) امتداد وسائط (فيديو/صوت/صور — مجموعات المحلل القائمة) → MEDIA
 *  6) امتداد مستند → DOCUMENT
 *  7) بلا امتداد أو امتداد مجهول → CLUTTER (فوضى)
 *
 * لا حذف هنا أبداً — المخرجات تُعرض في صندوق المراجعة الأسبوعي.
 */
object DownloadClassifier {

    /** امتدادات التنزيلات الجزئية/المخلفة السلوكية */
    val PARTIAL_EXTS = setOf("crdownload", "part", "tmp", "nfpart", "nfjournal", "partial")

    /** امتدادات المثبّتات */
    val INSTALLER_EXTS = setOf("apk", "apks", "xapk", "aab")

    /** امتدادات الأرشيفات (نفس مجموعة المحلل) */
    val ARCHIVE_EXTS = Categories.archives

    /** امتدادات الوسائط = فيديو + صوت + صور من مجموعة المحلل القائمة */
    val MEDIA_EXTS: Set<String> = Categories.video + Categories.audio + Categories.images

    /** امتدادات المستندات من مجموعة المحلل القائمة */
    val DOC_EXTS: Set<String> = Categories.docs

    /** نمط نسخ إعادة التنزيل: «اسم (N)» قبل الامتداد */
    private val COPY_SUFFIX = Regex("^(.+)\\s\\((\\d+)\\)$")

    /** الامتداد الصغير بعد آخر نقطة */
    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    /** هل يطابق الاسم نمط نسخة إعادة تنزيل «اسم (N).ext»؟ */
    fun isCopyPile(name: String): Boolean {
        val stem = name.substringBeforeLast('.', name)
        return stem.isNotEmpty() && COPY_SUFFIX.matches(stem)
    }

    /**
     * التصنيف من (الاسم، مسار الأب) فقط — لا IO إطلاقاً.
     * مسار الأب محفوظ للقرارات المستقبلية (قواعد مسار) دون تغيير التوقيع.
     */
    fun classify(name: String, parentPath: String = ""): DownloadClass {
        val ext = extensionOf(name)
        return when {
            ext in PARTIAL_EXTS -> DownloadClass.CLUTTER
            ext in INSTALLER_EXTS -> DownloadClass.INSTALLER
            ext in ARCHIVE_EXTS -> DownloadClass.ARCHIVE
            ext in MEDIA_EXTS -> DownloadClass.MEDIA
            ext in DOC_EXTS -> DownloadClass.DOCUMENT
            else -> DownloadClass.CLUTTER
        }
    }

    /**
     * قاعدة السلوك الدفعية: «اسم (N).ext» يُعدّ فوضى فقط إذا وُجد الأساس «اسم.ext»
     * في نفس المجلد (كومة إعادة تنزيل حقيقية) — أسماء مثل «season 1 (2020).mkv» تبقى وسائط.
     */
    private fun copyPileOverride(files: List<ClassifiedFile>): List<ClassifiedFile> {
        val result = files.toMutableList()
        val byParent = files.groupBy { it.path.substringBeforeLast('/') }
        for ((_, list) in byParent) {
            val names = list.map { it.name }.toSet()
            for (i in list.indices) {
                val f = list[i]
                if (f.clazz == DownloadClass.CLUTTER || !isCopyPile(f.name)) continue
                val ext = f.name.substringAfterLast('.', "")
                val stem = f.name.substringBeforeLast('.', f.name).replace(Regex("\\s\\(\\d+\\)$"), "")
                val base = if (ext.isEmpty()) stem else "$stem.$ext"
                if (base in names) result[i] = f.copy(clazz = DownloadClass.CLUTTER)
            }
        }
        return result
    }

    /** تصنيف قائمة صفوف فهرس (ملفات فقط) مع عمر الأيام وقاعدة كومة النسخ الدفعية */
    fun classifyAll(
        rows: List<SnapshotRow>,
        nowMs: Long,
    ): List<ClassifiedFile> {
        val mapped = rows
            .filter { !it.isDirectory }
            .map { r ->
                ClassifiedFile(
                    path = r.path,
                    name = r.name,
                    size = r.size,
                    modified = r.modified,
                    ageDays = ((nowMs - r.modified).coerceAtLeast(0L) / DAY_MS).toInt(),
                    clazz = classify(r.name, r.parent),
                )
            }
        return copyPileOverride(mapped)
    }

    /** هل يحتوي المسار على مجلد تنزيلات معياري؟ (لتحديد نطاق صندوق المراجعة) */
    fun isDownloadPath(path: String, rootPrefix: String): Boolean {
        val rel = path.removePrefix(rootPrefix).trimStart('/')
        val first = rel.substringBefore('/')
        return first.equals("Download", ignoreCase = true) || first.equals("Downloads", ignoreCase = true)
    }

    const val DAY_MS = 86_400_000L
}
