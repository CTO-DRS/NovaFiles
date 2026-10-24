@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.about.LicenseCatalog
import com.novafiles.app.core.about.LibLicense
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupFooter
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSListRow
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.ui.ios.rememberBottomBarClearance

/**
 * v0.39 — شاشة «حول التطبيق» بنمط إعدادات iOS:
 * إصدار التطبيق، المشروع على GitHub، وكتالوج تراخيص المصدر المفتوح
 * (متطلب نشر المتجرين + F-Droid) — كل المدخلات من LicenseCatalog
 * الحتمي، وبوابة اختباراته تضمن عدم انزياح الإصدارات عن toml الحقيقي.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenSecurityGuide: () -> Unit) {
    val context = LocalContext.current
    val palette = LocalIOSPalette.current
    val scrollState = rememberScrollState()
    var selected by remember { mutableStateOf<LibLicense?>(null) }

    val versionName = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()
    val versionCode = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).let {
            @Suppress("DEPRECATION") it.versionCode
        }
    }.getOrNull() ?: 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.groupedBg)
            .verticalScroll(scrollState)
            .padding(bottom = rememberBottomBarClearance()),
    ) {
        IOSNavBar(
            title = stringResource(R.string.about_title),
            collapsed = scrollState.value > 24,
        )

        // ----- الإصدار والمصدر -----
        IOSGroupHeader(stringResource(R.string.about_title))
        IOSGroupCard {
            IOSListRow(
                title = stringResource(R.string.about_version),
                subtitle = "$versionName ($versionCode)",
                showSeparator = true,
            )
            IOSListRow(
                title = stringResource(R.string.about_github),
                subtitle = stringResource(R.string.about_github_hint),
                showChevron = true,
                showSeparator = true,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/CTO-DRS/NovaFiles"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
            IOSListRow(
                title = stringResource(R.string.about_view_guide),
                subtitle = stringResource(R.string.settings_security_guide_hint),
                showChevron = true,
                showSeparator = false,
                onClick = onOpenSecurityGuide,
            )
        }
        IOSGroupFooter(stringResource(R.string.about_open_source))

        // ----- تراخيص المصدر المفتوح -----
        IOSGroupHeader(stringResource(R.string.about_licenses))
        IOSGroupCard {
            val libs = LicenseCatalog.sortedByName()
            libs.forEachIndexed { i, lib ->
                IOSListRow(
                    title = lib.name,
                    subtitle = "${lib.version} · ${lib.license}",
                    onClick = { selected = lib },
                    showSeparator = i != libs.lastIndex,
                )
            }
        }
        IOSGroupFooter(stringResource(R.string.about_licenses_hint))
        Spacer(Modifier.height(32.dp))
    }

    // ورقة تفاصيل المكتبة
    selected?.let { lib ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(lib.name) },
            text = {
                Column {
                    Text(
                        lib.note,
                        style = TextStyle(fontSize = 14.sp),
                        color = palette.label,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${stringResource(R.string.about_license)}: ${lib.license} · ${lib.version}",
                        style = TextStyle(fontSize = 12.sp),
                        color = palette.secondaryLabel,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        lib.source,
                        style = TextStyle(fontSize = 11.sp),
                        color = palette.secondaryLabel,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selected = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}
