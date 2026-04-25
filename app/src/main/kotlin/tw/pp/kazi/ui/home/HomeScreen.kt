package tw.pp.kazi.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
    val lanState by container.lanState.collectAsState()
    val incognito by container.incognito.collectAsState()
    val scope = rememberCoroutineScope()

    val enabledSites = remember(sites) { sites.filter { it.enabled }.sortedBy { it.order } }

    // 從 detail 返回時還原上次狀態（站台、分類、頁碼、清單），避免重新打 API 也不會跳回第一個站台。
    val initialSnapshot = remember { container.homeSnapshot }
    val restoredSite = remember(enabledSites, initialSnapshot) {
        initialSnapshot?.let { snap -> enabledSites.firstOrNull { it.id == snap.selectedSiteId } }
    }
    val restoredCategory = remember(initialSnapshot) {
        initialSnapshot?.let { snap ->
            snap.categories.firstOrNull { it.typeId == snap.selectedCategoryTypeId }
        }
    }

    var selectedSite by remember { mutableStateOf<Site?>(restoredSite) }
    var selectedCategory by remember { mutableStateOf<Category?>(restoredCategory) }
    var categories by remember { mutableStateOf<List<Category>>(initialSnapshot?.categories ?: emptyList()) }
    var videos by remember { mutableStateOf<List<Video>>(initialSnapshot?.videos ?: emptyList()) }
    var page by remember { mutableIntStateOf(initialSnapshot?.page ?: 1) }
    var pageCount by remember { mutableIntStateOf(initialSnapshot?.pageCount ?: 1) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    // 若 snapshot 還原成功，跳過第一次 LaunchedEffect 觸發的 API 抓取
    var skipNextFetch by remember { mutableStateOf(initialSnapshot != null && restoredSite != null) }

    val searchFocusRequester = remember { FocusRequester() }
    val firstVideoFocus = remember { FocusRequester() }
    // 換頁後要把 focus 移到新頁第一張卡，避免 focus 卡在「下一頁」按鈕。
    var pendingFirstVideoFocus by remember { mutableStateOf(false) }

    LaunchedEffect(enabledSites) {
        if (selectedSite == null && enabledSites.isNotEmpty()) {
            selectedSite = enabledSites.first()
        } else if (enabledSites.none { it.id == selectedSite?.id }) {
            selectedSite = enabledSites.firstOrNull()
        }
    }

    LaunchedEffect(selectedSite, selectedCategory, page, retryKey) {
        val site = selectedSite ?: return@LaunchedEffect
        if (skipNextFetch) {
            skipNextFetch = false
            return@LaunchedEffect
        }
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

    DisposableEffect(Unit) {
        onDispose {
            val site = selectedSite ?: return@onDispose
            container.homeSnapshot = tw.pp.kazi.HomeUiSnapshot(
                selectedSiteId = site.id,
                selectedCategoryTypeId = selectedCategory?.typeId,
                categories = categories,
                videos = videos,
                page = page,
                pageCount = pageCount,
            )
        }
    }

    LaunchedEffect(enabledSites.isNotEmpty()) {
        if (enabledSites.isNotEmpty()) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    // 換頁後 videos 會重新載入；只在「新 videos 來」的那一刻 focus 第一張，避免 focus 在舊資料的第一張卡上
    LaunchedEffect(videos) {
        if (pendingFirstVideoFocus && videos.isNotEmpty()) {
            kotlinx.coroutines.delay(50) // 等 LazyVerticalGrid 把第一張 item compose 出來
            runCatching { firstVideoFocus.requestFocus() }
            pendingFirstVideoFocus = false
        }
    }

    val headerState = rememberCollapsibleHeaderState()

    CollapsibleHeader(
        state = headerState,
        topBar = {
            GradientTopBar(
                title = "咔滋影院",
                subtitle = selectedSite?.name ?: "請先到設定新增站點",
                titleBadges = if (incognito || lanState.running) {
                    {
                        if (incognito) {
                            tw.pp.kazi.ui.components.StatusPill(
                                text = "🕶 無痕",
                                onClick = { container.setIncognito(false) },
                            )
                        }
                        if (lanState.running) {
                            tw.pp.kazi.ui.components.StatusPill(
                                text = "🟢 遠端遙控",
                                onClick = {
                                    scope.launch {
                                        container.stopLan()
                                        container.configRepository.updateLanShare(false)
                                    }
                                },
                            )
                        }
                    }
                } else null,
                trailing = {
                    val compact = windowSize.isCompact
                    AppButton(
                        text = "搜尋",
                        icon = Icons.Filled.Search,
                        onClick = { nav.navigate(Routes.search()) },
                        iconOnly = compact,
                        modifier = Modifier.focusRequester(searchFocusRequester),
                    )
                    AppButton(
                        text = "歷史",
                        icon = Icons.Filled.History,
                        onClick = { nav.navigate(Routes.History) },
                        primary = false,
                        iconOnly = compact,
                    )
                    AppButton(
                        text = "收藏",
                        icon = Icons.Filled.Star,
                        onClick = { nav.navigate(Routes.Favorites) },
                        primary = false,
                        iconOnly = compact,
                    )
                    ViewModeToggle(
                        current = settings.viewMode,
                        compact = compact,
                        onPick = { scope.launch { container.configRepository.updateViewMode(it) } },
                    )
                    AppButton(
                        text = if (incognito) "無痕中" else "無痕",
                        icon = Icons.Filled.VisibilityOff,
                        onClick = { container.setIncognito(!incognito) },
                        primary = incognito,
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
                        firstItemFocus = firstVideoFocus,
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
                    onPrev = {
                        if (page > 1) { page--; pendingFirstVideoFocus = true }
                    },
                    onNext = {
                        if (page < pageCount) { page++; pendingFirstVideoFocus = true }
                    },
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
    firstItemFocus: FocusRequester,
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
        itemsIndexed(videos, key = { _, v -> v.vodId }) { idx, v ->
            PosterCard(
                title = v.vodName,
                remarks = v.vodRemarks,
                imageUrl = v.vodPic,
                fromSite = v.fromSite,
                aspectRatio = viewMode.aspectRatio,
                onClick = { onClick(v) },
                focusRequester = if (idx == 0) firstItemFocus else null,
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
