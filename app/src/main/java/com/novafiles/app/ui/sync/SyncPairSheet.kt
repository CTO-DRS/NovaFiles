package com.novafiles.app.ui.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.sync.ConflictPolicy
import com.novafiles.app.core.sync.SyncDirection
import com.novafiles.app.core.sync.SyncInterval
import com.novafiles.app.data.db.CloudAccountEntity
import com.novafiles.app.data.db.SyncPairEntity
import com.novafiles.app.data.sync.SyncRepository
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSFilledButton
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSMiniField
import com.novafiles.app.ui.ios.IOSSwitch
import com.novafiles.app.ui.ios.LocalIOSPalette
import java.io.File
import kotlinx.coroutines.launch

/**
 * v0.32.0 — ورقة إنشاء/تعديل زوج مزامنة + منتقي مجلدات محلي مدمج.
 * كل القواعد تُطبَّق عبر SyncRepository.validate قبل الحفظ، والجدولة
 * يضبطها المستودع تلقائياً بعد الكتابة.
 */

/** شريحة خيارات منقسمة بنمط iOS (نمط SegmentedAlgo في شاشة التحقق) */
@Composable
private fun SegmentedRow(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val p = LocalIOSPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(p.fill, RoundedCornerShape(9.dp)),
    ) {
        options.forEachIndexed { i, label ->
            val isSel = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
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
                        fontSize = 12.sp,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = p.label,
                    maxLines = 1,
                )
            }
        }
    }
}

