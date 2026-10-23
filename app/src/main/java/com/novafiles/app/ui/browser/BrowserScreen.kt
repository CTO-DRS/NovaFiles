@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.novafiles.app.R
import com.novafiles.app.core.fs.PathDisplay
import com.novafiles.app.core.fs.SortMode
import com.novafiles.app.core.fs.StorageRoot
import com.novafiles.app.core.gesture.DoubleTapPolicy
import com.novafiles.app.core.gesture.GestureConfig
import com.novafiles.app.core.gesture.SwipeAction
import com.novafiles.app.core.perms.StorageAccess
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.tags.TagAssignSheet
import com.novafiles.app.ui.tags.TagFilterBar
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.rememberBottomBarClearance
import com.novafiles.app.ui.ios.IOSNavIconButton
import com.novafiles.app.ui.ios.LocalIOSPalette
import java.io.File

/**
 * شاشة الملفات — تتكيف مع عرض النافذة:
 *  ─ أقل من 600dp (هاتف): لوحة واحدة كما هي
 *  ─ 600dp فأكثر (جهاز لوحي/هاتف بالعرض): لوحتان مستقلتان بنمط iPad
 */
@Composable
fun BrowserScreen(
    onOpenFile: (File) -> Unit,
    onOpenSearchFor: (String) -> Unit,
    onOpenFtp: () -> Unit = {},
    onOpenDav: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    onOpenOps: () -> Unit = {},
    onOpenDirectSend: () -> Unit = {},
    onOpenAnalyzer: () -> Unit = {},
    onOpenApps: () -> Unit = {},
    onOpenChecksums: (String) -> Unit = {},
    onOpenMediaTools: (String) -> Unit = {},
) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    if (shouldUseDualPane(widthDp)) {
        DualPaneBrowser(
            onOpenFile = onOpenFile,
            onOpenFtp = onOpenFtp,
            onOpenDav = onOpenDav,
            onOpenNetwork = onOpenNetwork,
        )
    } else {
        SinglePaneBrowser(
            onOpenFile = onOpenFile,
            onOpenSearchFor = onOpenSearchFor,
            onOpenFtp = onOpenFtp,
            onOpenDav = onOpenDav,
            onOpenTerminal = onOpenTerminal,
            onOpenNetwork = onOpenNetwork,
            onOpenOps = onOpenOps,
            onOpenDirectSend = onOpenDirectSend,
            onOpenAnalyzer = onOpenAnalyzer,
            onOpenApps = onOpenApps,
            onOpenChecksums = onOpenChecksums,
            onOpenMediaTools = onOpenMediaTools,
        )
    }
}

