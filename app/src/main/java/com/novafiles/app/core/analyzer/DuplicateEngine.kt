package com.novafiles.app.core.analyzer

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * v0.14.0 — كاشف الملفات المكررة (pure Kotlin)
 *
 * الأنبوب المُعلن في خارطة الطريق (محافظ على الأداء والبطارية):
 *  1) تجميع أولي بالحجم فقط — يقتل 99% من الملفات الفريدة بلا قراءة
 *  2) هاش جزئي (أول 4KB + آخر 4KB) للمجموعات المتساوية الحجم
 *  3) MD5 كامل متدفق لما ينجو من الترشيح — تأكيد نهائي
 *  4) (اختياري) dHash إدراكي للصور المتشابهة (near-duplicates) بمسافة Hamming
 *
 * لا حذف هنا أبداً — المحرك يبلّغ فقط والحذف بعنوان واضح من UI بتأكيد صريح.
 */

/** عنصر مكرر داخل مجموعة */
data class DupItem(
    val path: String,
    val size: Long,
    val hash: String,
)

/** مجموعة تكرار مؤكدة (نفس المحتوى) أو متشابهة إدراكياً */
data class DuplicateGroup(
    val items: List<DupItem>,
    val exact: Boolean,
) {
    /** الحجم القابل للاستعادة = حجم الملف × (العدد − 1) */
    val wastedBytes: Long get() = if (items.isEmpty()) 0L else items.first().size * (items.size - 1)
    val representative: DupItem get() = items.first()
}

/** تقرير الفحص */
data class DuplicateReport(
    val groups: List<DuplicateGroup>,
    val scannedFiles: Int,
    val hashedFiles: Int,
) {
    val totalWastedBytes: Long get() = groups.sumOf { it.wastedBytes }
}

/** مزيّج فك ترميز الصور إلى بكسلات رمادية 9×8 (يُنفَّذ بـ BitmapFactory في أندرويد) */
fun interface ImageDecoder {
    /** يعيد مصفوفة 72 قيمة رمادية (0..255) بترتيب صفوف، أو null عند فشل الفك */
    fun decodeGrayscale(file: File): IntArray?
}

/** إعدادات الفحص */
data class DuplicateOptions(
    val minSizeBytes: Long = 1L,
    val nearDuplicateImages: Boolean = false,
    val hammingThreshold: Int = 8, // من 64 بت — ≤ 8 فرق يعتبر تشابهاً قوياً
)

