@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.novafiles.app.R
import com.novafiles.app.core.archive.ArchiveRequest
import com.novafiles.app.core.archive.CreateFormat
import com.novafiles.app.core.archive.CreateLevel
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.LocalIOSPalette

/**
 * v0.30 — ورقة «ضغط إلى…» بنمط iOS.
 *
 * من التحديد المتعدد: اختيار الصيغة (ZIP/7z/TAR/TAR.GZ/TAR.BZ2/TAR.XZ)
 * ومستوى الضغط وكلمة مرور اختيارية (ZIP AES-256 / 7z AES-256) وحجم تقسيم
 * اختياري مع فهرس إعادة تجميع — ثم الإنشاء عبر قائمة العمليات الخلفية
 * مع تقدم حي وبصمة SHA-256 تلقائية في سجل العملية.
 */
@Composable
fun CompressToSheet(
    itemCount: Int,
    suggestedName: String,
    onDismiss: () -> Unit,
    onCompress: (ArchiveRequest) -> Unit,
) {
    var format by remember { mutableStateOf(CreateFormat.ZIP) }
    var level by remember { mutableStateOf(CreateLevel.BALANCED) }
    var password by remember { mutableStateOf("") }
    var splitBytes by remember { mutableStateOf(0L) }
    val palette = LocalIOSPalette.current

    val passwordUsable = format == CreateFormat.ZIP || format == CreateFormat.SEVEN_Z
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.compress_to_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.compress_to_subtitle, itemCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // ── الصيغة ──
            Text(
                stringResource(R.string.compress_format),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CreateFormat.entries.forEach { f ->
                    FilterChip(
                        selected = format == f,
                        onClick = { format = f },
                        label = { Text(f.ext) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── مستوى الضغط ──
            Text(
                stringResource(R.string.compress_level),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                levelChip(CreateLevel.STORE, R.string.compress_level_store, level) { level = it }
                levelChip(CreateLevel.FASTEST, R.string.compress_level_fastest, level) { level = it }
                levelChip(CreateLevel.BALANCED, R.string.compress_level_balanced, level) { level = it }
                levelChip(CreateLevel.MAXIMUM, R.string.compress_level_maximum, level) { level = it }
            }
            Spacer(Modifier.height(12.dp))

            // ── كلمة المرور (ZIP/7z فقط) ──
            Text(
                stringResource(R.string.compress_password),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = passwordUsable,
                singleLine = true,
                placeholder = {
                    Text(
                        stringResource(R.string.compress_password_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
            )
            Spacer(Modifier.height(12.dp))

            // ── تقسيم الأحجام ──
            Text(
                stringResource(R.string.compress_split),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                splitChip(0L, stringResource(R.string.compress_split_none), splitBytes) { splitBytes = it }
                splitChip(1L * 1024 * 1024, "1 MB", splitBytes) { splitBytes = it }
                splitChip(10L * 1024 * 1024, "10 MB", splitBytes) { splitBytes = it }
                splitChip(100L * 1024 * 1024, "100 MB", splitBytes) { splitBytes = it }
                splitChip(1024L * 1024 * 1024, "1 GB", splitBytes) { splitBytes = it }
            }
            Spacer(Modifier.height(12.dp))

            // ── الاسم الناتج ──
            Text(
                stringResource(R.string.compress_output_name, "$suggestedName${format.ext}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            IOSFilledButton(
                text = stringResource(R.string.compress_action),
                onClick = {
                    onCompress(
                        ArchiveRequest(
                            format = format,
                            level = level,
                            password = password.takeIf { passwordUsable && it.isNotEmpty() }?.toCharArray(),
                            splitBytes = splitBytes,
                        ),
                    )
                },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun levelChip(
    value: CreateLevel,
    labelRes: Int,
    current: CreateLevel,
    onSelect: (CreateLevel) -> Unit,
) {
    FilterChip(
        selected = current == value,
        onClick = { onSelect(value) },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
private fun splitChip(
    value: Long,
    label: String,
    current: Long,
    onSelect: (Long) -> Unit,
) {
    FilterChip(
        selected = current == value,
        onClick = { onSelect(value) },
        label = { Text(label) },
    )
}
