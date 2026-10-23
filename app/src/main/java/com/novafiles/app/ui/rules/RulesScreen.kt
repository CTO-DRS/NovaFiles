@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.rules.RuleEventType
import com.novafiles.app.core.rules.RuleWatcherService
import com.novafiles.app.core.rules.RuleTemplates
import com.novafiles.app.core.rules.WatchRule
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.ui.ios.IOSAlertDialog
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupFooter
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.IOSSwitch
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * v0.35 — شاشة «الأتمتة»: قواعد «عند ظهور X افعل Y» بنمط iOS.
 * مفتاح المراقبة الحية (خدمة FileObserver)، قائمة القواعد مع تشغيل/معاينة/
 * تراجع، قوالب جاهزة، وسجل التنفيذ الحي.
 */
@Composable
fun RulesScreen(
    initialPath: String?,
    onBack: () -> Unit,
) {
    val p = LocalIOSPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = context.novaContainer

    val rules by container.rulesRepo.rules.collectAsState(initial = emptyList())
    val logEvents by container.ruleLog.events.collectAsState()
    val runs by container.ruleEngine.runs.collectAsState()
    val watchState by RuleWatcherService.state.collectAsState()
    val accounts by container.db.cloudAccountsDao().observeAll().collectAsState(initial = emptyList())

    var editorRule by remember { mutableStateOf<WatchRule?>(null) }
    var editorIsNew by remember { mutableStateOf(false) }
    var previewRule by remember { mutableStateOf<WatchRule?>(null) }
    var deleteRule by remember { mutableStateOf<WatchRule?>(null) }
    var alertText by remember { mutableStateOf<String?>(null) }
    var templateForNew by remember { mutableStateOf<String?>(null) }

    val baseFolder = initialPath?.takeIf { it.isNotBlank() } ?: "/storage/emulated/0"

    Column(
        Modifier
            .fillMaxSize()
            .background(p.groupedBg),
    ) {
        IOSNavBar(
            title = stringResource(R.string.rules_title),
            onBack = onBack,
            actions = {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.rules_add),
                    tint = p.accent,
                    modifier = Modifier
                        .clickable {
                            editorIsNew = true
                            editorRule = RuleTemplates.newRule(
                                name = "", watchFolder = baseFolder,
                                conditions = com.novafiles.app.core.rules.RuleConditions(),
                                actions = emptyList(), now = System.currentTimeMillis(),
                            )
                        }
                        .padding(8.dp),
                )
            },
        )

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // ── المراقبة الحية ──
            IOSGroupHeader(text = stringResource(R.string.rules_watch_master))
            IOSGroupCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.rules_watch_master),
                            fontSize = 15.sp, color = p.label,
                        )
                        Text(
                            text = if (watchState.running)
                                stringResource(R.string.rules_watching_folders, watchState.watching)
                            else stringResource(R.string.rules_watch_off),
                            fontSize = 12.sp, color = p.secondaryLabel,
                        )
                    }
                    IOSSwitch(
                        checked = watchState.running,
                        onCheckedChange = { on ->
                            if (on) RuleWatcherService.start(context) else RuleWatcherService.stop(context)
                        },
                    )
                }
            }
            IOSGroupFooter(text = stringResource(R.string.rules_watch_hint))

            // ── القواعد ──
            IOSGroupHeader(text = stringResource(R.string.rules_section_rules))
            if (rules.isEmpty()) {
                IOSGroupCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.rules_empty_title),
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.label,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.rules_empty_text),
                            fontSize = 13.sp, color = p.secondaryLabel,
                        )
                    }
                }
            } else {
                IOSGroupCard {
                    rules.forEach { rule ->
                        RuleRow(
                            rule = rule,
                            onToggle = { on ->
                                scope.launch {
                                    container.rulesRepo.save(
                                        rules.map { if (it.id == rule.id) it.copy(enabled = on) else it },
                                    )
                                }
                            },
                            onPreview = { previewRule = rule },
                            onRun = {
                                scope.launch {
                                    val out = container.ruleEngine.runRule(
                                        rule, container.ruleEngine.scanFolder(java.io.File(rule.watchFolder)),
                                    )
                                    alertText = if (out.dryRun)
                                        context.getString(R.string.rules_toast_dry, out.matched)
                                    else
                                        context.getString(R.string.rules_toast_run, out.ok, out.failed)
                                }
                            },
                            onEdit = {
                                editorIsNew = false
                                editorRule = rule
                            },
                            onDelete = { deleteRule = rule },
                        )
                    }
                }
            }

            // ── القوالب ──
            IOSGroupHeader(text = stringResource(R.string.rules_templates_header))
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuleTemplates.ALL.forEach { tpl ->
                    Column(
                        Modifier
                            .width(150.dp)
                            .background(p.cardBg, RoundedCornerShape(14.dp))
                            .clickable {
                                val r = tpl.build(baseFolder, System.currentTimeMillis())
                                editorIsNew = true
                                editorRule = r
                                templateForNew = tpl.id
                            }
                            .padding(12.dp),
                    ) {
                        Text(
                            templateTitle(tpl.id),
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.label,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            templateDesc(tpl.id),
                            fontSize = 11.sp, color = p.secondaryLabel,
                        )
                    }
                }
            }
            IOSGroupFooter(text = stringResource(R.string.rules_templates_hint))

            // ── آخر التشغيلات مع التراجع ──
            if (runs.isNotEmpty()) {
                IOSGroupHeader(text = stringResource(R.string.rules_runs_header))
                IOSGroupCard {
                    runs.take(5).forEach { run ->
                        IOSListRow(
                            title = run.ruleName + if (run.dryRun) " · " + stringResource(R.string.rules_dry_run_badge) else "",
                            subtitle = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(run.startedAt)) +
                                " · " + stringResource(R.string.rules_run_summary, run.ok, run.failed),
                            trailing = {
                                if (run.undoable) {
                                    Row(
                                        Modifier
                                            .clickable {
                                                scope.launch {
                                                    val n = container.ruleEngine.undoRun(run.runId)
                                                    alertText = context.getString(R.string.rules_undo_done, n)
                                                }
                                            }
                                            .padding(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.Undo,
                                            contentDescription = stringResource(R.string.rules_undo),
                                            tint = p.accent,
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.rules_undo), fontSize = 13.sp, color = p.accent)
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // ── سجل التنفيذ ──
            IOSGroupHeader(text = stringResource(R.string.rules_log_header))
            IOSGroupCard {
                if (logEvents.isEmpty()) {
                    Text(
                        stringResource(R.string.rules_log_empty),
                        fontSize = 13.sp, color = p.secondaryLabel,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    logEvents.take(12).forEach { ev ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.foundation.layout.Box(
                                Modifier
                                    .width(8.dp)
                                    .height(8.dp)
                                    .background(
                                        when (ev.type) {
                                            RuleEventType.ACTION_OK, RuleEventType.UNDO -> androidx.compose.ui.graphics.Color(0xFF34C759)
                                            RuleEventType.ACTION_FAILED, RuleEventType.UNDO_FAILED -> androidx.compose.ui.graphics.Color(0xFFFF3B30)
                                            RuleEventType.DRY_RUN -> androidx.compose.ui.graphics.Color(0xFFFF9500)
                                            else -> androidx.compose.ui.graphics.Color(0xFF8E8E93)
                                        },
                                        RoundedCornerShape(4.dp),
                                    ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    eventLabel(ev.type),
                                    fontSize = 13.sp, color = p.label, fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    ev.ruleName + " · " + ev.detail,
                                    fontSize = 11.sp, color = p.secondaryLabel,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── المحرر ──
    editorRule?.let { draft ->
        RuleEditorSheet(
            draft = draft,
            isNew = editorIsNew,
            accounts = accounts,
            onDismiss = { editorRule = null },
            onSave = { saved ->
                scope.launch {
                    val list = container.rulesRepo.rules.first()
                    val merged = if (list.any { it.id == saved.id })
                        list.map { if (it.id == saved.id) saved else it }
                    else list + saved
                    container.rulesRepo.save(merged)
                    editorRule = null
                }
            },
        )
    }

    // ── معاينة التجربة الجافة ──
    previewRule?.let { rule ->
        ModalBottomSheet(onDismissRequest = { previewRule = null }) {
            PreviewContent(rule = rule) { previewRule = null }
        }
    }

    // ── تأكيد الحذف ──
    deleteRule?.let { rule ->
        IOSAlertDialog(
            title = stringResource(R.string.rules_delete_confirm_title),
            message = stringResource(R.string.rules_delete_confirm_text),
            confirmText = stringResource(R.string.rules_delete),
            destructive = true,
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                scope.launch {
                    container.rulesRepo.save(rules.filter { it.id != rule.id })
                    deleteRule = null
                }
            },
            onDismiss = { deleteRule = null },
        )
    }

    // ── تنبيه عام ──
    alertText?.let { msg ->
        IOSAlertDialog(
            title = stringResource(R.string.rules_title),
            message = msg,
            confirmText = stringResource(R.string.action_ok),
            onConfirm = { alertText = null },
        )
    }
}

/** صف قاعدة واحدة في القائمة */
@Composable
private fun RuleRow(
    rule: WatchRule,
    onToggle: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val p = LocalIOSPalette.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rule.name.ifBlank { stringResource(R.string.rules_unnamed) },
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = p.label,
                    )
                    if (rule.dryRun) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.rules_dry_run_badge),
                            fontSize = 10.sp, color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .background(p.accent, RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = "📁 " + rule.watchFolder.substringAfterLast('/') +
                        (if (rule.lastRunAt != null)
                            " · " + stringResource(R.string.rules_last_run, rule.runCount)
                        else ""),
                    fontSize = 12.sp, color = p.secondaryLabel,
                    maxLines = 1,
                )
            }
            IOSSwitch(checked = rule.enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniAction(stringResource(R.string.rules_preview), onClick = onPreview)
            MiniAction(stringResource(R.string.rules_run_now), onClick = onRun)
            MiniAction(stringResource(R.string.rules_edit), onClick = onEdit)
            MiniAction(stringResource(R.string.rules_delete), onClick = onDelete, destructive = true)
        }
    }
}

/** زر مصغّر لإجراءات الصف */
@Composable
private fun MiniAction(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    val p = LocalIOSPalette.current
    Text(
        label,
        fontSize = 12.sp,
        color = if (destructive) p.destructive else p.accent,
        modifier = Modifier
            .background(
                if (destructive) p.destructive.copy(alpha = 0.1f) else p.fill,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** محتوى ورقة المعاينة: خطة التجربة الجافة من المحرك مباشرة */
@Composable
private fun PreviewContent(rule: WatchRule, onClose: () -> Unit) {
    val p = LocalIOSPalette.current
    val container = LocalContext.current.novaContainer
    val plan = remember(rule) {
        container.ruleEngine.plan(rule, container.ruleEngine.scanFolder(java.io.File(rule.watchFolder)))
    }
    Column(Modifier.padding(bottom = 28.dp)) {
        Text(
            stringResource(R.string.rules_plan_header),
            fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.label,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Text(
            stringResource(R.string.rules_plan_files, plan.size),
            fontSize = 13.sp, color = p.secondaryLabel,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (plan.isEmpty()) {
            Text(
                stringResource(R.string.rules_plan_empty),
                fontSize = 13.sp, color = p.secondaryLabel,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            plan.take(20).forEach { item ->
                IOSListRow(
                    title = item.file.name,
                    subtitle = item.actions.map { actionSummary(it) }.joinToString(" → "),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.action_cancel),
            fontSize = 16.sp, color = p.accent,
            modifier = Modifier
                .clickable { onClose() }
                .padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

/** ملخص فعل بالعربية للمعاينة */
@Composable
private fun actionSummary(a: com.novafiles.app.core.rules.RuleAction): String = when (a) {
    is com.novafiles.app.core.rules.RuleAction.MoveTo -> "→ " + a.targetDir.substringAfterLast('/')
    is com.novafiles.app.core.rules.RuleAction.ArchiveZip ->
        stringResource(R.string.rules_action_zip) + " · " + a.namePattern + ".zip"
    is com.novafiles.app.core.rules.RuleAction.Checksum ->
        stringResource(R.string.rules_action_sum) + " · " + a.algorithm.name
    is com.novafiles.app.core.rules.RuleAction.TagFile ->
        stringResource(R.string.rules_action_tag) + " · " + a.tag
    is com.novafiles.app.core.rules.RuleAction.UploadCloud ->
        stringResource(R.string.rules_action_cloud) + " · " + a.remoteDir
}

/** تسميات أحداث السجل */
@Composable
private fun eventLabel(t: RuleEventType): String = when (t) {
    RuleEventType.RUN_START -> stringResource(R.string.rules_ev_run_start)
    RuleEventType.DRY_RUN -> stringResource(R.string.rules_ev_dry_run)
    RuleEventType.ACTION_OK -> stringResource(R.string.rules_ev_action_ok)
    RuleEventType.ACTION_FAILED -> stringResource(R.string.rules_ev_action_failed)
    RuleEventType.UNDO -> stringResource(R.string.rules_ev_undo)
    RuleEventType.UNDO_FAILED -> stringResource(R.string.rules_ev_undo_failed)
    RuleEventType.SKIPPED -> stringResource(R.string.rules_ev_skipped)
}

/** عناوين القوالب */
@Composable
private fun templateTitle(id: String): String = when (id) {
    "whatsapp_monthly" -> stringResource(R.string.rules_tpl_whatsapp_monthly_title)
    "apk_collect" -> stringResource(R.string.rules_tpl_apk_collect_title)
    "screenshots_sort" -> stringResource(R.string.rules_tpl_screenshots_sort_title)
    "docs_cloud" -> stringResource(R.string.rules_tpl_docs_cloud_title)
    "archives_checksum" -> stringResource(R.string.rules_tpl_archives_checksum_title)
    else -> id
}

/** أوصاف القوالب */
@Composable
private fun templateDesc(id: String): String = when (id) {
    "whatsapp_monthly" -> stringResource(R.string.rules_tpl_whatsapp_monthly_desc)
    "apk_collect" -> stringResource(R.string.rules_tpl_apk_collect_desc)
    "screenshots_sort" -> stringResource(R.string.rules_tpl_screenshots_sort_desc)
    "docs_cloud" -> stringResource(R.string.rules_tpl_docs_cloud_desc)
    "archives_checksum" -> stringResource(R.string.rules_tpl_archives_checksum_desc)
    else -> ""
}
