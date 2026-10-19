package com.novafiles.app.core.analyzer

import java.io.File
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
}

/** عنصر مخلف مكتشف */
data class JunkItem(
    val path: String,
    val size: Long,
    val category: JunkCategory,
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
                    when {
                        f.extension.lowercase() == "apk" && age >= minAgeMs ->
                            items.add(JunkItem(f.absolutePath, f.length(), JunkCategory.ORPHAN_APKS))

                        isPartialLeftover(name) && age >= minAgeMs ->
                            items.add(JunkItem(f.absolutePath, f.length(), JunkCategory.LEFTOVER_PARTIALS))

                        isThumbFile(name) && age >= minAgeMs ->
                            items.add(JunkItem(f.absolutePath, f.length(), JunkCategory.STALE_THUMBS))
                    }
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

        return JunkReport(items)
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
