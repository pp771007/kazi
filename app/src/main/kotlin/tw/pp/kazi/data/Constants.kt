package tw.pp.kazi.data

object MacCmsApiSpec {
    const val VOD_API_PATH = "/api.php/provide/vod/"

    const val PARAM_KEYWORD = "wd"
    const val PARAM_TYPE = "t"
    const val PARAM_PAGE = "pg"
    const val PARAM_ACTION = "ac"
    const val PARAM_IDS = "ids"

    const val ACTION_VIDEOLIST = "videolist"

    const val CODE_SUCCESS = 1

    const val SOURCE_DELIMITER = "\$\$\$"
    const val EPISODE_DELIMITER = "#"
    const val NAME_URL_DELIMITER = "\$"

    val UNSUPPORTED_RESPONSES = setOf("暂不支持搜索", "不支持")

    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; KaziCinema) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"

    // 站台 HTTP 失敗時 log 多少 body 出來幫 debug（避免 log 爆）
    const val HTTP_ERROR_BODY_LOG_CHARS = 200
}

object NetworkConfig {
    const val CALL_TIMEOUT_SEC = 20L
    const val CONNECT_TIMEOUT_SEC = 8L
    const val READ_TIMEOUT_SEC = 15L
    const val WRITE_TIMEOUT_SEC = 15L
    const val CONN_POOL_SIZE = 8
    const val CONN_KEEPALIVE_MIN = 5L
    const val HEALTH_CHECK_PARALLELISM = 8
}

object HistoryConfig {
    const val MAX_ITEMS = 200
    const val SAVE_INTERVAL_MS = 15_000L
    const val UPDATE_CHECK_BATCH = 20
    const val POSITION_IGNORED_THRESHOLD_MS = 5_000L
}

object FavoriteConfig {
    const val MAX_ITEMS = 500
}

object PlayerConfig {
    const val SEEK_STEP_MS = 10_000L
    const val SEEK_STEP_LONG_MS = 60_000L

    // D-pad ←/→ 按住快進的 step 公式：step = base + held*rate（秒），封頂 maxStep；throttle 控制 fire 頻率。
    // 「中速」是 v0.5.82 四檔實測後使用者最順手的組合（rate=10, throttle=80ms 比之前 rate=5/100ms 累計快 2x）：
    //   - held=0: 10s（短按精準）
    //   - held=5: 60s
    //   - held=8: 90s（到頂）
    //   累計按 5 秒 ~36 分、按 10 秒 ~2 小時，對主流電視盒長片場景剛剛好
    const val SEEK_STEP_BASE_S = 10L
    const val SEEK_HOLD_LINEAR_RATE_PER_S = 10L
    const val SEEK_HOLD_MAX_STEP_S = 90L
    const val SEEK_HOLD_THROTTLE_MS = 80L

    const val CONTROLS_AUTO_HIDE_MS = 5_000L
    const val POSITION_POLL_MS = 500L
    // 左上角時鐘只顯示到分鐘，但 1 秒 tick 讓跨分鐘時即時跳動（成本遠低於上面的 position poll）
    const val CLOCK_TICK_MS = 1_000L
    val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    const val DEFAULT_SPEED = 1.0f
    // 長按全螢幕暫時加速到的倍數（YT pattern：鬆手回原本速度）
    const val LONG_PRESS_SPEED = 2.0f
    // 雙擊累加 seek 的 debounce：停止點擊多少毫秒後才實際 seek（省記憶體＋減少 buffer 抖動）
    const val SEEK_COMMIT_DELAY_MS = 700L
    // 網路類錯誤自動 retry 前等多久（等網路 settle）
    const val PLAYER_RETRY_DELAY_MS = 2000L

    // 影片比例偵測門檻：>1.1 視為橫向、<0.9 視為直向，之間不強制
    const val ORIENTATION_LANDSCAPE_THRESHOLD = 1.1f
    const val ORIENTATION_PORTRAIT_THRESHOLD = 0.9f

    // 手勢參數
    const val GESTURE_DECISION_PX = 16f
    const val GESTURE_INDICATOR_HIDE_MS = 700L
    const val SEEK_GESTURE_MAX_MS = 120_000L
    const val BRIGHTNESS_DEFAULT_FALLBACK = 0.5f

    // Log 記錄 URL 的最大長度
    const val URL_LOG_MAX_CHARS = 200
}

object SearchConfig {
    const val HISTORY_MAX = 20
}

object PosterConfig {
    // 自動判斷預覽圖方向：抽前 N 張實際解碼出長寬，多數決決定整個 grid 的格子形狀。
    // 只看前幾張就夠代表一個站台 / 一批搜尋結果，多了只是浪費頻寬。
    const val DETECT_SAMPLE_COUNT = 5

    // 偵測時把圖縮到這個邊長再解碼即可（只要長寬比，不需要原圖）→ 省記憶體，電視盒不會 OOM。
    const val DETECT_DECODE_PX = 256

    // 長寬比 (width / height) 分類門檻：>= 橫、<= 直、之間算方形。
    const val LANDSCAPE_RATIO = 1.2f
    const val PORTRAIT_RATIO = 0.85f

    // 混站畫面用 Fit 不裁切，空白處墊一張同圖的超小縮圖放大 → 天然模糊、不吃 GPU、不挑 Android 版本。
    const val BLUR_BG_DECODE_PX = 24

    // 密度往「緊湊」加欄時的下限，避免極端情況算出 0 / 1 欄。
    const val MIN_COLUMNS = 2

    // 瀑布流卡片在量到真實比例前先用的預設比例（直式 2:3），載入後依實際圖更新。
    const val MASONRY_DEFAULT_RATIO = 2f / 3f
}

object LanConfig {
    const val DEFAULT_PORT = 38721
}

object CheckStatusKeys {
    const val SUCCESS = "success"
    const val FAILED = "failed"
}

object ConfigKeys {
    const val SITE_TITLE = "site_title"
    const val REQUEST_TIMEOUT = "request_timeout"
    const val LAN_SHARE_ENABLED = "lan_share_enabled"
    const val INCOGNITO_MODE = "incognito_mode"
    const val SEARCH_HISTORY = "search_history"
    const val SITE_VIEW_MODES = "site_view_modes"
    const val POSTER_DISPLAY_MODE = "poster_display_mode"
    const val POSTER_DENSITY = "poster_density"
}
