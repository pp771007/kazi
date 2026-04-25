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
import tw.pp.kazi.util.atomicWriteText
import tw.pp.kazi.util.readJsonOrBackup
import java.io.File
import java.net.URI

class SiteRepository(context: Context) {

    private val dataDir = File(context.filesDir, ConfigRepository.DATA_DIR_NAME).apply { mkdirs() }
    private val sitesFile = File(dataDir, SITES_FILE_NAME)
    private val mutex = Mutex()
    private val listSerializer = ListSerializer(Site.serializer())

    private val _sites = MutableStateFlow<List<Site>>(emptyList())
    val sites: StateFlow<List<Site>> = _sites.asStateFlow()

    // 第一次 load 完才會 true；HomeScreen 拿來區分「還沒讀檔」vs「真的沒站台」
    // 不然 App 剛開瞬間會閃一下「還沒有啟用的站點」
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _sites.value = sitesFile.readJsonOrBackup(fallback = { emptyList() }) {
                AppJson.decodeFromString(listSerializer, it)
            }.sortedBy { it.order }
            _loaded.value = true
        }
    }

    suspend fun addSite(rawUrl: String, rawName: String?): Result<Site> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val cleanedUrl = cleanBaseUrl(rawUrl) ?: return@withLock Result.failure(
                IllegalArgumentException("URL 格式不正確")
            )
            if (_sites.value.any { it.url.equals(cleanedUrl, ignoreCase = true) }) {
                return@withLock Result.failure(IllegalStateException("該站點已存在"))
            }
            val name = rawName?.trim()?.takeIf { it.isNotEmpty() } ?: autoName(cleanedUrl)
            val site = Site(
                id = System.currentTimeMillis(),
                name = name,
                url = cleanedUrl,
                order = _sites.value.size,
            )
            val updated = _sites.value + site
            _sites.value = updated
            writeToDisk(updated)
            Result.success(site)
        }
    }

    suspend fun editSite(
        id: Long,
        newName: String,
        newUrl: String,
        newSslVerify: Boolean,
    ): Result<Site> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = _sites.value.firstOrNull { it.id == id }
                ?: return@withLock Result.failure(IllegalArgumentException("找不到站點"))
            val cleanedUrl = cleanBaseUrl(newUrl)
                ?: return@withLock Result.failure(IllegalArgumentException("URL 格式不正確"))
            if (_sites.value.any { it.id != id && it.url.equals(cleanedUrl, ignoreCase = true) }) {
                return@withLock Result.failure(IllegalStateException("URL 與其他站點重複"))
            }
            val updated = existing.copy(
                name = newName.trim().ifEmpty { autoName(cleanedUrl) },
                url = cleanedUrl,
                sslVerify = newSslVerify,
            )
            val list = _sites.value.map { if (it.id == id) updated else it }
            _sites.value = list
            writeToDisk(list)
            Result.success(updated)
        }
    }

    suspend fun updateSite(updated: Site) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _sites.value.map { if (it.id == updated.id) updated else it }
            _sites.value = list
            writeToDisk(list)
        }
    }

    suspend fun deleteSite(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _sites.value.filter { it.id != id }
                .mapIndexed { i, s -> s.copy(order = i) }
            _sites.value = list
            writeToDisk(list)
        }
    }

    suspend fun moveSite(id: Long, direction: MoveDirection) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _sites.value.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return@withLock
            val swapWith = when (direction) {
                MoveDirection.Up -> idx - 1
                MoveDirection.Down -> idx + 1
            }
            if (swapWith !in list.indices) return@withLock
            val a = list[idx]
            list[idx] = list[swapWith]
            list[swapWith] = a
            val reordered = list.mapIndexed { i, s -> s.copy(order = i) }
            _sites.value = reordered
            writeToDisk(reordered)
        }
    }

    suspend fun toggleEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = _sites.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
            _sites.value = list
            writeToDisk(list)
        }
    }

    /**
     * 把目前所有站台序列化成精簡 JSON（沒 id/order/check 狀態），給跨裝置匯出用。
     */
    fun exportToJson(): String {
        val items = _sites.value.map {
            SiteExportItem(name = it.name, url = it.url, sslVerify = it.sslVerify, enabled = it.enabled)
        }
        return AppJson.encodeToString(EXPORT_LIST_SERIALIZER, items)
    }

    /**
     * 解析匯入字串，回傳 (要新增的, 已存在被略過的, 解析失敗訊息)。
     * 不直接寫入；UI 拿去預覽 + 確認後再呼叫 importApply。
     */
    fun parseImport(raw: String): ImportPreview {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ImportPreview(emptyList(), emptyList(), "貼上的內容是空的")
        val parsed = runCatching {
            AppJson.decodeFromString(EXPORT_LIST_SERIALIZER, trimmed)
        }.getOrElse {
            return ImportPreview(emptyList(), emptyList(), "JSON 解析失敗：${it.message}")
        }
        val existingUrls = _sites.value.map { it.url.lowercase() }.toSet()
        val seen = mutableSetOf<String>()
        val toAdd = mutableListOf<SiteExportItem>()
        val skipped = mutableListOf<SiteExportItem>()
        parsed.forEach { item ->
            val cleaned = cleanBaseUrl(item.url)?.lowercase() ?: return@forEach
            if (cleaned in existingUrls || cleaned in seen) skipped += item
            else { toAdd += item; seen += cleaned }
        }
        return ImportPreview(toAdd, skipped, null)
    }

    /**
     * 把 parseImport 回傳的 toAdd 寫進 repo。回傳 (成功數, 失敗數)。
     */
    suspend fun importApply(items: List<SiteExportItem>): ImportResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            var ok = 0
            var fail = 0
            val current = _sites.value.toMutableList()
            items.forEach { item ->
                val cleanedUrl = cleanBaseUrl(item.url)
                if (cleanedUrl == null) { fail++; return@forEach }
                if (current.any { it.url.equals(cleanedUrl, ignoreCase = true) }) { fail++; return@forEach }
                val site = Site(
                    id = System.currentTimeMillis() + ok,  // 加 ok 避免同毫秒撞 id
                    name = item.name.trim().ifEmpty { autoName(cleanedUrl) },
                    url = cleanedUrl,
                    enabled = item.enabled,
                    sslVerify = item.sslVerify,
                    order = current.size,
                )
                current += site
                ok++
            }
            _sites.value = current.toList()
            writeToDisk(current)
            ImportResult(added = ok, failed = fail)
        }
    }

    suspend fun recordCheckResult(id: Long, healthy: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val timestamp = System.currentTimeMillis().toString()
            val list = _sites.value.map { s ->
                if (s.id != id) s
                else if (healthy) s.copy(
                    lastCheck = timestamp,
                    consecutiveErrors = 0,
                    checkStatus = CheckStatusKeys.SUCCESS,
                )
                else s.copy(
                    lastCheck = timestamp,
                    consecutiveErrors = s.consecutiveErrors + 1,
                    checkStatus = CheckStatusKeys.FAILED,
                )
            }
            _sites.value = list
            writeToDisk(list)
        }
    }

    private fun writeToDisk(sites: List<Site>) {
        sitesFile.atomicWriteText(AppJson.encodeToString(listSerializer, sites))
    }

    private fun autoName(cleanUrl: String): String {
        return runCatching {
            val host = URI(cleanUrl).host ?: return@runCatching "未命名站點"
            val trimmed = host.removePrefix("www.").removePrefix("api.")
            val parts = trimmed.split('.')
            val pick = if (parts.size > 1 && parts.last() in COMMON_TLDS) parts[parts.size - 2]
            else parts.first()
            pick.replaceFirstChar { it.uppercaseChar() }
        }.getOrElse { "未命名站點" }
    }

    companion object {
        const val SITES_FILE_NAME = "sites.json"
        private val COMMON_TLDS = setOf("com", "net", "org", "xyz", "top", "cn", "cc", "tv", "io")
        private val EXPORT_LIST_SERIALIZER = ListSerializer(SiteExportItem.serializer())
    }
}

enum class MoveDirection { Up, Down }

data class ImportPreview(
    val toAdd: List<SiteExportItem>,
    val skipped: List<SiteExportItem>,
    val errorMessage: String?,
)

data class ImportResult(val added: Int, val failed: Int)

internal fun cleanBaseUrl(raw: String): String? {
    if (raw.isBlank()) return null
    val withProto = if (raw.startsWith("http", ignoreCase = true)) raw else "http://$raw"
    return runCatching {
        val uri = URI(withProto)
        if (uri.scheme == null || uri.host == null) return@runCatching null
        "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
    }.getOrNull()
}
