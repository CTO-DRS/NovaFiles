@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.security.GuideSection
import com.novafiles.app.core.security.GuideSeverity
import com.novafiles.app.core.security.GuideProgress
import com.novafiles.app.core.security.SecurityGuide
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupFooter
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.ui.ios.rememberBottomBarClearance
import kotlinx.coroutines.launch

/**
 * v0.38 — الدليل الأمني للمستخدم الجديد — شاشة iOS بثمانية أقسام.
 *
 * كل قسم: شارة شدة ملوّنة (جوهرية/مستحسنة/متقدمة) + عنوان + شرح،
 * ونقر الشارة الدائرية يسجّل القسم مُقرأاً (فوراً محفوظاً في DataStore).
 * التذييل يعرض نسبة الإنجاز حتماً من SecurityGuide.percent.
 */
@Composable
fun SecurityGuideScreen(onBack: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    val rawRead by container.settings.securityGuideRead.collectAsState(initial = "")
    val progress = rememberReadProgress(rawRead)
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalIOSPalette.current.groupedBg)
            .verticalScroll(scrollState)
            .padding(bottom = rememberBottomBarClearance()),
    ) {
        IOSNavBar(
            title = stringResource(R.string.guide_title),
            collapsed = scrollState.value > 24,
            onBack = onBack,
        )

        IOSGroupHeader(stringResource(R.string.guide_footer_hint))

        // الأقسام الثمانية — كل شحنة أقسام جوهرية في بطاقة مستقلة
        val groups = rememberSections()
        groups.forEach { (severity, sections) ->
            IOSGroupHeader(stringResource(severityLabel(severity)))
            IOSGroupCard {
                sections.forEachIndexed { index, section ->
                    GuideRow(
                        section = section,
                        read = section.id in progress.readIds,
                        onToggleRead = { id ->
                            val next = if (id in progress.readIds) {
                                SecurityGuide.markUnread(progress, id)
                            } else {
                                SecurityGuide.markRead(progress, id)
                            }
                            if (next != null) {
                                scope.launch { container.settings.setSecurityGuideRead(SecurityGuide.serialize(next)) }
                            }
                        },
                        showSeparator = index != sections.lastIndex,
                    )
                }
            }
        }

        IOSGroupFooter(
            if (SecurityGuide.isComplete(progress)) {
                stringResource(R.string.guide_complete)
            } else {
                stringResource(
                    R.string.guide_progress,
                    SecurityGuide.percent(progress),
                    progress.readCount,
                    SecurityGuide.SECTIONS.size,
                )
            },
        )
        Spacer(Modifier.height(32.dp))
    }
}

/** حل CSV الخام إلى حالة تقدّم — حتماً عبر المحرك الخالص */
@Composable
private fun rememberReadProgress(raw: String): GuideProgress =
    androidx.compose.runtime.remember(raw) { SecurityGuide.parse(raw) }

/** تجميع الأقسام حسب الشدة بترتيب العرض */
@Composable
private fun rememberSections(): List<Pair<GuideSeverity, List<GuideSection>>> =
    androidx.compose.runtime.remember {
        SecurityGuide.SECTIONS.groupBy { it.severity }
            .let { grouped ->
                GuideSeverity.entries.mapNotNull { sev -> grouped[sev]?.let { sev to it } }
            }
    }

private fun severityLabel(sev: GuideSeverity): Int = when (sev) {
    GuideSeverity.ESSENTIAL -> R.string.severity_essential
    GuideSeverity.RECOMMENDED -> R.string.severity_recommended
    GuideSeverity.ADVANCED -> R.string.severity_advanced
}

private fun severityColor(sev: GuideSeverity, accent: androidx.compose.ui.graphics.Color): androidx.compose.ui.graphics.Color = when (sev) {
    GuideSeverity.ESSENTIAL -> accent
    GuideSeverity.RECOMMENDED -> accent.copy(alpha = 0.72f)
    GuideSeverity.ADVANCED -> accent.copy(alpha = 0.45f)
}

private fun sectionTitle(section: GuideSection): Int = when (section.id) {
    "vault_pin" -> R.string.guide_s_vault_pin_t
    "encryption" -> R.string.guide_s_encryption_t
    "recovery" -> R.string.guide_s_recovery_t
    "network_shares" -> R.string.guide_s_network_t
    "cloud_privacy" -> R.string.guide_s_cloud_t
    "permissions" -> R.string.guide_s_perms_t
    "safe_delete" -> R.string.guide_s_safedelete_t
    "updates" -> R.string.guide_s_updates_t
    else -> R.string.guide_title
}

private fun sectionBody(section: GuideSection): Int = when (section.id) {
    "vault_pin" -> R.string.guide_s_vault_pin_b
    "encryption" -> R.string.guide_s_encryption_b
    "recovery" -> R.string.guide_s_recovery_b
    "network_shares" -> R.string.guide_s_network_b
    "cloud_privacy" -> R.string.guide_s_cloud_b
    "permissions" -> R.string.guide_s_perms_b
    "safe_delete" -> R.string.guide_s_safedelete_b
    "updates" -> R.string.guide_s_updates_b
    else -> R.string.guide_footer_hint
}

/** صف القسم: شارة قراءة دائرية + شارة شدة + عنوان وشرح */
@Composable
private fun GuideRow(
    section: GuideSection,
    read: Boolean,
    onToggleRead: (String) -> Unit,
    showSeparator: Boolean,
) {
    val p = LocalIOSPalette.current
    val sevColor = severityColor(section.severity, p.accent)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleRead(section.id) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // شارة القراءة الدائرية — مثل رسائل Mail المُقرأة (الصف كله قابل للنقر)
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (read) sevColor else androidx.compose.ui.graphics.Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (read) {
                    Text("✓", color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(sevColor.copy(alpha = 0.14f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            stringResource(severityLabel(section.severity)),
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            color = sevColor,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(sectionTitle(section)),
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                        color = if (read) p.secondaryLabel else p.label,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(sectionBody(section)),
                    style = TextStyle(fontSize = 13.sp),
                    color = p.secondaryLabel,
                )
            }
        }
        if (showSeparator) {
            Box(
                modifier = Modifier
                    .padding(start = 52.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(p.separator),
            )
        }
    }
}
