package com.novafiles.app.ui.checksum

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.checksum.ChecksumAlgorithm
import com.novafiles.app.core.checksum.ChecksumEngine
import com.novafiles.app.core.checksum.ChecksumManifest
import com.novafiles.app.core.checksum.CompareEngine
import com.novafiles.app.core.checksum.CompareResult
import com.novafiles.app.core.checksum.HashResult
import com.novafiles.app.core.checksum.VerifyOutcome
import com.novafiles.app.core.checksum.VerifySummary
import com.novafiles.app.core.fs.FileIndexer
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSMiniField
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.IOSSwitch
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.util.Format
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.29.0 — التحقق والمطابقة (iPadOS).
 *
 * طبقة Compose رقيقة فوق محركات checksum الخالصة:
 * مجلد هدف موروث من المتصفح، مفتاح تكرار، محدد خوارزمية منقسم،
 * تقدم حي + إيقاف، صفوف بصمة أحادية المسافة بالنقر للنسخ،
 * حفظ/نسخ ملف بصمات بنسق GNU، تحقق ملف بصمات مكشوف تلقائياً،
 * تحقق ببصمة ملصوقة، ومقارنة ملفين بايت-ببايت.
 */

/** ألوان أحكام التحقق — نظام iOS الدلالي الثابت */
private val VerifyOk = Color(0xFF34C759)
private val VerifyBad = Color(0xFFFF3B30)
private val VerifyWarn = Color(0xFFFF9500)

/** امتداد ملف البصمات لكل خوارزمية */
private fun ChecksumAlgorithm.manifestExt(): String = when (this) {
    ChecksumAlgorithm.MD5 -> "md5"
    ChecksumAlgorithm.SHA1 -> "sha1"
    ChecksumAlgorithm.SHA256 -> "sha256"
}

/** يكشف ملف بصمات جاهز في المجلد (checksums.md5/.sha1/.sha256) */
private fun detectManifest(folder: File): Pair<String, ChecksumAlgorithm>? =
    listOf(
        ChecksumAlgorithm.MD5 to "checksums.md5",
        ChecksumAlgorithm.SHA1 to "checksums.sha1",
        ChecksumAlgorithm.SHA256 to "checksums.sha256",
    ).firstOrNull { File(folder, it.second).isFile }
        ?.let { (algo, name) -> name to algo }

