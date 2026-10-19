package com.novafiles.app.core.analyzer

/**
 * v0.14.0 — محرك تحليل التخزين (pure Kotlin)
 *
 * يعمل على لقطة الفهرس مباشرة (لا مسح ثانٍ للقرص):
 * - تحليل الفئات: عدد وحجم كل فئة من امتداد الاسم
 * - تجميع الشجرة: حجم كل مجلد شجرةً فرعية bottom-up (تكراري أياً كان العمق)
 * - لوحة الملفات الكبيرة والقديمة
 *
 * كل العمليات تكرارية (Stack صريح) لتتحمل 100 ألف صف بلا خطر StackOverflow.
 */
object StorageAnalyzer {

    /** تحليل الفئات من لقطة الفهرس — المجلدات تُحصى ضمن totalDirs لا ضمن الفئات */
    fun analyze(rows: List<SnapshotRow>): StorageReport {
        val counts = LongArray(StorageCategory.entries.size)
        val bytes = LongArray(StorageCategory.entries.size)
        var totalFiles = 0
        var totalDirs = 0
        var used = 0L
        for (r in rows) {
            if (r.isDirectory) {
                totalDirs++
                continue
            }
            val c = Categories.of(r.name).ordinal
            counts[c]++
            bytes[c] += r.size
            totalFiles++
            used += r.size
        }
        val cats = StorageCategory.entries.mapIndexed { i, c ->
            CategorySummary(c, counts[i].toInt(), bytes[i])
        }
        return StorageReport(cats, totalFiles, totalDirs, used)
    }

    /**
     * تجميع شجرة الأدلة من اللقطة: يعيد كل مجلد ظهر في اللقطة (أو استُنتج كأب)
     * مع حجم شجرته الفرعية الكاملة. يُستخدم للـ treemap والتنقل للأسفل.
     *
     * ملاحظات:
     * - صفوف الملفات الناقصة الأب (مسار فاسد) تُهمل بأمان
     * - المجلدات الفارغة تُدرج بحجم 0 كي تظل قابلة للعرض والتنقل
     * - التجميع bottom-up بترتيب العمق تنازلياً: ابن أعمق من أبيه دائماً في المسار،
     *   لذا مسح واحد تنازلي يكفي (بدون حساب عمق صريح ولا تكرار عكسي)
     */
    fun aggregateTree(rows: List<SnapshotRow>): Map<String, DirNode> {
        val directFileBytes = HashMap<String, Long>()
        val fileCounts = HashMap<String, Int>()
        val childrenOf = HashMap<String, MutableList<String>>()
        val names = HashMap<String, String>()

        val allDirs = LinkedHashSet<String>()
        for (r in rows) {
            names[r.path] = r.name
            if (r.isDirectory) {
                allDirs.add(r.path)
                if (r.parent.isNotEmpty()) {
                    allDirs.add(r.parent)
                    childrenOf.getOrPut(r.parent) { ArrayList() }.add(r.path)
                }
            } else if (r.parent.isNotEmpty()) {
                allDirs.add(r.parent)
                fileCounts[r.parent] = (fileCounts[r.parent] ?: 0) + 1
                directFileBytes[r.parent] = (directFileBytes[r.parent] ?: 0L) + r.size
            }
        }
        // إغلاق الأسلاف: أي مجلد معروف يضيف سلسلة أسلافه (فهرس جزئي — الملف موجود وأبوه البعيد لا)
        for (d in allDirs.toList()) {
            var p = parentOf(d)
            while (p != null && p !in allDirs) {
                allDirs.add(p)
                p = parentOf(p)
            }
        }

        // bottom-up: الأعمق أولاً (عدد '/' أكبر = أعمق) — الابن دائماً أعمق من أبيه
        val subtree = HashMap<String, Long>(allDirs.size * 2)
        val childDirCount = HashMap<String, Int>(allDirs.size * 2)
        val ordered = allDirs.sortedByDescending { pathDepth(it) }
        for (d in ordered) {
            var sum = directFileBytes[d] ?: 0L
            var kids = 0
            for (c in childrenOf[d] ?: emptyList()) {
                sum += subtree[c] ?: 0L
                kids++
            }
            subtree[d] = sum
            childDirCount[d] = kids
        }

        return allDirs.associateWith { d ->
            DirNode(
                path = d,
                name = names[d] ?: d.substringAfterLast('/'),
                subtreeBytes = subtree[d] ?: 0L,
                directFileBytes = directFileBytes[d] ?: 0L,
                childDirs = childDirCount[d] ?: 0,
                fileCount = fileCounts[d] ?: 0,
            )
        }
    }

    /**
     * أبناء مجلد معيّن جاهزون للـ treemap: مجلدات فرعية بحجم شجرتها + ملفات مباشرة،
     * مرتبة تنازلياً بالحجم. المجلدات الفارغة تُدرج بحجم 0 في النهاية.
     */
    fun childrenForTreemap(
        dir: String,
        rows: List<SnapshotRow>,
        tree: Map<String, DirNode>,
    ): List<TreemapNode> {
        val out = ArrayList<TreemapNode>()
        val seenDirs = HashSet<String>()
        val seenFiles = HashSet<String>()
        for (r in rows) {
            if (r.parent != dir) continue
            if (r.isDirectory) {
                if (seenDirs.add(r.path)) {
                    val node = tree[r.path]
                    out.add(TreemapNode(r.name, node?.subtreeBytes ?: 0L, r.path, isDir = true))
                }
            } else {
                if (seenFiles.add(r.path)) {
                    out.add(TreemapNode(r.name, r.size, r.path, isDir = false))
                }
            }
        }
        return out.sortedByDescending { it.value }
    }

    /** ملفات كبيرة: أكبر من حد الحجم، مرتبة تنازلياً */
    fun largeFiles(rows: List<SnapshotRow>, minSizeBytes: Long, limit: Int = 50): List<NotableFile> {
        return rows.asSequence()
            .filter { !it.isDirectory && it.size >= minSizeBytes }
            .map { NotableFile(it.path, it.name, it.size, it.modified) }
            .sortedByDescending { it.size }
            .take(limit)
            .toList()
    }

    /** ملفات قديمة: حجم أدنى + آخر تعديل أقدم من n يوم */
    fun oldFiles(rows: List<SnapshotRow>, nowMs: Long, olderThanDays: Int, minSizeBytes: Long = 0L, limit: Int = 50): List<NotableFile> {
        val cutoff = nowMs - olderThanDays * 86_400_000L
        return rows.asSequence()
            .filter { !it.isDirectory && it.size >= minSizeBytes && it.modified <= cutoff }
            .map { NotableFile(it.path, it.name, it.size, it.modified) }
            .sortedBy { it.modified } // الأقدم أولاً
            .take(limit)
            .toList()
    }

    /** أب المسار بفاصل '/' — يعيد null إن لم يوجد (جذر) */
    fun parentOf(path: String): String? {
        val i = path.lastIndexOf('/')
        return when {
            i <= 0 -> null
            i == path.length - 1 -> parentOf(path.dropLast(1))
            else -> path.substring(0, i)
        }
    }

    private fun pathDepth(path: String): Int {
        var n = 0
        for (ch in path) if (ch == '/') n++
        return n
    }
}
