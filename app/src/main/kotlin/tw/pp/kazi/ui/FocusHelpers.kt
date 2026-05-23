package tw.pp.kazi.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

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
