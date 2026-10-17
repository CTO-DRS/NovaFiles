package com.novafiles.app.core.fs

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** أوضاع الترتيب */
enum class SortMode(val key: String) { NAME("name"), SIZE("size"), DATE("date");

    companion object {
        fun fromKey(key: String?): SortMode = entries.firstOrNull { it.key == key } ?: NAME
    }
}

/** قلب التصفح المحلي: سرد وترتيب وتصنيف عناصر مجلد */
object LocalBrowser {

    fun list(dir: File, showHidden: Boolean, sort: SortMode): List<File> {
        val children = dir.listFiles() ?: return emptyList()
        return children
            .filter { showHidden || !it.name.startsWith(".") }
            .sortedWith(
                compareByDescending<File> { it.isDirectory }
                    .thenBy { f ->
                        when (sort) {
                            SortMode.NAME -> f.name.lowercase(Locale.getDefault())
                            SortMode.SIZE -> 0L // يُكمِل المقارنة بالأسفل
                            SortMode.DATE -> -f.lastModified() // الأحدث أولاً
                        }
                    }
                    .thenBy { f ->
                        when (sort) {
                            SortMode.SIZE -> -f.length()
                            else -> 0L
                        }
                    }
            )
    }

    fun itemCount(dir: File): Int = dir.listFiles()?.size ?: 0

    fun formatDate(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        return fmt.format(Date(millis))
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = -1
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024.0
            i++
        }
        return String.format(Locale.US, "%.1f %s", value, units[i])
    }
}
