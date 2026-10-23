package com.novafiles.app.ui.analyzer

import android.os.StatFs
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.analyzer.SnapshotRow
import com.novafiles.app.core.smart.DownloadClass
import com.novafiles.app.core.smart.DownloadClassifier
import com.novafiles.app.core.smart.MaintenanceSuggester
import com.novafiles.app.core.smart.PerDirBlob
import com.novafiles.app.core.smart.ReviewBox
import com.novafiles.app.core.smart.StorageForecaster
import com.novafiles.app.core.smart.StoragePoint
import com.novafiles.app.core.smart.StoragePulse
import com.novafiles.app.core.smart.PulseReport
import com.novafiles.app.core.smart.ForecastState
import com.novafiles.app.core.smart.ReviewBundle
import com.novafiles.app.data.db.StoragePointEntity
import com.novafiles.app.data.db.SmartDismissEntity
import com.novafiles.app.core.analyzer.DuplicateEngine
import com.novafiles.app.core.analyzer.DuplicateOptions
import com.novafiles.app.core.fs.FileIndexer
import com.novafiles.app.core.ops.OpKind
import com.novafiles.app.core.ops.OpSpec
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.util.Format
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.34.0 — تبويب «نبضة التخزين» داخل المحلل:
 * اتجاه الاستهلاك (انحدار خطي أسبوعي) + أكبر المصادر النامية
 * + اقتراحات الصيانة مرتبة بالوفر + صندوق المراجعة الأسبوعي للتنزيلات.
 * كل الحذف عبر مهملات العمليات — لا حذف صامت أبداً.
 */

private const val LAST_REVIEW_KEY = "smart_last_review"

