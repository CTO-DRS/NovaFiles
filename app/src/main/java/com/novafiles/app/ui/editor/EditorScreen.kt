@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import com.novafiles.app.R
import com.novafiles.app.ui.ios.IOSNavTextButton
import com.novafiles.app.ui.ios.IOSNavBar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** محرر نصوص مدمج (حد 1MB) — ملفات txt/md/الكود */
@Composable
fun EditorScreen(path: String?) {
    val snackbar = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var content by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }

    val savedMsg = stringResource(R.string.editor_saved)
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    LaunchedEffect(path) {
        val file = path?.let { File(it) }
        if (file == null || !file.exists()) {
            error = "no file"
            loaded = true
            return@LaunchedEffect
        }
        if (file.length() > 1024 * 1024) {
            error = "too_large"
        } else {
            content = withContext(Dispatchers.IO) {
                runCatching { file.readText() }.getOrDefault("")
            }
        }
        loaded = true
    }

    Scaffold(
        containerColor = com.novafiles.app.ui.ios.LocalIOSPalette.current.groupedBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            IOSNavBar(
                title = File(path ?: "").name.ifEmpty { stringResource(R.string.editor_title) },
                collapsed = true,
                onBack = { backDispatcher?.onBackPressed() },
                actions = {
                    if (error == null && path != null) {
                        IOSNavTextButton(text = stringResource(R.string.action_save)) {
                            val file = File(path)
                            scope.launch(Dispatchers.IO) {
                                runCatching { file.writeText(content) }
                                    .onSuccess { error = null }
                                withContext(Dispatchers.Main) {
                                    snackbar.showSnackbar(savedMsg)
                                    dirty = false
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !loaded -> Box(Modifier.fillMaxSize()) {}
                error == "too_large" -> CenterMsg(stringResource(R.string.editor_too_large))
                error == "no file" -> CenterMsg(stringResource(R.string.editor_read_fail))
                else -> {
                    androidx.compose.material3.OutlinedTextField(
                        value = content,
                        onValueChange = { content = it; dirty = true },
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterMsg(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
