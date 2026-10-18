package com.novafiles.app.core.fs

import java.io.File

/**
 * ════════════════════════════════════════════════════════════════
 *  شجرة المجلدات الجانبية — منطق خالص بلا Android
 *  ─ بناء قائمة مسطّحة من شجرة مجلدات قابلة للفتح/الطي
 *  ─ الجذور قائمة (تخزين داخلي، بطاقة SD…) وتحتها فروع المجلدات
 *  ─ المسار الحالي مميَّز وكل أسلافه يفتحون تلقائياً
 * ════════════════════════════════════════════════════════════════
 */

/** عقدة جاهزة للعرض في قائمة مسطّحة */
data class TreeNode(
    val name: String,
    val path: String,
    /** عمق التداخل: 0 للجذور */
    val depth: Int,
    val isExpanded: Boolean,
    /** هو المسار المعروض حالياً في اللوحة */
    val isCurrent: Boolean,
    /** أحد أسلاف المسار الحالي (سلسلة الوصول إليه) */
    val isAncestor: Boolean,
    /** له أبناء ظاهرون حالياً (مفتوح فعلاً) */
    val hasVisibleChildren: Boolean,
)

object DirTree {

    /**
     * المجموعة الافتراضية للفتح عند أول عرض: كل أسلاف المسار الحالي + المسار نفسه.
     * مثال: ‎/storage/emulated/0/DCIM/Camera ← ثلاثة أسلاف + المسار.
     */
    fun defaultExpanded(current: String): Set<String> {
        val out = LinkedHashSet<String>()
        var f = File(current)
        // صعود حتى جذر التخزين (نستبعد /storage و /storage/emulated كجذور معروضة)
        while (true) {
            val p = f.absolutePath
            if (p == "/storage" || p == "/storage/emulated" || p == "/") break
            out.add(p)
            val parent = f.parentFile ?: break
            f = parent
        }
        return out
    }

    /**
     * بناء القائمة المسطّحة للعرض:
     * ─ roots: عقد الجذر (تخزين داخلي…) بعمق 0
     * ─ expanded: مسارات المجلدات المفتوحة
     * ─ listDirs: مزوّد أبناء المجلد (فقط المجلدات) — قابل للاستبدال في الاختبار
     * الترتيب: الجذور بترتيبها ثم الأبناء أبجدياً (حساس حالة الأحرف الطبيعي).
     */
    fun build(
        roots: List<File>,
        current: String,
        expanded: Set<String>,
        listDirs: (File) -> List<File> = { dir ->
            dir.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                ?: emptyList()
        },
    ): List<TreeNode> {
        val out = mutableListOf<TreeNode>()
        val cur = File(current).absolutePath.trimEnd('/')
        fun isAncestorOfCurrent(path: String): Boolean =
            cur != path && cur.startsWith(path.trimEnd('/') + "/")

        fun walk(dir: File, depth: Int) {
            val path = dir.absolutePath.trimEnd('/')
            val open = expanded.contains(path)
            val children = if (open) listDirs(dir) else emptyList()
            out.add(
                TreeNode(
                    name = dir.name.ifEmpty { dir.absolutePath },
                    path = path,
                    depth = depth,
                    isExpanded = open,
                    isCurrent = path == cur,
                    isAncestor = isAncestorOfCurrent(path),
                    hasVisibleChildren = children.isNotEmpty(),
                ),
            )
            for (c in children) walk(c, depth + 1)
        }

        for (r in roots) walk(r, 0)
        return out
    }
}
