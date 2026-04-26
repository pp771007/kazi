package tw.pp.kazi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    // --- compareSemver ----------------------------------------------------

    @Test
    fun `compareSemver—主版本大的勝出`() {
        assertTrue(UpdateChecker.compareSemver("1.0.0", "0.9.9") > 0)
    }

    @Test
    fun `compareSemver—次版本大的勝出`() {
        assertTrue(UpdateChecker.compareSemver("0.5.2", "0.5.1") > 0)
    }

    @Test
    fun `compareSemver—修訂號大的勝出`() {
        assertTrue(UpdateChecker.compareSemver("0.5.10", "0.5.9") > 0)
    }

    @Test
    fun `compareSemver—完全相同回 0`() {
        assertEquals(0, UpdateChecker.compareSemver("0.5.0", "0.5.0"))
    }

    @Test
    fun `compareSemver—不同段數靠右補 0`() {
        // 0.5 vs 0.5.0 應該相等
        assertEquals(0, UpdateChecker.compareSemver("0.5", "0.5.0"))
    }

    @Test
    fun `compareSemver—rc 後綴中非數字 token 被忽略`() {
        // mapNotNull{toIntOrNull()} 會把 "rc1" 整段濾掉（不是 int），所以 0.5.0-rc1 解析後是 [0,5,0]
        // 跟 0.5.0 一樣 → 視為相等。這跟標準 semver（1.0.0-rc1 < 1.0.0）不同，
        // 但對 kazi 來說夠用：我們不發 pre-release 給使用者，所以不會走到這條 corner case
        assertEquals(0, UpdateChecker.compareSemver("0.5.0-rc1", "0.5.0"))
    }

    @Test
    fun `compareSemver—反向比較對稱`() {
        assertTrue(UpdateChecker.compareSemver("0.5.0", "0.5.1") < 0)
    }

    // --- isNewerThanLocal -------------------------------------------------
    // BuildConfig.VERSION_NAME 在 unit test 環境是 unit test BuildConfig，難以 mock；
    // 透過 compareSemver 已涵蓋核心邏輯，這邊只驗 "0.0.0-local 一律當有更新" 這個特殊規則
    // 沒辦法直接驗（因為要 mock BuildConfig），而是靠 compareSemver 保證底層邏輯對

    // --- pickApkAsset -----------------------------------------------------

    @Test
    fun `pickApkAsset—找到第一個 apk asset`() {
        val release = GitHubRelease(
            tagName = "v0.5.0",
            htmlUrl = "https://github.com/x/y/releases/tag/v0.5.0",
            assets = listOf(
                GitHubAsset(name = "Source.zip", browserDownloadUrl = "u1"),
                GitHubAsset(name = "kazi-0.5.0.apk", browserDownloadUrl = "u2"),
                GitHubAsset(name = "checksums.txt", browserDownloadUrl = "u3"),
            ),
        )
        val asset = UpdateChecker.pickApkAsset(release)
        assertEquals("kazi-0.5.0.apk", asset?.name)
    }

    @Test
    fun `pickApkAsset—無 apk 回 null`() {
        val release = GitHubRelease(
            tagName = "v0.5.0",
            htmlUrl = "u",
            assets = listOf(
                GitHubAsset(name = "Source.zip", browserDownloadUrl = "u1"),
            ),
        )
        assertNull(UpdateChecker.pickApkAsset(release))
    }

    @Test
    fun `pickApkAsset—大小寫不敏感`() {
        val release = GitHubRelease(
            tagName = "v0.5.0",
            htmlUrl = "u",
            assets = listOf(GitHubAsset(name = "kazi.APK", browserDownloadUrl = "u1")),
        )
        assertEquals("kazi.APK", UpdateChecker.pickApkAsset(release)?.name)
    }
}
