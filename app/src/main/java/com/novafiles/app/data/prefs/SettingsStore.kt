package com.novafiles.app.data.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "nova_settings")

/** وضع السمة */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** حافظة الملفات العامة: عناصر منسوخة أو مقصوصة بانتظار اللصق (قابلة للمراقبة في Compose) */
class ClipboardState {
    var paths: List<String> by mutableStateOf(emptyList())
    var op: ClipboardOp? by mutableStateOf(null)
    fun clear() {
        paths = emptyList()
        op = null
    }
}

enum class ClipboardOp { COPY, MOVE }

/** إعدادات التطبيق المحفوظة عبر DataStore */
class SettingsStore(private val context: Context) {

    private object Keys {
        val VIEW_GRID = booleanPreferencesKey("view_grid")
        val SORT = stringPreferencesKey("sort")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val THEME = stringPreferencesKey("theme_mode")
    }

    val viewGrid: Flow<Boolean> = context.dataStore.data.map { it[Keys.VIEW_GRID] ?: false }
    val showHidden: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_HIDDEN] ?: false }
    val sortMode: Flow<String> = context.dataStore.data.map { it[Keys.SORT] ?: "name" }
    val themeMode: Flow<String> = context.dataStore.data.map { it[Keys.THEME] ?: ThemeMode.SYSTEM.name }

    suspend fun setViewGrid(grid: Boolean) = context.dataStore.edit { it[Keys.VIEW_GRID] = grid }
    suspend fun setShowHidden(hidden: Boolean) = context.dataStore.edit { it[Keys.SHOW_HIDDEN] = hidden }
    suspend fun setSortMode(mode: String) = context.dataStore.edit { it[Keys.SORT] = mode }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }

    /** وصول خام للتخزين (تستخدمه الخزنة لحفظ ملح وتجزئة PIN) */
    suspend fun peekRaw(): Preferences = context.dataStore.data.first()

    suspend fun putRaw(key: String, value: String) =
        context.dataStore.edit { it[stringPreferencesKey(key)] = value }
}
