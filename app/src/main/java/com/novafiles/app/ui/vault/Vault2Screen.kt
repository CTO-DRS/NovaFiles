@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.vault

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.novafiles.app.R
import com.novafiles.app.core.fs.LocalBrowser
import com.novafiles.app.core.vault2.DecoyOutcome
import com.novafiles.app.core.vault2.LocalFileFs
import com.novafiles.app.core.vault2.Vault2Repository
import com.novafiles.app.core.vault2.VaultEntry
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSMiniField
import com.novafiles.app.ui.ios.IOSNavIconButton
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.launch

/**
 * الخزنة 2.0 (v0.15): مجلدات مشفرة بشجرة كاملة، مفاتيح HKDF لكل مجلد،
 * تشفير متدفق NVF2، عبارة استرجاع إلزامية، وضع تنكّر (حاسبة)، سجل تسلل،
 * إخفاء تلقائي بعد 30 ثانية، وبوابة حيوية للعمليات المدمرة.
 *
 * الحالات: SETUP (أول تشغيل) → LOCKED → UNLOCKED (متصفح).
 */
@Composable
fun Vault2Screen() {
    val context = LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val palette = LocalIOSPalette.current

    var phase by remember { mutableStateOf(Phase.LOADING) }
    var repo by remember { mutableStateOf<Vault2Repository?>(null) }
    var currentFolder by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var showTools by remember { mutableStateOf(false) }
    var browserTick by remember { mutableStateOf(0) }
    var importTarget by remember { mutableStateOf("") }

    // الإخفاء التلقائي — مراقب دورة حياة النشاط
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> container.autoHide.onLeave(System.currentTimeMillis())
                Lifecycle.Event.ON_RESUME -> {
                    if (container.autoHide.onEnter(System.currentTimeMillis()) && phase == Phase.UNLOCKED) {
                        runCatching { repo?.close() }
                        repo = null
                        phase = Phase.LOCKED
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    LaunchedEffect(Unit) {
        phase = if (!container.vault2.isPinSet()) Phase.SETUP else Phase.LOCKED
    }

    // منتقي الاستيراد — يعمل على المستودع الحالي والمجلد الحالي
    val currentRepo: () -> Vault2Repository? = { repo }
    val pickToImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val repository = currentRepo()
        if (uri != null && repository != null) {
            scope.launch {
                runCatching {
                    val display = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                    } ?: "file_${System.currentTimeMillis()}"
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        repository.importFile(importTarget, display.replace('/', '_'), input)
                    } ?: error("read failed")
                }.onSuccess { message = context.getString(R.string.vault_encrypted_done) }
                    .onFailure { message = it.message ?: context.getString(R.string.op_failed) }
                browserTick++
            }
        }
    }

    fun lock() {
        runCatching { repo?.close() }
        repo = null
        currentFolder = ""
        phase = Phase.LOCKED
    }

    Scaffold(
        containerColor = palette.groupedBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (phase == Phase.UNLOCKED) {
                IOSNavBar(
                    title = stringResource(R.string.nav_vault),
                    actions = {
                        IOSNavIconButton(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.action_new_folder)) {
                            scope.launch {
                                repo?.createFolder(currentFolder, nextFolderName(repo!!, currentFolder))
                                browserTick++
                            }
                        }
                        IOSNavIconButton(Icons.Filled.FileUpload, contentDescription = stringResource(R.string.action_encrypt_vault)) {
                            importTarget = currentFolder
                            pickToImport.launch(arrayOf("*/*"))
                        }
                        IOSNavIconButton(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.action_details)) {
                            showTools = true
                        }
                        IOSNavIconButton(Icons.Filled.Lock, contentDescription = stringResource(R.string.action_lock)) { lock() }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (phase) {
                Phase.LOADING -> Unit
                Phase.SETUP -> SetupFlow(
                    onDone = { repository ->
                        repo = repository
                        currentFolder = ""
                        phase = Phase.UNLOCKED
                    },
                )
                Phase.LOCKED -> LockScreen(
                    onUnlocked = { repository ->
                        repo = repository
                        container.autoHide.unhide()
                        currentFolder = ""
                        phase = Phase.UNLOCKED
                    },
                )
                Phase.UNLOCKED -> {
                    val repository = repo
                    if (repository != null) {
                        VaultBrowser(
                            repository = repository,
                            currentFolder = currentFolder,
                            onFolderChange = { currentFolder = it },
                            tick = browserTick,
                            onMove = { browserTick++ },
                            onMessage = { message = it },
                        )
                    }
                }
            }
        }
    }

    if (phase == Phase.UNLOCKED && showTools && repo != null) {
        VaultToolsSheet(
            repository = repo!!,
            onDismiss = { showTools = false },
            onMessage = { message = it },
            onWiped = { lock() },
        )
    }
}

