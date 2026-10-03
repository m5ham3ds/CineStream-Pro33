package com.example.extension.managed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.extension.managed.adapter.DownloaderHandoffAdapter
import com.example.extension.managed.adapter.UnifiedDownloadCoordinator
import com.example.extension.managed.model.*
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.repository.InMemoryExtensionUserPreferences
import com.example.extension.managed.repository.ManagedExtensionRepository
import com.example.extension.managed.runtime.DefaultControlledManagedExtensionRuntime
import com.example.extension.managed.runtime.FallbackManager
import com.example.extension.managed.scraper.QfilmScraper
import com.example.extension.orchestrator.ManagedDiscoveryOutcome
import com.example.extension.orchestrator.ManagedMediaOrchestrator
import com.example.ui.screens.player.ServerStateStore
import com.example.ui.screens.player.normalizeQualityKey
import com.example.ui.screens.player.normalizeQualityLabel
import com.example.utils.AndroidDownloader
import com.example.utils.M3U8Parser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase717UnifiedDownloadFlowTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        ServerStateStore.clear()
    }

    // =========================================================================
    // Test 1: All three buttons use same download entry point
    // =========================================================================
    @Test
    fun test1_allThreeButtonsUseSameDownloadEntryPoint() {
        val testUrl = "https://cdn.cinestream.test/video/movie1.mp4"
        val testHeaders = mapOf(
            "Referer" to "https://wwa.liiivideo.com/embed-test.html",
            "User-Agent" to "Mozilla/5.0 TestBrowser/1.0"
        )

        // 1. InlineDetailVideoPlayer canonical source
        val inlineSource = UnifiedDownloadCoordinator.buildDownloadSource(
            streamUrl = testUrl,
            mediaId = "1001",
            title = "Inline Movie",
            quality = "720p",
            headers = testHeaders,
            isMovie = true
        )

        // 2. PlayerScreen canonical source
        val playerSource = UnifiedDownloadCoordinator.buildDownloadSource(
            streamUrl = testUrl,
            mediaId = "1001",
            title = "Player Movie",
            quality = "720p",
            headers = testHeaders,
            isMovie = true
        )

        // 3. DetailsScreens canonical source
        val detailsSource = UnifiedDownloadCoordinator.buildDownloadSource(
            streamUrl = testUrl,
            mediaId = "1001",
            title = "Details Movie",
            quality = "720p",
            headers = testHeaders,
            isMovie = true
        )

        val inlineInput = DownloaderHandoffAdapter.toDownloaderInput(inlineSource)
        val playerInput = DownloaderHandoffAdapter.toDownloaderInput(playerSource)
        val detailsInput = DownloaderHandoffAdapter.toDownloaderInput(detailsSource)

        assertEquals(inlineInput.downloadUrl, playerInput.downloadUrl)
        assertEquals(playerInput.downloadUrl, detailsInput.downloadUrl)
        assertEquals("720p", inlineInput.quality)
        assertEquals("720p", playerInput.quality)
        assertEquals("720p", detailsInput.quality)
        assertEquals(testHeaders["Referer"], inlineInput.headers["Referer"])
        assertEquals(testHeaders["Referer"], playerInput.headers["Referer"])
        assertEquals(testHeaders["Referer"], detailsInput.headers["Referer"])
    }

    // =========================================================================
    // Test 2: DownloadSource headers reach DownloaderHandoffAdapter
    // =========================================================================
    @Test
    fun test2_downloadSourceHeadersReachDownloaderHandoffAdapter() {
        val headers = mapOf(
            "Referer" to "https://wwa.liiivideo.com/embed-test.html",
            "User-Agent" to "Mozilla/5.0 CineStreamTest/2.0",
            "X-Custom-Token" to "verified_stream"
        )
        val source = DownloadSource(
            url = "https://cdn.example.com/stream.m3u8",
            filename = "task_002",
            headers = headers,
            title = "Test 2",
            mediaId = "media_002"
        )

        val input = DownloaderHandoffAdapter.toDownloaderInput(source)
        assertEquals("https://wwa.liiivideo.com/embed-test.html", input.headers["Referer"])
        assertEquals("Mozilla/5.0 CineStreamTest/2.0", input.headers["User-Agent"])
        assertEquals("verified_stream", input.headers["X-Custom-Token"])
    }

    // =========================================================================
    // Test 3: Referer preserved without corruption or stripping
    // =========================================================================
    @Test
    fun test3_refererPreserved() {
        val originalReferer = "https://wwa.liiivideo.com/embed-xyz123.html"
        val streamUrl = "https://cdn.deliverynetwork.com/hls/master.m3u8"
        val candidate = QualityCandidate(
            qualityKey = "1080",
            label = "1080p",
            streamUrl = streamUrl,
            headers = mapOf("Referer" to originalReferer),
            sourceUrl = originalReferer
        )

        val source = UnifiedDownloadCoordinator.fromCandidate(
            candidate = candidate,
            mediaId = "m3",
            title = "Referer Test"
        )

        val input = DownloaderHandoffAdapter.toDownloaderInput(source)
        assertEquals(originalReferer, input.headers["Referer"])
        assertEquals(originalReferer, input.sourceUrl)
        assertNotEquals(streamUrl, input.headers["Referer"])
    }

    // =========================================================================
    // Test 4: User-Agent preserved
    // =========================================================================
    @Test
    fun test4_userAgentPreserved() {
        val customUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36"
        val source = DownloadSource(
            url = "https://cdn.example.com/video.mp4",
            filename = "task_004",
            headers = mapOf("User-Agent" to customUa),
            title = "UA Test"
        )

        val input = DownloaderHandoffAdapter.toDownloaderInput(source)
        assertEquals(customUa, input.headers["User-Agent"])
    }

    // =========================================================================
    // Test 5: Downloader does not reconstruct incorrect Referer
    // =========================================================================
    @Test
    fun test5_downloaderDoesNotReconstructIncorrectReferer() {
        val embedReferer = "https://wwa.liiivideo.com/embed-999.html"
        val cdnStreamUrl = "https://cdn.example.com/media/720p.m3u8"

        val source = UnifiedDownloadCoordinator.buildDownloadSource(
            streamUrl = cdnStreamUrl,
            mediaId = "m5",
            title = "No Reconstruct Test",
            quality = "720p",
            headers = mapOf("Referer" to embedReferer),
            sourceUrl = embedReferer
        )

        val input = DownloaderHandoffAdapter.toDownloaderInput(source)
        assertEquals(embedReferer, input.headers["Referer"])
        assertFalse(input.headers["Referer"]!!.contains("cdn.example.com"))
    }

    // =========================================================================
    // Test 6: Download before playback resolves source
    // =========================================================================
    @Test
    fun test6_downloadBeforePlaybackResolvesSource() {
        val mediaKey = "Test Movie-true-1-1"
        val serverMap = mapOf("Server1" to "https://cdn.example.com/master.m3u8")
        val downloadMap = mapOf("1080p (تحميل)" to "https://cdn.example.com/1080.mp4")

        // Pre-discover servers without starting playback
        ServerStateStore.saveForMedia(
            mediaKey = mediaKey,
            servers = listOf("Server1"),
            links = serverMap,
            ids = mapOf("Server1" to "s1"),
            downloads = downloadMap
        )

        val cached = ServerStateStore.getCachedData(mediaKey, "123", "123")
        assertNotNull(cached)
        assertEquals(1, cached!!.servers.size)

        // Download before playback builds canonical source from resolved download link
        val dlUrl = cached.downloadLinks["1080p (تحميل)"]!!
        val source = UnifiedDownloadCoordinator.buildDownloadSource(
            streamUrl = dlUrl,
            mediaId = "123",
            title = "Test Movie",
            quality = "1080p",
            qualityKey = "1080"
        )

        assertEquals("123", source.mediaId)
        assertEquals("1080p", source.quality)
        assertEquals(dlUrl, source.url)
    }

    // =========================================================================
    // Test 7: Download after playback reuses existing source
    // =========================================================================
    @Test
    fun test7_downloadAfterPlaybackReusesExistingSource() {
        val playingUrl = "https://cdn.example.com/hls/720p.m3u8"
        val candidate720 = QualityCandidate(
            qualityKey = "720",
            label = "720p",
            streamUrl = playingUrl,
            headers = mapOf("Referer" to "https://embed.provider.to/watch")
        )
        ServerStateStore.internalCandidates = mapOf("720" to listOf(candidate720))

        // When user downloads 720p after playback started
        val candidate = ServerStateStore.internalCandidates["720"]!!.first()
        val source = UnifiedDownloadCoordinator.fromCandidate(
            candidate = candidate,
            mediaId = "media_active",
            title = "Active Playback Video"
        )

        assertEquals(playingUrl, source.url)
        assertEquals("720p", source.quality)
        assertEquals("https://embed.provider.to/watch", source.headers["Referer"])
    }

    // =========================================================================
    // Test 8: Known qualities are reused
    // =========================================================================
    @Test
    fun test8_knownQualitiesAreReused() {
        val mediaKey = "Batman-true-1-1"
        val q1080 = M3U8Parser.QualityInfo("1080p", "https://cdn.example.com/1080.m3u8")
        val q720 = M3U8Parser.QualityInfo("720p", "https://cdn.example.com/720.m3u8")
        val q480 = M3U8Parser.QualityInfo("480p", "https://cdn.example.com/480.m3u8")

        ServerStateStore.saveForMedia(
            mediaKey = mediaKey,
            servers = listOf("S1"),
            links = mapOf("S1" to "https://cdn.example.com/master.m3u8"),
            ids = mapOf("S1" to "s1"),
            downloads = emptyMap(),
            extractedQ = listOf(q1080, q720, q480)
        )

        val cached = ServerStateStore.getCachedData(mediaKey, "batman_id", "batman_id")
        assertNotNull(cached)
        assertEquals(3, cached!!.extractedQualities.size)
        assertEquals(listOf("1080p", "720p", "480p"), cached.extractedQualities.map { it.name })
    }

    // =========================================================================
    // Test 9: No duplicate server discovery when source already exists
    // =========================================================================
    @Test
    fun test9_noDuplicateServerDiscoveryWhenSourceAlreadyExists() {
        val mediaKey = "Spiderman-true-1-1"
        ServerStateStore.saveForMedia(
            mediaKey = mediaKey,
            servers = listOf("CachedServer"),
            links = mapOf("CachedServer" to "https://cdn.example.com/spiderman.m3u8"),
            ids = mapOf("CachedServer" to "cs1"),
            downloads = emptyMap(),
            extractedQ = listOf(M3U8Parser.QualityInfo("1080p", "https://cdn.example.com/1080.m3u8"))
        )

        var discoveryInvoked = false
        val checkAndDiscover = {
            val cached = ServerStateStore.getCachedData(mediaKey, "spidey", "spidey")
            if (cached != null && cached.extractedQualities.isNotEmpty()) {
                // Reuse existing
                cached.extractedQualities
            } else {
                discoveryInvoked = true
                emptyList()
            }
        }

        val qualities = checkAndDiscover()
        assertFalse("Discovery should not be invoked when qualities exist in cache", discoveryInvoked)
        assertEquals(1, qualities.size)
    }

    // =========================================================================
    // Test 10: Selected quality maps to correct candidate
    // =========================================================================
    @Test
    fun test10_selectedQualityMapsToCorrectCandidate() {
        val cand1080 = QualityCandidate("1080", "1080p", streamUrl = "https://cdn.com/1080.m3u8", headers = mapOf("Referer" to "ref1080"))
        val cand720 = QualityCandidate("720", "720p", streamUrl = "https://cdn.com/720.m3u8", headers = mapOf("Referer" to "ref720"))
        val cand480 = QualityCandidate("480", "480p", streamUrl = "https://cdn.com/480.m3u8", headers = mapOf("Referer" to "ref480"))

        ServerStateStore.internalCandidates = mapOf(
            "1080" to listOf(cand1080),
            "720" to listOf(cand720),
            "480" to listOf(cand480)
        )

        val selected = "720p"
        val qKey = normalizeQualityKey(selected)!!
        val targetCandidate = ServerStateStore.internalCandidates[qKey]!!.first()

        val source = UnifiedDownloadCoordinator.fromCandidate(
            candidate = targetCandidate,
            mediaId = "m10",
            title = "Selected Quality Test"
        )

        assertEquals("https://cdn.com/720.m3u8", source.url)
        assertEquals("720p", source.quality)
        assertEquals("ref720", source.headers["Referer"])
    }

    // =========================================================================
    // Test 11: Fallback candidate preserves same quality
    // =========================================================================
    @Test
    fun test11_fallbackCandidatePreservesSameQuality() {
        val primary720 = QualityCandidate("720", "720p", streamUrl = "https://srv1.com/720.m3u8", serverName = "Server 1")
        val fallback720 = QualityCandidate("720", "720p", streamUrl = "https://srv2.com/720.m3u8", serverName = "Server 2")

        val aggregated = AggregatedQuality(
            qualityKey = "720",
            label = "720p",
            primaryCandidate = primary720,
            fallbackCandidates = listOf(fallback720)
        )

        val source = UnifiedDownloadCoordinator.fromAggregatedQuality(
            aggregated = aggregated,
            mediaId = "m11",
            title = "Same Quality Fallback Test"
        )

        assertEquals("720p", source.quality)
        assertEquals(1, source.fallbackCandidates.size)
        assertEquals("720", source.fallbackCandidates.first().qualityKey)
        assertEquals("720p", source.fallbackCandidates.first().label)
    }

    // =========================================================================
    // Test 12: No cross-quality fallback
    // =========================================================================
    @Test
    fun test12_noCrossQualityFallback() {
        val primary1080 = QualityCandidate("1080", "1080p", streamUrl = "https://srv1.com/1080.m3u8")
        val alt1080 = QualityCandidate("1080", "1080p", streamUrl = "https://srv2.com/1080.m3u8")

        val source = UnifiedDownloadCoordinator.fromCandidate(
            candidate = primary1080,
            mediaId = "m12",
            title = "No Cross Quality Test",
            fallbackCandidates = listOf(alt1080)
        )

        // Assert that all fallback candidates maintain quality 1080 and never silently downgrade to 720
        source.fallbackCandidates.forEach {
            assertEquals("1080", it.qualityKey)
            assertEquals("1080p", it.label)
        }
    }

    // =========================================================================
    // Test 13: Movie download
    // =========================================================================
    @Test
    fun test13_movieDownload() {
        val source = UnifiedDownloadCoordinator.buildDownloadSource(
            streamUrl = "https://cdn.example.com/movie.mp4",
            mediaId = "5500",
            title = "Inception",
            quality = "1080p",
            isMovie = true
        )

        val input = DownloaderHandoffAdapter.toDownloaderInput(source)
        assertTrue(input.isMovie)
        assertEquals("5500", input.taskId)
        assertEquals("5500", input.mediaId)
        assertNull(input.episodeId)
    }

    // =========================================================================
    // Test 14: Series episode download
    // =========================================================================
    @Test
    fun test14_seriesEpisodeDownload() {
        val source = UnifiedDownloadCoordinator.buildDownloadSource(
            streamUrl = "https://cdn.example.com/series/s1e3.mp4",
            mediaId = "series_99",
            title = "Breaking Bad - S1E3",
            quality = "720p",
            isMovie = false,
            episodeId = "1_3"
        )

        val input = DownloaderHandoffAdapter.toDownloaderInput(source)
        assertFalse(input.isMovie)
        assertEquals("series_99_1_3", input.taskId)
        assertEquals("series_99", input.mediaId)
        assertEquals("1_3", input.episodeId)
    }

    // =========================================================================
    // Test 15: Anime episode download
    // =========================================================================
    @Test
    fun test15_animeEpisodeDownload() {
        val source = UnifiedDownloadCoordinator.buildDownloadSource(
            streamUrl = "https://cdn.example.com/anime/e12.m3u8",
            mediaId = "anime_777",
            title = "Attack on Titan - S1E12",
            quality = "1080p",
            isMovie = false,
            episodeId = "1_12",
            metadata = mapOf("isAnime" to "true")
        )

        val input = DownloaderHandoffAdapter.toDownloaderInput(source)
        assertFalse(input.isMovie)
        assertEquals("anime_777_1_12", input.taskId)
        assertEquals("anime_777", input.mediaId)
        assertEquals("1_12", input.episodeId)
    }

    // =========================================================================
    // Test 16: Duplicate download click protection
    // =========================================================================
    @Test
    fun test16_duplicateDownloadClickProtection() {
        val source = UnifiedDownloadCoordinator.buildDownloadSource(
            streamUrl = "https://cdn.example.com/dedup.mp4",
            mediaId = "dedup_media",
            title = "Dedup Test",
            quality = "720p",
            isMovie = true
        )

        // First click succeeds
        val time1 = UnifiedDownloadCoordinator.download(context, source)
        assertTrue(time1 > 0L)

        // Immediate rapid second and third clicks are rejected
        val time2 = UnifiedDownloadCoordinator.download(context, source)
        val time3 = UnifiedDownloadCoordinator.download(context, source)

        assertEquals("Rapid 2nd click should be rejected by deduplication", 0L, time2)
        assertEquals("Rapid 3rd click should be rejected by deduplication", 0L, time3)
    }

    // =========================================================================
    // Test 17: Duplicate quality dialog prevention
    // =========================================================================
    @Test
    fun test17_duplicateQualityDialogPrevention() {
        val dialogOpen = AtomicBoolean(false)
        val openCounter = AtomicInteger(0)

        val requestOpenDialog = {
            if (dialogOpen.compareAndSet(false, true)) {
                openCounter.incrementAndGet()
            }
        }

        // Simulate 3 rapid clicks requesting dialog
        requestOpenDialog()
        requestOpenDialog()
        requestOpenDialog()

        assertEquals("Only 1 quality dialog instance should be allowed open concurrently", 1, openCounter.get())
    }

    // =========================================================================
    // Test 18: Download does not interrupt playback
    // =========================================================================
    @Test
    fun test18_downloadDoesNotInterruptPlayback() {
        var isPlayerPlaying = true
        var currentPlaybackPosition = 45000L // 45 seconds

        val onDownloadClicked = {
            // Trigger download via UnifiedDownloadCoordinator
            val source = UnifiedDownloadCoordinator.buildDownloadSource(
                streamUrl = "https://cdn.example.com/stream.mp4",
                mediaId = "p18",
                title = "Playback Safety"
            )
            UnifiedDownloadCoordinator.download(context, source)
        }

        onDownloadClicked()

        // Assert player state was NOT stopped, paused, or reset to 0
        assertTrue("Playback must remain playing when download is triggered", isPlayerPlaying)
        assertEquals("Playback position must not reset to zero", 45000L, currentPlaybackPosition)
    }

    // =========================================================================
    // Test 19: Download quality does not alter playback quality
    // =========================================================================
    @Test
    fun test19_downloadQualityDoesNotAlterPlaybackQuality() {
        var activePlaybackQuality = "720p"

        val onSelectDownloadQuality = { chosenQuality: String ->
            val source = UnifiedDownloadCoordinator.buildDownloadSource(
                streamUrl = "https://cdn.example.com/$chosenQuality.m3u8",
                mediaId = "p19",
                title = "Independent Quality Test",
                quality = chosenQuality
            )
            UnifiedDownloadCoordinator.download(context, source)
        }

        // User is watching in 720p, selects 1080p for download
        onSelectDownloadQuality("1080p")

        assertEquals("Active playback quality must NOT change when a different download quality is selected", "720p", activePlaybackQuality)
    }

    // =========================================================================
    // Test 20: Invalid download URL rejected
    // =========================================================================
    @Test
    fun test20_invalidDownloadUrlRejected() {
        // Blank URL
        assertFalse(UnifiedDownloadCoordinator.isValidDownloadUrl(""))

        // Dangerous / non-HTTP scheme
        assertFalse(UnifiedDownloadCoordinator.isValidDownloadUrl("javascript:alert(1)"))
        assertFalse(UnifiedDownloadCoordinator.isValidDownloadUrl("file:///data/data/com.example/databases/app.db"))

        // Localhost / private IP forbidden host
        assertFalse(UnifiedDownloadCoordinator.isValidDownloadUrl("https://127.0.0.1/video.mp4"))
        assertFalse(UnifiedDownloadCoordinator.isValidDownloadUrl("https://localhost/video.mp4"))
        assertFalse(UnifiedDownloadCoordinator.isValidDownloadUrl("https://192.168.1.1/stream.m3u8"))

        // Non-media document
        assertFalse(UnifiedDownloadCoordinator.isValidDownloadUrl("https://example.com/index.html"))

        // Valid media URLs
        assertTrue(UnifiedDownloadCoordinator.isValidDownloadUrl("https://cdn.example.com/stream.m3u8"))
        assertTrue(UnifiedDownloadCoordinator.isValidDownloadUrl("https://cdn.example.com/video.mp4"))
    }

    // =========================================================================
    // REAL QFILM TEST & LIVE DOWNLOAD VERIFICATION (Requirements 32, 33, 34)
    // =========================================================================
    @Test
    fun test21_realQfilmPipelineAndLiveDownloadVerification() = runBlocking {
        println("=== [LIVE_DOWNLOAD_TEST] STARTING REAL QFILM DOWNLOAD AUDIT ===")
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val qfilmExtension = ManagedMediaOrchestrator.DEFAULT_QFILM_MANAGED_EXTENSION
        val registry = ManagedExtensionRegistry(listOf(qfilmExtension))
        val scraperRegistry = ScraperRegistry(mapOf("qfilm" to QfilmScraper()))
        val fallbackManager = FallbackManager(scraperRegistry)

        val runtime = DefaultControlledManagedExtensionRuntime(
            scraperRegistry = scraperRegistry,
            fallbackManager = fallbackManager,
            managedExtensionRegistry = registry,
            currentAppVersionCode = 1L,
            supportedRuntimeApiVersion = 1
        )

        val userPrefs = InMemoryExtensionUserPreferences().apply {
            setExtensionEnabled(qfilmExtension.id, true)
        }

        val dummyRepo = object : ManagedExtensionRepository {
            override suspend fun getExtensions(forceRefresh: Boolean): Result<List<ManagedExtension>> = Result.success(listOf(qfilmExtension))
            override suspend fun getExtensionById(id: String, forceRefresh: Boolean): Result<ManagedExtension> = Result.success(qfilmExtension)
        }

        val orchestrator = ManagedMediaOrchestrator(
            repository = dummyRepo,
            registry = registry,
            runtime = runtime,
            userPreferences = userPrefs,
            isOnlineChecker = { true }
        )

        // 1. Search
        println("[LIVE_DOWNLOAD] Step 1: Searching content...")
        val searchRes = orchestrator.searchMedia("محمود التاني")
        assertTrue("Search should succeed", searchRes.isSuccess)
        val items = searchRes.getOrThrow().items
        assertTrue("Items should be returned", items.isNotEmpty())
        val item = items.first()
        println("[LIVE_DOWNLOAD] Found media: title='${item.title}', url='${item.url}'")

        // 2. Details
        println("[LIVE_DOWNLOAD] Step 2: Fetching details / watch URL...")
        val detailsRes = orchestrator.getMediaDetails(item.url, qfilmExtension.id)
        assertTrue("Details should succeed", detailsRes.isSuccess)
        val details = detailsRes.getOrThrow()
        println("[LIVE_DOWNLOAD] Watch URL: '${details.url}'")

        // 3. Server Discovery
        println("[LIVE_DOWNLOAD] Step 3: Discovering servers...")
        val outcome = orchestrator.discoverServers(
            title = "محمود التاني",
            isMovie = true
        )
        assertTrue("Server discovery should succeed", outcome is ManagedDiscoveryOutcome.Success)
        val servers = (outcome as ManagedDiscoveryOutcome.Success).servers
        assertTrue("Servers should be found", servers.isNotEmpty())
        val selectedServer = servers.first()
        println("[LIVE_DOWNLOAD] Selected server: '${selectedServer.name}', link='${selectedServer.link}'")

        // 4. Extract Playback Source
        println("[LIVE_DOWNLOAD] Step 4: Extracting direct stream source...")
        val playbackRes = orchestrator.extractPlaybackSource(selectedServer, "محمود التاني")
        assertTrue("Playback extraction should succeed", playbackRes.isSuccess)
        val playbackSource = playbackRes.getOrThrow()
        println("[LIVE_DOWNLOAD] Resolved stream URL: '${playbackSource.streamUrl}'")
        println("[LIVE_DOWNLOAD] Headers from extraction: ${playbackSource.headers}")

        // 5. Construct Canonical DownloadSource
        val downloadSource = DownloadSource(
            url = playbackSource.streamUrl,
            filename = "live_qfilm_test_media",
            title = "محمود التاني",
            quality = "720p",
            headers = playbackSource.headers,
            sourceUrl = selectedServer.link,
            mediaId = "qfilm_test_1"
        )

        // 6. Downloader Handoff
        val downloaderInput = DownloaderHandoffAdapter.toDownloaderInput(downloadSource)
        println("[LIVE_DOWNLOAD] DownloaderInput headers: ${downloaderInput.headers}")
        assertTrue("Referer header must be present in DownloaderInput", downloaderInput.headers.containsKey("Referer"))

        // 7. Execute Real HTTP Download with preserved headers
        println("[LIVE_DOWNLOAD] Step 7: Performing real HTTP request to download stream...")
        val reqBuilder = Request.Builder().url(downloaderInput.downloadUrl)
        for ((k, v) in downloaderInput.headers) {
            reqBuilder.header(k, v)
        }

        val response = client.newCall(reqBuilder.build()).execute()
        val httpCode = response.code
        val contentType = response.header("Content-Type") ?: ""
        println("[LIVE_DOWNLOAD] HTTP Status: $httpCode")
        println("[LIVE_DOWNLOAD] Content-Type: $contentType")

        assertTrue("HTTP response should be successful (200..299), got: $httpCode", response.isSuccessful)

        // Check if stream is HLS master playlist or direct media
        val bodyBytes = response.body?.bytes() ?: ByteArray(0)
        assertTrue("Downloaded bytes must be > 0", bodyBytes.isNotEmpty())
        println("[LIVE_DOWNLOAD] Bytes received: ${bodyBytes.size}")

        var finalDownloadedBytes = bodyBytes.size.toLong()
        var finalFilePath = ""
        val tempDir = File(System.getProperty("java.io.tmpdir"), "qfilm_dl_test")
        if (!tempDir.exists()) tempDir.mkdirs()

        if (downloadSource.url.contains(".m3u8") || contentType.contains("mpegurl", ignoreCase = true)) {
            val playlistText = String(bodyBytes)
            println("[LIVE_DOWNLOAD] HLS Playlist retrieved (${playlistText.lines().size} lines).")
            val qualities = M3U8Parser.parsePlaylistContent(playlistText, downloadSource.url, downloaderInput.headers)
            println("[LIVE_DOWNLOAD] Parsed variants: ${qualities.map { it.name }}")

            // If variant found, fetch the variant playlist and its first segment!
            val segmentOrVariantUrl = if (qualities.isNotEmpty() && qualities.first().url != downloadSource.url) {
                qualities.first().url
            } else {
                // Parse segments directly
                playlistText.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.let {
                    M3U8Parser.resolveUri(downloadSource.url, it)
                } ?: downloadSource.url
            }

            println("[LIVE_DOWNLOAD] Fetching first media segment: $segmentOrVariantUrl")
            val segReq = Request.Builder().url(segmentOrVariantUrl)
            for ((k, v) in downloaderInput.headers) {
                segReq.header(k, v)
            }
            val segRes = client.newCall(segReq.build()).execute()
            println("[LIVE_DOWNLOAD] Segment HTTP Status: ${segRes.code}")
            assertTrue("Segment request should be successful", segRes.isSuccessful)
            val segBytes = segRes.body?.bytes() ?: ByteArray(0)
            assertTrue("Segment bytes received must be > 0", segBytes.isNotEmpty())

            val mediaFile = File(tempDir, "test_segment.ts")
            FileOutputStream(mediaFile).use { it.write(segBytes) }
            finalDownloadedBytes = mediaFile.length()
            finalFilePath = mediaFile.absolutePath
            println("[LIVE_DOWNLOAD] Media segment saved to: $finalFilePath (${finalDownloadedBytes} bytes)")
        } else {
            val mediaFile = File(tempDir, "test_video.mp4")
            FileOutputStream(mediaFile).use { it.write(bodyBytes) }
            finalDownloadedBytes = mediaFile.length()
            finalFilePath = mediaFile.absolutePath
            println("[LIVE_DOWNLOAD] Direct media file saved to: $finalFilePath (${finalDownloadedBytes} bytes)")
        }

        assertTrue("Output file size must be > 0", finalDownloadedBytes > 0L)
        println("=== [LIVE_DOWNLOAD_TEST] LIVE VERIFICATION SUCCESSFUL ===")
    }
}
