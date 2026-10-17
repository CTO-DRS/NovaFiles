@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.novafiles.app.R
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.core.fs.LocalBrowser
import com.novafiles.app.core.fs.SortMode
import com.novafiles.app.core.perms.StorageAccess
import com.novafiles.app.novaContainer
import com.novafiles.app.util.TypeIcons
import java.io.File

@Composable
fun BrowserScreen(
    onOpenFile: (File) -> Unit,
    onOpenSearchFor: (String) -> Unit,
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
    val favorites by vm.favoritesPaths.collectAsState()
    val clipboard = vm.clipboardOp.paths
    val clipboardOp = vm.clipboardOp.op

    val snackbar = remember { SnackbarHostState() }
    var hasAccess by remember { mutableStateOf(StorageAccess.hasAccess(context)) }

    // تحميل المسار عند أول ظهور وعند تغيّره
    LaunchedEffect(Unit) { vm.load(path) }

    // رسائل سريعة: نترجم المفاتيح الداخلية إلى نصوص
    LaunchedEffect(message) {
        val msg = message ?: return@LaunchedEffect
        when {
            msg == "trash:ok" -> snackbar.showSnackbar(context.getString(R.string.action_delete_done))
            msg == "paste:ok" -> snackbar.showSnackbar(context.getString(R.string.op_done))
            msg == "restore:ok" -> snackbar.showSnackbar(context.getString(R.string.op_done))
            msg == "mkdir:ok" || msg == "rename:ok" || msg == "vault:ok" ->
                snackbar.showSnackbar(context.getString(R.string.op_done))
            msg.startsWith("copied:") -> snackbar.showSnackbar(
                context.getString(R.string.copied_n, msg.substringAfter(":").toInt())
            )
            msg.startsWith("cut:") -> snackbar.showSnackbar(
                context.getString(R.string.cut_n, msg.substringAfter(":").toInt())
            )
            else -> snackbar.showSnackbar(msg)
        }
        vm.consumeMessage()
    }

    val activity = context as? androidx.appcompat.app.AppCompatActivity

    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }
    BackHandler(enabled = selection.isEmpty()) {
        if (!vm.up()) activity?.finish()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (selection.isEmpty()) {
                BrowserTopBar(
                    path = path,
                    viewGrid = viewGrid,
                    showHidden = showHidden,
                    sort = vm.sortModeNow(),
                    clipboardCount = clipboard.size,
                    hasClipboard = clipboardOp != null,
                    onUp = { vm.up() },
                    onToggleView = { vm.setViewGrid(!viewGrid) },
                    onToggleHidden = { vm.setShowHidden(!showHidden) },
                    onSort = { vm.setSort(it) },
                    onRefresh = { vm.refresh() },
                    onNewFolder = { vm.showDialog(BrowserDialog.NewFolder) },
                    onPaste = { vm.pasteHere() },
                )
            } else {
                SelectionTopBar(
                    count = selection.size,
                    onSelectAll = { vm.selectAll() },
                    onCopy = { vm.copySelection() },
                    onCut = { vm.cutSelection() },
                    onDelete = { vm.showDialog(BrowserDialog.ConfirmDelete(selection.size)) },
                    onClear = { vm.clearSelection() },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.showDialog(BrowserDialog.NewFolder) }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.action_new_folder))
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
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
            is BrowserDialog.ConfirmDelete -> AlertDialog(
                onDismissRequest = { vm.closeDialog() },
                title = { Text(stringResource(R.string.dialog_delete_title)) },
                text = { Text(stringResource(R.string.dialog_delete_body, d.count)) },
                confirmButton = {
                    TextButton(onClick = { vm.deleteSelection(); vm.closeDialog() }) {
                        Text(stringResource(R.string.action_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { vm.closeDialog() }) { Text(stringResource(R.string.action_cancel)) }
                },
            )
            BrowserDialog.None -> {}
        }
    }

    // تحديث الصلاحية بعد العودة من الإعدادات
    LaunchedEffect(path) { hasAccess = StorageAccess.hasAccess(context) }
}

@Composable
private fun BrowserTopBar(
    path: String,
    viewGrid: Boolean,
    showHidden: Boolean,
    sort: SortMode,
    clipboardCount: Int,
    hasClipboard: Boolean,
    onUp: () -> Unit,
    onToggleView: () -> Unit,
    onToggleHidden: () -> Unit,
    onSort: (SortMode) -> Unit,
    onRefresh: () -> Unit,
    onNewFolder: () -> Unit,
    onPaste: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Column {
                Text(File(path).name.ifEmpty { "NovaFiles" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    path,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        navigationIcon = {
            if (File(path).parentFile != null) {
                IconButton(onClick = onUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_go_up))
                }
            }
        },
        actions = {
            if (hasClipboard) {
                IconButton(onClick = onPaste) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = stringResource(R.string.action_paste_here))
                }
            }
            Box {
                IconButton(onClick = { sortOpen = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.sort_by))
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sort_name)) },
                        onClick = { onSort(SortMode.NAME); sortOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sort_size)) },
                        onClick = { onSort(SortMode.SIZE); sortOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sort_date)) },
                        onClick = { onSort(SortMode.DATE); sortOpen = false },
                    )
                }
            }
            IconButton(onClick = onToggleView) {
                Icon(
                    if (viewGrid) Icons.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = stringResource(R.string.view_grid),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_new_folder)) },
                        onClick = { onNewFolder(); menuOpen = false },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_refresh)) },
                        onClick = { onRefresh(); menuOpen = false },
                        leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.show_hidden)) },
                        onClick = { onToggleHidden(); menuOpen = false },
                        trailingIcon = { if (showHidden) Icon(Icons.Filled.Close, null) },
                    )
                }
            }
        },
    )
}

