package com.novafiles.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import com.novafiles.app.core.archive.ArchiveEngine
import com.novafiles.app.core.fs.FileType
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSBarItem
import com.novafiles.app.ui.ios.IOSBottomBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import com.novafiles.app.ui.archive.ArchiveBrowserScreen
import com.novafiles.app.ui.browser.BrowserScreen
import com.novafiles.app.ui.direct.DirectScreen
import com.novafiles.app.ui.editor.EditorScreen
import com.novafiles.app.ui.favorites.FavoritesScreen
import com.novafiles.app.ui.ftp.FtpScreen
import com.novafiles.app.ui.network.ConnectionFormScreen
import com.novafiles.app.ui.recents.RecentScreen
import com.novafiles.app.ui.network.NetworkScreen
import com.novafiles.app.ui.network.RemoteBrowserScreen
import com.novafiles.app.ui.search.SearchScreen
import com.novafiles.app.ui.analyzer.AnalyzerScreen
import com.novafiles.app.ui.ops.OpsScreen
import com.novafiles.app.ui.settings.SettingsScreen
import com.novafiles.app.ui.vault.Vault2Screen
import com.novafiles.app.ui.viewer.ImageViewerScreen
import androidx.lifecycle.viewmodel.initializer
import java.io.File

/** مسارات التنقل */
object Routes {
    const val BROWSER = "browser"
    const val FAVORITES = "favorites"
    const val VAULT = "vault"          // v0.19 — صار شاشة فرعية تُفتح من الإعدادات
    const val RECENTS = "recents"      // v0.19 — تبويب «الأخيرة» في الشريط السفلي
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val EDITOR = "editor?path={path}"
    const val VIEWER = "viewer?path={path}"
    const val ARCHIVE = "archive?path={path}"
    const val FTP = "ftp"
    const val NETWORK = "network"
    const val CONN_FORM = "connform?connId={connId}"
    const val REMOTE = "remote?connId={connId}"
    const val OPS = "ops"
    const val DIRECT = "direct?mode={mode}"
    const val ANALYZER = "analyzer"
    const val CLOUD = "cloud"
    const val CLOUD_BROWSER = "cloudbrowser?accountId={accountId}"

    fun cloudBrowser(accountId: Long) = "cloudbrowser?accountId=$accountId"

    fun editor(path: String) = "editor?path=${android.net.Uri.encode(path)}"
    fun viewer(path: String) = "viewer?path=${android.net.Uri.encode(path)}"
    fun archive(path: String) = "archive?path=${android.net.Uri.encode(path)}"
    fun connForm(connId: Long) = "connform?connId=$connId"
    fun remote(connId: Long) = "remote?connId=$connId"
    fun direct(sendMode: Boolean) = "direct?mode=${if (sendMode) "send" else "receive"}"
}

private data class Tab(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val iconActive: androidx.compose.ui.graphics.vector.ImageVector? = null,
)

