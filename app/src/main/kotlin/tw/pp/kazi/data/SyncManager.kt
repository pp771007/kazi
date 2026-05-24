package tw.pp.kazi.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tw.pp.kazi.util.Logger

/**
 * 帳號同步:把觀看歷史 + 收藏同步到 maccms-parser 伺服器(account-bound)。
 * 與網頁共用「共通格式」(鍵=videoId+siteUrl,續看錨點用 episodeName)。
 * 啟動時拉取合併(較新者贏);本機變動後 debounce 推送。登入用網頁密碼拿 session cookie。
 */
class SyncManager(
    private val config: ConfigRepository,
    private val history: HistoryRepository,
    private val favorites: FavoriteRepository,
    private val sites: SiteRepository,
    private val scope: CoroutineScope,
) {
    @Volatile private var sessionCookie: String? = null
    private val syncMutex = Mutex()
    private var pushJob: Job? = null
    private var started = false

    // 伺服器可能是 http(區網)或自簽 https → 用 trust-all client;不自動跟隨轉址(要讀 /login 的 Set-Cookie)
    private val client by lazy {
        HttpClients.forSite(false).newBuilder().followRedirects(false).build()
    }

    private fun baseUrl(): String? {
        val s = config.settings.value
        return if (s.syncEnabled) s.syncServerUrl.trimEnd('/') else null
    }

    /** 啟動同步:先拉取合併,再開始監聽本機變動推送。可重複呼叫(只會啟動一次監聽)。 */
    fun start() {
        if (baseUrl() == null) return
        scope.launch { sync() }
        if (started) return
        started = true
        scope.launch { history.items.drop(1).collect { schedulePush() } }
        scope.launch { favorites.items.drop(1).collect { schedulePush() } }
    }

    private fun schedulePush() {
        if (baseUrl() == null) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            withContext(Dispatchers.IO) { pushAll() }
        }
    }

    /** 手動 / 啟動同步:登入 → 拉取 → 合併(較新者贏)→ 寫回本機 + 推回伺服器。回傳成功與否。 */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext false
        syncMutex.withLock {
            if (sessionCookie == null && !login()) return@withLock false

            val remoteHist = authedGet("$base/api/history")?.let { parseHistory(it) }
            if (remoteHist != null) {
                val merged = mergeByKey(history.items.value, remoteHist, { "${it.videoId}|${it.siteUrl}" }, { it.updatedAt })
                history.replaceAll(merged)
                authedPost("$base/api/history", encodeHistory(merged))
            }

            val remoteFav = authedGet("$base/api/favorites")?.let { parseFavorites(it) }
            if (remoteFav != null) {
                val merged = mergeByKey(favorites.items.value, remoteFav, { "${it.videoId}|${it.siteUrl}" }, { it.addedAt })
                favorites.replaceAll(merged)
                authedPost("$base/api/favorites", encodeFavorites(merged))
            }

            // 記下最後同步時間 + 抓帳號暱稱(讓設定/歷史頁顯示「已綁定:XXX」,跟網頁對照是否同一帳號)
            val nickname = authedGet("$base/api/account")?.let { parseNickname(it) }
            markSynced(nickname)
            true
        }
    }

    private fun parseNickname(raw: String): String? = runCatching {
        (AppJson.parseToJsonElement(raw) as JsonObject)["nickname"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /** 記下最後同步時間(+暱稱)。fire-and-forget 寫進 config,UI 觀察 settings flow 自動更新。 */
    private fun markSynced(nickname: String?) {
        scope.launch { config.updateSyncStatus(System.currentTimeMillis(), nickname) }
    }

    /** 只推送本機現況(本機變動後用;不拉取、不合併,避免迴圈)。 */
    private fun pushAll() {
        val base = baseUrl() ?: return
        if (sessionCookie == null && !loginBlocking()) return
        val okH = authedPost("$base/api/history", encodeHistory(history.items.value))
        val okF = authedPost("$base/api/favorites", encodeFavorites(favorites.items.value))
        if (okH || okF) markSynced(null)
    }

    /** 測試連線用:嘗試登入。給設定頁 / 遠端遙控按「測試」。 */
    suspend fun testLogin(): Boolean = withContext(Dispatchers.IO) { login() }

    /** 解除綁定:丟掉 session、取消待推送。設定清空(config)後 baseUrl() 即回 null,後續變動不再推。 */
    fun clear() {
        pushJob?.cancel()
        sessionCookie = null
    }

    private fun loginBlocking(): Boolean = login()

    private fun login(): Boolean {
        val base = baseUrl() ?: return false
        val pw = config.settings.value.syncPassword
        val body = FormBody.Builder().add("password", pw).build()
        val req = Request.Builder().url("$base/login").post(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                // 成功:302 + Set-Cookie session=...;失敗:200(回登入頁)或鎖定
                val cookie = resp.headers("Set-Cookie").firstOrNull { it.startsWith("session=") }
                if (resp.code in 300..399 && cookie != null) {
                    sessionCookie = cookie.substringBefore(';')
                    true
                } else {
                    Logger.w("Sync login failed: code=${resp.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Logger.w("Sync login error: ${e.message}")
            false
        }
    }

    private fun authedGet(url: String): String? {
        val cookie = sessionCookie ?: return null
        val req = Request.Builder().url(url).header("Cookie", cookie).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()
                else { if (resp.code == 401) sessionCookie = null; null }
            }
        } catch (e: Exception) { Logger.w("Sync GET $url: ${e.message}"); null }
    }

    private fun authedPost(url: String, json: String): Boolean {
        val cookie = sessionCookie ?: return false
        val req = Request.Builder().url(url)
            .header("Cookie", cookie)
            .post(json.toRequestBody(JSON_MEDIA)).build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 401) sessionCookie = null
                resp.isSuccessful
            }
        } catch (e: Exception) { Logger.w("Sync POST $url: ${e.message}"); false }
    }

    private fun siteIdForUrl(siteUrl: String): Long =
        sites.sites.value.firstOrNull { it.url == siteUrl }?.id ?: siteUrl.hashCode().toLong()

    // ---- 共通格式 mapping ----
    private fun historyToCanonical(it: HistoryItem): JsonObject = buildJsonObject {
        put("videoId", it.videoId)
        put("siteUrl", it.siteUrl)
        put("siteName", it.siteName)
        put("videoName", it.videoName)
        put("videoPic", it.videoPic)
        put("episodeName", it.episodeName)
        put("episodeUrl", "")          // kazi 不存集網址;網頁讀到空值會改用集名定位
        put("sourceIndex", it.sourceIndex)
        put("episodeIndex", it.episodeIndex)
        put("positionSec", it.positionMs / 1000.0)
        put("durationSec", it.durationMs / 1000.0)
        put("totalEpisodes", it.totalEpisodes)
        put("updatedAt", it.updatedAt)
    }

    private fun historyFromCanonical(o: JsonObject): HistoryItem? {
        val videoId = o["videoId"]?.jsonPrimitive?.longOrNull ?: return null
        val siteUrl = o["siteUrl"]?.jsonPrimitive?.contentOrNull ?: ""
        return HistoryItem(
            videoId = videoId,
            videoName = o["videoName"]?.jsonPrimitive?.contentOrNull ?: "",
            videoPic = o["videoPic"]?.jsonPrimitive?.contentOrNull ?: "",
            siteId = siteIdForUrl(siteUrl),
            siteName = o["siteName"]?.jsonPrimitive?.contentOrNull ?: "",
            siteUrl = siteUrl,
            sourceIndex = o["sourceIndex"]?.jsonPrimitive?.intOrNull ?: 0,
            episodeIndex = o["episodeIndex"]?.jsonPrimitive?.intOrNull ?: 0,
            episodeName = o["episodeName"]?.jsonPrimitive?.contentOrNull ?: "",
            positionMs = ((o["positionSec"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
            durationMs = ((o["durationSec"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
            totalEpisodes = o["totalEpisodes"]?.jsonPrimitive?.intOrNull ?: 0,
            updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        )
    }

    private fun favToCanonical(it: FavoriteItem): JsonObject = buildJsonObject {
        put("videoId", it.videoId)
        put("siteUrl", it.siteUrl)
        put("siteName", it.siteName)
        put("videoName", it.videoName)
        put("videoPic", it.videoPic)
        put("vodRemarks", it.vodRemarks)
        put("addedAt", it.addedAt)
    }

    private fun favFromCanonical(o: JsonObject): FavoriteItem? {
        val videoId = o["videoId"]?.jsonPrimitive?.longOrNull ?: return null
        val siteUrl = o["siteUrl"]?.jsonPrimitive?.contentOrNull ?: ""
        return FavoriteItem(
            videoId = videoId,
            videoName = o["videoName"]?.jsonPrimitive?.contentOrNull ?: "",
            videoPic = o["videoPic"]?.jsonPrimitive?.contentOrNull ?: "",
            vodRemarks = o["vodRemarks"]?.jsonPrimitive?.contentOrNull ?: "",
            siteId = siteIdForUrl(siteUrl),
            siteName = o["siteName"]?.jsonPrimitive?.contentOrNull ?: "",
            siteUrl = siteUrl,
            addedAt = o["addedAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
        )
    }

    private fun parseHistory(raw: String): List<HistoryItem> = runCatching {
        (AppJson.parseToJsonElement(raw) as JsonArray).mapNotNull { historyFromCanonical(it.jsonObject) }
    }.getOrElse { emptyList() }

    private fun parseFavorites(raw: String): List<FavoriteItem> = runCatching {
        (AppJson.parseToJsonElement(raw) as JsonArray).mapNotNull { favFromCanonical(it.jsonObject) }
    }.getOrElse { emptyList() }

    private fun encodeHistory(list: List<HistoryItem>): String =
        AppJson.encodeToString(JsonElement.serializer(), JsonArray(list.map { historyToCanonical(it) }))

    private fun encodeFavorites(list: List<FavoriteItem>): String =
        AppJson.encodeToString(JsonElement.serializer(), JsonArray(list.map { favToCanonical(it) }))

    private fun <T> mergeByKey(local: List<T>, remote: List<T>, key: (T) -> String, time: (T) -> Long): List<T> {
        val map = LinkedHashMap<String, T>()
        (local + remote).forEach { item ->
            val k = key(item)
            val existing = map[k]
            if (existing == null || time(item) > time(existing)) map[k] = item
        }
        return map.values.toList()
    }

    companion object {
        // 推送節流:必須「大於」播放中每 15 秒一次的歷史存檔間隔(HistoryConfig.SAVE_INTERVAL_MS),
        // 否則每次進度存檔都會觸發一次伺服器推送 → 連續播放時狂打 Vercel/KV。設 30s 讓播放中
        // 的多次進度更新被合併:每次存檔都重設計時器,推送只在「停止觀看後」發一次,一個觀看
        // session ≈ 1 次推送(而非每 15 秒一次)。代價:變動最多延遲 30 秒才同步上去,跨裝置足夠。
        private const val PUSH_DEBOUNCE_MS = 30_000L
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
