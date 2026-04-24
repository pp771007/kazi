package tw.pp.kazi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import tw.pp.kazi.util.Logger

data class ProbeResult(
    val healthy: Boolean,
    val name: String?,
    val message: String,
)

class SiteScanner(private val api: MacCmsApi) {

    suspend fun probe(baseUrl: String): ProbeResult {
        val dummy = Site(id = 0, name = "", url = baseUrl)
        val health = api.checkSiteHealth(dummy)
        if (health.status != HealthStatus.Success) {
            return ProbeResult(healthy = false, name = null, message = health.message)
        }
        val name = runCatching { fetchSiteName(baseUrl) }
            .onFailure { Logger.w("fetchSiteName failed for $baseUrl: ${it.message}") }
            .getOrNull()
        return ProbeResult(healthy = true, name = name, message = health.message)
    }

    private suspend fun fetchSiteName(baseUrl: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(baseUrl)
            .header("User-Agent", DEFAULT_UA)
            .get()
            .build()
        HttpClients.forSite(sslVerify = true).newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            extractTitle(body)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun extractTitle(html: String): String? {
        val raw = TITLE_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        return unescapeHtml(raw).trim().takeIf { it.isNotEmpty() }
    }

    private fun unescapeHtml(s: String): String = s
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

    companion object {
        private val TITLE_REGEX = Regex(
            "<title[^>]*>([\\s\\S]*?)</title>",
            RegexOption.IGNORE_CASE,
        )
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
