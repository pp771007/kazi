package tw.pp.kazi.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 按下某方向鍵時,把 focus 直接 requestFocus 到 [target]。電視盒 D-pad 純空間搜尋會「跳過下一排」
 * (例如從右側按鈕往下、或最後一列卡片往下到分頁)時補這一手。
 *
 * 為什麼安全(不會像之前那些做法閃退 / ANR):
 * - 是「按鍵事件」觸發,不是焦點狀態(onFocusChanged)觸發 → 不會自我觸發成無窮迴圈 ANR。
 * - 不是 focusProperties 落點 / focusGroup 重導向 → 不會把 OK 點擊吃掉。
 * - requestFocus 包 runCatching:[target] 還沒掛上(虛擬化 / 還沒 compose)時不消費事件、
 *   退回正常的空間搜尋(往下捲),不會「FocusRequester is not initialized」閃退。
 *
 * [target] 為 null 時不掛(例如沒有下一頁、手機觸控不需要)。
 */
fun Modifier.keyFocusJump(key: Key, target: FocusRequester?): Modifier {
    if (target == null) return this
    return this.onPreviewKeyEvent { ke ->
        if (ke.type == KeyEventType.KeyDown && ke.key == key) {
            runCatching { target.requestFocus() }.isSuccess
        } else false
    }
}

/**
 * 跟 [keyFocusJump] 一樣是「按↓跳到 [target]」,但 [target] 可能在畫面下方還沒 compose(例如整頁卡片
 * 下面的分頁、內層分頁下面的外層分頁)。直接 requestFocus 會失敗 → 改成「先 [scrollToTarget] 把它捲進畫面、
 * 等一格 frame 畫出來、再 requestFocus(包 runCatching)」。一律消費↓事件(避免又被正常空間搜尋帶去上一頁)。
 *
 * [scope] 用呼叫端的 rememberCoroutineScope;[scrollToTarget] 例如 gridState.animateScrollToItem(footerIndex)。
 */
fun Modifier.keyScrollFocus(
    scope: CoroutineScope,
    target: FocusRequester?,
    scrollToTarget: suspend () -> Unit,
): Modifier {
    if (target == null) return this
    return this.onPreviewKeyEvent { ke ->
        if (ke.type == KeyEventType.KeyDown && ke.key == Key.DirectionDown) {
            scope.launch {
                scrollToTarget()
                delay(50)
                runCatching { target.requestFocus() }
            }
            true
        } else false
    }
}
