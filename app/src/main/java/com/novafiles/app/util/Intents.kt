package com.novafiles.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.novafiles.app.core.fs.FileType
import java.io.File
import java.util.Locale

/** فتح الملفات ومشاركتها عبر تطبيقات أخرى (من خلال FileProvider للأمان) */
object Intents {

    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun mimeOf(file: File): String = when (FileType.of(file)) {
        FileType.IMAGE -> "image/*"
        FileType.VIDEO -> "video/*"
        FileType.AUDIO -> "audio/*"
        FileType.PDF -> "application/pdf"
        FileType.ARCHIVE -> "application/zip"
        FileType.APK -> "application/vnd.android.package-archive"
        FileType.TEXT, FileType.CODE -> "text/plain"
        FileType.DOC -> "application/msword"
        else -> "*/*"
    }

    /** هل يفتح هذا الملف في عارض NovaFiles الداخلي؟ */
    fun opensInternally(file: File): Boolean {
        val t = FileType.of(file)
        return t == FileType.IMAGE || t == FileType.TEXT || t == FileType.CODE
    }

    fun view(context: Context, file: File) {
        if (opensInternally(file)) return // تتم داخل التطبيق عبر التنقل
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri(context, file), mimeOf(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun share(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(file)
            putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, file.name).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /** إرسال الملف إلى VaultScreen للتشفير عبر مسار URI موثوق */
    fun mimeLabel(file: File): String =
        mimeOf(file).uppercase(Locale.getDefault())
}
