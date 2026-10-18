package com.novafiles.app.core.network

import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * اختبار تكامل حقيقي: عميل SFTP الجديد (SftpFs عبر sshj) يعمل ضد خادم SSH مدمج
 * (Apache MINA sshd) على منفذ عابر. جذر الاختبار داخل مجلد المستخدم لأن خادم
 * MINA يعرض نظام الملفات الحقيقي — مسارات SFTP المطلقة تطابق مسارات النظام.
 */
class SftpFsTest {

    private lateinit var root: File
    private lateinit var server: SshServer
    private lateinit var fs: SftpFs
    private val knownHosts = InMemoryKnownHosts()

    /** مسار SFTP المطلق لجذر الاختبار (على يونكس = المسار الحقيقي) */
    private fun sftpPath(name: String = root.name): String =
        "/" + root.absolutePath.trimStart('/') + (if (name != root.name) "/$name" else "")

    @Before
    fun setUp() {
        // داخل مجلد المستخدم حتى تكون المسارات المطلقة عبر SFTP صحيحة
        root = Files.createTempDirectory(
            Paths.get(System.getProperty("user.home")),
            "nova_sftp_test_",
        ).toFile()
        // بذرة بيانات: مجلد فيه ملفان وملف في الجذر
        File(root, "مستندات").mkdirs()
        File(root, "مستندات/تقرير.txt").writeText("تقرير عربي للأختبار")
        File(root, "صورة.bin").writeBytes(ByteArray(8192) { (it % 251).toByte() })

        server = SshServer.setUpDefaultServer()
        server.port = 0
        server.keyPairProvider = SimpleGeneratorHostKeyProvider().apply {
            algorithm = "RSA"
            keySize = 2048
        }
        server.passwordAuthenticator = PasswordAuthenticator { user, pass, _ ->
            user == "nova" && pass == "nova1234"
        }
        server.subsystemFactories = listOf(SftpSubsystemFactory())
        server.start()

        fs = SftpFs("127.0.0.1", server.port, "nova", "nova1234", knownHosts)
    }

    @After
    fun tearDown() {
        runCatching { fs.close() }
        runCatching { server.stop(true) }
        root.deleteRecursively()
    }

    @Test
    fun `list seeded dir with utf8 names`() = kotlinx.coroutines.runBlocking {
        val items = fs.list(sftpPath())
        assertEquals(2, items.size)
        val dirs = items.filter { it.isDir }
        val files = items.filterNot { it.isDir }
        assertEquals(listOf("مستندات"), dirs.map { it.name })
        assertEquals(listOf("صورة.bin"), files.map { it.name })
        assertEquals(8192L, files.first().size)
    }

    @Test
    fun `list subdirectory paths are remote-joined`() = kotlinx.coroutines.runBlocking {
        val base = sftpPath()
        val items = fs.list("$base/مستندات")
        assertEquals(1, items.size)
        assertEquals("تقرير.txt", items.first().name)
        assertEquals("$base/مستندات/تقرير.txt", items.first().path)
        assertTrue(items.first().isDir.not())
    }

    @Test
    fun `download preserves content and fires progress`() = kotlinx.coroutines.runBlocking {
        val target = Files.createTempFile("nova_sftp_dl_", ".bin").toFile()
        try {
            var last = 0L
            var calls = 0
            fs.download(sftpPath("صورة.bin"), target) { written -> last = written; calls++ }
            assertArrayEquals(File(root, "صورة.bin").readBytes(), target.readBytes())
            assertEquals(8192L, last)
            assertTrue("يجب أن تُستدعى تقارير التقدم", calls > 0)
        } finally {
            target.delete()
        }
    }

