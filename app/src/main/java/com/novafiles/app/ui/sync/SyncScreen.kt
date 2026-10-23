package com.novafiles.app.ui.sync

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novafiles.app.R
import com.novafiles.app.core.sync.SyncDirection
import com.novafiles.app.core.sync.SyncInterval
import com.novafiles.app.core.sync.SyncRunStatus
import com.novafiles.app.core.sync.SyncSchedule
import com.novafiles.app.data.db.SyncPairEntity
import com.novafiles.app.data.db.SyncRunEntity
import com.novafiles.app.data.sync.SyncRepository
import com.novafiles.app.core.sync.SyncScheduler
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.IOSSwitch
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.util.Format
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * v0.32.0 — شاشة أزواج المزامنة المجدولة (iPadOS).
 *
 * طبقة رقيقة فوق SyncPairEngine الخالص وSyncRepository:
 * قائمة الأزواج بحالتها الأخيرة، «شغّل الآن» اليدوي المضمون،
 * سجل الجولات الحلقي لكل زوج، وورقة إنشاء/تعديل كاملة.
 */
private val StatusOk = Color(0xFF34C759)
private val StatusBad = Color(0xFFFF3B30)
private val StatusWarn = Color(0xFFFF9500)
private val StatusIdle = Color(0xFF8E8E93)

@Composable
private fun statusColor(status: SyncRunStatus): Color = when (status) {
    SyncRunStatus.SUCCESS -> StatusOk
    SyncRunStatus.PARTIAL, SyncRunStatus.BLOCKED -> StatusWarn
    SyncRunStatus.FAILED -> StatusBad
    else -> StatusIdle
}

@Composable
private fun statusLabel(status: SyncRunStatus): String = when (status) {
    SyncRunStatus.RUNNING -> stringResource(R.string.sync_status_RUNNING)
    SyncRunStatus.SUCCESS -> stringResource(R.string.sync_status_SUCCESS)
    SyncRunStatus.PARTIAL -> stringResource(R.string.sync_status_PARTIAL)
    SyncRunStatus.FAILED -> stringResource(R.string.sync_status_FAILED)
    SyncRunStatus.CANCELLED -> stringResource(R.string.sync_status_CANCELLED)
    SyncRunStatus.BLOCKED -> stringResource(R.string.sync_status_BLOCKED)
}

/** تقصير المسار للعرض — يبقي النهاية (الاسم الأخير) وطوله 38 حرفاً كحد أقصى */
private fun shortPath(p: String): String {
    val clean = p.trim('/').ifEmpty { "/" }
    return if (clean.length <= 38) clean else "…${clean.takeLast(37)}"
}

