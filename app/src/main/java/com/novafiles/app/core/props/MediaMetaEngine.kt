package com.novafiles.app.core.props

/**
 * v0.36.0 — محرك ميتاداتا الوسائط الخالص: مدة/ترميز/دقة الصوت والفيديو بلا اعتماديات.
 *
 * يدعم حاويتين بقراءة بايتات مباشرة (كلها قابلة للاختبار JVM على مصفوفات مُصنَّعة):
 * 1. **MP4/MOV/M4A**: صناديق ISOBMFF — ftyp (الماركة) وmoov→mvhd (مقياس الزمن
 *    والمدة، نسختا v0 وv1) وtrak→tkhd (الأبعاد 16.16) وstsd (fourcc الترميز)
 *    ومصدر الصوت mp4a→esds غير مطلوب؛ sampleRate من mvhd لا يكفي — يُقرأ من
 *    صندوق mp4a (بعد stsd). مسار واحد يكفي (أول video ثم أول audio).
 * 2. **MP3**: رأس ID3v2 (v2.3 وv2.4) بترميزات لاتيني-1/UTF-16/UTF-16BE/UTF-8 —
 *    الأسماء العربية تنجو سليمة — مع تقدير المدة من أول إطار MPEG (تقدير
 *    حتمي بسرعة البت، معلَن كتقدير لا قياس).
 *
 * التسامح: ملف غير معروف → container=null بلا استثناء أبداً؛ صناديق تالفة تُتخطى.
 */
object MediaMetaEngine {

    /** الحقول var لأنها تُملأ أثناء التجول في الصناديق/الإطارات ثم تُجمَّد */
    data class MediaMeta(
        var container: String? = null,      // "mp4" | "mov" | "m4a" | "mp3"
        var majorBrand: String? = null,     // ماركة ftyp مثل "isom"
        var durationSec: Double? = null,
        var durationEstimated: Boolean = false, // MP3 بتقدير سرعة البت
        var width: Int? = null,
        var height: Int? = null,
        var codec: String? = null,          // fourcc خام: avc1 / hvc1 / mp4a
        var title: String? = null,
        var artist: String? = null,
        var album: String? = null,
        var year: String? = null,
        var sampleRateHz: Int? = null,
        var bitrateKbps: Int? = null,
    ) {
        val hasAny: Boolean
            get() = container != null || title != null || artist != null || album != null ||
                durationSec != null || width != null || codec != null
    }

    // ─────────────────────────── الواجهة ───────────────────────────

    /**
     * يحلل بايتات ملف. totalSizeOverride: حجم الملف الحقيقي عندما تُقرأ بايتات
     * مقصوصة (رأس الملف فقط) — يستخدم لتقدير مدة MP3 من سرعة البت بدل حجم
     * المصفوفة المقصوصة. 0 = المصفوفة هي الملف كاملاً.
     */
    fun parse(bytes: ByteArray, totalSizeOverride: Long = 0L): MediaMeta {
        if (bytes.size < 12) return MediaMeta()
        return when {
            isMp4(bytes) -> parseMp4(bytes)
            isMp3(bytes) || looksLikeMp3WithId3(bytes) -> parseMp3(bytes, totalSizeOverride)
            else -> MediaMeta()
        }
    }

    // ─────────────────────────── MP4 / ISOBMFF ───────────────────────────

    private fun isMp4(b: ByteArray): Boolean =
        b.size >= 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
            b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()

    private fun parseMp4(b: ByteArray): MediaMeta {
        val out = MediaMeta()
        // ftyp: الحجم في 0..3 ثم "ftyp" ثم الماركة 4 أحرف
        out.container = "mp4"
        if (b.size >= 12) out.majorBrand = fourcc(b, 8)

        // التجول التكراري في الصناديق العليا — نبحث عن moov
        var pos = 0
        var boxes = 0
        while (pos + 8 <= b.size && boxes < MAX_TOP_BOXES) {
            val size = u32(b, pos)
            if (size < 8) break // تالف أو صندوق إلى النهاية (64-bit) — نتوقف بأمان
            val type = fourcc(b, pos + 4) ?: break
            if (type == "moov") {
                walkMoov(b, pos + 8, pos + size, out)
                break
            }
            pos += size
            boxes++
        }
        return out
    }

