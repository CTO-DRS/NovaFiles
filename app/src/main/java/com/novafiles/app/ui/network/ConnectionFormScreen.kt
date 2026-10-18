package com.novafiles.app.ui.network

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.network.RemoteFsFactory
import com.novafiles.app.data.db.ConnectionEntity
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupFooter
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSMiniField
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.launch

/**
 * شاشة إضافة/تعديل اتصال شبكة بنمط iOS — بروتوكول (FTP/SMB)، بيانات خادم،
 * بيانات دخول، اختبار اتصال فوري، وحفظ في قاعدة البيانات.
 */
@Composable
fun ConnectionFormScreen(
    connectionId: Long,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val p = LocalIOSPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { context.novaContainer.db.connectionsDao() }

    var protocol by remember { mutableStateOf("FTP") }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var domain by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var initialPath by remember { mutableStateOf("") }

    var loaded by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var alertText by remember { mutableStateOf<String?>(null) }

    // تحميل بيانات اتصال موجود
    LaunchedEffect(connectionId) {
        if (connectionId > 0) {
            dao.byId(connectionId)?.let { c ->
                protocol = c.protocol.uppercase()
                name = c.name
                host = c.host
                port = c.port.toString()
                domain = c.domain
                username = c.username
                password = c.password
                share = c.share
                initialPath = c.initialPath
                loaded = true
            }
        }
    }

    fun buildEntity(): ConnectionEntity = ConnectionEntity(
        id = if (connectionId > 0) connectionId else 0L,
        name = name.ifBlank { host },
        protocol = protocol,
        host = host.trim(),
        port = port.toIntOrNull() ?: RemoteFsFactory.defaultPort(protocol),
        domain = domain.trim(),
        username = username.trim(),
        password = password,
        share = share.trim(),
        initialPath = initialPath.trim(),
        lastUsedAt = 0L,
    )

    fun validate(): String? {
        if (host.isBlank()) return context.getString(R.string.net_need_host)
        if (protocol.equals("SMB", true) && share.isBlank()) return context.getString(R.string.net_need_share)
        return null
    }

    fun runTest() {
        val v = validate()
        if (v != null) {
            alertText = v
            return
        }
        scope.launch {
            testing = true
            testResult = null
            testMessage = null
            var opened: com.novafiles.app.core.network.RemoteFs? = null
            try {
                opened = RemoteFsFactory.open(buildEntity())
                opened.test()
                testResult = true
            } catch (e: Exception) {
                testResult = false
                testMessage = e.message ?: e.javaClass.simpleName
            } finally {
                runCatching { opened?.close() }
                testing = false
            }
        }
    }

    fun save() {
        val v = validate()
        if (v != null) {
            alertText = v
            return
        }
        scope.launch {
            val e = buildEntity()
            // عند التعديل نحافظ على آخر استخدام
            if (connectionId > 0) {
                dao.byId(connectionId)?.let { old ->
                    dao.upsert(e.copy(lastUsedAt = old.lastUsedAt))
                }
            } else {
                dao.upsert(e)
            }
            onSaved()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(p.groupedBg)) {
        IOSNavBar(
            title = stringResource(
                if (loaded) R.string.net_form_title_edit else R.string.net_form_title_new,
            ),
            onBack = onBack,
        )

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(4.dp))

            // ═══ البروتوكول ═══
            IOSGroupHeader(stringResource(R.string.net_protocol_section))
            IOSGroupCard {
                IOSSegmented(
                    options = listOf("FTP", "SMB"),
                    selected = if (protocol.equals("SMB", true)) 1 else 0,
                    onSelect = { idx ->
                        if (!testing) {
                            protocol = if (idx == 1) "SMB" else "FTP"
                            port = RemoteFsFactory.defaultPort(protocol).toString()
                            testResult = null
                            testMessage = null
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            // ═══ الخادم ═══
            IOSGroupHeader(stringResource(R.string.net_section_server))
            IOSGroupCard {
                IOSListRow(
                    title = stringResource(R.string.net_host),
                    showSeparator = true,
                    trailing = {
                        IOSMiniField(
                            value = host,
                            onValueChange = { host = it.take(120) },
                            hint = "192.168.1.10",
                            modifier = Modifier.width(180.dp),
                        )
                    },
                )
                IOSListRow(
                    title = stringResource(R.string.net_port),
                    showSeparator = !protocol.equals("SMB", true),
                    trailing = {
                        IOSMiniField(
                            value = port,
                            onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                            hint = if (protocol.equals("SMB", true)) "445" else "21",
                            modifier = Modifier.width(96.dp),
                        )
                    },
                )
                if (protocol.equals("SMB", true)) {
                    IOSListRow(
                        title = stringResource(R.string.net_share),
                        showSeparator = true,
                        trailing = {
                            IOSMiniField(
                                value = share,
                                onValueChange = { share = it.take(60) },
                                hint = "Public",
                                modifier = Modifier.width(180.dp),
                            )
                        },
                    )
                    IOSListRow(
                        title = stringResource(R.string.net_domain),
                        showSeparator = false,
                        subtitle = null,
                        trailing = {
                            IOSMiniField(
                                value = domain,
                                onValueChange = { domain = it.take(60) },
                                hint = "WORKGROUP",
                                modifier = Modifier.width(180.dp),
                            )
                        },
                    )
                }
            }
            IOSGroupFooter(
                if (protocol.equals("SMB", true)) stringResource(R.string.net_smb_footer)
                else stringResource(R.string.net_ftp_footer)
            )

            // ═══ بيانات الدخول ═══
            IOSGroupHeader(stringResource(R.string.net_section_credentials))
            IOSGroupCard {
                IOSListRow(
                    title = stringResource(R.string.net_username),
                    showSeparator = true,
                    trailing = {
                        IOSMiniField(
                            value = username,
                            onValueChange = { username = it.take(60) },
                            hint = "guest",
                            modifier = Modifier.width(180.dp),
                        )
                    },
                )
                IOSListRow(
                    title = stringResource(R.string.net_password),
                    showSeparator = true,
                    trailing = {
                        IOSMiniField(
                            value = password,
                            onValueChange = { password = it.take(80) },
                            hint = "••••••",
                            modifier = Modifier.width(180.dp),
                        )
                    },
                )
                IOSListRow(
                    title = stringResource(R.string.net_name),
                    showSeparator = false,
                    trailing = {
                        IOSMiniField(
                            value = name,
                            onValueChange = { name = it.take(40) },
                            hint = stringResource(R.string.net_name_hint),
                            modifier = Modifier.width(180.dp),
                        )
                    },
                )
            }
            IOSGroupFooter(stringResource(R.string.net_credentials_footer))

            // ═══ اختبار الاتصال ═══
            IOSGroupCard {
                IOSListRow(
                    title = if (testing) stringResource(R.string.net_testing) else stringResource(R.string.net_test),
                    showSeparator = false,
                    onClick = if (testing) null else ({ runTest() }),
                    leading = {
                        if (testing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = p.accent,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                    trailing = {
                        when {
                            testResult == true -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.net_test_ok),
                                    style = TextStyle(fontSize = 15.sp),
                                    color = p.green,
                                )
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = p.green, modifier = Modifier.size(18.dp))
                            }
                            testResult == false -> Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = p.destructive,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
                if (testResult == false && testMessage != null) {
                    Text(
                        stringResource(R.string.net_test_fail, testMessage ?: ""),
                        style = TextStyle(fontSize = 13.sp),
                        color = p.destructive,
                        modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 10.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            IOSFilledButton(
                text = stringResource(R.string.net_save),
                onClick = { save() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).alpha(if (testing) 0.5f else 1f),
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    // ═══ تنبيهات ═══
    if (alertText != null) {
        IOSAlertDialog(
            title = stringResource(R.string.net_title),
            message = alertText,
            confirmText = stringResource(R.string.action_ok),
            onConfirm = { alertText = null },
        )
    }
}

/** مفتاح منقسم iOS (UISegmentedControl) بخيارين أو أكثر */
@Composable
private fun IOSSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalIOSPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(p.fill, RoundedCornerShape(9.dp)),
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(2.dp)
                    .background(
                        if (isSel) p.cardBg else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(7.dp),
                    )
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = p.label,
                )
            }
        }
    }
}
