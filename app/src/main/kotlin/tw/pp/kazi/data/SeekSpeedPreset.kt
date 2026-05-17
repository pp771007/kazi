package tw.pp.kazi.data

/**
 * 電視盒 D-pad ←/→ 按住快進的速度預設組。Slow（標準）= 之前 v0.5.76 的調整。
 *
 * step(held) = baseSec + held × ratePerSec，封頂 maxStepSec；throttle 控制 fire 頻率。
 * 提供四檔讓使用者依電視盒按住 KeyEvent 速率 / 個人習慣挑：累計移動量參考表（throttle 跟 step 共同決定）：
 *
 *   檔位     按 5 秒累計    按 10 秒累計
 *   Slow     ~18 分        ~1 小時
 *   Medium   ~36 分        ~2 小時
 *   Fast     ~1 小時       ~4 小時
 *   Turbo    ~2 小時       ~7 小時
 */
enum class SeekSpeedPreset(
    val key: String,
    val label: String,
    val description: String,
    val baseSec: Long,
    val ratePerSec: Long,
    val maxStepSec: Long,
    val throttleMs: Long,
) {
    Slow("slow", "慢速（標準）", "按 10 秒約 1 小時", 10L, 5L, 60L, 100L),
    Medium("medium", "中速", "按 10 秒約 2 小時", 10L, 10L, 90L, 80L),
    Fast("fast", "快速", "按 10 秒約 4 小時", 15L, 15L, 120L, 60L),
    Turbo("turbo", "極速", "按 10 秒約 7 小時", 20L, 25L, 180L, 50L);

    companion object {
        val Default: SeekSpeedPreset = Slow
        fun fromKey(key: String?): SeekSpeedPreset =
            entries.firstOrNull { it.key == key } ?: Default
    }
}
