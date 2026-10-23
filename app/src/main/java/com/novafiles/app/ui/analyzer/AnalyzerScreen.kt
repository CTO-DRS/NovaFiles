package com.novafiles.app.ui.analyzer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.analyzer.Categories
import com.novafiles.app.core.analyzer.NotableFile
import com.novafiles.app.core.analyzer.SnapshotRow
import com.novafiles.app.core.analyzer.StorageAnalyzer
import com.novafiles.app.core.analyzer.StorageCategory
import com.novafiles.app.core.analyzer.StorageReport
import com.novafiles.app.core.analyzer.Treemap
import com.novafiles.app.core.analyzer.TreemapNode
import com.novafiles.app.core.analyzer.TreemapRect
import com.novafiles.app.core.fs.FileIndexer
import com.novafiles.app.core.report.ReportExporter
import com.novafiles.app.data.db.FileIndexEntity
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.IOSNavIconButton
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.util.Intents
import com.novafiles.app.util.ReportFiles
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.14.0 — شاشة تحليل التخزين (ذكاء التخزين)
 * خمس تبويبات بنمط iOS: نظرة عامة / الخريطة / المكررات / المنظّف / نبضة (v0.34).
 * البيانات من لقطة الفهرس (لا مسح ثانٍ للقرص).
 * تبويب النبضة يخضع لمفتاح الطبقة الذكية في الإعدادات.
 */
enum class AnalyzerTab { OVERVIEW, MAP, DUPLICATES, JUNK, PULSE }

/** ألوان الفئات — لوحة iOS مميزة لكل فئة */
fun categoryColor(c: StorageCategory): Color = when (c) {
    StorageCategory.VIDEO -> Color(0xFF5856D6)
    StorageCategory.AUDIO -> Color(0xFFFF2D55)
    StorageCategory.IMAGES -> Color(0xFF34C759)
    StorageCategory.DOCS -> Color(0xFFFF9500)
    StorageCategory.APPS -> Color(0xFF007AFF)
    StorageCategory.ARCHIVES -> Color(0xFFAF52DE)
    StorageCategory.OTHER -> Color(0xFF8E8E93)
}

fun categoryTitleRes(c: StorageCategory): Int = when (c) {
    StorageCategory.VIDEO -> R.string.cat_video
    StorageCategory.AUDIO -> R.string.cat_audio
    StorageCategory.IMAGES -> R.string.cat_images
    StorageCategory.DOCS -> R.string.cat_docs
    StorageCategory.APPS -> R.string.cat_apps
    StorageCategory.ARCHIVES -> R.string.cat_archives
    StorageCategory.OTHER -> R.string.cat_other
}

