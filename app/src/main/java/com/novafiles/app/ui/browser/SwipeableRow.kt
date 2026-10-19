package com.novafiles.app.ui.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.novafiles.app.R
import com.novafiles.app.core.gesture.GestureConfig
import com.novafiles.app.core.gesture.SwipeAction
import com.novafiles.app.core.gesture.SwipeActionResolver
import com.novafiles.app.core.gesture.SwipeDecision
import com.novafiles.app.core.gesture.SwipeDirection
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.launch

/**
 * v0.17 — صف قابل للسحب أفقياً بإجراءات قابلة للضبط.
 *
 * تغليف شفاف: المحتوى الأصلي يبقى كما هو، والخلفية تكشف أيقونة الإجراء
 * أثناء السحب. عند الإفلات فوق العتبة يُطلق الإجراء المقابل للاتجاه.
 * القرار نفسه يأتي من [SwipeActionResolver] الخالص (مُختبر على JVM).
 *
 * v0.18 — الحاوية مقصوصة بزوايا البطاقة المستديرة (18dp) حتى تظهر
 * خلفية الإجراء بنفس شكل البطاقة، والخلفية الافتراضية شفافة لأن
 * البطاقة نفسها ترسم خلفيتها.
 */
private val SwipeRowShape = RoundedCornerShape(18.dp)

@Composable
internal fun SwipeableRow(
    fileKey: String,
    config: GestureConfig,
    onAction: (SwipeAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = LocalIOSPalette.current
    val resolver = remember(config) { SwipeActionResolver(config) }
    val scope = rememberCoroutineScope()
    val offsetX = remember(fileKey) { Animatable(0f) }
    var rowSize by remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowSize = it }
            .clip(SwipeRowShape)
            .background(
                when {
                    offsetX.value > SwipeActionResolver.MIN_DISTANCE_DP ->
                        palette.accent.copy(alpha = 0.16f)
                    offsetX.value < -SwipeActionResolver.MIN_DISTANCE_DP ->
                        palette.orange.copy(alpha = 0.16f)
                    else -> Color.Transparent
                },
            ),
    ) {
        // خلفية الإجراء: أيقونة + تسمية بحسب الاتجاه
        val rightAction = config.actionFor(SwipeDirection.RIGHT)
        val leftAction = config.actionFor(SwipeDirection.LEFT)
        if (offsetX.value > 12f && rightAction != SwipeAction.NONE) {
            SwipeHint(
                icon = rightAction.icon(),
                label = rightAction.label(),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 20.dp),
            )
        } else if (offsetX.value < -12f && leftAction != SwipeAction.NONE) {
            SwipeHint(
                icon = leftAction.icon(),
                label = leftAction.label(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX.value }
                .pointerInput(fileKey, config) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            scope.launch { offsetX.snapTo(offsetX.value + amount) }
                        },
                        onDragEnd = {
                            val decision =
                                resolver.resolve(0f, offsetX.value, rowSize.width.toFloat())
                            scope.launch {
                                offsetX.animateTo(0f, tween(180))
                            }
                            if (decision is SwipeDecision.Committed) onAction(decision.action)
                        },
                        onDragCancel = {
                            scope.launch { offsetX.animateTo(0f, tween(180)) }
                        },
                    )
                },
        ) {
            content()
        }
    }
}

@Composable
private fun SwipeHint(icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    val palette = LocalIOSPalette.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = palette.accent)
        Text(label, color = palette.accent, style = MaterialTheme.typography.labelSmall)
    }
}

private fun SwipeAction.icon(): ImageVector = when (this) {
    SwipeAction.FAVORITE -> Icons.Filled.Star
    SwipeAction.DETAILS -> Icons.Filled.Info
    SwipeAction.SHARE -> Icons.Filled.Share
    SwipeAction.COPY, SwipeAction.MOVE -> Icons.AutoMirrored.Filled.DriveFileMove
    SwipeAction.COMPRESS -> Icons.Filled.FolderZip
    SwipeAction.RENAME -> Icons.Filled.Edit
    SwipeAction.TRASH, SwipeAction.DELETE -> Icons.Filled.Delete
    SwipeAction.NONE -> Icons.Filled.Info
}

@Composable
private fun SwipeAction.label(): String = when (this) {
    SwipeAction.FAVORITE -> stringResource(R.string.swipe_favorite)
    SwipeAction.DETAILS -> stringResource(R.string.swipe_details)
    SwipeAction.SHARE -> stringResource(R.string.swipe_share)
    SwipeAction.COPY -> stringResource(R.string.action_copy)
    SwipeAction.MOVE -> stringResource(R.string.action_move)
    SwipeAction.COMPRESS -> stringResource(R.string.swipe_compress)
    SwipeAction.RENAME -> stringResource(R.string.action_rename)
    SwipeAction.TRASH, SwipeAction.DELETE -> stringResource(R.string.action_delete)
    SwipeAction.NONE -> ""
}
