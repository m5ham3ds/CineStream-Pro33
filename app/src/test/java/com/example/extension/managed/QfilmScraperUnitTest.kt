package com.example.extension.managed

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.scraper.QfilmScraper
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLEncoder

class QfilmScraperUnitTest {

    private val scraper = QfilmScraper()
    private val extension = ManagedExtension(
        id = "qfilm",
        name = "كيو فيلم",
        baseUrl = "https://a.qfilm.tv",
        scraperKey = "qfilm",
        contentTypes = setOf(ContentType.MOVIE, ContentType.ANIME),
        status = ExtensionLifecycleStatus.ACTIVE
    )

    // ==========================================
    // 1. METADATA & CONTRACT TESTS
    // ==========================================

    @Test
    fun scraperMetadata_isStrictlyCompliant() {
        assertEquals("qfilm", scraper.scraperKey)
        assertEquals(1, scraper.implementationVersion)

        // Capabilities: SEARCH, DETAILS, SERVER_DISCOVERY, VIDEO_EXTRACTION
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.SEARCH))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.DETAILS))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.SERVER_DISCOVERY))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.VIDEO_EXTRACTION))
        assertFalse(scraper.supportedCapabilities.contains(ScraperCapability.EPISODES))
        assertFalse(scraper.supportedCapabilities.contains(ScraperCapability.DIRECT_DOWNLOAD))

        // Content Types: MOVIE & ANIME only (NO SERIES)
        assertTrue(scraper.supportedContentTypes.contains(ContentType.MOVIE))
        assertTrue(scraper.supportedContentTypes.contains(ContentType.ANIME))
        assertFalse(scraper.supportedContentTypes.contains(ContentType.SERIES))
    }

    @Test
    fun getEpisodes_returnsEmptyList_noSeriesSupport() = runBlocking {
        val result = scraper.getEpisodes(extension, "https://a.qfilm.tv/series/test", 1)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    // ==========================================
    // 2. SEARCH & TITLE MATCHING TESTS
    // ==========================================

    @Test
    fun search_emptyQuery_returnsEmptySuccess() = runBlocking {
        val result = scraper.search(extension, SearchRequest(query = "   "))
        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertTrue(data.items.isEmpty())
        assertEquals(1, data.page)
    }

    @Test
    fun search_insecureHttpBaseUrl_rejectedBySecurity() = runBlocking {
        val insecureExt = extension.copy(baseUrl = "http://a.qfilm.tv")
        val result = scraper.search(insecureExt, SearchRequest(query = "Inception"))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ExtensionError.SecurityViolation)
    }

    @Test
    fun titleNormalization_handlesArabicAndEnglishAndSpecialChars() {
        // Arabic normalization (alif with hamza, taa marbuta, etc.)
        val normalizedArabic = scraper.normalizeTitle("فيلم! أنمي: المحقق، كونان؟ (2024)")
        assertEquals("فيلم انمي المحقق كونان 2024", normalizedArabic)

        // English normalization
        val normalizedEnglish = scraper.normalizeTitle("Spider-Man: Across the Spider-Verse [1080p]!")
        assertEquals("spider man across the spider verse 1080p", normalizedEnglish)
    }

    @Test
    fun calculateMatchScore_accuratelyScoresMatches() {
        val query = "Inception"
        val exactMatch = scraper.calculateMatchScore(query, "Inception")
        assertEquals(1.0, exactMatch, 0.001)

        val containedMatch = scraper.calculateMatchScore(query, "فيلم Inception مترجم")
        assertTrue(containedMatch >= 0.95)

        val partialMatch = scraper.calculateMatchScore("Spider Man No Way Home", "Spider Man Homecoming")
        assertTrue(partialMatch > 0.0 && partialMatch < 0.95)

        val zeroMatch = scraper.calculateMatchScore("Batman", "Interstellar")
        assertEquals(0.0, zeroMatch, 0.001)
    }

    @Test
    fun urlEncoding_formatsSearchQueryCorrectly() {
        val rawQuery = "فيلم كونان 2024"
        val encoded = URLEncoder.encode(rawQuery, "UTF-8")
        assertFalse(encoded.contains(" "))
        assertTrue(encoded.contains("%"))
    }

    @Test
    fun parseHtmlSearchResults_extractsMoviesAndAnimeWithPosters() {
        val sampleSearchHtml = """
            <html>
                <body>
                    <ul class="pm-ul-browse-videos">
                        <li class="pm-li-video">
                            <a href="https://a.qfilm.tv/watch.php?vid=101" title="فيلم Oppenheimer 2023">
                                <img src="https://a.qfilm.tv/uploads/thumbs/oppenheimer.jpg" />
                                <h3 class="caption"><a href="https://a.qfilm.tv/watch.php?vid=101">فيلم Oppenheimer 2023</a></h3>
                            </a>
                        </li>
                        <li class="pm-li-video">
                            <a href="https://a.qfilm.tv/watch.php?vid=202" title="فيلم انمي رحلة إلى الفضاء">
                                <img data-echo="https://a.qfilm.tv/uploads/thumbs/anime.jpg" />
                                <h3 class="caption"><a href="https://a.qfilm.tv/watch.php?vid=202">فيلم انمي رحلة إلى الفضاء</a></h3>
                            </a>
                        </li>
                    </ul>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleSearchHtml)
        val elements = doc.select("ul.pm-ul-browse-videos li")
        assertEquals(2, elements.size)

        val movieEl = elements[0]
        val movieHref = movieEl.selectFirst("a[href*='watch.php']")?.attr("href")
        val movieTitle = movieEl.selectFirst("h3.caption")?.text()
        val moviePoster = movieEl.selectFirst("img")?.attr("src")
        assertEquals("https://a.qfilm.tv/watch.php?vid=101", movieHref)
        assertEquals("فيلم Oppenheimer 2023", movieTitle)
        assertEquals("https://a.qfilm.tv/uploads/thumbs/oppenheimer.jpg", moviePoster)

        val animeEl = elements[1]
        val animeTitle = animeEl.selectFirst("h3.caption")?.text().orEmpty()
        val isAnime = animeTitle.contains("انمي") || animeTitle.contains("أنمي")
        assertTrue("Must detect anime content type", isAnime)
    }

    // ==========================================
    // 3. DETAILS & WATCH.PHP -> PLAY.PHP TESTS
    // ==========================================

    @Test
    fun parseWatchPage_extractsPlayPhpLinkCorrectly() {
        val sampleWatchHtml = """
            <html>
                <head>
                    <title>مشاهدة فيلم Oppenheimer 2023</title>
                    <meta property="og:image" content="https://a.qfilm.tv/poster.jpg" />
                </head>
                <body>
                    <div class="pm-video-heading">
                        <h1>مشاهدة فيلم Oppenheimer 2023</h1>
                    </div>
                    <div id="pm-video-description">
                        قصة حياة العالم روبرت أوبنهايمر وصناعة القنبلة الذرية.
                    </div>
                    <div class="video-bibplayer">
                        <a class="xtgo" href="play.php?vid=101">سيرفرات المشاهدة والتحميل</a>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleWatchHtml)
        val title = doc.selectFirst(".pm-video-heading h1")?.text()
        val playLink = doc.selectFirst(".video-bibplayer a.xtgo, a[href*='play.php']")?.attr("href")

        assertEquals("مشاهدة فيلم Oppenheimer 2023", title)
        assertEquals("play.php?vid=101", playLink)
    }

    @Test
    fun parseWatchPage_resolvesRelativeAndAbsolutePlayPhpUrls() {
        val base = "https://a.qfilm.tv"

        val relPath = "play.php?vid=303"
        val resolvedRel = if (relPath.startsWith("/")) "$base$relPath" else "$base/$relPath"
        assertEquals("https://a.qfilm.tv/play.php?vid=303", resolvedRel)

        val absPath = "/play.php?vid=303"
        val resolvedAbs = if (absPath.startsWith("/")) "$base$absPath" else "$base/$absPath"
        assertEquals("https://a.qfilm.tv/play.php?vid=303", resolvedAbs)

        val fullUrl = "https://a.qfilm.tv/play.php?vid=303"
        val resolvedFull = if (fullUrl.startsWith("http")) fullUrl else "$base/$fullUrl"
        assertEquals("https://a.qfilm.tv/play.php?vid=303", resolvedFull)
    }

    // ==========================================
    // 4. SERVER DISCOVERY & PARSER TESTS
    // ==========================================

    @Test
    fun extractServersFromJs_parsesVarServersArray() {
        val playHtmlWithVarServers = """
            <html>
                <head>
                    <script type="text/javascript">
                        var servers = [
                            '<iframe src="https://uqload.com/embed-abc12345.html" width="100%" height="100%"></iframe>',
                            '<iframe src="https://vidbom.com/embed-xyz987.html" allowfullscreen></iframe>',
                            '<iframe src="https://streamtape.com/e/st12345678" frameborder="0"></iframe>'
                        ];
                    </script>
                </head>
                <body>
                    <div id="player-container"></div>
                </body>
            </html>
        """.trimIndent()

        val urls = scraper.extractServersFromJs(playHtmlWithVarServers)
        assertEquals(3, urls.size)
        assertEquals("https://uqload.com/embed-abc12345.html", urls[0])
        assertEquals("https://vidbom.com/embed-xyz987.html", urls[1])
        assertEquals("https://streamtape.com/e/st12345678", urls[2])
    }

    @Test
    fun extractServersFromJs_parsesWindowServersArray() {
        val playHtmlWithWindowServers = """
            <html>
                <script>
                    window.servers = [
                        "https://cdn.server.com/master.m3u8",
                        "https://doodstream.com/e/dood1234"
                    ];
                </script>
            </html>
        """.trimIndent()

        val urls = scraper.extractServersFromJs(playHtmlWithWindowServers)
        assertEquals(2, urls.size)
        assertEquals("https://cdn.server.com/master.m3u8", urls[0])
        assertEquals("https://doodstream.com/e/dood1234", urls[1])
    }

    @Test
    fun parseServerArrayItems_handlesHtmlStringsWithIframeAndEscapedQuotes() {
        val rawItems = listOf(
            "<iframe src=\"https://uqload.to/embed-123.html\"></iframe>",
            "<iframe src='https://vidbom.com/embed-456.html'></iframe>",
            "\"https://cdn.direct.com/video.mp4\"",
            "https://streamtape.com/e/789"
        )
        val extracted = scraper.parseServerArrayItems(rawItems)
        assertEquals(4, extracted.size)
        assertEquals("https://uqload.to/embed-123.html", extracted[0])
        assertEquals("https://vidbom.com/embed-456.html", extracted[1])
        assertEquals("https://cdn.direct.com/video.mp4", extracted[2])
        assertEquals("https://streamtape.com/e/789", extracted[3])
    }

    @Test
    fun extractServersFromDom_parsesDirectIframesAndLinks() {
        val playHtmlWithDomIframes = """
            <html>
                <body>
                    <div class="video-holder">
                        <iframe src="https://mycima.stream/embed/999"></iframe>
                    </div>
                    <ul class="servers">
                        <li><a data-src="https://akamaized.net/stream.m3u8">سيرفر مباشر</a></li>
                    </ul>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(playHtmlWithDomIframes)
        val urls = scraper.extractServersFromDom(doc)
        assertEquals(2, urls.size)
        assertTrue(urls.contains("https://mycima.stream/embed/999"))
        assertTrue(urls.contains("https://akamaized.net/stream.m3u8"))
    }

    @Test
    fun serverDeduplication_eliminatesDuplicateUrls() {
        val duplicateHtml = """
            <html>
                <script>
                    var servers = [
                        '<iframe src="https://uqload.com/embed-123.html"></iframe>',
                        '<iframe src="https://uqload.com/embed-123.html"></iframe>'
                    ];
                </script>
                <body>
                    <iframe src="https://uqload.com/embed-123.html"></iframe>
                </body>
            </html>
        """.trimIndent()

        val jsUrls = scraper.extractServersFromJs(duplicateHtml)
        val doc = Jsoup.parse(duplicateHtml)
        val domUrls = scraper.extractServersFromDom(doc)
        val allUrls = (jsUrls + domUrls).distinct()

        assertEquals(1, allUrls.size)
        assertEquals("https://uqload.com/embed-123.html", allUrls[0])
    }

    @Test
    fun classifyServerName_correctlyLabelsKnownHosts() {
        assertEquals("Uqload", scraper.classifyServerName("https://uqload.com/embed-123.html", 1))
        assertEquals("VidBom", scraper.classifyServerName("https://vidbom.com/xyz", 2))
        assertEquals("DoodStream", scraper.classifyServerName("https://doodstream.com/e/123", 3))
        assertEquals("StreamTape", scraper.classifyServerName("https://streamtape.com/e/abc", 4))
        assertEquals("Akamai CDN", scraper.classifyServerName("https://akamaized.net/master.m3u8", 5))
        assertEquals("Myhost", scraper.classifyServerName("https://myhost.org/video", 6))
    }

    @Test
    fun serverClassification_directVersusEmbed() {
        val directM3u8 = "https://server.com/live/playlist.m3u8"
        val directMp4 = "https://server.com/videos/movie.mp4"
        val embedPage = "https://uqload.com/embed-99.html"

        val item1 = ServerItem(id = "1", name = "S1", link = directM3u8, isDirectStream = true)
        val item2 = ServerItem(id = "2", name = "S2", link = directMp4, isDirectStream = true)
        val item3 = ServerItem(id = "3", name = "S3", link = embedPage, isDirectStream = false)

        assertEquals(ServerType.DIRECT, item1.serverType)
        assertFalse(item1.requiresWebView)

        assertEquals(ServerType.DIRECT, item2.serverType)
        assertFalse(item2.requiresWebView)

        assertEquals(ServerType.EMBED, item3.serverType)
        assertTrue(item3.requiresWebView)
    }

    // ==========================================
    // 5. MOST IMPORTANT UNIT TEST (SECTION 34)
    // ==========================================

    @Test
    fun discoverServers_mostImportantUnitTest_legacyLikePlayPageWithMultipleHtmlStringsProducesServerDiscoveryResult() {
        val legacyLikePlayHtml = """
            <html>
                <head>
                    <title>سيرفرات مشاهدة فيلم Inception</title>
                    <script type="text/javascript">
                        var servers = [
                            '<iframe src="https://uqload.com/embed-111.html" width="100%"></iframe>',
                            '<iframe src=\"https://vidbom.com/embed-222.html\" allowfullscreen></iframe>',
                            '<iframe src="https://streamtape.com/e/333" frameborder="0"></iframe>',
                            "https://cdn.akamaized.net/direct/hls/master.m3u8"
                        ];
                    </script>
                </head>
                <body>
                    <div id="bibplayer">
                        <iframe src="https://uqload.com/embed-111.html"></iframe>
                    </div>
                </body>
            </html>
        """.trimIndent()

        // 1. Verify JS extraction of multiple server HTML strings
        val jsUrls = scraper.extractServersFromJs(legacyLikePlayHtml)
        assertEquals(4, jsUrls.size)
        assertEquals("https://uqload.com/embed-111.html", jsUrls[0])
        assertEquals("https://vidbom.com/embed-222.html", jsUrls[1])
        assertEquals("https://streamtape.com/e/333", jsUrls[2])
        assertEquals("https://cdn.akamaized.net/direct/hls/master.m3u8", jsUrls[3])

        // 2. Verify ServerDiscoveryResult creation with canonical models
        val serverItems = jsUrls.mapIndexed { index, url ->
            val isDirect = url.contains(".m3u8") || url.contains(".mp4")
            ServerItem(
                id = (index + 1).toString(),
                name = scraper.classifyServerName(url, index + 1),
                link = url,
                isDirectStream = isDirect,
                serverType = if (isDirect) ServerType.DIRECT else ServerType.EMBED,
                requiresWebView = !isDirect
            )
        }

        val discoveryResult = ServerDiscoveryResult(
            servers = serverItems,
            sourcePageUrl = "https://a.qfilm.tv/play.php?vid=101"
        )

        assertEquals(4, discoveryResult.servers.size)
        assertEquals("Uqload", discoveryResult.servers[0].name)
        assertEquals(ServerType.EMBED, discoveryResult.servers[0].serverType)
        assertTrue(discoveryResult.servers[0].requiresWebView)

        assertEquals("VidBom", discoveryResult.servers[1].name)
        assertEquals(ServerType.EMBED, discoveryResult.servers[1].serverType)
        assertTrue(discoveryResult.servers[1].requiresWebView)

        assertEquals("StreamTape", discoveryResult.servers[2].name)
        assertEquals(ServerType.EMBED, discoveryResult.servers[2].serverType)
        assertTrue(discoveryResult.servers[2].requiresWebView)

        assertEquals("Akamai CDN", discoveryResult.servers[3].name)
        assertEquals(ServerType.DIRECT, discoveryResult.servers[3].serverType)
        assertFalse(discoveryResult.servers[3].requiresWebView)
    }

    // ==========================================
    // 6. CONTROLLED WEB ENGINE PRIMARY EXECUTION PATH
    // ==========================================

    @Test
    fun discoverServers_withControlledWebViewEngine_usesAsPrimaryPath() = runBlocking {
        val session = ExtractionSession(
            extensionId = extension.id,
            scraperKey = extension.scraperKey,
            targetPageUrl = "https://a.qfilm.tv/watch.php?vid=101"
        )
        val request = ServerDiscoveryRequest(
            targetUrl = "https://a.qfilm.tv/watch.php?vid=101",
            mediaTitle = "Oppenheimer"
        )

        var webEngineCalled = false
        val mockWebEngine = object : WebExtractionEngine {
            override suspend fun extractServers(
                targetUrl: String,
                script: String,
                timeoutMs: Long,
                expectedOrigin: String
            ): Result<List<ServerItem>> {
                webEngineCalled = true
                assertEquals("https://a.qfilm.tv/watch.php?vid=101", targetUrl)
                assertEquals("https://a.qfilm.tv", expectedOrigin)
                return Result.success(
                    listOf(
                        ServerItem(id = "1", name = "Uqload", link = "https://uqload.com/embed-1.html"),
                        ServerItem(id = "2", name = "VidBom", link = "https://vidbom.com/embed-2.html"),
                        ServerItem(id = "3", name = "سيرفر 3", link = "https://cdn.example.com/master.m3u8")
                    )
                )
            }

            override suspend fun extractStreamUrl(
                targetUrl: String,
                targetServerId: String?,
                script: String,
                timeoutMs: Long,
                expectedOrigin: String
            ): Result<String> = Result.failure(Exception("Not used"))

            override fun cancel() {}
        }

        val result = scraper.discoverServers(extension, session, request, mockWebEngine)
        assertTrue("ControlledWebViewEngine must succeed", result.isSuccess)
        assertTrue("ControlledWebViewEngine MUST be invoked as primary path", webEngineCalled)

        val data = result.getOrThrow()
        assertEquals(3, data.servers.size)
        assertEquals("Uqload", data.servers[0].name)
        assertEquals(ServerType.EMBED, data.servers[0].serverType)
        assertEquals("VidBom", data.servers[1].name)
        assertEquals(ServerType.EMBED, data.servers[1].serverType)
        assertEquals("سيرفر 3", data.servers[2].name)
        assertEquals(ServerType.DIRECT, data.servers[2].serverType)
    }

    // ==========================================
    // 7. STREAM EXTRACTION TESTS
    // ==========================================

    @Test
    fun extractStream_directM3u8_producesHlsPlaybackAndDownloadSources() = runBlocking {
        val m3u8Url = "https://cdn.example.com/movie/master.m3u8"
        val serverItem = ServerItem(
            id = "1",
            name = "سيرفر سريع",
            link = m3u8Url,
            isDirectStream = true,
            serverType = ServerType.DIRECT,
            requiresWebView = false
        )
        val request = ExtractionRequest(serverItem = serverItem, mediaTitle = "Oppenheimer")
        val session = ExtractionSession(
            sessionId = "session-1",
            extensionId = extension.id,
            scraperKey = extension.scraperKey,
            targetPageUrl = "https://a.qfilm.tv/watch.php?vid=101"
        )

        val result = scraper.extractStream(extension, session, request, webEngine = null)
        assertTrue("Direct m3u8 extraction must succeed without WebView", result.isSuccess)

        val extraction = result.getOrThrow()
        val playback = extraction.playbackSource
        assertNotNull(playback)
        assertEquals(m3u8Url, playback?.streamUrl)
        assertEquals(StreamProtocol.HLS, playback?.protocol)
        assertEquals("application/x-mpegURL", playback?.mimeType)
        assertTrue(playback?.headers?.containsKey("Referer") == true)
        assertTrue(playback?.headers?.containsKey("User-Agent") == true)

        val download = extraction.downloadSource
        assertNotNull(download)
        assertEquals(m3u8Url, download?.url)
        assertEquals(StreamProtocol.HLS, download?.protocol)
        assertEquals("application/x-mpegURL", download?.mimeType)
    }

    @Test
    fun extractStream_directMp4_producesDirectFilePlaybackAndDownloadSources() = runBlocking {
        val mp4Url = "https://storage.example.com/movies/film.mp4"
        val serverItem = ServerItem(
            id = "2",
            name = "سيرفر MP4",
            link = mp4Url,
            isDirectStream = true,
            serverType = ServerType.DIRECT,
            requiresWebView = false
        )
        val request = ExtractionRequest(serverItem = serverItem, mediaTitle = "Film")
        val session = ExtractionSession(
            sessionId = "session-2",
            extensionId = extension.id,
            scraperKey = extension.scraperKey,
            targetPageUrl = "https://a.qfilm.tv/watch.php?vid=202"
        )

        val result = scraper.extractStream(extension, session, request, webEngine = null)
        assertTrue(result.isSuccess)

        val extraction = result.getOrThrow()
        assertEquals(mp4Url, extraction.playbackSource?.streamUrl)
        assertEquals(StreamProtocol.DIRECT_FILE, extraction.playbackSource?.protocol)
        assertEquals("video/mp4", extraction.playbackSource?.mimeType)

        assertEquals(mp4Url, extraction.downloadSource?.url)
        assertEquals(StreamProtocol.DIRECT_FILE, extraction.downloadSource?.protocol)
    }

    @Test
    fun extractStream_embedServer_callsWebEngineAndResolvesStream() = runBlocking {
        val embedUrl = "https://uqload.com/embed-test.html"
        val resolvedM3u8 = "https://cdn.uqload.com/hls/test/master.m3u8"
        val serverItem = ServerItem(
            id = "1",
            name = "Uqload",
            link = embedUrl,
            isDirectStream = false,
            serverType = ServerType.EMBED,
            requiresWebView = true
        )
        val request = ExtractionRequest(serverItem = serverItem, mediaTitle = "Film")
        val session = ExtractionSession(
            sessionId = "session-3",
            extensionId = extension.id,
            scraperKey = extension.scraperKey,
            targetPageUrl = "https://a.qfilm.tv/watch.php?vid=303"
        )

        val mockWebEngine = object : WebExtractionEngine {
            override suspend fun extractServers(
                targetUrl: String,
                script: String,
                timeoutMs: Long,
                expectedOrigin: String
            ): Result<List<ServerItem>> = Result.failure(Exception("Not used"))

            override suspend fun extractStreamUrl(
                targetUrl: String,
                targetServerId: String?,
                script: String,
                timeoutMs: Long,
                expectedOrigin: String
            ): Result<String> {
                assertEquals(embedUrl, targetUrl)
                return Result.success(resolvedM3u8)
            }

            override fun cancel() {}
        }

        val result = scraper.extractStream(extension, session, request, mockWebEngine)
        assertTrue("Embed extraction via WebEngine must succeed", result.isSuccess)

        val extraction = result.getOrThrow()
        assertEquals(resolvedM3u8, extraction.playbackSource?.streamUrl)
        assertEquals(StreamProtocol.HLS, extraction.playbackSource?.protocol)
        assertEquals(resolvedM3u8, extraction.downloadSource?.url)
    }

    // ==========================================
    // 8. WEBVIEW JAVASCRIPT STATE MACHINE TESTS (SECTION 35)
    // ==========================================

    @Test
    fun bundledQfilmScript_containsStateMachinesAndPageDispatch() {
        val script = scraper.getBundledQfilmScript()

        // 1. Must handle search.php
        assertTrue("Script must handle search.php", script.contains("search.php"))
        assertTrue("Script must query browse videos in DOM", script.contains("ul.pm-ul-browse-videos li"))

        // 2. Must handle watch.php and transition to play.php
        assertTrue("Script must handle watch.php", script.contains("watch.php"))
        assertTrue("Script must find play link on watch page", script.contains("play.php"))
        assertTrue("Script must search for xtgo selector", script.contains(".video-bibplayer a.xtgo"))

        // 3. Must handle play.php and poll for window.servers
        assertTrue("Script must handle play.php", script.contains("play.php"))
        assertTrue("Script must poll for window.servers", script.contains("window.servers"))
        assertTrue("Script must poll for servers", script.contains("servers"))
        assertTrue("Script must have bounded polling", script.contains("maxPlayPoll"))
        assertTrue("Script must poll at ~500ms intervals", script.contains("500"))

        // 4. Must use DOMParser for HTML string elements
        assertTrue("Script must use DOMParser", script.contains("DOMParser"))
        assertTrue("Script must find iframe tag", script.contains("iframe"))

        // 5. Must communicate exclusively via SafeScraperBridge
        assertTrue("Script must use SafeScraperBridge", script.contains("SafeScraperBridge"))
        assertTrue("Script must post SERVER_LIST message", script.contains("SERVER_LIST"))
    }

    // ==========================================
    // 9. SECURITY & DECOUPLING AUDIT TESTS
    // ==========================================

    @Test
    fun securityAudit_sourceCodeContainsNoProhibitedImports() {
        val candidate1 = File("app/src/main/java/com/example/extension/managed/scraper/QfilmScraper.kt")
        val candidate2 = File("src/main/java/com/example/extension/managed/scraper/QfilmScraper.kt")
        val scraperFile = if (candidate1.exists()) candidate1 else candidate2
        assertTrue("QfilmScraper source file must exist", scraperFile.exists())
        val content = scraperFile.readText()

        // 1. No AndroidBridge
        assertFalse("Must not contain AndroidBridge", content.contains("AndroidBridge"))
        assertFalse("Must not contain sendServersV2", content.contains("sendServersV2"))
        assertFalse("Must not contain window.Android", content.contains("window.Android"))

        // 2. No Firebase imports
        assertFalse("Must not import Firebase", content.contains("com.google.firebase"))
        assertFalse("Must not import Firestore", content.contains("com.google.firebase.firestore"))

        // 3. No Player imports
        assertFalse("Must not import ExoPlayer", content.contains("androidx.media3.exoplayer"))
        assertFalse("Must not import PlayerView", content.contains("androidx.media3.ui.PlayerView"))

        // 4. No Downloader Service imports
        assertFalse("Must not import StreamDownloaderService", content.contains("StreamDownloaderService"))
        assertFalse("Must not import AndroidDownloader", content.contains("AndroidDownloader"))

        // 5. No UI / Compose imports
        assertFalse("Must not import androidx.compose", content.contains("androidx.compose"))
        assertFalse("Must not import UI screens", content.contains("com.example.ui"))

        // 6. HTTPS enforcement check exists
        assertTrue("Must enforce HTTPS checks", content.contains("https://"))
    }
}
