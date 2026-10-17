package com.novafiles.app.core.fs

import java.io.File

/** تصنيفات الملفات المستخدمة في الأيقونات ومرشحات البحث الذكي */
enum class FileType {
    FOLDER, IMAGE, VIDEO, AUDIO, DOC, TEXT, CODE, ARCHIVE, APK, PDF, OTHER;

    companion object {
        private val imageExts = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg")
        private val videoExts = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts")
        private val audioExts = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "wma")
        private val archiveExts = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "apk.xz")
        private val docExts = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods")
        private val codeExts = setOf("kt", "java", "py", "js", "ts", "html", "css", "xml", "json",
            "c", "cpp", "h", "go", "rs", "sh", "yml", "yaml", "toml", "sql", "php", "rb", "swift")
        private val textExts = setOf("txt", "md", "log", "csv", "ini", "conf", "properties")

        fun of(file: File): FileType {
            if (file.isDirectory) return FOLDER
            val ext = file.extension.lowercase()
            return when {
                ext in imageExts -> IMAGE
                ext == "pdf" -> PDF
                ext in videoExts -> VIDEO
                ext in audioExts -> AUDIO
                ext in archiveExts -> ARCHIVE
                ext == "apk" -> APK
                ext in docExts -> DOC
                ext in codeExts -> CODE
                ext in textExts -> TEXT
                else -> OTHER
            }
        }

        /** تصنيف يُستخدم لمرشح البحث (يجمع TEXT وCODE وDOC معاً) */
        fun filterGroup(type: FileType, file: File): Boolean {
            val real = of(file)
            return when (type) {
                FOLDER -> real == FOLDER
                IMAGE, VIDEO, AUDIO, ARCHIVE, APK, PDF -> real == type
                DOC -> real == DOC || real == TEXT || real == CODE
                else -> true
            }
        }
    }
}