@Composable
private fun SelectionTopBar(
    count: Int,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(R.string.items_count, count)) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear_selection))
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.action_select_all))
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
            }
            IconButton(onClick = onCut) {
                Icon(Icons.Filled.ContentCut, contentDescription = stringResource(R.string.action_move))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        },
    )
}

@Composable
private fun PermissionBanner(onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.storage_access_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.storage_access_body), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onGrant) { Text(stringResource(R.string.storage_access_grant)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListFileItem(
    file: File,
    selected: Boolean,
    favorite: Boolean,
    onTap: () -> Unit,
    onLongTap: () -> Unit,
    vm: BrowserViewModel,
) {
    val type = FileType.of(file)
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongTap)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Checkbox(checked = true, onCheckedChange = { onLongTap() })
        }
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (type == FileType.IMAGE && file.exists()) {
                AsyncImage(
                    model = file,
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    TypeIcons.icon(type),
                    contentDescription = file.name,
                    tint = TypeIcons.color(type),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (file.isDirectory) stringResource(R.string.items_count, LocalBrowser.itemCount(file))
                else "${LocalBrowser.humanSize(file.length())} • ${LocalBrowser.formatDate(file.lastModified())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (favorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        ItemMenu(file = file, vm = vm, context = context)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridFileItem(
    file: File,
    selected: Boolean,
    favorite: Boolean,
    onTap: () -> Unit,
    onLongTap: () -> Unit,
    vm: BrowserViewModel,
) {
    val type = FileType.of(file)
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .combinedClickable(onClick = onTap, onLongClick = onLongTap)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (type == FileType.IMAGE && file.exists()) {
                AsyncImage(
                    model = file,
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    TypeIcons.icon(type),
                    contentDescription = file.name,
                    tint = TypeIcons.color(type),
                    modifier = Modifier.size(34.dp),
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            file.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (!file.isDirectory) {
            Text(
                LocalBrowser.humanSize(file.length()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ItemMenu(file = file, vm = vm, context = context, compact = true)
    }
}

/** قائمة الخيارات لكل عنصر (تعمل من لقطة الطبقة العليا) */
@Composable
private fun ItemMenu(file: File, vm: BrowserViewModel, context: android.content.Context, compact: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(if (compact) 30.dp else 40.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = null, modifier = Modifier.size(if (compact) 18.dp else 22.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val isFav = vm.favoritesPaths.collectAsState().value.contains(file.absolutePath)
            if (!file.isDirectory) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_encrypt_vault)) },
                    onClick = { open = false; vm.encryptIntoVault(file) },
                    leadingIcon = { Icon(Icons.Filled.Lock, null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_share)) },
                    onClick = { open = false; com.novafiles.app.util.Intents.share(context, file) },
                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                )
            }
            DropdownMenuItem(
                text = { Text(if (isFav) stringResource(R.string.action_favorite_remove) else stringResource(R.string.action_favorite_add)) },
                onClick = { open = false; vm.toggleFavorite(file) },
                leadingIcon = { Icon(if (isFav) Icons.Filled.Star else Icons.Filled.StarBorder, null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename)) },
                onClick = { open = false; vm.showDialog(BrowserDialog.Rename(file)) },
                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_details)) },
                onClick = { open = false; vm.showDialog(BrowserDialog.Details(file)) },
                leadingIcon = { Icon(Icons.Filled.Info, null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                onClick = {
                    open = false
                    vm.toggleSelect(file)
                    vm.showDialog(BrowserDialog.ConfirmDelete(1))
                },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
            )
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun DetailsDialog(file: File, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.dialog_details_title)) },
        text = {
            Column {
                Text(file.name, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(8.dp))
                Text("${stringResource(R.string.details_path)}: ${file.absolutePath}", style = MaterialTheme.typography.bodySmall)
                if (file.isDirectory) {
                    Text(
                        "${stringResource(R.string.details_items)}: ${LocalBrowser.itemCount(file)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "${stringResource(R.string.details_size)}: ${LocalBrowser.humanSize(file.length())}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    "${stringResource(R.string.details_modified)}: ${LocalBrowser.formatDate(file.lastModified())}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_ok)) } },
    )
}
