package com.novafiles.app.ui.media

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.media.ImageBatchPlan
import com.novafiles.app.core.media.ImageCollisionPolicy
import com.novafiles.app.core.media.ImageFormat
import com.novafiles.app.core.media.ImageOp
import com.novafiles.app.core.media.ImageToolsEngine
import com.novafiles.app.core.media.ImageWarning
import com.novafiles.app.core.media.MediaCatalogEngine
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupFooter
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.IOSPalette
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.util.Format
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v0.31.0 — أدوات الوسائط: معالجة دفعية للصور بنمط iOS.
 *
 * ورقة رقيقة فوق الخطة الحتمية: صيغة الإخراج، جودة الضغط، الأبعاد
 * القصوى، وسياسة الاصطدام — معاينة خطة حية قبل التنفيذ ثم تشغيل
 * عبر Bitmap في الخلفية مع تقدم وإلغاء. المحرك خالص والتنفيذ محلي.
 */

/** اختيار صيغة الإخراج في الواجهة — «أصلي» يعني بلا تحويل */
private enum class FormatChoice(val format: ImageFormat?) {
    KEEP(null),
    JPEG(ImageFormat.JPEG),
    PNG(ImageFormat.PNG),
    WEBP(ImageFormat.WEBP),
}

/** اختيار الأبعاد القصوى — «أصلي» يعني بلا تصغير */
private enum class DimChoice(val max: Int?) {
    KEEP(null),
    D4000(4000),
    D1920(1920),
    D1280(1280),
    D800(800),
}

