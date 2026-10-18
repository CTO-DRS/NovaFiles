package com.novafiles.app.core.network

import java.io.File

/**
 * طبقة الشبكة الموحّدة — واجهة نظام ملفات بعيد واحدة تعمل فوق بروتوكولات متعددة
 * (FTP عبر Apache Commons Net، و SMB2/3 عبر SMBJ)، بحيث تتعامل الشاشات معها
 * بلا اهتمام بالبروتوكول الأساسي.
 *
 * اصطلاح المسارات: مسار مطلق داخل الجذر البعيد بمفاصل «/»، والجذر هو "" (فارغ).
 * مثال: "/Documents/ملاحظات.txt" — لـ FTP يُستخدم كما هو، ولـ SMB يُحوَّل إلى
 * مسار نسبي داخل المشاركة بفواصل «\» داخلياً.
 */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val lastModified: Long,
)

interface RemoteFs : AutoCloseable {
    /** وصف نصي للاتصال يظهر في الشريط (مثل ftp://192.168.1.5:2121) */
    val displayUrl: String

    /** سرد محتوى مجلد بعيد (المسار "" يعني الجذر) */
    suspend fun list(path: String): List<RemoteEntry>

    /** تنزيل ملف بعيد إلى ملف محلي مع تقرير تقدم بالبايتات */
    suspend fun download(remotePath: String, target: File, onProgress: (Long) -> Unit = {})

    /** رفع ملف محلي إلى مجلد بعيد مع تقرير تقدم بالبايتات */
    suspend fun upload(source: File, remoteDir: String, onProgress: (Long) -> Unit = {})

    /** حذف ملف أو مجلد بعيد */
    suspend fun delete(path: String, isDir: Boolean)

    /** إنشاء مجلد جديد داخل مجلد بعيد */
    suspend fun mkdir(parent: String, name: String)

    /** إعادة تسمية عنصر بعيد (بدون تغيير مجلده) */
    suspend fun rename(path: String, newName: String)

    /** اختبار الاتصال: يعود بالاسم البعيد إن نجح ويرمي استثناء إن فشل */
    suspend fun test(): String

    override fun close()
}

/** أدوات مساعدة لتوحيد مسارات الشبكة بين البروتوكولات */
object RemotePaths {
    /** ضمّ اسم عنصر إلى مسار أب بعيد */
    fun join(parent: String, name: String): String =
        if (parent.isBlank() || parent == "/") "/$name"
        else "${parent.trimEnd('/')}/$name"

    /** اسم العنصر الأخير من مسار بعيد */
    fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** المسار الأب لمسار بعيد ("" للجذر) */
    fun parentOf(path: String): String {
        val p = path.trimEnd('/')
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "" else p.substring(0, idx)
    }

    /** مسار نسبي بفواصل SMB («\») من مسار موحّد */
    fun toSmbRel(path: String): String =
        path.trim('/').replace('/', '\\').let { if (it.isBlank()) "" else it }
}
