package com.novafiles.app.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.fs.DirTree
import com.novafiles.app.core.fs.TreeNode
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.launch
import java.io.File

/**
 * ════════════════════════════════════════════════════════════════
 *  لوحة الشجرة الجانبية — قفزات سريعة بين المجلدات
 *  ─ تظهر كطبقة منزلقة من الحافة الابتدائية (يمين في العربية)
 *  ─ مجلدات قابلة للفتح/الطي بأسهم، والمسار الحالي مميَّز
 *  ─ التنقّل لا يغلقها — إغلاقها من الخلفية أو زر الإغلاق
 * ════════════════════════════════════════════════════════════════
 */

/** عروض الشجرة الافتراضية: جذر التخزين الداخلي وحده (نفس جذر المتصفح) */
internal fun defaultTreeRoots(): List<File> {
    val root = android.os.Environment.getExternalStorageDirectory() ?: return emptyList()
    return listOf(root)
}

@Composable
internal fun DirectoryTreePanel(
    visible: Boolean,
    currentPath: String,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val p = LocalIOSPalette.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var expanded by remember(currentPath) {
        mutableStateOf(
            runCatching { DirTree.defaultExpanded(currentPath) }.getOrDefault(emptySet()),
        )
    }

    // الشجرة تُبنى من مسار حالي + حالة الفتح — القراءة محلية سريعة (مجلدات فقط)
    val nodes = if (visible) {
        remember(expanded, currentPath) {
            runCatching {
                DirTree.build(defaultTreeRoots(), currentPath, expanded)
            }.getOrDefault(emptyList())
        }
    } else {
        emptyList()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        // خلفية معتمة — لمسها يغلق اللوحة
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            initialOffsetX = { if (rtl) it else -it },
        ) + fadeIn(),
        exit = slideOutHorizontally(
            targetOffsetX = { if (rtl) it else -it },
        ) + fadeOut(),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp)
                    .background(p.groupedBg)
                    .padding(top = 8.dp),
            ) {
                // رأس اللوحة
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.tree_title),
                        style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        color = p.label,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.tree_close),
                        tint = p.accent,
                        modifier = Modifier
                            .size(22.dp)
                            .clickable(onClick = onDismiss),
                    )
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // v0.36 — الإشارات المرجعية: مجلدات العمل اليومية فوق الشجرة
                    item { BookmarksSection(currentPath, onNavigate) }
                    item { Spacer(Modifier.height(6.dp)) }
                    items(nodes, key = { it.path }) { node ->
                        TreeRow(
                            node = node,
                            onToggle = { dir ->
                                expanded = expanded.toMutableSet().apply {
                                    if (!add(dir)) remove(dir)
                                }
                            },
                            onNavigate = onNavigate,
                        )
                    }
                }
            }
        }
    }
}

/**
 * v0.36 — قسم الإشارات المرجعية أعلى الشجرة: وصول فوري لمجلدات العمل اليومية،
 * منفصلة عن المفضلة. زر (+) يثبّت المجلد الحالي، و(X) يزيل مرجعاً.
 */
@Composable
private fun BookmarksSection(
    currentPath: String,
    onNavigate: (String) -> Unit,
) {
    val p = LocalIOSPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val store = context.novaContainer.bookmarks
    val list by store.bookmarks.collectAsState(initial = emptyList())
    val alreadyBookmarked = list.any { it.path == currentPath }

    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = p.accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.bookmarks_title),
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = p.secondaryLabel,
                modifier = Modifier.weight(1f),
            )
            // تثبيت المجلد الحالي — يختفي الزر إذا كان مرجعاً بالفعل
            if (!alreadyBookmarked) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.bookmarks_add_current),
                    tint = p.accent,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable {
                            scope.launch {
                                val added = store.add(currentPath, currentPath.substringAfterLast('/'))
                                // السقف الممتلئ يُتجاهل بصمت — القائمة صغيرة بطبيعتها (24)
                                if (!added) Unit
                            }
                        },
                )
            }
        }

        list.forEach { bm ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .background(p.accent.copy(alpha = 0.10f), RoundedCornerShape(9.dp))
                    .clickable { onNavigate(bm.path) }
                    .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Bookmarks,
                    contentDescription = null,
                    tint = p.accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    bm.name,
                    style = TextStyle(fontSize = 14.sp),
                    color = p.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.bookmarks_remove),
                    tint = p.secondaryLabel,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { scope.launch { store.remove(bm.path) } },
                )
            }
        }

        if (list.isEmpty()) {
            Text(
                stringResource(R.string.bookmarks_empty),
                style = TextStyle(fontSize = 12.sp),
                color = p.secondaryLabel,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        // فاصل خفيف قبل الشجرة
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun TreeRow(
    node: TreeNode,
    onToggle: (String) -> Unit,
    onNavigate: (String) -> Unit,
) {
    val p = LocalIOSPalette.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .background(
                when {
                    node.isCurrent -> p.accent.copy(alpha = 0.16f)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                },
                RoundedCornerShape(9.dp),
            )
            .clickable { onNavigate(node.path) }
            .padding(start = (8 + node.depth * 18).dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // سهم الفتح/الطي: مفتوح نحو الأسفل، مغلق نحو اتجاه الأبناء (حسب الاتجاه)
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = p.secondaryLabel,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    rotationZ = when {
                        node.isExpanded -> 0f // مفتوح: للأسفل في الاتجاهين
                        rtl -> 90f // مغلق في RTL: نحو اليسار حيث الأبناء
                        else -> -90f // مغلق في LTR: نحو اليمين حيث الأبناء
                    }
                }
                .clickable(
                    onClick = { onToggle(node.path) },
                    // منطقة لمس أوسع قليلاً
                ),
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            tint = if (node.isCurrent || node.isAncestor) p.accent else p.secondaryLabel,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            node.name,
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = if (node.isCurrent || node.isAncestor) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (node.isCurrent) p.accent else p.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
