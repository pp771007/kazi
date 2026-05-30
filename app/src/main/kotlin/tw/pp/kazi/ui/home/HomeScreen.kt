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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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
import tw.pp.kazi.data.PosterConfig
import tw.pp.kazi.data.Video
import tw.pp.kazi.ui.GridLayout
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.Routes
import tw.pp.kazi.ui.WindowSize
import tw.pp.kazi.ui.keyScrollFocus
import tw.pp.kazi.ui.posterLayoutFor
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.FocusableTag
import tw.pp.kazi.ui.components.HorizontalPageSwipe
import tw.pp.kazi.ui.components.pageSwipeIgnore
import tw.pp.kazi.ui.components.LoadingState
import tw.pp.kazi.ui.components.Pager
import tw.pp.kazi.ui.components.PosterCard
import tw.pp.kazi.ui.components.PosterFill
import tw.pp.kazi.ui.components.PullToRefreshBoxIfCompact
import tw.pp.kazi.ui.components.ScreenScaffold
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
    // 影片格最後一列按↓時,跳到分頁的「下一頁」(掛在這顆上)。用 key 事件 + runCatching 觸發,安全不 ANR。
    val nextPageFocus = remember { FocusRequester() }
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

    // 站台 / 類別 strip 的捲動位置 hoist 到這層：避免 CategoryStrip 因暫時 categories=[] 從
    // composition 拔掉再重掛時把 scroll position 弄丟。
    val siteStripState = rememberLazyListState()
    val categoryStripState = rememberLazyListState()

    // D-pad 列間移動全交給 focusGroup + focusRestorer + 框架空間搜尋（見各 strip 的 focusGroup()）。
    // 不再手動指定上下落點 —— 以前那套手動落點 + 守衛正是「跳過分類」與「指到沒掛上的 requester 閃退」的病根，
    // 實機(模擬器)驗證拿掉後上下移動全對、記住上次位置、虛擬化也不崩。

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

            val err = errorMsg

            // 站點 / 分類列。永遠當影片格 LazyGrid 的 header 一起捲動,不再依 loading 切到別段排版 →
            // header 全程不被 unmount → 點下去焦點留在 chip、focusRestorer 記得選的那顆、往上回得來。
            @Composable
            fun Strips() {
                // 站點 / 分類列自己會水平捲動 → 標記為換頁手勢排除區,左右滑不從這兩列觸發換頁。
                Column(modifier = Modifier.fillMaxWidth().pageSwipeIgnore("home-strips")) {
                    SiteStrip(
                        sites = enabledSites,
                        selected = selectedSite,
                        onPick = { picked ->
                            if (picked.id == selectedSite?.id) return@SiteStrip
                            selectedSite = picked
                            categories = emptyList()
                            selectedCategory = null
                            page = 1
                            pendingContentFocus = true
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
                                if (it?.typeId == selectedCategory?.typeId) return@CategoryStrip
                                selectedCategory = it
                                page = 1
                                pendingContentFocus = true
                            },
                            windowSize = windowSize,
                            listState = categoryStripState,
                        )
                    }
                }
            }

            // 載入 / 錯誤 / 空清單 → 畫進影片格內容區(statusContent);有片則 null,正常顯示影片卡。
            // 三者都讓 Strips 留在同一個 grid header,不會被 unmount → 焦點/記憶不丟。
            val statusContent: (@Composable () -> Unit)? = when {
                loading -> { { LoadingState() } }
                err != null -> {
                    {
                        EmptyState(
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
                    }
                }
                videos.isEmpty() -> {
                    {
                        EmptyState(
                            title = "這個分類下沒有內容",
                            subtitle = "試試切換其他分類或搜尋",
                            icon = Icons.Filled.MovieFilter,
                        )
                    }
                }
                else -> null
            }
            val videosShown = statusContent == null

            // 手機(非電視)左右滑換頁;電視盒不攔(用 D-pad + 分頁鈕)。載入/空/錯誤時不換頁。
            HorizontalPageSwipe(
                enabled = !windowSize.isTv && videosShown && pageCount > 1,
                canPrev = page > 1,
                canNext = page < pageCount,
                onPrev = { if (page > 1) { page--; pendingContentFocus = true } },
                onNext = { if (page < pageCount) { page++; pendingContentFocus = true } },
                modifier = Modifier.weight(1f),
            ) {
                VideoGrid(
                    header = { Strips() },
                    statusContent = statusContent,
                    videos = videos,
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
                    nextPageRequester = if (videosShown && pageCount > 1 && page < pageCount) nextPageFocus else null,
                    footerContent = if (videosShown && pageCount > 1) {
                        {
                            Pager(
                                page = page,
                                pageCount = pageCount,
                                onPrev = { if (page > 1) { page--; pendingContentFocus = true } },
                                onNext = { if (page < pageCount) { page++; pendingContentFocus = true } },
                                onJump = { target -> page = target; pendingContentFocus = true },
                                windowSize = windowSize,
                                nextPageRequester = nextPageFocus,
                            )
                        }
                    } else null,
                )
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
) {
    val compact = windowSize.isCompact
    // TV：D-pad 第一次進這列(沒記錄)→ 停第一顆;之後 focusRestorer 自動記住上次位置、回上次。
    // 列間上下移動交給框架空間搜尋,不手動指落點(見檔頭 CLAUDE.md 焦點守則)
    val firstFocus = remember { FocusRequester() }
    val lastFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val firstId = sites.firstOrNull()?.id
    val lastId = sites.lastOrNull()?.id
    val selectedId = selected?.id
    // 循環：first 按 ← 去 last、last 按 → 去 first。
    val cycleTargetForFirst = lastFocus
    val cycleTargetForLast = firstFocus
    // 第一顆可見才把 fallback 指它(避免被捲出畫面虛擬化時 requestFocus 沒掛上的 requester 而 crash)。
    val firstVisible by remember {
        derivedStateOf {
            firstId != null && listState.layoutInfo.visibleItemsInfo.any { it.key == firstId }
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
                .focusRestorer { if (firstVisible) firstFocus else FocusRequester.Default },
        ) {
            items(sites, key = { it.id }) { s ->
                val isSelected = s.id == selectedId
                val isFirst = s.id == firstId
                val isLast = s.id == lastId
                val itemRequester: FocusRequester? = when {
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
                    modifier = reqMod.then(keyMod),
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
) {
    val compact = windowSize.isCompact
    val firstFocus = remember { FocusRequester() }  // 「全部」永遠在 first 位置
    val lastFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val isAllSelected = selected == null
    val lastCategoryId = categories.lastOrNull()?.typeId
    // 循環：「全部」按 ← 去 last、last 按 → 去「全部」。
    val cycleTargetForFirst = lastFocus
    val cycleTargetForLast = firstFocus
    // 列表總長度（含「全部」項）：scroll 用得到
    val totalItems = categories.size + 1
    // 第一次進這列(沒記錄)→ 停「全部」(第一顆)。「全部」是 index 0 的無 key item,可見才指它。
    val firstVisible by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.index == 0 } }
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
                .focusRestorer { if (firstVisible) firstFocus else FocusRequester.Default },
        ) {
            item {
                FocusableTag(
                    text = "全部",
                    selected = isAllSelected,
                    onClick = { onPick(null) },
                    modifier = Modifier
                        .focusRequester(firstFocus)
                        .onPreviewKeyEvent { ke ->
                            if (ke.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (ke.key == Key.DirectionLeft && categories.isNotEmpty()) {
                                scope.launch {
                                    listState.scrollToItem(totalItems - 1)
                                    runCatching { cycleTargetForFirst.requestFocus() }
                                }
                                true
                            } else false
                        },
                )
            }
            items(categories, key = { it.typeId }) { c ->
                val isSelected = selected?.typeId == c.typeId
                val isLast = c.typeId == lastCategoryId
                val itemRequester: FocusRequester? = if (isLast) lastFocus else null
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
                    modifier = reqMod.then(keyMod),
                )
            }
        }
    }
}

// 載入/空/錯誤狀態畫在 grid 內容區時的最小高度,讓 spinner/空狀態有空間置中,不會擠在站點列正下方。
private val STATUS_MIN_HEIGHT = 320.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoGrid(
    videos: List<Video>,
    windowSize: WindowSize,
    firstItemFocus: FocusRequester,
    clickedVodId: Long?,
    clickedItemFocus: FocusRequester,
    onClick: (Video) -> Unit,
    // 列表最上面的 header(站點/分類列):當第一個 item 一起捲動。
    header: (@Composable () -> Unit)? = null,
    // 載入 / 空清單 / 錯誤狀態:非 null 時取代影片格內容,畫在 header 底下同一個 grid 內。
    // 關鍵:讓 header(站點/分類列)無論 loading 與否都待在「同一個 grid 的同一個 item」,
    // 不會因為切換到別段排版被 unmount → 焦點留在剛選的 chip、focusRestorer 記憶不丟。
    statusContent: (@Composable () -> Unit)? = null,
    // 整齊網格最後一列按↓時跳到的「下一頁」requester(掛在 footer 分頁上);可用時才傳
    nextPageRequester: FocusRequester? = null,
    footerContent: (@Composable () -> Unit)? = null,
) {
    val layout = posterLayoutFor(windowSize)
    val columns = layout.columns
    val padding = PaddingValues(horizontal = windowSize.pagePadding(), vertical = 12.dp)
    val gap = windowSize.gridGap()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val headerCount = if (header != null) 1 else 0  // footer / scroll index 要算進 header

    fun focusFor(idx: Int, v: Video): FocusRequester? = when {
        clickedVodId != null && v.vodId == clickedVodId -> clickedItemFocus
        idx == 0 -> firstItemFocus
        else -> null
    }

    if (layout.grid == GridLayout.Masonry) {
        // 瀑布流：每張卡高度跟著圖的真實比例跑。比例要等圖載入後才知道，先用預設值撐著、
        // 載到了再更新 → 卡片高度重排（masonry 本來就會這樣）。電視導航靠空間搜尋，不掛↓ redirect。
        val ratios = remember { mutableStateMapOf<String, Float>() }
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            contentPadding = padding,
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalItemSpacing = gap,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) {
                item(span = StaggeredGridItemSpan.FullLine, key = "home-header") { header() }
            }
            if (statusContent != null) {
                item(span = StaggeredGridItemSpan.FullLine, key = "home-status") {
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(min = STATUS_MIN_HEIGHT),
                        contentAlignment = Alignment.Center,
                    ) { statusContent() }
                }
            } else {
                staggeredItemsIndexed(
                    videos,
                    key = { _, v -> v.vodId },
                ) { idx, v ->
                    PosterCard(
                        title = v.vodName,
                        remarks = v.vodRemarks,
                        imageUrl = v.vodPic,
                        fromSite = v.fromSite,
                        aspectRatio = ratios[v.vodPic] ?: PosterConfig.MASONRY_DEFAULT_RATIO,
                        fill = PosterFill.Crop,
                        onRatio = { ratios[v.vodPic] = it },
                        onClick = { onClick(v) },
                        focusRequester = focusFor(idx, v),
                    )
                }
                if (footerContent != null) {
                    item(span = StaggeredGridItemSpan.FullLine) { footerContent() }
                }
            }
        }
        return
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "home-header") { header() }
        }
        if (statusContent != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "home-status") {
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = STATUS_MIN_HEIGHT),
                    contentAlignment = Alignment.Center,
                ) { statusContent() }
            }
        } else {
            itemsIndexed(videos, key = { _, v -> v.vodId }) { idx, v ->
                // 最後一列卡片按↓ → 先把分頁捲進畫面、再 focus「下一頁」(分頁在卡片下方、按↓當下還沒 compose,
                // 直接 requestFocus 會落空 → 用 keyScrollFocus 先捲再 focus)。footer index = header + videos。
                val isLastRow = idx >= videos.size - columns
                val downKeyMod = if (isLastRow) {
                    Modifier.keyScrollFocus(scope, nextPageRequester) { gridState.animateScrollToItem(headerCount + videos.size) }
                } else Modifier
                PosterCard(
                    title = v.vodName,
                    remarks = v.vodRemarks,
                    imageUrl = v.vodPic,
                    fromSite = v.fromSite,
                    aspectRatio = layout.cellAspect,
                    fill = layout.fill,
                    onClick = { onClick(v) },
                    modifier = downKeyMod,
                    focusRequester = focusFor(idx, v),
                )
            }
            if (footerContent != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { footerContent() }
            }
        }
    }
}

