package com.novafiles.app.ui.terminal

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.terminal.NovaShell
import com.novafiles.app.core.terminal.ShellColor
import com.novafiles.app.core.terminal.ShellLine
import com.novafiles.app.core.terminal.TerminalBuffer
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * الطرفية المدمجة — v0.28.
 *
 * طبقة Compose رقيقة فوق شل NovaFiles الخالص:
 * بطاقة داكنة أحادية المسافة، محث حي بمجلد العمل، شريط مفاتيح مساعد
 * (تاريخ ↑↓، إكمال Tab، مسح)، شارات مرشحي الإكمال، وإتاحة كاملة
 * لناتج الأوامر عبر قارئ الشاشة (نص خام قابل للتحديد).
 */

// لوحة الطرفية الداكنة الثابتة — هوية مستقلة عن لوحة التطبيق
private val TermBg = Color(0xFF0D1117)
private val TermBorder = Color(0xFF30363D)
private val TermText = Color(0xFFE6EDF3)
private val TermBlue = Color(0xFF79C0FF)
private val TermGreen = Color(0xFF7EE787)
private val TermRed = Color(0xFFFF7B72)
private val TermYellow = Color(0xFFD29922)
private val TermDim = Color(0xFF8B949E)
private val TermCyan = Color(0xFF76E3EA)

private fun colorOf(c: ShellColor): Color = when (c) {
    ShellColor.BLUE -> TermBlue
    ShellColor.GREEN -> TermGreen
    ShellColor.RED -> TermRed
    ShellColor.YELLOW -> TermYellow
    ShellColor.DIM -> TermDim
    ShellColor.CYAN -> TermCyan
    ShellColor.DEFAULT -> TermText
}

/** بداية آخر رمز في السطر (يجتهد مع التهريب) — لاستبداله بشارة مرشح */
private fun lastTokenStart(s: String): Int {
    var i = s.length
    while (i > 0) {
        if (s[i - 1] == ' ') {
            var bs = 0
            var j = i - 2
            while (j >= 0 && s[j] == '\\') { bs++; j-- }
            if (bs % 2 == 0) return i
        }
        i--
    }
    return 0
}

