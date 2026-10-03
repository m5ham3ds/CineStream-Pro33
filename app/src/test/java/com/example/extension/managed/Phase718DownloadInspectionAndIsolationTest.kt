package com.example.extension.managed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.extension.managed.model.QualityCandidate
import com.example.extension.managed.model.QualityEvidence
import com.example.ui.screens.player.MediaServerData
import com.example.ui.screens.player.ServerStateStore
import com.example.utils.M3U8Parser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase718DownloadInspectionAndIsolationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ServerStateStore.clear()
    }

    @After
    fun tearDown() {
        ServerStateStore.clear()
    }

    @Test
    fun test01_getCachedData_neverFallsBackToUnrelatedMediaKey() {
        // Given: User watched or inspected Movie A
        val movieAKey = "Spider-Man-true-1-1"
        val movieAId = "550"
        val movieAQualities = listOf(
            M3U8Parser.QualityInfo("1080p", "https://cdn.example.com/spiderman_1080.m3u8"),
            M3U8Parser.QualityInfo("720p", "https://cdn.example.com/spiderman_720.m3u8")
        )
        val movieACandidates = mapOf(
            "1080" to listOf(
                QualityCandidate(
                    qualityKey = "1080",
                    label = "1080p",
                    streamUrl = "https://cdn.example.com/spiderman_1080.m3u8",
                    evidence = QualityEvidence.RESOLUTION
                )
            ),
            "720" to listOf(
                QualityCandidate(
                    qualityKey = "720",
                    label = "720p",
                    streamUrl = "https://cdn.example.com/spiderman_720.m3u8",
                    evidence = QualityEvidence.RESOLUTION
                )
            )
        )

        ServerStateStore.saveForMedia(
            mediaKey = movieAKey,
            servers = listOf("Server 1", "Server 2"),
            links = mapOf("Server 1" to "https://srv1.com/embed"),
            ids = mapOf("Server 1" to "1"),
            downloads = emptyMap(),
            extractedQ = movieAQualities,
            internalCand = movieACandidates,
            website = "Qfilm",
            altKeys = listOf(movieAId)
        )

        assertEquals(movieAKey, ServerStateStore.currentMediaKey)
        assertNotNull(ServerStateStore.getCachedData(movieAKey))
        assertNotNull(ServerStateStore.getCachedData(movieAId))

        // When: User navigates to Movie B (Batman) which has never been inspected or played
        val movieBKey = "The Batman-true-1-1"
        val movieBId = "999"

        // Then: Querying cached data for Movie B MUST return null, NOT Movie A's data!
        val cachedForMovieB = ServerStateStore.getCachedData(movieBKey, movieBId)
        assertNull("Crucial: Movie B must NOT receive Movie A's cached data!", cachedForMovieB)

        val hasServersForB = ServerStateStore.hasExtractedServers(movieBKey, movieBId)
        assertFalse("Crucial: hasExtractedServers for Movie B must be false!", hasServersForB)

        val directDataForB = ServerStateStore.getDataForMedia(movieBKey)
        assertNull("getDataForMedia for Movie B must be null!", directDataForB)
    }

    @Test
    fun test02_prepareForMedia_strictlyClearsActiveStateWhenNavigatingToNewMedia() {
        // Given: Active store contains Movie A state
        val movieAKey = "Avatar-true-1-1"
        ServerStateStore.saveForMedia(
            mediaKey = movieAKey,
            servers = listOf("SrvA"),
            links = mapOf("SrvA" to "https://srva.com"),
            ids = emptyMap(),
            downloads = emptyMap(),
            extractedQ = listOf(M3U8Parser.QualityInfo("1080p", "https://avatar.m3u8")),
            internalCand = mapOf(
                "1080" to listOf(
                    QualityCandidate(
                        qualityKey = "1080",
                        label = "1080p",
                        streamUrl = "https://avatar.m3u8",
                        evidence = QualityEvidence.RESOLUTION
                    )
                )
            ),
            website = "EgyDead"
        )

        assertEquals(1, ServerStateStore.extractedQualities.size)
        assertEquals(1, ServerStateStore.extractedServers.size)
        assertEquals(1, ServerStateStore.internalCandidates.size)

        // When: Preparing for Movie B (Interstellar) which is not in cache
        val movieBKey = "Interstellar-true-1-1"
        ServerStateStore.prepareForMedia(movieBKey, "interstellar_id")

        // Then: All active variables and flows MUST be completely empty and reset to Movie B
        assertEquals(movieBKey, ServerStateStore.currentMediaKey)
        assertTrue("Extracted qualities must be cleared for new media!", ServerStateStore.extractedQualities.isEmpty())
        assertTrue("Extracted servers must be cleared for new media!", ServerStateStore.extractedServers.isEmpty())
        assertTrue("Internal candidates must be cleared for new media!", ServerStateStore.internalCandidates.isEmpty())
        assertTrue("Qualities flow value must be cleared!", ServerStateStore.extractedQualitiesFlow.value.isEmpty())
        assertTrue("Servers flow value must be cleared!", ServerStateStore.serversFlow.value.isEmpty())
    }

    @Test
    fun test03_prepareForMedia_restoresCachedDataWhenNavigatingBack() {
        // Given: Movie A is in cache
        val movieAKey = "MovieA-true-1-1"
        val movieAQualities = listOf(M3U8Parser.QualityInfo("720p", "https://moviea/720.m3u8"))
        ServerStateStore.saveForMedia(
            mediaKey = movieAKey,
            servers = listOf("S1"),
            links = mapOf("S1" to "https://s1"),
            ids = emptyMap(),
            downloads = emptyMap(),
            extractedQ = movieAQualities,
            website = "Qfilm"
        )

        // When: Navigate to Movie B (clean reset)
        ServerStateStore.prepareForMedia("MovieB-true-1-1")
        assertTrue(ServerStateStore.extractedQualities.isEmpty())

        // And then: Navigate back to Movie A
        ServerStateStore.prepareForMedia(movieAKey)

        // Then: Movie A state is instantly restored from cache
        assertEquals(movieAKey, ServerStateStore.currentMediaKey)
        assertEquals(1, ServerStateStore.extractedQualities.size)
        assertEquals("720p", ServerStateStore.extractedQualities.first().name)
        assertEquals(1, ServerStateStore.extractedQualitiesFlow.value.size)
    }

    @Test
    fun test04_inspectionResultIsCachedAndSharedWithDetailsScreenAndPlayAction() = runBlocking {
        // Given: A movie that has not been played yet
        val movieKey = "Inception-true-1-1"
        val movieId = "140607"
        val directStreamUrl = "https://cdn.stream.com/inception/master.m3u8"
        val embedServerLink = "https://embed.domain.com/v/12345"

        // When: Download inspection resolves servers and qualities
        val candidates = listOf(
            QualityCandidate(
                qualityKey = "1080",
                label = "1080p",
                streamUrl = "https://cdn.stream.com/inception/1080.m3u8",
                serverName = "FastServer",
                evidence = QualityEvidence.RESOLUTION
            ),
            QualityCandidate(
                qualityKey = "720",
                label = "720p",
                streamUrl = "https://cdn.stream.com/inception/720.m3u8",
                serverName = "FastServer",
                evidence = QualityEvidence.RESOLUTION
            )
        )
        val qualities = listOf(
            M3U8Parser.QualityInfo("1080p", "https://cdn.stream.com/inception/1080.m3u8"),
            M3U8Parser.QualityInfo("720p", "https://cdn.stream.com/inception/720.m3u8")
        )

        ServerStateStore.saveForMedia(
            mediaKey = movieKey,
            servers = listOf("FastServer"),
            links = mapOf("FastServer" to embedServerLink),
            ids = mapOf("FastServer" to "srv1"),
            downloads = emptyMap(),
            extractedQ = qualities,
            internalCand = mapOf("1080" to listOf(candidates[0]), "720" to listOf(candidates[1])),
            website = "Qfilm",
            altKeys = listOf(movieId)
        )

        // Then: Both the download dialog AND the details screen have access to the cached data!
        val cachedForDetails = ServerStateStore.getCachedData(movieId, movieKey)
        assertNotNull(cachedForDetails)
        assertEquals(2, cachedForDetails!!.extractedQualities.size)
        assertEquals("1080p", cachedForDetails.extractedQualities.first().name)
        assertEquals("https://cdn.stream.com/inception/1080.m3u8", cachedForDetails.extractedQualities.first().url)

        // And: The details screen resume / play action directly resolves the playable URL
        val lastPlayback = null
        val cachedUrl = if (cachedForDetails.extractedQualities.isNotEmpty() && cachedForDetails.extractedQualities.first().url.isNotBlank()) {
            cachedForDetails.extractedQualities.first().url
        } else if (cachedForDetails.serverLinks.isNotEmpty()) {
            cachedForDetails.serverLinks.values.firstOrNull()
        } else null

        assertNotNull("Details screen must resolve cachedUrl immediately from download inspection!", cachedUrl)
        assertEquals("https://cdn.stream.com/inception/1080.m3u8", cachedUrl)
    }

    @Test
    fun test05_episodeIsolation_preventsEpisodeDataBleeding() {
        val seriesTitle = "Breaking Bad"
        val seriesId = "1396"
        val ep1Key = "$seriesTitle-false-1-1"
        val ep1Id = "${seriesId}_1"
        val ep2Key = "$seriesTitle-false-1-2"
        val ep2Id = "${seriesId}_2"

        // Cache Episode 1
        ServerStateStore.saveForMedia(
            mediaKey = ep1Key,
            servers = listOf("Ep1Server"),
            links = mapOf("Ep1Server" to "https://ep1.com"),
            ids = emptyMap(),
            downloads = emptyMap(),
            extractedQ = listOf(M3U8Parser.QualityInfo("1080p", "https://ep1/1080.m3u8")),
            altKeys = listOf(ep1Id)
        )

        // Prepare for Episode 2
        ServerStateStore.prepareForMedia(ep2Key, ep2Id, seriesId)

        // Episode 2 must NOT have Episode 1 cached data
        val ep2Cache = ServerStateStore.getCachedData(ep2Key, ep2Id)
        assertNull("Episode 2 must not see Episode 1's cache!", ep2Cache)
        assertTrue(ServerStateStore.extractedQualities.isEmpty())
    }
}
