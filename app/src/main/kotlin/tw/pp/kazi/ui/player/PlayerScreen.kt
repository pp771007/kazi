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
import androidx.compose.foundation.focusGroup
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
    // bumped 在每次「使用者活動」時 tick 一下，讓 auto-hide 計時器從最後一次活動重新算
    // （之前用 LaunchedEffect(controlsVisible) 當 key，setter 把 true 寫成 true 不會 restart，
    // 所以按住時計時器是從第一次按下算起，不是最後一次）
    var controlsActivityTick by remember { mutableIntStateOf(0) }
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
    // 雙擊累加 seek（YT 風）：每次雙擊只累加，停止 SEEK_COMMIT_DELAY_MS 才實際 seek
    var pendingSeekDeltaMs by remember { mutableLongStateOf(0L) }
    var pendingSeekStartPos by remember { mutableLongStateOf(0L) }
    var seekCommitTrigger by remember { mutableIntStateOf(0) }

    // 長按 2x 速狀態：hoist 出來讓 drag handler 也能 reset，避免 long-press 跟 drag race 後 speed 卡住
    var longPressActive by remember { mutableStateOf(false) }
    var speedBeforeLongPress by remember { mutableFloatStateOf(PlayerConfig.DEFAULT_SPEED) }
    fun cancelLongPressSpeed() {
        if (longPressActive) {
            speed = speedBeforeLongPress
            longPressActive = false
            gestureIndicator = null
        }
    }

    // 把 delta 累加到 pendingSeekDeltaMs，反向時以當前位置重新起算。
    // 雙擊 (touch) 跟 D-pad LEFT/RIGHT 共用：每次只記累計，等 SEEK_COMMIT_DELAY_MS 沒新事件 commit 一次 seek
    fun accumulateSeek(p: Player, delta: Long) {
        val sameDirection = (pendingSeekDeltaMs == 0L)
            || (pendingSeekDeltaMs > 0) == (delta > 0)
        if (!sameDirection) {
            pendingSeekStartPos = p.currentPosition
            pendingSeekDeltaMs = delta
        } else {
            if (pendingSeekDeltaMs == 0L) {
                pendingSeekStartPos = p.currentPosition
            }
            pendingSeekDeltaMs += delta
        }
    }

    // 網路錯誤時自動 retry 用的 token；onPlayerError 撞到網路類錯誤就 ++，LaunchedEffect 補一次 prepare
    var playerRetryToken by remember { mutableIntStateOf(0) }

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

    val sitesLoaded by container.siteRepository.loaded.collectAsState()
    LaunchedEffect(siteId, vodId, sitesLoaded) {
        if (details != null) return@LaunchedEffect
        // 等 site 列表載完再判斷站台是否存在；不然 App 剛開、sites 還沒讀檔時會誤報「找不到站點」
        if (!sitesLoaded) return@LaunchedEffect
        val s = site
        if (s == null) {
            playbackError = "找不到對應站點"
            loading = false
            return@LaunchedEffect
        }
        when (val r = container.macCmsApi.fetchDetails(s, vodId)) {
            is ApiResult.Success -> {
                details = r.data
                container.cacheDetails(siteId, vodId, r.data)
            }
            // 失敗時要寫進 playbackError，不然 LoadingState 收掉後畫面就一片空黑、使用者只能按返回
            is ApiResult.Error -> playbackError = r.message
        }
        loading = false
    }

    // 網路錯誤後自動 retry：等 2 秒讓網路 settle，再 prepare + play 一次
    LaunchedEffect(playerRetryToken) {
        if (playerRetryToken == 0) return@LaunchedEffect
        delay(PlayerConfig.PLAYER_RETRY_DELAY_MS)
        playbackError = null
        player.prepare()
        player.play()
    }

    // 切集 / 切 source 的時候清掉 retry counter
    LaunchedEffect(currentEpisode, currentSourceIdx) {
        playerRetryToken = 0
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
                val epName = currentEpisode?.name.orEmpty()
                val url = currentEpisode?.url.orEmpty()
                tw.pp.kazi.util.Logger.w(
                    "Player error on '$epName' (${error.errorCodeName}): url=${url.take(PlayerConfig.URL_LOG_MAX_CHARS)}",
                )
                when (error.errorCode) {
                    // 網路類：通常是暫時性，自動 retry 一次
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                        playerRetryToken += 1
                    }
                    // source 類錯誤（解析、解碼、HTTP 4xx/404）：這個 source 本身壞了，自動跳下一個
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                        val d = details
                        if (d != null && currentSourceIdx < d.sources.size - 1) {
                            // 換到下一個 source；盡量保留原本的 ep idx（同一部戲不同源通常集數對齊），
                            // 新源集數不夠時 clamp 到最後一集。原本寫死 0 會讓使用者選第 4 集失敗自動跳到第 1 集
                            val nextIdx = currentSourceIdx + 1
                            val nextSrc = d.sources.getOrNull(nextIdx)
                            val maxEp = (nextSrc?.episodes?.size ?: 1) - 1
                            currentSourceIdx = nextIdx
                            currentEpIdx = currentEpIdx.coerceIn(0, maxEp.coerceAtLeast(0))
                            return
                        }
                        playbackError = friendlyPlaybackError(error)
                    }
                    else -> playbackError = friendlyPlaybackError(error)
                }
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

    LaunchedEffect(controlsVisible, controlsActivityTick) {
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

    // 雙擊累加 seek 的提交：每次 seekCommitTrigger 變動就重新計時，timer 結束才真的 seek
    LaunchedEffect(seekCommitTrigger) {
        if (pendingSeekDeltaMs == 0L) return@LaunchedEffect
        delay(PlayerConfig.SEEK_COMMIT_DELAY_MS)
        val target = (pendingSeekStartPos + pendingSeekDeltaMs)
            .coerceIn(0, player.duration.coerceAtLeast(0))
        player.seekTo(target)
        positionMs = target
        pendingSeekDeltaMs = 0L
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

    // ←/→ 行為：第一下 10 秒小跳；按住時 step = 10 + held + held²/5（秒）
    // 早期 ~+1/s 跟使用者預期接近，後期二次方項加速明顯飛快。throttle 100ms 壓成 10Hz；換方向重置
    var seekHoldStartMs by remember { mutableLongStateOf(0L) }
    var seekHoldDirection by remember { mutableStateOf<Boolean?>(null) }
    var lastSeekHandledMs by remember { mutableLongStateOf(0L) }

    // 回傳要 seek 的毫秒；null 代表本次事件被 throttle 跳過（不 seek 但仍然 consume）
    fun computeSeekStep(forward: Boolean, repeatCount: Int): Long? {
        val now = System.currentTimeMillis()
        val isFirstPress = repeatCount == 0
        val directionChanged = seekHoldDirection != null && seekHoldDirection != forward
        if (isFirstPress || directionChanged) {
            seekHoldStartMs = now
            seekHoldDirection = forward
            lastSeekHandledMs = now
            return PlayerConfig.SEEK_STEP_BASE_S * 1000L
        }
        if (now - lastSeekHandledMs < PlayerConfig.SEEK_HOLD_THROTTLE_MS) return null
        lastSeekHandledMs = now
        val held = (now - seekHoldStartMs) / 1000
        val stepSec = PlayerConfig.SEEK_STEP_BASE_S + held + (held * held) / PlayerConfig.SEEK_HOLD_QUAD_DIVISOR
        return stepSec * 1000L
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
                controlsActivityTick++
                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                        if (player.isPlaying) player.pause() else player.play(); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        val step = computeSeekStep(
                            forward = false,
                            repeatCount = keyEvent.nativeKeyEvent.repeatCount,
                        )
                        if (step != null) {
                            accumulateSeek(player, -step)
                            seekCommitTrigger += 1
                            gestureIndicator = GestureIndicator.Seek(
                                targetMs = (pendingSeekStartPos + pendingSeekDeltaMs)
                                    .coerceIn(0, player.duration.coerceAtLeast(0)),
                                deltaMs = pendingSeekDeltaMs,
                                durationMs = player.duration,
                            )
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        val step = computeSeekStep(
                            forward = true,
                            repeatCount = keyEvent.nativeKeyEvent.repeatCount,
                        )
                        if (step != null) {
                            accumulateSeek(player, step)
                            seekCommitTrigger += 1
                            gestureIndicator = GestureIndicator.Seek(
                                targetMs = (pendingSeekStartPos + pendingSeekDeltaMs)
                                    .coerceIn(0, player.duration.coerceAtLeast(0)),
                                deltaMs = pendingSeekDeltaMs,
                                durationMs = player.duration,
                            )
                        }
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

            // 觸控手勢層（YouTube 風格：單擊切控制列、雙擊左右側 ±10s、雙擊中央暫停、長按 2x 速）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { _ ->
                                tryAwaitRelease()
                                cancelLongPressSpeed()
                            },
                            onLongPress = { _ ->
                                speedBeforeLongPress = speed
                                speed = PlayerConfig.LONG_PRESS_SPEED
                                longPressActive = true
                                gestureIndicator = GestureIndicator.Speed(speed)
                                // 不要在這裡 set controlsVisible — speed 提示靠 gestureIndicator overlay 自己顯示，
                                // 一但把 controlsVisible 設 true，700ms 後 gesture indicator 淡掉時，
                                // 中央暫停按鈕會跟著彈出來（bug）
                            },
                            onTap = {
                                controlsVisible = !controlsVisible
                                if (controlsVisible) controlsActivityTick++
                            },
                            onDoubleTap = { offset ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val step = PlayerConfig.SEEK_STEP_MS
                                val delta = when {
                                    offset.x < w / 3f -> -step
                                    offset.x > w * 2f / 3f -> step
                                    else -> 0L
                                }
                                if (delta == 0L) {
                                    // 中央雙擊 = play/pause
                                    if (player.isPlaying) player.pause() else player.play()
                                } else {
                                    accumulateSeek(player, delta)
                                    val target = (pendingSeekStartPos + pendingSeekDeltaMs)
                                        .coerceIn(0, player.duration.coerceAtLeast(0))
                                    gestureIndicator = GestureIndicator.Seek(
                                        targetMs = target,
                                        deltaMs = pendingSeekDeltaMs,
                                        durationMs = player.duration,
                                    )
                                    seekCommitTrigger += 1 // 重新計時 debounce
                                }
                                controlsVisible = true
                                controlsActivityTick++
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        // 只接管「垂直 drag」做亮度／音量；橫向滑動讓給系統手勢（避免邊緣手勢誤觸）
                        var mode: DragMode? = null
                        var startX = 0f
                        var startY = 0f
                        var startBrightness = 0f
                        var startVolume = 0
                        detectDragGestures(
                            onDragStart = { offset: Offset ->
                                // drag 接管手勢時，如果剛好正在長按 2x，要把 speed 還原（不然 speed 卡在 2x）
                                cancelLongPressSpeed()
                                mode = null
                                startX = offset.x
                                startY = offset.y
                                startBrightness = currentBrightness(activity)
                                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            },
                            onDrag = { change, _ ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                val dx = change.position.x - startX
                                val dy = change.position.y - startY
                                if (mode == null) {
                                    // 垂直明顯主導才接（避免跟系統橫向邊緣手勢撞）
                                    val verticalDominant = abs(dy) > PlayerConfig.GESTURE_DECISION_PX
                                        && abs(dy) > abs(dx) * 1.5f
                                    if (verticalDominant) {
                                        mode = if (startX < w / HALF_DIVIDER) DragMode.BRIGHTNESS
                                            else DragMode.VOLUME
                                        controlsVisible = true
                                        controlsActivityTick++
                                    }
                                }
                                when (mode) {
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
                            onDragEnd = { mode = null },
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
                // Speed 提示靠上方一些，避免擋到正在播的內容；其他（亮度/音量/seek）維持置中
                val alignment = if (indicator is GestureIndicator.Speed) Alignment.TopCenter else Alignment.Center
                val topPad = if (indicator is GestureIndicator.Speed) GESTURE_INDICATOR_TOP_PAD else 0.dp
                GestureOverlay(
                    indicator,
                    modifier = Modifier.align(alignment).padding(top = topPad),
                )
            }

            // 控制列顯示時，畫面中央也放一顆大的 play/pause 按鈕（YT pattern）。
            // gestureIndicator 出現時暫時藏掉，免得跟 seek 累加 indicator 疊在一起。
            AnimatedVisibility(
                visible = controlsVisible && gestureIndicator == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                CenterPlayPauseButton(
                    isPlaying = isPlaying,
                    onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                        controlsVisible = true
                        controlsActivityTick++
                    },
                )
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
                        controlsActivityTick++
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

private enum class DragMode { BRIGHTNESS, VOLUME }

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
private fun CenterPlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(Color(0x66000000))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.tv.material3.Icon(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "暫停" else "播放",
            tint = Color.White,
            modifier = Modifier.size(44.dp),
        )
    }
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
            // 之前這裡有一顆「▶/⏸」icon — 已經跟中央大圓鈕重複，拿掉。
            AppButton(
                text = "${speed}x",
                icon = Icons.Filled.Speed,
                onClick = onToggleSpeedMenu,
                primary = false,
            )
        }
        if (showSpeedMenu) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.focusGroup(),
            ) {
                items(PlayerConfig.PLAYBACK_SPEEDS.toList()) { s ->
                    FocusableTag(
                        text = "${s}x",
                        selected = s == speed,
                        onClick = { onPickSpeed(s) },
                    )
                }
            }
        }
        // 之前這裡塞一大段操作說明，太嘮叨。手勢／快捷鍵讓使用者自己摸索（YT/Netflix 也都不放）。
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
            val density = androidx.compose.ui.platform.LocalDensity.current
            Box(
                modifier = Modifier
                    .offset(x = with(density) {
                        (trackWidthPx * displayProgress).toDp() - thumbSize / 2
                    })
                    .size(thumbSize)
                    .clip(RoundedCornerShape(thumbSize / 2))
                    .background(AppColors.Primary),
            )
            // 拖曳中顯示時間 tooltip 在 thumb 上方（YT 風格）
            if (draggingProgress != null) {
                val targetMs = (draggingProgress!! * durationMs).toLong()
                Box(
                    modifier = Modifier
                        .offset(
                            x = with(density) {
                                (trackWidthPx * displayProgress).toDp() - SEEKBAR_TOOLTIP_HALF
                            },
                            y = -SEEKBAR_TOOLTIP_OFFSET_Y,
                        )
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    androidx.tv.material3.Text(
                        text = formatDuration(targetMs),
                        color = Color.White,
                        style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
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
    val s = site ?: return
    val d = details ?: return
    if (positionMs <= 0 || durationMs <= 0) return
    // 跟 HistoryScreen 檢查更新時的 maxOfOrNull 對齊（line 99），不然使用者切到較少集數
    // 的 source 看完後存進的數字是「當前 source 的集數」，下次掃描跟「全部 sources 的最大值」
    // 比，會誤標 hasUpdate / 誤算 newEpisodesCount
    val totalAcrossSources = d.sources.maxOfOrNull { it.episodes.size } ?: 0

    // 無痕模式：只追記「已收藏 / 已有觀看紀錄」的影片進度。
    // 新片（沒收藏沒紀錄）才是真正的「探索狀態」，維持完全無痕；已承諾過的片
    // （主動收藏 / 看過一次）就讓進度記下來方便 resume，不然使用者會在收藏頁看不到進度感到困惑。
    if (container.incognito.value) {
        val key = vodId to s.id
        val isFavorite = container.favoriteRepository.items.value.any {
            it.videoId == key.first && it.siteId == key.second
        }
        val isInHistory = container.historyRepository.items.value.any {
            it.videoId == key.first && it.siteId == key.second
        }
        if (!isFavorite && !isInHistory) return
    }

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
                totalEpisodes = totalAcrossSources,
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
private val SEEKBAR_TOOLTIP_HALF = 30.dp
private val SEEKBAR_TOOLTIP_OFFSET_Y = 32.dp

// Speed 加速提示距離畫面頂端的距離
private val GESTURE_INDICATOR_TOP_PAD = 80.dp
