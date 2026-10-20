package com.novafiles.app.ui.apps

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.novafiles.app.R
import com.novafiles.app.core.apps.AppEngine
import com.novafiles.app.core.apps.AppEntry
import com.novafiles.app.core.apps.AppFilter
import com.novafiles.app.core.apps.AppSort
import com.novafiles.app.ui.ios.IOSAction
import com.novafiles.app.ui.ios.IOSActionSheet
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.util.Intents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * شاشة «مدير التطبيقات» — v0.22
 * التطبيقات المثبتة (مستخدم/نظام) بفرز وتصفية، ونسخ احتياطي APK،
 * ومشاركة، وإزالة تثبيت، ومسح ملفات APK المتراكمة في التخزين لتنظيفها.
 * المنطق الحتمي كله في AppEngine الخالص — هذه الشاشة طبقة عرض رقيقة فقط.
 */

/** هدف الإجراءات: تطبيق مثبت أو ملف APK في التخزين */
private sealed interface AppTarget {
    data class Installed(val app: AppEntry) : AppTarget
    data class ApkFile(val file: File) : AppTarget
}

@Composable
fun AppsScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalIOSPalette.current

    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var apkFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var filter by remember { mutableStateOf(AppFilter.ALL) }
    var target by remember { mutableStateOf<AppTarget?>(null) }

    // ── فحص التطبيقات المثبتة عبر PackageManager (طبقة Android الرقيقة) ──
    LaunchedEffect(Unit) {
        loading = true
        apps = withContext(Dispatchers.IO) { scanInstalledApps(context) }
        loading = false
    }
    // ── ملفات APK المتراكمة: تُمسح عند اختيار تصفية «ملفات APK» ──
    LaunchedEffect(filter) {
        if (filter == AppFilter.APK_FILES) {
            apkFiles = withContext(Dispatchers.IO) {
                val sd = Environment.getExternalStorageDirectory()
                AppEngine.findApkFiles(
                    roots = listOf(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        File(sd, "Bluetooth"),
                    ),
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.groupedBg),
    ) {
        IOSNavBar(
            title = stringResource(R.string.apps_title),
            onBack = onBack,
        )

        // ── شرائح التصفية بنمط iOS ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterPill(stringResource(R.string.apps_filter_all), filter == AppFilter.ALL) { filter = AppFilter.ALL }
            FilterPill(stringResource(R.string.apps_filter_user), filter == AppFilter.USER) { filter = AppFilter.USER }
            FilterPill(stringResource(R.string.apps_filter_system), filter == AppFilter.SYSTEM) { filter = AppFilter.SYSTEM }
            FilterPill(stringResource(R.string.apps_filter_apk), filter == AppFilter.APK_FILES) { filter = AppFilter.APK_FILES }
        }

        when {
            loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            filter == AppFilter.APK_FILES && apkFiles.isEmpty() ->
                EmptyState(
                    icon = Icons.Filled.PlayArrow,
                    title = stringResource(R.string.apps_apk_empty),
                    hint = stringResource(R.string.apps_apk_empty_hint),
                )
            filter != AppFilter.APK_FILES && AppEngine.filter(apps, filter).isEmpty() ->
                EmptyState(
                    icon = Icons.Filled.Android,
                    title = stringResource(R.string.apps_empty),
                    hint = stringResource(R.string.apps_empty_hint),
                )
            else -> {
                val summaryText = if (filter == AppFilter.APK_FILES) {
                    stringResource(R.string.apps_summary, apkFiles.size, Formatter.formatShortFileSize(context, apkFiles.sumOf { it.length() }))
                } else {
                    val visible = AppEngine.filter(apps, filter)
                    stringResource(R.string.apps_summary, visible.size, Formatter.formatShortFileSize(context, AppEngine.userTotalBytes(visible)))
                }
                Text(
                    summaryText,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (filter == AppFilter.APK_FILES) {
                        items(apkFiles, key = { it.absolutePath }) { f ->
                            ApkFileRow(file = f, onTap = { target = AppTarget.ApkFile(f) })
                        }
                    } else {
                        val visible = AppEngine.present(apps, filter, AppSort.NAME)
                        items(visible, key = { it.packageName }) { app ->
                            InstalledAppRow(app = app, onTap = { target = AppTarget.Installed(app) })
                        }
                    }
                }
            }
        }
    }

    // ── ورقة إجراءات iOS حسب الهدف ──
    val t = target
    IOSActionSheet(
        visible = t != null,
        onDismiss = { target = null },
        cancelText = stringResource(R.string.action_cancel),
        title = when (t) {
            is AppTarget.Installed -> t.app.label
            is AppTarget.ApkFile -> t.file.name
            null -> null
        },
        actions = when (t) {
            is AppTarget.Installed -> installedActions(context, scope, t.app) { target = null }
            is AppTarget.ApkFile -> apkFileActions(context, t.file) { target = null }
            null -> emptyList()
        },
    )
}

