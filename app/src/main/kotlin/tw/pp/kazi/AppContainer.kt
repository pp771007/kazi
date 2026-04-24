package tw.pp.kazi

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tw.pp.kazi.data.ConfigRepository
import tw.pp.kazi.data.HistoryRepository
import tw.pp.kazi.data.LanConfig
import tw.pp.kazi.data.MacCmsApi
import tw.pp.kazi.data.RemoteSearchRequest
import tw.pp.kazi.data.SiteRepository
import tw.pp.kazi.data.SiteScanner
import tw.pp.kazi.data.Video
import tw.pp.kazi.data.VideoDetails
import tw.pp.kazi.lan.LanServer
import tw.pp.kazi.util.ChineseConverter
import tw.pp.kazi.util.Logger
import tw.pp.kazi.util.Network

class AppContainer(private val context: Context) {

    private val appContext = context.applicationContext

    val configRepository = ConfigRepository(context)
    val siteRepository = SiteRepository(context)
    val historyRepository = HistoryRepository(context)
    val favoriteRepository = tw.pp.kazi.data.FavoriteRepository(context)
    val macCmsApi = MacCmsApi()
    val siteScanner = SiteScanner(macCmsApi)

    private var lanServer: LanServer? = null

    private val _lanState = MutableStateFlow(
        LanState(running = false, url = null, port = LanConfig.DEFAULT_PORT)
    )
    val lanState: StateFlow<LanState> = _lanState.asStateFlow()

    // 用 StateFlow 持有最近一次遠端搜尋請求；
    // App 在背景時也會保留，新 Activity / 前景時透過 repeatOnLifecycle 收到
    private val _pendingRemoteSearch = MutableStateFlow<RemoteSearchRequest?>(null)
    val pendingRemoteSearch: StateFlow<RemoteSearchRequest?> = _pendingRemoteSearch.asStateFlow()

    // LinkedHashMap with access-order + size cap = simple LRU
    private val detailsCache: MutableMap<Pair<Long, Long>, VideoDetails> =
        object : LinkedHashMap<Pair<Long, Long>, VideoDetails>(
            DETAILS_CACHE_CAPACITY,
            DETAILS_CACHE_LOAD_FACTOR,
            /* accessOrder = */ true,
        ) {
            override fun removeEldestEntry(eldest: Map.Entry<Pair<Long, Long>, VideoDetails>): Boolean =
                size > DETAILS_CACHE_MAX
        }

    @Synchronized
    fun cacheDetails(siteId: Long, vodId: Long, details: VideoDetails) {
        detailsCache[siteId to vodId] = details
    }

    @Synchronized
    fun cachedDetails(siteId: Long, vodId: Long): VideoDetails? =
        detailsCache[siteId to vodId]

    // 一次性傳遞給 DetailScreen 的同名他站列表；DetailScreen 進入時 consume 並清空。
    // 只在 UI thread 讀寫，不需額外同步。
    var pendingDetailPeers: List<Video>? = null

    fun submitRemoteSearch(request: RemoteSearchRequest): Boolean {
        _pendingRemoteSearch.value = request
        return true
    }

    fun consumePendingRemoteSearch() {
        _pendingRemoteSearch.value = null
    }

    suspend fun bootstrap() {
        configRepository.load()
        siteRepository.load()
        historyRepository.load()
        favoriteRepository.load()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            ChineseConverter.preload(appContext)
        }
        if (configRepository.settings.value.lanShareEnabled) startLan()
    }

    fun startLan(): Boolean {
        if (lanServer != null) return true
        val server = LanServer(
            port = LanConfig.DEFAULT_PORT,
            siteRepository = siteRepository,
            onRemoteSearch = { req -> submitRemoteSearch(req) },
            appContext = appContext,
        )
        return if (server.safeStart()) {
            lanServer = server
            val ip = Network.localIp()
            _lanState.value = LanState(
                running = true,
                url = if (ip != null) "http://$ip:${LanConfig.DEFAULT_PORT}" else null,
                port = LanConfig.DEFAULT_PORT,
            )
            Logger.i("LAN share started at ${_lanState.value.url}")
            true
        } else {
            Logger.w("LAN share failed to start")
            false
        }
    }

    fun stopLan() {
        lanServer?.safeStop()
        lanServer = null
        _lanState.value = LanState(running = false, url = null, port = LanConfig.DEFAULT_PORT)
    }

    companion object {
        private const val DETAILS_CACHE_MAX = 30
        private const val DETAILS_CACHE_CAPACITY = 32
        private const val DETAILS_CACHE_LOAD_FACTOR = 0.75f
    }
}

data class LanState(val running: Boolean, val url: String?, val port: Int)
