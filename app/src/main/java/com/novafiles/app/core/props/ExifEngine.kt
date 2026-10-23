package com.novafiles.app.core.props

import kotlin.math.abs

/**
 * v0.36.0 — محرك EXIF الخالص: قراءة بيانات الصور الوصفية من JPEG بلا أي اعتماد أندرويد.
 *
 * لماذا خالص؟ androidx.exifinterface غير قابل للاختبار على JVM — قاعدة الحوكمة
 * تفرض كل منطق محرك خالص 100% مُختبَراً. هذا المحلل يقرأ بنية الملف بايت-ببايت:
 * قطاعات JPEG (FF E1 = APP1) ثم رأس TIFF داخلها: IFD0 → Exif SubIFD → GPS IFD،
 * مع دعم كامل للترتيب الصغير II والكبير MM، والقيم المضمّنة (≤4 بايت) والمُؤشَّرة.
 *
 * التسامح: ملف غير JPEG → null؛ EXIF ناقص/تالف → يُعاد ما قُرئ فقط ولا يُرمى
 * استثناء أبداً؛ قطاعات تالفة → تُتخطى. الحدود: أقصى 64 قطاعاً وأقصى 512 مدخلاً
 * لكل IFD (حماية من الملفات المعادية).
 */
object ExifEngine {

    /** معلومات الصورة المُستخرجة — كل حقل اختياري (قد يغيب من الكاميرا أو الملف) */
    data class ExifInfo(
        val cameraMake: String? = null,
        val cameraModel: String? = null,
        val dateTime: String? = null,
        val takenAt: String? = null,
        val exposureTimeSec: Double? = null,
        val fNumber: Double? = null,
        val iso: Int? = null,
        val focalLengthMm: Double? = null,
        val orientation: Int? = null,
        val width: Int? = null,
        val height: Int? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
        val altitudeM: Double? = null,
        /** توازن الأبيض: 0 تلقائي، 1 يدوي */
        val whiteBalance: Int? = null,
    ) {
        val hasGps: Boolean get() = latitude != null && longitude != null
        val hasAny: Boolean
            get() = cameraMake != null || cameraModel != null || dateTime != null ||
                takenAt != null || exposureTimeSec != null || fNumber != null ||
                iso != null || focalLengthMm != null || orientation != null ||
                width != null || height != null || hasGps || altitudeM != null ||
                whiteBalance != null
    }

    // ─────────────────────────── الواجهة ───────────────────────────

