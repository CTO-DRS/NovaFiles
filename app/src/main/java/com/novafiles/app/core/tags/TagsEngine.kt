package com.novafiles.app.core.tags

/**
 * ══════════════════════════════════════════════════════════
 *  TagsEngine — v0.33 «الوسوم والرفوف الذكية»
 *  منطق الوسوم الخالص: إنشاء/تسمية/تلوين/إسناد/فلترة.
 *  ─ عزل آمن للأسماء العربية والإيموجي: لا حذف إلا لمحارف
 *    التحكم وعلامات الاتجاه غير المرئية — ZWJ في الإيموجي
 *    المركّبة يُحفظ كما هو، والاقتطاع عند حدود نقاط الترميز.
 *  ─ كل العمليات على حالة غير قابلة للتغيير [TagsState]
 *    حتمية وقابلة للاختبار JVM بالكامل.
 * ══════════════════════════════════════════════════════════
 */
object TagsEngine {

    /** أقصى طول لاسم الوسم (بنقاط الترميز لا الأحرف UTF-16) */
    const val MAX_NAME_CODE_POINTS = 24

    /** أخطاء العمليات */
    enum class TagError { EMPTY, DUPLICATE, NOT_FOUND, BAD_COLOR }

    /** تعريف وسم */
    data class TagDef(
        val id: Long,
        val name: String,
        val colorIndex: Int,
        val createdAt: Long,
    )

    /** حالة الوسوم الكاملة (غير قابلة للتغيير) */
    data class TagsState(
        val defs: List<TagDef> = emptyList(),
        /** tagId ← مجموعة مسارات موسومة */
        val links: Map<Long, Set<String>> = emptyMap(),
    )

    /** نتيجة عملية تعديلية: الحالة الجديدة + التعريف المتأثر + خطأ إن وُجد */
    data class TagMutation(
        val state: TagsState,
        val def: TagDef? = null,
        val error: TagError? = null,
    ) {
        val ok: Boolean get() = error == null
    }

    /** وضع الفلترة بوسوم متعددة */
    enum class FilterMode { ANY, ALL }

    // ─────────── التطبيع (عزل آمن للعربية والإيموجي) ───────────

    /** محارف تحكم واتجاه غير مرئية تُحذف (لا نلمس ZWJ/ZWNJ — جزء من الإيموجي والفارسية) */
    private val INVISIBLE = Regex("[\\u0000-\\u001F\\u007F\\u200B\\u200E\\u200F\\uFEFF]")

    /** فراغات متتابعة → مسافة واحدة */
    private val WHITESPACE = Regex("\\s+")

    /**
     * تطبيع اسم الوسم: حذف محارف التحكم/الاتجاه، تقليص الفراغات،
     * اقتطاع عند [MAX_NAME_CODE_POINTS] نقطة ترميز.
     * يعيد null إن كان فارغاً بعد التطبيع.
     */
    fun normalize(raw: String): String? {
        val cleaned = raw.replace(INVISIBLE, "").trim()
        val collapsed = cleaned.replace(WHITESPACE, " ")
        if (collapsed.isEmpty()) return null
        val cpCount = collapsed.codePointCount(0, collapsed.length)
        if (cpCount <= MAX_NAME_CODE_POINTS) return collapsed
        val end = collapsed.offsetByCodePoints(0, MAX_NAME_CODE_POINTS)
        return collapsed.substring(0, end)
    }

    /**
     * مفتاح التفرد: الاسم المطبع + lowercase (للاتينية؛ العربية والإيموجي
     * بلا حالة فيمنع «Work» و«work» مكررين دون الإساءة للعربية).
     */
    fun canonical(name: String): String = normalize(name)?.lowercase() ?: ""

    /** لون افتراضي حتمي من الاسم */
    fun defaultColor(name: String): Int = TagPalette.colorFor(canonical(name))

    /** ترتيب العرض: اسمياً حتمياً */
    fun sortedDefs(state: TagsState): List<TagDef> =
        state.defs.sortedWith(compareBy({ canonical(it.name) }, { it.id }))

    // ─────────── العمليات ───────────

    /** إنشاء وسم جديد — اللون تلقائي إن لم يُحدد */
    fun create(
        state: TagsState,
        name: String,
        colorIndex: Int? = null,
        now: Long,
    ): TagMutation {
        val normalized = normalize(name) ?: return TagMutation(state, error = TagError.EMPTY)
        val key = normalized.lowercase()
        if (state.defs.any { canonical(it.name) == key }) {
            return TagMutation(state, error = TagError.DUPLICATE)
        }
        val color = when {
            colorIndex == null -> defaultColor(normalized)
            colorIndex in 0 until TagPalette.COUNT -> colorIndex
            else -> return TagMutation(state, error = TagError.BAD_COLOR)
        }
        val nextId = (state.defs.maxOfOrNull { it.id } ?: 0L) + 1L
        val def = TagDef(nextId, normalized, color, now)
        val newState = state.copy(defs = state.defs + def)
        return TagMutation(newState, def = def)
    }

