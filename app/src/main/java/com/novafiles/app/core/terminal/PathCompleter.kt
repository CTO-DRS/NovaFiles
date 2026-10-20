package com.novafiles.app.core.terminal

import java.io.File
import java.util.Locale

/**
 * مكمّل المسارات والأوامر لشل NovaFiles — خالص ومُختبَر 100%.
 *
 * السلوك:
 * - الرمز الأول → إكمال اسم أمر (من السجل المعروف) مع مسافة ختامية
 * - باقي الرموز → إكمال مسار: بادئة مشتركة، أو قائمة مرشحين للشارات
 * - يضيف «/» تلقائياً عند اكتمال مجلد
 * - يحترم الاقتباس والتهريب: «my dir» يُدرج كـ «my\ dir» أو داخل اقتباس
 * - الملفات المخفية تظهر فقط إذا بدأت البادئة بـ «.»
 * - يكمل بالنسبة لمجلد العمل الحالي، ويحترم «~/» والمسارات المطلقة
 */
class PathCompleter(private val home: File) {

    data class Completion(
        /** السطر بعد الإكمال (كما هو إن لم يوجد إكمال) */
        val line: String,
        /** موضع المؤشر الجديد */
        val cursor: Int,
        /** أسماء مرشحة للعرض كشارات (≤ 8) */
        val candidates: List<String>,
        /** هل تغيّر السطر فعلاً؟ */
        val completed: Boolean,
        /** عدد مرشحين إضافيين فوق الحد المعروض */
        val moreCount: Int = 0,
    )

    fun complete(input: String, cursor: Int, commands: Set<String>, cwd: File): Completion {
        val loc = locateToken(input, cursor)
        val typedRaw = input.substring(loc.start, loc.end)
        val cleaned = stripQuote(ShellPaths.unescape(typedRaw))

        // ── إكمال اسم الأمر (الرمز الأول بلا مسارات) ──
        if (loc.index == 0 && !cleaned.contains('/')) {
            val matches = commands.filter { it.startsWith(cleaned) }.sorted()
            if (matches.isEmpty()) return unchanged(input, cursor)
            if (matches.size == 1) {
                return replace(input, loc, matches.first() + " ", emptyList(), true)
            }
            val lcp = longestCommonPrefix(matches)
            return if (lcp.length > cleaned.length) {
                replace(input, loc, lcp, emptyList(), true)
            } else {
                Completion(input, cursor, matches.take(DISPLAY_LIMIT), false, matches.size - DISPLAY_LIMIT)
            }
        }

        // ── إكمال المسار ──
        val slashIdx = cleaned.lastIndexOf('/')
        val dirPart = if (slashIdx < 0) "" else cleaned.substring(0, slashIdx + 1) // مع الشرطة
        val prefix = if (slashIdx < 0) cleaned else cleaned.substring(slashIdx + 1)

        val baseDir = when {
            dirPart.isEmpty() -> cwd
            dirPart == "~" || dirPart == "~/" -> home
            dirPart.startsWith("~/") -> File(home, dirPart.removePrefix("~/"))
            dirPart.startsWith("/") -> File(dirPart)
            else -> File(cwd, dirPart)
        }
        if (!baseDir.isDirectory) return unchanged(input, cursor)

        val showHidden = prefix.startsWith(".")
        val matches = baseDir.listFiles()
            ?.filter { it.name.startsWith(prefix, ignoreCase = true) && (showHidden || !it.name.startsWith(".")) }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?: emptyList()

        if (matches.isEmpty()) return unchanged(input, cursor)

        // إعادة بناء الرمز مع الحفاظ على صيغة الجزء الثابت (~ / مطلق / نسبي)
        fun rebuild(name: String): String {
            val isDir = File(baseDir, name).isDirectory
            val body = dirPart + name + (if (isDir) "/" else "")
            val quoted = typedRaw.startsWith("'") || typedRaw.startsWith("\"")
            return when {
                quoted -> "\"$body\""
                else -> ShellPaths.escape(body)
            }
        }

        if (matches.size == 1) {
            return replace(input, loc, rebuild(matches.first().name), emptyList(), true)
        }

        val names = matches.map { it.name }
        val lcp = longestCommonPrefix(names)
        val lcpExtends = lcp.length > prefix.length
        return if (lcpExtends) {
            val allDirs = matches.all { it.isDirectory }
            val body = dirPart + lcp + (if (allDirs) "/" else "")
            val quoted = typedRaw.startsWith("'") || typedRaw.startsWith("\"")
            val token = if (quoted) "\"$body\"" else ShellPaths.escape(body)
            replace(input, loc, token, names.take(DISPLAY_LIMIT), true, (names.size - DISPLAY_LIMIT).coerceAtLeast(0))
        } else {
            Completion(input, cursor, names.take(DISPLAY_LIMIT), false, (names.size - DISPLAY_LIMIT).coerceAtLeast(0))
        }
    }

