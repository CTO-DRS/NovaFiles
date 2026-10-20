package com.novafiles.app.core.ftp

import com.novafiles.app.core.netlog.ConnectionLog
import com.novafiles.app.core.netlog.NetEventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * v0.27 — اختبار تكامل تحسينات FTP: ربط سجل الاتصالات والجلسات
 * فوق خادم حقيقي على 127.0.0.1.
 */
class FtpServerLogTest {

    private lateinit var root: File
    private lateinit var server: FtpServer
    private val log = ConnectionLog(50)

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "novaftplog-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        server = FtpServer(root, port = freePort(), username = "nova", password = "nova", connLog = log)
        assertTrue(server.start())
    }

    @After
    fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** ينتظر حتى تتحقق الشرط في السجل (أحداث الخيوط الشبكية) */
    private fun awaitLog(timeoutMs: Long = 3000, condition: (List<com.novafiles.app.core.netlog.NetEvent>) -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition(log.events.value)) return
            Thread.sleep(40)
        }
    }

    private class Client(host: String, port: Int) {
        val socket = Socket().apply { connect(InetSocketAddress(host, port), 5000) }
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)

        init {
            assertTrue(readReply().startsWith("220"))
        }

        fun send(line: String) {
            writer.write(line + "\r\n")
            writer.flush()
        }

        fun readReply(): String = reader.readLine() ?: ""
    }

    @Test
    fun `failed login is recorded`() {
        val c = Client("127.0.0.1", server.port)
        c.send("USER nova")
        assertTrue(c.readReply().startsWith("331"))
        c.send("PASS wrong")
        assertTrue(c.readReply().startsWith("530"))
        awaitLog { events -> events.any { it.type == NetEventType.LOGIN_FAILED } }
        assertTrue(log.events.value.any { it.type == NetEventType.LOGIN_FAILED })
        c.socket.close()
    }

    @Test
    fun `connect login download and disconnect are recorded`() {
        File(root, "file.txt").writeText("payload")
        val c = Client("127.0.0.1", server.port)
        c.send("USER nova")
        c.readReply()
        c.send("PASS nova")
        assertTrue(c.readReply().startsWith("230"))

        // جلسة نشطة مسجلة ومصادقة
        assertEquals(1, server.sessions.activeCount)
        assertTrue(server.sessions.snapshot().any { it.authenticated && it.host == "127.0.0.1" })

        // تنزيل عبر PASV
        c.send("PASV")
        val pasv = c.readReply()
        assertTrue(pasv.startsWith("227"))
        val nums = pasv.substringAfter('(').substringBefore(')').split(',')
        val dataPort = nums[4].toInt() * 256 + nums[5].toInt()
        val data = Socket().apply { connect(InetSocketAddress("127.0.0.1", dataPort), 5000) }
        c.send("RETR file.txt")
        assertTrue(c.readReply().startsWith("150"))
        val got = data.getInputStream().readBytes()
        data.close()
        assertTrue(c.readReply().startsWith("226"))
        assertEquals("payload", String(got))
        c.socket.close()

        awaitLog { events ->
            events.any { it.type == NetEventType.DOWNLOAD && it.bytes == got.size.toLong() } &&
                events.any { it.type == NetEventType.LOGIN } &&
                events.any { it.type == NetEventType.CONNECT } &&
                events.any { it.type == NetEventType.DISCONNECT }
        }
        val types = log.events.value.map { it.type }
        assertTrue(types.contains(NetEventType.DOWNLOAD))
        assertTrue(types.contains(NetEventType.LOGIN))
        assertTrue(types.contains(NetEventType.CONNECT))
    }

    @Test
    fun `upload is recorded with byte count`() {
        val c = Client("127.0.0.1", server.port)
        c.send("USER nova")
        c.readReply()
        c.send("PASS nova")
        c.readReply()
        c.send("PASV")
        val pasv = c.readReply()
        val nums = pasv.substringAfter('(').substringBefore(')').split(',')
        val dataPort = nums[4].toInt() * 256 + nums[5].toInt()
        val data = Socket().apply { connect(InetSocketAddress("127.0.0.1", dataPort), 5000) }
        c.send("STOR up.txt")
        assertTrue(c.readReply().startsWith("150"))
        data.getOutputStream().write("uploaded-content".toByteArray())
        data.getOutputStream().flush()
        data.close()
        assertTrue(c.readReply().startsWith("226"))
        c.socket.close()

        awaitLog { events -> events.any { it.type == NetEventType.UPLOAD && it.bytes == 16L } }
        assertTrue(log.events.value.any { it.type == NetEventType.UPLOAD && it.detail == "/up.txt" })
        assertEquals("uploaded-content", File(root, "up.txt").readText())
    }

    @Test
    fun `mkdir delete rename are recorded`() {
        val c = Client("127.0.0.1", server.port)
        c.send("USER nova")
        c.readReply()
        c.send("PASS nova")
        c.readReply()
        c.send("MKD folder")
        assertTrue(c.readReply().startsWith("257"))
        c.send("DELE none.txt")
        c.readReply()
        c.send("RNFR folder")
        c.readReply()
        c.send("RNTO renamed")
        assertTrue(c.readReply().startsWith("250"))
        c.socket.close()

        awaitLog { events -> events.any { it.type == NetEventType.MOVE && it.detail.contains("renamed") } }
        val types = log.events.value.map { it.type }
        assertTrue(types.contains(NetEventType.MKDIR))
        assertTrue(types.contains(NetEventType.MOVE))
    }

    @Test
    fun `kick closes the live connection`() {
        val c = Client("127.0.0.1", server.port)
        c.send("USER nova")
        c.readReply()
        c.send("PASS nova")
        c.readReply()

        awaitLog { events -> events.any { it.type == NetEventType.LOGIN } }
        val snap = server.sessions.snapshot()
        assertEquals(1, snap.size)
        assertTrue(server.sessions.kick(snap[0].id))
        // القراءة تفشل لأن المقبس أُغلق من الخادم
        val line = c.reader.readLine()
        assertEquals(null, line)
        c.socket.close()
    }
}
