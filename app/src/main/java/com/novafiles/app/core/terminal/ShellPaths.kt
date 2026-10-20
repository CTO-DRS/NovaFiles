package com.novafiles.app.core.terminal

import java.io.File

/**
 * أدوات المسارات لشل NovaFiles — خالصة وقابلة للاختبار 100%.
 *
 * حلّ الوسائط النسبية، تطبيع «.» و«..»، تحويل أنماط glob إلى Regex،
 * وتقسيم رمز الإكمال إلى (مجلد، بادئة).
 */
object ShellPaths {

    /**
     * حلّ مسار وسيط إلى مسار مطلق مطبَّع:
     * - «~» أو «~/…» → الجذر الرئيسي
     * - مطلق (يبدأ بـ/) → تطبيع مباشر
     * - نسبي → يُلحق بمجلد العمل الحالي
     * يعالج «.» و«..» (و«..» فوق الجذر يبقى عند الجذر).
     */
    fun resolve(cwd: String, home: String, arg: String): String {
        val base = when {
            arg == "~" -> home
            arg.startsWith("~/") -> home + arg.removePrefix("~")
            arg.startsWith("/") -> arg
            else -> "$cwd/$arg"
        }
        return normalize(base)
    }

    /** تطبيع مسار مطلق: طيّ الشرطات، إزالة «.»، صعود «..» ضمن حدود الجذر */
    fun normalize(path: String): String {
        if (path.isEmpty()) return "/"
        val absolute = path.startsWith("/")
        val out = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += seg
            }
        }
        val joined = out.joinToString("/")
        return when {
            !absolute -> if (joined.isEmpty()) "." else joined
            joined.isEmpty() -> "/"
            else -> "/$joined"
        }
    }

    /**
     * تقسيم رمز إكمال مسار إلى (مسار المجلد الخام، بادئة الاسم).
     * «a/b/c» → («a/b», «c») · «a/b/» → («a/b», «») · «doc» → («», «doc»)
     * يحترم التهريب: «my\ file/x» → («my file», «x»).
     */
    fun splitDirPrefix(token: String): Pair<String, String> {
        val unescaped = unescape(token)
        val idx = unescaped.lastIndexOf('/')
        return if (idx < 0) "" to unescaped
        else unescaped.substring(0, idx) to unescaped.substring(idx + 1)
    }

    /** فك تهريب الرموز المكتوبة في السطر: ‎\ (مسافة) و \\ و \" */
    fun unescape(token: String): String {
        val sb = StringBuilder(token.length)
        var i = 0
        while (i < token.length) {
            val c = token[i]
            if (c == '\\' && i + 1 < token.length) {
                sb.append(token[i + 1])
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** تهريب مسار لإدراجه في السطر: مسافة/اقتباس/خط مائل عكسي تُسبَب بـ \ */
    fun escape(path: String): String =
        path.replace("\\", "\\\\").replace(" ", "\\ ").replace("\"", "\\\"")

    /**
     * تحويل نمط glob للاسم (find -name) إلى Regex:
     * «*» أي عدد من المحارف، «?» محرف واحد، الباقي حرفي.
     */
    fun globToRegex(pattern: String): Regex {
        val sb = StringBuilder()
        for (c in pattern) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return sb.toString().toRegex()
    }

    /** هل المسار داخل نطاق [root] (أو هو نفسه)؟ — يُستخدم لحراسة rm */
    fun isWithinOrEqual(path: File, root: File): Boolean {
        val p = path.canonicalFile.path.let { if (it.length > 1) it.trimEnd('/') else it }
        val r = root.canonicalFile.path.let { if (it.length > 1) it.trimEnd('/') else it }
        return p == r || p.startsWith("$r/")
    }
}