@Composable
fun MediaToolsScreen(folderPath: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalIOSPalette.current
    val scope = rememberCoroutineScope()
    val engine = remember { ImageToolsEngine() }
    val catalog = remember { MediaCatalogEngine() }

    val folder = remember(folderPath) { File(folderPath ?: "/sdcard") }

    // ── الحالة ──
    var images by remember { mutableStateOf<List<File>>(emptyList()) }
    var dims by remember { mutableStateOf<Map<String, Pair<Int, Int>>>(emptyMap()) }
    var formatChoice by remember { mutableStateOf(FormatChoice.KEEP) }
    var quality by remember { mutableIntStateOf(85) }
    var dimChoice by remember { mutableStateOf<DimChoice>(DimChoice.KEEP) }
    var collisionPolicy by remember { mutableStateOf(ImageCollisionPolicy.RENAME) }
    var collisionSheet by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<ImageBatchPlan?>(null) }
    var running by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    var runDone by remember { mutableIntStateOf(0) }
    var runFailed by remember { mutableIntStateOf(0) }
    var runTotal by remember { mutableIntStateOf(0) }
    var currentName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    // ── تحميل صور المجلد وقراءة أبعادها ──
    LaunchedEffect(folder) {
        val loaded = withContext(Dispatchers.IO) {
            val files = folder.listFiles()
                ?.filter { it.isFile && catalog.isConvertibleImage(it.name) }
                ?.sortedBy { it.name.lowercase() }
                ?: emptyList()
            val d = HashMap<String, Pair<Int, Int>>()
            for (f in files) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    d[f.absolutePath] = opts.outWidth to opts.outHeight
                }
            }
            files to d
        }
        images = loaded.first
        dims = loaded.second
    }

    // ── بناء الخطة الحية عند أي تغيير في الخيارات ──
    LaunchedEffect(images, formatChoice, quality, dimChoice, collisionPolicy, dims) {
        if (images.isEmpty()) {
            plan = null
            return@LaunchedEffect
        }
        val ops = ArrayList<ImageOp>()
        formatChoice.format?.let { ops += ImageOp.Convert(it) }
        if (formatChoice.format?.supportsQuality != false) ops += ImageOp.Compress(quality)
        dimChoice.max?.let { ops += ImageOp.Downscale(it, it) }
        plan = withContext(Dispatchers.IO) {
            engine.planBatch(images, ops, folder, collisionPolicy, dims)
        }
    }

    fun startRun() {
        val p = plan ?: return
        if (running || p.executable == 0) return
        running = true
        cancelled = false
        runDone = 0
        runFailed = 0
        runTotal = p.executable
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ImageBatchExecutor.execute(
                    items = p.items,
                    onProgress = { d, t, name ->
                        runDone = d
                        runTotal = t
                        currentName = name
                    },
                    isCancelled = { cancelled },
                )
            }
            running = false
            message = if (result.cancelled) {
                context.getString(R.string.media_tools_cancelled, result.done)
            } else {
                context.getString(R.string.media_tools_done, result.done, result.failed)
            }
            // إعادة تحميل الأبعاد بعد التغييرات
            val reloaded = withContext(Dispatchers.IO) {
                val d = HashMap<String, Pair<Int, Int>>()
                for (f in images) {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) d[f.absolutePath] = opts.outWidth to opts.outHeight
                }
                d
            }
            dims = reloaded
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        IOSNavBar(
            title = stringResource(R.string.media_tools_title),
            subtitle = folder.name,
            onBack = onBack,
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ═══ المجلد ═══
            item {
                if (message != null) {
                    IOSGroupHeader(text = message ?: "")
                }
                IOSGroupCard {
                    IOSListRow(
                        title = stringResource(R.string.media_tools_folder),
                        subtitle = folder.absolutePath,
                        showSeparator = false,
                    )
                }
            }

            // ═══ صيغة الإخراج ═══
            item {
                IOSGroupCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.media_tools_format),
                            fontSize = 15.sp,
                            color = palette.label,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row {
                            FormatChoice.entries.forEach { c ->
                                FormatChip(
                                    label = stringResource(
                                        when (c) {
                                            FormatChoice.KEEP -> R.string.media_tools_format_keep
                                            FormatChoice.JPEG -> R.string.media_tools_format_jpeg
                                            FormatChoice.PNG -> R.string.media_tools_format_png
                                            FormatChoice.WEBP -> R.string.media_tools_format_webp
                                        }
                                    ),
                                    selected = formatChoice == c,
                                    enabled = !running,
                                    onClick = { formatChoice = c },
                                )
                            }
                        }

                        // جودة الضغط — تظهر عند صيغة تدعم الجودة أو «أصلي»
                        val qualityApplicable = formatChoice == FormatChoice.KEEP ||
                            formatChoice.format?.supportsQuality == true
                        if (qualityApplicable) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                stringResource(R.string.media_tools_quality, quality),
                                fontSize = 13.sp,
                                color = palette.secondaryLabel,
                            )
                            Slider(
                                value = quality.toFloat(),
                                onValueChange = { quality = it.toInt().coerceIn(1, 100) },
                                valueRange = 10f..100f,
                                enabled = !running,
                            )
                        }
                    }
                }
            }

            // ═══ الأبعاد القصوى ═══
            item {
                IOSGroupCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.media_tools_max_dim),
                            fontSize = 15.sp,
                            color = palette.label,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row {
                            DimChoice.entries.forEach { c ->
                                FormatChip(
                                    label = when (c) {
                                        DimChoice.KEEP -> stringResource(R.string.media_tools_dim_keep)
                                        else -> "${c.max}px"
                                    },
                                    selected = dimChoice == c,
                                    enabled = !running,
                                    onClick = { dimChoice = c },
                                )
                            }
                        }
                    }
                }
            }

            // ═══ سياسة الاصطدام ═══
            item {
                IOSGroupCard {
                    IOSListRow(
                        title = stringResource(R.string.media_tools_collision),
                        subtitle = stringResource(
                            when (collisionPolicy) {
                                ImageCollisionPolicy.RENAME -> R.string.media_tools_collision_rename
                                ImageCollisionPolicy.SKIP -> R.string.media_tools_collision_skip
                                ImageCollisionPolicy.OVERWRITE -> R.string.media_tools_collision_overwrite
                            }
                        ),
                        showSeparator = false,
                        onClick = { if (!running) collisionSheet = true },
                    )
                }
            }

            // ═══ معاينة الخطة ═══
            item {
                val p = plan
                if (p == null || images.isEmpty()) {
                    IOSGroupCard {
                        IOSListRow(
                            title = stringResource(R.string.media_tools_empty),
                            showSeparator = false,
                        )
                    }
                } else {
                    IOSGroupHeader(
                        text = stringResource(R.string.media_tools_plan, p.executable, p.total) +
                            if (p.skipped > 0) " · " + stringResource(R.string.media_tools_plan_skipped, p.skipped) else "",
                    )
                }
            }
            items(plan?.items ?: emptyList()) { item ->
                IOSGroupCard {
                    IOSListRow(
                        title = item.source.name,
                        subtitle = if (item.skipped) {
                            stringResource(R.string.media_tools_skip_generic)
                        } else {
                            val dimsText = if (item.expectedWidth != null) {
                                " ${item.expectedWidth}×${item.expectedHeight}"
                            } else ""
                            "→ ${item.output.name}$dimsText" +
                                (if (item.warnings.isNotEmpty()) " · ⚠" else "")
                        },
                        showSeparator = false,
                    )
                }
            }
            // تحذيرات الخطة — ملخص واحد
            item {
                val p = plan
                if (p != null && p.warnings > 0) {
                    IOSGroupFooter(
                        text = buildString {
                            if (p.items.any { ImageWarning.ALPHA_LOSS in it.warnings }) {
                                append(stringResource(R.string.media_tools_warn_alpha)); append(" ")
                            }
                            if (p.items.any { ImageWarning.QUALITY_IGNORED in it.warnings }) {
                                append(stringResource(R.string.media_tools_warn_quality)); append(" ")
                            }
                            if (p.items.any { ImageWarning.UPSCALE_NOOP in it.warnings }) {
                                append(stringResource(R.string.media_tools_warn_upscale))
                            }
                        },
                    )
                }
            }

            // ═══ زر التشغيل ═══
            item {
                IOSGroupCard {
                    Column(Modifier.padding(16.dp)) {
                        if (running) {
                            Text(
                                stringResource(R.string.media_tools_running, runDone, runTotal) +
                                    if (currentName.isNotEmpty()) " · $currentName" else "",
                                fontSize = 12.sp,
                                color = palette.secondaryLabel,
                            )
                            Spacer(Modifier.height(8.dp))
                            IOSFilledButton(
                                text = stringResource(R.string.media_tools_cancel),
                                onClick = { cancelled = true },
                            )
                        } else {
                            IOSFilledButton(
                                text = stringResource(
                                    R.string.media_tools_run,
                                    plan?.executable ?: 0
                                ),
                                onClick = { startRun() },
                                enabled = (plan?.executable ?: 0) > 0,
                            )
                        }
                        if (runFailed > 0 && !running) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.media_tools_failures, runFailed),
                                fontSize = 12.sp,
                                color = androidx.compose.ui.graphics.Color(0xFFFF3B30),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // ورقة سياسة الاصطدام
    IOSActionSheet(
        visible = collisionSheet,
        onDismiss = { collisionSheet = false },
        title = stringResource(R.string.media_tools_collision),
        cancelText = stringResource(R.string.action_cancel),
        actions = listOf(
            IOSAction(stringResource(R.string.media_tools_collision_rename), selected = collisionPolicy == ImageCollisionPolicy.RENAME) {
                collisionPolicy = ImageCollisionPolicy.RENAME
            },
            IOSAction(stringResource(R.string.media_tools_collision_skip), selected = collisionPolicy == ImageCollisionPolicy.SKIP) {
                collisionPolicy = ImageCollisionPolicy.SKIP
            },
            IOSAction(stringResource(R.string.media_tools_collision_overwrite), selected = collisionPolicy == ImageCollisionPolicy.OVERWRITE) {
                collisionPolicy = ImageCollisionPolicy.OVERWRITE
            },
        ),
    )
}

/** رقاقة اختيار صغيرة — نمط iOS المقطعي المبسط */
@Composable
private fun FormatChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalIOSPalette.current
    androidx.compose.material3.Surface(
        color = if (selected) androidx.compose.ui.graphics.Color(0xFF007AFF) else palette.cardBg,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Text(
            label,
            color = if (selected) androidx.compose.ui.graphics.Color.White else palette.label,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
