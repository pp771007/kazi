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

    private const val SEARCH_ROUTE_TEMPLATE = "search?keyword={keyword}&sites={sites}"
    private const val DETAIL_ROUTE_TEMPLATE = "detail/{siteId}/{vodId}"
    private const val PLAYER_ROUTE_TEMPLATE = "player/{siteId}/{vodId}/{sourceIdx}/{episodeIdx}/{positionMs}"

    const val SearchPattern = SEARCH_ROUTE_TEMPLATE
    const val DetailPattern = DETAIL_ROUTE_TEMPLATE
    const val PlayerPattern = PLAYER_ROUTE_TEMPLATE

    const val ArgKeyword = "keyword"
    const val ArgSites = "sites"
    const val ArgSiteId = "siteId"
    const val ArgVodId = "vodId"
    const val ArgSourceIdx = "sourceIdx"
    const val ArgEpisodeIdx = "episodeIdx"
    const val ArgPositionMs = "positionMs"

    fun search(keyword: String = "", siteIds: List<Long> = emptyList()): String {
        val k = URLEncoder.encode(keyword, Charsets.UTF_8.name())
        val s = siteIds.joinToString(",")
        return "search?keyword=$k&sites=$s"
    }

    fun detail(siteId: Long, vodId: Long) = "detail/$siteId/$vodId"

    fun player(
        siteId: Long,
        vodId: Long,
        sourceIdx: Int,
        episodeIdx: Int,
        positionMs: Long = 0L,
    ) = "player/$siteId/$vodId/$sourceIdx/$episodeIdx/$positionMs"
}