@Composable
fun AnalyzerScreen(onBack: () -> Unit) {
    val palette = LocalIOSPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = context.novaContainer

    var tab by remember { mutableStateOf(AnalyzerTab.OVERVIEW) }
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf<List<SnapshotRow>>(emptyList()) }
    var report by remember { mutableStateOf<StorageReport?>(null) }
    var tree by remember { mutableStateOf<Map<String, com.novafiles.app.core.analyzer.DirNode>>(emptyMap()) }
    val smartEnabled by container.settings.smartEnabled.collectAsState(initial = true)

    // إيقاف الطبقة الذكية يعيد المستخدم للنظرة العامة فوراً
    LaunchedEffect(smartEnabled) {
        if (!smartEnabled && tab == AnalyzerTab.PULSE) tab = AnalyzerTab.OVERVIEW
    }

    fun loadSnapshot() {
        loading = true
        scope.launch {
            withContext(Dispatchers.IO) {
                FileIndexer.ensureIndexed(container.db)
                val entities: List<FileIndexEntity> = container.db.fileIndexDao().all()
                val snap = entities.map {
                    SnapshotRow(it.path, it.name, it.parent, it.size, it.modified, it.isDirectory)
                }
                rows = snap
                report = StorageAnalyzer.analyze(snap)
                tree = StorageAnalyzer.aggregateTree(snap)
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { loadSnapshot() }

    Column(modifier = Modifier.fillMaxSize().background(palette.groupedBg)) {
        IOSNavBar(
            title = stringResource(R.string.analyzer_title),
            subtitle = stringResource(R.string.analyzer_subtitle),
            onBack = onBack,
        ) {
            // v0.36 — تصدير تقرير التخزين الحالي CSV+JSON إلى مجلد التقارير ومشاركته
            IOSNavIconButton(
                icon = Icons.Filled.IosShare,
                contentDescription = stringResource(R.string.report_export_storage),
                onClick = {
                    val rep = report ?: return@IOSNavIconButton
                    val currentRows = rows
                    val currentTree = tree
                    scope.launch(Dispatchers.IO) {
                        val now = System.currentTimeMillis()
                        val csv = ReportExporter.storageCsv(rep)
                        ReportFiles.write("storage", "csv", csv, now)
                        ReportFiles.write("storage", "json", ReportExporter.storageJson(rep), now)
                        ReportFiles.write(
                            "dirs", "csv",
                            ReportExporter.dirsCsv(currentTree.values.toList()), now,
                        )
                        ReportFiles.write(
                            "notable", "csv",
                            ReportExporter.notableCsv(currentRows.filter { !it.isDirectory }
                                .sortedByDescending { it.size }.take(200)
                                .map { NotableFile(it.path, it.name, it.size, it.modified) }),
                        )
                        val f = ReportFiles.write("storage", "csv", csv, now) // المشاركة: CSV
                        // المشاركة تفتح لآخر ملف كُتب — أعد استخدام أول CSV حتمي
                        if (f != null) Intents.share(context, f)
                    }
                },
            )
            IOSNavIconButton(icon = Icons.Filled.Refresh, contentDescription = null, onClick = { loadSnapshot() })
        }

        AnalyzerTabBar(selected = tab, tabs = tabsFor(smartEnabled), onSelect = { tab = it })

        when (tab) {
            AnalyzerTab.OVERVIEW -> OverviewTab(loading, rows, report)
            AnalyzerTab.MAP -> MapTab(loading, rows, tree)
            AnalyzerTab.DUPLICATES -> DuplicatesTab(rows)
            AnalyzerTab.JUNK -> JunkTab()
            AnalyzerTab.PULSE -> PulseTab(rows, loading)
        }
    }
}

// ═══════════════ شريط التبويبات ═══════════════

/** تبويبات المحلل — النبضة تظهر فقط مع تفعيل الطبقة الذكية */
private fun tabsFor(smartEnabled: Boolean): List<Pair<AnalyzerTab, Int>> = buildList {
    add(AnalyzerTab.OVERVIEW to R.string.analyzer_tab_overview)
    add(AnalyzerTab.MAP to R.string.analyzer_tab_map)
    add(AnalyzerTab.DUPLICATES to R.string.analyzer_tab_duplicates)
    add(AnalyzerTab.JUNK to R.string.analyzer_tab_junk)
    if (smartEnabled) add(AnalyzerTab.PULSE to R.string.analyzer_tab_pulse)
}

@Composable
private fun AnalyzerTabBar(
    selected: AnalyzerTab,
    tabs: List<Pair<AnalyzerTab, Int>>,
    onSelect: (AnalyzerTab) -> Unit,
) {
    val palette = LocalIOSPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.fill),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        tabs.forEach { (t, res) ->
            val isSel = t == selected
            Text(
                text = stringResource(res),
                fontSize = 13.sp,
                maxLines = 1,
                color = if (isSel) palette.label else palette.secondaryLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSel) palette.cardBg else Color.Transparent)
                    .clickable { onSelect(t) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
                    .weight(1f, fill = true),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ═══════════════ تبويب النظرة العامة ═══════════════

@Composable
private fun OverviewTab(
    loading: Boolean,
    rows: List<SnapshotRow>,
    report: StorageReport?,
) {
    val palette = LocalIOSPalette.current
    if (loading) {
        LoadingView()
        return
    }
    val rep = report ?: return
    val large = remember(rows) { StorageAnalyzer.largeFiles(rows, minSizeBytes = 50L * 1024 * 1024, limit = 20) }
    val old = remember(rows) {
        StorageAnalyzer.oldFiles(rows, System.currentTimeMillis(), olderThanDays = 365, minSizeBytes = 10L * 1024 * 1024, limit = 20)
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            IOSGroupHeader(text = stringResource(R.string.analyzer_used) + " · " +
                com.novafiles.app.util.Format.bytes(rep.usedBytes))
        }
        item {
            IOSGroupCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.analyzer_files_count, rep.totalFiles, rep.totalDirs),
                        style = TextStyle(fontSize = 15.sp),
                        color = palette.label,
                    )
                    Spacer(Modifier.height(12.dp))
                    // شريط نسب الفئات المكوّم
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp)),
                    ) {
                        rep.bySizeDesc.forEach { c ->
                            val frac = if (rep.usedBytes > 0) c.totalBytes.toFloat() / rep.usedBytes else 0f
                            Box(
                                Modifier
                                    .weight(frac.coerceIn(0.005f, 1f))
                                    .fillMaxSize()
                                    .background(categoryColor(c.category)),
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        items(rep.bySizeDesc.filter { it.fileCount > 0 }) { c ->
            IOSGroupCard {
                IOSListRow(
                    title = stringResource(categoryTitleRes(c.category)),
                    subtitle = com.novafiles.app.util.Format.bytes(c.totalBytes) + " · " + c.fileCount,
                    leading = {
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(categoryColor(c.category)),
                        )
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        // الملفات الكبيرة والأقدم — لوحة من الفهرس الحالي (بلا مسح جديد)
        item {
            Spacer(Modifier.height(10.dp))
            IOSGroupHeader(text = stringResource(R.string.analyzer_large))
        }
        if (large.isEmpty()) {
            item {
                IOSGroupCard {
                    IOSListRow(
                        title = stringResource(R.string.analyzer_no_files),
                        showSeparator = false,
                    )
                }
            }
        } else {
            items(large) { f ->
                IOSGroupCard {
                    NotableRow(f, subtitle = com.novafiles.app.util.Format.bytes(f.size))
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            IOSGroupHeader(text = stringResource(R.string.analyzer_old))
        }
        items(old) { f ->
            IOSGroupCard {
                NotableRow(
                    f,
                    subtitle = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(f.modified)) +
                        " · " + com.novafiles.app.util.Format.bytes(f.size),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun NotableRow(f: NotableFile, subtitle: String) {
    IOSListRow(
        title = f.name,
        subtitle = subtitle,
        showSeparator = false,
        leading = {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(categoryColor(Categories.of(f.name))),
            )
        },
    )
}

// ═══════════════ تبويب الخريطة (Treemap) ═══════════════

@Composable
private fun MapTab(
    loading: Boolean,
    rows: List<SnapshotRow>,
    tree: Map<String, com.novafiles.app.core.analyzer.DirNode>,
) {
    val palette = LocalIOSPalette.current
    var currentDir by remember { mutableStateOf(FileIndexer.ROOT) }

    if (loading) {
        LoadingView()
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // فتات الخبز — التنقل لأعلى
        Breadcrumbs(currentDir, onNavigate = { currentDir = it })

        Text(
            text = stringResource(R.string.map_hint),
            style = TextStyle(fontSize = 12.sp),
            color = palette.secondaryLabel,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        val children = remember(currentDir, rows, tree) {
            StorageAnalyzer.childrenForTreemap(currentDir, rows, tree)
        }

        if (children.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_folder), color = palette.secondaryLabel)
            }
            return@Column
        }

        // لوحة الرسم: squarify على أبعاد اللوحة بالبكسل الفعلي + لمس للتنقل
        // (Canvas يرسم بالبكسل — يجب أن تُحسب المستطيلات بنفس المساحة كي تصيب اللمس)
        BoxWithConstraints(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            val density = LocalDensity.current
            val wDp = maxWidth
            val hDp = 420.dp
            val wPx = with(density) { wDp.toPx() }
            val hPx = with(density) { hDp.toPx() }
            val rects = remember(children, wPx, hPx) {
                Treemap.squarify(children, wPx, hPx, padding = 2f)
            }
            Canvas(
                modifier = Modifier
                    .width(wDp)
                    .height(hDp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.fill)
                    .pointerInput(rects) {
                        detectTapGestures { pos ->
                            val hit = rects.lastOrNull {
                                pos.x >= it.x && pos.x <= it.x + it.w && pos.y >= it.y && pos.y <= it.y + it.h
                            }
                            if (hit != null && hit.node.isDir) currentDir = hit.node.path
                        }
                    },
            ) {
                for (r in rects) {
                    val color = if (r.node.isDir) {
                        Color(0xFF0A84FF).copy(alpha = 0.85f)
                    } else {
                        categoryColor(Categories.of(r.node.label)).copy(alpha = 0.9f)
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(r.x, r.y),
                        size = Size(r.w, r.h),
                        cornerRadius = CornerRadius(4f, 4f),
                    )
                }
            }
        }

        // دليل الألوان
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StorageCategory.entries.take(5).forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(categoryColor(c)))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(categoryTitleRes(c)), fontSize = 10.sp, color = palette.secondaryLabel, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Breadcrumbs(currentDir: String, onNavigate: (String) -> Unit) {
    val palette = LocalIOSPalette.current
    val rootLabel = stringResource(R.string.map_root)
    val parts = remember(currentDir) {
        buildList {
            add(rootLabel to FileIndexer.ROOT)
            var acc = FileIndexer.ROOT
            currentDir.removePrefix(FileIndexer.ROOT).split('/').filter { it.isNotEmpty() }.forEach { seg ->
                acc = "$acc/$seg"
                add(seg to acc)
            }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                parts.forEachIndexed { i, (label, path) ->
                    if (i > 0) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = palette.tertiaryLabel,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = palette.accent,
                        modifier = Modifier.clickable { onNavigate(path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingView() {
    val palette = LocalIOSPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.analyzer_indexing), color = palette.secondaryLabel)
    }
}
