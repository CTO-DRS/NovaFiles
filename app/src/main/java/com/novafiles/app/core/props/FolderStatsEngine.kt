package com.novafiles.app.core.props

import com.novafiles.app.core.analyzer.Categories
import com.novafiles.app.core.analyzer.StorageCategory
import java.io.File

/**
 * v0.36.0 — محرك إحصاء المجلدات الخالص: عدّادات شاملة لورقة الخصائص.
 *
 * مسح تكراري حتمي (LIFO) مع: سقف عمق 50، سقف 100k عقدة، حارس دورات عبر
 * المسارات المطلقة، ودمج الأعطال (مجلد غير مقروء يُحسب كمجلد فارغ دون انهيار).
 * الفئات تعيد استخدام تجميع المحلل (Categories) لاتساق الأرقام مع Treemap.
 *
 * القابلية للاختبار: مزوّد الأبناء listDirs قابل للحقن — الاختبارات JVM
 * تعمل على أقراص مؤقتة أو شجرة مصنوعة يدوياً بلا لمس القرص الفعلي.
 */
object FolderStatsEngine {

    /** صف عنصر داخل المجلد (ملف) — لأكبر عنصر وأقدم/أحدث زمن */
    data class FileEntry(val path: String, val name: String, val size: Long, val modified: Long)

    data class FolderStats(
        val totalBytes: Long,
        val fileCount: Int,
        val dirCount: Int,
        val maxDepth: Int,
        /** عدد الملفات لكل فئة (فقط الملفات — المجلدات لا تُصنَّف) */
        val byCategory: Map<StorageCategory, Int>,
        val largest: FileEntry? = null,
        val oldestModified: Long? = null,
        val newestModified: Long? = null,
    ) {
        /** الفئة المهيمنة بالعدد — null للمجلد الفارغ */
        val dominantCategory: StorageCategory?
            get() = byCategory.entries.filter { it.value > 0 }.maxByOrNull { it.value }?.key
    }

    /** مزوّد أبناء المجلد — الافتراضي java.io.File، والاختبارات تحقن شجرتها */
    fun interface Lister {
        fun list(dir: File): List<File>
    }

    private val FS_LISTER = Lister { d -> d.listFiles()?.toList() ?: emptyList() }

    fun scan(
        root: File,
        lister: Lister = FS_LISTER,
        depthCap: Int = DEFAULT_MAX_DEPTH,
        maxNodes: Int = DEFAULT_MAX_NODES,
    ): FolderStats {
        var totalBytes = 0L
        var fileCount = 0
        var dirCount = 0
        var seenMaxDepth = 0
        val byCat = mutableMapOf<StorageCategory, Int>()
        var largest: FileEntry? = null
        var oldest: Long? = null
        var newest: Long? = null
        val visited = HashSet<String>()

        // مكدس من (مجلد، عمقه) — LIFO يعطي ترتيب DFS حتمياً
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        var nodes = 0

        while (stack.isNotEmpty() && nodes < maxNodes) {
            val (dir, depth) = stack.removeLast()
            val key = runCatching { dir.absolutePath }.getOrDefault(dir.path)
            if (!visited.add(key)) continue // حارس دورات (روابط رمزية)
            nodes++

            if (depth > depthCap) continue // حارس أمان — الدفع نفسه مقصوص أدناه
            val children = runCatching { lister.list(dir) }.getOrDefault(emptyList())
            for (c in children) {
                if (c.isDirectory) {
                    dirCount++ // يُعدّ كل مجلد مكتشف حتى لو اقتُص عمقه
                    if (depth + 1 <= depthCap) {
                        seenMaxDepth = maxOf(seenMaxDepth, depth + 1)
                        stack.addLast(c to depth + 1)
                    }
                } else {
                    val size = runCatching { c.length() }.getOrDefault(0L).coerceAtLeast(0)
                    val mtime = runCatching { c.lastModified() }.getOrDefault(0L)
                    totalBytes += size
                    fileCount++
                    val cat = Categories.of(c.name)
                    byCat[cat] = (byCat[cat] ?: 0) + 1
                    if (largest == null || size > largest!!.size) {
                        largest = FileEntry(c.absolutePath, c.name, size, mtime)
                    }
                    if (mtime > 0) {
                        oldest = minOf(oldest ?: mtime, mtime)
                        newest = maxOf(newest ?: mtime, mtime)
                    }
                }
            }
        }

        return FolderStats(
            totalBytes = totalBytes,
            fileCount = fileCount,
            dirCount = dirCount,
            maxDepth = seenMaxDepth,
            byCategory = byCat,
            largest = largest,
            oldestModified = oldest,
            newestModified = newest,
        )
    }

    const val DEFAULT_MAX_DEPTH = 50
    const val DEFAULT_MAX_NODES = 100_000
}