    @Test
    fun `upload creates remote file with same content`() = kotlinx.coroutines.runBlocking {
        val src = Files.createTempFile("nova_sftp_up_", ".txt").toFile()
        src.writeText("رفع عبر SFTP — نص عربي")
        try {
            fs.upload(src, sftpPath()) { }
            val names = fs.list(sftpPath()).map { it.name }
            assertTrue(names.contains(src.name))
            // نتحقق من المحتوى بالتنزيل مرة أخرى
            val back = Files.createTempFile("nova_sftp_rt_", ".txt").toFile()
            try {
                fs.download(sftpPath(src.name), back) { }
                assertEquals(src.readText(), back.readText())
            } finally {
                back.delete()
            }
        } finally {
            src.delete()
        }
    }

    @Test
    fun `mkdir then rename then verify`() = kotlinx.coroutines.runBlocking {
        val base = sftpPath()
        fs.mkdir(base, "مجلد جديد")
        var names = fs.list(base).map { it.name }
        assertTrue(names.contains("مجلد جديد"))
        fs.rename("$base/مجلد جديد", "مجلد معاد تسميته")
        names = fs.list(base).map { it.name }
        assertTrue(names.contains("مجلد معاد تسميته"))
        assertTrue(!names.contains("مجلد جديد"))
    }

    @Test
    fun `delete file`() = kotlinx.coroutines.runBlocking {
        fs.delete(sftpPath("صورة.bin"), isDir = false)
        val names = fs.list(sftpPath()).map { it.name }
        assertTrue(!names.contains("صورة.bin"))
    }

    @Test
    fun `delete directory recursively`() = kotlinx.coroutines.runBlocking {
        val base = sftpPath()
        fs.mkdir(base, "شجرة")
        fs.mkdir("$base/شجرة", "فرع")
        File(root, "شجرة/فرع/عمق.txt").writeText("عمق")
        File(root, "شجرة/ملف.txt").writeText("سطحي")
        fs.delete("$base/شجرة", isDir = true)
        val names = fs.list(base).map { it.name }
        assertTrue(!names.contains("شجرة"))
    }

    @Test
    fun `test returns OK`() = kotlinx.coroutines.runBlocking {
        assertEquals("OK", fs.test())
    }

    @Test
    fun `wrong password is rejected`() {
        val bad = SftpFs("127.0.0.1", server.port, "nova", "كلمة-خاطئة", knownHosts)
        try {
            assertThrows(Exception::class.java) {
                kotlinx.coroutines.runBlocking { bad.list(sftpPath()) }
            }
        } finally {
            runCatching { bad.close() }
        }
        Unit
    }

    @Test
    fun `tofu remembers host key on first use`() = kotlinx.coroutines.runBlocking {
        // الاتصال يحدث بكسل عند أول عملية — ننفّذ عملية حقيقية أولاً
        fs.test()
        assertNotNull(knownHosts.fingerprintFor("127.0.0.1", server.port))
    }

    @Test
    fun `tofu rejects changed host key`() {
        // بصمة محفوظة مسبقاً لا تطابق مفتاح الخادم الحقيقي — يجب رفض الاتصال
        val store = InMemoryKnownHosts()
        store.remember("127.0.0.1", server.port, "ZW5jIG9sZCBmYWtlIGZpbmdlcnByaW50IGJhc2U2NA")
        val suspect = SftpFs("127.0.0.1", server.port, "nova", "nova1234", store)
        try {
            assertThrows(Exception::class.java) {
                kotlinx.coroutines.runBlocking { suspect.list("/") }
            }
        } finally {
            runCatching { suspect.close() }
        }
        Unit
    }

    @Test
    fun `close is idempotent`() {
        fs.close()
        fs.close() // يجب ألا يرمي استثناء
    }

    @Test
    fun `default port for sftp is 22`() {
        assertEquals(22, RemoteFsFactory.defaultPort("SFTP"))
        assertEquals(22, RemoteFsFactory.defaultPort("sftp"))
        assertEquals(445, RemoteFsFactory.defaultPort("SMB"))
        assertEquals(21, RemoteFsFactory.defaultPort("FTP"))
    }
}
