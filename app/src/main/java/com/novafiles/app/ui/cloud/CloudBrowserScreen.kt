package com.novafiles.app.ui.cloud

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.cloudClientId
import com.novafiles.app.core.cloud.CloudEntry
import com.novafiles.app.core.provider.ProviderEntry
import com.novafiles.app.core.provider.StorageProvider
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSAlertTextField
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.16 — متصفح حساب سحابي: نفس تجربة RemoteBrowser لكن فوق StorageProvider
 * السحابي — سرد، تنقل، تنزيل إلى Download/NovaFiles، رفع، مجلد جديد،
 * إعادة تسمية، حذف. (يُبنى على واجهة الموحّد فتعمل Drive/Dropbox/OneDrive بلا تمييز)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CloudBrowserScreen(
    accountId: Long,
    onBack: () -> Unit,
) {
    val p = LocalIOSPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val container = context.novaContainer

    var provider by remember { mutableStateOf<StorageProvider?>(null) }
    var accountName by remember { mutableStateOf("") }
    var openError by remember { mutableStateOf<String?>(null) }

    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<ProviderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var opError by remember { mutableStateOf<String?>(null) }

    // نقل جارٍ
    var transferring by remember { mutableStateOf(false) }
    var transferBytes by remember { mutableStateOf(0L) }
    var transferName by remember { mutableStateOf("") }

    // حوارات
    var showNewFolder by remember { mutableStateOf(false) }
    var renameEntry by remember { mutableStateOf<ProviderEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<ProviderEntry?>(null) }
    var alertText by remember { mutableStateOf<String?>(null) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(snackbarMsg) {
        snackbarMsg?.let {
            snackbar.showSnackbar(it)
            snackbarMsg = null
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val prov = provider ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            transferring = true
            try {
                withContext(Dispatchers.IO) {
                    val temp = File.createTempFile("up", ".bin", context.cacheDir)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { input.copyTo(it) }
                    }
                    val name = queryDisplayName(context, uri) ?: temp.name
                    prov.writeFrom(temp, path) {}
                    temp.delete()
                }
                entries = withContext(Dispatchers.IO) { prov.list(path) }
            } catch (e: Exception) {
                opError = e.message ?: e.javaClass.simpleName
            } finally {
                transferring = false
            }
        }
    }

    // ═══ بناء المزوّد عند الفتح ═══
    LaunchedEffect(accountId) {
        try {
            val account = withContext(Dispatchers.IO) {
                container.db.cloudAccountsDao().byId(accountId)
            } ?: throw IllegalStateException("account missing")
            accountName = account.displayName
            val prov = container.cloudAccounts.providerFor(account, context.cloudClientId(account.provider))
            provider = prov
            loading = false
        } catch (e: Exception) {
            openError = e.message ?: e.javaClass.simpleName
            loading = false
        }
    }

    // ═══ سرد المسار الحالي ═══
    LaunchedEffect(provider, path) {
        val prov = provider ?: return@LaunchedEffect
        loading = true
        try {
            entries = withContext(Dispatchers.IO) { prov.list(path) }
        } catch (e: Exception) {
            opError = e.message
        } finally {
            loading = false
        }
    }

    // رجوع للخلف عبر نظام التنقل
    BackHandler(enabled = path.isNotEmpty()) { path = "" }

    Scaffold(
        containerColor = p.groupedBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            IOSNavBar(
                title = accountName,
                subtitle = breadcrumb(path),
                onBack = {
                    if (path.isNotEmpty()) path = com.novafiles.app.core.cloud.RemotePathUtil.parentOf(path)
                    else onBack()
                },
                actions = {
                    IOSNavIconButtonAction(Icons.Filled.CreateNewFolder) { showNewFolder = true }
                    IOSNavIconButtonAction(Icons.Filled.UploadFile) {
                        picker.launch(arrayOf("*/*"))
                    }
                    IOSNavIconButtonAction(Icons.Filled.Refresh) { path = path } // يعيد السرد
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loading && entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.empty_folder), color = p.secondaryLabel, fontSize = 15.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    items(entries, key = { it.path }) { entry ->
                        CloudEntryRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDir) {
                                    path = entry.path
                                } else {
                                    scope.launch {
                                        downloadEntry(
                                            provider, entry, context,
                                            setTransfer = { transferring = it },
                                            setBytes = { transferBytes = it },
                                            setName = { transferName = it },
                                            onDone = { msg -> snackbarMsg = msg },
                                            onError = { opError = it },
                                        )
                                    }
                                }
                            },
                            onLongClick = { /* قائمة الأفعال عبر الحوارات */ },
                            onRename = { renameEntry = entry },
                            onDelete = { confirmDelete = entry },
                        )
                    }
                    item {
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }

            if (transferring) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        stringResource(R.string.cloud_transferring, transferName),
                        fontSize = 13.sp, color = p.secondaryLabel,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (transferBytes > 0) 1f else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            opError?.let { err ->
                AlertDialog(
                    onDismissRequest = { opError = null },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { opError = null }) { Text("OK") }
                    },
                    title = { Text(stringResource(R.string.cloud_error)) },
                    text = { Text(err.take(300)) },
                )
            }
        }
    }

    // ═══ حوار مجلد جديد ═══
    if (showNewFolder) {
        var folderName by remember { mutableStateOf("") }
        IOSAlertDialog(
            title = stringResource(R.string.action_new_folder),
            confirmText = stringResource(R.string.action_create),
            onConfirm = {
                showNewFolder = false
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            provider?.mkdir(path, folderName.ifBlank { "NovaFolder" })
                        }
                        entries = withContext(Dispatchers.IO) { provider?.list(path) ?: emptyList() }
                    } catch (e: Exception) {
                        opError = e.message
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showNewFolder = false },
            content = { IOSAlertTextField(value = folderName, onValueChange = { folderName = it }) },
        )
    }

    // ═══ حوار إعادة التسمية ═══
    renameEntry?.let { entry ->
        var newName by remember(entry.path) { mutableStateOf(entry.name) }
        IOSAlertDialog(
            title = stringResource(R.string.action_rename),
            confirmText = stringResource(R.string.action_rename),
            onConfirm = {
                renameEntry = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { provider?.rename(entry.path, newName) }
                        entries = withContext(Dispatchers.IO) { provider?.list(path) ?: emptyList() }
                    } catch (e: Exception) {
                        opError = e.message
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { renameEntry = null },
            content = { IOSAlertTextField(value = newName, onValueChange = { newName = it }) },
        )
    }

    // ═══ تأكيد الحذف ═══
    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.cloud_delete_title)) },
            text = { Text(stringResource(R.string.cloud_delete_body, entry.name)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { provider?.delete(entry.path, entry.isDir) }
                            entries = withContext(Dispatchers.IO) { provider?.list(path) ?: emptyList() }
                        } catch (e: Exception) {
                            opError = e.message
                        }
                    }
                }) { Text(stringResource(R.string.action_delete), color = p.destructive) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // ═══ خطأ فتح الحساب ═══
    openError?.let { err ->
        AlertDialog(
            onDismissRequest = { openError = null; onBack() },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { openError = null; onBack() }) { Text("OK") }
            },
            title = { Text(stringResource(R.string.cloud_error)) },
            text = { Text(err.take(300)) },
        )
    }
}

