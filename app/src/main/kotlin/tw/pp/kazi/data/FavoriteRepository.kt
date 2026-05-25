package tw.pp.kazi.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    // _all = 含墓碑的完整清單(持久化 + 同步用);items = 只有未刪的(給 UI)
    private val _all = MutableStateFlow<List<FavoriteItem>>(emptyList())
    val allItems: StateFlow<List<FavoriteItem>> = _all.asStateFlow()
    private val _items = MutableStateFlow<List<FavoriteItem>>(emptyList())
    val items: StateFlow<List<FavoriteItem>> = _items.asStateFlow()

    // 使用者手動操作(收藏 / 取消 / 清空)→ 發訊號讓同步立刻上傳,不走播放進度那條 30 秒 debounce
    private val _discreteChange = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val discreteChange: SharedFlow<Unit> = _discreteChange.asSharedFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = file.readJsonOrBackup(fallback = { emptyList() }) {
                AppJson.decodeFromString(listSerializer, it)
            }
            setAll(loaded)
        }
    }

    private fun setAll(list: List<FavoriteItem>) {
        val now = System.currentTimeMillis()
        val active = list.filter { it.deletedAt == 0L }
            .sortedByDescending { it.addedAt }
            .take(FavoriteConfig.MAX_ITEMS)
        val tombstones = list.filter { it.deletedAt > 0L && it.deletedAt > now - TOMBSTONE_TTL_MS }
        val combined = active + tombstones
        _all.value = combined
        _items.value = active
        persist(combined)
    }

    fun isFavorite(videoId: Long, siteId: Long): Boolean =
        _items.value.any { it.videoId == videoId && it.siteId == siteId }

    suspend fun toggle(item: FavoriteItem): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val isActive = _items.value.any { it.videoId == item.videoId && it.siteId == item.siteId }
            val others = _all.value.filterNot { it.videoId == item.videoId && it.siteId == item.siteId }
            val nowFavorite: Boolean
            if (isActive) {
                // 取消收藏 = 留一個墓碑跟著同步
                nowFavorite = false
                setAll(others + item.copy(deletedAt = System.currentTimeMillis()))
            } else {
                // 收藏(可能是之前刪過的,直接以新的 active 蓋過去)
                nowFavorite = true
                setAll(listOf(item.copy(deletedAt = 0)) + others)
            }
            nowFavorite
        }.also { _discreteChange.tryEmit(Unit) }
    }

    suspend fun remove(videoId: Long, siteId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val updated = _all.value.map {
                if (it.videoId == videoId && it.siteId == siteId) it.copy(deletedAt = now) else it
            }
            setAll(updated)
        }
        _discreteChange.tryEmit(Unit)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val updated = _all.value.map { if (it.deletedAt == 0L) it.copy(deletedAt = now) else it }
            setAll(updated)
        }
        _discreteChange.tryEmit(Unit)
    }

    // 同步合併後整批覆寫(含墓碑)
    suspend fun replaceAll(items: List<FavoriteItem>) = withContext(Dispatchers.IO) {
        mutex.withLock { setAll(items) }
    }

    private fun persist(items: List<FavoriteItem>) {
        file.atomicWriteText(AppJson.encodeToString(listSerializer, items))
    }

    companion object {
        const val FAVORITES_FILE_NAME = "favorites.json"
    }
}