    // ─────────────────────────── مساعدات ───────────────────────────

    private data class TokenLoc(val start: Int, val end: Int, val index: Int)

    private fun unchanged(input: String, cursor: Int) =
        Completion(input, cursor, emptyList(), false)

    private fun replace(
        input: String,
        loc: TokenLoc,
        token: String,
        candidates: List<String>,
        completed: Boolean,
        moreCount: Int = 0,
    ): Completion {
        val line = input.substring(0, loc.start) + token + input.substring(loc.end)
        return Completion(line, loc.start + token.length, candidates, completed, moreCount)
    }

    private fun longestCommonPrefix(items: List<String>): String {
        if (items.isEmpty()) return ""
        var prefix = items.first()
        for (s in items.drop(1)) {
            var i = 0
            val limit = minOf(prefix.length, s.length)
            while (i < limit && prefix[i] == s[i]) i++
            prefix = prefix.substring(0, i)
            if (prefix.isEmpty()) break
        }
        return prefix
    }

    /** نزع اقتباس محيط (كامل أو مفتوح فقط من البداية أثناء الكتابة) */
    private fun stripQuote(s: String): String {
        if (s.length >= 2) {
            val first = s.first()
            val last = s.last()
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return s.substring(1, s.length - 1)
            }
        }
        if (s.startsWith("'") || s.startsWith("\"")) return s.substring(1)
        return s
    }

    /**
     * تحديد الرمز الجاري عند [cursor]: يمسح الرموز أماماً بقواعد الطرفية
     * (اقتباس/تهريب) ويعيد نطاق الرمز الذي يقف عنده المؤشر، أو رمزاً
     * فارغاً جديداً إن كان المؤشر على مسافة بعد رمز مكتمل.
     */
    private fun locateToken(input: String, cursor: Int): TokenLoc {
        val caret = cursor.coerceIn(0, input.length)
        var i = 0
        var index = 0
        while (i < input.length) {
            // تجاوز الفواصل
            while (i < input.length && (input[i] == ' ' || input[i] == '\t')) i++
            if (i >= input.length) break
            val start = i
            val end = scanTokenEnd(input, i)
            if (caret <= end) {
                // مؤشر على مسافة عند نهاية الرمز → رمز جديد فارغ بعده
                if (caret == end && caret < input.length && (input[caret] == ' ' || input[caret] == '\t')) {
                    return TokenLoc(caret, caret, index + 1)
                }
                return TokenLoc(start, end, index)
            }
            index++
            i = end
        }
        return TokenLoc(caret, caret, index)
    }

    /** نهاية الرمز بدءاً من [from] — يحترم الاقتباس والتهريب */
    private fun scanTokenEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            when (val c = s[i]) {
                ' ', '\t' -> return i
                '\'' -> {
                    val close = s.indexOf('\'', i + 1)
                    i = if (close < 0) s.length else close + 1
                }

                '"' -> {
                    i++
                    while (i < s.length) {
                        when {
                            s[i] == '\\' && i + 1 < s.length -> i += 2
                            s[i] == '"' -> {
                                i++
                                break
                            }

                            else -> i++
                        }
                    }
                }

                '\\' -> i += 2
                else -> i++
            }
        }
        return i
    }

    companion object {
        const val DISPLAY_LIMIT = 8
    }
}
