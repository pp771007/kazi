package tw.pp.kazi.data

/**
 * 搜尋輸入解析後的結果。
 * `慶餘年 -第二季 -預告` → SearchQuery(include="慶餘年", excludes=["第二季", "預告"])
 */
data class SearchQuery(val include: String, val excludes: List<String>)

/**
 * 解析搜尋輸入。中文字串用空白分詞；以 `-` 開頭的 token 算排除詞，去重。
 * 單獨一個 `-` 不視為排除詞（避免 user 打到一半判錯）。
 */
fun parseSearchQuery(raw: String): SearchQuery {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return SearchQuery("", emptyList())
    val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val includeParts = mutableListOf<String>()
    val excludeParts = mutableListOf<String>()
    for (t in tokens) {
        if (t.length > 1 && t.startsWith("-")) {
            excludeParts += t.substring(1)
        } else if (t != "-") {
            includeParts += t
        }
    }
    return SearchQuery(
        include = includeParts.joinToString(" "),
        excludes = excludeParts.distinct(),
    )
}

/**
 * 多站搜尋結果聚合：同名影片合併成一筆，sources 保留所有來源。
 * pic / remarks 取第一個非空字串，避免顯示空白。
 */
data class AggregatedVideo(
    val name: String,
    val pic: String,
    val remarks: String,
    val sources: List<Video>,
)

fun aggregateByName(videos: List<Video>): List<AggregatedVideo> =
    videos.groupBy { it.vodName }.map { (name, list) ->
        AggregatedVideo(
            name = name,
            pic = list.firstOrNull { it.vodPic.isNotBlank() }?.vodPic ?: "",
            remarks = list.firstOrNull { it.vodRemarks.isNotBlank() }?.vodRemarks ?: "",
            sources = list,
        )
    }

/**
 * 把 result 套用排除詞過濾。空 excludes 直接回原物件。
 * perSite 的 count 會跟著重算；原本有量但全被過濾掉的站台會標 Empty（Failed 不動）。
 */
fun applyExcludes(r: MultiSearchResult, excludes: List<String>): MultiSearchResult {
    if (excludes.isEmpty()) return r
    val filtered = r.videos.filterNot { video ->
        excludes.any { ex -> video.vodName.contains(ex, ignoreCase = true) }
    }
    val filteredCountBySiteId = filtered.groupingBy { it.fromSiteId ?: 0L }.eachCount()
    val adjustedPerSite = r.perSite.map { s ->
        val newCount = filteredCountBySiteId[s.siteId] ?: 0
        when {
            s.status == SiteSearchStatus.Failed -> s
            newCount == 0 && s.count > 0 -> s.copy(count = 0, status = SiteSearchStatus.Empty)
            else -> s.copy(count = newCount)
        }
    }
    return r.copy(
        videos = filtered,
        total = filtered.size,
        perSite = adjustedPerSite,
    )
}
