@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novafiles.app.R
import com.novafiles.app.core.rename.CaseMode
import com.novafiles.app.core.rename.RenameEngine
import com.novafiles.app.core.rename.RenameOptions
import com.novafiles.app.core.rename.RenameStatus
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.LocalIOSPalette
import java.io.File

/**
 * v0.12 — ورقة إعادة التسمية الجماعية بنمط iOS.
 *
 * منشئ أنماط حي: بادئة/لاحقة/اسم أساس + عدّاد محشو + بحث واستبدال
 * (حرفي أو Regex) + تحويل الحالة + تاريخ آخر تعديل — مع معاينة
 * مباشرة لكل ملف وحالة تعارضه، ثم تنفيذ مع تراجع من شريط التنبيهات.
 */
@Composable
fun BatchRenameSheet(
    files: List<File>,
    onDismiss: () -> Unit,
    onApply: (List<com.novafiles.app.core.rename.RenameItem>) -> Unit,
) {
    var prefix by remember { mutableStateOf("") }
    var suffix by remember { mutableStateOf("") }
    var baseName by remember { mutableStateOf("") }
    var useCounter by remember { mutableStateOf(false) }
    var padding by remember { mutableStateOf(0) }
    var find by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var caseMode by remember { mutableStateOf(CaseMode.KEEP) }
    var insertDate by remember { mutableStateOf(false) }

    val options = RenameOptions(
        prefix = prefix,
        suffix = suffix,
        baseName = baseName.ifBlank { null },
        useCounter = useCounter,
        counterPadding = padding,
        find = find,
        replacement = replacement,
        regex = regex,
        caseMode = caseMode,
        insertDate = insertDate,
    )
    // المعاينة تُحسب حياً عند أي تغيير — خفيفة حتى مئات الملفات
    val plan = remember(options, files) { RenameEngine.plan(files, options) }
    val okCount = plan.count { it.status == RenameStatus.OK }
    val palette = LocalIOSPalette.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.batch_title, files.size),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.batch_subtitle, okCount, plan.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // الصف الأول: بادئة / لاحقة
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text(stringResource(R.string.batch_prefix)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = suffix,
                    onValueChange = { suffix = it },
                    label = { Text(stringResource(R.string.batch_suffix)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = baseName,
                    onValueChange = { baseName = it },
                    label = { Text(stringResource(R.string.batch_base)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.batch_counter), style = MaterialTheme.typography.labelSmall)
                    Switch(checked = useCounter, onCheckedChange = { useCounter = it })
                }
            }
            if (useCounter) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 1, 2, 3).forEach { p ->
                        FilterChip(
                            selected = padding == p,
                            onClick = { padding = p },
                            label = { Text(if (p == 0) "1" else "%0${p}d".format(1)) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // بحث واستبدال
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it },
                    label = { Text(stringResource(R.string.batch_find)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text(stringResource(R.string.batch_replace)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.batch_regex), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(4.dp))
                Switch(checked = regex, onCheckedChange = { regex = it })
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.batch_date), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(4.dp))
                Switch(checked = insertDate, onCheckedChange = { insertDate = it })
            }
            Spacer(Modifier.height(4.dp))

            // تحويل الحالة
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val labels = mapOf(
                    CaseMode.KEEP to stringResource(R.string.case_keep),
                    CaseMode.LOWER to stringResource(R.string.case_lower),
                    CaseMode.UPPER to stringResource(R.string.case_upper),
                    CaseMode.CAPITALIZE to stringResource(R.string.case_capitalize),
                )
                labels.forEach { (mode, label) ->
                    FilterChip(selected = caseMode == mode, onClick = { caseMode = mode }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // المعاينة الحية
            Text(stringResource(R.string.batch_preview), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.weight(1f, fill = false).height(220.dp)) {
                items(plan) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.file.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1.2f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val (text, color) = when (item.status) {
                            RenameStatus.OK -> item.newName.orEmpty() to palette.accent
                            RenameStatus.UNCHANGED -> stringResource(R.string.batch_unchanged) to
                                MaterialTheme.colorScheme.onSurfaceVariant
                            RenameStatus.EXISTS -> stringResource(R.string.batch_exists) to palette.destructive
                            RenameStatus.DUPLICATE -> stringResource(R.string.batch_duplicate) to palette.destructive
                            RenameStatus.INVALID -> stringResource(R.string.batch_invalid) to palette.destructive
                        }
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = color,
                            modifier = Modifier.weight(1.2f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            IOSFilledButton(
                text = stringResource(R.string.batch_apply, okCount),
                onClick = { if (okCount > 0) { onApply(plan); onDismiss() } },
                destructive = false,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
