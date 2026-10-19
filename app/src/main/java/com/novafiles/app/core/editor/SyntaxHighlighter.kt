package com.novafiles.app.core.editor

/**
 * v0.17 — مُلوِّن الشيفرة (tokenizer مبسّط).
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * المخرجات امتدادات (نطاقات [start,end)) على سطر واحد — تُحوَّل في UI إلى
 * AnnotatedString بألوان الثيم. تُغطى: Kotlin، Java، Python، JavaScript،
 * TypeScript، JSON، XML/HTML، CSS، Bash، SQL. أما غير المعروف فيُعاد بلا امتدادات.
 *
 * الأسلوب: خطاطيف بسيطة بالحالة الحالية (سلسلة/تعليق) — لا regex ثقيل،
 * سلوك حتمي وقابل للاختبار الدقيق بالمواضع.
 */

enum class TokenKind { KEYWORD, STRING, COMMENT, NUMBER, ANNOTATION, TAG, PROPERTY, PUNCT }

data class Token(val kind: TokenKind, val start: Int, val end: Int) {
    val length: Int get() = end - start
}

object SyntaxHighlighter {

    /** اكتشاف اللغة من الامتداد — يعيد null للأنواع غير المدعومة */
    fun languageOf(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js", "mjs" -> "javascript"
            "ts", "tsx", "jsx" -> "typescript"
            "json" -> "json"
            "xml", "html", "htm", "svg", "gradle" -> "xml"
            "css" -> "css"
            "sh", "bash", "zsh" -> "bash"
            "sql" -> "sql"
            "c", "h", "cpp", "hpp", "go", "rs", "php", "rb", "swift", "cs" -> "clike"
            else -> null
        }
    }

    private val KOTLIN_KW = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
        "in", "interface", "is", "null", "object", "package", "return", "super", "this",
        "throw", "true", "try", "typealias", "val", "var", "when", "while", "import",
        "private", "public", "internal", "protected", "override", "open", "abstract",
        "suspend", "data", "sealed", "enum", "companion", "lateinit", "by", "get", "set",
    )
    private val JAVA_KW = setOf(
        "abstract", "boolean", "break", "byte", "case", "catch", "char", "class", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "if", "implements", "import", "instanceof", "int", "interface", "long", "new",
        "null", "package", "private", "protected", "public", "return", "static", "super",
        "switch", "this", "throw", "true", "false", "try", "void", "while", "var",
    )
    private val PYTHON_KW = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
        "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
        "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return",
        "True", "try", "while", "with", "yield", "self",
    )
    private val JS_KW = setOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "false", "finally", "for",
        "function", "if", "import", "in", "instanceof", "let", "new", "null", "of", "return",
        "static", "super", "switch", "this", "throw", "true", "try", "typeof", "undefined",
        "var", "void", "while", "yield",
    )
    private val SQL_KW = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete",
        "create", "table", "index", "view", "drop", "alter", "add", "and", "or", "not",
        "null", "join", "left", "right", "inner", "outer", "on", "group", "by", "order",
        "having", "limit", "offset", "primary", "key", "foreign", "references", "as", "distinct",
        "union", "all", "exists", "between", "like", "in", "is",
    )
    private val BASH_KW = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
        "function", "return", "exit", "local", "export", "echo", "cd", "sudo", "apt", "npm",
        "git", "in", "read", "shift", "break", "continue", "source",
    )

    private val keywords: Map<String, Set<String>> = mapOf(
        "kotlin" to KOTLIN_KW, "java" to JAVA_KW, "python" to PYTHON_KW,
        "javascript" to JS_KW, "typescript" to JS_KW, "sql" to SQL_KW, "bash" to BASH_KW,
        "clike" to JAVA_KW,
    )
    private val hashComments: Set<String> = setOf("python", "bash")
    private val slashComments: Set<String> =
        setOf("kotlin", "java", "javascript", "typescript", "clike", "css", "sql")

    /**
     * تلوين سطر واحد — إدخال سطر منفصل يبسط الاستئناف من UI (سطر بسطر).
     * @param inBlockComment هل السطر يبدأ داخل تعليق كتلة؟
     * @return الامتدادات + هل ما زلنا داخل تعليق كتلة في نهاية السطر
     */
    fun highlightLine(
        line: String,
        language: String,
        inBlockComment: Boolean = false,
    ): Pair<List<Token>, Boolean> {
        val tokens = mutableListOf<Token>()
        var i = 0
        var inBlock = inBlockComment
        val kws = keywords[language] ?: emptySet()

        fun wordAt(idx: Int): String {
            var e = idx
            while (e < line.length && (line[e].isLetterOrDigit() || line[e] == '_')) e++
            return line.substring(idx, e)
        }

        if (inBlock) {
            val end = line.indexOf("*/")
            if (end < 0) return Pair(listOf(Token(TokenKind.COMMENT, 0, line.length)), true)
            tokens.add(Token(TokenKind.COMMENT, 0, end + 2))
            i = end + 2
            inBlock = false
        }

        while (i < line.length) {
            val c = line[i]
            when {
                // تعليقات الأسطر
                language in slashComments && line.startsWith("//", i) -> {
                    tokens.add(Token(TokenKind.COMMENT, i, line.length)); i = line.length
                }
                language in hashComments && c == '#' -> {
                    tokens.add(Token(TokenKind.COMMENT, i, line.length)); i = line.length
                }
                // بداية تعليق كتلة
                language in slashComments && line.startsWith("/*", i) -> {
                    val end = line.indexOf("*/", i + 2)
                    if (end < 0) {
                        tokens.add(Token(TokenKind.COMMENT, i, line.length)); i = line.length; inBlock = true
                    } else {
                        tokens.add(Token(TokenKind.COMMENT, i, end + 2)); i = end + 2
                    }
                }
                language == "xml" && line.startsWith("<!--", i) -> {
                    val end = line.indexOf("-->", i + 4)
                    if (end < 0) {
                        tokens.add(Token(TokenKind.COMMENT, i, line.length)); i = line.length; inBlock = true
                    } else {
                        tokens.add(Token(TokenKind.COMMENT, i, end + 3)); i = end + 3
                    }
                }
                // سلاسل نصية (json: مفتاح قبول النقطتين ⇒ PROPERTY)
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != c) {
                        if (line[j] == '\\') j++ // تخطي الحرف المهرب
                        j++
                    }
                    val end = if (j < line.length) j + 1 else line.length
                    if (language == "json") {
                        var k = end
                        while (k < line.length && line[k] == ' ') k++
                        if (k < line.length && line[k] == ':') {
                            tokens.add(Token(TokenKind.PROPERTY, i, end))
                        } else {
                            tokens.add(Token(TokenKind.STRING, i, end))
                        }
                    } else {
                        tokens.add(Token(TokenKind.STRING, i, end))
                    }
                    i = end
                }
                // XML: وسم أو خاصية
                language == "xml" && c == '<' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != '>' && line[j] != ' ') j++
                    val end = if (j < line.length) j else line.length
                    tokens.add(Token(TokenKind.TAG, i, end)); i = end
                }
                // أرقام
                c.isDigit() -> {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '.' ||
                            (line[j] == '_' && j + 1 < line.length && line[j + 1].isDigit()))
                    ) j++
                    tokens.add(Token(TokenKind.NUMBER, i, j)); i = j
                }
                // كلمات مفتاحية / خصائص JSON
                c.isLetter() || c == '_' -> {
                    val w = wordAt(i)
                    val nextChar = line.getOrNull(i + w.length)
                    when {
                        language == "json" && nextChar == ':' ->
                            tokens.add(Token(TokenKind.PROPERTY, i, i + w.length))
                        kws.any { it.equals(w, ignoreCase = true) } ->
                            tokens.add(Token(TokenKind.KEYWORD, i, i + w.length))
                        w.startsWith("@") || (w.startsWith("_") && w.length > 1) ->
                            tokens.add(Token(TokenKind.ANNOTATION, i, i + w.length))
                        else -> { /* معرّف عادي — بلا امتداد */ }
                    }
                    i += w.length
                }
                else -> i++
            }
        }
        return Pair(tokens.filter { it.end <= line.length && it.start < it.end }, inBlock)
    }

    /** تلوين نص متعدد الأسطر — يعيد امتدادات لكل سطر (للاستخدام المباشر في UI) */
    fun highlight(lines: List<String>, language: String): List<List<Token>> {
        var inBlock = false
        return lines.map { line ->
            val (tokens, stillInBlock) = highlightLine(line, language, inBlock)
            inBlock = stillInBlock
            tokens
        }
    }
}
