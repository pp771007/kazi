package tw.pp.kazi.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * 標題列隨捲動收合 — 往下滑時頂列平滑往上收，往上滑一點就會顯示出來。
 * 典型行為像 IG / Chrome。
 */
class CollapsibleHeaderState {
    val offsetPx: MutableFloatState = mutableFloatStateOf(0f)
    val heightPx: MutableFloatState = mutableFloatStateOf(0f)

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val h = heightPx.floatValue
            if (h <= 0f || available.y == 0f) return Offset.Zero
            // SideEffect（程式碼觸發的 scroll，e.g. TV D-pad focus 的 bringIntoView）
            // 「往下方向」要排除，否則跟頂列收合會形成 feedback loop 害短 card 抖動。
            // 但「往上方向」(available.y > 0) 要接受 — 不然使用者用 D-pad 切到列表頂端時，
            // 列表雖然捲回頂了，offsetPx 沒更新，頂列會卡在收合位置一大段在畫面外
            if (source == NestedScrollSource.SideEffect && available.y < 0f) return Offset.Zero
            val newOffset = (offsetPx.floatValue + available.y).coerceIn(-h, 0f)
            offsetPx.floatValue = newOffset
            return Offset.Zero
        }
    }
}

@Composable
fun rememberCollapsibleHeaderState(): CollapsibleHeaderState = remember { CollapsibleHeaderState() }

/**
 * 外層容器。topBar 會隨著子內容的捲動自動收合。
 * content lambda 收到 [PaddingValues]，把它套到最外層才不會被頂列蓋住。
 */
@Composable
fun CollapsibleHeader(
    modifier: Modifier = Modifier,
    state: CollapsibleHeaderState = rememberCollapsibleHeaderState(),
    topBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(state.nestedScrollConnection),
    ) {
        val visibleBarPx = (state.heightPx.floatValue + state.offsetPx.floatValue).coerceAtLeast(0f)
        val topPadding = with(density) { visibleBarPx.toDp() }
        content(PaddingValues(top = topPadding))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { state.heightPx.floatValue = it.height.toFloat() }
                .offset { IntOffset(0, state.offsetPx.floatValue.roundToInt()) },
        ) {
            topBar()
        }
    }
}

