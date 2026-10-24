@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Environment
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import com.novafiles.app.R
import com.novafiles.app.core.backup.BookmarkRef
import com.novafiles.app.core.backup.DecodeResult
import com.novafiles.app.core.backup.ExportPayload
import com.novafiles.app.core.backup.ImportPolicy
import com.novafiles.app.core.backup.SettingsExportEngine
import com.novafiles.app.core.gesture.GestureConfig
import com.novafiles.app.core.gesture.SwipeAction
import com.novafiles.app.core.l10n.LanguageCatalog
import com.novafiles.app.core.theme.IconPackEngine
import com.novafiles.app.core.theme.PaletteEngine
import com.novafiles.app.data.prefs.ThemeMode
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSCheckMark
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupFooter
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.rememberBottomBarClearance
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.util.Intents
import com.novafiles.app.util.ReportFiles
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * الإعدادات بنمط إعدادات iOS تماماً:
 * عنوان كبير، قوائم مجمّعة بحواف دائرية، فاصل شعري داخلي،
 * وعلامة ✓ زرقاء للخيار المحدد (اللغة والسمة).
 * v0.19 — قسم «الأمان»: بوابة الخزنة بعد نقلها من الشريط السفلي.
 * v0.39 — قسم «نسخ الإعدادات»: نقل بين الأجهزة بلا سحابة.
 * v0.40 — صف «دليل المستخدم»: عشرة فصول ببحث حي وتتبّع تقدّم.
 */
