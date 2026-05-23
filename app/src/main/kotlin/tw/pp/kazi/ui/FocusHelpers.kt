package tw.pp.kazi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * 「focus 從外面進到這一列時,固定停在指定那顆 [target]」的通用做法。用在:
 * 搜尋頁進到那列固定停搜尋框、分頁進去固定停「下一頁」—— 同一套邏輯。
 *
 * 為什麼用 onFocusChanged + requestFocus,不用 focusGroup/focusRestorer/focusProperties.enter:
 * 那幾種「群組進入重導向」會讓目標看起來有 focus 但 OK 點不動(把點擊吃掉,見 CLAUDE.md)。
 * requestFocus 是真 focus、可立即按。
 *
 * 只在「從沒 focus → 有 focus」的進入瞬間導向 [target](hasFocus false→true);
 * 進來之後使用者再往旁邊移(例如從下一頁按←到上一頁)不會被一直拉回 → 仍可正常操作其他顆。
 * [target] 必須掛在這個 Modifier 範圍內某顆 focusable 上,且 [enabled] 為真時它確實 attach 著。
 */
@Composable
fun Modifier.enterFocusOn(target: FocusRequester, enabled: Boolean = true): Modifier {
    var wasFocused by remember { mutableStateOf(false) }
    return this.onFocusChanged { state ->
        if (enabled && state.hasFocus && !wasFocused) {
            runCatching { target.requestFocus() }
        }
        wasFocused = state.hasFocus
    }
}
