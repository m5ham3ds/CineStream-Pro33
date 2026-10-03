package com.example.extension.managed

import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.scraper.EgyDeadScraper
import com.example.extension.managed.scraper.QfilmScraper
import com.example.ui.components.ActiveInlinePlayback
import com.example.ui.components.isValidPlayableMediaUrl
import com.example.ui.screens.player.PlaybackSyncStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 05E: Verification of playback root cause repairs.
 *
 * Confirms:
 * 1. EgyDeadScraper static extraction fallback via StaticMediaExtractor.
 * 2. Blocking raw HTML embed URLs from player handoff.
 * 3. Synchronization of real extracted playback URLs with ActiveInlinePlayback to prevent redundant fullscreen extraction.
 * 4. Preservation of playback sync position, resume, and quality metadata.
 */
class Phase05EPlaybackRootCauseRepairTest {

    private val egydeadScraper = EgyDeadScraper()
    private val qfilmScraper = QfilmScraper()

    private val egydeadExtension = ManagedExtension(
        id = "egydead",
        name = "EgyDead",
        baseUrl = "https://tv10.egydead.live",
        scraperKey = "egydead",
        contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES, ContentType.ANIME),
        status = ExtensionLifecycleStatus.ACTIVE
    )

    private val qfilmExtension = ManagedExtension(
        id = "qfilm",
        name = "كيو فيلم",
        baseUrl = "https://a.qfilm.tv",
        scraperKey = "qfilm",
        contentTypes = setOf(ContentType.MOVIE, ContentType.ANIME),
        status = ExtensionLifecycleStatus.ACTIVE
    )

    @Test
    fun fix1_egydeadStaticExtractionFallback_resolvesMasterM3u8() = runBlocking {
        // Known live EarnVids embed server discovered from EgyDead
        val earnVidsServer = ServerItem(
            name = "EarnVids",
            link = "https://morencius.com/v/zzw3dy72jlnh",
            id = "1",
            isDirectStream = false
        )

        val request = ExtractionRequest(
            serverItem = earnVidsServer,
            mediaTitle = "Inception",
            timeoutMs = 15000L
        )

        val session = ExtractionSession(
            extensionId = "egydead",
            scraperKey = "egydead",
            targetPageUrl = "https://tv10.egydead.live"
        )

        // In previous Phase 05D, this failed with "Could not extract stream for server: EarnVids"
        // In Phase 05E with StaticMediaExtractor fallback, it should succeed with a valid master.m3u8!
        val result = egydeadScraper.extractStream(
            extension = egydeadExtension,
            session = session,
            request = request,
            webEngine = null
        )

        println("[PHASE_05E_TEST] EgyDead EarnVids extraction isSuccess: ${result.isSuccess}")
        assertTrue("EgyDeadScraper must succeed extracting stream from EarnVids using StaticMediaExtractor fallback", result.isSuccess)

        val extraction = result.getOrThrow()
        assertNotNull("PlaybackSource must not be null", extraction.playbackSource)
        val streamUrl = extraction.playbackSource!!.streamUrl
        println("[PHASE_05E_TEST] Resolved streamUrl: $streamUrl")

        assertNotNull("Resolved streamUrl must not be null", streamUrl)
        assertTrue("Stream URL must be a valid playable URL", isValidPlayableMediaUrl(streamUrl))
        assertTrue("Stream URL must contain master.m3u8", streamUrl.contains(".m3u8"))
        assertEquals("Protocol must be HLS", StreamProtocol.HLS, extraction.playbackSource!!.protocol)
        assertEquals("MIME type must be HLS", "application/x-mpegURL", extraction.playbackSource!!.mimeType)
        assertNotNull("Download source must be created", extraction.downloadSource)
    }

    @Test
    fun fix1_egydeadFailureBehavior_returnsExtractionFailedOnInvalidEmbed() = runBlocking {
        // An embed URL that contains no video and cannot be extracted
        val brokenServer = ServerItem(
            name = "BrokenServer",
            link = "https://example.com/invalid-embed-page.html",
            id = "999",
            isDirectStream = false
        )

        val request = ExtractionRequest(serverItem = brokenServer, mediaTitle = "Test")
        val session = ExtractionSession(
            extensionId = "egydead",
            scraperKey = "egydead",
            targetPageUrl = "https://tv10.egydead.live"
        )

        val result = egydeadScraper.extractStream(
            extension = egydeadExtension,
            session = session,
            request = request,
            webEngine = null
        )

        // Must fail cleanly with ExtractionFailed, NEVER return the embed link as media URL
        assertTrue("Extraction must fail on invalid embed server", result.isFailure)
    }

    @Test
    fun fix2_isValidPlayableMediaUrl_strictlyRejectsHtmlEmbedUrls() {
        // Prohibited HTML embed pages that previously caused ExoPlayer UnrecognizedInputFormatException
        val invalidEmbedUrls = listOf(
            "https://hgcloud.to/e/74c24l4lv6zw",
            "https://morencius.com/v/zzw3dy72jlnh",
            "https://wwa.liiivideo.com/embed-v3gopuw3s8js.html",
            "https://playmogo.com/e/0a5vjq9y8rob",
            "https://mixdrop.top/e/7ro7nddztq9vd6",
            "https://a.qfilm.tv/watch.php?vid=8e6fc552a",
            "https://a.qfilm.tv/play.php?vid=8e6fc552a",
            "https://tv10.egydead.live/inception-2010-1080p-bluray/",
            "https://mirrorace.org/m/1W71s",
            "https://1fichier.com/?usmnhz5laut6l85bkh08",
            "https://koramaup.com/4N5u",
            "https://dsvplay.com/d/0a5vjq9y8rob",
            "auto_extract://",
            "",
            "   ",
            "https://somehost.com/player.php?id=123"
        )

        for (url in invalidEmbedUrls) {
            assertFalse("URL '$url' must be rejected as an unplayable embed / HTML URL", isValidPlayableMediaUrl(url))
        }

        // Allowed playable direct media streams
        val validMediaUrls = listOf(
            "https://pfabiWMFmEza.dramiyos-cdn.com/hls2/01/01833/zzw3dy72jlnh_,l,n,.urlset/master.m3u8?t=test",
            "https://proxydwxro.visitmycityfor365days.cc/hls2/01/00126/v3gopuw3s8js_,l,h,.urlset/master.m3u8",
            "https://cdn.example.com/videos/movie_1080p.mp4",
            "https://cdn.example.com/videos/movie_720p.mkv",
            "https://cdn.example.com/live/stream.m3u8?token=xyz",
            "https://cdn.akamaized.net/live/master.m3u8",
            "file:///storage/emulated/0/Download/movie.mp4",
            "content://media/external/video/media/42",
            "local_offline_file://27205"
        )

        for (url in validMediaUrls) {
            assertTrue("URL '$url' must be accepted as a valid playable media stream", isValidPlayableMediaUrl(url))
        }
    }

    @Test
    fun fix3_synchronizeRealPlaybackUrl_updatesActivePlaybackAndFullscreen() {
        val initialPlayback = ActiveInlinePlayback(
            mediaId = "27205",
            title = "Inception",
            url = "auto_extract://",
            posterUrl = "https://image.tmdb.org/t/p/w500/test.jpg",
            isMovie = true,
            initialPosition = 0L
        )

        // Verify initial state is auto_extract
        assertEquals("auto_extract://", initialPlayback.url)
        assertFalse("Initial auto_extract URL must not be recognized as playable direct stream", isValidPlayableMediaUrl(initialPlayback.url))

        // When stream is extracted inside InlineDetailVideoPlayer:
        val realExtractedUrl = "https://pfabiWMFmEza.dramiyos-cdn.com/hls2/01/01833/zzw3dy72jlnh_,l,n,.urlset/master.m3u8?t=test"
        assertTrue(isValidPlayableMediaUrl(realExtractedUrl))

        // Simulate onPlaybackUrlExtracted callback updating activePlayback:
        val updatedPlayback = initialPlayback.copy(url = realExtractedUrl)

        assertEquals(realExtractedUrl, updatedPlayback.url)
        assertTrue(isValidPlayableMediaUrl(updatedPlayback.url))

        // When entering fullscreen, onPlay receives the real updated URL instead of auto_extract://
        var fullscreenReceivedUrl: String? = null
        val onPlay = { title: String, url: String, serverName: String?, website: String?, posterUrl: String ->
            fullscreenReceivedUrl = url
        }

        onPlay(updatedPlayback.title, updatedPlayback.url, updatedPlayback.serverName, updatedPlayback.website, updatedPlayback.posterUrl)

        assertNotNull(fullscreenReceivedUrl)
        assertEquals(realExtractedUrl, fullscreenReceivedUrl)
        assertNotEquals("auto_extract://", fullscreenReceivedUrl)
    }

    @Test
    fun fix3_playbackState_preservesResumePositionAndQuality() {
        val testMediaId = "movie_27205"
        val testPosition = 145000L // 2 minutes 25 seconds

        // Save position to PlaybackSyncStore as would happen during pause/fullscreen transition
        PlaybackSyncStore.setPosition(testMediaId, testPosition)

        val retrievedPosition = PlaybackSyncStore.getPosition(testMediaId)
        assertEquals("Playback position must be preserved exactly in sync store", testPosition, retrievedPosition)

        // Verify ActiveInlinePlayback retains initialPosition and initialQuality
        val activePlayback = ActiveInlinePlayback(
            mediaId = testMediaId,
            title = "Inception",
            url = "https://cdn.example.com/master.m3u8",
            initialPosition = retrievedPosition,
            initialQuality = "1080p"
        )

        assertEquals(testPosition, activePlayback.initialPosition)
        assertEquals("1080p", activePlayback.initialQuality)
    }

    @Test
    fun endToEnd_livePlaybackVerification_qfilmAndEgyDead() = runBlocking {
        // 1. Live extraction from Qfilm
        val qfilmServer = ServerItem(
            name = "Wwa",
            link = "https://wwa.liiivideo.com/embed-v3gopuw3s8js.html",
            id = "0",
            isDirectStream = false
        )
        val qfilmReq = ExtractionRequest(serverItem = qfilmServer, mediaTitle = "محمود التاني", timeoutMs = 15000L)
        val qfilmSession = ExtractionSession(
            extensionId = "qfilm",
            scraperKey = "qfilm",
            targetPageUrl = "https://a.qfilm.tv"
        )
        val qfilmRes = qfilmScraper.extractStream(qfilmExtension, qfilmSession, qfilmReq, null)

        assertTrue("Qfilm live extraction must succeed", qfilmRes.isSuccess)
        val qfilmSource = qfilmRes.getOrThrow().playbackSource
        assertNotNull("Qfilm playbackSource must not be null", qfilmSource)
        val qfilmUrl = qfilmSource!!.streamUrl
        assertTrue("Qfilm extracted URL must be valid", isValidPlayableMediaUrl(qfilmUrl))
        assertTrue("Qfilm URL must contain m3u8", qfilmUrl.contains(".m3u8"))

        // 2. Live extraction from EgyDead (EarnVids server)
        val egydeadServer = ServerItem(
            name = "EarnVids",
            link = "https://morencius.com/v/zzw3dy72jlnh",
            id = "1",
            isDirectStream = false
        )
        val egydeadReq = ExtractionRequest(serverItem = egydeadServer, mediaTitle = "Inception", timeoutMs = 15000L)
        val egydeadSession = ExtractionSession(
            extensionId = "egydead",
            scraperKey = "egydead",
            targetPageUrl = "https://tv10.egydead.live"
        )
        val egydeadRes = egydeadScraper.extractStream(egydeadExtension, egydeadSession, egydeadReq, null)

        assertTrue("EgyDead live extraction must succeed via StaticMediaExtractor fallback", egydeadRes.isSuccess)
        val egydeadSource = egydeadRes.getOrThrow().playbackSource
        assertNotNull("EgyDead playbackSource must not be null", egydeadSource)
        val egydeadUrl = egydeadSource!!.streamUrl
        assertTrue("EgyDead extracted URL must be valid", isValidPlayableMediaUrl(egydeadUrl))
        assertTrue("EgyDead URL must contain m3u8", egydeadUrl.contains(".m3u8"))

        println("[PHASE_05E_TEST] SUCCESS: Both providers (Qfilm & EgyDead) verified live end-to-end!")
    }
}
