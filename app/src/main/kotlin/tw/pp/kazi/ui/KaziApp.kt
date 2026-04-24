package tw.pp.kazi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.net.URLDecoder
import tw.pp.kazi.AppContainer
import tw.pp.kazi.ui.detail.DetailScreen
import tw.pp.kazi.ui.favorites.FavoritesScreen
import tw.pp.kazi.ui.history.HistoryScreen
import tw.pp.kazi.ui.home.HomeScreen
import tw.pp.kazi.ui.lan.LanShareScreen
import tw.pp.kazi.ui.logs.LogScreen
import tw.pp.kazi.ui.player.PlayerScreen
import tw.pp.kazi.ui.scan.ScanSitesScreen
import tw.pp.kazi.ui.search.SearchScreen
import tw.pp.kazi.ui.settings.SettingsScreen
import tw.pp.kazi.ui.setup.SetupScreen
import tw.pp.kazi.ui.theme.AppColors
import tw.pp.kazi.ui.theme.KaziTheme

val LocalAppContainer = compositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

val LocalNavController = compositionLocalOf<NavHostController> {
    error("NavController not provided")
}

@Composable
fun KaziApp(container: AppContainer) {
    KaziTheme {
        val nav = rememberNavController()
        val windowSize = rememberWindowSize()

        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(Unit) {
            // 只在 Activity 至少 STARTED 時收；背景時請求在 AppContainer 排隊，
            // 回前景時立刻觸發（StateFlow 重播最近一次值）
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.pendingRemoteSearch.filterNotNull().collect { req ->
                    val enabled = container.siteRepository.sites.value.filter { it.enabled }
                    val ids = req.siteIds.ifEmpty { enabled.map { it.id } }
                    container.consumePendingRemoteSearch()
                    nav.navigate(Routes.search(req.keyword, ids)) {
                        popUpTo(Routes.Home)
                        launchSingleTop = true
                    }
                }
            }
        }

        CompositionLocalProvider(
            LocalAppContainer provides container,
            LocalNavController provides nav,
            LocalWindowSize provides windowSize,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0C0C16),
                                AppColors.Bg,
                                Color(0xFF0A0A12),
                            ),
                        ),
                    ),
            ) {
                NavHost(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                    navController = nav,
                    startDestination = Routes.Home,
                ) {
                    composable(Routes.Home) { HomeScreen() }
                    composable(Routes.Setup) { SetupScreen() }
                    composable(Routes.Settings) { SettingsScreen() }
                    composable(Routes.History) { HistoryScreen() }
                    composable(Routes.Favorites) { FavoritesScreen() }
                    composable(Routes.LanShare) { LanShareScreen() }
                    composable(Routes.Logs) { LogScreen() }
                    composable(Routes.ScanSites) { ScanSitesScreen() }
                    composable(
                        route = Routes.SearchPattern,
                        arguments = listOf(
                            navArgument(Routes.ArgKeyword) {
                                type = NavType.StringType; defaultValue = ""
                            },
                            navArgument(Routes.ArgSites) {
                                type = NavType.StringType; defaultValue = ""
                            },
                        ),
                    ) { back ->
                        val kwRaw = back.arguments?.getString(Routes.ArgKeyword).orEmpty()
                        val sitesRaw = back.arguments?.getString(Routes.ArgSites).orEmpty()
                        val keyword = runCatching {
                            URLDecoder.decode(kwRaw, Charsets.UTF_8.name())
                        }.getOrDefault(kwRaw)
                        val siteIds = sitesRaw.split(",").mapNotNull { it.toLongOrNull() }.toSet()
                        SearchScreen(initialKeyword = keyword, initialSiteIds = siteIds)
                    }
                    composable(Routes.DetailPattern) { back ->
                        val args = back.arguments ?: return@composable
                        DetailScreen(
                            siteId = args.getString(Routes.ArgSiteId)?.toLongOrNull() ?: 0L,
                            vodId = args.getString(Routes.ArgVodId)?.toLongOrNull() ?: 0L,
                        )
                    }
                    composable(Routes.PlayerPattern) { back ->
                        val args = back.arguments ?: return@composable
                        PlayerScreen(
                            siteId = args.getString(Routes.ArgSiteId)?.toLongOrNull() ?: 0L,
                            vodId = args.getString(Routes.ArgVodId)?.toLongOrNull() ?: 0L,
                            sourceIdx = args.getString(Routes.ArgSourceIdx)?.toIntOrNull() ?: 0,
                            episodeIdx = args.getString(Routes.ArgEpisodeIdx)?.toIntOrNull() ?: 0,
                            resumePositionMs = args.getString(Routes.ArgPositionMs)?.toLongOrNull() ?: 0L,
                        )
                    }
                }
            }
        }
    }
}