    /** إعادة تسمية وسم قائم */
    fun rename(state: TagsState, id: Long, newName: String): TagMutation {
        val target = state.defs.firstOrNull { it.id == id }
            ?: return TagMutation(state, error = TagError.NOT_FOUND)
        val normalized = normalize(newName) ?: return TagMutation(state, error = TagError.EMPTY)
        val key = normalized.lowercase()
        if (state.defs.any { it.id != id && canonical(it.name) == key }) {
            return TagMutation(state, error = TagError.DUPLICATE)
        }
        val updated = target.copy(name = normalized)
        val newState = state.copy(defs = state.defs.map { if (it.id == id) updated else it })
        return TagMutation(newState, def = updated)
    }

    /** تغيير لون وسم */
    fun setColor(state: TagsState, id: Long, colorIndex: Int): TagMutation {
        val target = state.defs.firstOrNull { it.id == id }
            ?: return TagMutation(state, error = TagError.NOT_FOUND)
        if (colorIndex !in 0 until TagPalette.COUNT) {
            return TagMutation(state, error = TagError.BAD_COLOR)
        }
        val updated = target.copy(colorIndex = colorIndex)
        val newState = state.copy(defs = state.defs.map { if (it.id == id) updated else it })
        return TagMutation(newState, def = updated)
    }

    /** حذف وسم — تُحذف إسناداته تلقائياً */
    fun remove(state: TagsState, id: Long): TagMutation {
        if (state.defs.none { it.id == id }) return TagMutation(state, error = TagError.NOT_FOUND)
        val newState = state.copy(
            defs = state.defs.filter { it.id != id },
            links = state.links - id,
        )
        return TagMutation(newState)
    }

    /** وسم مسارات — الإسناد عمية (تكرار نفس المسار بلا أثر) */
    fun assign(state: TagsState, tagId: Long, paths: Collection<String>): TagMutation {
        if (state.defs.none { it.id == tagId }) return TagMutation(state, error = TagError.NOT_FOUND)
        val valid = paths.filter { it.isNotBlank() }.toSet()
        if (valid.isEmpty()) return TagMutation(state)
        val current = state.links[tagId] ?: emptySet()
        val newState = state.copy(links = state.links + (tagId to (current + valid)))
        return TagMutation(newState)
    }

    /** إزالة وسم واحد عن مسار */
    fun unassign(state: TagsState, tagId: Long, path: String): TagMutation {
        if (state.defs.none { it.id == tagId }) return TagMutation(state, error = TagError.NOT_FOUND)
        val current = state.links[tagId] ?: return TagMutation(state)
        val updated = current - path
        val links = if (updated.isEmpty()) state.links - tagId else state.links + (tagId to updated)
        return TagMutation(state.copy(links = links))
    }

    /** ملف حُذف من القرص — إزالة المسار من كل الوسوم دفعة واحدة */
    fun untagPath(state: TagsState, path: String): TagMutation {
        val links = state.links.mapValues { (_, paths) -> paths - path }
            .filterValues { it.isNotEmpty() }
        return TagMutation(state.copy(links = links))
    }

    // ─────────── قراءات ───────────

    /** مسارات وسم مرتبة أبجدياً */
    fun tagPaths(state: TagsState, tagId: Long): List<String> =
        (state.links[tagId] ?: emptySet()).sorted()

    /** وسوم مسار معيّن مرتبة اسمياً */
    fun tagsForPath(state: TagsState, path: String): List<TagDef> =
        sortedDefs(state).filter { state.links[it.id]?.contains(path) == true }

    /** عدد مسارات كل وسم */
    fun counts(state: TagsState): Map<Long, Int> =
        state.defs.associate { it.id to (state.links[it.id]?.size ?: 0) }

    /**
     * فلترة قائمة مسارات بوسوم.
     * ANY: المسار يحمل أياً من الوسوم · ALL: يحملها كلها.
     * قائمة وسوم فارغة تعيد المدخلات كما هي (بلا فلترة).
     */
    fun filterPaths(
        state: TagsState,
        paths: Collection<String>,
        tagIds: Collection<Long>,
        mode: FilterMode = FilterMode.ANY,
    ): List<String> {
        if (tagIds.isEmpty()) return paths.toList()
        val sets = tagIds.map { state.links[it] ?: emptySet() }
        return paths.filter { path ->
            when (mode) {
                FilterMode.ANY -> sets.any { path in it }
                FilterMode.ALL -> sets.all { path in it }
            }
        }
    }
}
