package com.novafiles.app.core.cloud

import java.io.File
import java.io.InputStream

/**
 * v0.16 — واجهة نظام الملفات السحابي الموحّدة (نظير RemoteFs لكن سحابي).
 *
 * الاختلافات الجوهرية عن RemoteFs:
 *  - [CloudEntry] يحمل etag/hasHash (خادمي) — أساس كشف التعارض وexact-once
 *  - [writeFromStream] رفع من تدفق مباشر — أساس النقل الموزّع بلا تمرير عبر القرص
 *  - [readFrom] تنزيل من إزاحة (Range) — أساس استئناف التنزيل
 *  - جلسات رفع قابلة للاستئناف عبر [UploadSessionStore] (يبقى الرفع حياً بعد
 *    موت العملية — معيار نجاح الخارطة: «2 GB resumable upload survives death»)
 *
 * اصطلاح المسارات: نفس اصطلاح RemoteFs — "" للجذر و"/" مفاصل (Dropbox/OneDrive
 * أصلاً مساري، وDrive يُحل مسارياً عبر ذاكرة path→id داخل المهايئ).
 */
data class CloudEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
    /** معرّف سحابي خام (Drive id، Dropbox id، Graph id) */
    val remoteId: String? = null,
    /** بصمة خادمية (etag أو md5) إن توفرت */
    val etag: String? = null,
)

/** خطأ عنصر غير موجود — يترجم 404 من كل المزوّدين */
class CloudNotFoundException(path: String, provider: String) :
    java.io.FileNotFoundException("$provider: not found: $path")

/** خطأ اسم موجود مسبقاً — يترجم 409 من كل المزوّدين */
class CloudConflictException(name: String, provider: String) :
    java.io.IOException("$provider: name exists: $name")

/**
 * واجهة CloudFs — تطبّقها المهايئات الثلاثة.
 */
interface CloudFs : AutoCloseable {
    val displayUrl: String

    suspend fun list(path: String): List<CloudEntry>
    suspend fun stat(path: String): CloudEntry

    /** قراءة متدفقة من إزاحة — أساس التنزيل والاستئناف والنقل الموزّع */
    suspend fun readFrom(path: String, offset: Long = 0L, consumer: suspend (InputStream) -> Unit)

    /** تنزيل كامل أو من إزاحة (إن دعمه المزوّد) إلى ملف محلي — يبني فوق readFrom */
    suspend fun readTo(
        path: String,
        target: File,
        offset: Long = 0L,
        onProgress: (Long) -> Unit = {},
    ) {
        target.parentFile?.mkdirs()
        java.io.FileOutputStream(target, offset > 0).use { out ->
            var done = 0L
            readFrom(path, offset) { stream ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done)
                }
            }
        }
    }

    /** رفع ملف محلي — يستخدم جلسة قابلة للاستئناف للملفات الكبيرة */
    suspend fun writeFrom(source: File, dir: String, onProgress: (Long) -> Unit = {}): CloudEntry

    /** رفع من تدفق — أساس النقل الموزّع (بث مزوّد→مزوّد بلا تخزين وسيط) */
    suspend fun writeFromStream(
        dir: String,
        name: String,
        size: Long,
        openInput: () -> InputStream,
        onProgress: (Long) -> Unit = {},
    ): CloudEntry

    suspend fun mkdir(parent: String, name: String): CloudEntry
    suspend fun delete(path: String, isDir: Boolean)
    suspend fun rename(path: String, newName: String)
    suspend fun move(path: String, targetDir: String, newName: String)
    suspend fun copy(path: String, targetDir: String, newName: String)
    suspend fun test(): String

    override fun close()
}

/**
 * مخزن جلسات الرفع القابلة للاستئناف — يحفظ رابط الجلسة والإزاحة لكل ملف
 * (الإنتاج: Room، الاختبارات: ذاكرة) فيصمد الرفع بعد موت العملية.
 */
