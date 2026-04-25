package tw.pp.kazi.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import tw.pp.kazi.ui.LocalWindowSize
import tw.pp.kazi.ui.WindowSize
import tw.pp.kazi.ui.isCompact
import tw.pp.kazi.ui.theme.AppColors
import tw.pp.kazi.ui.topBarHorizontal
import tw.pp.kazi.ui.topBarVertical

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    primary: Boolean = true,
    danger: Boolean = false,
    iconOnly: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()

    val bg = when {
        !enabled -> Color(0xFF2A2A3E)
        danger && focused -> AppColors.Error
        danger -> AppColors.Error.copy(alpha = 0.85f)
        primary && focused -> AppColors.PrimaryVariant
        primary -> AppColors.Primary
        focused -> AppColors.Surface
        else -> AppColors.BgElevated
    }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(160), label = "btn-scale")
    // border 寬度固定 2dp 不動畫，避免每次 focus 變化造成 layout shift
    val borderBrush = rememberFocusFlowBrush(active = focused, idleColor = Color.Transparent)
    val borderWidth = 2.dp
    // 微立體：rest 有 2dp 陰影，focus 4dp、按下去 0dp（沉下去的觸感）
    val elevation by animateDpAsState(
        when {
            !enabled -> 0.dp
            pressed -> 0.dp
            focused -> 4.dp
            else -> 2.dp
        },
        tween(120),
        label = "btn-elev",
    )

    val hPad = if (iconOnly) BUTTON_ICON_ONLY_PAD else BUTTON_H_PAD
    val vPad = BUTTON_V_PAD

    Row(
        verticalAlignment = Alignment.CenterVertically,
        // 內容置中：當 button 被 weight/fillMaxWidth 撐開時，避免文字偏左讓 button 看起來像 input
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = modifier
            .scale(scale)
            .shadow(elevation, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(BorderStroke(borderWidth, borderBrush), RoundedCornerShape(10.dp))
            .focusable(enabled = enabled, interactionSource = interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = hPad, vertical = vPad),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = text,
                tint = if (enabled) AppColors.OnBg else AppColors.OnBgDim,
                modifier = Modifier.size(18.dp),
            )
        }
        if (!iconOnly) {
            Text(
                text = text,
                color = if (enabled) AppColors.OnBg else AppColors.OnBgDim,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FocusableTag(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    val bg = when {
        selected -> AppColors.Primary
        focused -> AppColors.Surface
        else -> AppColors.BgElevated
    }
    val borderBrush = rememberFocusFlowBrush(active = focused, idleColor = Color.Transparent)
    val borderWidth = 2.dp
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(160), label = "tag-scale")

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(BorderStroke(borderWidth, borderBrush), RoundedCornerShape(999.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            color = if (selected || focused) AppColors.OnBg else AppColors.OnBgMuted,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterCard(
    title: String,
    remarks: String,
    imageUrl: String,
    fromSite: String? = null,
    aspectRatio: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val context = LocalContext.current

    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(200), label = "card-scale")
    // border 寬度固定 3dp，只切換 brush（focus = 流光 / idle = 微透明灰），避免 layout 抖動
    val borderBrush = rememberFocusFlowBrush(active = focused, idleColor = Color(0x15FFFFFF))
    val borderWidth = 3.dp
    val elevation by animateDpAsState(if (focused) 12.dp else 0.dp, tween(160), label = "card-elev")

    val baseModifier = modifier
        .scale(scale)
        .shadow(elevation, RoundedCornerShape(12.dp))
        .clip(RoundedCornerShape(12.dp))
        .background(AppColors.BgCard)
        .border(BorderStroke(borderWidth, borderBrush), RoundedCornerShape(12.dp))
    val focusedModifier = if (focusRequester != null) baseModifier.focusRequester(focusRequester) else baseModifier
    Column(
        modifier = focusedModifier
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .background(AppColors.ShimmerStart),
        ) {
            if (imageUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(imageUrl).crossfade(true).build(),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (remarks.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = remarks,
                        color = AppColors.OnBg,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (fromSite != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(AppColors.Primary.copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = fromSite,
                        color = AppColors.OnBg,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        // 電視盒上字太小看不清楚 → wide 用 bodyMedium、compact 維持 bodySmall
        val windowSize = LocalWindowSize.current
        Text(
            text = title,
            color = AppColors.OnBg,
            style = if (windowSize.isCompact) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .heightIn(min = if (windowSize.isCompact) 36.dp else 48.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.verticalGradient(listOf(AppColors.Primary, AppColors.Secondary))),
            )
            Text(
                text = title,
                color = AppColors.OnBg,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        action?.invoke()
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EmptyState(
    title: String,
    subtitle: String = "",
    icon: ImageVector = Icons.Filled.Error,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.OnBgDim, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, color = AppColors.OnBg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle, color = AppColors.OnBgMuted,
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
        }
        action?.let {
            Spacer(Modifier.height(18.dp))
            it()
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String = "載入中⋯") {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PulsingDot()
        Spacer(Modifier.height(14.dp))
        Text(label, color = AppColors.OnBgMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PulsingDot() {
    val phase = remember { androidx.compose.animation.core.Animatable(0.4f) }
    LaunchedEffect(Unit) {
        while (true) {
            phase.animateTo(1f, tween(800))
            phase.animateTo(0.4f, tween(800))
        }
    }
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(50))
            .background(AppColors.Primary.copy(alpha = phase.value)),
    )
}

/**
 * 自適應標題列：
 * - Compact 手機直立：標題行 + 可橫向捲動的操作按鈕行
 * - Expanded 電視：標題與按鈕同列
 */
@Composable
fun GradientTopBar(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    titleBadges: (@Composable RowScope.() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val windowSize = LocalWindowSize.current
    val bg = Brush.horizontalGradient(listOf(AppColors.BgElevated, AppColors.Bg))

    if (windowSize.isCompact) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(bg)
                .padding(
                    horizontal = windowSize.topBarHorizontal(),
                    vertical = windowSize.topBarVertical(),
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TitleColumn(title = title, subtitle = subtitle, titleBadges = titleBadges, compact = true)
            if (trailing != null) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) { trailing() }
                    }
                }
            }
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(bg)
                .padding(
                    horizontal = windowSize.topBarHorizontal(),
                    vertical = windowSize.topBarVertical(),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TitleColumn(title = title, subtitle = subtitle, titleBadges = titleBadges, compact = false)
            }
            if (trailing != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) { trailing() }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TitleColumn(
    title: String,
    subtitle: String?,
    titleBadges: (@Composable RowScope.() -> Unit)?,
    compact: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = AppColors.OnBg,
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (titleBadges != null) titleBadges()
    }
    if (subtitle != null) {
        Text(
            subtitle,
            color = AppColors.OnBgMuted,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 給 GradientTopBar.titleBadges 用的小膠囊。傳 onClick 就會變成可點擊的 toggle。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderBrush = rememberFocusFlowBrush(
        active = focused && onClick != null,
        idleColor = Color.Transparent,
    )
    // 只有可點的 pill 預留 border 空間（避免抖動）；不可點的維持無框
    val borderWidth = if (onClick != null) 2.dp else 0.dp
    val baseModifier = modifier
        .clip(RoundedCornerShape(999.dp))
        .background(Color(0x33FFFFFF))
        .border(BorderStroke(borderWidth, borderBrush), RoundedCornerShape(999.dp))
    val pillModifier = if (onClick != null) {
        baseModifier
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
    } else {
        baseModifier
    }
    Box(
        modifier = pillModifier.padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            color = AppColors.OnBg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

val CardShape: Shape = RoundedCornerShape(14.dp)

private val BUTTON_H_PAD = 14.dp
private val BUTTON_V_PAD = 9.dp
private val BUTTON_ICON_ONLY_PAD = 9.dp

/**
 * 流光 focus border：active 時用一個漸層 brush 沿 border 走動，比單色框醒目很多。
 * idleColor: 沒 focus 時的 border 顏色（用 SolidColor 包起來）。
 */
@Composable
private fun rememberFocusFlowBrush(active: Boolean, idleColor: Color): Brush {
    if (!active) return SolidColor(idleColor)
    val transition = rememberInfiniteTransition(label = "focus-flow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "focus-flow-phase",
    )
    val tile = 220f
    // shift 走完整一個 tile（0 → tile），加上首尾同色 + Repeated tile mode → 接縫無感
    val shift = phase * tile
    return Brush.linearGradient(
        colors = listOf(
            AppColors.Primary,
            Color(0xFFFFFFFF),
            AppColors.Secondary,
            AppColors.Primary,
        ),
        start = Offset(shift, 0f),
        end = Offset(shift + tile, tile),
        tileMode = TileMode.Repeated,
    )
}
