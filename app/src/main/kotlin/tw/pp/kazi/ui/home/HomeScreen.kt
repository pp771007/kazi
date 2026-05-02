package tw.pp.kazi.ui.home

import android.app.Activity
import android.widget.Toast
import tw.pp.kazi.util.Logger
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
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
import androidx.compose.ui.platform.LocalContext
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
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.FocusableTag
import tw.pp.kazi.ui.components.LoadingState
import tw.pp.kazi.ui.components.Pager
import tw.pp.kazi.ui.components.PosterCard
import tw.pp.kazi.ui.components.ScreenScaffold
import tw.pp.kazi.ui.components.ViewModeToggle
import tw.pp.kazi.ui.gridGap
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

private const val BACK_EXIT_WINDOW_MS = 2000L

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen() {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val windowSize = LocalWindowSize.current
    val context = LocalContext.current
    val sites by container.siteRepository.sites.collectAsState()
    val sitesLoaded by container.siteRepository.loaded.collectAsState()
    val settings by container.configRepository.settings.collectAsState()
    val lanState by container.lanState.collectAsState()
    val incognito by container.incognito.collectAsState()
    val scope = rememberCoroutineScope()

    // 首頁是 nav stack 的根，按返回會直接 finish Activity；加雙擊確認避免誤退
    var lastBackTime by remember { mutableLongStateOf(0L) }
    // didExit 旗標：finish() 之後 onDispose 還會跑，會把剛清的 snapshot 又從 selectedSite 寫回去；
    // 設這個 flag 讓 onDispose 知道這次是「主動退出」要跳過儲存
    var didExit by remember { mutableStateOf(false) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackTime < BACK_EXIT_WINDOW_MS) {
            // 使用者主動退出：清掉所有 UI snapshot，讓下次重進是 fresh state
            // （process 沒被殺的話，AppContainer 裡的 snapshot 會殘留 → 站台選擇/搜尋結果都還在）
            didExit = true
            container.homeSnapshot = null
            container.searchSnapshot = null
            container.historyLastFocusKey = null
            container.homeTopBarFocusKey = null
            container.pendingDetailPeers = null
            (context as? Activity)?.finish()
        } else {
            lastBackTime = now
            Toast.makeText(context, "再按一次返回退出", Toast.LENGTH_SHORT).show()
        }
    }

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

    // top bar 各個會跳出本畫面的按鈕都掛一個 requester；返回時 focus 回原本那顆
    val searchFocusRequester = remember { FocusRequester() }
    val historyFocusRequester = remember { FocusRequester() }
    val favoritesFocusRequester = remember { FocusRequester() }
    val lanFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }

    val firstVideoFocus = remember { FocusRequester() }
    val clickedVideoFocus = remember { FocusRequester() }
    // 換頁後要把 focus 移到新頁第一張卡，避免 focus 卡在「下一頁」按鈕。
    var pendingFirstVideoFocus by remember { mutableStateOf(false) }
    // 從 detail 返回時要 focus 在剛才點進去的那張卡，不是 site strip
    val restoreClickedVodId = remember { initialSnapshot?.lastClickedVodId }
    var pendingClickedFocus by remember { mutableStateOf(restoreClickedVodId != null) }
    // 從 settings/history/favorites/lan 等子畫面返回時，focus 回到對應的 top bar 按鈕
    val restoreTopBarKey = remember {
        container.homeTopBarFocusKey.also { container.homeTopBarFocusKey = null }
    }

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
            // 雙擊退出時 BackHandler 已經把 snapshot 清成 null，這裡就不要再寫回去
            if (didExit) return@onDispose
            val site = selectedSite ?: return@onDispose
            container.homeSnapshot = tw.pp.kazi.HomeUiSnapshot(
                selectedSiteId = site.id,
                selectedCategoryTypeId = selectedCategory?.typeId,
                categories = categories,
                videos = videos,
                page = page,
                pageCount = pageCount,
                lastClickedVodId = container.homeSnapshot?.lastClickedVodId,
            )
        }
    }

    LaunchedEffect(enabledSites.isNotEmpty()) {
        // 優先順序：(1) 點過卡片 → 等卡片 focus 回去；(2) 有 top bar key 就 focus 那顆；(3) 預設 focus 搜尋
        if (enabledSites.isNotEmpty() && !pendingClickedFocus) {
            val target = when (restoreTopBarKey) {
                "history" -> historyFocusRequester
                "favorites" -> favoritesFocusRequester
                "lan" -> lanFocusRequester
                "settings" -> settingsFocusRequester
                "search" -> searchFocusRequester
                else -> searchFocusRequester
            }
            runCatching { target.requestFocus() }
        }
    }

    // 換頁後 videos 會重新載入；只在「新 videos 來」的那一刻 focus 第一張
    LaunchedEffect(videos) {
        if (pendingFirstVideoFocus && videos.isNotEmpty()) {
            kotlinx.coroutines.delay(50) // 等 LazyVerticalGrid 把第一張 item compose 出來
            runCatching { firstVideoFocus.requestFocus() }
            pendingFirstVideoFocus = false
        }
    }

    // 從 detail 返回 → focus 剛才點進去的那張卡
    LaunchedEffect(videos) {
        if (pendingClickedFocus && restoreClickedVodId != null && videos.any { it.vodId == restoreClickedVodId }) {
            kotlinx.coroutines.delay(50)
            runCatching { clickedVideoFocus.requestFocus() }
            pendingClickedFocus = false
        }
    }

    ScreenScaffold(
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
                    // Compact (手機直立) 跟 Medium (手機橫躺) 都用 icon-only — 7 顆按鈕全文字會把
                    // 標題列的「無痕 / 遠端遙控」status pill 擠到看不清；Expanded（平板/電視）才顯示文字
                    val compact = windowSize != WindowSize.Expanded
                    AppButton(
                        text = "搜尋",
                        icon = Icons.Filled.Search,
                        onClick = {
                            container.homeTopBarFocusKey = "search"
                            nav.navigate(Routes.search())
                        },
                        iconOnly = compact,
                        modifier = Modifier.focusRequester(searchFocusRequester),
                    )
                    AppButton(
                        text = "歷史",
                        icon = Icons.Filled.History,
                        onClick = {
                            container.homeTopBarFocusKey = "history"
                            nav.navigate(Routes.History)
                        },
                        primary = false,
                        iconOnly = compact,
                        modifier = Modifier.focusRequester(historyFocusRequester),
                    )
                    AppButton(
                        text = "收藏",
                        icon = Icons.Filled.Star,
                        onClick = {
                            container.homeTopBarFocusKey = "favorites"
                            nav.navigate(Routes.Favorites)
                        },
                        primary = false,
                        iconOnly = compact,
                        modifier = Modifier.focusRequester(favoritesFocusRequester),
                    )
                    ViewModeToggle(
                        current = settings.viewMode,
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
                        text = "遠端遙控",
                        icon = Icons.Filled.QrCode2,
                        onClick = {
                            container.homeTopBarFocusKey = "lan"
                            scope.launch {
                                // 一鍵：沒開就順手 enable，但要等 startLan 真的成功才寫 settings + 導頁
                                // 失敗時還是導去 LanShareScreen 讓使用者看到「未啟用」+ 可手動重試
                                if (!lanState.running) {
                                    val ok = container.startLan()
                                    if (ok) {
                                        container.configRepository.updateLanShare(true)
                                    } else {
                                        Logger.w("startLan failed from Home one-tap; navigating to LanShareScreen so user sees the failure")
                                    }
                                }
                                nav.navigate(Routes.LanShare)
                            }
                        },
                        primary = lanState.running,
                        iconOnly = compact,
                        modifier = Modifier.focusRequester(lanFocusRequester),
                    )
                    AppButton(
                        text = "設定",
                        icon = Icons.Filled.Settings,
                        onClick = {
                            container.homeTopBarFocusKey = "settings"
                            nav.navigate(Routes.Settings)
                        },
                primary = false,
                iconOnly = compact,
                modifier = Modifier.focusRequester(settingsFocusRequester),
            )
        },
        onBack = null,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 等站台檔案讀完才判斷 empty，不然 App 一進來會閃一下「還沒有啟用的站點」
            if (!sitesLoaded) {
                LoadingState()
                return@Column
            }
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

            // 站台 / 類別 strip 的捲動位置 hoist 到這層，避免 CategoryStrip 因暫時 categories=[]
            // 從 composition 拔掉再重掛時把 scroll position 弄丟（同樣道理 SiteStrip 在 grid header 重組時也保險）
            val siteStripState = rememberLazyListState()
            val categoryStripState = rememberLazyListState()

            // 站點 / 類別兩條 strip 是「跟著內容捲」的—正常顯示影片時塞進 grid 當 header，
            // loading / 載入失敗 / 該分類沒內容時則保留釘在頂部的舊版（這時使用者反而需要切站台逃出來）
            val strips: @Composable () -> Unit = {
                SiteStrip(
                    sites = enabledSites,
                    selected = selectedSite,
                    onPick = { picked ->
                        // 點到當前站台 no-op；不然命令式清掉 categories 但 LaunchedEffect
                        // 沒任何 key 改變不會 re-fire，類別列表會永久消失
                        if (picked.id == selectedSite?.id) return@SiteStrip
                        selectedSite = picked
                        categories = emptyList()
                        selectedCategory = null
                        page = 1
                        // 切站 → 分類列表會被換掉，舊的捲動位置在新的列表上沒意義，回到開頭
                        scope.launch { categoryStripState.scrollToItem(0) }
                    },
                    windowSize = windowSize,
                    listState = siteStripState,
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
                        listState = categoryStripState,
                    )
                }
            }

            val err = errorMsg
            val showVideos = !loading && err == null && videos.isNotEmpty()

            if (!showVideos) {
                strips()
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading -> LoadingState()
                        err != null -> EmptyState(
                            title = "載入失敗",
                            subtitle = err,
                            icon = Icons.Filled.ErrorOutline,
                            action = {
                                AppButton(
                                    text = "重試",
                                    icon = Icons.Filled.Refresh,
                                    onClick = { retryKey += 1 },
                                )
                            },
                        )
                        else -> EmptyState(
                            title = "這個分類下沒有內容",
                            subtitle = "試試切換其他分類或搜尋",
                            icon = Icons.Filled.MovieFilter,
                        )
                    }
                }
            } else {
                VideoGrid(
                    videos = videos,
                    viewMode = settings.viewMode,
                    windowSize = windowSize,
                    firstItemFocus = firstVideoFocus,
                    clickedVodId = restoreClickedVodId,
                    clickedItemFocus = clickedVideoFocus,
                    onClick = { v ->
                        val site = selectedSite ?: return@VideoGrid
                        // 記下這次點的 vodId，從 detail 返回時 focus 會自動回到這張
                        container.homeSnapshot = container.homeSnapshot?.copy(lastClickedVodId = v.vodId)
                            ?: tw.pp.kazi.HomeUiSnapshot(
                                selectedSiteId = site.id,
                                selectedCategoryTypeId = selectedCategory?.typeId,
                                categories = categories,
                                videos = videos,
                                page = page,
                                pageCount = pageCount,
                                lastClickedVodId = v.vodId,
                            )
                        nav.navigate(Routes.detail(site.id, v.vodId))
                    },
                    header = {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column { strips() }
                        }
                    },
                    footer = if (pageCount > 1) {
                        {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Pager(
                                    page = page,
                                    pageCount = pageCount,
                                    onPrev = {
                                        if (page > 1) { page--; pendingFirstVideoFocus = true }
                                    },
                                    onNext = {
                                        if (page < pageCount) { page++; pendingFirstVideoFocus = true }
                                    },
                                    onJump = { target ->
                                        page = target
                                        pendingFirstVideoFocus = true
                                    },
                                    windowSize = windowSize,
                                )
                            }
                        }
                    } else null,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SiteStrip(
    sites: List<Site>,
    selected: Site?,
    onPick: (Site) -> Unit,
    windowSize: WindowSize,
    listState: LazyListState,
) {
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
            state = listState,
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
    listState: LazyListState,
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
            state = listState,
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
    clickedVodId: Long?,
    clickedItemFocus: FocusRequester,
    onClick: (Video) -> Unit,
    header: (LazyGridScope.() -> Unit)? = null,
    footer: (LazyGridScope.() -> Unit)? = null,
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
        header?.invoke(this)
        itemsIndexed(videos, key = { _, v -> v.vodId }) { idx, v ->
            PosterCard(
                title = v.vodName,
                remarks = v.vodRemarks,
                imageUrl = v.vodPic,
                fromSite = v.fromSite,
                aspectRatio = viewMode.aspectRatio,
                onClick = { onClick(v) },
                focusRequester = when {
                    clickedVodId != null && v.vodId == clickedVodId -> clickedItemFocus
                    idx == 0 -> firstItemFocus
                    else -> null
                },
            )
        }
        footer?.invoke(this)
    }
}
