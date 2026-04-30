package tw.pp.kazi.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import tw.pp.kazi.ui.columnsFor
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.FocusableTag
import tw.pp.kazi.ui.components.LoadingState
import tw.pp.kazi.ui.components.Pager
import tw.pp.kazi.ui.components.PosterCard
import tw.pp.kazi.ui.components.ScreenScaffold
import tw.pp.kazi.ui.gridGap
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.theme.AppColors
import tw.pp.kazi.util.ChineseConverter
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
) {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val windowSize = LocalWindowSize.current
    val sites by container.siteRepository.sites.collectAsState()
    val settings by container.configRepository.settings.collectAsState()
    val incognito by container.incognito.collectAsState()
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val enabledSites = remember(sites) { sites.filter { it.enabled } }

    // 從子畫面（detail / player）返回時還原。
    // 規則：snapshot 的 submittedKeyword 跟這次 nav 的 initialKeyword 一致（且 siteIds 也相同）→ 還原；
    // 否則代表是新一輪搜尋（例如手機遠端送過來不同 keyword），不能用 snapshot，要 fresh search。
    // 之前寫成「只有 initialKeyword 為空才用 snapshot」是錯的 —— 從 detail 返回時 nav arg 還是原本的 keyword，
    // 結果 snapshot 永遠不被用、每次返回都重搜。
    val initialSnapshot = remember {
        val snap = container.searchSnapshot ?: return@remember null
        val sameKeyword = (initialKeyword.isBlank() || initialKeyword == snap.submittedKeyword)
        val sameSites = (initialSiteIds.isEmpty() || initialSiteIds == snap.selectedIds)
        if (sameKeyword && sameSites) snap else null
    }

    var selectedIds by remember(enabledSites) {
        mutableStateOf(
            when {
                initialSiteIds.isNotEmpty() -> initialSiteIds
                initialSnapshot != null -> initialSnapshot.selectedIds
                else -> enabledSites.map { it.id }.toSet()
            }
        )
    }
    var keyword by remember { mutableStateOf(initialSnapshot?.keyword ?: initialKeyword) }
    var submittedKeyword by remember { mutableStateOf(initialSnapshot?.submittedKeyword) }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MultiSearchResult?>(initialSnapshot?.result) }
    var selectorExpanded by remember { mutableStateOf(initialSnapshot?.selectorExpanded ?: true) }
    var page by remember { mutableIntStateOf(initialSnapshot?.page ?: 1) }
    var pageCount by remember { mutableIntStateOf(initialSnapshot?.pageCount ?: 1) }
    val focusRequester = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val clickedResultFocus = remember { FocusRequester() }
    val restoreClickedAggName = remember { initialSnapshot?.lastClickedAggName }
    var pendingResultFocus by remember { mutableStateOf(false) }
    var pendingClickedFocus by remember { mutableStateOf(restoreClickedAggName != null) }

    val aggregated = remember(result) {
        result?.videos?.let(::aggregateByName) ?: emptyList()
    }

    // 客戶端內層分頁：把 aggregated 切成 INNER_PAGE_SIZE 一塊，避免一次塞太多卡（特別是空 keyword 抓最新時）
    var displayPage by remember { mutableIntStateOf(1) }
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
            selectorExpanded = false
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
        // 只有「使用者主動點搜尋進來」（沒帶 keyword、沒 snapshot 還原）才搶 focus 到輸入框；
        // 遠端推進來自動搜尋的情況下千萬別 focus，不然鍵盤會直接蓋住載入動畫
        val isFreshUserEntry = initialSnapshot == null
            && !pendingClickedFocus
            && initialKeyword.isBlank()
        if (isFreshUserEntry) {
            runCatching { focusRequester.requestFocus() }
        }
        if (initialKeyword.isNotBlank() && initialSnapshot == null) runSearch()
    }

    // 搜完 / 換頁（外層 or 內層） → focus 第一個結果（避免 focus 卡在 input 鍵盤又彈出來）
    LaunchedEffect(displayedSlice) {
        if (pendingResultFocus && displayedSlice.isNotEmpty()) {
            kotlinx.coroutines.delay(50)
            runCatching { firstResultFocus.requestFocus() }
            pendingResultFocus = false
        }
    }

    // 從 detail 返回 → 先把內層頁切到那張卡所在頁，再 focus 它
    LaunchedEffect(aggregated, displayPage) {
        if (pendingClickedFocus && restoreClickedAggName != null) {
            val idx = aggregated.indexOfFirst { it.name == restoreClickedAggName }
            if (idx < 0) return@LaunchedEffect
            val targetPage = (idx / INNER_PAGE_SIZE) + 1
            if (displayPage != targetPage) {
                displayPage = targetPage
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(50)
            runCatching { clickedResultFocus.requestFocus() }
            pendingClickedFocus = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            container.searchSnapshot = tw.pp.kazi.SearchUiSnapshot(
                keyword = keyword,
                submittedKeyword = submittedKeyword,
                selectedIds = selectedIds,
                result = result,
                selectorExpanded = selectorExpanded,
                page = page,
                pageCount = pageCount,
                lastClickedAggName = container.searchSnapshot?.lastClickedAggName,
            )
        }
    }

    ScreenScaffold(
        title = "搜尋",
        subtitle = when {
            submittedKeyword == null -> "同時搜所有已啟用站點"
            submittedKeyword!!.isBlank() -> "最新列表"
            else -> "「${submittedKeyword}」結果"
        },
        titleBadges = if (incognito) {
            { tw.pp.kazi.ui.components.StatusPill("🕶 無痕（不會留紀錄）") }
        } else null,
        onBack = { nav.popBackStack() },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

        // 搜尋輸入 + 站點選擇 + 排除提示 + 搜尋紀錄。出現在 grid header（有結果）或頂部 sticky（無結果/載入/錯誤）
        val searchControls: @Composable () -> Unit = {
            Column(
                modifier = Modifier.padding(
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
                if (selectorExpanded) {
                    SiteSelector(
                        sites = enabledSites,
                        selected = selectedIds,
                        onToggle = {
                            selectedIds = if (it in selectedIds) selectedIds - it else selectedIds + it
                        },
                        onSelectAll = { selectedIds = enabledSites.map { s -> s.id }.toSet() },
                        onSelectNone = { selectedIds = emptySet() },
                    )
                } else {
                    CollapsedSelectorChip(
                        selectedCount = selectedIds.size,
                        totalCount = enabledSites.size,
                        onExpand = { selectorExpanded = true },
                    )
                }
                // 最近搜尋 pills 跟 selectorExpanded 無關，一律放外面，搜過 / 沒搜過都看得到
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
            // 有結果 → 搜尋輸入 + 統計列塞進 grid header，跟著影片一起捲，省垂直空間
            val vm = settings.viewMode
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            // 內層換頁：先換 displayPage、捲回卡片列起點、等動畫＋一個 layout frame 再 focus 第一張卡。
            // 之前用 pendingResultFocus + LaunchedEffect(displayedSlice) 那條路，會跟 animateScroll 比賽：
            // 動畫還沒結束、新第一張卡還沒被 LazyGrid compose 出來時 requestFocus 就被丟掉，焦點時有時無。
            // 把 scroll → focus 串成同一個 coroutine 並等動畫完成才 focus，順序就保證了。
            // FIRST_CARD_INDEX = searchControls(0) + StatsBar(1) + 頂部 Pager(2) → 3
            val goToInnerPage: (Int) -> Unit = { target ->
                val clamped = target.coerceIn(1, innerPageCount)
                if (clamped != displayPage) {
                    displayPage = clamped
                    scope.launch {
                        gridState.animateScrollToItem(FIRST_CARD_INDEX)
                        // 動畫結束後再給 LazyGrid 一格時間 attach focusRequester
                        kotlinx.coroutines.delay(50)
                        runCatching { firstResultFocus.requestFocus() }
                    }
                }
            }
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(vm.columnsFor(windowSize)),
                contentPadding = PaddingValues(
                    horizontal = windowSize.pagePadding(),
                    vertical = 12.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(windowSize.gridGap()),
                verticalArrangement = Arrangement.spacedBy(windowSize.gridGap()),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { searchControls() }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    StatsBar(r!!, aggregated.size, windowSize)
                }
                // 內層分頁鈕（頂）：只有 aggregated 多到要切時才出現
                if (innerPageCount > 1) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                }
                itemsIndexed(
                    displayedSlice,
                    key = { _, agg -> agg.name },
                ) { idx, agg ->
                    PosterCard(
                        title = agg.name,
                        remarks = agg.remarks,
                        imageUrl = agg.pic,
                        fromSite = if (agg.sources.size > 1) "${agg.sources.size} 來源"
                            else agg.sources.firstOrNull()?.fromSite,
                        aspectRatio = vm.aspectRatio,
                        onClick = {
                            val first = agg.sources.firstOrNull() ?: return@PosterCard
                            val sid = first.fromSiteId ?: return@PosterCard
                            container.pendingDetailPeers =
                                if (agg.sources.size > 1) agg.sources else null
                            // 記下這次點的影片名（用名字做 key 因為聚合後沒有單一 vodId）
                            container.searchSnapshot = container.searchSnapshot
                                ?.copy(lastClickedAggName = agg.name)
                                ?: tw.pp.kazi.SearchUiSnapshot(
                                    keyword = keyword,
                                    submittedKeyword = submittedKeyword,
                                    selectedIds = selectedIds,
                                    result = result,
                                    selectorExpanded = selectorExpanded,
                                    page = page,
                                    pageCount = pageCount,
                                    lastClickedAggName = agg.name,
                                )
                            nav.navigate(Routes.detail(sid, first.vodId))
                        },
                        focusRequester = when {
                            restoreClickedAggName != null && agg.name == restoreClickedAggName -> clickedResultFocus
                            idx == 0 -> firstResultFocus
                            else -> null
                        },
                    )
                }
                // 內層分頁鈕（底）：跟頂部同一組
                if (innerPageCount > 1) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                }
                if (pageCount > 1) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Pager(
                            page = page,
                            pageCount = pageCount,
                            onPrev = { if (page > 1) runSearch(page - 1) },
                            onNext = { if (page < pageCount) runSearch(page + 1) },
                            onJump = { target -> runSearch(target) },
                            windowSize = windowSize,
                            label = "資料頁",
                        )
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
                    value = value,
                    onValueChange = onChange,
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
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CollapsedSelectorChip(
    selectedCount: Int,
    totalCount: Int,
    onExpand: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FocusableTag(
            text = "站點 $selectedCount/$totalCount · 修改 ▾",
            selected = false,
            onClick = onExpand,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SiteSelector(
    sites: List<Site>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
) {
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
            AppButton(text = "全選", onClick = onSelectAll, primary = false)
            AppButton(text = "全不選", onClick = onSelectNone, primary = false)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sites, key = { it.id }) { s ->
                FocusableTag(
                    text = s.name,
                    selected = s.id in selected,
                    onClick = { onToggle(s.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HistoryPills(items: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "最近搜尋",
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            AppButton(text = "清除", icon = Icons.Filled.ClearAll, onClick = onClear, primary = false)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(items) { kw ->
                FocusableTag(text = kw, selected = false, onClick = { onPick(kw) })
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
