package tw.pp.kazi.ui

import tw.pp.kazi.data.PosterConfig
import tw.pp.kazi.ui.components.PosterFill

enum class GridLayout { Uniform, Masonry }

/**
 * 海報網格怎麼排。已定案的全 App 規則（手機瀑布流、電視方形），由螢幕大小直接決定，不再偵測方向：
 * - [grid]       Uniform = 整齊網格（電視，D-pad 好導航）；Masonry = 高低錯落（手機，圖最自然）
 * - [cellAspect] 整齊網格的格子比例；瀑布流每張卡用自己的真實比例，這裡只是預設值
 * - [fill]       圖怎麼填進格子；方形用 Fit 不裁切，瀑布流格子已等於圖比例所以 Crop 不會裁到
 * - [columns]    欄數
 */
data class PosterLayout(
    val grid: GridLayout,
    val cellAspect: Float,
    val fill: PosterFill,
    val columns: Int,
)

fun posterLayoutFor(size: WindowSize): PosterLayout = when (size) {
    // 電視：方形 + Fit + 標準密度
    WindowSize.Expanded -> PosterLayout(
        grid = GridLayout.Uniform,
        cellAspect = 1f,
        fill = PosterFill.Fit,
        columns = PosterConfig.TV_SQUARE_COLUMNS,
    )
    // 手機橫式 / 小平板：瀑布流 3 欄
    WindowSize.Medium -> PosterLayout(
        grid = GridLayout.Masonry,
        cellAspect = PosterConfig.MASONRY_DEFAULT_RATIO,
        fill = PosterFill.Crop,
        columns = PosterConfig.MASONRY_COLUMNS_MEDIUM,
    )
    // 手機直式：瀑布流 2 欄大圖
    WindowSize.Compact -> PosterLayout(
        grid = GridLayout.Masonry,
        cellAspect = PosterConfig.MASONRY_DEFAULT_RATIO,
        fill = PosterFill.Crop,
        columns = PosterConfig.MASONRY_COLUMNS_COMPACT,
    )
}
