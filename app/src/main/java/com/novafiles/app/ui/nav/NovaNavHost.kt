package com.novafiles.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.novafiles.app.R
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.ui.ios.IOSBarItem
import com.novafiles.app.ui.ios.IOSBottomBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.ui.browser.BrowserScreen
import com.novafiles.app.ui.editor.EditorScreen
import com.novafiles.app.ui.favorites.FavoritesScreen
import com.novafiles.app.ui.ftp.FtpScreen
import com.novafiles.app.ui.network.ConnectionFormScreen
import com.novafiles.app.ui.network.NetworkScreen
import com.novafiles.app.ui.network.RemoteBrowserScreen
import com.novafiles.app.ui.search.SearchScreen
import com.novafiles.app.ui.settings.SettingsScreen
import com.novafiles.app.ui.vault.VaultScreen
import com.novafiles.app.ui.viewer.ImageViewerScreen
import com.novafiles.app.ui.zip.ZipBrowserScreen
import java.io.File

/** مسارات التنقل */
object Routes {
    const val BROWSER = "browser"
    const val FAVORITES = "favorites"
    const val VAULT = "vault"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val EDITOR = "editor?path={path}"
    const val VIEWER = "viewer?path={path}"
    const val ZIP = "zip?path={path}"
    const val FTP = "ftp"
    const val NETWORK = "network"
    const val CONN_FORM = "connform?connId={connId}"
    const val REMOTE = "remote?connId={connId}"

    fun editor(path: String) = "editor?path=${android.net.Uri.encode(path)}"
    fun viewer(path: String) = "viewer?path=${android.net.Uri.encode(path)}"
    fun zip(path: String) = "zip?path=${android.net.Uri.encode(path)}"
    fun connForm(connId: Long) = "connform?connId=$connId"
    fun remote(connId: Long) = "remote?connId=$connId"
}

private data class Tab(val route: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab(Routes.BROWSER, R.string.nav_browser, Icons.Filled.Folder),
    Tab(Routes.SEARCH, R.string.nav_search, Icons.Filled.Search),
    Tab(Routes.FAVORITES, R.string.nav_favorites, Icons.Filled.Star),
    Tab(Routes.VAULT, R.string.nav_vault, Icons.Filled.Lock),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun NovaRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }
    val palette = LocalIOSPalette.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.groupedBg),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            NovaNavHost(nav = nav)
        }
        // ═══ شريط التبويبات السفلي بنمط iOS ═══
        if (showBottomBar) {
            IOSBottomBar(
                items = tabs.map { tab ->
                    IOSBarItem(
                        key = tab.route,
                        label = stringResource(tab.labelRes),
                        icon = tab.icon,
                    )
                },
                selected = currentRoute ?: Routes.BROWSER,
                onSelect = { route -> nav.navigateTop(route) },
            )
        }
    }
}

/** فتح ملف: عارض صور، محرر نصوص، عارض أرشيف، أو تطبيق خارجي */
fun openFile(nav: NavHostController, file: File) {
    val ctx = nav.context
    when (FileType.of(file)) {
        FileType.IMAGE -> nav.navigate(Routes.viewer(file.absolutePath))
        FileType.TEXT, FileType.CODE -> nav.navigate(Routes.editor(file.absolutePath))
        FileType.ARCHIVE ->
            if (file.name.endsWith(".zip", ignoreCase = true)) nav.navigate(Routes.zip(file.absolutePath))
            else com.novafiles.app.util.Intents.view(ctx, file)
        else -> com.novafiles.app.util.Intents.view(ctx, file)
    }
}

@Composable
private fun NovaNavHost(nav: NavHostController) {
    NavHost(
        navController = nav,
        startDestination = Routes.BROWSER,
    ) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                onOpenFile = { openFile(nav, it) },
                onOpenSearchFor = { /* يُفتح من شريط التنقل مباشرة */ },
                onOpenFtp = { nav.navigate(Routes.FTP) },
                onOpenNetwork = { nav.navigate(Routes.NETWORK) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(onOpenFile = { openFile(nav, it) })
        }
        composable(Routes.FAVORITES) {
            FavoritesScreen(onOpenFile = { openFile(nav, it) })
        }
        composable(Routes.VAULT) {
            VaultScreen()
        }
        composable(Routes.SETTINGS) {
            SettingsScreen()
        }
        composable(Routes.FTP) {
            FtpScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.NETWORK) {
            NetworkScreen(
                onBack = { nav.popBackStack() },
                onConnect = { nav.navigate(Routes.remote(it.id)) },
                onEdit = { nav.navigate(Routes.connForm(it.id)) },
                onAdd = { nav.navigate(Routes.connForm(0L)) },
            )
        }
        composable(
            Routes.CONN_FORM,
            arguments = listOf(navArgument("connId") {
                type = NavType.LongType
                defaultValue = 0L
            }),
        ) { entry ->
            ConnectionFormScreen(
                connectionId = entry.arguments?.getLong("connId") ?: 0L,
                onBack = { nav.popBackStack() },
                onSaved = { nav.popBackStack() },
            )
        }
        composable(
            Routes.REMOTE,
            arguments = listOf(navArgument("connId") {
                type = NavType.LongType
            }),
        ) { entry ->
            RemoteBrowserScreen(
                connectionId = entry.arguments?.getLong("connId") ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(navArgument("path") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { entry ->
            EditorScreen(path = entry.arguments?.getString("path"))
        }
        composable(
            Routes.VIEWER,
            arguments = listOf(navArgument("path") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { entry ->
            ImageViewerScreen(path = entry.arguments?.getString("path"))
        }
        composable(
            Routes.ZIP,
            arguments = listOf(navArgument("path") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { entry ->
            ZipBrowserScreen(
                path = entry.arguments?.getString("path"),
                onBack = { nav.popBackStack() },
            )
        }
    }
}

private fun NavHostController.navigateTop(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
