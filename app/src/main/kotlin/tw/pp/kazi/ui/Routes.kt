package tw.pp.kazi.ui

import java.net.URLEncoder

object Routes {
    const val Home = "home"
    const val Setup = "setup"
    const val Settings = "settings"
    const val History = "history"
    const val Favorites = "favorites"
    const val LanShare = "lan_share"
    const val Logs = "logs"
    const val ScanSites = "scan_sites"

    private const val SEARCH_ROUTE_TEMPLATE = "search?keyword={keyword}&sites={sites}&auto={auto}"
    private const val DETAIL_ROUTE_TEMPLATE = "detail/{siteId}/{vodId}"
    private const val PLAYER_ROUTE_TEMPLATE = "player/{siteId}/{vodId}/{sourceIdx}/{episodeIdx}/{positionMs}?siteUrl={siteUrl}"

    const val SearchPattern = SEARCH_ROUTE_TEMPLATE
    const val DetailPattern = DETAIL_ROUTE_TEMPLATE
    const val PlayerPattern = PLAYER_ROUTE_TEMPLATE

    const val ArgKeyword = "keyword"
    const val ArgSites = "sites"
    const val ArgAuto = "auto"
    const val ArgSiteId = "siteId"
    const val ArgVodId = "vodId"
    const val ArgSourceIdx = "sourceIdx"
    const val ArgEpisodeIdx = "episodeIdx"
    const val ArgPositionMs = "positionMs"
    const val ArgSiteUrl = "siteUrl"

    // autoSearch=false:只帶關鍵字進搜尋頁、聚焦輸入框(游標在最後)、不自動搜(詳情頁的「搜尋」用)。
    fun search(keyword: String = "", siteIds: List<Long> = emptyList(), autoSearch: Boolean = true): String {
        val k = URLEncoder.encode(keyword, Charsets.UTF_8.name())
        val s = siteIds.joinToString(",")
        return "search?keyword=$k&sites=$s&auto=$autoSearch"
    }

    fun detail(siteId: Long, vodId: Long) = "detail/$siteId/$vodId"

    fun player(
        siteId: Long,
        vodId: Long,
        sourceIdx: Int,
        episodeIdx: Int,
        positionMs: Long = 0L,
        siteUrl: String = "",
    ): String {
        // siteUrl 是「跨裝置續看」的退路:同步來的歷史本地 siteId 對不上時(siteId 是 hashCode
        // 湊的假值),PlayerScreen 改用 siteUrl 找站台。從本地站台(詳情頁)播放可不帶,siteId 一定對得上。
        val u = URLEncoder.encode(siteUrl, Charsets.UTF_8.name())
        return "player/$siteId/$vodId/$sourceIdx/$episodeIdx/$positionMs?siteUrl=$u"
    }
}
