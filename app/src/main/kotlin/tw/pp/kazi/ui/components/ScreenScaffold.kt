package tw.pp.kazi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.isTv
import tw.pp.kazi.ui.theme.AppColors

/**
 * 子畫面共用的外殼：頂列（標題 + 返回 + trailing actions）+ 捲動時自動收合 + 內容區
 *
 * 把 rememberCollapsibleHeaderState + CollapsibleHeader + GradientTopBar 三層組合包起來，
 * 子畫面只要寫 ScreenScaffold(title = "...", onBack = { nav.popBackStack() }) { innerPadding -> ... } 就好。
 */
@Composable
fun ScreenScaffold(
    title: String,
    subtitle: String? = null,
    titleBadges: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    // 想跟著捲動收合的次級內容（例如首頁站點/分類列）。一定會收合（連電視盒也收，釋放影片格空間）。
    subHeader: (@Composable () -> Unit)? = null,
    // 子畫面想自己控制 header（例如換頁時主動 reset 展開）就傳入；不傳就讓 scaffold 內部建一個
    headerState: CollapsibleHeaderState = rememberCollapsibleHeaderState(),
    content: @Composable (PaddingValues) -> Unit,
) {
    // 電視盒釘住頂列（時鐘隨時可見），只收 subHeader；手機整塊一起收。
    CollapsibleHeader(
        state = headerState,
        pinTopBar = LocalWindowSize.current.isTv,
        topBar = {
            GradientTopBar(
                title = title,
                subtitle = subtitle,
                titleBadges = titleBadges,
                trailing = trailing,
                onBack = onBack,
            )
        },
        subHeader = subHeader?.let { sub ->
            // 墊不透明底色：收合滑動 / 內容捲到底下時，不會從站點列縫隙透出內容
            { Box(Modifier.fillMaxWidth().background(AppColors.Bg)) { sub() } }
        },
        content = content,
    )
}
