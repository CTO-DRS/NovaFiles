package com.novafiles.app.core.ftp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** عنوان IPv4 المحلي للجهاز على الشبكة (wlan0 أولًا) — لعرضه في واجهة المشاركة */
fun localIpv4(preferWifi: Boolean = true): String? {
    val candidates = mutableListOf<Pair<String, String>>() // iface to ip
    try {
        val it = NetworkInterface.getNetworkInterfaces() ?: return null
        while (it.hasMoreElements()) {
            val nif = it.nextElement()
            if (!nif.isUp || nif.isLoopback) continue
            val addrs = nif.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is Inet4Address && !a.isLoopbackAddress) {
                    candidates.add(nif.name to a.hostAddress)
                }
            }
        }
    } catch (_: Exception) {
    }
    if (candidates.isEmpty()) return null
    return if (preferWifi) {
        candidates.firstOrNull { it.first.startsWith("wlan") }?.second
            ?: candidates.firstOrNull { it.first.startsWith("eth") }?.second
            ?: candidates.first().second
    } else {
        candidates.first().second
    }
}

/**
 * ══════════════════════════════════════════════════════════
 *  خادم FTP خالص بلغة Kotlin (بدون أي اعتمادية خارجية)
 *  ─ RFC 959 الأساسية + UTF-8 + الوضع المُنشِط PASV والفعّال PORT
 *  ─ مقصور على مجذر محدد ولا يمكن الهروب منه (حماية من ../)
 *  ─ قابل للاختبار على JVM مباشرة (لا يعتمد على Android)
 * ══════════════════════════════════════════════════════════
 */