interface UploadSessionStore {
    fun load(key: String): UploadSession?
    fun save(key: String, session: UploadSession)
    fun remove(key: String)

    data class UploadSession(
        val url: String,
        val offset: Long,
        val createdAtMs: Long,
        val totalBytes: Long,
    )
}

class InMemorySessionStore : UploadSessionStore {
    private val map = HashMap<String, UploadSessionStore.UploadSession>()
    override fun load(key: String) = map[key]
    override fun save(key: String, session: UploadSessionStore.UploadSession) { map[key] = session }
    override fun remove(key: String) { map.remove(key) }
}

/** جسر CloudFs → StorageProvider: يعلن قدرات السحابة ويرفع ETag للسرد */
class CloudFsProvider(
    override val id: String,
    private val fs: CloudFs,
    /** مفتاح المزوّد: drive | dropbox | onedrive */
    val providerKey: String,
) : com.novafiles.app.core.provider.StorageProvider {

    override val scheme: String = providerKey
    override val displayUrl: String get() = fs.displayUrl

    override val capabilities: Set<com.novafiles.app.core.provider.ProviderCapability> =
        setOf(
            com.novafiles.app.core.provider.ProviderCapability.LIST,
            com.novafiles.app.core.provider.ProviderCapability.READ,
            com.novafiles.app.core.provider.ProviderCapability.WRITE,
            com.novafiles.app.core.provider.ProviderCapability.MKDIR,
            com.novafiles.app.core.provider.ProviderCapability.DELETE,
            com.novafiles.app.core.provider.ProviderCapability.RENAME,
            com.novafiles.app.core.provider.ProviderCapability.SERVER_COPY,
            com.novafiles.app.core.provider.ProviderCapability.SERVER_MOVE,
            com.novafiles.app.core.provider.ProviderCapability.RANGE_READ,
        )

    override suspend fun list(path: String) =
        fs.list(path).map {
            com.novafiles.app.core.provider.ProviderEntry(
                name = it.name, path = it.path, isDir = it.isDir,
                size = it.size, lastModified = it.lastModified, etag = it.etag,
            )
        }

    override suspend fun stat(path: String): com.novafiles.app.core.provider.ProviderEntry {
        val e = fs.stat(path)
        return com.novafiles.app.core.provider.ProviderEntry(
            name = e.name, path = e.path, isDir = e.isDir,
            size = e.size, lastModified = e.lastModified, etag = e.etag,
        )
    }

    override suspend fun readTo(
        path: String,
        target: File,
        onProgress: (Long) -> Unit,
    ) = fs.readTo(path, target, 0L, onProgress)

    /** استئناف التنزيل عبر readFrom بإزاحة — يستخدمه TransferManager v2 */
    override suspend fun readRangeTo(
        path: String,
        offset: Long,
        sink: java.io.OutputStream,
        onProgress: (Long) -> Unit,
    ) {
        var done = 0L
        fs.readFrom(path, offset) { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                done += n
                onProgress(done)
            }
        }
    }

    override suspend fun writeFrom(source: File, dir: String, onProgress: (Long) -> Unit): String? {
        fs.writeFrom(source, dir, onProgress)
        return null
    }

    override suspend fun mkdir(parent: String, name: String) {
        fs.mkdir(parent, name)
    }

    override suspend fun delete(path: String, isDir: Boolean) = fs.delete(path, isDir)

    override suspend fun rename(path: String, newName: String) = fs.rename(path, newName)

    override suspend fun move(path: String, targetDir: String, newName: String) =
        fs.move(path, targetDir, newName)

    override suspend fun copy(path: String, targetDir: String, newName: String) =
        fs.copy(path, targetDir, newName)

    override suspend fun test(): String = fs.test()

    /** وصول خام للنقل الموزّع والواجهات الأعلى */
    fun unwrap(): CloudFs = fs

    override fun close() = fs.close()
}
