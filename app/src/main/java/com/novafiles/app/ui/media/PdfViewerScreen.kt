package com.novafiles.app.ui.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.media.InMemoryPdfReadingStore
import com.novafiles.app.core.media.PdfPagerEngine
import com.novafiles.app.core.media.PdfReadingStore
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.IOSPalette
import com.novafiles.app.ui.ios.LocalIOSPalette
import java.io.File

/**
 * v0.31.0 — عارض PDF خفيف بلا اعتماديات عبر PdfRenderer النظام.
 *
 * تنقّل صفحات بأزرار وشريط انتقال مباشر، تكبير بدرجات ثابتة (وأصابع)،
 * وتذكر آخر صفحة قراءة عبر مخزن القراءة. منطق الترقيم كله من
 * محرك PDF الخالص — هذه الشاشة ترسم البتات فقط.
 */

/** مخزن آخر صفحة فوق SharedPreferences */
class SharedPrefsPdfReadingStore(context: android.content.Context) : PdfReadingStore {
    private val prefs = context.getSharedPreferences("nova_pdf_reading", android.content.Context.MODE_PRIVATE)

    override fun save(key: String, page: Int, pageCount: Int) {
        prefs.edit().putInt("page_$key", page).putInt("count_$key", pageCount).apply()
    }

    override fun load(key: String): com.novafiles.app.core.media.PdfReadingPoint? {
        if (!prefs.contains("page_$key")) return null
        return com.novafiles.app.core.media.PdfReadingPoint(
            page = prefs.getInt("page_$key", 0),
            pageCount = prefs.getInt("count_$key", 0),
            savedAt = 0L,
        )
    }

    override fun clear(key: String) {
        prefs.edit().remove("page_$key").remove("count_$key").apply()
    }
}

/** نتيجة فتح المستند */
private class PdfDoc(val renderer: PdfRenderer) {
    val pageCount: Int get() = renderer.pageCount