private enum class Phase { LOADING, SETUP, LOCKED, UNLOCKED }

private fun nextFolderName(repo: Vault2Repository, parentId: String): String {
    val existing = repo.index.children(parentId).map { it.name }.toSet()
    var i = 1
    while (true) {
        val candidate = "مجلد $i"
        if (candidate !in existing) return candidate
        i++
    }
}

// ---------- مسار الإعداد الأول: PIN → عبارة استرجاع إلزامية → تأكيد ----------

@Composable
private fun SetupFlow(onDone: (Vault2Repository) -> Unit) {
    val context = LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf<List<String>>(emptyList()) }
    var quizIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
    var quizAnswers by remember { mutableStateOf(mapOf<Int, String>()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when (step) {
            0 -> {
                Text(stringResource(R.string.vault_set_pin), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.v2_setup_lede),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                IOSMiniField(value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) }, hint = stringResource(R.string.vault_pin_hint), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                IOSMiniField(value = confirm, onValueChange = { confirm = it.filter { c -> c.isDigit() }.take(8) }, hint = stringResource(R.string.vault_confirm_pin), modifier = Modifier.fillMaxWidth())
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                IOSFilledButton(text = stringResource(R.string.action_ok), onClick = {
                    when {
                        pin.length < 4 -> error = context.getString(R.string.vault_pin_too_short)
                        pin != confirm -> error = context.getString(R.string.vault_pin_mismatch)
                        else -> scope.launch {
                            container.vault2.setRealPin(pin)
                            phrase = container.vault2.newRecoveryPhrase()
                            quizIndices = (0 until 12).shuffled().take(3)
                            step = 1
                        }
                    }
                })
            }
            1 -> {
                Text(stringResource(R.string.v2_recovery_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.v2_recovery_must),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                phrase.chunked(3).forEachIndexed { rowIdx, rowWords ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowWords.forEachIndexed { colIdx, word ->
                            val index = rowIdx * 3 + colIdx + 1
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(10.dp),
                            ) {
                                Text("$index. $word", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                IOSFilledButton(text = stringResource(R.string.v2_recovery_wrote_down), onClick = { step = 2 })
            }
            2 -> {
                Text(stringResource(R.string.v2_recovery_confirm_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.v2_recovery_confirm_lede),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                quizIndices.sorted().forEach { idx ->
                    Text(stringResource(R.string.v2_recovery_word_n, idx + 1), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    IOSMiniField(
                        value = quizAnswers[idx] ?: "",
                        onValueChange = { quizAnswers = quizAnswers + (idx to it) },
                        hint = stringResource(R.string.v2_recovery_confirm_hint),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                IOSFilledButton(text = stringResource(R.string.action_ok), onClick = {
                    val ok = quizIndices.all { idx ->
                        (quizAnswers[idx] ?: "").trim().lowercase() == phrase[idx].lowercase()
                    }
                    if (ok) {
                        scope.launch { onDone(container.vault2.openRepository()) }
                    } else {
                        error = context.getString(R.string.v2_recovery_wrong)
                    }
                })
            }
        }
    }
}

// ---------- شاشة القفل: PIN أو بصمة + قيد التسلل + التنكّر ----------

@Composable
private fun LockScreen(onUnlocked: (Vault2Repository) -> Unit) {
    val context = LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    val activity = context as? AppCompatActivity
    val canBiometric = remember {
        activity != null && BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showBiometric() {
        val act = activity ?: return
        val prompt = BiometricPrompt(
            act,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    scope.launch { onUnlocked(container.vault2.openRepository()) }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.vault_locked_title))
            .setNegativeButtonText(context.getString(R.string.action_cancel))
            .build()
        prompt.authenticate(info)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.vault_locked_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        IOSMiniField(
            value = pin,
            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); wrong = false },
            hint = stringResource(R.string.vault_pin_hint),
            modifier = Modifier.fillMaxWidth(),
        )
        if (wrong) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.vault_wrong_pin), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        IOSFilledButton(
            text = stringResource(R.string.vault_unlock),
            onClick = {
                scope.launch {
                    val log = runCatching {
                        container.vault2.openIntrusionLog(LocalFileFs(container.vault2.vaultRoot()))
                    }.getOrNull()
                    when (val outcome = container.vault2.evaluateEntry(pin, log, container.vault2.isIntrusionPhotoEnabled())) {
                        DecoyOutcome.Real -> onUnlocked(container.vault2.openRepository())
                        DecoyOutcome.Decoy -> onUnlocked(container.vault2.openDecoyRepository())
                        is DecoyOutcome.Fail -> wrong = true
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (canBiometric) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.vault_unlock_biometric),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.clickable { showBiometric() }.padding(8.dp),
            )
        }
    }
}

// ---------- المتصفح: شجرة المجلدات المشفرة ----------

@Composable
private fun VaultBrowser(
    repository: Vault2Repository,
    currentFolder: String,
    onFolderChange: (String) -> Unit,
    tick: Int,
    onMove: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember(currentFolder, tick) { mutableStateOf(repository.index.children(currentFolder)) }
    var moveEntry by remember { mutableStateOf<VaultEntry?>(null) }
    var renameEntry by remember { mutableStateOf<VaultEntry?>(null) }

    val exportEntry = remember { mutableStateOf<VaultEntry?>(null) }
    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val entry = exportEntry.value
        if (uri != null && entry != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        repository.exportFile(entry.id, out)
                    } ?: error("open failed")
                }.onSuccess { onMessage(context.getString(R.string.vault_decrypted_done)) }
                    .onFailure { onMessage(it.message ?: context.getString(R.string.op_failed)) }
            }
        }
        exportEntry.value = null
    }

    Column(Modifier.fillMaxSize()) {
        // مسار التنقل
        val crumbs = repository.index.pathOf(currentFolder)
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            item {
                Text(
                    stringResource(R.string.v2_root),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { onFolderChange("") }.padding(6.dp),
                )
            }
            items(crumbs) { crumb ->
                Text(" / ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    crumb.name,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.clickable { onFolderChange(crumb.id) }.padding(6.dp),
                )
            }
        }

        Text(
            stringResource(R.string.v2_files_count, repository.fileCount(), LocalBrowser.humanSize(repository.totalSize())),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_vault), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (entry.isFolder) Icons.Filled.Folder else Icons.Filled.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (entry.isFolder) onFolderChange(entry.id)
                                    else {
                                        exportEntry.value = entry
                                        saveExport.launch(entry.name)
                                    }
                                },
                        ) {
                            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (entry.isFolder) stringResource(R.string.v2_folder)
                                else LocalBrowser.humanSize(entry.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { moveEntry = entry }) {
                            Icon(Icons.Filled.DriveFileMove, contentDescription = stringResource(R.string.action_move))
                        }
                        IconButton(onClick = { renameEntry = entry }) {
                            Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = stringResource(R.string.action_rename))
                        }
                        IconButton(onClick = {
                            scope.launch {
                                runCatching {
                                    val deleted = repository.deleteRecursive(entry.id)
                                    onMessage(context.getString(R.string.v2_deleted_count, deleted))
                                }.onFailure { onMessage(it.message ?: context.getString(R.string.op_failed)) }
                                onMove()
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    moveEntry?.let { entry ->
        MoveDialog(
            repository = repository,
            entry = entry,
            onDismiss = { moveEntry = null },
            onMoved = { targetId ->
                moveEntry = null
                scope.launch {
                    runCatching { repository.move(entry.id, targetId) }
                        .onFailure { onMessage(it.message ?: context.getString(R.string.op_failed)) }
                    onMove()
                }
            },
        )
    }
    renameEntry?.let { entry ->
        RenameDialog(
            current = entry.name,
            onDismiss = { renameEntry = null },
            onRename = { newName ->
                renameEntry = null
                scope.launch {
                    runCatching { repository.rename(entry.id, newName) }
                        .onFailure { onMessage(it.message ?: context.getString(R.string.op_failed)) }
                    onMove()
                }
            },
        )
    }
}
