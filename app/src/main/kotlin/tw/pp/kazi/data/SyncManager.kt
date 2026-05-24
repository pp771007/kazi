package tw.pp.kazi.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private val syncMutex = Mutex()
    private var pushJob: Job? = null
    private var started = false

    // 伺服器可能是 http(區網)或自簽 https → 用 trust-all client。驗證走裝置 token(X-Sync-Token header),不用 cookie。
    private val client by lazy { HttpClients.forSite(false) }

    private fun baseUrl(): String? {
        val u = config.settings.value.syncServerUrl
        return if (u.isNotBlank()) u.trimEnd('/') else null
    }

    private fun token(): String? = config.settings.value.syncToken.ifBlank { null }

    /** 啟動同步:先拉取合併,再開始監聽本機變動推送。可重複呼叫(只會啟動一次監聽)。 */
    fun start() {
        if (baseUrl() == null) return
        scope.launch { sync() }
        if (started) return
        started = true
        // 收 allItems(含墓碑)→ 軟刪也算變動、會觸發推送
        scope.launch { history.allItems.drop(1).collect { schedulePush() } }
        scope.launch { favorites.allItems.drop(1).collect { schedulePush() } }
    }

    private fun schedulePush() {
        if (baseUrl() == null) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            withContext(Dispatchers.IO) { pushAll() }
        }
    }

    /** 確保有裝置 token:沒有但還存著密碼(剛綁定 / 舊版升級)就用密碼換一次 token、之後清掉密碼。回傳 token 或 null。 */
    private fun ensureToken(base: String): String? {
        token()?.let { return it }
        val pw = config.settings.value.syncPassword
        if (pw.isBlank()) return null
        val minted = mintToken(base, pw) ?: return null
        runBlocking { config.setSyncToken(minted.first) }
        minted.second?.let { markSynced(it) }
        return minted.first
    }

    /** 用密碼換 token(POST /api/sync/token)。回傳 (token, nickname) 或 null。 */
    private fun mintToken(base: String, password: String): Pair<String, String?>? {
        val body = buildJsonObject {
            put("password", password)
            put("label", android.os.Build.MODEL ?: "Android")
        }.toString().toRequestBody(JSON_MEDIA)
        val req = Request.Builder().url("$base/api/sync/token").post(body).build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Logger.w("mint token failed: ${resp.code}"); return null }
                val o = AppJson.parseToJsonElement(resp.body?.string() ?: "") as? JsonObject ?: return null
                val t = o["token"]?.jsonPrimitive?.contentOrNull ?: return null
                t to o["nickname"]?.jsonPrimitive?.contentOrNull
            }
        } catch (e: Exception) { Logger.w("mint token error: ${e.message}"); null }
    }

    /** 手動 / 啟動同步:確保 token → 拉取 → 合併(較新者贏)→ 寫回本機 + 推回伺服器。回傳成功與否。 */
    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext false
        syncMutex.withLock {
            val t = ensureToken(base) ?: return@withLock false

            val remoteHist = authedGet("$base/api/history", t)?.let { parseHistory(it) }
            if (remoteHist != null) {
                // 用 allItems(含墓碑)合併;有效時間取 max(updatedAt, deletedAt) → 刪除若較新就壓過 active
                val merged = mergeByKey(history.allItems.value, remoteHist, { "${it.videoId}|${it.siteUrl}" }, { maxOf(it.updatedAt, it.deletedAt) })
                history.replaceAll(merged)
                authedPost("$base/api/history", encodeHistory(merged), t)
            }

            val remoteFav = authedGet("$base/api/favorites", t)?.let { parseFavorites(it) }
            if (remoteFav != null) {
                val merged = mergeByKey(favorites.allItems.value, remoteFav, { "${it.videoId}|${it.siteUrl}" }, { maxOf(it.addedAt, it.deletedAt) })
                favorites.replaceAll(merged)
                authedPost("$base/api/favorites", encodeFavorites(merged), t)
            }

            // 記下最後同步時間 + 抓帳號暱稱(讓設定/歷史頁顯示「已綁定:XXX」,跟網頁對照是否同一帳號)
            val nickname = authedGet("$base/api/account", t)?.let { parseNickname(it) }
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
        val t = ensureToken(base) ?: return
        val okH = authedPost("$base/api/history", encodeHistory(history.allItems.value), t)
        val okF = authedPost("$base/api/favorites", encodeFavorites(favorites.allItems.value), t)
        if (okH || okF) markSynced(null)
    }

    /** 綁定 / 測試連線:用目前 config 的密碼換 token。給設定頁 / 遠端遙控按「儲存並測試連線」。 */
    suspend fun bind(): Boolean = withContext(Dispatchers.IO) {
        val base = baseUrl() ?: return@withContext false
        ensureToken(base) != null
    }

    /** 解除綁定:通知伺服器作廢這台的 token,並取消待推送(設定清空由 AppContainer 處理)。 */
    suspend fun unbind() = withContext(Dispatchers.IO) {
        val base = baseUrl(); val t = token()
        if (base != null && t != null) {
            runCatching {
                client.newCall(
                    Request.Builder().url("$base/api/sync/token").header("X-Sync-Token", t).delete().build()
                ).execute().close()
            }
        }
        pushJob?.cancel()
    }

    private fun authedGet(url: String, token: String): String? {
        val req = Request.Builder().url(url).header("X-Sync-Token", token).get().build()
        return try {
            client.newCall(req).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
        } catch (e: Exception) { Logger.w("Sync GET $url: ${e.message}"); null }
    }

    private fun authedPost(url: String, json: String, token: String): Boolean {
        val req = Request.Builder().url(url).header("X-Sync-Token", token)
            .post(json.toRequestBody(JSON_MEDIA)).build()
        return try {
            client.newCall(req).execute().use { resp -> resp.isSuccessful }
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
        put("deletedAt", it.deletedAt)
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
            deletedAt = o["deletedAt"]?.jsonPrimitive?.longOrNull ?: 0,
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
        put("deletedAt", it.deletedAt)
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
            deletedAt = o["deletedAt"]?.jsonPrimitive?.longOrNull ?: 0,
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
