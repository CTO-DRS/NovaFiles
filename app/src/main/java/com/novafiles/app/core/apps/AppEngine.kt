package com.novafiles.app.core.apps

import java.io.File
import java.util.Locale

/**
 * ══════════════════════════════════════════════════════════
 *  AppEngine — محرك مدير التطبيقات (JVM خالص قابل للاختبار) — v0.22
 *  ─ فرز وتصفية قائمة التطبيقات المثبتة
 *  ─ تسمية ملفات النسخ الاحتياطي APK بشكل آمن ومعقّم
 *  ─ مسح ملفات APK المتراكمة في التخزين بعمق محدود
 *  طبقة Android (PackageManager) تبقى رقيقة في AppsScreen —
 *  كل المنطق القابل للاختبار يعيش هنا بلا أي اعتماد أندرويدي.
 * ══════════════════════════════════════════════════════════
 */

/** صف تطبيق مُجمَّع من PackageManager (بلا أنواع أندرويد لتبقى JVM خالصة) */
data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val installTimeMs: Long,
    val isSystem: Boolean,
    val apkPath: String,
)

/** أنماط الفرز المتاحة */
enum class AppSort { NAME, SIZE, RECENT }

/** تصفية المصدر */
enum class AppFilter { ALL, USER, SYSTEM, APK_FILES }

object AppEngine {

    /** فرز حتمي: الاسم أبجدياً (غير حساس لحالة الأحرف)، الحجم تنازلياً، الأحدث تثبيتاً تنازلياً —
     *  كسر التعادل دائماً باسم الحزمة أبجدياً حتى تكون النتائج ثابتة عبر الأجهزة */
    fun sort(apps: List<AppEntry>, mode: AppSort): List<AppEntry> = when (mode) {
        AppSort.NAME ->
            apps.sortedWith(
                compareBy({ it.label.lowercase(Locale.ROOT) }, { it.packageName }),
            )
        AppSort.SIZE ->
            apps.sortedWith(
                compareByDescending<AppEntry> { it.sizeBytes }.thenBy { it.packageName },
            )
        AppSort.RECENT ->
            apps.sortedWith(
                compareByDescending<AppEntry> { it.installTimeMs }.thenBy { it.packageName },
            )
    }

    /** تصفية المصدر: الكل / مثبت من المستخدم / تطبيقات النظام */
    fun filter(apps: List<AppEntry>, f: AppFilter): List<AppEntry> = when (f) {
        AppFilter.ALL, AppFilter.APK_FILES -> apps
        AppFilter.USER -> apps.filterNot { it.isSystem }
        AppFilter.SYSTEM -> apps.filter { it.isSystem }
    }

    /** تطبيق الفرز والتصفية معاً (سلسلة الشاشة القياسية) */
    fun present(apps: List<AppEntry>, f: AppFilter, s: AppSort): List<AppEntry> =
        sort(filter(apps, f), s)

    /** الحجم الكلي لتطبيقات المستخدم (تُعرض في صف الملخص) */
    fun userTotalBytes(apps: List<AppEntry>): Long =
        apps.filterNot { it.isSystem }.sumOf { it.sizeBytes }

    /**
     * تسمية ملف النسخة الاحتياطية: «Label_v1.2.3(45).apk»
     * ─ تعقيم محارف الملفات غير القانونية [\\/:*?"<>|] وعناصر التحكم إلى _
     * ─ طيّ المسافات المتكررة وتقليم الأطراف
     * ─ اسم فارغ أو كله محارف غير قانونية → «app»
     * ─ قصّ الجزء النصي عند 60 محرفاً كي لا تتضخم الأسماء
     */
    fun backupFileName(label: String, versionName: String, versionCode: Long): String {
        val sanitizedBase = label
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .let { if (it.isEmpty() || it.all { c -> c == '_' }) "app" else it }
            .let { if (it.length > 60) it.take(60).trimEnd() else it }
            .ifEmpty { "app" }
        val ver = versionName.ifBlank { "0" }
        return "${sanitizedBase}_v${ver}(${versionCode}).apk"
    }

    /**
     * مسح ملفات APK المتراكمة في مجلدات شائعة (التنزيلات، بلوتوث، واتساب، تيليغرام…)
     * ─ عمق محدود وحد أقصى كما في RecentsEngine
     * ─ تجاهل المجلدات المخفية وغير القابلة للقراءة
     * ─ الترتيب تنازلياً بالحجم ثم الاسم (حتمي)
     */
    fun findApkFiles(
        roots: List<File>,
        now: Long = System.currentTimeMillis(),
        limit: Int = 100,
        maxDepth: Int = 3,
    ): List<File> {
        val out = ArrayList<Pair<File, Long>>() // ملف + حجم
        val seen = HashSet<String>()

        fun walk(dir: File, depth: Int) {
            if (depth > maxDepth || out.size >= limit * 4) return
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (out.size >= limit * 4) return
                if (f.isDirectory) {
                    if (!f.isHidden && f.canRead()) walk(f, depth + 1)
                } else if (f.isFile && f.length() > 0 && f.canRead() && !f.isHidden) {
                    if (f.name.endsWith(".apk", ignoreCase = true) && seen.add(f.canonicalPath)) {
                        out += f to f.length()
                    }
                }
            }
        }
        roots.forEach { root ->
            if (root.isDirectory && root.canRead() && seen.add(root.canonicalPath)) walk(root, 0)
        }
        return out.sortedWith(
            compareByDescending<Pair<File, Long>> { it.second }.thenBy { it.first.name.lowercase(Locale.ROOT) },
        ).take(limit).map { it.first }
    }

    /** اشتقاق مسار مجلد النسخ الاحتياطي من جذر التنزيلات (لن يُنشئ شيئاً هنا) */
    fun backupDir(downloadsRoot: File): File = File(downloadsRoot, "NovaFiles-APKs")

    /** المسار الكامل المخطط لنسخة احتياطية (يتحقق من الاصطدام بإلحاق رقم) */
    fun backupTarget(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val stem = fileName.removeSuffix(".apk")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$stem($n).apk")
            n++
        }
        return candidate
    }
}
