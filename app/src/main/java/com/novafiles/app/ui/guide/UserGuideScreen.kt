@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.guide

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.guide.GuideCategory
import com.novafiles.app.core.guide.GuideChapter
import com.novafiles.app.core.guide.UserGuide
import com.novafiles.app.core.guide.UserGuideProgress
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSGroupFooter
import com.novafiles.app.ui.ios.IOSGroupHeader
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.ui.ios.rememberBottomBarClearance
import kotlinx.coroutines.launch

/**
 * v0.40 — دليل المستخدم المدمج — شاشة iOS بعشرة فصول في سبع فئات.
 *
 * حقل بحث حي يرشّح الفصول حتماً عبر UserGuide.search (تطبيع عربي
 * وإنجليزي)، ونقر الفصل يسجّله مقروءاً (فوراً محفوظاً في DataStore).
 * التذييل يعرض نسبة الإنجاز والفصل التالي غير المقروء حتماً من
 * UserGuide.percent وnextUnread.
 */
@Composable
fun UserGuideScreen(onBack: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    val rawRead by container.settings.userGuideRead.collectAsState(initial = "")
    val progress = remember(rawRead) { UserGuide.parse(rawRead) }
    var query by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    val results = remember(query) { UserGuide.search(query) }
    val searching = UserGuide.normalize(query).isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalIOSPalette.current.groupedBg)
            .verticalScroll(scrollState)
            .padding(bottom = rememberBottomBarClearance()),
    ) {
        IOSNavBar(
            title = stringResource(R.string.ug_title),
            collapsed = scrollState.value > 24,
            onBack = onBack,
        )

        IOSGroupHeader(stringResource(R.string.ug_intro))

        // ----- حقل البحث الحي -----
        SearchField(
            query = query,
            onQueryChange = { query = it },
        )

        if (searching) {
            // ----- نتائج البحث: قائمة مسطحة بترتيب العرض -----
            if (results.isEmpty()) {
                IOSGroupCard {
                    Text(
                        stringResource(R.string.ug_no_results),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        style = TextStyle(fontSize = 14.sp),
                        color = LocalIOSPalette.current.secondaryLabel,
                    )
                }
            } else {
                IOSGroupCard {
                    results.forEachIndexed { index, chapter ->
                        ChapterRow(
                            chapter = chapter,
                            read = chapter.id in progress.readIds,
                            onToggleRead = { id -> toggleRead(progress, id) { next ->
                                scope.launch { container.settings.setUserGuideRead(UserGuide.serialize(next)) }
                            } },
                            showSeparator = index != results.lastIndex,
                        )
                    }
                }
            }
        } else {
            // ----- الفصول العشرة مجمّعة بالفئات بترتيبها الحتمي -----
            GuideCategory.entries.forEach { category ->
                val inCategory = results.filter { it.category == category }
                if (inCategory.isEmpty()) return@forEach
                IOSGroupHeader(stringResource(categoryLabel(category)))
                IOSGroupCard {
                    inCategory.forEachIndexed { index, chapter ->
                        ChapterRow(
                            chapter = chapter,
                            read = chapter.id in progress.readIds,
                            onToggleRead = { id -> toggleRead(progress, id) { next ->
                                scope.launch { container.settings.setUserGuideRead(UserGuide.serialize(next)) }
                            } },
                            showSeparator = index != inCategory.lastIndex,
                        )
                    }
                }
            }
        }

        IOSGroupFooter(
            if (UserGuide.isComplete(progress)) {
                stringResource(R.string.ug_complete)
            } else {
                val next = UserGuide.nextUnread(progress)
                if (searching || next == null) {
                    stringResource(
                        R.string.ug_progress,
                        UserGuide.percent(progress),
                        progress.readCount,
                        UserGuide.CHAPTERS.size,
                    )
                } else {
                    stringResource(
                        R.string.ug_progress,
                        UserGuide.percent(progress),
                        progress.readCount,
                        UserGuide.CHAPTERS.size,
                    ) + " · " + stringResource(R.string.ug_jump_next, stringResource(chapterTitle(next)))
                }
            },
        )
        Spacer(Modifier.height(32.dp))
    }
}

/** تبديل حالة القراءة — القرار حتماً في المحرك الخالص */
private inline fun toggleRead(
    progress: UserGuideProgress,
    id: String,
    persist: (UserGuideProgress) -> Unit,
) {
    val next = if (id in progress.readIds) {
        UserGuide.markUnread(progress, id)
    } else {
        UserGuide.markRead(progress, id)
    }
    if (next != null) persist(next)
}

/** حقل البحث بنمط iOS — مستدير فوق خلفية fill بلا أيقونات */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val p = LocalIOSPalette.current
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(p.fill)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (query.isEmpty()) {
            Text(
                stringResource(R.string.ug_search_hint),
                style = TextStyle(fontSize = 15.sp),
                color = p.tertiaryLabel,
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = p.label),
            visualTransformation = VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun categoryLabel(category: GuideCategory): Int = when (category) {
    GuideCategory.BASICS -> R.string.ug_cat_basics
    GuideCategory.FILES -> R.string.ug_cat_files
    GuideCategory.NETWORK -> R.string.ug_cat_network
    GuideCategory.CLOUD -> R.string.ug_cat_cloud
    GuideCategory.SECURITY -> R.string.ug_cat_security
    GuideCategory.SMART -> R.string.ug_cat_smart
    GuideCategory.TOOLS -> R.string.ug_cat_tools
}

private fun chapterTitle(chapter: GuideChapter): Int = when (chapter.id) {
    "basics" -> R.string.ug_c_basics_t
    "search_tags" -> R.string.ug_c_search_t
    "archives" -> R.string.ug_c_archives_t
    "network" -> R.string.ug_c_network_t
    "cloud_sync" -> R.string.ug_c_cloud_t
    "vault" -> R.string.ug_c_vault_t
    "smart" -> R.string.ug_c_smart_t
    "automation" -> R.string.ug_c_automation_t
    "tools" -> R.string.ug_c_tools_t
    "settings_transfer" -> R.string.ug_c_settings_t
    else -> R.string.ug_title
}

private fun chapterBody(chapter: GuideChapter): Int = when (chapter.id) {
    "basics" -> R.string.ug_c_basics_b
    "search_tags" -> R.string.ug_c_search_b
    "archives" -> R.string.ug_c_archives_b
    "network" -> R.string.ug_c_network_b
    "cloud_sync" -> R.string.ug_c_cloud_b
    "vault" -> R.string.ug_c_vault_b
    "smart" -> R.string.ug_c_smart_b
    "automation" -> R.string.ug_c_automation_b
    "tools" -> R.string.ug_c_tools_b
    "settings_transfer" -> R.string.ug_c_settings_b
    else -> R.string.ug_intro
}

/** صف الفصل: شارة قراءة دائرية + كبسولة فئة + عنوان وشرح */
@Composable
private fun ChapterRow(
    chapter: GuideChapter,
    read: Boolean,
    onToggleRead: (String) -> Unit,
    showSeparator: Boolean,
) {
    val p = LocalIOSPalette.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleRead(chapter.id) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // شارة القراءة الدائرية — الصف كله قابل للنقر
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (read) p.accent else androidx.compose.ui.graphics.Color.Transparent),
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
                            .background(p.accent.copy(alpha = 0.14f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            stringResource(categoryLabel(chapter.category)),
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            color = p.accent,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(chapterTitle(chapter)),
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                        color = if (read) p.secondaryLabel else p.label,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(chapterBody(chapter)),
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
