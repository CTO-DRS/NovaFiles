package com.novafiles.app.ui.recents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.os.Environment
import android.text.format.Formatter
import com.novafiles.app.R
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.core.fs.FileIndexer
import com.novafiles.app.core.fs.LocalBrowser
import com.novafiles.app.core.fs.RecentsEngine
import com.novafiles.app.core.fs.RecentFile
import com.novafiles.app.core.fs.RelativeTime
import com.novafiles.app.core.shelves.SmartShelvesEngine
import com.novafiles.app.core.shelves.SuggestionsEngine
import com.novafiles.app.data.db.FavoriteEntity
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.ui.ios.rememberBottomBarClearance
import com.novafiles.app.util.TypeIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * شاشة «الأخيرة» — v0.19 أساسها، v0.33 يضيف «الرفوف الذكية»:
 * تبويبات علوية (الأخيرة + 4 رفوف حتمية فوق الفهرس) وبطاقة
 * «نبضة سريعة» من SuggestionsEngine (أساس نبضة التخزين v0.34).
 */

/** تبويبات الشاشة: الأخيرة + الرفوف الأربعة */
private enum class ShelfTab { RECENT, RECENT_DOCS, LARGE_DOWNLOADS, STALE_90D, OLD_SCREENSHOTS }

@Composable
fun RecentScreen(onOpenFile: (File) -> Unit) {
    val context = LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    val palette = LocalIOSPalette.current

    var loading by remember { mutableStateOf(true) }
    var files by remember { mutableStateOf<List<RecentFile>>(emptyList()) }
    var shelves by remember { mutableStateOf<Map<SmartShelvesEngine.ShelfId, List<SmartShelvesEngine.IndexedFile>>>(emptyMap()) }
    var suggestions by remember { mutableStateOf<List<SuggestionsEngine.Suggestion>>(emptyList()) }
    var tab by remember { mutableStateOf(ShelfTab.RECENT) }
    val favorites by container.db.favoritesDao().observeAll().collectAsState(initial = emptyList())
    val favPaths = remember(favorites) { favorites.map { it.path }.toSet() }

    LaunchedEffect(Unit) {
        loading = true
        files = withContext(Dispatchers.IO) {
            RecentsEngine.collect(
                roots = listOf(Environment.getExternalStorageDirectory()),
                maxDepth = 3,
                limit = 150,
            )
        }
        // v0.33 — الرفوف الذكية فوق الفهرس الفوري (تحفيز الفهرسة إن لزم)
        withContext(Dispatchers.IO) {
            runCatching {
                FileIndexer.ensureIndexed(container.db)
                val index = container.db.fileIndexDao().all().map { row ->
                    SmartShelvesEngine.IndexedFile(
                        path = row.path, name = row.name, parent = row.parent,
                        size = row.size, modified = row.modified, isDirectory = row.isDirectory,
                    )
                }
                val now = System.currentTimeMillis()
                shelves = SmartShelvesEngine.compute(
                    index = index,
                    now = now,
                    recentlyOpened = files.map { it.path }.toSet(),
                )
                suggestions = SuggestionsEngine.compute(index, now)
            }
        }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.groupedBg),
    ) {
        IOSNavBar(title = stringResource(R.string.nav_recents))

        // v0.33 — تبويبات الرفوف الذكية
        ShelfTabsRow(tab = tab, shelfCounts = shelves.mapValues { it.value.size }, onSelect = { tab = it })

        when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            tab == ShelfTab.RECENT && files.isEmpty() ->
                EmptyShelfView()
            tab != ShelfTab.RECENT && (shelves[tab.toShelfId()] ?: emptyList()).isEmpty() ->
                EmptyShelfView()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp + rememberBottomBarClearance()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // v0.33 — بطاقة «نبضة سريعة» أعلى قائمة الأخيرة
                if (tab == ShelfTab.RECENT && suggestions.isNotEmpty()) {
                    item(key = "suggestions") {
                        SuggestionsCard(suggestions)
                    }
                }
                val shown: List<RecentFile> = when (tab) {
                    ShelfTab.RECENT -> files
                    else -> (shelves[tab.toShelfId()] ?: emptyList()).map {
                        RecentFile(path = it.path, name = it.name, size = it.size, modifiedAt = it.modified)
                    }
                }
                items(shown, key = { it.path }) { rf ->
                    RecentRow(
                        file = rf,
                        favorite = favPaths.contains(rf.path),
                        now = System.currentTimeMillis(),
                        onOpen = { onOpenFile(File(rf.path)) },
                        onToggleFavorite = {
                            scope.launch {
                                if (favPaths.contains(rf.path)) {
                                    container.db.favoritesDao().remove(rf.path)
                                } else {
                                    container.db.favoritesDao().add(
                                        FavoriteEntity(path = rf.path, name = rf.name, isDirectory = false),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun ShelfTab.toShelfId(): SmartShelvesEngine.ShelfId? = when (this) {
    ShelfTab.RECENT -> null
    ShelfTab.RECENT_DOCS -> SmartShelvesEngine.ShelfId.RECENT_DOCS
    ShelfTab.LARGE_DOWNLOADS -> SmartShelvesEngine.ShelfId.LARGE_DOWNLOADS
    ShelfTab.STALE_90D -> SmartShelvesEngine.ShelfId.STALE_90D
    ShelfTab.OLD_SCREENSHOTS -> SmartShelvesEngine.ShelfId.OLD_SCREENSHOTS
}

@Composable
private fun EmptyShelfView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.History,
                contentDescription = null,
                tint = LocalIOSPalette.current.secondaryLabel.copy(alpha = 0.45f),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.recents_empty),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.recents_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = LocalIOSPalette.current.secondaryLabel.copy(alpha = 0.8f),
            )
        }
    }
}

/** صف تبويبات: الأخيرة + الرفوف الأربعة (أفقي قابل للتمرير) */
@Composable
private fun ShelfTabsRow(
    tab: ShelfTab,
    shelfCounts: Map<SmartShelvesEngine.ShelfId, Int>,
    onSelect: (ShelfTab) -> Unit,
) {
    val palette = LocalIOSPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tabs = listOf(
            ShelfTab.RECENT to stringResource(R.string.shelves_tab_recent),
            ShelfTab.RECENT_DOCS to stringResource(R.string.shelves_recent_docs),
            ShelfTab.LARGE_DOWNLOADS to stringResource(R.string.shelves_large_downloads),
            ShelfTab.STALE_90D to stringResource(R.string.shelves_stale_90),
            ShelfTab.OLD_SCREENSHOTS to stringResource(R.string.shelves_old_screenshots),
        )
        tabs.forEach { (t, label) ->
            val selected = tab == t
            val count = t.toShelfId()?.let { shelfCounts[it] ?: 0 } ?: 0
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) palette.accent else palette.barBg)
                    .clickable { onSelect(t) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) androidx.compose.ui.graphics.Color.White else palette.label,
                )
                if (t != ShelfTab.RECENT && count > 0) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "$count",
                        style = MaterialTheme.typography.labelSmall,
                        color = (if (selected) androidx.compose.ui.graphics.Color.White else palette.label)
                            .copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

/** بطاقة «نبضة سريعة» — أهم الاقتراحات بالوفر المتوقع */
@Composable
private fun SuggestionsCard(suggestions: List<SuggestionsEngine.Suggestion>) {
    val palette = LocalIOSPalette.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.barBg)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.suggestions_title),
                style = MaterialTheme.typography.titleSmall,
                color = palette.label,
            )
        }
        Spacer(Modifier.height(8.dp))
        suggestions.forEach { s ->
            val bytes = remember(s.bytes) { LocalBrowser.humanSize(s.bytes) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(palette.accent.copy(alpha = 0.7f)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (s.id) {
                        SuggestionsEngine.SuggestionId.OLD_SCREENSHOTS ->
                            stringResource(R.string.suggestions_old_screenshots, s.count, bytes)
                        SuggestionsEngine.SuggestionId.OLD_INSTALLERS ->
                            stringResource(R.string.suggestions_old_installers, s.count, bytes)
                        SuggestionsEngine.SuggestionId.STALE_LARGE ->
                            stringResource(R.string.suggestions_stale_large, s.count, bytes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RecentRow(
    file: RecentFile,
    favorite: Boolean,
    now: Long,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val palette = LocalIOSPalette.current
    val context = LocalContext.current
    val type = FileType.of(File(file.name))
    val exists = remember(file.path) { File(file.path).exists() }
    val relLabel = remember(file.modifiedAt) { RelativeTime.of(System.currentTimeMillis(), file.modifiedAt) }
    val timeText = when (relLabel) {
        RelativeTime.Label.Now -> stringResource(R.string.time_now)
        is RelativeTime.Label.Minutes -> stringResource(R.string.time_minutes, relLabel.count)
        is RelativeTime.Label.Hours -> stringResource(R.string.time_hours, relLabel.count)
        RelativeTime.Label.Yesterday -> stringResource(R.string.time_yesterday)
        is RelativeTime.Label.Days -> stringResource(R.string.time_days, relLabel.count)
        RelativeTime.Label.Older ->
            remember(file.modifiedAt) {
                DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.modifiedAt))
            }
    }
    val sizeText = remember(file.size) { Formatter.formatShortFileSize(context, file.size) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.barBg)
            .clickable(enabled = exists, onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // بلاطة الأيقونة الملوّنة — نفس لغة بطاقات المتصفح
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(TypeIcons.color(type).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                TypeIcons.icon(type),
                contentDescription = null,
                tint = TypeIcons.color(type),
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyLarge,
                color = palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$timeText • $sizeText",
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.Star,
            contentDescription = stringResource(R.string.swipe_favorite),
            tint = if (favorite) palette.accent else palette.tertiaryLabel.copy(alpha = 0.5f),
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggleFavorite)
                .padding(9.dp),
        )
    }
}
