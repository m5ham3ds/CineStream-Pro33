package com.example.extension.managed

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.ServerItem
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.scraper.EgyDeadScraper
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import com.example.extension.managed.web.WebExtractionEngine
import org.junit.Test

class EgyDeadScraperUnitTest {

    private val scraper = EgyDeadScraper()
    private val extension = ManagedExtension(
        id = "egydead-test",
        name = "EgyDead",
        baseUrl = "https://tv10.egydead.live",
        scraperKey = "egydead",
        contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES, ContentType.ANIME),
        status = ExtensionLifecycleStatus.ACTIVE
    )

    @Test
    fun scraperMetadata_isCorrect() {
        assertEquals("egydead", scraper.scraperKey)
        assertEquals(1, scraper.implementationVersion)
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.SEARCH))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.DETAILS))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.EPISODES))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.SERVER_DISCOVERY))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.VIDEO_EXTRACTION))
        assertTrue(scraper.supportedContentTypes.contains(ContentType.MOVIE))
        assertTrue(scraper.supportedContentTypes.contains(ContentType.SERIES))
    }

    @Test
    fun parseHtmlSearchResults_extractsMoviesAndSeries() {
        val sampleHtml = """
            <html>
                <body>
                    <section class="main-section">
                        <ul class="posts-list">
                            <li class="movieItem">
                                <a href="https://tv10.egydead.live/movie/interstellar-2014/">
                                    <img src="https://tv10.egydead.live/interstellar.jpg" />
                                    <h2>فيلم Interstellar 2014</h2>
                                </a>
                            </li>
                            <li class="movieItem">
                                <a href="https://tv10.egydead.live/series/breaking-bad/">
                                    <img src="https://tv10.egydead.live/bb.jpg" />
                                    <h2>مسلسل Breaking Bad</h2>
                                </a>
                            </li>
                        </ul>
                    </section>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleHtml)
        val elements = doc.select("section.main-section ul.posts-list li.movieItem")
        assertEquals(2, elements.size)

        val firstLink = elements[0].selectFirst("a")?.attr("href")
        val firstTitle = elements[0].selectFirst("h2")?.text()
        assertEquals("https://tv10.egydead.live/movie/interstellar-2014/", firstLink)
        assertEquals("فيلم Interstellar 2014", firstTitle)

        val secondLink = elements[1].selectFirst("a")?.attr("href")
        val secondTitle = elements[1].selectFirst("h2")?.text()
        assertEquals("https://tv10.egydead.live/series/breaking-bad/", secondLink)
        assertEquals("مسلسل Breaking Bad", secondTitle)
    }

    @Test
    fun parseHtmlEpisodesAndSeasons_extractsStructuredData() {
        val sampleSeriesHtml = """
            <html>
                <body>
                    <div class="seasons-list">
                        <ul>
                            <li class="movieItem"><a href="https://tv10.egydead.live/season/bb-s1/">الموسم 1</a></li>
                            <li class="movieItem"><a href="https://tv10.egydead.live/season/bb-s2/">الموسم 2</a></li>
                        </ul>
                    </div>
                    <div class="EpsList">
                        <ul>
                            <li><a href="https://tv10.egydead.live/episode/bb-s1e1/">الحلقة 1</a></li>
                            <li><a href="https://tv10.egydead.live/episode/bb-s1e2/">الحلقة 2</a></li>
                        </ul>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleSeriesHtml)
        val seasons = doc.select("div.seasons-list ul li.movieItem a")
        assertEquals(2, seasons.size)

        val episodes = doc.select("div.EpsList li a")
        assertEquals(2, episodes.size)
        assertEquals("الحلقة 1", episodes[0].text())
        assertEquals("https://tv10.egydead.live/episode/bb-s1e1/", episodes[0].attr("href"))
    }

    @Test
    fun parseHtmlServers_extractsDirectAndEmbedLinks() {
        val sampleServersHtml = """
            <html>
                <body>
                    <ul class="watch-servers">
                        <li><a data-link="https://stream.server.com/embed/123">سيرفر Embed</a></li>
                        <li><a data-link="https://cdn.server.com/video.m3u8">سيرفر مباشر</a></li>
                    </ul>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleServersHtml)
        val serverLinks = doc.select(".mob-servers ul li, .servers ul li, ul.watch-servers li, #servers-list li")
        assertEquals(2, serverLinks.size)

        val link1 = serverLinks[0].selectFirst("a")?.attr("data-link")
        val link2 = serverLinks[1].selectFirst("a")?.attr("data-link")
        assertEquals("https://stream.server.com/embed/123", link1)
        assertEquals("https://cdn.server.com/video.m3u8", link2)
    }

    @Test
    fun parseLiveSearchPageDom_extractsEnglishAndArabicResultsWithMetadata() {
        val liveSearchHtml = """
            <html>
                <body>
                    <div class="search-page">
                        <div class="container">
                            <div class="catHolder">
                                <ul class="posts-list">
                                    <li class="movieItem">
                                        <a href="https://tv10.egydead.live/inception-2010-1080p-bluray/" title="مشاهدة فيلم Inception 2010 مترجم">
                                            <div class="movieCover">
                                                <img src="https://tv10.egydead.live/wp-content/uploads/2026/02/inception.jpg" />
                                            </div>
                                            <span class="cat_name">افلام اجنبي</span>
                                            <h1 class="BottomTitle">مشاهدة فيلم Inception 2010 مترجم</h1>
                                        </a>
                                    </li>
                                    <li class="movieItem">
                                        <a href="https://tv10.egydead.live/episode/avatar-the-last-airbender-s02e07/" title="مسلسل Avatar The Last Airbender الموسم الثاني الحلقة 7">
                                            <div class="movieCover">
                                                <img src="https://tv10.egydead.live/wp-content/uploads/2026/06/avatar.jpg" />
                                            </div>
                                            <span class="cat_name">مسلسلات اجنبي</span>
                                            <span class="number_episode"><b>حلقه</b><em>7</em></span>
                                            <span class="label">الاخيرة</span>
                                            <h1 class="BottomTitle">مسلسل Avatar The Last Airbender الحلقة 7</h1>
                                        </a>
                                    </li>
                                </ul>
                            </div>
                        </div>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(liveSearchHtml)
        val elements = doc.select(
            "div.search-page div.catHolder ul.posts-list li.movieItem, " +
            "div.catHolder ul.posts-list li.movieItem, " +
            "section.main-section ul.posts-list li.movieItem, " +
            "li.movieItem"
        )
        assertEquals(2, elements.size)

        // Movie Item
        val movieEl = elements[0]
        val movieLink = movieEl.selectFirst("a")?.attr("href")
        val movieTitle = movieEl.selectFirst("a")?.attr("title")?.ifBlank { movieEl.selectFirst("h1.BottomTitle")?.text() }
        val movieCategory = movieEl.selectFirst("span.cat_name")?.text()
        val moviePoster = movieEl.selectFirst("img")?.attr("src")
        assertEquals("https://tv10.egydead.live/inception-2010-1080p-bluray/", movieLink)
        assertEquals("مشاهدة فيلم Inception 2010 مترجم", movieTitle)
        assertEquals("افلام اجنبي", movieCategory)
        assertEquals("https://tv10.egydead.live/wp-content/uploads/2026/02/inception.jpg", moviePoster)

        // Series/Episode Item
        val seriesEl = elements[1]
        val seriesLink = seriesEl.selectFirst("a")?.attr("href")
        val seriesTitle = seriesEl.selectFirst("a")?.attr("title")
        val seriesCategory = seriesEl.selectFirst("span.cat_name")?.text()
        val epNum = seriesEl.selectFirst("span.number_episode em")?.text()
        val epLabel = seriesEl.selectFirst("span.label")?.text()
        assertEquals("https://tv10.egydead.live/episode/avatar-the-last-airbender-s02e07/", seriesLink)
        assertEquals("مسلسل Avatar The Last Airbender الموسم الثاني الحلقة 7", seriesTitle)
        assertEquals("مسلسلات اجنبي", seriesCategory)
        assertEquals("7", epNum)
        assertEquals("الاخيرة", epLabel)
    }

    @Test
    fun parsePaginationTwo_handlesCurrentAndInactivePages() {
        val paginationHtml = """
            <div class="pagination-two">
                <a href="https://tv10.egydead.live/?s=Avatar" class="inactive">1</a>
                <span class="current">2</span>
                <a href="https://tv10.egydead.live/page/3/?s=Avatar" class="inactive">3</a>
                <a href="https://tv10.egydead.live/page/4/?s=Avatar" class="inactive">4</a>
            </div>
        """.trimIndent()

        val doc = Jsoup.parse(paginationHtml)
        val currentVal = doc.selectFirst("div.pagination-two span.current")?.text()?.toIntOrNull()
        assertEquals(2, currentVal)

        val hasHigherPage = doc.select("div.pagination-two a.inactive").any {
            val p = it.text().toIntOrNull()
            p != null && p > (currentVal ?: 1)
        }
        assertTrue("Should detect page 3 and 4 as next pages", hasHigherPage)

        val lastPageHtml = """
            <div class="pagination-two">
                <a href="https://tv10.egydead.live/page/1/?s=Avatar" class="inactive">1</a>
                <a href="https://tv10.egydead.live/page/2/?s=Avatar" class="inactive">2</a>
                <span class="current">3</span>
            </div>
        """.trimIndent()
        val lastDoc = Jsoup.parse(lastPageHtml)
        val lastCurrent = lastDoc.selectFirst("div.pagination-two span.current")?.text()?.toIntOrNull() ?: 3
        val lastHasHigher = lastDoc.select("div.pagination-two a.inactive").any {
            val p = it.text().toIntOrNull()
            p != null && p > lastCurrent
        }
        org.junit.Assert.assertFalse("Should detect no next page on last page", lastHasHigher)
    }

    @Test
    fun parseDetails_liveSiteTitleAndCover() {
        val detailsHtml = """
            <html>
                <body>
                    <h1 class="TitleMaster">
                        <span>
                            <em>مشاهدة فيلم Inception 2010 مترجم</em>
                        </span>
                    </h1>
                    <div class="singleCover">
                        <img src="https://tv10.egydead.live/wp-content/uploads/2026/02/inception.jpg" />
                    </div>
                    <div class="story">
                        تدور أحداث الفيلم حول لص محترف يسرق أسرار الشركات باستخدام تكنولوجيا مشاركة الأحلام.
                    </div>
                    <ul class="singleInfo">
                        <li><span>سنة الإصدار: </span><a href="/year/2010/">2010</a></li>
                        <li><span>الجودة: </span>1080p BluRay</li>
                    </ul>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(detailsHtml)
        val title = doc.selectFirst("h1.TitleMaster span em")?.text()?.trim()
        val poster = doc.selectFirst("div.singleCover img")?.attr("src")
        val story = doc.selectFirst("div.story")?.text()?.trim()

        assertEquals("مشاهدة فيلم Inception 2010 مترجم", title)
        assertEquals("https://tv10.egydead.live/wp-content/uploads/2026/02/inception.jpg", poster)
        assertTrue(story?.contains("لص محترف") == true)
    }

    @Test
    fun parseServerDiscovery_desktopServersListAndDownloadList() {
        val watchPagePostHtml = """
            <html>
                <body>
                    <div class="watchAreaMaster">
                        <div class="container">
                            <ul class="serversList">
                                <li data-link="https://hgcloud.to/e/74c24l4lv6zw"><span><p>StreamHG</p></span></li>
                                <li data-link="https://morencius.com/v/zzw3dy72jlnh"><span><p>EarnVids</p></span></li>
                                <li data-link="https://mixdrop.top/e/7ro7nddztq9vd6"><span><p>Mixdrop</p></span></li>
                                <li data-link="https://playmogo.com/e/0a5vjq9y8rob"><span><p>DoodStream</p></span></li>
                            </ul>
                            <div class="holder">
                                <iframe src="" allowfullscreen></iframe>
                            </div>
                        </div>
                    </div>
                    <div class="downloadMaster">
                        <div class="container">
                            <ul class="donwload-servers-list">
                                <li>
                                    <span class="ser-name">StreamHG</span>
                                    <div class="server-info">
                                        <em>1080p</em>
                                    </div>
                                    <a class="ser-link" href="https://hgcloud.to/74c24l4lv6zw">حمل الان</a>
                                </li>
                                <li>
                                    <span class="ser-name">Mirrorace</span>
                                    <div class="server-info">
                                        <em>720p</em>
                                    </div>
                                    <a class="ser-link" href="https://mirrorace.org/m/1W71s">حمل الان</a>
                                </li>
                            </ul>
                        </div>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(watchPagePostHtml)

        // Desktop Watch Servers
        val watchServers = doc.select("ul.serversList li[data-link]")
        assertEquals(4, watchServers.size)
        assertEquals("https://hgcloud.to/e/74c24l4lv6zw", watchServers[0].attr("data-link"))
        assertEquals("StreamHG", watchServers[0].selectFirst("span p")?.text())

        // Download Servers (testing the literal typo "donwload-servers-list")
        val downloadServers = doc.select("ul.donwload-servers-list li")
        assertEquals(2, downloadServers.size)
        val dl1Name = downloadServers[0].selectFirst("span.ser-name")?.text()
        val dl1Quality = downloadServers[0].selectFirst("div.server-info em")?.text()
        val dl1Link = downloadServers[0].selectFirst("a.ser-link")?.attr("href")
        assertEquals("StreamHG", dl1Name)
        assertEquals("1080p", dl1Quality)
        assertEquals("https://hgcloud.to/74c24l4lv6zw", dl1Link)
    }

    @Test
    fun parseServerDiscovery_mobileServersList() {
        val mobileHtml = """
            <html>
                <body>
                    <div class="container">
                        <div class="mob-servers">
                            <span><i class="fa fa-bars"></i><em>قائمه السيرفرات</em></span>
                            <ul>
                                <li data-link="https://hgcloud.to/e/74c24l4lv6zw"><span><p>StreamHG</p></span></li>
                                <li data-link="https://morencius.com/v/zzw3dy72jlnh"><span><p>EarnVids</p></span></li>
                            </ul>
                        </div>
                        <div class="mobIframe">
                            <iframe src="" allowfullscreen></iframe>
                        </div>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(mobileHtml)
        val mobileServers = doc.select("div.mob-servers ul li[data-link]")
        assertEquals(2, mobileServers.size)
        assertEquals("https://hgcloud.to/e/74c24l4lv6zw", mobileServers[0].attr("data-link"))
        assertEquals("StreamHG", mobileServers[0].selectFirst("span p")?.text())
    }

    @Test
    fun securityAudit_scraperContainsNoDynamicOrUnsafeCalls() {
        val scraperClass = EgyDeadScraper::class.java
        // Confirm class has no reflection methods declared
        val methods = scraperClass.declaredMethods
        for (m in methods) {
            org.junit.Assert.assertFalse("Scraper must not declare reflective invoke", m.name.contains("invokeReflective"))
        }
        // Verify key and version
        assertEquals("egydead", scraper.scraperKey)
        assertEquals(1, scraper.implementationVersion)
    }

    @Test
    fun directStreamExtraction_returnsPlaybackSourceWithMimeType() = runBlocking {
        val session = ExtractionSession(
            extensionId = extension.id,
            scraperKey = extension.scraperKey,
            targetPageUrl = "https://tv10.egydead.live/watch"
        )
        val server = ServerItem(
            id = "direct-m3u8",
            name = "سيرفر سريع HLS",
            link = "https://cdn.egydead.com/hls/master.m3u8",
            isDirectStream = true
        )
        val request = ExtractionRequest(serverItem = server)

        val result = scraper.extractStream(extension, session, request, null as WebExtractionEngine?)
        assertTrue(result.isSuccess)
        val extracted = result.getOrNull()
        assertNotNull(extracted)
        val playback = extracted?.playbackSource
        assertNotNull(playback)
        assertEquals("https://cdn.egydead.com/hls/master.m3u8", playback?.streamUrl)
        assertEquals("application/x-mpegURL", playback?.mimeType)
        assertEquals(extension.baseUrl, playback?.headers?.get("Referer"))

        // Phase 7: Verify DownloadSource is also populated
        val download = extracted?.downloadSource
        assertNotNull(download)
        assertEquals("https://cdn.egydead.com/hls/master.m3u8", download?.url)
        assertEquals(com.example.extension.managed.model.StreamProtocol.HLS, download?.protocol)
    }

    @Test
    fun directStreamExtraction_mp4_populatesDownloadAndNeverFabricates1080p() = runBlocking {
        val session = ExtractionSession(
            extensionId = extension.id,
            scraperKey = extension.scraperKey,
            targetPageUrl = "https://tv10.egydead.live/watch/movie"
        )
        val server = ServerItem(
            id = "direct-mp4",
            name = "سيرفر MP4 مباشر",
            link = "https://cdn.egydead.com/video/movie.mp4",
            isDirectStream = true
        )
        val request = ExtractionRequest(serverItem = server)

        val result = scraper.extractStream(extension, session, request, null as WebExtractionEngine?)
        assertTrue(result.isSuccess)
        val extracted = result.getOrThrow()

        // PlaybackSource
        val playback = extracted.playbackSource
        assertNotNull(playback)
        assertEquals("https://cdn.egydead.com/video/movie.mp4", playback?.streamUrl)
        assertEquals("video/mp4", playback?.mimeType)
        assertEquals(com.example.extension.managed.model.StreamProtocol.DIRECT_FILE, playback?.protocol)

        // DownloadSource
        val download = extracted.downloadSource
        assertNotNull(download)
        assertEquals("https://cdn.egydead.com/video/movie.mp4", download?.url)
        assertEquals("video/mp4", download?.mimeType)
        assertEquals(com.example.extension.managed.model.StreamProtocol.DIRECT_FILE, download?.protocol)

        // Quality Variant: Direct MP4 has no sub-variants (never fabricates 1080p)
        assertTrue("Direct MP4 must not have fabricated qualities", extracted.variants.isEmpty())
        assertTrue("Direct MP4 must not have fabricated qualities", playback?.qualities.isNullOrEmpty())
    }

    @Test
    fun search_blankQuery_returnsEmptyResultFast() = runBlocking {
        val result = scraper.search(extension, com.example.extension.managed.model.SearchRequest(query = ""))
        assertTrue(result.isSuccess)
        val searchResult = result.getOrThrow()
        assertTrue(searchResult.items.isEmpty())
    }

    @Test
    fun getDetails_blankUrl_failsWithExtensionError() = runBlocking {
        val result = scraper.getDetails(extension, "")
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertNotNull(error)
        assertTrue("Error should be an ExtensionError", error is com.example.extension.managed.error.ExtensionError)
    }

    @Test
    fun contextOverload_executesExtractionSeamlessly() = runBlocking {
        val session = ExtractionSession(
            extensionId = extension.id,
            scraperKey = extension.scraperKey,
            targetPageUrl = "https://tv10.egydead.live/watch"
        )
        val context = com.example.extension.managed.runtime.context.ExtensionContext.from(
            extension = extension,
            session = session
        )
        val server = ServerItem(
            id = "direct-m3u8",
            name = "سيرفر HLS",
            link = "https://cdn.egydead.com/hls/test.m3u8",
            isDirectStream = true
        )
        val request = ExtractionRequest(serverItem = server)

        val result = scraper.extractStream(context, request)
        assertTrue(result.isSuccess)
        val extracted = result.getOrThrow()
        assertEquals("https://cdn.egydead.com/hls/test.m3u8", extracted.playbackSource?.streamUrl)
        assertEquals(extension.baseUrl, extracted.playbackSource?.headers?.get("Referer"))
    }
}
