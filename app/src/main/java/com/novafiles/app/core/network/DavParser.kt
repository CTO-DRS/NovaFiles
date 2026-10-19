package com.novafiles.app.core.network

import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.IOException
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.parsers.SAXParserFactory

/**
 * v0.13 — محلل ردود WebDAV (207 Multi-Status).
 *
 * SAX خالص من JDK — يعمل على الجهاز وفي اختبارات JVM بلا أي اعتماد إضافي.
 * صُمِّم ليتحمل اختلافات الخوادم (Apache/Nextcloud/ownCloud/IIS):
 *  - مساحات أسماء ببادئات مختلفة (d: أو D: أو بلا بادئة) — نطابق الاسم المحلي فقط
 *  - href قد يكون مساراً مطلقاً أو رابطاً كاملاً — نستخرج المسار ونفكّ الترميز
 *  - التاريخ RFC 1123 (HTTP-date) أو ISO 8601 — نجرّب الاثنين وإلا 0
 *  - خصائص ناقصة (بلا حجم أو تاريخ) — قيم افتراضية آمنة
 */

/** مورد WebDAV كما ورد في رد PROPFIND */
data class DavResource(
    /** مسار العنصر بعد فكّ ترميز href (يبدأ بـ «/» وينتهي بلا «/») */
    val path: String,
    val displayName: String,
    val isCollection: Boolean,
    val contentLength: Long,
    val lastModified: Long,
    val etag: String? = null,
)

object DavParser {

    /** يحلل جسم 207 متعدد الحالات ويعيد كل الموارد بالترتيب الوارد */
    fun parseMultistatus(input: InputStream): List<DavResource> {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        // لا نحمّل أي كيانات خارجية — حماية من XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        try {
            val parser = factory.newSAXParser()
            val handler = MultistatusHandler()
            parser.parse(input, handler)
            return handler.resources
        } catch (e: SAXException) {
            throw IOException("bad multistatus xml: ${e.message}", e)
        }
    }

    /** يفكّ ترميز %XX في مسار فقط (لا نحوّل + إلى مسافة — هذا سلوك URLs لا المسارات) */
    fun percentDecode(raw: String): String {
        if ('%' !in raw) return raw
        val bytes = ArrayList<Byte>(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 2 < raw.length) {
                val code = raw.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    bytes.add(code.toByte())
                    i += 3
                    continue
                }
            }
            // حرف حرفي — أضف بايتات UTF-8 الخاصة به (يدعم نصاً مختلطاً مرمزاً وغير مرمز)
            for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes.add(b)
            i++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    /** يفكّ href إلى مسار داخلي نظيف (يزيل مخطط/مضيف إن وجدت، ويزيل «/» الزائدة) */
    fun hrefToPath(href: String): String {
        var h = href.trim()
        // أزل المخطط والمضيف إن كان href رابطاً كاملاً
        val schemeEnd = h.indexOf("://")
        if (schemeEnd > 0) {
            val slash = h.indexOf('/', schemeEnd + 3)
            h = if (slash >= 0) h.substring(slash) else "/"
        }
        // فك ترميز كل مقطع على حدة حتى لا يفسد مقطع يحوي «/» مرمزاً (%2F)
        val decoded = h.split('/').joinToString("/") { seg ->
            if (seg.isEmpty()) "" else percentDecode(seg)
        }
        return "/" + decoded.trimStart('/').trimEnd('/')
    }

    /** يحلل تاريخ WebDAV: RFC 1123 أولاً ثم ISO 8601 وإلا 0 */
    fun parseDate(raw: String?): Long {
        val s = raw?.trim() ?: return 0L
        if (s.isEmpty()) return 0L
        try {
            return OffsetDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) { }
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) { }
        return 0L
    }
}

/** معالج SAX يجمع عناصر response ويستخرج خصائص DAV الأساسية */
private class MultistatusHandler : DefaultHandler() {

    val resources = ArrayList<DavResource>()

    private var inResponse = false
    private var inProp = false
    private var currentHref: String? = null
    private var displayName: String? = null
    private var isCollection = false
    private var length = 0L
    private var modified = 0L
    private var etag: String? = null
    private var text = StringBuilder()
    /** الاسم المحلي للعنصر النصي الحالي داخل prop (fetchcontentlength …) */
    private var propTextTag: String? = null

    override fun startElement(uri: String?, localName: String, qName: String, attrs: Attributes?) {
        val ln = localName.lowercase()
        when {
            ln == "response" && inResponse.not() -> {
                inResponse = true
                currentHref = null; displayName = null; isCollection = false
                length = 0L; modified = 0L; etag = null
            }
            ln == "propstat" && inResponse -> inProp = true
            ln == "prop" && inProp -> { /* نبدأ جمع الخصائص */ }
            ln == "collection" && inProp -> isCollection = true
            ln == "href" && inResponse -> { text = StringBuilder(); propTextTag = "href" }
            inProp && inResponse -> {
                text = StringBuilder()
                propTextTag = when (ln) {
                    "displayname" -> "displayname"
                    "getcontentlength" -> "getcontentlength"
                    "getlastmodified" -> "getlastmodified"
                    "getetag" -> "getetag"
                    "modificationdate" -> "getlastmodified" // بعض الخوادم ترسل ISO هنا
                    else -> null
                }
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (propTextTag != null) text.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String, qName: String?) {
        val ln = localName.lowercase()
        val value = text.toString().trim()
        when {
            ln == "href" && propTextTag == "href" -> {
                currentHref = DavParser.hrefToPath(value)
                propTextTag = null
            }
            ln == "prop" && inProp -> inProp = false
            ln == "propstat" && inResponse -> inProp = false
            inProp && propTextTag != null -> {
                when (propTextTag) {
                    "displayname" -> if (displayName == null && value.isNotEmpty()) displayName = value
                    "getcontentlength" -> length = value.toLongOrNull() ?: 0L
                    "getlastmodified" -> if (modified == 0L) modified = DavParser.parseDate(value)
                    "getetag" -> if (etag == null && value.isNotEmpty()) etag = value
                }
                propTextTag = null
            }
            ln == "response" && inResponse -> {
                val href = currentHref
                if (href != null) {
                    resources += DavResource(
                        path = href,
                        displayName = displayName ?: href.substringAfterLast('/'),
                        isCollection = isCollection,
                        contentLength = length,
                        lastModified = modified,
                        etag = etag,
                    )
                }
                inResponse = false
            }
        }
    }
}
