package com.novafiles.app.core.sync

import com.novafiles.app.core.cloud.CloudFs
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v0.32.0 — جانب زوج مزامنة: مصدر أو وجهة.
 *
 * الأزواج تعمل فوق تجريد المخازن (قاعدة الخارطة): محلي↔محلي أو
 * محلي↔سحابة عبر [CloudFs] لأي مزود. كل جانب يبني لقطة [SyncNode]
 * ويوفر عمليات IO الضيقة التي تحتاجها الخطة: قراءة/كتابة/حذف/إنشاء
 * مجلدات/نسخ داخلي لنسخة التعارض.
 */
interface SyncSide : AutoCloseable {
    /** وصف للعرض في التقارير (المسار أو رابط المزود) */
    val label: String

    /** لقطة كاملة للجانب — مرتبة حتماً (مسار نسبي مفروز) */
    suspend fun snapshot(): List<SyncNode>

    /** فتح تدفق قراءة لملف بمسار نسبي — المستهلك مسؤول عن الإغلاق */
    suspend fun openInput(relativePath: String): InputStream

    /** إنشاء مجلد بكل آبائه بمسار نسبي */
    suspend fun mkdirs(relativePath: String)

    /** كتابة ملف من تدفق بحجم معلوم — يعيد عدد البايتات المكتوبة */
    suspend fun write(relativePath: String, size: Long, input: InputStream, onProgress: (Long) -> Unit = {}): Long

    /** حذف ملف أو مجلد (تكراري للمجلدات) */
    suspend fun delete(relativePath: String, isDir: Boolean)

    /** نسخ داخلي داخل الجانب نفسه — أساس «الاحتفاظ بالنسختين» */
    suspend fun copyWithin(relativePath: String, newRelativePath: String)

    /** ضبط زمن التعديل — أفضل جهد (السحابي قد يتجاهله) */
    suspend fun setModified(relativePath: String, mtime: Long)
}

// ═══════════════════════ الجانب المحلي (java.io خالص) ═══════════════════════

/**
 * جانب محلي فوق java.io — قابل للاختبار 100% على أقراص مؤقتة JVM.
 * المسارات النسبية تُحل تحت الجذر؛ أي محاولة صعود خارج الجذر تُرفض
 * (نمط حارس Zip-Slip القائم في المشروع).
 */
class LocalSide(private val root: File) : SyncSide {

    override val label: String get() = root.absolutePath

    private fun resolve(relativePath: String): File {
        val raw = relativePath.trim()
        require(raw.isNotEmpty()) { "empty relative path" }
        // حارس تسلل صريح قبل التطبيع — «..» الخام يُرفض لا يُبتلع
        require(!raw.split('/', '\\').contains("..")) { "path traversal: $relativePath" }
        val clean = SyncPairEngine.normalizePath(raw)
        val f = File(root, clean)
        val rootPath = root.canonicalPath + File.separator
        require(f.canonicalPath.startsWith(rootPath)) { "path escapes root: $relativePath" }
        return f
    }

    override suspend fun snapshot(): List<SyncNode> {
        val out = ArrayList<SyncNode>()
        walk(root, "", out)
        out.sortBy { it.relativePath }
        return out
    }

    private fun walk(dir: File, prefix: String, out: MutableList<SyncNode>) {
        val children = dir.listFiles() ?: return
        for (child in children.sortedBy { it.name }) {
            val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                out.add(SyncNode(rel, 0L, child.lastModified(), isDir = true))
                walk(child, rel, out)
            } else if (child.isFile) {
                out.add(SyncNode(rel, child.length(), child.lastModified(), isDir = false))
            }
        }
    }

    override suspend fun openInput(relativePath: String): InputStream =
        resolve(relativePath).inputStream()

    override suspend fun mkdirs(relativePath: String) {
        resolve(relativePath).mkdirs()
    }

    override suspend fun write(relativePath: String, size: Long, input: InputStream, onProgress: (Long) -> Unit): Long {
        val target = resolve(relativePath)
        target.parentFile?.mkdirs()
        var done = 0L
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                onProgress(done)
            }
        }
        return done
    }

    override suspend fun delete(relativePath: String, isDir: Boolean) {
        val f = resolve(relativePath)
        if (isDir) f.deleteRecursively() else f.delete()
    }

    override suspend fun copyWithin(relativePath: String, newRelativePath: String) {
        val from = resolve(relativePath)
        val to = resolve(newRelativePath)
        to.parentFile?.mkdirs()
        from.copyTo(to, overwrite = false)
    }

    override suspend fun setModified(relativePath: String, mtime: Long) {
        resolve(relativePath).setLastModified(mtime)
    }

    override fun close() { /* لا موارد محلية تُغلق */ }
}

// ═══════════════════════ الجانب السحابي (فوق CloudFs) ═══════════════════════

/**
 * جانب سحابي فوق [CloudFs] — يعمل مع أي مزود (Drive/Dropbox/OneDrive)
 * بلا تمييز: السرد يتكرر من جذر الزوج، والكتابة عبر writeFromStream
 * (بث مباشر)، ونسخة التعارض عبر النسخ الخادمي عند توفره وإلا إعادة رفع.
 */
