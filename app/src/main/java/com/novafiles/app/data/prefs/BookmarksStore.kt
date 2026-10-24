package com.novafiles.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novafiles.app.core.fs.Bookmark
import com.novafiles.app.core.fs.BookmarksCodec
import com.novafiles.app.core.fs.BookmarksEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.bookmarksDataStore by preferencesDataStore(name = "nova_bookmarks")

/**
 * v0.36.0 — مخزن الإشارات المرجعية للمسارات في DataStore (نمط RulesRepository).
 * الإشارات منفصلة عن المفضلة: مجلدات العمل اليومية للوصول الفوري من الشجرة الجانبية.
 */
class BookmarksStore(private val context: Context) {

    private object Keys {
        val BOOKMARKS = stringPreferencesKey("bookmarks_json")
    }

    val bookmarks: Flow<List<Bookmark>> =
        context.bookmarksDataStore.data.map { prefs ->
            BookmarksCodec.decode(prefs[Keys.BOOKMARKS].orEmpty())
                .let { BookmarksEngine.sortedForDisplay(it) }
        }

    /** يضيف مساراً — يرفض عند الامتلاء (يعيد false ليعرض المستخدم تنبيه السقف) */
    suspend fun add(path: String, name: String): Boolean {
        val current = BookmarksCodec.decode(peek())
        val next = BookmarksEngine.add(current, path, name, System.currentTimeMillis())
            ?: return false
        save(next)
        return true
    }

    suspend fun remove(path: String) {
        val current = BookmarksCodec.decode(peek())
        save(BookmarksEngine.remove(current, path))
    }

    suspend fun move(path: String, delta: Int) {
        val current = BookmarksCodec.decode(peek())
        save(BookmarksEngine.move(current, path, delta))
    }

    /**
     * v0.39 — استبدال كامل من نسخة مستوردة: إزالة التكرار بالمحافظة
     * على الأول والالتزام بالسقف حتماً (24) — الترتيب كما ورد.
     * يعيد عدد الإشارات المحفوظة فعلاً.
     */
    suspend fun replaceAll(list: List<Bookmark>): Int {
        val kept = list.distinctBy { it.path }.take(24)
        save(kept)
        return kept.size
    }

    private suspend fun save(list: List<Bookmark>) {
        context.bookmarksDataStore.edit { prefs ->
            prefs[Keys.BOOKMARKS] = BookmarksCodec.encode(list)
        }
    }

    private suspend fun peek(): String = runCatching {
        context.bookmarksDataStore.data.first()[Keys.BOOKMARKS].orEmpty()
    }.getOrDefault("")
}
