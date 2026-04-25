package tw.pp.kazi.ui.history

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.draw.scale
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
import tw.pp.kazi.ui.Routes
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.EmptyState
import tw.pp.kazi.ui.components.GradientTopBar
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

    Column(modifier = Modifier.fillMaxSize()) {
        GradientTopBar(
            title = "觀看歷史",
            subtitle = "${items.size} 筆紀錄",
            titleBadges = if (incognito) {
                { tw.pp.kazi.ui.components.StatusPill("🕶 無痕（不會新增）") }
            } else null,
            trailing = {
                AppButton(
                    text = if (checking) "檢查中" else "檢查更新",
                    icon = Icons.Filled.Refresh,
                    iconOnly = compact,
                    onClick = {
                        scope.launch {
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
                AppButton(
                    text = "清空",
                    icon = Icons.Filled.DeleteSweep,
                    onClick = { scope.launch { container.historyRepository.clear() } },
                    enabled = items.isNotEmpty(),
                    primary = false,
                    danger = true,
                    iconOnly = compact,
                )
                AppButton(
                    text = "返回",
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = { nav.popBackStack() },
                    primary = false,
                    iconOnly = compact,
                )
            },
        )

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
                HistoryRow(
                    item = item,
                    onResume = {
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
                    onOpen = {
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HistoryRow(
    item: HistoryItem,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, tween(180), label = "hist-scale")
    val borderColor by animateColorAsState(
        if (focused) AppColors.FocusRing else Color(0x22FFFFFF), tween(160), label = "hist-border"
    )

    val progress = if (item.durationMs > 0) item.positionMs.toFloat() / item.durationMs.toFloat() else 0f

    Row(
        modifier = Modifier
            .scale(scale)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppColors.BgCard)
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null) { onResume() }
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
                AsyncImage(
                    model = ImageRequest.Builder(context).data(item.videoPic).crossfade(true).build(),
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
            Text(
                "${item.siteName} · ${item.episodeName}",
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
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppButton(text = "詳情", icon = Icons.Filled.Info, onClick = onOpen, primary = false)
                AppButton(text = "刪除", icon = Icons.Filled.Delete, onClick = onDelete, primary = false, danger = true)
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
