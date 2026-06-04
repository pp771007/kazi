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

    // 使用者手動操作(刪除 / 清空)→ 發訊號讓同步立刻上傳,不走播放進度那條 30 秒 debounce
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

    // 各線路獨立一筆的鍵:videoId + siteId + 線路名
    private fun keyOf(it: HistoryItem) = "${it.videoId}|${it.siteId}|${it.sourceFlag}"

    // 把舊資料正規化成「每線路一筆」:
    // - 帶 lines 表的(v0.20.0 過渡格式 / 別台同步來的)→ 每條線路炸成一筆;
    // - 沒有 lines 的 → 本來就一筆(sourceFlag 可能空 = 更舊的資料);
    // - 墓碑原樣保留。最後用 (videoId,siteId,sourceFlag) 去重取較新。
    private fun explodeForSplit(list: List<HistoryItem>): List<HistoryItem> {
        val out = ArrayList<HistoryItem>()
        for (it in list) {
            if (it.deletedAt > 0L) { out.add(it.copy(lines = emptyMap())); continue }
            if (it.lines.isNotEmpty()) {
                for ((flag, lp) in it.lines) {
                    out.add(it.copy(
                        sourceFlag = flag,
                        episodeIndex = lp.episodeIndex,
                        episodeName = lp.episodeName.ifBlank { it.episodeName },
                        positionMs = lp.positionMs,
                        durationMs = lp.durationMs,
                        totalEpisodes = if (lp.totalEpisodes > 0) lp.totalEpisodes else it.totalEpisodes,
                        updatedAt = if (lp.updatedAt > 0) lp.updatedAt else it.updatedAt,
                        lines = emptyMap(),
                    ))
                }
                // 頂層線路不在 lines 裡的話補一筆(保險,別漏掉目前線路)
                if (it.sourceFlag.isNotBlank() && !it.lines.containsKey(it.sourceFlag)) {
                    out.add(it.copy(lines = emptyMap()))
                }
            } else {
                out.add(it.copy(lines = emptyMap()))
            }
        }
        val eff = { x: HistoryItem -> maxOf(x.updatedAt, x.deletedAt) }
        val byKey = LinkedHashMap<String, HistoryItem>()
        for (it in out) {
            val ex = byKey[keyOf(it)]
            if (ex == null || eff(it) >= eff(ex)) byKey[keyOf(it)] = it
        }
        return byKey.values.toList()
    }

    // active 取未刪、按時間排序、限量;墓碑只留 TTL 內的(超過清掉)。同時更新兩個 flow + 落地。
    // 進來的資料先正規化成「每線路一筆」,讓 load / record / 同步各路徑都統一成拆開後的格式。
    private fun setAll(list: List<HistoryItem>) {
        val now = System.currentTimeMillis()
        val normalized = explodeForSplit(list)
        val active = normalized.filter { it.deletedAt == 0L }
            .sortedByDescending { it.updatedAt }
            .take(HistoryConfig.MAX_ITEMS)
        val tombstones = normalized.filter { it.deletedAt > 0L && it.deletedAt > now - TOMBSTONE_TTL_MS }
        val combined = active + tombstones
        _all.value = combined
        _items.value = active
        persist(combined)
    }

    // 某部片某站台「某條線路」的紀錄
    fun find(videoId: Long, siteId: Long, sourceFlag: String): HistoryItem? =
        _items.value.firstOrNull { it.videoId == videoId && it.siteId == siteId && it.sourceFlag == sourceFlag }

    // 某部片某站台「最近看的那條線路」(詳情頁「繼續看」/自動挑線路用)
    fun findLatest(videoId: Long, siteId: Long): HistoryItem? =
        _items.value.filter { it.videoId == videoId && it.siteId == siteId }.maxByOrNull { it.updatedAt }

    suspend fun record(item: HistoryItem) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // 只取代「同一條線路」那一筆,其他線路的紀錄保留
            val filtered = _all.value.filterNot {
                it.videoId == item.videoId && it.siteId == item.siteId && it.sourceFlag == item.sourceFlag
            }
            setAll(listOf(item) + filtered)
        }
    }

    // 軟刪:標記 deletedAt=now 留在清單裡跟著同步(而非真的移除)。拆開後一張卡=一條線路,刪的是該線路那筆。
    suspend fun remove(videoId: Long, siteId: Long, sourceFlag: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val updated = _all.value.map {
                if (it.videoId == videoId && it.siteId == siteId && it.sourceFlag == sourceFlag) it.copy(deletedAt = now) else it
            }
            setAll(updated)
        }
        _discreteChange.tryEmit(Unit)
    }

    // 清空 = 把目前所有未刪的標記成墓碑(這樣「清空」也會同步出去)
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val updated = _all.value.map { if (it.deletedAt == 0L) it.copy(deletedAt = now) else it }
            setAll(updated)
        }
        _discreteChange.tryEmit(Unit)
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
