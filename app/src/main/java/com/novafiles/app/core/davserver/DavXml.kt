package com.novafiles.app.core.davserver

/**
 * v0.27 — مولّد XML لاستجابات WebDAV خالص.
 * يبني multistatus 207 بنفس القواعد التي يستهلكها عميل NovaFiles نفسه
 * (DavParser) فتتوافق الشاشات معه، ويضمن الترميز الفعلي للأسماء العربية.
 */
object DavXml {

    /** عنصر مورد واحد داخل multistatus */
    data class Entry(
        /** المسار المنسّط (بدون بادئة /) — "" للجذر */
        val path: String,
        val isCollection: Boolean,
        val size: Long,
        val modifiedMillis: Long,
        val etag: String?,
        val contentType: String?,
    )

    /** يبني استجابة multistatus كاملة لمجموعة مداخل */
    fun multistatus(entries: List<Entry>, hrefPrefix: String = "/"): String {
        val sb = StringBuilder(entries.size * 160 + 64)
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<D:multistatus xmlns:D=\"DAV:\">")
        for (e in entries) {
            val href = hrefPrefix + DavPath.encodeHref(e.path) + if (e.isCollection && e.path.isNotEmpty()) "/" else ""
            sb.append("<D:response>")
            sb.append("<D:href>").append(xmlEscape(href)).append("</D:href>")
            sb.append("<D:propstat><D:prop>")
            sb.append("<D:displayname>").append(xmlEscape(DavPath.displayName(e.path))).append("</D:displayname>")
            if (e.isCollection) {
                sb.append("<D:resourcetype><D:collection/></D:resourcetype>")
            } else {
                sb.append("<D:resourcetype/>")
                sb.append("<D:getcontentlength>").append(e.size).append("</D:getcontentlength>")
                e.contentType?.let { sb.append("<D:getcontenttype>").append(xmlEscape(it)).append("</D:getcontenttype>") }
            }
            sb.append("<D:getlastmodified>").append(httpDate(e.modifiedMillis)).append("</D:getlastmodified>")
            e.etag?.let { sb.append("<D:getetag>").append(xmlEscape(it)).append("</D:getetag>") }
            sb.append("<D:supportedlock>")
            sb.append("<D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry>")
            sb.append("</D:supportedlock>")
            sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>")
            sb.append("</D:response>")
        }
        sb.append("</D:multistatus>")
        return sb.toString()
    }

    /**
     * استجابة LOCK ناجحة — lockdiscovery بقيد حصري write.
     * يتوقع معظم العملاء (Windows/Finder) هذه البنية وإلا رفض الكتابة.
     */
    fun lockResponse(token: String, owner: String?, timeoutSeconds: Long, depth: String): String {
        val sb = StringBuilder(320)
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>")
        sb.append("<D:locktype><D:write/></D:locktype>")
        sb.append("<D:lockscope><D:exclusive/></D:lockscope>")
        sb.append("<D:depth>").append(xmlEscape(depth)).append("</D:depth>")
        owner?.let { sb.append("<D:owner><D:href>").append(xmlEscape(it)).append("</D:href></D:owner>") }
        sb.append("<D:timeout>Second-").append(timeoutSeconds.coerceIn(1, 3600)).append("</D:timeout>")
        sb.append("<D:locktoken><D:href>").append(xmlEscape(token)).append("</D:href></D:locktoken>")
        sb.append("</D:activelock></D:lockdiscovery></D:prop>")
        return sb.toString()
    }
}

/**
 * فهرس HTML لمجلد — يخدم GET على مجموعة للمتصفحات العادية،
 * فيتصفح المستخدم التخزين من أي هاتف/حاسوب بمتصفح فقط دون عميل DAV.
 * كل الأسماء مهروبة XML وكل روابط href مُرمَّزة فعلياً.
 */
object HtmlIndex {

    data class Item(val name: String, val isDir: Boolean, val size: Long, val modifiedMillis: Long)

    fun render(rootPath: String, items: List<Item>): String {
        val sb = StringBuilder(items.size * 96 + 256)
        val title = xmlEscape(if (rootPath.isEmpty()) "/" else "/${DavPath.encodeHref(rootPath)}")
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>").append(title).append("</title>")
        sb.append("<style>body{font-family:-apple-system,sans-serif;background:#f2f2f7;margin:0;padding:16px}")
        sb.append("h1{font-size:20px}ul{list-style:none;padding:0}li{background:#fff;border-radius:12px;")
        sb.append("margin:6px 0;padding:12px 16px;display:flex;justify-content:space-between}")
        sb.append("a{text-decoration:none;color:#007aff;font-size:16px}span{color:#8e8e93;font-size:13px}</style>")
        sb.append("</head><body><h1>").append(title).append("</h1><ul>")
        for (it in items) {
            val href = DavPath.encodeHref(it.name) + if (it.isDir) "/" else ""
            val size = if (it.isDir) "—" else formatSize(it.size)
            sb.append("<li><a href=\"").append(xmlEscape(href)).append("\">")
            sb.append(if (it.isDir) "\uD83D\uDCC1 " else "\uD83D\uDCC4 ")
            sb.append(xmlEscape(it.name)).append("</a><span>").append(size).append("</span></li>")
        }
        sb.append("</ul><p style=\"color:#8e8e93;font-size:12px\">NovaFiles WebDAV</p></body></html>")
        return sb.toString()
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1 shl 30 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1e6)
        bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1e3)
        else -> "$bytes B"
    }
}