    /** يحلل بايتات ملف — يعيد null إذا لم يكن JPEG أو بلا EXIF، ولا يرمي أبداً */
    fun parse(bytes: ByteArray): ExifInfo? {
        if (bytes.size < 4) return null
        if ((bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) return null

        var i = 2
        var segments = 0
        while (i + 4 <= bytes.size && segments < MAX_SEGMENTS) {
            // كل قطاع يبدأ بـ 0xFF — متسامح مع حشو 0xFF المتكرر
            if ((bytes[i].toInt() and 0xFF) != 0xFF) return null
            var j = i + 1
            while (j < bytes.size && (bytes[j].toInt() and 0xFF) == 0xFF) j++
            if (j >= bytes.size) return null
            val marker = bytes[j].toInt() and 0xFF
            val lenPos = j + 1
            if (marker == 0xD9 || marker == 0xDA) break // EOI أو SOS — انتهت الرؤوس
            if (lenPos + 2 > bytes.size) return null
            val len = u16(bytes, lenPos)
            if (len < 2) return null
            val segStart = lenPos + 2
            val segEndSafe = minOf(segStart + len - 2, bytes.size)
            if (marker == 0xE1 && segStart + 6 <= bytes.size) {
                // APP1: يجب أن يبدأ بـ "Exif\0\0"
                if (bytes[segStart] == 'E'.code.toByte() && bytes[segStart + 1] == 'x'.code.toByte() &&
                    bytes[segStart + 2] == 'i'.code.toByte() && bytes[segStart + 3] == 'f'.code.toByte() &&
                    bytes[segStart + 4].toInt() == 0 && bytes[segStart + 5].toInt() == 0
                ) {
                    val tiff = segStart + 6
                    val tiffLen = segEndSafe - tiff
                    if (tiffLen > 8) {
                        val info = parseTiff(bytes, tiff, tiffLen)
                        if (info != null) return info
                    }
                }
            }
            i = segStart + len - 2
            segments++
        }
        return null
    }

    // ─────────────────────────── TIFF/IFD ───────────────────────────

    private fun parseTiff(b: ByteArray, base: Int, len: Int): ExifInfo? {
        if (len < 8) return null
        val big = when {
            b[base] == 'I'.code.toByte() && b[base + 1] == 'I'.code.toByte() -> false
            b[base] == 'M'.code.toByte() && b[base + 1] == 'M'.code.toByte() -> true
            else -> return null
        }
        val magic = if (big) u16at(b, base + 2, true) ?: 0 else u16at(b, base + 2, false) ?: 0
        if (magic != 42) return null
        val ifd0Off = u32(b, base + 4, big).toInt()
        if (ifd0Off <= 0 || ifd0Off >= len) return null

        var make: String? = null; var model: String? = null; var dateTime: String? = null
        var orientation: Int? = null; var width: Int? = null; var height: Int? = null
        var exifIfd = -1; var gpsIfd = -1

        val ifd0 = base + ifd0Off
        val n0 = ifdCount(b, ifd0, base, len, big) ?: return null
        for (e in 0 until n0) {
            val ep = entryPos(ifd0, e)
            val tag = u16at(b, ep, big) ?: break
            val f = entryFields(b, ep, base, big, len) ?: break
            when (tag) {
                TAG_MAKE -> make = ascii(b, f.type, f.count, f.valuePos)
                TAG_MODEL -> model = ascii(b, f.type, f.count, f.valuePos)
                TAG_DATETIME -> dateTime = ascii(b, f.type, f.count, f.valuePos)
                TAG_ORIENTATION -> orientation = firstInt(b, f, big)
                TAG_WIDTH -> width = firstInt(b, f, big)
                TAG_HEIGHT -> height = firstInt(b, f, big)
                TAG_EXIF_IFD -> exifIfd = firstInt(b, f, big) ?: -1
                TAG_GPS_IFD -> gpsIfd = firstInt(b, f, big) ?: -1
            }
        }

        var takenAt: String? = null; var exposure: Double? = null; var fNum: Double? = null
        var iso: Int? = null; var focal: Double? = null; var exifW: Int? = null; var exifH: Int? = null
        var whiteBalance: Int? = null
        if (exifIfd > 0) {
            val ifd = base + exifIfd
            val n = ifdCount(b, ifd, base, len, big) ?: 0
            for (e in 0 until n) {
                val ep = entryPos(ifd, e)
                val tag = u16at(b, ep, big) ?: break
                val f = entryFields(b, ep, base, big, len) ?: break
                when (tag) {
                    TAG_EXPOSURE -> exposure = firstRational(b, f, big)
                    TAG_FNUMBER -> fNum = firstRational(b, f, big)
                    TAG_ISO -> iso = firstInt(b, f, big)
                    TAG_DATETIME_ORIGINAL -> takenAt = ascii(b, f.type, f.count, f.valuePos)
                    TAG_FOCAL -> focal = firstRational(b, f, big)
                    TAG_EXIF_W -> exifW = firstInt(b, f, big)
                    TAG_EXIF_H -> exifH = firstInt(b, f, big)
                    TAG_WHITE_BALANCE -> whiteBalance = firstInt(b, f, big)
                }
            }
        }

        var lat: Double? = null; var lon: Double? = null; var alt: Double? = null
        if (gpsIfd > 0) {
            val ifd = base + gpsIfd
            val n = ifdCount(b, ifd, base, len, big) ?: 0
            var latRef: String? = null; var lonRef: String? = null
            var latDMS: DoubleArray? = null; var lonDMS: DoubleArray? = null
            var altRef = 0.0
            for (e in 0 until n) {
                val ep = entryPos(ifd, e)
                val tag = u16at(b, ep, big) ?: break
                val f = entryFields(b, ep, base, big, len) ?: break
                when (tag) {
                    TAG_GPS_LAT_REF -> latRef = ascii(b, f.type, f.count, f.valuePos)
                    TAG_GPS_LAT -> latDMS = rationals(b, f, big)
                    TAG_GPS_LON_REF -> lonRef = ascii(b, f.type, f.count, f.valuePos)
                    TAG_GPS_LON -> lonDMS = rationals(b, f, big)
                    // altitudeRef بايت (نوع 1) أو SHORT (3) بحسب الكاميرا — كلاهما 0/1
                    TAG_GPS_ALT_REF ->
                        altRef = firstInt(b, f, big)?.toDouble() ?: firstRational(b, f, big) ?: 0.0
                    TAG_GPS_ALT -> alt = firstRational(b, f, big)
                }
            }
            latDMS?.let { if (it.size >= 3) lat = dmsToDeg(it[0], it[1], it[2], latRef) }
            lonDMS?.let { if (it.size >= 3) lon = dmsToDeg(it[0], it[1], it[2], lonRef) }
            if (alt != null && altRef != 0.0) alt = -alt
        }

        val info = ExifInfo(
            cameraMake = clean(make), cameraModel = clean(model),
            dateTime = clean(dateTime), takenAt = clean(takenAt),
            exposureTimeSec = exposure?.takeIf { it > 0 },
            fNumber = fNum?.takeIf { it > 0 },
            iso = iso?.takeIf { it > 0 },
            focalLengthMm = focal?.takeIf { it > 0 },
            orientation = orientation?.takeIf { it in 1..8 },
            width = width ?: exifW,
            height = height ?: exifH,
            latitude = lat, longitude = lon, altitudeM = alt,
            whiteBalance = whiteBalance?.takeIf { it == 0 || it == 1 },
        )
        return if (info.hasAny) info else null
    }

    private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

    private fun dmsToDeg(d: Double, m: Double, s: Double, ref: String?): Double {
        var deg = d + m / 60.0 + s / 3600.0
        val r = ref?.trim()?.uppercase()
        if (r == "S" || r == "W") deg = -deg
        return deg
    }

    // ─────────────────────────── عناصر IFD ───────────────────────────

    private fun ifdCount(b: ByteArray, ifd: Int, base: Int, len: Int, big: Boolean): Int? {
        if (ifd + 2 > base + len) return null
        val n = u16at(b, ifd, big) ?: return null
        if (n < 0 || n > MAX_IFD_ENTRIES) return null
        return n
    }

    private fun entryPos(ifd: Int, index: Int) = ifd + 2 + index * 12

    /** حقول مدخل: النوع، العدد، موضع القيمة (مضمّنة في المدخل نفسه أو بإزاحة) */
    private data class Entry(val type: Int, val count: Int, val valuePos: Int)

    private fun entryFields(b: ByteArray, ep: Int, base: Int, big: Boolean, len: Int): Entry? {
        val type = u16at(b, ep + 2, big) ?: return null
        val count = u32(b, ep + 4, big).toInt()
        if (type < 1 || type > 12 || count <= 0 || count > MAX_ENTRY_COUNT) return null
        val unit = typeSize(type) ?: return null
        val total = unit * count
        if (total <= 4) return Entry(type, count, ep + 8) // القيمة مضمّنة داخل المدخل
        val off = u32(b, ep + 8, big).toInt()
        if (off < 0 || off >= len) return null
        return Entry(type, count, base + off) // الإزاحة نسبية لبداية رأس TIFF
    }

    private fun typeSize(t: Int): Int? = when (t) {
        1, 2, 6, 7 -> 1       // BYTE, ASCII, SBYTE, UNDEFINED
        3 -> 2                // SHORT
        4, 9 -> 4             // LONG, SLONG
        5, 10 -> 8            // RATIONAL, SRATIONAL
        else -> null          // FLOAT/DOUBLE نادرة في EXIF الأساسي — تُتجاهل بأمان
    }

    private fun ascii(b: ByteArray, type: Int, count: Int, vPos: Int): String? {
        if (type != 2) return null
        val maxLen = minOf(count, b.size - vPos)
        if (maxLen <= 0) return null
        val sb = StringBuilder()
        for (k in 0 until maxLen) {
            val c = b[vPos + k].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun firstInt(b: ByteArray, f: Entry, big: Boolean): Int? = when (f.type) {
        3 -> u16at(b, f.valuePos, big)
        4 -> u32(b, f.valuePos, big).takeIf { it <= Int.MAX_VALUE.toLong() }?.toInt()
        else -> null
    }

    private fun firstRational(b: ByteArray, f: Entry, big: Boolean): Double? = when (f.type) {
        5, 10 -> {
            val num = u32(b, f.valuePos, big)
            val den = u32(b, f.valuePos + 4, big)
            if (den != 0L && num in 0..(1L shl 52)) num.toDouble() / den.toDouble() else null
        }
        else -> null
    }

    private fun rationals(b: ByteArray, f: Entry, big: Boolean): DoubleArray? {
        if (f.type != 5 && f.type != 10) return null
        val n = minOf(f.count, 8)
        val out = DoubleArray(n)
        for (k in 0 until n) {
            val p = f.valuePos + k * 8
            val num = u32(b, p, big)
            val den = u32(b, p + 4, big)
            out[k] = if (den != 0L) num.toDouble() / den.toDouble() else 0.0
        }
        return out
    }

    // ─────────────────────────── قراءة البايتات ───────────────────────────

    private fun u16at(b: ByteArray, p: Int, big: Boolean): Int? {
        if (p < 0 || p + 2 > b.size) return null
        return if (big) {
            ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        } else {
            (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
        }
    }

    private fun u16(b: ByteArray, p: Int): Int = u16at(b, p, false) ?: 0

    private fun u32(b: ByteArray, p: Int, big: Boolean): Long {
        if (p < 0 || p + 4 > b.size) return 0
        return if (big) {
            ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
                ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
        } else {
            ((b[p + 3].toLong() and 0xFF) shl 24) or ((b[p + 2].toLong() and 0xFF) shl 16) or
                ((b[p + 1].toLong() and 0xFF) shl 8) or (b[p].toLong() and 0xFF)
        }
    }

    // ─────────────────────────── الوسوم ───────────────────────────

    private const val TAG_MAKE = 0x010F
    private const val TAG_MODEL = 0x0110
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_DATETIME = 0x0132
    private const val TAG_WIDTH = 0x0100
    private const val TAG_HEIGHT = 0x0101
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_GPS_IFD = 0x8825
    private const val TAG_EXPOSURE = 0x829A
    private const val TAG_FNUMBER = 0x829D
    private const val TAG_ISO = 0x8827
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_FOCAL = 0x920A
    private const val TAG_EXIF_W = 0xA002
    private const val TAG_EXIF_H = 0xA003
    private const val TAG_WHITE_BALANCE = 0xA403
    private const val TAG_GPS_LAT_REF = 0x0001
    private const val TAG_GPS_LAT = 0x0002
    private const val TAG_GPS_LON_REF = 0x0003
    private const val TAG_GPS_LON = 0x0004
    private const val TAG_GPS_ALT_REF = 0x0005
    private const val TAG_GPS_ALT = 0x0006

    private const val MAX_SEGMENTS = 64
    private const val MAX_IFD_ENTRIES = 512
    private const val MAX_ENTRY_COUNT = 1024

    // ─────────────────────────── النصوص الودّية ───────────────────────────

    /**
     * زمن التعريض بأسلوب الكاميرات: ‎1/125 s‎ أو ‎2.5 s‎ — null عند غيابه.
     * يُقرَّب إلى أقرب مقام قياسي (ضمن 5%) لعرض أنظف.
     */
    fun friendlyExposure(sec: Double): String? {
        if (sec <= 0) return null
        if (sec >= 1) {
            return if (sec == sec.toLong().toDouble()) "${sec.toLong()} s"
            else String.format("%.1f s", sec)
        }
        val standard = intArrayOf(
            8000, 6400, 4000, 3200, 2000, 1600, 1000, 800, 500, 400,
            250, 200, 125, 100, 60, 50, 30, 25, 15, 13, 10, 8, 6, 4, 3, 2,
        )
        for (d in standard) {
            if (abs(sec - 1.0 / d) / sec < 0.05) return "1/$d s"
        }
        return String.format("1/%d s", (1.0 / sec).toInt().coerceAtLeast(1))
    }

    /** إحداثيات بنمط درجات عشرية مع اتجاه: ‎24.7136° N, 46.6753° E‎ */
    fun friendlyGps(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        val latC = if (lat >= 0) "N" else "S"
        val lonC = if (lon >= 0) "E" else "W"
        return String.format("%.4f° %s, %.4f° %s", abs(lat), latC, abs(lon), lonC)
    }
}
