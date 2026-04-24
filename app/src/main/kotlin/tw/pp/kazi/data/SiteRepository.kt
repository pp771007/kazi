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
import java.io.File
import java.net.URI

class SiteRepository(context: Context) {

    private val dataDir = File(context.filesDir, ConfigRepository.DATA_DIR_NAME).apply { mkdirs() }
    private val sitesFile = File(dataDir, SITES_FILE_NAME)
    private val mutex = Mutex()
    private val listSerializer = ListSerializer(Site.serializer())

    private val _sites = MutableStateFlow<List<Site>>(emptyList())
    val sites: StateFlow<List<Site>> = _sites.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val raw = runCatching { sitesFile.readText() }.getOrNull()
            _sites.value = raw?.takeIf { it.isNotBlank() }
                ?.let { runCatching { AppJson.decodeFromString(listSerializer, it) }.getOrNull() }
                ?.sortedBy { it.order }
                ?: emptyList()
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
        val tmp = File(dataDir, "$SITES_FILE_NAME.tmp")
        tmp.writeText(AppJson.encodeToString(listSerializer, sites))
        if (!tmp.renameTo(sitesFile)) {
            sitesFile.writeText(tmp.readText())
            tmp.delete()
        }
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
    }
}

enum class MoveDirection { Up, Down }

internal fun cleanBaseUrl(raw: String): String? {
    if (raw.isBlank()) return null
    val withProto = if (raw.startsWith("http", ignoreCase = true)) raw else "http://$raw"
    return runCatching {
        val uri = URI(withProto)
        if (uri.scheme == null || uri.host == null) return@runCatching null
        "${uri.scheme}://${uri.host}${if (uri.port > 0) ":${uri.port}" else ""}"
    }.getOrNull()
}
