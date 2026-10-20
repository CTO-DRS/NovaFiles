package com.novafiles.app.core.analyzer

import com.novafiles.app.core.checksum.ChecksumEngine
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * v0.14.0 — ماسح المخلفات (pure Kotlin)
 *
 * قواعد الخارطة الطريق: كل فئة تُراجع فردياً ولا يُحذف شيء بصمت —
 * الماسح «يبلّغ فقط» والحذف حصرياً من UI بتأكيد صريح لكل دفعة.
 */

/** فئات المخلفات */
enum class JunkCategory {
    /** أدلة فارغة (بعد الصعود من الأعمق) */
    EMPTY_DIRS,

    /** حزم APK يتيمة: في التنزيلات وأقدم من n يوم (تثبيت غادر) */
    ORPHAN_APKS,

    /** مصغرات قديمة: .thumbnails ومشتقات الأنماط */
    STALE_THUMBS,

    /** بقايا تنزيلات: .nfpart و .nfjournal و .crdownload و .partial و .tmp القديمة */
    LEFTOVER_PARTIALS,

    /** v0.29 — مكررات المحتوى: بصمة محتوى لا اسم (النسخ الزائدة فقط — المرجعية محفوظة) */
    CONTENT_DUPLICATES,
}

/** عنصر مخلف مكتشف */
data class JunkItem(
    val path: String,
    val size: Long,
    val category: JunkCategory,
    /** v0.29 — عدد النسخ في مجموعة المحتوى المكرر (×N) — 1 لغير مكررات المحتوى */
    val copies: Int = 1,
)

/** تقرير المخلفات مجمّعاً بالفئة */
data class JunkReport(
    val items: List<JunkItem>,
) {
    fun byCategory(c: JunkCategory): List<JunkItem> = items.filter { it.category == c }

    fun bytes(c: JunkCategory): Long = items.sumOf { if (it.category == c) it.size else 0L }

    val totalBytes: Long get() = items.sumOf { it.size }
}

