package com.example.extension.managed

import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.scraper.Anime4UpScraper
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Forensic unit test suite for Anime4UpScraper (PHASE NEW-A4U-01).
 * Validates all 14 mandatory contract vectors:
 * 1. Search parsing
 * 2. Empty search
 * 3. Details parsing
 * 4. Episode parsing
 * 5. Episode number extraction
 * 6. Server discovery
 * 7. Missing data-watch
 * 8. Duplicate servers
 * 9. Relative URLs
 * 10. Invalid URLs
 * 11. Unknown server names
 * 12. Unknown qualities
 * 13. Malformed HTML
 * 14. Playback handoff
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Anime4UpScraperUnitTest {

    private val scraper = Anime4UpScraper()
    private val testBaseUrl = "https://w1.anime4up.rest"
    private val dummyExtension = ManagedExtension(
        id = "anime4up",
        name = "Anime4Up",
        baseUrl = testBaseUrl,
        scraperKey = "anime4up",
        contentTypes = setOf(ContentType.ANIME, ContentType.MOVIE, ContentType.SERIES),
        status = ExtensionLifecycleStatus.ACTIVE
    )

    // --- 1. Search parsing ---
    @Test
    fun test01_searchParsing() {
        val html = """
            <html><body>
                <div class="anime-card-container">
                    <div class="anime-card-poster">
                        <a href="https://w1.anime4up.rest/anime/attack-on-titan/" class="overlay" title="هجوم العمالقة">
                            <img src="https://w1.anime4up.rest/uploads/aot.jpg" />
                        </a>
                    </div>
                    <div class="anime-card-details">
                        <h3 class="anime-card-title">هجوم العمالقة</h3>
                        <span class="anime-card-year">2013</span>
                    </div>
                </div>
                <div class="anime-card-themex">
                    <a href="/anime/one-piece-red-movie/" class="overlay" title="فيلم ون بيس ريد">
                        <img data-src="/uploads/red.jpg" />
                    </a>
                    <h3>فيلم ون بيس ريد</h3>
                    <span class="year">2022</span>
                </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val items = scraper.parseSearchDocument(doc, testBaseUrl)

        assertEquals(2, items.size)

        // Item 1: Series Anime
        val item1 = items[0]
        assertEquals("هجوم العمالقة", item1.title)
        assertEquals("https://w1.anime4up.rest/anime/attack-on-titan/", item1.url)
        assertEquals("https://w1.anime4up.rest/uploads/aot.jpg", item1.posterUrl)
        assertEquals("2013", item1.year)
        assertEquals(ContentType.ANIME, item1.contentType)

        // Item 2: Movie Anime
        val item2 = items[1]
        assertEquals("فيلم ون بيس ريد", item2.title)
        assertEquals("https://w1.anime4up.rest/anime/one-piece-red-movie/", item2.url)
        assertEquals("https://w1.anime4up.rest/uploads/red.jpg", item2.posterUrl)
        assertEquals("2022", item2.year)
        assertEquals(ContentType.MOVIE, item2.contentType)
    }

    // --- 2. Empty search ---
    @Test
    fun test02_emptySearch() = runBlocking {
        // Blank search should return empty result immediately without network call
        val resultBlank = scraper.search(dummyExtension, SearchRequest(query = ""))
        assertTrue(resultBlank.isSuccess)
        val searchResult = resultBlank.getOrNull()
        assertNotNull(searchResult)
        assertTrue(searchResult!!.items.isEmpty())

        val resultWhitespace = scraper.search(dummyExtension, SearchRequest(query = "   "))
        assertTrue(resultWhitespace.isSuccess)
        assertTrue(resultWhitespace.getOrNull()!!.items.isEmpty())
    }

    // --- 3. Details parsing ---
    @Test
    fun test03_detailsParsing() {
        val html = """
            <html><body>
                <div class="anime-details">
                    <h1 class="anime-details-title">ون بيس - One Piece</h1>
                    <span class="anime-english-title">One Piece</span>
                    <div class="anime-poster">
                        <img class="thumbnail" src="https://w1.anime4up.rest/uploads/onepiece.jpg" />
                    </div>
                    <div class="anime-story">
                        تبدأ القصة بإعدام غول دي روجر ملك القراصنة...
                    </div>
                    <div class="anime-info">
                        <span class="anime-year">1999</span>
                        <span class="anime-rating">8.9</span>
                        <span class="anime-status">مستمر</span>
                        <span class="anime-studio">Toei Animation</span>
                        <span class="anime-country">اليابان</span>
                        <span class="anime-episodes-count">1100+</span>
                        <div class="anime-genres">
                            <a href="/genre/action/">أكشن</a>
                            <a href="/genre/adventure/">مغامرات</a>
                        </div>
                    </div>
                    <div id="ULEpisodesList">
                        <li><a href="https://w1.anime4up.rest/episode/one-piece-episode-1/">الحلقة 1</a></li>
                        <li><a href="https://w1.anime4up.rest/episode/one-piece-episode-2/">الحلقة 2</a></li>
                    </div>
                </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, "https://w1.anime4up.rest/anime/one-piece/")
        val details = scraper.parseDetailsDocument(doc, "https://w1.anime4up.rest/anime/one-piece/", testBaseUrl)

        assertEquals("ون بيس - One Piece", details.title)
        assertEquals("https://w1.anime4up.rest/uploads/onepiece.jpg", details.posterUrl)
        assertTrue(details.description!!.contains("غول دي روجر"))
        assertEquals("1999", details.year)
        assertEquals(8.9, details.rating ?: 0.0, 0.01)
        assertEquals(listOf("أكشن", "مغامرات"), details.genres)
        assertEquals("One Piece", details.metadata["originalTitle"])
        assertEquals("مستمر", details.metadata["status"])
        assertEquals("Toei Animation", details.metadata["studio"])
        assertEquals("اليابان", details.metadata["country"])
        assertEquals("1100+", details.metadata["episodeCount"])
        assertEquals(2, details.episodes.size)
    }

    // --- 4. Episode parsing ---
    @Test
    fun test04_episodeParsing() {
        val html = """
            <html><body>
                <ul id="ULEpisodesList">
                    <li><a href="https://w1.anime4up.rest/episode/jujutsu-kaisen-episode-1/">الحلقة 1</a></li>
                    <li><a href="https://w1.anime4up.rest/episode/jujutsu-kaisen-episode-2/">الحلقة 2</a></li>
                    <li><a href="https://w1.anime4up.rest/episode/jujutsu-kaisen-episode-3/">الحلقة 3</a></li>
                </ul>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val episodes = scraper.parseEpisodesDocument(doc, testBaseUrl, seasonNumber = 1)

        assertEquals(3, episodes.size)
        assertEquals(1, episodes[0].episodeNumber)
        assertEquals("https://w1.anime4up.rest/episode/jujutsu-kaisen-episode-1/", episodes[0].url)
        assertEquals(2, episodes[1].episodeNumber)
        assertEquals(3, episodes[2].episodeNumber)
    }

    // --- 5. Episode number extraction ---
    @Test
    fun test05_episodeNumberExtraction() {
        // Fallback 1: From raw text
        val numFromText = scraper.extractEpisodeNumber("الحلقة 45 مترجمة", "https://w1.anime4up.rest/episode/ep-x", 1)
        assertEquals(45, numFromText)

        // Fallback 1B: English Ep keyword
        val numFromEpText = scraper.extractEpisodeNumber("Episode 12", "https://w1.anime4up.rest/episode/xyz", 1)
        assertEquals(12, numFromEpText)

        // Fallback 2: From URL slug when text is generic
        val numFromUrl = scraper.extractEpisodeNumber("مشاهدة الحلقة", "https://w1.anime4up.rest/episode/naruto-shippuden-episode-167/", 1)
        assertEquals(167, numFromUrl)

        // Fallback 2B: From URL trailing dash number
        val numFromUrlDash = scraper.extractEpisodeNumber("مشاهدة الحلقة", "https://w1.anime4up.rest/episode/bleach-tybw-3/", 1)
        assertEquals(3, numFromUrlDash)

        // Fallback 3: Position in DOM when text and URL have no numeric hints
        val numFromDom = scraper.extractEpisodeNumber("أوفا خاصة", "https://w1.anime4up.rest/episode/special-ova/", 9)
        assertEquals(9, numFromDom)
    }

    // --- 6. Server discovery ---
    @Test
    fun test06_serverDiscovery() {
        val html = """
            <html><body>
                <ul id="episode-servers">
                    <li data-watch="https://embed.server1.com/v/aot1" data-quality="1080p">
                        <span class="server-name">السيرفر السريع</span>
                    </li>
                    <li data-watch="https://stream.server2.com/play/xyz" data-quality="720p">
                        <span class="server-name">سيرفر بديل</span>
                    </li>
                </ul>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val servers = scraper.parseServersDocument(doc, "https://w1.anime4up.rest/episode/aot-1/", testBaseUrl)

        assertEquals(2, servers.size)
        assertEquals("السيرفر السريع (1080p)", servers[0].name)
        assertEquals("https://embed.server1.com/v/aot1", servers[0].link)
        assertEquals("1080p", servers[0].metadata["quality"])
        assertEquals(ServerType.EMBED, servers[0].serverType)
        assertTrue(servers[0].requiresWebView)

        assertEquals("سيرفر بديل (720p)", servers[1].name)
        assertEquals("https://stream.server2.com/play/xyz", servers[1].link)
        assertEquals("720p", servers[1].metadata["quality"])
    }

    // --- 7. Missing data-watch ---
    @Test
    fun test07_missingDataWatch() {
        // When li has no data-watch, fallback to player iframe in page
        val htmlWithIframe = """
            <html><body>
                <ul id="episode-servers">
                    <li><span class="server-name">سيرفر معطل</span></li>
                </ul>
                <div id="episode-player">
                    <iframe src="https://player.anime4up.rest/embed/default-1"></iframe>
                </div>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(htmlWithIframe, testBaseUrl)
        val servers = scraper.parseServersDocument(doc, "https://w1.anime4up.rest/episode/1/", testBaseUrl)

        assertEquals(1, servers.size)
        assertEquals("https://player.anime4up.rest/embed/default-1", servers[0].link)
        assertEquals("السيرفر الافتراضي", servers[0].name)

        // When neither data-watch nor iframe exists
        val htmlEmpty = "<html><body><div>لا توجد سيرفرات</div></body></html>"
        val docEmpty = Jsoup.parse(htmlEmpty, testBaseUrl)
        val serversEmpty = scraper.parseServersDocument(docEmpty, "https://w1.anime4up.rest/episode/1/", testBaseUrl)
        assertTrue(serversEmpty.isEmpty())
    }

    // --- 8. Duplicate servers ---
    @Test
    fun test08_duplicateServers() {
        val html = """
            <html><body>
                <ul id="episode-servers">
                    <li data-watch="https://embed.server.com/play/same"><span class="server-name">سيرفر 1</span></li>
                    <li data-watch="https://embed.server.com/play/same"><span class="server-name">سيرفر مكرر</span></li>
                    <li data-watch="https://embed.server.com/play/other"><span class="server-name">سيرفر مختلف</span></li>
                </ul>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val servers = scraper.parseServersDocument(doc, "https://w1.anime4up.rest/episode/1/", testBaseUrl)

        // Duplicates must be removed
        assertEquals(2, servers.size)
        assertEquals("https://embed.server.com/play/same", servers[0].link)
        assertEquals("https://embed.server.com/play/other", servers[1].link)
    }

    // --- 9. Relative URLs ---
    @Test
    fun test09_relativeUrls() {
        val resolvedRootRelative = scraper.resolveUrl(testBaseUrl, "/embed/player?id=45")
        assertEquals("https://w1.anime4up.rest/embed/player?id=45", resolvedRootRelative)

        val resolvedProtocolRelative = scraper.resolveUrl(testBaseUrl, "//cdn.anime4up.rest/watch/1")
        assertEquals("https://cdn.anime4up.rest/watch/1", resolvedProtocolRelative)

        val resolvedWatchRelative = scraper.resolveWatchUrl("/video/embed/88", testBaseUrl)
        assertEquals("https://w1.anime4up.rest/video/embed/88", resolvedWatchRelative)
    }

    // --- 10. Invalid URLs ---
    @Test
    fun test10_invalidUrls() {
        // Dangerous schemes must be rejected
        assertNull(scraper.resolveWatchUrl("javascript:alert(1)", testBaseUrl))
        assertNull(scraper.resolveWatchUrl("file:///android_asset/secret.txt", testBaseUrl))
        assertNull(scraper.resolveWatchUrl("data:text/html,<script>bad()</script>", testBaseUrl))
        assertNull(scraper.resolveWatchUrl("", testBaseUrl))
        assertNull(scraper.resolveWatchUrl("   ", testBaseUrl))
    }

    // --- 11. Unknown server names ---
    @Test
    fun test11_unknownServerNames() {
        val html = """
            <html><body>
                <ul id="episode-servers">
                    <li data-watch="https://embed.unknown.com/1"></li>
                    <li data-watch="https://embed.unknown.com/2"></li>
                </ul>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val servers = scraper.parseServersDocument(doc, "https://w1.anime4up.rest/episode/1/", testBaseUrl)

        assertEquals(2, servers.size)
        assertEquals("سيرفر 1", servers[0].name)
        assertEquals("سيرفر 2", servers[1].name)
    }

    // --- 12. Unknown qualities ---
    @Test
    fun test12_unknownQualities() {
        val html = """
            <html><body>
                <ul id="episode-servers">
                    <li data-watch="https://embed.server.com/stream">
                        <span class="server-name">سيرفر بدون دقة محددة</span>
                    </li>
                </ul>
            </body></html>
        """.trimIndent()

        val doc = Jsoup.parse(html, testBaseUrl)
        val servers = scraper.parseServersDocument(doc, "https://w1.anime4up.rest/episode/1/", testBaseUrl)

        assertEquals(1, servers.size)
        // Quality must not be artificially assumed or hallucinated
        assertNull(servers[0].metadata["quality"])
        assertEquals("سيرفر بدون دقة محددة", servers[0].name)
    }

    // --- 13. Malformed HTML ---
    @Test
    fun test13_malformedHtml() {
        val brokenHtml = """
            <div><span class="anime-card-title">incomplete element
            <a href="/anime/broken
            <li data-watch="https://embed.broken.com/unclosed
        """.trimIndent()

        val doc = Jsoup.parse(brokenHtml, testBaseUrl)

        // Neither search, details, nor servers should throw unhandled runtime exceptions on malformed DOM
        val searchItems = scraper.parseSearchDocument(doc, testBaseUrl)
        assertNotNull(searchItems)

        val servers = scraper.parseServersDocument(doc, "https://w1.anime4up.rest/episode/1/", testBaseUrl)
        assertNotNull(servers)

        val episodes = scraper.parseEpisodesDocument(doc, testBaseUrl, 1)
        assertNotNull(episodes)
    }

    // --- 14. Playback handoff ---
    @Test
    fun test14_playbackHandoff() = runBlocking {
        val session = ExtractionSession(
            extensionId = "anime4up",
            scraperKey = "anime4up",
            targetPageUrl = "https://w1.anime4up.rest/episode/1",
            timeoutMs = 5000L
        )

        // 14A. Direct MP4 playback handoff
        val mp4Server = ServerItem(
            id = "s1",
            name = "Direct MP4",
            link = "https://cdn.anime4up.rest/videos/ep1.mp4",
            isDirectStream = true
        )
        val mp4Result = scraper.extractStream(dummyExtension, session, ExtractionRequest(mp4Server))
        assertTrue(mp4Result.isSuccess)
        val mp4Source = mp4Result.getOrThrow().playbackSource
        assertNotNull(mp4Source)
        assertEquals("https://cdn.anime4up.rest/videos/ep1.mp4", mp4Source!!.streamUrl)
        assertEquals(StreamProtocol.DIRECT_FILE, mp4Source.protocol)
        assertEquals("video/mp4", mp4Source.mimeType)

        // 14B. Direct HLS m3u8 playback handoff
        val hlsServer = ServerItem(
            id = "s2",
            name = "Direct HLS",
            link = "https://cdn.anime4up.rest/hls/master.m3u8",
            isDirectStream = true
        )
        val hlsResult = scraper.extractStream(dummyExtension, session, ExtractionRequest(hlsServer))
        assertTrue(hlsResult.isSuccess)
        val hlsSource = hlsResult.getOrThrow().playbackSource
        assertNotNull(hlsSource)
        assertEquals("https://cdn.anime4up.rest/hls/master.m3u8", hlsSource!!.streamUrl)
        assertEquals(StreamProtocol.HLS, hlsSource.protocol)
        assertEquals("application/x-mpegURL", hlsSource.mimeType)

        // 14C. Embed handoff without webEngine
        val embedServer = ServerItem(
            id = "s3",
            name = "Embed Player",
            link = "https://embed.player.com/watch/99",
            isDirectStream = false
        )
        val embedResult = scraper.extractStream(dummyExtension, session, ExtractionRequest(embedServer))
        assertTrue(embedResult.isSuccess)
        val embedSource = embedResult.getOrThrow().playbackSource
        assertNotNull(embedSource)
        assertEquals("https://embed.player.com/watch/99", embedSource!!.streamUrl)
        assertEquals(StreamProtocol.EMBED, embedSource.protocol)
    }
}