@Composable
fun TerminalScreen(onBack: (() -> Unit)? = null) {
    val palette = LocalIOSPalette.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val home = remember {
        Environment.getExternalStorageDirectory()?.absoluteFile ?: File("/storage/emulated/0")
    }
    val shell = remember { NovaShell(home) }
    val buffer = remember { TerminalBuffer() }

    var outLines by remember { mutableStateOf<List<ShellLine>>(emptyList()) }
    var cwdText by remember { mutableStateOf(shell.cwdPath()) }
    var input by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var moreCount by remember { mutableIntStateOf(0) }
    var histIndex by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()

    // سطر الترحيب عند أول فتح
    val welcome = stringResource(R.string.terminal_welcome)
    LaunchedEffect(Unit) {
        if (buffer.isEmpty) {
            buffer.append(ShellLine.dim(welcome))
            outLines = buffer.snapshot()
        }
    }

    // تمرير تلقائي إلى الأسفل مع كل ناتج جديد
    LaunchedEffect(outLines.size, buffer.version) {
        val maxValue = scrollState.maxValue
        if (maxValue > 0) scrollState.animateScrollTo(maxValue)
    }

    fun execute(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return
        histIndex = null
        candidates = emptyList()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { shell.execute(trimmed) }
            buffer.applyOutcome(outcome)
            outLines = buffer.snapshot()
            cwdText = shell.cwdPath()
        }
    }

    fun runCompletion() {
        val comp = shell.complete(input)
        input = comp.line
        candidates = comp.candidates
        moreCount = comp.moreCount
    }

    fun historyStep(dir: Int) {
        val hist = shell.history
        if (hist.isEmpty()) return
        val current = histIndex
        when {
            dir < 0 -> {
                val next = ((current ?: hist.size) - 1).coerceIn(0, hist.size - 1)
                histIndex = next
                input = hist[next]
            }

            else -> {
                if (current == null) return
                val next = current + 1
                if (next >= hist.size) {
                    histIndex = null
                    input = ""
                } else {
                    histIndex = next
                    input = hist[next]
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.groupedBg),
    ) {
        IOSNavBar(title = stringResource(R.string.terminal_title), onBack = onBack)

        // ── بطاقة الطرفية الداكنة ──
        val a11yOutput = stringResource(R.string.terminal_a11y_output)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .background(TermBg, RoundedCornerShape(18.dp))
                .border(0.5.dp, TermBorder, RoundedCornerShape(18.dp))
                .semantics { contentDescription = a11yOutput },
        ) {
            val annotated = remember(outLines) { renderLines(outLines) }
            SelectionContainer {
                Text(
                    text = annotated,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        textDirection = TextDirection.Ltr,
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(14.dp),
                )
            }
        }

        // ── شارات مرشحي الإكمال ──
        if (candidates.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                candidates.forEach { name ->
                    Text(
                        text = name,
                        style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                        color = palette.accent,
                        maxLines = 1,
                        modifier = Modifier
                            .background(palette.cardBg, RoundedCornerShape(8.dp))
                            .border(0.5.dp, palette.separator, RoundedCornerShape(8.dp))
                            .clickable {
                                val start = lastTokenStart(input)
                                val probe = input.substring(0, start) + name
                                val comp = shell.complete(probe)
                                input = comp.line
                                candidates = comp.candidates
                                moreCount = comp.moreCount
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                if (moreCount > 0) {
                    Text(
                        text = "+$moreCount",
                        style = TextStyle(fontSize = 12.sp),
                        color = palette.secondaryLabel,
                    )
                }
            }
        }

        // ── شريط المفاتيح المساعد ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Chip(stringResource(R.string.terminal_chip_history_up)) { historyStep(-1) }
            Chip(stringResource(R.string.terminal_chip_history_down)) { historyStep(1) }
            Chip(stringResource(R.string.terminal_chip_tab)) { runCompletion() }
            Chip("/") { input += "/" }
            Chip("~") { input += "~" }
            Chip(stringResource(R.string.terminal_chip_clear_input)) { input = "" }
            Chip(stringResource(R.string.terminal_chip_clear_screen)) {
                buffer.clear()
                outLines = buffer.snapshot()
            }
        }

        // ── سطر الإدخال مع المحث ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .background(palette.cardBg, RoundedCornerShape(14.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "nova:",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                color = palette.accent,
            )
            Text(
                text = cwdText,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = palette.secondaryLabel,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 4.dp)
                    .clickable {
                        clipboard.setText(AnnotatedString(cwdText))
                    },
            )
            Box(modifier = Modifier.weight(1f)) {
                if (input.isEmpty()) {
                    Text(
                        text = stringResource(R.string.terminal_input_hint),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        color = palette.tertiaryLabel,
                        maxLines = 1,
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = palette.label,
                        textDirection = TextDirection.Ltr,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { execute(input) }),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                )
            }
            if (input.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.terminal_send),
                    color = palette.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { execute(input) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }

        // ── تلميح سفلي ──
        Text(
            text = stringResource(R.string.terminal_footer_tip),
            style = TextStyle(fontSize = 11.sp),
            color = palette.tertiaryLabel,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 14.dp),
        )
    }
}

@Composable
private fun Chip(label: String, onTap: () -> Unit) {
    val palette = LocalIOSPalette.current
    Text(
        text = label,
        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        color = palette.label,
        modifier = Modifier
            .background(palette.cardBg, RoundedCornerShape(10.dp))
            .border(0.5.dp, palette.separator, RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** تحويل أسطر الناتج إلى نص منسّق واحد قابل للتحديد والقراءة */
private fun renderLines(lines: List<ShellLine>): AnnotatedString = buildAnnotatedString {
    lines.forEachIndexed { i, line ->
        if (i > 0) append("\n")
        withStyle(
            SpanStyle(
                color = colorOf(line.color),
                fontWeight = if (line.color == ShellColor.RED) FontWeight.SemiBold else FontWeight.Normal,
            ),
        ) { append(line.text) }
    }
}
