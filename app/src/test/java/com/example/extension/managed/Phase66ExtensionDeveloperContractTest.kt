package com.example.extension.managed

import android.net.Uri
import com.example.extension.managed.adapter.DownloaderHandoffAdapter
import com.example.extension.managed.adapter.PlayerHandoffAdapter
import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.runtime.DefaultControlledManagedExtensionRuntime
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.runtime.FallbackManager
import com.example.extension.managed.runtime.challenge.ChallengeHandler
import com.example.extension.managed.runtime.challenge.ChallengeResult
import com.example.extension.managed.runtime.context.ExtensionContext
import com.example.extension.managed.runtime.network.ConnectivityMonitor
import com.example.extension.managed.runtime.network.DefaultConnectivityMonitor
import com.example.extension.managed.runtime.network.NetworkState
import com.example.extension.managed.web.SafeScraperBridge
import com.example.extension.managed.web.TrustedEmbedHostPolicy
import com.example.extension.managed.web.WebExtractionEngine
import com.example.utils.M3U8Parser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Phase 6.6 — Extension Developer Contract Verification Test Suite.
 *
 * Formally validates that the Managed Extension Runtime and BaseSiteScraper contract
 * fulfill all constitutional requirements:
 * 1. Scraper Identity & Metadata Specification
 * 2. Search Contract & Normalization
 * 3. Media Details Contract
 * 4. Episodes Contract & Numbering
 * 5. Server Discovery Contract (Direct vs Embed classification)
 * 6. Media Extraction Contract & Strict Quality Invariant (No fabricated 1080p)
 * 7. Quality Parsing Utility Contract (MediaVariantParser)
 * 8. Error Classification & Recoverability Taxonomy (ExtensionError)
 * 9. Execution Context Encapsulation (ExtensionContext)
 * 10. Handoff Sanitization (Player & Downloader isolation, cookie stripping)
 * 11. Security Sandbox Policy (TrustedEmbedHostPolicy & SafeScraperBridge)
 * 12. Strict Module Decoupling (Zero UI, Zero Firebase, Zero Dynamic Code Loading)
 * 13. Full End-to-End Canonical Pipeline Verification
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase66ExtensionDeveloperContractTest {

    /**
     * Canonical reference test scraper conforming 100% to the BaseSiteScraper contract.
     * Operates purely with deterministic in-memory fixtures.
     */
    private class CanonicalReferenceTestScraper(
        override val scraperKey: String = "canonical_ref",
        override val implementationVersion: Int = 1,
        override val supportedCapabilities: Set<ScraperCapability> = setOf(
            ScraperCapability.SEARCH,
            ScraperCapability.DETAILS,
            ScraperCapability.EPISODES,
            ScraperCapability.SERVER_DISCOVERY,
            ScraperCapability.VIDEO_EXTRACTION
        ),
        override val supportedContentTypes: Set<ContentType> = setOf(
            ContentType.MOVIE,
            ContentType.SERIES,
            ContentType.ANIME
        )
    ) : BaseSiteScraper {

        var lastSearchQuery: String? = null
        var lastDetailsUrl: String? = null
        var lastEpisodesSeriesUrl: String? = null
        var lastDiscoveryTargetUrl: String? = null
        var lastExtractionServerLink: String? = null

        override suspend fun search(
            extension: ManagedExtension,
            request: SearchRequest
        ): Result<SearchResult> {
            lastSearchQuery = request.query
            if (request.query.isBlank()) {
                return Result.success(SearchResult(items = emptyList(), page = request.page))
            }
            if (request.query.equals("TRIGGER_PARSE_ERROR", ignoreCase = true)) {
                return Result.failure(ExtensionError.ParseError("Simulated DOM parse error"))
            }
            if (request.query.equals("TRIGGER_NOT_FOUND", ignoreCase = true)) {
                return Result.failure(ExtensionError.MediaNotFound(request.query))
            }

            val item = SearchMediaItem(
                id = "item_ref_1",
                title = "Result for ${request.query}",
                posterUrl = "${extension.baseUrl}/poster.jpg",
                url = "${extension.baseUrl}/watch/${request.query.lowercase()}",
                contentType = request.contentType ?: ContentType.MOVIE,
                year = "2024"
            )
            return Result.success(
                SearchResult(
                    items = listOf(item),
                    page = request.page,
                    hasNextPage = false,
                    metadata = mapOf("provider" to scraperKey)
                )
            )
        }

        override suspend fun getDetails(
            extension: ManagedExtension,
            url: String
        ): Result<MediaDetailsResult> {
            lastDetailsUrl = url
            return Result.success(
                MediaDetailsResult(
                    id = url.hashCode().toString(),
                    title = "Canonical Title",
                    description = "A canonical media description.",
                    posterUrl = "${extension.baseUrl}/poster.jpg",
                    bannerUrl = "${extension.baseUrl}/banner.jpg",
                    contentType = ContentType.MOVIE,
                    url = url,
                    year = "2024",
                    rating = 8.5,
                    runtime = "120 min"
                )
            )
        }

        override suspend fun getEpisodes(
            extension: ManagedExtension,
            seriesUrl: String,
            season: Int
        ): Result<List<EpisodeItem>> {
            lastEpisodesSeriesUrl = seriesUrl
            val episodes = listOf(
                EpisodeItem(
                    id = "ep_1",
                    title = "Episode 1",
                    episodeNumber = 1,
                    seasonNumber = season,
                    url = "$seriesUrl/season/$season/episode/1"
                ),
                EpisodeItem(
                    id = "ep_2",
                    title = "Episode 2",
                    episodeNumber = 2,
                    seasonNumber = season,
                    url = "$seriesUrl/season/$season/episode/2"
                )
            )
            return Result.success(episodes)
        }

        override suspend fun discoverServers(
            extension: ManagedExtension,
            session: ExtractionSession,
            request: ServerDiscoveryRequest,
            webEngine: WebExtractionEngine?
        ): Result<ServerDiscoveryResult> {
            lastDiscoveryTargetUrl = request.targetUrl
            val directServer = ServerItem(
                id = "srv_direct",
                name = "Fast Direct CDN",
                link = "https://cdn.example.com/stream.mp4",
                isDirectStream = true
            )
            val embedServer = ServerItem(
                id = "srv_embed",
                name = "StreamHG Embed",
                link = "https://hgcloud.to/e/test1234",
                isDirectStream = false
            )
            return Result.success(
                ServerDiscoveryResult(
                    servers = listOf(directServer, embedServer),
                    sourcePageUrl = request.targetUrl
                )
            )
        }

        override suspend fun extractStream(
            extension: ManagedExtension,
            session: ExtractionSession,
            request: ExtractionRequest,
            webEngine: WebExtractionEngine?
        ): Result<ExtractionResult> {
            lastExtractionServerLink = request.serverItem.link
            val isDirect = request.serverItem.isDirectStream

            val variant = MediaVariant(
                url = request.serverItem.link,
                protocol = if (isDirect) StreamProtocol.DIRECT_FILE else StreamProtocol.HLS,
                label = if (isDirect) "720p" else "Auto",
                height = if (isDirect) 720 else null
            )

            val playbackSource = PlaybackSource(
                streamUrl = request.serverItem.link,
                mimeType = if (isDirect) "video/mp4" else "application/x-mpegURL",
                protocol = if (isDirect) StreamProtocol.DIRECT_FILE else StreamProtocol.HLS,
                variants = listOf(variant),
                headers = mapOf("Referer" to extension.baseUrl)
            )

            val downloadSource = DownloadSource(
                url = request.serverItem.link,
                mimeType = if (isDirect) "video/mp4" else "application/x-mpegURL",
                protocol = if (isDirect) StreamProtocol.DIRECT_FILE else StreamProtocol.HLS,
                headers = mapOf("Referer" to extension.baseUrl)
            )

            return Result.success(
                ExtractionResult(
                    playbackSource = playbackSource,
                    downloadSource = downloadSource,
                    variants = listOf(variant)
                )
            )
        }
    }

    // ==========================================
    // 1. SCRAPER IDENTITY & METADATA CONTRACT
    // ==========================================

    @Test
    fun test01_scraperIdentityAndMetadataContract() {
        val scraper = CanonicalReferenceTestScraper()
        assertEquals("canonical_ref", scraper.scraperKey)
        assertTrue(scraper.implementationVersion >= 1)
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.SEARCH))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.DETAILS))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.EPISODES))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.SERVER_DISCOVERY))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.VIDEO_EXTRACTION))
        assertTrue(scraper.supportedContentTypes.contains(ContentType.MOVIE))
        assertTrue(scraper.supportedContentTypes.contains(ContentType.SERIES))
    }

    // ==========================================
    // 2. SEARCH CONTRACT & NORMALIZATION
    // ==========================================

    @Test
    fun test02_searchContract_normalizedExecution() = runBlocking {
        val scraper = CanonicalReferenceTestScraper()
        val extension = ManagedExtension(
            id = "ext_ref",
            name = "Canonical Extension",
            baseUrl = "https://canonical.live",
            scraperKey = "canonical_ref",
            runtimeApiVersion = 1
        )
        val request = SearchRequest(query = "Matrix", contentType = ContentType.MOVIE, page = 1)
        val result = scraper.search(extension, request)

        assertTrue(result.isSuccess)
        val searchResult = result.getOrThrow()
        assertEquals(1, searchResult.items.size)
        assertEquals("Matrix", scraper.lastSearchQuery)
        val item = searchResult.items.first()
        assertEquals("Result for Matrix", item.title)
        assertEquals("https://canonical.live/watch/matrix", item.url)
        assertEquals(ContentType.MOVIE, item.contentType)
        assertEquals("2024", item.year)
        assertEquals(1, searchResult.page)
        assertFalse(searchResult.hasNextPage)
    }

    @Test
    fun test03_searchContract_emptyQueryHandling() = runBlocking {
        val scraper = CanonicalReferenceTestScraper()
        val extension = ManagedExtension(
            id = "ext_ref",
            name = "Canonical Extension",
            baseUrl = "https://canonical.live",
            scraperKey = "canonical_ref",
            runtimeApiVersion = 1
        )
        val result = scraper.search(extension, SearchRequest(query = ""))
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().items.isEmpty())
    }

    @Test
    fun test04_searchContract_contextOverload() = runBlocking {
        val scraper = CanonicalReferenceTestScraper()
        val context = ExtensionContext(
            extensionId = "ext_ctx",
            scraperKey = "canonical_ref",
            baseUrl = "https://context.live",
            runtimeApiVersion = 1,
            connectivity = DefaultConnectivityMonitor { true },
            session = ExtractionSession(extensionId = "ext_ctx", scraperKey = "canonical_ref", targetPageUrl = "https://context.live")
        )
        val result = scraper.search(context, SearchRequest("Inception"))
        assertTrue(result.isSuccess)
        assertEquals("Result for Inception", result.getOrThrow().items.first().title)
        assertEquals("Inception", scraper.lastSearchQuery)
    }

    // ==========================================
    // 3. MEDIA DETAILS CONTRACT
    // ==========================================

    @Test
    fun test05_mediaDetailsContract_normalizedExecution() = runBlocking {
        val scraper = CanonicalReferenceTestScraper()
        val extension = ManagedExtension(
            id = "ext_ref",
            name = "Canonical Extension",
            baseUrl = "https://canonical.live",
            scraperKey = "canonical_ref",
            runtimeApiVersion = 1
        )
        val result = scraper.getDetails(extension, "https://canonical.live/watch/matrix")
        assertTrue(result.isSuccess)
        val details = result.getOrThrow()
        assertEquals("Canonical Title", details.title)
        assertEquals(8.5, details.rating!!, 0.01)
        assertEquals("https://canonical.live/watch/matrix", scraper.lastDetailsUrl)
        assertEquals(ContentType.MOVIE, details.contentType)
    }

    @Test
    fun test06_mediaDetailsContract_contextAndDetailsRequestOverload() = runBlocking {
        val scraper = CanonicalReferenceTestScraper()
        val context = ExtensionContext(
            extensionId = "ext_ctx",
            scraperKey = "canonical_ref",
            baseUrl = "https://context.live",
            runtimeApiVersion = 1,
            connectivity = DefaultConnectivityMonitor { true },
            session = ExtractionSession(extensionId = "ext_ctx", scraperKey = "canonical_ref", targetPageUrl = "https://context.live")
        )
        val request = DetailsRequest(url = "https://context.live/movie/interstellar", id = "m_1")
        val result = scraper.getDetails(context, request)
        assertTrue(result.isSuccess)
        assertEquals("https://context.live/movie/interstellar", scraper.lastDetailsUrl)
        assertEquals("Canonical Title", result.getOrThrow().title)
    }

    // ==========================================
    // 4. EPISODES CONTRACT & NUMBERING
    // ==========================================

    @Test
    fun test07_episodesContract_seasonAndEpisodeIndexing() = runBlocking {
        val scraper = CanonicalReferenceTestScraper()
        val extension = ManagedExtension(
            id = "ext_ref",
            name = "Canonical Extension",
            baseUrl = "https://canonical.live",
            scraperKey = "canonical_ref",
            runtimeApiVersion = 1
        )
        val result = scraper.getEpisodes(extension, "https://canonical.live/series/dark", season = 2)
        assertTrue(result.isSuccess)
        val episodes = result.getOrThrow()
        assertEquals(2, episodes.size)
        assertEquals(1, episodes[0].episodeNumber)
        assertEquals(2, episodes[0].seasonNumber)
        assertEquals(2, episodes[1].episodeNumber)
        assertEquals(2, episodes[1].seasonNumber)
        assertEquals("https://canonical.live/series/dark", scraper.lastEpisodesSeriesUrl)
    }

    @Test
    fun test08_episodesContract_contextAndEpisodesRequestOverload() = runBlocking {
        val scraper = CanonicalReferenceTestScraper()
        val context = ExtensionContext(
            extensionId = "ext_ctx",
            scraperKey = "canonical_ref",
            baseUrl = "https://context.live",
            runtimeApiVersion = 1,
            connectivity = DefaultConnectivityMonitor { true },
            session = ExtractionSession(extensionId = "ext_ctx", scraperKey = "canonical_ref", targetPageUrl = "https://context.live")
        )
        val epRequest = EpisodesRequest(seriesUrl = "https://context.live/series/dark", season = 3)
        val result = scraper.getEpisodes(context, epRequest)
        assertTrue(result.isSuccess)
        val episodeList = result.getOrThrow()
        assertEquals(3, episodeList.seasonNumber)
        assertEquals(2, episodeList.episodes.size)
        assertEquals("https://context.live/series/dark", scraper.lastEpisodesSeriesUrl)
    }

    // ==========================================
    // 5. SERVER DISCOVERY CONTRACT
    // ==========================================

    @Test
    fun test09_serverDiscoveryContract_directVsEmbedClassification() = runBlocking {
        val scraper = CanonicalReferenceTestScraper()
        val extension = ManagedExtension(
            id = "ext_ref",
            name = "Canonical Extension",
            baseUrl = "https://canonical.live",
            scraperKey = "canonical_ref",
            runtimeApiVersion = 1
        )
        val session = ExtractionSession(extensionId = "ext_ref", scraperKey = "canonical_ref", targetPageUrl = "https://canonical.live/watch/matrix")
        val request = ServerDiscoveryRequest(targetUrl = "https://canonical.live/watch/matrix", mediaTitle = "The Matrix")

        val result = scraper.discoverServers(extension, session, request)
        assertTrue(result.isSuccess)
        val servers = result.getOrThrow().servers
        assertEquals(2, servers.size)

        // Direct Server
        val direct = servers.first { it.isDirectStream }
        assertEquals(ServerType.DIRECT, direct.serverType)
        assertFalse(direct.requiresWebView)
        assertEquals("Fast Direct CDN", direct.name)

        // Embed Server
        val embed = servers.first { !it.isDirectStream }
        assertEquals(ServerType.EMBED, embed.serverType)
        assertTrue(embed.requiresWebView)
        assertEquals("StreamHG Embed", embed.name)
    }

    @Test
    fun test10_serverDiscoveryContract_contextOverload() = runBlocking {
        val scraper = CanonicalReferenceTestScraper()
        val context = ExtensionContext(
            extensionId = "ext_ctx",
            scraperKey = "canonical_ref",
            baseUrl = "https://context.live",
            runtimeApiVersion = 1,
            connectivity = DefaultConnectivityMonitor { true },
            session = ExtractionSession(extensionId = "ext_ctx", scraperKey = "canonical_ref", targetPageUrl = "https://context.live/watch")
        )
        val request = ServerDiscoveryRequest(targetUrl = "https://context.live/watch/avatar")
        val result = scraper.discoverServers(context, request)
        assertTrue(result.isSuccess)
        assertEquals("https://context.live/watch/avatar", scraper.lastDiscoveryTargetUrl)
        assertEquals(2, result.getOrThrow().servers.size)
    }

    // ==========================================
    // 6. MEDIA EXTRACTION & STRICT QUALITY INVARIANT
    // ==========================================

    @Test
    fun test11_mediaExtractionContract_strictQualityInvariant() = runBlocking {
        val scraper = CanonicalReferenceTestScraper()
        val context = ExtensionContext(
            extensionId = "ext_ctx",
            scraperKey = "canonical_ref",
            baseUrl = "https://context.live",
            runtimeApiVersion = 1,
            connectivity = DefaultConnectivityMonitor { true },
            session = ExtractionSession(extensionId = "ext_ctx", scraperKey = "canonical_ref", targetPageUrl = "https://context.live/watch")
        )

        // Extraction for Direct 720p server
        val directServer = ServerItem(id = "s1", name = "Direct", link = "https://cdn.example.com/video.mp4", isDirectStream = true)
        val directResult = scraper.extractStream(context, ExtractionRequest(directServer))
        assertTrue(directResult.isSuccess)
        val directExtracted = directResult.getOrThrow()
        assertEquals(1, directExtracted.variants.size)
        assertEquals("720p", directExtracted.variants.first().displayQuality)
        assertEquals(StreamProtocol.DIRECT_FILE, directExtracted.playbackSource?.protocol)

        // Extraction for Embed/HLS server with unknown height -> MUST BE "Auto", NEVER "1080p"
        val embedServer = ServerItem(id = "s2", name = "Embed", link = "https://hgcloud.to/e/123", isDirectStream = false)
        val embedResult = scraper.extractStream(context, ExtractionRequest(embedServer))
        assertTrue(embedResult.isSuccess)
        val embedExtracted = embedResult.getOrThrow()
        assertEquals("Auto", embedExtracted.variants.first().displayQuality)
        assertNotEquals("1080p", embedExtracted.variants.first().displayQuality)
        assertEquals(StreamProtocol.HLS, embedExtracted.playbackSource?.protocol)
    }

    // ==========================================
    // 7. QUALITY PARSING UTILITY (MediaVariantParser)
    // ==========================================

    @Test
    fun test12_mediaVariantParser_convertsWithoutFabricating1080p() {
        val quality720 = M3U8Parser.QualityInfo("720p", "https://cdn.example.com/720/index.m3u8")
        val variant720 = MediaVariantParser.fromQualityInfo(quality720)
        assertEquals(720, variant720.height)
        assertEquals("720p", variant720.displayQuality)

        val quality1080 = M3U8Parser.QualityInfo("1080p", "https://cdn.example.com/1080/index.m3u8")
        val variant1080 = MediaVariantParser.fromQualityInfo(quality1080)
        assertEquals(1080, variant1080.height)
        assertEquals("1080p", variant1080.displayQuality)

        val qualityAuto = M3U8Parser.QualityInfo("Auto", "https://cdn.example.com/master.m3u8")
        val variantAuto = MediaVariantParser.fromQualityInfo(qualityAuto)
        assertNull(variantAuto.height)
        assertEquals("Auto", variantAuto.displayQuality)
        assertNotEquals("1080p", variantAuto.displayQuality)
    }

    // ==========================================
    // 8. ERROR CLASSIFICATION & RECOVERABILITY TAXONOMY
    // ==========================================

    @Test
    fun test13_errorTaxonomy_recoverabilityClassification() {
        val fallbackManager = FallbackManager()

        // Non-recoverable fatal errors (Stop pipeline immediately)
        val noInternet = ExtensionError.NoInternet()
        assertFalse("NoInternet must be fatal", noInternet.isRecoverable)
        assertFalse(fallbackManager.isRecoverable(noInternet))

        val incompatibleApp = ExtensionError.IncompatibleAppVersion(1, 2)
        assertFalse(incompatibleApp.isRecoverable)
        assertFalse(fallbackManager.isRecoverable(incompatibleApp))

        val incompatibleRuntime = ExtensionError.IncompatibleRuntime(1, 2)
        assertFalse(incompatibleRuntime.isRecoverable)
        assertFalse(fallbackManager.isRecoverable(incompatibleRuntime))

        val userDisabled = ExtensionError.UserDisabled("ext_1")
        assertFalse(userDisabled.isRecoverable)
        assertFalse(fallbackManager.isRecoverable(userDisabled))

        val securityViolation = ExtensionError.SecurityViolation("Blocked private host")
        assertFalse(securityViolation.isRecoverable)
        assertFalse(fallbackManager.isRecoverable(securityViolation))

        val invalidConfig = ExtensionError.InvalidConfiguration("Missing baseUrl")
        assertFalse(invalidConfig.isRecoverable)
        assertFalse(fallbackManager.isRecoverable(invalidConfig))

        // Recoverable errors (FallbackManager tries next candidate)
        val timeout = ExtensionError.Timeout(15000)
        assertTrue("Timeout must be recoverable", timeout.isRecoverable)
        assertTrue(fallbackManager.isRecoverable(timeout))

        val mediaNotFound = ExtensionError.MediaNotFound("query")
        assertTrue(mediaNotFound.isRecoverable)
        assertTrue(fallbackManager.isRecoverable(mediaNotFound))

        val extractionFailed = ExtensionError.ExtractionFailed("stream 404")
        assertTrue(extractionFailed.isRecoverable)
        assertTrue(fallbackManager.isRecoverable(extractionFailed))

        val parseError = ExtensionError.ParseError("Selector changed")
        assertTrue("ParseError must be recoverable", parseError.isRecoverable)
        assertTrue(fallbackManager.isRecoverable(parseError))

        val cloudflareChallenge = ExtensionError.CloudflareChallenge("Turnstile active")
        assertTrue(cloudflareChallenge.isRecoverable)
        assertTrue(fallbackManager.isRecoverable(cloudflareChallenge))

        val invalidMedia = ExtensionError.InvalidMedia("Codec unsupported")
        assertTrue(invalidMedia.isRecoverable)
        assertTrue(fallbackManager.isRecoverable(invalidMedia))
    }

    // ==========================================
    // 9. EXECUTION CONTEXT ENCAPSULATION
    // ==========================================

    @Test
    fun test14_extensionContextEncapsulation_andProjection() {
        val extension = ManagedExtension(
            id = "ext_123",
            name = "Test Extension",
            baseUrl = "https://example.com",
            scraperKey = "test_key",
            runtimeApiVersion = 2
        )
        val session = ExtractionSession(extensionId = "ext_123", scraperKey = "test_key", targetPageUrl = "https://example.com/watch")
        val monitor = DefaultConnectivityMonitor { true }

        val context = ExtensionContext.from(
            extension = extension,
            session = session,
            connectivity = monitor
        )

        assertEquals("ext_123", context.extensionId)
        assertEquals("test_key", context.scraperKey)
        assertEquals("https://example.com", context.baseUrl)
        assertEquals(2, context.runtimeApiVersion)
        assertTrue(context.connectivity.isConnected())
        assertEquals(NetworkState.AVAILABLE, context.connectivity.getNetworkState())

        val projectedExtension = context.toManagedExtension()
        assertEquals(context.extensionId, projectedExtension.id)
        assertEquals(context.scraperKey, projectedExtension.scraperKey)
        assertEquals(context.baseUrl, projectedExtension.baseUrl)
        assertEquals(context.runtimeApiVersion, projectedExtension.runtimeApiVersion)
    }

    // ==========================================
    // 10. HANDOFF SANITIZATION CONTRACTS
    // ==========================================

    @Test
    fun test15_downloaderHandoff_strictlySanitizesSessionCookies() {
        val dirtyTask = DownloadTaskRequest(
            id = "task_99",
            title = "Sanitization Test",
            downloadUrl = "https://cdn.example.com/file.mp4",
            headers = mapOf(
                "Cookie" to "session_token=SUPER_SECRET_COOKIE",
                "Set-Cookie" to "auth=SECRET",
                "User-Agent" to "CineStreamDownloader/1.0",
                "Referer" to "https://trusted.live"
            )
        )

        val downloaderInput = DownloaderHandoffAdapter.toDownloaderInput(dirtyTask)
        assertEquals("task_99", downloaderInput.taskId)
        assertEquals("Sanitization Test", downloaderInput.mediaTitle)
        assertEquals("https://cdn.example.com/file.mp4", downloaderInput.downloadUrl)
        assertNull("Cookie header MUST be stripped", downloaderInput.headers["Cookie"])
        assertNull("Set-Cookie header MUST be stripped", downloaderInput.headers["Set-Cookie"])
        assertEquals("CineStreamDownloader/1.0", downloaderInput.headers["User-Agent"])
        assertEquals("https://trusted.live", downloaderInput.headers["Referer"])
    }

    @Test
    fun test16_playerHandoff_producesCleanPlayerInput() {
        val playbackSource = PlaybackSource(
            streamUrl = "https://cdn.example.com/master.m3u8",
            mimeType = "application/x-mpegURL",
            protocol = StreamProtocol.HLS,
            variants = listOf(
                MediaVariant(url = "https://cdn.example.com/720.m3u8", height = 720, label = "720p")
            )
        )

        val playerInput = PlayerHandoffAdapter.toPlayerInput(
            playbackSource = playbackSource,
            serverName = "CDN 1",
            websiteName = "Canonical Site"
        )

        assertEquals("https://cdn.example.com/master.m3u8", playerInput.mediaUrl)
        assertEquals("CDN 1", playerInput.serverName)
        assertEquals("Canonical Site", playerInput.websiteName)
        assertEquals(StreamProtocol.HLS, playerInput.protocol)
        assertEquals(1, playerInput.qualities.size)
        assertEquals("720p", playerInput.qualities.first().label)
    }

    // ==========================================
    // 11. SECURITY SANDBOX POLICY CONTRACT
    // ==========================================

    @Test
    fun test17_trustedEmbedHostPolicy_blocksUnsafeSchemesAndPrivateHosts() {
        // Unsafe schemes
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("http://example.com/cleartext")))
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("file:///data/data/com.example/databases/db")))
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("content://media/external/images")))
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("javascript:alert(1)")))
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("intent://example.com")))

        // Private / SSRF targets
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("https://localhost/api")))
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("https://127.0.0.1/admin")))
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("https://192.168.1.1/router")))
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("https://10.0.0.1/internal")))
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("https://169.254.169.254/latest/meta-data")))
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("https://server.local/test")))
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("https://portal.internal/dashboard")))

        // Allowed targets
        assertTrue(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("https://hgcloud.to/e/1234")))
        assertTrue(TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("https://streamhg.to/embed-xyz")))
        assertTrue(TrustedEmbedHostPolicy.isNavigationAllowed(
            targetUri = Uri.parse("https://custom.live/video/1"),
            expectedOriginHost = "custom.live"
        ))
    }

    @Test
    fun test18_safeScraperBridge_enforcesOriginAndSizeLimits() {
        var extractionResultReceived: String? = null
        var reportedError: ExtensionError? = null

        val bridge = SafeScraperBridge(
            expectedOrigin = "https://trusted.live",
            listener = object : SafeScraperBridge.BridgeMessageListener {
                override fun onServerList(servers: List<ServerItem>) {}
                override fun onExtractionResult(streamUrl: String) {
                    extractionResultReceived = streamUrl
                }
                override fun onChallengeState(status: ChallengeStatus) {}
                override fun onError(error: ExtensionError) {
                    reportedError = error
                }
            }
        )

        // Oversized payload (> 64KB)
        val hugePayload = "{\"type\":\"EXTRACTION_RESULT\",\"streamUrl\":\"" + "x".repeat(66000) + "\"}"
        bridge.postMessage(hugePayload)
        assertNotNull("Payload exceeding 64KB must trigger error", reportedError)
        assertNull(extractionResultReceived)

        // Valid payload
        reportedError = null
        val validPayload = "{\"type\":\"EXTRACTION_RESULT\",\"streamUrl\":\"https://trusted.live/stream.m3u8\"}"
        bridge.postMessage(validPayload)
        assertNull(reportedError)
        assertEquals("https://trusted.live/stream.m3u8", extractionResultReceived)
    }

    // ==========================================
    // 12. STRICT MODULE DECOUPLING (STATIC ANALYSIS)
    // ==========================================

    @Test
    fun test19_strictDecoupling_zeroUiImportsInManagedPackage() {
        val rootDir = File("src/main/java/com/example/extension/managed")
        val altRootDir = File("app/src/main/java/com/example/extension/managed")
        val targetDir = if (rootDir.exists()) rootDir else altRootDir

        assertTrue("Managed directory must exist at ${targetDir.absolutePath}", targetDir.exists())

        val forbiddenUiImports = listOf(
            "com.example.ui.",
            "androidx.compose.",
            "android.app.Activity",
            "android.app.Dialog",
            "android.widget.Toast",
            "androidx.fragment.app.Fragment"
        )

        val violations = mutableListOf<String>()

        targetDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    for (forbidden in forbiddenUiImports) {
                        if (trimmed.contains(forbidden)) {
                            violations.add("${file.name}: $trimmed")
                        }
                    }
                }
            }
        }

        assertTrue(
            "com.example.extension.managed must NOT import UI or Compose. Violations found: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun test20_strictDecoupling_zeroFirebaseAndCloudinaryInScrapers() {
        val rootDir = File("src/main/java/com/example/extension/managed/scraper")
        val altRootDir = File("app/src/main/java/com/example/extension/managed/scraper")
        val targetDir = if (rootDir.exists()) rootDir else altRootDir

        if (targetDir.exists()) {
            val forbidden = listOf(
                "com.google.firebase",
                "com.cloudinary"
            )

            val violations = mutableListOf<String>()

            targetDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                file.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import ")) {
                        for (f in forbidden) {
                            if (trimmed.contains(f)) {
                                violations.add("${file.name}: $trimmed")
                            }
                        }
                    }
                }
            }

            assertTrue(
                "Scrapers must NOT import Firebase or Cloudinary. Violations found: $violations",
                violations.isEmpty()
            )
        }
    }

    @Test
    fun test21_strictDecoupling_zeroDynamicCodeLoading() {
        val rootDir = File("src/main/java/com/example/extension")
        val altRootDir = File("app/src/main/java/com/example/extension")
        val targetDir = if (rootDir.exists()) rootDir else altRootDir

        val forbiddenKeywords = listOf(
            "DexClassLoader",
            "PathClassLoader",
            "dalvik.system",
            "loadDex",
            "Class.forName"
        )

        val violations = mutableListOf<String>()

        targetDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                for (keyword in forbiddenKeywords) {
                    if (line.contains(keyword)) {
                        violations.add("${file.name}:${index + 1}: $line")
                    }
                }
            }
        }

        assertTrue(
            "Dynamic code loading / reflection is strictly prohibited in extensions. Violations: $violations",
            violations.isEmpty()
        )
    }

    // ==========================================
    // 13. FULL LIFECYCLE SIMULATED PIPELINE
    // ==========================================

    @Test
    fun test22_fullLifecycleSimulatedPipeline() = runBlocking {
        // Setup registry with Canonical Reference Scraper
        val scraper = CanonicalReferenceTestScraper()
        val scraperRegistry = ScraperRegistry(mapOf(scraper.scraperKey to scraper))

        val testExt = ManagedExtension(
            id = "sim_ext",
            name = "Simulated Extension",
            baseUrl = "https://simulated.live",
            scraperKey = scraper.scraperKey,
            runtimeApiVersion = 1,
            status = ExtensionLifecycleStatus.ACTIVE,
            userEnabled = true,
            priority = 100,
            contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES, ContentType.ANIME)
        )
        val extensionRegistry = ManagedExtensionRegistry(listOf(testExt))

        val connectivityMonitor = DefaultConnectivityMonitor { true }

        val runtime = DefaultControlledManagedExtensionRuntime(
            scraperRegistry = scraperRegistry,
            managedExtensionRegistry = extensionRegistry,
            connectivityMonitor = connectivityMonitor,
            currentAppVersionCode = 1L,
            supportedRuntimeApiVersion = 1
        )

        // 1. Search
        val searchOutcome = runtime.search(SearchRequest(query = "Interstellar"))
        assertTrue("Search must succeed", searchOutcome.isSuccess)
        val searchResult = searchOutcome.getOrThrow()
        assertEquals(1, searchResult.items.size)
        val mediaItem = searchResult.items.first()
        assertEquals("Result for Interstellar", mediaItem.title)

        // 2. Details
        val detailsOutcome = runtime.getDetails(testExt, mediaItem.url)
        assertTrue("Details must succeed", detailsOutcome.isSuccess)
        assertEquals("Canonical Title", detailsOutcome.getOrThrow().title)

        // 3. Episodes
        val episodesOutcome = runtime.getEpisodes(testExt, mediaItem.url, season = 1)
        assertTrue("Episodes must succeed", episodesOutcome.isSuccess)
        assertEquals(2, episodesOutcome.getOrThrow().size)

        // 4. Server Discovery
        val serverOutcome = runtime.discoverServers(ServerDiscoveryRequest(targetUrl = mediaItem.url))
        assertTrue("Server discovery must succeed", serverOutcome.isSuccess)
        val serverList = serverOutcome.getOrThrow()
        assertEquals(2, serverList.servers.size)

        // 5. Extraction
        val selectedServer = serverList.servers.first { it.isDirectStream }
        val extractionOutcome = runtime.extractStream(ExtractionRequest(selectedServer))
        assertTrue("Extraction must succeed", extractionOutcome.isSuccess)
        val extractionResult = extractionOutcome.getOrThrow()
        assertNotNull(extractionResult.playbackSource)
        assertNotNull(extractionResult.downloadSource)

        // 6. Player Handoff
        val playerInput = PlayerHandoffAdapter.toPlayerInput(
            playbackSource = extractionResult.playbackSource!!,
            serverName = selectedServer.name,
            websiteName = testExt.name
        )
        assertEquals("https://cdn.example.com/stream.mp4", playerInput.mediaUrl)
        assertEquals("Fast Direct CDN", playerInput.serverName)
        assertEquals("Simulated Extension", playerInput.websiteName)

        // 7. Downloader Handoff
        val downloaderInput = DownloaderHandoffAdapter.toDownloaderInput(
            downloadSource = extractionResult.downloadSource!!,
            taskId = "task_sim_1",
            mediaTitle = mediaItem.title
        )
        assertEquals("task_sim_1", downloaderInput.taskId)
        assertEquals("Result for Interstellar", downloaderInput.mediaTitle)
        assertEquals("https://cdn.example.com/stream.mp4", downloaderInput.downloadUrl)
    }
}