class CloudSide(
    private val fs: CloudFs,
    private val rootPath: String,
    override val label: String = rootPath,
) : SyncSide {

    /** المسار الكامل داخل المزود لمسار نسبي ("" = جذر المزود) */
    private fun full(relativePath: String): String {
        val clean = SyncPairEngine.normalizePath(relativePath)
        val base = rootPath.trim('/')
        if (clean.isEmpty()) return if (base.isEmpty()) "" else "/$base"
        return if (base.isEmpty()) "/$clean" else "/$base/$clean"
    }

    /** المسار النسبي من مسار مزود كامل تحت الجذر (أو null خارج الجذر) */
    private fun relativeOf(fullPath: String): String? {
        val base = rootPath.trim('/')
        val p = fullPath.trim('/')
        if (base.isEmpty()) return p.ifEmpty { null }
        return if (p.startsWith("$base/")) p.removePrefix("$base/") else null
    }

    /**
     * تحويل القراءة المتدفقة consumer-style في CloudFs إلى InputStream
     * عبر أنبوب — منتج في نطاق IO مستقل لا يُنتظر (كان انتظاره ميتاً
     * حتمياً: الأنبوب يمتلئ والمنتج ينتظر مستهلكاً لم يصل بعد).
     * المستهلك الذي يغلق الأنبوب مبكراً يفكك المنتج بخطأ أنبوب مكسور.
     */
    private fun pipedFrom(providerPath: String): InputStream {
        val pipedIn = java.io.PipedInputStream(256 * 1024)
        val pipedOut = java.io.PipedOutputStream(pipedIn)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            try {
                fs.readFrom(providerPath) { stream ->
                    stream.use { it.copyTo(pipedOut, 64 * 1024) }
                }
                runCatching { pipedOut.close() }
            } catch (_: Throwable) {
                // المستهلك أغلق مبكراً أو فشل السحب — الإغلاق يوقظه بخطأ
                runCatching { pipedOut.close() }
            }
        }
        return pipedIn
    }

    override suspend fun snapshot(): List<SyncNode> {
        val out = ArrayList<SyncNode>()
        val root = rootPath.trim('/')
        walk(if (root.isEmpty()) "" else "/$root", out)
        out.sortBy { it.relativePath }
        return out
    }

    private suspend fun walk(dir: String, out: MutableList<SyncNode>) {
        val entries = runCatching { fs.list(dir) }.getOrDefault(emptyList())
        for (e in entries.sortedBy { it.name }) {
            val rel = relativeOf(e.path) ?: continue
            if (e.isDir) {
                out.add(SyncNode(rel, 0L, e.lastModified, isDir = true))
                walk(e.path, out)
            } else {
                out.add(SyncNode(rel, e.size, e.lastModified, isDir = false))
            }
        }
    }

    override suspend fun openInput(relativePath: String): InputStream =
        pipedFrom(full(relativePath))

    override suspend fun mkdirs(relativePath: String) {
        val clean = SyncPairEngine.normalizePath(relativePath)
        if (clean.isEmpty()) return
        val base = rootPath.trim('/')
        var parent = if (base.isEmpty()) "" else "/$base"
        for (part in clean.split('/')) {
            val target = if (parent.isEmpty()) "/$part" else "$parent/$part"
            val exists = runCatching {
                fs.list(parent).any { it.isDir && it.name == part }
            }.getOrDefault(false)
            if (!exists) {
                runCatching { fs.mkdir(parent, part) }
                    .onFailure { e ->
                        // تعارض الوجود المسبق مقبول (سباق جولتين)
                        if (!e.message.orEmpty().contains("exists", ignoreCase = true)) throw e
                    }
            }
            parent = target
        }
    }

    override suspend fun write(relativePath: String, size: Long, input: InputStream, onProgress: (Long) -> Unit): Long {
        val clean = SyncPairEngine.normalizePath(relativePath)
        val slash = clean.lastIndexOf('/')
        val dirFull = full(if (slash > 0) clean.substring(0, slash) else "")
        val name = clean.substring(slash + 1)
        fs.writeFromStream(dir = dirFull, name = name, size = size, openInput = { input })
        return size
    }

    override suspend fun delete(relativePath: String, isDir: Boolean) {
        fs.delete(full(relativePath), isDir)
    }

    override suspend fun copyWithin(relativePath: String, newRelativePath: String) {
        val src = full(relativePath)
        val cleanNew = SyncPairEngine.normalizePath(newRelativePath)
        val slash = cleanNew.lastIndexOf('/')
        val destDir = full(if (slash > 0) cleanNew.substring(0, slash) else "")
        val name = cleanNew.substring(slash + 1)
        val result = runCatching { fs.copy(src, destDir, name) }
        if (result.isFailure) {
            // لا نسخ خادمي — إعادة رفع من تدفق القراءة
            val stat = runCatching { fs.stat(src) }.getOrNull()
            fs.writeFromStream(
                dir = destDir,
                name = name,
                size = stat?.size ?: 0L,
                openInput = { pipedFrom(src) },
            )
        }
    }

    override suspend fun setModified(relativePath: String, mtime: Long) {
        // المزودات السحابية تضبط زمن الرفع بنفسها — أفضل جهد يعني التجاهل هنا
    }

    override fun close() {
        runCatching { fs.close() }
    }
}
