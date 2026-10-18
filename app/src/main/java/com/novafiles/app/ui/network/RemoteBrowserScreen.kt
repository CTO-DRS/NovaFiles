package com.novafiles.app.ui.network

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.network.RemoteEntry
import com.novafiles.app.core.network.RemoteFs
import com.novafiles.app.core.network.RemoteFsFactory
import com.novafiles.app.core.network.RemotePaths
import com.novafiles.app.core.perms.StorageAccess
import com.novafiles.app.data.db.ConnectionEntity
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.ui.ios.IOSAlertTextField
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSNavIconButton
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * متصفح خادم بعيد بنمط iOS — سرد مجلدات FTP/SMB والتنقل بينها،
 * تنزيل ملفات إلى Download/NovaFiles، رفع من الجهاز، مجلد جديد،
 * إعادة تسمية، وحذف — كل ذلك فوق واجهة RemoteFs الموحّدة.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RemoteBrowserScreen(
    connectionId: Long,
    onBack: () -> Unit,
) {
    val p = LocalIOSPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val dao = remember { context.novaContainer.db.connectionsDao() }

    var conn by remember { mutableStateOf<ConnectionEntity?>(null) }
    var fs by remember { mutableStateOf<RemoteFs?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }

    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var opError by remember { mutableStateOf<String?>(null) }

    // نقل جارٍ (تنزيل/رفع)
    var transferring by remember { mutableStateOf(false) }
    var transferBytes by remember { mutableStateOf(0L) }
    var transferName by remember { mutableStateOf("") }

    // حوارات
    var sheetEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameEntry by remember { mutableStateOf<RemoteEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<RemoteEntry?>(null) }
    var alertText by remember { mutableStateOf<String?>(null) }

    fun msg(res: Int, arg: String? = null) {
        scope.launch {
            snackbar.showSnackbar(
                if (arg == null) context.getString(res) else context.getString(res, arg),
            )
        }
    }

    fun reload(f: RemoteFs, target: String) {
        scope.launch {
            loading = true
            opError = null
            try {
                entries = f.list(target)
                path = target
            } catch (e: Exception) {
                opError = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    // ═══ فتح الجلسة مرة واحدة ═══
    LaunchedEffect(connectionId) {
        val c = dao.byId(connectionId)
        if (c == null) {
            openError = context.getString(R.string.net_conn_missing)
            loading = false
            return@LaunchedEffect
        }
        conn = c
        try {
            val opened = RemoteFsFactory.open(c)
            fs = opened
            val start = c.initialPath
            entries = opened.list(start)
            path = start
            loading = false
        } catch (e: Exception) {
            openError = e.message ?: e.javaClass.simpleName
            loading = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { fs?.close() }
    }

    fun goUp() {
        val f = fs ?: return
        if (path.isBlank()) {
            onBack()
        } else {
            transferName = ""
            reload(f, RemotePaths.parentOf(path))
        }
    }

    BackHandler { goUp() }

    fun downloadEntry(e: RemoteEntry) {
        val f = fs ?: return
        if (!StorageAccess.hasAccess(context)) {
            alertText = context.getString(R.string.net_need_storage)
            return
        }
        scope.launch {
            transferring = true
            transferBytes = 0
            transferName = e.name
            try {
                val dir = File("/storage/emulated/0/Download/NovaFiles")
                dir.mkdirs()
                var target = File(dir, e.name)
                var i = 1
                while (target.exists()) {
                    target = File(dir, "($i) ${e.name}")
                    i++
                }
                f.download(e.path, target) { written -> transferBytes = written }
                msg(R.string.net_download_done, target.name)
            } catch (e: Exception) {
                msg(R.string.net_op_failed, e.message ?: "")
            } finally {
                transferring = false
            }
        }
    }

    fun uploadTemp(temp: File) {
        val f = fs ?: return
        scope.launch {
            transferring = true
            transferBytes = 0
            transferName = temp.name
            try {
                f.upload(temp, path) { written -> transferBytes = written }
                msg(R.string.net_upload_done, temp.name)
                runCatching { temp.delete() }
                reload(f, path)
            } catch (e: Exception) {
                msg(R.string.net_op_failed, e.message ?: "")
            } finally {
                transferring = false
            }
        }
    }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "upload.bin"
            val temp = File(context.cacheDir, "nova_upload_$name")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                } ?: throw IllegalStateException(context.getString(R.string.net_op_failed, "open"))
                uploadTemp(temp)
            } catch (e: Exception) {
                runCatching { temp.delete() }
                msg(R.string.net_op_failed, e.message ?: "")
            }
        }
    }

    Scaffold(
        containerColor = p.groupedBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            IOSNavBar(
                title = conn?.name ?: stringResource(R.string.net_title),
                subtitle = if (path.isBlank()) {
                    val c = conn
                    if (c == null) ""
                    else if (c.protocol.equals("SMB", true)) "smb://${c.host}:${c.port}/${c.share}"
                    else "ftp://${c.host}:${c.port}"
                } else RemotePaths.nameOf(path),
                collapsed = true,
                onBack = { goUp() },
                actions = {
                    IOSNavIconButton(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.net_new_folder)) {
                        showNewFolder = true
                    }
                    IOSNavIconButton(Icons.Filled.UploadFile, contentDescription = stringResource(R.string.net_upload)) {
                        uploadLauncher.launch(arrayOf("*/*"))
                    }
                    IOSNavIconButton(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh)) {
                        fs?.let { reload(it, path) }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (transferring) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.net_op_running, transferName, formatBytes(transferBytes)),
                    style = TextStyle(fontSize = 13.sp),
                    color = p.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                openError != null -> ErrorCard(openError ?: "") { onBack() }
                opError != null -> ErrorCard(opError ?: "") { fs?.let { reload(it, path) } }
                entries.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.net_empty_dir),
                        style = TextStyle(fontSize = 15.sp),
                        color = p.secondaryLabel,
                    )
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.path }) { e ->
                        RemoteRow(
                            entry = e,
                            onTap = {
                                if (e.isDir) fs?.let { reload(it, e.path) }
                                else sheetEntry = e
                            },
                            onLongTap = { sheetEntry = e },
                        )
                    }
                }
            }
        }
    }

    // ═══ ورقة إجراءات عنصر ═══
    val se = sheetEntry
    if (se != null) {
        // النصوص تُحلّ في نطاق قابل للتركيب قبل buildList (stringResource لا يُستدعى داخل lambda عادية)
        val labelOpen = stringResource(R.string.net_open)
        val labelNewFolderHere = stringResource(R.string.net_new_folder_here)
        val labelDownload = stringResource(R.string.net_download)
        val labelRename = stringResource(R.string.net_rename)
        val labelDelete = stringResource(R.string.action_delete)
        IOSActionSheet(
            visible = true,
            onDismiss = { sheetEntry = null },
            cancelText = stringResource(R.string.action_cancel),
            title = se.name,
            actions = buildList {
                if (se.isDir) {
                    add(IOSAction(labelOpen) { fs?.let { reload(it, se.path) } })
                    add(IOSAction(labelNewFolderHere) { showNewFolder = true })
                } else {
                    add(IOSAction(labelDownload) { downloadEntry(se) })
                }
                add(IOSAction(labelRename) { renameEntry = se })
                add(
                    IOSAction(labelDelete, destructive = true) {
                        confirmDelete = se
                    },
                )
            },
        )
    }

    // ═══ حوار مجلد جديد ═══
    if (showNewFolder) {
        var folderName by remember(showNewFolder) { mutableStateOf("") }
        IOSAlertDialog(
            title = stringResource(R.string.net_new_folder_title),
            confirmText = stringResource(R.string.net_create),
            onConfirm = {
                val n = folderName.trim()
                showNewFolder = false
                if (n.isNotEmpty()) {
                    val f = fs
                    scope.launch {
                        if (f != null) {
                            try {
                                f.mkdir(path, n)
                                reload(f, path)
                            } catch (e: Exception) {
                                msg(R.string.net_op_failed, e.message ?: "")
                            }
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showNewFolder = false },
            content = {
                IOSAlertTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                )
            },
        )
    }

    // ═══ حوار إعادة تسمية ═══
    val re = renameEntry
    if (re != null) {
        var newName by remember(re) { mutableStateOf(re.name) }
        IOSAlertDialog(
            title = stringResource(R.string.net_rename_title),
            confirmText = stringResource(R.string.net_rename_apply),
            onConfirm = {
                val n = newName.trim()
                renameEntry = null
                val f = fs
                if (n.isNotEmpty() && n != re.name && f != null) {
                    scope.launch {
                        try {
                            f.rename(re.path, n)
                            reload(f, path)
                        } catch (e: Exception) {
                            msg(R.string.net_op_failed, e.message ?: "")
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { renameEntry = null },
            content = {
                IOSAlertTextField(
                    value = newName,
                    onValueChange = { newName = it },
                )
            },
        )
    }

    // ═══ تأكيد حذف عنصر بعيد ═══
    val de = confirmDelete
    if (de != null) {
        IOSAlertDialog(
            title = stringResource(R.string.net_delete_remote_title),
            message = stringResource(R.string.net_delete_remote_body, de.name),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                confirmDelete = null
                sheetEntry = null
                val f = fs
                scope.launch {
                    if (f != null) {
                        try {
                            f.delete(de.path, de.isDir)
                            reload(f, path)
                        } catch (e: Exception) {
                            msg(R.string.net_op_failed, e.message ?: "")
                        }
                    }
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { confirmDelete = null },
            destructive = true,
        )
    }

    // ═══ تنبيه عام ═══
    if (alertText != null) {
        IOSAlertDialog(
            title = stringResource(R.string.net_title),
            message = alertText,
            confirmText = stringResource(R.string.action_ok),
            onConfirm = { alertText = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RemoteRow(
    entry: RemoteEntry,
    onTap: () -> Unit,
    onLongTap: () -> Unit,
) {
    val p = LocalIOSPalette.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onTap, onLongClick = onLongTap)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (icon, tint) = remoteIcon(entry.name, entry.isDir)
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = TextStyle(fontSize = 17.sp),
                    color = p.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!entry.isDir) {
                    Text(
                        buildString {
                            append(formatBytes(entry.size))
                            if (entry.lastModified > 0) {
                                append(" · ")
                                append(SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(entry.lastModified)))
                            }
                        },
                        style = TextStyle(fontSize = 13.sp),
                        color = p.secondaryLabel,
                        maxLines = 1,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .padding(start = 54.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(p.separator),
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    val p = LocalIOSPalette.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text(
            stringResource(R.string.net_error_title),
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            color = p.destructive,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = TextStyle(fontSize = 13.sp),
            color = p.secondaryLabel,
            maxLines = 4,
        )
        Spacer(Modifier.height(16.dp))
        com.novafiles.app.ui.ios.IOSFilledButton(
            text = stringResource(R.string.net_retry),
            onClick = onRetry,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

private fun remoteIcon(name: String, isDir: Boolean): Pair<ImageVector, Color> {
    if (isDir) return Icons.Filled.Folder to Color(0xFF34AADC)
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> Icons.Filled.Image to Color(0xFF34C759)
        "mp4", "mkv", "mov", "avi", "webm", "3gp" -> Icons.Filled.Videocam to Color(0xFFFF9500)
        "mp3", "wav", "flac", "ogg", "m4a", "aac" -> Icons.Filled.MusicNote to Color(0xFFFF2D55)
        "zip", "rar", "7z", "tar", "gz" -> Icons.Filled.FolderZip to Color(0xFFAF52DE)
        "pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "md" -> Icons.Filled.Description to Color(0xFF007AFF)
        else -> Icons.Filled.InsertDriveFile to Color(0xFF8E8E93)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

private fun queryDisplayName(context: android.content.Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cur.moveToFirst()) cur.getString(idx) else null
        }
    }.getOrNull()
