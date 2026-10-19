package com.novafiles.app.core.assoc

/**
 * v0.17 — خرائط MIME الشاملة (ملحق → نوع MIME → فئة ودّية).
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 * يغذي: فلاتر «فتح باستخدام»، مرشّحات intent-filter، وتصنيف العارض.
 */
object MimeResolver {

    private val EXT_TO_MIME: Map<String, String> = buildMap {
        // نصوص وكود
        put("txt", "text/plain"); put("text", "text/plain"); put("log", "text/plain")
        put("csv", "text/csv"); put("tsv", "text/tab-separated-values")
        put("md", "text/markdown"); put("markdown", "text/markdown")
        put("html", "text/html"); put("htm", "text/html")
        put("css", "text/css"); put("xml", "text/xml"); put("svg", "image/svg+xml")
        put("kt", "text/x-kotlin"); put("java", "text/x-java-source")
        put("py", "text/x-python"); put("js", "text/javascript"); put("ts", "text/typescript")
        put("json", "application/json"); put("yaml", "text/yaml"); put("yml", "text/yaml")
        put("toml", "text/plain"); put("ini", "text/plain"); put("conf", "text/plain")
        put("properties", "text/plain"); put("sh", "application/x-sh"); put("bat", "text/plain")
        put("sql", "application/sql"); put("php", "application/x-httpd-php")
        put("c", "text/x-c"); put("h", "text/x-c"); put("cpp", "text/x-c++"); put("hpp", "text/x-c++")
        put("go", "text/x-go"); put("rs", "text/x-rust"); put("swift", "text/x-swift")
        put("rb", "text/x-ruby"); put("cs", "text/x-csharp")
        // صور
        put("jpg", "image/jpeg"); put("jpeg", "image/jpeg"); put("png", "image/png")
        put("gif", "image/gif"); put("webp", "image/webp"); put("bmp", "image/bmp")
        put("heic", "image/heic"); put("heif", "image/heif"); put("ico", "image/x-icon")
        put("tiff", "image/tiff"); put("tif", "image/tiff"); put("avif", "image/avif")
        // صوت
        put("mp3", "audio/mpeg"); put("flac", "audio/flac"); put("wav", "audio/x-wav")
        put("ogg", "audio/ogg"); put("oga", "audio/ogg"); put("m4a", "audio/mp4")
        put("aac", "audio/aac"); put("opus", "audio/opus"); put("wma", "audio/x-ms-wma")
        put("mid", "audio/midi"); put("midi", "audio/midi"); put("amr", "audio/amr")
        // فيديو
        put("mp4", "video/mp4"); put("m4v", "video/mp4"); put("mkv", "video/x-matroska")
        put("avi", "video/x-msvideo"); put("mov", "video/quicktime"); put("webm", "video/webm")
        put("3gp", "video/3gpp"); put("ts", "video/mp2t"); put("wmv", "video/x-ms-wmv")
        put("flv", "video/x-flv")
        // مستندات
        put("pdf", "application/pdf")
        put("doc", "application/msword")
        put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        put("xls", "application/vnd.ms-excel")
        put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        put("ppt", "application/vnd.ms-powerpoint")
        put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation")
        put("odt", "application/vnd.oasis.opendocument.text")
        put("ods", "application/vnd.oasis.opendocument.spreadsheet")
        put("odp", "application/vnd.oasis.opendocument.presentation")
        put("epub", "application/epub+zip")
        // أرشيفات
        put("zip", "application/zip"); put("7z", "application/x-7z-compressed")
        put("rar", "application/x-rar-compressed"); put("tar", "application/x-tar")
        put("gz", "application/gzip"); put("tgz", "application/gzip")
        put("bz2", "application/x-bzip2"); put("xz", "application/x-xz")
        put("iso", "application/x-iso9660-image")
        // تطبيقات وثنائيات
        put("apk", "application/vnd.android.package-archive")
        put("jar", "application/java-archive")
        put("dex", "application/vnd.android.dex")
        // خطوط
        put("ttf", "font/ttf"); put("otf", "font/otf"); put("woff", "font/woff"); put("woff2", "font/woff2")
    }

    private val TYPE_NAMES: Map<String, Pair<String, String>> = mapOf(
        "text/" to Pair("نص", "Text"),
        "image/" to Pair("صورة", "Image"),
        "audio/" to Pair("صوت", "Audio"),
        "video/" to Pair("فيديو", "Video"),
        "application/pdf" to Pair("مستند PDF", "PDF document"),
        "application/zip" to Pair("أرشيف ZIP", "ZIP archive"),
        "application/x-7z-compressed" to Pair("أرشيف 7z", "7z archive"),
        "application/x-rar-compressed" to Pair("أرشيف RAR", "RAR archive"),
        "application/gzip" to Pair("أرشيف GZip", "GZip archive"),
        "application/x-tar" to Pair("أرشيف TAR", "TAR archive"),
        "application/x-bzip2" to Pair("أرشيف BZip2", "BZip2 archive"),
        "application/x-xz" to Pair("أرشيف XZ", "XZ archive"),
        "application/vnd.android.package-archive" to Pair("حزمة أندرويد", "Android package"),
        "application/msword" to Pair("مستند Word", "Word document"),
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to Pair("مستند Word", "Word document"),
        "application/vnd.ms-excel" to Pair("جدول Excel", "Excel sheet"),
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to Pair("جدول Excel", "Excel sheet"),
        "application/vnd.ms-powerpoint" to Pair("عرض PowerPoint", "PowerPoint deck"),
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to Pair("عرض PowerPoint", "PowerPoint deck"),
        "application/epub+zip" to Pair("كتاب إلكتروني", "E-book"),
        "font/" to Pair("خط", "Font"),
        "application/" to Pair("ملف تطبيق", "Application file"),
    )

    /** ملحق → MIME (أحرف صغيرة دائماً) — افتراضي application/octet-stream */
    fun fromExtension(fileNameOrExt: String): String {
        val ext = fileNameOrExt.substringAfterLast('.', "").lowercase()
        return EXT_TO_MIME[ext] ?: "application/octet-stream"
    }

    /** الفئة العليا: نص/صورة/صوت/فيديو/أرشيف/تطبيق/مستند */
    fun category(mime: String): String = when {
        mime.startsWith("text/") -> "text"
        mime.startsWith("image/") -> "image"
        mime.startsWith("audio/") -> "audio"
        mime.startsWith("video/") -> "video"
        mime in setOf(
            "application/zip", "application/x-7z-compressed", "application/x-rar-compressed",
            "application/x-tar", "application/gzip", "application/x-bzip2", "application/x-xz",
            "application/x-iso9660-image",
        ) -> "archive"
        mime == "application/vnd.android.package-archive" || mime == "application/java-archive" -> "app"
        mime in setOf(
            "application/pdf", "application/msword", "application/epub+zip",
            "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
        ) -> "document"
        mime.startsWith("font/") -> "font"
        else -> "other"
    }

    /** اسم ودّي بالعربية أو الإنجليزية */
    fun friendly(mime: String, english: Boolean = false): String {
        val hit = TYPE_NAMES.entries.firstOrNull { mime.startsWith(it.key) }
        return hit?.value?.let { if (english) it.second else it.first }
            ?: if (english) "File" else "ملف"
    }

    /** كل الامتدادات المعروفة لمجموعة MIME معينة — لبناء intent-filter ووثائق الإتاحة */
    fun extensionsOf(mimePrefix: String): List<String> =
        EXT_TO_MIME.filterValues { it.startsWith(mimePrefix) }.keys.sorted()
}
