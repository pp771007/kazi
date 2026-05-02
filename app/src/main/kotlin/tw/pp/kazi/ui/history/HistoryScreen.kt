package tw.pp.kazi.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import tw.pp.kazi.data.ApiResult
import tw.pp.kazi.data.HistoryConfig
import tw.pp.kazi.data.HistoryItem
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.isTv
import tw.pp.kazi.ui.Routes
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.ScreenScaffold
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.pagePadding
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val windowSize = LocalWindowSize.current
    val compact = windowSize.isCompact
    val items by container.historyRepository.items.collectAsState()
    val incognito by container.incognito.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkProgress by remember { mutableStateOf<String?>(null) }

    // 從 player / detail 返回時，focus 回到原本那一列的「繼續」按鈕；
    // 冷進入則 focus 第一筆的「繼續」按鈕。只在 TV 跑（手機觸控不需要起點）。
    val restoreFocusKey = remember { container.historyLastFocusKey }
    val rowFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val firstItemKey = remember(items) { items.firstOrNull()?.let { "${it.siteId}-${it.videoId}" } }
    // 若有 restore key 對得上就用它；否則打第一筆當預設 focus 起點
    val effectiveFocusKey = remember(restoreFocusKey, items) {
        when {
            restoreFocusKey != null && items.any { "${it.siteId}-${it.videoId}" == restoreFocusKey } -> restoreFocusKey
            else -> firstItemKey
        }
    }
    var pendingRowFocus by remember { mutableStateOf(true) }
    LaunchedEffect(items, effectiveFocusKey) {
        if (!windowSize.isTv) return@LaunchedEffect
        if (pendingRowFocus && effectiveFocusKey != null) {
            kotlinx.coroutines.delay(50)
            runCatching { rowFocusRequester.requestFocus() }
            pendingRowFocus = false
        }
    }

    ScreenScaffold(
        title = "觀看歷史",
        subtitle = "${items.size} 筆紀錄",
        titleBadges = if (incognito) {
            { tw.pp.kazi.ui.components.StatusPill("🕶 無痕（新片不留紀錄）") }
        } else null,
        onBack = { nav.popBackStack() },
        trailing = {
                AppButton(
                    text = if (checking) "檢查中" else "檢查更新",
                    icon = Icons.Filled.Refresh,
                    iconOnly = compact,
                    onClick = {
                        // 用 container.appScope 不是 rememberCoroutineScope —— 使用者可能掃到一半就返回，
                        // 那時 rememberCoroutineScope 會 cancel，markUpdateStatus 寫到一半的 row 不一致。
                        // 用 appScope 確保整輪掃完才結束。
                        container.appScope.launch {
                            checking = true
                            checkProgress = null
                            val sites = container.siteRepository.sites.value
                            val target = items.take(HistoryConfig.UPDATE_CHECK_BATCH)
                            var updated = 0
                            var failed = 0
                            target.forEachIndexed { idx, item ->
                                checkProgress = "檢查中 ${idx + 1}/${target.size}：${item.videoName}"
                                val s = sites.firstOrNull { it.id == item.siteId }
                                if (s == null) { failed++; return@forEachIndexed }
                                val r = container.macCmsApi.fetchDetails(s, item.videoId)
                                if (r is ApiResult.Success) {
                                    val total = r.data.sources.maxOfOrNull { it.episodes.size } ?: 0
                                    if (total > item.totalEpisodes) updated++
                                    container.historyRepository.markUpdateStatus(item.videoId, item.siteId, total)
                                } else {
                                    failed++
                                }
                            }
                            checkProgress = "完成：${updated} 部有更新、${failed} 部失敗（共 ${target.size}）"
                            checking = false
                        }
                    },
                    enabled = !checking && items.isNotEmpty(),
                    primary = false,
                )
                tw.pp.kazi.ui.components.ConfirmDeleteButton(
                    text = "清空",
                    icon = Icons.Filled.DeleteSweep,
                    onConfirm = { scope.launch { container.historyRepository.clear() } },
                    enabled = items.isNotEmpty(),
                    iconOnly = compact,
                )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

        checkProgress?.let {
            Text(
                it,
                color = if (checking) AppColors.OnBgMuted else AppColors.Success,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = windowSize.pagePadding(), vertical = 8.dp),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }

        if (items.isEmpty()) {
            EmptyState(
                title = "還沒有觀看紀錄",
                subtitle = "開始看片後會自動記錄上次播放位置",
                icon = Icons.Filled.HistoryToggleOff,
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (compact) HISTORY_CELL_MIN_COMPACT else HISTORY_CELL_MIN_WIDE),
            contentPadding = PaddingValues(
                horizontal = windowSize.pagePadding(),
                vertical = 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { "${it.siteId}-${it.videoId}" }) { item ->
                val key = "${item.siteId}-${item.videoId}"
                HistoryRow(
                    item = item,
                    focusRequester = if (key == effectiveFocusKey) rowFocusRequester else null,
                    onResume = {
                        container.historyLastFocusKey = key
                        nav.navigate(
                            Routes.player(
                                siteId = item.siteId,
                                vodId = item.videoId,
                                sourceIdx = item.sourceIndex,
                                episodeIdx = item.episodeIndex,
                                positionMs = item.positionMs,
                            )
                        )
                    },
                    onPlayNext = {
                        container.historyLastFocusKey = key
                        nav.navigate(
                            Routes.player(
                                siteId = item.siteId,
                                vodId = item.videoId,
                                sourceIdx = item.sourceIndex,
                                episodeIdx = item.episodeIndex + 1,
                                positionMs = 0L,
                            )
                        )
                    },
                    onOpen = {
                        container.historyLastFocusKey = key
                        nav.navigate(Routes.detail(item.siteId, item.videoId))
                    },
                    onDelete = {
                        scope.launch { container.historyRepository.remove(item.videoId, item.siteId) }
                    },
                )
            }
        }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun HistoryRow(
    item: HistoryItem,
    onResume: () -> Unit,
    onPlayNext: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val context = LocalContext.current
    val useCrossfade = LocalWindowSize.current != tw.pp.kazi.ui.WindowSize.Expanded
    val progress = if (item.durationMs > 0) item.positionMs.toFloat() / item.durationMs.toFloat() else 0f
    val hasNextEp = item.totalEpisodes > 0 && item.episodeIndex + 1 < item.totalEpisodes

    // 不在 Row 自己加 clickable —— 之前整列 onClick = onResume，但 TV 遙控進去就直接觸發，
    // 沒辦法選裡面的「詳情」「刪除」。改成 row 純展示，所有動作都用裡面的 button，DPAD 可以一個個 focus。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.BgCard)
            .border(2.dp, Color(0x22FFFFFF), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = POSTER_THUMB_W, height = POSTER_THUMB_H)
                .clip(RoundedCornerShape(8.dp))
                .background(AppColors.ShimmerStart),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (item.videoPic.isNotBlank()) {
                val imageRequest = remember(item.videoPic, useCrossfade) {
                    ImageRequest.Builder(context).data(item.videoPic).crossfade(useCrossfade).build()
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = item.videoName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0x22FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .background(AppColors.Primary),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.videoName,
                    color = AppColors.OnBg,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.hasUpdate) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(AppColors.Accent)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "+${item.newEpisodesCount}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            // 「第 X / Y 集 · 集名」 — 沒有 totalEpisodes 時 fallback 顯示集名
            val episodeLabel = buildString {
                if (item.totalEpisodes > 0) {
                    append("第 ${item.episodeIndex + 1} / ${item.totalEpisodes} 集")
                    if (item.episodeName.isNotBlank() && item.episodeName != "${item.episodeIndex + 1}") {
                        append(" · ${item.episodeName}")
                    }
                } else if (item.episodeName.isNotBlank()) {
                    append(item.episodeName)
                }
            }
            Text(
                if (episodeLabel.isNotBlank()) "${item.siteName} · $episodeLabel" else item.siteName,
                color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${formatDuration(item.positionMs)} / ${formatDuration(item.durationMs)}",
                color = AppColors.OnBgDim,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.height(6.dp))
            val compact = LocalWindowSize.current.isCompact
            // 用 FlowRow 不用 Row：3-4 顆 button 在窄卡片上會排不下，FlowRow 會自動換行
            // 而不是把最後一顆「刪除」擠出畫面
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AppButton(
                    text = "繼續",
                    icon = Icons.Filled.PlayArrow,
                    onClick = onResume,
                    iconOnly = compact,
                    modifier = if (focusRequester != null)
                        Modifier.focusRequester(focusRequester) else Modifier,
                )
                // 下一集 button 一律顯示佔位，沒下一集時 disabled，避免跟有下一集的 row 高度不一致
                AppButton(
                    text = "下一集",
                    icon = Icons.Filled.SkipNext,
                    onClick = onPlayNext,
                    enabled = hasNextEp,
                    iconOnly = compact,
                    primary = false,
                )
                AppButton(
                    text = "詳情",
                    icon = Icons.Filled.Info,
                    onClick = onOpen,
                    iconOnly = compact,
                    primary = false,
                )
                tw.pp.kazi.ui.components.ConfirmDeleteButton(
                    text = "刪除",
                    icon = Icons.Filled.Delete,
                    onConfirm = onDelete,
                    iconOnly = compact,
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "--:--"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

private val HISTORY_CELL_MIN_COMPACT = 280.dp
private val HISTORY_CELL_MIN_WIDE = 320.dp
private val POSTER_THUMB_W = 80.dp
private val POSTER_THUMB_H = 116.dp
