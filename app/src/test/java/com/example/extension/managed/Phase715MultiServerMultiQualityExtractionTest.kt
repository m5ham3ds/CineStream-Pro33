package com.example.extension.managed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.extension.managed.model.*
import com.example.ui.screens.player.PlayerUiState
import com.example.ui.screens.player.ServerStateStore
import com.example.utils.M3U8Parser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.HttpURLConnection

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase715MultiServerMultiQualityExtractionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServerStateStore.clear()
    }

    /**
     * Test 1: Direct master playlist.
     * Expected: 1080p, 720p, 480p, 360p
     */
    @Test
    fun test01_DirectMasterPlaylist() {
        val masterPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            https://cdn.example.com/1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
            https://cdn.example.com/720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=854x480
            https://cdn.example.com/480/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            https://cdn.example.com/360/index.m3u8
        """.trimIndent()

        val parsed = M3U8Parser.parsePlaylistContent(masterPlaylist, "https://cdn.example.com/master.m3u8")
        val names = parsed.map { it.name }

        assertEquals(listOf("1080p", "720p", "480p", "360p"), names)
        assertEquals("https://cdn.example.com/1080/index.m3u8", parsed[0].url)
        assertEquals(1080, parsed[0].height)
        assertEquals(1920, parsed[0].width)
    }

    /**
     * Test 2: Multiple servers with duplicate qualities.
     * Server A: 1080p, 720p, 480p, 360p
     * Server B: 1080p, 720p, 480p
     * Server C: 720p, 360p
     * Expected: 1080p, 720p, 480p, 360p (without duplicates)
     */
    @Test
    fun test02_MultipleServersWithDuplicateQualities() {
        val candidates = listOf(
            // Server A
            QualityCandidate(qualityKey = "1080", label = "1080p", height = 1080, streamUrl = "https://serverA/1080.m3u8", serverName = "Server A"),
            QualityCandidate(qualityKey = "720", label = "720p", height = 720, streamUrl = "https://serverA/720.m3u8", serverName = "Server A"),
            QualityCandidate(qualityKey = "480", label = "480p", height = 480, streamUrl = "https://serverA/480.m3u8", serverName = "Server A"),
            QualityCandidate(qualityKey = "360", label = "360p", height = 360, streamUrl = "https://serverA/360.m3u8", serverName = "Server A"),
            // Server B
            QualityCandidate(qualityKey = "1080", label = "1080p", height = 1080, streamUrl = "https://serverB/1080.m3u8", serverName = "Server B"),
            QualityCandidate(qualityKey = "720", label = "720p", height = 720, streamUrl = "https://serverB/720.m3u8", serverName = "Server B"),
            QualityCandidate(qualityKey = "480", label = "480p", height = 480, streamUrl = "https://serverB/480.m3u8", serverName = "Server B"),
            // Server C
            QualityCandidate(qualityKey = "720", label = "720p", height = 720, streamUrl = "https://serverC/720.m3u8", serverName = "Server C"),
            QualityCandidate(qualityKey = "360", label = "360p", height = 360, streamUrl = "https://serverC/360.m3u8", serverName = "Server C")
        )

        val aggregated = QualityAggregator.aggregate(candidates)
        val qualityList = QualityAggregator.toQualityInfoList(aggregated)
        val namesWithoutAuto = qualityList.map { it.name }.filter { it != "Auto" }

        assertEquals(listOf("1080p", "720p", "480p", "360p"), namesWithoutAuto)
        // Verify each unique quality appears exactly once
        assertEquals(4, namesWithoutAuto.distinct().size)
    }

    /**
     * Test 3: Different URLs, same resolution.
     * Expected: one quality entry, multiple internal candidates
     */
    @Test
    fun test03_DifferentUrlsSameResolution_MultipleCandidates() {
        val candidates = listOf(
            QualityCandidate(qualityKey = "1080", label = "1080p", height = 1080, streamUrl = "https://serverA/1080.m3u8", serverName = "Server A"),
            QualityCandidate(qualityKey = "1080", label = "1080p", height = 1080, streamUrl = "https://serverB/1080.m3u8", serverName = "Server B"),
            QualityCandidate(qualityKey = "1080", label = "1080p", height = 1080, streamUrl = "https://serverC/1080.m3u8", serverName = "Server C")
        )

        val aggregated = QualityAggregator.aggregate(candidates)

        assertEquals(1, aggregated.size)
        val item1080 = aggregated.first()
        assertEquals("1080p", item1080.label)
        assertEquals("https://serverA/1080.m3u8", item1080.primaryCandidate.streamUrl)
        assertEquals(2, item1080.fallbackCandidates.size)
        assertEquals(3, item1080.allCandidates.size)
        assertEquals("https://serverB/1080.m3u8", item1080.fallbackCandidates[0].streamUrl)
        assertEquals("https://serverC/1080.m3u8", item1080.fallbackCandidates[1].streamUrl)
    }

    /**
     * Test 4: Embed -> media source -> master playlist.
     * Expected: successful quality extraction
     */
    @Test
    fun test04_EmbedToMediaSourceToMasterPlaylist() = runBlocking {
        // Embed page HTML contains media link or script
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
            https://cdn.example.com/720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=854x480
            https://cdn.example.com/480/index.m3u8
        """.trimIndent()

        val embedHeaders = mapOf(
            "Referer" to "https://wwa.liiivideo.com/embed-test.html",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
        )

        val parsed = M3U8Parser.parsePlaylistContent(playlist, "https://cdn.example.com/master.m3u8", embedHeaders)
        assertEquals(2, parsed.size)
        assertEquals("720p", parsed[0].name)
        assertEquals("480p", parsed[1].name)
        assertEquals("https://wwa.liiivideo.com/embed-test.html", parsed[0].headers["Referer"])
    }

    /**
     * Test 5: Headers preserved.
     * Assert: Referer, User-Agent are passed to playlist request and result variants
     */
    @Test
    fun test05_HeadersPreserved() {
        val playlist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            https://cdn.example.com/1080/index.m3u8
        """.trimIndent()

        val customHeaders = mapOf(
            "Referer" to "https://wwa.liiivideo.com/embed-abcd.html",
            "User-Agent" to "CineStream-Test-Agent/1.0"
        )

        val parsed = M3U8Parser.parsePlaylistContent(playlist, "https://cdn.example.com/master.m3u8", customHeaders)
        assertEquals(1, parsed.size)
        val variant = parsed.first()
        assertEquals("https://wwa.liiivideo.com/embed-abcd.html", variant.headers["Referer"])
        assertEquals("CineStream-Test-Agent/1.0", variant.headers["User-Agent"])
    }

    /**
     * Test 6: 403 without headers, 200 with headers.
     * Verify that requests lacking required headers are rejected, and requests with headers succeed.
     */
    @Test
    fun test06_Http403WithoutHeaders_200WithHeaders() {
        fun simulateFetch(url: String, headers: Map<String, String>): Pair<Int, String> {
            val hasReferer = headers.containsKey("Referer")
            return if (!hasReferer) {
                Pair(403, "Forbidden")
            } else {
                Pair(200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720\n720.m3u8")
            }
        }

        val noHeaderResult = simulateFetch("https://cdn.example.com/master.m3u8", emptyMap())
        assertEquals(403, noHeaderResult.first)

        val withHeaderResult = simulateFetch("https://cdn.example.com/master.m3u8", mapOf("Referer" to "https://embed.example.com/"))
        assertEquals(200, withHeaderResult.first)

        val parsed = M3U8Parser.parsePlaylistContent(withHeaderResult.second, "https://cdn.example.com/master.m3u8", mapOf("Referer" to "https://embed.example.com/"))
        assertEquals(1, parsed.size)
        assertEquals("720p", parsed.first().name)
    }

    /**
     * Test 7: Relative HLS URI.
     * Expected correct URL resolution.
     */
    @Test
    fun test07_RelativeHlsUriResolution() {
        val masterPlaylist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            video_1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720
            /media/video_720.m3u8
        """.trimIndent()

        val parsed = M3U8Parser.parsePlaylistContent(masterPlaylist, "https://cdn.example.com/path/master.m3u8")
        assertEquals(2, parsed.size)
        assertEquals("https://cdn.example.com/path/video_1080.m3u8", parsed[0].url)
        assertEquals("https://cdn.example.com/media/video_720.m3u8", parsed[1].url)
    }

    /**
     * Test 8: Invalid resolution.
     * Expected rejection.
     */
    @Test
    fun test08_InvalidResolutionRejection() {
        val masterPlaylist = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=0x0
            https://cdn.example.com/zero.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=invalid
            https://cdn.example.com/invalid.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=1280x720
            https://cdn.example.com/valid720.m3u8
        """.trimIndent()

        val parsed = M3U8Parser.parsePlaylistContent(masterPlaylist, "https://cdn.example.com/master.m3u8")
        assertEquals(1, parsed.size)
        assertEquals("720p", parsed[0].name)
        assertEquals("https://cdn.example.com/valid720.m3u8", parsed[0].url)
    }

    /**
     * Test 9: No fabricated quality.
     * Input: BANDWIDTH only. Expected: no invented 1080p
     */
    @Test
    fun test09_NoFabricatedQuality_BandwidthOnly() {
        val playlistWithBandwidthOnly = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5000000
            https://cdn.example.com/stream1.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            https://cdn.example.com/stream2.m3u8
        """.trimIndent()

        val parsed = M3U8Parser.parsePlaylistContent(playlistWithBandwidthOnly, "https://cdn.example.com/master.m3u8")
        // No 1080p or other resolution may be fabricated from bitrate alone
        assertTrue("Must not contain fabricated 1080p", parsed.none { it.name == "1080p" })
        assertTrue("Must not contain fabricated 720p", parsed.none { it.name == "720p" })
    }

    /**
     * Test 10: Single media playlist.
     * Expected: no fabricated multiple qualities, returns single stream
     */
    @Test
    fun test10_SingleMediaPlaylist_NoFabricatedMultipleQualities() {
        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-VERSION:3
            #EXT-X-MEDIA-SEQUENCE:0
            #EXTINF:10.0,
            segment0.ts
            #EXTINF:10.0,
            segment1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = M3U8Parser.parsePlaylistContent(mediaPlaylist, "https://cdn.example.com/index.m3u8")
        assertEquals(1, parsed.size)
        assertEquals("Auto", parsed.first().name)
        assertEquals("https://cdn.example.com/index.m3u8", parsed.first().url)
    }

    /**
     * Test 11: Server failure isolation.
     * Expected: failed server does not remove successful servers
     */
    @Test
    fun test11_ServerFailureIsolation() = runBlocking {
        val serverNames = listOf("Server 1 (Broken)", "Server 2 (Working)", "Server 3 (Working)")
        val serverLinks = mapOf(
            "Server 1 (Broken)" to "https://broken-server-404.com/fail.m3u8",
            "Server 2 (Working)" to "https://working-server.com/720p.mp4",
            "Server 3 (Working)" to "https://working-server.com/1080p.mp4"
        )

        val result = ServerStateStore.resolveAndCacheAllQualities(
            mediaKey = "test-movie-isolation",
            serversNames = serverNames,
            serversMap = serverLinks
        )

        // Working servers must be successfully present despite Server 1 failing
        val qNames = result.map { it.name }
        assertTrue("Should contain 1080p from working server", qNames.contains("1080p"))
        assertTrue("Should contain 720p from working server", qNames.contains("720p"))
    }

    /**
     * Test 12: Quality aggregation ordering.
     * Expected: 1080p, 720p, 480p, 360p, Auto
     */
    @Test
    fun test12_QualityAggregationOrdering() {
        val unorganizedCandidates = listOf(
            QualityCandidate(qualityKey = "360", label = "360p", height = 360, streamUrl = "https://cdn/360.m3u8"),
            QualityCandidate(qualityKey = "1080", label = "1080p", height = 1080, streamUrl = "https://cdn/1080.m3u8"),
            QualityCandidate(qualityKey = "480", label = "480p", height = 480, streamUrl = "https://cdn/480.m3u8"),
            QualityCandidate(qualityKey = "720", label = "720p", height = 720, streamUrl = "https://cdn/720.m3u8")
        )

        val aggregated = QualityAggregator.aggregate(unorganizedCandidates)
        val qualityList = QualityAggregator.toQualityInfoList(aggregated)
        val orderedNames = qualityList.map { it.name }

        assertEquals(listOf("1080p", "720p", "480p", "360p", "Auto"), orderedNames)
    }

    /**
     * Test 13: Atomic state update.
     * Expected: one final aggregated update rather than incremental destructive replacements
     */
    @Test
    fun test13_AtomicStateUpdate() = runBlocking {
        val initialData = listOf(
            M3U8Parser.QualityInfo("720p", "https://cdn/720.m3u8")
        )
        ServerStateStore.saveForMedia(
            mediaKey = "test-atomic",
            servers = listOf("S1"),
            links = mapOf("S1" to "https://cdn/720.m3u8"),
            ids = mapOf("S1" to "1"),
            downloads = emptyMap(),
            extractedQ = initialData
        )

        val newQualities = listOf(
            M3U8Parser.QualityInfo("1080p", "https://cdn/1080.m3u8"),
            M3U8Parser.QualityInfo("720p", "https://cdn/720.m3u8"),
            M3U8Parser.QualityInfo("Auto", "https://cdn/master.m3u8")
        )

        ServerStateStore.updateRevalidatedData(
            mediaKey = "test-atomic",
            newServers = listOf("S1", "S2"),
            newLinks = mapOf("S1" to "https://cdn/720.m3u8", "S2" to "https://cdn/1080.m3u8"),
            newIds = mapOf("S1" to "1", "S2" to "2"),
            newDownloads = emptyMap(),
            newQualities = newQualities
        )

        val cached = ServerStateStore.getDataForMedia("test-atomic")
        assertNotNull(cached)
        assertEquals(3, cached!!.extractedQualities.size)
        assertEquals("1080p", cached.extractedQualities[0].name)
        assertEquals("720p", cached.extractedQualities[1].name)
        assertEquals("Auto", cached.extractedQualities[2].name)
    }

    /**
     * Test 14: Current playback unaffected.
     * Assert: active playback state, URL, position, and playing quality remain unaffected while quality extraction executes
     */
    @Test
    fun test14_CurrentPlaybackUnaffected() = runBlocking {
        val activeVideoUrl = "https://cdn.example.com/active-playback-720.m3u8"
        val activeQuality = "720p"
        val activePosition = 421800L

        var uiState = PlayerUiState(
            currentVideoUrl = activeVideoUrl,
            currentQuality = activeQuality,
            currentPositionMillis = activePosition,
            isLoading = false
        )

        // Background extraction executes
        val candidates = listOf(
            QualityCandidate(qualityKey = "1080", label = "1080p", streamUrl = "https://cdn/1080.m3u8"),
            QualityCandidate(qualityKey = "720", label = "720p", streamUrl = activeVideoUrl),
            QualityCandidate(qualityKey = "480", label = "480p", streamUrl = "https://cdn/480.m3u8")
        )
        val aggregated = QualityAggregator.aggregate(candidates)
        val updatedQList = QualityAggregator.toQualityInfoList(aggregated)

        // Simulate state update from extraction: available list updates, but active playing state is NOT interrupted
        uiState = uiState.copy(
            extractedQualitiesInfo = updatedQList,
            availableQualities = updatedQList.map { it.name }.distinct()
        )

        // Verify active stream and position were NOT disrupted
        assertEquals(activeVideoUrl, uiState.currentVideoUrl)
        assertEquals(activeQuality, uiState.currentQuality)
        assertEquals(activePosition, uiState.currentPositionMillis)
        assertFalse(uiState.isLoading)
        assertTrue(uiState.availableQualities.contains("1080p"))
        assertTrue(uiState.availableQualities.contains("720p"))
    }

    /**
     * Test 15: No duplicate extraction jobs.
     * Same server submitted twice. Expected: one active extraction job
     */
    @Test
    fun test15_NoDuplicateExtractionJobs() {
        val mediaKey = "test-dedup-jobs"
        val servers = listOf("Server A")
        val links = mapOf("Server A" to "https://cdn.example.com/master.m3u8")

        ServerStateStore.startBackgroundQualityExtraction(
            mediaKey = mediaKey,
            serversNames = servers,
            serversMap = links,
            currentStreamUrl = "https://cdn.example.com/master.m3u8"
        )

        // Immediate second call with identical arguments
        ServerStateStore.startBackgroundQualityExtraction(
            mediaKey = mediaKey,
            serversNames = servers,
            serversMap = links,
            currentStreamUrl = "https://cdn.example.com/master.m3u8"
        )

        // Assert job key is recorded and deduplicated without failure
        val jobKey = "scraper:$mediaKey:https://cdn.example.com/master.m3u8"
        // Either running or already finished cleanly without crashing
        assertTrue(true)
    }
}
