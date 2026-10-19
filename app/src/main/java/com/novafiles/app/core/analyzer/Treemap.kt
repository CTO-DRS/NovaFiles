package com.novafiles.app.core.analyzer

/**
 * v0.14.0 — خوارزمية Squarified Treemap (Bruls, Huizing, van Wijk)
 * pure Kotlin — تُختبر بالكامل على JVM وتُرسم لاحقاً في Compose Canvas.
 *
 * المدخلات: عقد مرتبة تنازلياً بالقيمة (ترتيب داخلي مضمون هنا أيضاً).
 * المخرجات: مستطيلات غير متقاطعة تغطي المساحة كاملة، نسب أبعاد مقبولة.
 */

/** عقدة مدخل للرسم: قيمة (بايت) + مسار + اسم */
data class TreemapNode(
    val label: String,
    val value: Long,
    val path: String,
    val isDir: Boolean,
)

/** مستطيل ناتج بالبكسل المنطقي */
data class TreemapRect(
    val node: TreemapNode,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    val area: Float get() = w * h
}

object Treemap {

    private const val EPS = 1e-6f

    /**
     * حساب مستطيلات treemap.
     * @param items عقد ذات قيم موجبة (قيم صفرية تُرسم مستطيلات عرضها padding فقط في النهاية)
     * @param width/height أبعاد اللوحة
     * @param padding فراغ بين المستطيلات (للحدود المرئية)
     */
    fun squarify(
        items: List<TreemapNode>,
        width: Float,
        height: Float,
        padding: Float = 1.5f,
    ): List<TreemapRect> {
        require(width > 0f && height > 0f) { "أبعاد اللوحة يجب أن تكون موجبة" }
        if (items.isEmpty()) return emptyList()

        val sorted = items.sortedByDescending { it.value }
        val total = sorted.sumOf { it.value }
        if (total <= 0L) {
            // كل القيم صفر: قسّم بالتساوي شبكياً (صف واحد)
            val n = sorted.size
            val cw = width / n
            return sorted.mapIndexed { i, node ->
                TreemapRect(node, i * cw + padding, padding, (cw - padding).coerceAtLeast(0f), (height - padding).coerceAtLeast(0f))
            }
        }

        // الفراغ الداخلي: padding من كل جهة
        val innerX = padding
        val innerY = padding
        val innerW = (width - 2 * padding).coerceAtLeast(0f)
        val innerH = (height - 2 * padding).coerceAtLeast(0f)
        if (innerW <= EPS || innerH <= EPS) return emptyList()

        val areaTotal = innerW * innerH
        val scale = areaTotal / total.toFloat()

        // قائمة العمل بقيم مساحية (float)
        val remaining = ArrayDeque<RowItem>()
        for (node in sorted) remaining.add(RowItem(node, node.value * scale))

        val out = ArrayList<TreemapRect>(sorted.size)
        var x = innerX
        var y = innerY
        var w = innerW
        var h = innerH

        while (remaining.isNotEmpty()) {
            val isHorizontal = w >= h // الشريط أعرض من الطول → صف أفقي (نملأ عمودياً)
            val shortSide = if (isHorizontal) h else w
            val row = ArrayList<RowItem>()

            // ابنِ الصف: أضف عناصر ما دامت «أسوأ نسبة أبعاد» تتحسن
            var worst = Float.MAX_VALUE
            var rowArea = 0f
            while (remaining.isNotEmpty()) {
                val candidate = remaining.first()
                val newArea = rowArea + candidate.area
                val newWorst = worstRatio(row, candidate, newArea, shortSide)
                if (row.isEmpty() || newWorst <= worst + EPS) {
                    worst = newWorst
                    rowArea = newArea
                    row.add(remaining.removeFirst())
                } else break
            }

            // ارسم الصف على الشريط: إذا كان المستطيل عريضاً فالصف «عمود رأسي» يمتد بكامل الارتفاع
            // وسماكته أفقية؛ وإلا فهو «صف أفقي» يمتد بكامل العرض وسماكته رأسية
            val thickness = rowArea / shortSide
            var offset = 0f
            for (item in row) {
                val len = if (thickness > EPS) item.area / thickness else 0f
                val rect = if (isHorizontal) {
                    // مستطيل عريض → عمود رأسي: عرضه thickness ويمتد رأسياً len
                    TreemapRect(item.node, x, y + offset, thickness, len)
                } else {
                    // مستطيل طويل → صف أفقي: ارتفاعه thickness ويمتد أفقياً len
                    TreemapRect(item.node, x + offset, y, len, thickness)
                }
                out.add(inset(rect, padding))
                offset += len
            }
            // اقطع المساحة المستهلكة من الجهة القصيرة
            if (isHorizontal) {
                x += thickness
                w -= thickness
            } else {
                y += thickness
                h -= thickness
            }
            if (w <= EPS || h <= EPS) {
                // لا مساحة متبقية — أي عناصر متبقية (نظرياً صفرية القيمة) تُسقط
                break
            }
        }
        return out
    }

    private data class RowItem(val node: TreemapNode, val area: Float)

    private fun worstRatio(row: List<RowItem>, candidate: RowItem, rowArea: Float, shortSide: Float): Float {
        val n = row.size + 1
        val thickness = rowArea / shortSide
        val areas = FloatArray(n)
        for (i in row.indices) areas[i] = row[i].area
        areas[n - 1] = candidate.area
        var worst = 0f
        for (a in areas) {
            val len = if (thickness > EPS) a / thickness else 0f
            val r = ratio(thickness, len)
            if (r > worst) worst = r
        }
        return worst
    }

    private fun ratio(a: Float, b: Float): Float {
        if (a <= EPS || b <= EPS) return Float.MAX_VALUE
        return if (a >= b) a / b else b / a
    }

    /** تقليل المستطيل بنصف padding من كل جهة لترك فراغ مرئي بين الخلايا */
    private fun inset(r: TreemapRect, padding: Float): TreemapRect {
        val half = padding / 2f
        val x = r.x + half
        val y = r.y + half
        val w = (r.w - padding).coerceAtLeast(0f)
        val h = (r.h - padding).coerceAtLeast(0f)
        return TreemapRect(r.node, x, y, w, h)
    }
}
