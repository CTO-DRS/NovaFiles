package com.novafiles.app.data.prefs

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        val FTP_PORT = intPreferencesKey("ftp_port")
        val FTP_USER = stringPreferencesKey("ftp_user")
        val FTP_PASS = stringPreferencesKey("ftp_pass")

        // v0.17 — الثيمات والإيماءات والارتباطات
        val PALETTE = stringPreferencesKey("palette_id")
        val FOLDER_ACCENTS = stringPreferencesKey("folder_accents")
        val ICON_PACK = stringPreferencesKey("icon_pack_manifest")
        val GESTURES = stringPreferencesKey("gesture_config")
        val DOUBLE_TAP_NAV = booleanPreferencesKey("double_tap_navigate")
        val OPEN_WITH_DEFAULTS = stringPreferencesKey("open_with_defaults")
    }

    val viewGrid: Flow<Boolean> = context.dataStore.data.map { it[Keys.VIEW_GRID] ?: false }
    val showHidden: Flow<Boolean> = context.dataStore.data.map { it[Keys.SHOW_HIDDEN] ?: false }
    val sortMode: Flow<String> = context.dataStore.data.map { it[Keys.SORT] ?: "name" }
    val themeMode: Flow<String> = context.dataStore.data.map { it[Keys.THEME] ?: ThemeMode.SYSTEM.name }

    // إعدادات المشاركة عبر WiFi (خادم FTP)
    val ftpPort: Flow<Int> = context.dataStore.data.map { it[Keys.FTP_PORT] ?: 2121 }
    val ftpUser: Flow<String> = context.dataStore.data.map { it[Keys.FTP_USER] ?: "nova" }
    val ftpPass: Flow<String> = context.dataStore.data.map { it[Keys.FTP_PASS] ?: "nova" }

    suspend fun setViewGrid(grid: Boolean) = context.dataStore.edit { it[Keys.VIEW_GRID] = grid }
    suspend fun setShowHidden(hidden: Boolean) = context.dataStore.edit { it[Keys.SHOW_HIDDEN] = hidden }
    suspend fun setSortMode(mode: String) = context.dataStore.edit { it[Keys.SORT] = mode }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setFtpPort(port: Int) = context.dataStore.edit { it[Keys.FTP_PORT] = port }
    suspend fun setFtpUser(user: String) = context.dataStore.edit { it[Keys.FTP_USER] = user }
    suspend fun setFtpPass(pass: String) = context.dataStore.edit { it[Keys.FTP_PASS] = pass }

    // v0.17 — الثيمات والإيماءات والارتباطات
    val paletteId: Flow<String> = context.dataStore.data.map { it[Keys.PALETTE] ?: "nova" }
    val folderAccents: Flow<String> = context.dataStore.data.map { it[Keys.FOLDER_ACCENTS] ?: "" }
    val iconPack: Flow<String> = context.dataStore.data.map { it[Keys.ICON_PACK] ?: "" }
    val gestures: Flow<String> = context.dataStore.data.map { it[Keys.GESTURES] ?: "" }
    val doubleTapNavigate: Flow<Boolean> = context.dataStore.data.map { it[Keys.DOUBLE_TAP_NAV] ?: false }
    val openWithDefaults: Flow<String> = context.dataStore.data.map { it[Keys.OPEN_WITH_DEFAULTS] ?: "" }

    suspend fun setPaletteId(id: String) = context.dataStore.edit { it[Keys.PALETTE] = id }
    suspend fun setFolderAccents(raw: String) = context.dataStore.edit { it[Keys.FOLDER_ACCENTS] = raw }
    suspend fun setIconPack(raw: String) = context.dataStore.edit { it[Keys.ICON_PACK] = raw }
    suspend fun setGestures(raw: String) = context.dataStore.edit { it[Keys.GESTURES] = raw }
    suspend fun setDoubleTapNavigate(on: Boolean) = context.dataStore.edit { it[Keys.DOUBLE_TAP_NAV] = on }
    suspend fun setOpenWithDefaults(raw: String) = context.dataStore.edit { it[Keys.OPEN_WITH_DEFAULTS] = raw }

    /** وصول خام للتخزين (تستخدمه الخزنة لحفظ ملح وتجزئة PIN) */
    suspend fun peekRaw(): Preferences = context.dataStore.data.first()

    suspend fun putRaw(key: String, value: String) =
        context.dataStore.edit { it[stringPreferencesKey(key)] = value }
}
