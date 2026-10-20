package com.novafiles.app.core.checksum

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * v0.29.0 — محرك البصمات الخالص (pure JVM)
 *
 * قاعدة الخارطة: محرك بصمات خالص بتدفقات موفرة للذاكرة —
 * مخزن ثابت 64KB مهما كان حجم الملف، تقدم لكل ملف (عدد + بايتات)،
 * إلغاء تعاوني بين الملفات، وجمع مجلدات حتمي الترتيب.
 */

/** خوارزميات البصمة المدعومة */
enum class ChecksumAlgorithm(val jcaName: String, val hexLength: Int) {
    MD5("MD5", 32),
    SHA1("SHA-1", 40),
    SHA256("SHA-256", 64),
    ;

    companion object {
        /** استنتاج الخوارزمية من طول البصمة السداسي (32/40/64) */
        fun byHexLength(hex: String): ChecksumAlgorithm? = entries.firstOrNull { it.hexLength == hex.length }
    }
}

/** نتيجة بصمة ملف واحد */
data class HashResult(
    val file: File,
    val size: Long,
    /** البصمة السداسية الصغيرة، أو null عند الفشل (صلاحية القراءة مثلاً) */
    val hex: String?,
)

/**
 * محرك البصمات: تجزئة دفعية بمخزن ثابت، تقدم، وإلغاء تعاوني.
 * كل الدوال خالصة فوق java.io — قابلة للاختبار على أقراص مؤقتة 100%.
 */
class ChecksumEngine(
    /** حجم المخزن الثابت (64KB افتراضياً — ذاكرة ثابتة مهما بلغ حجم الملف) */
    private val bufferSize: Int = 64 * 1024,
) {

    /**
     * تجزئة دفعية لقائمة ملفات بخوارزمية واحدة.
     * @param onProgress يُنادى بعد كل ملف: (الملفات المنتهية، البايتات الكلية المقروءة)
     * @param isCancelled يُفحص بين الملفات (إلغاء تعاوني — لا يقطع ملفاً منتصف القراءة)
     * @return نتائج بنفس ترتيب المدخلات؛ null لكل ملف فاشل القراءة
     */
    suspend fun hashFiles(
        files: List<File>,
        algorithm: ChecksumAlgorithm,
        onProgress: (Int, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): List<HashResult?> {
        val results = ArrayList<HashResult?>(files.size)
        var bytesTotal = 0L
        var done = 0
        for (f in files) {
            currentCoroutineContext().ensureActive()
            if (isCancelled()) break
            val r = hashFile(f, algorithm)
            results.add(r)
            if (r != null) bytesTotal += r.size
            done++
            onProgress(done, bytesTotal)
        }
        return results
    }

    /** بصمة ملف واحد بتدفق 64KB — null إن تعذر القراءة */
    fun hashFile(file: File, algorithm: ChecksumAlgorithm): HashResult? {
        if (!file.isFile) return null
        val md = MessageDigest.getInstance(algorithm.jcaName)
        val buf = ByteArray(bufferSize)
        try {
            FileInputStream(file).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) md.update(buf, 0, n)
                }
            }
        } catch (_: Exception) {
            return null
        }
        return HashResult(file, file.length(), md.digest().toHex())
    }

    /**
     * جمع الملفات من مجلد حتمي الترتيب (فرز بالاسم الصغير) —
     * المستوى الأول فقط أو الشجرة كاملة حسب الطلب. الملفات المخفية مستثناة.
     */
    fun collectFiles(root: File, recursive: Boolean): List<File> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<File>()
        if (recursive) {
            val stack = ArrayDeque<File>()
            stack.add(root)
            while (stack.isNotEmpty()) {
                val dir = stack.removeLastOrNull() ?: break
                val children = dir.listFiles() ?: continue
                for (f in children.sortedBy { it.name.lowercase() }) {
                    if (f.isHidden) continue
                    if (f.isDirectory) stack.add(f) else if (f.isFile) out.add(f)
                }
            }
        } else {
            val children = root.listFiles() ?: return emptyList()
            for (f in children.sortedBy { it.name.lowercase() }) {
                if (f.isFile && !f.isHidden) out.add(f)
            }
        }
        return out
    }

    companion object {
        /** تحويل مصفوفة بايتات إلى سداسي صغير */
        fun ByteArray.toHex(): String {
            val sb = StringBuilder(size * 2)
            for (b in this) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        private const val HEX = "0123456789abcdef"
    }
}
