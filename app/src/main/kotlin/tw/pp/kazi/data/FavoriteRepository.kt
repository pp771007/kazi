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

class FavoriteRepository(context: Context) {

    private val dataDir = File(context.filesDir, ConfigRepository.DATA_DIR_NAME).apply { mkdirs() }
    private val file = File(dataDir, FAVORITES_FILE_NAME)
    private val mutex = Mutex()
    private val listSerializer = ListSerializer(FavoriteItem.serializer())

    private val _items = MutableStateFlow<List<FavoriteItem>>(emptyList())
    val items: StateFlow<List<FavoriteItem>> = _items.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _items.value = file.readJsonOrBackup(fallback = { emptyList() }) {
                AppJson.decodeFromString(listSerializer, it)
            }.sortedByDescending { it.addedAt }
        }
    }

    fun isFavorite(videoId: Long, siteId: Long): Boolean =
        _items.value.any { it.videoId == videoId && it.siteId == siteId }

    suspend fun toggle(item: FavoriteItem): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = _items.value.firstOrNull {
                it.videoId == item.videoId && it.siteId == item.siteId
            }
            val nowFavorite: Boolean
            val updated = if (existing != null) {
                nowFavorite = false
                _items.value.filterNot { it.videoId == item.videoId && it.siteId == item.siteId }
            } else {
                nowFavorite = true
                (listOf(item) + _items.value).take(FavoriteConfig.MAX_ITEMS)
            }
            _items.value = updated
            persist(updated)
            nowFavorite
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

    private fun persist(items: List<FavoriteItem>) {
        file.atomicWriteText(AppJson.encodeToString(listSerializer, items))
    }

    companion object {
        const val FAVORITES_FILE_NAME = "favorites.json"
    }
}
