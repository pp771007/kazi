package tw.pp.kazi.ui.player

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tw.pp.kazi.data.ApiResult
import tw.pp.kazi.data.HistoryConfig
import tw.pp.kazi.data.HistoryItem
import tw.pp.kazi.data.MacCmsApiSpec
import tw.pp.kazi.data.PlayerConfig
import tw.pp.kazi.data.VideoDetails
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.components.AppButton
import tw.pp.kazi.ui.components.FocusableTag
import tw.pp.kazi.ui.components.LoadingState
import tw.pp.kazi.ui.theme.AppColors
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.math.abs

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    siteId: Long,
    vodId: Long,
    sourceIdx: Int,
    episodeIdx: Int,
    resumePositionMs: Long = 0L,
) {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val sites by container.siteRepository.sites.collectAsState()
    val site = remember(sites, siteId) { sites.firstOrNull { it.id == siteId } }
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val scope = rememberCoroutineScope()

    var details by remember(siteId, vodId) {
        mutableStateOf<VideoDetails?>(container.cachedDetails(siteId, vodId))
    }
    var currentSourceIdx by remember(siteId, vodId) { mutableIntStateOf(sourceIdx) }
    var currentEpIdx by remember(siteId, vodId) { mutableIntStateOf(episodeIdx) }
    var loading by remember(siteId, vodId) { mutableStateOf(details == null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(PlayerConfig.DEFAULT_SPEED) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var pendingResumeMs by remember(siteId, vodId, sourceIdx, episodeIdx) {
        mutableLongStateOf(resumePositionMs)
    }
    var gestureIndicator by remember { mutableStateOf<GestureIndicator?>(null) }

    val player = remember {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(MacCmsApiSpec.DEFAULT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val msFactory = DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(msFactory)
            .build().apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    val currentEpisode = remember(details, currentSourceIdx, currentEpIdx) {
        details?.sources?.getOrNull(currentSourceIdx)?.episodes?.getOrNull(currentEpIdx)
    }

    LaunchedEffect(siteId, vodId) {
        if (details != null) return@LaunchedEffect
        val s = site ?: return@LaunchedEffect
        val r = container.macCmsApi.fetchDetails(s, vodId)
        if (r is ApiResult.Success) {
            details = r.data
            container.cacheDetails(siteId, vodId, r.data)
        }
        loading = false
    }

    LaunchedEffect(currentEpisode) {
        val ep = currentEpisode ?: return@LaunchedEffect
        playbackError = null
        val mediaItem = MediaItem.Builder()
            .setUri(ep.url)
            .apply {
                if (ep.url.endsWith(".m3u8", ignoreCase = true)) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        val resumeAt = pendingResumeMs
        if (resumeAt > HistoryConfig.POSITION_IGNORED_THRESHOLD_MS) {
            player.seekTo(resumeAt)
        }
        pendingResumeMs = 0L
        player.play()
    }

    val playerListener = remember {
        object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p }
            override fun onPlayerError(error: PlaybackException) {
                playbackError = friendlyPlaybackError(error)
                val epName = currentEpisode?.name.orEmpty()
                val url = currentEpisode?.url.orEmpty()
                tw.pp.kazi.util.Logger.w(
                    "Player error on '$epName' (${error.errorCodeName}): url=${url.take(PlayerConfig.URL_LOG_MAX_CHARS)}",
                )
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    val d = details ?: return
                    val src = d.sources.getOrNull(currentSourceIdx) ?: return
                    if (currentEpIdx < src.episodes.size - 1) {
                        currentEpIdx += 1
                    }
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val w = videoSize.width
                val h = videoSize.height
                if (w <= 0 || h <= 0) return
                val ratio = w.toFloat() / h.toFloat()
                activity?.requestedOrientation = when {
                    ratio > PlayerConfig.ORIENTATION_LANDSCAPE_THRESHOLD ->
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    ratio < PlayerConfig.ORIENTATION_PORTRAIT_THRESHOLD ->
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }
    DisposableEffect(playerListener) {
        player.addListener(playerListener)
        onDispose { player.removeListener(playerListener) }
    }

    LaunchedEffect(currentEpisode) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.coerceAtLeast(0)
            delay(PlayerConfig.POSITION_POLL_MS)
        }
    }

    LaunchedEffect(currentEpisode) {
        while (true) {
            delay(HistoryConfig.SAVE_INTERVAL_MS)
            saveHistoryIfReady(
                container = container, site = site, details = details,
                vodId = vodId, sourceIdx = currentSourceIdx, episodeIdx = currentEpIdx,
                episodeName = currentEpisode?.name.orEmpty(),
                positionMs = player.currentPosition, durationMs = player.duration,
                scope = scope,
            )
        }
    }

    LaunchedEffect(speed) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // 離開播放器時把系統列叫回來，避免影響其他畫面
            activity?.window?.let { w ->
                val c = WindowCompat.getInsetsController(w, w.decorView)
                c.show(WindowInsetsCompat.Type.systemBars())
            }
            // 用 appScope，避免 rememberCoroutineScope 在 dispose 時剛好被 cancel 導致歷史沒寫進去
            saveHistoryIfReady(
                container = container, site = site, details = details,
                vodId = vodId, sourceIdx = currentSourceIdx, episodeIdx = currentEpIdx,
                episodeName = currentEpisode?.name.orEmpty(),
                positionMs = player.currentPosition, durationMs = player.duration,
                scope = container.appScope,
            )
            player.release()
        }
    }

    // 橫式全螢幕：藏掉狀態列／導覽列，讓影片真正鋪滿；直式維持原樣（保留狀態列）。
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(isLandscape) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { /* 還原放在外層 onDispose */ }
    }

    LaunchedEffect(controlsVisible) {
        if (controlsVisible) {
            delay(PlayerConfig.CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    LaunchedEffect(gestureIndicator) {
        if (gestureIndicator != null) {
            delay(PlayerConfig.GESTURE_INDICATOR_HIDE_MS)
            gestureIndicator = null
        }
    }

    BackHandler {
        if (showSpeedMenu) showSpeedMenu = false else nav.popBackStack()
    }

    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    val keyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { keyFocusRequester.requestFocus() }
    }

    // DPAD 連按 ←/→ 加速 seek：階梯從 10s → 30s → 1m → 5m → 10m，停按 1.5 秒重置
    var seekLadderIdx by remember { mutableIntStateOf(0) }
    var lastSeekKeyMs by remember { mutableLongStateOf(0L) }

    fun nextSeekStep(): Long {
        val now = System.currentTimeMillis()
        val ladder = PlayerConfig.SEEK_LADDER_MS
        seekLadderIdx = if (now - lastSeekKeyMs > PlayerConfig.SEEK_LADDER_RESET_MS) 0
            else (seekLadderIdx + 1).coerceAtMost(ladder.size - 1)
        lastSeekKeyMs = now
        return ladder[seekLadderIdx]
    }

    fun cyclePlaybackSpeed(forward: Boolean) {
        val speeds = PlayerConfig.PLAYBACK_SPEEDS
        val curIdx = speeds.indexOfFirst { it == speed }.let { if (it < 0) speeds.size / 2 else it }
        val nextIdx = if (forward) (curIdx + 1).coerceAtMost(speeds.size - 1)
            else (curIdx - 1).coerceAtLeast(0)
        speed = speeds[nextIdx]
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(keyFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                controlsVisible = true
                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                        if (player.isPlaying) player.pause() else player.play(); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        val step = nextSeekStep()
                        val target = (player.currentPosition - step).coerceAtLeast(0)
                        player.seekTo(target)
                        gestureIndicator = GestureIndicator.Seek(
                            targetMs = target,
                            deltaMs = -step,
                            durationMs = player.duration,
                        )
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val step = nextSeekStep()
                        val target = (player.currentPosition + step).coerceAtMost(player.duration)
                        player.seekTo(target)
                        gestureIndicator = GestureIndicator.Seek(
                            targetMs = target,
                            deltaMs = step,
                            durationMs = player.duration,
                        )
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        cyclePlaybackSpeed(forward = true)
                        gestureIndicator = GestureIndicator.Speed(speed)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        cyclePlaybackSpeed(forward = false)
                        gestureIndicator = GestureIndicator.Speed(speed)
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN -> {
                        val src = details?.sources?.getOrNull(currentSourceIdx) ?: return@onPreviewKeyEvent false
                        if (currentEpIdx < src.episodes.size - 1) currentEpIdx += 1
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP -> {
                        if (currentEpIdx > 0) currentEpIdx -= 1
                        true
                    }
                    else -> false
                }
            },
    ) {
        if (loading) {
            LoadingState(label = "載入影片⋯")
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        this.setKeepContentOnPlayerReset(true)
                        // 不要搶掉外層 Compose Box 的 focus，否則 DPAD 事件不會進到 onPreviewKeyEvent
                        isFocusable = false
                        isFocusableInTouchMode = false
                        descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 觸控手勢層（點擊切控制列、雙擊暫停、拖曳手勢）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = {
                                if (player.isPlaying) player.pause() else player.play()
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        var mode: DragMode? = null
                        var startX = 0f
                        var startY = 0f
                        var startPos = 0L
                        var startBrightness = 0f
                        var startVolume = 0
                        detectDragGestures(
                            onDragStart = { offset: Offset ->
                                mode = null
                                startX = offset.x
                                startY = offset.y
                                startPos = player.currentPosition
                                startBrightness = currentBrightness(activity)
                                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                controlsVisible = true
                            },
                            onDrag = { change, _ ->
                                // 每次 drag 都重讀 size，避開旋轉後尺寸過期的問題
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val dx = change.position.x - startX
                                val dy = change.position.y - startY
                                if (mode == null) {
                                    if (abs(dx) > PlayerConfig.GESTURE_DECISION_PX || abs(dy) > PlayerConfig.GESTURE_DECISION_PX) {
                                        mode = when {
                                            abs(dx) > abs(dy) -> DragMode.SEEK
                                            startX < w / HALF_DIVIDER -> DragMode.BRIGHTNESS
                                            else -> DragMode.VOLUME
                                        }
                                    }
                                }
                                when (mode) {
                                    DragMode.SEEK -> {
                                        val maxSeekMs = (player.duration.coerceAtLeast(0) / 3L)
                                            .coerceAtMost(PlayerConfig.SEEK_GESTURE_MAX_MS)
                                        val deltaMs = ((dx / w) * maxSeekMs).toLong()
                                        val target = (startPos + deltaMs)
                                            .coerceIn(0, player.duration.coerceAtLeast(0))
                                        gestureIndicator = GestureIndicator.Seek(
                                            targetMs = target,
                                            deltaMs = target - startPos,
                                            durationMs = player.duration,
                                        )
                                    }
                                    DragMode.BRIGHTNESS -> {
                                        val delta = -(dy / h)
                                        val newVal = (startBrightness + delta).coerceIn(0f, 1f)
                                        applyBrightness(activity, newVal)
                                        gestureIndicator = GestureIndicator.Brightness(newVal)
                                    }
                                    DragMode.VOLUME -> {
                                        val delta = -(dy / h) * maxVolume
                                        val newVal = (startVolume + delta).toInt().coerceIn(0, maxVolume)
                                        audioManager.setStreamVolume(
                                            AudioManager.STREAM_MUSIC, newVal, 0,
                                        )
                                        gestureIndicator = GestureIndicator.Volume(newVal, maxVolume)
                                    }
                                    null -> Unit
                                }
                            },
                            onDragEnd = {
                                if (mode == DragMode.SEEK) {
                                    (gestureIndicator as? GestureIndicator.Seek)?.let {
                                        player.seekTo(it.targetMs)
                                    }
                                }
                                mode = null
                            },
                            onDragCancel = { mode = null },
                        )
                    },
            )

            playbackError?.let {
                ErrorOverlay(
                    message = it,
                    onRetry = {
                        playbackError = null
                        player.prepare()
                        player.play()
                    },
                    onClose = { nav.popBackStack() },
                )
            }

            gestureIndicator?.let { indicator ->
                GestureOverlay(indicator, modifier = Modifier.align(Alignment.Center))
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ControlsBar(
                    title = details?.video?.vodName.orEmpty(),
                    epName = currentEpisode?.name.orEmpty(),
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    speed = speed,
                    showSpeedMenu = showSpeedMenu,
                    onToggleSpeedMenu = { showSpeedMenu = !showSpeedMenu },
                    onPickSpeed = { speed = it; showSpeedMenu = false },
                    onSeekTo = { target ->
                        val dur = player.duration.coerceAtLeast(0)
                        val clamped = target.coerceIn(0, if (dur > 0) dur else target)
                        player.seekTo(clamped)
                        positionMs = clamped
                        controlsVisible = true
                    },
                )
            }
        }
    }
}

/**
 * ExoPlayer 的 error code 很難懂，轉成使用者看得懂的訊息。
 * 末尾附上代碼給開發者 debug。
 */
private fun friendlyPlaybackError(error: PlaybackException): String {
    val base = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "無法連線到影片伺服器，可能是該來源已失效"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "影片伺服器拒絕請求（可能需要特定 User-Agent 或已下架）"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "影片不存在（404）"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "沒有權限存取這個影片"
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
            "影片伺服器回傳非影片內容（可能是網頁）"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "影片格式不支援或檔案損毀"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
            "HLS / DASH 清單檔案解析失敗"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
            "解碼失敗，這台裝置可能不支援這個影片的編碼"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ->
            "音訊初始化失敗"
        else -> "播放失敗（${error.errorCodeName}）"
    }
    return base
}

private enum class DragMode { SEEK, BRIGHTNESS, VOLUME }

private sealed class GestureIndicator {
    data class Seek(val targetMs: Long, val deltaMs: Long, val durationMs: Long) : GestureIndicator()
    data class Brightness(val value: Float) : GestureIndicator()
    data class Volume(val current: Int, val max: Int) : GestureIndicator()
    data class Speed(val value: Float) : GestureIndicator()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GestureOverlay(indicator: GestureIndicator, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 22.dp, vertical = 14.dp),
    ) {
        when (indicator) {
            is GestureIndicator.Seek -> {
                val sign = if (indicator.deltaMs >= 0) "+" else "-"
                val deltaSec = abs(indicator.deltaMs) / 1000
                Text(
                    "${formatDuration(indicator.targetMs)} / ${formatDuration(indicator.durationMs)}  (${sign}${deltaSec}s)",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            is GestureIndicator.Brightness -> {
                Text(
                    "亮度 ${(indicator.value * 100).toInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            is GestureIndicator.Volume -> {
                Text(
                    "音量 ${indicator.current} / ${indicator.max}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            is GestureIndicator.Speed -> {
                Text(
                    "倍速 ${indicator.value}x",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun currentBrightness(activity: android.app.Activity?): Float {
    val current = activity?.window?.attributes?.screenBrightness ?: -1f
    return if (current < 0f) PlayerConfig.BRIGHTNESS_DEFAULT_FALLBACK else current
}

private fun applyBrightness(activity: android.app.Activity?, value: Float) {
    val window = activity?.window ?: return
    val lp = window.attributes
    lp.screenBrightness = value
    window.attributes = lp
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ControlsBar(
    title: String,
    epName: String,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    showSpeedMenu: Boolean,
    onToggleSpeedMenu: () -> Unit,
    onPickSpeed: (Float) -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val titleText = if (epName.isBlank()) title else "$title · $epName"
        Text(
            titleText,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ProgressBar(
            positionMs = positionMs,
            durationMs = durationMs,
            onSeek = onSeekTo,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                formatDuration(positionMs) + " / " + formatDuration(durationMs),
                color = Color(0xFFBBBBBB),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (isPlaying) "▶" else "⏸",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            AppButton(
                text = "${speed}x",
                icon = Icons.Filled.Speed,
                onClick = onToggleSpeedMenu,
                primary = false,
            )
        }
        if (showSpeedMenu) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(PlayerConfig.PLAYBACK_SPEEDS.toList()) { s ->
                    FocusableTag(
                        text = "${s}x",
                        selected = s == speed,
                        onClick = { onPickSpeed(s) },
                    )
                }
            }
        }
        Text(
            "遙控器：OK=暫停／←→=快轉（連按加速 10s→30s→1m→5m→10m）／↑↓=切倍速／頻道±=切集。手機：左右滑=進度／左滑直=亮度／右滑直=音量／雙擊=暫停",
            color = Color(0x88FFFFFF),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit, onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.BgCard)
                .padding(24.dp),
        ) {
            androidx.tv.material3.Icon(
                Icons.Filled.ErrorOutline, null,
                tint = AppColors.Error,
                modifier = Modifier.size(48.dp),
            )
            Text(
                "播放失敗",
                color = AppColors.OnBg,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(message, color = AppColors.OnBgMuted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton(text = "重試", icon = Icons.Filled.Refresh, onClick = onRetry)
                AppButton(text = "關閉", onClick = onClose, primary = false)
            }
        }
    }
}

@Composable
private fun ProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    var trackWidthPx by remember { mutableIntStateOf(0) }
    // 拖曳中以 local 進度顯示，避免 UI 跳動（player position 還沒回報就被覆寫）
    var draggingProgress by remember { mutableStateOf<Float?>(null) }

    val seekable = durationMs > 0
    val livedProgress = if (seekable) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val displayProgress = draggingProgress ?: livedProgress

    fun progressFromX(x: Float): Float {
        val w = trackWidthPx.toFloat().coerceAtLeast(1f)
        return (x / w).coerceIn(0f, 1f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 加大可觸控高度（thumb 尺寸方便手指點擊），但視覺只畫細條
            .height(SEEKBAR_TOUCH_HEIGHT)
            .onSizeChanged { size: IntSize -> trackWidthPx = size.width }
            .pointerInput(seekable) {
                if (!seekable) return@pointerInput
                detectTapGestures(
                    onTap = { offset ->
                        val target = (progressFromX(offset.x) * durationMs).toLong()
                        onSeek(target)
                    },
                )
            }
            .pointerInput(seekable) {
                if (!seekable) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        draggingProgress = progressFromX(offset.x)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        draggingProgress = progressFromX(change.position.x)
                    },
                    onDragEnd = {
                        val p = draggingProgress
                        if (p != null) onSeek((p * durationMs).toLong())
                        draggingProgress = null
                    },
                    onDragCancel = { draggingProgress = null },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 軌道
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SEEKBAR_TRACK_HEIGHT)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x33FFFFFF)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(displayProgress)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AppColors.Primary),
            )
        }
        // 拖曳中或已有時長時畫一個小圓點當 thumb，方便看見手指拖到哪
        if (seekable) {
            val thumbSize = if (draggingProgress != null) SEEKBAR_THUMB_DRAG else SEEKBAR_THUMB_IDLE
            Box(
                modifier = Modifier
                    .offset(x = with(androidx.compose.ui.platform.LocalDensity.current) {
                        (trackWidthPx * displayProgress).toDp() - thumbSize / 2
                    })
                    .size(thumbSize)
                    .clip(RoundedCornerShape(thumbSize / 2))
                    .background(AppColors.Primary),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

private fun saveHistoryIfReady(
    container: tw.pp.kazi.AppContainer,
    site: tw.pp.kazi.data.Site?,
    details: VideoDetails?,
    vodId: Long,
    sourceIdx: Int,
    episodeIdx: Int,
    episodeName: String,
    positionMs: Long,
    durationMs: Long,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (container.incognito.value) return
    val s = site ?: return
    val d = details ?: return
    if (positionMs <= 0 || durationMs <= 0) return
    val src = d.sources.getOrNull(sourceIdx)
    scope.launch {
        container.historyRepository.record(
            HistoryItem(
                videoId = vodId,
                videoName = d.video.vodName,
                videoPic = d.video.vodPic,
                siteId = s.id,
                siteName = s.name,
                siteUrl = s.url,
                sourceIndex = sourceIdx,
                episodeIndex = episodeIdx,
                episodeName = episodeName,
                positionMs = positionMs,
                durationMs = durationMs,
                totalEpisodes = src?.episodes?.size ?: 0,
            )
        )
    }
}

// 螢幕寬度分半參數（左半亮度 / 右半音量），留 local 因為只影響手勢區分邏輯
private const val HALF_DIVIDER = 2f

// 進度條：軌道很細但點擊熱區要大，免得手指點不到
private val SEEKBAR_TOUCH_HEIGHT = 28.dp
private val SEEKBAR_TRACK_HEIGHT = 5.dp
private val SEEKBAR_THUMB_IDLE = 12.dp
private val SEEKBAR_THUMB_DRAG = 18.dp
