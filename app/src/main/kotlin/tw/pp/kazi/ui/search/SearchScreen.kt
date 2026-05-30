package tw.pp.kazi.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import tw.pp.kazi.data.AggregatedVideo
import tw.pp.kazi.data.MultiSearchResult
import tw.pp.kazi.data.SearchQuery
import tw.pp.kazi.data.Site
import tw.pp.kazi.data.SiteSearchStatus
import tw.pp.kazi.data.Video
import tw.pp.kazi.data.aggregateByName
import tw.pp.kazi.data.applyExcludes
import tw.pp.kazi.data.parseSearchQuery
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.Routes
import tw.pp.kazi.ui.WindowSize
import tw.pp.kazi.data.PosterConfig
import tw.pp.kazi.ui.GridLayout
import tw.pp.kazi.ui.keyScrollFocus
import tw.pp.kazi.ui.posterLayoutFor
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.FocusableTag
import tw.pp.kazi.ui.components.HorizontalPageSwipe
import tw.pp.kazi.ui.components.pageSwipeIgnore
import tw.pp.kazi.ui.components.pageSwipeAnchored
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
import tw.pp.kazi.util.ChineseConverter
import tw.pp.kazi.util.Logger
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

// 多站聚合後一次顯示太多卡，使用者要捲很久才能到下一個畫面 → 客戶端切片，每頁 24 筆
private const val INNER_PAGE_SIZE = 24

