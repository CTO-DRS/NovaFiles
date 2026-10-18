@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
 * ════════════════════════════════════════════════════════════════
 *  وضع اللوحتين (Dual Pane) — قلب تجربة الجهاز اللوحي كما في MiXplorer
 *  ─ لوحتان مستقلتان تماماً: كل واحدة بمسارها وتحديدها وعروضها
 *  ─ نقل/نسخ مباشر بين اللوحتين بنقرة واحدة من شريط التحديد
 *  ─ الرجوع يخدم اللوحة النشطة (الأحدث لمساً) أولاً
 *  ─ لوحة غير نشطة باهتة قليلاً — إشارة بصرية بأسلوب iOS
 * ════════════════════════════════════════════════════════════════
 */

/** الحد الأدنى لعرض النافذة (dp) الذي يُفعِّل اللوحتين — iPad عمودياً/هاتف بالعرض فأكثر */
const val DUAL_PANE_MIN_WIDTH_DP = 600

/** سياسة التخطيط — دالة نقية لتُختبر في JVM */
fun shouldUseDualPane(screenWidthDp: Int): Boolean = screenWidthDp >= DUAL_PANE_MIN_WIDTH_DP

@Composable
internal fun DualPaneBrowser(
    onOpenFile: (File) -> Unit,
    onOpenFtp: () -> Unit,
    onOpenNetwork: () -> Unit,
) {
    val context = LocalContext.current
    val palette = LocalIOSPalette.current
    val factory = viewModelFactory {
        initializer { BrowserViewModel(context.novaContainer, context.applicationContext) }
    }
    val vmA: BrowserViewModel = viewModel(key = "pane_a", factory = factory)
    val vmB: BrowserViewModel = viewModel(key = "pane_b", factory = factory)

    // اللوحة النشطة (الأحدث لمساً) — تحكم الرجوع والعرض
    var focused by rememberSaveable { mutableIntStateOf(0) }
    val focusedVm = if (focused == 0) vmA else vmB

    val selA by vmA.selection.collectAsState()
    val selB by vmB.selection.collectAsState()
    val msgA by vmA.message.collectAsState()
    val msgB by vmB.message.collectAsState()
    val pathA by vmA.path.collectAsState()
    val pathB by vmB.path.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    var hasAccess by remember { mutableStateOf(StorageAccess.hasAccess(context)) }
    val activity = context as? androidx.appcompat.app.AppCompatActivity

    // تحميل أولي للوحتين
    LaunchedEffect(Unit) {
        vmA.load(vmA.path.value)
        vmB.load(vmB.path.value)
    }

    // رسائل كلتا اللوحتين في شريط Snackbar واحد أسفل المنطقة
    LaunchedEffect(msgA) {
        val msg = msgA ?: return@LaunchedEffect
        showBrowserMessage(snackbar, context, msg)
        vmA.consumeMessage()
    }
    LaunchedEffect(msgB) {
        val msg = msgB ?: return@LaunchedEffect
        showBrowserMessage(snackbar, context, msg)
        vmB.consumeMessage()
    }

    // تحديث الصلاحية عند التنقل بين المجلدات
    LaunchedEffect(pathA, pathB) { hasAccess = StorageAccess.hasAccess(context) }

    // الرجوع: يُسجَّل معالج الصعود أولاً ثم معالج التحديد (الأحدث تفعيلاً يتقدم)
    BackHandler(enabled = true) {
        if (!focusedVm.up()) activity?.finish()
    }
    BackHandler(enabled = (if (focused == 0) selA else selB).isNotEmpty()) {
        focusedVm.clearSelection()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.groupedBg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!hasAccess) {
                PermissionBanner(onGrant = { activity?.let { StorageAccess.request(it) } })
            }
            Row(modifier = Modifier.weight(1f)) {
                BrowserPane(
                    vm = vmA,
                    focused = focused == 0,
                    onFocus = { focused = 0 },
                    onOpenFile = onOpenFile,
                    dualMode = true,
                    otherPath = { vmB.path.value },
                    onOpenFtp = onOpenFtp,
                    onOpenNetwork = onOpenNetwork,
                    modifier = Modifier.weight(1f),
                )
                // خط شعري فاصل بين اللوحتين
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(0.5.dp)
                        .background(palette.separator),
                )
                BrowserPane(
                    vm = vmB,
                    focused = focused == 1,
                    onFocus = { focused = 1 },
                    onOpenFile = onOpenFile,
                    dualMode = true,
                    otherPath = { vmA.path.value },
                    onOpenFtp = onOpenFtp,
                    onOpenNetwork = onOpenNetwork,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * لوحة متصفح واحدة مكتفية بذاتها: شريط علوي، قائمة/شبكة، تحديد، حوارات، أوراق إجراءات.
 * تُستخدم حصرياً داخل DualPaneBrowser — الشاشة الأحادية لها SinglePaneBrowser.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserPane(
    vm: BrowserViewModel,
    focused: Boolean,
    onFocus: () -> Unit,
    onOpenFile: (File) -> Unit,
    dualMode: Boolean,
    otherPath: () -> String,
    onOpenFtp: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val path by vm.path.collectAsState()
    val items by vm.items.collectAsState()
    val loading by vm.loading.collectAsState()
    val selection by vm.selection.collectAsState()
    val dialog by vm.dialog.collectAsState()
    val opState by vm.op.collectAsState()
    val viewGrid by vm.viewGrid.collectAsState()
    val showHidden by vm.showHidden.collectAsState()
    val sortMode by vm.sortMode.collectAsState()
    val favorites by vm.favoritesPaths.collectAsState()
    val hasClipboard = vm.clipboardOp.op != null

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val palette = LocalIOSPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var sendOpen by remember { mutableStateOf(false) }
    var treeOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val quickQr by vm.quickQr.collectAsState()
    val focusInteraction = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) { vm.load(path) }

    val contentModifier = Modifier
        .fillMaxSize()
        .alpha(if (focused) 1f else 0.72f)

    Box(modifier = modifier) {
        Box(modifier = contentModifier) {
            Scaffold(
                containerColor = palette.groupedBg,
                modifier = Modifier.fillMaxSize(),
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
                            onBack = if (File(path).parentFile != null) ({ onFocus(); vm.up() }) else null,
                            actions = {
                                IOSNavIconButton(
                                    Icons.Filled.AccountTree,
                                    contentDescription = stringResource(R.string.tree_open),
                                ) { onFocus(); treeOpen = true }
                                if (hasClipboard) {
                                    IOSNavIconButton(
                                        Icons.Filled.ContentPaste,
                                        contentDescription = stringResource(R.string.action_paste_here),
                                    ) { onFocus(); vm.pasteHere() }
                                }
                                IOSNavIconButton(
                                    Icons.Filled.Sort,
                                    contentDescription = stringResource(R.string.sort_by),
                                ) { onFocus(); sortOpen = true }
                                IOSNavIconButton(
                                    if (viewGrid) Icons.Filled.ViewList else Icons.Filled.GridView,
                                    contentDescription = stringResource(R.string.view_grid),
                                ) { onFocus(); vm.setViewGrid(!viewGrid) }
                                IOSNavIconButton(Icons.Filled.MoreVert, contentDescription = null) {
                                    onFocus(); menuOpen = true
                                }
                                IOSNavIconButton(
                                    Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.action_new_folder),
                                ) { onFocus(); vm.showDialog(BrowserDialog.NewFolder) }
                            },
                        )
                    } else {
                        IOSNavBar(
                            title = stringResource(R.string.items_count, selection.size),
                            collapsed = true,
                            onBack = { vm.clearSelection() },
                            backIcon = Icons.Filled.Close,
                            actions = {
                                if (dualMode) {
                                    IOSNavIconButton(
                                        Icons.Filled.CompareArrows,
                                        contentDescription = stringResource(R.string.pane_send_to_other),
                                    ) { onFocus(); sendOpen = true }
                                }
                                IOSNavIconButton(
                                    Icons.Filled.QrCode2,
                                    contentDescription = stringResource(R.string.qr_send_action),
                                ) {
                                    onFocus()
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
                                ) { onFocus(); vm.selectAll() }
                                IOSNavIconButton(
                                    Icons.Filled.FolderZip,
                                    contentDescription = stringResource(R.string.action_compress),
                                ) { onFocus(); vm.zipSelection() }
                                IOSNavIconButton(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.action_copy),
                                ) { onFocus(); vm.copySelection() }
                                IOSNavIconButton(
                                    Icons.Filled.ContentCut,
                                    contentDescription = stringResource(R.string.action_move),
                                ) { onFocus(); vm.cutSelection() }
                                IOSNavIconButton(
                                    Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = palette.destructive,
                                ) {
                                    onFocus()
                                    vm.showDialog(BrowserDialog.ConfirmDelete(selection.size))
                                }
                            },
                        )
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .clickable(
                            interactionSource = focusInteraction,
                            indication = null,
                        ) { onFocus() },
                ) {
                    if (opState.running) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    when {
                        loading -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }

                        items.isEmpty() -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.empty_folder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        viewGrid -> LazyVerticalGrid(
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
                                        onFocus()
                                        if (selection.isNotEmpty()) vm.toggleSelect(file)
                                        else if (file.isDirectory) vm.load(file.absolutePath)
                                        else onOpenFile(file)
                                    },
                                    onLongTap = { onFocus(); vm.toggleSelect(file) },
                                    vm = vm,
                                )
                            }
                        }

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                        ) {
                            items(items, key = { it.absolutePath }) { file ->
                                ListFileItem(
                                    file = file,
                                    selected = selection.contains(file.absolutePath),
                                    favorite = favorites.contains(file.absolutePath),
                                    onTap = {
                                        onFocus()
                                        if (selection.isNotEmpty()) vm.toggleSelect(file)
                                        else if (file.isDirectory) vm.load(file.absolutePath)
                                        else onOpenFile(file)
                                    },
                                    onLongTap = { onFocus(); vm.toggleSelect(file) },
                                    vm = vm,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── الحوارات (خارج طبقة البهت لتظل بكامل الوضوح) ──
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
            BrowserDialog.None -> {}
        }

        // لوحة الشجرة الجانبية لهذه اللوحة — خارج طبقة البهت
        DirectoryTreePanel(
            visible = treeOpen,
            currentPath = path,
            onNavigate = { vm.load(it) },
            onDismiss = { treeOpen = false },
        )
    }

    // حوار إرسال QR السريع لهذه اللوحة
    QuickQrHost(
        state = quickQr,
        itemCount = 1,
        onDismiss = { vm.consumeQuickQr() },
    )

    // ورقة الإرسال إلى اللوحة الأخرى — جوهر وضع اللوحتين
    IOSActionSheet(
        visible = sendOpen,
        onDismiss = { sendOpen = false },
        cancelText = stringResource(R.string.action_cancel),
        title = stringResource(R.string.pane_send_to_other),
        actions = listOf(
            IOSAction(stringResource(R.string.pane_send_copy)) {
                vm.sendSelectionTo(File(otherPath()), move = false)
            },
            IOSAction(stringResource(R.string.pane_send_move)) {
                vm.sendSelectionTo(File(otherPath()), move = true)
            },
        ),
    )

    // ورقة الترتيب
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

    // ورقة خيارات المجلد
    IOSActionSheet(
        visible = menuOpen,
        onDismiss = { menuOpen = false },
        cancelText = stringResource(R.string.action_cancel),
        actions = listOf(
            IOSAction(stringResource(R.string.action_new_folder)) { vm.showDialog(BrowserDialog.NewFolder) },
            IOSAction(stringResource(R.string.action_refresh)) { vm.refresh() },
            IOSAction(stringResource(R.string.show_hidden), selected = showHidden) { vm.setShowHidden(!showHidden) },
            IOSAction(stringResource(R.string.menu_wifi_share)) {
                menuOpen = false
                onOpenFtp()
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
