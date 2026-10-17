package com.novafiles.app.ui.browser

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novafiles.app.AppContainer
import com.novafiles.app.core.fs.FileOps
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.core.fs.LocalBrowser
import com.novafiles.app.core.fs.SortMode
import com.novafiles.app.data.db.FavoriteEntity
import com.novafiles.app.data.prefs.ClipboardOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** حالة حوار عامة للواجهة */
sealed interface BrowserDialog {
    data object NewFolder : BrowserDialog
    data class Rename(val file: File) : BrowserDialog
    data class Details(val file: File) : BrowserDialog
    data class ConfirmDelete(val count: Int) : BrowserDialog
    data object None : BrowserDialog
}

/** حالة عملية جارية (نسخ/نقل/حذف) */
data class OpState(
    val running: Boolean = false,
    val message: String? = null,
    val failed: Boolean = false,
)

class BrowserViewModel(
    private val container: AppContainer,
    private val appContext: Context,
) : ViewModel() {

    private val trashDir = File(Environment.getExternalStorageDirectory(), ".NovaFilesTrash")
    val vaultDir = container.vault.vaultDir

    // ----- الحالة -----
    private val _path = MutableStateFlow(Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0")
    val path: StateFlow<String> = _path

    private val _items = MutableStateFlow<List<File>>(emptyList())
    val items: StateFlow<List<File>> = _items

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection

    private val _dialog = MutableStateFlow<BrowserDialog>(BrowserDialog.None)
    val dialog: StateFlow<BrowserDialog> = _dialog

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _op = MutableStateFlow(OpState())
    val op: StateFlow<OpState> = _op

    val viewGrid: StateFlow<Boolean> = container.settings.viewGrid
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val showHidden: StateFlow<Boolean> = container.settings.showHidden
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var currentSort = SortMode.NAME

    val favoritesPaths: StateFlow<Set<String>> = container.db.favoritesDao().observeAll()
        .map { list -> list.map { it.path }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val clipboardOp = container.clipboard

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            container.settings.sortMode.collect { key ->
                currentSort = SortMode.fromKey(key)
                refresh()
            }
        }
    }

    // ----- التصفح -----

    fun load(path: String) {
        _path.value = path
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            val dir = File(path)
            _items.value = if (dir.isDirectory) {
                LocalBrowser.list(dir, showHidden.value, currentSort)
            } else emptyList()
            _loading.value = false
        }
    }

    fun refresh() = load(_path.value)

    fun up(): Boolean {
        val parent = File(_path.value).parentFile ?: return false
        if (parent.absolutePath == "/storage" || parent.absolutePath == "/storage/emulated") return false
        load(parent.absolutePath)
        return true
    }

    // ----- التحديد -----

    fun toggleSelect(file: File) {
        val sel = _selection.value.toMutableSet()
        if (!sel.add(file.absolutePath)) sel.remove(file.absolutePath)
        _selection.value = sel
    }

    fun selectAll() {
        _selection.value = _items.value.map { it.absolutePath }.toSet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    // ----- الحافظة -----

    fun copySelection() {
        clipboardOp.paths = _selection.value.toList()
        clipboardOp.op = ClipboardOp.COPY
        _message.value = "copied:${_selection.value.size}"
        clearSelection()
    }

    fun cutSelection() {
        clipboardOp.paths = _selection.value.toList()
        clipboardOp.op = ClipboardOp.MOVE
        _message.value = "cut:${_selection.value.size}"
        clearSelection()
    }

    fun pasteHere() {
        val op = clipboardOp.op ?: return
        val sources = clipboardOp.paths.map { File(it) }.filter { it.exists() }
        if (sources.isEmpty()) return
        val target = File(_path.value)
        runOp {
            val res = if (op == ClipboardOp.COPY) FileOps.copyAll(sources, target)
            else FileOps.moveAll(sources, target)
            res.getOrThrow()
            if (op == ClipboardOp.MOVE) clipboardOp.clear()
            "paste:ok"
        }
    }

    // ----- العمليات -----

    fun deleteSelection() {
        val sources = _selection.value.map { File(it) }.filter { it.exists() }
        clearSelection()
        runOp {
            FileOps.moveToTrash(sources, trashDir).getOrThrow()
            // تنظيف أي مفضلة داخل مجلدات حُذفت
            sources.filter { it.isDirectory }.forEach {
                container.db.favoritesDao().removeUnder(it.absolutePath)
            }
            "trash:ok"
        }
    }

    fun deleteTrashItem(file: File) = runOp {
        check(file.deleteRecursively()) { "delete failed" }
        "trash:ok"
    }

    fun restoreTrashItem(file: File) {
        val name = file.name.substringAfter('_', file.name)
        val root = Environment.getExternalStorageDirectory()
        runOp {
            val res = FileOps.moveAll(listOf(file), root)
            res.getOrThrow()
            "restore:ok"
        }
    }

    fun rename(file: File, newName: String) = runOp {
        val wasFavorite = favoritesPaths.value.contains(file.absolutePath)
        val renamed = FileOps.rename(file, newName).getOrThrow()
        viewModelScope.launch {
            val dao = container.db.favoritesDao()
            if (wasFavorite) {
                dao.remove(file.absolutePath)
                dao.add(FavoriteEntity(renamed.absolutePath, renamed.name, renamed.isDirectory))
            }
        }
        "rename:ok"
    }

    fun createFolder(name: String) = runOp {
        FileOps.newFolder(File(_path.value), name).getOrThrow()
        "mkdir:ok"
    }

    fun encryptIntoVault(file: File) = runOp {
        container.vault.encryptIntoVault(file).getOrThrow()
        "vault:ok"
    }

    private fun runOp(block: suspend () -> String) {
        viewModelScope.launch {
            _op.value = OpState(running = true)
            withContext(Dispatchers.IO) { runCatching { block() } }
                .onSuccess { _message.value = it }
                .onFailure { _op.value = OpState(running = false, failed = true, message = it.message) }
            if (_op.value.running) _op.value = OpState(running = false)
            refresh()
        }
    }

    // ----- المفضلة -----

    fun toggleFavorite(file: File) {
        viewModelScope.launch {
            val dao = container.db.favoritesDao()
            if (_favoritesContains(file)) dao.remove(file.absolutePath)
            else dao.add(FavoriteEntity(file.absolutePath, file.name, file.isDirectory))
        }
    }

    private var favSnapshot: Set<String> = emptySet()

    private fun _favoritesContains(file: File): Boolean {
        favSnapshot = favoritesPaths.value
        return favSnapshot.contains(file.absolutePath)
    }

    fun isFavorite(file: File): Boolean = favoritesPaths.value.contains(file.absolutePath)

    // ----- حوارات -----

    fun showDialog(d: BrowserDialog) {
        _dialog.value = d
    }

    fun closeDialog() {
        _dialog.value = BrowserDialog.None
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun setViewGrid(grid: Boolean) = viewModelScope.launch { container.settings.setViewGrid(grid) }
    fun setShowHidden(hidden: Boolean) = viewModelScope.launch { container.settings.setShowHidden(hidden) }
    fun setSort(mode: SortMode) = viewModelScope.launch { container.settings.setSortMode(mode.key) }

    fun sortModeNow(): SortMode = currentSort

    /** تصنيف نوع ملف للواجهة */
    fun typeOf(file: File): FileType = FileType.of(file)
}
