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
            // SideEffect（程式碼觸發的 scroll，e.g. TV D-pad focus 的 bringIntoView）特別處理，
            // 避免 bringIntoView 跟頂列收合互相拉扯：
            //  · 往下（收合方向）完全不收 — 不然會 feedback loop 害短 card 抖動。
            //  · 往上（捲回頂端）直接把頂列「攤平」到完全展開，而不是逐格累加 offset。
            //    逐格累加會在列表頂端讓 bringIntoView 反覆微調 offset → 第一列上下一直抖。
            //    直接歸 0 是冪等的：之後再來幾次往上 SideEffect 都還是 0、不再動 layout，迴圈就斷了；
            //    也順便修掉原本「D-pad 切到頂端但 offsetPx 沒更新、頂列卡在收合位置」的問題。
            if (source == NestedScrollSource.SideEffect) {
                if (available.y > 0f && offsetPx.floatValue != 0f) offsetPx.floatValue = 0f
                return Offset.Zero
            }
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
    // false = 頂列固定不收合（電視盒用：時鐘隨時看得到）。true = 隨捲動收合（手機用：把空間讓給內容）。
    collapsible: Boolean = true,
    topBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val barModifier = if (collapsible) {
        Modifier.fillMaxSize().nestedScroll(state.nestedScrollConnection)
    } else Modifier.fillMaxSize()
    Box(modifier = modifier.then(barModifier)) {
        // 固定模式 offset 恆 0 → 頂列釘住、內容固定空出整個頂列高度
        val offset = if (collapsible) state.offsetPx.floatValue else 0f
        val visibleBarPx = (state.heightPx.floatValue + offset).coerceAtLeast(0f)
        val topPadding = with(density) { visibleBarPx.toDp() }
        content(PaddingValues(top = topPadding))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { state.heightPx.floatValue = it.height.toFloat() }
                .offset { IntOffset(0, offset.roundToInt()) },
        ) {
            topBar()
        }
    }
}