@Composable
internal fun PulseTab(rows: List<SnapshotRow>, loading: Boolean) {
    val palette = LocalIOSPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = context.novaContainer

    var points by remember { mutableStateOf<List<StoragePoint>>(emptyList()) }
    var capacity by remember { mutableStateOf(0L) }
    var dismissed by remember { mutableStateOf(emptySet<String>()) }
    var clusters by remember { mutableStateOf(emptyList<MaintenanceSuggester.DuplicateCluster>()) }
    var report by remember { mutableStateOf<PulseReport?>(null) }
    var dupScanning by remember { mutableStateOf(false) }
    var dupProgress by remember { mutableStateOf(0) }
    var reviewLast by remember { mutableStateOf<Long?>(null) }
    var bundle by remember { mutableStateOf<ReviewBundle?>(null) }
    var pendingDelete by remember { mutableStateOf<List<String>>(emptyList()) }

    suspend fun loadReviewAndGet(): Long? = withContext(Dispatchers.IO) {
        container.settings.peekRaw()[androidx.datastore.preferences.core.stringPreferencesKey(LAST_REVIEW_KEY)]?.toLongOrNull()
    }

    fun rebuildReview(d: Set<String>, last: Long?) {
        val now = System.currentTimeMillis()
        val downloads = rows.filter { !it.isDirectory && DownloadClassifier.isDownloadPath(it.path, FileIndexer.ROOT) }
        val classified = DownloadClassifier.classifyAll(downloads, now)
        bundle = ReviewBox.build(
            items = ReviewBox.itemsOf(classified),
            lastReviewedAt = last,
            nowMs = now,
            dismissedPaths = d.filter { it.startsWith("REVIEW:") }.map { it.removePrefix("REVIEW:") }.toSet(),
        )
    }

    fun rebuildReport(pts: List<StoragePoint>, cap: Long, d: Set<String>, cl: List<MaintenanceSuggester.DuplicateCluster>) {
        report = StoragePulse.report(
            points = pts,
            capacityBytes = cap,
            rows = rows,
            clusters = cl,
            nowMs = System.currentTimeMillis(),
            dismissedKeys = d,
        )
    }

    LaunchedEffect(rows) {
        if (loading) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val dao = container.db.storagePointsDao()
            var pts = dao.allByTime().map {
                StoragePoint(it.capturedAt, it.usedBytes, PerDirBlob.decode(it.perDirBlob))
            }
            val now = System.currentTimeMillis()
            if (StorageForecaster.shouldCapture(pts, now)) {
                val p = StorageForecaster.capturePoint(rows, now, FileIndexer.ROOT)
                dao.insert(
                    StoragePointEntity(
                        capturedAt = p.capturedAt,
                        usedBytes = p.usedBytes,
                        perDirBlob = PerDirBlob.encode(p.perDir),
                    ),
                )
                pts = dao.allByTime().map {
                    StoragePoint(it.capturedAt, it.usedBytes, PerDirBlob.decode(it.perDirBlob))
                }
            }
            val cap = runCatching { StatFs(FileIndexer.ROOT).totalBytes }.getOrDefault(0L)
            val d = container.db.smartDismissDao().allKeys().toSet()
            points = pts
            capacity = cap
            dismissed = d
            rebuildReport(pts, cap, d, clusters)
        }
        loadReviewAndGet().let { last ->
            reviewLast = last
            rebuildReview(dismissed, last)
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // ═══════════ اتجاه الاستهلاك ═══════════
        item { IOSGroupHeader(text = stringResource(R.string.pulse_trend_title)) }
        item {
            IOSGroupCard {
                Column(Modifier.padding(16.dp)) {
                    val f = report?.trend
                    if (f == null) {
                        Text(
                            stringResource(R.string.pulse_trend_insufficient),
                            style = TextStyle(fontSize = 14.sp),
                            color = palette.secondaryLabel,
                        )
                    } else {
                        Text(
                            text = when (f.state) {
                                ForecastState.GROWING ->
                                    stringResource(R.string.pulse_trend_growing, formatWeeks(f.weeksToFull ?: 0.0))
                                ForecastState.STABLE -> stringResource(R.string.pulse_trend_stable)
                                ForecastState.FREEING -> stringResource(R.string.pulse_trend_freeing)
                                ForecastState.FULL -> stringResource(R.string.pulse_trend_full)
                                ForecastState.INSUFFICIENT_DATA -> stringResource(R.string.pulse_trend_insufficient)
                            },
                            style = TextStyle(fontSize = 15.sp),
                            color = palette.label,
                        )
                        if (f.state == ForecastState.GROWING || f.state == ForecastState.FREEING) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.pulse_weekly_rate, Format.bytes(f.weeklyRateBytes)),
                                style = TextStyle(fontSize = 13.sp),
                                color = palette.secondaryLabel,
                            )
                        }
                        if (f.state != ForecastState.INSUFFICIENT_DATA) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.pulse_confidence, (f.confidence * 100).toInt()),
                                style = TextStyle(fontSize = 13.sp),
                                color = palette.secondaryLabel,
                            )
                        }
                    }
                }
            }
        }

        // ═══════════ أكبر المصادر النامية ═══════════
        item { IOSGroupHeader(text = stringResource(R.string.pulse_growing_title)) }
        item {
            IOSGroupCard {
                Column {
                    val growing = report?.growingDirs.orEmpty()
                    if (growing.isEmpty()) {
                        IOSListRow(
                            title = stringResource(R.string.pulse_no_growth),
                            showSeparator = false,
                        )
                    } else {
                        growing.forEachIndexed { i, g ->
                            IOSListRow(
                                title = g.path.ifEmpty { stringResource(R.string.pulse_root_dir) },
                                subtitle = "+" + Format.bytes(g.growthBytes),
                                showSeparator = i < growing.size - 1,
                                leading = {
                                    Spacer(
                                        Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFFF3B30)),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        // ═══════════ اقتراحات الصيانة ═══════════
        item {
            IOSGroupHeader(
                text = stringResource(R.string.pulse_suggestions_title) +
                    " · " + stringResource(R.string.pulse_scan_dups),
            )
        }
        item {
            IOSGroupCard {
                Column(Modifier.padding(16.dp)) {
                    IOSFilledButton(
                        text = if (dupScanning) stringResource(R.string.pulse_dup_scanning, dupProgress)
                        else stringResource(R.string.pulse_scan_dups),
                        onClick = {
                            if (!dupScanning) {
                                dupScanning = true
                                dupProgress = 0
                                scope.launch {
                                    val paths = rows.filter { !it.isDirectory && it.size > 0 }.map { it.path }
                                    val rep = withContext(Dispatchers.IO) {
                                        DuplicateEngine().scan(paths, DuplicateOptions()) { dupProgress = it }
                                    }
                                    clusters = rep.groups.map { g ->
                                        MaintenanceSuggester.DuplicateCluster(
                                            paths = g.items.map { it.path },
                                            sizeBytes = g.items.first().size,
                                        )
                                    }
                                    rebuildReport(points, capacity, dismissed, clusters)
                                    dupScanning = false
                                }
                            }
                        },
                    )
                }
            }
        }
        item {
            val suggestions = report?.suggestions.orEmpty()
            if (suggestions.isEmpty()) {
                IOSGroupCard {
                    IOSListRow(
                        title = stringResource(R.string.pulse_no_suggestions),
                        showSeparator = false,
                    )
                }
            } else {
                IOSGroupCard {
                    Column {
                        suggestions.forEachIndexed { i, s ->
                            IOSListRow(
                                title = s.title,
                                subtitle = "+" + Format.bytes(s.savingsBytes) + " · " +
                                    stringResource(reasonRes(s.reasonKey), *s.reasonArgs.toTypedArray()),
                                showSeparator = i < suggestions.size - 1,
                                trailing = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.pulse_dismiss_cd),
                                        tint = palette.tertiaryLabel,
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable {
                                                dismissed = dismissed + s.key
                                                report = report?.copy(
                                                    suggestions = report?.suggestions.orEmpty().filterNot { it.key == s.key },
                                                )
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        container.db.smartDismissDao().add(SmartDismissEntity(s.key))
                                                    }
                                                }
                                            },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        // ═══════════ صندوق المراجعة الأسبوعي ═══════════
        item { IOSGroupHeader(text = stringResource(R.string.pulse_review_title)) }
        item {
            val b = bundle
            IOSGroupCard {
                Column {
                    Text(
                        text = stringResource(R.string.pulse_review_hint),
                        style = TextStyle(fontSize = 12.sp),
                        color = palette.secondaryLabel,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                    )
                    if (b != null && !b.due) {
                        IOSListRow(
                            title = stringResource(R.string.pulse_review_not_due, b.daysSinceLastReview ?: 0L),
                            showSeparator = false,
                        )
                    }
                    if (b?.due == true) {
                        IOSListRow(
                            title = stringResource(R.string.review_snooze),
                            showSeparator = false,
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        container.settings.putRaw(LAST_REVIEW_KEY, System.currentTimeMillis().toString())
                                    }
                                    val last = loadReviewAndGet()
                                    reviewLast = last
                                    rebuildReview(dismissed, last)
                                }
                            },
                        )
                    }
                }
            }
        }
        val b = bundle
        if (b?.due == true) {
            if (b.groups.isEmpty()) {
                item {
                    IOSGroupCard {
                        IOSListRow(
                            title = stringResource(R.string.pulse_empty_downloads),
                            showSeparator = false,
                        )
                    }
                }
            }
            for ((clazz, items) in b.groups) {
                item {
                    Spacer(Modifier.height(2.dp))
                    IOSGroupCard {
                        Column {
                            IOSListRow(
                                title = stringResource(classTitleRes(clazz)) + " (${items.size})",
                                subtitle = Format.bytes(items.sumOf { it.size }),
                                showSeparator = true,
                                destructive = clazz == DownloadClass.CLUTTER,
                            )
                            items.forEachIndexed { i, it0 ->
                                IOSListRow(
                                    title = it0.name,
                                    subtitle = Format.bytes(it0.size) + " · " +
                                        stringResource(R.string.junk_age_hint, it0.ageDays),
                                    showSeparator = i < items.size - 1,
                                    trailing = {
                                        Row {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = stringResource(R.string.review_keep_cd),
                                                tint = palette.green,
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clickable {
                                                        scope.launch {
                                                            withContext(Dispatchers.IO) {
                                                                container.db.smartDismissDao()
                                                                    .add(SmartDismissEntity("REVIEW:" + it0.path))
                                                            }
                                                            val d = dismissed + "REVIEW:" + it0.path
                                                            dismissed = d
                                                            rebuildReview(d, reviewLast)
                                                        }
                                                    },
                                            )
                                            Spacer(Modifier.width(14.dp))
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = stringResource(R.string.review_delete_cd),
                                                tint = palette.destructive,
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .clickable { pendingDelete = listOf(it0.path) },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (pendingDelete.isNotEmpty()) {
        IOSAlertDialog(
            title = stringResource(R.string.review_confirm_title),
            message = stringResource(R.string.review_confirm_text, pendingDelete.size),
            confirmText = stringResource(R.string.review_delete_cd),
            destructive = true,
            onDismiss = { pendingDelete = emptyList() },
            onConfirm = {
                val files = pendingDelete.map { File(it) }
                scope.launch {
                    withContext(Dispatchers.IO) {
                        container.db.smartDismissDao().add(SmartDismissEntity("REVIEW:" + pendingDelete.first()))
                    }
                    dismissed = dismissed + ("REVIEW:" + pendingDelete.first())
                    container.ops.enqueue(
                        OpSpec(
                            kind = OpKind.TRASH,
                            title = context.getString(R.string.pulse_review_title),
                            sources = files,
                        ),
                    )
                    rebuildReview(dismissed, reviewLast)
                }
                pendingDelete = emptyList()
            },
        )
    }
}

/** مفتاح سبب الاقتراح → مورد السلسلة */
private fun reasonRes(key: String): Int = when (key) {
    "sugg_reason_duplicates" -> R.string.sugg_reason_duplicates
    "sugg_reason_cache" -> R.string.sugg_reason_cache
    "sugg_reason_clutter" -> R.string.sugg_reason_clutter
    "sugg_reason_apks" -> R.string.sugg_reason_apks
    else -> R.string.pulse_no_suggestions
}

/** عنوان فئة التصنيف */
fun classTitleRes(c: DownloadClass): Int = when (c) {
    DownloadClass.DOCUMENT -> R.string.class_document
    DownloadClass.MEDIA -> R.string.class_media
    DownloadClass.INSTALLER -> R.string.class_installer
    DownloadClass.ARCHIVE -> R.string.class_archive
    DownloadClass.CLUTTER -> R.string.class_clutter
}

/** تنسيق الأسابيع: رقم واحد عشري تحت 10 وإلا صحيح */
private fun formatWeeks(w: Double): String =
    if (w >= 10.0) String.format(Locale.US, "%.0f", w) else String.format(Locale.US, "%.1f", w)
