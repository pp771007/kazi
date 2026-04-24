package tw.pp.kazi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Site(
    val id: Long,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    @SerialName("ssl_verify") val sslVerify: Boolean = true,
    val note: String = "",
    val order: Int = 0,
    @SerialName("last_check") val lastCheck: String? = null,
    @SerialName("consecutive_errors") val consecutiveErrors: Int = 0,
    @SerialName("check_status") val checkStatus: String? = null,
)

@Serializable
data class Category(
    @SerialName("type_id") val typeId: Long,
    @SerialName("type_name") val typeName: String,
    @SerialName("type_pid") val typePid: Long = 0,
)

@Serializable
data class Video(
    @SerialName("vod_id") val vodId: Long,
    @SerialName("vod_name") val vodName: String = "",
    @SerialName("vod_pic") val vodPic: String = "",
    @SerialName("vod_remarks") val vodRemarks: String = "",
    @SerialName("vod_year") val vodYear: String = "",
    @SerialName("vod_area") val vodArea: String = "",
    @SerialName("vod_lang") val vodLang: String = "",
    @SerialName("type_name") val typeName: String = "",
    @SerialName("vod_time") val vodTime: String = "",
    @SerialName("vod_content") val vodContent: String = "",
    @SerialName("vod_director") val vodDirector: String = "",
    @SerialName("vod_actor") val vodActor: String = "",
    val fromSite: String? = null,
    val fromSiteUrl: String? = null,
    val fromSiteId: Long? = null,
)

data class Episode(val name: String, val url: String)

data class VideoSource(val flag: String, val episodes: List<Episode>)

data class VideoDetails(
    val video: Video,
    val sources: List<VideoSource>,
)

data class VideoListPage(
    val page: Int,
    val pageCount: Int,
    val total: Int,
    val videos: List<Video>,
    val categories: List<Category>,
)

data class MultiSearchResult(
    val videos: List<Video>,
    val page: Int,
    val pageCount: Int,
    val total: Int,
    val perSite: List<SiteSearchResult>,
)

data class SiteSearchResult(
    val siteId: Long,
    val siteName: String,
    val status: SiteSearchStatus,
    val count: Int,
    val message: String? = null,
)

enum class SiteSearchStatus { Success, Empty, Failed }

data class HealthResult(
    val siteId: Long,
    val name: String,
    val url: String,
    val status: HealthStatus,
    val message: String,
)

enum class HealthStatus { Success, Failed, Error, Skipped }

@Serializable
data class FavoriteItem(
    val videoId: Long,
    val videoName: String,
    val videoPic: String = "",
    val vodRemarks: String = "",
    val siteId: Long,
    val siteName: String,
    val siteUrl: String,
    val addedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class HistoryItem(
    val videoId: Long,
    val videoName: String,
    val videoPic: String = "",
    val siteId: Long,
    val siteName: String,
    val siteUrl: String,
    val sourceIndex: Int = 0,
    val episodeIndex: Int = 0,
    val episodeName: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val totalEpisodes: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val hasUpdate: Boolean = false,
    val newEpisodesCount: Int = 0,
)

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String) : ApiResult<Nothing>()
}

