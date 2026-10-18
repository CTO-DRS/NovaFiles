@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.zip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novafiles.app.R
import com.novafiles.app.core.archive.ZipArchive
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
 * عارض الأرشيف — فتح ملفات zip داخل التطبيق:
 * سرد المحتويات بأحجامها، معلومات الضغط، وزر استخراج كامل مع تقدم حي.
 */
@Composable
fun ZipBrowserScreen(
    path: String?,
    onBack: () -> Unit,
) {
    val zip = path?.let { File(it) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val palette = LocalIOSPalette.current

    var entries by remember { mutableStateOf<List<com.novafiles.app.core.archive.ZipEntryInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var extractedTo by remember { mutableStateOf<String?>(null) }

    // تحميل السرد خارج الخيط الرئيسي
    LaunchedEffect(path) {
        if (zip == null || !zip.isFile) {
            error = "no_file"
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching { ZipArchive.list(zip) }
        }
        result.onSuccess { entries = it }
            .onFailure { error = it.message ?: "read_failed" }
        loading = false
    }

    // فرز: المجلدات أولًا ثم أبجدياً
    val sorted = remember(entries) {
        entries.sortedWith(compareByDescending<com.novafiles.app.core.archive.ZipEntryInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
    }
    val filesCount = entries.count { !it.isDirectory }
    val dirsCount = entries.count { it.isDirectory }
    val totalRaw = entries.sumOf { if (it.isDirectory) 0L else it.size }
    val zipSize = zip?.length() ?: 0L

    val doneMsg = stringResource(R.string.zip_done, filesCount)
    val failMsg = stringResource(R.string.zip_failed)

    fun extractAll() {
        val z = zip ?: return
        if (extracting) return
        scope.launch {
            extracting = true
            progress = 0f
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = uniqueDest(z)
                    val count = ZipArchive.extractAll(z, dest) { done, total ->
                        progress = if (total > 0) done.toFloat() / total else 1f
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
                title = zip?.name ?: stringResource(R.string.zip_title),
                subtitle = zip?.parent,
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
                    androidx.compose.material3.CircularProgressIndicator()
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.zip_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    // ملخص الأرشيف
                    Spacer(Modifier.height(8.dp))
                    IOSGroupHeader(stringResource(R.string.zip_title))
                    IOSGroupCard {
                        IOSListRow(
                            title = stringResource(R.string.zip_counts, filesCount, dirsCount),
                            leading = {
                                androidx.compose.material3.Icon(
                                    TypeIcons.icon(FileType.ARCHIVE),
                                    contentDescription = null,
                                    tint = TypeIcons.color(FileType.ARCHIVE),
                                    modifier = Modifier.size(24.dp),
                                )
                            },
                        )
                        IOSListRow(
                            title = stringResource(R.string.zip_original_size, LocalBrowser.humanSize(totalRaw)),
                        )
                        IOSListRow(
                            title = stringResource(R.string.zip_compressed, LocalBrowser.humanSize(zipSize)),
                            showSeparator = false,
                        )
                    }

                    // شريط التقدم أثناء الاستخراج
                    if (extracting) {
                        Spacer(Modifier.height(10.dp))
                        IOSGroupCard {
                            Column(Modifier.padding(vertical = 12.dp)) {
                                Text(
                                    stringResource(R.string.zip_extracting),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = palette.secondaryLabel,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }

                    if (extractedTo != null && !extracting) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.zip_extracted_to, extractedTo ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.secondaryLabel,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }

                    // قائمة المحتويات
                    Spacer(Modifier.height(10.dp))
                    IOSGroupHeader(stringResource(R.string.zip_contents))
                    IOSGroupCard(modifier = Modifier.weight(1f)) {
                        if (sorted.isEmpty()) {
                            Text(
                                stringResource(R.string.zip_empty),
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
                                        subtitle = if (isDir) stringResource(R.string.zip_folder)
                                        else LocalBrowser.humanSize(entry.size),
                                        showSeparator = index < sorted.lastIndex,
                                        leading = {
                                            Box(
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                androidx.compose.material3.Icon(
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
                        text = stringResource(R.string.zip_extract_all),
                        onClick = { extractAll() },
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/** وجهة استخراج فريدة: الاسم الأساسي، ثم " (2)"، " (3)"… */
private fun uniqueDest(zip: File): File {
    val parent = zip.parentFile ?: File(".")
    val base = zip.nameWithoutExtension.ifEmpty { "archive" }
    var dest = File(parent, base)
    var i = 2
    while (dest.exists()) {
        dest = File(parent, "$base ($i)")
        i++
    }
    dest.mkdirs()
    return dest
}
