package com.novafiles.app.core.terminal

import com.novafiles.app.util.Format
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * شل NovaFiles — v0.28: طرفية مدمجة تعمل فوق طبقة الملفات مباشرة.
 *
 * الأوامر المطلوبة في الخارطة: ls/cd/cp/mv/rm/mkdir/touch/du/find/cat/zip/unzip/hash
 * إضافة إلى بنية تحتية: pwd/echo/history/help/clear (ولقب ll = ls -l).
 *
 * لا يطلق المحرك أي عملية خارجية ولا يعتمد على /system/bin —
 * كل شيء Kotlin خالص يُختبر على JVM بأقراص مؤقتة.
 *
 * سياسة الأمان:
 * - rm يرفض إزالة جذر التخزين الرئيسي
 * - cp/mv يرفضان نسخ/نقل مجلد إلى داخله أو أحد فروعه
 * - unzip يفوّض حارس Zip-Slip المشترك (ArchiveSafety)
 * - ناتج كل أمر محدود بسقف أسطر لحماية الواجهة
 */
class NovaShell(
    private val home: File,
    startDir: File = home,
    private val now: () -> Long = System::currentTimeMillis,
) {

    // ─────────────────────────── الحالة ───────────────────────────

    var cwd: File = startDir.absoluteFile
        private set

    private var prevDir: File? = null
    private val historyLines = ArrayDeque<String>()

    val history: List<String> get() = historyLines.toList()

    fun cwdPath(): String = cwd.absolutePath

    // ─────────────────────────── التنفيذ ───────────────────────────

    fun execute(raw: String): ShellOutcome {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ShellOutcome(emptyList())
        if (trimmed != "history") remember(trimmed)
        return try {
            val parsed = CommandParser.parse(trimmed).orThrow()
            var ok = true
            var cleared = false
            val all = ArrayList<ShellLine>()
            for (seg in parsed.segments) {
                if (seg.joiner == Joiner.AND_THEN && !ok) break
                val res = runSegment(seg.tokens, seg.redirect)
                ok = res.ok
                cleared = cleared || res.clearRequested
                all += res.lines
            }
            ShellOutcome(all, cleared, ok)
        } catch (e: ShellParseException) {
            ShellOutcome(listOf(ShellLine.err(e.message ?: "parse error")), ok = false)
        }
    }

    private class SegmentResult(
        val lines: List<ShellLine> = emptyList(),
        val ok: Boolean = true,
        val clearRequested: Boolean = false,
    )

    private fun runSegment(tokens: List<String>, redirect: ShellRedirect?): SegmentResult {
        val res = try {
            dispatch(tokens)
        } catch (e: ShellParseException) {
            SegmentResult(listOf(ShellLine.err(e.message ?: "error")), ok = false)
        } catch (e: Exception) {
            SegmentResult(listOf(ShellLine.err(errText(tokens.firstOrNull() ?: "?", e.message ?: "error"))), ok = false)
        }
        if (redirect == null) return res

        // توجيه الإخراج: الأخطاء تبقى على الشاشة والبقية إلى الملف
        val toFile = res.lines.filter { it.color != ShellColor.RED }
        val toScreen = res.lines.filter { it.color == ShellColor.RED }
        return try {
            val target = resolve(redirect.path)
            if (target.isDirectory) {
                SegmentResult(toScreen + ShellLine.err(errText(">", "is a directory: ${target.path}")), ok = false)
            } else {
                target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                if (redirect.append) {
                    target.appendText(toFile.joinToString("") { it.text + "\n" }, Charsets.UTF_8)
                } else {
                    target.writeText(toFile.joinToString("") { it.text + "\n" }, Charsets.UTF_8)
                }
                SegmentResult(toScreen, ok = res.ok)
            }
        } catch (e: Exception) {
            SegmentResult(toScreen + ShellLine.err(errText(">", e.message ?: "redirect failed")), ok = false)
        }
    }

    private fun dispatch(tokens: List<String>): SegmentResult {
        if (tokens.isEmpty()) return SegmentResult()
        val name = tokens.first()
        val rest = tokens.drop(1)
        val cmd = when (name) {
            "ll" -> "ls"
            else -> name
        }
        return when (cmd) {
            "pwd" -> SegmentResult(listOf(ShellLine.ok(cwd.absolutePath)))
            "echo" -> cmdEcho(rest)
            "cd" -> cmdCd(rest)
            "ls" -> cmdLs(rest, name == "ll")
            "cp" -> cmdCopyMove(rest, move = false)
            "mv" -> cmdCopyMove(rest, move = true)
            "rm" -> cmdRm(rest)
            "mkdir" -> cmdMkdir(rest)
            "touch" -> cmdTouch(rest)
            "cat" -> cmdCat(rest)
            "du" -> cmdDu(rest)
            "find" -> cmdFind(rest)
            "zip" -> cmdZip(rest)
            "unzip" -> cmdUnzip(rest)
            "hash" -> cmdHash(rest)
            "history" -> cmdHistory()
            "help" -> SegmentResult(buildHelp())
            "clear" -> SegmentResult(emptyList(), clearRequested = true)
            else -> SegmentResult(
                listOf(
                    ShellLine.err("nova: command not found: $name"),
                    ShellLine.dim("type help for the command list"),
                ),
                ok = false,
            )
        }
    }

    // ─────────────────────────── وسائط مساعدة ───────────────────────────

    private class Args(
        val flags: MutableSet<Char> = mutableSetOf(),
        val long: MutableSet<String> = mutableSetOf(),
        val positional: MutableList<String> = mutableListOf(),
    )

    private fun splitArgs(tokens: List<String>): Args {
        val a = Args()
        var endFlags = false
        for (t in tokens) {
            when {
                !endFlags && t == "--" -> endFlags = true
                !endFlags && t.startsWith("--") && t.length > 2 -> a.long += t.substring(2)
                !endFlags && t.length > 1 && t.startsWith("-") && t != "-" ->
                    t.substring(1).forEach { a.flags += it }
                else -> a.positional += t
            }
        }
        return a
    }

    private fun errText(cmd: String, msg: String) = "$cmd: $msg"

    private fun requireFlags(cmd: String, args: Args, known: String) {
        val unknown = args.flags - known.toSet()
        if (unknown.isNotEmpty()) throw ShellParseException("$cmd: invalid option -- '${unknown.first()}'")
        if (args.long.isNotEmpty()) throw ShellParseException("$cmd: unrecognized option '--${args.long.first()}'")
    }

    private fun resolve(arg: String): File =
        File(ShellPaths.resolve(cwd.absolutePath, home.absolutePath, arg))

    private fun remember(line: String) {
        if (historyLines.lastOrNull() == line) return
        historyLines.addLast(line)
        while (historyLines.size > HISTORY_MAX) historyLines.removeFirst()
    }

    // ─────────────────────────── الأوامر ───────────────────────────

    private fun cmdEcho(rest: List<String>): SegmentResult =
        SegmentResult(listOf(ShellLine.ok(rest.joinToString(" "))))

    private fun cmdCd(rest: List<String>): SegmentResult {
        if (rest.size > 1) return SegmentResult(listOf(ShellLine.err("cd: too many arguments")), ok = false)
        val arg = rest.firstOrNull()
        val target = when {
            arg == null || arg == "~" -> home
            arg == "-" -> prevDir ?: return SegmentResult(
                listOf(ShellLine.err("cd: no previous directory")), ok = false,
            )
            else -> resolve(arg)
        }
        if (!target.exists()) {
            return SegmentResult(listOf(ShellLine.err("cd: no such directory: ${target.path}")), ok = false)
        }
        if (!target.isDirectory) {
            return SegmentResult(listOf(ShellLine.err("cd: not a directory: ${target.path}")), ok = false)
        }
        prevDir = cwd
        cwd = target.absoluteFile
        if (arg == "-") return SegmentResult(listOf(ShellLine.dim(cwd.absolutePath)))
        return SegmentResult()
    }

    private fun cmdLs(rest: List<String>, implicitLong: Boolean): SegmentResult {
        val args = splitArgs(rest)
        requireFlags("ls", args, "la")
        val long = implicitLong || 'l' in args.flags
        val all = 'a' in args.flags
        val targets = args.positional.ifEmpty { listOf(".") }

        val lines = ArrayList<ShellLine>()
        var ok = true

        val groups = ArrayList<File>()   // مجلدات تُسرَد محتوياتها
        val directFiles = ArrayList<File>() // ملفات تُعرض بأسمائها مباشرة

        for (t in targets) {
            val f = resolve(t)
            if (!f.exists()) {
                lines += ShellLine.err("ls: cannot access '$t': No such file or directory")
                ok = false
                continue
            }
            if (f.isDirectory) groups += f else directFiles += f
        }

        fun childEntries(dir: File): List<Pair<File, Boolean>> {
            val children = dir.listFiles()?.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }),
            ) ?: emptyList()
            return children.filter { all || !it.name.startsWith(".") }.map { it to it.name.startsWith(".") }
        }

        fun longRow(f: File, hiddenMark: Boolean): ShellLine {
            val type = if (f.isDirectory) "d" else "-"
            val size = if (f.isDirectory) "-" else Format.bytes(f.length())
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(f.lastModified()))
            val color = when {
                f.isDirectory -> ShellColor.BLUE
                hiddenMark -> ShellColor.DIM
                else -> ShellColor.DEFAULT
            }
            return ShellLine("$type  ${size.padStart(9)}  $date  ${f.name}", color)
        }

        var first = true
        if (directFiles.isNotEmpty()) {
            for (f in directFiles.sortedBy { it.name.lowercase(Locale.ROOT) }) {
                lines += if (long) longRow(f, false) else ShellLine.ok(f.name)
            }
            first = false
        }
        for (dir in groups) {
            if (!first) lines += ShellLine.dim("")
            if (groups.size + (if (directFiles.isNotEmpty()) 1 else 0) > 1) {
                lines += ShellLine.dim("${dir.absolutePath}:")
            }
            val entries = childEntries(dir)
            for ((f, hidden) in entries) {
                lines += if (long) longRow(f, hidden) else {
                    when {
                        f.isDirectory -> ShellLine.dir(f.name)
                        hidden -> ShellLine.dim(f.name)
                        else -> ShellLine.ok(f.name)
                    }
                }
            }
            if (entries.isEmpty()) lines += ShellLine.dim("(empty)")
            first = false
        }
        return SegmentResult(cap(lines, "ls"), ok = ok)
    }

    private fun cmdCopyMove(rest: List<String>, move: Boolean): SegmentResult {
        val cmd = if (move) "mv" else "cp"
        val args = splitArgs(rest)
        requireFlags(cmd, args, if (move) "f" else "rRf")
        val recursive = 'r' in args.flags || 'R' in args.flags
        if (args.positional.size < 2) {
            return SegmentResult(listOf(ShellLine.err("usage: $cmd [-r] source... target")), ok = false)
        }
        val target = resolve(args.positional.last())
        val sources = args.positional.dropLast(1).map { resolve(it) }

        val lines = ArrayList<ShellLine>()
        var ok = true
        var total = 0

        if (target.isDirectory) {
            for (src in sources) {
                try {
                    if (!move && src.isDirectory && !recursive) {
                        throw ShellParseException("$cmd: -r not specified; omitting directory '${src.name}'")
                    }
                    if (ShellPaths.isWithinOrEqual(target, src)) {
                        throw ShellParseException("$cmd: cannot copy '${src.name}' into itself")
                    }
                    total += if (move) moveInto(src, File(target, src.name)) else copyTree(src, File(target, src.name))
                } catch (e: ShellParseException) {
                    lines += ShellLine.err(e.message ?: "$cmd: failed")
                    ok = false
                }
            }
        } else {
            if (sources.size > 1) {
                return SegmentResult(listOf(ShellLine.err("$cmd: target '${target.path}' is not a directory")), ok = false)
            }
            val src = sources.first()
            try {
                if (!move && src.isDirectory && !recursive) {
                    throw ShellParseException("$cmd: -r not specified; omitting directory '${src.name}'")
                }
                if (src.isDirectory && ShellPaths.isWithinOrEqual(target.parentFile ?: home, src)) {
                    throw ShellParseException("$cmd: cannot copy '${src.name}' into itself")
                }
                target.parentFile?.let { if (!it.exists()) it.mkdirs() }
                total = if (move) moveInto(src, target) else copyTree(src, target)
            } catch (e: ShellParseException) {
                lines += ShellLine.err(e.message ?: "$cmd: failed")
                ok = false
            }
        }
        if (ok) lines += ShellLine.dim("${if (move) "moved" else "copied"} $total item(s)")
        return SegmentResult(lines, ok = ok)
    }

    /** نقل عنصر إلى وجهة محددة — يعيد عدد العناصر المنقولة */
    private fun moveInto(src: File, dst: File): Int {
        if (!src.exists()) throw ShellParseException("mv: no such file or directory: ${src.path}")
        if (src.isFile) {
            if (dst.isDirectory) throw ShellParseException("mv: cannot overwrite directory '${dst.path}' with a file")
            if (src.renameTo(dst)) return 1
            dst.parentFile?.mkdirs()
            copyRec(src, dst)
            src.delete()
            return 1
        }
        // مجلد
        if (dst.exists() && !dst.isDirectory) throw ShellParseException("mv: cannot overwrite file '${dst.path}' with a directory")
        if (src.renameTo(dst)) return countChildren(dst)
        dst.mkdirs()
        var count = 0
        src.listFiles()?.forEach { child -> count += moveInto(child, File(dst, child.name)) }
        src.deleteRecursively()
        return count
    }

    private fun countChildren(dir: File): Int =
        dir.walkTopDown().filter { it.isFile }.count()

    /** نسخ عنصر إلى وجهة محددة — يعيد عدد الملفات المنسوخة */
    private fun copyTree(src: File, dst: File): Int {
        if (!src.exists()) throw ShellParseException("cp: no such file or directory: ${src.path}")
        if (src.isFile) {
            if (dst.isDirectory) throw ShellParseException("cp: cannot overwrite directory '${dst.path}' with a file")
            copyRec(src, dst)
            return 1
        }
        if (dst.exists() && !dst.isDirectory) throw ShellParseException("cp: cannot overwrite file '${dst.path}' with a directory")
        dst.mkdirs()
        var count = 0
        src.listFiles()?.forEach { child -> count += copyTree(child, File(dst, child.name)) }
        return count
    }

    private fun copyRec(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { copyRec(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            dst.outputStream().use { out ->
                src.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun cmdRm(rest: List<String>): SegmentResult {
        val args = splitArgs(rest)
        requireFlags("rm", args, "rf")
        val recursive = 'r' in args.flags || 'R' in args.flags
        if (args.positional.isEmpty()) {
            return SegmentResult(listOf(ShellLine.err("usage: rm [-r] path...")), ok = false)
        }
        val lines = ArrayList<ShellLine>()
        var ok = true
        for (t in args.positional) {
            val f = resolve(t)
            when {
                !f.exists() -> {
                    lines += ShellLine.err("rm: no such file or directory: $t")
                    ok = false
                }

                f.canonicalFile.path == "/" || f.canonicalFile == home.canonicalFile -> {
                    lines += ShellLine.err("rm: refusing to remove the storage root")
                    ok = false
                }

                f.isDirectory && recursive -> {
                    if (f.deleteRecursively()) Unit else {
                        lines += ShellLine.err("rm: failed to delete: $t")
                        ok = false
                    }
                }

                f.isDirectory -> {
                    val empty = f.listFiles()?.isEmpty() ?: true
                    if (empty) f.delete() else {
                        lines += ShellLine.err("rm: directory not empty (use -r): $t")
                        ok = false
                    }
                }

                else -> if (!f.delete()) {
                    lines += ShellLine.err("rm: failed to delete: $t")
                    ok = false
                }
            }
        }
        return SegmentResult(lines, ok = ok)
    }

    private fun cmdMkdir(rest: List<String>): SegmentResult {
        val args = splitArgs(rest)
        requireFlags("mkdir", args, "p")
        if (args.positional.isEmpty()) {
            return SegmentResult(listOf(ShellLine.err("usage: mkdir [-p] dir...")), ok = false)
        }
        val lines = ArrayList<ShellLine>()
        var ok = true
        for (t in args.positional) {
            val f = resolve(t)
            when {
                f.isDirectory && 'p' in args.flags -> Unit
                f.exists() -> {
                    lines += ShellLine.err("mkdir: already exists: $t")
                    ok = false
                }

                else -> {
                    val made = if ('p' in args.flags) f.mkdirs() else f.mkdir()
                    if (!made) {
                        lines += ShellLine.err("mkdir: failed: $t")
                        ok = false
                    }
                }
            }
        }
        return SegmentResult(lines, ok = ok)
    }

    private fun cmdTouch(rest: List<String>): SegmentResult {
        val args = splitArgs(rest)
        requireFlags("touch", args, "")
        if (args.positional.isEmpty()) {
            return SegmentResult(listOf(ShellLine.err("usage: touch file...")), ok = false)
        }
        val lines = ArrayList<ShellLine>()
        var ok = true
        for (t in args.positional) {
            val f = resolve(t)
            when {
                f.exists() -> f.setLastModified(now())
                f.parentFile?.isDirectory == true -> {
                    try {
                        f.createNewFile()
                    } catch (e: Exception) {
                        lines += ShellLine.err("touch: cannot create: $t")
                        ok = false
                    }
                }

                else -> {
                    lines += ShellLine.err("touch: no such directory: ${f.parent ?: "/"}")
                    ok = false
                }
            }
        }
        return SegmentResult(lines, ok = ok)
    }

    private fun cmdCat(rest: List<String>): SegmentResult {
        val args = splitArgs(rest)
        requireFlags("cat", args, "n")
        if (args.positional.isEmpty()) {
            return SegmentResult(listOf(ShellLine.err("usage: cat [-n] file...")), ok = false)
        }
        val lines = ArrayList<ShellLine>()
        var ok = true
        var number = 0
        for (t in args.positional) {
            val f = resolve(t)
            when {
                !f.exists() -> {
                    lines += ShellLine.err("cat: no such file: $t")
                    ok = false
                }

                f.isDirectory -> {
                    lines += ShellLine.err("cat: is a directory: $t")
                    ok = false
                }

                else -> f.useLines(Charsets.UTF_8) { seq ->
                    for (l in seq) {
                        if (lines.size >= CAT_MAX_LINES) {
                            lines += ShellLine.dim("... output truncated at $CAT_MAX_LINES lines")
                            return@useLines
                        }
                        number++
                        lines += if ('n' in args.flags) {
                            ShellLine.ok(number.toString().padStart(6) + "  " + l)
                        } else {
                            ShellLine.ok(l)
                        }
                    }
                }
            }
        }
        return SegmentResult(lines, ok = ok)
    }

    private fun cmdDu(rest: List<String>): SegmentResult {
        val args = splitArgs(rest)
        requireFlags("du", args, "h")
        val targets = args.positional.ifEmpty { listOf(".") }
        val lines = ArrayList<ShellLine>()
        var ok = true
        for (t in targets) {
            val f = resolve(t)
            if (!f.exists()) {
                lines += ShellLine.err("du: no such file or directory: $t")
                ok = false
                continue
            }
            val bytes = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val shown = if ('h' in args.flags) Format.bytes(bytes) else bytes.toString()
            lines += ShellLine.ok("$shown\t${f.absolutePath}")
        }
        return SegmentResult(lines, ok = ok)
    }

    private fun cmdFind(rest: List<String>): SegmentResult {
        // خيارات الكلمات: -name/-type/-maxdepth — تُحلَّل يدوياً
        var start: File = cwd
        var pattern: Regex? = null
        var type: Char? = null
        var maxDepth = FIND_MAX_DEPTH
        var i = 0
        while (i < rest.size) {
            when (rest[i]) {
                "-name" -> {
                    if (i + 1 >= rest.size) return SegmentResult(listOf(ShellLine.err("find: -name requires a pattern")), ok = false)
                    pattern = ShellPaths.globToRegex(rest[i + 1])
                    i += 2
                }

                "-type" -> {
                    if (i + 1 >= rest.size || rest[i + 1].length != 1 || rest[i + 1][0] !in "fd") {
                        return SegmentResult(listOf(ShellLine.err("find: -type must be f or d")), ok = false)
                    }
                    type = rest[i + 1][0]
                    i += 2
                }

                "-maxdepth" -> {
                    val v = rest.getOrNull(i + 1)?.toIntOrNull()
                    if (v == null || v < 1 || v > 32) {
                        return SegmentResult(listOf(ShellLine.err("find: -maxdepth must be 1..32")), ok = false)
                    }
                    maxDepth = v
                    i += 2
                }

                else -> {
                    if (i == 0) start = resolve(rest[i]) else {
                        return SegmentResult(listOf(ShellLine.err("find: unknown option: ${rest[i]}")), ok = false)
                    }
                    i++
                }
            }
        }
        if (!start.exists()) {
            return SegmentResult(listOf(ShellLine.err("find: no such file or directory: ${start.path}")), ok = false)
        }
        val lines = ArrayList<ShellLine>()
        fun walk(dir: File, depth: Int) {
            for (child in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                val matchType = when (type) {
                    'f' -> child.isFile
                    'd' -> child.isDirectory
                    else -> true
                }
                if (matchType && (pattern == null || pattern.matches(child.name))) {
                    if (lines.size >= FIND_MAX_RESULTS) {
                        lines += ShellLine.dim("... results truncated at $FIND_MAX_RESULTS")
                        return
                    }
                    lines += ShellLine.ok(child.absolutePath)
                }
                if (child.isDirectory && depth < maxDepth) walk(child, depth + 1)
            }
        }
        // نقطة البداية نفسها تُختبر ضد المرشحات (سلوك POSIX)
        val startMatch = when (type) {
            'f' -> start.isFile
            'd' -> start.isDirectory
            else -> true
        }
        if (startMatch && (pattern == null || pattern.matches(start.name))) {
            lines += ShellLine.ok(start.absolutePath)
        }
        if (start.isDirectory) walk(start, 1)
        return SegmentResult(cap(lines, "find"))
    }

    private fun cmdZip(rest: List<String>): SegmentResult {
        val args = splitArgs(rest)
        requireFlags("zip", args, "")
        if (args.positional.size < 2) {
            return SegmentResult(listOf(ShellLine.err("usage: zip archive.zip source...")), ok = false)
        }
        val out = resolve(args.positional.first())
        val sources = args.positional.drop(1).map { resolve(it) }
        val missing = sources.filter { !it.exists() }
        if (missing.isNotEmpty()) {
            return SegmentResult(listOf(ShellLine.err("zip: no such file or directory: ${missing.first().path}")), ok = false)
        }
        val count = com.novafiles.app.core.archive.ZipArchive.createZip(sources, out)
        val size = Format.bytes(out.length())
        return SegmentResult(listOf(ShellLine.good("created ${out.path} ($count file(s), $size)")))
    }

    private fun cmdUnzip(rest: List<String>): SegmentResult {
        val args = splitArgs(rest)
        requireFlags("unzip", args, "")
        if (args.positional.isEmpty()) {
            return SegmentResult(listOf(ShellLine.err("usage: unzip archive.zip [destDir]")), ok = false)
        }
        val zip = resolve(args.positional.first())
        if (!zip.isFile) {
            return SegmentResult(listOf(ShellLine.err("unzip: no such file: ${zip.path}")), ok = false)
        }
        val dest = args.positional.getOrNull(1)?.let { resolve(it) } ?: cwd
        val count = com.novafiles.app.core.archive.ZipArchive.extractAll(zip, dest)
        return SegmentResult(listOf(ShellLine.good("extracted $count file(s) to ${dest.absolutePath}")))
    }

    private fun cmdHash(rest: List<String>): SegmentResult {
        val args = splitArgs(rest)
        requireFlags("hash", args, "a")
        var algo = "sha256"
        if ('a' in args.flags) {
            if (args.positional.isEmpty()) {
                return SegmentResult(listOf(ShellLine.err("hash: -a requires md5|sha1|sha256")), ok = false)
            }
            algo = args.positional.removeAt(0).lowercase(Locale.US)
        }
        if (algo !in HASH_ALGOS) {
            return SegmentResult(listOf(ShellLine.err("hash: unsupported algorithm: $algo (md5|sha1|sha256)")), ok = false)
        }
        if (args.positional.isEmpty()) {
            return SegmentResult(listOf(ShellLine.err("usage: hash [-a md5|sha1|sha256] file...")), ok = false)
        }
        val lines = ArrayList<ShellLine>()
        var ok = true
        for (t in args.positional) {
            val f = resolve(t)
            when {
                !f.exists() -> {
                    lines += ShellLine.err("hash: no such file: $t")
                    ok = false
                }

                f.isDirectory -> {
                    lines += ShellLine.err("hash: is a directory: $t")
                    ok = false
                }

                else -> {
                    val md = MessageDigest.getInstance(algo)
                    f.inputStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                        }
                    }
                    val hex = md.digest().joinToString("") { "%02x".format(it) }
                    lines += ShellLine.ok("$hex  ${f.name}")
                }
            }
        }
        return SegmentResult(lines, ok = ok)
    }

    private fun cmdHistory(): SegmentResult {
        val lines = historyLines.mapIndexed { idx, cmd ->
            ShellLine.ok((idx + 1).toString().padStart(4) + "  " + cmd)
        }
        return SegmentResult(if (lines.isEmpty()) listOf(ShellLine.dim("(no history yet)")) else lines)
    }

    private fun buildHelp(): List<ShellLine> {
        val rows = listOf(
            "ls [-la] [path...]     list directories (-l long, -a hidden)",
            "ll [path...]           ls -l",
            "cd [dir|-|~]           change directory (empty = home)",
            "pwd                    print working directory",
            "cp [-r] src... dst     copy (dirs need -r)",
            "mv [-f] src... dst     move/rename",
            "rm [-r] path...        remove (dirs need -r)",
            "mkdir [-p] dir...      create directories",
            "touch file...          create empty / update timestamp",
            "cat [-n] file...       print file contents",
            "du [-h] [path...]      disk usage per path",
            "find [path] [-name p] [-type f|d] [-maxdepth n]   search",
            "zip out.zip src...     create a ZIP archive",
            "unzip a.zip [dest]     extract a ZIP archive",
            "hash [-a md5|sha1|sha256] file...   checksums (default sha256)",
            "echo text...           print text",
            "history                command history",
            "clear                  clear the screen",
            "help                   this list",
            "",
            "also supported: 'a ; b' sequences, 'a && b' chains,",
            "output redirection '> file' and '>> file', quotes and \\ escapes.",
        )
        return rows.map { if (it.isEmpty()) ShellLine.dim("") else ShellLine.ok(it) }
    }

    // ─────────────────────────── إكمال المسارات ───────────────────────────

    fun complete(input: String, cursor: Int = input.length): PathCompleter.Completion =
        PathCompleter(home).complete(input, cursor, COMMAND_NAMES, cwd)

    // ─────────────────────────── ثوابت ───────────────────────────

    companion object {
        const val HISTORY_MAX = 200
        const val CAT_MAX_LINES = 2000
        const val FIND_MAX_DEPTH = 16
        const val FIND_MAX_RESULTS = 2000
        const val MAX_OUTPUT_LINES = 4000
        val HASH_ALGOS = setOf("md5", "sha1", "sha256")
        val COMMAND_NAMES = setOf(
            "ls", "ll", "cd", "pwd", "cp", "mv", "rm", "mkdir", "touch",
            "cat", "du", "find", "zip", "unzip", "hash", "echo", "history", "help", "clear",
        )

        private fun cap(lines: List<ShellLine>, cmd: String): List<ShellLine> =
            if (lines.size <= MAX_OUTPUT_LINES) lines
            else lines.take(MAX_OUTPUT_LINES) +
                ShellLine.warn("$cmd: output truncated at $MAX_OUTPUT_LINES lines")
    }
}