/** تنزيل عنصر إلى Download/NovaFiles عبر المزوّد الموحد */
private suspend fun downloadEntry(
    provider: StorageProvider?,
    entry: ProviderEntry,
    context: android.content.Context,
    setTransfer: (Boolean) -> Unit,
    setBytes: (Long) -> Unit,
    setName: (String) -> Unit,
    onDone: (String) -> Unit,
    onError: (String?) -> Unit,
) {
    val prov = provider ?: return
    setTransfer(true)
    setName(entry.name)
    setBytes(0)
    try {
        withContext(Dispatchers.IO) {
            val dir = File("/storage/emulated/0/Download/NovaFiles")
            dir.mkdirs()
            val target = File(dir, entry.name)
            prov.readTo(entry.path, target) { bytes -> setBytes(bytes) }
        }
        onDone(context.getString(R.string.cloud_downloaded, entry.name))
    } catch (e: Exception) {
        onError(e.message ?: e.javaClass.simpleName)
    } finally {
        setTransfer(false)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudEntryRow(
    entry: ProviderEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalIOSPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entryIcon(entry),
            contentDescription = null,
            tint = if (entry.isDir) p.accent else p.secondaryLabel,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 16.sp,
                color = p.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (entry.isDir) stringResource(R.string.cloud_item_folder)
                else formatSize(entry.size) + " · " + formatDate(entry.lastModified),
                fontSize = 12.sp,
                color = p.secondaryLabel,
            )
        }
        Text(
            stringResource(R.string.cloud_long_press_hint),
            fontSize = 11.sp,
            color = p.tertiaryLabel,
        )
    }
}

private fun entryIcon(entry: ProviderEntry) = when {
    entry.isDir -> Icons.Filled.Folder
    entry.name.endsWith(".jpg") || entry.name.endsWith(".png") || entry.name.endsWith(".jpeg") ||
        entry.name.endsWith(".webp") || entry.name.endsWith(".gif") || entry.name.endsWith(".heic") -> Icons.Filled.Image
    entry.name.endsWith(".mp4") || entry.name.endsWith(".mov") || entry.name.endsWith(".webm") ||
        entry.name.endsWith(".3gp") -> Icons.Filled.Videocam
    entry.name.endsWith(".mp3") || entry.name.endsWith(".m4a") || entry.name.endsWith(".wav") ||
        entry.name.endsWith(".ogg") || entry.name.endsWith(".flac") -> Icons.Filled.MusicNote
    entry.name.endsWith(".txt") || entry.name.endsWith(".md") || entry.name.endsWith(".kt") ||
        entry.name.endsWith(".json") || entry.name.endsWith(".pdf") -> Icons.Filled.Description
    else -> Icons.Filled.InsertDriveFile
}

@Composable
private fun IOSNavIconButtonAction(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val p = LocalIOSPalette.current
    androidx.compose.material3.IconButton(onClick = onClick) {
        Icon(icon, contentDescription = null, tint = p.accent, modifier = Modifier.size(22.dp))
    }
}

private fun breadcrumb(path: String): String =
    if (path.isBlank()) "/..." else path

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

private fun formatDate(ts: Long): String =
    if (ts <= 0) "" else SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(ts))

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
