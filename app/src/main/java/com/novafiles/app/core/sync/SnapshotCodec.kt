package com.novafiles.app.core.sync

/**
 * v0.32.0 — مرمّز لقطة المزامنة (خالص): تسلسل [SyncNode]s إلى نص مضغوط
 * سطر-لكل-عقدة وخوارجه — يخزن أساس ثنائي الاتجاه في عمود sync_pairs.
 *
 * النسق: سطر ترويسة «NVSYNC1» ثم أسطر «t|path|size|mtime» حيث t = d/f
 * والمسار مشفر URL (UTF-8) فلا تصادم مع الفواصل مهما كان الاسم عربياً
 * أو فيه أسطر جديدة أو أنبوب عمودي.
 */
object SnapshotCodec {

    private const val HEADER = "NVSYNC1"

    /** يرمّز لقطة كاملة — ترتيب مستقر (مفروز بالمسار) */
    fun encode(nodes: List<SyncNode>): String {
        val sb = StringBuilder(HEADER)
        for (n in nodes.sortedBy { it.relativePath }) {
            val p = java.net.URLEncoder.encode(n.relativePath, "UTF-8")
            sb.append('\n')
                .append(if (n.isDir) 'd' else 'f').append('|')
                .append(p).append('|')
                .append(if (n.isDir) 0L else n.size).append('|')
                .append(n.lastModified)
        }
        return sb.toString()
    }

    /** يفكّ نص لقطة — السطور الغريبة تُتجاهل بصمت (تسامح إصلاحي) */
    fun decode(text: String?): List<SyncNode> {
        if (text.isNullOrBlank()) return emptyList()
        val lines = text.split('\n')
        if (lines.firstOrNull() != HEADER) return emptyList()
        val out = ArrayList<SyncNode>(lines.size - 1)
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val parts = line.split('|')
            if (parts.size != 4) continue
            val isDir = when (parts[0]) {
                "d" -> true
                "f" -> false
                else -> continue
            }
            val path = runCatching { java.net.URLDecoder.decode(parts[1], "UTF-8") }.getOrNull() ?: continue
            if (path.isEmpty()) continue
            val size = parts[2].toLongOrNull() ?: continue
            val mtime = parts[3].toLongOrNull() ?: continue
            out.add(SyncNode(path, size, mtime, isDir))
        }
        return out
    }
}
