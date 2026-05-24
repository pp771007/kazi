package tw.pp.kazi.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import tw.pp.kazi.util.atomicWriteText
import tw.pp.kazi.util.readJsonOrBackup
import java.io.File

data class AppSettings(
    val siteTitle: String = DEFAULT_SITE_TITLE,
    val requestTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val lanShareEnabled: Boolean = false,
    val incognitoMode: Boolean = false,
    val searchHistory: List<String> = emptyList(),
    // 帳號同步:連到 maccms-parser 伺服器,歷史/收藏跨裝置共用。空字串=未設定。
    val syncServerUrl: String = "",
    val syncPassword: String = "",  // 只在「綁定當下」用一次去換 token;換到後即清空、不長存
    val syncToken: String = "",     // 裝置 token(換到後只帶這個,不再傳密碼)
    val syncLastSyncAt: Long = 0,   // 最後一次成功同步的時間(0=從未)
    val syncNickname: String = "",  // 同步帳號的暱稱(伺服器回傳,確認綁哪個帳號)
) {
    // 有網址 + (token 或還沒換的密碼)就算已設定
    val syncEnabled: Boolean get() = syncServerUrl.isNotBlank() && (syncToken.isNotBlank() || syncPassword.isNotBlank())

    companion object {
        const val DEFAULT_SITE_TITLE = "咔滋影院"
        const val DEFAULT_TIMEOUT_SECONDS = 8
    }
}

class ConfigRepository(context: Context) {

    private val dataDir = File(context.filesDir, DATA_DIR_NAME).apply { mkdirs() }
    private val configFile = File(dataDir, CONFIG_FILE_NAME)
    private val mutex = Mutex()
    private val stringListSerializer = ListSerializer(String.serializer())

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val obj = configFile.readJsonOrBackup(fallback = { JsonObject(emptyMap()) }) {
                AppJson.parseToJsonElement(it) as? JsonObject ?: JsonObject(emptyMap())
            }

            val historyArr = obj[ConfigKeys.SEARCH_HISTORY] as? JsonArray
            val history = historyArr?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

