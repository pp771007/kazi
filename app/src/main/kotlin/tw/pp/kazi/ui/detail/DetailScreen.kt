package tw.pp.kazi.ui.detail

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import tw.pp.kazi.data.ApiResult
import tw.pp.kazi.data.FavoriteItem
import tw.pp.kazi.data.HistoryConfig
import tw.pp.kazi.data.HistoryItem
import tw.pp.kazi.data.Site
import tw.pp.kazi.data.VideoDetails
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.Routes
import tw.pp.kazi.ui.WindowSize
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.CopyTextButton
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.FocusableTag
import tw.pp.kazi.ui.components.LoadingState
import tw.pp.kazi.ui.components.SectionHeader
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.isTv
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DetailScreen(siteId: Long, vodId: Long) {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val windowSize = LocalWindowSize.current
    val sites by container.siteRepository.sites.collectAsState()
    val history by container.historyRepository.items.collectAsState()
    val favorites by container.favoriteRepository.items.collectAsState()
    val incognito by container.incognito.collectAsState()
    val scope = rememberCoroutineScope()

    // 從 SearchScreen 聚合卡帶進來的同名他站（一次性），DetailScreen 進入時 consume。
    val peers = remember {
        container.pendingDetailPeers.also { container.pendingDetailPeers = null }
    }

    // siteId / vodId 作為 state — 切換同名站時就地更新不換頁。
    var currentSiteId by rememberSaveable { mutableLongStateOf(siteId) }
    var currentVodId by rememberSaveable { mutableLongStateOf(vodId) }

    val site = remember(sites, currentSiteId) { sites.firstOrNull { it.id == currentSiteId } }
    val isFavorited = remember(favorites, currentSiteId, currentVodId) {
        favorites.any { it.siteId == currentSiteId && it.videoId == currentVodId }
    }

    // 從 player 返回時 NavCompose 會重建 composable，普通 remember 會 reset 害頁面重新 fetch；
    // 用 cache 當初始值 + 命中就跳過 fetch（PlayerScreen 已是這 pattern，這邊對齊）
    val cachedInitial = remember(currentSiteId, currentVodId) {
        container.cachedDetails(currentSiteId, currentVodId)
    }
    var details by remember(currentSiteId, currentVodId) { mutableStateOf<VideoDetails?>(cachedInitial) }
    var loading by remember(currentSiteId, currentVodId) { mutableStateOf(cachedInitial == null) }
    var errorMsg by remember(currentSiteId, currentVodId) { mutableStateOf<String?>(null) }
    // selectedSource 改 saveable，使用者手動切過 source 後 → 看影片 → 返回，不要被打回 0
    var selectedSource by rememberSaveable(currentSiteId, currentVodId) { mutableIntStateOf(0) }
    // 「依 history 自動挑 source」只在這部影片第一次顯示時做一次，之後讓使用者選擇主導
    var didAutoPickSource by rememberSaveable(currentSiteId, currentVodId) { mutableStateOf(false) }
    var episodesReversed by rememberSaveable { mutableStateOf(false) }
    val firstEpisodeFocus = remember { FocusRequester() }
    val resumeFocus = remember { FocusRequester() }
    // 進入頁面只 focus 一次；切 peer (currentSiteId/vodId 變動) 視為重新進入，要重新搶焦
    var pendingInitialFocus by remember(currentSiteId, currentVodId) { mutableStateOf(true) }

    val historyItem = remember(history, currentSiteId, currentVodId) {
        history.firstOrNull { it.siteId == currentSiteId && it.videoId == currentVodId }
    }

    LaunchedEffect(currentSiteId, currentVodId) {
        if (site == null) {
            errorMsg = "找不到對應站點"
            loading = false
            return@LaunchedEffect
        }
        // cache 命中：略過 API（從 player 返回 / 切 peer 又回來時 reload 的根因）
        if (details != null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        errorMsg = null
        when (val r = container.macCmsApi.fetchDetails(site, currentVodId)) {
            is ApiResult.Success -> {
                details = r.data
                container.cacheDetails(currentSiteId, currentVodId, r.data)
            }
            is ApiResult.Error -> errorMsg = r.message
        }
        loading = false
    }

    // source 自動挑：第一次進這部影片時依 history（或第一個有集數的 source）挑一次；
    // 已挑過就不再覆寫使用者後來的選擇。details / historyItem 任一變化都重新評估這個條件
    LaunchedEffect(details, historyItem, didAutoPickSource) {
        val d = details
        if (d == null || didAutoPickSource) return@LaunchedEffect
        val firstNonEmpty = d.sources.indexOfFirst { it.episodes.isNotEmpty() }
        val fromHistory = historyItem
            ?.takeIf { it.sourceIndex in d.sources.indices }
            ?.takeIf { d.sources[it.sourceIndex].episodes.isNotEmpty() }
            ?.sourceIndex
        selectedSource = fromHistory ?: firstNonEmpty.coerceAtLeast(0)
        didAutoPickSource = true
    }

    // 載入完才 focus；歷史有效就 focus 繼續觀看，否則 fallback 第一集。
    // delay 50ms 等 focusable 真的 attach 到 layout，否則 request 會在 attach 前打到 → 失效。
    // 只在 TV 跑：手機觸控不需要 visible focus 起點
    LaunchedEffect(details) {
        if (!windowSize.isTv) return@LaunchedEffect
        if (details == null || !pendingInitialFocus) return@LaunchedEffect
        kotlinx.coroutines.delay(50)
        val canResume = historyItem != null
            && historyItem.positionMs > HistoryConfig.POSITION_IGNORED_THRESHOLD_MS
        val target = if (canResume) resumeFocus else firstEpisodeFocus
        runCatching { target.requestFocus() }
        pendingInitialFocus = false
    }

    // 反序切換：focus 顯示順序的第一集（保留原本行為），但只在初次 focus 已完成後才生效，
    // 不然 details 第一次載入時這條跟初次 focus 那條會搶
    LaunchedEffect(episodesReversed) {
        if (!windowSize.isTv) return@LaunchedEffect
        if (details == null || pendingInitialFocus) return@LaunchedEffect
        kotlinx.coroutines.delay(50)
        runCatching { firstEpisodeFocus.requestFocus() }
    }

    if (loading) { LoadingState(); return }
    if (errorMsg != null || details == null) {
        EmptyState(
            title = "載入失敗",
            subtitle = errorMsg ?: "找不到影片",
            icon = Icons.Filled.ErrorOutline,
            action = {
                AppButton(
                    text = "返回",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = { nav.popBackStack() },
                )
            },
        )
        return
    }

    val d = details!!

    // 點海報全螢幕放大；再點背景或按返回鍵收起。Dialog 處理 z-order + 系統返回鍵 dismiss。
    var zoomedPosterUrl by remember { mutableStateOf<String?>(null) }

    val onToggleFavorite: () -> Unit = {
        site?.let { currentSite ->
            scope.launch {
                container.favoriteRepository.toggle(
                    FavoriteItem(
                        videoId = currentVodId,
                        videoName = d.video.vodName,
                        videoPic = d.video.vodPic,
                        vodRemarks = d.video.vodRemarks,
                        siteId = currentSite.id,
                        siteName = currentSite.name,
                        siteUrl = currentSite.url,
                    )
                )
            }
        }
    }

    val onPeerPick: (tw.pp.kazi.data.Video) -> Unit = { peer ->
        val sid = peer.fromSiteId
        if (sid != null && (sid != currentSiteId || peer.vodId != currentVodId)) {
            currentSiteId = sid
            currentVodId = peer.vodId
        }
    }

    // 「下一集」相對於 history 紀錄的源跟集數；對齊「繼續觀看」的 source（不是當前 selectedSource，
    // 不然切 source 後 下一集 含意會跟 繼續觀看 脫節）。沒下一集就 null，layout 不渲染按鈕
    val onPlayNext: (() -> Unit)? = run {
        val h = historyItem ?: return@run null
        val src = d.sources.getOrNull(h.sourceIndex) ?: return@run null
        val nextIdx = h.episodeIndex + 1
        if (nextIdx !in src.episodes.indices) return@run null
        val handler: () -> Unit = {
            nav.navigate(Routes.player(currentSiteId, currentVodId, h.sourceIndex, nextIdx))
        }
        handler
    }

    val onSearchByName: (String) -> Unit = { name ->
        nav.navigate(Routes.search(name))
    }

    // 點集數播放：剛好點到「上次看的那一集」（同 source、同 episode）且 history 有效（> threshold）
    // → 從 history positionMs 接著看；其他情況從頭開始。避免使用者本來想繼續，結果不小心點同一集
    // 被打回 0:00。對齊「繼續觀看」按鈕的行為。
    val onEpisodePlay: (Int) -> Unit = { idx ->
        val h = historyItem
        val resumePos = if (h != null
            && h.sourceIndex == selectedSource
            && h.episodeIndex == idx
            && h.positionMs > HistoryConfig.POSITION_IGNORED_THRESHOLD_MS) {
            h.positionMs
        } else 0L
        nav.navigate(Routes.player(currentSiteId, currentVodId, selectedSource, idx, resumePos))
    }

    // Compact (手機直版) → 單欄垂直版型
    // Medium (手機橫向) → Wide 但 poster 欄縮窄，標題立刻可見 + 右邊有內容
    // Expanded (電視盒 / 大平板) → Wide 標準寬度
    if (windowSize.isCompact) {
        CompactLayout(
            d = d, site = site, siteId = currentSiteId, vodId = currentVodId,
            selectedSource = selectedSource,
            onSourcePick = { selectedSource = it },
            historyItem = historyItem,
            firstEpisodeFocus = firstEpisodeFocus,
            resumeFocus = resumeFocus,
            isFavorited = isFavorited,
            onToggleFavorite = onToggleFavorite,
            onBack = { nav.popBackStack() },
            onResume = { h ->
                nav.navigate(
                    Routes.player(currentSiteId, currentVodId, h.sourceIndex, h.episodeIndex, h.positionMs)
                )
            },
            onEpisode = onEpisodePlay,
            onPlayNext = onPlayNext,
            onSearchByName = onSearchByName,
            pagePad = windowSize.pagePadding(),
            peers = peers,
            onPeerPick = onPeerPick,
            episodesReversed = episodesReversed,
            onToggleReversed = { episodesReversed = !episodesReversed },
            incognito = incognito,
            onZoomPoster = { zoomedPosterUrl = it },
        )
    } else {
        WideLayout(
            d = d, site = site, siteId = currentSiteId, vodId = currentVodId,
            selectedSource = selectedSource,
            onSourcePick = { selectedSource = it },
            historyItem = historyItem,
            firstEpisodeFocus = firstEpisodeFocus,
            resumeFocus = resumeFocus,
            isFavorited = isFavorited,
            onToggleFavorite = onToggleFavorite,
            onBack = { nav.popBackStack() },
            onResume = { h ->
                nav.navigate(
                    Routes.player(currentSiteId, currentVodId, h.sourceIndex, h.episodeIndex, h.positionMs)
                )
            },
            onEpisode = onEpisodePlay,
            onPlayNext = onPlayNext,
            onSearchByName = onSearchByName,
            peers = peers,
            onPeerPick = onPeerPick,
            episodesReversed = episodesReversed,
            onToggleReversed = { episodesReversed = !episodesReversed },
            incognito = incognito,
            onZoomPoster = { zoomedPosterUrl = it },
            // Medium (手機橫向) 用窄 poster 欄，避免 380dp+2:3=570dp 高的圖把整個螢幕吃掉，
            // 而且 poster 欄縮短後 right column 才有空間放劇情/sources/episodes（不然右邊會空一塊）
            posterColWidth = if (windowSize == WindowSize.Medium) POSTER_COL_WIDTH_MEDIUM
                else POSTER_COL_WIDTH_EXPANDED,
        )
    }

    zoomedPosterUrl?.let { url ->
        FullscreenPosterDialog(
            url = url,
            contentDescription = d.video.vodName,
            onDismiss = { zoomedPosterUrl = null },
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IncognitoBadge() {
    // 點下去關掉無痕 — 跟 Home / Search / History / Favorites 的「🕶 無痕」pill 行為一致
    val container = LocalAppContainer.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x33FFFFFF))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null) {
                container.setIncognito(false)
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            "🕶 無痕模式",
            color = AppColors.OnBg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CompactLayout(
    d: VideoDetails,
    site: Site?,
    siteId: Long,
    vodId: Long,
    selectedSource: Int,
    onSourcePick: (Int) -> Unit,
    historyItem: HistoryItem?,
    firstEpisodeFocus: FocusRequester,
    resumeFocus: FocusRequester,
    isFavorited: Boolean,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    onResume: (HistoryItem) -> Unit,
    onEpisode: (Int) -> Unit,
    onPlayNext: (() -> Unit)?,
    onSearchByName: (String) -> Unit,
    pagePad: Dp,
    peers: List<tw.pp.kazi.data.Video>?,
    onPeerPick: (tw.pp.kazi.data.Video) -> Unit,
    episodesReversed: Boolean,
    onToggleReversed: () -> Unit,
    incognito: Boolean,
    onZoomPoster: (String) -> Unit,
) {
    val context = LocalContext.current
    val v = d.video
    val src = d.sources.getOrNull(selectedSource)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = pagePad, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppButton(
                text = if (isFavorited) "已收藏" else "收藏",
                icon = if (isFavorited) Icons.Filled.Star else Icons.Filled.StarBorder,
                onClick = onToggleFavorite,
                primary = isFavorited,
            )
            if (incognito) IncognitoBadge()
            Spacer(Modifier.weight(1f))
            AppButton(
                text = "返回",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = onBack,
                primary = false,
            )
        }

        PeerRow(peers = peers, currentSiteId = siteId, onPeerPick = onPeerPick)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val posterInteraction = remember { MutableInteractionSource() }
            val posterFocused by posterInteraction.collectIsFocusedAsState()
            val posterScale by animateFloatAsState(
                if (posterFocused) POSTER_FOCUS_SCALE else 1f,
                tween(POSTER_FOCUS_ANIM_MS),
                label = "compact-poster-scale",
            )
            val posterBorderWidth by animateDpAsState(
                if (posterFocused) POSTER_FOCUS_BORDER_WIDTH else 0.dp,
                tween(POSTER_FOCUS_ANIM_MS),
                label = "compact-poster-border",
            )
            Box(
                modifier = Modifier
                    .scale(posterScale)
                    .width(COMPACT_POSTER_W)
                    .aspectRatio(POSTER_ASPECT)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.BgCard)
                    .border(
                        BorderStroke(
                            posterBorderWidth,
                            if (posterFocused) AppColors.FocusRing else Color.Transparent,
                        ),
                        RoundedCornerShape(10.dp),
                    )
                    .then(
                        if (v.vodPic.isNotBlank())
                            Modifier.clickable(
                                interactionSource = posterInteraction,
                                indication = null,
                            ) { onZoomPoster(v.vodPic) }
                        else Modifier
                    ),
            ) {
                if (v.vodPic.isNotBlank()) {
                    val imageRequest = remember(v.vodPic) {
                        ImageRequest.Builder(context).data(v.vodPic).crossfade(true).build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = v.vodName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var titleExpanded by rememberSaveable { mutableStateOf(false) }
                Text(
                    v.vodName,
                    color = AppColors.OnBg,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = if (titleExpanded) Int.MAX_VALUE else 2,
                    overflow = if (titleExpanded) TextOverflow.Visible else TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { titleExpanded = !titleExpanded },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CopyTextButton(text = v.vodName)
                    AppButton(
                        text = "搜尋",
                        icon = Icons.Filled.Search,
                        onClick = { onSearchByName(v.vodName) },
                        primary = false,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (v.vodRemarks.isNotBlank()) BadgeSmall(v.vodRemarks, AppColors.Accent)
                    if (v.vodYear.isNotBlank()) BadgeSmall(v.vodYear, AppColors.Secondary)
                }
                InfoLine("類型", v.typeName)
                InfoLine("站點", site?.name ?: "-")
            }
        }

        if (historyItem != null && historyItem.positionMs > HistoryConfig.POSITION_IGNORED_THRESHOLD_MS) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppButton(
                    text = "繼續觀看：${historyItem.episodeName.ifBlank { "第 ${historyItem.episodeIndex + 1} 集" }}",
                    icon = Icons.Filled.PlayArrow,
                    onClick = { onResume(historyItem) },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(resumeFocus),
                )
                if (onPlayNext != null) {
                    AppButton(
                        text = "下一集",
                        icon = Icons.Filled.SkipNext,
                        onClick = onPlayNext,
                        primary = false,
                    )
                }
            }
        }

        if (v.vodActor.isNotBlank()) InfoLine("演員", v.vodActor, maxLines = 2)
        if (v.vodDirector.isNotBlank()) InfoLine("導演", v.vodDirector)

        if (v.vodContent.isNotBlank()) {
            var contentExpanded by rememberSaveable { mutableStateOf(false) }
            SectionHeader(title = "劇情簡介")
            Text(
                v.vodContent.htmlDecode().trim(),
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (contentExpanded) Int.MAX_VALUE else CONTENT_MAX_LINES,
                overflow = if (contentExpanded) TextOverflow.Visible else TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { contentExpanded = !contentExpanded },
            )
        }

        if (d.sources.size > 1) {
            val currentSourceFocus = remember { FocusRequester() }
            SectionHeader(title = "播放來源")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .focusGroup()
                    .focusRestorer { currentSourceFocus },
            ) {
                itemsIndexed(d.sources) { idx, s ->
                    val isCurrent = idx == selectedSource
                    FocusableTag(
                        text = "${s.flag} (${s.episodes.size})",
                        selected = isCurrent,
                        onClick = { onSourcePick(idx) },
                        modifier = if (isCurrent) Modifier.focusRequester(currentSourceFocus) else Modifier,
                    )
                }
            }
        }

        SectionHeader(
            title = "集數（${src?.episodes?.size ?: 0}）",
            action = {
                if (src != null && src.episodes.isNotEmpty()) {
                    FocusableTag(
                        text = if (episodesReversed) "反序 ↑" else "正序 ↓",
                        selected = episodesReversed,
                        onClick = onToggleReversed,
                    )
                }
            },
        )
        if (src == null || src.episodes.isEmpty()) {
            Text("此來源無集數", color = AppColors.OnBgMuted)
        } else {
            EpisodeGrid(
                episodes = src.episodes,
                selectedSource = selectedSource,
                historyItem = historyItem,
                firstEpisodeFocus = firstEpisodeFocus,
                onEpisode = onEpisode,
                minCell = EPISODE_CELL_MIN_COMPACT,
                reversed = episodesReversed,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun WideLayout(
    d: VideoDetails,
    site: Site?,
    siteId: Long,
    vodId: Long,
    selectedSource: Int,
    onSourcePick: (Int) -> Unit,
    historyItem: HistoryItem?,
    firstEpisodeFocus: FocusRequester,
    resumeFocus: FocusRequester,
    isFavorited: Boolean,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    onResume: (HistoryItem) -> Unit,
    onEpisode: (Int) -> Unit,
    onPlayNext: (() -> Unit)?,
    onSearchByName: (String) -> Unit,
    peers: List<tw.pp.kazi.data.Video>?,
    onPeerPick: (tw.pp.kazi.data.Video) -> Unit,
    episodesReversed: Boolean,
    onToggleReversed: () -> Unit,
    incognito: Boolean,
    onZoomPoster: (String) -> Unit,
    posterColWidth: Dp,
) {
    val context = LocalContext.current
    val v = d.video
    val src = d.sources.getOrNull(selectedSource)

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(posterColWidth)
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(Color(0xFF1A1A2E), AppColors.Bg)))
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppButton(
                    text = if (isFavorited) "已收藏" else "收藏",
                    icon = if (isFavorited) Icons.Filled.Star else Icons.Filled.StarBorder,
                    onClick = onToggleFavorite,
                    primary = isFavorited,
                )
                if (incognito) IncognitoBadge()
                Spacer(Modifier.weight(1f))
                AppButton(
                    text = "返回",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = onBack,
                    primary = false,
                )
            }
            PeerRow(peers = peers, currentSiteId = siteId, onPeerPick = onPeerPick)
            val posterInteraction = remember { MutableInteractionSource() }
            val posterFocused by posterInteraction.collectIsFocusedAsState()
            val posterScale by animateFloatAsState(
                if (posterFocused) POSTER_FOCUS_SCALE else 1f,
                tween(POSTER_FOCUS_ANIM_MS),
                label = "wide-poster-scale",
            )
            val posterBorderWidth by animateDpAsState(
                if (posterFocused) POSTER_FOCUS_BORDER_WIDTH else 0.dp,
                tween(POSTER_FOCUS_ANIM_MS),
                label = "wide-poster-border",
            )
            Box(
                modifier = Modifier
                    .scale(posterScale)
                    .fillMaxWidth()
                    .aspectRatio(POSTER_ASPECT)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.BgCard)
                    .border(
                        BorderStroke(
                            posterBorderWidth,
                            if (posterFocused) AppColors.FocusRing else Color.Transparent,
                        ),
                        RoundedCornerShape(14.dp),
                    )
                    .then(
                        if (v.vodPic.isNotBlank())
                            Modifier.clickable(
                                interactionSource = posterInteraction,
                                indication = null,
                            ) { onZoomPoster(v.vodPic) }
                        else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (v.vodPic.isNotBlank()) {
                    val imageRequest = remember(v.vodPic) {
                        ImageRequest.Builder(context).data(v.vodPic).crossfade(true).build()
                    }
                    AsyncImage(
                        model = imageRequest,
                        contentDescription = v.vodName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    androidx.tv.material3.Icon(
                        Icons.Filled.Movie, null,
                        tint = AppColors.OnBgDim,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
            Text(
                v.vodName,
                color = AppColors.OnBg,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CopyTextButton(text = v.vodName)
                AppButton(
                    text = "搜尋",
                    icon = Icons.Filled.Search,
                    onClick = { onSearchByName(v.vodName) },
                    primary = false,
                )
            }
            if (v.vodRemarks.isNotBlank() || v.vodYear.isNotBlank() || v.vodArea.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (v.vodRemarks.isNotBlank()) BadgeSmall(v.vodRemarks, AppColors.Accent)
                    if (v.vodYear.isNotBlank()) BadgeSmall(v.vodYear, AppColors.Secondary)
                    if (v.vodArea.isNotBlank()) BadgeSmall(v.vodArea, AppColors.Primary)
                }
            }
            InfoLine(label = "類型", value = v.typeName)
            InfoLine(label = "導演", value = v.vodDirector)
            InfoLine(label = "演員", value = v.vodActor, maxLines = 3)
            InfoLine(label = "站點", value = site?.name ?: "-")
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // 繼續觀看放在右欄最上方，進頁面就 focus 在這；下一集視 history 而定（沒就不渲染）
            if (historyItem != null && historyItem.positionMs > HistoryConfig.POSITION_IGNORED_THRESHOLD_MS) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        text = "繼續觀看：${historyItem.episodeName.ifBlank { "第 ${historyItem.episodeIndex + 1} 集" }}",
                        icon = Icons.Filled.PlayArrow,
                        onClick = { onResume(historyItem) },
                        modifier = Modifier.focusRequester(resumeFocus),
                    )
                    if (onPlayNext != null) {
                        AppButton(
                            text = "下一集",
                            icon = Icons.Filled.SkipNext,
                            onClick = onPlayNext,
                            primary = false,
                        )
                    }
                }
            }

            if (v.vodContent.isNotBlank()) {
                var contentExpanded by rememberSaveable { mutableStateOf(false) }
                SectionHeader(title = "劇情簡介")
                Text(
                    v.vodContent.htmlDecode().trim(),
                    color = AppColors.OnBgMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (contentExpanded) Int.MAX_VALUE else CONTENT_MAX_LINES,
                    overflow = if (contentExpanded) TextOverflow.Visible else TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { contentExpanded = !contentExpanded },
                )
            }

            if (d.sources.size > 1) {
                val currentSourceFocus = remember { FocusRequester() }
                SectionHeader(title = "播放來源")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .focusGroup()
                        .focusRestorer { currentSourceFocus },
                ) {
                    itemsIndexed(d.sources) { idx, s ->
                        val isCurrent = idx == selectedSource
                        FocusableTag(
                            text = "${s.flag} (${s.episodes.size})",
                            selected = isCurrent,
                            onClick = { onSourcePick(idx) },
                            modifier = if (isCurrent) Modifier.focusRequester(currentSourceFocus) else Modifier,
                        )
                    }
                }
            }

            SectionHeader(
                title = "集數（${src?.episodes?.size ?: 0}）",
                action = {
                    if (src != null && src.episodes.isNotEmpty()) {
                        FocusableTag(
                            text = if (episodesReversed) "反序 ↑" else "正序 ↓",
                            selected = episodesReversed,
                            onClick = onToggleReversed,
                        )
                    }
                },
            )
            if (src == null || src.episodes.isEmpty()) {
                Text("此來源無集數", color = AppColors.OnBgMuted)
            } else {
                EpisodeGrid(
                    episodes = src.episodes,
                    selectedSource = selectedSource,
                    historyItem = historyItem,
                    firstEpisodeFocus = firstEpisodeFocus,
                    onEpisode = onEpisode,
                    minCell = EPISODE_CELL_MIN_WIDE,
                    reversed = episodesReversed,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeGrid(
    episodes: List<tw.pp.kazi.data.Episode>,
    selectedSource: Int,
    historyItem: HistoryItem?,
    firstEpisodeFocus: FocusRequester,
    onEpisode: (Int) -> Unit,
    minCell: Dp,
    reversed: Boolean = false,
) {
    val displayed = remember(episodes, reversed) {
        if (reversed) episodes.withIndex().toList().reversed() else episodes.withIndex().toList()
    }
    // 「正在看」是這個 source 的某一集 → 把 firstEpisodeFocus 從第一格搬到那一格，
    // 這樣外面 requestFocus 進來會直接落在 ▶ 那一集，而不是 ep 1
    val watchingDisplayIdx = remember(displayed, historyItem, selectedSource) {
        if (historyItem?.sourceIndex != selectedSource) return@remember -1
        if (historyItem.episodeIndex !in episodes.indices) return@remember -1
        displayed.indexOfFirst { it.index == historyItem.episodeIndex }
    }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // watching 在第 50 集時，LazyGrid 沒 scroll 過去那一格根本還沒 compose，
    // requestFocus 就吃不到。先 scroll 把 watching 帶進視窗，再讓 focus 落定
    LaunchedEffect(watchingDisplayIdx) {
        if (watchingDisplayIdx > 0) {
            runCatching { gridState.scrollToItem(watchingDisplayIdx) }
        }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = minCell),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.heightIn(min = 200.dp, max = 600.dp),
    ) {
        itemsIndexed(displayed) { displayIdx, indexedEp ->
            val idx = indexedEp.index
            val ep = indexedEp.value
            val watching = historyItem?.sourceIndex == selectedSource &&
                    historyItem.episodeIndex == idx
            // 有正在看那一集 → focus 落在它；沒有就 fallback 第一格
            val attachInitialFocus = if (watchingDisplayIdx >= 0) watching else displayIdx == 0
            FocusableTag(
                text = if (watching) "▶ ${ep.name}" else ep.name,
                selected = watching,
                onClick = { onEpisode(idx) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (attachInitialFocus) Modifier.focusRequester(firstEpisodeFocus) else Modifier),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PeerRow(
    peers: List<tw.pp.kazi.data.Video>?,
    currentSiteId: Long,
    onPeerPick: (tw.pp.kazi.data.Video) -> Unit,
) {
    if (peers == null || peers.size <= 1) return
    val currentFocus = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "同名站點（${peers.size}）",
            color = AppColors.OnBgMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .focusGroup()
                .focusRestorer { currentFocus },
        ) {
            itemsIndexed(peers, key = { _, it -> "${it.fromSiteId}-${it.vodId}" }) { _, peer ->
                val isCurrent = peer.fromSiteId == currentSiteId
                FocusableTag(
                    text = peer.fromSite ?: "未知",
                    selected = isCurrent,
                    onClick = { onPeerPick(peer) },
                    modifier = if (isCurrent) Modifier.focusRequester(currentFocus) else Modifier,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BadgeSmall(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.25f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfoLine(label: String, value: String, maxLines: Int = 1) {
    if (value.isBlank()) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            color = AppColors.OnBgDim,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.widthIn(min = 32.dp),
        )
        Text(
            value,
            color = AppColors.OnBg,
            style = MaterialTheme.typography.labelSmall,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun String.htmlDecode(): String = this
    .replace("&nbsp;", " ")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace(Regex("<[^>]+>"), "")

/**
 * 點海報展開的全螢幕預覽。ContentScale.Fit 保留原比例不裁切，背景填黑、留白讓圖
 * 不變形。點任意處或按系統返回鍵 dismiss（Dialog 預設處理 back press）。
 * Dialog 用 usePlatformDefaultWidth=false 撐到整個螢幕。
 */
@Composable
private fun FullscreenPosterDialog(
    url: String,
    contentDescription: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val interaction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                // indication=null：全螢幕 ripple 動畫視覺很怪，純當 dismiss 區
                .clickable(interactionSource = interaction, indication = null) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// 海報 focus 動畫：放大 + 加白色 border，給 TV D-pad 焦點明顯回饋。
// scale 1.06 / border 4dp 跟 PosterCard 的設定接近，視覺一致
private const val POSTER_FOCUS_SCALE = 1.06f
private val POSTER_FOCUS_BORDER_WIDTH = 4.dp
private const val POSTER_FOCUS_ANIM_MS = 120

// Wide layout poster 欄寬度。Expanded（電視盒 / 大平板）用 380dp 走標準大圖；
// Medium（手機橫向 ~800×360）用 240dp，左右分欄比例 ~30/70，標題立刻可見、右邊內容有空間
private val POSTER_COL_WIDTH_EXPANDED = 380.dp
private val POSTER_COL_WIDTH_MEDIUM = 240.dp
private val COMPACT_POSTER_W = 120.dp
private const val POSTER_ASPECT = 2f / 3f
private const val CONTENT_MAX_LINES = 6
private val EPISODE_CELL_MIN_COMPACT = 80.dp
private val EPISODE_CELL_MIN_WIDE = 110.dp
