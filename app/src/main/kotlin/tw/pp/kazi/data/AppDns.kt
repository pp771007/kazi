package tw.pp.kazi.data

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import tw.pp.kazi.util.Logger
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 一組 DoH(DNS over HTTPS)服務。bootstrap = 服務本身的 IP,免得「查 DNS 伺服器的位址」又得靠系統 DNS。 */
data class DohProvider(
    val id: String,
    val label: String,
    val url: String,
    val bootstrap: List<String>,
)

object DnsProviders {
    const val SYSTEM_ID = "system"
    const val CUSTOM_ID = "custom"

    // 端點與 bootstrap IP 都實際打過 RFC 8484 查詢確認會回 application/dns-message
    val PRESETS = listOf(
        DohProvider("cloudflare", "Cloudflare", "https://cloudflare-dns.com/dns-query", listOf("1.1.1.1", "1.0.0.1")),
        DohProvider("google", "Google", "https://dns.google/dns-query", listOf("8.8.8.8", "8.8.4.4")),
        // Quad9 只接受 HTTP/2(HTTP/1.1 回 505),OkHttp 會自動協商 h2
        DohProvider("quad9", "Quad9", "https://dns.quad9.net/dns-query", listOf("9.9.9.9", "149.112.112.112")),
        DohProvider("adguard", "AdGuard 擋廣告", "https://dns.adguard-dns.com/dns-query", listOf("94.140.14.14", "94.140.15.15")),
        DohProvider("twnic", "TWNIC 101", "https://dns.twnic.tw/dns-query", listOf("101.101.101.101", "101.102.103.104")),
        DohProvider("alidns", "阿里", "https://dns.alidns.com/dns-query", listOf("223.5.5.5", "223.6.6.6")),
        DohProvider("dnspod", "騰訊", "https://doh.pub/dns-query", listOf("1.12.12.12", "120.53.53.53")),
    )

    /** 依設定挑出要用的 DoH;null = 用系統 DNS。自訂網址不合法時也回 null。 */
    fun resolve(providerId: String, customUrl: String): DohProvider? = when (providerId) {
        SYSTEM_ID -> null
        CUSTOM_ID -> normalizeCustomUrl(customUrl)?.let { DohProvider(CUSTOM_ID, "自訂", it.toString(), emptyList()) }
        else -> PRESETS.firstOrNull { it.id == providerId }
    }

    /**
     * 自訂輸入:完整網址照用;只打了 IP / 網域(例如 "8.8.8.8"、"dns.google")就補成 https://<輸入>/dns-query,
     * 多數 DoH 服務都掛在這個路徑。
     */
    fun normalizeCustomUrl(input: String): HttpUrl? {
        val s = input.trim()
        if (s.isEmpty()) return null
        val full = if (s.contains("://")) s else "https://$s$DEFAULT_DOH_PATH"
        return full.toHttpUrlOrNull()
    }

    private const val DEFAULT_DOH_PATH = "/dns-query"
}

/**
 * 全 App 共用的 DNS 解析器:所有 OkHttpClient(API / 同步 / 更新 / 圖片 / 開了 DoH 時的播放器)都掛這一顆,
 * 換 DNS 設定只要換掉裡面的 delegate,不用重建各個 client。
 */
object AppDns : Dns {

    @Volatile private var doh: DnsOverHttps? = null

    // DoH 每次查詢都是一個 HTTPS 請求;系統 DNS 有 OS 層快取,DoH 沒有 → 自己留一份短效快取,
    // 免得每開一條新連線(圖片、影片分段換 host)都多一趟來回。
    private val cache = ConcurrentHashMap<String, CachedAnswer>()

    private class CachedAnswer(val addresses: List<InetAddress>, val expiresAt: Long)

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(DnsConfig.TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(DnsConfig.TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    fun apply(provider: DohProvider?) {
        doh = provider?.let(::build)
        cache.clear()
        Logger.i("DNS -> ${provider?.url ?: "system"}")
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val d = doh ?: return Dns.SYSTEM.lookup(hostname)
        if (isIpLiteral(hostname)) return Dns.SYSTEM.lookup(hostname)
        val now = System.currentTimeMillis()
        cache[hostname]?.takeIf { it.expiresAt > now }?.let { return it.addresses }
        return try {
            d.lookup(hostname).also {
                cache[hostname] = CachedAnswer(it, now + DnsConfig.CACHE_TTL_MS)
            }
        } catch (e: UnknownHostException) {
            // DoH 連不上(服務被擋 / 斷線)或是區網名稱 → 退回系統 DNS,寧可照舊能用也不要整個 App 連不上
            Logger.w("DoH lookup failed for $hostname, fallback to system: ${e.message}")
            Dns.SYSTEM.lookup(hostname)
        }
    }

    /** 設定頁「試一下」用:直接打這組 DoH(不走 fallback),回傳耗時毫秒;失敗丟例外。 */
    fun probe(provider: DohProvider): Long {
        val start = System.nanoTime()
        build(provider).lookup(DnsConfig.PROBE_HOST)
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    private fun build(provider: DohProvider): DnsOverHttps =
        DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(provider.url.toHttpUrlOrNull() ?: error("bad DoH url: ${provider.url}"))
            .apply {
                if (provider.bootstrap.isNotEmpty()) {
                    bootstrapDnsHosts(provider.bootstrap.map { InetAddress.getByName(it) })
                }
            }
            .build()

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || host.all { it.isDigit() || it == '.' }
}