// LazyVerticalGrid header 順序固定：searchControls / StatsBar / 內層 Pager（頂）/ 卡片⋯
// 內層換頁時要捲到第一張卡的位置（不是 0，否則會把搜尋欄也帶進來）
private const val FIRST_CARD_INDEX = 3

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    initialKeyword: String = "",
    initialSiteIds: Set<Long> = emptySet(),
    // false = 只帶字進來、聚焦輸入框,不自動搜(詳情頁「搜尋」用)。預設 true 維持原行為。
    allowAutoSearch: Boolean = true,
) {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val windowSize = LocalWindowSize.current
    val sites by container.siteRepository.sites.collectAsState()
    val settings by container.configRepository.settings.collectAsState()
    val incognito by container.incognito.collectAsState()
    val lanState by container.lanState.collectAsState()
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val enabledSites = remember(sites) { sites.filter { it.enabled } }

    val searchSnap = rememberScreenSnapshot("search")

    // 從子畫面（detail / player）返回時還原。
    // 「是不是同一輪搜尋」用「開啟這個 entry 的 nav arg」(originKeyword) 來比，而不是比 submittedKeyword：
    // submittedKeyword 會在使用者於搜尋頁改關鍵字後變動，之前拿它比，會讓「從詳情頁點搜尋→改關鍵字查詢→
    // 看影片返回」時，savedKeyword(=改後的) 對不上 nav arg(=原本的) → 誤判成新搜尋把 snapshot reset 掉、
    // 關鍵字退回詳情頁那個。改用 originKeyword（這輪開場的 arg，使用者改關鍵字不會動到它）就穩定了。
    // initialKeyword 為空（從首頁點搜尋進來）一律視為同一輪 → 還原上次搜尋。
    val hadValidSnapshot = remember(searchSnap) {
        val savedOrigin: String? = searchSnap.peek("originKeyword")
        val savedSites: Set<Long>? = searchSnap.peek("selectedIds")
        val hasAnything = savedSites != null
        val sameOrigin = initialKeyword.isBlank() || initialKeyword == savedOrigin
        val sameSites = initialSiteIds.isEmpty() || initialSiteIds == savedSites
        val valid = hasAnything && sameOrigin && sameSites
        // 不用 discard：本次 session 還要繼續搜尋、儲存新狀態；只是把舊的清掉
        if (hasAnything && !valid) searchSnap.reset()
        valid
    }
    // 記住這輪搜尋是用哪個 nav arg 開場的，供下次返回時的 hadValidSnapshot 比對（reset 後會重設成當前 arg）。
    // 純粹為了寫回 snapshot bag，值本身在這裡用不到，所以不接回變數
    searchSnap.state("originKeyword") { initialKeyword }

    var selectedIds by searchSnap.state("selectedIds") {
        if (initialSiteIds.isNotEmpty()) initialSiteIds else enabledSites.map { it.id }.toSet()
    }
    var keyword by searchSnap.state("keyword") { initialKeyword }
    var submittedKeyword by searchSnap.state<String?>("submittedKeyword") { null }
    var loading by remember { mutableStateOf(false) }
    var result by searchSnap.state<MultiSearchResult?>("result") { null }
    var page by searchSnap.state("page") { 1 }
    var pageCount by searchSnap.state("pageCount") { 1 }
    var lastClickedAggName by searchSnap.state<String?>("lastClickedAggName") { null }
    val focusRequester = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val clickedResultFocus = remember { FocusRequester() }
    // 結果最後一列按↓ → 跳分頁「下一頁」(內層優先,否則外層)。key 事件 + runCatching 觸發,安全不 ANR/閃退。
    val innerNextPageFocus = remember { FocusRequester() }
    val outerNextPageFocus = remember { FocusRequester() }
    // 進畫面那一瞬間的值即「上次點過的卡」；之後 onClick 會更新成新值，
    // 但 restoreClickedAggName 已經 capture 起來，繼續代表「這次進畫面要 focus 的那張」
    val restoreClickedAggName = remember { lastClickedAggName }
    var pendingResultFocus by remember { mutableStateOf(false) }
    var pendingClickedFocus by remember { mutableStateOf(restoreClickedAggName != null) }

    val aggregated = remember(result) {
        result?.videos?.let(::aggregateByName) ?: emptyList()
    }

    // 客戶端內層分頁：把 aggregated 切成 INNER_PAGE_SIZE 一塊，避免一次塞太多卡（特別是空 keyword 抓最新時）。
    // 走 snapshot 才能在「點卡 → detail → 返回」後還原到原本那一頁，不然 displayPage 永遠回到 1。
    var displayPage by searchSnap.state("displayPage") { 1 }
    val innerPageCount = remember(aggregated) {
        ((aggregated.size + INNER_PAGE_SIZE - 1) / INNER_PAGE_SIZE).coerceAtLeast(1)
    }
    // displayPage 不能超過實際頁數（aggregated 變小時要 clamp）
    LaunchedEffect(innerPageCount) {
        if (displayPage > innerPageCount) displayPage = innerPageCount
    }
    val displayedSlice = remember(aggregated, displayPage) {
        val from = ((displayPage - 1) * INNER_PAGE_SIZE).coerceAtLeast(0)
        val to = (from + INNER_PAGE_SIZE).coerceAtMost(aggregated.size)
        if (from >= aggregated.size) emptyList() else aggregated.subList(from, to)
    }

    // 若 SearchScreen 在 sites 還沒從 disk 讀完前就 compose（例如 cold start 直接遠端推搜尋），
    // 預設 selectedIds 會是 emptySet → runSearch 直接 no-op。等 enabledSites 真的有值時補上一次預選全部。
    // 加上 hadValidSnapshot / initialSiteIds 守門，避免覆蓋掉使用者的「全不選」狀態或 nav arg
    LaunchedEffect(enabledSites) {
        if (!hadValidSnapshot && initialSiteIds.isEmpty()
            && selectedIds.isEmpty() && enabledSites.isNotEmpty()) {
            selectedIds = enabledSites.map { it.id }.toSet()
        }
    }

    val parsedQuery = remember(keyword) { parseSearchQuery(keyword) }

    fun runSearch(targetPage: Int = 1) {
        val kw = keyword.trim()
        if (selectedIds.isEmpty()) return
        val parsed = parseSearchQuery(kw)
        // 完全空 → 抓站台最新列表；只有排除詞、沒可搜尋的正向詞 → 不搜（避免拉全站再過濾）
        if (kw.isNotEmpty() && parsed.include.isBlank()) return
        keyboardController?.hide()
        focusManager.clearFocus()
        scope.launch {
            loading = true
            submittedKeyword = kw
            page = targetPage
            if (targetPage == 1) {
                // addSearchKeyword 內部會 trim 並忽略空字串，不必另外擋
                if (!container.incognito.value) container.configRepository.addSearchKeyword(kw)
            }
            val serverResult = container.macCmsApi.multiSiteSearch(
                sites = enabledSites.filter { it.id in selectedIds },
                keyword = parsed.include,
                page = targetPage,
            )
            val applied = applyExcludes(serverResult, parsed.excludes)
            result = applied
            pageCount = applied.pageCount.coerceAtLeast(1)
            displayPage = 1
            loading = false
            pendingResultFocus = true
        }
    }

    LaunchedEffect(Unit) {
        // 進搜尋頁預設把 focus 放在搜尋框。例外：
        //  · 從 detail 返回要回到剛點的那張卡（pendingClickedFocus）→ 交給下面的 effect 處理
        //  · 遠端推進來自動搜尋（帶 keyword 且非 snapshot 還原）→ 別搶 focus，不然 IME 蓋住載入動畫
        // 只在 TV 搶 focus；手機進搜尋頁搶 focus 會害 IME 自動彈出來
        // allowAutoSearch=false(詳情頁「搜尋」帶字進來):不自動搜,改成聚焦輸入框讓使用者自己改/送出。
        val autoSearch = initialKeyword.isNotBlank() && !hadValidSnapshot && allowAutoSearch
        if (windowSize.isTv && !pendingClickedFocus && !autoSearch) {
            // 等 SearchField 真的 compose、focusRequester 掛上節點再搶；少了這個 delay，requestFocus
            // 會在元素還沒 attach 時被丟掉，focus 就 fallback 到 top bar 第一顆鈕（無痕鍵）
            kotlinx.coroutines.delay(50)
            runCatching { focusRequester.requestFocus() }
        }
        if (autoSearch) runSearch()
    }

    // 搜完 / 換頁（外層 or 內層） → focus 第一個結果；
    // 空結果（沒找到）→ focus 拉回搜尋輸入欄，免得 runSearch 裡 clearFocus 之後 focus 飄到 top bar
    LaunchedEffect(displayedSlice, loading) {
        if (!windowSize.isTv || loading || !pendingResultFocus) return@LaunchedEffect
        kotlinx.coroutines.delay(50)
        if (displayedSlice.isNotEmpty()) {
            runCatching { firstResultFocus.requestFocus() }
        } else {
            runCatching { focusRequester.requestFocus() }
        }
        pendingResultFocus = false
    }

    // 從 detail 返回 → focus 那張卡。displayPage 已經從 snapshot 還原到當時那頁，
    // 所以這裡只負責 focus；萬一 aggregated 因 result 重建後該卡跑到別頁（防呆），順手切過去
    LaunchedEffect(aggregated, displayPage) {
        if (!pendingClickedFocus || restoreClickedAggName == null) return@LaunchedEffect
        val idx = aggregated.indexOfFirst { it.name == restoreClickedAggName }
        if (idx < 0) return@LaunchedEffect
        val targetPage = (idx / INNER_PAGE_SIZE) + 1
        if (displayPage != targetPage) {
            displayPage = targetPage
            return@LaunchedEffect
        }
        // 手機觸控不需要主動 focus，純切頁就夠
        if (!windowSize.isTv) {
            pendingClickedFocus = false
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(50)
        runCatching { clickedResultFocus.requestFocus() }
        pendingClickedFocus = false
    }

    // 跟 HomeScreen 同樣理由：換頁時把 header 主動 reset 展開，避免被卡在收合狀態
    val headerState = tw.pp.kazi.ui.components.rememberCollapsibleHeaderState()
    LaunchedEffect(page, displayPage) {
        headerState.offsetPx.floatValue = 0f
    }

    ScreenScaffold(
        title = "搜尋",
        subtitle = when {
            submittedKeyword == null -> "同時搜所有已啟用站點"
            submittedKeyword!!.isBlank() -> "最新列表"
            else -> "「${submittedKeyword}」結果"
        },
        headerState = headerState,
        titleBadges = if (incognito || lanState.running) {
            {
                if (incognito) {
                    tw.pp.kazi.ui.components.StatusPill(
                        text = "🕶 無痕（不會留紀錄）",
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
            // 跟 home 同一組 trailing（去掉「搜尋」因為已經在搜尋頁了），方便從搜尋直接跳其他畫面
            val compact = windowSize != WindowSize.Expanded
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
                    // 跟首頁同款一鍵 toggle：開著時再點直接關掉（不進 QR 頁），沒開才 enable + 導 QR 頁
                    scope.launch {
                        if (lanState.running) {
                            container.stopLan()
                            container.configRepository.updateLanShare(false)
                        } else {
                            val ok = container.startLan()
                            if (ok) {
                                container.configRepository.updateLanShare(true)
                            } else {
                                Logger.w("startLan failed from Search one-tap; navigating to LanShareScreen so user sees the failure")
                            }
                            nav.navigate(Routes.LanShare)
                        }
                    }
                },
                primary = lanState.running,
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
        onBack = { nav.popBackStack() },
    ) { innerPadding ->
        PullToRefreshBoxIfCompact(
            isRefreshing = loading,
            onRefresh = {
                // 同步先把 loading 設為 true，PTR 指示器才不會在 LaunchedEffect 還沒跑前就消失
                loading = true
                runSearch(page)
            },
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
        Column(modifier = Modifier.fillMaxSize()) {

        // 搜尋輸入 + 站點選擇 + 排除提示 + 搜尋紀錄。出現在 grid header（有結果）或頂部 sticky（無結果/載入/錯誤）
        // pageSwipeAnchored:有結果時這區是 grid header,換頁拖曳時反向抵銷位移留在原地,只有結果卡片跟著滑。
        // (無結果時是釘頂、不在 HorizontalPageSwipe 內,modifier 自動退化成 no-op)
        val searchControls: @Composable () -> Unit = {
            Column(
                modifier = Modifier.pageSwipeAnchored().padding(
                    horizontal = windowSize.pagePadding(),
                    vertical = if (windowSize.isCompact) 6.dp else 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(if (windowSize.isCompact) 6.dp else 10.dp),
            ) {
                SearchField(
                    value = keyword,
                    onChange = { keyword = it },
                    onSubmit = { runSearch() },
                    onToSimplified = { keyword = ChineseConverter.toSimplified(keyword, appContext) },
                    focusRequester = focusRequester,
                    compact = windowSize.isCompact,
                    incognito = incognito,
                )
                if (parsedQuery.excludes.isNotEmpty()) {
                    ExcludeHint(parsedQuery.excludes)
                }
                // 站台選擇一律展開（不再有收合 chip）：進搜尋頁就能直接挑站台，少一層展開操作。
                // 列間上下移動交給框架空間搜尋（chip LazyRow 自帶 focusGroup），不手動指落點。
                SiteSelector(
                    sites = enabledSites,
                    selected = selectedIds,
                    onToggle = {
                        selectedIds = if (it in selectedIds) selectedIds - it else selectedIds + it
                    },
                    onSelectAll = { selectedIds = enabledSites.map { s -> s.id }.toSet() },
                    onSelectNone = { selectedIds = emptySet() },
                )
                // 最近搜尋 pills 一律放外面，搜過 / 沒搜過都看得到
                if (settings.searchHistory.isNotEmpty()) {
                    HistoryPills(
                        items = settings.searchHistory,
                        onPick = { kw ->
                            // 點 pill 時把 IME 收起來（電視盒上 BasicTextField 會被 focus 到，不收會彈鍵盤）
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            keyword = kw
                            runSearch()
                        },
                        onRemove = { kw ->
                            scope.launch { container.configRepository.removeSearchKeyword(kw) }
                            android.widget.Toast.makeText(appContext, "已刪除「$kw」", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onClear = { scope.launch { container.configRepository.clearSearchHistory() } },
                    )
                }
            }
        }

        val r = result
        val showResults = !loading && r != null && r.videos.isNotEmpty()

        if (!showResults) {
            // 載入中/沒結果/還沒搜尋 → 搜尋輸入區釘頂部讓使用者好操作
            searchControls()
            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading -> LoadingState(label = "多站搜尋中⋯")
                    r == null -> EmptyState(
                        title = "輸入關鍵字或直接搜尋",
                        subtitle = "不輸入關鍵字會顯示各站最新列表",
                        icon = Icons.Filled.Search,
                    )
                    else -> EmptyState(
                        title = "沒有找到相關影片",
                        subtitle = "試試其他關鍵字或按「簡」轉簡體",
                        icon = Icons.Filled.SearchOff,
                    )
                }
            }
        } else {
            // 有結果 → 搜尋輸入 + 統計列塞進 grid header，跟著影片一起捲，省垂直空間。
            // 排版規則跟全 App 一致：手機瀑布流、電視方形(Fit 不裁切)。
            val layout = posterLayoutFor(windowSize)
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            val staggeredState = rememberLazyStaggeredGridState()
            // 內層換頁：先換 displayPage、捲回卡片列起點、等動畫＋一個 layout frame 再 focus 第一張卡。
            // 把 scroll → focus 串成同一個 coroutine 並等動畫完成才 focus，順序就保證了。
            // FIRST_CARD_INDEX = searchControls(0) + StatsBar(1) + 頂部 Pager(2) → 3
            val goToInnerPage: (Int) -> Unit = { target ->
                val clamped = target.coerceIn(1, innerPageCount)
                if (clamped != displayPage) {
                    displayPage = clamped
                    scope.launch {
                        if (layout.grid == GridLayout.Masonry) staggeredState.animateScrollToItem(FIRST_CARD_INDEX)
                        else gridState.animateScrollToItem(FIRST_CARD_INDEX)
                        // 動畫結束後再給 grid 一格時間 attach focusRequester；只在 TV 搶焦(TV 必為整齊網格)
                        if (windowSize.isTv) {
                            kotlinx.coroutines.delay(50)
                            runCatching { firstResultFocus.requestFocus() }
                        }
                    }
                }
            }
            @Composable
            fun resultCard(idx: Int, agg: AggregatedVideo, aspectRatio: Float, fill: PosterFill, modifier: Modifier, onRatio: ((Float) -> Unit)?) {
                PosterCard(
                    title = agg.name,
                    remarks = agg.remarks,
                    imageUrl = agg.pic,
                    fromSite = if (agg.sources.size > 1) "${agg.sources.size} 來源"
                        else agg.sources.firstOrNull()?.fromSite,
                    aspectRatio = aspectRatio,
                    fill = fill,
                    onRatio = onRatio,
                    onClick = {
                        val first = agg.sources.firstOrNull() ?: return@PosterCard
                        val sid = first.fromSiteId ?: return@PosterCard
                        container.pendingDetailPeers =
                            if (agg.sources.size > 1) agg.sources else null
                        // 記下這次點的影片名（用名字做 key 因為聚合後沒有單一 vodId）
                        lastClickedAggName = agg.name
                        nav.navigate(Routes.detail(sid, first.vodId))
                    },
                    modifier = modifier,
                    focusRequester = when {
                        restoreClickedAggName != null && agg.name == restoreClickedAggName -> clickedResultFocus
                        idx == 0 -> firstResultFocus
                        else -> null
                    },
                )
            }

            @Composable
            fun topInnerPager() {
                Pager(
                    page = displayPage,
                    pageCount = innerPageCount,
                    onPrev = { goToInnerPage(displayPage - 1) },
                    onNext = { goToInnerPage(displayPage + 1) },
                    windowSize = windowSize,
                    simplified = true,
                    accent = true,
                    label = "顯示頁",
                )
            }

            @Composable
            fun bottomInnerPager() {
                Pager(
                    page = displayPage,
                    pageCount = innerPageCount,
                    onPrev = { goToInnerPage(displayPage - 1) },
                    onNext = { goToInnerPage(displayPage + 1) },
                    windowSize = windowSize,
                    simplified = true,
                    accent = true,
                    label = "顯示頁",
                    nextPageRequester = innerNextPageFocus,
                )
            }

            @Composable
            fun outerPager() {
                Pager(
                    page = page,
                    pageCount = pageCount,
                    onPrev = { if (page > 1) runSearch(page - 1) },
                    onNext = { if (page < pageCount) runSearch(page + 1) },
                    onJump = { target -> runSearch(target) },
                    windowSize = windowSize,
                    label = "資料頁",
                    nextPageRequester = outerNextPageFocus,
                )
            }

            // 手機左右滑換頁:內層(顯示頁)優先,內層翻完才翻外層(資料頁)。電視盒不攔。
            HorizontalPageSwipe(
                enabled = !windowSize.isTv && (innerPageCount > 1 || pageCount > 1),
                canPrev = displayPage > 1 || page > 1,
                canNext = displayPage < innerPageCount || page < pageCount,
                onPrev = {
                    if (displayPage > 1) goToInnerPage(displayPage - 1)
                    else if (page > 1) runSearch(page - 1)
                },
                onNext = {
                    if (displayPage < innerPageCount) goToInnerPage(displayPage + 1)
                    else if (page < pageCount) runSearch(page + 1)
                },
            ) {
            if (layout.grid == GridLayout.Masonry) {
                val ratios = remember { mutableStateMapOf<String, Float>() }
                LazyVerticalStaggeredGrid(
                    state = staggeredState,
                    columns = StaggeredGridCells.Fixed(layout.columns),
                    contentPadding = PaddingValues(horizontal = windowSize.pagePadding(), vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(windowSize.gridGap()),
                    verticalItemSpacing = windowSize.gridGap(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = StaggeredGridItemSpan.FullLine) { searchControls() }
                    item(span = StaggeredGridItemSpan.FullLine) { StatsBar(r!!, aggregated.size, windowSize) }
                    if (innerPageCount > 1) {
                        item(span = StaggeredGridItemSpan.FullLine) { topInnerPager() }
                    }
                    staggeredItemsIndexed(displayedSlice, key = { _, agg -> agg.name }) { idx, agg ->
                        resultCard(
                            idx, agg,
                            aspectRatio = ratios[agg.pic] ?: PosterConfig.MASONRY_DEFAULT_RATIO,
                            fill = PosterFill.Crop,
                            modifier = Modifier,
                            onRatio = { ratios[agg.pic] = it },
                        )
                    }
                    if (innerPageCount > 1) {
                        item(span = StaggeredGridItemSpan.FullLine) { bottomInnerPager() }
                    }
                    if (pageCount > 1) {
                        item(span = StaggeredGridItemSpan.FullLine) { outerPager() }
                    }
                }
            } else {
                val columns = layout.columns
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(horizontal = windowSize.pagePadding(), vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(windowSize.gridGap()),
                    verticalArrangement = Arrangement.spacedBy(windowSize.gridGap()),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { searchControls() }
                    item(span = { GridItemSpan(maxLineSpan) }) { StatsBar(r!!, aggregated.size, windowSize) }
                    if (innerPageCount > 1) {
                        item(span = { GridItemSpan(maxLineSpan) }) { topInnerPager() }
                    }
                    // 最後一列卡片按↓ → 跳分頁「下一頁」(內層優先,否則外層)。分頁在卡片下方還沒 compose,
                    // 用 keyScrollFocus 先捲到底(內外層分頁都在最底、相鄰)再 focus 目標。
                    val cardNext: FocusRequester? = when {
                        innerPageCount > 1 && displayPage < innerPageCount -> innerNextPageFocus
                        pageCount > 1 && page < pageCount -> outerNextPageFocus
                        else -> null
                    }
                    val headerCount = 2 + (if (innerPageCount > 1) 1 else 0)
                    val footerCount = (if (innerPageCount > 1) 1 else 0) + (if (pageCount > 1) 1 else 0)
                    val lastItemIndex = (headerCount + displayedSlice.size + footerCount - 1).coerceAtLeast(0)
                    itemsIndexed(displayedSlice, key = { _, agg -> agg.name }) { idx, agg ->
                        val isLastRow = idx >= displayedSlice.size - columns
                        val downMod = if (isLastRow) {
                            Modifier.keyScrollFocus(scope, cardNext) { gridState.animateScrollToItem(lastItemIndex) }
                        } else Modifier
                        resultCard(
                            idx, agg,
                            aspectRatio = layout.cellAspect,
                            fill = layout.fill,
                            modifier = downMod,
                            onRatio = null,
                        )
                    }
                    if (innerPageCount > 1) {
                        item(span = { GridItemSpan(maxLineSpan) }) { bottomInnerPager() }
                    }
                    if (pageCount > 1) {
                        item(span = { GridItemSpan(maxLineSpan) }) { outerPager() }
                    }
                }
            }
            }
        }
        }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onToSimplified: () -> Unit,
    focusRequester: FocusRequester,
    compact: Boolean,
    incognito: Boolean,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    // BasicTextField 會吃掉方向鍵(游標移動),按↓不會自己往下換列 → 卡在搜尋框。
    // 攔↓改叫 focusManager.moveFocus(Down):交給框架找下面那列(不寫死落點),這樣才能離開搜尋框。
    val releaseDownMod = Modifier.onPreviewKeyEvent { ke ->
        if (ke.type == KeyEventType.KeyDown && ke.key == Key.DirectionDown) {
            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
        } else false
    }

    // 用 TextFieldValue 自己管 selection（String overload 控不了游標位置）。
    // 對外仍維持 String API：value 是 source of truth，內部只是多帶一個 selection。
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // 外部改了文字（簡轉 / 點最近搜尋 / snapshot 還原）→ 同步進來並把游標擺到最後面
    LaunchedEffect(value) {
        if (fieldValue.text != value) fieldValue = TextFieldValue(value, TextRange(value.length))
    }
    // focus 到搜尋框時把游標放到字的最後面，方便接著打 / 往前刪
    LaunchedEffect(focused) {
        if (focused) fieldValue = fieldValue.copy(selection = TextRange(fieldValue.text.length))
    }

    @Composable
    fun InputBox(modifier: Modifier = Modifier) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0D0D15))
                .border(
                    2.dp,
                    if (focused) AppColors.FocusRing else Color(0x22FFFFFF),
                    RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.tv.material3.Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = AppColors.OnBgMuted,
                modifier = Modifier.size(20.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = fieldValue,
                    onValueChange = {
                        fieldValue = it
                        if (it.text != value) onChange(it.text)
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = AppColors.OnBg, fontSize = 16.sp),
                    cursorBrush = SolidColor(AppColors.Primary),
                    // 無痕時關掉自動修正/建議，連帶要求 IME 不要把輸入內容學進個人字典。
                    // 註：IME_FLAG_NO_PERSONALIZED_LEARNING 在 Compose KeyboardOptions 沒有直接對應參數，
                    // autoCorrect=false 是 Compose 層能做到的最接近設定（會關掉 suggestions / 多數 IME 的學習行為）。
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search,
                        autoCorrectEnabled = !incognito,
                    ),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    interactionSource = interaction,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).then(releaseDownMod),
                    decorationBox = { inner ->
                        if (value.isEmpty()) {
                            Text(
                                "關鍵字（用 -詞 可排除）",
                                color = AppColors.OnBgDim,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        inner()
                    },
                )
            }
            if (value.isNotEmpty()) {
                val clearInteraction = remember { MutableInteractionSource() }
                val clearFocused by clearInteraction.collectIsFocusedAsState()
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (clearFocused) AppColors.Primary else Color(0x22FFFFFF))
                        .border(
                            2.dp,
                            if (clearFocused) AppColors.FocusRing else Color.Transparent,
                            RoundedCornerShape(999.dp),
                        )
                        .focusable(interactionSource = clearInteraction)
                        .clickable(interactionSource = clearInteraction, indication = null) {
                            onChange("")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.tv.material3.Icon(
                        Icons.Filled.Close,
                        contentDescription = "清除",
                        tint = if (clearFocused) Color.White else AppColors.OnBgMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InputBox(Modifier.fillMaxWidth())
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppButton(
                    text = "簡",
                    icon = Icons.Filled.Translate,
                    onClick = onToSimplified,
                    enabled = value.isNotBlank(),
                    primary = false,
                )
                AppButton(
                    text = "搜尋",
                    icon = Icons.Filled.Search,
                    onClick = onSubmit,
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke(this)
            }
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InputBox(Modifier.weight(1f))
            AppButton(
                text = "簡",
                icon = Icons.Filled.Translate,
                onClick = onToSimplified,
                enabled = value.isNotBlank(),
                primary = false,
            )
            AppButton(
                text = "搜尋",
                icon = Icons.Filled.Search,
                onClick = onSubmit,
            )
            trailing?.invoke(this)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExcludeHint(excludes: List<String>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "排除：",
            color = AppColors.OnBgMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        excludes.forEach { ex ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(AppColors.Error.copy(alpha = 0.22f))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    "−$ex",
                    color = AppColors.Error,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SiteSelector(
    sites: List<Site>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    // 展開時搜尋框↓的落點掛在「全選」鈕上（這列最上方的可 focus 元素）
    entryModifier: Modifier = Modifier,
) {
    // multi-select 沒有單一「當前」概念，fallback 用第一個已選；都沒選就 fallback 第一顆 chip
    val firstSelectedFocus = remember { FocusRequester() }
    val firstSiteFocus = remember { FocusRequester() }
    val lastSiteFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstSelectedId = remember(sites, selected) {
        sites.firstOrNull { it.id in selected }?.id
    }
    val firstId = sites.firstOrNull()?.id
    val lastId = sites.lastOrNull()?.id
    // 首尾相連（跟首頁站點列同款）：first 按 ← 跳 last、last 按 → 跳 first。
    // 對端剛好是 firstSelected 那顆時就請求 firstSelectedFocus，否則用 first/last 的 anchor。
    val cycleTargetForFirst = if (lastId != null && lastId == firstSelectedId) firstSelectedFocus else lastSiteFocus
    val cycleTargetForLast = if (firstId != null && firstId == firstSelectedId) firstSelectedFocus else firstSiteFocus
    // 「站點數 / 全選 / 全不選」這排在 chip 上面;從這排按↓,空間搜尋會跳過 chip。
    // 跟分頁同一套「先確認顯示再 focus」:先把 chip 列捲到第一顆、再 focus(雖然第一顆通常本就可見)。
    val chipTarget = if (firstId != null && firstId == firstSelectedId) firstSelectedFocus else firstSiteFocus
    val chipDownMod = Modifier.keyScrollFocus(scope, chipTarget) { listState.animateScrollToItem(0) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "站點 ${selected.size}/${sites.size}",
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            AppButton(text = "全選", onClick = onSelectAll, primary = false, modifier = chipDownMod)
            AppButton(text = "全不選", onClick = onSelectNone, primary = false, modifier = chipDownMod)
        }
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .pageSwipeIgnore("search-sites")  // 站台 chip 列自己水平捲動,不從這列觸發換頁
                .focusGroup()
                // 落點站台被捲出畫面時 requester 沒掛上，restore 直接回它會崩 → 可見才用
                .focusRestorer {
                    val targetId = firstSelectedId ?: firstId
                    if (targetId != null && listState.layoutInfo.visibleItemsInfo.any { it.key == targetId })
                        (if (firstSelectedId != null) firstSelectedFocus else firstSiteFocus)
                    else FocusRequester.Default
                },
        ) {
            itemsIndexed(sites, key = { _, s -> s.id }) { idx, s ->
                val isFirstSelected = s.id == firstSelectedId
                val isFirst = s.id == firstId
                val isLast = s.id == lastId
                // 一顆只能掛一個 FocusRequester：優先 firstSelected > first > last
                val itemRequester: FocusRequester? = when {
                    isFirstSelected -> firstSelectedFocus
                    isFirst -> firstSiteFocus
                    isLast -> lastSiteFocus
                    else -> null
                }
                val reqMod = if (itemRequester != null) Modifier.focusRequester(itemRequester) else Modifier
                // 循環：先 scroll 確保對端 composed，再 requestFocus（runCatching 防對端剛好沒掛上）
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
                    selected = s.id in selected,
                    onClick = { onToggle(s.id) },
                    modifier = reqMod.then(keyMod),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HistoryPills(
    items: List<String>,
    onPick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    // 從上面（站點 chip）↓ 進來的落點掛在「清除」鈕上；它↑ 再回 chip
    entryModifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "最近搜尋（長按單筆刪除）",
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            AppButton(text = "清除", icon = Icons.Filled.ClearAll, onClick = onClear, primary = false, modifier = entryModifier)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.focusGroup(),
        ) {
            items(items) { kw ->
                FocusableTag(
                    text = kw,
                    selected = false,
                    onClick = { onPick(kw) },
                    onLongClick = { onRemove(kw) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsBar(r: MultiSearchResult, aggregatedCount: Int, windowSize: WindowSize) {
    val failed = r.perSite.count { it.status == SiteSearchStatus.Failed }
    val mainLabel = if (aggregatedCount < r.videos.size) {
        "共 $aggregatedCount 部 · ${r.videos.size} 個來源"
    } else {
        "共 ${r.videos.size} 筆"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = windowSize.pagePadding(), vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x20FFFFFF))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                mainLabel,
                color = AppColors.OnBg,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
            )
            if (failed > 0) {
                Text(
                    "· ${failed} 站失敗",
                    color = AppColors.Error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.pageSwipeIgnore("search-stats"),  // 各站狀態列自己水平捲動,不觸發換頁
        ) {
            items(r.perSite.sortedWith(compareBy({ statusOrder(it.status) }, { -it.count }))) { s ->
                SiteStatusPill(s.siteName, s.count, s.status, s.message)
            }
        }
    }
}

private fun statusOrder(s: SiteSearchStatus): Int = when (s) {
    SiteSearchStatus.Success -> 0
    SiteSearchStatus.Empty -> 1
    SiteSearchStatus.Failed -> 2
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SiteStatusPill(name: String, count: Int, status: SiteSearchStatus, message: String?) {
    val (icon, tint, label) = when (status) {
        SiteSearchStatus.Success -> Triple("✓", AppColors.Success, "$name · $count")
        SiteSearchStatus.Empty -> Triple("○", AppColors.OnBgDim, "$name · 無")
        SiteSearchStatus.Failed -> Triple("✗", AppColors.Error, "$name · 失敗")
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x22FFFFFF))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(icon, color = tint, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text(label, color = AppColors.OnBg, style = MaterialTheme.typography.labelSmall)
    }
}
