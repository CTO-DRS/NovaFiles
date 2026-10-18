package com.novafiles.app.ui.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** عنصر داخل ورقة الإجراءات */
data class IOSAction(
    val label: String,
    val destructive: Boolean = false,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * ورقة إجراءات iOS الأصيلة (UIAlertController .actionSheet) —
 * بطاقة سفلية بحواف 14dp، صفوف 56dp بنص أزرق مركزي، خيارات مُحددة بعلامة ✓،
 * زر الإلغاء في بطاقة منفصلة أسفلها بخط شبه عريض.
 */
@Composable
fun IOSActionSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    cancelText: String,
    title: String? = null,
    message: String? = null,
    actions: List<IOSAction>,
) {
    if (!visible) return
    val p = LocalIOSPalette.current
    val sheetBg = if (p == IOSDark) Color(0xFF2C2C2E).copy(alpha = 0.94f) else Color(0xFFF2F2F7).copy(alpha = 0.96f)
    val noRipple = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onDismiss, indication = null, interactionSource = noRipple),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 10.dp)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 10.dp),
            ) {
                // بطاقة الإجراءات
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(sheetBg, RoundedCornerShape(14.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (title != null || message != null) {
                        Text(
                            title ?: "",
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            color = p.secondaryLabel,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                        )
                        if (message != null) {
                            Text(
                                message,
                                style = TextStyle(fontSize = 13.sp),
                                color = p.secondaryLabel,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    actions.forEachIndexed { i, action ->
                        if (i > 0 || title != null || message != null) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(p.separator),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clickable { onDismiss(); action.onClick() }
                                .padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (action.selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = if (action.destructive) p.destructive else p.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                action.label,
                                style = TextStyle(fontSize = 20.sp),
                                color = if (action.destructive) p.destructive else p.accent,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f),
                            )
                            if (action.selected) {
                                Spacer(Modifier.size(18.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // بطاقة الإلغاء المنفصلة
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(sheetBg, RoundedCornerShape(14.dp))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        cancelText,
                        style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                        color = p.accent,
                    )
                }
            }
        }
    }
}
