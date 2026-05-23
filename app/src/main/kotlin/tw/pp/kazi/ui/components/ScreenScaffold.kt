package tw.pp.kazi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    // 想跟著頂列一起收合的次級內容（例如首頁的站點 / 分類列）。放在頂列下方、同屬會被捲動收起的 header 區
    subHeader: (@Composable () -> Unit)? = null,
    // 子畫面想自己控制 header（例如換頁時主動 reset 展開）就傳入；不傳就讓 scaffold 內部建一個
    headerState: CollapsibleHeaderState = rememberCollapsibleHeaderState(),
    content: @Composable (PaddingValues) -> Unit,
) {
    CollapsibleHeader(
        state = headerState,
        topBar = {
            // 頂列 + subHeader 視為同一塊會收合的 header；墊底色避免捲動時內容從半透明區透出來
            Column(modifier = Modifier.fillMaxWidth().background(AppColors.Bg)) {
                GradientTopBar(
                    title = title,
                    subtitle = subtitle,
                    titleBadges = titleBadges,
                    trailing = trailing,
                    onBack = onBack,
                )
                subHeader?.invoke()
            }
        },
        content = content,
    )
}
