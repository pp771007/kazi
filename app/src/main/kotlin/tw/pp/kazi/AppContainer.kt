package tw.pp.kazi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network as AndroidNetwork
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tw.pp.kazi.data.ConfigRepository
import tw.pp.kazi.data.HistoryRepository
import tw.pp.kazi.data.LanConfig
import tw.pp.kazi.data.MacCmsApi
import tw.pp.kazi.data.RemoteSearchRequest
import tw.pp.kazi.data.SiteRepository
import tw.pp.kazi.data.SiteScanner
import tw.pp.kazi.data.UpdateChecker
import tw.pp.kazi.data.Video
import tw.pp.kazi.data.VideoDetails
import tw.pp.kazi.lan.LanServer
import tw.pp.kazi.util.ChineseConverter
import tw.pp.kazi.util.Logger
import tw.pp.kazi.util.Network

class AppContainer(private val context: Context) {

    private val appContext = context.applicationContext

    // Application 等級 scope；用於那些「離開 composition 後還必須完成」的 IO（例如離開
    // 播放器時把進度寫進歷史）。不可以用 rememberCoroutineScope，那個會在 composable
    // 離開 composition 時被 cancel，導致 IO 還沒執行就被砍掉。
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val configRepository = ConfigRepository(context)
    val siteRepository = SiteRepository(context)
    val historyRepository = HistoryRepository(context)
    val favoriteRepository = tw.pp.kazi.data.FavoriteRepository(context)
    val macCmsApi = MacCmsApi()
    val siteScanner = SiteScanner(macCmsApi)

    private var lanServer: LanServer? = null
    private var lanNetworkCallback: ConnectivityManager.NetworkCallback? = null

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

    // 各畫面的 UI snapshot：從子畫面返回時還原狀態。每個畫面用一個 String key 取得自己的 bag（map），
    // bag 內 by-stateKey 存任意 state value。實際讀寫包在 ui.components.ScreenSnapshot helper，
    // 這裡只提供 raw access。只在 UI thread 讀寫。
    private val screenSnapshots: MutableMap<String, MutableMap<String, Any?>> = mutableMapOf()

    fun snapshotBag(key: String): MutableMap<String, Any?> =
        screenSnapshots.getOrPut(key) { mutableMapOf() }

    fun clearSnapshot(key: String) {
        screenSnapshots.remove(key)
    }

    fun clearAllSnapshots() {
        screenSnapshots.clear()
    }

    // 觀看歷史頁返回時的 focus 還原 key（"siteId-vodId"）
    var historyLastFocusKey: String? = null
    // 首頁 top bar 按鈕之間互相點擊的 focus 還原 key（"search" / "history" / 等）
    var homeTopBarFocusKey: String? = null

    // 無痕模式：存進 ConfigRepository，App 重啟會記住上次狀態
    val incognito: StateFlow<Boolean> = configRepository.settings
        .map { it.incognitoMode }
        .stateIn(appScope, SharingStarted.Eagerly, false)

    fun setIncognito(value: Boolean) {
        appScope.launch { configRepository.updateIncognito(value) }
    }

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
            UpdateChecker.cleanupCache(appContext)
        }
        if (configRepository.settings.value.lanShareEnabled) startLan()
    }

    fun startLan(): Boolean {
        if (lanServer != null) return true
        // 先試 DEFAULT_PORT 跟它附近 3 個（多數情況下都成功），
        // 全撞了就 fallback 到 port=0 讓 OS 隨便挑一個 ephemeral port
        val candidates = (0..3).map { LanConfig.DEFAULT_PORT + it } + 0
        val server = candidates.firstNotNullOfOrNull { port ->
            val s = LanServer(
                port = port,
                siteRepository = siteRepository,
                onRemoteSearch = { req -> submitRemoteSearch(req) },
                appContext = appContext,
            )
            if (s.safeStart()) s else null
        }
        if (server == null) {
            Logger.w("LAN share failed on all candidate ports")
            return false
        }
        lanServer = server
        val ip = Network.localIp()
        val actualPort = server.listeningPort
        _lanState.value = LanState(
            running = true,
            url = if (ip != null) "http://$ip:$actualPort" else null,
            port = actualPort,
        )
        Logger.i("LAN share started at ${_lanState.value.url}")
        registerLanNetworkCallback(actualPort)
        return true
    }

    fun stopLan() {
        unregisterLanNetworkCallback()
        lanServer?.safeStop()
        lanServer = null
        _lanState.value = LanState(running = false, url = null, port = LanConfig.DEFAULT_PORT)
    }

    /**
     * 監聽預設網路變化（換 WiFi、IP 重新分配、熱點切換），有變動就重算 URL，
     * 不然 QR 一啟動只抓一次 IP，使用者換房間後 QR 會指到死位址。
     */
    private fun registerLanNetworkCallback(port: Int) {
        unregisterLanNetworkCallback()
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: AndroidNetwork, linkProperties: LinkProperties) {
                refreshLanUrl(port)
            }
            override fun onAvailable(network: AndroidNetwork) {
                refreshLanUrl(port)
            }
            override fun onLost(network: AndroidNetwork) {
                refreshLanUrl(port)
            }
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { lanNetworkCallback = cb }
            .onFailure { Logger.w("registerNetworkCallback failed: ${it.message}") }
    }

    private fun unregisterLanNetworkCallback() {
        val cb = lanNetworkCallback ?: return
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { cm?.unregisterNetworkCallback(cb) }
        lanNetworkCallback = null
    }

    private fun refreshLanUrl(port: Int) {
        val ip = Network.localIp()
        val newUrl = if (ip != null) "http://$ip:$port" else null
        if (newUrl != _lanState.value.url) {
            _lanState.value = _lanState.value.copy(url = newUrl)
            Logger.i("LAN URL refreshed to $newUrl")
        }
    }

    companion object {
        private const val DETAILS_CACHE_MAX = 30
        private const val DETAILS_CACHE_CAPACITY = 32
        private const val DETAILS_CACHE_LOAD_FACTOR = 0.75f
    }
}

data class LanState(val running: Boolean, val url: String?, val port: Int)
