package tw.pp.kazi.ui.home

import android.app.Activity
import android.widget.Toast
import tw.pp.kazi.util.Logger
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import tw.pp.kazi.ui.components.PullToRefreshBoxIfCompact
import tw.pp.kazi.ui.components.ScreenScaffold
import tw.pp.kazi.ui.components.ViewModeToggle
import tw.pp.kazi.ui.components.rememberScreenSnapshot
import tw.pp.kazi.ui.gridGap
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.isTv
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

    val homeSnap = rememberScreenSnapshot("home")

    // 首頁是 nav stack 的根，按返回會直接 finish Activity；加雙擊確認避免誤退
    var lastBackTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBackTime < BACK_EXIT_WINDOW_MS) {
            // 使用者主動退出：清掉所有 UI snapshot，讓下次重進是 fresh state
            // （process 沒被殺的話，AppContainer 裡的 snapshot 會殘留 → 站台選擇/搜尋結果都還在）
            homeSnap.discard()
            container.clearSnapshot("search")
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
    // ScreenSnapshot 在進畫面時自動讀回上次值，離開時自動寫回；不必手寫 onDispose。
    var selectedSite by homeSnap.state<Site?>("selectedSite") { null }
    var selectedCategory by homeSnap.state<Category?>("selectedCategory") { null }
    var categories by homeSnap.state<List<Category>>("categories") { emptyList() }
    var videos by homeSnap.state<List<Video>>("videos") { emptyList() }
    var page by homeSnap.state("page") { 1 }
    var pageCount by homeSnap.state("pageCount") { 1 }
    var lastClickedVodId by homeSnap.state<Long?>("lastClickedVodId") { null }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    // 若 snapshot 還原成功（selectedSite 在進畫面時就有值），跳過第一次 LaunchedEffect 的 API 抓取
    var skipNextFetch by remember { mutableStateOf(selectedSite != null) }

    // top bar 各個會跳出本畫面的按鈕都掛一個 requester；返回時 focus 回原本那顆
    val searchFocusRequester = remember { FocusRequester() }
    val historyFocusRequester = remember { FocusRequester() }
    val favoritesFocusRequester = remember { FocusRequester() }
    val lanFocusRequester = remember { FocusRequester() }
    val settingsFocusRequester = remember { FocusRequester() }

    val firstVideoFocus = remember { FocusRequester() }
    val clickedVideoFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    // grid 最後一列卡片按↓的 redirect 目標 — 掛在 footer Pager 的「下一頁」上
    val nextPageFocus = remember { FocusRequester() }
    // D-pad 垂直流向：top bar ↓ → 站點 strip → 分類 strip → 影片格。每一段明確指定下一段的落點，
    // 不交給 Compose 空間搜尋自己猜（靠右的按鈕按↓會跳過靠左的列）。掛在各 strip「當前選中」那顆上。
    val siteStripFocus = remember { FocusRequester() }
    val categoryStripFocus = remember { FocusRequester() }
    // 切站、切分類、換頁後要把 focus 移到新內容（成功 → 第一張卡；失敗 → 重試按鈕），
    // 避免 focus 卡在剛剛點過的 strip tag 或「下一頁」按鈕上。
    var pendingContentFocus by remember { mutableStateOf(false) }
    // 從 detail 返回時要 focus 在剛才點進去的那張卡，不是 site strip。
    // 進畫面那一瞬間的 lastClickedVodId 即「上次點過的卡」；之後 onClick 會更新成新值，
    // 但 restoreClickedVodId 已經 capture 起來，繼續代表「這次進畫面要 focus 的那張」
    val restoreClickedVodId = remember { lastClickedVodId }
    var pendingClickedFocus by remember { mutableStateOf(restoreClickedVodId != null) }
    // 從 settings/history/favorites/lan 等子畫面返回時，focus 回到對應的 top bar 按鈕
    val restoreTopBarKey = remember {
        container.homeTopBarFocusKey.also { container.homeTopBarFocusKey = null }
    }

    LaunchedEffect(enabledSites) {
        if (selectedSite == null && enabledSites.isNotEmpty()) {
            selectedSite = enabledSites.first()
            // snapshot 沒給 site 的情況；要打 API 抓新 site 的清單
            skipNextFetch = false
        } else if (enabledSites.isNotEmpty() && enabledSites.none { it.id == selectedSite?.id }) {
            // snapshot 裡的 Site 已被刪除（幽靈），換成第一個可用站台 → 重 fetch
            selectedSite = enabledSites.firstOrNull()
            skipNextFetch = false
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

    LaunchedEffect(enabledSites.isNotEmpty()) {
        // 優先順序：(1) 點過卡片 → 等卡片 focus 回去；(2) 有 top bar key 就 focus 那顆；(3) 預設 focus 搜尋
        // 只在 TV (Expanded) 主動搶焦；手機觸控不需要 visible focus 起點
        if (windowSize.isTv && enabledSites.isNotEmpty() && !pendingClickedFocus) {
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

    // 切站／切分類／換頁觸發後，pendingContentFocus=true。等 loading 結束、依結果把 focus 拉到對應元素：
    //   成功有片 → 第一張卡；失敗 → 重試按鈕；空清單但無 error → 不動
    LaunchedEffect(loading, videos, errorMsg) {
        if (!windowSize.isTv || loading || !pendingContentFocus) return@LaunchedEffect
        kotlinx.coroutines.delay(50) // 等對應元素 compose 出來
        when {
            errorMsg != null -> runCatching { retryFocus.requestFocus() }
            videos.isNotEmpty() -> runCatching { firstVideoFocus.requestFocus() }
            else -> return@LaunchedEffect // 空清單沒 retry 按鈕，保留原 focus；保持 flag 等下次重抓
        }
        pendingContentFocus = false
    }

    // 從 detail 返回 → focus 剛才點進去的那張卡
    LaunchedEffect(videos) {
        if (windowSize.isTv && pendingClickedFocus && restoreClickedVodId != null && videos.any { it.vodId == restoreClickedVodId }) {
            kotlinx.coroutines.delay(50)
            runCatching { clickedVideoFocus.requestFocus() }
            pendingClickedFocus = false
        }
    }

    // 自己持有 headerState：換頁時 LazyGrid items 被新 key 替換、scrollState 隱性回 0，
    // 但 bringIntoView 對首張卡（已在頂端）不需 scroll → 沒 dispatch 給 nestedScroll →
    // header 卡在收合。使用者在頂端拉下會被 overscroll 吞掉、沒機會 expand header。
    // 主動在換頁時把 offsetPx 重設成 0（完整展開）
    val headerState = tw.pp.kazi.ui.components.rememberCollapsibleHeaderState()
    LaunchedEffect(page) {
        headerState.offsetPx.floatValue = 0f
    }

    // 站台 / 類別 strip 的捲動位置 hoist 到這層：一來避免 CategoryStrip 因暫時 categories=[] 從
    // composition 拔掉再重掛時把 scroll position 弄丟；二來下面要靠它判斷「選中項現在是否 visible」。
    val siteStripState = rememberLazyListState()
    val categoryStripState = rememberLazyListState()

    // D-pad ↓ 落點（只在 TV、且目標那一列「確定 render 且 requester 已掛上」時才指；
    // 否則 null = 維持原本空間搜尋，絕不把焦點導到不存在的元素造成卡死）
    val videosShown = !loading && errorMsg == null && videos.isNotEmpty()
    // siteStripFocus 掛在「選中站台」那顆上 → 必須 selectedSite 真的在清單內（排除幽靈站台那一瞬間沒人掛）
    val siteStripAttached = enabledSites.any { it.id == selectedSite?.id }
    // 但「在清單內」不等於「已 compose」：LazyRow 會把捲到畫面外的 item 虛擬化拔掉，它掛的
    // FocusRequester 就沒有對應節點。focusProperties 的 up/down 指到這種沒掛上的 requester，D-pad 一移動
    // Compose 解析落點時會 check 失敗直接 crash（站台選左、分類選右時分類「全部」被捲出畫面就會中）。
    // 所以「選中項落點」再加一道：它真的 visible 時才指，否則退回 null（空間搜尋，相鄰列仍會正確落下）。
    val siteSelectedVisible by remember {
        derivedStateOf {
            val id = selectedSite?.id
            id != null && siteStripState.layoutInfo.visibleItemsInfo.any { it.key == id }
        }
    }
    val categorySelectedVisible by remember {
        derivedStateOf {
            val sel = selectedCategory
            if (sel == null) {
                // 「全部」是 index 0 的無 key item
                categoryStripState.layoutInfo.visibleItemsInfo.any { it.index == 0 }
            } else {
                categoryStripState.layoutInfo.visibleItemsInfo.any { it.key == sel.typeId }
            }
        }
    }
    // firstVideoFocus 掛在第一張卡上，但「第一張卡剛好是上次點過的卡」時 VideoGrid 會改掛 clickedItemFocus
    // → 這種情況 firstVideoFocus 沒人掛，不能當落點
    val firstVideoAttached = videosShown &&
        (restoreClickedVodId == null || videos.firstOrNull()?.vodId != restoreClickedVodId)
    val topBarDown = if (windowSize.isTv && siteStripAttached && siteSelectedVisible) siteStripFocus else null
    val siteStripDown = if (windowSize.isTv) when {
        categories.isNotEmpty() && categorySelectedVisible -> categoryStripFocus
        firstVideoAttached -> firstVideoFocus
        else -> null
    } else null
    val categoryStripDown = if (windowSize.isTv && firstVideoAttached) firstVideoFocus else null
    // ↑ 方向（對稱補上，否則從靠右元素往上一樣會跳過靠左的中間列）：
    // 站台列↑→top bar 的「搜尋」鈕（永遠存在）；分類列↑→當前選中站台
    val siteStripUp = if (windowSize.isTv) searchFocusRequester else null
    val categoryStripUp = if (windowSize.isTv && siteStripAttached && siteSelectedVisible) siteStripFocus else null
    val topBarDownMod = if (topBarDown != null) {
        Modifier.focusProperties { down = topBarDown }
    } else Modifier

    ScreenScaffold(
        title = "咔滋影院",
        subtitle = selectedSite?.name ?: "請先到設定新增站點",
        headerState = headerState,
        titleBadges = if (incognito || lanState.running) {
            {
                // 時鐘已由 GradientTopBar 在電視盒每頁統一顯示，這裡只留無痕 / 區網狀態膠囊
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
                        modifier = Modifier.focusRequester(searchFocusRequester).then(topBarDownMod),
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
                        modifier = Modifier.focusRequester(historyFocusRequester).then(topBarDownMod),
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
                        modifier = Modifier.focusRequester(favoritesFocusRequester).then(topBarDownMod),
                    )
                    ViewModeToggle(
                        current = settings.viewMode,
                        onPick = { scope.launch { container.configRepository.updateViewMode(it) } },
                        modifier = topBarDownMod,
                    )
                    AppButton(
                        text = if (incognito) "無痕中" else "無痕",
                        icon = Icons.Filled.VisibilityOff,
                        onClick = { container.setIncognito(!incognito) },
                        primary = incognito,
                        iconOnly = compact,
                        modifier = topBarDownMod,
                    )
                    AppButton(
                        text = "遠端遙控",
                        icon = Icons.Filled.QrCode2,
                        onClick = {
                            scope.launch {
                                if (lanState.running) {
                                    // 開著時再點一次：直接關掉，省一次「進頁面→按停止」的操作
                                    container.stopLan()
                                    container.configRepository.updateLanShare(false)
                                } else {
                                    // 沒開：順手 enable，成功才寫 settings + 導頁。
                                    // 失敗仍導去 LanShareScreen 讓使用者看到「未啟用」+ 可手動重試
                                    container.homeTopBarFocusKey = "lan"
                                    val ok = container.startLan()
                                    if (ok) {
                                        container.configRepository.updateLanShare(true)
                                    } else {
                                        Logger.w("startLan failed from Home one-tap; navigating to LanShareScreen so user sees the failure")
                                    }
                                    nav.navigate(Routes.LanShare)
                                }
                            }
                        },
                        primary = lanState.running,
                        iconOnly = compact,
                        modifier = Modifier.focusRequester(lanFocusRequester).then(topBarDownMod),
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
                modifier = Modifier.focusRequester(settingsFocusRequester).then(topBarDownMod),
            )
        },
        onBack = null,
    ) { innerPadding ->
        PullToRefreshBoxIfCompact(
            isRefreshing = loading,
            onRefresh = {
                // 同步先把 loading 設為 true，PTR 指示器才不會在 LaunchedEffect 還沒跑前就消失
                loading = true
                retryKey += 1
            },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
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

            // 站點 / 類別 strip 固定釘在頂部，不再隨 loading / showVideos 切換在「grid header」跟
            // 「直接 Column 子層」之間搬家。原本搬家會把 SiteStrip 卸載再重掛，剛點過的 site tag
            // 失去 focus，Compose 把焦點 fallback 到 top bar 的「無痕」按鈕。
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
                    pendingContentFocus = true
                    // 切站 → 分類列表會被換掉，舊的捲動位置在新的列表上沒意義，回到開頭
                    scope.launch { categoryStripState.scrollToItem(0) }
                },
                windowSize = windowSize,
                listState = siteStripState,
                selectedFocus = siteStripFocus,
                downTarget = siteStripDown,
                upTarget = siteStripUp,
            )
            if (categories.isNotEmpty()) {
                CategoryStrip(
                    categories = categories,
                    selected = selectedCategory,
                    onPick = {
                        if (it?.typeId == selectedCategory?.typeId) return@CategoryStrip
                        selectedCategory = it
                        page = 1
                        pendingContentFocus = true
                    },
                    windowSize = windowSize,
                    listState = categoryStripState,
                    selectedFocus = categoryStripFocus,
                    downTarget = categoryStripDown,
                    upTarget = categoryStripUp,
                )
            }

            val err = errorMsg
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
                                onClick = {
                                    pendingContentFocus = true
                                    retryKey += 1
                                },
                                modifier = Modifier.focusRequester(retryFocus),
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
                        clickedVodId = restoreClickedVodId,
                        clickedItemFocus = clickedVideoFocus,
                        onClick = { v ->
                            val site = selectedSite ?: return@VideoGrid
                            // 記下這次點的 vodId，從 detail 返回時 focus 會自動回到這張卡
                            lastClickedVodId = v.vodId
                            nav.navigate(Routes.detail(site.id, v.vodId))
                        },
                        nextPageRequester = nextPageFocus,
                        canGoNextPage = pageCount > 1 && page < pageCount,
                        footer = if (pageCount > 1) {
                            {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Pager(
                                        page = page,
                                        pageCount = pageCount,
                                        onPrev = {
                                            if (page > 1) { page--; pendingContentFocus = true }
                                        },
                                        onNext = {
                                            if (page < pageCount) { page++; pendingContentFocus = true }
                                        },
                                        onJump = { target ->
                                            page = target
                                            pendingContentFocus = true
                                        },
                                        windowSize = windowSize,
                                        nextPageRequester = nextPageFocus,
                                    )
                                }
                            }
                        } else null,
                    )
                }
            }
        }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SiteStrip(
    sites: List<Site>,
    selected: Site?,
    onPick: (Site) -> Unit,
    windowSize: WindowSize,
    listState: LazyListState,
    // 由 HomeScreen hoist 進來：掛在「當前選中站台」那顆上，當 top bar↓ 的落點 + restorer fallback
    selectedFocus: FocusRequester,
    // 這一列任一顆按↓/↑的固定落點（↓=分類 strip / 第一張卡；↑=top bar）；null = 維持原本空間搜尋
    downTarget: FocusRequester? = null,
    upTarget: FocusRequester? = null,
) {
    val compact = windowSize.isCompact
    // TV：D-pad 進入這列時，focus 應該停在當前選中的站台，不是離卡片最近的那顆。
    // restorer 第一次進來用 fallback（指向 selected 的 requester），之後記住使用者最後 focus 的位置
    val dirMod = if (downTarget != null || upTarget != null) {
        Modifier.focusProperties {
            if (downTarget != null) down = downTarget
            if (upTarget != null) up = upTarget
        }
    } else Modifier
    val firstFocus = remember { FocusRequester() }
    val lastFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val firstId = sites.firstOrNull()?.id
    val lastId = sites.lastOrNull()?.id
    val selectedId = selected?.id
    // 循環 focus 目標：first 按 ← 要去 last。如果 last 同時也是 selected，請求 selectedFocus；否則 lastFocus。
    // last 按 → 要去 first，同理
    val cycleTargetForFirst = if (lastId != null && lastId == selectedId) selectedFocus else lastFocus
    val cycleTargetForLast = if (firstId != null && firstId == selectedId) selectedFocus else firstFocus
    // selectedFocus 掛在「選中站台」那顆上。但啟動瞬間 selectedSite 還是 null（稍後 LaunchedEffect 才設），
    // 或選中項被 LazyRow 捲出畫面虛擬化時，這顆 requester 就沒掛在任何節點上。focusRestorer 直接回它的話，
    // Compose 觸發 restore → requestFocus → 「FocusRequester is not initialized」直接 crash（電視盒啟動閃退的元兇）。
    // 所以「選中項真的可見」時才用它，否則回 Default（交給預設行為，不會崩）。
    val selectedVisible by remember {
        derivedStateOf {
            selectedId != null && listState.layoutInfo.visibleItemsInfo.any { it.key == selectedId }
        }
    }
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
            // focusGroup：讓 Compose 把整個 strip 當一個 focus 集合，按下時整組離開到下一組
            // (category strip 或 video grid)，不會因 spatial 距離計算跳過 category 直接到 video card
            modifier = Modifier
                .focusGroup()
                .focusRestorer { if (selectedVisible) selectedFocus else FocusRequester.Default },
        ) {
            items(sites, key = { it.id }) { s ->
                val isSelected = s.id == selectedId
                val isFirst = s.id == firstId
                val isLast = s.id == lastId
                // 一個 item 只能掛一個 FocusRequester（chain 多次只用最後一個），優先 selected > first > last
                val itemRequester: FocusRequester? = when {
                    isSelected -> selectedFocus
                    isFirst -> firstFocus
                    isLast -> lastFocus
                    else -> null
                }
                val reqMod = if (itemRequester != null) Modifier.focusRequester(itemRequester) else Modifier
                // 循環：first 按 ← 跳到 last requester，last 按 → 跳到 first。先 scroll 確保對端 composed，再 requestFocus
                val keyMod = if (isFirst || isLast) Modifier.onPreviewKeyEvent { ke ->
                    if (ke.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        isFirst && ke.key == Key.DirectionLeft -> {
                            scope.launch {
                                listState.scrollToItem(sites.size - 1)
                                runCatching { cycleTargetForFirst.requestFocus() }
                            }
                            true
                        }
                        isLast && ke.key == Key.DirectionRight -> {
                            scope.launch {
                                listState.scrollToItem(0)
                                runCatching { cycleTargetForLast.requestFocus() }
                            }
                            true
                        }
                        else -> false
                    }
                } else Modifier
                FocusableTag(
                    text = s.name,
                    selected = isSelected,
                    onClick = { onPick(s) },
                    modifier = reqMod.then(keyMod).then(dirMod),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CategoryStrip(
    categories: List<Category>,
    selected: Category?,
    onPick: (Category?) -> Unit,
    windowSize: WindowSize,
    listState: LazyListState,
    // 由 HomeScreen hoist 進來：掛在「當前分類（或全部）」那顆上，當站點 strip↓ 的落點 + restorer fallback
    selectedFocus: FocusRequester,
    // 這一列任一顆按↓/↑的固定落點（↓=第一張卡；↑=站台 strip）；null = 維持原本空間搜尋
    downTarget: FocusRequester? = null,
    upTarget: FocusRequester? = null,
) {
    val compact = windowSize.isCompact
    val dirMod = if (downTarget != null || upTarget != null) {
        Modifier.focusProperties {
            if (downTarget != null) down = downTarget
            if (upTarget != null) up = upTarget
        }
    } else Modifier
    val firstFocus = remember { FocusRequester() }  // 「全部」永遠在 first 位置
    val lastFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val isAllSelected = selected == null
    val lastCategoryId = categories.lastOrNull()?.typeId
    val isLastCategorySelected = lastCategoryId != null && selected?.typeId == lastCategoryId
    // 「全部」按 ← → 跳 last category（若 last 是 selected 則用 selectedFocus）
    val cycleTargetForFirst = if (isLastCategorySelected) selectedFocus else lastFocus
    // last category 按 → → 跳「全部」（若「全部」是 selected 則用 selectedFocus）
    val cycleTargetForLast = if (isAllSelected) selectedFocus else firstFocus
    // 列表總長度（含「全部」項）：scroll 用得到
    val totalItems = categories.size + 1
    // 同 SiteStrip：選中分類被捲出畫面時 selectedFocus 沒掛上，focusRestorer 直接回它會 crash。
    // 「全部」是 index 0 的無 key item，其餘用 typeId 當 key 判斷可見。
    val selectedVisible by remember {
        derivedStateOf {
            val sel = selected
            if (sel == null) listState.layoutInfo.visibleItemsInfo.any { it.index == 0 }
            else listState.layoutInfo.visibleItemsInfo.any { it.key == sel.typeId }
        }
    }
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
            // focusGroup：見 SiteStrip 同款註解；focusRestorer 讓 D-pad 進這列時 focus 落在當前分類
            modifier = Modifier
                .focusGroup()
                .focusRestorer { if (selectedVisible) selectedFocus else FocusRequester.Default },
        ) {
            item {
                val itemRequester = if (isAllSelected) selectedFocus else firstFocus
                FocusableTag(
                    text = "全部",
                    selected = isAllSelected,
                    onClick = { onPick(null) },
                    modifier = Modifier
                        .focusRequester(itemRequester)
                        .onPreviewKeyEvent { ke ->
                            if (ke.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (ke.key == Key.DirectionLeft && categories.isNotEmpty()) {
                                scope.launch {
                                    listState.scrollToItem(totalItems - 1)
                                    runCatching { cycleTargetForFirst.requestFocus() }
                                }
                                true
                            } else false
                        }
                        .then(dirMod),
                )
            }
            items(categories, key = { it.typeId }) { c ->
                val isSelected = selected?.typeId == c.typeId
                val isLast = c.typeId == lastCategoryId
                val itemRequester: FocusRequester? = when {
                    isSelected -> selectedFocus
                    isLast -> lastFocus
                    else -> null
                }
                val reqMod = if (itemRequester != null) Modifier.focusRequester(itemRequester) else Modifier
                val keyMod = if (isLast) Modifier.onPreviewKeyEvent { ke ->
                    if (ke.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (ke.key == Key.DirectionRight) {
                        scope.launch {
                            listState.scrollToItem(0)
                            runCatching { cycleTargetForLast.requestFocus() }
                        }
                        true
                    } else false
                } else Modifier
                FocusableTag(
                    text = c.typeName,
                    selected = isSelected,
                    onClick = { onPick(c) },
                    modifier = reqMod.then(keyMod).then(dirMod),
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
    nextPageRequester: FocusRequester? = null,
    canGoNextPage: Boolean = false,
    footer: (LazyGridScope.() -> Unit)? = null,
) {
    val columns = viewMode.columnsFor(windowSize)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(
            horizontal = windowSize.pagePadding(),
            vertical = 12.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(windowSize.gridGap()),
        verticalArrangement = Arrangement.spacedBy(windowSize.gridGap()),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(videos, key = { _, v -> v.vodId }) { idx, v ->
            // 最後一列卡片：按↓強制 focus 到「下一頁」。focusProperties.down 是 stable API，
            // 只在 spatial down 觸發、不會干擾 OK / Enter 點擊（v0.5.68–73 的 focusGroup 坑就是因為
            // group container 攔了 click event）。下一頁 disabled 時不掛，避免無效 redirect 焦點卡死
            val isLastRow = idx >= videos.size - columns
            val downModifier = if (isLastRow && nextPageRequester != null && canGoNextPage) {
                Modifier.focusProperties { down = nextPageRequester }
            } else Modifier
            PosterCard(
                title = v.vodName,
                remarks = v.vodRemarks,
                imageUrl = v.vodPic,
                fromSite = v.fromSite,
                aspectRatio = viewMode.aspectRatio,
                onClick = { onClick(v) },
                modifier = downModifier,
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

