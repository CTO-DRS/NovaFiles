@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.novafiles.app.R
import com.novafiles.app.core.fs.FileIndexer
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.core.fs.LocalBrowser
import com.novafiles.app.core.fs.SearchEngine
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.ui.ios.IOSMiniField
import com.novafiles.app.ui.ios.IOSNavIconButton
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.IOSSearchField
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.novaContainer
import com.novafiles.app.util.TypeIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import java.io.File

/** شاشة البحث الذكي — نتائج فورية من فهرس Room-FTS، وفحص حيّ احتياطي قبل جاهزية الفهرس */
@Composable
fun SearchScreen(onOpenFile: (File) -> Unit) {
    val container = LocalContext.current.novaContainer
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf(FileType.OTHER) } // OTHER = الكل
    var minSizeMb by remember { mutableStateOf("") }
    var maxAgeDays by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SearchEngine.SearchState>(SearchEngine.SearchState.Scanning(0)) }
    var scanning by remember { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    var typeOpen by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val root = remember { File(FileIndexer.ROOT) }
    val indexReady by FileIndexer.ready.collectAsState()
    val indexBusy by FileIndexer.indexing.collectAsState()
    val indexProgress by FileIndexer.progress.collectAsState()

    // تحفيز الفهرسة الأولى عند فتح الشاشة (إن لم تكن جاهزة)
    LaunchedEffect(Unit) { if (!indexReady) launch { FileIndexer.ensureIndexed(container.db) } }

    // إعادة تشغيل البحث مع خصم زمني قصير (تجربة كتابة سلسة)
    LaunchedEffect(query, typeFilter, minSizeMb, maxAgeDays, indexReady) {
        job?.cancel()
        if (query.isBlank() && typeFilter == FileType.OTHER && minSizeMb.isBlank() && maxAgeDays.isBlank()) {
            state = SearchEngine.SearchState.Results(emptyList(), done = true)
            scanning = false
            return@LaunchedEffect
        }
        delay(250)
        val q = SearchEngine.Query(
            text = query,
            type = typeFilter,
            minSizeBytes = ((minSizeMb.toDoubleOrNull() ?: 0.0) * 1024 * 1024).toLong(),
            maxAgeDays = maxAgeDays.toIntOrNull(),
            root = root,
            showHidden = false,
        )
        if (indexReady) {
            // ★ بحث فوري من الفهرس — بلا فحص ولا انتظار
            scanning = false
            state = runCatching {
                SearchEngine.SearchState.Results(FileIndexer.search(container.db, q), done = true)
            }.getOrElse { SearchEngine.SearchState.Results(emptyList(), done = true) }
        } else {
            // فحص حيّ احتياطي حتى تكتمل الفهرسة
            scanning = true
            job = launch {
                kotlinx.coroutines.withTimeoutOrNull(60_000) {
                    SearchEngine.searchStream(q)
                        .catch { state = SearchEngine.SearchState.Results(emptyList(), done = true) }
                        .collect { st ->
                            state = st
                            if (st is SearchEngine.SearchState.Results && st.done) scanning = false
                        }
                }
                scanning = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(LocalIOSPalette.current.groupedBg)) {
        IOSNavBar(
            title = stringResource(R.string.nav_search),
            actions = {
                IOSNavIconButton(Icons.Filled.FilterList, contentDescription = stringResource(R.string.filter_type)) {
                    filtersOpen = true
                }
            },
        )

        IOSSearchField(
            value = query,
            onValueChange = { query = it },
            hint = stringResource(R.string.search_hint),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        // شريط المرشحات
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                AssistChip(
                    onClick = { typeOpen = true },
                    label = {
                        Text(
                            stringResource(
                                if (typeFilter == FileType.OTHER) R.string.type_any else R.string.filter_type
                            ) + if (typeFilter != FileType.OTHER) ": ${typeFilter.name}" else ""
                        )
                    },
                )
            }
            IOSMiniField(
                value = minSizeMb,
                onValueChange = { minSizeMb = it.filter { c -> c.isDigit() } },
                hint = stringResource(R.string.filter_min_size),
                modifier = Modifier.width(150.dp),
            )
            IOSMiniField(
                value = maxAgeDays,
                onValueChange = { maxAgeDays = it.filter { c -> c.isDigit() } },
                hint = stringResource(R.string.filter_max_age),
                modifier = Modifier.width(150.dp),
            )
        }

        // حالة الفهرسة الخلفية (تظهر حتى يصبح البحث فورياً)
        if (indexBusy) {
            Text(
                stringResource(R.string.index_building, indexProgress),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when (val s = state) {
            is SearchEngine.SearchState.Scanning -> {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.search_scanning), style = MaterialTheme.typography.bodySmall)
                }
            }
            is SearchEngine.SearchState.Results -> {
                if (s.files.isEmpty() && !scanning) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.empty_search),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        stringResource(R.string.search_found, s.files.size),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(s.files, key = { it.absolutePath }) { file ->
                            SearchResultRow(file = file, onTap = { onOpenFile(file) })
                        }
                    }
                }
            }
        }
    }

    // ورقة إجراءات iOS — نوع الملف
    val typeOptions = listOf(
        FileType.OTHER to stringResource(R.string.type_any),
        FileType.IMAGE to stringResource(R.string.type_image),
        FileType.VIDEO to stringResource(R.string.type_video),
        FileType.AUDIO to stringResource(R.string.type_audio),
        FileType.DOC to stringResource(R.string.type_doc),
        FileType.ARCHIVE to stringResource(R.string.type_archive),
        FileType.APK to stringResource(R.string.type_apk),
    )
    IOSActionSheet(
        visible = typeOpen,
        onDismiss = { typeOpen = false },
        cancelText = stringResource(R.string.action_cancel),
        title = stringResource(R.string.filter_type),
        actions = typeOptions.map { (t, label) ->
            IOSAction(label, selected = typeFilter == t) { typeFilter = t }
        },
    )

    // ورقة إجراءات iOS — المرشحات
    IOSActionSheet(
        visible = filtersOpen,
        onDismiss = { filtersOpen = false },
        cancelText = stringResource(R.string.action_cancel),
        title = stringResource(R.string.filter_type),
        actions = listOf(
            IOSAction(stringResource(R.string.filter_min_size)) { },
            IOSAction(stringResource(R.string.filter_max_age)) { },
            IOSAction(stringResource(R.string.index_rebuild)) {
                scope.launch { FileIndexer.rebuild(container.db) }
            },
        ),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultRow(file: File, onTap: () -> Unit) {
    val type = FileType.of(file)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (type == FileType.IMAGE) {
                AsyncImage(
                    model = file,
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(TypeIcons.icon(type), null, tint = TypeIcons.color(type), modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                file.parent ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            LocalBrowser.humanSize(file.length()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