// v0.19 — الخزنة انتقلت إلى الإعدادات، ومكانها تبويب «الأخيرة»
// v0.20 — أيقونات مزدوجة: مفرغة للخامل ومجسّمة للنشط (سلوك iOS)
private val tabs = listOf(
    Tab(Routes.BROWSER, R.string.nav_browser, Icons.Outlined.Folder, Icons.Filled.Folder),
    Tab(Routes.SEARCH, R.string.nav_search, Icons.Outlined.Search, Icons.Filled.Search),
    Tab(Routes.FAVORITES, R.string.nav_favorites, Icons.Outlined.Star, Icons.Filled.Star),
    Tab(Routes.RECENTS, R.string.nav_recents, Icons.Outlined.History, Icons.Filled.History),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun NovaRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }
    val palette = LocalIOSPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // v0.12 — استهلاك حمولة nova-direct:// القادمة من مسح QR:
    // إنشاء اتصال FTP مؤقت للجهاز المرسل ثم فتح متصفح الخادم البعيد
    LaunchedEffect(Unit) {
        val payload = context.novaContainer.pendingDirectPayload ?: return@LaunchedEffect
        context.novaContainer.pendingDirectPayload = null
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val id = context.novaContainer.db.connectionsDao().upsert(
                com.novafiles.app.data.db.ConnectionEntity(
                    name = payload.deviceName.ifEmpty { "Wi-Fi Direct" },
                    protocol = "FTP",
                    host = payload.host,
                    port = payload.port,
                    username = payload.user,
                    password = payload.pass,
                ),
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                nav.navigate(Routes.remote(id))
            }
        }
    }

    // v0.17 — نقرات الودجات: خزنة/عمليات/فتح مسار
    LaunchedEffect(Unit) {
        val target = context.novaContainer.pendingWidgetTarget ?: return@LaunchedEffect
        context.novaContainer.pendingWidgetTarget = null
        when {
            target == com.novafiles.app.widget.WidgetBridge.TARGET_VAULT ->
                nav.navigate(Routes.VAULT)
            target == com.novafiles.app.widget.WidgetBridge.TARGET_TRANSFERS ->
                nav.navigate(Routes.OPS)
            target.startsWith(com.novafiles.app.widget.WidgetBridge.TARGET_OPEN_PATH) -> {
                val path = target.removePrefix(com.novafiles.app.widget.WidgetBridge.TARGET_OPEN_PATH)
                val f = java.io.File(path)
                if (f.isDirectory) nav.navigate(Routes.BROWSER)
                else if (f.exists()) openFile(nav, f)
            }
        }
    }

    // v0.17 — «فتح باستخدام NovaFiles» (ACTION_VIEW من تطبيق آخر)
    LaunchedEffect(Unit) {
        val raw = context.novaContainer.pendingViewPath ?: return@LaunchedEffect
        context.novaContainer.pendingViewPath = null
        val path = raw.removePrefix("file://")
        val f = java.io.File(path)
        if (f.exists()) openFile(nav, f)
    }

    // v0.21 — تراكب: المحتوى يمتد بكامل الشاشة خلف الشريط الزجاجي الشفاف
    // (تُلغى المساحة السوداء الميتة التي كانت فوق الشريط وتحته عند التكديس)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.groupedBg),
    ) {
        NovaNavHost(nav = nav)
        // ═══ شريط التبويبات السفلي العائم الشفاف فوق المحتوى ═══
        if (showBottomBar) {
            IOSBottomBar(
                items = tabs.map { tab ->
                    IOSBarItem(
                        key = tab.route,
                        label = stringResource(tab.labelRes),
                        icon = tab.icon,
                        iconActive = tab.iconActive,
                    )
                },
                selected = currentRoute ?: Routes.BROWSER,
                onSelect = { route -> nav.navigateTop(route) },
                modifier = Modifier.align(Alignment.BottomCenter),
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
            if (ArchiveEngine.supports(file.name)) nav.navigate(Routes.archive(file.absolutePath))
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
                onOpenOps = { nav.navigate(Routes.OPS) },
                onOpenDirectSend = { nav.navigate(Routes.direct(sendMode = true)) },
                onOpenAnalyzer = { nav.navigate(Routes.ANALYZER) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(onOpenFile = { openFile(nav, it) })
        }
        composable(Routes.FAVORITES) {
            FavoritesScreen(onOpenFile = { openFile(nav, it) })
        }
        composable(Routes.RECENTS) {
            RecentScreen(onOpenFile = { openFile(nav, it) })
        }
        // v0.19 — الخزنة شاشة فرعية تُفتح من الإعدادات (مع زر رجوع)
        composable(Routes.VAULT) {
            Vault2Screen(onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onOpenVault = { nav.navigate(Routes.VAULT) })
        }
        composable(Routes.FTP) {
            FtpScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.OPS) {
            // شاشة العمليات — نفس قائمة OpQueue عبر الحاوية المشتركة
            val ctx = nav.context
            val vm: com.novafiles.app.ui.browser.BrowserViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                factory = androidx.lifecycle.viewmodel.viewModelFactory {
                    initializer({
                        com.novafiles.app.ui.browser.BrowserViewModel(ctx.novaContainer, ctx.applicationContext)
                    })
                },
            )
            OpsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.ANALYZER) {
            AnalyzerScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.CLOUD) {
            com.novafiles.app.ui.cloud.CloudScreen(
                onBack = { nav.popBackStack() },
                onBrowse = { accountId -> nav.navigate(Routes.cloudBrowser(accountId)) },
            )
        }
        composable(
            Routes.CLOUD_BROWSER,
            arguments = listOf(navArgument("accountId") {
                type = NavType.LongType
            }),
        ) { entry ->
            com.novafiles.app.ui.cloud.CloudBrowserScreen(
                accountId = entry.arguments?.getLong("accountId") ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.DIRECT,
            arguments = listOf(navArgument("mode") {
                type = NavType.StringType
                defaultValue = "send"
            }),
        ) { entry ->
            val sendMode = entry.arguments?.getString("mode") != "receive"
            DirectScreen(
                sendMode = sendMode,
                onBack = { nav.popBackStack() },
                onOpenRemote = { connId -> nav.navigate(Routes.remote(connId)) },
            )
        }
        composable(Routes.NETWORK) {
            NetworkScreen(
                onBack = { nav.popBackStack() },
                onConnect = { nav.navigate(Routes.remote(it.id)) },
                onEdit = { nav.navigate(Routes.connForm(it.id)) },
                onAdd = { nav.navigate(Routes.connForm(0L)) },
                onCloud = { nav.navigate(Routes.CLOUD) },
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
            Routes.ARCHIVE,
            arguments = listOf(navArgument("path") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }),
        ) { entry ->
            ArchiveBrowserScreen(
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