/** فحص التطبيقات المثبتة — واجهة PackageManager فقط، والناتج AppEntry خالص */
private fun scanInstalledApps(context: android.content.Context): List<AppEntry> {
    val pm = context.packageManager
    val infos = if (Build.VERSION.SDK_INT >= 33) {
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getInstalledPackages(PackageManager.GET_META_DATA)
    }
    return infos.mapNotNull { pi ->
        val ai = pi.applicationInfo ?: return@mapNotNull null
        val apk = ai.sourceDir ?: return@mapNotNull null
        AppEntry(
            packageName = pi.packageName,
            label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pi.packageName),
            versionName = pi.versionName ?: "",
            versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong(),
            sizeBytes = runCatching { File(apk).length() }.getOrDefault(0L),
            installTimeMs = pi.firstInstallTime,
            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            apkPath = apk,
        )
    }
}

/** إجراءات تطبيق مثبت */
private fun installedActions(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    app: AppEntry,
    onDone: () -> Unit,
): List<IOSAction> {
    val actions = mutableListOf<IOSAction>()
    val pm = context.packageManager
    val launchable = pm.getLaunchIntentForPackage(app.packageName) != null
    if (launchable) {
        actions += IOSAction(context.getString(R.string.apps_action_open)) {
            onDone()
            runCatching { context.startActivity(pm.getLaunchIntentForPackage(app.packageName)) }
        }
    }
    actions += IOSAction(context.getString(R.string.apps_action_backup)) {
        onDone()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = AppEngine.backupDir(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    )
                    dir.mkdirs()
                    val name = AppEngine.backupFileName(app.label, app.versionName, app.versionCode)
                    val targetFile = AppEngine.backupTarget(dir, name)
                    File(app.apkPath).copyTo(targetFile, overwrite = false)
                }.isSuccess
            }
            Toast.makeText(
                context,
                context.getString(if (ok) R.string.apps_backup_done else R.string.apps_backup_fail),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    actions += IOSAction(context.getString(R.string.apps_action_share)) {
        onDone()
        runCatching { Intents.share(context, File(app.apkPath)) }
    }
    actions += IOSAction(context.getString(R.string.apps_action_info)) {
        onDone()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")),
            )
        }
    }
    if (!app.isSystem) {
        actions += IOSAction(
            label = context.getString(R.string.apps_action_uninstall),
            destructive = true,
        ) {
            onDone()
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")),
                )
            }
        }
    }
    return actions
}

/** إجراءات ملف APK في التخزين: تثبيت / مشاركة / حذف */
private fun apkFileActions(
    context: android.content.Context,
    file: File,
    onDone: () -> Unit,
): List<IOSAction> = listOf(
    IOSAction(context.getString(R.string.apps_action_install)) {
        onDone()
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Intents.contentUri(context, file), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    },
    IOSAction(context.getString(R.string.apps_action_share)) {
        onDone()
        runCatching { Intents.share(context, file) }
    },
    IOSAction(
        label = context.getString(R.string.apps_action_delete),
        destructive = true,
    ) {
        onDone()
        file.delete()
    },
)

/** شريحة تصفية كبسولية بانتقال لوني ناعم */
@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalIOSPalette.current
    val bg by animateColorAsState(
        if (selected) p.accent.copy(alpha = 0.16f) else p.fill,
        tween(180),
        label = "appFilterBg",
    )
    val fg by animateColorAsState(
        if (selected) p.accent else p.secondaryLabel,
        tween(180),
        label = "appFilterFg",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            maxLines = 1,
        )
    }
}

/** صف تطبيق مثبت: أيقونة حقيقية + الاسم + النسخة والحجم والتاريخ */
@Composable
private fun InstalledAppRow(app: AppEntry, onTap: () -> Unit) {
    val palette = LocalIOSPalette.current
    val context = LocalContext.current
    val icon = remember(app.packageName) { loadAppIcon(context, app.packageName) }
    val date = remember(app.installTimeMs) {
        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(app.installTimeMs))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.cardBg)
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconView(icon = icon, size = 44.dp)
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                app.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "v${app.versionName.ifBlank { "—" }} · ${Formatter.formatShortFileSize(context, app.sizeBytes)} · $date",
                fontSize = 12.sp,
                color = palette.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** صف ملف APK في التخزين */
@Composable
private fun ApkFileRow(file: File, onTap: () -> Unit) {
    val palette = LocalIOSPalette.current
    val context = LocalContext.current
    val date = remember(file.lastModified()) {
        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(file.lastModified()))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.cardBg)
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconView(icon = null, size = 44.dp)
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.nameWithoutExtension,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${Formatter.formatShortFileSize(context, file.length())} · $date",
                fontSize = 12.sp,
                color = palette.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** أيقونة التطبيق: حقيقية إن أمكن وإلا مربع Android رمزي */
@Composable
private fun AppIconView(icon: ImageBitmap?, size: Dp = 44.dp) {
    val palette = LocalIOSPalette.current
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Android,
                contentDescription = null,
                tint = palette.secondaryLabel,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/** تحميل أيقونة تطبيق مثبت (خارج التركيب، مع حماية كاملة) */
private fun loadAppIcon(context: android.content.Context, packageName: String): ImageBitmap? =
    runCatching {
        context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap()
    }.getOrNull()

/** حالة فارغة موحدة بأسلوب الشاشات الأخرى */
@Composable
private fun EmptyState(icon: ImageVector, title: String, hint: String) {
    val palette = LocalIOSPalette.current
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                icon,
                contentDescription = null,
                tint = palette.secondaryLabel.copy(alpha = 0.45f),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = palette.secondaryLabel.copy(alpha = 0.8f),
            )
        }
    }
}
