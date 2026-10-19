package com.novafiles.app.core.gesture

/**
 * v0.17 — محرك الإيماءات القابل للضبط (سحب أفقي على صفوف القوائم).
 *
 * Kotlin خالص — يُختبر بالكامل على JVM.
 *
 * لكل اتجاه سحب (يمين/يسار) إجراء مرتبط يمكن المستخدم تغييره من الإعدادات.
 * الإجراءات المدمرة (حذف/نقل للسلة) مسموحة لكنها ليست افتراضية أبداً،
 * والإجراء NONE يعطل السحب في ذلك الاتجاه.
 */

/** الإجراءات المتاحة لسحب الصف */
enum class SwipeAction {
    NONE, FAVORITE, DETAILS, SHARE, COPY, MOVE, COMPRESS, RENAME, TRASH, DELETE;

    /** هل الإجراء مدمّر (يستوجب تأكيداً عند التنفيذ)؟ */
    val destructive: Boolean get() = this == TRASH || this == DELETE

    companion object {
        /** الافتراضي الآمن: يمين = تفضيل، يسار = تفاصيل */
        val DEFAULT_RIGHT = FAVORITE
        val DEFAULT_LEFT = DETAILS

        fun safeParse(raw: String?): SwipeAction? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/** إعدادات الإيماءات — قابلة للتسلسل في DataStore */
data class GestureConfig(
    val enabled: Boolean = true,
    val rightAction: SwipeAction = SwipeAction.DEFAULT_RIGHT,
    val leftAction: SwipeAction = SwipeAction.DEFAULT_LEFT,
    /** عتبة إفلات السحب كنسبة من عرض الصف (0..1) */
    val commitRatio: Float = 0.42f,
) {
    init {
        require(commitRatio in 0.2f..0.8f) { "commitRatio خارج النطاق" }
    }

    fun actionFor(direction: SwipeDirection): SwipeAction = when (direction) {
        SwipeDirection.RIGHT -> rightAction
        SwipeDirection.LEFT -> leftAction
    }

    /** تسلسل مضغوط: "enabled=true;right=FAVORITE;left=DETAILS;ratio=0.42" */
    fun serialize(): String = buildString {
        append("enabled="); append(enabled)
        append(";right="); append(rightAction.name)
        append(";left="); append(leftAction.name)
        append(";ratio="); append(commitRatio)
    }

    companion object {
        fun parse(raw: String?): GestureConfig {
            if (raw.isNullOrBlank()) return GestureConfig()
            var enabled = true
            var right = SwipeAction.DEFAULT_RIGHT
            var left = SwipeAction.DEFAULT_LEFT
            var ratio = 0.42f
            raw.split(';').forEach { part ->
                val i = part.indexOf('=')
                if (i <= 0) return@forEach
                val k = part.substring(0, i).trim().lowercase()
                val v = part.substring(i + 1).trim()
                when (k) {
                    "enabled" -> enabled = v.equals("true", ignoreCase = true)
                    "right" -> SwipeAction.safeParse(v)?.let { right = it }
                    "left" -> SwipeAction.safeParse(v)?.let { left = it }
                    "ratio" -> v.toFloatOrNull()?.let { if (it in 0.2f..0.8f) ratio = it }
                }
            }
            return runCatching { GestureConfig(enabled, right, left, ratio) }
                .getOrDefault(GestureConfig())
        }
    }
}

enum class SwipeDirection { RIGHT, LEFT }

/** محوّل حركة السحب إلى إجراء — حالة صرفة قابلة للاختبار */
class SwipeActionResolver(private val config: GestureConfig) {

    /**
     * قرار السحب من المسافة الإجمالية المقطوعة.
     * startPos موضع البداية، endPos موضع الإفلات، rowWidth عرض الصف.
     * السحب لليمين = startPos < endPos.
     */
    fun resolve(startPos: Float, endPos: Float, rowWidth: Float): SwipeDecision {
        if (!config.enabled) return SwipeDecision.None
        if (rowWidth <= 0f) return SwipeDecision.None
        val delta = endPos - startPos
        if (kotlin.math.abs(delta) < MIN_DISTANCE_DP) return SwipeDecision.None
        val direction = if (delta > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
        val action = config.actionFor(direction)
        if (action == SwipeAction.NONE) return SwipeDecision.None
        val ratio = kotlin.math.abs(delta) / rowWidth
        return if (ratio >= config.commitRatio) SwipeDecision.Committed(action, direction, ratio)
        else SwipeDecision.Cancelled(action, direction, ratio)
    }

    companion object {
        /** أقل مسافة قبل اعتبارها سحباً (لتجنب الخلط مع النقر) */
        const val MIN_DISTANCE_DP = 24f
    }
}

sealed class SwipeDecision {
    object None : SwipeDecision()
    data class Committed(val action: SwipeAction, val direction: SwipeDirection, val ratio: Float) : SwipeDecision()
    data class Cancelled(val action: SwipeAction, val direction: SwipeDirection, val ratio: Float) : SwipeDecision()
}
