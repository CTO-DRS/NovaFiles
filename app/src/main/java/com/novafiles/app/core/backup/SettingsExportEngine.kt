package com.novafiles.app.core.backup

/**
 * v0.39 — جاهزية الإطلاق: نقل الإعدادات بين الأجهزة (تصدير/استيراد).
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * الفلسفة: ملف نسخة واحد «nova-settings.json» يحمل تفضيلات المستخدم
 * (السمة، اللوحة، الإيماءات، حزمة الأيقونات، الارتباطات…) وإشاراته
 * المرجعية — ليعيد المستخدم تجربته كاملة على جهاز جديد بلا حساب سحابي
 * وبلا أي تتبّع. المحرك يفصل ثلاث طبقات:
 *
 *  1. **المرمّز الحتمي** — JSON مكتوب يدوياً بترتيب مفاتيح مفروز،
 *     ببصمة FNV-1a تُكتشف أي تلف أو تعديل خارجي (نمط FNV-1a للوسوم).
 *  2. **التحقق** — مخطط واحد معروف (schema=1)، مخطط أحدث يُرفض برسالة
 *     عربية صريحة (ترقية التطبيق مطلوب)، ملف تالف يُرفض بلا استثناءات.
 *  3. **سياسة الدمج** — «دمج» يملأ الناقص فقط (الحالي يفوز) و«استبدال»
 *     يفوز فيه المستورد على كل المفاتيح المعروفة. المفاتيح السرية
 *     (كلمات مرور خوادم FTP/WebDAV) **مستبعدة نهائياً** من قائمة
 *     التصدير — لا نسخة إعدادات تخرج من الجهاز باعتماديات.
 *
 * المفاتيح المجهولة من إصدار أحدث تُحصى وتُتخطّى (توافق أمامي)،
 * والقرار كله حتمي مُختبَر.
 */

/** إشارة مرجعية داخل النسخة — اسم ومسار فقط (لا ميتاداتا أخرى) */
data class BookmarkRef(val name: String, val path: String)

/** نسخة الإعدادات القابلة للترميز */
data class ExportPayload(
    val schema: Int,
    val exportedAt: String, // ISO-8601 نصياً
    val prefs: Map<String, String>,      // مفتاح → قيمة نصية خام
    val bookmarks: List<BookmarkRef>,
)

/** نتيجة الفك — نجاح أو رفض بسبب عربي محدد */
sealed class DecodeResult {
    data class Ok(val payload: ExportPayload, val skippedUnknownPrefs: Int) : DecodeResult()
    data class Rejected(val reason: String) : DecodeResult()
}

/** سياسة تطبيق النسخة المستوردة */
enum class ImportPolicy { MERGE, REPLACE }

/** ملخص تطبيق النسخة على الجهاز */
data class ImportSummary(
    val appliedPrefs: Int,
    val skippedPrefs: Int,   // مفاتيح غير معروفة تُتخطّى للأمام
    val appliedBookmarks: Int,
    val droppedBookmarks: Int, // فائضة عن السقف أو بمسار فارغ
)

object SettingsExportEngine {

    /** المخطط الوحيد المدعوم — إصدار أحدث يتطلب ترقية التطبيق */
    const val SUPPORTED_SCHEMA = 1

    /** سقف الإشارات المرجعية — محاذٍ لسقف BookmarksEngine (v0.36) */
    const val BOOKMARKS_CAP = 24

    /**
     * مفاتيح التفضيلات القابلة للتصدير — **لا سرّ بينها**: كلمات مرور
     * وأسماء مستخدمي خوادم FTP/WebDAV مستبعدة عمداً (بصمة أمنية).
     */
    val EXPORTABLE_PREF_KEYS: Set<String> = setOf(
        "view_grid", "sort", "show_hidden", "theme_mode",
        "palette_id", "folder_accents", "icon_pack_manifest",
        "gesture_config", "double_tap_navigate", "open_with_defaults",
        "smart_enabled", "security_guide_read",
        "onboarding_done",
        "ftp_port", "dav_port", "ftp_speed_kb",
    )

    // ═══════════ 1) البصمة (FNV-1a 64bit — نمط الوسوم الحتمي) ═══════════

