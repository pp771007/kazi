package tw.pp.kazi.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.FocusableTag
import tw.pp.kazi.ui.components.LoadingState
import tw.pp.kazi.ui.components.SectionHeader
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
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

    var details by remember(currentSiteId, currentVodId) { mutableStateOf<VideoDetails?>(null) }
    var loading by remember(currentSiteId, currentVodId) { mutableStateOf(true) }
    var errorMsg by remember(currentSiteId, currentVodId) { mutableStateOf<String?>(null) }
    var selectedSource by remember(currentSiteId, currentVodId) { mutableIntStateOf(0) }
    var episodesReversed by rememberSaveable { mutableStateOf(false) }
    val firstEpisodeFocus = remember { FocusRequester() }

    val historyItem = remember(history, currentSiteId, currentVodId) {
        history.firstOrNull { it.siteId == currentSiteId && it.videoId == currentVodId }
    }

    LaunchedEffect(currentSiteId, currentVodId) {
        if (site == null) {
            errorMsg = "找不到對應站點"
            loading = false
            return@LaunchedEffect
        }
        loading = true
        errorMsg = null
        when (val r = container.macCmsApi.fetchDetails(site, currentVodId)) {
            is ApiResult.Success -> {
                details = r.data
                container.cacheDetails(currentSiteId, currentVodId, r.data)
                val firstNonEmpty = r.data.sources.indexOfFirst { it.episodes.isNotEmpty() }
                val fromHistory = historyItem
                    ?.takeIf { it.sourceIndex in r.data.sources.indices }
                    ?.takeIf { r.data.sources[it.sourceIndex].episodes.isNotEmpty() }
                    ?.sourceIndex
                selectedSource = fromHistory ?: firstNonEmpty.coerceAtLeast(0)
            }
            is ApiResult.Error -> errorMsg = r.message
        }
        loading = false
    }

    LaunchedEffect(details, episodesReversed) {
        if (details != null) runCatching { firstEpisodeFocus.requestFocus() }
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

    if (windowSize.isCompact) {
        CompactLayout(
            d = d, site = site, siteId = currentSiteId, vodId = currentVodId,
            selectedSource = selectedSource,
            onSourcePick = { selectedSource = it },
            historyItem = historyItem,
            firstEpisodeFocus = firstEpisodeFocus,
            isFavorited = isFavorited,
            onToggleFavorite = onToggleFavorite,
            onBack = { nav.popBackStack() },
            onResume = { h ->
                nav.navigate(
                    Routes.player(currentSiteId, currentVodId, h.sourceIndex, h.episodeIndex, h.positionMs)
                )
            },
            onEpisode = { idx ->
                nav.navigate(Routes.player(currentSiteId, currentVodId, selectedSource, idx))
            },
            pagePad = windowSize.pagePadding(),
            peers = peers,
            onPeerPick = onPeerPick,
            episodesReversed = episodesReversed,
            onToggleReversed = { episodesReversed = !episodesReversed },
            incognito = incognito,
        )
    } else {
        WideLayout(
            d = d, site = site, siteId = currentSiteId, vodId = currentVodId,
            selectedSource = selectedSource,
            onSourcePick = { selectedSource = it },
            historyItem = historyItem,
            firstEpisodeFocus = firstEpisodeFocus,
            isFavorited = isFavorited,
            onToggleFavorite = onToggleFavorite,
            onBack = { nav.popBackStack() },
            onResume = { h ->
                nav.navigate(
                    Routes.player(currentSiteId, currentVodId, h.sourceIndex, h.episodeIndex, h.positionMs)
                )
            },
            onEpisode = { idx ->
                nav.navigate(Routes.player(currentSiteId, currentVodId, selectedSource, idx))
            },
            peers = peers,
            onPeerPick = onPeerPick,
            episodesReversed = episodesReversed,
            onToggleReversed = { episodesReversed = !episodesReversed },
            incognito = incognito,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CopyTextButton(text: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            copied = false
        }
    }
    AppButton(
        text = if (copied) "已複製" else "複製",
        icon = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
        onClick = {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
            copied = true
        },
        primary = false,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IncognitoBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x33FFFFFF))
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

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
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
    isFavorited: Boolean,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    onResume: (HistoryItem) -> Unit,
    onEpisode: (Int) -> Unit,
    pagePad: Dp,
    peers: List<tw.pp.kazi.data.Video>?,
    onPeerPick: (tw.pp.kazi.data.Video) -> Unit,
    episodesReversed: Boolean,
    onToggleReversed: () -> Unit,
    incognito: Boolean,
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppButton(
                text = "返回",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = onBack,
                primary = false,
            )
            AppButton(
                text = if (isFavorited) "已收藏" else "收藏",
                icon = if (isFavorited) Icons.Filled.Star else Icons.Filled.StarBorder,
                onClick = onToggleFavorite,
                primary = isFavorited,
            )
            if (incognito) IncognitoBadge()
        }

        PeerRow(peers = peers, currentSiteId = siteId, onPeerPick = onPeerPick)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(COMPACT_POSTER_W)
                    .aspectRatio(POSTER_ASPECT)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.BgCard),
            ) {
                if (v.vodPic.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(v.vodPic).crossfade(true).build(),
                        contentDescription = v.vodName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        v.vodName,
                        color = AppColors.OnBg,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CopyTextButton(text = v.vodName)
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
            AppButton(
                text = "繼續觀看：${historyItem.episodeName}",
                icon = Icons.Filled.PlayArrow,
                onClick = { onResume(historyItem) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (v.vodActor.isNotBlank()) InfoLine("演員", v.vodActor, maxLines = 2)
        if (v.vodDirector.isNotBlank()) InfoLine("導演", v.vodDirector)

        if (v.vodContent.isNotBlank()) {
            SectionHeader(title = "劇情簡介")
            Text(
                v.vodContent.htmlDecode().trim(),
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = CONTENT_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (d.sources.size > 1) {
            SectionHeader(title = "播放來源")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(d.sources) { idx, s ->
                    FocusableTag(
                        text = "${s.flag} (${s.episodes.size})",
                        selected = idx == selectedSource,
                        onClick = { onSourcePick(idx) },
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

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
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
    isFavorited: Boolean,
    onToggleFavorite: () -> Unit,
    onBack: () -> Unit,
    onResume: (HistoryItem) -> Unit,
    onEpisode: (Int) -> Unit,
    peers: List<tw.pp.kazi.data.Video>?,
    onPeerPick: (tw.pp.kazi.data.Video) -> Unit,
    episodesReversed: Boolean,
    onToggleReversed: () -> Unit,
    incognito: Boolean,
) {
    val context = LocalContext.current
    val v = d.video
    val src = d.sources.getOrNull(selectedSource)

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .width(POSTER_COL_WIDTH)
                .fillMaxHeight()
                .background(Brush.verticalGradient(listOf(Color(0xFF1A1A2E), AppColors.Bg)))
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppButton(
                    text = "返回",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = onBack,
                    primary = false,
                )
                AppButton(
                    text = if (isFavorited) "已收藏" else "收藏",
                    icon = if (isFavorited) Icons.Filled.Star else Icons.Filled.StarBorder,
                    onClick = onToggleFavorite,
                    primary = isFavorited,
                )
                if (incognito) IncognitoBadge()
            }
            PeerRow(peers = peers, currentSiteId = siteId, onPeerPick = onPeerPick)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(POSTER_ASPECT)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.BgCard),
                contentAlignment = Alignment.Center,
            ) {
                if (v.vodPic.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(v.vodPic).crossfade(true).build(),
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
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    v.vodName,
                    color = AppColors.OnBg,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                CopyTextButton(text = v.vodName)
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

            if (historyItem != null && historyItem.positionMs > HistoryConfig.POSITION_IGNORED_THRESHOLD_MS) {
                Spacer(Modifier.height(4.dp))
                AppButton(
                    text = "繼續觀看：${historyItem.episodeName}",
                    icon = Icons.Filled.PlayArrow,
                    onClick = { onResume(historyItem) },
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (v.vodContent.isNotBlank()) {
                SectionHeader(title = "劇情簡介")
                Text(
                    v.vodContent.htmlDecode().trim(),
                    color = AppColors.OnBgMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = CONTENT_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (d.sources.size > 1) {
                SectionHeader(title = "播放來源")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(d.sources) { idx, s ->
                        FocusableTag(
                            text = "${s.flag} (${s.episodes.size})",
                            selected = idx == selectedSource,
                            onClick = { onSourcePick(idx) },
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
    LazyVerticalGrid(
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
            FocusableTag(
                text = if (watching) "▶ ${ep.name}" else ep.name,
                selected = watching,
                onClick = { onEpisode(idx) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (displayIdx == 0) Modifier.focusRequester(firstEpisodeFocus) else Modifier),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PeerRow(
    peers: List<tw.pp.kazi.data.Video>?,
    currentSiteId: Long,
    onPeerPick: (tw.pp.kazi.data.Video) -> Unit,
) {
    if (peers == null || peers.size <= 1) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "同名站點（${peers.size}）",
            color = AppColors.OnBgMuted,
            style = MaterialTheme.typography.labelSmall,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(peers, key = { _, it -> "${it.fromSiteId}-${it.vodId}" }) { _, peer ->
                FocusableTag(
                    text = peer.fromSite ?: "未知",
                    selected = peer.fromSiteId == currentSiteId,
                    onClick = { onPeerPick(peer) },
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

private val POSTER_COL_WIDTH = 380.dp
private val COMPACT_POSTER_W = 120.dp
private const val POSTER_ASPECT = 2f / 3f
private const val CONTENT_MAX_LINES = 6
private val EPISODE_CELL_MIN_COMPACT = 80.dp
private val EPISODE_CELL_MIN_WIDE = 110.dp
