package com.novafiles.app.core.provider

import com.novafiles.app.core.network.RemoteEntry
import com.novafiles.app.core.network.RemoteFs
import com.novafiles.app.core.network.RemotePaths
import com.novafiles.app.core.network.RangeReadable
import com.novafiles.app.core.network.WebDavFs
import java.io.File
import java.io.FileOutputStream

/**
 * v0.13 — جسر RemoteFs → StorageProvider.
 *
 * كل بروتوكولات الشبكة المجرّبة (FTP/SFTP/SMB/WebDAV) تُغلَّف بهذا المهايئ
 * فتظهر في طبقة التطبيق كمزوّد موحّد — كود مُجرّب لا يُعاد اختراعه.
 * يُفصّل قدرات كل مخطط من نوع الفئة المدرك، ويكشف قدرات WebDAV الإضافية
 * (نسخ/نقل خادمي + قراءة مدى).
 */
class RemoteFsProvider(
    /** معرّف المثيل — عادة conn-<id> من قاعدة الاتصالات */
    override val id: String,
    private val fs: RemoteFs,
) : StorageProvider {

    override val scheme: String = when (fs) {
        is WebDavFs -> "webdav"
        else -> "remote"
    }

    override val displayUrl: String get() = fs.displayUrl

    override val capabilities: Set<ProviderCapability> = buildSet {
        addAll(ProviderCapability.entries.toSet() - ProviderCapability.SERVER_COPY - ProviderCapability.RANGE_READ)
        if (fs is WebDavFs) add(ProviderCapability.SERVER_COPY)
        if (fs is RangeReadable) add(ProviderCapability.RANGE_READ)
    }

    override suspend fun list(path: String): List<ProviderEntry> =
        fs.list(path).map { it.toProvider() }

    override suspend fun stat(path: String): ProviderEntry =
        (fs as? WebDavFs)?.statDav(path)?.let {
            ProviderEntry(
                name = it.displayName, path = it.path, isDir = it.isCollection,
                size = it.contentLength, lastModified = it.lastModified, etag = it.etag,
            )
        } ?: list(RemotePaths.parentOf(path)).firstOrNull { it.path == path }
            ?: ProviderEntry(
                name = RemotePaths.nameOf(path), path = path,
                isDir = false, size = 0, lastModified = 0,
            )

    override suspend fun readTo(path: String, target: File, onProgress: (Long) -> Unit) =
        fs.download(path, target, onProgress)

    override suspend fun writeFrom(source: File, dir: String, onProgress: (Long) -> Unit): String? {
        fs.upload(source, dir, onProgress)
        return null
    }

    override suspend fun mkdir(parent: String, name: String) = fs.mkdir(parent, name)

    override suspend fun delete(path: String, isDir: Boolean) = fs.delete(path, isDir)

    override suspend fun rename(path: String, newName: String) = fs.rename(path, newName)

    override suspend fun move(path: String, targetDir: String, newName: String) {
        if (fs is WebDavFs) return fs.moveServerSide(path, targetDir, newName)
        super.move(path, targetDir, newName)
    }

    override suspend fun copy(path: String, targetDir: String, newName: String) {
        if (fs is WebDavFs) return fs.copyServerSide(path, targetDir, newName)
        super.copy(path, targetDir, newName)
    }

    override suspend fun readRangeTo(
        path: String,
        offset: Long,
        sink: java.io.OutputStream,
        onProgress: (Long) -> Unit,
    ) {
        if (fs is RangeReadable) return fs.readRange(path, offset, sink, onProgress)
        super.readRangeTo(path, offset, sink, onProgress)
    }

    override suspend fun test(): String = fs.test()

    /** الوصول الخام للمكوّنات القديمة التي تطلب RemoteFs مباشرة */
    fun unwrap(): RemoteFs = fs

    override fun close() = fs.close()
}

