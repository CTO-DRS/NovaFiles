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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderZip
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.novafiles.app.core.archive.ArchiveEngine
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.core.fs.LocalBrowser
import com.novafiles.app.core.fs.SortMode
import com.novafiles.app.core.perms.StorageAccess
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSAlertTextField
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSNavIconButton
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.util.TypeIcons
import java.io.File

@Composable
fun BrowserScreen(
    onOpenFile: (File) -> Unit,
    onOpenSearchFor: (String) -> Unit,
    onOpenFtp: () -> Unit = {},
    onOpenNetwork: () -> Unit = {},
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
    var menuOpen by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }

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
            msg == "zip:ok" -> snackbar.showSnackbar(context.getString(R.string.archive_created))
            msg == "unzip:ok" -> snackbar.showSnackbar(context.getString(R.string.archive_done_brief))
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
            BrowserDialog.None -> {}
        }
    }

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
            IOSAction(stringResource(R.string.menu_network)) {
                menuOpen = false
                onOpenNetwork()
            },
        ),
    )
}

@Composable
private fun PermissionBanner(onGrant: () -> Unit) {
    IOSGroupCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.storage_access_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.storage_access_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/** ورقة إجراءات iOS لكل عنصر (تعمل من لقطة الطبقة العليا) */
@Composable
private fun ItemMenu(file: File, vm: BrowserViewModel, context: android.content.Context, compact: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(if (compact) 30.dp else 40.dp)) {
        Icon(Icons.Filled.MoreVert, contentDescription = null, modifier = Modifier.size(if (compact) 18.dp else 22.dp))
    }
    val isFav = vm.favoritesPaths.collectAsState().value.contains(file.absolutePath)
    val isExtractable = !file.isDirectory && ArchiveEngine.supports(file.name)
    val sEncrypt = stringResource(R.string.action_encrypt_vault)
    val sShare = stringResource(R.string.action_share)
    val sExtract = stringResource(R.string.action_extract)
    val sFavAdd = stringResource(R.string.action_favorite_add)
    val sFavRemove = stringResource(R.string.action_favorite_remove)
    val sRename = stringResource(R.string.action_rename)
    val sDetails = stringResource(R.string.action_details)
    val sDelete = stringResource(R.string.action_delete)
    IOSActionSheet(
        visible = open,
        onDismiss = { open = false },
        cancelText = stringResource(R.string.action_cancel),
        title = file.name,
        actions = buildList {
            if (isExtractable) {
                add(IOSAction(sExtract) { vm.extractArchive(file) })
            }
            if (!file.isDirectory) {
                add(IOSAction(sEncrypt) { vm.encryptIntoVault(file) })
                add(IOSAction(sShare) { com.novafiles.app.util.Intents.share(context, file) })
            }
            add(
                IOSAction(
                    if (isFav) sFavRemove else sFavAdd,
                    selected = isFav,
                ) { vm.toggleFavorite(file) },
            )
            add(IOSAction(sRename) { vm.showDialog(BrowserDialog.Rename(file)) })
            add(IOSAction(sDetails) { vm.showDialog(BrowserDialog.Details(file)) })
            add(
                IOSAction(sDelete, destructive = true) {
                    vm.toggleSelect(file)
                    vm.showDialog(BrowserDialog.ConfirmDelete(1))
                },
            )
        },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    IOSAlertDialog(
        title = title,
        confirmText = stringResource(R.string.action_ok),
        onConfirm = { if (text.isNotBlank()) onConfirm(text.trim()) },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            IOSAlertTextField(value = text, onValueChange = { text = it })
        },
    )
}

@Composable
private fun DetailsDialog(file: File, onClose: () -> Unit) {
    IOSAlertDialog(
        title = stringResource(R.string.dialog_details_title),
        confirmText = stringResource(R.string.action_ok),
        onConfirm = onClose,
        content = {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "${stringResource(R.string.details_path)}: ${file.absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (file.isDirectory) {
                    Text(
                        "${stringResource(R.string.details_items)}: ${LocalBrowser.itemCount(file)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "${stringResource(R.string.details_size)}: ${LocalBrowser.humanSize(file.length())}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${stringResource(R.string.details_modified)}: ${LocalBrowser.formatDate(file.lastModified())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
