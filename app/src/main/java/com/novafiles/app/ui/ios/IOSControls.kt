package com.novafiles.app.ui.ios

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** حقل بحث iOS — خلفية رمادية دائرية بعدسة مكبّرة، مثل تطبيقات النظام */
@Composable
fun IOSSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val p = LocalIOSPalette.current
    val selectionColors = TextSelectionColors(handleColor = p.accent, backgroundColor = p.accent.copy(alpha = 0.3f))
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(p.fill, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = p.secondaryLabel,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(6.dp))
        CompositionTextSelection(selectionColors) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 17.sp, color = p.label),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                hint,
                                style = TextStyle(fontSize = 17.sp),
                                color = p.secondaryLabel,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
private fun CompositionTextSelection(
    selectionColors: TextSelectionColors,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalTextSelectionColors provides selectionColors,
        content = content,
    )
}

/** مجموعة قائمة iOS المجمّعة (Inset Grouped Section) — بطاقة بحواف دائرية ناعمة */
@Composable
fun IOSGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val p = LocalIOSPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(p.cardBg, RoundedCornerShape(16.dp)),
    ) {
        content()
    }
}

/** عنوان قسم أعلى المجموعة (نمط إعدادات iOS — رمادي صغير) */
@Composable
fun IOSGroupHeader(text: String) {
    val p = LocalIOSPalette.current
    Text(
        text,
        style = TextStyle(fontSize = 13.sp),
        color = p.secondaryLabel,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = 14.dp, bottom = 2.dp),
    )
}

/** تذييل قسم أسفل المجموعة (نمط إعدادات iOS) */
@Composable
fun IOSGroupFooter(text: String) {
    val p = LocalIOSPalette.current
    Text(
        text,
        style = TextStyle(fontSize = 13.sp),
        color = p.secondaryLabel,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 6.dp),
    )
}

/** صف قائمة iOS مع فاصل شعري داخلي وسهم تنقّل اختياري */
@Composable
fun IOSListRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = false,
    showSeparator: Boolean = true,
    destructive: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    val p = LocalIOSPalette.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = TextStyle(fontSize = 17.sp),
                    color = if (destructive) p.destructive else p.label,
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = TextStyle(fontSize = 13.sp),
                        color = p.secondaryLabel,
                        maxLines = 1,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (showChevron) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = p.tertiaryLabel,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (showSeparator) {
            Box(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(p.separator),
            )
        }
    }
}

/** مفتاح تبديل iOS الأصيل — أخضر، لا ينقلب في RTL مثل UISwitch */
@Composable
fun IOSSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val p = LocalIOSPalette.current
    val knobX by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(150),
        label = "iosSwitchKnob",
    )
    Box(
        modifier = Modifier
            .size(width = 51.dp, height = 31.dp)
            .background(
                if (checked) p.green else p.fill,
                RoundedCornerShape(15.5.dp),
            )
            .clickable { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .absoluteOffset(x = knobX)
                .size(27.dp)
                .background(Color.White, CircleShape),
        )
    }
}

/** علامة ✓ زرقاء لصفوف الاختيار (اللغة/السمة) */
@Composable
fun IOSCheckMark() {
    val p = LocalIOSPalette.current
    Icon(
        Icons.Filled.Check,
        contentDescription = null,
        tint = p.accent,
        modifier = Modifier.size(20.dp),
    )
}

/**
 * تنبيه iOS الأصيل (Cupertino Alert) — عرض 280dp، حواف 14dp،
 * أزرار مفصولة بخطوط شعرية، زر تأكيد شبه عريض.
 */
@Composable
fun IOSAlertDialog(
    title: String,
    message: String? = null,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    destructive: Boolean = false,
    content: @Composable (() -> Unit)? = null,
) {
    val p = LocalIOSPalette.current
    Dialog(onDismissRequest = { (onDismiss ?: onConfirm)() }) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (p == IOSDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7),
            modifier = Modifier.width(280.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(19.dp))
                Text(
                    title,
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = p.label,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (message != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        message,
                        style = TextStyle(fontSize = 13.sp),
                        color = p.label,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (content != null) {
                    Spacer(Modifier.height(12.dp))
                    content()
                    Spacer(Modifier.height(12.dp))
                } else {
                    Spacer(Modifier.height(16.dp))
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(p.separator),
                )
                Row(modifier = Modifier.height(44.dp)) {
                    if (dismissText != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onDismiss?.invoke() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                dismissText,
                                style = TextStyle(fontSize = 17.sp),
                                color = p.accent,
                            )
                        }
                        Box(
                            Modifier
                                .width(0.5.dp)
                                .height(44.dp)
                                .background(p.separator),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onConfirm() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            confirmText,
                            style = TextStyle(
                                fontSize = 17.sp,
                                fontWeight = if (destructive) FontWeight.SemiBold else FontWeight.SemiBold,
                            ),
                            color = if (destructive) p.destructive else p.accent,
                        )
                    }
                }
            }
        }
    }
}

/** زر iOS رئيسي (نمط زر النظام الأزرق الممتلئ) */
@Composable
fun IOSFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val p = LocalIOSPalette.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .background(
                when {
                    !enabled -> p.fill
                    destructive -> p.destructive
                    else -> p.accent
                },
                RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            color = if (enabled) Color.White else p.secondaryLabel,
        )
    }
}

/** حقل إدخال مصغّر بنمط iOS (للمرشحات) — خلفية رمادية بحد دائري */
@Composable
fun IOSMiniField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val p = LocalIOSPalette.current
    val selectionColors = TextSelectionColors(handleColor = p.accent, backgroundColor = p.accent.copy(alpha = 0.3f))
    CompositionTextSelection(selectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = p.label),
            cursorBrush = SolidColor(p.accent),
            modifier = modifier
                .height(36.dp)
                .background(p.fill, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            hint,
                            style = TextStyle(fontSize = 14.sp),
                            color = p.secondaryLabel,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** حقل إدخال iOS داخل التنبيهات — خلفية رمادية بحواف دائرية */
@Composable
fun IOSAlertTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalIOSPalette.current
    val selectionColors = TextSelectionColors(handleColor = p.accent, backgroundColor = p.accent.copy(alpha = 0.3f))
    CompositionTextSelection(selectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 17.sp, color = p.label),
            cursorBrush = SolidColor(p.accent),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(36.dp)
                .background(p.fill, RoundedCornerShape(9.dp))
                .padding(horizontal = 8.dp),
            decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { inner() } },
        )
    }
}
