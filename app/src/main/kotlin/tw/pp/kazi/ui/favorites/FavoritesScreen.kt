package tw.pp.kazi.ui.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import tw.pp.kazi.data.PosterConfig
import tw.pp.kazi.ui.GridLayout
import tw.pp.kazi.ui.posterLayoutFor
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.PosterCard
import tw.pp.kazi.ui.components.PosterFill
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
            {
                tw.pp.kazi.ui.components.StatusPill(
                    text = "🕶 無痕（已收藏的仍記進度）",
                    onClick = { container.setIncognito(false) },
                )
            }
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

            val layout = posterLayoutFor(windowSize)
            val padding = PaddingValues(horizontal = windowSize.pagePadding(), vertical = 12.dp)
            val gap = windowSize.gridGap()
            val firstKey = favorites.firstOrNull()?.let { "${it.siteId}-${it.videoId}" }

            if (layout.grid == GridLayout.Masonry) {
                val ratios = remember { mutableStateMapOf<String, Float>() }
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(layout.columns),
                    contentPadding = padding,
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalItemSpacing = gap,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    staggeredItems(favorites, key = { "${it.siteId}-${it.videoId}" }) { fav ->
                        val key = "${fav.siteId}-${fav.videoId}"
                        PosterCard(
                            title = fav.videoName,
                            remarks = fav.vodRemarks,
                            imageUrl = fav.videoPic,
                            fromSite = fav.siteName,
                            aspectRatio = ratios[fav.videoPic] ?: PosterConfig.MASONRY_DEFAULT_RATIO,
                            fill = PosterFill.Crop,
                            onRatio = { ratios[fav.videoPic] = it },
                            onClick = { nav.navigate(Routes.detail(fav.siteId, fav.videoId)) },
                            focusRequester = if (key == firstKey) firstCardFocus else null,
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(layout.columns),
                    contentPadding = padding,
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(favorites, key = { "${it.siteId}-${it.videoId}" }) { fav ->
                        val key = "${fav.siteId}-${fav.videoId}"
                        PosterCard(
                            title = fav.videoName,
                            remarks = fav.vodRemarks,
                            imageUrl = fav.videoPic,
                            fromSite = fav.siteName,
                            aspectRatio = layout.cellAspect,
                            fill = layout.fill,
                            onClick = { nav.navigate(Routes.detail(fav.siteId, fav.videoId)) },
                            focusRequester = if (key == firstKey) firstCardFocus else null,
                        )
                    }
                }
            }
        }
    }
}
