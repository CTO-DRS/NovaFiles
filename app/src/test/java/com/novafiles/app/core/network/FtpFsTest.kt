package com.novafiles.app.core.network

import com.novafiles.app.core.ftp.FtpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * اختبار تكامل حقيقي: عميل FTP الجديد (FtpFs عبر Apache Commons Net)
 * يعمل ضد خادم FTP المدمج في التطبيق (FtpServer) على منفذ عابر.
 */
class FtpFsTest {

    private lateinit var root: File
    private lateinit var server: FtpServer
    private lateinit var fs: FtpFs

    @Before
    fun setUp() {
        root = Files.createTempDirectory("nova_ftpfs_test").toFile()
        // بذرة بيانات: مجلد فيه ملفان وملف في الجذر
        File(root, "مستندات").mkdirs()
        File(root, "مستندات/تقرير.txt").writeText("تقرير عربي للأختبار")
        File(root, "صورة.bin").writeBytes(ByteArray(4096) { (it % 251).toByte() })
        server = FtpServer(root, 0, "nova", "nova1234")
        assertTrue("خادم الاختبار يجب أن يبدأ", server.start())
        fs = FtpFs("127.0.0.1", server.port, "nova", "nova1234")
    }

    @After
    fun tearDown() {
        runCatching { fs.close() }
        server.stop()
        root.deleteRecursively()
    }

    @Test
    fun `list root with utf8 names`() = kotlinx.coroutines.runBlocking {
        val items = fs.list("")
        assertEquals(2, items.size)
        val dirs = items.filter { it.isDir }
        val files = items.filterNot { it.isDir }
        assertEquals(listOf("مستندات"), dirs.map { it.name })
        assertEquals(listOf("صورة.bin"), files.map { it.name })
        assertEquals(4096L, files.first().size)
    }

    @Test
    fun `list subdirectory`() = kotlinx.coroutines.runBlocking {
        val items = fs.list("/مستندات")
        assertEquals(1, items.size)
        assertEquals("تقرير.txt", items.first().name)
        assertEquals("/مستندات/تقرير.txt", items.first().path)
        assertTrue(items.first().isDir.not())
    }

    @Test
    fun `download preserves arabic content`() = kotlinx.coroutines.runBlocking {
        val target = File.createTempFile("dl_", ".txt")
        fs.download("/مستندات/تقرير.txt", target)
        assertEquals("تقرير عربي للأختبار", target.readText())
        target.delete()
        Unit
    }

    @Test
    fun `download reports progress bytes`() = kotlinx.coroutines.runBlocking {
        val target = File.createTempFile("dl_", ".bin")
        var last = 0L
        fs.download("/صورة.bin", target) { last = it }
        assertEquals(4096L, last)
        assertEquals(4096L, target.length())
        target.delete()
        Unit
    }

    @Test
    fun `upload then verify then rename then delete`() = kotlinx.coroutines.runBlocking {
        val src = File.createTempFile("up_", ".dat")
        val payload = ByteArray(150_000) { (it % 199).toByte() }
        src.writeBytes(payload)

        var uploaded = 0L
        fs.upload(src, "/مستندات") { uploaded = it }
        assertTrue("يجب أن يُبلّغ الرفع عن بايتات", uploaded >= payload.size)

        val after = fs.list("/مستندات")
        assertTrue(after.any { it.name == src.name && !it.isDir && it.size == payload.size.toLong() })

        fs.rename("/مستندات/${src.name}", "بعد_التسمية.dat")
        assertTrue(fs.list("/مستندات").none { it.name == src.name })

        val round = File.createTempFile("round_", ".dat")
        fs.download("/مستندات/بعد_التسمية.dat", round)
        assertArrayEquals(payload, round.readBytes())
        round.delete()

        fs.delete("/مستندات/بعد_التسمية.dat", false)
        assertTrue(fs.list("/مستندات").none { it.name == "بعد_التسمية.dat" })
        src.delete()
        Unit
    }

    @Test
    fun `mkdir then upload into it`() = kotlinx.coroutines.runBlocking {
        fs.mkdir("", "مجلد_جديد")
        val dirs = fs.list("").filter { it.isDir }
        assertTrue(dirs.any { it.name == "مجلد_جديد" })

        val src = File.createTempFile("in_", ".txt")
        src.writeText("داخل المجلد الجديد")
        fs.upload(src, "/مجلد_جديد") {}
        val sub = fs.list("/مجلد_جديد")
        assertEquals(1, sub.size)
        assertEquals(src.name, sub.first().name)

        // تنظيف ثم تحقق
        fs.delete("/مجلد_جديد/${src.name}", false)
        fs.delete("/مجلد_جديد", true)
        assertTrue(fs.list("").none { it.name == "مجلد_جديد" })
        src.delete()
        Unit
    }

    @Test
    fun `test returns OK`() = kotlinx.coroutines.runBlocking {
        assertEquals("OK", fs.test())
    }

    @Test
    fun `display url formatting`() {
        assertEquals("ftp://127.0.0.1:${server.port}", fs.displayUrl)
    }

    @Test
    fun `remote paths helpers`() {
        assertEquals("/a", RemotePaths.join("", "a"))
        assertEquals("/a/b", RemotePaths.join("/a", "b"))
        assertEquals("/a/b/c", RemotePaths.join("/a/b/", "c"))
        assertEquals("c", RemotePaths.nameOf("/a/b/c"))
        assertEquals("/a/b", RemotePaths.parentOf("/a/b/c"))
        assertEquals("", RemotePaths.parentOf("/c"))
        assertEquals("dir\\sub", RemotePaths.toSmbRel("/dir/sub"))
        assertEquals("", RemotePaths.toSmbRel(""))
    }

    @Test
    fun `wrong credentials fail`() = kotlinx.coroutines.runBlocking {
        val bad = FtpFs("127.0.0.1", server.port, "nova", "wrong")
        var failed = false
        try {
            bad.list("")
        } catch (e: Exception) {
            failed = true
        } finally {
            bad.close()
        }
        assertTrue("يجب أن يفشل الدخول بكلمة مرور خاطئة", failed)
    }
}
