package com.novafiles.app.ui.dav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.netlog.ConnectionLog
import com.novafiles.app.core.netlog.NetEventType
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupFooter
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.LocalIOSPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** لون نقطة الحدث حسب نوعه — بلون iOS دلالي */
private fun eventColor(type: NetEventType, accent: androidx.compose.ui.graphics.Color,
                       green: androidx.compose.ui.graphics.Color,
                       destructive: androidx.compose.ui.graphics.Color,
                       secondary: androidx.compose.ui.graphics.Color) = when (type) {
    NetEventType.LOGIN -> green
    NetEventType.DOWNLOAD, NetEventType.UPLOAD -> accent
    NetEventType.LOGIN_FAILED, NetEventType.ERROR -> destructive
    else -> secondary
}

/**
 * v0.27 — قسم «سجل الاتصال» المشترك بين شاشتي FTP وWebDAV:
 * آخر 8 أحداث بنقطة لونية ونوع مترجم وتفصيل ووقت، مع مسح السجل.
 */
@Composable
fun NetLogSection(log: ConnectionLog, showClear: Boolean = true) {
    val p = LocalIOSPalette.current
    val events by log.events.collectAsState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    IOSGroupHeader(stringResource(R.string.ftp_log_section))
    IOSGroupCard {
        if (events.isEmpty()) {
            Text(
                stringResource(R.string.ftp_log_empty),
                style = TextStyle(fontSize = 13.sp),
                color = p.secondaryLabel,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            )
        } else {
            for (e in events.take(8)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                eventColor(e.type, p.accent, p.green, p.destructive, p.secondaryLabel),
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            eventLabel(e.type),
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                            color = p.label,
                        )
                        if (e.detail.isNotBlank()) {
                            Text(
                                e.detail,
                                style = TextStyle(fontSize = 12.sp),
                                color = p.secondaryLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        timeFmt.format(Date(e.time)),
                        style = TextStyle(fontSize = 12.sp),
                        color = p.secondaryLabel,
                    )
                }
            }
            if (showClear) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .height(34.dp)
                        .clickable { log.clear() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.ftp_log_clear),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        color = p.accent,
                    )
                }
            }
        }
    }
    IOSGroupFooter(stringResource(R.string.ftp_security_note))
}

@Composable
fun eventLabel(type: NetEventType): String = stringResource(
    when (type) {
        NetEventType.CONNECT -> R.string.netlog_connect
        NetEventType.LOGIN -> R.string.netlog_login
        NetEventType.LOGIN_FAILED -> R.string.netlog_login_failed
        NetEventType.DISCONNECT -> R.string.netlog_disconnect
        NetEventType.DOWNLOAD -> R.string.netlog_download
        NetEventType.UPLOAD -> R.string.netlog_upload
        NetEventType.MKDIR -> R.string.netlog_mkdir
        NetEventType.DELETE -> R.string.netlog_delete
        NetEventType.MOVE -> R.string.netlog_move
        NetEventType.ERROR -> R.string.netlog_error
    },
)

/** صف عدد الاتصالات النشطة المشترك بين الشاشتين */
@Composable
fun SessionsRow(count: Int, showSeparator: Boolean = true) {
    val p = LocalIOSPalette.current
    IOSListRow(
        title = stringResource(R.string.ftp_sessions_row),
        subtitle = stringResource(R.string.ftp_sessions_hint),
        showSeparator = showSeparator,
        trailing = {
            Text(
                count.toString(),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                color = if (count > 0) p.green else p.secondaryLabel,
            )
        },
    )
}
