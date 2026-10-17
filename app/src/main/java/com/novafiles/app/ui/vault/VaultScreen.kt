@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.vault

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.novafiles.app.R
import com.novafiles.app.core.fs.LocalBrowser
import com.novafiles.app.novaContainer
import com.novafiles.app.util.TypeIcons
import com.novafiles.app.core.fs.FileType
import java.io.File
import kotlinx.coroutines.launch

/**
 * الخزنة المشفرة:
 * - إنشاء PIN (مملّح ومجزأ) ثم قفل/فتح (PIN أو بصمة)
 * - تشفير ملفات عبر منتقي المستندات → نقلها مشفرة إلى الخزنة
 * - فك تشفير أي ملف إلى مكان يختاره المستخدم
 */
@Composable
fun VaultScreen() {
    val context = LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var pinSet by remember { mutableStateOf<Boolean?>(null) }
    var unlocked by remember { mutableStateOf(false) }
    var vaultFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    fun reload() {
        vaultFiles = container.vault.listEncrypted()
    }

    LaunchedEffect(Unit) {
        pinSet = container.vault.isPinSet()
        reload()
    }

    val encryptedDone = stringResource(R.string.vault_encrypted_done)
    val decryptedDone = stringResource(R.string.vault_decrypted_done)
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    // منتقي ملف للتشفير
    val pickToEncrypt = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val name = container.vault.resolveTempName(context, uri)
                    val temp = File(context.cacheDir, name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("read failed")
                    val res = container.vault.encryptIntoVault(temp)
                    temp.delete()
                    res.getOrThrow()
                }.onSuccess {
                    message = encryptedDone
                    reload()
                }.onFailure {
                    message = it.message ?: context.getString(R.string.op_failed)
                }
            }
        }
    }

    // منتقي مكان الحفظ عند فك التشفير
    var decryptSource by remember { mutableStateOf<File?>(null) }
    val saveDecrypted = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val src = decryptSource
        if (uri != null && src != null) {
            scope.launch {
                container.vault.decryptToFile(src, tempTarget(context, uri)).fold(
                    onSuccess = { plain ->
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            plain.inputStream().use { it.copyTo(out) }
                        }
                        plain.delete()
                        message = decryptedDone
                    },
                    onFailure = { message = it.message ?: context.getString(R.string.op_failed) },
                )
            }
        }
        decryptSource = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_vault)) },
                actions = {
                    if (unlocked) {
                        IconButton(onClick = { unlocked = false }) {
                            Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.action_lock))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (unlocked) {
                FloatingActionButton(onClick = {
                    pickToEncrypt.launch(arrayOf("*/*"))
                }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_pick_file_encrypt))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                pinSet == null -> Box(Modifier.fillMaxSize()) {}
                pinSet == false -> SetPinScreen(onDone = {
                    pinSet = true
                    unlocked = true
                    reload()
                })
                !unlocked -> UnlockScreen(
                    onUnlocked = { unlocked = true },
                    vault = container.vault,
                    activity = context as? AppCompatActivity,
                )
                else -> {
                    val totalSize = vaultFiles.sumOf { it.length() }
                    Text(
                        stringResource(R.string.vault_encrypted_count, vaultFiles.size, LocalBrowser.humanSize(totalSize)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                    if (vaultFiles.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.empty_vault),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(vaultFiles, key = { it.absolutePath }) { file ->
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
                                        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            file.name.removeSuffix(".nvf"),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            LocalBrowser.humanSize(file.length()),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    IconButton(onClick = {
                                        decryptSource = file
                                        saveDecrypted.launch(file.name.removeSuffix(".nvf"))
                                    }) {
                                        Icon(Icons.Filled.LockOpen, contentDescription = stringResource(R.string.action_decrypt))
                                    }
                                    IconButton(onClick = {
                                        container.vault.deleteEncrypted(file)
                                        reload()
                                    }) {
                                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** هدف مؤقت لفك التشفير قبل نسخه إلى Uri المستخدم */
private fun tempTarget(context: android.content.Context, uri: Uri): File {
    val name = uri.lastPathSegment ?: "decrypted"
    return File(context.cacheDir, "dec_${System.currentTimeMillis()}_$name")
}

private suspend fun com.novafiles.app.core.vault.VaultManager.resolveTempName(
    context: android.content.Context,
    uri: Uri,
): String {
    val display = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
    return (display ?: "file_${System.currentTimeMillis()}").replace("/", "_")
}

// ---------- إنشاء PIN ----------

@Composable
private fun SetPinScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(stringResource(R.string.vault_set_pin), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(16.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
            label = { Text(stringResource(R.string.vault_pin_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it.filter { c -> c.isDigit() }.take(8) },
            label = { Text(stringResource(R.string.vault_confirm_pin)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.size(16.dp))
        Button(
            onClick = {
                when {
                    pin.length < 4 -> error = context.getString(R.string.vault_pin_too_short)
                    pin != confirm -> error = context.getString(R.string.vault_pin_mismatch)
                    else -> scope.launch {
                        container.vault.setPin(pin)
                        onDone()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_ok)) }
    }
}

// ---------- فتح القفل ----------

@Composable
private fun UnlockScreen(
    onUnlocked: () -> Unit,
    vault: com.novafiles.app.core.vault.VaultManager,
    activity: AppCompatActivity?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    val canBiometric = remember {
        activity != null && BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showBiometric() {
        val act = activity ?: return
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(
            act,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
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
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Fingerprint,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.vault_locked_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(16.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8); wrong = false },
            label = { Text(stringResource(R.string.vault_pin_hint)) },
            isError = wrong,
            supportingText = { if (wrong) Text(stringResource(R.string.vault_wrong_pin)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.size(16.dp))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    if (vault.verifyPin(pin)) onUnlocked() else wrong = true
                }
            }) { Text(stringResource(R.string.vault_unlock)) }
            if (canBiometric) {
                TextButton(onClick = { showBiometric() }) {
                    Text(stringResource(R.string.vault_unlock_biometric))
                }
            }
        }
    }
}
