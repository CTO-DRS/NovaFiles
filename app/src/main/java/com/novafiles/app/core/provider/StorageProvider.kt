package com.novafiles.app.core.provider

import com.novafiles.app.core.network.RemotePaths
import java.io.File

/**
 * v0.13 — تجريد StorageProvider: واجهة SDK-Style واحدة للمخازن كلها.
 *
 * هذا هو حجر الأساس لرؤية «السحابة الموحّدة»: التصفح المحلي و FTP/SFTP/SMB
 * و WebDAV — ولاحقاً Drive/Dropbox/OneDrive (v0.16) — كلها خلف واجهة واحدة،
 * فتعمل مدير النقل v2 ومحرك البحث وقوائم الملفات على أي مخزن بلا تمييز.
 *
 * اصطلاح المسارات: مسار مطلق داخل جذر المزوّد بمفاصل «/»، والجذر هو ""
 * (نفس اصطلاح RemoteFs) — مثال: "/Documents/ملاحظات.txt".
 *
 * الفرق عن RemoteFs: RemoteFs واجهة بروتوكولات الشبكة فقط؛ StorageProvider
 * أعم — يشمل التخزين المحلي، ويحمل بيانات غنية (ETag، القدرات)، ويلغّم
 * العمليات التي لا يدعمها المخزن بدل أن تختفي بصمت.
 */

/** عنصر في مخزن — إثراء RemoteEntry بـ ETag لذاكرة السرد وكشف التعارض */
data class ProviderEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val etag: String? = null,
)

/** قدرات المزوّد — تسألها الشاشات قبل عرض الأزرار أو تنفيذ العملية */
enum class ProviderCapability {
    LIST, READ, WRITE, MKDIR, DELETE, RENAME,
    SERVER_COPY, SERVER_MOVE, RANGE_READ,
}

/** عملية غير مدعومة من هذا المزوّد — تُعرض للمستخدم كرسالة لا بصمت */
class UnsupportedCapabilityException(cap: ProviderCapability) :
    IllegalStateException("provider does not support ${cap.name}")

interface StorageProvider : AutoCloseable {
    /** معرّف فريد لمثيل المزوّد (مثل conn-7 أو local-primary) */
    val id: String

    /** مخطط المخزن: local | ftp | sftp | smb | webdav */
    val scheme: String

    /** وصف نصي للاتصال يظهر في الشريط */
    val displayUrl: String

    /** القدرات المعلنة — الافتراضي المتفائل كل شيء ما عدا النسخ الخادمية */
    val capabilities: Set<ProviderCapability>
        get() = ProviderCapability.entries.toSet() - ProviderCapability.SERVER_COPY

    fun can(cap: ProviderCapability): Boolean = cap in capabilities

    /** سرد مجلد (الجذر "") */
    suspend fun list(path: String): List<ProviderEntry>

    /** خصائص عنصر واحد — تستخدمه ذاكرة السرد ومدير النقل قبل الاستئناف */
    suspend fun stat(path: String): ProviderEntry

    /** قراءة ملف كامل إلى ملف محلي مع تقدم بايتي */
    suspend fun readTo(path: String, target: File, onProgress: (Long) -> Unit = {})

    /** رفع ملف محلي إلى مجلد بعيد — يعيد ETag الملف الجديد إن توفره */
    suspend fun writeFrom(source: File, dir: String, onProgress: (Long) -> Unit = {}): String?

    /** إنشاء مجلد */
    suspend fun mkdir(parent: String, name: String)

    /** حذف ملف أو مجلد */
    suspend fun delete(path: String, isDir: Boolean)

    /** إعادة تسمية (داخل المجلد نفسه) */
    suspend fun rename(path: String, newName: String)

    /** نقل خادمي بين مجلدين إن دعمه المخزن وإلا UnsupportedCapabilityException */
    suspend fun move(path: String, targetDir: String, newName: String) {
        if (RemotePaths.parentOf(path) == targetDir) return rename(path, newName)
        throw UnsupportedCapabilityException(ProviderCapability.SERVER_MOVE)
    }

    /** نسخ خادمي إن دعمه المخزن وإلا UnsupportedCapabilityException */
    suspend fun copy(path: String, targetDir: String, newName: String) {
        throw UnsupportedCapabilityException(ProviderCapability.SERVER_COPY)
    }

    /**
     * قراءة مدى بايتي من إزاحة معينة إلى sink — أساس استئناف التنزيل
     * في مدير النقل v2. المزوّدات التي تدعمها تعلن RANGE_READ.
     */
    suspend fun readRangeTo(
        path: String,
        offset: Long,
        sink: java.io.OutputStream,
        onProgress: (Long) -> Unit = {},
    ) {
        throw UnsupportedCapabilityException(ProviderCapability.RANGE_READ)
    }

    /** اختبار الاتصال — يعيد اسم المورد الجذر */
    suspend fun test(): String

    override fun close()
}
