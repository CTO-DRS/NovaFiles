@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.settings

import android.os.Environment
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.novafiles.app.R
import com.novafiles.app.data.prefs.ThemeMode
import com.novafiles.app.novaContainer
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File

/** الإعدادات: اللغة (عربي/إنجليزي)، السمة (نظام/فاتح/داكن)، تفريغ السلة، عن التطبيق */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    var localeTag by remember { mutableStateOf("ar") }
    var cleared by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        container.settings.themeMode.collect {
            themeMode = runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM)
        }
    }
    LaunchedEffect(Unit) {
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        localeTag = if (current.isBlank()) "ar" else current.substringBefore("-")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(title = { Text(stringResource(R.string.settings_title)) })

        // ----- اللغة -----
        SectionCard(title = stringResource(R.string.settings_language)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = localeTag == "ar",
                    onClick = {
                        localeTag = "ar"
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar"))
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("العربية") }
                SegmentedButton(
                    selected = localeTag == "en",
                    onClick = {
                        localeTag = "en"
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("English") }
            }
        }

        // ----- السمة -----
        SectionCard(title = stringResource(R.string.settings_theme)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(
                    ThemeMode.SYSTEM to R.string.theme_system,
                    ThemeMode.LIGHT to R.string.theme_light,
                    ThemeMode.DARK to R.string.theme_dark,
                )
                modes.forEachIndexed { index, (mode, labelRes) ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = {
                            themeMode = mode
                            scope.launch { container.settings.setThemeMode(mode) }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    ) { Text(stringResource(labelRes)) }
                }
            }
        }

        // ----- سلة المحذوفات -----
        SectionCard(title = stringResource(R.string.nav_settings)) {
            TextButton(onClick = {
                scope.launch {
                    val trash = File(Environment.getExternalStorageDirectory(), ".NovaFilesTrash")
                    trash.deleteRecursively()
                    trash.mkdirs()
                    cleared = true
                }
            }) { Text(stringResource(R.string.settings_clear_trash)) }
            if (cleared) {
                Text(
                    stringResource(R.string.settings_trash_cleared),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ----- عن التطبيق -----
        SectionCard(title = stringResource(R.string.settings_about)) {
            Text(
                stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(32.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(12.dp))
            content()
        }
    }
}
