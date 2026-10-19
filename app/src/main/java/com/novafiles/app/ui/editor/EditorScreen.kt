@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import com.novafiles.app.R
import com.novafiles.app.core.editor.InlineStyle
import com.novafiles.app.core.editor.MarkdownRenderer
import com.novafiles.app.core.editor.MdBlock
import com.novafiles.app.core.editor.SyntaxHighlighter
import com.novafiles.app.core.editor.TokenKind
import com.novafiles.app.core.editor.VirtualTextBuffer
import com.novafiles.app.ui.ios.IOSNavTextButton
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** حد وضع التحرير الكامل — ما فوقه يفتح بالوضع الافتراضي المتجول (v0.17) */
private const val EDIT_LIMIT_BYTES = 1024 * 1024

/**
 * محرر النصوص 2.0 (v0.17):
 *  - Markdown: معاينة منسقة بتحويل فوري (تبويب تحرير/معاينة)
 *  - الشيفرة: وضع تلوين للقراءة بخط احتكاري ملون حسب اللغة
 *  - الملفات الضخمة (>1MB): وضع افتراضي بنوافذ — لا يُحمَّل الملف كاملاً
 *    مع قفز إلى سطر وتنقل صفحة صفحة
 */
@Composable
fun EditorScreen(path: String?) {
    val snackbar = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var content by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }

    val file = remember(path) { path?.let { File(it) } }
    val isMarkdown = remember(path) {
        path != null && path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")
    }
    val codeLang = remember(path) { path?.let { SyntaxHighlighter.languageOf(it) } }
    val isLarge = remember(file) { file != null && file.length() > EDIT_LIMIT_BYTES }

    // وضع العرض الحالي: تحرير / معاينة / تلوين (للشيفرة)
    var previewMode by remember { mutableStateOf(false) }
    var highlightMode by remember { mutableStateOf(false) }

    // الوضع الافتراضي للملفات الضخمة
    var bigLine by remember { mutableIntStateOf(0) }
    var bigLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var bigTotal by remember { mutableIntStateOf(0) }
    var bigReader by remember { mutableStateOf<VirtualTextBuffer?>(null) }
    var jumpText by remember { mutableStateOf("") }

    val savedMsg = stringResource(R.string.editor_saved)
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    LaunchedEffect(path) {
        if (file == null || !file.exists()) {
            error = "no file"
            loaded = true
            return@LaunchedEffect
        }
        if (isLarge) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val raf = RandomAccessFile(file, "r")
                    val reader = object : VirtualTextBuffer.PageReader {
                        override val length: Long = raf.length()
                        override fun readAt(offset: Long, len: Int): ByteArray {
                            raf.seek(offset)
                            val buf = ByteArray(len)
                            val n = raf.read(buf)
                            return if (n == len) buf else buf.copyOfRange(0, n.coerceAtLeast(0))
                        }
                    }
                    val vtb = VirtualTextBuffer(reader)
                    bigReader = vtb
                    if (vtb.isText) {
                        bigTotal = vtb.lineCount.toInt()
                        bigLines = vtb.readWindow(0, 60)
                    } else {
                        error = "binary"
                    }
                }.onFailure { error = "no file" }
            }
        } else {
            content = withContext(Dispatchers.IO) {
                runCatching { file.readText() }.getOrDefault("")
            }
        }
        loaded = true
    }

    // تحميل النافذة عند تنقل الملف الضخم
    LaunchedEffect(bigLine, bigReader) {
        val vtb = bigReader ?: return@LaunchedEffect
        if (!vtb.isText) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { vtb.readWindow(bigLine.toLong(), 60) }
                .onSuccess { bigLines = it }
        }
    }

    fun save() {
        val f = file ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { f.writeText(content) }
                .onSuccess { error = null }
            withContext(Dispatchers.Main) {
                snackbar.showSnackbar(savedMsg)
                dirty = false
            }
        }
    }

    Scaffold(
        containerColor = com.novafiles.app.ui.ios.LocalIOSPalette.current.groupedBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            IOSNavBar(
                title = File(path ?: "").name.ifEmpty { stringResource(R.string.editor_title) },
                collapsed = true,
                onBack = { backDispatcher?.onBackPressed() },
                actions = {
                    if (isMarkdown && !isLarge && error == null) {
                        IOSNavTextButton(
                            text = stringResource(
                                if (previewMode) R.string.editor_edit else R.string.editor_preview,
                            ),
                        ) { previewMode = !previewMode }
                    }
                    if (codeLang != null && !isLarge && !isMarkdown && error == null) {
                        IOSNavTextButton(
                            text = stringResource(
                                if (highlightMode) R.string.editor_plain else R.string.editor_highlight,
                            ),
                        ) { highlightMode = !highlightMode }
                    }
                    if (error == null && path != null && !isLarge) {
                        IconButton(onClick = { save() }) {
                            Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.action_save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !loaded -> Box(Modifier.fillMaxSize()) {}
                error == "binary" -> CenterMsg(stringResource(R.string.editor_binary))
                error == "too_large" -> CenterMsg(stringResource(R.string.editor_too_large))
                error == "no file" -> CenterMsg(stringResource(R.string.editor_read_fail))
                isLarge -> LargeFileView(
                    lines = bigLines,
                    centerLine = bigLine,
                    totalLines = bigTotal,
                    onJump = { line -> bigLine = line.coerceIn(0, (bigTotal - 1).coerceAtLeast(0)) },
                    jumpText = jumpText,
                    onJumpTextChange = { jumpText = it },
                )
                previewMode && isMarkdown -> MarkdownPreview(content)
                highlightMode && codeLang != null -> HighlightedCode(content, codeLang)
                else -> OutlinedTextField(
                    value = content,
                    onValueChange = { content = it; dirty = true },
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }
    }
}

/** عرض الملف الضخم: نافذة أسطر + تنقل صفحات + قفز لسطر */
@Composable
private fun LargeFileView(
    lines: List<String>,
    centerLine: Int,
    totalLines: Int,
    onJump: (Int) -> Unit,
    jumpText: String,
    onJumpTextChange: (String) -> Unit,
) {
    val palette = LocalIOSPalette.current
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onJump(centerLine - 100) }) {
                Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null, tint = palette.accent)
            }
            Text(
                stringResource(R.string.editor_virtual_position, centerLine + 1, totalLines),
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondaryLabel,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onJump(centerLine + 100) }) {
                Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null, tint = palette.accent)
            }
            OutlinedTextField(
                value = jumpText,
                onValueChange = onJumpTextChange,
                modifier = Modifier.width(110.dp).padding(end = 8.dp),
                placeholder = { Text(stringResource(R.string.editor_jump_to_line), fontSize = 11.sp) },
                textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                singleLine = true,
            )
            IOSNavTextButton(text = stringResource(R.string.editor_go)) {
                jumpText.toIntOrNull()?.let { onJump(it - 1) }
            }
        }
        Text(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            text = lines.joinToString("\n"),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}

