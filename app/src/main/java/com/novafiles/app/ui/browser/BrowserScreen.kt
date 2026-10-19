@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.novafiles.app.core.fs.SortMode
import com.novafiles.app.core.perms.StorageAccess
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSNavBar
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
    onOpenNetwork: () -> Unit = {},
    onOpenOps: () -> Unit = {},
    onOpenDirectSend: () -> Unit = {},
) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    if (shouldUseDualPane(widthDp)) {
        DualPaneBrowser(
            onOpenFile = onOpenFile,
            onOpenFtp = onOpenFtp,
            onOpenNetwork = onOpenNetwork,
        )
    } else {
        SinglePaneBrowser(
            onOpenFile = onOpenFile,
            onOpenSearchFor = onOpenSearchFor,
            onOpenFtp = onOpenFtp,
            onOpenNetwork = onOpenNetwork,
            onOpenOps = onOpenOps,
            onOpenDirectSend = onOpenDirectSend,
        )
    }
}

@Composable
private fun SinglePaneBrowser(
    onOpenFile: (File) -> Unit,
    onOpenSearchFor: (String) -> Unit,
    onOpenFtp: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenOps: () -> Unit,
    onOpenDirectSend: () -> Unit,
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
    var treeOpen by remember { mutableStateOf(false) }
    val quickQr by vm.quickQr.collectAsState()
    val opRecords by vm.opRecords.collectAsState()

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
                IOSNavBar(
                    title = File(path).name.ifEmpty { "NovaFiles" },
                    subtitle = path,
                    collapsed = scrolled,
                    onBack = if (File(path).parentFile != null) ({ vm.up() }) else null,
                    actions = {
                        IOSNavIconButton(
                            Icons.Filled.AccountTree,
                            contentDescription = stringResource(R.string.tree_open),
                        ) { treeOpen = true }
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
                        IOSNavIconButton(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.action_new_folder),
                        ) { vm.showDialog(BrowserDialog.NewFolder) }
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
                            Icons.Filled.SelectAll,
                            contentDescription = stringResource(R.string.action_select_all),
                        ) { vm.selectAll() }
                        IOSNavIconButton(
                            Icons.Filled.FolderZip,
                            contentDescription = stringResource(R.string.action_compress),
                        ) { vm.zipSelection() }
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
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty_folder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (viewGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    state = gridState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.absolutePath }) { file ->
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
                LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                    items(items, key = { it.absolutePath }) { file ->
                        ListFileItem(
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
            BrowserDialog.None -> {}
        }

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
