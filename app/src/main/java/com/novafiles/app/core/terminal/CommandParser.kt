package com.novafiles.app.core.terminal

/**
 * محلل أوامر شل NovaFiles — خالص ومُختبَر 100%.
 *
 * يدعم:
 * - تقسيم الأوامر على المسافات مع احترام الاقتباس المفرد '…' (حرفي تماماً)
 *   والمزدوج "…" (مع تهريب \" و \\)، والتهريب بخط مائل خارج الاقتباس \ س
 * - الوسائط الملصوقة بالاقتباس: ab"cd ef"gh → abcde fgh كوسيط واحد
 * - تسلسل الأوامر: «;» (دائماً) و «&&» (عند النجاح)
 * - توجيه الإخراج ‎> ملف و ‎>> ملف (يُلحق)
 * - التعليقات: «#» في بداية رمز تجعل بقية السطر تجاهلاً
 * - يرفض بوضوح: الأنابيب «|» وتشغيل الخلفية «&» المفرد
 */

object CommandParser {

    // ─────────────────────────── الواجهة ───────────────────────────

    fun parse(raw: String): ParseResult {
        val segments = ArrayList<ShellSegment>()
        val tokens = ArrayList<String>()
        var joiner = Joiner.FIRST
        var redirect: ShellRedirect? = null

        val cur = StringBuilder()
        var tokenActive = false
        var i = 0
        val n = raw.length

        fun flushToken() {
            if (tokenActive) {
                tokens += cur.toString()
                cur.setLength(0)
                tokenActive = false
            }
        }

        fun flushSegment(nextJoiner: Joiner) {
            flushToken()
            if (tokens.isNotEmpty() || redirect != null) {
                if (tokens.isEmpty()) {
                    return  // توجيه بلا أمر — خطأ أدناه عبر حالة رموز فارغة
                }
                segments += ShellSegment(tokens.toList(), redirect, joiner)
                tokens.clear()
                redirect = null
                joiner = nextJoiner
            }
        }

        while (i < n) {
            val c = raw[i]
            when {
                c == ' ' || c == '\t' -> {
                    flushToken()
                    i++
                }

                c == '#' && !tokenActive -> {
                    // تعليق — بقية السطر تُتجاهل (فقط عندما لا نكون داخل رمز)
                    i = n
                }

                c == ';' -> {
                    flushSegment(Joiner.THEN)
                    i++
                }

                c == '&' -> {
                    if (i + 1 < n && raw[i + 1] == '&') {
                        flushSegment(Joiner.AND_THEN)
                        i += 2
                    } else {
                        return ParseResult.Err("unsupported: background execution '&' — use && to chain")
                    }
                }

                c == '|' -> {
                    return ParseResult.Err("unsupported: pipes '|' — NovaFiles shell runs one command at a time")
                }

                c == '>' -> {
                    flushToken()
                    val append = i + 1 < n && raw[i + 1] == '>'
                    i += if (append) 2 else 1
                    // تجاوز المسافات حتى هدف التوجيه
                    while (i < n && (raw[i] == ' ' || raw[i] == '\t')) i++
                    if (i >= n) return ParseResult.Err("syntax error: expected file after '>'")
                    val (target, next) = readToken(raw, i)
                    if (target.isEmpty()) return ParseResult.Err("syntax error: expected file after '>'")
                    redirect = ShellRedirect(target, append)
                    i = next
                }

                c == '\'' -> {
                    tokenActive = true
                    val close = raw.indexOf('\'', i + 1)
                    if (close < 0) return ParseResult.Err("syntax error: unterminated 'quote'")
                    cur.append(raw, i + 1, close)
                    i = close + 1
                }

                c == '"' -> {
                    tokenActive = true
                    i++
                    var closed = false
                    while (i < n) {
                        val d = raw[i]
                        when {
                            d == '\\' && i + 1 < n && (raw[i + 1] == '"' || raw[i + 1] == '\\') -> {
                                cur.append(raw[i + 1])
                                i += 2
                            }

                            d == '"' -> {
                                closed = true
                                i++
                                break
                            }

                            else -> {
                                cur.append(d)
                                i++
                            }
                        }
                    }
                    if (!closed) return ParseResult.Err("syntax error: unterminated \"quote\"")
                }

                c == '\\' -> {
                    tokenActive = true
                    if (i + 1 < n) {
                        cur.append(raw[i + 1])
                        i += 2
                    } else {
                        return ParseResult.Err("syntax error: trailing backslash")
                    }
                }

                else -> {
                    tokenActive = true
                    cur.append(c)
                    i++
                }
            }
        }

        flushSegment(Joiner.FIRST)

        if (tokens.isNotEmpty() || redirect != null) {
            return ParseResult.Err("syntax error: dangling redirection without command")
        }
        return ParseResult.Ok(ParsedLine(segments))
    }

    /**
     * قراءة رمز كامل بدءاً من [start] (خارج سياق المحلل) — يُستخدم أيضاً
     * من المكمّل لمعرفة حدود الرمز الجاري. يعيد (النص المفكوك، فهرس ما بعده).
     * قد يعيد نصاً غير مغلق (اقتباس معلّق) — المتصل يقرر.
     */
    fun readToken(raw: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            when {
                c == ' ' || c == '\t' || c == ';' || c == '>' || c == '&' || c == '|' || c == '#' -> break

                c == '\'' -> {
                    val close = raw.indexOf('\'', i + 1)
                    if (close < 0) {
                        sb.append(raw, i + 1, n)
                        return sb.toString() to n
                    }
                    sb.append(raw, i + 1, close)
                    i = close + 1
                }

                c == '"' -> {
                    i++
                    var closed = false
                    while (i < n) {
                        val d = raw[i]
                        when {
                            d == '\\' && i + 1 < n && (raw[i + 1] == '"' || raw[i + 1] == '\\') -> {
                                sb.append(raw[i + 1])
                                i += 2
                            }

                            d == '"' -> {
                                closed = true
                                i++
                                break
                            }

                            else -> {
                                sb.append(d)
                                i++
                            }
                        }
                    }
                    if (!closed) return sb.toString() to n
                }

                c == '\\' -> {
                    if (i + 1 < n) {
                        sb.append(raw[i + 1])
                        i += 2
                    } else {
                        i = n
                    }
                }

                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString() to i
    }
}