    private fun walkMoov(b: ByteArray, start: Int, end: Int, out: MediaMeta) {
        var pos = start
        var haveVideoTrack = false
        var haveAudioTrack = false
        while (pos + 8 <= end && pos + 8 <= b.size) {
            val size = u32(b, pos)
            if (size < 8 || pos + size > end) break
            val type = fourcc(b, pos + 4) ?: break
            when (type) {
                "mvhd" -> out.durationSec = parseMvhd(b, pos, size)
                "trak" -> {
                    val isVideo = trackIsVideo(b, pos + 8, pos + size)
                    val isAudio = !isVideo && trackIsAudio(b, pos + 8, pos + size)
                    when {
                        // الفيديو أولوية: أبعاده وترميزه يفوزان على أي مسار لاحق
                        isVideo && !haveVideoTrack -> {
                            haveVideoTrack = true
                            trakDims(b, pos + 8, pos + size)?.let { (w, h) ->
                                out.width = w; out.height = h
                            }
                            trakCodec(b, pos + 8, pos + size)?.let { out.codec = it }
                        }
                        // الصوت: ترميزه يُستخدم فقط إن لم يوجد ترميز فيديو
                        isAudio && !haveAudioTrack -> {
                            haveAudioTrack = true
                            if (out.codec == null) {
                                trakCodec(b, pos + 8, pos + size)?.let { out.codec = it }
                            }
                            trakSampleRate(b, pos + 8, pos + size)?.let { out.sampleRateHz = it }
                        }
                    }
                }
            }
            pos += size
        }
    }

    /** mvhd: v0 (timescale@12, duration@16 بعد رأس 4+4+8) — v1 (بعد 4+4+16، قيم 8 بايت) */
    private fun parseMvhd(b: ByteArray, boxPos: Int, boxSize: Int): Double? {
        val p = boxPos + 8 // بعد size+type
        if (p + 20 > b.size || boxSize < 24) return null
        val version = b[p].toInt() and 0xFF
        return if (version == 1) {
            if (p + 28 > b.size) return null
            val timescale = u32(b, p + 20)
            val duration = u64(b, p + 24)
            if (timescale > 0 && duration in 0..(1L shl 48)) duration.toDouble() / timescale else null
        } else {
            if (p + 20 > b.size) return null
            val timescale = u32(b, p + 12)
            val duration = u32(b, p + 16)
            if (timescale > 0) duration.toDouble() / timescale else null
        }
    }

    /** هل هذا المسار فيديو؟ يفحص hdlr في mdia (handler = vide) */
    private fun trackIsVideo(b: ByteArray, start: Int, end: Int): Boolean = handlerType(b, start, end) == "vide"

    private fun trackIsAudio(b: ByteArray, start: Int, end: Int): Boolean = handlerType(b, start, end) == "soun"