@Composable
private fun SinglePaneBrowser(
    onOpenFile: (File) -> Unit,
    onOpenSearchFor: (String) -> Unit,
    onOpenFtp: () -> Unit,
    onOpenDav: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenOps: () -> Unit,
    onOpenDirectSend: () -> Unit,
    onOpenAnalyzer: () -> Unit = {},
    onOpenApps: () -> Unit = {},
    onOpenChecksums: (String) -> Unit = {},
    onOpenMediaTools: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val vm: BrowserViewModel = viewModel(
        factory = viewModelFactory {
            initializer { BrowserViewModel(context.novaContainer, context.applicationContext) }
        }
    )

    val path by vm.path.collectAsState()
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val selection by vm.selection.collectAsState()
    val dialog by vm.dialog.collectAsState()
    val message by vm.message.collectAsState()
    val opState by vm.op.collectAsState()
    val viewGrid by vm.viewGrid.collectAsState()
    val showHidden by vm.showHidden.collectAsState()
    val sortMode by vm.sortMode.collectAsState()
    val favorites by vm.favoritesPaths.collectAsState()
    val clipboard = vm.clipboardOp.paths
    val clipboardOp = vm.clipboardOp.op
    val hasClipboard = clipboardOp != null

    val snackbar = remember { SnackbarHostState() }
    var hasAccess by remember { mutableStateOf(StorageAccess.hasAccess(context)) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val palette = LocalIOSPalette.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    // v0.17 — إيماءات السحب القابلة للضبط + النقر المزدوج للتنقل
    val gesturesRaw by context.novaContainer.settings.gestures.collectAsState("")
    val gestureConfig = remember(gesturesRaw) { GestureConfig.parse(gesturesRaw) }
    val doubleTapEnabled by context.novaContainer.settings.doubleTapNavigate.collectAsState(false)
    val doubleTap = remember { DoubleTapPolicy() }
    var pendingTapKey by remember { mutableStateOf<String?>(null) }

    fun handleSwipeAction(action: SwipeAction, file: File) {
        when (action) {
            SwipeAction.FAVORITE -> vm.toggleFavorite(file)
            SwipeAction.DETAILS -> vm.showDialog(BrowserDialog.Details(file))
            SwipeAction.SHARE -> com.novafiles.app.util.Intents.share(context, file)
            SwipeAction.COPY -> { vm.toggleSelect(file); vm.copySelection() }
            SwipeAction.MOVE -> { vm.toggleSelect(file); vm.cutSelection() }
            SwipeAction.COMPRESS -> { vm.toggleSelect(file); vm.zipSelection() }
            SwipeAction.RENAME -> vm.showDialog(BrowserDialog.Rename(file))
            SwipeAction.TRASH, SwipeAction.DELETE -> { vm.toggleSelect(file); vm.deleteSelection() }
            SwipeAction.NONE -> {}
        }
    }
    var treeOpen by remember { mutableStateOf(false) }
    val quickQr by vm.quickQr.collectAsState()
    val opRecords by vm.opRecords.collectAsState()

    // v0.33 — الوسوم: شريط فلترة + ورقة إسناد للتحديد
    val tagsRepo = context.novaContainer.tags
    val tagDefs by tagsRepo.observeDefs().collectAsState(initial = emptyList())
    var tagCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var selectedTagId by remember { mutableStateOf<Long?>(null) }
    var tagFilterPaths by remember { mutableStateOf<Set<String>?>(null) }
    var tagSheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tagDefs) { tagCounts = tagsRepo.counts() }
    LaunchedEffect(selectedTagId) {
        tagFilterPaths = selectedTagId?.let { tagsRepo.pathsForTag(it).toSet() }
    }
    val visibleItems: List<File> = remember(items, tagFilterPaths) {
        val filterSet: Set<String>? = tagFilterPaths
        if (filterSet == null) items else items.filter { f -> f.absolutePath in filterSet }
    }

    // v0.12 — تحديث تلقائي للمجلد عندما تنتهي عملية خلفية (نسخ/نقل/حذف…)
    var lastFinishedOps by remember { mutableStateOf(0) }
    LaunchedEffect(opRecords) {
        val finished = opRecords.count {
            it.status == com.novafiles.app.core.ops.OpStatus.DONE ||
                it.status == com.novafiles.app.core.ops.OpStatus.FAILED ||
                it.status == com.novafiles.app.core.ops.OpStatus.CANCELLED
        }
        if (finished > lastFinishedOps) {
            lastFinishedOps = finished
            vm.refresh()
        }
    }

    // تحميل المسار عند أول ظهور وعند تغيّره
    LaunchedEffect(Unit) { vm.load(path) }

    // رسائل سريعة: نترجم المفاتيح الداخلية إلى نصوص
    LaunchedEffect(message) {
        val msg = message ?: return@LaunchedEffect
        if (msg.startsWith("rename_batch:")) {
            // إعادة تسمية جماعية — شريط مع زر «تراجع» لاستعادة الأسماء الأصلية
            val result = snackbar.showSnackbar(
                context.getString(R.string.batch_done_n, msg.substringAfter(":").toInt()),
                actionLabel = context.getString(R.string.batch_undo),
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                vm.undoBatchRename()
            }
        } else {
            showBrowserMessage(snackbar, context, msg)
        }
        vm.consumeMessage()
    }

    val activity = context as? androidx.appcompat.app.AppCompatActivity

    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }
    BackHandler(enabled = selection.isEmpty()) {
        if (!vm.up()) activity?.finish()
    }

    Scaffold(
        containerColor = palette.groupedBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selection.isEmpty()) {
                val scrolled = if (viewGrid) {
                    gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 12
                } else {
                    listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 12
                }
                // v0.18 — عنوان ودّي للجذور («الذاكرة الداخلية» بدل «0»)
                // ومسار مضغوط كشارة تحت العنوان
                val root = StorageRoot.of(path)
                val displayTitle = when (root) {
                    StorageRoot.INTERNAL -> stringResource(R.string.root_internal)
                    StorageRoot.DEVICE -> stringResource(R.string.root_device)
                    null -> File(path).name.ifEmpty { "NovaFiles" }
                }
                val compactSub = PathDisplay.compactPath(path)
                IOSNavBar(
                    title = displayTitle,
                    subtitle = compactSub,
                    collapsed = scrolled,
                    onBack = if (File(path).parentFile != null) ({ vm.up() }) else null,
                    actions = {
                        if (hasClipboard) {
                            IOSNavIconButton(
                                Icons.Filled.ContentPaste,
                                contentDescription = stringResource(R.string.action_paste_here),
                            ) { vm.pasteHere() }
                        }
                        IOSNavIconButton(Icons.Filled.Sort, contentDescription = stringResource(R.string.sort_by)) {
                            sortOpen = true
                        }
                        IOSNavIconButton(
                            if (viewGrid) Icons.Filled.ViewList else Icons.Filled.GridView,
                            contentDescription = stringResource(R.string.view_grid),
                        ) { vm.setViewGrid(!viewGrid) }
                        IOSNavIconButton(Icons.Filled.MoreVert, contentDescription = null) {
                            menuOpen = true
                        }
                        // v0.19 — زر «+» حُذف من الشريط لتخفيفه (مجلد جديد متاح في ورقة «المزيد»)
                    },
                )
            } else {
                IOSNavBar(
                    title = stringResource(R.string.items_count, selection.size),
                    collapsed = true,
                    onBack = { vm.clearSelection() },
                    backIcon = Icons.Filled.Close,
                    actions = {
                        if (selection.size > 1) {
                            IOSNavIconButton(
                                Icons.Filled.DriveFileRenameOutline,
                                contentDescription = stringResource(R.string.batch_action),
                            ) { vm.showDialog(BrowserDialog.BatchRename) }
                        }
                        IOSNavIconButton(
                            Icons.Filled.QrCode2,
                            contentDescription = stringResource(R.string.qr_send_action),
                        ) {
                            launchQuickQr(
                                context = context,
                                scope = scope,
                                files = selection.map { File(it) },
                                onReady = { vm.showQuickQr(it) },
                                onError = { vm.notify("qr_no_network") },
                            )
                        }
                        IOSNavIconButton(
                            Icons.Filled.Label,
                            contentDescription = stringResource(R.string.tags_action),
                        ) { tagSheetOpen = true }
                        IOSNavIconButton(
                            Icons.Filled.SelectAll,
                            contentDescription = stringResource(R.string.action_select_all),
                        ) { vm.selectAll() }
                        IOSNavIconButton(
                            Icons.Filled.FolderZip,
                            contentDescription = stringResource(R.string.action_compress),
                        ) { vm.showDialog(BrowserDialog.CompressTo) }
                        IOSNavIconButton(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy),
                        ) { vm.copySelection() }
                        IOSNavIconButton(
                            Icons.Filled.ContentCut,
                            contentDescription = stringResource(R.string.action_move),
                        ) { vm.cutSelection() }
                        IOSNavIconButton(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = palette.destructive,
                        ) { vm.showDialog(BrowserDialog.ConfirmDelete(selection.size)) }
                    },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!hasAccess) {
                PermissionBanner(
                    onGrant = {
                        activity?.let { StorageAccess.request(it) }
                    }
                )
            }
            if (opState.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            // v0.33 — شريط فلترة الوسوم (يظهر عند وجود وسوم فقط)
            if (selection.isEmpty()) {
                TagFilterBar(
                    defs = tagDefs,
                    counts = tagCounts,
                    selectedTagId = selectedTagId,
                    onSelect = { selectedTagId = it },
                )
            }
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (visibleItems.isEmpty()) {
                // v0.18 — حالة فارغة مصممة: أيقونة هادئة + عنوان + تلميح
                val palette = LocalIOSPalette.current
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = palette.secondaryLabel.copy(alpha = 0.45f),
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            stringResource(R.string.empty_folder),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.empty_folder_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.secondaryLabel.copy(alpha = 0.8f),
                        )
                    }
                }
            } else if (viewGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxSize(),
                    state = gridState,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp + rememberBottomBarClearance()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visibleItems, key = { it.absolutePath }) { file ->
                        GridFileItem(
                            file = file,
                            selected = selection.contains(file.absolutePath),
                            favorite = favorites.contains(file.absolutePath),
                            onTap = {
                                if (selection.isNotEmpty()) vm.toggleSelect(file)
                                else if (file.isDirectory) vm.load(file.absolutePath)
                                else onOpenFile(file)
                            },
                            onLongTap = { vm.toggleSelect(file) },
                            vm = vm,
                        )
                    }
                }
            } else {
                // v0.18 — قائمة بطاقات: هوامش داخلية 12dp وفراغ 6dp بين البطاقات
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp + rememberBottomBarClearance()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visibleItems, key = { it.absolutePath }) { file ->
                        // v0.17 — النقر المزدوج للتنقل على المجلدات (اختياري من الإعدادات)
                        val rowSelected = selection.contains(file.absolutePath) ||
                            (doubleTapEnabled && pendingTapKey == file.absolutePath)
                        SwipeableRow(
                            fileKey = file.absolutePath,
                            config = gestureConfig,
                            onAction = { handleSwipeAction(it, file) },
                        ) {
                            ListFileItem(
                                file = file,
                                selected = rowSelected,
                                favorite = favorites.contains(file.absolutePath),
                                onTap = {
                                    if (selection.isNotEmpty()) {
                                        vm.toggleSelect(file)
                                    } else if (doubleTapEnabled && file.isDirectory) {
                                        if (doubleTap.onTap(System.currentTimeMillis(), file.absolutePath)) {
                                            pendingTapKey = null
                                            vm.load(file.absolutePath)
                                        } else {
                                            pendingTapKey = file.absolutePath
                                        }
                                    } else if (file.isDirectory) {
                                        vm.load(file.absolutePath)
                                    } else {
                                        onOpenFile(file)
                                    }
                                },
                                onLongTap = { vm.toggleSelect(file) },
                                vm = vm,
                            )
                        }
                    }
                }
            }
        }

        // الحوارات
        when (val d = dialog) {
            is BrowserDialog.NewFolder -> NameDialog(
                title = stringResource(R.string.dialog_new_folder_title),
                initial = "",
                onDismiss = { vm.closeDialog() },
                onConfirm = { name -> vm.createFolder(name); vm.closeDialog() },
            )
            is BrowserDialog.Rename -> NameDialog(
                title = stringResource(R.string.dialog_rename_title),
                initial = d.file.name,
                onDismiss = { vm.closeDialog() },
                onConfirm = { name -> vm.rename(d.file, name); vm.closeDialog() },
            )
            is BrowserDialog.Details -> DetailsDialog(file = d.file, onClose = { vm.closeDialog() })
            is BrowserDialog.ConfirmDelete -> IOSAlertDialog(
                title = stringResource(R.string.dialog_delete_title),
                message = stringResource(R.string.dialog_delete_body, d.count),
                confirmText = stringResource(R.string.action_delete),
                onConfirm = { vm.deleteSelection(); vm.closeDialog() },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { vm.closeDialog() },
                destructive = true,
            )
            BrowserDialog.BatchRename -> BatchRenameSheet(
                files = selection.map { File(it) }.sortedBy { it.name.lowercase() },
                onDismiss = { vm.closeDialog() },
                onApply = { plan ->
                    vm.applyBatchRename(plan)
                },
            )
            // v0.30 — ورقة «ضغط إلى…» : صيغة + مستوى + كلمة مرور + تقسيم
            BrowserDialog.CompressTo -> CompressToSheet(
                itemCount = selection.size,
                suggestedName = compressBaseName(selection.map { File(it) }, path),
                onDismiss = { vm.closeDialog() },
                onCompress = { request ->
                    vm.compressSelection(request)
                    vm.closeDialog()
                },
            )
            BrowserDialog.None -> {}
        }

        // v0.33 — ورقة إسناد الوسوم للتحديد الحالي
        TagAssignSheet(
            visible = tagSheetOpen,
            paths = selection.toList(),
            repo = tagsRepo,
            onDismiss = { tagSheetOpen = false },
        )

        // لوحة الشجرة الجانبية — فوق كل شيء، من الحافة الابتدائية
        DirectoryTreePanel(
            visible = treeOpen,
            currentPath = path,
            onNavigate = { vm.load(it) },
            onDismiss = { treeOpen = false },
        )
    }

    // حوار إرسال QR السريع
    QuickQrHost(
        state = quickQr,
        itemCount = 1,
        onDismiss = { vm.consumeQuickQr() },
    )

    // تحديث الصلاحية بعد العودة من الإعدادات
    LaunchedEffect(path) { hasAccess = StorageAccess.hasAccess(context) }

    // ورقة إجراءات iOS — الترتيب
    IOSActionSheet(
        visible = sortOpen,
        onDismiss = { sortOpen = false },
        cancelText = stringResource(R.string.action_cancel),
        title = stringResource(R.string.sort_by),
        actions = listOf(
            IOSAction(stringResource(R.string.sort_name), selected = sortMode == SortMode.NAME) { vm.setSort(SortMode.NAME) },
            IOSAction(stringResource(R.string.sort_size), selected = sortMode == SortMode.SIZE) { vm.setSort(SortMode.SIZE) },
            IOSAction(stringResource(R.string.sort_date), selected = sortMode == SortMode.DATE) { vm.setSort(SortMode.DATE) },
        ),
    )

    // ورقة إجراءات iOS — خيارات المجلد
    IOSActionSheet(
        visible = menuOpen,
        onDismiss = { menuOpen = false },
        cancelText = stringResource(R.string.action_cancel),
        actions = listOf(
            IOSAction(stringResource(R.string.tree_open)) {
                menuOpen = false
                treeOpen = true
            },
            IOSAction(stringResource(R.string.action_new_folder)) { vm.showDialog(BrowserDialog.NewFolder) },
            IOSAction(stringResource(R.string.action_refresh)) { vm.refresh() },
            IOSAction(
                stringResource(R.string.show_hidden),
                selected = showHidden,
            ) { vm.setShowHidden(!showHidden) },
            IOSAction(stringResource(R.string.menu_wifi_share)) {
                menuOpen = false
                onOpenFtp()
            },
            IOSAction(stringResource(R.string.menu_webdav_server)) {
                menuOpen = false
                onOpenDav()
            },
            IOSAction(stringResource(R.string.menu_terminal)) {
                menuOpen = false
                onOpenTerminal()
            },
            IOSAction(stringResource(R.string.direct_menu_entry)) {
                menuOpen = false
                onOpenDirectSend()
            },
            IOSAction(stringResource(R.string.ops_menu_entry)) {
                menuOpen = false
                onOpenOps()
            },
            IOSAction(stringResource(R.string.menu_network)) {
                menuOpen = false
                onOpenNetwork()
            },
            IOSAction(stringResource(R.string.analyzer_title)) {
                menuOpen = false
                onOpenAnalyzer()
            },
            IOSAction(stringResource(R.string.apps_title)) {
                menuOpen = false
                onOpenApps()
            },
            IOSAction(stringResource(R.string.menu_checksums)) {
                menuOpen = false
                onOpenChecksums(path)
            },
            IOSAction(stringResource(R.string.menu_media_tools)) {
                menuOpen = false
                onOpenMediaTools(path)
            },
            IOSAction(stringResource(R.string.qr_show)) {
                menuOpen = false
                launchQuickQr(
                    context = context,
                    scope = scope,
                    files = listOf(File(path)),
                    onReady = { vm.showQuickQr(it) },
                    onError = { vm.notify("qr_no_network") },
                )
            },
        ),
    )
}

/**
 * v0.30 — الاسم الأساس المقترح لورقة «ضغط إلى…»:
 * عنصر واحد → اسمه دون امتداد، وأكثر → اسم المجلد الحالي.
 */
internal fun compressBaseName(sources: List<File>, currentPath: String): String =
    if (sources.size == 1) {
        sources[0].nameWithoutExtension.ifEmpty { sources[0].name }
    } else {
        File(currentPath).name.ifEmpty { "Archive" }
    }
