package com.novafiles.app.ui.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novafiles.app.R
import com.novafiles.app.core.fs.LocalBrowser
import com.novafiles.app.core.ops.OpKind
import com.novafiles.app.core.ops.OpRecord
import com.novafiles.app.core.ops.OpStatus
import com.novafiles.app.ui.browser.BrowserViewModel
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette

/**
 * v0.12 — شاشة قائمة العمليات الخلفية.
 * تُظهر كل العمليات (جارية/منتظرة/موقوفة/منتهية) مع تقدّق بايتي
 * وأزرار إيقاف/استئناف/إلغاء، وزر «مسح المنتهية» في شريط التنقل.
 */
@Composable
fun OpsScreen(
    vm: BrowserViewModel,
    onBack: () -> Unit,
) {
    val ops by vm.opRecords.collectAsState()
    val palette = LocalIOSPalette.current
    val ordered = ops.sortedByDescending { it.id }
    val hasFinished = ops.any { it.status.finished }

    Column(modifier = Modifier.fillMaxSize()) {
        IOSNavBar(
            title = stringResource(R.string.ops_title),
            onBack = onBack,
            actions = {
                IconButton(onClick = { vm.clearFinishedOps() }, enabled = hasFinished) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.ops_clear_finished),
                        tint = if (hasFinished) palette.destructive else Color.Gray,
                    )
                }
            },
        )

        if (ordered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.ops_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ordered, key = { it.id }) { record ->
                    OpCard(
                        record = record,
                        onPause = { vm.pauseOp(record.id) },
                        onResume = { vm.resumeOp(record.id) },
                        onCancel = { vm.cancelOp(record.id) },
                    )
                }
            }
        }
    }
}

private val OpStatus.finished: Boolean
    get() = this == OpStatus.DONE || this == OpStatus.FAILED || this == OpStatus.CANCELLED

@Composable
private fun OpCard(
    record: OpRecord,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = LocalIOSPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(palette.groupedBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(kindIcon(record.kind), contentDescription = null, tint = palette.accent)
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        record.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        statusText(record),
                        style = MaterialTheme.typography.bodySmall,
                        color = when (record.status) {
                            OpStatus.FAILED -> palette.destructive
                            OpStatus.DONE -> Color(0xFF34C759)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (record.status) {
                    OpStatus.RUNNING -> IconButton(onClick = onPause) {
                        Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.ops_pause))
                    }
                    OpStatus.PAUSED -> IconButton(onClick = onResume) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.ops_resume))
                    }
                    else -> {}
                }
                if (!record.status.finished) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                            tint = palette.destructive,
                        )
                    }
                }
            }

            val p = record.progress
            if (record.status == OpStatus.RUNNING || record.status == OpStatus.PAUSED) {
                Spacer(Modifier.size(6.dp))
                if (p != null && p.bytesTotal > 0) {
                    val fraction = (p.bytesDone.toDouble() / p.bytesTotal).coerceIn(0.0, 1.0)
                    LinearProgressIndicator(
                        progress = { fraction.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.size(4.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            p.currentName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${LocalBrowser.humanSize(p.bytesDone)} / ${LocalBrowser.humanSize(p.bytesTotal)}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (record.status == OpStatus.FAILED && record.error != null) {
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.ops_error, record.error ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.destructive,
                )
            }
        }
    }
}

@Composable
private fun statusText(record: OpRecord): String = when (record.status) {
    OpStatus.QUEUED -> stringResource(R.string.ops_status_queued)
    OpStatus.RUNNING -> stringResource(R.string.ops_status_running)
    OpStatus.PAUSED -> stringResource(R.string.ops_status_paused)
    OpStatus.DONE -> stringResource(R.string.ops_status_done)
    OpStatus.FAILED -> stringResource(R.string.ops_status_failed)
    OpStatus.CANCELLED -> stringResource(R.string.ops_status_cancelled)
}

private fun kindIcon(kind: OpKind): ImageVector = when (kind) {
    OpKind.COPY -> Icons.Filled.ContentCopy
    OpKind.MOVE -> Icons.Filled.ContentCut
    OpKind.TRASH -> Icons.Filled.Delete
    OpKind.ZIP -> Icons.Filled.FolderZip
    OpKind.EXTRACT -> Icons.Filled.Archive
}
