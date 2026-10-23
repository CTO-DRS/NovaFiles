package com.novafiles.app.ui.analyzer

import android.content.Context
import androidx.compose.runtime.Composable
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.util.Intents
import com.novafiles.app.util.ReportFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v0.36.0 — أدوات تصدير التقارير المشتركة بين تبويبات المحلل.
 * الكتابة إلى Download/NovaFiles-Reports/ ثم فتح مشاركة نظامية للملف نفسه —
 * المشاركة هي التأكيد المرئي، وفشل الكتابة صامت (runCatching في ReportFiles).
 */
internal fun exportReportAndShare(context: Context, kind: String, ext: String, content: String) {
    val f = ReportFiles.write(kind, ext, content)
    if (f != null) Intents.share(context, f)
}

/** زر تصدير iOS — يظهر فقط حين يوجد تقرير قابل للتصدير */
@Composable
internal fun ExportReportButton(
    text: String,
    content: (() -> String)?,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    kind: String,
    ext: String = "csv",
) {
    if (content == null) return
    IOSFilledButton(
        text = text,
        onClick = {
            val c = content()
            scope.launch(Dispatchers.IO) { exportReportAndShare(context, kind, ext, c) }
        },
    )
}
