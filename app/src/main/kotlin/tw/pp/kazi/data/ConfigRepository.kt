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
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class AppSettings(
    val siteTitle: String = DEFAULT_SITE_TITLE,
    val requestTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val viewMode: ViewMode = ViewMode.Default,
    val lanShareEnabled: Boolean = false,
    val incognitoMode: Boolean = false,
    val searchHistory: List<String> = emptyList(),
) {
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
            val raw = runCatching { configFile.readText() }.getOrNull()
            val obj = raw?.takeIf { it.isNotBlank() }
                ?.let { runCatching { AppJson.parseToJsonElement(it) }.getOrNull() as? JsonObject }
                ?: JsonObject(emptyMap())

            val historyArr = obj[ConfigKeys.SEARCH_HISTORY] as? JsonArray
            val history = historyArr?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

            _settings.value = AppSettings(
                siteTitle = (obj[ConfigKeys.SITE_TITLE] as? JsonPrimitive)?.content
                    ?: AppSettings.DEFAULT_SITE_TITLE,
                requestTimeoutSeconds = (obj[ConfigKeys.REQUEST_TIMEOUT] as? JsonPrimitive)?.intOrNull
                    ?: AppSettings.DEFAULT_TIMEOUT_SECONDS,
                viewMode = ViewMode.fromKey((obj[ConfigKeys.VIEW_MODE] as? JsonPrimitive)?.content),
                lanShareEnabled = (obj[ConfigKeys.LAN_SHARE_ENABLED] as? JsonPrimitive)?.booleanOrNull
                    ?: false,
                incognitoMode = (obj[ConfigKeys.INCOGNITO_MODE] as? JsonPrimitive)?.booleanOrNull
                    ?: false,
                searchHistory = history,
            )
        }
    }

    suspend fun updateViewMode(mode: ViewMode) = update { it.copy(viewMode = mode) }

    suspend fun updateLanShare(enabled: Boolean) = update { it.copy(lanShareEnabled = enabled) }

    suspend fun updateIncognito(enabled: Boolean) = update { it.copy(incognitoMode = enabled) }

    suspend fun updateTimeout(seconds: Int) = update { it.copy(requestTimeoutSeconds = seconds) }

    suspend fun updateSiteTitle(title: String) = update {
        it.copy(siteTitle = title.ifBlank { AppSettings.DEFAULT_SITE_TITLE })
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
                ConfigKeys.VIEW_MODE to JsonPrimitive(settings.viewMode.key),
                ConfigKeys.LAN_SHARE_ENABLED to JsonPrimitive(settings.lanShareEnabled),
                ConfigKeys.INCOGNITO_MODE to JsonPrimitive(settings.incognitoMode),
                ConfigKeys.SEARCH_HISTORY to AppJson.parseToJsonElement(
                    AppJson.encodeToString(stringListSerializer, settings.searchHistory)
                ),
            )
        )
        val tmp = File(dataDir, "$CONFIG_FILE_NAME.tmp")
        tmp.writeText(AppJson.encodeToString(JsonObject.serializer(), obj))
        if (!tmp.renameTo(configFile)) {
            configFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val DATA_DIR_NAME = "data"
        const val CONFIG_FILE_NAME = "config.json"
    }
}
