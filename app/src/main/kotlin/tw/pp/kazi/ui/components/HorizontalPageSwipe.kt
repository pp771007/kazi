package tw.pp.kazi.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlin.math.abs
import kotlinx.coroutines.launch
import tw.pp.kazi.ui.theme.AppColors

private const val DRAG_RESISTANCE = 0.6f       // 內容跟手指位移的比例(越大越跟手)
private val COMMIT_DISTANCE = 56.dp            // 內容位移超過這距離就換頁。用固定 dp(非螢幕寬%)→ 橫式不必滑超遠
private const val FLICK_VELOCITY = 800f        // 快速一甩也換頁(px/s),不必拖滿門檻
private const val DISARM_RATIO = 0.5f          // 上膛後要往回拉超過門檻一半才解除(遲滯,免得門檻邊緣抖動反覆)
private const val MAX_DRAG_FRACTION = 0.5f     // 能換的方向最多拖半個螢幕寬
private const val DISABLED_PEEK_FRACTION = 0.06f // 沒得換的方向只給一點點回彈手感

/**
 * 手機左右滑換頁。左滑(內容往左)→ [onNext];右滑 → [onPrev]。
 * 防誤觸:要明顯水平滑、且超過螢幕寬 1/4 才換;只認水平,不影響內層上下捲動。
 * 視覺:拖動時內容跟著手指(帶阻尼)位移 + 邊緣浮出「下一頁/上一頁」提示;放手過門檻換頁、否則平滑彈回。
 * [enabled] false(電視盒 / 沒分頁)時完全不攔手勢。
 */
@Composable
fun HorizontalPageSwipe(
    enabled: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val scope = rememberCoroutineScope()
    val commitPx = with(androidx.compose.ui.platform.LocalDensity.current) { COMMIT_DISTANCE.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableIntStateOf(1) }
    // 上膛方向:-1=下一頁(左滑) +1=上一頁(右滑) 0=未上膛。滑過門檻就上膛、變藍鎖定,
    // 往回拉超過門檻一半(DISARM_RATIO)才解除 → 結尾往回一點點不會被取消。
    var armedDir by remember { mutableIntStateOf(0) }

    fun finishDrag(velocity: Float) {
        val o = offsetX
        val movedEnough = abs(o) > widthPx * 0.04f
        when {
            // 已上膛 → 照鎖定方向換;或同方向快速一甩(還沒拖滿門檻)也換
            armedDir < 0 && canNext -> { onNext(); offsetX = 0f }
            armedDir > 0 && canPrev -> { onPrev(); offsetX = 0f }
            velocity <= -FLICK_VELOCITY && movedEnough && canNext -> { onNext(); offsetX = 0f }
            velocity >= FLICK_VELOCITY && movedEnough && canPrev -> { onPrev(); offsetX = 0f }
            else -> scope.launch { animate(o, 0f, animationSpec = spring()) { v, _ -> offsetX = v } }
        }
        armedDir = 0
    }

    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            // 自己在 Initial pass 判定手勢方向:父層比子層(瀑布流捲動)先收到事件。一旦判定「水平占多數」,
            // 就在 Initial pass consume 掉 → 子層的上下捲動完全收不到、搶不走(修「斜著滑會被取消」)。
            // 判定為垂直就完全不 consume,讓捲動正常運作。
            .pointerInput(canPrev, canNext) {
                val tracker = VelocityTracker()
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    tracker.resetTracking()
                    val pointerId: PointerId = down.id
                    var dx = 0f
                    var dy = 0f
                    var decided = false
                    var horizontal = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (!change.pressed) break
                        val pc = change.positionChange()
                        if (!decided) {
                            dx += pc.x; dy += pc.y
                            if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                                decided = true
                                horizontal = abs(dx) >= abs(dy)
                            }
                        }
                        if (decided) {
                            if (!horizontal) break  // 垂直手勢 → 放掉,讓子層捲動
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            // 兩側各自的可拖上限:能換的方向給半螢幕、不能換的方向只給小幅 peek。
                            // 用「offset 落在哪一側(正負)」夾,不看單次移動方向 →
                            // 從深處往回甩時不會因「這一刻往反方向移」就把上限縮成 peek、把 offset 瞬間夾回原點(修「往回一點就變黑」)。
                            val minOff = if (canNext) -widthPx * MAX_DRAG_FRACTION else -widthPx * DISABLED_PEEK_FRACTION
                            val maxOff = if (canPrev) widthPx * MAX_DRAG_FRACTION else widthPx * DISABLED_PEEK_FRACTION
                            offsetX = (offsetX + pc.x * DRAG_RESISTANCE).coerceIn(minOff, maxOff)
                            when {
                                offsetX <= -commitPx && canNext -> armedDir = -1
                                offsetX >= commitPx && canPrev -> armedDir = 1
                                abs(offsetX) < commitPx * DISARM_RATIO -> armedDir = 0
                            }
                        }
                    }
                    if (decided && horizontal) finishDrag(tracker.calculateVelocity().x)
                }
            },
    ) {
        Box(Modifier.graphicsLayer { translationX = offsetX }) { content() }

        if (offsetX < -1f && canNext) {
            EdgeHint(Alignment.CenterEnd, Icons.AutoMirrored.Filled.ArrowForward, "下一頁", ready = armedDir < 0)
        } else if (offsetX > 1f && canPrev) {
            EdgeHint(Alignment.CenterStart, Icons.AutoMirrored.Filled.ArrowBack, "上一頁", ready = armedDir > 0)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BoxScope.EdgeHint(alignment: Alignment, icon: ImageVector, label: String, ready: Boolean) {
    Box(
        modifier = Modifier
            .align(alignment)
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (ready) AppColors.Primary else AppColors.BgElevated)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = AppColors.OnBg, modifier = Modifier.padding(end = 4.dp))
            Text(label, color = AppColors.OnBg, style = MaterialTheme.typography.labelMedium)
        }
    }
}