/** مزوّد التخزين المحلي — جذر واحد (مثل ذاكرة التطبيق أو SD) خلف الواجهة الموحدة */
class LocalStorageProvider(
    override val id: String,
    /** جذر المزوّد على القرص — كل المسارات الداخلية نسبية إليه */
    private val root: File,
    override val displayUrl: String = "local://${root.absolutePath}",
) : StorageProvider {

    override val scheme: String = "local"

    override val capabilities: Set<ProviderCapability> =
        ProviderCapability.entries.toSet() - ProviderCapability.RANGE_READ

    /** يحوّل مساراً موحّداً إلى ملف فعلي داخل الجذر — ويمنع الهروب خارج الجذر (Slip) */
    private fun resolve(path: String): File {
        val clean = path.trim('/').replace('\\', '/')
        if (clean.isEmpty()) return root
        val f = File(root, clean)
        val rootPath = root.canonicalPath + File.separator
        if (!f.canonicalPath.startsWith(rootPath) && f.canonicalPath != root.canonicalPath)
            throw SecurityException("path escapes provider root: $path")
        return f
    }

    private fun entryOf(f: File): ProviderEntry {
        val rel = f.absolutePath.removePrefix(root.absolutePath).replace(File.separatorChar, '/')
        return ProviderEntry(
            name = f.name,
            path = "/" + rel.trimStart('/'),
            isDir = f.isDirectory,
            size = if (f.isFile) f.length() else 0L,
            lastModified = f.lastModified(),
        )
    }

    override suspend fun list(path: String): List<ProviderEntry> {
        val dir = resolve(path)
        if (!dir.exists()) throw java.io.IOException("not found: ${path.ifEmpty { "/" }}")
        val children = dir.listFiles() ?: return emptyList()
        return children
            .filter { it.name != ".nfp" && !it.name.endsWith(".nfpart") && !it.name.endsWith(".nfjournal") } // ملفات النظام والنقل المؤقتة لا تظهر
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .map { entryOf(it) }
    }

    override suspend fun stat(path: String): ProviderEntry {
        val f = resolve(path)
        if (!f.exists()) throw java.io.IOException("not found: ${path.ifEmpty { "/" }}")
        return entryOf(f)
    }

    override suspend fun readTo(path: String, target: File, onProgress: (Long) -> Unit) {
        val src = resolve(path)
        var done = 0L
        src.inputStream().use { ins ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done)
                }
            }
        }
    }

    override suspend fun writeFrom(source: File, dir: String, onProgress: (Long) -> Unit): String? {
        val target = resolve(RemotePaths.join(dir, source.name))
        var done = 0L
        source.inputStream().use { ins ->
            FileOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done)
                }
            }
        }
        return null
    }

    override suspend fun mkdir(parent: String, name: String) {
        val d = resolve(RemotePaths.join(parent, name))
        if (!d.mkdirs() && !d.isDirectory)
            throw java.io.IOException("mkdir failed: $name")
    }

    override suspend fun delete(path: String, isDir: Boolean) {
        val f = resolve(path)
        if (!f.exists()) return
        val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (!ok) throw java.io.IOException("delete failed: ${f.name}")
    }

    override suspend fun rename(path: String, newName: String) {
        val f = resolve(path)
        val to = File(f.parentFile, newName)
        if (to.exists()) throw com.novafiles.app.core.network.DavConflictException("conflict: $newName")
        if (!f.renameTo(to)) throw java.io.IOException("rename failed: ${f.name}")
    }

    override suspend fun move(path: String, targetDir: String, newName: String) {
        val f = resolve(path)
        val to = resolve(RemotePaths.join(targetDir, newName))
        if (to.exists()) throw com.novafiles.app.core.network.DavConflictException("conflict: $newName")
        if (!f.renameTo(to)) {
            // عبر أنظمة الملفات المختلفة — نسخ ثم حذف
            to.outputStream().use { out -> f.inputStream().use { it.copyTo(out, 64 * 1024) } }
            if (f.isDirectory) f.deleteRecursively() else f.delete()
        }
    }

    override suspend fun copy(path: String, targetDir: String, newName: String) {
        val f = resolve(path)
        val to = resolve(RemotePaths.join(targetDir, newName))
        if (to.exists()) throw com.novafiles.app.core.network.DavConflictException("conflict: $newName")
        if (f.isDirectory) f.copyRecursively(to) else {
            to.outputStream().use { out -> f.inputStream().use { it.copyTo(out, 64 * 1024) } }
        }
    }

    override suspend fun test(): String = root.name

    override fun close() { /* لا موارد */ }
}

/** تحويل موحّد بين نماذج العناصر */
fun RemoteEntry.toProvider(): ProviderEntry = ProviderEntry(
    name = name, path = path, isDir = isDir,
    size = size, lastModified = lastModified, etag = null,
)
