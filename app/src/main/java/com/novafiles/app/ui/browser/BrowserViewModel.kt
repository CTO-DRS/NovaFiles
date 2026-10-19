package com.novafiles.app.ui.browser

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novafiles.app.AppContainer
import com.novafiles.app.core.archive.ArchiveEngine
import com.novafiles.app.core.archive.ZipArchive
import com.novafiles.app.core.archive.stripArchiveExt
import com.novafiles.app.core.fs.FileOps
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.core.fs.LocalBrowser
import com.novafiles.app.core.fs.SortMode
import com.novafiles.app.core.ops.ConflictPolicy
import com.novafiles.app.core.ops.OpKind
import com.novafiles.app.core.ops.OpService
import com.novafiles.app.core.ops.OpSpec
import com.novafiles.app.core.rename.RenameEngine
import com.novafiles.app.core.rename.RenameItem
import com.novafiles.app.R
import com.novafiles.app.data.db.FavoriteEntity
import com.novafiles.app.data.prefs.ClipboardOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
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
    data object BatchRename : BrowserDialog
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

    /** v0.15: مجلد الخزنة 2.0 — يظهر في متصفح «الملفات المخفية» كأي مجلد */
    val vaultDir: File
        get() = container.vault2.vaultRoot()

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

    // حوار «إرسال عبر QR» السريع — مشترك بين قائمة العنصر وشريط التحديد
    private val _quickQr = MutableStateFlow<QuickQrState?>(null)
    val quickQr: StateFlow<QuickQrState?> = _quickQr.asStateFlow()

    val viewGrid: StateFlow<Boolean> = container.settings.viewGrid
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val showHidden: StateFlow<Boolean> = container.settings.showHidden
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val sortMode: StateFlow<SortMode> = container.settings.sortMode
        .map { SortMode.fromKey(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SortMode.NAME)

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

    /**
     * إرسال التحديد مباشرة إلى مجلد اللوحة الأخرى (وضع اللوحتين).
     * يختصر دورة «نسخ ← التبديل ← لصق» إلى نقرة واحدة.
     */
    fun sendSelectionTo(targetDir: File, move: Boolean) {
        val sources = _selection.value.map { File(it) }.filter { it.exists() }
        clearSelection()
        if (sources.isEmpty()) return
        runOp {
            val res = if (move) FileOps.moveAll(sources, targetDir) else FileOps.copyAll(sources, targetDir)
            res.getOrThrow()
            if (move) {
                clipboardOp.clear()
                "move:ok"
            } else {
                "copied:${sources.size}"
            }
        }
    }

    /**
     * v0.12 — اللصق يذهب الآن إلى قائمة العمليات الخلفية بدل التنفيذ المباشر،
     * فتستطيع متابعة التصفح أثناء نقل مجلدات ضخمة.
     */
    fun pasteHere() {
        val op = clipboardOp.op ?: return
        val sources = clipboardOp.paths.map { File(it) }.filter { it.exists() }
        if (sources.isEmpty()) return
        val kind = if (op == ClipboardOp.COPY) OpKind.COPY else OpKind.MOVE
        if (op == ClipboardOp.MOVE) clipboardOp.clear()
        clearSelection()
        enqueueOps(kind, sources, File(_path.value))
    }

    // ----- العمليات -----

    /**
     * v0.12 — الحذف إلى السلة عبر قائمة العمليات الخلفية.
     * تنظيف المفضلة يحدث فوراً (المسارات معلومة) لأنه رخيص ولا يحتاج انتظار النسخ.
     */
    fun deleteSelection() {
        val sources = _selection.value.map { File(it) }.filter { it.exists() }
        clearSelection()
        if (sources.isEmpty()) return
        viewModelScope.launch {
            sources.filter { it.isDirectory }.forEach {
                container.db.favoritesDao().removeUnder(it.absolutePath)
            }
        }
        enqueueOps(OpKind.TRASH, sources, null)
    }

    fun deleteTrashItem(file: File) = runOp {
        check(file.deleteRecursively()) { "delete failed" }
        "trash:ok"
    }

    fun restoreTrashItem(file: File) {
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
        // v0.15: استيراد مباشر إلى الخزنة 2.0 — تشفير متدفق ثم حذف الأصل
        val repository = container.vault2.openRepository()
        repository.use {
            file.inputStream().use { input ->
                it.importFile("", file.name, input)
            }
        }
        file.delete()
        "vault:ok"
    }

    // ----- الأرشيفات ZIP -----

    /** ضغط العناصر المحددة — v0.12: عبر قائمة العمليات الخلفية */
    fun zipSelection() {
        val sources = _selection.value.map { File(it) }.filter { it.exists() }
        clearSelection()
        if (sources.isEmpty()) return
        val base = if (sources.size == 1) {
            sources[0].nameWithoutExtension.ifEmpty { sources[0].name }
        } else {
            File(_path.value).name.ifEmpty { "Archive" }
        }
        val out = uniqueZipName(File(_path.value), "$base.zip")
        enqueueOps(OpKind.ZIP, sources, out)
    }

    /** استخراج أي أرشيف مدعوم (zip/7z/tar/rar/…) — v0.12: عبر قائمة العمليات */
    fun extractArchive(archive: File) {
        val dest = File(archive.parentFile, stripArchiveExt(archive.name).ifEmpty { "extracted" })
        enqueueOps(OpKind.EXTRACT, listOf(archive), dest)
    }

    /**
     * v0.12 — إعادة تسمية جماعية عبر RenameEngine مع تراجع متاح.
     */
    fun applyBatchRename(plan: List<RenameItem>) = runOp {
        val n = RenameEngine.apply(plan).getOrThrow()
        lastRenamePlan = plan
        "rename_batch:$n"
    }

    fun undoBatchRename() = runOp {
        val plan = lastRenamePlan ?: return@runOp "rename_nothing"
        RenameEngine.undo(plan).getOrThrow()
        lastRenamePlan = null
        "rename_undo:ok"
    }

    private var lastRenamePlan: List<RenameItem>? = null

    // ----- قائمة العمليات الخلفية (v0.12) -----

    val opRecords get() = container.ops.ops

    private fun enqueueOps(kind: OpKind, sources: List<File>, target: File?) {
        val count = sources.size
        val title = appContext.getString(
            when (kind) {
                OpKind.COPY -> R.string.op_title_copy
                OpKind.MOVE -> R.string.op_title_move
                OpKind.TRASH -> R.string.op_title_trash
                OpKind.ZIP -> R.string.op_title_zip
                OpKind.EXTRACT -> R.string.op_title_extract
            },
            count,
        )
        container.ops.enqueue(
            OpSpec(kind = kind, title = title, sources = sources, target = target, policy = ConflictPolicy.RENAME),
        )
        OpService.start(appContext)
        _message.value = "queued:$count"
    }

    fun pauseOp(id: String) = container.ops.pause(id)
    fun resumeOp(id: String) = container.ops.resume(id)
    fun cancelOp(id: String) = container.ops.cancel(id)
    fun clearFinishedOps() = container.ops.clearFinished()

    private fun uniqueZipName(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.removeSuffix(".zip")
        var i = 2
        while (f.exists()) {
            f = File(dir, "$base ($i).zip")
            i++
        }
        return f
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

    /** رسالة مباشرة إلى شريط التنبيهات (تُستخدم من تدفقات خارج runOp) */
    fun notify(msg: String) {
        _message.value = msg
    }

    fun showQuickQr(state: QuickQrState) {
        _quickQr.value = state
    }

    fun consumeQuickQr() {
        _quickQr.value = null
    }

    fun setViewGrid(grid: Boolean) = viewModelScope.launch { container.settings.setViewGrid(grid) }
    fun setShowHidden(hidden: Boolean) = viewModelScope.launch { container.settings.setShowHidden(hidden) }
    fun setSort(mode: SortMode) = viewModelScope.launch { container.settings.setSortMode(mode.key) }

    fun sortModeNow(): SortMode = currentSort

    /** تصنيف نوع ملف للواجهة */
    fun typeOf(file: File): FileType = FileType.of(file)
}
