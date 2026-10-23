@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.checksum.ChecksumAlgorithm
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.core.rules.RuleAction
import com.novafiles.app.core.rules.RuleConditions
import com.novafiles.app.core.rules.WatchRule
import com.novafiles.app.data.db.CloudAccountEntity
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSMiniField
import com.novafiles.app.ui.ios.IOSSwitch
import com.novafiles.app.ui.ios.LocalIOSPalette

/**
 * v0.35 — ورقة محرر القاعدة السفلية: الاسم، مجلد المراقبة، الشروط
 * (نمط/أنواع/حجم)، الأفعال بالترتيب، وضع التجربة الجافة، والتحقق قبل الحفظ.
 */
@Composable
fun RuleEditorSheet(
    draft: WatchRule,
    isNew: Boolean,
    accounts: List<CloudAccountEntity>,
    onDismiss: () -> Unit,
    onSave: (WatchRule) -> Unit,
) {
    val p = LocalIOSPalette.current
    var rule by remember(draft.id) { mutableStateOf(draft) }
    var accountPickOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }

    val errors = rule.validationErrors()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = if (isNew) stringResource(R.string.rules_editor_new) else stringResource(R.string.rules_editor_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = p.label,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            // ── الأساس ──
            IOSGroupHeader(text = stringResource(R.string.rules_section_basics))
            IOSGroupCard {
                EditorFieldRow(stringResource(R.string.rules_field_name)) {
                    IOSMiniField(
                        value = rule.name,
                        onValueChange = { rule = rule.copy(name = it) },
                        hint = stringResource(R.string.rules_field_name_hint),
                        modifier = Modifier.width(180.dp),
                    )
                }
                EditorFieldRow(stringResource(R.string.rules_field_folder)) {
                    IOSMiniField(
                        value = rule.watchFolder,
                        onValueChange = { rule = rule.copy(watchFolder = it) },
                        hint = stringResource(R.string.rules_field_folder_hint),
                        modifier = Modifier.width(180.dp),
                    )
                }
            }

            // ── الشروط ──
            IOSGroupHeader(text = stringResource(R.string.rules_conditions_header))
            IOSGroupCard {
                EditorFieldRow(stringResource(R.string.rules_cond_glob)) {
                    IOSMiniField(
                        value = rule.conditions.nameGlob.orEmpty(),
                        onValueChange = { g -> rule = rule.copy(conditions = rule.conditions.copy(nameGlob = g.ifBlank { null })) },
                        hint = stringResource(R.string.rules_cond_glob_hint),
                        modifier = Modifier.width(180.dp),
                    )
                }
                // أنواع الملفات — رقائق قابلة للاختيار المتعدد
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        stringResource(R.string.rules_cond_types),
                        fontSize = 13.sp, color = p.secondaryLabel,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val selectable = listOf(
                            FileType.IMAGE, FileType.VIDEO, FileType.AUDIO,
                            FileType.DOC, FileType.PDF, FileType.ARCHIVE,
                            FileType.APK, FileType.TEXT, FileType.CODE,
                        )
                        selectable.forEach { t ->
                            val selected = t in rule.conditions.types
                            Text(
                                text = typeLabel(t),
                                fontSize = 12.sp,
                                color = if (selected) androidx.compose.ui.graphics.Color.White else p.label,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier
                                    .background(
                                        if (selected) p.accent else p.fill,
                                        RoundedCornerShape(14.dp),
                                    )
                                    .clickable {
                                        rule = rule.copy(
                                            conditions = rule.conditions.copy(
                                                types = if (selected) rule.conditions.types - t
                                                else rule.conditions.types + t,
                                            ),
                                        )
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                EditorFieldRow(stringResource(R.string.rules_cond_min)) {
                    IOSMiniField(
                        value = rule.conditions.minSizeBytes?.let { (it / (1024 * 1024)).toString() }.orEmpty(),
                        onValueChange = { s ->
                            val mb = s.toLongOrNull()
                            rule = rule.copy(conditions = rule.conditions.copy(minSizeBytes = mb?.times(1024 * 1024)))
                        },
                        hint = "0",
                        modifier = Modifier.width(90.dp),
                    )
                }
                EditorFieldRow(stringResource(R.string.rules_cond_max)) {
                    IOSMiniField(
                        value = rule.conditions.maxSizeBytes?.let { (it / (1024 * 1024)).toString() }.orEmpty(),
                        onValueChange = { s ->
                            val mb = s.toLongOrNull()
                            rule = rule.copy(conditions = rule.conditions.copy(maxSizeBytes = mb?.times(1024 * 1024)))
                        },
                        hint = "∞",
                        modifier = Modifier.width(90.dp),
                    )
                }
            }

            // ── الأفعال ──
            IOSGroupHeader(text = stringResource(R.string.rules_actions_header))
            IOSGroupCard {
                rule.actions.forEachIndexed { idx, action ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${idx + 1}. ${actionLabel(action)}",
                            fontSize = 14.sp, color = p.label,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.rules_action_remove),
                            tint = p.secondaryLabel,
                            modifier = Modifier
                                .clickable { rule = rule.copy(actions = rule.actions - action) }
                                .padding(4.dp),
                        )
                    }
                    // تفاصيل الفعل تحت عنوانه
                    ActionConfig(
                        action = action,
                        accounts = accounts,
                        accountLabel = accounts.firstOrNull { it.id == (action as? RuleAction.UploadCloud)?.accountId }?.displayName,
                        onAccountPick = { accountPickOpen = true },
                        onChange = { new ->
                            rule = rule.copy(actions = rule.actions.mapIndexed { i, a -> if (i == idx) new else a })
                        },
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { addOpen = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.rules_action_add),
                        fontSize = 15.sp, color = p.accent, fontWeight = FontWeight.Medium,
                    )
                }
            }

            // ── التجربة الجافة والحفظ ──
            IOSGroupCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.rules_dry_run_switch),
                            fontSize = 15.sp, color = p.label,
                        )
                        Text(
                            stringResource(R.string.rules_dry_run_hint),
                            fontSize = 12.sp, color = p.secondaryLabel,
                        )
                    }
                    IOSSwitch(checked = rule.dryRun, onCheckedChange = { rule = rule.copy(dryRun = it) })
                }
            }

            if (errors.isNotEmpty()) {
                Text(
                    text = errors.map { errorLabel(it) }.joinToString("\n"),
                    fontSize = 13.sp, color = p.destructive,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }

            Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(
                    stringResource(R.string.action_cancel),
                    fontSize = 16.sp, color = p.secondaryLabel,
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.rules_save),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (errors.isEmpty()) p.accent else p.secondaryLabel,
                    modifier = Modifier
                        .clickable(enabled = errors.isEmpty()) { onSave(rule) }
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                )
            }
        }
    }

    if (addOpen) {
        IOSActionSheet(
            visible = true,
            onDismiss = { addOpen = false },
            cancelText = stringResource(R.string.action_cancel),
            title = stringResource(R.string.rules_action_add),
            actions = listOf(
                RuleAction.MoveTo("") to R.string.rules_action_move,
                RuleAction.ArchiveZip("") to R.string.rules_action_zip,
                RuleAction.Checksum(ChecksumAlgorithm.SHA256) to R.string.rules_action_sum,
                RuleAction.TagFile("") to R.string.rules_action_tag,
                RuleAction.UploadCloud(0L, "/NovaFiles") to R.string.rules_action_cloud,
            ).map { (action, label) ->
                IOSAction(stringResource(label)) {
                    addOpen = false
                    rule = rule.copy(actions = rule.actions + action)
                }
            },
        )
    }

    if (accountPickOpen) {
        IOSActionSheet(
            visible = true,
            onDismiss = { accountPickOpen = false },
            cancelText = stringResource(R.string.action_cancel),
            title = stringResource(R.string.rules_cloud_account),
            actions = accounts.map { acct ->
                IOSAction("${acct.displayName} (${acct.provider})") {
                    accountPickOpen = false
                    val i = rule.actions.indexOfFirst { it is RuleAction.UploadCloud }
                    if (i >= 0) {
                        rule = rule.copy(
                            actions = rule.actions.mapIndexed { j, a ->
                                if (j == i) (a as RuleAction.UploadCloud).copy(accountId = acct.id) else a
                            },
                        )
                    }
                }
            },
        )
    }
}