class JunkScanner(
    /** بوابات خارجية للأنظمة الافتراضية (مثلاً: استثناء ملفات تحت المستندات المرئية) */
    private val skipPaths: Set<String> = emptySet(),
    /** v0.29 — محرك البصمات للمكررات عبر الأنواع (قابل للحقن للاختبار) */
    private val checksumEngine: ChecksumEngine = ChecksumEngine(),
) {

    /**
     * يمسح شجرة مجلد جذر (عادة /storage/emulated/0).
     * @param nowMs الزمن الحالي (قابل للحقن للاختبار)
     * @param minAgeDays الحد الأدنى لعمر الملفات المخلفة (افتراضي 7 أيام) — يحمي عمليات جارية
     */
    suspend fun scan(
        root: File,
        nowMs: Long = System.currentTimeMillis(),
        minAgeDays: Int = 7,
        onProgress: (Int) -> Unit = {},
    ): JunkReport {
        val items = ArrayList<JunkItem>()
        val minAgeMs = minAgeDays * 86_400_000L
        val visited = HashSet<String>()
        var progress = 0
        // v0.29 — مرشحو مكررات المحتوى: كل ملف عادي زارته الشجرة (بلا تصنيف مسبق)
        val dupCandidates = ArrayList<File>()

        // مسح تكراري كامل للشجرة (DFS صريح بلا تكرار عكسي — أمان العمق)
        val stack = ArrayDeque<File>()
        stack.add(root)
        val emptyDirs = ArrayList<File>()

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLastOrNull() ?: break
            if (dir.absolutePath in visited) continue
            visited.add(dir.absolutePath)
            val children = dir.listFiles() ?: continue

            for (f in children) {
                if (f.absolutePath in skipPaths) continue
                if (f.isDirectory) {
                    // مصغرات: أدلة الأنماط المعروفة تُمسح كاملة كنفاية
                    if (isThumbnailDir(f.name)) {
                        addTree(f, JunkCategory.STALE_THUMBS, items)
                        continue
                    }
                    stack.add(f)
                } else {
                    val name = f.name
                    val age = nowMs - f.lastModified()
                    var classified = false
                    when {
                        f.extension.lowercase() == "apk" && age >= minAgeMs -> {
                            items.add(JunkItem(f.absolutePath, f.length(), JunkCategory.ORPHAN_APKS))
                            classified = true
                        }

                        isPartialLeftover(name) && age >= minAgeMs -> {
                            items.add(JunkItem(f.absolutePath, f.length(), JunkCategory.LEFTOVER_PARTIALS))
                            classified = true
                        }

                        isThumbFile(name) && age >= minAgeMs -> {
                            items.add(JunkItem(f.absolutePath, f.length(), JunkCategory.STALE_THUMBS))
                            classified = true
                        }
                    }
                    // v0.29 — غير المصنّف كنفاية يدخل مرشحي مكررات المحتوى
                    if (!classified) dupCandidates.add(f)
                    progress++
                    if (progress % 256 == 0) onProgress(progress)
                }
            }
            // دليل فارغ الآن؟ (listFiles فارغة تماماً — ورقة خالية)
            if (children.isEmpty() && dir != root) {
                emptyDirs.add(dir)
            }
        }

        // الأدلة الفارغة من الأعمق للأضحك: حذف أب فارغ يعني حذف سلسلة —
        // نعرض الجذر الفارغ فقط (حذفه يحذف السلسلة كلها) كي لا نعرض عناصر مضاعفة
        val roots = ArrayList<File>()
        val emptySet = emptyDirs.mapTo(HashSet()) { it.absolutePath }
        for (d in emptyDirs.sortedByDescending { it.absolutePath.count { ch -> ch == '/' } }) {
            val p = d.parentFile?.absolutePath
            if (p in emptySet) continue // أبها فارغ أيضاً وسيُعرض هو
            roots.add(d)
        }
        for (d in roots) items.add(JunkItem(d.absolutePath, 0L, JunkCategory.EMPTY_DIRS))

        // v0.29 — مكررات المحتوى عبر الأنواع: حجم ← هاش جزئي رأس/ذيل 4KB ← MD5 كامل.
        // النفايات المصنّفة أعلاه لا تتضاعف — لا تدخل المرشحين أصلاً.
        findContentDuplicates(dupCandidates, items)

        return JunkReport(items)
    }

    /**
     * كشف مكررات المحتوى: بصمة المحتوى لا الاسم (تعبر الأنواع — نسخة صورة بامتداد مختلف
     * تُكتشف إن كان المحتوى مطابقاً). ثلاث مراحل توفيراً للقراءة:
     * 1) تجميع بالحجم — الوحيد بحجمه لا يُقرأ أبداً
     * 2) هاش جزئي (رأس 4KB + ذيل 4KB) — يُسقط معظم المشابهين بالحجم دون قراءة كاملة
     * 3) MD5 كامل عبر محرك v0.29 — الحسم النهائي
     *
     * الناتج: النسخ الزائدة فقط (المرجعية = أصغر مسار محفوظة)، مع عدّاد ×N،
     * والمجموعات مرتبة بالبايتات المهدورة تنازلياً.
     */
    private suspend fun findContentDuplicates(candidates: List<File>, out: MutableList<JunkItem>) {
        currentCoroutineContext().ensureActive()
        // المرحلة 1 — تجميع بالحجم (الملفات المفردة والمصفرة لا قيمة لها)
        val bySize = HashMap<Long, MutableList<File>>()
        for (f in candidates) {
            if (f.length() <= 0L) continue // فارغة/غير قابلة للقياس — مستثناة
            bySize.getOrPut(f.length()) { ArrayList() }.add(f)
        }
        val sameSize = bySize.values.filter { it.size > 1 }
        if (sameSize.isEmpty()) return

        // المرحلة 2 — هاش جزئي رأس/ذيل 4KB (MD5 على العينة)
        val partialGroups = HashMap<Long, MutableList<File>>() // مفتاح: hash جزئي مدمج بالحجم
        for (group in sameSize) {
            for (f in group) {
                val sample = partialSampleHash(f) ?: continue
                partialGroups.getOrPut(sample * 31L + f.length()) { ArrayList() }.add(f)
            }
        }
        val survivors = partialGroups.values.filter { it.size > 1 }.flatten()
        if (survivors.isEmpty()) return

        // المرحلة 3 — MD5 كامل عبر محرك البصمات (إلغاء تعاوني بين الملفات)
        val full = HashMap<String, MutableList<File>>()
        for (f in survivors) {
            currentCoroutineContext().ensureActive()
            val hex = checksumEngine.hashFile(f, com.novafiles.app.core.checksum.ChecksumAlgorithm.MD5)?.hex ?: continue
            full.getOrPut(hex) { ArrayList() }.add(f)
        }

        // النسخ الزائدة فقط — المرجعية (أصغر مسار) محفوظة، المجموعات بالهدر تنازلياً
        val groups = full.values.filter { it.size > 1 }
            .sortedByDescending { g -> g.first().length() * (g.size - 1) }
        for (g in groups) {
            val size = g.first().length()
            val ordered = g.sortedBy { it.absolutePath }
            for (extra in ordered.drop(1)) {
                out.add(JunkItem(extra.absolutePath, size, JunkCategory.CONTENT_DUPLICATES, copies = g.size))
            }
        }
    }

    /** بصمة عينة رأس/ذيل 4KB — null عند تعذر القراءة */
    private fun partialSampleHash(f: File): Long? {
        return try {
            val md = MessageDigest.getInstance("MD5")
            FileInputStream(f).use { input ->
                val head = ByteArray(SAMPLE_BYTES)
                val nHead = input.read(head)
                if (nHead > 0) md.update(head, 0, nHead)
                if (f.length() > SAMPLE_BYTES) {
                    val tailStart = f.length() - SAMPLE_BYTES
                    if (input.skip(tailStart) == tailStart) {
                        val tail = ByteArray(SAMPLE_BYTES)
                        var nTail = 0
                        while (nTail < SAMPLE_BYTES) {
                            val n = input.read(tail, nTail, SAMPLE_BYTES - nTail)
                            if (n < 0) break
                            nTail += n
                        }
                        if (nTail > 0) md.update(tail, 0, nTail)
                    }
                }
            }
            // طيّ 16 بايت MD5 إلى Long ثابت للمقارنة (يظل مؤهلاً كمرشح فقط — لا حسم)
            val d = md.digest()
            var acc = 0L
            for (b in d) acc = acc * 31L + b
            acc
        } catch (_: Exception) {
            null
        }
    }

    /** يضيف كل الملفات تحت دليل (أياً كان عمقه) كعناصر فئة معينة — للأدلة المعروفة أنها نفاية */
    private fun addTree(dir: File, category: JunkCategory, out: MutableList<JunkItem>) {
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLastOrNull() ?: break
            val children = d.listFiles() ?: continue
            for (f in children) {
                if (f.isDirectory) stack.add(f)
                else out.add(JunkItem(f.absolutePath, f.length(), category))
            }
        }
    }

    companion object {
        /** حجم عينة الهاش الجزئي (رأس 4KB + ذيل 4KB) */
        internal const val SAMPLE_BYTES = 4 * 1024

        /** أنماط أدلة المصغرات الشائعة (أندرويد + تطبيقات الطرف الثالث) */
        private val THUMB_DIRS = setOf(".thumbnails", "thumbnails", ".thumbs", "picsart-thumbs")

        /** أنماط بقايا التنزيلات (بما فيها مخلفات نقل v0.13 الخاصة بنا) */
        private val PARTIAL_SUFFIXES = setOf(
            "nfpart", "nfjournal",          // نقل NovaFiles — استئناف
            "crdownload", "partial", "download", "part", // متصفحات
            "tmp", "temp",                  // مؤقتات عامة
        )

        fun isThumbnailDir(name: String): Boolean = name.lowercase() in THUMB_DIRS

        /** ملفات مصغرات متسربة خارج الأدلة: img_*.thumb، *.thm */
        fun isThumbFile(name: String): Boolean {
            val n = name.lowercase()
            return n.endsWith(".thm") || (n.startsWith("img_") && n.endsWith(".thumb"))
        }

        /** هل الاسم بقايا تنزيل/ملف مؤقت؟ (الامتداد بعد آخر نقطة، والنقطة الأخيرة قد تكون المخفية) */
        fun isPartialLeftover(name: String): Boolean {
            val n = name.lowercase()
            // ملفات مخفية بلا امتداد مرئي: ".nfpart" كاملة؟ لا — نمطنا: name.nfpart
            val ext = n.substringAfterLast('.', "")
            return ext in PARTIAL_SUFFIXES
        }
    }
}
