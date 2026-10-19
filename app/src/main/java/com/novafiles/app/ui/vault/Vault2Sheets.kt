@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.vault

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.novafiles.app.R
import com.novafiles.app.core.vault.VaultManager
import com.novafiles.app.core.vault2.IntrusionLog
import com.novafiles.app.core.vault2.LocalFileFs
import com.novafiles.app.core.vault2.RecoveryBackup
import com.novafiles.app.core.vault2.RecoveryPhrase
import com.novafiles.app.core.vault2.SecureWipe
import com.novafiles.app.core.vault2.Vault2Repository
import com.novafiles.app.core.vault2.VaultEntry
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSMiniField
import com.novafiles.app.ui.ios.IOSSwitch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * ورقة أدوات الخزنة 2.0: النسخ الاحتياطي للاسترجاع، سجل التسلل، إعدادات
 * التنكّر/الإخفاء التلقائي/صورة التسلل، هجرة ملفات الخزنة القديمة (v0.3)،
 * والمسح الآمن الكامل خلف بوابة حيوية.
 */
@Composable
fun VaultToolsSheet(
    repository: Vault2Repository,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
    onWiped: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()

    var showRecoveryWords by remember { mutableStateOf(false) }
    var showPhraseConfirm by remember { mutableStateOf(false) }
    var recoveryWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var showIntrusion by remember { mutableStateOf(false) }
    var showRestore by remember { mutableStateOf(false) }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var restorePhrase by remember { mutableStateOf("") }
    var showDecoyPin by remember { mutableStateOf(false) }
    var decoyPin by remember { mutableStateOf("") }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var autoHideOn by remember { mutableStateOf(true) }
    var photoOn by remember { mutableStateOf(false) }
    var decoyOn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        autoHideOn = container.vault2.isAutoHideEnabled()
        photoOn = container.vault2.isIntrusionPhotoEnabled()
        decoyOn = container.vault2.isDecoyEnabled()
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { container.vault2.setIntrusionPhotoEnabled(granted) }
        if (granted) photoOn = true else onMessage(context.getString(R.string.v2_photo_needs_permission))
    }

    // تصدير النسخة الاحتياطية
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val words = container.vault2.newRecoveryPhrase()
                    val backup = container.vault2.buildBackup(repository, words)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(backup) } ?: error("write failed")
                    words
                }.onSuccess { words ->
                    recoveryWords = words
                    showPhraseConfirm = true
                }.onFailure { onMessage(it.message ?: context.getString(R.string.op_failed)) }
            }
        }
    }

    // استيراد نسخة احتياطية
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()?.let { restoreBytes = it; showRestore = true }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            IOSGroupHeader(stringResource(R.string.v2_recovery_section))
            IOSGroupCard {
                IOSListRow(
                    title = stringResource(R.string.v2_backup_export),
                    subtitle = stringResource(R.string.v2_backup_export_hint),
                    showChevron = true,
                    showSeparator = true,
                ) { exportBackup.launch("nova-vault-backup.nvbr") }
                IOSListRow(
                    title = stringResource(R.string.v2_backup_restore),
                    subtitle = stringResource(R.string.v2_backup_restore_hint),
                    showChevron = true,
                    showSeparator = false,
                ) { pickBackup.launch(arrayOf("*/*")) }
            }

            IOSGroupHeader(stringResource(R.string.v2_privacy_section))
            IOSGroupCard {
                IOSListRow(
                    title = stringResource(R.string.v2_auto_hide),
                    subtitle = stringResource(R.string.v2_auto_hide_hint),
                    showSeparator = true,
                    trailing = { IOSSwitch(checked = autoHideOn, onCheckedChange = {
                        autoHideOn = it
                        container.autoHide.enabled = it
                        scope.launch { container.vault2.setAutoHideEnabled(it) }
                    }) },
                )
                IOSListRow(
                    title = stringResource(R.string.v2_intrusion_photo),
                    subtitle = stringResource(R.string.v2_intrusion_photo_hint),
                    showSeparator = true,
                    trailing = { IOSSwitch(checked = photoOn, onCheckedChange = {
                        if (it) cameraPermission.launch(android.Manifest.permission.CAMERA)
                        else {
                            photoOn = false
                            scope.launch { container.vault2.setIntrusionPhotoEnabled(false) }
                        }
                    }) },
                )
                IOSListRow(
                    title = stringResource(R.string.v2_decoy_mode),
                    subtitle = stringResource(R.string.v2_decoy_mode_hint),
                    showSeparator = false,
                    trailing = { IOSSwitch(checked = decoyOn, onCheckedChange = { enabled ->
                        scope.launch {
                            if (enabled && !container.vault2.isPinSet()) {
                                onMessage(context.getString(R.string.v2_decoy_needs_pin))
                                return@launch
                            }
                            if (enabled) {
                                decoyPin = ""
                                showDecoyPin = true
                            } else {
                                container.vault2.setDecoyEnabled(false)
                                decoyOn = false
                            }
                        }
                    }) },
                )
                IOSListRow(
                    title = stringResource(R.string.v2_intrusion_log),
                    subtitle = stringResource(R.string.v2_intrusion_log_hint),
                    showChevron = true,
                    showSeparator = false,
                ) { showIntrusion = true }
            }

            IOSGroupHeader(stringResource(R.string.v2_maintenance_section))
            IOSGroupCard {
                IOSListRow(
                    title = stringResource(R.string.v2_legacy_import),
                    subtitle = stringResource(R.string.v2_legacy_import_hint),
                    showChevron = true,
                    showSeparator = true,
                ) {
                    scope.launch {
                        val migrated = migrateLegacyVault(container.vault, repository)
                        onMessage(context.getString(R.string.v2_legacy_done, migrated))
                    }
                }
                IOSListRow(
                    title = stringResource(R.string.v2_wipe_all),
                    subtitle = stringResource(R.string.v2_wipe_all_hint),
                    destructive = true,
                    showSeparator = false,
                ) { showWipeConfirm = true }
            }
        }
    }

    // عبارات الاسترجاع بعد التصدير
    if (showPhraseConfirm) {
        RecoveryWordsDialog(
            words = recoveryWords,
            onDismiss = { showPhraseConfirm = false },
        )
    }
    if (showRecoveryWords) {
        RecoveryWordsDialog(words = recoveryWords, onDismiss = { showRecoveryWords = false })
    }

    // سجل التسلل
    if (showIntrusion) {
        IntrusionLogSheet(onDismiss = { showIntrusion = false })
    }

    // استعادة نسخة احتياطية
    if (showRestore) {
        IOSAlertDialog(
            title = stringResource(R.string.v2_backup_restore),
            message = stringResource(R.string.v2_restore_phrase_hint),
            confirmText = stringResource(R.string.action_ok),
            onConfirm = {
                val bytes = restoreBytes
                val words = restorePhrase.trim().lowercase().split(Regex("\\s+"))
                if (bytes != null && RecoveryPhrase.validate(words)) {
                    runCatching {
                        val restored = RecoveryBackup.restore(bytes, words)
                        container.vault2.adoptRestoredMaster(restored.masterKey)
                        val fresh = container.vault2.openRepository()
                        fresh.replaceIndex(restored.index)
                    }.onSuccess {
                        onMessage(context.getString(R.string.v2_restore_ok))
                        showRestore = false
                        onDismiss()
                    }.onFailure {
                        onMessage(context.getString(R.string.v2_restore_fail))
                    }
                } else {
                    onMessage(context.getString(R.string.v2_restore_fail))
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showRestore = false },
            content = {
                IOSMiniField(
                    value = restorePhrase,
                    onValueChange = { restorePhrase = it },
                    hint = stringResource(R.string.v2_restore_phrase_hint),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            },
        )
    }

    // رمز التنكّر
    if (showDecoyPin) {
        IOSAlertDialog(
            title = stringResource(R.string.v2_decoy_pin),
            message = stringResource(R.string.v2_decoy_pin_hint),
            confirmText = stringResource(R.string.action_ok),
            onConfirm = {
                if (decoyPin.length >= 4 && decoyPin.all { it.isDigit() }) {
                    scope.launch {
                        container.vault2.setDecoyPin(decoyPin)
                        container.vault2.setDecoyEnabled(true)
                        decoyOn = true
                        showDecoyPin = false
                        onMessage(context.getString(R.string.v2_decoy_enabled_done))
                    }
                } else {
                    onMessage(context.getString(R.string.vault_pin_too_short))
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showDecoyPin = false },
            content = {
                IOSMiniField(
                    value = decoyPin,
                    onValueChange = { decoyPin = it.filter { c -> c.isDigit() }.take(8) },
                    hint = stringResource(R.string.vault_pin_hint),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            },
        )
    }

    // بوابة حيوية + تأكيد المسح الآمن الكامل
    if (showWipeConfirm) {
        val activity = context as? AppCompatActivity
        fun askBiometricThenWipe() {
            val act = activity ?: run { showWipeConfirm = true; return }
            val prompt = BiometricPrompt(
                act,
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        scope.launch {
                            runCatching {
                                SecureWipe.wipeTree(container.vault2.vaultRoot())
                                container.vault2.clearAllSecrets()
                            }.onSuccess {
                                onMessage(context.getString(R.string.v2_wipe_done))
                                showWipeConfirm = false
                                onDismiss()
                                onWiped()
                            }.onFailure { onMessage(it.message ?: context.getString(R.string.op_failed)) }
                        }
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.v2_wipe_gate))
                .setNegativeButtonText(context.getString(R.string.action_cancel))
                .build()
            prompt.authenticate(info)
        }
        IOSAlertDialog(
            title = stringResource(R.string.v2_wipe_all),
            message = stringResource(R.string.v2_wipe_all_confirm),
            confirmText = stringResource(R.string.v2_wipe_all),
            destructive = true,
            onConfirm = {
                showWipeConfirm = false
                askBiometricThenWipe()
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showWipeConfirm = false },
        )
    }
}

/** هجرة ملفات الخزنة القديمة (.nvf v0.3) إلى المستودع الجديد */
private suspend fun migrateLegacyVault(legacy: VaultManager, repository: Vault2Repository): Int {
    var count = 0
    for (file in legacy.listEncrypted()) {
        val temp = File.createTempFile("mig_", ".tmp")
        val ok = legacy.decryptToFile(file, temp).isSuccess
        if (ok) {
            temp.inputStream().use { input ->
                repository.importFile("", file.name.removeSuffix(".nvf"), input)
            }
            temp.delete()
            file.delete()
            count++
        } else {
            temp.delete()
        }
    }
    return count
}

/** عرض عبارة الاسترجاع للنسخ/التدوين */
@Composable
private fun RecoveryWordsDialog(words: List<String>, onDismiss: () -> Unit) {
    IOSAlertDialog(
        title = stringResource(R.string.v2_recovery_title),
        message = stringResource(R.string.v2_recovery_must),
        confirmText = stringResource(R.string.action_ok),
        onConfirm = onDismiss,
        content = {
            Column(Modifier.padding(horizontal = 16.dp)) {
                words.chunked(3).forEachIndexed { rowIdx, rowWords ->
                    Text(
                        rowWords.mapIndexed { i, w -> "${rowIdx * 3 + i + 1}. $w" }.joinToString("   "),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        },
    )
}

/** سجل التسلل — قائمة المحاولات الفاشلة */
@Composable
private fun IntrusionLogSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = context.novaContainer
    var entries by remember { mutableStateOf<List<IntrusionLog.Entry>>(emptyList()) }

    LaunchedEffect(Unit) {
        runCatching {
            val fs = LocalFileFs(container.vault2.vaultRoot())
            container.vault2.openIntrusionLog(fs).use { it.all() }
        }.getOrDefault(emptyList()).also { entries = it }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.v2_intrusion_log),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.v2_intrusion_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                IOSGroupCard {
                    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
                    entries.take(20).forEachIndexed { i, e ->
                        IOSListRow(
                            title = fmt.format(Date(e.timestamp)),
                            subtitle = when (e.kind) {
                                IntrusionLog.Kind.WRONG_PIN -> stringResource(R.string.v2_intrusion_wrong_pin)
                                IntrusionLog.Kind.WRONG_BIOMETRIC -> stringResource(R.string.v2_intrusion_wrong_bio)
                                IntrusionLog.Kind.DECOY_OPENED -> stringResource(R.string.v2_intrusion_decoy)
                                IntrusionLog.Kind.LOCK_OVERRIDE -> stringResource(R.string.v2_intrusion_override)
                            } + if (e.photoRef != null) " · " + stringResource(R.string.v2_intrusion_has_photo) else "",
                            showSeparator = i < entries.size - 1 && i < 19,
                        )
                    }
                }
            }
        }
    }
}

/** حوار نقل إلى مجلد (اختيار شجري بالنقر) */
@Composable
fun MoveDialog(
    repository: Vault2Repository,
    entry: VaultEntry,
    onDismiss: () -> Unit,
    onMoved: (String) -> Unit,
) {
    var currentId by remember { mutableStateOf("") }
    val folders = repository.index.entries
        .filter { it.isFolder && it.id != entry.id && it.parentId == currentId }
        .filter { it.id != entry.id && !repository.index.isDescendantOf(it.id, entry.id) }
        .sortedBy { it.name.lowercase() }
    val crumbs = repository.index.pathOf(currentId)

    IOSAlertDialog(
        title = stringResource(R.string.v2_move_title),
        message = entry.name,
        confirmText = stringResource(R.string.action_move),
        onConfirm = { onMoved(currentId) },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            Column(Modifier.padding(horizontal = 16.dp).height(240.dp).verticalScroll(rememberScrollState())) {
                Text(
                    (listOf("الجذر") + crumbs.map { it.name }).joinToString(" / "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (currentId.isNotEmpty()) {
                    Text(
                        "↑ " + stringResource(R.string.v2_move_up),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .clickable { currentId = repository.index.find(currentId)?.parentId ?: "" },
                    )
                }
                folders.forEach { folder ->
                    Text(
                        "📁 ${folder.name}",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickable { currentId = folder.id },
                    )
                }
                if (folders.isEmpty() && currentId.isEmpty()) {
                    Text(
                        stringResource(R.string.v2_no_folders),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/** حوار إعادة تسمية */
@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    IOSAlertDialog(
        title = stringResource(R.string.action_rename),
        confirmText = stringResource(R.string.action_save),
        onConfirm = { if (name.isNotBlank()) onRename(name.trim()) },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        content = {
            IOSMiniField(
                value = name,
                onValueChange = { name = it.take(120) },
                hint = current,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        },
    )
}
