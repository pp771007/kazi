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
 * 按↓時跳到 [target]:先把它捲進畫面([scrollToTarget])、等一格 frame 畫出來、再 requestFocus。
 * 電視盒 D-pad 純空間搜尋會「跳過下一排」(從右側按鈕往下、最後一列卡片往下到分頁)時補這一手,
 * 而且目標常在畫面下方還沒 compose(整頁卡片下面的分頁、內外層分頁),直接 requestFocus 會落空 →
 * 一律「先確認顯示再 focus」。一律消費↓事件,避免又被正常空間搜尋帶去上一頁。
 *
 * 為什麼安全(踩過的雷都避開了,見 CLAUDE.md):
 * - 「按鍵事件」觸發,不是焦點狀態(onFocusChanged)→ 不會自我觸發成無窮迴圈 ANR。
 * - 不是 focusProperties 落點 / focusGroup 重導向 → 不會把 OK 點擊吃掉。
 * - requestFocus 包 runCatching → 萬一還是沒掛上也不會「FocusRequester is not initialized」閃退。
 *
 * [scope] 用呼叫端的 rememberCoroutineScope;[scrollToTarget] 例如 gridState.animateScrollToItem(footerIndex)。
 * [target] 為 null 時不掛(例如沒有下一頁、手機觸控不需要)。
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
