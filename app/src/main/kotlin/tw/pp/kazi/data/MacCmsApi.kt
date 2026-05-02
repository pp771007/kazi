package tw.pp.kazi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import tw.pp.kazi.util.Logger
import tw.pp.kazi.data.MacCmsApiSpec as Spec

class MacCmsApi {

    private val json = AppJson

    suspend fun fetchList(
        site: Site,
        keyword: String? = null,
        typeId: Long? = null,
        page: Int = 1,
    ): ApiResult<VideoListPage> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeUrl(site.url)
            val apiUrl = "$baseUrl${Spec.VOD_API_PATH}"
            val params = buildMap<String, String> {
                put(Spec.PARAM_PAGE, page.toString())
                keyword?.takeIf { it.isNotBlank() }?.let { put(Spec.PARAM_KEYWORD, it) }
                typeId?.let { put(Spec.PARAM_TYPE, it.toString()) }
            }

            val raw = request(apiUrl, params, site.sslVerify)
                ?: return@runCatching errorResult("空回應，站點可能已失效")

            if (raw in Spec.UNSUPPORTED_RESPONSES) {
                return@runCatching errorResult("該站台暫不支持搜尋")
            }

            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return@runCatching errorResult("API 回傳非合法 JSON")

            val code = readInt(root, "code")
            val total = readInt(root, "total")
            // 用 resp 前綴跟外層 fun 的 page 參數區分；shadow 容易搞混 +
            // 可讀性差（API 回傳 page=0 vs 我們送出去的 page=1）
            val respPage = readInt(root, "page")
            val respPageCount = readInt(root, "pagecount")
            val categories = (root["class"] as? JsonArray)?.mapNotNull { el ->
                runCatching { json.decodeFromJsonElement(Category.serializer(), el) }.getOrNull()
            }.orEmpty()

