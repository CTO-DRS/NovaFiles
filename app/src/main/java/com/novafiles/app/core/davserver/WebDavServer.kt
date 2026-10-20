package com.novafiles.app.core.davserver

import com.novafiles.app.core.netlog.ConnectionLog
import com.novafiles.app.core.netlog.NetEventType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * ══════════════════════════════════════════════════════════
 *  v0.27 — مضيف خادم WebDAV فوق ServerSocket من java.base خالص
 *  (نفس أسلوب FtpServer: بلا اعتماديات، يعمل على Android ويُختبر JVM)
 *  ─ حلقة قبول + خيط لكل اتصال + keep-alive عبر طلبات متتالية
 *  ─ طبقة المحرك خالصة: المضيف يحلل ويكتب فقط
 *  ─ سجل اتصالات مشترك ConnectionLog لأحداث الجلسات والنقل
 * ══════════════════════════════════════════════════════════
 */
class WebDavServer(
    /** الجذر المشترك — كل المسارات مقصورة داخله */
    val root: File,
    /** منفذ الاستماع */
    @Volatile var port: Int = 8888,
    username: String = "nova",
    password: String = "nova",
    /** سجل الأحداث الاختياري (يشاركه FTP عند الرغبة) */
    val log: ConnectionLog? = null,
    /** حادّ سرعة اختياري مشترك (بايت/ثانية — 0 بلا حد) */
    val limiter: com.novafiles.app.core.netlog.SpeedLimiter? = null,
) {

    @Volatile var isRunning: Boolean = false
        private set

    private val engine = WebDavEngine(root, auth = DavAuth(username, password), log = log)
    private var serverSocket: ServerSocket? = null
    private val clientSockets: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    private var acceptThread: Thread? = null

    // ═══════════════ دورة الحياة ═══════════════

    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        if (!root.isDirectory && !root.mkdirs()) {
            log?.append(NetEventType.ERROR, "root not accessible: $root")
            return false
        }
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", port))
            if (port == 0) port = ss.localPort
            serverSocket = ss
            isRunning = true
            acceptThread = Thread({ acceptLoop(ss) }, "dav-accept").apply {
                isDaemon = true
                start()
            }
            log?.append(NetEventType.CONNECT, "WebDAV server listening on :$port root=$root")
            true
        } catch (e: Exception) {
            log?.append(NetEventType.ERROR, "start failed: ${e.message}")
            isRunning = false
            false
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { serverSocket?.close() }
        synchronized(clientSockets) {
            clientSockets.toList().forEach { runCatching { it.close() } }
            clientSockets.clear()
        }
        log?.append(NetEventType.DISCONNECT, "WebDAV server stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (isRunning) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (isRunning) log?.append(NetEventType.ERROR, "accept: ${e.message}") else break
                continue
            }
            if (!isRunning) {
                runCatching { client.close() }
                break
            }
            Thread({ serveConnection(client) }, "dav-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** حلقة اتصال: طلب → محرك → استجابة → keep-alive إن سمح الطرفان */
    private fun serveConnection(socket: Socket) {
        clientSockets.add(socket)
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            while (isRunning) {
                val req = try {
                    DavHttp.readRequest(input)
                } catch (e: DavHttp.BadRequest) {
                    // طلب فاسد — نجيب 400 ثم نغلق (بلا keep-alive)
                    runCatching {
                        DavHttp.writeResponse(
                            output,
                            DavResponse(400, "Bad Request", bodyBytes = e.message?.toByteArray()),
                            keepAlive = false,
                        )
                    }
                    break
                } ?: break
                val resp = engine.handle(req)
                val lim = limiter?.takeIf { it.bytesPerSecond > 0 }
                val out = if (lim != null) com.novafiles.app.core.netlog.ThrottledOutputStream(output, lim) else output
                DavHttp.writeResponse(out, resp, keepAlive = req.isKeepAlive)
                val drained: Boolean = when (val body = req.body) {
                    is DavHttp.LengthInputStream -> body.drain()
                    is DavHttp.ChunkedInputStream -> body.drain()
                    else -> true
                }
                if (!req.isKeepAlive || !drained) break
            }
        } catch (_: Exception) {
            // انقطاع أو طلب فاسد — نغلق بصمت
        } finally {
            clientSockets.remove(socket)
            runCatching { socket.close() }
        }
    }
}
