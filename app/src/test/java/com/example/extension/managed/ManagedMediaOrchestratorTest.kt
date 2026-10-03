package com.example.extension.managed

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.repository.ExtensionUserPreferences
import com.example.extension.managed.repository.InMemoryExtensionUserPreferences
import com.example.extension.managed.repository.ManagedExtensionRepository
import com.example.extension.orchestrator.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ManagedMediaOrchestratorTest {

    private lateinit var registry: ManagedExtensionRegistry
    private lateinit var userPreferences: ExtensionUserPreferences
    private lateinit var fakeRuntime: FakeControlledRuntime
    private lateinit var fakeRepository: FakeManagedRepository
    private lateinit var orchestrator: ManagedMediaOrchestrator

    private val testExtension = ManagedExtension(
        id = "egydead",
        name = "EgyDead (Managed)",
        description = "Test Description",
        baseUrl = "https://tv10.egydead.live",
        iconUrl = "",
        scraperKey = "egydead",
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
    }

    @Test
    fun testHasActiveExtensions() {
        assertTrue(orchestrator.hasActiveExtensions())
        assertTrue(orchestrator.hasActiveExtensions(ContentType.MOVIE))
        assertTrue(orchestrator.hasActiveExtensions(ContentType.SERIES))
        assertTrue(orchestrator.hasActiveExtensions(ContentType.ANIME))

        val names = orchestrator.getActiveExtensions().map { it.name }
        assertEquals(listOf("EgyDead (Managed)"), names)
    }

    @Test
    fun testUserDisablesExtension() {
        orchestrator.updateUserPreference("egydead", false)

        assertFalse(userPreferences.isExtensionEnabled("egydead"))
        // Phase 05H: Global administrative status remains authoritative over per-user toggles
        assertTrue(orchestrator.hasActiveExtensions())
        assertFalse(orchestrator.getActiveExtensions().isEmpty())

        // Re-enable
        orchestrator.updateUserPreference("egydead", true)
        assertTrue(userPreferences.isExtensionEnabled("egydead"))
        assertTrue(orchestrator.hasActiveExtensions())
    }

    @Test
    fun testDiscoverServersSuccess() = runBlocking {
        fakeRuntime.searchResponse = Result.success(
            SearchResult(
                items = listOf(
                    SearchMediaItem(
                        id = "m1",
                        title = "Inception",
                        url = "https://tv10.egydead.live/movie/inception",
                        contentType = ContentType.MOVIE
                    )
                )
            )
        )
        fakeRuntime.discoverServersResponse = Result.success(
            ServerDiscoveryResult(
                servers = listOf(
                    ServerItem(id = "srv1", name = "Server Fast", link = "https://server1.example/video.mp4")
                ),
                sourcePageUrl = "https://tv10.egydead.live/movie/inception"
            )
        )

        val outcome = orchestrator.discoverServers(
            title = "Inception",
            year = "2010",
            isMovie = true,
            season = 1,
            episode = 1
        )

        assertTrue(outcome is ManagedDiscoveryOutcome.Success)
        val success = outcome as ManagedDiscoveryOutcome.Success
        assertEquals(1, success.servers.size)
        assertEquals("Server Fast", success.servers[0].name)
        assertEquals("https://tv10.egydead.live/movie/inception", success.sourceUrl)
    }

    @Test
    fun testDiscoverServersOfflineReturnsNoInternet() = runBlocking {
        val offlineOrchestrator = ManagedMediaOrchestrator(
            repository = fakeRepository,
            registry = registry,
            runtime = fakeRuntime,
            userPreferences = userPreferences,
            isOnlineChecker = { false }
        )

        val outcome = offlineOrchestrator.discoverServers(
            title = "Inception",
            year = "2010",
            isMovie = true,
            season = 1,
            episode = 1
        )

        assertTrue(outcome is ManagedDiscoveryOutcome.RecoverableFailure)
        val failure = outcome as ManagedDiscoveryOutcome.RecoverableFailure
        assertTrue(failure.error is ExtensionError.NoInternet)
    }

    @Test
    fun testDiscoverServersWhenNoActiveExtensionsFailsWithMediaNotFound() = runBlocking {
        registry.setExtensions(emptyList())

        val outcome = orchestrator.discoverServers(
            title = "Inception",
            year = "2010",
            isMovie = true,
            season = 1,
            episode = 1
        )

        assertTrue(outcome is ManagedDiscoveryOutcome.RecoverableFailure)
        val failure = outcome as ManagedDiscoveryOutcome.RecoverableFailure
        assertTrue(failure.error is ExtensionError.MediaNotFound)
    }

    // --- Fakes ---

    private class FakeControlledRuntime : ControlledManagedExtensionRuntime {
        override val currentAppVersionCode: Long = 1L
        override val supportedRuntimeApiVersion: Int = 1

        var searchResponse: Result<SearchResult> = Result.failure(ExtensionError.MediaNotFound("Not found"))
        var discoverServersResponse: Result<ServerDiscoveryResult> = Result.failure(ExtensionError.ExtractionFailed("Failed", null))
        var extractStreamResponse: Result<ExtractionResult> = Result.failure(ExtensionError.ExtractionFailed("Failed", null))

        override suspend fun search(request: SearchRequest): Result<SearchResult> = searchResponse
        override suspend fun search(extensions: List<ManagedExtension>, request: SearchRequest): Result<SearchResult> = searchResponse

        override suspend fun discoverServers(request: ServerDiscoveryRequest): Result<ServerDiscoveryResult> = discoverServersResponse
        override suspend fun discoverServers(extensions: List<ManagedExtension>, request: ServerDiscoveryRequest): Result<ServerDiscoveryResult> = discoverServersResponse

        override suspend fun extractStream(request: ExtractionRequest): Result<ExtractionResult> = extractStreamResponse
        override suspend fun extractStream(extensions: List<ManagedExtension>, request: ExtractionRequest): Result<ExtractionResult> = extractStreamResponse

        override suspend fun getDetails(extension: ManagedExtension, url: String): Result<MediaDetailsResult> {
            return Result.failure(ExtensionError.MediaNotFound("Not found"))
        }

        override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int): Result<List<EpisodeItem>> {
            return Result.success(emptyList())
        }
    }

    private class FakeManagedRepository : ManagedExtensionRepository {
        var extensions = listOf<ManagedExtension>()

        override suspend fun getExtensions(forceRefresh: Boolean): Result<List<ManagedExtension>> {
            return Result.success(extensions)
        }

        override suspend fun getExtensionById(id: String, forceRefresh: Boolean): Result<ManagedExtension> {
            val found = extensions.firstOrNull { it.id == id }
            return if (found != null) Result.success(found) else Result.failure(Exception("Unknown extension $id"))
        }
    }
}