    private fun handlerType(b: ByteArray, start: Int, end: Int): String? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= b.size) {
            val size = u32(b, pos)
            if (size < 8 || pos + size > end) break
            val type = fourcc(b, pos + 4) ?: break
            if (type == "mdia") return findHdlr(b, pos + 8, pos + size)
            pos += size
        }
        return null
    }

    private fun findHdlr(b: ByteArray, start: Int, end: Int): String? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= b.size) {
            val size = u32(b, pos)
            if (size < 8 || pos + size > end) break
            val type = fourcc(b, pos + 4) ?: break
            if (type == "hdlr" && pos + 16 <= b.size) {
                return fourcc(b, pos + 16) // version/flags(4) + pre_defined(4) ثم handler
            }
            pos += size
        }
        return null
    }

    /** tkhd: العرض/الارتفاع آخر 8 بايتات بنقطة ثابتة 16.16 (v0 وv1 سواء) */
    private fun trakDims(b: ByteArray, start: Int, end: Int): Pair<Int, Int>? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= b.size) {
            val size = u32(b, pos)
            if (size < 8 || pos + size > end) break
            val type = fourcc(b, pos + 4) ?: break
            if (type == "tkhd") {
                val endBox = pos + size
                if (endBox - 8 < pos + 8) return null
                val wFixed = u32(b, endBox - 8)
                val hFixed = u32(b, endBox - 4)
                val w = (wFixed ushr 16).toInt()
                val h = (hFixed ushr 16).toInt()
                return if (w in 1..65535 && h in 1..65535) w to h else null
            }
            pos += size
        }
        return null
    }

    /** stsd: أول مدخل يعطي fourcc الترميز (avc1/hev1/mp4a…) — المدخل صندوق: [size][fourcc] */
    private fun trakCodec(b: ByteArray, start: Int, end: Int): String? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= b.size) {
            val size = u32(b, pos)
            if (size < 8 || pos + size > end) break
            val type = fourcc(b, pos + 4) ?: break
            if (type == "mdia") {
                val minf = find(b, pos + 8, pos + size, "minf") ?: return null
                val stbl = find(b, minf + 8, pos + size, "stbl") ?: return null
                val stsd = find(b, stbl + 8, pos + size, "stsd") ?: return null
                // stsd: رأس 8 + version/flags 4 + entry_count 4 = 16، ثم المدخل:
                // size(4) عند +16 وfourcc عند +20
                if (stsd + 24 <= b.size) return fourcc(b, stsd + 20)
                return null
            }
            pos += size
        }
        return null
    }

    /** معدل العينات من صندوق mp4a: SampleEntry(8) + reserved(8) + 2+2+2+2 ثم samplerate 16.16 */
    private fun trakSampleRate(b: ByteArray, start: Int, end: Int): Int? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= b.size) {
            val size = u32(b, pos)
            if (size < 8 || pos + size > end) break
            val type = fourcc(b, pos + 4) ?: break
            if (type == "mdia") {
                val minf = find(b, pos + 8, pos + size, "minf") ?: return null
                val stbl = find(b, minf + 8, pos + size, "stbl") ?: return null
                val stsd = find(b, stbl + 8, pos + size, "stsd") ?: return null
                if (stsd + 20 <= b.size) {
                    val entryType = fourcc(b, stsd + 20) ?: return null
                    if (entryType == "mp4a" && stsd + 52 <= b.size) {
                        // داخل مدخل mp4a (منذ بدايته عند stsd+16):
                        // size(4)+fourcc(4) ثم reserved(6)+dataref(2)+reserved(8)
                        // +channels(2)+samplesize(2)+pre(2)+reserved(2) = 32 بايت
                        // ثم samplerate 16.16 عند stsd+48
                        val rateFixed = u32(b, stsd + 48)
                        return (rateFixed ushr 16).takeIf { it in 1000..192000 }
                    }
                }
                return null
            }
            pos += size
        }
        return null
    }

    /** يجد صندوقاً فرعياً بالاسم ويعيد موضع رأسه */
    private fun find(b: ByteArray, start: Int, end: Int, name: String): Int? {
        var pos = start
        while (pos + 8 <= end && pos + 8 <= b.size) {
            val size = u32(b, pos)
            if (size < 8 || pos + size > end) break
            if (fourcc(b, pos + 4) == name) return pos
            pos += size
        }
        return null
    }

    // ─────────────────────────── MP3 / ID3v2 ───────────────────────────

    private fun isMp3(b: ByteArray): Boolean = b.size >= 2 &&
        (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0

    private fun looksLikeMp3WithId3(b: ByteArray): Boolean =
        b.size >= 10 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()

    private fun parseMp3(b: ByteArray, totalSizeOverride: Long = 0L): MediaMeta {
        val out = MediaMeta(container = "mp3")
        var audioStart = 0

        if (looksLikeMp3WithId3(b)) {
            val tagSize = syncsafe(b, 6) // حجم الوسم بعد الرأس (syncsafe)
            audioStart = 10 + tagSize
            parseId3Frames(b, 10, audioStart, out)
        }

        // أول إطار MPEG → سرعة بت ومعدل عينات + تقدير المدة
        val frame = mpegFrameHeader(b, audioStart)
        if (frame != null) {
            out.bitrateKbps = frame.bitrateKbps
            out.sampleRateHz = frame.sampleRateHz
            val audioBytes = when {
                totalSizeOverride > b.size -> totalSizeOverride - audioStart
                else -> b.size - audioStart.toLong().coerceAtMost(b.size.toLong())
            }
            if (frame.bitrateKbps > 0 && audioBytes > 0) {
                out.durationSec = audioBytes * 8.0 / (frame.bitrateKbps * 1000.0)
                out.durationEstimated = true
            }
        }
        return out
    }

    /** إطارات ID3v2: معرف 4 أحرف + حجم (v2.4 syncsafe / v2.3 عادي) — نصوص TIT2/TPE1/TALB/TYER */
    private fun parseId3Frames(b: ByteArray, start: Int, end: Int, out: MediaMeta) {
        if (end + 0 > b.size || start + 10 > b.size) return
        val major = (b[3].toInt() and 0xFF)
        var flags = b[5].toInt() and 0xFF
        if (flags and 0x40 != 0) {
            // extended header — نتخطاه بحجمه (syncsafe في v2.4)
            val extSize = if (major >= 4) syncsafe(b, start) else u32(b, start).toInt()
            val skip = if (major >= 4) extSize else extSize + 4
            parseFramesFrom(b, start + skip, end, major, out)
            return
        }
        parseFramesFrom(b, start, end, major, out)
    }

    private fun parseFramesFrom(b: ByteArray, start: Int, end: Int, major: Int, out: MediaMeta) {
        var pos = start
        var frames = 0
        while (pos + 10 <= end && pos + 10 <= b.size && frames < MAX_ID3_FRAMES) {
            val id = ascii4(b, pos) ?: break
            if (id[0] !in 'A'..'Z') break // نهاية الإطارات (حشو)
            val size = if (major >= 4) syncsafe(b, pos + 4) else u32(b, pos + 4).toInt()
            if (size <= 0 || pos + 10 + size > b.size) break
            when (id) {
                "TIT2" -> out.title = id3Text(b, pos + 10, size)
                "TPE1" -> out.artist = id3Text(b, pos + 10, size)
                "TALB" -> out.album = id3Text(b, pos + 10, size)
                "TYER", "TDRC" -> out.year = id3Text(b, pos + 10, size)?.take(4)
            }
            pos += 10 + size
            frames++
        }
    }

    /** بايت الترميز: 0 لاتيني-1، 1 UTF-16 مع BOM، 2 UTF-16BE، 3 UTF-8 — العربي يعمل في كلها */
    private fun id3Text(b: ByteArray, p: Int, size: Int): String? {
        if (size < 1 || p + size > b.size) return null
        val enc = b[p].toInt() and 0xFF
        val body = b.copyOfRange(p + 1, p + size)
        return try {
            when (enc) {
                0 -> String(body, charset("ISO-8859-1")).trimEnd('\u0000').ifEmpty { null }
                1 -> decodeUtf16WithBom(body)
                2 -> String(body, charset("UTF-16BE")).trimEnd('\u0000').ifEmpty { null }
                3 -> String(body, Charsets.UTF_8).trimEnd('\u0000').ifEmpty { null }
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeUtf16WithBom(body: ByteArray): String? {
        if (body.size < 2) return null
        val bom = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        return when (bom) {
            0xFEFF -> String(body, 2, body.size - 2, charset("UTF-16BE"))
            0xFFFE -> String(body, 2, body.size - 2, charset("UTF-16LE"))
            else -> String(body, charset("UTF-16"))
        }.trimEnd('\u0000').ifEmpty { null }
    }

    /** رأس أول إطار MPEG-1/2: 11 بت sync + إصدار + طبقة + سرعة بت + معدل عينات */
    private fun mpegFrameHeader(b: ByteArray, from: Int): FrameInfo? {
        var i = from
        val limit = minOf(b.size - 4, from + 65536)
        while (i < limit) {
            if ((b[i].toInt() and 0xFF) == 0xFF && (b[i + 1].toInt() and 0xE0) == 0xE0) {
                val versionBits = (b[i + 1].toInt() shr 3) and 0x03
                val layerBits = (b[i + 1].toInt() shr 1) and 0x03
                val bitrateIdx = (b[i + 2].toInt() and 0xF0) shr 4
                val srIdx = (b[i + 2].toInt() and 0x0C) shr 2
                if (versionBits != 1 && layerBits != 0 && bitrateIdx != 0 && bitrateIdx != 15 && srIdx != 3) {
                    // جداول MPEG-1 Layer III وMPEG-2/2.5 Layer III
                    val isMpeg1 = versionBits == 3
                    val bitrateKbps = if (isMpeg1) M1_L3[bitrateIdx] else M2_L3[bitrateIdx]
                    val sr = when {
                        srIdx == 0 && isMpeg1 -> 44100
                        srIdx == 1 && isMpeg1 -> 48000
                        srIdx == 2 && isMpeg1 -> 32000
                        srIdx == 0 -> 22050
                        srIdx == 1 -> 24000
                        else -> 16000
                    }
                    return FrameInfo(bitrateKbps, sr)
                }
            }
            i++
        }
        return null
    }

    private data class FrameInfo(val bitrateKbps: Int, val sampleRateHz: Int)

    private val M1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val M2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

    // ─────────────────────────── أدوات ───────────────────────────

    /** حجم صندوق — Int كافٍ (الصناديق الفردية < 2GB) ويبسط كل حسابات التجول */
    private fun u32(b: ByteArray, p: Int): Int {
        if (p < 0 || p + 4 > b.size) return 0
        return ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
    }

    private fun u64(b: ByteArray, p: Int): Long {
        if (p < 0 || p + 8 > b.size) return 0
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (b[p + k].toLong() and 0xFF)
        return v
    }

    /** 4 بايت syncsafe: كل بايت 7 بت فقط — معيار ID3v2 */
    private fun syncsafe(b: ByteArray, p: Int): Int {
        if (p < 0 || p + 4 > b.size) return 0
        return ((b[p].toInt() and 0x7F) shl 21) or ((b[p + 1].toInt() and 0x7F) shl 14) or
            ((b[p + 2].toInt() and 0x7F) shl 7) or (b[p + 3].toInt() and 0x7F)
    }

    private fun fourcc(b: ByteArray, p: Int): String? {
        if (p < 0 || p + 4 > b.size) return null
        val sb = StringBuilder(4)
        for (k in 0 until 4) {
            val c = b[p + k].toInt() and 0xFF
            if (c < 0x20 || c > 0x7E) return null
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun ascii4(b: ByteArray, p: Int): String? = fourcc(b, p)

    private const val MAX_TOP_BOXES = 32
    private const val MAX_ID3_FRAMES = 256

    // ─────────────────────────── نصوص ودّية ───────────────────────────

    /** اسم ترميز ودّي: avc1→H.264، hvc1/hev1→H.265، mp4a→AAC، أو fourcc الخام */
    fun friendlyCodec(fourcc: String?): String? = when (fourcc) {
        "avc1", "avc3" -> "H.264"
        "hvc1", "hev1" -> "H.265"
        "mp4a" -> "AAC"
        "av01" -> "AV1"
        "vp09" -> "VP9"
        null -> null
        else -> fourcc
    }

    /** مدة بنمط mm:ss أو h:mm:ss (منطق v0.31 نفسه — هنا للخصائص) */
    fun friendlyDuration(sec: Double?): String? {
        if (sec == null || sec < 0 || !sec.isFinite()) return null
        val total = sec.toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
}
