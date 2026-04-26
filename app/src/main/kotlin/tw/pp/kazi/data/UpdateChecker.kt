package tw.pp.kazi.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import tw.pp.kazi.BuildConfig
import java.io.File

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("body") val body: String? = null,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    @SerialName("size") val size: Long = 0,
)

object UpdateChecker {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/pp771007/kazi/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    private const val DOWNLOAD_DIR = "update"
    private const val DOWNLOAD_BUF_SIZE = 8 * 1024
    private const val LOCAL_DEV_VERSION = "0.0.0-local"

    suspend fun fetchLatest(): GitHubRelease = withContext(Dispatchers.IO) {
        val client = HttpClients.forSite(sslVerify = true)
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("empty body")
            AppJson.decodeFromString(GitHubRelease.serializer(), body)
        }
    }

    /**
     * 把 "v0.5.1" / "0.5.1" 跟 BuildConfig.VERSION_NAME 比較。
     * 本機 dev build（"0.0.0-local"）一律回 true（任何 release 都比本機新，方便測試）。
     */
    fun isNewerThanLocal(remoteTag: String): Boolean {
        val local = BuildConfig.VERSION_NAME
        if (local == LOCAL_DEV_VERSION) return true
        return compareSemver(remoteTag.removePrefix("v"), local.removePrefix("v")) > 0
    }

    // 簡單的 semver 比較：用 . 跟 - 切，能轉 int 的取，逐段比；
    // 0.5.1 > 0.5.0、0.5.0 == 0.5.0、0.5.0-rc1 視為 0.5.0.1（夠用）
    private fun compareSemver(a: String, b: String): Int {
        val ap = a.split(".", "-").mapNotNull { it.toIntOrNull() }
        val bp = b.split(".", "-").mapNotNull { it.toIntOrNull() }
        val n = maxOf(ap.size, bp.size)
        for (i in 0 until n) {
            val av = ap.getOrElse(i) { 0 }
            val bv = bp.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    fun pickApkAsset(release: GitHubRelease): GitHubAsset? =
        release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    suspend fun download(
        context: Context,
        asset: GitHubAsset,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }
        // 把 cache 裡上次下載過的舊 .apk 清掉，免得 disk 一直長
        dir.listFiles()?.forEach { if (it.name != asset.name) it.delete() }
        val out = File(dir, asset.name)
        val client = HttpClients.forSite(sslVerify = true)
        val request = Request.Builder().url(asset.browserDownloadUrl).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            val total = if (asset.size > 0) asset.size else body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(DOWNLOAD_BUF_SIZE)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
        }
        out
    }

    fun canInstallApks(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun installApk(context: Context, file: File) {
        val authority = BuildConfig.APPLICATION_ID + FILE_PROVIDER_SUFFIX
        val uri = FileProvider.getUriForFile(context, authority, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    /**
     * App 啟動時呼叫：把 cache 裡留下來的 APK 清掉。
     *
     * 流程：使用者下載 → 安裝 → app 被新版取代 → 新版 bootstrap → cleanupCache。
     * 這時 cache 裡那顆 APK 就沒用了（它是上一版的安裝來源），刪掉省 ~15 MB。
     * 半下載失敗或使用者沒按確認的殘檔也順便一起清。
     */
    fun cleanupCache(context: Context) {
        val dir = File(context.cacheDir, DOWNLOAD_DIR)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.delete() }
    }
}
