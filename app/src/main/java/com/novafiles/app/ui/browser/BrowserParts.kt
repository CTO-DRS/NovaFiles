@file:OptIn(ExperimentalFoundationApi::class)

package com.novafiles.app.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
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
import com.novafiles.app.core.ftp.FtpServerService
import com.novafiles.app.core.ftp.localIpv4
import com.novafiles.app.core.perms.StorageAccess
import com.novafiles.app.core.qr.QrPayloads
import com.novafiles.app.data.prefs.SettingsStore
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.core.theme.FolderAccentEngine
import com.novafiles.app.core.theme.IconPackEngine
import com.novafiles.app.core.theme.PaletteEngine
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSAlertTextField
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.ui.qr.QrDialog
import com.novafiles.app.util.TypeIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

/** حالة حوار QR السريع — رابط جاهز + ما إذا كنا من بدأ الخادم ليجري إيقافه عند الإغلاق */
data class QuickQrState(
    val title: String,
    val url: String,
    val stopOnClose: Boolean,
)

/**
 * تشغيل «إرسال عبر QR»: يشغّل خادم FTP بجذر مجلد العنصر المحدد
 * ويولّد رابطاً مباشراً للمسح من الجهاز الثاني.
 * يعمل من قائمة العنصر أو شريط التحديد في الوضعين الأحادي واللوحين.
 */
internal fun launchQuickQr(
    context: android.content.Context,
    scope: CoroutineScope,
    files: List<File>,
    onReady: (QuickQrState) -> Unit,
    onError: () -> Unit,
) {
    val target = files.firstOrNull() ?: return
    val root = target.parentFile ?: return
    if (!StorageAccess.hasAccess(context)) {
        onError()
        return
    }
    scope.launch {
        val prefs = SettingsStore(context)
        val port = runCatching { prefs.ftpPort.first() }.getOrDefault(2121).coerceIn(1024, 65535)
        val user = runCatching { prefs.ftpUser.first() }.getOrDefault("nova").ifBlank { "nova" }
        val pass = runCatching { prefs.ftpPass.first() }.getOrDefault("nova").ifBlank { "nova" }
        val ip = localIpv4()
        if (ip == null) {
            onError()
            return@launch
        }
        // إن كان الخادم يعمل أصلاً بمجلد آخر نتركه يعمل بعد الإغلاق
        val stopOnClose = !FtpServerService.state.value.running
        FtpServerService.start(context, root.absolutePath, port, user, pass)
        val title = if (files.size == 1) target.name else "${files.size}"
        onReady(
            QuickQrState(
                title = title,
                url = QrPayloads.ftpTargetUrl(ip, port, user, pass, root.absolutePath, target.absolutePath),
                stopOnClose = stopOnClose,
            ),
        )
    }
}

