package tw.pp.kazi.ui.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.Routes
import tw.pp.kazi.ui.columnsFor
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.PosterCard
import tw.pp.kazi.ui.components.ScreenScaffold
import tw.pp.kazi.ui.gridGap
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.isTv
import tw.pp.kazi.ui.pagePadding

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen() {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val windowSize = LocalWindowSize.current
    val favorites by container.favoriteRepository.items.collectAsState()
    val settings by container.configRepository.settings.collectAsState()
    val incognito by container.incognito.collectAsState()
    val scope = rememberCoroutineScope()

    // TV 進頁面 focus 第一張卡，給 D-pad 一個起點；手機觸控完全不搶焦
    val firstCardFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var pendingFirstFocus by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(favorites) {
        if (!windowSize.isTv) return@LaunchedEffect
        if (pendingFirstFocus && favorites.isNotEmpty()) {
            kotlinx.coroutines.delay(50)
            runCatching { firstCardFocus.requestFocus() }
            pendingFirstFocus = false
        }
    }

    ScreenScaffold(
        title = "我的收藏",
        subtitle = "${favorites.size} 部影片",
        titleBadges = if (incognito) {
            { tw.pp.kazi.ui.components.StatusPill("🕶 無痕（已收藏的仍記進度）") }
        } else null,
        trailing = {
            tw.pp.kazi.ui.components.ConfirmDeleteButton(
                text = "清空",
                icon = Icons.Filled.DeleteSweep,
                onConfirm = { scope.launch { container.favoriteRepository.clear() } },
                enabled = favorites.isNotEmpty(),
                iconOnly = windowSize.isCompact,
            )
        },
        onBack = { nav.popBackStack() },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (favorites.isEmpty()) {
                EmptyState(
                    title = "還沒有收藏",
                    subtitle = "在影片詳情頁按「收藏」把喜歡的片加進來",
                    icon = Icons.Filled.StarBorder,
                )
                return@Column
            }

            val vm = settings.viewMode
            LazyVerticalGrid(
                columns = GridCells.Fixed(vm.columnsFor(windowSize)),
                contentPadding = PaddingValues(
                    horizontal = windowSize.pagePadding(),
                    vertical = 12.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(windowSize.gridGap()),
                verticalArrangement = Arrangement.spacedBy(windowSize.gridGap()),
                modifier = Modifier.fillMaxSize(),
            ) {
                val firstKey = favorites.firstOrNull()?.let { "${it.siteId}-${it.videoId}" }
                items(favorites, key = { "${it.siteId}-${it.videoId}" }) { fav ->
                    val key = "${fav.siteId}-${fav.videoId}"
                    PosterCard(
                        title = fav.videoName,
                        remarks = fav.vodRemarks,
                        imageUrl = fav.videoPic,
                        fromSite = fav.siteName,
                        aspectRatio = vm.aspectRatio,
                        onClick = { nav.navigate(Routes.detail(fav.siteId, fav.videoId)) },
                        focusRequester = if (key == firstKey) firstCardFocus else null,
                    )
                }
            }
        }
    }
}