class FtpServer(
    /** المجلد الجذر المشترك — كل المسارات مقصورة داخله */
    val root: File,
    /** منفذ التحكم */
    @Volatile var port: Int = 2121,
    @Volatile var username: String = "nova",
    @Volatile var password: String = "nova",
) {

    @Volatile var isRunning: Boolean = false
        private set

    /** سجل اختياري للأحداث (تستخدمه الخدمة/الشاشة للعرض) */
    @Volatile var onLog: ((String) -> Unit)? = null

    private var serverSocket: ServerSocket? = null
    private val clientSockets = java.util.Collections.synchronizedSet(HashSet<Socket>())
    private val passiveSockets = java.util.Collections.synchronizedSet(HashSet<ServerSocket>())
    private val dataSockets = java.util.Collections.synchronizedSet(HashSet<Socket>())
    private var acceptThread: Thread? = null
    private var clientSeq = 0

    // ═══════════════ دورة الحياة ═══════════════

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        if (!root.isDirectory && !root.mkdirs()) {
            log("root not accessible: $root")
            return false
        }
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = ss
            isRunning = true
            acceptThread = Thread({
                acceptLoop(ss)
            }, "ftp-accept").apply {
                isDaemon = true
                start()
            }
            log("FTP server started on port $port, root=$root")
            true
        } catch (e: Exception) {
            log("start failed: ${e.message}")
            isRunning = false
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        synchronized(passiveSockets) {
            passiveSockets.toList().forEach { try { it.close() } catch (_: Exception) {} }
            passiveSockets.clear()
        }
        synchronized(clientSockets) {
            clientSockets.toList().forEach { try { it.close() } catch (_: Exception) {} }
            clientSockets.clear()
        }
        synchronized(dataSockets) {
            dataSockets.toList().forEach { try { it.close() } catch (_: Exception) {} }
            dataSockets.clear()
        }
        log("FTP server stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (isRunning) {
            try {
                val client = ss.accept()
                if (!isRunning) { try { client.close() } catch (_: Exception) {}; break }
                val id = ++clientSeq
                Thread({
                    handleClient(client)
                }, "ftp-conn-$id").apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                if (isRunning) log("accept error: ${e.message}") else return
            }
        }
    }

    // ═══════════════ جلسة العميل ═══════════════

    private inner class Session(val socket: Socket) {
        val writer = BufferedOutputStream(socket.getOutputStream(), 8192)
        var authenticated = false
        var cwd: File = root
        var renameFrom: File? = null
        var restartOffset: Long = 0L
        /** مُنشئ اتصال البيانات القادم: إما PASV (قبول) أو PORT (اتصال صادر) */
        var dataSocketProvider: (() -> Socket)? = null

        fun reply(line: String) {
            try {
                writer.write((line + "\r\n").toByteArray(StandardCharsets.UTF_8))
                writer.flush()
            } catch (_: Exception) {}
        }
    }

    private fun handleClient(socket: Socket) {
        clientSockets.add(socket)
        try {
            socket.tcpNoDelay = true
            val session = Session(socket)
            session.reply("220 NovaFiles FTP ready")
            val reader = java.io.BufferedReader(
                java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8),
            )
            while (isRunning) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                handleCommand(session, line.trim())
            }
        } catch (_: Exception) {
            // انقطاع الاتصال — نغلق بصمت
        } finally {
            clientSockets.remove(socket)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleCommand(s: Session, line: String) {
        val sp = line.indexOf(' ')
        val cmd = (if (sp < 0) line else line.substring(0, sp)).uppercase(Locale.US)
        val arg = if (sp < 0) "" else line.substring(sp + 1).trim()

        // قبل المصادقة: USER / PASS / QUIT فقط
        if (!s.authenticated && cmd !in setOf("USER", "PASS", "QUIT")) {
            s.reply("530 Please login with USER and PASS")
            return
        }
        try {
            when (cmd) {
                "USER" -> s.reply("331 Password required")
                "PASS" -> {
                    if (arg == password) {
                        s.authenticated = true
                        s.reply("230 Login successful")
                        log("client logged in")
                    } else s.reply("530 Login incorrect")
                }
                "QUIT" -> { s.reply("221 Goodbye"); try { s.socket.close() } catch (_: Exception) {} }
                "SYST" -> s.reply("215 UNIX Type: L8")
                "FEAT" -> {
                    s.reply("211-Features:")
                    s.reply(" UTF8")
                    s.reply(" PASV")
                    s.reply(" SIZE")
                    s.reply(" MDTM")
                    s.reply(" REST STREAM")
                    s.reply("211 End")
                }
                "OPTS" -> s.reply(if (arg.equals("UTF8 ON", ignoreCase = true)) "200 OK" else "501 Not supported")
                "NOOP" -> s.reply("200 NOOP ok")
                "TYPE" -> s.reply("200 Type set") // دائمًا ثنائي داخلياً — آمن للنصوص أيضاً
                "MODE" -> s.reply(if (arg.equals("S", ignoreCase = true)) "200 Mode set" else "502 Not supported")
                "STRU" -> s.reply(if (arg.equals("F", ignoreCase = true)) "200 Structure set" else "502 Not supported")
                "PWD" -> s.reply("257 \"${abstractPath(s.cwd)}\" is the current directory")
                "CWD" -> {
                    val target = resolve(s, arg)
                    if (target.isDirectory && insideRoot(target)) {
                        s.cwd = target
                        s.reply("250 Directory changed to ${abstractPath(target)}")
                    } else s.reply("550 Directory not found: $arg")
                }
                "CDUP" -> {
                    val parent = s.cwd.parentFile
                    if (parent != null && insideRoot(parent)) {
                        s.cwd = parent
                        s.reply("250 Directory changed to ${abstractPath(parent)}")
                    } else s.reply("550 Already at root")
                }
                "MKD" -> {
                    val target = resolve(s, arg)
                    if (target.mkdir()) s.reply("257 \"${abstractPath(target)}\" created")
                    else s.reply("550 Cannot create directory")
                }
                "RMD" -> {
                    val target = resolve(s, arg)
                    if (target.isDirectory && target.listFiles()?.isEmpty() == true && target.delete()) {
                        s.reply("250 Directory removed")
                    } else s.reply("550 Cannot remove directory (not empty or failed)")
                }
                "DELE" -> {
                    val target = resolve(s, arg)
                    if (target.isFile && target.delete()) s.reply("250 File deleted")
                    else s.reply("550 Cannot delete file")
                }
                "RNFR" -> {
                    val target = resolve(s, arg)
                    if (target.exists()) {
                        s.renameFrom = target
                        s.reply("350 Ready for RNTO")
                    } else s.reply("550 File not found")
                }
                "RNTO" -> {
                    val from = s.renameFrom
                    val to = resolve(s, arg)
                    if (from != null && from.renameTo(to)) {
                        s.renameFrom = null
                        s.reply("250 Rename successful")
                    } else {
                        s.renameFrom = null
                        s.reply("550 Rename failed")
                    }
                }
                "SIZE" -> {
                    val target = resolve(s, arg)
                    if (target.isFile) s.reply("213 ${target.length()}") else s.reply("550 Not a regular file")
                }
                "MDTM" -> {
                    val target = resolve(s, arg)
                    if (target.exists()) {
                        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                        s.reply("213 ${fmt.format(Date(target.lastModified()))}")
                    } else s.reply("550 File not found")
                }
                "REST" -> {
                    s.restartOffset = arg.toLongOrNull() ?: 0L
                    s.reply("350 Restarting at $arg. Send RETR/STOR")
                }
                "PASV" -> {
                    val data = ServerSocket(0, 4)
                    data.soTimeout = 30_000
                    passiveSockets.add(data)
                    val portData = data.localPort
                    val ip = s.socket.localAddress?.hostAddress?.takeIf { it != "0.0.0.0" }
                        ?: localIpv4() ?: "127.0.0.1"
                    val h = ip.split(".").map { it.toInt() }
                    val p1 = portData / 256
                    val p2 = portData % 256
                    s.dataSocketProvider = {
                        try {
                            val accepted = data.accept()
                            dataSockets.add(accepted)
                            accepted
                        } finally {
                            passiveSockets.remove(data)
                        }
                    }
                    s.reply("227 Entering Passive Mode (${h[0]},${h[1]},${h[2]},${h[3]},$p1,$p2)")
                }
                "PORT" -> {
                    val parts = arg.split(",").map { it.trim().toIntOrNull() ?: -1 }
                    if (parts.size != 6 || parts.any { it < 0 || it > 255 }) {
                        s.reply("501 Invalid PORT arguments")
                    } else {
                        val host = "${parts[0]}.${parts[1]}.${parts[2]}.${parts[3]}"
                        val portActive = parts[4] * 256 + parts[5]
                        val controlAddr = s.socket.inetAddress?.hostAddress ?: host
                        // لا نسمح بالاتصال بعناوين خارج جهاز العميل نفسه (حماية bounce)
                        val target = if (host == controlAddr) host else controlAddr
                        s.dataSocketProvider = {
                            val sk = Socket()
                            sk.connect(InetSocketAddress(target, portActive), 15_000)
                            dataSockets.add(sk)
                            sk
                        }
                        s.reply("200 PORT command successful")
                    }
                }
                "LIST", "NLST" -> doList(s, cmd == "NLST", arg)
                "RETR" -> doRetr(s, arg)
                "STOR" -> doStor(s, arg)
                "APPE", "STOU", "MLSD", "MLST" -> s.reply("502 Command not supported — use LIST/RETR/STOR")
                "EPSV" -> s.reply("522 Network protocol not supported, use PASV")
                "ABOR" -> s.reply("226 No transfer in progress")
                else -> s.reply("502 Command $cmd not implemented")
            }
        } catch (e: Exception) {
            log("command $cmd error: ${e.message}")
            s.reply("451 Requested action aborted: local error")
        }
    }

    // ═══════════════ النقل ═══════════════

    private fun doList(s: Session, namesOnly: Boolean, arg: String) {
        val dir = resolve(s, arg.ifBlank { "." })
        if (!dir.isDirectory) { s.reply("550 Not a directory"); return }
        val entries = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        ) ?: emptyList()
        val fmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)
        s.reply("150 Opening data connection for LIST")
        val ok = withDataOutput(s) { out ->
            val w = java.io.OutputStreamWriter(BufferedOutputStream(out, 8192), StandardCharsets.UTF_8)
            for (f in entries) {
                val line = if (namesOnly) {
                    f.name
                } else {
                    val perms = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
                    val size = if (f.isDirectory) 4096L else f.length()
                    "$perms   1 nova nova ${"%12d".format(size)} ${fmt.format(Date(f.lastModified()))} ${f.name}"
                }
                w.write(line + "\r\n")
            }
            w.flush()
            true
        }
        s.reply(if (ok) "226 Transfer complete (${entries.size} items)" else "425 Cannot open data connection")
    }

    private fun doRetr(s: Session, arg: String) {
        val file = resolve(s, arg)
        if (!file.isFile) { s.reply("550 File not found: $arg"); return }
        s.reply("150 Opening BINARY mode data connection for ${file.name} (${file.length()} bytes)")
        val ok = withDataOutput(s) { out ->
            BufferedInputStream(file.inputStream().buffered(8192)).use { input ->
                if (s.restartOffset > 0) {
                    var skipped = 0L
                    while (skipped < s.restartOffset) {
                        val n = input.skip(s.restartOffset - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                    s.restartOffset = 0
                }
                input.copyTo(out, 8192)
            }
            out.flush()
            true
        }
        s.reply(if (ok) "226 Transfer complete" else "425 Cannot open data connection")
    }

    private fun doStor(s: Session, arg: String) {
        val file = resolve(s, arg)
        if (file.isDirectory) { s.reply("550 Target is a directory"); return }
        s.reply("150 Ok to send data")
        val ok = withDataInput(s) { input ->
            file.outputStream().buffered(8192).use { fos ->
                input.copyTo(fos, 8192)
                fos.flush()
            }
            true
        }
        s.reply(if (ok) "226 Transfer complete" else "425 Cannot open data connection")
    }

    /** يفتح اتصال البيانات (PASV/PORT) ويعطي مجرى الإخراج للكتلة (للأوامر المرسِلة: LIST/RETR) */
    private inline fun withDataOutput(s: Session, block: (java.io.OutputStream) -> Boolean): Boolean =
        withDataSocket(s) { block(it.getOutputStream()) }

    /** يفتح اتصال البيانات (PASV/PORT) ويعطي مجرى الإدخال للكتلة (للأوامر المستقبِلة: STOR) */
    private inline fun withDataInput(s: Session, block: (java.io.InputStream) -> Boolean): Boolean =
        withDataSocket(s) { block(it.getInputStream()) }

    /** يفتح اتصال البيانات (PASV/PORT) وينفذ الكتلة، ثم يغلق الاتصال دائماً */
    private inline fun withDataSocket(s: Session, block: (Socket) -> Boolean): Boolean {
        val provider = s.dataSocketProvider
        s.dataSocketProvider = null
        if (provider == null) return false
        var sk: Socket? = null
        return try {
            sk = provider()
            sk.tcpNoDelay = true
            block(sk)
        } catch (e: Exception) {
            log("data connection error: ${e.message}")
            false
        } finally {
            if (sk != null) dataSockets.remove(sk)
            try { sk?.close() } catch (_: Exception) {}
        }
    }

    // ═══════════════ المسارات ═══════════════

    /** يحوّل وسيط FTP إلى مسار مطلق داخل الجذر فقط (حماية من الهروب بـ ../) */
    private fun resolve(s: Session, arg: String): File {
        val clean = arg.trim()
        var base: File = if (clean.startsWith("/")) root else s.cwd
        val rel = clean.removePrefix("/")
        for (part in rel.split("/")) {
            when (part) {
                "", "." -> {}
                ".." -> {
                    val parent = base.parentFile
                    base = if (parent != null && insideRoot(parent)) parent else root
                }
                else -> base = File(base, part)
            }
        }
        return base
    }

    private fun insideRoot(f: File): Boolean {
        var cur: File? = f
        while (cur != null) {
            if (cur.absolutePath == root.absolutePath) return true
            cur = cur.parentFile
        }
        return false
    }

    /** المسار المجرد نسبةً للجذر: "/مجلد/فرعي" */
    private fun abstractPath(f: File): String {
        val rp = f.absolutePath
        val rootP = root.absolutePath.trimEnd('/')
        val rel = if (rp == rootP) "" else rp.removePrefix(rootP)
        return "/" + rel.trimStart('/')
    }

    private fun log(msg: String) {
        try { onLog?.invoke(msg) } catch (_: Exception) {}
    }
}
