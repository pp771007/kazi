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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.Routes
import tw.pp.kazi.ui.columnsFor
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.CollapsibleHeader
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.GradientTopBar
import tw.pp.kazi.ui.components.PosterCard
import tw.pp.kazi.ui.components.rememberCollapsibleHeaderState
import tw.pp.kazi.ui.gridGap
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.pagePadding

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen() {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val windowSize = LocalWindowSize.current
    val favorites by container.favoriteRepository.items.collectAsState()
    val settings by container.configRepository.settings.collectAsState()
    val scope = rememberCoroutineScope()

    val headerState = rememberCollapsibleHeaderState()

    CollapsibleHeader(
        state = headerState,
        topBar = {
            GradientTopBar(
                title = "我的收藏",
                subtitle = "${favorites.size} 部影片",
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
            )
        },
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
                items(favorites, key = { "${it.siteId}-${it.videoId}" }) { fav ->
                    PosterCard(
                        title = fav.videoName,
                        remarks = fav.vodRemarks,
                        imageUrl = fav.videoPic,
                        fromSite = fav.siteName,
                        aspectRatio = vm.aspectRatio,
                        onClick = { nav.navigate(Routes.detail(fav.siteId, fav.videoId)) },
                    )
                }
            }
        }
    }
}
