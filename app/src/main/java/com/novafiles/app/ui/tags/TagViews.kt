@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.novafiles.app.R
import com.novafiles.app.core.tags.TagPalette
import com.novafiles.app.data.db.TagDefEntity
import com.novafiles.app.data.tags.TagsRepository
import com.novafiles.app.ui.ios.IOSMiniField
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.launch

/**
 * v0.33 — مكوّنات واجهة الوسوم: نقطة لونية، شريحة فلترة،
 * صف وسوم أفقي، وورقة إسناد/إدارة الوسوم بنمط iOS.
 */

/** نقطة لون الوسم */
@Composable
fun TagColorDot(colorIndex: Int, sizeDp: Int = 10, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(Color(TagPalette.colorAt(colorIndex))),
    )
}

/** شريحة وسم واحدة (فلترة) — نقطة ملونة + الاسم + العدّ */
@Composable
fun TagFilterChip(
    def: TagDefEntity,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalIOSPalette.current
    val bg = if (selected) Color(TagPalette.colorAt(def.colorIndex)) else palette.barBg
    val fg = if (selected) Color(TagPalette.ON_COLOR) else palette.label
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!selected) TagColorDot(def.colorIndex, sizeDp = 9)
        if (!selected) Spacer(Modifier.width(6.dp))
        Text(
            def.name,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (count > 0) {
            Spacer(Modifier.width(5.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = fg.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * شريط فلترة الوسوم الأفقي: «الكل» + كل وسم بعدّه.
 * يظهر فقط عند وجود وسوم.
 */
@Composable
fun TagFilterBar(
    defs: List<TagDefEntity>,
    counts: Map<Long, Int>,
    selectedTagId: Long?,
    onSelect: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (defs.isEmpty()) return
    val palette = LocalIOSPalette.current
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
    ) {
        item {
            val allSelected = selectedTagId == null
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (allSelected) palette.accent else palette.barBg)
                    .clickable { onSelect(null) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.tags_filter_all),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (allSelected) Color.White else palette.label,
                )
            }
        }
        items(defs, key = { it.id }) { def ->
            TagFilterChip(
                def = def,
                count = counts[def.id] ?: 0,
                selected = selectedTagId == def.id,
                onClick = { onSelect(if (selectedTagId == def.id) null else def.id) },
            )
        }
    }
}

/**
 * ورقة إسناد الوسوم بنمط iOS: قائمة الوسوم بمربعات تحديد،
 * حقل إنشاء وسم جديد، وحذف الوسم نهائياً.
 * @param paths المسارات المحددة المراد وسمها
 */
@Composable
fun TagAssignSheet(
    visible: Boolean,
    paths: List<String>,
    repo: TagsRepository,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val scope = rememberCoroutineScope()
    val defs by repo.observeDefs().collectAsState(initial = emptyList())
    var newTagName by remember { mutableStateOf("") }
    var errorShown by remember { mutableStateOf(false) }

    // الوسوم الحالية للمسار الأول (التحديد المتزامن يعتمد عليه)
    var taggedIds by remember(paths) { mutableStateOf(setOf<Long>()) }
    androidx.compose.runtime.LaunchedEffect(paths) {
        if (paths.isEmpty()) {
            taggedIds = emptySet()
        } else {
            val sets: List<Set<Long>> = paths.map { p -> repo.tagsForPath(p).map { d -> d.id }.toSet() }
            taggedIds = sets.fold(setOf<Long>()) { acc, s ->
                if (acc.isEmpty()) s else acc intersect s
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Label,
                    contentDescription = null,
                    tint = LocalIOSPalette.current.accent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (paths.size == 1) {
                        stringResource(R.string.tags_assign_one, paths.first().substringAfterLast('/'))
                    } else {
                        stringResource(R.string.tags_assign_many, paths.size)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Spacer(Modifier.height(12.dp))

            if (defs.isEmpty()) {
                Text(
                    stringResource(R.string.tags_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                defs.forEach { def ->
                    val checked = def.id in taggedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                scope.launch {
                                    if (checked) {
                                        paths.forEach { repo.unassign(def.id, it) }
                                    } else {
                                        repo.assign(def.id, paths)
                                    }
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TagColorDot(def.colorIndex, sizeDp = 12)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            def.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = {
                            scope.launch { repo.remove(def.id) }
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.tags_delete_tag),
                                tint = LocalIOSPalette.current.destructive.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        if (checked) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = LocalIOSPalette.current.accent,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IOSMiniField(
                    value = newTagName,
                    onValueChange = {
                        newTagName = it
                        errorShown = false
                    },
                    hint = stringResource(R.string.tags_name_hint),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                val palette2 = LocalIOSPalette.current
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (newTagName.isBlank()) palette2.fill else palette2.accent)
                        .clickable(enabled = newTagName.isNotBlank()) {
                            scope.launch {
                                val result = repo.create(newTagName)
                                if (result.error == null) {
                                    newTagName = ""
                                    result.id?.let { id ->
                                        if (paths.isNotEmpty()) repo.assign(id, paths)
                                    }
                                } else {
                                    errorShown = true
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Text(
                        stringResource(R.string.tags_create_btn),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (newTagName.isBlank()) palette2.secondaryLabel else Color.White,
                    )
                }
            }
            if (errorShown) {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.tags_error_duplicate),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalIOSPalette.current.destructive,
                )
            }
        }
    }
}
