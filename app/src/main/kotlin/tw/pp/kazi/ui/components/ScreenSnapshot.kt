package tw.pp.kazi.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import tw.pp.kazi.AppContainer
import tw.pp.kazi.ui.LocalAppContainer

/**
 * 畫面狀態 back-restore：取代「自寫 data class + container var + onDispose 三處同步」的樣板。
 *
 * 用法：
 *   val snap = rememberScreenSnapshot("home")
 *   var page by snap.state("page") { 1 }   // 進畫面讀回上次值，離開自動寫回
 *   // 主動退出時清掉，下次重進是 fresh state
 *   snap.discard()
 *
 * 為什麼不用 rememberSaveable？因為要存的東西包含大型 in-memory object（搜尋結果 / 影片清單），
 * Bundle 序列化負擔太大且型別限制多；這份是 process 存活內的還原，process 死掉本來就重來。
 */
class ScreenSnapshot internal constructor(
    private val container: AppContainer,
    private val key: String,
) {
    // var 不是 val：reset() 後要換成新的空 bag 才能讓本次 session 的 state 被存到 container
    private var bag = container.snapshotBag(key)
    private var savesBlocked = false

    /**
     * 把 bag 的舊資料清掉，但本次 session 的 state 變更仍會在離開時被存回 container。
     * 用在「snapshot 內容過期但畫面要繼續用」的情境（例如 SearchScreen 收到不同 keyword 的 nav arg）。
     */
    fun reset() {
        container.clearSnapshot(key)
        bag = container.snapshotBag(key)
    }

    /**
     * 永久停止這個 snapshot 的寫回，且清掉 bag。用在「畫面要徹底退出、不該保留任何狀態」的情境
     * （例如 HomeScreen 雙擊返回退 App）。之後就算 onDispose 跑也不會把當下 state 倒回 bag。
     */
    fun discard() {
        savesBlocked = true
        bag.clear()
        container.clearSnapshot(key)
    }

    fun <T> peek(stateKey: String): T? {
        @Suppress("UNCHECKED_CAST")
        return bag[stateKey] as? T
    }

    @Composable
    fun <T> state(stateKey: String, default: () -> T): MutableState<T> {
        val state = remember(stateKey) {
            @Suppress("UNCHECKED_CAST")
            val initial: T = if (bag.containsKey(stateKey)) bag[stateKey] as T else default()
            mutableStateOf(initial)
        }
        DisposableEffect(stateKey) {
            onDispose {
                if (!savesBlocked) bag[stateKey] = state.value
            }
        }
        return state
    }
}

@Composable
fun rememberScreenSnapshot(key: String): ScreenSnapshot {
    val container = LocalAppContainer.current
    return remember(key) { ScreenSnapshot(container, key) }
}
