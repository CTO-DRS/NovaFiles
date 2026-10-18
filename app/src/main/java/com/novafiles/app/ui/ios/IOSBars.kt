package com.novafiles.app.ui.ios

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R

/**
 * ══════════════════════════════════════════════════════════
 *  الشريط العلوي بنمط iOS الأصيل (Large-Title Navigation Bar)
 *  ─ عنوان كبير 34sp يتقلّص إلى شريط مضغوط 44dp عند التمرير
 *  ─ خط فصل شعري + خلفية شبه شفافة عند التقلّص (تمويه iOS)
 *  ─ سهم رجوع ‹ يتطابق تلقائياً مع الاتجاه RTL
 * ══════════════════════════════════════════════════════════
 */
@Composable
fun IOSNavBar(
    title: String,
    subtitle: String? = null,
    collapsed: Boolean = false,
    onBack: (() -> Unit)? = null,
    forceDark: Boolean? = null,   // true = نمط داكن (عارض الصور)
    transparent: Boolean = false, // خلفية شفافة تماماً فوق المحتوى
    backIcon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val p = when (forceDark) {
        true -> IOSDark
        false -> IOSLight
        null -> LocalIOSPalette.current
    }
    val progress by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        animationSpec = tween(160),
        label = "iosNavCollapse",
    )
    // خلفية الشريط: موسّع = خلفية الشاشة، مضغوط = خلفية الشريط الضبابية
    val bgColor = when {
        transparent -> Color(0x66000000)
        else -> lerp(p.groupedBg, p.barBg, progress)
    }
    val largeBlock by animateDpAsState(
        targetValue = if (collapsed) 0.dp else if (subtitle != null) 58.dp else 46.dp,
        animationSpec = tween(160),
        label = "iosNavLarge",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // ---- الصف المضغوط 44dp: سهم الرجوع + العنوان في المنتصف + الإجراءات ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
        ) {
            // العنوان المضغوط في المنتصف (يظهر عند التمرير فقط)
            TextiOS(
                text = title,
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                color = if (transparent) Color.White else p.label,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 72.dp)
                    .alpha(progress),
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = backIcon,
                            contentDescription = stringResource(R.string.action_cancel),
                            tint = if (transparent) Color.White else p.accent,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                actions()
            }
        }

        // ---- كتلة العنوان الكبير (تختفي عند التمرير) — الإجراءات تبقى في الصف العلوي مثل iOS ----
        if (largeBlock > 0.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(largeBlock)
                    .padding(horizontal = 16.dp)
                    .alpha(1f - progress),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    TextiOS(
                        text = title,
                        style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
                        color = if (transparent) Color.White else p.label,
                        maxLines = 1,
                    )
                    if (subtitle != null) {
                        TextiOS(
                            text = subtitle,
                            style = TextStyle(fontSize = 12.sp),
                            color = p.secondaryLabel,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // ---- الخط الشعري السفلي عند التقلّص ----
        if (!transparent) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .alpha(progress)
                    .background(p.separator),
            )
        }
    }
}

/** زر أيقونة في الشريط العلوي iOS — أزرق تلقائياً */
@Composable
fun IOSNavIconButton(
    icon: ImageVector,
    contentDescription: String?,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val p = LocalIOSPalette.current
    Box(
        modifier = Modifier
            .size(38.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: p.accent,
            modifier = Modifier.size(23.dp),
        )
    }
}

/** زر نصي في الشريط العلوي iOS — مثل «حفظ» الأزرق */
@Composable
fun IOSNavTextButton(
    text: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val p = LocalIOSPalette.current
    TextiOS(
        text = text,
        style = TextStyle(fontSize = 17.sp),
        color = if (destructive) p.destructive else p.accent,
        maxLines = 1,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * ══════════════════════════════════════════════════════════
 *  شريط التبويبات السفلي بنمط iOS الأصيل (Tab Bar)
 *  ─ ارتفاع 49dp + مسافة شريط التنقل للنظام
 *  ─ أيقونات 25dp مع تسميات 10sp — الأزرق للنشِط والرمادي للبقية
 *  ─ خط فصل شعري علوي + خلفية شبه شفافة (تمويه iOS)
 * ══════════════════════════════════════════════════════════
 */
data class IOSBarItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun IOSBottomBar(
    items: List<IOSBarItem>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val p = LocalIOSPalette.current
    Column(modifier = Modifier.fillMaxWidth().background(p.tabBarBg)) {
        // الخط الشعري العلوي
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(p.separator),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(49.dp),
        ) {
            items.forEach { item ->
                val active = item.key == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { onSelect(item.key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(5.dp))
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = if (active) p.accent else p.secondaryLabel,
                        modifier = Modifier.size(25.dp),
                    )
                    Spacer(Modifier.height(1.dp))
                    TextiOS(
                        text = item.label,
                        style = TextStyle(fontSize = 10.sp),
                        color = if (active) p.accent else p.secondaryLabel,
                        maxLines = 1,
                    )
                }
            }
        }
        // مسافة شريط التنقل السفلي للنظام
        Spacer(
            Modifier
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars),
        )
    }
}

/** نص iOS موحّد (لضمان التوريث الصحيح في RTL) */
@Composable
private fun TextiOS(
    text: String,
    style: TextStyle,
    color: Color,
    maxLines: Int,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Text(
        text = text,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