/** صف تكوين فعل — يظهر فقط للأفعال ذات المعاملات */
@Composable
private fun ActionConfig(
    action: RuleAction,
    accounts: List<CloudAccountEntity>,
    accountLabel: String?,
    onAccountPick: () -> Unit,
    onChange: (RuleAction) -> Unit,
) {
    val p = LocalIOSPalette.current
    when (action) {
        is RuleAction.MoveTo -> Row(
            Modifier.padding(start = 28.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.rules_move_target), fontSize = 12.sp, color = p.secondaryLabel)
            Spacer(Modifier.width(8.dp))
            IOSMiniField(
                value = action.targetDir,
                onValueChange = { onChange(action.copy(targetDir = it)) },
                hint = stringResource(R.string.rules_move_target_hint),
                modifier = Modifier.weight(1f),
            )
        }
        is RuleAction.ArchiveZip -> Column(Modifier.padding(start = 28.dp, end = 16.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rules_zip_name), fontSize = 12.sp, color = p.secondaryLabel)
                Spacer(Modifier.width(8.dp))
                IOSMiniField(
                    value = action.namePattern,
                    onValueChange = { onChange(action.copy(namePattern = it)) },
                    hint = "WhatsApp-{date}",
                    modifier = Modifier.weight(1f),
                )
            }
            ToggleRow(stringResource(R.string.rules_zip_append), action.append) {
                onChange(action.copy(append = it))
            }
            ToggleRow(stringResource(R.string.rules_zip_delete), action.deleteAfter) {
                onChange(action.copy(deleteAfter = it))
            }
        }
        is RuleAction.TagFile -> Row(
            Modifier.padding(start = 28.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.rules_tag_text), fontSize = 12.sp, color = p.secondaryLabel)
            Spacer(Modifier.width(8.dp))
            IOSMiniField(
                value = action.tag,
                onValueChange = { onChange(action.copy(tag = it)) },
                hint = stringResource(R.string.rules_tag_text_hint),
                modifier = Modifier.weight(1f),
            )
        }
        is RuleAction.UploadCloud -> Column(Modifier.padding(start = 28.dp, end = 16.dp, bottom = 8.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = accounts.isNotEmpty()) { onAccountPick() }
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    stringResource(R.string.rules_cloud_account) + ": ",
                    fontSize = 12.sp, color = p.secondaryLabel,
                )
                Text(
                    accountLabel ?: stringResource(R.string.rules_cloud_pick),
                    fontSize = 12.sp,
                    color = if (accountLabel != null) p.label else p.destructive,
                    fontWeight = FontWeight.Medium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rules_cloud_dir), fontSize = 12.sp, color = p.secondaryLabel)
                Spacer(Modifier.width(8.dp))
                IOSMiniField(
                    value = action.remoteDir,
                    onValueChange = { onChange(action.copy(remoteDir = it)) },
                    hint = "/NovaFiles-Docs",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is RuleAction.Checksum -> Row(
            Modifier.padding(start = 28.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.rules_sum_algo), fontSize = 12.sp, color = p.secondaryLabel)
            ChecksumAlgorithm.entries.forEach { algo ->
                val selected = algo == action.algorithm
                Text(
                    text = when (algo) {
                        ChecksumAlgorithm.MD5 -> "MD5"
                        ChecksumAlgorithm.SHA1 -> "SHA-1"
                        ChecksumAlgorithm.SHA256 -> "SHA-256"
                    },
                    fontSize = 12.sp,
                    color = if (selected) androidx.compose.ui.graphics.Color.White else p.label,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier
                        .background(if (selected) p.accent else p.fill, RoundedCornerShape(12.dp))
                        .clickable { onChange(action.copy(algorithm = algo)) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = LocalIOSPalette.current.secondaryLabel, modifier = Modifier.weight(1f))
        IOSSwitch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun EditorFieldRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = LocalIOSPalette.current.label, modifier = Modifier.weight(1f))
        content()
    }
}