    /** FNV-1a 64-bit على UTF-8 — يُعيد القيمة بصيغة hex سدسية 16 خانة */
    fun fnv1aHex(text: String): String {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xffL)
            hash *= 0x100000001b3L
        }
        return "%016x".format(hash)
    }

    /**
     * الجسم المتعارف عليه الذي تُحسب بصمته — حتمي تماماً:
     * المخطط + الزمن + المفاتيح مفروزة + الإشارات بترتيبها المعطى.
     */
    fun canonicalBody(p: ExportPayload): String = buildString {
        append(p.schema)
        append('\u0001')
        append(p.exportedAt)
        append('\u0001')
        p.prefs.toSortedMap().forEach { (k, v) ->
            append(k).append('=').append(v).append('\u0002')
        }
        append('\u0001')
        p.bookmarks.forEach { b ->
            append(b.name).append('\u0003').append(b.path).append('\u0002')
        }
    }

    // ═══════════ 2) الترميز الحتمي ═══════════

    /** ترميز JSON — إخراج واحد لأي ترتيب إدخال (مفاتيح مفروزة، بصمة محسوبة) */
    fun encode(p: ExportPayload): String {
        fun esc(s: String): String = buildString {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (c < ' ') append("\\u%04x".format(c.code))
                    else append(c)
            }
        }
        val sum = fnv1aHex(canonicalBody(p))
        val prefs = p.prefs.toSortedMap().entries.joinToString(",") { e ->
            """"${esc(e.key)}":"${esc(e.value)}""""
        }
        val bms = p.bookmarks.joinToString(",") { b ->
            """{"n":${esc(b.name)},"p":${esc(b.path)}}"""
        }
        return """{"schema":${p.schema},"sum":"$sum","at":"${esc(p.exportedAt)}",""" +
            """"prefs":{$prefs},"bms":[$bms]}"""
    }

    // ═══════════ 3) الفك المتسامح ═══════════

    /**
     * فك نسخة: ملف تالف → Rejected (لا استثناءات)، مخطط أحدث → رفض
     * برسالة ترقية، بصمة مخالفة → رفض (تعديل خارجي أو تلف)، مفاتيح
     * تفضيلات مجهولة تُحصى وتُتخطّى، وإشارات بمسار فارغ تُسقَط
     * والفائض عن السقف يُقصّ حتماً.
     */
    fun decode(raw: String): DecodeResult {
        val text = raw.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return DecodeResult.Rejected("الملف ليس نسخة إعدادات JSON صالحة")
        }
        val body = text.substring(1, text.length - 1)
        val fields = splitTopLevel(body)
        val objOf = { s: String ->
            if (s.startsWith("{") && s.endsWith("}")) splitTopLevel(s.substring(1, s.length - 1))
            else emptyMap<String, String>()
        }

        // schema — رقم صريح مطلوب
        val schemaRaw = fields["schema"] ?: return DecodeResult.Rejected("النسخة بلا مخطط (schema)")
        val schema = schemaRaw.toIntOrNull() ?: return DecodeResult.Rejected("المخطط ليس رقماً: $schemaRaw")
        if (schema > SUPPORTED_SCHEMA) {
            return DecodeResult.Rejected(
                "مخطط النسخة ($schema) أحدث من المخطط المدعوم ($SUPPORTED_SCHEMA) — رقِّ التطبيق أولاً",
            )
        }
        if (schema < SUPPORTED_SCHEMA) {
            return DecodeResult.Rejected(
                "مخطط النسخة ($schema) أقدم من المخطط المدعوم ($SUPPORTED_SCHEMA) — النسخة غير صالحة",
            )
        }

        // at — نص زمني (نتسامح بصيغته لكن لا فراغ)
        val at = fields["at"] ?: return DecodeResult.Rejected("النسخة بلا زمن تصدير (at)")
        if (at.isBlank()) return DecodeResult.Rejected("زمن التصدير فارغ")

        // prefs — كائن نصي→نصي
        val prefsRaw = objOf(fields["prefs"] ?: "")
        val prefs = linkedMapOf<String, String>()
        for ((k, v) in prefsRaw) {
            if (k.isBlank()) continue
            prefs[k] = v
        }

        // bms — مصفوفة كائنات n/p
        val bmsRaw = fields["bms"]?.removeSurrounding("[", "]") ?: ""
        val bms = mutableListOf<BookmarkRef>()
        if (bmsRaw.isNotBlank()) {
            for (item in splitJsonObjects(bmsRaw)) {
                val f = objOf(item)
                val name = f["n"] ?: continue
                val path = f["p"] ?: continue
                if (path.isBlank()) continue // مسار فارغ يُسقَط دائماً
                if (bms.size >= BOOKMARKS_CAP) break // السقف حتمي
                bms += BookmarkRef(name, path)
            }
        }

        // البصمة — إعادة الحساب على الجسم المُعاد بناؤه
        val expected = fields["sum"] ?: return DecodeResult.Rejected("النسخة بلا بصمة تحقق (sum)")
        val rebuilt = ExportPayload(schema, at, prefs, bms.toList())
        val actual = fnv1aHex(canonicalBody(rebuilt))
        if (!expected.equals(actual, ignoreCase = true)) {
            return DecodeResult.Rejected(
                "بصمة التحقق مخالفة — الملف مُعدَّل خارجياً أو تالف (المتوقعة $expected والفعلية $actual)",
            )
        }

        val unknown = prefsRaw.count { it.key.isNotBlank() && it.key !in EXPORTABLE_PREF_KEYS }
        return DecodeResult.Ok(rebuilt, skippedUnknownPrefs = unknown)
    }

    // ═══════════ 4) سياسة التطبيق ═══════════

    /**
     * تطبيق النسخة على التفضيلات الحالية — الحتمية كاملة:
     *  - MERGE: الحالي يفوز على المتشابه، والناقص يُملأ من المستورد.
     *  - REPLACE: المستورد يفوز على كل المفاتيح المعروفة، والحالي
     *    الذي ليس من المفاتيح المعروفة يُحفظ (لا مسح عشوائي)، والمعروف
     *    الغائب من المستورد يُحذف فعلياً (يعود لقيمته الافتراضية).
     * المفاتيح المجهولة من إصدار أحدث تُتخطّى وتُحصى دائماً.
     */
    fun applyPolicy(current: Map<String, String>, payload: ExportPayload, policy: ImportPolicy): Pair<Map<String, String>, ImportSummary> {
        val known = payload.prefs.filterKeys { it in EXPORTABLE_PREF_KEYS }
        val skipped = payload.prefs.size - known.size
        val merged: Map<String, String> = when (policy) {
            ImportPolicy.MERGE -> {
                val out = current.toMutableMap()
                for ((k, v) in known) if (k !in out) out[k] = v
                out
            }
            ImportPolicy.REPLACE -> {
                val out = current.filterKeys { it !in EXPORTABLE_PREF_KEYS }.toMutableMap()
                for ((k, v) in known) out[k] = v
                out
            }
        }
        return merged to ImportSummary(
            appliedPrefs = known.size,
            skippedPrefs = skipped,
            appliedBookmarks = payload.bookmarks.size,
            droppedBookmarks = 0, // القص جرى عند الفك حتماً
        )
    }

    // ═══════════ 5) أدوات JSON المسطّحة الداخلية ═══════════

    /** تقسيم body مستوى-أعلى إلى key:value مع احترام الأنماط والأقواس */
    private fun splitTopLevel(body: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var i = 0
        while (i < body.length) {
            if (body[i] != '"') { i++; continue }
            val keyEnd = findStringEnd(body, i) ?: break
            val key = unescape(body.substring(i + 1, keyEnd))
            var j = keyEnd + 1
            while (j < body.length && body[j].isWhitespace()) j++
            if (j >= body.length || body[j] != ':') { i = keyEnd + 1; continue }
            j++
            while (j < body.length && body[j].isWhitespace()) j++
            if (j >= body.length) break
            val value: String
            when (body[j]) {
                '"' -> {
                    val end = findStringEnd(body, j) ?: break
                    value = unescape(body.substring(j + 1, end))
                    j = end + 1
                }
                '{' -> {
                    val end = matchBracket(body, j, '{', '}') ?: break
                    value = body.substring(j, end + 1)
                    j = end + 1
                }
                '[' -> {
                    val end = matchBracket(body, j, '[', ']') ?: break
                    value = body.substring(j, end + 1)
                    j = end + 1
                }
                else -> {
                    var end = j
                    while (end < body.length && body[end] != ',') end++
                    value = body.substring(j, end).trim()
                    j = end
                }
            }
            out[key] = value
            while (j < body.length && body[j] != ',') j++
            i = j + 1
        }
        return out
    }

    /** استخراج كائنات {} من نص مصفوفة مسطّحة */
    private fun splitJsonObjects(arr: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < arr.length) {
            if (arr[i] != '{') { i++; continue }
            val end = matchBracket(arr, i, '{', '}') ?: break
            out += arr.substring(i, end + 1)
            i = end + 1
        }
        return out
    }

    /** نهاية سلسلة JSON بدءاً من علامة الفتح — null عند النص غير المغلق */
    private fun findStringEnd(s: String, open: Int): Int? {
        var i = open + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == '"') return i
            i++
        }
        return null
    }

    /** نهاية القوس المقابل مع عدّ التداخل واحترام السلاسل */
    private fun matchBracket(s: String, open: Int, openC: Char, closeC: Char): Int? {
        var depth = 0
        var i = open
        var inStr = false
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                openC -> depth++
                closeC -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    /** فك تسلسلات JSON القياسية — المجهول يُتخطّى حرفياً (تسامح) */
    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.substring(i + 2, (i + 6).coerceAtMost(s.length))
                        hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 4
                    }
                    else -> sb.append(n)
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
