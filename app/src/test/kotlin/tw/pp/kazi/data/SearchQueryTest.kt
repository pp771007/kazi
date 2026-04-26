package tw.pp.kazi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {

    // --- parseSearchQuery -------------------------------------------------

    @Test
    fun `空字串回空 query`() {
        val q = parseSearchQuery("")
        assertEquals("", q.include)
        assertTrue(q.excludes.isEmpty())
    }

    @Test
    fun `純空白回空 query`() {
        val q = parseSearchQuery("   ")
        assertEquals("", q.include)
        assertTrue(q.excludes.isEmpty())
    }

    @Test
    fun `單一 token 進 include`() {
        val q = parseSearchQuery("測試片")
        assertEquals("測試片", q.include)
        assertTrue(q.excludes.isEmpty())
    }

    @Test
    fun `多個正向 token 用空白合併`() {
        val q = parseSearchQuery("測試片 第一季")
        assertEquals("測試片 第一季", q.include)
    }

    @Test
    fun `負向 token 進 excludes`() {
        val q = parseSearchQuery("測試片 -第二季 -預告")
        assertEquals("測試片", q.include)
        assertEquals(listOf("第二季", "預告"), q.excludes)
    }

    @Test
    fun `重複的負向 token 去重`() {
        val q = parseSearchQuery("片名 -廣告 -廣告 -預告")
        assertEquals(listOf("廣告", "預告"), q.excludes)
    }

    @Test
    fun `單獨一個 - 不視為排除詞`() {
        // 使用者打到一半，不應該以為要排除空字串
        val q = parseSearchQuery("片名 -")
        assertEquals("片名", q.include)
        assertTrue(q.excludes.isEmpty())
    }

    // --- aggregateByName --------------------------------------------------

    private fun video(id: Long, name: String, pic: String = "", remarks: String = ""): Video =
        Video(
            vodId = id,
            vodName = name,
            vodPic = pic,
            vodRemarks = remarks,
            fromSiteId = 1L,
            fromSite = "site",
        )

    @Test
    fun `aggregateByName 同名合併 sources`() {
        val list = listOf(
            video(1, "測試片"),
            video(2, "測試片"),
            video(3, "其他片"),
        )
        val agg = aggregateByName(list)
        assertEquals(2, agg.size)
        val target = agg.first { it.name == "測試片" }
        assertEquals(2, target.sources.size)
    }

    @Test
    fun `aggregateByName pic 取第一個非空字串`() {
        val list = listOf(
            video(1, "片", pic = ""),
            video(2, "片", pic = "https://example.com/p.jpg"),
            video(3, "片", pic = "https://example.com/q.jpg"),
        )
        val agg = aggregateByName(list)
        assertEquals("https://example.com/p.jpg", agg[0].pic)
    }

    @Test
    fun `aggregateByName 全空 pic 留空字串不爆`() {
        val list = listOf(video(1, "片", pic = ""))
        val agg = aggregateByName(list)
        assertEquals("", agg[0].pic)
    }

    // --- applyExcludes ----------------------------------------------------

    private fun result(videos: List<Video>, perSite: List<SiteSearchResult> = emptyList()) =
        MultiSearchResult(
            videos = videos,
            page = 1,
            pageCount = 1,
            total = videos.size,
            perSite = perSite,
        )

    @Test
    fun `applyExcludes 空 excludes 直接回原物件`() {
        val r = result(listOf(video(1, "片")))
        val out = applyExcludes(r, emptyList())
        // 同 reference 表示完全沒做事
        assertTrue(out === r)
    }

    @Test
    fun `applyExcludes 過濾 vodName 含排除詞的影片`() {
        val r = result(listOf(
            video(1, "測試片第一季"),
            video(2, "測試片第二季預告"),
            video(3, "測試片第三季"),
        ))
        val out = applyExcludes(r, listOf("預告"))
        assertEquals(2, out.videos.size)
        assertEquals(2, out.total)
    }

    @Test
    fun `applyExcludes 大小寫不敏感`() {
        val r = result(listOf(video(1, "Trailer Movie")))
        val out = applyExcludes(r, listOf("trailer"))
        assertTrue(out.videos.isEmpty())
    }

    @Test
    fun `applyExcludes 重新計算 perSite count，全部被過濾的 site 標 Empty`() {
        val s1video = video(1, "片A").copy(fromSiteId = 1L)
        val s2video = video(2, "片B 預告").copy(fromSiteId = 2L)
        val r = result(
            videos = listOf(s1video, s2video),
            perSite = listOf(
                SiteSearchResult(siteId = 1L, siteName = "S1", count = 1, status = SiteSearchStatus.Success),
                SiteSearchResult(siteId = 2L, siteName = "S2", count = 1, status = SiteSearchStatus.Success),
            ),
        )
        val out = applyExcludes(r, listOf("預告"))
        assertEquals(1, out.videos.size)
        val s1Row = out.perSite.first { it.siteId == 1L }
        val s2Row = out.perSite.first { it.siteId == 2L }
        assertEquals(1, s1Row.count)
        assertEquals(SiteSearchStatus.Success, s1Row.status)
        assertEquals(0, s2Row.count)
        assertEquals(SiteSearchStatus.Empty, s2Row.status)
    }

    @Test
    fun `applyExcludes Failed 狀態的 site 不被改成 Empty`() {
        val r = result(
            videos = emptyList(),
            perSite = listOf(
                SiteSearchResult(siteId = 1L, siteName = "S1", count = 0, status = SiteSearchStatus.Failed),
            ),
        )
        val out = applyExcludes(r, listOf("xxx"))
        assertEquals(SiteSearchStatus.Failed, out.perSite[0].status)
    }
}
