package com.novafiles.app.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.security.PublicKey
import java.util.EnumSet

/**
 * عميل SFTP (مكتبة sshj) فوق واجهة RemoteFs الموحّدة.
 *
 * - توثيق كلمة مرور مع سقوط تلقائي إلى keyboard-interactive (تغطي خوادم OpenSSH
 *   وDropbear وخوادم الاستضافة المشتركة).
 * - تحقق «الثقة بأول استخدام» (TOFU): بصمة مفتاح الخادم تُحفظ عند أول اتصال عبر
 *   KnownHostStore وتُرفض الاتصالات إذا تغيّر المفتاح — حماية من الوسيط.
 * - عميل واحد ثابت مع إعادة اتصال تلقائي عند الانقطاع، وقفل يمنع تعارض النقل.
 * - حذف المجلدات تكراري (البروتوكول يمنع حذف مجلد غير فارغ مباشرة).
 */
class SftpFs(
    private val host: String,
    private val port: Int = 22,
    private val username: String,
    private val password: String,
    private val knownHosts: KnownHostStore? = null,
) : RemoteFs {

    override val displayUrl: String get() = "sftp://$host:$port"

    private val mutex = Mutex()
    private var client: SSHClient? = null
    private var sftp: SFTPClient? = null

    private fun newClient(): SSHClient = SSHClient().apply {
        connectTimeout = 12_000
        timeout = 30_000
        // لا محقّق افتراضي في sshj — TOFU هو المحقّق الوحيد
        addHostKeyVerifier(TofuVerifier())
    }

    private fun connectAndAuth(c: SSHClient) {
        c.connect(host, port)
        // authPassword يجرب «password» ثم يسقط تلقائياً إلى «keyboard-interactive»
        c.authPassword(username, password)
    }

    /** يزوّد جلسة SFTP جاهزة (يعيد الاتصال تلقائياً عند الانقطاع) ويجعل العمليات متتابعة */
    private suspend fun <T> useClient(block: (SFTPClient) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val c = client ?: createConnected()
            val s = sftp ?: c.newSFTPClient().also { sftp = it }
            try {
                block(s)
            } catch (e: Throwable) {
                // الاتصال أو القناة تالفة — تُهوى كلياً ليُعاد إنشاؤهما في العملية القادمة
                runCatching { s.close() }
                runCatching { c.disconnect() }
                client = null
                sftp = null
                throw e
            }
        }
    }

    /** ينشئ عميلاً متصلاً وموثقاً — يهوي كل شيء عند أي فشل كي لا يتسرب مقبس */
    private fun createConnected(): SSHClient {
        val nc = newClient()
        try {
            connectAndAuth(nc)
            sftp = nc.newSFTPClient()
            client = nc
            return nc
        } catch (e: Throwable) {
            runCatching { nc.disconnect() }
            throw e
        }
    }

    override suspend fun list(path: String): List<RemoteEntry> = useClient { s ->
        val dir = if (path.isBlank()) "/" else path
        s.ls(dir)
            .filter { it.name != "." && it.name != ".." }
            .map { r ->
                val at = r.attributes
                val isDir = at.type == FileMode.Type.DIRECTORY
                RemoteEntry(
                    name = r.name,
                    path = RemotePaths.join(path, r.name),
                    isDir = isDir,
                    size = if (isDir) 0L else at.size,
                    lastModified = at.mtime * 1000L,
                )
            }
            .sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun download(remotePath: String, target: File, onProgress: (Long) -> Unit): Unit =
        useClient { s ->
            target.parentFile?.mkdirs()
            val rf: RemoteFile = s.open(remotePath)
            try {
                val input = rf.RemoteFileInputStream()
                input.use { inp ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                            onProgress(total)
                        }
                    }
                }
            } catch (e: Throwable) {
                target.delete()
                throw e
            } finally {
                runCatching { rf.close() }
            }
        }

    override suspend fun upload(source: File, remoteDir: String, onProgress: (Long) -> Unit): Unit =
        useClient { s ->
            val remote = RemotePaths.join(remoteDir, source.name)
            val rf = s.open(remote, EnumSet.of(OpenMode.CREAT, OpenMode.WRITE, OpenMode.TRUNC))
            try {
                val counted = object : FilterInputStream(FileInputStream(source).buffered()) {
                    private var read = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) {
                            read += n
                            onProgress(read)
                        }
                        return n
                    }
                }
                val output = rf.RemoteFileOutputStream()
                counted.use { inp ->
                    output.use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
            } finally {
                runCatching { rf.close() }
            }
        }

    override suspend fun delete(path: String, isDir: Boolean): Unit = useClient { s ->
        deleteRecursive(s, if (path.isBlank()) "/" else path, isDir)
    }

    private fun deleteRecursive(s: SFTPClient, path: String, isDir: Boolean) {
        if (isDir) {
            for (child in s.ls(path)) {
                val n = child.name
                if (n == "." || n == "..") continue
                deleteRecursive(s, child.path, child.attributes.type == FileMode.Type.DIRECTORY)
            }
            s.rmdir(path)
        } else {
            s.rm(path)
        }
    }

    override suspend fun mkdir(parent: String, name: String): Unit = useClient { s ->
        s.mkdir(RemotePaths.join(parent, name))
    }

    override suspend fun rename(path: String, newName: String): Unit = useClient { s ->
        val to = RemotePaths.join(RemotePaths.parentOf(path), newName)
        s.rename(path, to)
    }

    override suspend fun test(): String = useClient { s ->
        if (s.statExistence("/") == null) throw IOException("SFTP root unreachable")
        "OK"
    }

    override fun close() {
        runCatching { sftp?.close() }
        client?.let { c -> runCatching { c.disconnect() } }
        sftp = null
        client = null
    }

    /**
     * محقّق مفتاح المضيف بنمط TOFU — المفتاح الوحيد المسجّل لدى العميل.
     * أول اتصال: تُحفظ البصمة ويُقبل المفتاح. الاتصالات التالية: تطابق إجباري.
     */
    private inner class TofuVerifier : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val store = knownHosts ?: return true // بلا مخزن: قبول (سلوك الاختبار البسيط)
            val fp = HostFingerprints.sha256(key)
            val stored = store.fingerprintFor(hostname, port)
            if (stored == null) {
                store.remember(hostname, port, fp)
                return true
            }
            return stored == fp
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }
}
