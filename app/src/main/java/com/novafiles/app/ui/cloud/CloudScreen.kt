package com.novafiles.app.ui.cloud

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.cloudClientId
import com.novafiles.app.core.cloud.CloudProviderDef
import com.novafiles.app.data.db.CloudAccountEntity
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSNavIconButton
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.16 — شاشة الحسابات السحابية: قائمة الحسابات الموثقة، إضافة حساب عبر
 * OAuth PKCE (متصفح النظام)، مزامنة الكاميرا لكل حساب (تفعيل/مزامنة الآن).
 */
@Composable
fun CloudScreen(
    onBack: () -> Unit,
    onBrowse: (Long) -> Unit,
) {
    val p = LocalIOSPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val container = context.novaContainer

    val accounts by remember { container.db.cloudAccountsDao().observeAll() }
        .collectAsState(initial = emptyList())

    var adding by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<CloudAccountEntity?>(null) }
    var alertText by remember { mutableStateOf<String?>(null) }

    // عودة OAuth من المتصفح — تُستهلك مرة واحدة (تُضبط من MainActivity.onNewIntent)
    LaunchedEffect(Unit) {
        while (true) {
            val redirect = container.pendingOAuthRedirect
            val pending = container.pendingCloudAuth
            if (redirect != null && pending != null) {
                container.pendingOAuthRedirect = null
                busy = true
                try {
                    val account = container.cloudAccounts.completeAuth(
                        pending, redirect,
                        mapOf(
                            "drive" to container.cloudClientId("drive"),
                            "dropbox" to container.cloudClientId("dropbox"),
                            "onedrive" to container.cloudClientId("onedrive"),
                        ),
                    )
                    container.pendingCloudAuth = null
                    snackbar.showSnackbar(context.getString(R.string.cloud_added, account.displayName))
                } catch (e: Exception) {
                    container.pendingCloudAuth = null
                    snackbar.showSnackbar(e.message ?: "oauth failed")
                } finally {
                    busy = false
                }
            }
            delay(500)
        }
    }

    Scaffold(
        containerColor = p.groupedBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            IOSNavBar(
                title = stringResource(R.string.cloud_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cloud_add))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (busy) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp) }
            }

            if (accounts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.CloudOff, contentDescription = null,
                            tint = p.secondaryLabel, modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.cloud_empty), color = p.secondaryLabel, fontSize = 15.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(accounts, key = { it.id }) { account ->
                        CloudAccountCard(
                            account = account,
                            onBrowse = { onBrowse(account.id) },
                            onSyncNow = {
                                scope.launch {
                                    busy = true
                                    try {
                                        val report = container.cameraSync.syncNow(account)
                                        val msg = when {
                                            report == null -> context.getString(R.string.cloud_sync_no_client)
                                            report.constraintsBlocked -> context.getString(R.string.cloud_sync_blocked)
                                            report.failed.isNotEmpty() ->
                                                context.getString(R.string.cloud_sync_partial, report.uploaded.size, report.failed.size)
                                            else -> context.getString(R.string.cloud_sync_done, report.uploaded.size, report.skipped.size)
                                        }
                                        snackbar.showSnackbar(msg)
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            onToggleSync = { enabled ->
                                scope.launch {
                                    container.db.cloudAccountsDao().upsert(account.copy(syncEnabled = enabled))
                                }
                            },
                            onRemove = { confirmRemove = account },
                        )
                    }
                }
            }
        }
    }

    // ═══ ورقة اختيار المزوّد ═══
    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            confirmButton = {},
            title = { Text(stringResource(R.string.cloud_pick_provider)) },
            text = {
                Column {
                    for (def in CloudProviderDef.ALL) {
                        val clientId = context.cloudClientId(def.key)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    adding = false
                                    if (clientId.isBlank()) {
                                        alertText = context.getString(R.string.cloud_no_client_id, def.displayName)
                                    } else {
                                        val pending = container.cloudAccounts.beginAuth(def, clientId)
                                        container.pendingCloudAuth = pending
                                        openBrowser(context, pending.url)
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProviderBadge(def.key)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(def.displayName, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = p.label)
                                Text(
                                    if (clientId.isBlank()) stringResource(R.string.cloud_needs_client_id)
                                    else def.scopes.firstOrNull() ?: "",
                                    fontSize = 12.sp,
                                    color = p.secondaryLabel,
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    // ═══ تأكيد الإزالة ═══
    confirmRemove?.let { account ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.cloud_remove_title)) },
            text = { Text(stringResource(R.string.cloud_remove_body, account.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    scope.launch {
                        container.cloudAccounts.removeAccount(account)
                        snackbar.showSnackbar(context.getString(R.string.cloud_removed))
                    }
                }) { Text(stringResource(R.string.action_delete), color = p.destructive) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    alertText?.let { msg ->
        AlertDialog(
            onDismissRequest = { alertText = null },
            confirmButton = {
                TextButton(onClick = { alertText = null }) { Text("OK") }
            },
            title = { Text(stringResource(R.string.cloud_setup_required)) },
            text = { Text(msg) },
        )
    }
}

/** فتح المتصفح النظامي للتفويض (Chrome/Custom Tab يظهر تلقائياً) */
fun openBrowser(context: android.content.Context, url: String) {
    context.startActivity(
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** شارة مزوّد بلون هوية تقريبي */
@Composable
fun ProviderBadge(providerKey: String) {
    val colors = mapOf(
        "drive" to Color(0xFF1FA463),
        "dropbox" to Color(0xFF0061FF),
        "onedrive" to Color(0xFF0364B8),
    )
    Box(
        modifier = Modifier
            .size(42.dp)
            .background(colors[providerKey] ?: Color(0xFF6E7278), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.CloudDone, contentDescription = null,
            tint = Color.White, modifier = Modifier.size(22.dp),
        )
    }
}

/** بطاقة حساب — تصفح، مزامنة كاميرا، مزامنة الآن، إزالة */
@Composable
private fun CloudAccountCard(
    account: CloudAccountEntity,
    onBrowse: () -> Unit,
    onSyncNow: () -> Unit,
    onToggleSync: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val p = LocalIOSPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.cardBg, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderBadge(account.provider)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(account.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = p.label)
                Text(
                    if (account.syncEnabled) {
                        stringResource(
                            R.string.cloud_sync_on,
                            account.targetFolder,
                            formatLastSync(account.lastSyncAt),
                        )
                    } else {
                        stringResource(R.string.cloud_sync_off)
                    },
                    fontSize = 12.sp,
                    color = p.secondaryLabel,
                    maxLines = 2,
                )
            }
            Switch(checked = account.syncEnabled, onCheckedChange = onToggleSync)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CardButton(stringResource(R.string.cloud_browse), Icons.Filled.Folder, onBrowse)
            CardButton(stringResource(R.string.cloud_sync_now), Icons.Filled.CloudSync, onSyncNow)
            CardButton(stringResource(R.string.action_delete), Icons.Filled.Delete, onRemove)
        }
    }
}

@Composable
private fun CardButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val p = LocalIOSPalette.current
    Row(
        modifier = Modifier
            .background(p.fill, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = p.accent)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = p.accent)
    }
}

private fun formatLastSync(ts: Long): String =
    if (ts <= 0) "—"
    else SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(ts))
