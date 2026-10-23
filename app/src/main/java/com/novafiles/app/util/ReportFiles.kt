package com.novafiles.app.util

import android.os.Environment
import java.io.File

/**
 * v0.36.0 — كتابة تقارير التصدير إلى Download/NovaFiles-Reports/ ومشاركتها.
 * نفس نمط نسخ APK الاحتياطية: كتابة مباشرة تحت Downloads المتاح بامتياز
 * MANAGE_EXTERNAL_STORAGE، والأسماء من ReportExporter.suggestName.
 */
object ReportFiles {

    fun reportsDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "NovaFiles-Reports",
    )

    /** يكتب تقريراً باسم مقترح — يعيد الملف عند النجاح أو null بصمت (لا استثناءات للواجهة) */
    fun write(kind: String, ext: String, content: String, nowMillis: Long = System.currentTimeMillis()): File? =
        runCatching {
            val dir = reportsDir()
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, com.novafiles.app.core.report.ReportExporter.suggestName(kind, ext, nowMillis))
            f.writeText(content, Charsets.UTF_8)
            f
        }.getOrNull()

    /** أحدث تقارير التنظيف المحفوظة — للعرض في المنظّف */
    fun latestCleanup(): File? =
        reportsDir().listFiles { f -> f.name.startsWith("nova-cleanup-") }
            ?.maxByOrNull { it.lastModified() }
}
