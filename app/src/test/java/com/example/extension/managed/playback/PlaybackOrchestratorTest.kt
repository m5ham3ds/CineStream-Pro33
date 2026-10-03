package com.example.extension.managed.playback

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.EpisodeItem
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ExtractionResult
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.MediaDetailsResult
import com.example.extension.managed.model.PlaybackSource
import com.example.extension.managed.model.QualitySource
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchMediaItem
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.SearchResult
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.model.ServerDiscoveryResult
import com.example.extension.managed.model.ServerItem
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.searchorder.ExtensionEligibilityFilter
import com.example.extension.managed.searchorder.SearchOrder
import com.example.extension.managed.searchorder.SearchOrderRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackOrchestratorTest {

    private lateinit var sufficiencyPolicy: QualitySufficiencyPolicy

    @Before
    fun setUp() {
        sufficiencyPolicy = QualitySufficiencyPolicy()
    }

    @Test
    fun qualitySufficiencyPolicy_classifiesCorrectTiers() {
        assertEquals(QualitySufficiencyPolicy.QualityTier.HIGH, QualitySufficiencyPolicy.classifyQuality("1080p"))
        assertEquals(QualitySufficiencyPolicy.QualityTier.HIGH, QualitySufficiencyPolicy.classifyQuality("FHD"))
        assertEquals(QualitySufficiencyPolicy.QualityTier.HIGH, QualitySufficiencyPolicy.classifyQuality("4K"))
        assertEquals(QualitySufficiencyPolicy.QualityTier.MEDIUM, QualitySufficiencyPolicy.classifyQuality("720p"))
        assertEquals(QualitySufficiencyPolicy.QualityTier.MEDIUM, QualitySufficiencyPolicy.classifyQuality("HD"))
        assertEquals(QualitySufficiencyPolicy.QualityTier.LOW, QualitySufficiencyPolicy.classifyQuality("480p"))
        assertEquals(QualitySufficiencyPolicy.QualityTier.LOW, QualitySufficiencyPolicy.classifyQuality("360p"))
        assertEquals(QualitySufficiencyPolicy.QualityTier.LOW, QualitySufficiencyPolicy.classifyQuality("SD"))
        assertEquals(QualitySufficiencyPolicy.QualityTier.AUTO, QualitySufficiencyPolicy.classifyQuality("Auto"))
    }

    @Test
    fun qualitySufficiencyPolicy_evaluatesSufficiencyCorrectly() {
        // Empty is not sufficient
        assertFalse(sufficiencyPolicy.isSufficient(emptyList()))

        // Only LOW is not sufficient
        assertFalse(sufficiencyPolicy.isSufficient(listOf("480p")))

        // Only HIGH is not sufficient when requireMedium is true
        assertFalse(sufficiencyPolicy.isSufficient(listOf("1080p")))

        // HIGH + MEDIUM is sufficient
        assertTrue(sufficiencyPolicy.isSufficient(listOf("1080p", "720p")))

        // HIGH + LOW is sufficient
        assertTrue(sufficiencyPolicy.isSufficient(listOf("1080p", "480p")))

        // HIGH + MEDIUM + LOW is sufficient
        assertTrue(sufficiencyPolicy.isSufficient(listOf("1080p", "720p", "480p")))
    }

    @Test
    fun orchestratePlayback_emitsFirstPlayableEarlyAndSucceeds() = runBlocking {
        val egydeadExt = ManagedExtension(
            id = "egydead",
            name = "EgyDead",
            baseUrl = "https://tv10.egydead.live",
            scraperKey = "egydead",
            priority = 100,
            contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES),
            status = ExtensionLifecycleStatus.ACTIVE,
            userEnabled = true
        )

        ManagedExtensionRegistry.INSTANCE.setExtensions(listOf(egydeadExt))

        val fakeSearchOrderRepo = object : SearchOrderRepository {
            override suspend fun getSearchOrder(forceRefresh: Boolean): SearchOrder? {
                return SearchOrder(movie = listOf("egydead"))
            }

            override suspend fun getOrderForContentType(
                contentType: ContentType,
                forceRefresh: Boolean
            ): List<String> = listOf("egydead")

            override fun clearCache() {}
        }

        val eligibilityFilter = ExtensionEligibilityFilter(
            scraperRegistry = ScraperRegistry.INSTANCE
        )

        // Mock runtime that returns immediate playable result
        val mockRuntime = object : ControlledManagedExtensionRuntime {
            override val currentAppVersionCode: Long = 100L
            override val supportedRuntimeApiVersion: Int = 1

            override suspend fun search(
                extensions: List<ManagedExtension>,
                request: SearchRequest
            ): Result<SearchResult> {
                return Result.success(
                    SearchResult(
                        items = listOf(
                            SearchMediaItem(
                                id = "1",
                                title = request.query,
                                url = "https://tv10.egydead.live/movie/test",
                                contentType = ContentType.MOVIE
                            )
                        )
                    )
                )
            }

            override suspend fun search(request: SearchRequest): Result<SearchResult> =
                search(emptyList(), request)

            override suspend fun discoverServers(
                extensions: List<ManagedExtension>,
                request: ServerDiscoveryRequest
            ): Result<ServerDiscoveryResult> {
                return Result.success(
                    ServerDiscoveryResult(
                        servers = listOf(
                            ServerItem(
                                id = "srv1",
                                name = "Server 1",
                                link = "https://stream.example.com/direct.mp4",
                                isDirectStream = true
                            )
                        ),
                        sourcePageUrl = request.targetUrl
                    )
                )
            }

            override suspend fun discoverServers(request: ServerDiscoveryRequest): Result<ServerDiscoveryResult> =
                discoverServers(emptyList(), request)

            override suspend fun extractStream(
                extensions: List<ManagedExtension>,
                request: ExtractionRequest
            ): Result<ExtractionResult> {
                return Result.success(
                    ExtractionResult(
                        playbackSource = PlaybackSource(
                            streamUrl = "https://stream.example.com/direct.mp4",
                            qualities = listOf(
                                QualitySource("1080p", "https://stream.example.com/1080.mp4"),
                                QualitySource("720p", "https://stream.example.com/720.mp4")
                            )
                        )
                    )
                )
            }

            override suspend fun extractStream(request: ExtractionRequest): Result<ExtractionResult> =
                extractStream(emptyList(), request)

            override suspend fun getDetails(extension: ManagedExtension, url: String) =
                Result.failure<MediaDetailsResult>(RuntimeException())

            override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int) =
                Result.failure<List<EpisodeItem>>(RuntimeException())
        }

        val orchestrator = PlaybackOrchestrator(
            searchOrderRepository = fakeSearchOrderRepo,
            eligibilityFilter = eligibilityFilter,
            extensionRegistry = ManagedExtensionRegistry.INSTANCE,
            runtime = mockRuntime,
            sufficiencyPolicy = sufficiencyPolicy
        )

        val session = PlaybackSession(
            mediaId = "123",
            title = "Inception",
            isMovie = true,
            contentType = ContentType.MOVIE
        )

        var firstPlayableReceived: PlaybackSource? = null
        val outcome = orchestrator.orchestratePlayback(
            session = session,
            onFirstPlayableSource = { firstPlayableReceived = it }
        )

        assertTrue(outcome is PlaybackOrchestratorOutcome.Started)
        assertNotNull(firstPlayableReceived)
        assertEquals("https://stream.example.com/direct.mp4", firstPlayableReceived?.streamUrl)
        assertTrue(session.firstPlayableDeferred.isCompleted)
    }

    @Test
    fun orchestratePlayback_failsCleanlyWhenSearchOrderIsEmpty() = runBlocking {
        val fakeSearchOrderRepo = object : SearchOrderRepository {
            override suspend fun getSearchOrder(forceRefresh: Boolean): SearchOrder? {
                return SearchOrder(movie = emptyList())
            }

            override suspend fun getOrderForContentType(
                contentType: ContentType,
                forceRefresh: Boolean
            ): List<String> = emptyList()

            override fun clearCache() {}
        }

        val eligibilityFilter = ExtensionEligibilityFilter(
            scraperRegistry = ScraperRegistry.INSTANCE
        )

        val mockRuntime = object : ControlledManagedExtensionRuntime {
            override val currentAppVersionCode: Long = 100L
            override val supportedRuntimeApiVersion: Int = 1
            override suspend fun search(extensions: List<ManagedExtension>, request: SearchRequest) =
                Result.failure<SearchResult>(RuntimeException("Search failed"))
            override suspend fun search(request: SearchRequest) =
                Result.failure<SearchResult>(RuntimeException("Search failed"))
            override suspend fun discoverServers(extensions: List<ManagedExtension>, request: ServerDiscoveryRequest) =
                Result.failure<ServerDiscoveryResult>(RuntimeException("Discovery failed"))
            override suspend fun discoverServers(request: ServerDiscoveryRequest) =
                Result.failure<ServerDiscoveryResult>(RuntimeException("Discovery failed"))
            override suspend fun extractStream(extensions: List<ManagedExtension>, request: ExtractionRequest) =
                Result.failure<ExtractionResult>(RuntimeException("Extraction failed"))
            override suspend fun extractStream(request: ExtractionRequest) =
                Result.failure<ExtractionResult>(RuntimeException("Extraction failed"))
            override suspend fun getDetails(extension: ManagedExtension, url: String) =
                Result.failure<MediaDetailsResult>(RuntimeException())
            override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int) =
                Result.failure<List<EpisodeItem>>(RuntimeException())
        }

        val orchestrator = PlaybackOrchestrator(
            searchOrderRepository = fakeSearchOrderRepo,
            eligibilityFilter = eligibilityFilter,
            extensionRegistry = ManagedExtensionRegistry.INSTANCE,
            runtime = mockRuntime,
            sufficiencyPolicy = sufficiencyPolicy
        )

        val session = PlaybackSession(
            mediaId = "123",
            title = "Empty Content",
            isMovie = true,
            contentType = ContentType.MOVIE
        )

        var errorEmitted = false
        val outcome = orchestrator.orchestratePlayback(
            session = session,
            onFirstPlayableSource = {},
            onError = { errorEmitted = true }
        )

        assertTrue(outcome is PlaybackOrchestratorOutcome.Failure)
        assertTrue(errorEmitted)
    }
}
