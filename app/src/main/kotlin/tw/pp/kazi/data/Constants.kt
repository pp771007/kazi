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
    const val CONTROLS_AUTO_HIDE_MS = 5_000L
    const val POSITION_POLL_MS = 500L
    val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    const val DEFAULT_SPEED = 1.0f

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
    const val VIEW_MODE = "view_mode"
    const val LAN_SHARE_ENABLED = "lan_share_enabled"
    const val SEARCH_HISTORY = "search_history"
}
