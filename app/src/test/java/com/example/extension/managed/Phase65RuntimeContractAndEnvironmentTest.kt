package com.example.extension.managed

import android.net.Uri
import com.example.extension.managed.adapter.DownloaderHandoffAdapter
import com.example.extension.managed.adapter.PlayerHandoffAdapter
import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.runtime.CredentialProjector
import com.example.extension.managed.runtime.DefaultControlledManagedExtensionRuntime
import com.example.extension.managed.runtime.ExtensionTimeoutDefaults
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.runtime.challenge.ChallengeHandler
import com.example.extension.managed.runtime.challenge.ChallengeResult
import com.example.extension.managed.runtime.context.ExtensionContext
import com.example.extension.managed.runtime.network.ConnectivityMonitor
import com.example.extension.managed.runtime.network.DefaultConnectivityMonitor
import com.example.extension.managed.runtime.network.NetworkState
import com.example.extension.managed.web.SafeScraperBridge
import com.example.extension.managed.web.TrustedEmbedHostPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase65RuntimeContractAndEnvironmentTest {

    // ==========================================
    // 1. CONTRACT TESTS
    // ==========================================

    @Test
    fun test01_searchContract_normalizedAndLanguage() {
        val request = SearchRequest(query = "Interstellar", contentType = ContentType.MOVIE, page = 1, language = "ar")
        assertEquals("Interstellar", request.query)
        assertEquals(ContentType.MOVIE, request.contentType)
        assertEquals(1, request.page)
        assertEquals("ar", request.language)

        val item = SearchMediaItem(
            id = "item_1",
            title = "Interstellar",
            posterUrl = "https://example.com/poster.jpg",
            url = "https://example.com/movie/interstellar",
            contentType = ContentType.MOVIE,
            year = "2014"
        )
        val result = SearchResult(items = listOf(item), page = 1, hasNextPage = false, metadata = mapOf("total" to "1"))
        assertEquals(1, result.items.size)
        assertEquals("Interstellar", result.items.first().title)
        assertEquals("1", result.metadata["total"])
    }

    @Test
    fun test02_detailsAndEpisodesContract_normalized() {
        val detailsRequest = DetailsRequest(url = "https://example.com/movie/interstellar", id = "m1")
        assertEquals("https://example.com/movie/interstellar", detailsRequest.url)

        val detailsResult = MediaDetailsResult(
            id = "m1",
            title = "Interstellar",
            description = "A team of explorers travel through a wormhole in space.",
            posterUrl = "https://example.com/poster.jpg",
            bannerUrl = "https://example.com/banner.jpg",
            contentType = ContentType.MOVIE,
            url = "https://example.com/movie/interstellar",
            year = "2014",
            rating = 8.7,
            runtime = "169 min"
        )
        assertEquals("Interstellar", detailsResult.title)
        assertEquals(8.7, detailsResult.rating!!, 0.01)

        val epRequest = EpisodesRequest(seriesUrl = "https://example.com/series/stranger-things", season = 1)
        val episode = EpisodeItem(
            id = "ep1",
            title = "Chapter One: The Vanishing of Will Byers",
            episodeNumber = 1,
            seasonNumber = 1,
            url = "https://example.com/watch/ep1"
        )
        val epList = EpisodeList(episodes = listOf(episode), seasonNumber = 1)
        assertEquals(1, epList.episodes.size)
        assertEquals(1, epList.seasonNumber)
    }

    @Test
    fun test03_serverDiscoveryContract_normalized() {
        val server = ServerItem(
            id = "srv1",
            name = "StreamHG",
            link = "https://hgcloud.to/e/123",
            isDirectStream = false
        )
        assertEquals("srv1", server.id)
        assertEquals(ServerType.EMBED, server.serverType)
        assertTrue(server.requiresWebView)
        assertEquals("https://hgcloud.to/e/123", server.sourceUrl)

        val directServer = ServerItem(
            id = "srv2",
            name = "Direct MP4",
            link = "https://cdn.example.com/video.mp4",
            isDirectStream = true
        )
        assertEquals(ServerType.DIRECT, directServer.serverType)
        assertFalse(directServer.requiresWebView)

        val discoveryResult = ServerDiscoveryResult(
            servers = listOf(server, directServer),
            sourcePageUrl = "https://example.com/watch/interstellar"
        )
        assertEquals(2, discoveryResult.servers.size)
    }

    @Test
    fun test04_mediaVariantAndQuality_neverFabricates1080p() {
        // Discovered 720p variant
        val variant720 = MediaVariant(
            url = "https://cdn.example.com/720.m3u8",
            width = 1280,
            height = 720,
            bitrate = 2500000L,
            protocol = StreamProtocol.HLS
        )
        assertEquals("720p", variant720.displayQuality)
        val qSource720 = variant720.toQualitySource()
        assertEquals("720p", qSource720.label)
        assertEquals("1280x720", qSource720.resolution)

        // Discovered variant with unknown height and no label -> MUST BE "Auto", NEVER "1080p"
        val unknownVariant = MediaVariant(
            url = "https://cdn.example.com/master.m3u8",
            protocol = StreamProtocol.HLS
        )
        assertEquals("Auto", unknownVariant.displayQuality)
        assertNotEquals("1080p", unknownVariant.displayQuality)

        val qualityFromUnknown = unknownVariant.toQualitySource()
        assertEquals("Auto", qualityFromUnknown.label)
        assertNull(qualityFromUnknown.resolution)
    }

    @Test
    fun test05_mediaVariantParser_fromQualityInfo() {
        val qualityInfo = com.example.utils.M3U8Parser.QualityInfo("720p", "https://cdn.example.com/720/index.m3u8")
        val variant = MediaVariantParser.fromQualityInfo(qualityInfo)

        assertEquals(720, variant.height)
        assertEquals("720p", variant.label)
        assertEquals("https://cdn.example.com/720/index.m3u8", variant.url)
        assertEquals(StreamProtocol.HLS, variant.protocol)

        val autoQuality = com.example.utils.M3U8Parser.QualityInfo("Auto", "https://cdn.example.com/master.m3u8")
        val autoVariant = MediaVariantParser.fromQualityInfo(autoQuality)
        assertNull(autoVariant.height)
        assertTrue(autoVariant.isDefault)
        assertEquals("Auto", autoVariant.displayQuality)
    }

    @Test
    fun test06_playbackAndDownloadSources_normalized() {
        val playback = PlaybackSource(
            streamUrl = "https://cdn.example.com/stream.m3u8",
            mimeType = "application/x-mpegURL",
            protocol = StreamProtocol.HLS,
            variants = listOf(
                MediaVariant(url = "https://cdn.example.com/720.m3u8", height = 720),
                MediaVariant(url = "https://cdn.example.com/480.m3u8", height = 480)
            )
        )
        assertEquals(2, playback.qualities.size)
        assertEquals("720p", playback.qualities[0].label)
        assertEquals("480p", playback.qualities[1].label)

        val download = DownloadSource(
            url = "https://cdn.example.com/movie.mp4",
            filename = "movie.mp4",
            mimeType = "video/mp4",
            protocol = StreamProtocol.DIRECT_FILE,
            estimatedBytes = 1024L * 1024L * 500L
        )
        assertEquals(StreamProtocol.DIRECT_FILE, download.protocol)
        assertEquals(500L * 1024L * 1024L, download.estimatedBytes)
    }

    // ==========================================
    // 2. SECURITY TESTS
    // ==========================================

    @Test
    fun test07_trustedEmbedHostPolicy_enforcesHttpsAndRejectsUnsafeSchemes() {
        assertFalse("HTTP must be rejected", TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("http://hgcloud.to/e/123")))
        assertFalse("file:// must be rejected", TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("file:///android_asset/exploit.html")))
        assertFalse("content:// must be rejected", TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("content://media/external/images/media/1")))
        assertFalse("javascript: must be rejected", TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("javascript:alert(1)")))
        assertFalse("intent: must be rejected", TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("intent://example.com/#Intent;action=android.intent.action.VIEW;end")))
    }

    @Test
    fun test08_trustedEmbedHostPolicy_rejectsLocalhostAndPrivateIps() {
        val forbiddenUrls = listOf(
            "https://localhost/exploit",
            "https://127.0.0.1/admin",
            "https://127.0.1.1/internal",
            "https://0.0.0.0/test",
            "https://10.0.0.1/router",
            "https://192.168.1.1/setup",
            "https://172.16.0.1/gateway",
            "https://172.31.255.255/intranet",
            "https://169.254.169.254/metadata",
            "https://device.local/service",
            "https://corp.internal/secret"
        )
        for (url in forbiddenUrls) {
            val uri = Uri.parse(url)
            assertFalse("Private host pattern '$url' must be blocked", TrustedEmbedHostPolicy.isNavigationAllowed(uri))
        }
    }

    @Test
    fun test09_trustedEmbedHostPolicy_allowsTrustedHostsAndExpectedOrigin() {
        assertTrue("hgcloud.to must be allowed", TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("https://hgcloud.to/e/123")))
        assertTrue("streamhg.to must be allowed", TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("https://streamhg.to/embed-abc.html")))
        assertTrue("Expected origin host must be allowed", TrustedEmbedHostPolicy.isNavigationAllowed(
            targetUri = Uri.parse("https://tv10.egydead.live/watch/123"),
            expectedOriginHost = "tv10.egydead.live"
        ))
        assertFalse("Unknown random domain must be rejected", TrustedEmbedHostPolicy.isNavigationAllowed(Uri.parse("https://untrusted-ad-network.com/banner")))
    }

    @Test
    fun test10_safeScraperBridge_messageValidation() {
        var errorReported: ExtensionError? = null
        var streamExtracted: String? = null

        val bridge = SafeScraperBridge(
            expectedOrigin = "https://trusted.com",
            listener = object : SafeScraperBridge.BridgeMessageListener {
                override fun onServerList(servers: List<ServerItem>) {}
                override fun onExtractionResult(streamUrl: String) {
                    streamExtracted = streamUrl
                }
                override fun onChallengeState(status: ChallengeStatus) {}
                override fun onError(error: ExtensionError) {
                    errorReported = error
                }
            }
        )

        // Null message -> error
        bridge.postMessage(null)
        assertNotNull(errorReported)

        // Oversized message > 64KB -> error
        errorReported = null
        val hugeMessage = "{\"type\":\"EXTRACTION_RESULT\",\"streamUrl\":\"" + "a".repeat(70000) + "\"}"
        bridge.postMessage(hugeMessage)
        assertNotNull("Oversized payload must be rejected", errorReported)

        // Valid message
        errorReported = null
        val validMessage = "{\"type\":\"EXTRACTION_RESULT\",\"streamUrl\":\"https://trusted.com/video.m3u8\"}"
        bridge.postMessage(validMessage)
        assertNull(errorReported)
        assertEquals("https://trusted.com/video.m3u8", streamExtracted)
    }

    @Test
    fun test11_credentialProjector_minimalExposure() {
        val sessionHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Referer" to "https://trusted.com/watch",
            "X-Internal-Token" to "SUPER_SECRET_TOKEN",
            "Authorization" to "Bearer 123456"
        )
        val sessionCookies = mapOf(
            "cf_clearance" to "cf_token_val",
            "session_id" to "private_session_id"
        )

        // Standard projection without explicitly requesting internal secrets
        val projected = CredentialProjector.projectHeaders(
            sessionHeaders = sessionHeaders,
            sessionCookies = sessionCookies,
            requiredHeaderKeys = emptySet(),
            requiredCookieKeys = emptySet()
        )

        assertEquals("Mozilla/5.0", projected["User-Agent"])
        assertEquals("https://trusted.com/watch", projected["Referer"])
        assertNull("Internal token must not be projected", projected["X-Internal-Token"])
        assertNull("Authorization header must not be projected", projected["Authorization"])
        assertNull("Cookies must not be projected when not explicitly requested", projected["Cookie"])
    }

    @Test
    fun test12_downloaderHandoff_stripsCookies() {
        val taskWithCookies = DownloadTaskRequest(
            id = "task1",
            title = "Movie",
            downloadUrl = "https://cdn.example.com/movie.mp4",
            headers = mapOf(
                "Cookie" to "session=secret123",
                "User-Agent" to "ExoPlayer/2.0",
                "Set-Cookie" to "auth=456"
            )
        )
        val input = DownloaderHandoffAdapter.toDownloaderInput(taskWithCookies)
        assertEquals("task1", input.taskId)
        assertEquals("Movie", input.mediaTitle)
        assertEquals("https://cdn.example.com/movie.mp4", input.downloadUrl)
        assertNull("Cookie header must be stripped", input.headers["Cookie"])
        assertNull("Set-Cookie header must be stripped", input.headers["Set-Cookie"])
        assertEquals("ExoPlayer/2.0", input.headers["User-Agent"])

        val sourceWithCookies = DownloadSource(
            url = "https://cdn.example.com/file.mp4",
            headers = mapOf("Cookie" to "bad=token", "Referer" to "https://cdn.example.com")
        )
        val inputFromSource = DownloaderHandoffAdapter.toDownloaderInput(sourceWithCookies, "task2", "Title")
        assertNull("Cookie header must be stripped from DownloadSource", inputFromSource.headers["Cookie"])
        assertEquals("https://cdn.example.com", inputFromSource.headers["Referer"])
    }

    @Test
    fun test13_playerHandoff_cleanInputModel() {
        val playbackSource = PlaybackSource(
            streamUrl = "https://cdn.example.com/master.m3u8",
            mimeType = "application/x-mpegURL",
            protocol = StreamProtocol.HLS,
            variants = listOf(
                MediaVariant(url = "https://cdn.example.com/720.m3u8", height = 720)
            )
        )
        val playerInput = PlayerHandoffAdapter.toPlayerInput(
            playbackSource = playbackSource,
            serverName = "Server 1",
            websiteName = "Managed Source"
        )
        assertEquals("https://cdn.example.com/master.m3u8", playerInput.mediaUrl)
        assertEquals("Server 1", playerInput.serverName)
        assertEquals("Managed Source", playerInput.websiteName)
        assertEquals(StreamProtocol.HLS, playerInput.protocol)
        assertEquals(1, playerInput.variants.size)
        assertEquals("720p", playerInput.variants.first().displayQuality)
    }

    // ==========================================
    // 3. RUNTIME & CONTEXT TESTS
    // ==========================================

    @Test
    fun test14_extensionContext_encapsulation() {
        val extension = ManagedExtension(
            id = "ext_1",
            name = "Test Extension",
            baseUrl = "https://test.live",
            scraperKey = "test_key",
            runtimeApiVersion = 1
        )
        val session = ExtractionSession(extensionId = "ext_1", scraperKey = "test_key", targetPageUrl = "https://test.live/watch")
        val monitor = DefaultConnectivityMonitor { true }

        val context = ExtensionContext.from(
            extension = extension,
            session = session,
            connectivity = monitor
        )

        assertEquals("ext_1", context.extensionId)
        assertEquals("test_key", context.scraperKey)
        assertEquals("https://test.live", context.baseUrl)
        assertEquals(1, context.runtimeApiVersion)
        assertTrue(context.connectivity.isConnected())
        assertEquals(NetworkState.AVAILABLE, context.connectivity.getNetworkState())
        assertEquals("ext_1", context.session.extensionId)
        assertNotNull(context.hostPolicy)
    }

    @Test
    fun test15_connectivityMonitor_andFastFail() = runBlocking {
        val offlineMonitor = DefaultConnectivityMonitor { false }
        assertFalse(offlineMonitor.isConnected())
        assertEquals(NetworkState.UNAVAILABLE, offlineMonitor.getNetworkState())

        val runtime = DefaultControlledManagedExtensionRuntime(
            connectivityMonitor = offlineMonitor
        )

        val searchResult = runtime.search(SearchRequest("test"))
        assertTrue("Must fail when offline", searchResult.isFailure)
        val errorMsg = searchResult.exceptionOrNull()?.message ?: ""
        assertTrue("Error must indicate internet failure", errorMsg.contains("internet", ignoreCase = true))

        val serverResult = runtime.discoverServers(ServerDiscoveryRequest("https://test.com/watch"))
        assertTrue("Must fail when offline", serverResult.isFailure)
        val serverErrorMsg = serverResult.exceptionOrNull()?.message ?: ""
        assertTrue("Server error must indicate internet failure", serverErrorMsg.contains("internet", ignoreCase = true))
    }

    @Test
    fun test16_challengeHandler_contract() = runBlocking {
        val mockHandler = object : ChallengeHandler {
            override suspend fun handleChallenge(
                targetUrl: String,
                status: ChallengeStatus,
                timeoutMs: Long
            ): Result<ChallengeResult> {
                return Result.success(
                    ChallengeResult(
                        status = ChallengeStatus.SOLVED,
                        resolvedCookies = mapOf("cf_clearance" to "token_123"),
                        userAgent = "Mozilla/5.0"
                    )
                )
            }
        }

        val result = mockHandler.handleChallenge("https://test.com", ChallengeStatus.DETECTED)
        assertTrue(result.isSuccess)
        val challengeResult = result.getOrThrow()
        assertEquals(ChallengeStatus.SOLVED, challengeResult.status)
        assertEquals("token_123", challengeResult.resolvedCookies["cf_clearance"])
    }

    @Test
    fun test17_extensionTimeoutDefaults() {
        assertEquals(15000L, ExtensionTimeoutDefaults.SEARCH_TIMEOUT_MS)
        assertEquals(15000L, ExtensionTimeoutDefaults.DETAILS_TIMEOUT_MS)
        assertEquals(15000L, ExtensionTimeoutDefaults.EPISODES_TIMEOUT_MS)
        assertEquals(15000L, ExtensionTimeoutDefaults.SERVER_DISCOVERY_TIMEOUT_MS)
        assertEquals(15000L, ExtensionTimeoutDefaults.WEB_EXTRACTION_TIMEOUT_MS)
        assertEquals(15000L, ExtensionTimeoutDefaults.STREAM_EXTRACTION_TIMEOUT_MS)
        assertEquals(15000L, ExtensionTimeoutDefaults.CHALLENGE_TIMEOUT_MS)
    }

    @Test
    fun test18_extensionError_newStandardizedTypes() {
        val configError = ExtensionError.InvalidConfiguration("Missing base url")
        assertEquals("INVALID_CONFIGURATION", configError.code)
        assertFalse("InvalidConfiguration is fatal", configError.isRecoverable)

        val mediaError = ExtensionError.InvalidMedia("Codec unsupported")
        assertEquals("INVALID_MEDIA", mediaError.code)
        assertTrue(mediaError.isRecoverable)

        val secError = ExtensionError.SecurityViolation("Navigation to private IP blocked")
        assertEquals("SECURITY_VIOLATION", secError.code)
        assertFalse("SecurityViolation is fatal", secError.isRecoverable)
    }

    // ==========================================
    // 4. ARCHITECTURE TESTS
    // ==========================================

    @Test
    fun test19_verifyManagedPackageHasZeroUiDependencies() {
        val rootDir = File("src/main/java/com/example/extension/managed")
        val altRootDir = File("app/src/main/java/com/example/extension/managed")
        val targetDir = if (rootDir.exists()) rootDir else altRootDir

        assertTrue("Managed directory must exist at: ${targetDir.absolutePath}", targetDir.exists())

        val forbiddenUiImports = listOf(
            "com.example.ui.",
            "androidx.compose.ui.",
            "androidx.compose.material3.",
            "android.app.Activity",
            "android.app.Dialog",
            "androidx.fragment.app.Fragment"
        )

        val violatingFiles = mutableListOf<String>()

        targetDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    for (forbidden in forbiddenUiImports) {
                        if (trimmed.contains(forbidden)) {
                            violatingFiles.add("${file.name}: $trimmed")
                        }
                    }
                }
            }
        }

        assertTrue(
            "Managed package must NOT import UI or Compose classes. Violations found: $violatingFiles",
            violatingFiles.isEmpty()
        )
    }

    @Test
    fun test20_verifyScrapersDoNotImportFirebaseOrCloudinary() {
        val rootDir = File("src/main/java/com/example/extension/managed/scraper")
        val altRootDir = File("app/src/main/java/com/example/extension/managed/scraper")
        val targetDir = if (rootDir.exists()) rootDir else altRootDir

        if (targetDir.exists()) {
            val forbiddenImports = listOf(
                "com.google.firebase",
                "com.cloudinary"
            )

            val violatingFiles = mutableListOf<String>()

            targetDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
                val lines = file.readLines()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import ")) {
                        for (forbidden in forbiddenImports) {
                            if (trimmed.contains(forbidden)) {
                                violatingFiles.add("${file.name}: $trimmed")
                            }
                        }
                    }
                }
            }

            assertTrue(
                "Scrapers must NOT import Firebase or Cloudinary. Violations found: $violatingFiles",
                violatingFiles.isEmpty()
            )
        }
    }

    @Test
    fun test21_verifyNoDynamicCodeLoading() {
        val rootDir = File("src/main/java/com/example/extension")
        val altRootDir = File("app/src/main/java/com/example/extension")
        val targetDir = if (rootDir.exists()) rootDir else altRootDir

        assertTrue("Extension directory must exist", targetDir.exists())

        val forbiddenKeywords = listOf(
            "DexClassLoader",
            "PathClassLoader",
            "dalvik.system",
            "loadDex",
            "Class.forName"
        )

        val violations = mutableListOf<String>()

        targetDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            for ((idx, line) in lines.withIndex()) {
                for (keyword in forbiddenKeywords) {
                    if (line.contains(keyword)) {
                        violations.add("${file.name}:${idx + 1}: $line")
                    }
                }
            }
        }

        assertTrue(
            "Dynamic code loading / reflection is strictly prohibited in managed extensions. Found: $violations",
            violations.isEmpty()
        )
    }
}
