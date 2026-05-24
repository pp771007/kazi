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

class HistoryRepository(context: Context) {

    private val dataDir = File(context.filesDir, ConfigRepository.DATA_DIR_NAME).apply { mkdirs() }
    private val file = File(dataDir, HISTORY_FILE_NAME)
    private val mutex = Mutex()
    private val listSerializer = ListSerializer(HistoryItem.serializer())

    // _all = 含墓碑的完整清單(持久化 + 同步用);items = 只有未刪的(給 UI)
    private val _all = MutableStateFlow<List<HistoryItem>>(emptyList())
    val allItems: StateFlow<List<HistoryItem>> = _all.asStateFlow()
    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = file.readJsonOrBackup(fallback = { emptyList() }) {
                AppJson.decodeFromString(listSerializer, it)
            }
            setAll(loaded)
        }
    }

    // active 取未刪、按時間排序、限量;墓碑只留 TTL 內的(超過清掉)。同時更新兩個 flow + 落地。
    private fun setAll(list: List<HistoryItem>) {
        val now = System.currentTimeMillis()
        val active = list.filter { it.deletedAt == 0L }
            .sortedByDescending { it.updatedAt }
            .take(HistoryConfig.MAX_ITEMS)
        val tombstones = list.filter { it.deletedAt > 0L && it.deletedAt > now - TOMBSTONE_TTL_MS }
        val combined = active + tombstones
        _all.value = combined
        _items.value = active
        persist(combined)
    }

    fun find(videoId: Long, siteId: Long): HistoryItem? =
        _items.value.firstOrNull { it.videoId == videoId && it.siteId == siteId }

    suspend fun record(item: HistoryItem) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val filtered = _all.value.filterNot { it.videoId == item.videoId && it.siteId == item.siteId }
            setAll(listOf(item) + filtered)
        }
    }

    // 軟刪:標記 deletedAt=now 留在清單裡跟著同步(而非真的移除),否則下次同步會被其他裝置推回來復活
    suspend fun remove(videoId: Long, siteId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val updated = _all.value.map {
                if (it.videoId == videoId && it.siteId == siteId) it.copy(deletedAt = now) else it
            }
            setAll(updated)
        }
    }

    // 清空 = 把目前所有未刪的標記成墓碑(這樣「清空」也會同步出去)
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val updated = _all.value.map { if (it.deletedAt == 0L) it.copy(deletedAt = now) else it }
            setAll(updated)
        }
    }

    // 同步合併後整批覆寫(含墓碑)
    suspend fun replaceAll(items: List<HistoryItem>) = withContext(Dispatchers.IO) {
        mutex.withLock { setAll(items) }
    }

    suspend fun markUpdateStatus(videoId: Long, siteId: Long, totalEpisodes: Int) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = _all.value.map { item ->
                    if (item.videoId == videoId && item.siteId == siteId) {
                        val newCount = (totalEpisodes - item.totalEpisodes).coerceAtLeast(0)
                        item.copy(
                            totalEpisodes = totalEpisodes,
                            hasUpdate = totalEpisodes > item.totalEpisodes,
                            newEpisodesCount = newCount,
                        )
                    } else item
                }
                setAll(updated)
            }
        }

    private fun persist(items: List<HistoryItem>) {
        file.atomicWriteText(AppJson.encodeToString(listSerializer, items))
    }

    companion object {
        const val HISTORY_FILE_NAME = "history.json"
    }
}