class DuplicateEngine(
    private val decoder: ImageDecoder? = null,
) {

    /**
     * فحص قائمة مسارات ملفات (من لقطة الفهرس عادة).
     * @param onProgress عدد الملفات المُهاشَمة حتى الآن (لشريط تقدم UI)
     */
    suspend fun scan(
        paths: List<String>,
        options: DuplicateOptions = DuplicateOptions(),
        onProgress: (Int) -> Unit = {},
    ): DuplicateReport {
        val files = ArrayList<File>(paths.size)
        for (p in paths) {
            val f = File(p)
            if (f.isFile && f.length() >= options.minSizeBytes) files.add(f)
        }

        // ─── المرحلة 1: تجميع بالحجم ───
        val bySize = HashMap<Long, MutableList<File>>()
        for (f in files) bySize.getOrPut(f.length()) { ArrayList() }.add(f)
        val sizeGroups = bySize.values.filter { it.size > 1 }

        // ─── المرحلة 2: هاش جزئي (رأس + ذيل) داخل كل مجموعة حجم ───
        var hashed = 0
        val partialSurvivors = ArrayList<List<File>>()
        for (g in sizeGroups) {
            val partialMap = HashMap<String, MutableList<File>>()
            for (f in g) {
                currentCoroutineContext().ensureActive()
                val key = partialKey(f, f.length())
                partialMap.getOrPut(key) { ArrayList() }.add(f)
                hashed++
                if (hashed % 64 == 0) onProgress(hashed)
            }
            for (list in partialMap.values) if (list.size > 1) partialSurvivors.add(list)
        }

        // ─── المرحلة 3: MD5 كامل — هوية نهائية = الحجم:الهاش ───
        val byFull = HashMap<String, MutableList<File>>()
        for (list in partialSurvivors) {
            for (f in list) {
                currentCoroutineContext().ensureActive()
                val md = fullMd5(f)
                byFull.getOrPut("${f.length()}:$md") { ArrayList() }.add(f)
                hashed++
            }
        }
        val exactGroups = byFull.entries
            .filter { it.value.size > 1 }
            .map { (key, files) ->
                val md = key.substringAfter(':')
                DuplicateGroup(
                    items = files.map { DupItem(it.absolutePath, it.length(), md) }
                        .sortedBy { it.path },
                    exact = true,
                )
            }

        // ─── المرحلة 4: تشابه إدراكي للصور (اختياري) ───
        val nearGroups = if (options.nearDuplicateImages && decoder != null) {
            val images = files.filter { isImageName(it.name) }
            val hashes = ArrayList<Pair<File, Long>>(images.size)
            for (f in images) {
                currentCoroutineContext().ensureActive()
                val gray = decoder.decodeGrayscale(f) ?: continue
                hashes.add(f to dHash(gray))
            }
            // عنقدة بسيطة: فرز بالهاش ثم تجميع المسافات القريبة (union-find مصغّر)
            val parent = IntArray(hashes.size) { it }
            fun find(i: Int): Int = if (parent[i] == i) i else find(parent[i]).also { parent[i] = it }
            for (i in hashes.indices) {
                for (j in i + 1 until hashes.size) {
                    if (hamming(hashes[i].second, hashes[j].second) <= options.hammingThreshold) {
                        parent[find(i)] = find(j)
                    }
                }
            }
            val clusters = HashMap<Int, MutableList<Pair<File, Long>>>()
            for (i in hashes.indices) clusters.getOrPut(find(i)) { ArrayList() }.add(hashes[i])
            clusters.values
                .filter { it.size > 1 }
                .map { list ->
                    DuplicateGroup(
                        items = list.map { DupItem(it.first.absolutePath, it.first.length(), "dhash:%016x".format(it.second)) }
                            .sortedBy { it.path },
                        exact = false,
                    )
                }
        } else emptyList()

        onProgress(hashed)
        return DuplicateReport(
            groups = (exactGroups + nearGroups).sortedByDescending { it.wastedBytes },
            scannedFiles = files.size,
            hashedFiles = hashed,
        )
    }

    companion object {
        const val PARTIAL_CHUNK = 4096

        /** مفتاح جزئي: MD5 لأول 4KB + آخر 4KB */
        fun partialKey(f: File, size: Long): String {
            val md = MessageDigest.getInstance("MD5")
            f.inputStream().use { ins ->
                feed(ins, md, PARTIAL_CHUNK)
                if (size > PARTIAL_CHUNK * 2) {
                    ins.skip(size - PARTIAL_CHUNK)
                    feed(ins, md, PARTIAL_CHUNK)
                } else if (size > PARTIAL_CHUNK) {
                    feed(ins, md, PARTIAL_CHUNK) // المتبقي كله (أقل من 4KB)
                }
            }
            return hex(md.digest())
        }

        /** MD5 كامل متدفق بذاكرة ثابتة */
        fun fullMd5(f: File): String {
            val md = MessageDigest.getInstance("MD5")
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return hex(md.digest())
        }

        private fun feed(ins: InputStream, md: MessageDigest, max: Int) {
            val buf = ByteArray(8192)
            var left = max
            while (left > 0) {
                val want = minOf(left, buf.size)
                val n = ins.read(buf, 0, want)
                if (n < 0) break
                md.update(buf, 0, n)
                left -= n
            }
        }

        fun hex(bytes: ByteArray): String {
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append("0123456789abcdef"[v ushr 4])
                sb.append("0123456789abcdef"[v and 0x0F])
            }
            return sb.toString()
        }

        fun isImageName(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
        }

        // ═════════ dHash الإدراكي ═════════

        /**
         * بصمة dHash 64-بت من بكسلات رمادية 9×8 (72 قيمة):
         * قارن كل بكسل بجاره الأيمن في الصف → 8×8 = 64 بت.
         * هذا التطبيق pure-Kotlin يعمل على JVM للاختبار، وفك الصورة نفسها
         * يُجرى في مزيّج ImageDecoder (BitmapFactory على أندرويد).
         */
        fun dHash(gray9x8: IntArray): Long {
            require(gray9x8.size == 72) { "توقع 72 بكسل 9×8، وجد ${gray9x8.size}" }
            var hash = 0L
            var bit = 0
            for (row in 0 until 8) {
                for (col in 0 until 8) {
                    val left = gray9x8[row * 9 + col]
                    val right = gray9x8[row * 9 + col + 1]
                    if (left > right) hash = hash or (1L shl bit)
                    bit++
                }
            }
            return hash
        }

        /** مسافة Hamming بين بصمتين */
        fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
    }
}
