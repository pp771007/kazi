package tw.pp.kazi.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import tw.pp.kazi.data.ApiResult
import tw.pp.kazi.data.Category
import tw.pp.kazi.data.Site
import tw.pp.kazi.data.Video
import tw.pp.kazi.data.ViewMode
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.Routes
import tw.pp.kazi.ui.WindowSize
import tw.pp.kazi.ui.columnsFor
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.CollapsibleHeader
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.FocusableTag
import tw.pp.kazi.ui.components.GradientTopBar
import tw.pp.kazi.ui.components.LoadingState
import tw.pp.kazi.ui.components.PosterCard
import tw.pp.kazi.ui.components.rememberCollapsibleHeaderState
import tw.pp.kazi.ui.gridGap
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen() {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val windowSize = LocalWindowSize.current
    val sites by container.siteRepository.sites.collectAsState()
    val settings by container.configRepository.settings.collectAsState()
    val scope = rememberCoroutineScope()

    val enabledSites = remember(sites) { sites.filter { it.enabled }.sortedBy { it.order } }

    var selectedSite by remember { mutableStateOf<Site?>(null) }
    var selectedCategory by remember { mutableStateOf<Category?>(null) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var videos by remember { mutableStateOf<List<Video>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var pageCount by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(enabledSites) {
        if (selectedSite == null && enabledSites.isNotEmpty()) {
            selectedSite = enabledSites.first()
        } else if (enabledSites.none { it.id == selectedSite?.id }) {
            selectedSite = enabledSites.firstOrNull()
        }
    }

    LaunchedEffect(selectedSite, selectedCategory, page, retryKey) {
        val site = selectedSite ?: return@LaunchedEffect
        loading = true
        errorMsg = null
        when (val r = container.macCmsApi.fetchList(
            site = site,
            typeId = selectedCategory?.typeId,
            page = page,
        )) {
            is ApiResult.Success -> {
                videos = r.data.videos
                pageCount = r.data.pageCount.coerceAtLeast(1)
                if (selectedCategory == null && r.data.categories.isNotEmpty()) {
                    categories = r.data.categories
                }
            }
            is ApiResult.Error -> {
                videos = emptyList()
                errorMsg = r.message
            }
        }
        loading = false
    }

    LaunchedEffect(enabledSites.isNotEmpty()) {
        if (enabledSites.isNotEmpty()) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    val headerState = rememberCollapsibleHeaderState()

    CollapsibleHeader(
        state = headerState,
        topBar = {
            GradientTopBar(
                title = "咔滋影院",
                subtitle = selectedSite?.name ?: "請先到設定新增站點",
                trailing = {
                    val compact = windowSize.isCompact
                    AppButton(
                        text = "搜尋",
                        icon = Icons.Filled.Search,
                        onClick = { nav.navigate(Routes.search()) },
                        iconOnly = compact,
                        modifier = Modifier.focusRequester(searchFocusRequester),
                    )
                    ViewModeToggle(
                        current = settings.viewMode,
                        compact = compact,
                        onPick = { scope.launch { container.configRepository.updateViewMode(it) } },
                    )
                    AppButton(
                        text = "收藏",
                        icon = Icons.Filled.Star,
                        onClick = { nav.navigate(Routes.Favorites) },
                        primary = false,
                        iconOnly = compact,
                    )
                    AppButton(
                        text = "歷史",
                        icon = Icons.Filled.History,
                        onClick = { nav.navigate(Routes.History) },
                        primary = false,
                        iconOnly = compact,
                    )
                    AppButton(
                        text = "設定",
                        icon = Icons.Filled.Settings,
                        onClick = { nav.navigate(Routes.Settings) },
                        primary = false,
                        iconOnly = compact,
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (enabledSites.isEmpty()) {
                EmptyState(
                    title = "還沒有啟用的站點",
                    subtitle = "請先到「設定」新增一個 MacCMS 資源站",
                    icon = Icons.Filled.Dns,
                    action = {
                        AppButton(
                            text = "前往站點管理",
                            icon = Icons.Filled.Settings,
                            onClick = { nav.navigate(Routes.Setup) },
                        )
                    },
                )
                return@Column
            }

            SiteStrip(
                sites = enabledSites,
                selected = selectedSite,
                onPick = {
                    selectedSite = it
                    categories = emptyList()
                    selectedCategory = null
                    page = 1
                },
                windowSize = windowSize,
            )

            if (categories.isNotEmpty()) {
                CategoryStrip(
                    categories = categories,
                    selected = selectedCategory,
                    onPick = {
                        selectedCategory = it
                        page = 1
                    },
                    windowSize = windowSize,
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading -> LoadingState()
                    errorMsg != null -> EmptyState(
                        title = "載入失敗",
                        subtitle = errorMsg!!,
                        icon = Icons.Filled.ErrorOutline,
                        action = {
                            AppButton(
                                text = "重試",
                                icon = Icons.Filled.Refresh,
                                onClick = { retryKey += 1 },
                            )
                        },
                    )
                    videos.isEmpty() -> EmptyState(
                        title = "這個分類下沒有內容",
                        subtitle = "試試切換其他分類或搜尋",
                        icon = Icons.Filled.MovieFilter,
                    )
                    else -> VideoGrid(
                        videos = videos,
                        viewMode = settings.viewMode,
                        windowSize = windowSize,
                        onClick = { v ->
                            val site = selectedSite ?: return@VideoGrid
                            nav.navigate(Routes.detail(site.id, v.vodId))
                        },
                    )
                }
            }

            if (pageCount > 1 && !loading) {
                Pager(
                    page = page,
                    pageCount = pageCount,
                    onPrev = { if (page > 1) page-- },
                    onNext = { if (page < pageCount) page++ },
                    windowSize = windowSize,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ViewModeToggle(current: ViewMode, compact: Boolean, onPick: (ViewMode) -> Unit) {
    if (compact) {
        // 手機：單顆按鈕點一次切下一個 mode，省頂列空間
        FocusableTag(
            text = current.emoji,
            selected = false,
            onClick = {
                val entries = ViewMode.entries
                val next = entries[(entries.indexOf(current) + 1) % entries.size]
                onPick(next)
            },
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ViewMode.entries.forEach { mode ->
                FocusableTag(
                    text = mode.emoji,
                    selected = mode == current,
                    onClick = { onPick(mode) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SiteStrip(sites: List<Site>, selected: Site?, onPick: (Site) -> Unit, windowSize: WindowSize) {
    val compact = windowSize.isCompact
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = windowSize.pagePadding(), vertical = if (compact) 4.dp else 6.dp),
    ) {
        if (!compact) {
            Text(
                "站點", color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(sites, key = { it.id }) { s ->
                FocusableTag(
                    text = s.name,
                    selected = s.id == selected?.id,
                    onClick = { onPick(s) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CategoryStrip(
    categories: List<Category>,
    selected: Category?,
    onPick: (Category?) -> Unit,
    windowSize: WindowSize,
) {
    val compact = windowSize.isCompact
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = windowSize.pagePadding(), vertical = if (compact) 2.dp else 4.dp),
    ) {
        if (!compact) {
            Text(
                "分類", color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            item {
                FocusableTag(
                    text = "全部",
                    selected = selected == null,
                    onClick = { onPick(null) },
                )
            }
            items(categories, key = { it.typeId }) { c ->
                FocusableTag(
                    text = c.typeName,
                    selected = selected?.typeId == c.typeId,
                    onClick = { onPick(c) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoGrid(
    videos: List<Video>,
    viewMode: ViewMode,
    windowSize: WindowSize,
    onClick: (Video) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(viewMode.columnsFor(windowSize)),
        contentPadding = PaddingValues(
            horizontal = windowSize.pagePadding(),
            vertical = 12.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(windowSize.gridGap()),
        verticalArrangement = Arrangement.spacedBy(windowSize.gridGap()),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(videos, key = { it.vodId }) { v ->
            PosterCard(
                title = v.vodName,
                remarks = v.vodRemarks,
                imageUrl = v.vodPic,
                fromSite = v.fromSite,
                aspectRatio = viewMode.aspectRatio,
                onClick = { onClick(v) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Pager(page: Int, pageCount: Int, onPrev: () -> Unit, onNext: () -> Unit, windowSize: WindowSize) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = windowSize.pagePadding(), vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppButton(text = "上頁", onClick = onPrev, enabled = page > 1, primary = false)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x22FFFFFF))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                "$page / $pageCount",
                color = AppColors.OnBg,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        AppButton(text = "下頁", onClick = onNext, enabled = page < pageCount, primary = false)
        Spacer(Modifier.weight(1f))
    }
}