@Composable
fun ChecksumScreen(initialPath: String?, onBack: () -> Unit) {
    val palette = LocalIOSPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val engine = remember { ChecksumEngine() }

    val folder = remember { File(initialPath ?: FileIndexer.ROOT) }

    // ── حالة الحساب ──
    var recursive by remember { mutableStateOf(false) }
    var algoIdx by remember { mutableIntStateOf(0) }
    val algo = ChecksumAlgorithm.entries[algoIdx]
    var running by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    var total by remember { mutableIntStateOf(0) }
    var done by remember { mutableIntStateOf(0) }
    var bytes by remember { mutableLongStateOf(0L) }
    var results by remember { mutableStateOf<List<HashResult>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    // ── حالة التحقق من ملف بصمات ──
    var manifest by remember { mutableStateOf<Pair<String, ChecksumAlgorithm>?>(null) }
    var manifestEntryCount by remember { mutableIntStateOf(0) }
    var verifying by remember { mutableStateOf(false) }
    var outcomes by remember { mutableStateOf<List<VerifyOutcome>>(emptyList()) }
    var summary by remember { mutableStateOf<VerifySummary?>(null) }

    // ── حالة التحقق ببصمة ملصوقة ──
    var pasteFile by remember { mutableStateOf("") }
    var pasteHash by remember { mutableStateOf("") }
    var pasteResult by remember { mutableStateOf<Triple<Int, List<Any>, Color>?>(null) }

    // ── حالة المقارنة ──
    var cmpA by remember { mutableStateOf("") }
    var cmpB by remember { mutableStateOf("") }
    var cmpResult by remember { mutableStateOf<CompareResult?>(null) }

    // كشف تلقائي لملف بصمات عند فتح الشاشة
    LaunchedEffect(folder) {
        manifest = detectManifest(folder)
        manifestEntryCount = manifest?.let { (name, _) ->
            runCatching {
                ChecksumManifest.parse(File(folder, name).readText()).entries.size
            }.getOrDefault(0)
        } ?: 0
        outcomes = emptyList()
        summary = null
    }

    fun notify(res: Int, vararg args: Any) {
        message = context.getString(res, *args)
    }

    fun startHash() {
        if (running) return
        running = true
        cancelled = false
        results = emptyList()
        done = 0
        bytes = 0
        scope.launch {
            val files = withContext(Dispatchers.IO) { engine.collectFiles(folder, recursive) }
            total = files.size
            if (files.isEmpty()) {
                running = false
                return@launch
            }
            val res = withContext(Dispatchers.IO) {
                engine.hashFiles(
                    files = files,
                    algorithm = algo,
                    onProgress = { d, b ->
                        done = d
                        bytes = b
                    },
                    isCancelled = { cancelled },
                )
            }
            results = res.filterNotNull()
            running = false
        }
    }

    fun startVerify() {
        val mf = manifest ?: return
        if (verifying) return
        verifying = true
        outcomes = emptyList()
        summary = null
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { File(folder, mf.first).readText() }.getOrNull() }
            if (text == null) {
                verifying = false
                notify(R.string.checksum_file_not_found, mf.first)
                return@launch
            }
            val parsed = ChecksumManifest.parse(text)
            manifestEntryCount = parsed.entries.size
            val (outs, sum) = withContext(Dispatchers.IO) {
                ChecksumManifest.verify(
                    parse = parsed,
                    algorithm = mf.second,
                    folder = folder,
                    engine = engine,
                )
            }
            outcomes = outs
            summary = sum
            verifying = false
        }
    }

    fun verifyPasted() {
        val name = pasteFile.trim()
        val hash = pasteHash.trim().lowercase()
        if (name.isEmpty() || hash.isEmpty()) return
        val detected = ChecksumAlgorithm.byHexLength(hash)
        if (detected == null) {
            pasteResult = Triple(R.string.checksum_algo_unknown, emptyList(), VerifyWarn)
            return
        }
        val f = File(folder, name)
        if (!f.isFile) {
            pasteResult = Triple(R.string.checksum_file_not_found, listOf(name), VerifyBad)
            return
        }
        val actual = engine.hashFile(f, detected)?.hex
        pasteResult = when {
            actual == null -> Triple(R.string.checksum_compare_missing, emptyList(), VerifyBad)
            actual == hash -> Triple(R.string.checksum_paste_result_ok, listOf(detected.jcaName), VerifyOk)
            else -> Triple(R.string.checksum_paste_result_bad, listOf(detected.jcaName), VerifyBad)
        }
    }

    fun compareFiles() {
        val a = cmpA.trim()
        val b = cmpB.trim()
        if (a.isEmpty() || b.isEmpty()) return
        cmpResult = CompareEngine.compare(File(folder, a), File(folder, b))
    }

    Column(Modifier.fillMaxSize()) {
        IOSNavBar(
            title = stringResource(R.string.checksum_title),
            subtitle = folder.name,
            onBack = onBack,
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ═══ المجلد الهدف + رسالة ═══
            item {
                if (message != null) {
                    IOSGroupHeader(text = message ?: "")
                }
                IOSGroupCard {
                    IOSListRow(
                        title = stringResource(R.string.checksum_folder),
                        subtitle = folder.absolutePath,
                        showSeparator = false,
                    )
                }
            }

            // ═══ الحساب ═══
            item {
                IOSGroupCard {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.checksum_recursive),
                                    style = TextStyle(fontSize = 15.sp),
                                    color = palette.label,
                                )
                            }
                            IOSSwitch(checked = recursive, onCheckedChange = { recursive = it })
                        }
                        Spacer(Modifier.height(12.dp))
                        SegmentedAlgo(
                            selected = algoIdx,
                            onSelect = { if (!running) algoIdx = it },
                        )
                        Spacer(Modifier.height(12.dp))
                        if (running) {
                            Text(
                                stringResource(R.string.checksum_progress, done, total) +
                                    " · " + Format.bytes(bytes),
                                style = TextStyle(fontSize = 12.sp),
                                color = palette.secondaryLabel,
                            )
                            Spacer(Modifier.height(8.dp))
                            IOSFilledButton(
                                text = stringResource(R.string.checksum_stop),
                                onClick = { cancelled = true },
                            )
                        } else {
                            IOSFilledButton(
                                text = stringResource(R.string.checksum_start),
                                onClick = { startHash() },
                            )
                        }
                        if (cancelled && done > 0 && !running) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.checksum_stopped, done),
                                style = TextStyle(fontSize = 12.sp),
                                color = VerifyWarn,
                            )
                        }
                    }
                }
            }

            // ═══ النتائج ═══
            if (results.isEmpty() && !running && total == 0) {
                item {
                    IOSGroupCard {
                        IOSListRow(
                            title = stringResource(R.string.checksum_empty),
                            showSeparator = false,
                        )
                    }
                }
            }
            if (results.isNotEmpty()) {
                item {
                    IOSGroupHeader(text = stringResource(R.string.checksum_results_header, results.size))
                }
                item {
                    IOSGroupCard {
                        Column {
                            IOSListRow(
                                title = stringResource(R.string.checksum_copy_all),
                                showSeparator = results.size > 0,
                                onClick = {
                                    clipboard.setText(AnnotatedString(ChecksumManifest.generate(results, folder)))
                                    notify(R.string.checksum_copy_done, "checksums.${algo.manifestExt()}")
                                },
                            )
                            IOSListRow(
                                title = stringResource(R.string.checksum_save, algo.manifestExt()),
                                showSeparator = false,
                                onClick = {
                                    scope.launch {
                                        val text = withContext(Dispatchers.IO) {
                                            ChecksumManifest.generate(results, folder)
                                        }
                                        val target = File(folder, "checksums.${algo.manifestExt()}")
                                        val ok = withContext(Dispatchers.IO) {
                                            runCatching { target.writeText(text) }.isSuccess
                                        }
                                        if (ok) {
                                            manifest = detectManifest(folder)
                                            manifestEntryCount = results.size
                                            notify(R.string.checksum_saved, target.name)
                                        } else {
                                            notify(R.string.checksum_error, target.name)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                items(results.size) { i ->
                    val r = results[i]
                    HashResultRow(
                        result = r,
                        onCopy = {
                            r.hex?.let {
                                clipboard.setText(AnnotatedString(it))
                                notify(R.string.checksum_copy_done, r.file.name)
                            }
                        },
                    )
                }
            }

            // ═══ التحقق من ملف بصمات ═══
            item {
                IOSGroupHeader(text = stringResource(R.string.checksum_verify_header))
            }
            item {
                IOSGroupCard {
                    Column {
                        val mf = manifest
                        if (mf == null) {
                            IOSListRow(
                                title = stringResource(R.string.checksum_no_manifest),
                                showSeparator = false,
                            )
                        } else {
                            IOSListRow(
                                title = stringResource(R.string.checksum_manifest_found, mf.first, manifestEntryCount),
                                subtitle = if (verifying) {
                                    stringResource(R.string.checksum_verifying, outcomes.size, manifestEntryCount)
                                } else null,
                                showSeparator = false,
                            )
                            IOSListRow(
                                title = stringResource(R.string.checksum_verify_btn),
                                showSeparator = false,
                                onClick = { startVerify() },
                            )
                        }
                    }
                }
            }
            if (summary != null) {
                item {
                    IOSGroupCard {
                        IOSListRow(
                            title = if (summary!!.allOk) {
                                stringResource(R.string.checksum_verify_all_ok)
                            } else {
                                stringResource(
                                    R.string.checksum_verify_summary,
                                    summary!!.ok,
                                    summary!!.mismatch,
                                    summary!!.missing,
                                )
                            },
                            subtitle = if (summary!!.badLines > 0) {
                                stringResource(R.string.checksum_bad_lines, summary!!.badLines)
                            } else null,
                            showSeparator = false,
                        )
                    }
                }
            }
            items(outcomes.size) { i ->
                val o = outcomes[i]
                VerifyRow(outcome = o)
            }

            // ═══ التحقق ببصمة ملصوقة ═══
            item {
                IOSGroupHeader(text = stringResource(R.string.checksum_paste_header))
            }
            item {
                IOSGroupCard {
                    Column(Modifier.padding(16.dp)) {
                        IOSMiniField(
                            value = pasteFile,
                            onValueChange = { pasteFile = it },
                            hint = stringResource(R.string.checksum_paste_file),
                        )
                        Spacer(Modifier.height(8.dp))
                        IOSMiniField(
                            value = pasteHash,
                            onValueChange = { pasteHash = it },
                            hint = stringResource(R.string.checksum_paste_hash),
                        )
                        Spacer(Modifier.height(10.dp))
                        IOSFilledButton(
                            text = stringResource(R.string.checksum_paste_btn),
                            onClick = { verifyPasted() },
                        )
                        pasteResult?.let { (res, args, color) ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                context.getString(res, *args.toTypedArray()),
                                style = TextStyle(fontSize = 13.sp),
                                color = color,
                            )
                        }
                    }
                }
            }

            // ═══ المقارنة بايت-ببايت ═══
            item {
                IOSGroupHeader(text = stringResource(R.string.checksum_compare_header))
            }
            item {
                IOSGroupCard {
                    Column(Modifier.padding(16.dp)) {
                        IOSMiniField(
                            value = cmpA,
                            onValueChange = { cmpA = it },
                            hint = stringResource(R.string.checksum_compare_first),
                        )
                        Spacer(Modifier.height(8.dp))
                        IOSMiniField(
                            value = cmpB,
                            onValueChange = { cmpB = it },
                            hint = stringResource(R.string.checksum_compare_second),
                        )
                        Spacer(Modifier.height(10.dp))
                        IOSFilledButton(
                            text = stringResource(R.string.checksum_compare_btn),
                            onClick = { compareFiles() },
                        )
                        cmpResult?.let { r ->
                            Spacer(Modifier.height(8.dp))
                            val (res, args, color) = when (r) {
                                is CompareResult.Same ->
                                    Triple(R.string.checksum_compare_same, emptyList<Any>(), VerifyOk)
                                is CompareResult.DifferentSize ->
                                    Triple(
                                        R.string.checksum_compare_size,
                                        listOf(Format.bytes(r.sizeA), Format.bytes(r.sizeB)),
                                        VerifyWarn,
                                    )
                                is CompareResult.DifferentBytes ->
                                    Triple(
                                        R.string.checksum_compare_diff,
                                        listOf(r.firstOffset, r.hexA, r.hexB),
                                        VerifyBad,
                                    )
                                is CompareResult.Unreadable ->
                                    Triple(R.string.checksum_compare_missing, emptyList<Any>(), VerifyBad)
                            }
                            Text(
                                context.getString(res, *args.toTypedArray()),
                                style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                                color = color,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** صف نتيجة بصمة — أحادي المسافة، النقر ينسخ البصمة */
@Composable
private fun HashResultRow(result: HashResult, onCopy: () -> Unit) {
    val palette = LocalIOSPalette.current
    val a11y = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics {
                contentDescription = a11y.getString(R.string.checksum_a11y_hash_row, result.file.name)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                result.file.name,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                color = palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                (result.hex ?: "—") + " · " + Format.bytes(result.size),
                style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                color = palette.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Spacer(Modifier.height(2.dp))
}

/** صف حكم التحقق بلون دلالي */
@Composable
private fun VerifyRow(outcome: VerifyOutcome) {
    val palette = LocalIOSPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val (title, color) = when (outcome) {
        is VerifyOutcome.Ok -> outcome.entry.path to VerifyOk
        is VerifyOutcome.Mismatch ->
            context.getString(R.string.checksum_verify_mismatch, outcome.actual) to VerifyBad
        is VerifyOutcome.Missing -> outcome.entry.path to VerifyWarn
    }
    IOSListRow(
        title = title,
        subtitle = when (outcome) {
            is VerifyOutcome.Ok -> stringResource(R.string.checksum_verify_ok)
            is VerifyOutcome.Mismatch -> outcome.entry.hash
            is VerifyOutcome.Missing -> stringResource(R.string.checksum_verify_missing)
        },
        showSeparator = false,
        trailing = {
            Box(
                Modifier
                    .width(10.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(color),
            )
        },
    )
}

/** مفتاح منقسم iOS لاختيار الخوارزمية (ثلاثة خيارات) */
@Composable
private fun SegmentedAlgo(selected: Int, onSelect: (Int) -> Unit) {
    val p = LocalIOSPalette.current
    val labels = listOf("MD5", "SHA-1", "SHA-256")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(p.fill, RoundedCornerShape(9.dp)),
    ) {
        labels.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(2.dp)
                    .background(
                        if (isSel) p.cardBg else Color.Transparent,
                        RoundedCornerShape(7.dp),
                    )
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = p.label,
                )
            }
        }
    }
}