/** مضيف حوار QR السريع — يعرض الرمز ويتولى إيقاف الخادم إن بدأناه له */
@Composable
internal fun QuickQrHost(
    state: QuickQrState?,
    itemCount: Int,
    onDismiss: () -> Unit,
) {
    if (state == null) return
    val context = LocalContext.current
    QrDialog(
        title = state.title,
        url = state.url,
        hint = stringResource(R.string.qr_send_hint),
        onDismiss = {
            if (state.stopOnClose) FtpServerService.stop(context)
            onDismiss()
        },
    )
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
        msg.startsWith("queued:") -> snackbar.showSnackbar(
            context.getString(R.string.ops_queued_n, msg.substringAfter(":").toInt()),
        )
        msg.startsWith("rename_batch:") -> snackbar.showSnackbar(
            context.getString(R.string.batch_done_n, msg.substringAfter(":").toInt()),
        )
        msg == "rename_undo:ok" -> snackbar.showSnackbar(context.getString(R.string.batch_undo_done))
        msg == "zip:ok" -> snackbar.showSnackbar(context.getString(R.string.archive_created))
        msg == "unzip:ok" -> snackbar.showSnackbar(context.getString(R.string.archive_done_brief))
        msg.startsWith("copied:") -> snackbar.showSnackbar(
            context.getString(R.string.copied_n, msg.substringAfter(":").toInt())
        )
        msg.startsWith("cut:") -> snackbar.showSnackbar(
            context.getString(R.string.cut_n, msg.substringAfter(":").toInt())
        )
        msg == "rar_encrypted" -> snackbar.showSnackbar(context.getString(R.string.error_rar_encrypted))
        msg == "rar5_unsupported" -> snackbar.showSnackbar(context.getString(R.string.error_rar5_unsupported))
        msg == "qr_no_network" -> snackbar.showSnackbar(context.getString(R.string.ftp_no_wifi))
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

    // v0.17 — حزمة الأيقونات + لون المجلد الموروث
    val packRaw by context.novaContainer.settings.iconPack.collectAsState("")
    val accentsRaw by context.novaContainer.settings.folderAccents.collectAsState("")
    val pack = remember(packRaw) { if (packRaw.isBlank()) null else IconPackEngine(packRaw) }
    val accentEngine = remember(accentsRaw) { FolderAccentEngine.deserialize(accentsRaw) }
    val folderAccentId = if (file.isDirectory) accentEngine.resolve(file.absolutePath) else null
    val icon = pack?.resolve(file.name) { TypeIcons.builtinKey(type) }
        ?.let { key -> TypeIcons.iconForKey(key, TypeIcons.icon(type)) }
        ?: TypeIcons.icon(type)
    val iconColor = folderAccentId
        ?.let { id -> Color(PaletteEngine.family(PaletteEngine.preset(id), dark = false).accent) }
        ?: TypeIcons.color(type)

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
                    icon,
                    contentDescription = file.name,
                    tint = iconColor,
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
    val qrScope = androidx.compose.runtime.rememberCoroutineScope()
    IconButton(onClick = { open = true }, modifier = Modifier.size(if (compact) 30.dp else 40.dp)) {
        Icon(Icons.Filled.MoreVert, contentDescription = null, modifier = Modifier.size(if (compact) 18.dp else 22.dp))
    }
    val isFav = vm.favoritesPaths.collectAsState().value.contains(file.absolutePath)
    val isExtractable = !file.isDirectory && ArchiveEngine.supports(file.name)
    val sEncrypt = stringResource(R.string.action_encrypt_vault)
    val sShare = stringResource(R.string.action_share)
    val sQrSend = stringResource(R.string.qr_send_action)
    val sExtract = stringResource(R.string.action_extract)
    val sFavAdd = stringResource(R.string.action_favorite_add)
    val sFavRemove = stringResource(R.string.action_favorite_remove)
    val sRename = stringResource(R.string.action_rename)
    val sDetails = stringResource(R.string.action_details)
    val sDelete = stringResource(R.string.action_delete)
    val sFolderColor = stringResource(R.string.folder_color_action)
    var colorOpen by remember { mutableStateOf(false) }
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
                IOSAction(sQrSend) {
                    open = false
                    launchQuickQr(
                        context = context,
                        scope = qrScope,
                        files = listOf(file),
                        onReady = { vm.showQuickQr(it) },
                        onError = { vm.notify("qr_no_network") },
                    )
                },
            )
            add(
                IOSAction(
                    if (isFav) sFavRemove else sFavAdd,
                    selected = isFav,
                ) { vm.toggleFavorite(file) },
            )
            add(IOSAction(sRename) { vm.showDialog(BrowserDialog.Rename(file)) })
            add(IOSAction(sDetails) { vm.showDialog(BrowserDialog.Details(file)) })
            if (file.isDirectory) {
                add(IOSAction(sFolderColor) { colorOpen = true })
            }
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

/**
 * v0.17 — حوار لون المجلد: 12 لوحة مختبرة للتباين، مع وراثة أقرب سلف.
 * الحفظ عبر FolderAccentEngine في DataStore — يظهر فوراً في أيقونات المجلدات.
 */
@Composable
internal fun FolderColorDialog(file: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val accentsRaw by context.novaContainer.settings.folderAccents.collectAsState("")
    val engine = remember(accentsRaw) { FolderAccentEngine.deserialize(accentsRaw) }
    val current = engine.resolve(file.absolutePath)
    IOSAlertDialog(
        title = stringResource(R.string.folder_color_title),
        confirmText = stringResource(R.string.action_done),
        onConfirm = onClose,
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onClose,
        content = {
            Column {
                Text(
                    stringResource(R.string.folder_color_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalIOSPalette.current.secondaryLabel,
                )
                Spacer(Modifier.height(12.dp))
                PaletteEngine.PRESETS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { preset ->
                            val fam = PaletteEngine.family(preset, dark = false)
                            val selected = preset.id == current
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color(fam.accent))
                                    .border(
                                        width = if (selected) 3.dp else 0.dp,
                                        color = LocalIOSPalette.current.label,
                                        shape = RoundedCornerShape(18.dp),
                                    )
                                    .clickable {
                                        scope.launch {
                                            val fresh = FolderAccentEngine.deserialize(
                                                context.novaContainer.settings.folderAccents.first(),
                                            )
                                            fresh.set(file.absolutePath, preset.id)
                                            context.novaContainer.settings.setFolderAccents(fresh.serialize())
                                            onClose()
                                        }
                                    },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    scope.launch {
                        val fresh = FolderAccentEngine.deserialize(
                            context.novaContainer.settings.folderAccents.first(),
                        )
                        fresh.unset(file.absolutePath)
                        context.novaContainer.settings.setFolderAccents(fresh.serialize())
                        onClose()
                    }
                }) { Text(stringResource(R.string.folder_color_reset)) }
            }
        },
    )
}