@Composable
fun SettingsScreen(
    onOpenVault: () -> Unit = {},
    onOpenSecurityGuide: () -> Unit = {},
    onOpenUserGuide: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val context = LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    var localeTag by remember { mutableStateOf("ar") }
    var cleared by remember { mutableStateOf(false) }
    var smartOn by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        container.settings.smartEnabled.collect { smartOn = it }
    }

    LaunchedEffect(Unit) {
        container.settings.themeMode.collect {
            themeMode = runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM)
        }
    }
    LaunchedEffect(Unit) {
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        localeTag = if (current.isBlank()) LanguageCatalog.BASE_CODE else current.substringBefore("-")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalIOSPalette.current.groupedBg)
            .verticalScroll(scrollState)
            .padding(bottom = rememberBottomBarClearance()),
    ) {
        IOSNavBar(
            title = stringResource(R.string.settings_title),
            collapsed = scrollState.value > 24,
        )

        // ----- اللغة (v0.37 — العشر لغات) -----
        IOSGroupHeader(stringResource(R.string.settings_language))
        IOSGroupCard {
            val languages = LanguageCatalog.ALL
            languages.forEachIndexed { index, locale ->
                IOSListRow(
                    title = locale.nativeName,
                    subtitle = locale.englishName,
                    onClick = {
                        localeTag = locale.code
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(locale.code))
                    },
                    showSeparator = index != languages.lastIndex,
                    trailing = { if (localeTag == locale.code) IOSCheckMark() },
                )
            }
        }

        // ----- السمة -----
        IOSGroupHeader(stringResource(R.string.settings_theme))
        IOSGroupCard {
            IOSListRow(
                title = stringResource(R.string.theme_system),
                onClick = {
                    themeMode = ThemeMode.SYSTEM
                    scope.launch { container.settings.setThemeMode(ThemeMode.SYSTEM) }
                },
                trailing = { if (themeMode == ThemeMode.SYSTEM) IOSCheckMark() },
            )
            IOSListRow(
                title = stringResource(R.string.theme_light),
                onClick = {
                    themeMode = ThemeMode.LIGHT
                    scope.launch { container.settings.setThemeMode(ThemeMode.LIGHT) }
                },
                trailing = { if (themeMode == ThemeMode.LIGHT) IOSCheckMark() },
            )
            IOSListRow(
                title = stringResource(R.string.theme_dark),
                onClick = {
                    themeMode = ThemeMode.DARK
                    scope.launch { container.settings.setThemeMode(ThemeMode.DARK) }
                },
                showSeparator = false,
                trailing = { if (themeMode == ThemeMode.DARK) IOSCheckMark() },
            )
        }

        // ----- اللون المميز (v0.17) -----
        IOSGroupHeader(stringResource(R.string.settings_accent))
        IOSGroupCard {
            val paletteSel by container.settings.paletteId
                .collectAsState(initial = "nova")
            PaletteSwatchGrid(
                selectedId = paletteSel,
                onSelect = { id -> scope.launch { container.settings.setPaletteId(id) } },
            )
        }

        // ----- الإيماءات (v0.17) -----
        IOSGroupHeader(stringResource(R.string.settings_gestures))
        IOSGroupCard {
            val gesturesRaw by container.settings.gestures.collectAsState(initial = "")
            val gestureConfig = remember(gesturesRaw) { GestureConfig.parse(gesturesRaw) }
            val doubleTapNav by container.settings.doubleTapNavigate.collectAsState(initial = false)

            IOSListRow(
                title = stringResource(R.string.gestures_enable_swipe),
                showSeparator = true,
                onClick = {
                    scope.launch {
                        container.settings.setGestures(
                            gestureConfig.copy(enabled = !gestureConfig.enabled).serialize(),
                        )
                    }
                },
                trailing = {
                    androidx.compose.material3.Switch(
                        checked = gestureConfig.enabled,
                        onCheckedChange = { on ->
                            scope.launch { container.settings.setGestures(gestureConfig.copy(enabled = on).serialize()) }
                        },
                    )
                },
            )
            IOSListRow(
                title = stringResource(R.string.gestures_swipe_right),
                subtitle = gestureConfig.rightAction.label(),
                showSeparator = true,
                onClick = {
                    scope.launch {
                        container.settings.setGestures(
                            gestureConfig.copy(rightAction = nextAction(gestureConfig.rightAction)).serialize(),
                        )
                    }
                },
            )
            IOSListRow(
                title = stringResource(R.string.gestures_swipe_left),
                subtitle = gestureConfig.leftAction.label(),
                showSeparator = true,
                onClick = {
                    scope.launch {
                        container.settings.setGestures(
                            gestureConfig.copy(leftAction = nextAction(gestureConfig.leftAction)).serialize(),
                        )
                    }
                },
            )
            IOSListRow(
                title = stringResource(R.string.gestures_double_tap),
                subtitle = stringResource(R.string.gestures_double_tap_hint),
                showSeparator = false,
                onClick = { scope.launch { container.settings.setDoubleTapNavigate(!doubleTapNav) } },
                trailing = {
                    androidx.compose.material3.Switch(
                        checked = doubleTapNav,
                        onCheckedChange = { on -> scope.launch { container.settings.setDoubleTapNavigate(on) } },
                    )
                },
            )
        }

        // ----- حزمة الأيقونات (v0.17) -----
        IOSGroupHeader(stringResource(R.string.settings_icon_pack))
        IOSGroupCard {
            var packOpen by remember { mutableStateOf(false) }
            IOSListRow(
                title = stringResource(R.string.icon_pack_import),
                subtitle = stringResource(R.string.icon_pack_hint),
                showSeparator = true,
                onClick = { packOpen = true },
            )
            IOSListRow(
                title = stringResource(R.string.icon_pack_clear),
                destructive = true,
                showSeparator = false,
                onClick = { scope.launch { container.settings.setIconPack("") } },
            )
            if (packOpen) {
                IconPackDialog(
                    onDismiss = { packOpen = false },
                    onSave = { manifest ->
                        scope.launch { container.settings.setIconPack(manifest) }
                        packOpen = false
                    },
                )
            }
        }

        // ----- الأمان: الخزنة + الدليل الأمني (v0.19 + v0.38) -----
        IOSGroupHeader(stringResource(R.string.settings_security))
        IOSGroupCard {
            IOSListRow(
                title = stringResource(R.string.nav_vault),
                subtitle = stringResource(R.string.vault_row_hint),
                showChevron = true,
                showSeparator = true,
                onClick = onOpenVault,
                leading = {
                    // بلاطة أيقونة بأسلوب إعدادات iOS
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .background(LocalIOSPalette.current.accent.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = LocalIOSPalette.current.accent,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                },
            )
            IOSListRow(
                title = stringResource(R.string.settings_user_guide),
                subtitle = stringResource(R.string.settings_user_guide_hint),
                showChevron = true,
                showSeparator = true,
                onClick = onOpenUserGuide,
            )
            IOSListRow(
                title = stringResource(R.string.settings_security_guide),
                subtitle = stringResource(R.string.settings_security_guide_hint),
                showChevron = true,
                showSeparator = false,
                onClick = onOpenSecurityGuide,
            )
        }

        // ----- الصيانة -----
        IOSGroupHeader(stringResource(R.string.nav_settings))
        IOSGroupCard {
            IOSListRow(
                title = stringResource(R.string.settings_clear_trash),
                destructive = true,
                showSeparator = false,
                onClick = {
                    scope.launch {
                        val trash = File(Environment.getExternalStorageDirectory(), ".NovaFilesTrash")
                        trash.deleteRecursively()
                        trash.mkdirs()
                        cleared = true
                    }
                },
            )
        }
        if (cleared) {
            IOSGroupFooter(stringResource(R.string.settings_trash_cleared))
        }

        // ----- الطبقة الذكية (v0.34) -----
        IOSGroupHeader(stringResource(R.string.settings_smart))
        IOSGroupCard {
            IOSListRow(
                title = stringResource(R.string.settings_smart),
                subtitle = stringResource(R.string.settings_smart_desc),
                showSeparator = false,
                trailing = {
                    com.novafiles.app.ui.ios.IOSSwitch(
                        checked = smartOn,
                        onCheckedChange = { on ->
                            smartOn = on
                            scope.launch { container.settings.setSmartEnabled(on) }
                        },
                    )
                },
            )
        }

        // ----- نسخ الإعدادات (v0.39 — نقل بين الأجهزة بلا سحابة) -----
        var backupStatus by remember { mutableStateOf<String?>(null) }
        var pendingImport by remember { mutableStateOf<DecodeResult.Ok?>(null) }

        val pickJson = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val text = runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    }.getOrNull()
                    when (val result = text?.let { SettingsExportEngine.decode(it) }) {
                        is DecodeResult.Ok -> pendingImport = result
                        is DecodeResult.Rejected ->
                            backupStatus = context.getString(R.string.settings_backup_import_bad, result.reason)
                        else ->
                            backupStatus = context.getString(
                                R.string.settings_backup_import_bad,
                                context.getString(R.string.settings_backup_bad_empty),
                            )
                    }
                }
            }
        }

        IOSGroupHeader(stringResource(R.string.settings_backup))
        IOSGroupCard {
            IOSListRow(
                title = stringResource(R.string.settings_backup_export),
                subtitle = stringResource(R.string.settings_backup_export_hint),
                showChevron = true,
                showSeparator = true,
                onClick = {
                    scope.launch {
                        val prefs = container.settings.exportPrefs()
                        val bms = container.bookmarks.bookmarks.first()
                            .map { BookmarkRef(it.name, it.path) }
                        val payload = ExportPayload(
                            schema = SettingsExportEngine.SUPPORTED_SCHEMA,
                            exportedAt = java.time.Instant.now().toString(),
                            prefs = prefs,
                            bookmarks = bms,
                        )
                        val f = ReportFiles.write("settings", "json", SettingsExportEngine.encode(payload))
                        if (f != null) {
                            backupStatus = context.getString(R.string.settings_backup_done, f.name)
                            Intents.share(context, f)
                        } else {
                            backupStatus = context.getString(
                                R.string.settings_backup_import_bad,
                                context.getString(R.string.settings_backup_bad_write),
                            )
                        }
                    }
                },
            )
            IOSListRow(
                title = stringResource(R.string.settings_backup_import),
                subtitle = stringResource(R.string.settings_backup_import_hint),
                showChevron = true,
                showSeparator = false,
                onClick = { pickJson.launch(arrayOf("application/json", "text/*")) },
            )
        }
        backupStatus?.let { IOSGroupFooter(it) }

        // ورقة سياسة الاستيراد — قرار صريح لا صمت
        pendingImport?.let { ok ->
            ImportPolicySheet(
                onDismiss = { pendingImport = null },
                onApply = { policy ->
                    pendingImport = null
                    scope.launch {
                        val current = container.settings.exportPrefs()
                        val (target, summary) = SettingsExportEngine.applyPolicy(current, ok.payload, policy)
                        val remove = current.keys - target.keys
                        container.settings.applyImportedPrefs(target, remove)
                        val added = when (policy) {
                            ImportPolicy.MERGE -> {
                                var n = 0
                                for (b in ok.payload.bookmarks) {
                                    if (container.bookmarks.add(b.path, b.name)) n++
                                }
                                n
                            }
                            ImportPolicy.REPLACE -> {
                                container.bookmarks.replaceAll(
                                    ok.payload.bookmarks.map { b ->
                                        com.novafiles.app.core.fs.Bookmark(b.path, b.name, System.currentTimeMillis())
                                    },
                                )
                            }
                        }
                        backupStatus = context.getString(
                            R.string.settings_backup_import_ok,
                            summary.appliedPrefs,
                            added,
                        )
                    }
                },
            )
        }

        // ----- عن التطبيق (v0.39 — صفوف حية بدل نص ثابت) -----
        IOSGroupHeader(stringResource(R.string.settings_about))
        IOSGroupCard {
            val versionName = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
            val versionCode = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).let {
                    @Suppress("DEPRECATION") it.versionCode
                }
            }.getOrNull() ?: 0
            IOSListRow(
                title = stringResource(R.string.about_version),
                subtitle = "$versionName ($versionCode)",
                showSeparator = true,
            )
            IOSListRow(
                title = stringResource(R.string.about_licenses),
                subtitle = stringResource(R.string.about_licenses_hint),
                showChevron = true,
                showSeparator = false,
                onClick = onOpenAbout,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * v0.39 — ورقة اختيار سياسة الاستيراد: دمج (الحالي يفوز) أو استبدال
 * (المستورد يفوز) — قرار صريح بلا قيمة افتراضية خفية.
 */
@Composable
private fun ImportPolicySheet(onDismiss: () -> Unit, onApply: (ImportPolicy) -> Unit) {
    var policy by remember { mutableStateOf(ImportPolicy.MERGE) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_choose_policy)) },
        text = {
            Column {
                PolicyRow(
                    label = stringResource(R.string.settings_backup_merge),
                    selected = policy == ImportPolicy.MERGE,
                    onClick = { policy = ImportPolicy.MERGE },
                )
                PolicyRow(
                    label = stringResource(R.string.settings_backup_replace),
                    selected = policy == ImportPolicy.REPLACE,
                    onClick = { policy = ImportPolicy.REPLACE },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(policy) }) {
                Text(stringResource(R.string.settings_backup_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun PolicyRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = TextStyle(fontSize = 14.sp))
    }
}

// ═══════════════ v0.17 — مساعدات الإعدادات الجديدة ═══════════════

/** شبكة عينات الألوان الـ12 — 4 في كل صف */
@Composable
private fun PaletteSwatchGrid(selectedId: String, onSelect: (String) -> Unit) {
    val palette = LocalIOSPalette.current
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PaletteEngine.PRESETS.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { preset ->
                    val fam = PaletteEngine.family(preset, dark = false)
                    val selected = preset.id == selectedId
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Color(fam.accent))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) palette.label
                                else palette.separator,
                                shape = CircleShape,
                            )
                            .clickable { onSelect(preset.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Text(
                                "✓",
                                color = androidx.compose.ui.graphics.Color(
                                    fam.onAccent,
                                ),
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** التالي في دورة إجراءات السحب (بترتيب ثابت حتمي) */
private fun nextAction(current: SwipeAction): SwipeAction {
    val order = listOf(
        SwipeAction.NONE, SwipeAction.FAVORITE, SwipeAction.DETAILS,
        SwipeAction.SHARE, SwipeAction.COPY, SwipeAction.MOVE,
        SwipeAction.COMPRESS, SwipeAction.RENAME, SwipeAction.TRASH, SwipeAction.DELETE,
    )
    val idx = order.indexOf(current)
    return order[(idx + 1) % order.size]
}

@Composable
private fun SwipeAction.label(): String = when (this) {
    SwipeAction.NONE -> stringResource(R.string.gesture_action_none)
    SwipeAction.FAVORITE -> stringResource(R.string.swipe_favorite)
    SwipeAction.DETAILS -> stringResource(R.string.swipe_details)
    SwipeAction.SHARE -> stringResource(R.string.swipe_share)
    SwipeAction.COPY -> stringResource(R.string.action_copy)
    SwipeAction.MOVE -> stringResource(R.string.action_move)
    SwipeAction.COMPRESS -> stringResource(R.string.swipe_compress)
    SwipeAction.RENAME -> stringResource(R.string.action_rename)
    SwipeAction.TRASH, SwipeAction.DELETE -> stringResource(R.string.action_delete)
}

/** حوار استيراد حزمة الأيقونات — نص يدوي (ملف .novapack) */
@Composable
private fun IconPackDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val valid = remember(text) { IconPackEngine(text).isValid }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.icon_pack_import)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.icon_pack_dialog_hint),
                    style = TextStyle(fontSize = 12.sp),
                    color = LocalIOSPalette.current.secondaryLabel,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    textStyle = TextStyle(fontSize = 12.sp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(text) }) {
                Text(stringResource(R.string.dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
