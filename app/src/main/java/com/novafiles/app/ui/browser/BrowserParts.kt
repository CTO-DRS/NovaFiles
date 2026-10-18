@file:OptIn(ExperimentalFoundationApi::class)

package com.novafiles.app.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import coil.compose.AsyncImage
import com.novafiles.app.R
import com.novafiles.app.core.archive.ArchiveEngine
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.core.fs.LocalBrowser
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSAlertTextField
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.util.TypeIcons
import java.io.File

/**
 * مكونات المتصفح المشتركة بين الشاشة الأحادية (الهاتف) واللوحتان (الجهاز اللوحي).
 * كلها internal — تُستخدم من BrowserScreen و BrowserPane.
 */

/** لافتة طلب صلاحية «كل الملفات» */
@Composable
internal fun PermissionBanner(onGrant: () -> Unit) {
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

/** ترجمة رسائل ViewModel الداخلية إلى نصوص Snackbar — مشتركة بين الوضعين */
suspend fun showBrowserMessage(snackbar: SnackbarHostState, context: android.content.Context, msg: String) {
    when {
        msg == "trash:ok" -> snackbar.showSnackbar(context.getString(R.string.action_delete_done))
        msg == "paste:ok" -> snackbar.showSnackbar(context.getString(R.string.op_done))
        msg == "restore:ok" -> snackbar.showSnackbar(context.getString(R.string.op_done))
        msg == "mkdir:ok" || msg == "rename:ok" || msg == "vault:ok" ->
            snackbar.showSnackbar(context.getString(R.string.op_done))
        msg == "move:ok" -> snackbar.showSnackbar(context.getString(R.string.op_done))
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
}

@Composable
internal fun ListFileItem(
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

@Composable
internal fun GridFileItem(
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
internal fun ItemMenu(file: File, vm: BrowserViewModel, context: android.content.Context, compact: Boolean = false) {
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
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
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
internal fun DetailsDialog(file: File, onClose: () -> Unit) {
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