    /** رسم صفحة بمقياس معطى (1 = الدقة الأصلية) */
    fun render(index: Int, scale: Float): Bitmap {
        renderer.openPage(index).use { page ->
            val w = (page.width * scale).toInt().coerceAtLeast(1)
            val h = (page.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(AndroidColor.WHITE)
            Canvas(bmp).let { page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            return bmp
        }
    }

    fun close() = renderer.close()
}

@Composable
fun PdfViewerScreen(path: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalIOSPalette.current
    val file = remember(path) { File(path) }
    val readingStore = remember { SharedPrefsPdfReadingStore(context) }
    val readingKey = remember(path) { file.absolutePath }

    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var openError by remember { mutableStateOf(false) }
    var pageCount by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var displayZoom by remember { mutableFloatStateOf(1.0f) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rendering by remember { mutableStateOf(true) }
    var jumpOpen by remember { mutableStateOf(false) }
    var jumpText by remember { mutableStateOf("") }
    var jumpError by remember { mutableStateOf(false) }

    // ── فتح المستند واستعادة آخر صفحة ──
    DisposableEffect(path) {
        val opened = runCatching {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfDoc(PdfRenderer(pfd))
        }.getOrNull()
        if (opened == null) {
            openError = true
        } else {
            doc = opened
            pageCount = opened.pageCount
            val saved = readingStore.load(readingKey)
            page = if (saved != null && saved.pageCount == opened.pageCount) {
                PdfPagerEngine.clampPage(saved.page, opened.pageCount)
            } else 0
        }
        onDispose {
            // حفظ آخر موضع قراءة
            if (pageCount > 0) readingStore.save(readingKey, page, pageCount)
            runCatching { opened?.close() }
        }
    }

    // ── رسم الصفحة الحالية عند تغيّرها أو تغيّر درجة التكبير ──
    LaunchedEffect(doc, page, nearestRenderZoom(displayZoom)) {
        val d = doc ?: return@LaunchedEffect
        rendering = true
        val zoom = nearestRenderZoom(displayZoom)
        val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { d.render(PdfPagerEngine.clampPage(page, d.pageCount), zoom) }.getOrNull()
        }
        bitmap = bmp
        rendering = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.groupedBg),
    ) {
        IOSNavBar(
            title = file.name,
            subtitle = PdfPagerEngine.pageLabel(page, pageCount),
            onBack = onBack,
            actions = {
                IconButton(onClick = { displayZoom = PdfPagerEngine.zoomIn(displayZoom) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.pdf_zoom_in), tint = palette.label)
                }
                IconButton(onClick = { displayZoom = PdfPagerEngine.zoomOut(displayZoom) }) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.pdf_zoom_out), tint = palette.label)
                }
            },
        )

        // ═══ سطح الصفحة ═══
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            val bmp = bitmap
            if (openError) {
                Text(
                    stringResource(R.string.pdf_error),
                    color = palette.secondaryLabel,
                    modifier = Modifier.padding(24.dp),
                )
            } else if (bmp != null) {
                val renderZoom = nearestRenderZoom(displayZoom)
                val scale = displayZoom / renderZoom
                val scroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .pointerInput(displayZoom) {
                            detectTransformGestures { _, _, gestureZoom, _ ->
                                if (gestureZoom != 1.0f) {
                                    displayZoom = PdfPagerEngine.clampZoom(displayZoom * gestureZoom)
                                }
                            }
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = PdfPagerEngine.pageLabel(page, pageCount),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                    )
                }
            }
            if (rendering && !openError) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        }

        // ═══ شريط التنقل السفلي ═══
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.cardBg)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { page = PdfPagerEngine.prevPage(page, pageCount) },
                enabled = pageCount > 0,
            ) {
                Text("‹", fontSize = 24.sp, color = if (page > 0) palette.label else palette.tertiaryLabel)
            }
            Slider(
                value = if (pageCount > 1) page.toFloat() / (pageCount - 1) else 0f,
                onValueChange = {
                    if (pageCount > 1) {
                        page = kotlin.math.round(it * (pageCount - 1)).toInt()
                    }
                },
                enabled = pageCount > 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { page = PdfPagerEngine.nextPage(page, pageCount) },
                enabled = pageCount > 0,
            ) {
                Text("›", fontSize = 24.sp, color = if (page < pageCount - 1) palette.label else palette.tertiaryLabel)
            }
            // الانتقال المباشر — انقر التسمية
            Text(
                PdfPagerEngine.pageLabel(page, pageCount),
                color = Color(0xFF007AFF),
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .clickableLabel { jumpOpen = true },
            )
        }
    }

    // حوار الانتقال المباشر لصفحة
    if (jumpOpen) {
        AlertDialog(
            onDismissRequest = { jumpOpen = false; jumpError = false },
            title = { Text(stringResource(R.string.pdf_jump)) },
            text = {
                Column {
                    Text(stringResource(R.string.pdf_page_hint, pageCount), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = jumpText,
                        onValueChange = { jumpText = it.filter { c -> c.isDigit() }; jumpError = false },
                        isError = jumpError,
                        singleLine = true,
                    )
                    if (jumpError) {
                        Text(
                            stringResource(R.string.pdf_invalid_page),
                            color = Color(0xFFFF3B30),
                            fontSize = 12.sp,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = jumpText.toIntOrNull()
                    if (n != null && PdfPagerEngine.isValidJump(n, pageCount)) {
                        page = PdfPagerEngine.jumpTo(n, pageCount)
                        jumpOpen = false
                        jumpText = ""
                    } else {
                        jumpError = true
                    }
                }) { Text(stringResource(R.string.action_go)) }
            },
            dismissButton = {
                TextButton(onClick = { jumpOpen = false; jumpText = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** أقرب درجة رسم للتكبير المعروض — الدقة الحقيقية للبتة */
private fun nearestRenderZoom(displayZoom: Float): Float {
    var best = PdfPagerEngine.ZOOM_STEPS[0]
    for (s in PdfPagerEngine.ZOOM_STEPS) {
        if (kotlin.math.abs(s - displayZoom) < kotlin.math.abs(best - displayZoom)) best = s
    }
    return best
}

/** امتداد نقرة بسيط للتسمية */
private fun Modifier.clickableLabel(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
