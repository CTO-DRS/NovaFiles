package com.novafiles.app.core.cloud

/**
 * v0.16 — محلل JSON مصغّر خالص (كوتلن فقط) لمهايئات السحابة.
 *
 * لماذا؟ مهايئات Drive/Dropbox/OneDrive تحتاج فقط قراءة حقول كائنات ومصفوفات،
 * وإضافة مكتبة JSON كاملة (Moshi/Gson) لقراءة < 20 حقلاً تضخّم APK وتضيف
 * سطح تبعيات. هذا المحلل يعطي: كائنات، مصفوفات، سلاسل (مع تهريب كامل)،
 * أرقام (طويلة/مزدوجة)، منطقية، وnull — بذاكرة O(n) وبلا انعكاس.
 *
 * لا يدعم: التعليقات، الأرقام الكبيرة بدقة BigDecimal (تُقرأ Double)،
 * أو كائنات مكررة المفاتيح (الأخير يفوز).
 */
object MiniJson {

    /** قيمة JSON — تمثيل موحّد بسيط */
    sealed interface Value {
        data class Obj(val fields: Map<String, Value>) : Value
        data class Arr(val items: List<Value>) : Value
        data class Str(val s: String) : Value
        data class Num(val raw: String) : Value
        data class Bool(val b: Boolean) : Value
        object Null : Value
    }

    /** خطأ تحليل — يظهر بموضع الحرف لتشخيص fixtures المزوّدين */
    class ParseException(message: String, val position: Int) :
        IllegalArgumentException("$message at offset $position")

    /** يحلل نصاً كاملاً — يرمي ParseException عند أي انحراف */
    fun parse(text: String): Value {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWs()
        if (!p.atEnd()) throw p.fail("trailing content")
        return v
    }

    /** يحلل ويستخرج كائن جذر مباشرة (الاستخدام الشائع في المهايئات) */
    fun parseObject(text: String): Map<String, Value> {
        val v = parse(text)
        return (v as? Value.Obj)?.fields ?: throw ParseException("expected object at root", 0)
    }

    // ---------- مستخرجات مريحة بتحويلات آمنة ----------

    fun asString(v: Value?): String? = (v as? Value.Str)?.s

    fun asLong(v: Value?): Long? = when (v) {
        is Value.Num -> v.raw.toLongOrNull() ?: v.raw.toDoubleOrNull()?.toLong()
        is Value.Str -> v.s.toLongOrNull()
        is Value.Bool -> if (v.b) 1L else 0L
        else -> null
    }

    fun asDouble(v: Value?): Double? = when (v) {
        is Value.Num -> v.raw.toDoubleOrNull()
        else -> null
    }

    fun asBool(v: Value?): Boolean? = (v as? Value.Bool)?.b

    fun asArray(v: Value?): List<Value> = (v as? Value.Arr)?.items ?: emptyList()

    fun asObject(v: Value?): Map<String, Value> = (v as? Value.Obj)?.fields ?: emptyMap()

    private class Parser(val s: String) {
        var i = 0

        fun atEnd() = i >= s.length

        fun fail(msg: String) = ParseException(msg, i)

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun parseValue(): Value {
            skipWs()
            if (atEnd()) throw fail("unexpected end")
            return when (s[i]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> Value.Str(parseString())
                't' -> literal("true", Value.Bool(true))
                'f' -> literal("false", Value.Bool(false))
                'n' -> literal("null", Value.Null)
                else -> parseNumber()
            }
        }

        private fun literal(word: String, v: Value): Value {
            if (i + word.length > s.length || !s.regionMatches(i, word, 0, word.length))
                throw fail("invalid literal")
            i += word.length
            return v
        }

        private fun parseObj(): Value.Obj {
            i++ // {
            val fields = LinkedHashMap<String, Value>()
            skipWs()
            if (!atEnd() && s[i] == '}') { i++; return Value.Obj(fields) }
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') throw fail("expected key string")
                val key = parseString()
                skipWs()
                if (atEnd() || s[i] != ':') throw fail("expected ':'")
                i++
                fields[key] = parseValue()
                skipWs()
                if (atEnd()) throw fail("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return Value.Obj(fields) }
                    else -> throw fail("expected ',' or '}'")
                }
            }
        }

        private fun parseArr(): Value.Arr {
            i++ // [
            val items = ArrayList<Value>()
            skipWs()
            if (!atEnd() && s[i] == ']') { i++; return Value.Arr(items) }
            while (true) {
                items.add(parseValue())
                skipWs()
                if (atEnd()) throw fail("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return Value.Arr(items) }
                    else -> throw fail("expected ',' or ']'")
                }
            }
        }

        fun parseString(): String {
            i++ // "
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw fail("unterminated string")
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        if (atEnd()) throw fail("bad escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) throw fail("bad unicode escape")
                                val hex = s.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: throw fail("bad unicode escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw fail("unknown escape \\$e")
                        }
                        i++
                    }
                    // سلاسل المزوّدين لا تحوي محارف تحكم خام — نرفضها بدل الصمت
                    else -> {
                        if (c.code < 0x20) throw fail("raw control char in string")
                        sb.append(c)
                        i++
                    }
                }
            }
        }

        private fun parseNumber(): Value.Num {
            val start = i
            if (!atEnd() && s[i] == '-') i++
            if (atEnd() || s[i] !in '0'..'9') throw fail("invalid number")
            while (!atEnd() && s[i] in '0'..'9') i++
            if (!atEnd() && s[i] == '.') {
                i++
                if (atEnd() || s[i] !in '0'..'9') throw fail("invalid fraction")
                while (!atEnd() && s[i] in '0'..'9') i++
            }
            if (!atEnd() && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (!atEnd() && (s[i] == '+' || s[i] == '-')) i++
                if (atEnd() || s[i] !in '0'..'9') throw fail("invalid exponent")
                while (!atEnd() && s[i] in '0'..'9') i++
            }
            return Value.Num(s.substring(start, i))
        }
    }
}
