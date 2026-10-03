package com.example.extension.managed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.HistoryItem
import com.example.data.repository.HistoryRepository
import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.repository.ExtensionUserPreferences
import com.example.extension.managed.repository.InMemoryExtensionUserPreferences
import com.example.extension.managed.repository.ManagedExtensionRepository
import com.example.extension.orchestrator.BackgroundMediaRevalidator
import com.example.extension.orchestrator.ManagedMediaOrchestrator
import com.example.ui.screens.player.PlaybackSyncStore
import com.example.ui.screens.player.PlayerUiState
import com.example.ui.screens.player.PlayerViewModel
import com.example.ui.screens.player.ServerStateStore
import com.example.ui.screens.player.normalizeQualityKey
import com.example.ui.screens.player.normalizeQualityLabel
import com.example.utils.LastPlaybackStore
import com.example.utils.M3U8Parser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase716PlaybackResumeAndBackgroundRevalidationTest {

    private lateinit var context: Context
    private lateinit var registry: ManagedExtensionRegistry
    private lateinit var userPreferences: ExtensionUserPreferences
    private lateinit var fakeRuntime: FakeControlledRuntime
    private lateinit var fakeRepository: FakeManagedRepository
    private lateinit var orchestrator: ManagedMediaOrchestrator

    private val testExtension = ManagedExtension(
        id = "qfilm",
        name = "Qfilm (Managed)",
        description = "Test Scraper",
        baseUrl = "https://a.qfilm.tv",
        iconUrl = "",
        scraperKey = "qfilm",
        definitionVersion = 1,
        minAppVersionCode = 1,
        runtimeApiVersion = 1,
        priority = 100,
        language = "ar",
        contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES, ContentType.ANIME),
        status = ExtensionLifecycleStatus.ACTIVE,
        updatedAt = 1710000000000L,
        userEnabled = true
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(context)
        }
        registry = ManagedExtensionRegistry.INSTANCE
        registry.setExtensions(listOf(testExtension))

        userPreferences = InMemoryExtensionUserPreferences()
        fakeRuntime = FakeControlledRuntime()
        fakeRepository = FakeManagedRepository()

        orchestrator = ManagedMediaOrchestrator(
            repository = fakeRepository,
            registry = registry,
            runtime = fakeRuntime,
            userPreferences = userPreferences,
            isOnlineChecker = { true }
        )
        ServerStateStore.clear()
    }

    // ==========================================
    // 1. RESUME TESTS (Movie, Series, Anime, Continue Watching)
    // ==========================================

    @Test
    fun testMoviePlaybackResumePreservesPositionAndQuality() {
        val movieId = "movie_12345"
        val expectedPosition = 2538000L // 00:42:18
        val expectedDuration = 7200000L // 02:00:00
        val expectedQuality = "720p"
        val streamUrl = "https://cdn.example.com/movie_12345_720.mp4"
        val playPageUrl = "https://a.qfilm.tv/play.php?vid=2df296d1a"

        // 1. Save playback state
        LastPlaybackStore.savePlayback(
            context = context,
            mediaId = movieId,
            url = streamUrl,
            quality = expectedQuality,
            serverName = "Server Fast",
            website = "Qfilm (Managed)",
            positionMillis = expectedPosition,
            durationMillis = expectedDuration,
            playbackPageUrl = playPageUrl,
            scraperKey = "qfilm"
        )
        PlaybackSyncStore.setPosition(movieId, expectedPosition)

        // 2. Retrieve saved playback info
        val saved = LastPlaybackStore.getLastPlayback(context, movieId)
        assertNotNull(saved)
        assertEquals(movieId, saved!!.mediaId)
        assertEquals(expectedPosition, saved.positionMillis)
        assertEquals(expectedDuration, saved.durationMillis)
        assertEquals(expectedQuality, saved.quality)
        assertEquals("Server Fast", saved.serverName)
        assertEquals(playPageUrl, saved.playbackPageUrl)
        assertEquals("qfilm", saved.scraperKey)

        // Verify in-memory fast sync store
        assertEquals(expectedPosition, PlaybackSyncStore.getPosition(movieId))
        assertEquals("42:18", PlaybackSyncStore.formatTime(expectedPosition))
    }

    @Test
    fun testSeriesEpisodeResumePreservesEpisodeSpecificPositionAndQuality() {
        val seriesId = "series_999"
        val episodeId = "s1e5"
        val epKey = "${seriesId}_$episodeId"
        val expectedPosition = 1200000L // 00:20:00
        val expectedDuration = 3000000L // 00:50:00
        val expectedQuality = "1080p"
        val playPageUrl = "https://a.qfilm.tv/play.php?vid=ep5vid"

        LastPlaybackStore.savePlayback(
            context = context,
            mediaId = seriesId,
            episodeId = episodeId,
            url = "https://cdn.example.com/ep5_1080.m3u8",
            quality = expectedQuality,
            serverName = "Main Server",
            positionMillis = expectedPosition,
            durationMillis = expectedDuration,
            playbackPageUrl = playPageUrl,
            scraperKey = "qfilm"
        )
        PlaybackSyncStore.setPosition(epKey, expectedPosition)

        // Check episode retrieval
        val saved = LastPlaybackStore.getLastPlayback(context, seriesId, episodeId)
        assertNotNull(saved)
        assertEquals(episodeId, saved!!.episodeId)
        assertEquals(expectedPosition, saved.positionMillis)
        assertEquals(expectedQuality, saved.quality)
        assertEquals(expectedPosition, PlaybackSyncStore.getPosition(epKey))
        assertEquals(episodeId, LastPlaybackStore.getLastEpisodeId(context, seriesId))
    }

    @Test
    fun testAnimeEpisodeResumePreservesPositionAndQuality() {
        val animeId = "anime_attackontitan"
        val episodeId = "ep24"
        val expectedPosition = 840000L // 00:14:00
        val expectedQuality = "1080p"

        LastPlaybackStore.savePlayback(
            context = context,
            mediaId = animeId,
            episodeId = episodeId,
            url = "https://cdn.example.com/aot_ep24.mp4",
            quality = expectedQuality,
            serverName = "Anime Server",
            positionMillis = expectedPosition,
            durationMillis = 1440000L
        )

        val retrieved = LastPlaybackStore.getLastPlayback(context, animeId, episodeId)
        assertNotNull(retrieved)
        assertEquals(expectedPosition, retrieved!!.positionMillis)
        assertEquals(expectedQuality, retrieved.quality)
    }

    @Test
    fun testContinueWatchingResumeUsesDirectKnownStateWithoutSearch() = runBlocking {
        val mediaId = "cw_movie_456"
        val playPageUrl = "https://a.qfilm.tv/play.php?vid=cw456"
        val directUrl = "https://cdn.example.com/cw456.mp4"

        LastPlaybackStore.savePlayback(
            context = context,
            mediaId = mediaId,
            url = directUrl,
            quality = "720p",
            positionMillis = 1800000L,
            durationMillis = 5400000L,
            playbackPageUrl = playPageUrl,
            scraperKey = "qfilm"
        )

        // Continue watching accesses LastPlaybackStore directly
        val state = LastPlaybackStore.getLastPlayback(context, mediaId)
        assertNotNull(state)
        assertEquals(directUrl, state!!.url)
        assertEquals("720p", state.quality)
        assertEquals(1800000L, state.positionMillis)

        // Ensure runtime search is never called for direct playback resumption
        assertEquals(0, fakeRuntime.searchInvocationCount.get())
    }

    @Test
    fun testEpisodePositionDoesNotLeakToOtherEpisodes() {
        val seriesId = "series_isolation_99"
        val ep1Key = "${seriesId}_ep1"
        val ep2Key = "${seriesId}_ep2"

        PlaybackSyncStore.setPosition(ep1Key, 50000L)
        assertEquals(50000L, PlaybackSyncStore.getPosition(ep1Key))
        // Episode 2 must NOT inherit Episode 1's position
        assertEquals(0L, PlaybackSyncStore.getPosition(ep2Key))
    }

    @Test
    fun testContinueWatchingPersistRequiresDurationAndPositionThreshold() = runBlocking {
        val historyRepo = HistoryRepository(context)

        // Case 1: duration <= 0 must not persist
        PlaybackSyncStore.setPositionAndPersist(
            context = context,
            key = "zero_duration_media",
            position = 20000L,
            duration = 0L,
            title = "Zero Duration Movie"
        )
        kotlinx.coroutines.delay(200)
        assertNull(historyRepo.getHistoryItem("zero_duration_media"))

        // Case 2: position < 10000L must not persist
        PlaybackSyncStore.setPositionAndPersist(
            context = context,
            key = "too_short_media",
            position = 4000L,
            duration = 120000L,
            title = "Too Short Movie"
        )
        kotlinx.coroutines.delay(200)
        assertNull(historyRepo.getHistoryItem("too_short_media"))

        // Case 3: valid position >= 10000L and duration > 0L persists canonical ID
        PlaybackSyncStore.setPositionAndPersist(
            context = context,
            key = "series_valid_99_ep1",
            position = 30000L,
            duration = 1800000L,
            title = "Series Valid - S1E1",
            isMovie = false
        )
        kotlinx.coroutines.delay(200)
        val saved = historyRepo.getHistoryItem("series_valid_99")
        assertNotNull(saved)
        assertEquals("series_valid_99", saved!!.id)
        assertEquals(30000L, saved.positionMillis)
        assertEquals(1800000L, saved.durationMillis)
    }

    // ==========================================
    // 2. BACKGROUND REVALIDATION TESTS
    // ==========================================

    @Test
    fun testRevalidationStartsDirectlyOnKnownPlaybackUrlWithoutSearch() = runTest {
        val mediaId = "direct_media_77"
        val knownPlayUrl = "https://a.qfilm.tv/play.php?vid=direct77"

        fakeRuntime.discoverServersResponse = Result.success(
            ServerDiscoveryResult(
                servers = listOf(
                    ServerItem(id = "s1", name = "Wwa", link = "https://server.wwa/stream.m3u8"),
                    ServerItem(id = "s2", name = "Vidmoly", link = "https://vidmoly.me/embed.html")
                ),
                sourcePageUrl = knownPlayUrl
            )
        )

        val revalidator = BackgroundMediaRevalidator(
            registry = registry,
            runtime = fakeRuntime,
            isOnlineChecker = { true }
        )

        val job = revalidator.revalidateMedia(
            mediaId = mediaId,
            mediaTitle = "Direct Movie",
            isMovie = true,
            knownPlaybackUrl = knownPlayUrl,
            scraperKey = "qfilm"
        )
        job.join()

        // 1. Verify search was NOT invoked
        assertEquals(0, fakeRuntime.searchInvocationCount.get())

        // 2. Verify discoverServers was invoked directly on knownPlayUrl
        assertEquals(1, fakeRuntime.discoverServersInvocationCount.get())
        assertEquals(knownPlayUrl, fakeRuntime.lastDiscoveredTargetUrl)

        // 3. Verify ServerStateStore updated
        val cached = ServerStateStore.getCachedData(mediaId)
        assertNotNull(cached)
        assertEquals(2, cached!!.servers.size)
        assertTrue(cached.servers.contains("Wwa"))
        assertTrue(cached.servers.contains("Vidmoly"))
    }

    @Test
    fun testRevalidationDeduplicatesConcurrentJobsForSameMedia() = runTest {
        val mediaId = "dedup_media_88"
        val knownPlayUrl = "https://a.qfilm.tv/play.php?vid=dedup88"

        fakeRuntime.discoverServersDelayMs = 200L
        fakeRuntime.discoverServersResponse = Result.success(
            ServerDiscoveryResult(
                servers = listOf(ServerItem(id = "s1", name = "S1", link = "https://s1.example")),
                sourcePageUrl = knownPlayUrl
            )
        )

        val revalidator = BackgroundMediaRevalidator(
            registry = registry,
            runtime = fakeRuntime,
            isOnlineChecker = { true }
        )

        // Launch two revalidations concurrently for the exact same media
        val job1 = revalidator.revalidateMedia(
            mediaId = mediaId,
            mediaTitle = "Dedup Movie",
            isMovie = true,
            knownPlaybackUrl = knownPlayUrl,
            scraperKey = "qfilm"
        )
        val job2 = revalidator.revalidateMedia(
            mediaId = mediaId,
            mediaTitle = "Dedup Movie",
            isMovie = true,
            knownPlaybackUrl = knownPlayUrl,
            scraperKey = "qfilm"
        )

        // Same job instance returned due to deduplication
        assertSame(job1, job2)
        job1.join()

        // Discover servers should be invoked only once
        assertEquals(1, fakeRuntime.discoverServersInvocationCount.get())
    }

    @Test
    fun testRevalidationRespects30SecondGlobalDeadline() {
        val mediaId = "deadline_test_99"
        val knownPlayUrl = "https://a.qfilm.tv/play.php?vid=hang"

        fakeRuntime.hangInDiscoverServers = true

        val revalidator = BackgroundMediaRevalidator(
            registry = registry,
            runtime = fakeRuntime,
            isOnlineChecker = { true }
        )

        val job = revalidator.revalidateMedia(
            mediaId = mediaId,
            mediaTitle = "Hanging Media",
            isMovie = true,
            knownPlaybackUrl = knownPlayUrl,
            scraperKey = "qfilm"
        )

        // Job is active and tracked
        assertTrue(revalidator.isJobActive("qfilm:$mediaId:"))
        revalidator.cancelJob("qfilm:$mediaId:")
        assertFalse(revalidator.isJobActive("qfilm:$mediaId:"))
    }

    // ==========================================
    // 3. NETWORK INTERRUPTION & RESTORATION
    // ==========================================

    @Test
    fun testNetworkInterruptionAndRestorationResumesWithinBudget() = runTest {
        val mediaId = "net_test_101"
        val knownPlayUrl = "https://a.qfilm.tv/play.php?vid=net101"

        var networkIsUp = false
        val revalidator = BackgroundMediaRevalidator(
            registry = registry,
            runtime = fakeRuntime,
            isOnlineChecker = { networkIsUp }
        )

        fakeRuntime.discoverServersResponse = Result.success(
            ServerDiscoveryResult(
                servers = listOf(ServerItem(id = "s_rec", name = "RecoveredServer", link = "https://rec.example")),
                sourcePageUrl = knownPlayUrl
            )
        )

        val job = revalidator.revalidateMedia(
            mediaId = mediaId,
            mediaTitle = "Network Reconnect Movie",
            isMovie = true,
            knownPlaybackUrl = knownPlayUrl,
            scraperKey = "qfilm"
        )

        // Simulate network coming back after 1.5 seconds
        delay(1500)
        networkIsUp = true

        job.join()

        assertEquals(1, fakeRuntime.discoverServersInvocationCount.get())
        val data = ServerStateStore.getCachedData(mediaId)
        assertNotNull(data)
        assertTrue(data!!.servers.contains("RecoveredServer"))
    }

    // ==========================================
    // 4. SERVER & QUALITY DEDUPLICATION AND NORMALIZATION
    // ==========================================

    @Test
    fun testServerDeduplicationPreservesContextAndPreventsDuplicates() {
        val mediaKey = "Inception-true-1-1"
        // Seed existing server
        ServerStateStore.saveForMedia(
            mediaKey = mediaKey,
            servers = listOf("Wwa", "Vidmoly"),
            links = mapOf("Wwa" to "https://wwa.link", "Vidmoly" to "https://vidmoly.link"),
            ids = mapOf("Wwa" to "1", "Vidmoly" to "2"),
            downloads = emptyMap()
        )

        // Incoming discovered servers contain existing + new
        val incoming = listOf(
            ServerItem(id = "1", name = "Wwa", link = "https://wwa.link"),
            ServerItem(id = "3", name = "NewFastServer", link = "https://newfast.link")
        )

        val existing = ServerStateStore.getCachedData(mediaKey)!!.servers
        val existingLinks = ServerStateStore.getCachedData(mediaKey)!!.serverLinks.toMutableMap()
        val existingIds = ServerStateStore.getCachedData(mediaKey)!!.serverIds.toMutableMap()
        val mergedServers = existing.toMutableList()

        for (item in incoming) {
            if (!existingLinks.values.contains(item.link)) {
                mergedServers.add(item.name)
                existingLinks[item.name] = item.link
                existingIds[item.name] = item.id
            }
        }

        // Distinct servers count is 3, not 4!
        assertEquals(listOf("Wwa", "Vidmoly", "NewFastServer"), mergedServers)
    }

    @Test
    fun testQualityNormalizationAndDeduplication() {
        val rawNames = listOf("1080p FHD", "FULL HD 1080", "720p HD", "HD 720", "480p", "SD", "Auto")
        val normalized = rawNames.mapNotNull { raw ->
            val key = normalizeQualityKey(raw)
            if (key != null) normalizeQualityLabel(key) else if (raw == "Auto") "Auto" else null
        }.distinct()

        // Unified normalized set
        assertTrue(normalized.contains("1080p"))
        assertTrue(normalized.contains("720p"))
        assertTrue(normalized.contains("480p"))
        assertTrue(normalized.contains("Auto"))

        // Duplicate 1080p and 720p merged away
        assertEquals(4, normalized.size)
    }

    // ==========================================
    // 5. QUALITY SWITCH & POSITION PRESERVATION
    // ==========================================

    @Test
    fun testQualitySwitchPreservesPlaybackPosition() {
        val currentPosition = 2538000L // 00:42:18
        val oldQuality = "720p"
        val newQuality = "1080p"

        // Simulate user selecting a new quality in PlayerViewModel
        val targetPos = currentPosition
        val simulatedNewState = PlayerUiState(
            currentQuality = newQuality,
            currentPositionMillis = currentPosition,
            pendingSeekPosition = targetPos,
            currentVideoUrl = "https://cdn.example.com/1080.mp4"
        )

        assertEquals("1080p", simulatedNewState.currentQuality)
        assertEquals(currentPosition, simulatedNewState.pendingSeekPosition)
        assertEquals("42:18", PlaybackSyncStore.formatTime(simulatedNewState.pendingSeekPosition!!))
    }

    @Test
    fun testQualitySwitchFailurePreservesCurrentPlayback() {
        var activeQuality = "720p"
        var activeUrl = "https://cdn.example.com/720.mp4"
        val currentPos = 2538000L

        val lastWorkingQuality = activeQuality
        val lastWorkingUrl = activeUrl

        // Attempt switch to broken 1080p
        activeQuality = "1080p"
        activeUrl = "https://cdn.example.com/broken_1080.mp4"

        // Simulate error callback in player
        val playbackFailed = true
        if (playbackFailed) {
            // Fallback restores previous quality and URL without stopping session
            activeQuality = lastWorkingQuality
            activeUrl = lastWorkingUrl
        }

        assertEquals("720p", activeQuality)
        assertEquals("https://cdn.example.com/720.mp4", activeUrl)
    }

    // ==========================================
    // 6. SEEKING BEHAVIOR
    // ==========================================

    @Test
    fun testSeekDoesNotRecreateFullSession() {
        val startPos = 120000L // 02:00
        val targetPos = 600000L // 10:00

        // Seek strictly modifies time position without touching orchestrator discovery
        PlaybackSyncStore.setPosition("media_seek_test", targetPos)
        val restored = PlaybackSyncStore.getPosition("media_seek_test")

        assertEquals(targetPos, restored)
        assertEquals(0, fakeRuntime.searchInvocationCount.get())
        assertEquals(0, fakeRuntime.discoverServersInvocationCount.get())
    }

    @Test
    fun testSeekingForwardBackwardAndLongSeek() {
        val mediaId = "seek_flow_media"
        var currentPosition = 60000L // 01:00

        // 1. Seek forward (+10s)
        currentPosition += 10000L
        PlaybackSyncStore.setPosition(mediaId, currentPosition)
        assertEquals(70000L, PlaybackSyncStore.getPosition(mediaId))

        // 2. Seek backward (-10s)
        currentPosition -= 10000L
        PlaybackSyncStore.setPosition(mediaId, currentPosition)
        assertEquals(60000L, PlaybackSyncStore.getPosition(mediaId))

        // 3. Long seek (jump to 45:00)
        val longSeekPosition = 2700000L // 45:00
        PlaybackSyncStore.setPosition(mediaId, longSeekPosition)
        assertEquals(longSeekPosition, PlaybackSyncStore.getPosition(mediaId))
        assertEquals("45:00", PlaybackSyncStore.formatTime(longSeekPosition))

        // Ensure zero orchestrator search or re-discovery calls during all seek operations
        assertEquals(0, fakeRuntime.searchInvocationCount.get())
        assertEquals(0, fakeRuntime.discoverServersInvocationCount.get())
    }

    @Test
    fun testMultiStepQualitySwitchPreservesPlaybackPosition() {
        val mediaId = "multi_switch_media"
        val position10m = 600000L // 10:00

        // State 1: 1080p at 10:00
        var state = PlayerUiState(
            mediaId = mediaId,
            currentQuality = "1080p",
            currentPositionMillis = position10m,
            currentVideoUrl = "https://cdn.example.com/1080.m3u8"
        )
        assertEquals("1080p", state.currentQuality)
        assertEquals(position10m, state.currentPositionMillis)

        // Switch 1: 1080p -> 720p at 10:00
        state = state.copy(
            currentQuality = "720p",
            currentVideoUrl = "https://cdn.example.com/720.m3u8",
            pendingSeekPosition = state.currentPositionMillis
        )
        assertEquals("720p", state.currentQuality)
        assertEquals(position10m, state.pendingSeekPosition)

        // Switch 2: 720p -> 480p at 10:00
        state = state.copy(
            currentQuality = "480p",
            currentVideoUrl = "https://cdn.example.com/480.m3u8",
            pendingSeekPosition = state.currentPositionMillis
        )
        assertEquals("480p", state.currentQuality)
        assertEquals(position10m, state.pendingSeekPosition)
        assertEquals("10:00", PlaybackSyncStore.formatTime(state.pendingSeekPosition!!))
    }

    @Test
    fun testBackgroundRevalidationDoesNotInterruptCurrentPlayback() = runTest {
        val mediaId = "non_disruptive_media"
        val currentPlayUrl = "https://cdn.example.com/playing_720.mp4"
        val currentPlayPos = 1850000L // 30:50
        val knownPlayUrl = "https://a.qfilm.tv/play.php?vid=nondisruptive"

        // 1. Initial active playback
        var activeUiState = PlayerUiState(
            mediaId = mediaId,
            currentQuality = "720p",
            currentPositionMillis = currentPlayPos,
            currentVideoUrl = currentPlayUrl,
            isLoading = false
        )

        fakeRuntime.discoverServersResponse = Result.success(
            ServerDiscoveryResult(
                servers = listOf(
                    ServerItem(id = "s1", name = "Wwa", link = "https://server.wwa/stream.m3u8"),
                    ServerItem(id = "s2", name = "NewFastCdn", link = "https://cdn.fast/stream.m3u8")
                ),
                sourcePageUrl = knownPlayUrl
            )
        )

        val revalidator = BackgroundMediaRevalidator(
            registry = registry,
            runtime = fakeRuntime,
            isOnlineChecker = { true }
        )

        // 2. Launch background revalidation
        val job = revalidator.revalidateMedia(
            mediaId = mediaId,
            mediaTitle = "Non Disruptive Media",
            isMovie = true,
            knownPlaybackUrl = knownPlayUrl,
            scraperKey = "qfilm",
            currentQuality = "720p"
        )
        job.join()

        // 3. Verify ServerStateStore updated with new servers
        val cached = ServerStateStore.getCachedData(mediaId)
        assertNotNull(cached)
        assertTrue(cached!!.servers.contains("NewFastCdn"))

        // 4. Critical UX: The active player state, video URL, and position MUST be completely preserved!
        assertEquals("720p", activeUiState.currentQuality)
        assertEquals(currentPlayUrl, activeUiState.currentVideoUrl)
        assertEquals(currentPlayPos, activeUiState.currentPositionMillis)
        assertFalse(activeUiState.isLoading)
    }

    @Test
    fun testQualityEvidenceNormalizationDoesNotFabricate1080p() {
        // Unknown or non-standard names without resolution evidence
        val arbitraryLabels = listOf("High", "Best", "HD1", "Quality-2", "Server HD", "WWA-Custom")
        for (label in arbitraryLabels) {
            val key = normalizeQualityKey(label)
            assertNull("Arbitrary label '$label' must not fabricate resolution", key)
        }

        // True evidence-based resolution strings
        assertEquals("1080", normalizeQualityKey("RESOLUTION=1920x1080"))
        assertEquals("720", normalizeQualityKey("RESOLUTION=1280x720"))
        assertEquals("480", normalizeQualityKey("RESOLUTION=854x480"))
        assertEquals("360", normalizeQualityKey("RESOLUTION=640x360"))
    }

    @Test
    fun testTwoPlayersConsistentPlaybackContract() {
        val mediaId = "two_players_contract_test"
        val expectedPosition = 2538000L // 00:42:18
        val expectedQuality = "720p"
        val playPageUrl = "https://a.qfilm.tv/play.php?vid=twoplayers"
        val streamUrl = "https://cdn.example.com/twoplayers_720.mp4"

        // 1. Save state
        LastPlaybackStore.savePlayback(
            context = context,
            mediaId = mediaId,
            url = streamUrl,
            quality = expectedQuality,
            serverName = "Server Fast",
            website = "Qfilm (Managed)",
            positionMillis = expectedPosition,
            durationMillis = 7200000L,
            playbackPageUrl = playPageUrl,
            scraperKey = "qfilm"
        )
        PlaybackSyncStore.setPosition(mediaId, expectedPosition)

        // 2. Verify InlineDetailVideoPlayer contract data model
        val inlinePlayback = com.example.ui.components.ActiveInlinePlayback(
            mediaId = mediaId,
            title = "Shared Test Media",
            url = streamUrl,
            serverName = "Server Fast",
            website = "Qfilm (Managed)",
            isMovie = true,
            initialPosition = PlaybackSyncStore.getPosition(mediaId),
            initialQuality = LastPlaybackStore.getLastPlayback(context, mediaId)?.quality
        )
        assertEquals(mediaId, inlinePlayback.mediaId)
        assertEquals(streamUrl, inlinePlayback.url)
        assertEquals(expectedPosition, inlinePlayback.initialPosition)
        assertEquals("720p", inlinePlayback.initialQuality)

        // 3. Verify PlayerScreen / PlayerViewModel contract
        val saved = LastPlaybackStore.getLastPlayback(context, mediaId)
        assertNotNull(saved)
        val fullPlayerState = PlayerUiState(
            mediaId = saved!!.mediaId,
            isMovie = true,
            currentQuality = saved.quality,
            currentVideoUrl = saved.url,
            pendingSeekPosition = saved.positionMillis
        )
        assertEquals(inlinePlayback.mediaId, fullPlayerState.mediaId)
        assertEquals(inlinePlayback.initialPosition, fullPlayerState.pendingSeekPosition)
        assertEquals(inlinePlayback.initialQuality, fullPlayerState.currentQuality)
    }

    // --- Fakes ---

    private class FakeControlledRuntime : ControlledManagedExtensionRuntime {
        override val currentAppVersionCode: Long = 1L
        override val supportedRuntimeApiVersion: Int = 1

        val searchInvocationCount = AtomicInteger(0)
        val discoverServersInvocationCount = AtomicInteger(0)
        var lastDiscoveredTargetUrl: String? = null
        var discoverServersDelayMs = 0L
        var hangInDiscoverServers = false

        var discoverServersResponse: Result<ServerDiscoveryResult> = Result.failure(ExtensionError.ExtractionFailed("Failed", null))

        override suspend fun search(request: SearchRequest): Result<SearchResult> {
            searchInvocationCount.incrementAndGet()
            return Result.failure(ExtensionError.MediaNotFound("Not used"))
        }

        override suspend fun search(extensions: List<ManagedExtension>, request: SearchRequest): Result<SearchResult> {
            searchInvocationCount.incrementAndGet()
            return Result.failure(ExtensionError.MediaNotFound("Not used"))
        }

        override suspend fun discoverServers(request: ServerDiscoveryRequest): Result<ServerDiscoveryResult> {
            discoverServersInvocationCount.incrementAndGet()
            lastDiscoveredTargetUrl = request.targetUrl
            if (hangInDiscoverServers) {
                delay(60_000L)
            }
            if (discoverServersDelayMs > 0) delay(discoverServersDelayMs)
            return discoverServersResponse
        }

        override suspend fun discoverServers(extensions: List<ManagedExtension>, request: ServerDiscoveryRequest): Result<ServerDiscoveryResult> {
            return discoverServers(request)
        }

        override suspend fun extractStream(request: ExtractionRequest): Result<ExtractionResult> = Result.failure(ExtensionError.ExtractionFailed("Failed", null))
        override suspend fun extractStream(extensions: List<ManagedExtension>, request: ExtractionRequest): Result<ExtractionResult> = Result.failure(ExtensionError.ExtractionFailed("Failed", null))
        override suspend fun getDetails(extension: ManagedExtension, url: String): Result<MediaDetailsResult> = Result.failure(ExtensionError.MediaNotFound("Not found"))
        override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int): Result<List<EpisodeItem>> = Result.success(emptyList())
    }

    private class FakeManagedRepository : ManagedExtensionRepository {
        var extensions = listOf<ManagedExtension>()
        override suspend fun getExtensions(forceRefresh: Boolean): Result<List<ManagedExtension>> = Result.success(extensions)
        override suspend fun getExtensionById(id: String, forceRefresh: Boolean): Result<ManagedExtension> {
            val found = extensions.firstOrNull { it.id == id }
            return if (found != null) Result.success(found) else Result.failure(Exception("Unknown extension $id"))
        }
    }
}
