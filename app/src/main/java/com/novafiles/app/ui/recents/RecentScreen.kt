package com.novafiles.app.ui.recents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.novafiles.app.core.fs.RecentsEngine
import com.novafiles.app.core.fs.RecentFile
import com.novafiles.app.core.fs.RelativeTime
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
 * شاشة «الأخيرة» — v0.19
 * أحدث الملفات المعدَّلة في الذاكرة الداخلية، بترتيب زمني تنازلي،
 * مع وقت نسبي ودّي («منذ 5 د»/«أمس») وحجم مقروء، ووسم نجمة للمفضلة.
 */
@Composable
fun RecentScreen(onOpenFile: (File) -> Unit) {
    val context = LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    val palette = LocalIOSPalette.current

    var loading by remember { mutableStateOf(true) }
    var files by remember { mutableStateOf<List<RecentFile>>(emptyList()) }
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
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.groupedBg),
    ) {
        IOSNavBar(title = stringResource(R.string.nav_recents))

        when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            files.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = null,
                        tint = palette.secondaryLabel.copy(alpha = 0.45f),
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
                        color = palette.secondaryLabel.copy(alpha = 0.8f),
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp + rememberBottomBarClearance()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(files, key = { it.path }) { rf ->
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
