package com.example.extension.managed

import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.scraper.AnimeBlkomScraper
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Forensic unit test suite for AnimeBlkomScraper (PHASE NEW-BLK-01).
 * Validates all 14 mandatory contract vectors:
 * 1. Search
 * 2. Empty Search
 * 3. Search Result Parsing
 * 4. Details
 * 5. Episode List
 * 6. Episode Number
 * 7. Server Discovery
 * 8. data-src extraction
 * 9. iframe extraction
 * 10. Relative URL resolution
 * 11. Malformed URL
 * 12. Duplicate server
 * 13. Missing server
 * 14. Playback handoff
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnimeBlkomScraperUnitTest {

    private val scraper = AnimeBlkomScraper()
    private val testBaseUrl = "https://animeblkom.net"
    private val dummyExtension = ManagedExtension(
        id = "animeblkom",
        name = "AnimeBlkom",
        baseUrl = testBaseUrl,
        scraperKey = "animeblkom",
        contentTypes = setOf(ContentType.ANIME, ContentType.MOVIE, ContentType.SERIES),
        status = ExtensionLifecycleStatus.ACTIVE
    )

    // --- 1. Search ---
    @Test
    fun test01_search() {
        val html = """
            <html><body>
                <div class="search-results">
                    <div class="item">
                        <a href="https://animeblkom.net/anime/bleach" title="بليتش">
                            <div class="poster"><img src="/uploads/bleach.jpg" /></div>
                            <span class="anime-title">بليتش</span>
                        </a>
                    </div>
                </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val items = scraper.parseSearchDocument(doc, testBaseUrl)
        assertFalse(items.isEmpty())
        assertEquals("بليتش", items[0].title)
        assertEquals("https://animeblkom.net/anime/bleach", items[0].url)
    }

    // --- 2. Empty Search ---
    @Test
    fun test02_emptySearch() = runBlocking {
        val blankResult = scraper.search(dummyExtension, SearchRequest(query = ""))
        assertTrue(blankResult.isSuccess)
        val searchResult = blankResult.getOrNull()
        assertNotNull(searchResult)
        assertTrue(searchResult!!.items.isEmpty())

        val whitespaceResult = scraper.search(dummyExtension, SearchRequest(query = "     "))
        assertTrue(whitespaceResult.isSuccess)
        assertTrue(whitespaceResult.getOrNull()!!.items.isEmpty())
    }

    // --- 3. Search Result Parsing ---
    @Test
    fun test03_searchResultParsing() {
        val html = """
            <html><body>
                <div class="anime-card">
                    <div class="poster"><img src="https://animeblkom.net/images/naruto.jpg" /></div>
                    <h3 class="anime-title"><a href="/anime/naruto-shippuden">ناروتو شيبودن</a></h3>
                    <span class="year">2007</span>
                    <span class="rating">8.2</span>
                </div>
                <div class="anime-card">
                    <div class="poster"><img data-src="/images/conan.jpg" /></div>
                    <h3 class="anime-title"><a href="/anime/conan-movie-26">فيلم المحقق كونان الغواصة الحديدية</a></h3>
                    <span class="year">2023</span>
                    <span class="rating">8.5</span>
                </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val items = scraper.parseSearchDocument(doc, testBaseUrl)

        assertEquals(2, items.size)

        // Item 1: Series
        val item1 = items[0]
        assertEquals("ناروتو شيبودن", item1.title)
        assertEquals("https://animeblkom.net/anime/naruto-shippuden", item1.url)
        assertEquals("https://animeblkom.net/images/naruto.jpg", item1.posterUrl)
        assertEquals("2007", item1.year)
        assertEquals(ContentType.ANIME, item1.contentType)
        assertEquals("8.2", item1.extraMetadata["rating"])

        // Item 2: Movie
        val item2 = items[1]
        assertEquals("فيلم المحقق كونان الغواصة الحديدية", item2.title)
        assertEquals("https://animeblkom.net/anime/conan-movie-26", item2.url)
        assertEquals("https://animeblkom.net/images/conan.jpg", item2.posterUrl)
        assertEquals("2023", item2.year)
        assertEquals(ContentType.MOVIE, item2.contentType)
    }

    // --- 4. Details ---
    @Test
    fun test04_details() {
        val html = """
            <html><body>
                <div class="anime-details">
                    <h1 class="anime-title">ون بيس - One Piece</h1>
                    <div class="poster"><img src="/images/onepiece.jpg" /></div>
                    <div class="story">تبدأ مغامرة لوفي للبحث عن كنز ون بيس...</div>
                    <div class="rating">8.9</div>
                    <div class="year">1999</div>
                    <div class="status">مستمر</div>
                    <div class="studio">توي أنميشن</div>
                    <div class="country">اليابان</div>
                    <div class="episodes-count">1100+</div>
                    <div class="genres">
                        <a href="/genre/action">أكشن</a>
                        <a href="/genre/adventure">مغامرات</a>
                    </div>
                    <ul class="episodes-links">
                        <li class="episode-link"><a href="/watch/one-piece/1">الحلقة 1</a></li>
                        <li class="episode-link"><a href="/watch/one-piece/2">الحلقة 2</a></li>
                    </ul>
                </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://animeblkom.net/anime/one-piece")
        val details = scraper.parseDetailsDocument(doc, "https://animeblkom.net/anime/one-piece", testBaseUrl)

        assertEquals("ون بيس - One Piece", details.title)
        assertEquals("https://animeblkom.net/images/onepiece.jpg", details.posterUrl)
        assertTrue(details.description!!.contains("كنز ون بيس"))
        assertEquals("1999", details.year)
        assertEquals(8.9, details.rating ?: 0.0, 0.01)
        assertEquals("مستمر", details.metadata["status"])
        assertEquals("توي أنميشن", details.metadata["studio"])
        assertEquals("اليابان", details.metadata["country"])
        assertEquals("1100+", details.metadata["episodeCount"])
        assertEquals(listOf("أكشن", "مغامرات"), details.genres)
        assertEquals(2, details.episodes.size)
    }

    // --- 5. Episode List ---
    @Test
    fun test05_episodeList() {
        val html = """
            <html><body>
                <ul class="episodes-links">
                    <li class="episode-link"><a href="/watch/one-piece/0">الحلقة 0</a></li>
                    <li class="episode-link"><a href="/watch/one-piece/1">الحلقة 1</a></li>
                    <li class="episode-link"><a href="/watch/one-piece/2">الحلقة 2</a></li>
                </ul>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val episodes = scraper.parseEpisodesDocument(doc, testBaseUrl, seasonNumber = 1)

        assertEquals(3, episodes.size)
        assertEquals(0, episodes[0].episodeNumber)
        assertEquals("https://animeblkom.net/watch/one-piece/0", episodes[0].url)
        assertEquals(1, episodes[1].episodeNumber)
        assertEquals("https://animeblkom.net/watch/one-piece/1", episodes[1].url)
        assertEquals(2, episodes[2].episodeNumber)
        assertEquals("https://animeblkom.net/watch/one-piece/2", episodes[2].url)
    }

    // --- 6. Episode Number ---
    @Test
    fun test06_episodeNumber() {
        // 6A: Arabic keyword in DOM text
        val numFromText = scraper.extractEpisodeNumber("الحلقة 42 مترجمة", "https://animeblkom.net/watch/naruto/42", 1)
        assertEquals(42, numFromText)

        // 6B: Pure number string (common in AnimeBlkom prologue / episode 0)
        val numZero = scraper.extractEpisodeNumber("0", "https://animeblkom.net/watch/one-piece/0", 1)
        assertEquals(0, numZero)

        // 6C: URL slug matching (/watch/{slug}/{episode}) when text has no number
        val numFromUrl = scraper.extractEpisodeNumber("مشاهدة الآن", "https://animeblkom.net/watch/bleach/366", 1)
        assertEquals(366, numFromUrl)

        // 6D: URL trailing dash matching
        val numFromDashUrl = scraper.extractEpisodeNumber("الحلقة الخاصة", "https://animeblkom.net/watch/conan-ova-3", 1)
        assertEquals(3, numFromDashUrl)

        // 6E: Fallback position when text and URL contain no digits
        val numFallback = scraper.extractEpisodeNumber("حلقة خاصة", "https://animeblkom.net/watch/special/ova", 7)
        assertEquals(7, numFallback)
    }

    // --- 7. Server Discovery ---
    @Test
    fun test07_serverDiscovery() {
        val html = """
            <html><body>
                <ul class="servers-list">
                    <li data-src="https://embed.server1.com/v/abc" data-quality="1080p">
                        <span class="server-name">السيرفر الفائق</span>
                    </li>
                    <li data-src="https://stream.server2.com/play/xyz" data-quality="720p">
                        <span class="server-name">سيرفر بديل</span>
                    </li>
                </ul>
                <div class="embed-responsive">
                    <iframe src="https://player.blkom.com/embed/main"></iframe>
                </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val servers = scraper.parseServersDocument(doc, "https://animeblkom.net/watch/op/1", testBaseUrl)

        assertEquals(3, servers.size)

        // Server 1
        assertEquals("السيرفر الفائق (1080p)", servers[0].name)
        assertEquals("https://embed.server1.com/v/abc", servers[0].link)
        assertEquals("1080p", servers[0].metadata["quality"])
        assertEquals(ServerType.EMBED, servers[0].serverType)
        assertTrue(servers[0].requiresWebView)

        // Server 2
        assertEquals("سيرفر بديل (720p)", servers[1].name)
        assertEquals("https://stream.server2.com/play/xyz", servers[1].link)

        // Server 3: iframe
        assertEquals("https://player.blkom.com/embed/main", servers[2].link)
    }

    // --- 8. data-src extraction ---
    @Test
    fun test08_dataSrcExtraction() {
        val html = """
            <html><body>
                <div class="servers">
                    <button data-src="https://cdn.video.com/embed/99" data-quality="FHD" data-server="Google Drive">
                        Google Drive
                    </button>
                </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val servers = scraper.parseServersDocument(doc, "https://animeblkom.net/watch/1", testBaseUrl)

        assertEquals(1, servers.size)
        assertEquals("https://cdn.video.com/embed/99", servers[0].link)
        assertEquals("FHD", servers[0].metadata["quality"])
        assertTrue(servers[0].name.contains("Google Drive"))
    }

    // --- 9. iframe extraction ---
    @Test
    fun test09_iframeExtraction() {
        val html = """
            <html><body>
                <div class="video-container">
                    <iframe src="https://stream.provider.net/embed/abc123"></iframe>
                </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val servers = scraper.parseServersDocument(doc, "https://animeblkom.net/watch/1", testBaseUrl)

        assertEquals(1, servers.size)
        assertEquals("https://stream.provider.net/embed/abc123", servers[0].link)
        assertEquals(ServerType.EMBED, servers[0].serverType)
    }

    // --- 10. Relative URL resolution ---
    @Test
    fun test10_relativeUrlResolution() {
        val resolvedRoot = scraper.resolveUrl(testBaseUrl, "/watch/one-piece/5")
        assertEquals("https://animeblkom.net/watch/one-piece/5", resolvedRoot)

        val resolvedProtocol = scraper.resolveUrl(testBaseUrl, "//cdn.animeblkom.net/media/1")
        assertEquals("https://cdn.animeblkom.net/media/1", resolvedProtocol)

        val resolvedWatch = scraper.resolveWatchUrl("/embed/play?id=123", testBaseUrl)
        assertEquals("https://animeblkom.net/embed/play?id=123", resolvedWatch)
    }

    // --- 11. Malformed URL ---
    @Test
    fun test11_malformedUrl() {
        assertNull(scraper.resolveWatchUrl("javascript:evil()", testBaseUrl))
        assertNull(scraper.resolveWatchUrl("file:///data/local/tmp", testBaseUrl))
        assertNull(scraper.resolveWatchUrl("data:text/html,<script>", testBaseUrl))
        assertNull(scraper.resolveWatchUrl("", testBaseUrl))
        assertNull(scraper.resolveWatchUrl("    ", testBaseUrl))
    }

    // --- 12. Duplicate server ---
    @Test
    fun test12_duplicateServer() {
        val html = """
            <html><body>
                <ul class="servers-list">
                    <li data-src="https://embed.server.com/play/1"><span class="server-name">سيرفر 1</span></li>
                    <li data-src="https://embed.server.com/play/1"><span class="server-name">نسخة مكررة</span></li>
                    <li data-src="https://embed.server.com/play/2"><span class="server-name">سيرفر 2</span></li>
                </ul>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val servers = scraper.parseServersDocument(doc, "https://animeblkom.net/watch/1", testBaseUrl)

        assertEquals(2, servers.size)
        assertEquals("https://embed.server.com/play/1", servers[0].link)
        assertEquals("https://embed.server.com/play/2", servers[1].link)
    }

    // --- 13. Missing server ---
    @Test
    fun test13_missingServer() {
        val html = "<html><body><div class='no-servers'>لا توجد مصادر</div></body></html>"
        val doc = Jsoup.parse(html, testBaseUrl)
        val servers = scraper.parseServersDocument(doc, "https://animeblkom.net/watch/1", testBaseUrl)
        assertTrue(servers.isEmpty())
    }

    // --- 14. Playback handoff ---
    @Test
    fun test14_playbackHandoff() = runBlocking {
        val session = ExtractionSession(
            extensionId = "animeblkom",
            scraperKey = "animeblkom",
            targetPageUrl = "https://animeblkom.net/watch/one-piece/1",
            timeoutMs = 5000L
        )

        // 14A. Direct MP4
        val mp4Server = ServerItem(
            id = "s1",
            name = "Direct MP4",
            link = "https://cdn.animeblkom.net/video/ep1.mp4",
            isDirectStream = true
        )
        val mp4Result = scraper.extractStream(dummyExtension, session, ExtractionRequest(mp4Server))
        assertTrue(mp4Result.isSuccess)
        val mp4Source = mp4Result.getOrThrow().playbackSource
        assertNotNull(mp4Source)
        assertEquals("https://cdn.animeblkom.net/video/ep1.mp4", mp4Source!!.streamUrl)
        assertEquals(StreamProtocol.DIRECT_FILE, mp4Source.protocol)
        assertEquals("video/mp4", mp4Source.mimeType)

        // 14B. Direct HLS
        val hlsServer = ServerItem(
            id = "s2",
            name = "Direct HLS",
            link = "https://cdn.animeblkom.net/hls/ep1.m3u8",
            isDirectStream = true
        )
        val hlsResult = scraper.extractStream(dummyExtension, session, ExtractionRequest(hlsServer))
        assertTrue(hlsResult.isSuccess)
        val hlsSource = hlsResult.getOrThrow().playbackSource
        assertNotNull(hlsSource)
        assertEquals("https://cdn.animeblkom.net/hls/ep1.m3u8", hlsSource!!.streamUrl)
        assertEquals(StreamProtocol.HLS, hlsSource.protocol)
        assertEquals("application/x-mpegURL", hlsSource.mimeType)

        // 14C. Embed Handoff without webEngine
        val embedServer = ServerItem(
            id = "s3",
            name = "Embed Server",
            link = "https://embed.player.net/v/abc123",
            isDirectStream = false
        )
        val embedResult = scraper.extractStream(dummyExtension, session, ExtractionRequest(embedServer))
        assertTrue(embedResult.isSuccess)
        val embedSource = embedResult.getOrThrow().playbackSource
        assertNotNull(embedSource)
        assertEquals("https://embed.player.net/v/abc123", embedSource!!.streamUrl)
        assertEquals(StreamProtocol.EMBED, embedSource.protocol)
    }
}