/** معاينة Markdown المنسقة */
@Composable
private fun MarkdownPreview(content: String) {
    val palette = LocalIOSPalette.current
    val blocks = remember(content) { MarkdownRenderer.parse(content) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = annotated(block.spans, palette.accent),
                    fontSize = when (block.level) {
                        1 -> 26.sp; 2 -> 22.sp; 3 -> 19.sp; else -> 17.sp
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                is MdBlock.Paragraph -> Text(
                    text = annotated(block.spans, palette.accent),
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                is MdBlock.ListItem -> Row(Modifier.padding(start = (block.indent * 16).dp, top = 2.dp, bottom = 2.dp)) {
                    Text(
                        if (block.ordered) "${block.number ?: 1}. " else "•  ",
                        color = palette.accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = annotated(block.spans, palette.accent),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is MdBlock.Quote -> Row(Modifier.padding(vertical = 4.dp)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(40.dp)
                            .background(palette.accent.copy(alpha = 0.55f)),
                    )
                    Text(
                        text = annotated(block.spans, palette.accent),
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                        color = palette.secondaryLabel,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is MdBlock.CodeFence -> Text(
                    text = block.lines.joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = palette.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                )
                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                        .height(1.dp)
                        .background(palette.separator),
                )
            }
        }
    }
}

/** امتدادات Markdown إلى AnnotatedString */
private fun annotated(
    spans: List<com.novafiles.app.core.editor.InlineSpan>,
    accent: Color,
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        val style = SpanStyle(
            fontWeight = if (InlineStyle.BOLD in span.styles) FontWeight.Bold else null,
            fontStyle = if (InlineStyle.ITALIC in span.styles) FontStyle.Italic else null,
            textDecoration = if (InlineStyle.STRIKE in span.styles) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            fontFamily = if (InlineStyle.CODE in span.styles) FontFamily.Monospace else null,
            background = if (InlineStyle.CODE in span.styles) accent.copy(alpha = 0.12f) else Color.Unspecified,
            color = if (InlineStyle.LINK in span.styles) accent else Color.Unspecified,
        )
        withStyle(style) { append(span.text) }
    }
}

/** كود ملون للقراءة */
@Composable
private fun HighlightedCode(content: String, language: String) {
    val palette = LocalIOSPalette.current
    val lines = remember(content) { content.lines() }
    val tokensPerLine = remember(content, language) { SyntaxHighlighter.highlight(lines, language) }
    val accent = palette.accent
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        lines.forEachIndexed { idx, line ->
            Text(
                text = coloredLine(line, tokensPerLine[idx], accent),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
            )
        }
    }
}

/** تحويل توكنات سطر إلى AnnotatedString بألوان دالة */
private fun coloredLine(line: String, tokens: List<com.novafiles.app.core.editor.Token>, accent: Color): AnnotatedString =
    buildAnnotatedString {
        append(line)
        tokens.forEach { t ->
            if (t.end > line.length || t.start >= t.end) return@forEach
            val color = when (t.kind) {
                TokenKind.KEYWORD -> accent
                TokenKind.STRING -> Color(0xFF34C759)
                TokenKind.COMMENT -> Color(0xFF98989E)
                TokenKind.NUMBER -> Color(0xFFFF9500)
                TokenKind.ANNOTATION -> Color(0xFFBF5AF2)
                TokenKind.TAG -> accent
                TokenKind.PROPERTY -> Color(0xFF64D2FF)
                TokenKind.PUNCT -> Color.Unspecified
            }
            if (color != Color.Unspecified) {
                addStyle(SpanStyle(color = color), t.start, t.end)
            }
        }
    }

@Composable
private fun CenterMsg(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
