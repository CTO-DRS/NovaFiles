package com.novafiles.app.data.tags

import com.novafiles.app.core.tags.TagPalette
import com.novafiles.app.core.tags.TagsEngine
import com.novafiles.app.core.tags.TagsEngine.TagDef
import com.novafiles.app.core.tags.TagsEngine.TagsState
import com.novafiles.app.core.tags.TagsEngine.TagError
import com.novafiles.app.data.db.NovaDb
import com.novafiles.app.data.db.TagDefEntity
import com.novafiles.app.data.db.TagFileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * v0.33 — مستودع الوسوم: طبقة رقيقة فوق Room تفرض قواعد
 * TagsEngine (تطبيع/تفرد/ألوان حتمية) قبل أي كتابة.
 */
class TagsRepository(private val db: NovaDb) {

    // ─────────── ملاحظات حية ───────────

    fun observeDefs(): Flow<List<TagDefEntity>> = db.tagDefsDao().observeAll()

    /** وسوم كل مسار → خريطة جاهزة للعرض في نتائج البحث */
    fun observeTagsByPath(): Flow<Map<String, List<TagDefEntity>>> =
        combine(db.tagDefsDao().observeAll(), db.tagFileDao().observeAllLinks()) { defs, links ->
            val byId = defs.associateBy { it.id }
            val acc = HashMap<String, MutableList<TagDefEntity>>()
            links.forEach { link ->
                byId[link.tagId]?.let { def ->
                    acc.getOrPut(link.path) { mutableListOf() }.add(def)
                }
            }
            acc
        }

    fun observeLinksForPath(path: String): Flow<List<TagFileEntity>> =
        db.tagFileDao().observeForPath(path)

    // ─────────── قراءات لحظية ───────────

    suspend fun defs(): List<TagDefEntity> = db.tagDefsDao().all()

    suspend fun counts(): Map<Long, Int> =
        db.tagFileDao().counts().associate { it.tagId to it.n }

    suspend fun tagsForPath(path: String): List<TagDefEntity> {
        val ids = db.tagFileDao().tagIdsForPath(path).toSet()
        if (ids.isEmpty()) return emptyList()
        return loadDefs().filter { it.id in ids }
    }

    suspend fun pathsForTag(tagId: Long): List<String> =
        db.tagFileDao().pathsForTag(tagId)

    /** حالة خالصة كاملة (تُستخدم للتطبيع قبل عمليات الكتابة) */
    suspend fun state(): TagsState {
        val defs = loadDefs()
        val links = db.tagFileDao().all()
            .groupBy({ it.tagId }, { it.path })
            .mapValues { it.value.toSet() }
        return TagsState(
            defs = defs.map { TagDef(it.id, it.name, it.colorIndex, it.createdAt) },
            links = links,
        )
    }

    // ─────────── كتابات مُدقَّقة ───────────

    /** نتيجة الإنشاء: معرف الوسم الجديد أو الخطأ */
    data class CreateResult(val id: Long? = null, val error: TagError? = null)

    /** إنشاء وسم — يعيد معرّفه الجديد أو خطأ التكرار/الفارغ */
    suspend fun create(name: String, colorIndex: Int? = null): CreateResult {
        val normalized = TagsEngine.normalize(name) ?: return CreateResult(error = TagError.EMPTY)
        val key = normalized.lowercase()
        val clash = db.tagDefsDao().all().any { it.name.lowercase() == key }
        if (clash) return CreateResult(error = TagError.DUPLICATE)
        val color = colorIndex ?: TagPalette.colorFor(key)
        val id = db.tagDefsDao().insert(
            TagDefEntity(name = normalized, colorIndex = color, createdAt = System.currentTimeMillis()),
        )
        return if (id > 0) CreateResult(id = id) else CreateResult(error = TagError.DUPLICATE)
    }

    suspend fun rename(id: Long, newName: String): TagError? {
        val normalized = TagsEngine.normalize(newName) ?: return TagError.EMPTY
        val clash = db.tagDefsDao().all().any {
            it.id != id && it.name.lowercase() == normalized.lowercase()
        }
        if (clash) return TagError.DUPLICATE
        db.tagDefsDao().rename(id, normalized)
        return null
    }

    suspend fun setColor(id: Long, colorIndex: Int): TagError? {
        if (colorIndex !in 0 until TagPalette.COUNT) return TagError.BAD_COLOR
        if (db.tagDefsDao().byId(id) == null) return TagError.NOT_FOUND
        db.tagDefsDao().setColor(id, colorIndex)
        return null
    }

    suspend fun remove(id: Long) {
        db.tagFileDao().untagAll(id)
        db.tagDefsDao().remove(id)
    }

    /** وسم مجموعة مسارات بوسم (ينشئ الوسم إن لم يوجد) */
    suspend fun assign(tagId: Long, paths: Collection<String>) {
        val valid = paths.filter { it.isNotBlank() }.distinct()
        if (valid.isEmpty()) return
        val now = System.currentTimeMillis()
        db.tagFileDao().linkAll(valid.map { TagFileEntity(tagId, it, now) })
    }

    suspend fun unassign(tagId: Long, path: String) = db.tagFileDao().unlink(tagId, path)

    /** ملف حُذف — إزالة المسار من كل الوسوم */
    suspend fun untagPath(path: String) = db.tagFileDao().untagPath(path)

    // ─────────── أدوات ───────────

    private suspend fun loadDefs(): List<TagDefEntity> = db.tagDefsDao().all()
}