/** صف مفتاح بسيط داخل الورقة */
@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    IOSListRow(
        title = title,
        showSeparator = false,
        trailing = { IOSSwitch(checked = checked, onCheckedChange = onChange) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncPairSheet(
    editing: SyncPairEntity?,
    defaultLocalPath: String?,
    accounts: List<CloudAccountEntity>,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val palette = LocalIOSPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = context.novaContainer.sync

    // ── حالة النموذج ──
    var name by remember { mutableStateOf(editing?.name ?: "") }
    var sourceIsCloud by remember { mutableStateOf(editing?.let { it.sourceType == "cloud" } ?: false) }
    var sourcePath by remember {
        mutableStateOf(editing?.sourcePath ?: defaultLocalPath.orEmpty())
    }
    var sourceAccountId by remember { mutableStateOf(editing?.sourceAccountId ?: 0L) }
    var destIsCloud by remember { mutableStateOf(editing?.let { it.destType == "cloud" } ?: false) }
    var destPath by remember {
        mutableStateOf(editing?.destPath ?: "/storage/emulated/0/Documents")
    }
    var destAccountId by remember { mutableStateOf(editing?.destAccountId ?: 0L) }

    var dirIdx by remember { mutableIntStateOf(SyncDirection.entries.indexOfFirst { it.name == editing?.direction }.coerceAtLeast(0)) }
    var policyIdx by remember {
        mutableIntStateOf(
            if (editing?.conflictPolicy == "KEEP_BOTH") 1 else 0,
        )
    }
    var intervalIdx by remember {
        mutableIntStateOf(
            SyncInterval.entries.indexOfFirst { it.name == (editing?.interval ?: "MANUAL") }.coerceAtLeast(0),
        )
    }
    var wifiOnly by remember { mutableStateOf(editing?.wifiOnly ?: true) }
    var requireCharging by remember { mutableStateOf(editing?.requireCharging ?: false) }
    var verify by remember { mutableStateOf(editing?.verifyChecksums ?: false) }
    var enabled by remember { mutableStateOf(editing?.enabled ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickTarget by remember { mutableStateOf<Boolean?>(null) } // null مغلق، true المصدر، false الوجهة

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            // ═══ الاسم ═══
            IOSGroupHeader(stringResource(R.string.sync_pair_name))
            IOSMiniField(
                value = name,
                onValueChange = { name = it },
                hint = stringResource(R.string.sync_pair_name),
                modifier = Modifier.fillMaxWidth(),
            )

            // ═══ المصدر ═══
            IOSGroupHeader(stringResource(R.string.sync_source))
            IOSGroupCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SegmentedRow(
                        options = listOf(
                            stringResource(R.string.sync_side_local),
                            stringResource(R.string.sync_side_cloud),
                        ),
                        selected = if (sourceIsCloud) 1 else 0,
                        onSelect = {
                            sourceIsCloud = it == 1
                            if (sourceIsCloud && sourceAccountId == 0L) sourceAccountId = accounts.firstOrNull()?.id ?: 0L
                        },
                    )
                    if (sourceIsCloud) {
                        if (accounts.isEmpty()) {
                            Text(
                                stringResource(R.string.sync_no_cloud_accounts),
                                style = TextStyle(fontSize = 13.sp),
                                color = palette.destructive,
                            )
                        } else {
                            SegmentedRow(
                                options = accounts.take(3).map { it.displayName }.ifEmpty { listOf("—") },
                                selected = accounts.indexOfFirst { it.id == sourceAccountId }.coerceAtLeast(0),
                                onSelect = { sourceAccountId = accounts[it].id },
                            )
                            IOSMiniField(
                                value = sourcePath,
                                onValueChange = { sourcePath = it },
                                hint = stringResource(R.string.sync_cloud_folder),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        IOSListRow(
                            title = sourcePath.ifEmpty { stringResource(R.string.sync_pick_folder) },
                            showChevron = true,
                            onClick = { pickTarget = true },
                        )
                    }
                }
            }

            // ═══ الوجهة ═══
            IOSGroupHeader(stringResource(R.string.sync_dest))
            IOSGroupCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SegmentedRow(
                        options = listOf(
                            stringResource(R.string.sync_side_local),
                            stringResource(R.string.sync_side_cloud),
                        ),
                        selected = if (destIsCloud) 1 else 0,
                        onSelect = {
                            destIsCloud = it == 1
                            if (destIsCloud && destAccountId == 0L) destAccountId = accounts.firstOrNull()?.id ?: 0L
                        },
                    )
                    if (destIsCloud) {
                        if (accounts.isEmpty()) {
                            Text(
                                stringResource(R.string.sync_no_cloud_accounts),
                                style = TextStyle(fontSize = 13.sp),
                                color = palette.destructive,
                            )
                        } else {
                            SegmentedRow(
                                options = accounts.take(3).map { it.displayName }.ifEmpty { listOf("—") },
                                selected = accounts.indexOfFirst { it.id == destAccountId }.coerceAtLeast(0),
                                onSelect = { destAccountId = accounts[it].id },
                            )
                            IOSMiniField(
                                value = destPath,
                                onValueChange = { destPath = it },
                                hint = stringResource(R.string.sync_cloud_folder),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        IOSListRow(
                            title = destPath,
                            showChevron = true,
                            onClick = { pickTarget = false },
                        )
                    }
                }
            }

            // ═══ الاتجاه والتعارض ═══
            IOSGroupHeader(stringResource(R.string.sync_direction))
            IOSGroupCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SegmentedRow(
                        options = listOf(
                            stringResource(R.string.sync_dir_one_way),
                            stringResource(R.string.sync_dir_mirror),
                            stringResource(R.string.sync_dir_two_way),
                        ),
                        selected = dirIdx,
                        onSelect = { dirIdx = it },
                    )
                    if (SyncDirection.entries[dirIdx] == SyncDirection.TWO_WAY) {
                        IOSGroupHeader(stringResource(R.string.sync_conflict_policy))
                        SegmentedRow(
                            options = listOf(
                                stringResource(R.string.sync_policy_newer),
                                stringResource(R.string.sync_policy_keep_both),
                            ),
                            selected = policyIdx,
                            onSelect = { policyIdx = it },
                        )
                    }
                }
            }

            // ═══ الجدولة والقيود ═══
            IOSGroupHeader(stringResource(R.string.sync_schedule))
            IOSGroupCard {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SegmentedRow(
                        options = listOf(
                            stringResource(R.string.sync_interval_manual),
                            stringResource(R.string.sync_interval_hourly),
                            stringResource(R.string.sync_interval_6h),
                        ),
                        selected = intervalIdx.coerceIn(0, 2),
                        onSelect = { intervalIdx = it },
                    )
                    SegmentedRow(
                        options = listOf(
                            stringResource(R.string.sync_interval_12h),
                            stringResource(R.string.sync_interval_daily),
                            stringResource(R.string.sync_interval_weekly),
                        ),
                        selected = (intervalIdx - 3).coerceIn(0, 2),
                        onSelect = { intervalIdx = it + 3 },
                    )
                    SwitchRow(stringResource(R.string.sync_wifi_only), wifiOnly) { wifiOnly = it }
                    SwitchRow(stringResource(R.string.sync_require_charging), requireCharging) { requireCharging = it }
                    SwitchRow(stringResource(R.string.sync_verify_checksums), verify) { verify = it }
                    SwitchRow(stringResource(R.string.sync_enabled), enabled) { enabled = it }
                }
            }

            // ═══ الخطأ والحفظ ═══
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = TextStyle(fontSize = 13.sp),
                    color = palette.destructive,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            IOSFilledButton(
                text = stringResource(R.string.sync_save),
                onClick = {
                val source = SyncRepository.Side(
                    type = if (sourceIsCloud) SyncRepository.SideType.CLOUD else SyncRepository.SideType.LOCAL,
                    path = sourcePath.trim(),
                    accountId = if (sourceIsCloud) sourceAccountId else 0,
                )
                val dest = SyncRepository.Side(
                    type = if (destIsCloud) SyncRepository.SideType.CLOUD else SyncRepository.SideType.LOCAL,
                    path = destPath.trim(),
                    accountId = if (destIsCloud) destAccountId else 0,
                )
                val validation = SyncRepository.validate(name, source, dest)
                error = when (validation) {
                    SyncRepository.ValidationError.BlankName -> context.getString(R.string.sync_error_blank_name)
                    SyncRepository.ValidationError.LongName -> context.getString(R.string.sync_error_long_name)
                    SyncRepository.ValidationError.SameSide -> context.getString(R.string.sync_error_same_side)
                    SyncRepository.ValidationError.CloudToCloud -> context.getString(R.string.sync_error_cloud_to_cloud)
                    SyncRepository.ValidationError.EmptyCloudPath -> context.getString(R.string.sync_error_empty_cloud_path)
                    null -> null
                }
                if (validation == null) {
                    scope.launch {
                        val entity = (editing?.copy(
                            name = name.trim(),
                            sourceType = source.type.name,
                            sourcePath = source.path,
                            sourceAccountId = source.accountId,
                            destType = dest.type.name,
                            destPath = dest.path,
                            destAccountId = dest.accountId,
                            direction = SyncDirection.entries[dirIdx].name,
                            conflictPolicy = if (policyIdx == 1) "KEEP_BOTH" else "NEWER_WINS",
                            interval = SyncInterval.entries[intervalIdx].name,
                            wifiOnly = wifiOnly,
                            requireCharging = requireCharging,
                            verifyChecksums = verify,
                            enabled = enabled,
                        ) ?: SyncPairEntity(
                            name = name.trim(),
                            sourceType = source.type.name,
                            sourcePath = source.path,
                            sourceAccountId = source.accountId,
                            destType = dest.type.name,
                            destPath = dest.path,
                            destAccountId = dest.accountId,
                            direction = SyncDirection.entries[dirIdx].name,
                            conflictPolicy = if (policyIdx == 1) "KEEP_BOTH" else "NEWER_WINS",
                            interval = SyncInterval.entries[intervalIdx].name,
                            wifiOnly = wifiOnly,
                            requireCharging = requireCharging,
                            verifyChecksums = verify,
                            enabled = enabled,
                        ))
                        val ok = if (editing == null) repo.create(entity) != null else repo.update(entity)
                        if (!ok) {
                            error = context.getString(R.string.sync_error_duplicate_name)
                        } else {
                            onSaved()
                        }
                    }
                }
            },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    // ── منتقي المجلدات المحلي ──
    pickTarget?.let { forSource ->
        FolderPickerSheet(
            startPath = (if (forSource) sourcePath else destPath)
                .ifEmpty { defaultLocalPath.orEmpty() }
                .ifEmpty { "/storage/emulated/0" },
            onDismiss = { pickTarget = null },
            onPick = { picked ->
                if (forSource) sourcePath = picked else destPath = picked
                pickTarget = null
            },
        )
    }
}

/** منتقي مجلد محلي مدمج — تنقل بسيط فوق java.io مع زر استخدام */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderPickerSheet(
    startPath: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val palette = LocalIOSPalette.current
    var current by remember { mutableStateOf(startPath) }

    val dirs = remember(current) {
        runCatching {
            File(current).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                ?.map { it.name }
                ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
                .orEmpty()
        }.getOrDefault(emptyList())
    }
    val parent = File(current).parentFile

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            IOSGroupHeader(stringResource(R.string.sync_pick_folder_title))
            Text(
                current,
                style = TextStyle(fontSize = 12.sp),
                color = palette.secondaryLabel,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            IOSGroupCard {
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    if (parent != null) {
                        item {
                            IOSListRow(
                                title = "…",
                                onClick = { current = parent.absolutePath },
                            )
                        }
                    }
                    items(dirs) { dir ->
                        IOSListRow(
                            title = dir,
                            showChevron = true,
                            onClick = { current = File(current, dir).absolutePath },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            IOSFilledButton(
                text = stringResource(R.string.sync_pick_this_folder),
                onClick = { onPick(current) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
