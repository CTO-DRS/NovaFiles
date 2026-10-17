package com.novafiles.app.core.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * محرك البحث الذكي (النسخة الأولى):
 * - فحص تكراري خلفي للتخزين مع دعم الإلغاء الفوري
 * - مرشحات: النوع، الحجم الأدنى (MB)، حدث خلال آخر (يوم)
 * - النسخة القادمة v0.2 ستبدّل الفحص الحيّ بفهرسة Room-FTS فورية
 */
object SearchEngine {

    data class Query(
        val text: String,
        val type: FileType = FileType.OTHER, // OTHER = الكل
        val minSizeBytes: Long = 0L,
        val maxAgeDays: Int? = null,
        val root: File = File("/storage/emulated/0"),
        val limit: Int = 1000,
        val showHidden: Boolean = false,
    )

    /** نتائج تُبَث تدريجياً أثناء الفحص حتى تتوقف القائمة عند الحد الأقصى */
    fun searchStream(query: Query): Flow<SearchState> = flow {
        emit(SearchState.Scanning(0))
        var checked = 0
        val results = ArrayList<File>(64)
        val needle = query.text.trim().lowercase()
        val cutoff = query.maxAgeDays?.let { System.currentTimeMillis() - it * 86_400_000L }

        val stack = ArrayDeque<File>()
        stack.add(query.root)
        while (stack.isNotEmpty()) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val dir = stack.removeFirstOrNull() ?: continue
            val children = dir.listFiles() ?: continue
            for (f in children) {
                checked++
                if (checked % 400 == 0) emit(SearchState.Scanning(results.size))
                if (!query.showHidden && f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    stack.add(f)
                    continue
                }
                val matches =
                    (needle.isEmpty() || f.name.lowercase().contains(needle)) &&
                        FileType.filterGroup(query.type, f) &&
                        f.length() >= query.minSizeBytes &&
                        (cutoff == null || f.lastModified() >= cutoff)
                if (matches) {
                    results.add(f)
                    emit(SearchState.Results(results.toList(), done = false))
                    if (results.size >= query.limit) {
                        emit(SearchState.Results(results.toList(), done = true))
                        return@flow
                    }
                }
            }
        }
        emit(SearchState.Results(results.toList(), done = true))
    }.flowOn(Dispatchers.IO)

    sealed interface SearchState {
        data class Scanning(val found: Int) : SearchState
        data class Results(val files: List<File>, val done: Boolean) : SearchState
    }
}
