package tw.pp.kazi.data

/**
 * 影片列表海報的顯示方式。順序即首頁切換器上的編號 1～4。
 * - [CropAuto]  1 大圖裁切：自動偵測方向 + Crop 填滿，最大最滿，但站內尺寸不一時少數會被裁邊
 * - [FitAuto]   2 完整不裁：自動偵測方向 + Fit，多數派填滿、少數派留模糊邊，絕不裁切
 * - [Masonry]   3 瀑布流：每張用自己的真實比例高低錯落，不裁不留邊，電視遙控導航較跳
 * - [SquareFit] 4 固定方形：格子全正方形 + Fit，最整齊但圖偏小
 */
enum class PosterDisplayMode(val key: String, val label: String) {
    CropAuto("crop_auto", "大圖裁切"),
    FitAuto("fit_auto", "完整不裁"),
    Masonry("masonry", "瀑布流"),
    SquareFit("square_fit", "固定方形");

    companion object {
        val Default = CropAuto
        fun fromKey(key: String?): PosterDisplayMode =
            entries.firstOrNull { it.key == key } ?: Default
    }
}

/**
 * 一行幾張（圖的大小）。切換器上標號 a～c。[columnDelta] 疊加在各模式的基準欄數上。
 */
enum class PosterDensity(val key: String, val label: String, val columnDelta: Int) {
    Large("large", "大", -1),
    Standard("standard", "標準", 0),
    Compact("compact", "緊湊", 1);

    companion object {
        val Default = Standard
        fun fromKey(key: String?): PosterDensity =
            entries.firstOrNull { it.key == key } ?: Default
    }
}
