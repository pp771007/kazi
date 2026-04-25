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

    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _items.value = file.readJsonOrBackup(fallback = { emptyList() }) {
                AppJson.decodeFromString(listSerializer, it)
            }.sortedByDescending { it.updatedAt }
        }
    }

    fun find(videoId: Long, siteId: Long): HistoryItem? =
        _items.value.firstOrNull { it.videoId == videoId && it.siteId == siteId }

    suspend fun record(item: HistoryItem) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val filtered = _items.value.filterNot {
                it.videoId == item.videoId && it.siteId == item.siteId
            }
            val updated = (listOf(item) + filtered).take(HistoryConfig.MAX_ITEMS)
            _items.value = updated
            persist(updated)
        }
    }

    suspend fun remove(videoId: Long, siteId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = _items.value.filterNot { it.videoId == videoId && it.siteId == siteId }
            _items.value = updated
            persist(updated)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _items.value = emptyList()
            persist(emptyList())
        }
    }

    suspend fun markUpdateStatus(videoId: Long, siteId: Long, totalEpisodes: Int) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val updated = _items.value.map { item ->
                    if (item.videoId == videoId && item.siteId == siteId) {
                        val newCount = (totalEpisodes - item.totalEpisodes).coerceAtLeast(0)
                        item.copy(
                            totalEpisodes = totalEpisodes,
                            hasUpdate = totalEpisodes > item.totalEpisodes,
                            newEpisodesCount = newCount,
                        )
                    } else item
                }
                _items.value = updated
                persist(updated)
            }
        }

    private fun persist(items: List<HistoryItem>) {
        file.atomicWriteText(AppJson.encodeToString(listSerializer, items))
    }

    companion object {
        const val HISTORY_FILE_NAME = "history.json"
    }
}
