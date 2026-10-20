package com.novafiles.app.ui.ftp

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.ftp.FtpServerService
import com.novafiles.app.core.ftp.localIpv4
import com.novafiles.app.core.perms.StorageAccess
import com.novafiles.app.core.qr.QrPayloads
import com.novafiles.app.data.prefs.SettingsStore
import com.novafiles.app.ui.dav.NetLogSection
import com.novafiles.app.ui.dav.SessionsRow
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupFooter
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSMiniField
import com.novafiles.app.ui.ios.IOSSwitch
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.ui.qr.QrDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * شاشة «مشاركة عبر WiFi» — خادم FTP مدمج بنمط iOS.
 * مفتاح تبديل، بطاقة حالة مع الرابط ونسخه، إعدادات المنفذ/الحساب، وتعليمات الاتصال.
 */
@Composable
fun FtpScreen(onBack: () -> Unit) {
    val p = LocalIOSPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val prefs = remember { SettingsStore(context) }

    val shareState by FtpServerService.state.collectAsState()
    val running = shareState.running

    var port by remember { mutableStateOf("2121") }
    var user by remember { mutableStateOf("nova") }
    var pass by remember { mutableStateOf("nova") }
    var speedOn by remember { mutableStateOf(false) }
    var speedKb by remember { mutableStateOf("") }
    var alertText by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var qrOpen by remember { mutableStateOf(false) }

    // تحميل الإعدادات المحفوظة
    LaunchedEffect(Unit) {
        port = prefs.ftpPort.first().toString()
        user = prefs.ftpUser.first()
        pass = prefs.ftpPass.first()
        val kb = prefs.ftpSpeedKb.first()
        speedOn = kb > 0
        speedKb = if (kb > 0) kb.toString() else ""
    }

    // إعادة حساب العنوان المحلي عند تغيّر الحالة
    val ip = remember(running) { localIpv4() }

    // إذن الإشعارات (أندرويد 13+)
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun toggle(on: Boolean) {
        if (on) {
            if (!StorageAccess.hasAccess(context)) {
                alertText = context.getString(R.string.ftp_need_storage)
                return
            }
            val portNum = port.toIntOrNull() ?: 2121
            if (portNum !in 1024..65535) {
                alertText = context.getString(R.string.ftp_bad_port)
                return
            }
            if (user.isBlank() || pass.isBlank()) {
                alertText = context.getString(R.string.ftp_need_credentials)
                return
            }
            val kb = speedKb.toIntOrNull() ?: 0
            if (speedOn && kb <= 0) {
                alertText = context.getString(R.string.ftp_speed_bad)
                return
            }
            scope.launch {
                prefs.setFtpPort(portNum)
                prefs.setFtpUser(user.trim())
                prefs.setFtpPass(pass.trim())
                prefs.setFtpSpeedKb(if (speedOn) kb else 0)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            FtpServerService.start(
                context, "/storage/emulated/0", portNum, user.trim(), pass.trim(),
                speedBps = if (speedOn) kb * 1024L else 0,
            )
        } else {
            FtpServerService.stop(context)
        }
    }

    fun copyUrl() {
        val url = shareState.url ?: return
        clipboard.setText(AnnotatedString(url))
        copied = true
    }

    // رابط QR الكامل مع بيانات الدخول — من الحقول المحفوظة نفسها
    val qrUrl = if (running && ip != null) {
        QrPayloads.ftpShareUrl(ip, shareState.port, user.trim().ifBlank { "nova" }, pass.trim().ifBlank { "nova" })
    } else null

    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }

    Column(modifier = Modifier.fillMaxSize().background(p.groupedBg)) {
        IOSNavBar(
            title = stringResource(R.string.ftp_title),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(4.dp))

            // ═══ بطاقة الحالة ═══
            IOSGroupCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(
                                if (running) p.green else p.secondaryLabel.copy(alpha = 0.45f),
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(if (running) R.string.ftp_status_running else R.string.ftp_status_stopped),
                            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                            color = p.label,
                        )
                        val subtitle: String = when {
                            running -> shareState.url
                                ?: shareState.error
                                ?: stringResource(R.string.ftp_no_wifi)
                            else -> stringResource(R.string.ftp_status_hint)
                        }
                        Text(
                            subtitle,
                            style = TextStyle(fontSize = 13.sp),
                            color = p.secondaryLabel,
                            maxLines = 1,
                        )
                    }
                }
            }

            // ═══ المفتاح الرئيسي ═══
            IOSGroupCard {
                IOSListRow(
                    title = stringResource(R.string.ftp_share_switch),
                    subtitle = stringResource(R.string.ftp_share_hint),
                    showSeparator = false,
                    trailing = { IOSSwitch(checked = running, onCheckedChange = { toggle(it) }) },
                )
            }

            // ═══ v0.27 — الاتصالات النشطة (أثناء العمل) ═══
            if (running) {
                IOSGroupCard {
                    SessionsRow(count = shareState.sessions, showSeparator = false)
                }
            }

            // ═══ بطاقة الرابط (أثناء العمل) ═══
            if (running && shareState.url != null) {
                IOSGroupCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                shareState.url ?: "",
                                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                                color = p.accent,
                                maxLines = 1,
                            )
                            Text(
                                stringResource(R.string.ftp_root_label, shareState.root ?: ""),
                                style = TextStyle(fontSize = 13.sp),
                                color = p.secondaryLabel,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // زر عرض رمز QR
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(p.fill, RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.QrCode2,
                                contentDescription = stringResource(R.string.qr_show),
                                tint = p.accent,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable { qrOpen = true },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(p.fill, RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.ftp_copy_url),
                                tint = if (copied) p.green else p.accent,
                                modifier = Modifier
                                    .size(19.dp)
                                    .clickable { copyUrl() },
                            )
                        }
                    }
                }
            }

            // ═══ رسالة خطأ إن وُجدت ═══
            if (shareState.error != null && running) {
                Text(
                    shareState.error ?: "",
                    style = TextStyle(fontSize = 13.sp),
                    color = p.destructive,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
                )
            }

            // ═══ الإعدادات ═══
            IOSGroupHeader(stringResource(R.string.ftp_settings_section))
            IOSGroupCard {
                val fieldsAlpha = if (running) 0.4f else 1f
                IOSListRow(
                    title = stringResource(R.string.ftp_port),
                    showSeparator = true,
                    trailing = {
                        IOSMiniField(
                            value = port,
                            onValueChange = { if (!running) port = it.filter { ch -> ch.isDigit() }.take(5) },
                            hint = "2121",
                            modifier = Modifier.width(96.dp).alpha(fieldsAlpha),
                        )
                    },
                )
                IOSListRow(
                    title = stringResource(R.string.ftp_username),
                    showSeparator = true,
                    trailing = {
                        IOSMiniField(
                            value = user,
                            onValueChange = { if (!running) user = it.take(24) },
                            hint = "nova",
                            modifier = Modifier.width(140.dp).alpha(fieldsAlpha),
                        )
                    },
                )
                IOSListRow(
                    title = stringResource(R.string.ftp_password),
                    showSeparator = false,
                    trailing = {
                        IOSMiniField(
                            value = pass,
                            onValueChange = { if (!running) pass = it.take(24) },
                            hint = "••••",
                            modifier = Modifier.width(140.dp).alpha(fieldsAlpha),
                        )
                    },
                )
            }
            IOSGroupFooter(stringResource(R.string.ftp_settings_footer))

            // ═══ v0.27 — حد السرعة ═══
            IOSGroupHeader(stringResource(R.string.ftp_speed_section))
            IOSGroupCard {
                val fieldsAlpha = if (running) 0.4f else 1f
                IOSListRow(
                    title = stringResource(R.string.ftp_speed_switch),
                    subtitle = stringResource(R.string.ftp_speed_hint),
                    showSeparator = speedOn,
                    trailing = { IOSSwitch(checked = speedOn, onCheckedChange = { if (!running) speedOn = it }) },
                )
                if (speedOn) {
                    IOSListRow(
                        title = stringResource(R.string.ftp_speed_kb),
                        showSeparator = false,
                        trailing = {
                            IOSMiniField(
                                value = speedKb,
                                onValueChange = { if (!running) speedKb = it.filter { ch -> ch.isDigit() }.take(6) },
                                hint = "1024",
                                modifier = Modifier.width(110.dp).alpha(fieldsAlpha),
                            )
                        },
                    )
                }
            }

            // ═══ زر النسخ البديل وزر QR أثناء العمل ═══
            if (running && shareState.url != null) {
                IOSFilledButton(
                    text = stringResource(R.string.qr_show),
                    onClick = { qrOpen = true },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                IOSFilledButton(
                    text = stringResource(R.string.ftp_copy_url),
                    onClick = { copyUrl() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // ═══ التعليمات ═══
            IOSGroupHeader(stringResource(R.string.ftp_howto_header))
            IOSGroupCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    HowToRow("1", stringResource(R.string.ftp_howto_1))
                    HowToRow("2", stringResource(R.string.ftp_howto_2))
                    HowToRow("3", stringResource(R.string.ftp_howto_3))
                    HowToRow("4", stringResource(R.string.ftp_howto_4))
                }
            }
            IOSGroupFooter(stringResource(R.string.ftp_security_note))

            // ═══ v0.27 — سجل الاتصال ═══
            NetLogSection(log = FtpServerService.connLog, showClear = true)

            Spacer(Modifier.height(16.dp))
        }
    }

    // ═══ حوار رمز QR ═══
    if (qrOpen && qrUrl != null) {
        QrDialog(
            title = stringResource(R.string.qr_share_title),
            url = qrUrl,
            hint = stringResource(R.string.qr_share_hint),
            onDismiss = { qrOpen = false },
        )
    }

    // ═══ تنبيهات ═══
    if (alertText != null) {
        IOSAlertDialog(
            title = stringResource(R.string.ftp_alert_title),
            message = alertText,
            confirmText = stringResource(R.string.action_ok),
            onConfirm = { alertText = null },
        )
    }
}

@Composable
private fun HowToRow(step: String, text: String) {
    val p = LocalIOSPalette.current
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(p.fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                step,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = p.secondaryLabel,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = TextStyle(fontSize = 15.sp),
            color = p.label,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}