            if (code != Spec.CODE_SUCCESS) {
                if (keyword != null && total == 0) {
                    return@runCatching ApiResult.Success(
                        VideoListPage(
                            page = 0, pageCount = 0, total = 0,
                            videos = emptyList(), categories = categories,
                        )
                    )
                }
                val msg = (root["msg"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                return@runCatching errorResult(msg.ifBlank { "API 回傳錯誤" })
            }

            val videoObjs = (root["list"] as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
            if (videoObjs.isEmpty()) {
                return@runCatching ApiResult.Success(
                    VideoListPage(
                        page = respPage, pageCount = respPageCount, total = total,
                        videos = emptyList(), categories = categories,
                    )
                )
            }

            val ids = videoObjs.mapNotNull { it["vod_id"]?.jsonPrimitive?.longOrNull }
            val detailMap: Map<Long, JsonObject> = if (ids.isNotEmpty()) {
                val detailRaw = request(
                    apiUrl,
                    mapOf(
                        Spec.PARAM_ACTION to Spec.ACTION_VIDEOLIST,
                        Spec.PARAM_IDS to ids.joinToString(","),
                    ),
                    site.sslVerify,
                )
                val parsedRoot = detailRaw?.let {
                    runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
                }
                if (parsedRoot != null && readInt(parsedRoot, "code") == Spec.CODE_SUCCESS) {
                    (parsedRoot["list"] as? JsonArray).orEmpty()
                        .mapNotNull { it as? JsonObject }
                        .associateBy { it["vod_id"]?.jsonPrimitive?.longOrNull ?: MISSING_ID }
                        .filterKeys { it != MISSING_ID }
                } else emptyMap()
            } else emptyMap()

            val videos = videoObjs.map { obj ->
                val id = obj["vod_id"]?.jsonPrimitive?.longOrNull ?: 0L
                val detail = detailMap[id]
                val pic = resolvePic(baseUrl, obj, detail)
                val merged = mergeObjects(obj, detail, pic)
                json.decodeFromJsonElement(Video.serializer(), merged)
            }

            ApiResult.Success(
                VideoListPage(
                    page = respPage.coerceAtLeast(1),
                    pageCount = respPageCount.coerceAtLeast(1),
                    total = total,
                    videos = videos,
                    categories = categories,
                )
            )
        }.getOrElse { e ->
            Logger.w("fetchList failed for ${site.name}: ${e.javaClass.simpleName} ${e.message}")
            errorResult(networkErrorMessage(e))
        }
    }

    suspend fun fetchDetails(site: Site, vodId: Long): ApiResult<VideoDetails> =
        withContext(Dispatchers.IO) {
            runCatching {
                val baseUrl = normalizeUrl(site.url)
                val apiUrl = "$baseUrl${Spec.VOD_API_PATH}"
                val raw = request(
                    apiUrl,
                    mapOf(
                        Spec.PARAM_ACTION to Spec.ACTION_VIDEOLIST,
                        Spec.PARAM_IDS to vodId.toString(),
                    ),
                    site.sslVerify,
                ) ?: return@runCatching errorResult("詳情 API 空回應")

                if (raw in Spec.UNSUPPORTED_RESPONSES) {
                    return@runCatching errorResult("該站台暫不支持此功能")
                }

                // 詳情 API 不走嚴格 data class，直接用 JsonObject，避開站台欄位型別變異（int vs string）
                val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                    ?: return@runCatching errorResult("詳情 API 非合法 JSON")

                val code = (root["code"] as? JsonPrimitive)?.intOrNull
                    ?: (root["code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                    ?: 0
                if (code != Spec.CODE_SUCCESS) {
                    val msg = (root["msg"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    return@runCatching errorResult(msg.ifBlank { "API 回傳錯誤碼 $code" })
                }

                val list = root["list"] as? JsonArray
                    ?: return@runCatching errorResult("詳情缺少 list 欄位")
                val item = list.firstOrNull() as? JsonObject
                    ?: return@runCatching errorResult("詳情清單為空")

                val pic = resolvePic(baseUrl, item, item)
                val merged = mergeObjects(item, item, pic)
                val video = json.decodeFromJsonElement(Video.serializer(), merged)

                val playFrom = (item["vod_play_from"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val playUrl = (item["vod_play_url"] as? JsonPrimitive)?.contentOrNull.orEmpty()

                Logger.i(
                    "fetchDetails vodId=$vodId play_from.len=${playFrom.length} " +
                        "play_url.len=${playUrl.length} keys=${item.keys.take(20)}"
                )

                val sources = parseSources(playFrom, playUrl)

                if (sources.isEmpty()) {
                    Logger.w(
                        "fetchDetails: sources empty. play_from='${playFrom.take(120)}' " +
                            "play_url sample='${playUrl.take(200)}'"
                    )
                    return@runCatching errorResult(
                        "解析集數失敗（play_from 長 ${playFrom.length} / play_url 長 ${playUrl.length}）"
                    )
                }

                ApiResult.Success(VideoDetails(video = video, sources = sources))
            }.getOrElse { e ->
                Logger.w("fetchDetails failed for ${site.name}: ${e.javaClass.simpleName} ${e.message}")
                errorResult(networkErrorMessage(e))
            }
        }

    private fun parseSources(playFrom: String, playUrl: String): List<VideoSource> {
        if (playUrl.isBlank()) return emptyList()
        val flags = if (playFrom.isBlank()) listOf("") else playFrom.split(Spec.SOURCE_DELIMITER)
        val urls = playUrl.split(Spec.SOURCE_DELIMITER)
        val count = maxOf(flags.size, urls.size)
        return (0 until count).map { i ->
            val rawFlag = flags.getOrNull(i).orEmpty()
            val rawUrl = urls.getOrNull(i).orEmpty()
            val episodes = parseEpisodes(rawUrl)
            VideoSource(
                flag = rawFlag.ifBlank { "來源${i + 1}" },
                episodes = episodes,
            )
        }
    }

    private fun parseEpisodes(raw: String): List<Episode> {
        if (raw.isBlank()) return emptyList()
        return raw.trim()
            .split(Spec.EPISODE_DELIMITER)
            .filter { it.isNotBlank() }
            .mapIndexed { idx, seg ->
                // 用 limit=2 只切第一個 $，防止 URL 裡有 $ 把集數切爛
                val parts = seg.split(Spec.NAME_URL_DELIMITER, limit = 2)
                val fallbackName = "第${idx + 1}集"
                when (parts.size) {
                    2 -> Episode(
                        name = parts[0].ifBlank { fallbackName },
                        url = parts[1].trim(),
                    )
                    1 -> Episode(fallbackName, parts[0].trim())
                    else -> Episode(fallbackName, seg.trim())
                }
            }
            .filter { it.url.isNotBlank() }
    }

    suspend fun multiSiteSearch(
        sites: List<Site>,
        keyword: String,
        page: Int = 1,
    ): MultiSearchResult = coroutineScope {
        val deferred = sites.map { site ->
            async {
                val result = withTimeoutOrNull(PER_SITE_SEARCH_TIMEOUT_MS) {
                    fetchList(site, keyword = keyword, page = page)
                } ?: ApiResult.Error("搜尋逾時（${PER_SITE_SEARCH_TIMEOUT_MS / 1000} 秒）")
                site to result
            }
        }
        val pairs = deferred.awaitAll()

        val allVideos = mutableListOf<Video>()
        var maxPageCount = 0
        val perSite = mutableListOf<SiteSearchResult>()

        for ((site, result) in pairs) {
            when (result) {
                is ApiResult.Success -> {
                    val tagged = result.data.videos.map {
                        it.copy(
                            fromSite = site.name,
                            fromSiteId = site.id,
                            fromSiteUrl = site.url,
                        )
                    }
                    allVideos += tagged
                    perSite += SiteSearchResult(
                        siteId = site.id,
                        siteName = site.name,
                        status = if (tagged.isEmpty()) SiteSearchStatus.Empty else SiteSearchStatus.Success,
                        count = tagged.size,
                    )
                    if (result.data.pageCount > maxPageCount) maxPageCount = result.data.pageCount
                }
                is ApiResult.Error -> {
                    perSite += SiteSearchResult(
                        siteId = site.id,
                        siteName = site.name,
                        status = SiteSearchStatus.Failed,
                        count = 0,
                        message = result.message,
                    )
                }
            }
        }

        MultiSearchResult(
            videos = allVideos,
            page = page,
            pageCount = if (maxPageCount == 0) page else maxPageCount,
            total = allVideos.size,
            perSite = perSite,
        )
    }

    suspend fun checkSiteHealth(site: Site): HealthResult = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeUrl(site.url)
            val raw = request("$baseUrl${Spec.VOD_API_PATH}", emptyMap(), site.sslVerify)
            if (raw == null) {
                return@runCatching HealthResult(
                    site.id, site.name, site.url, HealthStatus.Failed, "連線失敗",
                )
            }
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            if (root != null && readInt(root, "code") == Spec.CODE_SUCCESS) {
                HealthResult(site.id, site.name, site.url, HealthStatus.Success, "正常")
            } else {
                val msg = (root?.get("msg") as? JsonPrimitive)?.contentOrNull
                HealthResult(
                    site.id, site.name, site.url, HealthStatus.Failed,
                    msg?.ifBlank { null } ?: "API 回傳異常",
                )
            }
        }.getOrElse { e ->
            HealthResult(site.id, site.name, site.url, HealthStatus.Error, networkErrorMessage(e))
        }
    }

    suspend fun checkSitesParallel(sites: List<Site>): List<HealthResult> = coroutineScope {
        sites.map { s -> async { checkSiteHealth(s) } }.awaitAll()
    }

    private fun request(url: String, params: Map<String, String>, sslVerify: Boolean): String? {
        val builder = url.toHttpUrlOrNull()?.newBuilder()
            ?: run {
                tw.pp.kazi.util.Logger.w("MacCmsApi.request: malformed URL $url")
                return null
            }
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val req = Request.Builder()
            .url(builder.build())
            .header("User-Agent", Spec.DEFAULT_USER_AGENT)
            .get()
            .build()
        HttpClients.forSite(sslVerify).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                // 之前 silent return null，現在把 status / body prefix 也 log 出來，debug 看得到
                val bodyPrefix = runCatching { resp.body?.string().orEmpty() }
                    .getOrDefault("")
                    .take(Spec.HTTP_ERROR_BODY_LOG_CHARS)
                tw.pp.kazi.util.Logger.w(
                    "MacCmsApi HTTP ${resp.code} on ${req.url}: ${bodyPrefix.replace("\n", " ").take(Spec.HTTP_ERROR_BODY_LOG_CHARS)}"
                )
                return null
            }
            return resp.body?.string()?.trim()
        }
    }

    /** 站台可能把 code/page/total 回傳字串或數字，兩種都吃 */
    private fun readInt(obj: JsonObject, key: String): Int {
        val prim = obj[key] as? JsonPrimitive ?: return 0
        return prim.intOrNull ?: prim.contentOrNull?.toIntOrNull() ?: 0
    }

    private fun resolvePic(baseUrl: String, primary: JsonObject?, detail: JsonObject?): String {
        val detailPic = detail?.get("vod_pic")?.jsonPrimitive?.contentOrNull
        val primaryPic = primary?.get("vod_pic")?.jsonPrimitive?.contentOrNull
        val pic = (detailPic ?: primaryPic).orEmpty()
        return when {
            pic.isBlank() -> ""
            pic.startsWith("http", ignoreCase = true) -> pic
            else -> "$baseUrl/${pic.trimStart('/')}"
        }
    }

    private fun mergeObjects(primary: JsonObject, detail: JsonObject?, resolvedPic: String): JsonElement {
        val merged = primary.toMutableMap()
        detail?.forEach { (k, v) -> if (!merged.containsKey(k)) merged[k] = v }
        merged["vod_pic"] = JsonPrimitive(resolvedPic)
        return JsonObject(merged)
    }

    private fun normalizeUrl(url: String): String {
        val withProto = if (url.startsWith("http", ignoreCase = true)) url else "http://$url"
        return withProto.trimEnd('/')
    }

    private fun errorResult(msg: String): ApiResult<Nothing> = ApiResult.Error(msg)

    private fun networkErrorMessage(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException -> "連線超時，站點可能已失效"
        is java.net.UnknownHostException -> "找不到站點主機"
        is java.net.ConnectException -> "無法連線到站點"
        else -> "網路錯誤：${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        private const val MISSING_ID = -1L
        private const val PER_SITE_SEARCH_TIMEOUT_MS = 5_000L
    }
}
