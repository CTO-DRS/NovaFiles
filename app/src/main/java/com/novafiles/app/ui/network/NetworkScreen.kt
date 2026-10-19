package com.novafiles.app.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.data.db.ConnectionEntity
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupFooter
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.launch

/**
 * شاشة «الاتصال بالخوادم» — قائمة الاتصالات المحفوظة (FTP / SMB) بنمط iOS،
 * مع إضافة واتصال وتعديل وحذف.
 */
@Composable
fun NetworkScreen(
    onBack: () -> Unit,
    onConnect: (ConnectionEntity) -> Unit,
    onEdit: (ConnectionEntity) -> Unit,
    onAdd: () -> Unit,
    onCloud: () -> Unit,
) {
    val p = LocalIOSPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { context.novaContainer.db.connectionsDao() }

    val connections by remember { dao.observeAll() }.collectAsState(initial = emptyList())

    var sheetFor by remember { mutableStateOf<ConnectionEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<ConnectionEntity?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(p.groupedBg)) {
        IOSNavBar(title = stringResource(R.string.net_title), onBack = onBack)

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(4.dp))

            // ═══ v0.16 — الحسابات السحابية (Drive/Dropbox/OneDrive عبر OAuth) ═══
            IOSGroupHeader(stringResource(R.string.cloud_title))
            IOSGroupCard {
                IOSListRow(
                    title = stringResource(R.string.cloud_section_row),
                    subtitle = stringResource(R.string.cloud_section_sub),
                    showChevron = true,
                    showSeparator = false,
                    onClick = onCloud,
                    leading = {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(Color(0x1F1FA463), RoundedCornerShape(7.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.CloudDone,
                                contentDescription = null,
                                tint = Color(0xFF1FA463),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }

            if (connections.isEmpty()) {
                // ═══ حالة فارغة ═══
                IOSGroupCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.Dns,
                            contentDescription = null,
                            tint = p.secondaryLabel,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.net_empty_title),
                            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                            color = p.label,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.net_empty_body),
                            style = TextStyle(fontSize = 13.sp),
                            color = p.secondaryLabel,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            } else {
                IOSGroupHeader(stringResource(R.string.net_saved_header))
                IOSGroupCard {
                    connections.forEachIndexed { index, conn ->
                        IOSListRow(
                            title = conn.name,
                            subtitle = "${conn.protocol} — ${conn.host}:${conn.port}",
                            showSeparator = index < connections.lastIndex,
                            onClick = { onConnect(conn) },
                            showChevron = true,
                            leading = {
                                val isSmbProto = conn.protocol.equals("SMB", true)
                                val isSftpProto = conn.protocol.equals("SFTP", true)
                                Box(
                                    modifier = Modifier
                                        .size(30.dp)
                                        .background(
                                            when {
                                                isSmbProto -> Color(0x1F34C759)
                                                isSftpProto -> Color(0x1FAF52DE)
                                                else -> Color(0x1F007AFF)
                                            },
                                            RoundedCornerShape(7.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        when {
                                            isSmbProto -> Icons.Filled.Dns
                                            isSftpProto -> Icons.Filled.VpnKey
                                            else -> Icons.Filled.Cloud
                                        },
                                        contentDescription = conn.protocol,
                                        tint = when {
                                            isSmbProto -> Color(0xFF34C759)
                                            isSftpProto -> Color(0xFFAF52DE)
                                            else -> p.accent
                                        },
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                            trailing = {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.net_more),
                                    tint = p.accent,
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clickable { sheetFor = conn },
                                )
                            },
                        )
                    }
                }
            }

            // ═══ زر الإضافة ═══
            IOSFilledButton(
                text = stringResource(R.string.net_add),
                onClick = onAdd,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            IOSGroupFooter(stringResource(R.string.net_footer))
            Spacer(Modifier.height(16.dp))
        }
    }

    // ═══ ورقة إجراءات اتصال ═══
    val c = sheetFor
    if (c != null) {
        IOSActionSheet(
            visible = true,
            onDismiss = { sheetFor = null },
            cancelText = stringResource(R.string.action_cancel),
            title = c.name,
            actions = listOf(
                IOSAction(stringResource(R.string.net_connect)) { onConnect(c) },
                IOSAction(stringResource(R.string.net_edit)) { onEdit(c) },
                IOSAction(stringResource(R.string.action_delete), destructive = true) { confirmDelete = c },
            ),
        )
    }

    // ═══ تأكيد الحذف ═══
    val del = confirmDelete
    if (del != null) {
        IOSAlertDialog(
            title = stringResource(R.string.net_delete_confirm),
            message = stringResource(R.string.net_delete_body, del.name),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = {
                confirmDelete = null
                sheetFor = null
                scope.launch { dao.remove(del.id) }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { confirmDelete = null },
            destructive = true,
        )
    }
}
