@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.archive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novafiles.app.R
import com.novafiles.app.core.archive.ArchiveEngine
import com.novafiles.app.core.archive.ZipEntryInfo
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.core.fs.LocalBrowser
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.util.TypeIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * عارض الأرشيف المعمم — يفتح ZIP و7z وTAR (وتغليفه gz/bz2/xz) وRAR وضغط أحادي الملف:
 * سرد المحتويات بأحجامها، ملخص، وزر استخراج كامل مع تقدم حي (أو غير محدد حين يجهل الحجم).
 */
@Composable
fun ArchiveBrowserScreen(
    path: String?,
    onBack: () -> Unit,
) {
    val archive = path?.let { File(it) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val palette = LocalIOSPalette.current

    var entries by remember { mutableStateOf<List<ZipEntryInfo>>(emptyList()) }
    var totalRaw by remember { mutableStateOf(0L) } // -1 = مجهول
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) } // null = غير محدد
    var extractedTo by remember { mutableStateOf<String?>(null) }

    // تحميل السرد خارج الخيط الرئيسي
    LaunchedEffect(path) {
        if (archive == null || !archive.isFile) {
            error = "no_file"
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val list = ArchiveEngine.list(archive)
                Pair(list, ArchiveEngine.totalUncompressed(archive))
            }
        }
        result.onSuccess { (list, total) ->
            entries = list
            totalRaw = total
        }.onFailure { error = it.message ?: "read_failed" }
        loading = false
    }

    // فرز: المجلدات أولًا ثم أبجدياً
    val sorted = remember(entries) {
        entries.sortedWith(compareByDescending<ZipEntryInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
    }
    val filesCount = entries.count { !it.isDirectory }
    val dirsCount = entries.count { it.isDirectory }
    val compressedSize = archive?.length() ?: 0L

    val doneMsg = stringResource(R.string.archive_done, filesCount)
    val failMsg = stringResource(R.string.archive_failed)

    fun extractAll() {
        val z = archive ?: return
        if (extracting) return
        scope.launch {
            extracting = true
            progress = null
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = uniqueDest(z)
                    val count = ArchiveEngine.extractAll(z, dest) { done, total ->
                        progress = if (total > 0) done.toFloat() / total else null
                    }
                    Pair(dest, count)
                }
            }
            extracting = false
            res.onSuccess { (dest, _) ->
                extractedTo = dest.absolutePath
                snackbar.showSnackbar(doneMsg)
            }.onFailure { snackbar.showSnackbar(failMsg) }
        }
    }

    Scaffold(
        containerColor = palette.groupedBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            IOSNavBar(
                title = archive?.name ?: stringResource(R.string.archive_title),
                subtitle = archive?.parent,
                collapsed = true,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.archive_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    // ملخص الأرشيف
                    Spacer(Modifier.height(8.dp))
                    IOSGroupHeader(stringResource(R.string.archive_title))
                    IOSGroupCard {
                        IOSListRow(
                            title = stringResource(R.string.archive_counts, filesCount, dirsCount),
                            leading = {
                                Icon(
                                    TypeIcons.icon(FileType.ARCHIVE),
                                    contentDescription = null,
                                    tint = TypeIcons.color(FileType.ARCHIVE),
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                        )
                        IOSListRow(
                            title = stringResource(
                                if (totalRaw >= 0) R.string.archive_original_size else R.string.archive_size_unknown,
                                if (totalRaw >= 0) LocalBrowser.humanSize(totalRaw) else "",
                            ),
                        )
                        IOSListRow(
                            title = stringResource(R.string.archive_compressed, LocalBrowser.humanSize(compressedSize)),
                            showSeparator = false,
                        )
                    }

                    // شريط التقدم أثناء الاستخراج
                    if (extracting) {
                        Spacer(Modifier.height(10.dp))
                        IOSGroupCard {
                            Column(Modifier.padding(vertical = 12.dp)) {
                                Text(
                                    stringResource(R.string.archive_extracting),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.secondaryLabel,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                val p = progress
                                if (p != null) {
                                    LinearProgressIndicator(
                                        progress = { p },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                    )
                                }
                            }
                        }
                    }

                    if (extractedTo != null && !extracting) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.archive_extracted_to, extractedTo ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.secondaryLabel,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }

                    // قائمة المحتويات
                    Spacer(Modifier.height(10.dp))
                    IOSGroupHeader(stringResource(R.string.archive_contents))
                    IOSGroupCard(modifier = Modifier.weight(1f)) {
                        if (sorted.isEmpty()) {
                            Text(
                                stringResource(R.string.archive_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.secondaryLabel,
                                modifier = Modifier.padding(16.dp),
                            )
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                itemsIndexed(sorted) { index, entry ->
                                    val isDir = entry.isDirectory
                                    val type = if (isDir) FileType.FOLDER else FileType.ofName(entry.name)
                                    IOSListRow(
                                        title = entry.name.trimEnd('/'),
                                        subtitle = when {
                                            isDir -> stringResource(R.string.archive_folder)
                                            entry.size >= 0 -> LocalBrowser.humanSize(entry.size)
                                            else -> "—"
                                        },
                                        showSeparator = index < sorted.lastIndex,
                                        leading = {
                                            Box(
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    TypeIcons.icon(type),
                                                    contentDescription = null,
                                                    tint = TypeIcons.color(type),
                                                    modifier = Modifier.size(22.dp),
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    IOSFilledButton(
                        text = stringResource(R.string.archive_extract_all),
                        onClick = { extractAll() },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** وجهة استخراج فريدة: الاسم الأساسي (بعد نزع الامتدادات)، ثم " (2)"، " (3)"… */
private fun uniqueDest(archive: File): File {
    val parent = archive.parentFile ?: File(".")
    val base = com.novafiles.app.core.archive.stripArchiveExt(archive.name).ifEmpty { "archive" }
    var dest = File(parent, base)
    var i = 2
    while (dest.exists()) {
        dest = File(parent, "$base ($i)")
        i++
    }
    dest.mkdirs()
    return dest
}
