package com.novafiles.app.core.ftp

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * اختبارات خادم FTP عبر عميل sockets خام على 127.0.0.1:
 * المصادقة، التصفح، القوائم، تنزيل/رفع، إعادة تسمية، حذف، وحماية الهروب من الجذر.
 */
class FtpServerTest {

    private lateinit var root: File
    private lateinit var server: FtpServer
    private var control: Socket? = null
    private lateinit var reader: BufferedReader
    private lateinit var writer: OutputStreamWriter

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "novaftp-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        server = FtpServer(root, port = freePort(), username = "nova", password = "nova")
        assertTrue(server.start())
    }

    @After
    fun tearDown() {
        server.stop()
        control?.close()
        root.deleteRecursively()
    }

    private fun freePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    // ═══════════════ عميل FTP خام ═══════════════

    private fun connect() {
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", server.port), 5000)
        control = s
        reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
        writer = OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
        assertTrue(readReply().startsWith("220"))
    }

    private fun send(line: String) {
        writer.write(line + "\r\n")
        writer.flush()
    }

    private fun cmd(line: String): String {
        send(line)
        return readReply()
    }

    /** يقرأ ردًا واحدًا أو متعدد الأسطر (211-... حتى 211 ...) */
    private fun readReply(): String {
        val sb = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            sb.appendLine(line)
            // متعدد الأسطر: رمز + '-' ... النهاية رمز + ' '
            if (line.length >= 4 && line[3] == ' ') break
            if (line.length in 1..3) break
        }
        return sb.toString().trim()
    }

    /** آخر رمز رقمي من الرد */
    private fun code(reply: String): Int = reply.substring(0, 3).toInt()

    private fun login() {
        assertEquals(331, code(cmd("USER nova")))
        assertEquals(230, code(cmd("PASS nova")))
    }

    /** طلب PASV وإرجاع منفذ البيانات */
    private fun pasv(): Int {
        val r = cmd("PASV")
        assertEquals(227, code(r))
        val m = Regex("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)").find(r)
            ?: throw AssertionError("bad PASV reply: $r")
        val g = m.groupValues
        return g[5].toInt() * 256 + g[6].toInt()
    }

    private fun dataSocket(): Socket {
        val port = pasv()
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", port), 5000)
        return s
    }

    // ═══════════════ الاختبارات ═══════════════

    @Test
    fun authFlow_wrongPasswordRejected() {
        connect()
        assertEquals(331, code(cmd("USER nova")))
        assertEquals(530, code(cmd("PASS wrong")))
        // قبل المصادقة يُرفض كل شيء
        assertEquals(530, code(cmd("PWD")))
        assertEquals(230, code(cmd("PASS nova")))
        assertEquals(215, code(cmd("SYST")))
    }

    @Test
    fun commandsBeforeLogin_areRejected() {
        connect()
        assertEquals(530, code(cmd("LIST")))
        assertEquals(530, code(cmd("RETR x")))
        assertEquals(530, code(cmd("STOR x")))
    }

    @Test
    fun pwdMkdCwd_navigateWithinRoot() {
        connect(); login()
        assertTrue(cmd("PWD").contains("\"/\""))
        assertEquals(257, code(cmd("MKD مجلد_عربي")))
        assertEquals(250, code(cmd("CWD مجلد_عربي")))
        assertTrue(cmd("PWD").contains("\"/مجلد_عربي\""))
        assertEquals(250, code(cmd("CDUP")))
        assertTrue(cmd("PWD").contains("\"/\""))
        assertTrue(File(root, "مجلد_عربي").isDirectory)
    }

    @Test
    fun storAndRetr_binaryRoundTrip() {
        connect(); login()
        val payload = ByteArray(150_000) { (it % 251).toByte() }
        // رفع
        dataSocket().use { ds ->
            send("STOR ملف ثنائي.bin")
            assertEquals(150, code(readReply()))
            ds.getOutputStream().apply { write(payload); flush() }
        }
        assertEquals(226, code(readReply()))
        val uploaded = File(root, "ملف ثنائي.bin")
        assertTrue(uploaded.isFile)
        assertArrayEquals(payload, uploaded.readBytes())
        // تنزيل
        val downloaded = dataSocket().use { ds ->
            send("RETR ملف ثنائي.bin")
            assertEquals(150, code(readReply()))
            assertEquals(226, code(readReply()))
            ds.getInputStream().readBytes()
        }
        assertArrayEquals(payload, downloaded)
    }

    @Test
    fun listAndNlst_showEntries() {
        File(root, "alpha.txt").writeText("hello")
        File(root, "beta.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "غيمة").mkdir()
        connect(); login()
        // LIST
        val listText = dataSocket().use { ds ->
            send("LIST")
            assertEquals(150, code(readReply()))
            assertEquals(226, code(readReply()))
            ds.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
        assertTrue(listText.contains("alpha.txt"))
        assertTrue(listText.contains("beta.bin"))
        assertTrue(listText.contains("غيمة"))
        assertTrue(listText.contains("drwxr-xr-x")) // المجلد
        // NLST
        val nlst = dataSocket().use { ds ->
            send("NLST")
            assertEquals(150, code(readReply()))
            assertEquals(226, code(readReply()))
            ds.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
        assertTrue(nlst.contains("alpha.txt"))
    }

    @Test
    fun sizeMdtmDeleRename_workOnFiles() {
        val f = File(root, "x.txt")
        f.writeText("0123456789")
        connect(); login()
        assertEquals(213, code(cmd("SIZE x.txt")))
        // إعادة تسمية
        assertEquals(350, code(cmd("RNFR x.txt")))
        assertEquals(250, code(cmd("RNTO y.txt")))
        assertFalse(f.exists())
        assertTrue(File(root, "y.txt").exists())
        // حذف
        assertEquals(250, code(cmd("DELE y.txt")))
        assertFalse(File(root, "y.txt").exists())
        assertEquals(550, code(cmd("DELE y.txt")))
    }

    @Test
    fun pathTraversal_isBlocked() {
        connect(); login()
        // محاولة الهروب خارج الجذر يجب أن تقصر عند الجذر
        assertEquals(250, code(cmd("CWD /..")))
        assertTrue(cmd("PWD").contains("\"/\""))
        assertEquals(250, code(cmd("CWD ..")))
        assertTrue(cmd("PWD").contains("\"/\""))
        // RETR لملف خارج الجذر عبر ../ — يُحل داخل الجذر فيفشل
        assertEquals(550, code(cmd("RETR ../../etc/passwd")))
    }

    @Test
    fun restOffset_resumesDownload() {
        val text = "ABCDEFGHIJ"
        File(root, "part.txt").writeText(text)
        connect(); login()
        assertEquals(350, code(cmd("REST 4")))
        val rest = dataSocket().use { ds ->
            send("RETR part.txt")
            assertEquals(150, code(readReply()))
            assertEquals(226, code(readReply()))
            ds.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        }
        assertEquals("EFGHIJ", rest)
    }

    @Test
    fun multipleSequentialTransfers_sameControlConnection() {
        connect(); login()
        repeat(3) { i ->
            val data = "chunk-$i".toByteArray()
            dataSocket().use { ds ->
                send("STOR c$i.txt")
                assertEquals(150, code(readReply()))
                ds.getOutputStream().apply { write(data); flush() }
            }
            assertEquals(226, code(readReply()))
        }
        repeat(3) { i ->
            assertEquals("chunk-$i", File(root, "c$i.txt").readText())
        }
    }

    @Test
    fun startStop_isIdempotent() {
        assertTrue(server.isRunning)
        server.stop()
        assertFalse(server.isRunning)
        server.stop() // إيقاف مزدوج آمن
        // إعادة تشغيل بعد الإيقاف تعمل
        assertTrue(server.start())
        assertTrue(server.isRunning)
    }

    @Test
    fun localIpv4_returnsNonLoopbackOrMachineIp() {
        val ip = localIpv4()
        // في حاويات CI قد لا يوجد عنوان — إن وُجد فليس 127.0.0.1
        if (ip != null) {
            assertFalse(ip.startsWith("127."))
        }
    }
}
