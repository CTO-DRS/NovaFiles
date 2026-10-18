package com.novafiles.app.core.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * ════════════════════════════════════════════════════════════════
 *  نقل QR — الجسر بين خادم FTP المدمج والجهاز الثاني
 *  ─ الرمز يحمل رابط ftp:// كاملاً مع بيانات الدخول
 *  ─ المولّد عبر ZXing core (خالص، بلا اعتماديات إضافية)
 *  ─ بناء الروابط دوال نقية قابلة للاختبار على JVM
 * ════════════════════════════════════════════════════════════════
 */

/** بناء روابط QR — دوال نقية بلا IO ولا Android */
object QrPayloads {

    /**
     * ترميز مكوّن URL: يترك الأحرف غير المحجوزة [A-Za-z0-9-._~]
     * ويحوّل كل ما عداه إلى ‎%XX بترميز UTF-8 (مطابق لـ RFC 3986).
     */
    fun percentEncode(raw: String): String {
        val sb = StringBuilder()
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append(String.format("%02X", c))
            }
        }
        return sb.toString()
    }

    /** رابط مشاركة الخادم كاملاً: ftp://user:pass@ip:port/ */
    fun ftpShareUrl(ip: String, port: Int, user: String, pass: String): String =
        "ftp://${percentEncode(user)}:${percentEncode(pass)}@$ip:$port/"

    /**
     * رابط مباشر لملف/مجلد داخل جذر الخادم:
     * rootPath مجذر الخادم المشترك وtargetPath المسار المطلوب الوصول إليه.
     * كل مقطع من المسار النسبي يُرمَّز على حدة لتبقى فواصل / سليمة.
     */
    fun ftpTargetUrl(ip: String, port: Int, user: String, pass: String, rootPath: String, targetPath: String): String {
        val root = rootPath.trimEnd('/')
        val rel = if (targetPath == root) {
            ""
        } else {
            targetPath.removePrefix(root).trimStart('/')
        }
        val encoded = rel.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { percentEncode(it) }
        return "ftp://${percentEncode(user)}:${percentEncode(pass)}@$ip:$port/" +
            (if (encoded.isEmpty()) "" else encoded + "/")
    }
}

/** توليد مصفوفة QR (قابلة للاختبار على JVM) وصورة Bitmap (أندرويد فقط) */
object QrCode {

    /** مصفوفة وحدات QR — نقية وتعمل على JVM مباشرة */
    fun matrix(content: String, size: Int = 512): com.google.zxing.common.BitMatrix {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
        )
        return QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    }

    /** صورة QR جاهزة للعرض في الواجهة — خلفية بيضاء ووحدات سوداء */
    fun bitmap(content: String, sizePx: Int = 720): Bitmap {
        val m = matrix(content, sizePx)
        val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565)
        for (x in 0 until m.width) {
            for (y in 0 until m.height) {
                bmp.setPixel(x, y, if (m.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
