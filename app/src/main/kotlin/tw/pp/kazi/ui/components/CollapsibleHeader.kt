package tw.pp.kazi.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.SideEffect
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
 * 標題列隨捲動收合 — 往下滑時收起、往上滑一點就顯示。典型行為像 IG / Chrome。
 *
 * 兩塊：[topBar]（可選擇釘住）+ subHeader（一定隨捲動收合）。
 * - 手機：整塊（topBar + subHeader）一起收，把空間全給內容。
 * - 電視盒：topBar 釘住（時鐘隨時看得到），只有 subHeader（站點/分類列）收合。
 */
class CollapsibleHeaderState {
    // 收合位移，<= 0（0 = 完全展開）。套用在「會收的那塊」上。
    val offsetPx: MutableFloatState = mutableFloatStateOf(0f)
    val topBarPx: MutableFloatState = mutableFloatStateOf(0f)
    val subHeaderPx: MutableFloatState = mutableFloatStateOf(0f)
    // 最多能收多少（>=0）。由 composable 依「是否釘住 topBar」算出後寫入。
    val maxCollapsePx: MutableFloatState = mutableFloatStateOf(0f)

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val max = maxCollapsePx.floatValue
            if (max <= 0f || available.y == 0f) return Offset.Zero
            // SideEffect（程式碼觸發的 scroll，如 TV D-pad focus 的 bringIntoView）特別處理：
            // 往下不收（避免 feedback loop 抖動）；往上（回頂端）直接攤平到 0（冪等、斷迴圈）。
            if (source == NestedScrollSource.SideEffect) {
                if (available.y > 0f && offsetPx.floatValue != 0f) offsetPx.floatValue = 0f
                return Offset.Zero
            }
            offsetPx.floatValue = (offsetPx.floatValue + available.y).coerceIn(-max, 0f)
            return Offset.Zero
        }
    }
}

@Composable
fun rememberCollapsibleHeaderState(): CollapsibleHeaderState = remember { CollapsibleHeaderState() }

/**
 * 外層容器。subHeader 一定隨捲動收合；topBar 視 [pinTopBar] 決定釘住或一起收。
 * content lambda 收到 [PaddingValues]，把它套到最外層才不會被 header 蓋住。
 */
@Composable
fun CollapsibleHeader(
    modifier: Modifier = Modifier,
    state: CollapsibleHeaderState = rememberCollapsibleHeaderState(),
    // true = topBar 釘住不收（只收 subHeader）；false = topBar 跟 subHeader 一起收
    pinTopBar: Boolean = false,
    topBar: @Composable () -> Unit,
    subHeader: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    val topH = state.topBarPx.floatValue
    val subH = state.subHeaderPx.floatValue
    // 可收範圍：釘住 topBar 時只收 subHeader；否則整塊都能收。
    val maxCollapse = if (pinTopBar) subH else topH + subH
    SideEffect { state.maxCollapsePx.floatValue = maxCollapse }

    val offset = state.offsetPx.floatValue.coerceIn(-maxCollapse, 0f)
    // 釘住時內容至少空出 topBar 高度；不釘時可一路收到 0。
    val pinnedFloor = if (pinTopBar) topH else 0f
    val visibleHeaderPx = (topH + subH + offset).coerceAtLeast(pinnedFloor)

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(state.nestedScrollConnection),
    ) {
        content(PaddingValues(top = with(density) { visibleHeaderPx.toDp() }))

        // subHeader 先畫，位在 topBar 下方、跟著 offset 往上收。收進 topBar 區域的部分會被後畫、
        // 自帶不透明底色的 topBar 蓋住；超出螢幕頂端的部分由螢幕邊界裁掉。
        if (subHeader != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, (topH + offset).roundToInt()) }
                    .onSizeChanged { state.subHeaderPx.floatValue = it.height.toFloat() },
            ) {
                subHeader()
            }
        }

        // topBar 後畫 → 蓋在 subHeader 上方。釘住時 offset 不套用(固定在頂端)。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, (if (pinTopBar) 0f else offset).roundToInt()) }
                .onSizeChanged { state.topBarPx.floatValue = it.height.toFloat() },
        ) {
            topBar()
        }
    }
}
