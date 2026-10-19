package com.novafiles.app.core.gesture

/**
 * v0.17 — القرارات الصرفة للسحب والإفلات بين اللوحتين + سياسة النقر المزدوج.
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * قواعد السحب والإفلات (مستوحاة من iPad Files):
 *  - عبر اللوحتين: الإفلات الافتراضي نسخ؛ مع طلب النقل (زر تعديل/وضع النقل) يصبح نقلاً.
 *  - داخل اللوحة نفسها: الإفلات إلى مجلد آخر نقل داخلي.
 *  - إفلات على المسار المصدر نفسه: مرفوض (لا معنى).
 *  - إفلات مجلد داخل ذاته أو داخل أحد أبنائه: مرفوض (حماية الحلقة).
 *  - إفلات خارج أي هدف صالح: لا شيء.
 */

/** معرّف اللوحة في وضع اللوحتين */
enum class PaneId { PRIMARY, SECONDARY }

/** حمولة السحب — مسارات مطلقة من مصدر واحد */
data class DragPayload(
    val paths: List<String>,
    val sourcePane: PaneId,
    /** طلب النقل بدل النسخ (مثل: الاستمرار على زر النقل أثناء الإفلات) */
    val moveRequested: Boolean = false,
) {
    init {
        require(paths.isNotEmpty()) { "حمولة سحب فارغة" }
    }
}

/** هدف الإفلات */
data class DropTarget(
    val dirPath: String,
    val pane: PaneId,
)

enum class DropOp { COPY, MOVE, NONE }

enum class DropDenyReason { NONE, SAME_DIR, SELF_DROP, INTO_SELF, INVALID }

/** قرار الإفلات النهائي */
data class DropDecision(
    val op: DropOp,
    val reason: DropDenyReason,
    /** المسارات المسموح نقلها فعلاً (قد تُستبعد المسارات المحظورة جزئياً) */
    val paths: List<String>,
)

object DragDropResolver {

    /**
     * حل قرار الإفلات.
     * @param sourceDir المجلد المصدر الأصلي للمسارات (لاستبعاد الإفلات على نفس المجلد)
     */
    fun resolve(
        payload: DragPayload,
        target: DropTarget?,
        sourceDir: String,
    ): DropDecision {
        if (target == null) return DropDecision(DropOp.NONE, DropDenyReason.NONE, emptyList())

        val targetN = norm(target.dirPath)
        val sourceN = norm(sourceDir)

        // إفلات على مجلد المصدر نفسه — لا معنى سواء عبر اللوحات أو داخلها
        if (targetN == sourceN) return DropDecision(DropOp.NONE, DropDenyReason.SAME_DIR, emptyList())

        val acceptable = payload.paths.filter { p ->
            val pn = norm(p)
            // إفلات ملف/مجلد على نفسه (المسار الهدف هو الملف نفسه)
            if (pn == targetN) return@filter false
            // إفلات مجلد داخل أحد أبنائه
            if (targetN.startsWith(pn.trimEnd('/') + "/")) return@filter false
            true
        }
        if (acceptable.isEmpty()) return DropDecision(DropOp.NONE, DropDenyReason.INTO_SELF, emptyList())

        val op = when {
            target.pane != payload.sourcePane -> if (payload.moveRequested) DropOp.MOVE else DropOp.COPY
            else -> DropOp.MOVE // داخل اللوحة نفسها: إفلات على مجلد = نقل داخلي
        }
        return DropDecision(op, DropDenyReason.NONE, acceptable)
    }

    private fun norm(p: String): String {
        var s = p.trim()
        while (s.contains("//")) s = s.replace("//", "/")
        if (s.length > 1 && s.endsWith("/")) s = s.trimEnd('/')
        return s
    }
}

/**
 * سياسة النقر المزدوج للتنقل (double-tap-to-navigate).
 *
 * عند تفعيل الوضع: النقرة الأولى تختار الصف فقط، والنقرة الثانية خلال النافذة
 * تنفّذ الدخول. الحالة صرفة وقابلة للاختبار — لا ترتبط بنظام المؤقتات.
 */
class DoubleTapPolicy(
    /** نافذة النقر المزدوج بالمللي ثانية */
    private val windowMs: Long = 300L,
) {
    init {
        require(windowMs in 100L..1000L) { "نافذة النقر المزدوج غير منطقية" }
    }

    private var firstTapAt: Long = -1L
    private var firstTapKey: String? = null

    /**
     * تسجيل نقرة على عنصر ما في الزمن المحدد.
     * @return true إن كانت هذه النقرة تُكمل نقراً مزدوجاً صالحاً
     */
    fun onTap(nowMs: Long, itemKey: String): Boolean {
        val isFirstValid = firstTapAt < 0 ||
            nowMs - firstTapAt > windowMs ||
            firstTapKey != itemKey
        if (isFirstValid) {
            firstTapAt = nowMs
            firstTapKey = itemKey
            return false
        }
        // نقرة ثانية صالحة: نُصفّر الحالة ونقبل
        firstTapAt = -1L
        firstTapKey = null
        return true
    }

    /** إلغاء الحالة (عند تغيير القائمة أو الخروج من الشاشة) */
    fun reset() {
        firstTapAt = -1L
        firstTapKey = null
    }

    /** هل هناك نقرة أولى معلّقة على عنصر معين الآن؟ (لإبراز التحديد) */
    fun pendingKey(): String? = firstTapKey
}
