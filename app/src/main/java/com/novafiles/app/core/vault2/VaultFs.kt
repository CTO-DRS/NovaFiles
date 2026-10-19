package com.novafiles.app.core.vault2

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * v0.15 — تجريد نظام ملفات الخزنة: يمكّن وضع «الخزنة على بطاقة SD» عبر SAF
 * دون تغيير أي منطق تشفير. التنفيذات:
 *  - [LocalFileFs]  : java.io.File (التخزين الداخلي/الأولي)
 *  - InMemoryFs     : الاختبارات (في ملف الاختبار)
 *  - SafVaultFs     : طبقة أندرويد (DocumentFile) — انظر Vault2Manager
 */
interface VaultFs : AutoCloseable {
    fun exists(path: String): Boolean
    fun size(path: String): Long
    fun list(path: String): List<String>
    fun openRead(path: String): InputStream
    fun openWrite(path: String): OutputStream
    fun delete(path: String): Boolean
    fun rename(from: String, to: String): Boolean
}

/** نظام ملفات محلي متجذر على مجلد واحد — يعمل على JVM وأندرويد معاً */
class LocalFileFs(private val root: File) : VaultFs {

    init { root.mkdirs() }

    private fun resolve(path: String): File {
        require(!path.contains("..")) { "path traversal rejected" }
        return File(root, path.trimStart('/'))
    }

    override fun exists(path: String): Boolean = resolve(path).exists()

    override fun size(path: String): Long = resolve(path).length()

    override fun list(path: String): List<String> =
        resolve(path).listFiles()?.map { it.name }?.sorted() ?: emptyList()

    override fun openRead(path: String): InputStream = FileInputStream(resolve(path))

    override fun openWrite(path: String): OutputStream {
        val f = resolve(path)
        f.parentFile?.mkdirs()
        return FileOutputStream(f)
    }

    override fun delete(path: String): Boolean = resolve(path).delete()

    override fun rename(from: String, to: String): Boolean = resolve(from).renameTo(resolve(to))

    /** مسار النظام المحلي (يستخدمه المسح الآمن فوق الملف الفعلي) */
    fun fileFor(path: String): File = resolve(path)

    override fun close() { /* لا موارد */ }
}
