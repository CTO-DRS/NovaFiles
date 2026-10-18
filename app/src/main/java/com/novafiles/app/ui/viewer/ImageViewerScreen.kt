package com.novafiles.app.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.novafiles.app.R
import com.novafiles.app.ui.ios.IOSNavIconButton
import com.novafiles.app.ui.ios.IOSNavBar
import java.io.File

/** عارض صور مع تكبير بالإصبعين وتحريك */
@Composable
fun ImageViewerScreen(path: String?) {
    val file = path?.let { File(it) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun resetZoom() {
        scale = 1f
        offset = Offset.Zero
    }

    BackHandler { resetZoom() }
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (file != null && file.exists()) {
            AsyncImage(
                model = file,
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        } else {
            Text(
                stringResource(R.string.editor_read_fail),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // شريط iOS العلوي الشفاف الداكن (مثل عارض الصور في iOS)
        IOSNavBar(
            title = file?.name ?: "NovaFiles",
            forceDark = true,
            transparent = true,
            collapsed = true,
            onBack = { backDispatcher?.onBackPressed() },
            actions = {
                IOSNavIconButton(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color.White,
                ) { resetZoom() }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
