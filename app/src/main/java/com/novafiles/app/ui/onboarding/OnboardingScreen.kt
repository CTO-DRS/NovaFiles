package com.novafiles.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.onboarding.OnboardingEngine
import com.novafiles.app.core.onboarding.OnboardingState
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.launch

/**
 * v0.39 — شاشة الترحيب للمستخدم الجديد (Onboarding) بنمط iOS:
 * صفحة كاملة لكل خطوة، أيقونة كبيرة في دائرة مميزة، نقاط تقدّم،
 * زر كبسولة رئيسي وزر تخطٍّ خفيف. الإتمام يُحفظ عبر OnboardingEngine
 * في DataStore — ولا تعود الشاشة أبداً بعد الإتمام (قرار حتمي).
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val container = LocalContext.current.novaContainer
    val scope = rememberCoroutineScope()
    val palette = LocalIOSPalette.current

    var stepIndex by remember { mutableIntStateOf(0) }
    var doneRaw by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        container.settings.onboardingDone.collect { doneRaw = it }
    }

    val state = remember(doneRaw) { OnboardingEngine.parse(doneRaw) }
    // العرض مرتبط بموضع الزائر الحالي لا بالحالة المحفوظة — الحالة تُوَثَّق عند كل إنهاء
    val step = OnboardingEngine.STEPS[stepIndex.coerceIn(0, OnboardingEngine.STEPS.lastIndex)]
    val isLast = stepIndex == OnboardingEngine.STEPS.lastIndex

    val icon: ImageVector = when (step.id) {
        "welcome" -> Icons.Filled.WavingHand
        "storage" -> Icons.Filled.FolderOpen
        "vault" -> Icons.Filled.Lock
        else -> Icons.Filled.AutoAwesome
    }

    fun finishStep() {
        val next = OnboardingEngine.markDone(state, step.id) ?: state
        scope.launch {
            container.settings.setOnboardingDone(OnboardingEngine.serialize(next))
            if (isLast) onFinished() else stepIndex += 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.groupedBg)
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.9f))

        // الأيقونة الكبيرة داخل دائرة مميزة
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null, // زخرفية — النصان أسفلها يكفيان قارئ الشاشة
                tint = palette.accent,
                modifier = Modifier.size(46.dp),
            )
        }

        Spacer(Modifier.height(28.dp))

        // عنوان الخطوة بنمط العنوان الكبير
        Text(
            stringResource(step.titleKey.resId()),
            style = TextStyle(fontSize = 28.sp),
            color = palette.label,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(14.dp))

        Text(
            stringResource(step.bodyKey.resId()),
            style = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
            color = palette.secondaryLabel,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))

        // نقاط التقدّم
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OnboardingEngine.STEPS.forEachIndexed { i, s ->
                val active = i == stepIndex
                val past = s.id in state.doneIds
                Box(
                    modifier = Modifier
                        .size(if (active) 9.dp else 7.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                active -> palette.accent
                                past -> palette.accent.copy(alpha = 0.45f)
                                else -> palette.separator
                            },
                        ),
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // زر الكبسولة الرئيسي
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.accent)
                .clickable { finishStep() }
                .padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(if (isLast) R.string.onboarding_start else R.string.onboarding_next),
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 17.sp,
            )
        }

        // تخطٍّ — ينهي كل الخطوات دفعة واحدة (قرار صريح لا غموض فيه)
        if (!isLast) {
            Text(
                stringResource(R.string.onboarding_skip),
                color = palette.secondaryLabel,
                fontSize = 14.sp,
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 20.dp)
                    .clickable {
                        scope.launch {
                            container.settings.setOnboardingDone(
                                OnboardingEngine.serialize(
                                    OnboardingState(OnboardingEngine.STEPS.map { it.id }.toSet()),
                                ),
                            )
                            onFinished()
                        }
                    },
            )
        } else {
            Spacer(Modifier.height(20.dp))
        }

        Text(
            stringResource(R.string.onboarding_step, stepIndex + 1, OnboardingEngine.STEPS.size),
            color = palette.secondaryLabel,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}

/** حلّ مفاتيح المحرك النصية إلى موارد R.string */
private fun String.resId(): Int = when (this) {
    "onboarding_welcome_t" -> R.string.onboarding_welcome_t
    "onboarding_welcome_b" -> R.string.onboarding_welcome_b
    "onboarding_perm_t" -> R.string.onboarding_perm_t
    "onboarding_perm_b" -> R.string.onboarding_perm_b
    "onboarding_vault_t" -> R.string.onboarding_vault_t
    "onboarding_vault_b" -> R.string.onboarding_vault_b
    "onboarding_smart_t" -> R.string.onboarding_smart_t
    "onboarding_smart_b" -> R.string.onboarding_smart_b
    else -> R.string.onboarding_welcome_t
}
