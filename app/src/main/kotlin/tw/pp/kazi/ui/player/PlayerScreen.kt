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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import tw.pp.kazi.data.Episode
import tw.pp.kazi.data.HistoryConfig
import tw.pp.kazi.data.HistoryItem
import tw.pp.kazi.data.Video
import tw.pp.kazi.data.VideoSource
import tw.pp.kazi.data.MacCmsApiSpec
import tw.pp.kazi.data.PlayerConfig
import tw.pp.kazi.data.VideoDetails
import tw.pp.kazi.ui.LocalAppContainer
import tw.pp.kazi.ui.LocalNavController
import tw.pp.kazi.ui.Routes
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.isTv
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
    siteUrl: String = "",
) {
    val container = LocalAppContainer.current
    val nav = LocalNavController.current
    val sites by container.siteRepository.sites.collectAsState()
    // 先用本地 siteId 找;跨裝置同步來的歷史其 siteId 對不上(是 siteUrl 的 hashCode 假值)時,
    // 退而用 siteUrl 找同一個站台 —— 只要本地有加這個站台就能續看,不再卡在「找不到對應站點」。
    val site = remember(sites, siteId, siteUrl) {
        sites.firstOrNull { it.id == siteId }
            ?: if (siteUrl.isNotEmpty()) sites.firstOrNull { it.url == siteUrl } else null
    }
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
    // 自動跳線路時的短暫提示(例如「線路A 無法播放,已自動換到 線路B」),幾秒後自動消失
    var lineNotice by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(PlayerConfig.DEFAULT_SPEED) }
    // 控制面板狀態機(電視用手動按鍵高亮,不走 Compose 焦點 → 不踩 focusGroup 吃 OK / focusProperties 閃退的雷):
    //  barFocused=焦點在底部控制條;openMenu!=None=正在展開某個清單;menuIndex/barIndex=高亮第幾個
    var barFocused by remember { mutableStateOf(false) }
    var barIndex by remember { mutableIntStateOf(0) }
    var openMenu by remember { mutableStateOf(PlayerMenu.None) }
    var menuIndex by remember { mutableIntStateOf(0) }
    // 換站用:拿片名即時跨站搜同名片(第一次開「換站」才搜)
    var peers by remember(siteId, vodId) { mutableStateOf<List<Video>?>(null) }
    var peersLoading by remember(siteId, vodId) { mutableStateOf(false) }
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

    // 把 delta 累加到 pendingSeekDeltaMs。
    // 雙擊 (touch) 跟 D-pad LEFT/RIGHT 共用：每次只記累計，等 SEEK_COMMIT_DELAY_MS 沒新事件 commit 一次 seek。
    // 不論方向永遠累加（不換方向 reset），「往前過頭按 ← 退幾秒」會正確 = 累計 - 10s，
    // 而不是丟掉之前 +30 min 的累計從零開始（之前那設計使用者描述「直接歸零往後」）
    fun accumulateSeek(p: Player, delta: Long) {
        if (pendingSeekDeltaMs == 0L) {
            pendingSeekStartPos = p.currentPosition
        }
        pendingSeekDeltaMs += delta
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
                            // 換到下一個 source。要保留「集數 + 秒數」,不然自動跳源會把續看歸零:
                            // - 集數:用集名/集號智慧對齊(同一部戲不同源集數未必同序),對不到才退回原 idx(clamp)。
                            // - 秒數:已經播出去就用當前位置;一開場就壞(位置=0)則退回原本要續看的點 resumePositionMs。
                            //   設給 pendingResumeMs,讓新 source 的 prepare 接著 seek 過去(跟手動換源同一套)。
                            val nextIdx = currentSourceIdx + 1
                            val nextEps = d.sources.getOrNull(nextIdx)?.episodes ?: emptyList()
                            val playedPos = player.currentPosition.coerceAtLeast(0)
                            pendingResumeMs =
                                if (playedPos > HistoryConfig.POSITION_IGNORED_THRESHOLD_MS) playedPos
                                else resumePositionMs
                            currentEpIdx = matchEpisodeIndex(epName, currentEpIdx, nextEps)
                            // 提示使用者「原線路壞了、自動換到哪條」,別讓它默默跳走
                            val failedFlag = d.sources.getOrNull(currentSourceIdx)?.flag?.ifBlank { "線路${currentSourceIdx + 1}" } ?: "線路${currentSourceIdx + 1}"
                            val nextFlag = d.sources.getOrNull(nextIdx)?.flag?.ifBlank { "線路${nextIdx + 1}" } ?: "線路${nextIdx + 1}"
                            lineNotice = "「$failedFlag」無法播放,已自動換到「$nextFlag」"
                            currentSourceIdx = nextIdx
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
                // 還原手勢調過的螢幕亮度,不然返回後整個 app 都維持播放時調的亮度
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
            // 用 appScope，避免 rememberCoroutineScope 在 dispose 時剛好被 cancel 導致歷史沒寫進去。
            // episodeName 不能用 currentEpisode：它是每次 recomposition 重算的 val，而這個 onDispose
            // closure 是 DisposableEffect(Unit) 第一次組合時建好就不再換的，會永遠 capture 到「進播放器當下那一集」。
            // 自動連播跳下一集後，currentEpIdx 是 state 會讀到新值、但 currentEpisode?.name 還停在第一集，
            // 存進去就變成「集號=2、集名=第一集」，外面繼續觀看顯示成第一集。改成用 fresh state 現查集名。
            val endEpisode = details?.sources?.getOrNull(currentSourceIdx)?.episodes?.getOrNull(currentEpIdx)
            // player.release() 之後就讀不到正確進度,先把秒數抓下來再丟進背景協程
            val exitPositionMs = player.currentPosition
            val exitDurationMs = player.duration
            // 離開播放器:先把進度寫進本機歷史,再「立刻」推一次同步(不等 30 秒 debounce),
            // 避免使用者一返回就把 app 滑掉、那筆進度還沒上傳 → 另一台看不到。用 appScope 確保跑完。
            container.appScope.launch {
                saveHistoryIfReady(
                    container = container, site = site, details = details,
                    vodId = vodId, sourceIdx = currentSourceIdx, episodeIdx = currentEpIdx,
                    episodeName = endEpisode?.name.orEmpty(),
                    positionMs = exitPositionMs, durationMs = exitDurationMs,
                )
                container.syncManager.pushNow()
            }
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

    LaunchedEffect(controlsVisible, controlsActivityTick, barFocused, openMenu) {
        // 操作控制條 / 開著清單時不自動隱藏,免得選到一半控制列消失
        if (controlsVisible && !barFocused && openMenu == PlayerMenu.None) {
            delay(PlayerConfig.CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    LaunchedEffect(gestureIndicator, longPressActive) {
        val g = gestureIndicator ?: return@LaunchedEffect
        // 長按 2x 速期間：speed 提示要一直停在畫面上，等放手 cancelLongPressSpeed() 才會被清掉。
        // 不然 indicator hide timer 跑完就消失，使用者看不出來「正在加速中」
        if (g is GestureIndicator.Speed && longPressActive) return@LaunchedEffect
        delay(PlayerConfig.GESTURE_INDICATOR_HIDE_MS)
        gestureIndicator = null
    }

    // 自動跳線路提示:顯示幾秒後自動消失
    LaunchedEffect(lineNotice) {
        if (lineNotice != null) {
            delay(PlayerConfig.LINE_NOTICE_MS)
            lineNotice = null
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
        // 逐層返回:先關清單 → 再退出控制條 → 最後才離開播放器
        when {
            openMenu != PlayerMenu.None -> openMenu = PlayerMenu.None
            barFocused -> barFocused = false
            else -> nav.popBackStack()
        }
    }

    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    val keyFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { keyFocusRequester.requestFocus() }
    }

    // 左上角現在時間（橫式全螢幕藏掉系統列後就看不到狀態列的時鐘，自己補一個）
    var clockText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        while (true) {
            clockText = fmt.format(java.util.Date())
            delay(PlayerConfig.CLOCK_TICK_MS)
        }
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
        val stepSec = (PlayerConfig.SEEK_STEP_BASE_S + held * PlayerConfig.SEEK_HOLD_LINEAR_RATE_PER_S)
            .coerceAtMost(PlayerConfig.SEEK_HOLD_MAX_STEP_S)
        return stepSec * 1000L
    }

    // 播放/暫停切換。看完最後一集會停在 STATE_ENDED,此時 play() 不會重播,要先 seekTo(0) 才會從頭。
    fun togglePlayPause() {
        when {
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0); player.play() }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    // 底部控制條上有哪幾顆鈕(只有 >1 來源才出現「換源」)。順序固定,index 對應這個 list。
    val barItems = buildList {
        add(PlayerMenu.Episodes)
        if ((details?.sources?.size ?: 0) > 1) add(PlayerMenu.Sources)
        add(PlayerMenu.Sites)
        add(PlayerMenu.Speed)
    }
    val episodes = details?.sources?.getOrNull(currentSourceIdx)?.episodes ?: emptyList()
    val sourcesList = details?.sources ?: emptyList()

    fun menuItemCount(menu: PlayerMenu): Int = when (menu) {
        PlayerMenu.Episodes -> episodes.size
        PlayerMenu.Sources -> sourcesList.size
        PlayerMenu.Sites -> peers?.size ?: 0
        PlayerMenu.Speed -> PlayerConfig.PLAYBACK_SPEEDS.size
        PlayerMenu.None -> 0
    }

    // 切到別站台:抓該站詳情 → 用集名/集號智慧對齊集數 → 帶當前秒數開新的播放器(取代當前這層,返回回到詳情/上一頁)
    fun applyPeerPick(peer: Video) {
        val pos = player.currentPosition.coerceAtLeast(0)
        val curName = currentEpisode?.name.orEmpty()
        val curIdx = currentEpIdx
        openMenu = PlayerMenu.None
        barFocused = false
        val pSite = sites.firstOrNull { it.id == peer.fromSiteId } ?: return
        scope.launch {
            when (val r = container.macCmsApi.fetchDetails(pSite, peer.vodId)) {
                is ApiResult.Success -> {
                    container.cacheDetails(pSite.id, peer.vodId, r.data)
                    val tgt = r.data.sources.firstOrNull()?.episodes ?: emptyList()
                    val epIdx = matchEpisodeIndex(curName, curIdx, tgt)
                    nav.popBackStack()
                    nav.navigate(Routes.player(pSite.id, peer.vodId, 0, epIdx, pos, pSite.url))
                }
                is ApiResult.Error -> playbackError = "換站失敗:${r.message}"
            }
        }
    }

    // 在清單裡按 OK 確認當前 menuIndex 的選擇
    fun confirmMenu() {
        val idx = menuIndex
        when (openMenu) {
            PlayerMenu.Episodes -> {
                // 手動挑某一集 = 從頭播該集
                if (idx in episodes.indices) { currentEpIdx = idx; pendingResumeMs = 0L }
                openMenu = PlayerMenu.None
            }
            PlayerMenu.Sources -> {
                val targetSource = sourcesList.getOrNull(idx)
                val target = targetSource?.episodes
                if (target != null) {
                    // 換線路:這條線路自己看過 → 接它自己的進度(集 + 秒);沒看過 → 對齊目前這集 + 帶當前秒數。
                    val flag = targetSource.flag.ifBlank { "線路${idx + 1}" }
                    val saved = site?.let { container.historyRepository.find(vodId, it.id)?.lines?.get(flag) }
                    if (saved != null && saved.positionMs > HistoryConfig.POSITION_IGNORED_THRESHOLD_MS
                        && saved.episodeIndex in target.indices) {
                        currentEpIdx = saved.episodeIndex
                        pendingResumeMs = saved.positionMs
                    } else {
                        pendingResumeMs = player.currentPosition.coerceAtLeast(0)
                        currentEpIdx = matchEpisodeIndex(currentEpisode?.name.orEmpty(), currentEpIdx, target)
                    }
                    currentSourceIdx = idx
                }
                openMenu = PlayerMenu.None
            }
            PlayerMenu.Sites -> peers?.getOrNull(idx)?.let { applyPeerPick(it) }
            PlayerMenu.Speed -> {
                PlayerConfig.PLAYBACK_SPEEDS.getOrNull(idx)?.let { speed = it }
                openMenu = PlayerMenu.None
            }
            PlayerMenu.None -> Unit
        }
    }

    // 從控制條按 OK 展開某顆鈕的清單;menuIndex 預設停在目前選中項。「換站」第一次開才跨站搜尋。
    fun openBarItem(menu: PlayerMenu) {
        openMenu = menu
        menuIndex = when (menu) {
            PlayerMenu.Episodes -> currentEpIdx.coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
            PlayerMenu.Sources -> currentSourceIdx.coerceIn(0, (sourcesList.size - 1).coerceAtLeast(0))
            PlayerMenu.Speed -> PlayerConfig.PLAYBACK_SPEEDS.indexOfFirst { it == speed }.coerceAtLeast(0)
            else -> 0
        }
        if (menu == PlayerMenu.Sites && peers == null && !peersLoading) {
            peersLoading = true
            scope.launch {
                val name = details?.video?.vodName.orEmpty()
                val enabled = sites.filter { it.enabled }
                val found = if (name.isBlank()) emptyList() else
                    container.macCmsApi.multiSiteSearch(enabled, name).videos
                        .filter { it.vodName == name && it.fromSiteId != siteId }
                peers = found
                peersLoading = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(keyFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // 記住按下「之前」控制列是否已顯示:待機區按↑要靠這個判斷「立刻收起」還是「叫出」
                val controlsWereVisible = controlsVisible
                controlsVisible = true
                controlsActivityTick++
                val code = keyEvent.nativeKeyEvent.keyCode
                when {
                    // === 清單展開中:清單是橫向 LazyRow,←/→ 移動高亮、↑ 關清單、OK 確認;其餘鍵(含 BACK / 音量)放行 ===
                    // ↑ 退一層跟其它層一致(影片區↑收控制列、控制條↑回影片);↓ 吃掉不做事(橫向列沒有上下移動)。
                    // BACK 不在這吃,交給 BackHandler 逐層關(關清單→退控制條→離開)
                    openMenu != PlayerMenu.None -> {
                        val count = menuItemCount(openMenu)
                        when (code) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (count > 0) menuIndex = (menuIndex - 1 + count) % count; true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (count > 0) menuIndex = (menuIndex + 1) % count; true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> { openMenu = PlayerMenu.None; true }
                            KeyEvent.KEYCODE_DPAD_DOWN -> true
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { confirmMenu(); true }
                            else -> false
                        }
                    }
                    // === 焦點在底部控制條:←/→ 換鈕、OK 展開該鈕清單、↑ 回影片 ===
                    barFocused -> when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (barItems.isNotEmpty()) barIndex = (barIndex - 1 + barItems.size) % barItems.size; true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (barItems.isNotEmpty()) barIndex = (barIndex + 1) % barItems.size; true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> { barFocused = false; true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            barItems.getOrNull(barIndex)?.let { openBarItem(it) }; true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> true   // 已在最底層,吃掉避免漏到別處
                        else -> false
                    }
                    // === 待機(影片區):OK 播放暫停、←/→ seek、↓ 進控制條 ===
                    else -> when (code) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                            togglePlayPause(); true
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
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            // ↓ 進底部控制條(取代原本「↓ 調倍速」;倍速改成控制條上一顆鈕)
                            if (barItems.isNotEmpty()) {
                                barFocused = true
                                barIndex = barIndex.coerceIn(0, barItems.size - 1)
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            // 控制列原本就開著(且停在影片區、沒有東西要選)→ ↑ 立刻收起,不用等自動隱藏;
                            // 原本是隱藏的 → 維持「↑ 叫出控制列」(開頭已 set controlsVisible = true)
                            if (controlsWereVisible) controlsVisible = false
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
                                    togglePlayPause()
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
                        // 起手位置在頂部 / 底部 edge zone 內 → 整段 drag 都不接，讓給系統手勢
                        // （頂部下拉叫 status bar、底部上滑觸發 home 手勢）。左右沒衝突所以不留。
                        var suppressedByEdge = false
                        val edgePx = GESTURE_EDGE_SAFE_ZONE.toPx()
                        detectDragGestures(
                            onDragStart = { offset: Offset ->
                                // drag 接管手勢時，如果剛好正在長按 2x，要把 speed 還原（不然 speed 卡在 2x）
                                cancelLongPressSpeed()
                                mode = null
                                startX = offset.x
                                startY = offset.y
                                startBrightness = currentBrightness(activity)
                                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val h = size.height.toFloat()
                                suppressedByEdge = offset.y < edgePx || offset.y > h - edgePx
                            },
                            onDrag = { change, _ ->
                                if (suppressedByEdge) return@detectDragGestures
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
                            onDragEnd = { mode = null; suppressedByEdge = false },
                            onDragCancel = { mode = null; suppressedByEdge = false },
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
                // Speed 跟 Seek 都靠上方，避免擋到正在播的內容；亮度／音量維持置中
                val topAligned = indicator is GestureIndicator.Speed || indicator is GestureIndicator.Seek
                val alignment = if (topAligned) Alignment.TopCenter else Alignment.Center
                val topPad = if (topAligned) GESTURE_INDICATOR_TOP_PAD else 0.dp
                GestureOverlay(
                    indicator,
                    modifier = Modifier.align(alignment).padding(top = topPad),
                )
            }

            // 自動跳線路提示:上方置中的小橫幅,幾秒後自動淡出
            lineNotice?.let { msg ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xE6000000))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        msg,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // 左上角現在時間，跟著 controlsVisible 一起淡入淡出
            AnimatedVisibility(
                visible = controlsVisible && gestureIndicator == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            ) {
                ClockOverlay(clockText)
            }

            // 手機沒實體 BACK 鍵、全螢幕沉浸又沒系統列，加一顆返回按鈕在右上才好退出。
            // TV 用遙控器 BACK 不需要這顆，避開電視盒。跟著 controlsVisible 一起淡入淡出
            val windowSize = LocalWindowSize.current
            if (!windowSize.isTv) {
                AnimatedVisibility(
                    visible = controlsVisible && gestureIndicator == null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                ) {
                    BackOverlayButton(onClick = { nav.popBackStack() })
                }
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
                        togglePlayPause()
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
                    positionMs = positionMs,
                    durationMs = durationMs,
                    speed = speed,
                    barItems = barItems,
                    barFocused = barFocused,
                    barIndex = barIndex,
                    openMenu = openMenu,
                    menuIndex = menuIndex,
                    currentEpIdx = currentEpIdx,
                    currentSourceIdx = currentSourceIdx,
                    episodes = episodes,
                    sources = sourcesList,
                    peers = peers,
                    peersLoading = peersLoading,
                    onSeekTo = { target ->
                        val dur = player.duration.coerceAtLeast(0)
                        val clamped = target.coerceIn(0, if (dur > 0) dur else target)
                        player.seekTo(clamped)
                        positionMs = clamped
                        controlsVisible = true
                        controlsActivityTick++
                    },
                    // 觸控:點控制條的鈕 = 展開該清單;點清單項 = 確認
                    onBarItemClick = { menu -> openBarItem(menu) },
                    onMenuItemClick = { idx -> menuIndex = idx; confirmMenu() },
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

// 播放器控制條上的清單種類(也當「目前展開哪個清單」用)
private enum class PlayerMenu { None, Episodes, Sources, Sites, Speed }

private val EPISODE_NUMBER_REGEX = Regex("\\d+")
private fun episodeNumber(name: String): Int? =
    EPISODE_NUMBER_REGEX.find(name)?.value?.toIntOrNull()

// 換來源 / 換站時對齊集數:先用集名裡的數字對(「第14集」→ 14),對不到再退回同一個集號(clamp)。
private fun matchEpisodeIndex(currentName: String, currentIdx: Int, target: List<Episode>): Int {
    if (target.isEmpty()) return 0
    episodeNumber(currentName)?.let { n ->
        val byNum = target.indexOfFirst { episodeNumber(it.name) == n }
        if (byNum >= 0) return byNum
    }
    return currentIdx.coerceIn(0, target.size - 1)
}

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
            .clip(RoundedCornerShape(10.dp))
            // 0x80 = 50% alpha — 比之前 0xCC (80%) 更穿透，看得到後面畫面；
            // 之前內部 22/14 padding 讓文字膠囊太大壓住畫面，縮成 10/4 更貼合字寬
            .background(Color(0x80000000))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        when (indicator) {
            is GestureIndicator.Seek -> {
                // 文字 + mini 進度條：藍條長度 = target/duration，讓使用者一眼看到「會跳到哪裡」
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        "${formatDuration(indicator.targetMs)} / ${formatDuration(indicator.durationMs)}  (${formatSeekDelta(indicator.deltaMs)})",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val progress = if (indicator.durationMs > 0) {
                        (indicator.targetMs.toFloat() / indicator.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f
                    Box(
                        modifier = Modifier
                            .width(SEEK_MINI_BAR_WIDTH)
                            .height(SEEK_MINI_BAR_HEIGHT)
                            .clip(RoundedCornerShape(SEEK_MINI_BAR_HEIGHT / 2))
                            .background(Color(0x33FFFFFF)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(tw.pp.kazi.ui.theme.AppColors.Primary),
                        )
                    }
                }
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
private fun ClockOverlay(timeText: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0x66000000))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            timeText,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BackOverlayButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0x66000000))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.tv.material3.Icon(
            Icons.Filled.Close,
            contentDescription = "關閉",
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
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
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    barItems: List<PlayerMenu>,
    barFocused: Boolean,
    barIndex: Int,
    openMenu: PlayerMenu,
    menuIndex: Int,
    currentEpIdx: Int,
    currentSourceIdx: Int,
    episodes: List<Episode>,
    sources: List<VideoSource>,
    peers: List<Video>?,
    peersLoading: Boolean,
    onSeekTo: (Long) -> Unit,
    onBarItemClick: (PlayerMenu) -> Unit,
    onMenuItemClick: (Int) -> Unit,
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
        // 展開的清單(在按鈕列上方);電視用 menuIndex 高亮 + 自動捲到該項,手機點項目即確認
        if (openMenu != PlayerMenu.None) {
            MenuRow(
                menu = openMenu,
                menuIndex = menuIndex,
                episodes = episodes,
                sources = sources,
                peers = peers,
                peersLoading = peersLoading,
                onItemClick = onMenuItemClick,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                formatDuration(positionMs) + " / " + formatDuration(durationMs),
                color = Color(0xFFBBBBBB),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            // 控制條按鈕(選集/換源/換站/倍速)。電視用 barFocused+barIndex 高亮、OK 展開;手機直接點。
            barItems.forEachIndexed { i, item ->
                FocusableTag(
                    text = barItemLabel(item, speed),
                    selected = barFocused && i == barIndex,
                    onClick = { onBarItemClick(item) },
                )
            }
        }
    }
}

private fun barItemLabel(menu: PlayerMenu, speed: Float): String = when (menu) {
    PlayerMenu.Episodes -> "選集"
    PlayerMenu.Sources -> "換源"
    PlayerMenu.Sites -> "換站"
    PlayerMenu.Speed -> "${speed}x"
    PlayerMenu.None -> ""
}

private fun episodeLabel(name: String, index: Int): String =
    if (name.isNotBlank() && name != "${index + 1}") name else "第${index + 1}集"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MenuRow(
    menu: PlayerMenu,
    menuIndex: Int,
    episodes: List<Episode>,
    sources: List<VideoSource>,
    peers: List<Video>?,
    peersLoading: Boolean,
    onItemClick: (Int) -> Unit,
) {
    if (menu == PlayerMenu.Sites) {
        when {
            peersLoading -> {
                Text("搜尋其他站台中…", color = Color(0xFFBBBBBB), style = MaterialTheme.typography.labelMedium)
                return
            }
            peers.isNullOrEmpty() -> {
                Text("找不到其他站台有這部片", color = Color(0xFFBBBBBB), style = MaterialTheme.typography.labelMedium)
                return
            }
        }
    }
    val labels: List<String> = when (menu) {
        PlayerMenu.Episodes -> episodes.mapIndexed { i, e -> episodeLabel(e.name, i) }
        PlayerMenu.Sources -> sources.mapIndexed { i, s -> s.flag.ifBlank { "來源${i + 1}" } }
        PlayerMenu.Sites -> peers.orEmpty().map { it.fromSite ?: "站台" }
        PlayerMenu.Speed -> PlayerConfig.PLAYBACK_SPEEDS.map { "${it}x" }
        PlayerMenu.None -> emptyList()
    }
    val listState = rememberLazyListState()
    // 用方向鍵移動 menuIndex 時自動把該項捲進畫面
    LaunchedEffect(menu, menuIndex) {
        if (menuIndex in labels.indices) runCatching { listState.animateScrollToItem(menuIndex) }
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(labels) { i, label ->
            FocusableTag(text = label, selected = i == menuIndex, onClick = { onItemClick(i) })
        }
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

/**
 * Seek 累計量顯示：60 秒以下顯示 `±X秒`，超過 60 秒拆成 `±X分Y秒`
 * （`±2分30秒` 比 `±150秒` 直觀很多，使用者按住 ← 加速 seek 後一眼看出跳了幾分鐘）
 */
private fun formatSeekDelta(deltaMs: Long): String {
    val sign = if (deltaMs >= 0) "+" else "-"
    val totalSec = abs(deltaMs) / 1000
    if (totalSec < 60) return "${sign}${totalSec}秒"
    val m = totalSec / 60
    val s = totalSec % 60
    return if (s == 0L) "${sign}${m}分" else "${sign}${m}分${s}秒"
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

private suspend fun saveHistoryIfReady(
    container: tw.pp.kazi.AppContainer,
    site: tw.pp.kazi.data.Site?,
    details: VideoDetails?,
    vodId: Long,
    sourceIdx: Int,
    episodeIdx: Int,
    episodeName: String,
    positionMs: Long,
    durationMs: Long,
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

    // 多線路進度:用「線路名」當鍵記下這條線路自己的進度,並保留其他線路既有的進度(不互相覆蓋)。
    // 線路名空白時退用「線路N」當鍵。頂層欄位仍鏡射「目前線路」供卡片顯示 / 舊版相容。
    val now = System.currentTimeMillis()
    val flag = d.sources.getOrNull(sourceIdx)?.flag?.ifBlank { "線路${sourceIdx + 1}" } ?: "線路${sourceIdx + 1}"
    val existingLines = container.historyRepository.find(vodId, s.id)?.lines ?: emptyMap()
    val mergedLines = existingLines + (flag to tw.pp.kazi.data.LineProgress(
        episodeIndex = episodeIdx,
        episodeName = episodeName,
        positionMs = positionMs,
        durationMs = durationMs,
        totalEpisodes = totalAcrossSources,
        updatedAt = now,
    ))

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
            updatedAt = now,
            sourceFlag = flag,
            lines = mergedLines,
        )
    )
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

// Speed / Seek 提示距離畫面頂端的距離 — 跟頂部留一點點空隙就好，越高越不擋畫面
private val GESTURE_INDICATOR_TOP_PAD = 12.dp

// Seek 提示下方 mini 進度條：給使用者「這次跳會到哪」的視覺感
private val SEEK_MINI_BAR_WIDTH = 240.dp
private val SEEK_MINI_BAR_HEIGHT = 4.dp

// 頂部 / 底部 edge safe zone — 在這條內起手的 vertical drag 不接亮度/音量手勢，
// 讓給 Android 系統手勢（頂部下拉叫 status bar、底部上滑觸發 home）。左右沒衝突所以不留。
private val GESTURE_EDGE_SAFE_ZONE = 40.dp
