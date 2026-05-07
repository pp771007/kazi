package tw.pp.kazi.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.isCompact

/**
 * 只在 Compact（手機）才包 PullToRefreshBox；TV / 平板維持普通 Box（遙控器沒下拉手勢，
 * 大螢幕也通常透過分頁/切換刷新，不需 PTR）。call site 寫法跟 Box 幾乎一樣，乾淨。
 *
 * isRefreshing 必須在 onRefresh 觸發後立刻為 true，PTR 才能維持指示器顯示直到 refresh 完成；
 * 用 fetch loading 當 isRefreshing 是最常見的接法，但 launch 內第一個 set 是 async 的，建議
 * 在 onRefresh callback 裡同步先把外部 loading flag 設成 true，避免指示器一閃而過。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshBoxIfCompact(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val windowSize = LocalWindowSize.current
    if (windowSize.isCompact) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier,
            content = content,
        )
    } else {
        Box(modifier = modifier, content = content)
    }
}
