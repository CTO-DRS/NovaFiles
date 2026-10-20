package com.novafiles.app.ui.analyzer

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.analyzer.DuplicateEngine
import com.novafiles.app.core.analyzer.DuplicateOptions
import com.novafiles.app.core.analyzer.DuplicateReport
import com.novafiles.app.core.analyzer.ImageDecoder
import com.novafiles.app.core.analyzer.JunkCategory
import com.novafiles.app.core.analyzer.JunkItem
import com.novafiles.app.core.analyzer.JunkScanner
import com.novafiles.app.core.analyzer.SnapshotRow
import com.novafiles.app.core.fs.FileIndexer
import com.novafiles.app.core.ops.OpKind
import com.novafiles.app.core.ops.OpSpec
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSSwitch
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.util.Format
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.14.0 — تبويبا المكررات والمنظّف.
 * الحذف دائماً: تحديد صريح ← حوار تأكيد ← نقل لمهملات OpQueue (قابل للاستعادة).
 */

// ══════════════════════════════════════════════════════════
//  المكررات
// ══════════════════════════════════════════════════════════

@Composable
internal fun DuplicatesTab(rows: List<SnapshotRow>) {
    val palette = LocalIOSPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = context.novaContainer

    var scanning by remember { mutableStateOf(false) }
    var scannedCount by remember { mutableStateOf(0) }
    var nearImages by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<DuplicateReport?>(null) }
    var openGroupId by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun startScan() {
        scanning = true
        selected = emptySet()
        scope.launch {
            val engine = DuplicateEngine(decoder = androidImageDecoder())
            val paths = rows.filter { !it.isDirectory && it.size > 0 }.map { it.path }
            val rep = withContext(Dispatchers.IO) {
                engine.scan(paths, DuplicateOptions(nearDuplicateImages = nearImages)) { scannedCount = it }
            }
            report = rep
            scanning = false
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            IOSGroupCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.dup_near_images),
                                style = TextStyle(fontSize = 15.sp),
                                color = palette.label,
                            )
                            Text(
                                stringResource(R.string.junk_age_hint, 7),
                                style = TextStyle(fontSize = 12.sp),
                                color = palette.secondaryLabel,
                            )
                        }
                        IOSSwitch(checked = nearImages, onCheckedChange = { nearImages = it })
                    }
                    Spacer(Modifier.height(12.dp))
                    IOSFilledButton(
                        text = if (scanning) stringResource(R.string.dup_scanning, scannedCount)
                        else stringResource(R.string.dup_scan),
                        onClick = { startScan() },
                    )
                }
            }
        }

        val rep = report
        if (rep != null) {
            if (rep.groups.isEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    IOSGroupCard {
                        IOSListRow(
                            title = stringResource(R.string.dup_no_groups),
                            showSeparator = false,
                        )
                    }
                }
            } else {
                item {
                    Spacer(Modifier.height(8.dp))
                    IOSGroupHeader(
                        text = stringResource(R.string.dup_result_title, rep.groups.size, Format.bytes(rep.totalWastedBytes)),
                    )
                }
                itemsIndexed(rep.groups) { gi, group ->
                    IOSGroupCard {
                        Column {
                            // رأس المجموعة: الهدر + النوع + فتح/إغلاق
                            IOSListRow(
                                title = group.items.first().path.substringAfterLast('/'),
                                subtitle = Format.bytes(group.wastedBytes) + " · " +
                                    stringResource(if (group.exact) R.string.dup_exact else R.string.dup_similar) +
                                    " · " + group.items.size,
                                showSeparator = group.items.size > 1 && openGroupId == gi,
                                showChevron = true,
                                onClick = { openGroupId = if (openGroupId == gi) null else gi },
                            )
                            if (openGroupId == gi) {
                                for (item in group.items) {
                                    val isSel = item.path in selected
                                    IOSListRow(
                                        title = item.path,
                                        subtitle = Format.bytes(item.size),
                                        showSeparator = true,
                                        onClick = {
                                            selected = if (isSel) selected - item.path else selected + item.path
                                        },
                                        trailing = {
                                            Box(
                                                Modifier
                                                    .size(22.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isSel) palette.accent else Color.Transparent)
                                                    .clickable {
                                                        selected = if (isSel) selected - item.path else selected + item.path
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (isSel) {
                                                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                if (selected.isNotEmpty()) {
                    item {
                        IOSFilledButton(
                            text = stringResource(R.string.dup_delete_selected, selected.size),
                            onClick = { confirmDelete = true },
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    if (confirmDelete && selected.isNotEmpty()) {
        IOSAlertDialog(
            title = stringResource(R.string.dup_confirm_title),
            message = stringResource(R.string.dup_confirm_text, selected.size),
            confirmText = stringResource(R.string.dup_delete_selected, selected.size),
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                val files = selected.map { File(it) }
                container.ops.enqueue(
                    OpSpec(
                        kind = OpKind.TRASH,
                        title = context.getString(R.string.analyzer_title),
                        sources = files,
                    ),
                )
                // أزل ما نُقل من المجموعات المعروضة
                selected = emptySet()
            },
        )
    }
}

// ══════════════════════════════════════════════════════════
//  المنظّف
// ══════════════════════════════════════════════════════════

@Composable
internal fun JunkTab() {
    val palette = LocalIOSPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = context.novaContainer

    var scanning by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<com.novafiles.app.core.analyzer.JunkReport?>(null) }
    var openCat by remember { mutableStateOf<JunkCategory?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmClean by remember { mutableStateOf(false) }

    fun startScan() {
        scanning = true
        selected = emptySet()
        scope.launch {
            val rep = withContext(Dispatchers.IO) {
                JunkScanner().scan(File(FileIndexer.ROOT))
            }
            report = rep
            scanning = false
        }
    }

    val rep = report
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            IOSGroupCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.junk_age_hint, 7),
                        style = TextStyle(fontSize = 12.sp),
                        color = palette.secondaryLabel,
                    )
                    Spacer(Modifier.height(12.dp))
                    IOSFilledButton(
                        text = stringResource(R.string.junk_scan),
                        onClick = { startScan() },
                    )
                }
            }
        }

        if (rep != null && rep.items.isEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                IOSGroupCard {
                    IOSListRow(title = stringResource(R.string.junk_no_junk), showSeparator = false)
                }
            }
        }

        if (rep != null && rep.items.isNotEmpty()) {
            val cats = listOf(
                JunkCategory.LEFTOVER_PARTIALS to R.string.junk_leftovers,
                JunkCategory.STALE_THUMBS to R.string.junk_stale_thumbs,
                JunkCategory.ORPHAN_APKS to R.string.junk_orphan_apks,
                JunkCategory.EMPTY_DIRS to R.string.junk_empty_dirs,
                JunkCategory.CONTENT_DUPLICATES to R.string.junk_content_dups,
            )
            for ((cat, res) in cats) {
                val items = rep.byCategory(cat)
                if (items.isEmpty()) continue
                item {
                    Spacer(Modifier.height(8.dp))
                    IOSGroupCard {
                        Column {
                            IOSListRow(
                                title = stringResource(res),
                                subtitle = stringResource(R.string.junk_items_count, items.size, Format.bytes(rep.bytes(cat))),
                                showChevron = true,
                                showSeparator = openCat == cat,
                                onClick = { openCat = if (openCat == cat) null else cat },
                            )
                            if (openCat == cat) {
                                IOSListRow(
                                    title = stringResource(R.string.junk_select_all),
                                    onClick = {
                                        val paths = items.map { it.path }.toSet()
                                        selected = if (selected.containsAll(paths)) selected - paths else selected + paths
                                    },
                                )
                                for (item in items.take(200)) {
                                    val isSel = item.path in selected
                                    IOSListRow(
                                        title = item.path,
                                        subtitle = when {
                                            item.category == JunkCategory.CONTENT_DUPLICATES ->
                                                (if (item.size > 0) Format.bytes(item.size) + " · " else "") +
                                                    "×${item.copies}"
                                            item.size > 0 -> Format.bytes(item.size)
                                            else -> null
                                        },
                                        onClick = {
                                            selected = if (isSel) selected - item.path else selected + item.path
                                        },
                                        trailing = {
                                            Box(
                                                Modifier
                                                    .size(22.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (isSel) palette.accent else Color.Transparent)
                                                    .clickable {
                                                        selected = if (isSel) selected - item.path else selected + item.path
                                                    },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (isSel) {
                                                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        },
                                    )
                                }
                                if (items.size > 200) {
                                    IOSListRow(
                                        title = "+${items.size - 200}",
                                        subtitle = null,
                                        showSeparator = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (selected.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(10.dp))
                    IOSFilledButton(
                        text = stringResource(R.string.junk_clean_selected, selected.size),
                        onClick = { confirmClean = true },
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (confirmClean && selected.isNotEmpty()) {
        IOSAlertDialog(
            title = stringResource(R.string.junk_confirm_title),
            message = stringResource(R.string.junk_confirm_text, selected.size),
            confirmText = stringResource(R.string.junk_clean_selected, selected.size),
            destructive = true,
            onDismiss = { confirmClean = false },
            onConfirm = {
                confirmClean = false
                val files = selected.filter { !it.endsWith("/") }.map { File(it) }
                if (files.isNotEmpty()) {
                    container.ops.enqueue(
                        OpSpec(
                            kind = OpKind.TRASH,
                            title = context.getString(R.string.analyzer_title),
                            sources = files,
                        ),
                    )
                }
                selected = emptySet()
            },
        )
    }
}

private fun androidImageDecoder(): ImageDecoder = ImageDecoder { file ->
    runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 9 && opts.outHeight / (sample * 2) >= 8) sample *= 2
        val decodeOpts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return@runCatching null
        val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, 9, 8, true)
        val px = IntArray(72)
        scaled.getPixels(px, 0, 9, 0, 0, 9, 8)
        IntArray(72) { i ->
            val p = px[i]
            // luma تقريبية: 0.299R + 0.587G + 0.114B
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
    }.getOrNull()
}
