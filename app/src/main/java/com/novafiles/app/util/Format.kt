package com.novafiles.app.util

import java.util.Locale

/** منسّق مشترك للأحجام — KB/MB/GB بأسلوب موحد عبر الشاشات */
object Format {

    fun bytes(b: Long): String {
        val abs = if (b < 0) -b else b
        return when {
            abs < 1024L -> "$b B"
            abs < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            abs < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
            abs < 1024L * 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
            else -> String.format(Locale.US, "%.2f TB", b / (1024.0 * 1024 * 1024 * 1024))
        }
    }
}