            _settings.value = AppSettings(
                siteTitle = (obj[ConfigKeys.SITE_TITLE] as? JsonPrimitive)?.content
                    ?: AppSettings.DEFAULT_SITE_TITLE,
                requestTimeoutSeconds = (obj[ConfigKeys.REQUEST_TIMEOUT] as? JsonPrimitive)?.intOrNull
                    ?: AppSettings.DEFAULT_TIMEOUT_SECONDS,
                lanShareEnabled = (obj[ConfigKeys.LAN_SHARE_ENABLED] as? JsonPrimitive)?.booleanOrNull
                    ?: false,
                incognitoMode = (obj[ConfigKeys.INCOGNITO_MODE] as? JsonPrimitive)?.booleanOrNull
                    ?: false,
                searchHistory = history,
                syncServerUrl = (obj[ConfigKeys.SYNC_SERVER_URL] as? JsonPrimitive)?.content ?: "",
                syncPassword = (obj[ConfigKeys.SYNC_PASSWORD] as? JsonPrimitive)?.content ?: "",
                syncToken = (obj[ConfigKeys.SYNC_TOKEN] as? JsonPrimitive)?.content ?: "",
                syncLastSyncAt = (obj[ConfigKeys.SYNC_LAST_SYNC_AT] as? JsonPrimitive)?.longOrNull ?: 0,
                syncNickname = (obj[ConfigKeys.SYNC_NICKNAME] as? JsonPrimitive)?.content ?: "",
            )
        }
    }

    suspend fun updateLanShare(enabled: Boolean) = update { it.copy(lanShareEnabled = enabled) }

    suspend fun updateIncognito(enabled: Boolean) = update { it.copy(incognitoMode = enabled) }

    suspend fun updateTimeout(seconds: Int) = update { it.copy(requestTimeoutSeconds = seconds) }

    suspend fun updateSiteTitle(title: String) = update {
        it.copy(siteTitle = title.ifBlank { AppSettings.DEFAULT_SITE_TITLE })
    }

    suspend fun updateSyncServer(url: String, password: String) = update {
        // 換伺服器/密碼 = 可能換帳號,清掉舊 token/暱稱/時間,等重新綁定
        it.copy(syncServerUrl = normalizeServerUrl(url), syncPassword = password, syncToken = "", syncNickname = "", syncLastSyncAt = 0)
    }

    /** 用密碼換到 token 後:存 token、清掉不再需要的密碼。 */
    suspend fun setSyncToken(token: String) = update {
        it.copy(syncToken = token, syncPassword = "")
    }

    /** 解除綁定:全部清空。 */
    suspend fun clearSync() = update {
        it.copy(syncServerUrl = "", syncPassword = "", syncToken = "", syncNickname = "", syncLastSyncAt = 0)
    }

    /** 同步成功後記下時間;nickname 非 null 時一併更新(從伺服器拿到的帳號暱稱)。 */
    suspend fun updateSyncStatus(lastSyncAt: Long, nickname: String?) = update {
        it.copy(syncLastSyncAt = lastSyncAt, syncNickname = nickname ?: it.syncNickname)
    }

    suspend fun addSearchKeyword(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        update {
            val next = (listOf(trimmed) + it.searchHistory.filterNot { h -> h == trimmed })
                .take(SearchConfig.HISTORY_MAX)
            it.copy(searchHistory = next)
        }
    }

    suspend fun clearSearchHistory() = update { it.copy(searchHistory = emptyList()) }

    suspend fun removeSearchKeyword(keyword: String) = update {
        it.copy(searchHistory = it.searchHistory.filterNot { h -> h == keyword })
    }

    private suspend fun update(block: (AppSettings) -> AppSettings) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = block(_settings.value)
            _settings.value = next
            writeToDisk(next)
        }
    }

    private fun writeToDisk(settings: AppSettings) {
        val obj = JsonObject(
            mapOf(
                ConfigKeys.SITE_TITLE to JsonPrimitive(settings.siteTitle),
                ConfigKeys.REQUEST_TIMEOUT to JsonPrimitive(settings.requestTimeoutSeconds),
                ConfigKeys.LAN_SHARE_ENABLED to JsonPrimitive(settings.lanShareEnabled),
                ConfigKeys.INCOGNITO_MODE to JsonPrimitive(settings.incognitoMode),
                ConfigKeys.SEARCH_HISTORY to AppJson.parseToJsonElement(
                    AppJson.encodeToString(stringListSerializer, settings.searchHistory)
                ),
                ConfigKeys.SYNC_SERVER_URL to JsonPrimitive(settings.syncServerUrl),
                ConfigKeys.SYNC_PASSWORD to JsonPrimitive(settings.syncPassword),
                ConfigKeys.SYNC_TOKEN to JsonPrimitive(settings.syncToken),
                ConfigKeys.SYNC_LAST_SYNC_AT to JsonPrimitive(settings.syncLastSyncAt),
                ConfigKeys.SYNC_NICKNAME to JsonPrimitive(settings.syncNickname),
            )
        )
        configFile.atomicWriteText(AppJson.encodeToString(JsonObject.serializer(), obj))
    }

    companion object {
        const val DATA_DIR_NAME = "data"
        const val CONFIG_FILE_NAME = "config.json"

        /**
         * 同步伺服器網址正規化:只留 origin(scheme://host[:port]),丟掉 /profile 這種路徑與 query。
         * 沒填協定時補 https://。解析失敗就退回原字串去尾斜線。
         * 例:"https://x.vercel.app/profile" → "https://x.vercel.app";"192.168.0.5:5000/a" → "https://..." 由解析決定。
         */
        fun normalizeServerUrl(input: String): String {
            var s = input.trim()
            if (s.isEmpty()) return ""
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
            return try {
                val u = java.net.URI(s)
                val host = u.host ?: return s.trimEnd('/')
                val scheme = (u.scheme ?: "https").lowercase()
                val port = if (u.port != -1) ":${u.port}" else ""
                "$scheme://$host$port"
            } catch (e: Exception) {
                s.trimEnd('/')
            }
        }
    }
}
