package tw.pp.kazi.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
private const val COMMIT_FRACTION = 0.15f      // 拖過螢幕寬 15% 就換頁(手指實際約 1/4 螢幕)
private const val FLICK_VELOCITY = 800f        // 快速一甩也換頁(px/s),不必拖滿門檻
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
    var offsetX by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableIntStateOf(1) }

    val dragState = rememberDraggableState { delta ->
        val goingNext = delta < 0
        val allowed = if (goingNext) canNext else canPrev
        val factor = if (allowed) DRAG_RESISTANCE else DRAG_RESISTANCE * 0.25f
        val limit = if (allowed) widthPx * MAX_DRAG_FRACTION else widthPx * DISABLED_PEEK_FRACTION
        offsetX = (offsetX + delta * factor).coerceIn(-limit, limit)
    }

    Box(
        modifier = modifier
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStopped = { velocity ->
                    val commit = widthPx * COMMIT_FRACTION
                    val o = offsetX
                    // 快速一甩(velocity 過門檻、且有移動一點)也算,不必拖滿距離
                    val flick = abs(velocity) >= FLICK_VELOCITY && abs(o) > widthPx * 0.04f
                    when {
                        (o <= -commit || (flick && velocity < 0)) && canNext -> { onNext(); offsetX = 0f }
                        (o >= commit || (flick && velocity > 0)) && canPrev -> { onPrev(); offsetX = 0f }
                        else -> scope.launch { animate(o, 0f, animationSpec = spring()) { v, _ -> offsetX = v } }
                    }
                },
            ),
    ) {
        Box(Modifier.graphicsLayer { translationX = offsetX }) { content() }

        val commit = widthPx * COMMIT_FRACTION
        if (offsetX < -1f && canNext) {
            EdgeHint(Alignment.CenterEnd, Icons.AutoMirrored.Filled.ArrowForward, "下一頁", ready = -offsetX >= commit)
        } else if (offsetX > 1f && canPrev) {
            EdgeHint(Alignment.CenterStart, Icons.AutoMirrored.Filled.ArrowBack, "上一頁", ready = offsetX >= commit)
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
