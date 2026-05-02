package tw.pp.kazi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tw.pp.kazi.data.ViewMode

enum class WindowSize { Compact, Medium, Expanded }

object BreakPoints {
    const val COMPACT_MAX_DP = 600
    const val MEDIUM_MAX_DP = 900
}

object Spacing {
    val PagePaddingCompact = 14.dp
    val PagePaddingExpanded = 32.dp

    val TopBarHorizontalCompact = 14.dp
    val TopBarHorizontalExpanded = 32.dp
    val TopBarVerticalCompact = 10.dp
    val TopBarVerticalExpanded = 16.dp

    val SectionGapCompact = 14.dp
    val SectionGapExpanded = 20.dp

    val GridGapCompact = 8.dp
    val GridGapExpanded = 14.dp
}

val LocalWindowSize = compositionLocalOf { WindowSize.Expanded }

@Composable
fun rememberWindowSize(): WindowSize {
    val w = LocalConfiguration.current.screenWidthDp
    return remember(w) {
        when {
            w < BreakPoints.COMPACT_MAX_DP -> WindowSize.Compact
            w < BreakPoints.MEDIUM_MAX_DP -> WindowSize.Medium
            else -> WindowSize.Expanded
        }
    }
}

val WindowSize.isCompact: Boolean get() = this == WindowSize.Compact

/**
 * 是否要主動 requestFocus 當作「進頁面預設 focus 起點」。
 * 只在 Expanded（電視盒 / 大平板）跑：D-pad 導航需要 focus 起點，沒主動設會卡。
 * Compact / Medium（手機直/橫）不主動搶焦：觸控使用者不需要 visible focus 邊框，
 * 主動 focus 反而會在 input box / button 上看到不必要的白框
 */
val WindowSize.isTv: Boolean get() = this == WindowSize.Expanded

fun WindowSize.pagePadding(): Dp =
    if (isCompact) Spacing.PagePaddingCompact else Spacing.PagePaddingExpanded

fun WindowSize.topBarHorizontal(): Dp =
    if (isCompact) Spacing.TopBarHorizontalCompact else Spacing.TopBarHorizontalExpanded

fun WindowSize.topBarVertical(): Dp =
    if (isCompact) Spacing.TopBarVerticalCompact else Spacing.TopBarVerticalExpanded

fun WindowSize.sectionGap(): Dp =
    if (isCompact) Spacing.SectionGapCompact else Spacing.SectionGapExpanded

fun WindowSize.gridGap(): Dp =
    if (isCompact) Spacing.GridGapCompact else Spacing.GridGapExpanded

/**
 * 手機直立時要更少欄位，否則 portrait poster 會細到看不清楚。
 */
fun ViewMode.columnsFor(size: WindowSize): Int = when (size) {
    WindowSize.Compact -> when (this) {
        ViewMode.Portrait -> 3
        ViewMode.Landscape -> 2
        ViewMode.Square -> 3
    }
    WindowSize.Medium -> when (this) {
        ViewMode.Portrait -> 4
        ViewMode.Landscape -> 3
        ViewMode.Square -> 4
    }
    WindowSize.Expanded -> columns
}
