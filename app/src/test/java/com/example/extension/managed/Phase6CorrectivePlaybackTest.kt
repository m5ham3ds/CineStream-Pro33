package com.example.extension.managed

import android.net.Uri
import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.model.*
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.runtime.DefaultControlledManagedExtensionRuntime
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.scraper.EgyDeadScraper
import com.example.extension.managed.web.TrustedEmbedHostPolicy
import com.example.extension.managed.web.WebExtractionEngine
import com.example.utils.ActiveTransferInfo
import com.example.utils.SavedPlaybackInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase6CorrectivePlaybackTest {

    // --- TEST 1: TrustedEmbedHostPolicy Validation ---
    @Test
    fun testTrustedEmbedHostPolicy_allowsTrustedHostsAndBlocksDisallowed() {
        // Known embed domains with HTTPS must be allowed
        val allowedEmbed1 = Uri.parse("https://hgcloud.to/e/abc123")
        assertTrue(
            "Known embed host hgcloud.to must be allowed",
            TrustedEmbedHostPolicy.isNavigationAllowed(allowedEmbed1, "egydead.live", "hgcloud.to")
        )

        val allowedEmbed2 = Uri.parse("https://sub.streamhg.to/play?id=xyz")
        assertTrue(
            "Subdomain of streamhg.to must be allowed",
            TrustedEmbedHostPolicy.isNavigationAllowed(allowedEmbed2)
        )

        val allowedOrigin = Uri.parse("https://tv10.egydead.live/watch/123")
        assertTrue(
            "Expected origin domain must be allowed",
            TrustedEmbedHostPolicy.isNavigationAllowed(allowedOrigin, "egydead.live")
        )

        // Non-HTTPS schemes must be blocked
        val httpUri = Uri.parse("http://hgcloud.to/e/abc")
        assertFalse("HTTP scheme must be blocked", TrustedEmbedHostPolicy.isNavigationAllowed(httpUri))

        val fileUri = Uri.parse("file:///android_asset/malicious.html")
        assertFalse("file:// scheme must be blocked", TrustedEmbedHostPolicy.isNavigationAllowed(fileUri))

        val javascriptUri = Uri.parse("javascript:alert(1)")
        assertFalse("javascript: scheme must be blocked", TrustedEmbedHostPolicy.isNavigationAllowed(javascriptUri))

        // Private IPs, loopback, and localhost must be blocked
        val localhostUri = Uri.parse("https://localhost:8080/test")
        assertFalse("localhost must be blocked", TrustedEmbedHostPolicy.isNavigationAllowed(localhostUri))

        val loopbackUri = Uri.parse("https://127.0.0.1/test")
        assertFalse("127.0.0.1 must be blocked", TrustedEmbedHostPolicy.isNavigationAllowed(loopbackUri))

        val privateIp10 = Uri.parse("https://10.0.0.1/test")
        assertFalse("10.x private IP must be blocked", TrustedEmbedHostPolicy.isNavigationAllowed(privateIp10))

        val privateIp192 = Uri.parse("https://192.168.1.100/test")
        assertFalse("192.168.x private IP must be blocked", TrustedEmbedHostPolicy.isNavigationAllowed(privateIp192))

        // Arbitrary unknown external domains must be blocked
        val unknownAd = Uri.parse("https://malicious-ad-tracker.com/popup")
        assertFalse("Unknown external domains must be blocked", TrustedEmbedHostPolicy.isNavigationAllowed(unknownAd, "egydead.live", "hgcloud.to"))
    }

    // --- TEST 2: WebEngine Provider Invocability in Runtime ---
    @Test
    fun testWebEngineProvider_isInvokedByRuntimeWhenExtractingStream() = runBlocking {
        var engineCreatedCount = 0
        var extractStreamUrlCalled = false

        val mockWebEngine = object : WebExtractionEngine {
            override suspend fun extractServers(targetUrl: String, script: String, timeoutMs: Long, expectedOrigin: String): Result<List<ServerItem>> {
                return Result.success(emptyList())
            }
            override suspend fun extractStreamUrl(targetUrl: String, targetServerId: String?, script: String, timeoutMs: Long, expectedOrigin: String): Result<String> {
                extractStreamUrlCalled = true
                return Result.success("https://hgcloud.to/media/stream.mp4")
            }
            override fun cancel() {}
        }

        val testScraper = object : BaseSiteScraper {
            override val scraperKey: String = "test_scraper"
            override val implementationVersion: Int = 1
            override val supportedCapabilities: Set<ScraperCapability> = setOf(ScraperCapability.SERVER_DISCOVERY, ScraperCapability.VIDEO_EXTRACTION)
            override val supportedContentTypes: Set<ContentType> = setOf(ContentType.MOVIE)
            override suspend fun search(extension: ManagedExtension, request: SearchRequest) = Result.failure<SearchResult>(Exception())
            override suspend fun getDetails(extension: ManagedExtension, url: String) = Result.failure<MediaDetailsResult>(Exception())
            override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int) = Result.failure<List<EpisodeItem>>(Exception())
            override suspend fun discoverServers(extension: ManagedExtension, session: ExtractionSession, request: ServerDiscoveryRequest, webEngine: WebExtractionEngine?) = Result.failure<ServerDiscoveryResult>(Exception())
            override suspend fun extractStream(extension: ManagedExtension, session: ExtractionSession, request: ExtractionRequest, webEngine: WebExtractionEngine?): Result<ExtractionResult> {
                assertNotNull("WebEngine must be passed to scraper", webEngine)
                val res = webEngine!!.extractStreamUrl(request.serverItem.link, request.serverItem.id, "", 5000L, extension.baseUrl)
                return Result.success(
                    ExtractionResult(
                        playbackSource = PlaybackSource(
                            streamUrl = res.getOrThrow(),
                            headers = emptyMap(),
                            mimeType = "video/mp4"
                        )
                    )
                )
            }
        }

        val scraperReg = ScraperRegistry(mapOf("test_scraper" to testScraper))
        val extReg = ManagedExtensionRegistry()
        val extension = ManagedExtension(
            id = "test_ext",
            name = "Test Ext",
            scraperKey = "test_scraper",
            baseUrl = "https://test.live",
            runtimeApiVersion = 1,
            contentTypes = setOf(ContentType.MOVIE),
            status = ExtensionLifecycleStatus.ACTIVE
        )
        extReg.setExtensions(listOf(extension))

        val runtime = DefaultControlledManagedExtensionRuntime(
            scraperRegistry = scraperReg,
            managedExtensionRegistry = extReg,
            webEngineProvider = {
                engineCreatedCount++
                mockWebEngine
            }
        )

        val serverItem = ServerItem(id = "srv1", name = "Embed Server", link = "https://test.live/embed/123", isDirectStream = false)
        val extractionResult = runtime.extractStream(ExtractionRequest(serverItem = serverItem))

        assertTrue("Extraction should succeed", extractionResult.isSuccess)
        assertTrue("WebEngine extractStreamUrl must have been called", extractStreamUrlCalled)
        assertEquals(1, engineCreatedCount)
        assertEquals("https://hgcloud.to/media/stream.mp4", extractionResult.getOrThrow().playbackSource?.streamUrl)
    }

    // --- TEST 3: EgyDeadScraper Direct Stream Extraction ---
    @Test
    fun testEgyDeadScraper_directMediaStreamDoesNotFabricateQualities() = runBlocking {
        val scraper = EgyDeadScraper()
        val extension = ManagedExtension(
            id = "egydead",
            name = "EgyDead",
            scraperKey = "egydead",
            baseUrl = "https://tv10.egydead.live",
            runtimeApiVersion = 1,
            contentTypes = setOf(ContentType.MOVIE),
            status = ExtensionLifecycleStatus.ACTIVE
        )
        val session = ExtractionSession(
            extensionId = extension.id,
            scraperKey = extension.scraperKey,
            targetPageUrl = extension.baseUrl
        )

        // Direct MP4
        val mp4Item = ServerItem(id = "mp4_srv", name = "MP4 Direct", link = "https://storage.provider.com/video.mp4", isDirectStream = true)
        val mp4Result = scraper.extractStream(extension, session, ExtractionRequest(serverItem = mp4Item), null)

        assertTrue(mp4Result.isSuccess)
        val mp4Source = mp4Result.getOrThrow().playbackSource
        assertNotNull(mp4Source)
        assertEquals("video/mp4", mp4Source?.mimeType)
        // Ensure no fabricated "1080p" is forced
        assertTrue("Direct MP4 must not have fabricated qualities", mp4Source?.qualities.isNullOrEmpty())
    }

    // --- TEST 4: Quality Defaults are Evidence-Based (Auto) ---
    @Test
    fun testQualityDefaults_areAutoNotFabricated1080p() {
        val savedInfo = SavedPlaybackInfo(
            mediaId = "m1",
            url = "https://example.com/stream.mp4"
        )
        assertEquals("SavedPlaybackInfo default quality must be Auto", "Auto", savedInfo.quality)

        val activeTransfer = ActiveTransferInfo(
            id = "t1",
            title = "Test Media"
        )
        assertEquals("ActiveTransferInfo default quality must be Auto", "Auto", activeTransfer.quality)
    }
}
