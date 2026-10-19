@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.direct

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
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.novafiles.app.R
import com.novafiles.app.core.ftp.FtpServerService
import com.novafiles.app.core.wifip2p.DirectPayload
import com.novafiles.app.core.wifip2p.DirectPayloadCodec
import com.novafiles.app.core.wifip2p.DirectPhase
import com.novafiles.app.data.db.ConnectionEntity
import com.novafiles.app.data.prefs.SettingsStore
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.ui.qr.QrDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * v0.12 — شاشة النقل المباشر عبر Wi-Fi Direct (بلا موجّه).
 *
 * إرسال: اكتشاف ← اقتران (نحن مالك المجموعة) ← استضافة خادم FTP
 * المدمج عبر واجهة P2P ← عرض رمز QR للمصافحة (nova-direct://).
 *
 * استقبال: انتظار الاقتران ← لصق/مسح رابط الإرسال ← إنشاء اتصال
 * مؤقت وفتح متصفح الخادم البعيد لتنزيل الملفات.
 */
@Composable
fun DirectScreen(
    sendMode: Boolean,
    onBack: () -> Unit,
    onOpenRemote: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = context.novaContainer
    val manager = container.direct
    val state by manager.state.collectAsState()
    var qrUrl by remember { mutableStateOf<String?>(null) }
    var pasteOpen by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { manager.startDiscovery() }

    Column(modifier = Modifier.fillMaxSize()) {
        IOSNavBar(
            title = stringResource(if (sendMode) R.string.direct_send_title else R.string.direct_receive_title),
            subtitle = stringResource(R.string.direct_subtitle),
            onBack = {
                manager.disconnect()
                onBack()
            },
            actions = {},
        )

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            when (state.phase) {
                DirectPhase.IDLE, DirectPhase.DISCOVERING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.direct_discovering), style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(14.dp))
                    if (state.peers.isEmpty()) {
                        IOSGroupCard {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    stringResource(R.string.direct_no_peers_title),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(
                                        if (sendMode) R.string.direct_no_peers_body_send
                                        else R.string.direct_no_peers_body_receive,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Text(stringResource(R.string.direct_peers), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.peers) { peer ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 1.dp,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .clickable(enabled = sendMode) {
                                                manager.connect(peer, asOwner = true)
                                            }
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = LocalIOSPalette.current.accent)
                                        Spacer(Modifier.width(10.dp))
                                        Text(peer.deviceName, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    }
                }

                DirectPhase.CONNECTING -> {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.direct_pairing))
                        }
                    }
                }

                DirectPhase.CONNECTED -> {
                    if (sendMode) {
                        // نحن مالك المجموعة — نشغّل خادم FTP المدمج ونعرض QR
                        LaunchedEffect(qrUrl) {
                            if (qrUrl == null) {
                                val prefs = SettingsStore(context)
                                val port = runCatching { prefs.ftpPort.first() }.getOrDefault(2121).coerceIn(1024, 65535)
                                val user = runCatching { prefs.ftpUser.first() }.getOrDefault("nova").ifBlank { "nova" }
                                val pass = runCatching { prefs.ftpPass.first() }.getOrDefault("nova").ifBlank { "nova" }
                                val host = state.ownerAddress ?: "192.168.49.1"
                                qrUrl = DirectPayloadCodec.encode(
                                    DirectPayload(
                                        host = host, port = port,
                                        deviceName = android.os.Build.MODEL,
                                        filesReady = 1, user = user, pass = pass,
                                    ),
                                )
                                FtpServerService.start(context, root = "/storage/emulated/0", port = port, user = user, pass = pass)
                            }
                        }
                        Text(stringResource(R.string.direct_connected_send), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        IOSGroupCard {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    stringResource(R.string.direct_qr_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        DirectReceiveSection(
                            ownerAddress = state.ownerAddress,
                            onPasteOpen = { pasteOpen = true },
                            onPasteText = { pasteText = it },
                        )
                    }
                }

                DirectPhase.FAILED -> {
                    IOSGroupCard {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                stringResource(R.string.direct_failed, state.error ?: ""),
                                color = LocalIOSPalette.current.destructive,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { manager.startDiscovery() }) {
                                Text(stringResource(R.string.net_retry))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.direct_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    // QR المصافحة للمرسل
    qrUrl?.let { url ->
        QrDialog(
            title = stringResource(R.string.direct_qr_title),
            url = url,
            hint = stringResource(R.string.direct_qr_hint2),
            onDismiss = { qrUrl = null },
        )
    }

    // لصق رابط الإرسال في وضع الاستقبال
    if (pasteOpen) {
        ModalBottomSheet(onDismissRequest = { pasteOpen = false }) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(stringResource(R.string.direct_paste_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { pasteText = it },
                    label = { Text(stringResource(R.string.direct_paste_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                IOSFilledButton(
                    text = stringResource(R.string.direct_open_link),
                    onClick = {
                        val payload = DirectPayloadCodec.decode(pasteText.trim())
                        if (payload != null) {
                            pasteOpen = false
                            scope.launch {
                                val id = container.db.connectionsDao().upsert(
                                    ConnectionEntity(
                                        name = payload.deviceName.ifEmpty { "Wi-Fi Direct" },
                                        protocol = "FTP",
                                        host = payload.host,
                                        port = payload.port,
                                        username = payload.user,
                                        password = payload.pass,
                                    ),
                                )
                                manager.disconnect()
                                onOpenRemote(id)
                            }
                        }
                    },
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DirectReceiveSection(
    ownerAddress: String?,
    onPasteOpen: () -> Unit,
    onPasteText: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    Text(stringResource(R.string.direct_connected_receive), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    IOSGroupCard {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                stringResource(R.string.direct_owner_address, ownerAddress ?: "—"),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.direct_receive_steps),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    IOSFilledButton(
        text = stringResource(R.string.direct_paste_button),
        onClick = onPasteOpen,
    )
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = {
        val text = clipboard.getText()?.toString().orEmpty()
        if (text.isNotEmpty()) onPasteText(text)
    }) {
        Text(stringResource(R.string.direct_paste_from_clipboard))
    }
}
