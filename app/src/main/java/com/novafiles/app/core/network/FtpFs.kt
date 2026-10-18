package com.novafiles.app.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * عميل FTP (Apache Commons Net) فوق واجهة RemoteFs الموحّدة.
 * - ترميز تحكم UTF-8 كامل (أسماء عربية).
 * - نمط Passive صريح لتفادي مشاكل NAT.
 * - سرد عبر MLSD أولاً ثم fallback إلى LIST التقليدي.
 * - عميل واحد ثابت مع إعادة اتصال تلقائية عند انقطاع، وقفل يمنع تعارض عمليات النقل.
 */
class FtpFs(
    private val host: String,
    private val port: Int = 21,
    private val username: String,
    private val password: String,
) : RemoteFs {

    override val displayUrl: String get() = "ftp://$host:$port"

    private val mutex = Mutex()
    private var client: FTPClient? = null

    private fun newClient(): FTPClient = FTPClient().apply {
        controlEncoding = "UTF-8"
        connectTimeout = 12_000
        defaultTimeout = 30_000
        setDataTimeout(180_000)
        isRemoteVerificationEnabled = false
    }

    private fun connectAndLogin(c: FTPClient) {
        c.connect(host, port)
        if (!c.login(username.ifBlank { "anonymous" }, password)) {
            val reply = c.replyString
            runCatching { c.disconnect() }
            throw IOException("login failed: $reply")
        }
        // النمط الثنائي يجب أن يُضبط بعد الدخول — TYPE قبل USER/PASS يُرفض من الخادم
        // ويبقى النقل بنمط ASCII (تلف البيانات بتحويل LF→CRLF)
        c.enterLocalPassiveMode()
        c.setFileType(FTP.BINARY_FILE_TYPE)
    }

    /** يزوّد عميلاً متصلاً (يعيد الاتصال تلقائياً عند الانقطاع) ويجعل العمليات متتابعة */
    private suspend fun <T> useClient(block: (FTPClient) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = client ?: newClient().also { connectAndLogin(it) }
            try {
                block(current)
            } catch (e: Throwable) {
                // الاتصال قد انقطع أو تلف — نتخلص منه ليُعاد إنشاؤه في العملية القادمة
                runCatching { current.disconnect() }
                client = null
                throw e
            }
        }
    }

    override suspend fun list(path: String): List<RemoteEntry> = useClient { c ->
        val dir = if (path.isBlank()) "/" else path
        val files: Array<FTPFile> = run {
            // MLSD أولاً — تحليل دقيق للأسماء والأحجام والتواريخ
            val m: Array<FTPFile>? = try {
                c.mlistDir(dir)
            } catch (e: IOException) {
                null
            }
            if (m != null && m.isNotEmpty()) m
            else {
                // fallback إلى LIST التقليدي (يغطي الخوادم القديمة وغير الداعمة لـ MLSD)
                val l = c.listFiles(dir)
                if (l != null && l.isNotEmpty()) l
                else {
                    // قائمة فارغة: نتحقق أن المجلد موجود فعلاً وإلا نرفع خطأ
                    if (c.changeWorkingDirectory(dir)) emptyArray()
                    else throw IOException("list failed: ${c.replyString}")
                }
            }
        }

        files.filter { f ->
            val n = f.name
            n != "." && n != ".." && n.isNotBlank()
        }.map { f ->
            RemoteEntry(
                name = f.name,
                path = RemotePaths.join(path, f.name),
                isDir = f.isDirectory,
                size = if (f.isDirectory) 0L else f.size,
                lastModified = f.timestamp?.timeInMillis ?: 0L,
            )
        }.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun download(remotePath: String, target: File, onProgress: (Long) -> Unit): Unit =
        useClient { c ->
            target.parentFile?.mkdirs()
            val out: OutputStream = object : FileOutputStream(target) {
                private var written = 0L
                override fun write(b: ByteArray, off: Int, len: Int) {
                    super.write(b, off, len)
                    if (len > 0) {
                        written += len
                        onProgress(written)
                    }
                }
            }
            val ok = try {
                c.retrieveFile(remotePath, out)
            } finally {
                runCatching { out.flush(); out.close() }
            }
            if (!ok) {
                target.delete()
                throw IOException("download failed: ${c.replyString}")
            }
        }

    override suspend fun upload(source: File, remoteDir: String, onProgress: (Long) -> Unit): Unit =
        useClient { c ->
            val remote = RemotePaths.join(remoteDir, source.name)
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
            val ok = try {
                c.storeFile(remote, counted)
            } finally {
                runCatching { counted.close() }
            }
            if (!ok) throw IOException("upload failed: ${c.replyString}")
        }

    override suspend fun delete(path: String, isDir: Boolean): Unit = useClient { c ->
        val ok = if (isDir) c.removeDirectory(path) else c.deleteFile(path)
        if (!ok) throw IOException("delete failed: ${c.replyString}")
    }

    override suspend fun mkdir(parent: String, name: String): Unit = useClient { c ->
        val remote = RemotePaths.join(parent, name)
        if (!c.makeDirectory(remote)) throw IOException("mkdir failed: ${c.replyString}")
    }

    override suspend fun rename(path: String, newName: String): Unit = useClient { c ->
        val parent = RemotePaths.parentOf(path)
        val to = RemotePaths.join(parent, newName)
        if (!c.rename(path, to)) throw IOException("rename failed: ${c.replyString}")
    }

    override suspend fun test(): String = useClient { c ->
        if (!c.changeWorkingDirectory("/")) throw IOException(c.replyString)
        "OK"
    }

    override fun close() {
        client?.let { c -> runCatching { c.logout() }; runCatching { c.disconnect() } }
        client = null
    }
}
