@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.novafiles.app.R
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.core.fs.LocalBrowser
import com.novafiles.app.novaContainer
import com.novafiles.app.util.TypeIcons
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** قائمة المفضلة المحفوظة في Room */
@Composable
fun FavoritesScreen(onOpenFile: (File) -> Unit) {
    val context = LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    val favorites by container.db.favoritesDao().observeAll().collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.nav_favorites)) })

        if (favorites.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.empty_favorites),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(favorites, key = { it.path }) { fav ->
                    val file = File(fav.path)
                    val type = FileType.of(file)
                    val exists = file.exists()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = exists) { onOpenFile(file) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (exists && type == FileType.IMAGE) {
                                AsyncImage(
                                    model = file,
                                    contentDescription = fav.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(
                                    if (!exists) Icons.Filled.Delete else TypeIcons.icon(type),
                                    contentDescription = null,
                                    tint = if (!exists) MaterialTheme.colorScheme.error else TypeIcons.color(type),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(fav.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (exists) fav.path else stringResource(R.string.empty_folder),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            scope.launch { container.db.favoritesDao().remove(fav.path) }
                        }) {
                            Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
    }
}
