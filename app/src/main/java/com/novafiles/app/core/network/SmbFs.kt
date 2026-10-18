package com.novafiles.app.core.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileRenameInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * عميل SMB2/SMB3 (مكتبة SMBJ) فوق واجهة RemoteFs الموحّدة — للوصول إلى
 * مشاركات Windows وSamba وNAS. المسار الموحّد «/dir/sub» يُحوَّل داخلياً
 * إلى مسار نسبي داخل المشاركة بفواصل «\».
 */
class SmbFs(
    private val host: String,
    private val port: Int = 445,
    private val domain: String,
    private val username: String,
    private val password: String,
    private val shareName: String,
) : RemoteFs {

    override val displayUrl: String get() = "smb://$host:$port/$shareName"

    private val mutex = Mutex()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    private val shareAccess: Set<SMB2ShareAccess> = setOf(
        SMB2ShareAccess.FILE_SHARE_READ,
        SMB2ShareAccess.FILE_SHARE_WRITE,
        SMB2ShareAccess.FILE_SHARE_DELETE,
    )

    private fun authContext(): AuthenticationContext =
        if (username.isBlank()) AuthenticationContext.anonymous()
        else AuthenticationContext(username, password.toCharArray(), domain.ifBlank { "" })

    /** يضمن وجود جلسة SMB نشطة (يُعاد الاتصال تلقائياً عند الانقطاع في العملية التالية) */
    private fun ensureSession(): DiskShare {
        val existing = share
        if (existing != null) return existing
        val cfg = SmbConfig.builder()
            .withMultiProtocolNegotiate(true)
            .withTimeout(15, TimeUnit.SECONDS)
            .build()
        val c = SMBClient(cfg).also { client = it }
        val conn: Connection = try {
            c.connect(host, port)
        } catch (e: IOException) {
            throw IOException("تعذّر الوصول إلى $host:$port — ${e.message}", e)
        }.also { connection = it }
        val s: Session = conn.authenticate(authContext()).also { session = it }
        val sh = s.connectShare(shareName)
        if (sh !is DiskShare) {
            runCatching { sh.close() }
            throw IOException("«$shareName» ليست مشاركة أقراص (النوع غير مدعوم)")
        }
        return sh.also { share = it }
    }

    private suspend fun <T> useShare(block: (DiskShare) -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                block(ensureSession())
            } catch (e: SMBApiException) {
                reset()
                throw IOException("SMB خطأ ${e.statusCode}: ${e.message}", e)
            } catch (e: IOException) {
                reset()
                throw e
            } catch (e: Throwable) {
                reset()
                throw e
            }
        }
    }

    private fun reset() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        share = null; session = null; connection = null; client = null
    }

    override suspend fun list(path: String): List<RemoteEntry> = useShare { sh ->
        val rel = RemotePaths.toSmbRel(path)
        val infos = sh.list(rel)
        infos.filter { info ->
            val n = info.fileName
            n != "." && n != ".." && n.isNotBlank()
        }.map { info ->
            val name = info.fileName
            val isDir = info.fileAttributes and 0x10L != 0L // FILE_ATTRIBUTE_DIRECTORY
            RemoteEntry(
                name = name,
                path = RemotePaths.join(path, name),
                isDir = isDir,
                size = if (isDir) 0L else info.endOfFile,
                lastModified = info.lastWriteTime?.toEpochMillis() ?: 0L,
            )
        }.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    override suspend fun download(remotePath: String, target: File, onProgress: (Long) -> Unit): Unit =
        useShare { sh ->
            val rel = RemotePaths.toSmbRel(remotePath)
            target.parentFile?.mkdirs()
            val f = sh.openFile(
                rel,
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.noneOf(FileAttributes::class.java),
                shareAccess,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            f.use { file ->
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
                file.inputStream.use { input -> input.copyTo(out, 64 * 1024) }
                runCatching { out.flush(); out.close() }
            }
        }

    override suspend fun upload(source: File, remoteDir: String, onProgress: (Long) -> Unit): Unit =
        useShare { sh ->
            val rel = RemotePaths.toSmbRel(RemotePaths.join(remoteDir, source.name))
            val f = sh.openFile(
                rel,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.noneOf(FileAttributes::class.java),
                shareAccess,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            f.use { file ->
                val counted = object : FilterInputStream(source.inputStream().buffered()) {
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
                counted.use { input ->
                    file.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
        }

    override suspend fun delete(path: String, isDir: Boolean): Unit = useShare { sh ->
        val rel = RemotePaths.toSmbRel(path)
        if (isDir) sh.rmdir(rel, true) else sh.rm(rel)
    }

    override suspend fun mkdir(parent: String, name: String): Unit = useShare { sh ->
        sh.mkdir(RemotePaths.toSmbRel(RemotePaths.join(parent, name)))
    }

    override suspend fun rename(path: String, newName: String): Unit = useShare { sh ->
        val rel = RemotePaths.toSmbRel(path)
        sh.setFileInformation(rel, FileRenameInformation(false, 0L, newName))
    }

    override suspend fun test(): String = useShare { sh ->
        sh.list("")
        "OK"
    }

    override fun close() = reset()
}