@Composable
fun SyncScreen(initialPath: String?, onBack: () -> Unit) {
    val palette = LocalIOSPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val container = context.novaContainer
    val repo = container.sync

    val pairs by repo.observePairs().collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by container.db.cloudAccountsDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var sheetOpen by remember { mutableStateOf(false) }
    var editPair by remember { mutableStateOf<SyncPairEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<SyncPairEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(palette.groupedBg)) {
        IOSNavBar(
            title = stringResource(R.string.sync_title),
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (pairs.isEmpty()) {
                item {
                    IOSGroupHeader(stringResource(R.string.sync_empty_title))
                    IOSGroupCard {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                stringResource(R.string.sync_empty_body),
                                style = TextStyle(fontSize = 15.sp),
                                color = palette.secondaryLabel,
                            )
                            Spacer(Modifier.height(14.dp))
                            IOSFilledButton(
                                text = stringResource(R.string.sync_add_pair),
                                onClick = {
                                    editPair = null
                                    sheetOpen = true
                                },
                            )
                        }
                    }
                }
            }

            items(pairs, key = { it.id }) { pair ->
                SyncPairCard(
                    pair = pair,
                    hasCloudAccounts = accounts.isNotEmpty(),
                    onToggle = { enabled ->
                        scope.launch { repo.setEnabled(pair.id, enabled) }
                    },
                    onRunNow = {
                        SyncScheduler.runNow(context, pair.id)
                        message = context.getString(R.string.sync_started)
                    },
                    onEdit = {
                        editPair = pair
                        sheetOpen = true
                    },
                    onDelete = { deleteTarget = pair },
                )
            }

            if (pairs.isNotEmpty()) {
                item {
                    IOSGroupCard {
                        Column(Modifier.padding(16.dp)) {
                            IOSFilledButton(
                                text = stringResource(R.string.sync_add_pair),
                                onClick = {
                                    editPair = null
                                    sheetOpen = true
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    // ── ورقة الإنشاء/التعديل ──
    if (sheetOpen) {
        SyncPairSheet(
            editing = editPair,
            defaultLocalPath = initialPath,
            accounts = accounts,
            onDismiss = { sheetOpen = false },
            onSaved = {
                sheetOpen = false
                message = null
            },
        )
    }

    // ── تأكيد الحذف ──
    deleteTarget?.let { target ->
        IOSAlertDialog(
            title = stringResource(R.string.sync_delete),
            message = stringResource(R.string.sync_delete_confirm, target.name),
            confirmText = stringResource(R.string.sync_delete),
            destructive = true,
            onConfirm = {
                scope.launch { repo.delete(target.id) }
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }

    // ── رسالة عابرة ──
    message?.let { msg ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            androidx.compose.material3.Snackbar { Text(msg, style = TextStyle(fontSize = 14.sp)) }
        }
    }
}

/** بطاقة زوج مزامنة واحدة — حالة، أزرار، وسجل جولات قابل للفتح */
@Composable
private fun SyncPairCard(
    pair: SyncPairEntity,
    hasCloudAccounts: Boolean,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalIOSPalette.current
    var expanded by remember { mutableStateOf(false) }

    val directionLabel = when (SyncDirection.fromName(pair.direction)) {
        SyncDirection.ONE_WAY -> stringResource(R.string.sync_dir_one_way)
        SyncDirection.MIRROR -> stringResource(R.string.sync_dir_mirror)
        SyncDirection.TWO_WAY -> stringResource(R.string.sync_dir_two_way)
    }
    val interval = SyncInterval.fromName(pair.interval)
    val scheduleLabel = when (interval) {
        SyncInterval.MANUAL -> stringResource(R.string.sync_interval_manual)
        SyncInterval.HOURLY -> stringResource(R.string.sync_interval_hourly)
        SyncInterval.EVERY_6_HOURS -> stringResource(R.string.sync_interval_6h)
        SyncInterval.EVERY_12_HOURS -> stringResource(R.string.sync_interval_12h)
        SyncInterval.DAILY -> stringResource(R.string.sync_interval_daily)
        SyncInterval.WEEKLY -> stringResource(R.string.sync_interval_weekly)
    }
    val now = System.currentTimeMillis()
    val nextRun = SyncSchedule.nextRunAt(now, pair.lastRunAt, interval)
    val lastRunText = pair.lastRunAt?.let {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
    } ?: stringResource(R.string.sync_never_ran)
    val nextRunText = when {
        interval == SyncInterval.MANUAL -> "—"
        nextRun == null -> "—"
        SyncSchedule.isOverdue(now, nextRun) ->
            stringResource(R.string.sync_overdue)
        else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(nextRun))
    }

    IOSGroupCard {
        // الترويسة: الاسم + المفتاح
        IOSListRow(
            title = pair.name,
            subtitle = "${shortPath(pair.sourcePath)} → ${shortPath(pair.destPath)}",
            showSeparator = true,
            trailing = {
                IOSSwitch(checked = pair.enabled, onCheckedChange = onToggle)
            },
        )
        IOSListRow(
            title = "$directionLabel · $scheduleLabel",
            subtitle = null,
            showSeparator = true,
        )
        IOSListRow(
            title = "${stringResource(R.string.sync_last_run)}: $lastRunText",
            subtitle = "${stringResource(R.string.sync_next_run)}: $nextRunText",
            showSeparator = true,
        )
        if (!pair.enabled) {
            IOSListRow(
                title = stringResource(R.string.sync_disabled),
                subtitle = null,
                showSeparator = true,
            )
        }

        // سجل الجولات — يُجمع حياً عند الفتح
        if (expanded) {
            RunHistory(pairId = pair.id)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) {
                IOSFilledButton(
                    text = stringResource(R.string.sync_run_now),
                    onClick = onRunNow,
                )
            }
            Box(Modifier.weight(1f)) {
                IOSFilledButton(
                    text = stringResource(R.string.sync_history),
                    onClick = { expanded = !expanded },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Box(Modifier.weight(1f)) {
                IOSFilledButton(
                    text = stringResource(R.string.sync_edit),
                    onClick = onEdit,
                    enabled = true,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                IOSFilledButton(
                    text = stringResource(R.string.sync_delete),
                    onClick = onDelete,
                    destructive = true,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** سجل جولات زوج — آخر 10 جولات حية من Room */
@Composable
private fun RunHistory(pairId: Long) {
    val container = androidx.compose.ui.platform.LocalContext.current.novaContainer
    val runs by container.sync.observeRuns(pairId).collectAsStateWithLifecycle(initialValue = emptyList())

    if (runs.isEmpty()) {
        IOSListRow(
            title = stringResource(R.string.sync_history_empty),
            subtitle = null,
            showSeparator = true,
        )
        return
    }
    runs.forEach { run ->
        RunRow(run)
    }
}

@Composable
private fun RunRow(run: SyncRunEntity) {
    val palette = LocalIOSPalette.current
    val status = SyncRunStatus.fromName(run.status)
    val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(run.startedAt))
    IOSListRow(
        title = statusLabel(status),
        subtitle = stringResource(
            R.string.sync_run_detail,
            run.copied, run.updated, run.deleted, run.conflicts, run.failed,
        ),
        showSeparator = true,
        leading = {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(statusColor(status)),
            )
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    whenText,
                    style = TextStyle(fontSize = 11.sp),
                    color = palette.secondaryLabel,
                )
                if (run.bytes > 0) {
                    Text(
                        Format.bytes(run.bytes),
                        style = TextStyle(fontSize = 11.sp),
                        color = palette.secondaryLabel,
                    )
                }
            }
        },
    )
}
