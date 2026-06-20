package tw.pp.kazi.data

/**
 * 從遠端遙控網頁送來的「裝這顆 APK 到電視盒」請求。
 * 兩種來源二選一:[localPath] 是手機已上傳到電視盒 cache 的檔;[url] 是要電視盒自己去下載的網址。
 */
data class RemoteApkInstallRequest(
    val fileName: String,
    val url: String? = null,
    val localPath: String? = null,
    // 每次送出給一個遞增序號:同一顆 APK 重送(例如「開好權限後再送一次」)時 value 才會變,
    // StateFlow 才會再 emit、畫面才會重觸發(否則 data class 相等被去重 → 重送沒反應)
    val seq: Long = 0,
)
