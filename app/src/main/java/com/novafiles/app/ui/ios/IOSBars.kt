package com.novafiles.app.ui.ios

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R

/**
 * ══════════════════════════════════════════════════════════
 *  الشريط العلوي بنمط iOS (Large-Title Navigation Bar) — v0.18
 *  ─ عنوان كبير يتقلّص إلى شريط مضغوط عند التمرير
 *  ─ v0.18: الشريط المضغوط يكتسب زوايا سفلية دائرية + ظلاً ناعماً
 *    (مظهر «الورقة العائمة»)، والمسار يُعرض في شارة مستديرة
 *  ─ سهم رجوع ‹ يتطابق تلقائياً مع الاتجاه RTL
 * ══════════════════════════════════════════════════════════
 */

/** زوايا أسفل الشريط المضغوط — مظهر الورقة العائمة */
private val NavCollapsedShape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)

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
        animationSpec = tween(180),
        label = "iosNavCollapse",
    )
    // خلفية الشريط: موسّع = خلفية الشاشة، مضغوط = خلفية الشريط الضبابية
    val bgColor = when {
        transparent -> Color(0x66000000)
        else -> lerp(p.groupedBg, p.barBg, progress)
    }
    val largeBlock by animateDpAsState(
        targetValue = if (collapsed) 0.dp else if (subtitle != null) 64.dp else 48.dp,
        animationSpec = tween(180),
        label = "iosNavLarge",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            // v0.18 — ظل ناعم يظهر مع التقلّص فقط (الورقة العائمة)
            .then(
                if (transparent) Modifier
                else Modifier.shadow(
                    elevation = 10.dp * progress,
                    shape = NavCollapsedShape,
                    clip = false,
                    ambientColor = Color.Black,
                    spotColor = Color.Black,
                ),
            )
            .background(
                bgColor,
                shape = if (transparent) RoundedCornerShape(0.dp) else NavCollapsedShape,
            )
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
                    .padding(horizontal = 84.dp)
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
                            .size(40.dp)
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
                        style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
                        color = if (transparent) Color.White else p.label,
                        maxLines = 1,
                    )
                    if (!subtitle.isNullOrEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        // v0.18 — شارة المسار المستديرة بدل النص الخام
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(p.fill)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            TextiOS(
                                text = subtitle,
                                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                color = p.secondaryLabel,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        // ---- الخط الشعري السفلي عند التقلّص ----
        if (!transparent) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(0.5.dp)
                    .alpha(progress)
                    .background(p.separator),
            )
        }
    }
}

/** زر أيقونة في الشريط العلوي iOS — أزرق تلقائياً، هدف لمس 40dp */
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
            .size(40.dp)
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
 *  شريط التبويبات السفلي بنمط iPadOS الحديث — v0.20
 *  ─ تصميم عائم منفصل: كبسولة بزوايا كاملة 26dp بهوامش من كل الجهات
 *    تطفو فوق مساحة أزرار النظام بظل ناعم
 *  ─ كبسولة نشطة منزلقة: تتحرك بانسيابية 240ms خلف أيقونة التبويب
 *    المختار بدل الظهور/الاختفاء (RTL/LTR تلقائياً)
 *  ─ تبديل الأيقونة: مفرغة للخامل ومجسّمة للنشط (سلوك iOS الأصيل)
 *  ─ تسميات 11sp + انتقال لوني 200ms + نبضة لمسية عند الاختيار
 * ══════════════════════════════════════════════════════════
 */
data class IOSBarItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
    /** v0.20 — نسخة مجسّمة تُعرض عند نشاط التبويب (اختيارية) */
    val iconActive: ImageVector? = null,
)

/** زوايا الكبسولة العائمة لشريط التبويبات */
private val BottomBarShape = RoundedCornerShape(26.dp)

/**
 * v0.21 — المساحة التي يحتلها الشريط السفلي العائم فوق المحتوى:
 * صف التبويبات 58 + حشوة الكبسولة 12 + الهامش السفلي 4 = 74dp،
 * يُضاف إليها ارتفاع مساحة أزرار النظام (navigationBars).
 * تستعملها شاشات التبويبات كحشوة سفلية للمحتوى كي يمرّ آخر عنصر
 * فوق الشريط الزجاجي الشفاف ولا يختفي تحته.
 */
@Composable
fun rememberBottomBarClearance(): Dp {
    val density = LocalDensity.current
    val navPx = WindowInsets.navigationBars.getBottom(density)
    return with(density) { navPx.toDp() } + 74.dp
}

@Composable
fun IOSBottomBar(
    items: List<IOSBarItem>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalIOSPalette.current
    val haptic = LocalHapticFeedback.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            // v0.20 — يطفو فوق مساحة أزرار التنقل للنظام
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 10.dp, end = 10.dp, bottom = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 14.dp,
                    shape = BottomBarShape,
                    clip = false,
                    ambientColor = Color.Black,
                    spotColor = Color.Black,
                )
                // v0.21 — زجاج iOS: خلفية شبه شفافة يظهر المحتوى من خلالها
                // (تُحلّ المساحة السوداء الميتة: المحتوى يمرّ خلف الشريط فعلياً)
                .background(p.tabBarBg.copy(alpha = 0.78f), shape = BottomBarShape)
                // حدّ شعري رفيع يبرز حواف الكبسولة على السطح الشفاف
                .border(0.5.dp, p.separator.copy(alpha = 0.6f), BottomBarShape)
                .padding(vertical = 6.dp),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
            ) {
                val cellWidth = maxWidth / items.size
                val selIndex = items.indexOfFirst { it.key == selected }.coerceAtLeast(0)
                val pillWidth = 48.dp
                // v0.20 — الكبسولة المنزلقة: تتحرك بين التبويبات بنعومة
                // (offset المبني على Dp واعٍ بالاتجاه: يعمل في RTL وLTR تلقائياً)
                val pillX by animateDpAsState(
                    targetValue = cellWidth * selIndex + (cellWidth - pillWidth) / 2,
                    animationSpec = tween(240, easing = FastOutSlowInEasing),
                    label = "tabPillX",
                )
                Box(
                    modifier = Modifier
                        .offset(x = pillX, y = 7.dp)
                        .size(width = pillWidth, height = 28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(p.accent.copy(alpha = 0.16f)),
                )
                Row(modifier = Modifier.fillMaxSize()) {
                    items.forEach { item ->
                        val active = item.key == selected
                        // انتقال لوني ناعم للون التبويب
                        val tint by animateColorAsState(
                            targetValue = if (active) p.accent else p.secondaryLabel,
                            animationSpec = tween(200),
                            label = "tabTint",
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSelect(item.key)
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(7.dp))
                            // v0.20 — الأيقونة تتبدل: مفرغة للخامل ومجسّمة للنشط
                            Icon(
                                imageVector = if (active) (item.iconActive ?: item.icon) else item.icon,
                                contentDescription = item.label,
                                tint = tint,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.height(2.dp))
                            TextiOS(
                                text = item.label,
                                style = TextStyle(fontSize = 11.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                                color = tint,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
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