/** تسمية نوع مترجمة — أسماء قصيرة تقنية مقبولة بالعربي أيضاً */
@Composable
internal fun typeLabel(t: FileType): String = when (t) {
    FileType.IMAGE -> stringResource(R.string.rules_type_image)
    FileType.VIDEO -> stringResource(R.string.rules_type_video)
    FileType.AUDIO -> stringResource(R.string.rules_type_audio)
    FileType.DOC -> stringResource(R.string.rules_type_doc)
    FileType.PDF -> "PDF"
    FileType.ARCHIVE -> stringResource(R.string.rules_type_archive)
    FileType.APK -> "APK"
    FileType.TEXT -> stringResource(R.string.rules_type_text)
    FileType.CODE -> stringResource(R.string.rules_type_code)
    else -> stringResource(R.string.rules_type_other)
}

/** تسمية فعل مترجمة مع ملخص معاملها */
@Composable
internal fun actionLabel(a: RuleAction): String = when (a) {
    is RuleAction.MoveTo -> stringResource(R.string.rules_action_move) + " → " + a.targetDir.substringAfterLast('/')
    is RuleAction.ArchiveZip -> stringResource(R.string.rules_action_zip) + " · " + a.namePattern
    is RuleAction.Checksum -> stringResource(R.string.rules_action_sum) + " · " + a.algorithm.name
    is RuleAction.TagFile -> stringResource(R.string.rules_action_tag) + " · " + a.tag
    is RuleAction.UploadCloud -> stringResource(R.string.rules_action_cloud) + " · " + a.remoteDir
}

/** رسائل أخطاء التحقق مترجمة */
@Composable
internal fun errorLabel(code: String): String = when (code) {
    "name_blank" -> stringResource(R.string.rules_error_name)
    "folder_blank" -> stringResource(R.string.rules_error_folder)
    "no_actions" -> stringResource(R.string.rules_error_no_actions)
    "move_into_watch" -> stringResource(R.string.rules_error_move_watch)
    "cloud_account_missing" -> stringResource(R.string.rules_error_cloud)
    "tag_blank" -> stringResource(R.string.rules_error_tag)
    "zip_name_blank" -> stringResource(R.string.rules_error_zip_name)
    else -> code
}
