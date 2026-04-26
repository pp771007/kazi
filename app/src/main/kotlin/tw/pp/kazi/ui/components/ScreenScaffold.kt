package tw.pp.kazi.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable

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
    content: @Composable (PaddingValues) -> Unit,
) {
    val headerState = rememberCollapsibleHeaderState()
    CollapsibleHeader(
        state = headerState,
        topBar = {
            GradientTopBar(
                title = title,
                subtitle = subtitle,
                titleBadges = titleBadges,
                trailing = trailing,
                onBack = onBack,
            )
        },
        content = content,
    )
}
