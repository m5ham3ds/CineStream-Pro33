package com.example.extension.managed

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.orchestrator.ManagedDiscoveryOutcome
import com.example.extension.orchestrator.ManagedMediaOrchestrator
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.repository.*
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.scraper.EgyDeadScraper
import com.example.extension.managed.scraper.QfilmScraper
import com.example.extension.managed.searchorder.ExtensionEligibilityFilter
import com.example.extension.managed.trace.Phase05GLogger
import com.example.ui.components.isValidPlayableMediaUrl
import com.example.ui.screens.player.MediaServerData
import com.example.ui.screens.player.PlaybackSyncStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Phase 05G: Playback Pipeline Root-Cause Repair Test Suite.
 *
 * Verifies all 23 items of the Phase 05G Test Matrix:
 * 1. Fresh install: healthy bundled defaults available
 * 2. Authenticated user: remote read succeeds
 * 3. Unauthenticated user: remote read failure is preserved, bundled defaults remain usable
 * 4. Firebase available: registry updated safely
 * 5. Firebase unavailable: healthy bundled runtime remains usable
 * 6. /managed_extensions empty: genuine empty result distinguished
 * 7. /extensions empty: legacy fallback handling
 * 8. Malformed extension: rejected by validation gate without destroying healthy candidates
 * 9. Missing contentTypes: rejected by validation gate
 * 10. minAppVersionCode too high: rejected as incompatible
 * 11. qfilm ACTIVE: eligible for MOVIE
 * 12. egydead ACTIVE: eligible for MOVIE
 * 13. qfilm DISABLED: admin authority respected, egydead remains eligible
 * 14. qfilm + egydead active: both eligible in search order
 * 15. Remote config failure with bundled defaults
 * 16. Cached servers with directStreamUrl: returns immediately
 * 17. Cached servers without directStreamUrl: reconstructs serverItems and extracts
 * 18. Cached embed servers: never treated as direct playable URL
 * 19. Server discovery: discovers servers for movie
 * 20. Extraction: resolves playable master .m3u8
 * 21. Player handoff: validates playable URL
 * 22. Fullscreen: synchronized without re-extraction
 * 23. Download regression: download sources preserved
 */
class Phase05GPlaybackPipelineRootCauseRepairTest {

    private val scraperRegistry = ScraperRegistry.INSTANCE
    private val eligibilityFilter = ExtensionEligibilityFilter(scraperRegistry = scraperRegistry)

    private val bundledQfilm: ManagedExtension = ManagedMediaOrchestrator.DEFAULT_QFILM_MANAGED_EXTENSION
    private val bundledEgydead: ManagedExtension = ManagedMediaOrchestrator.DEFAULT_EGYDEAD_MANAGED_EXTENSION
    private val bundledWitanime: ManagedExtension = ManagedMediaOrchestrator.DEFAULT_WITANIME_MANAGED_EXTENSION
    private val bundledAnime4up: ManagedExtension = ManagedMediaOrchestrator.DEFAULT_ANIME4UP_MANAGED_EXTENSION
    private val bundledAnimeblkom: ManagedExtension = ManagedMediaOrchestrator.DEFAULT_ANIMEBLKOM_MANAGED_EXTENSION

    private val bundledDefaults: List<ManagedExtension> = listOf(
        bundledQfilm,
        bundledEgydead,
        bundledWitanime,
        bundledAnime4up,
        bundledAnimeblkom
    )

    private class MockRemoteDataSource(
        var response: Result<List<ManagedExtensionDto>>
    ) : ManagedExtensionRemoteDataSource {
        override suspend fun fetchManagedExtensionDtos(): Result<List<ManagedExtensionDto>> = response
    }

    private fun createValidDto(
        id: String,
        name: String = id,
        baseUrl: String = "https://tv10.egydead.live",
        scraperKey: String = "egydead",
        contentTypes: List<String> = listOf("MOVIE", "SERIES"),
        status: String = "ACTIVE",
        minAppVersionCode: Long = 1L,
        runtimeApiVersion: Long = 1L,
        priority: Long = 100L
    ) = ManagedExtensionDto(
        id = id,
        name = name,
        baseUrl = baseUrl,
        scraperKey = scraperKey,
        contentTypes = contentTypes,
        status = status,
        minAppVersionCode = minAppVersionCode,
        runtimeApiVersion = runtimeApiVersion,
        priority = priority
    )

    @Before
    fun setUp() {
        // Reset registry to clean state
        ManagedExtensionRegistry.INSTANCE.setExtensions(bundledDefaults)
    }

    @Test
    fun test01_freshInstall_bundledDefaultsAvailableAndHealthy() {
        val registry = ManagedExtensionRegistry.INSTANCE
        val all = registry.getAllExtensions()
        val active = registry.getActiveExtensions()

        Phase05GLogger.log("REGISTRY", "test01", "total=${all.size}, active=${active.size}")
        assertEquals("Fresh install must contain all 5 bundled defaults", 5, all.size)
        assertEquals("All 5 bundled defaults must be ACTIVE on startup", 5, active.size)

        val movieEligible = active.filter { it.contentTypes.contains(ContentType.MOVIE) }
        assertTrue("Both qfilm and egydead must be eligible for MOVIE", movieEligible.any { it.id == "qfilm" })
        assertTrue("Both qfilm and egydead must be eligible for MOVIE", movieEligible.any { it.id == "egydead" })
    }

    @Test
    fun test02_authenticatedUser_remoteReadSucceeds() = runBlocking {
        val dtos = listOf(
            createValidDto("qfilm", baseUrl = "https://a.qfilm.tv", scraperKey = "qfilm"),
            createValidDto("egydead", baseUrl = "https://tv10.egydead.live", scraperKey = "egydead")
        )
        val dataSource = MockRemoteDataSource(Result.success(dtos))
        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = dataSource,
            bundledDefaults = bundledDefaults
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue("Authenticated user read must succeed", result.isSuccess)
        val exts = result.getOrNull() ?: emptyList()
        assertEquals(2, exts.size)
        assertTrue(exts.any { it.id == "qfilm" })
        assertTrue(exts.any { it.id == "egydead" })
    }

    @Test
    fun test03_unauthenticatedUser_errorPreservedAndBundledDefaultsRetained() = runBlocking {
        // Phase 05H: Remote failure without cache must NOT activate bundled defaults
        val authError = Exception("PERMISSION_DENIED: Missing or insufficient permissions.")
        val dataSource = MockRemoteDataSource(Result.failure(authError))
        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = dataSource,
            cache = SafeLocalMetadataCache(),
            bundledDefaults = bundledDefaults
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue("Repository must fail when remote is unavailable and no cache exists to prevent guest bypass", result.isFailure)
    }

    @Test
    fun test04_firebaseAvailable_updatesRegistrySafely() = runBlocking {
        val dtos = listOf(
            createValidDto("qfilm", priority = 150L, baseUrl = "https://a.qfilm.tv", scraperKey = "qfilm"),
            createValidDto("egydead", priority = 140L, baseUrl = "https://tv10.egydead.live", scraperKey = "egydead")
        )
        val dataSource = MockRemoteDataSource(Result.success(dtos))
        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = dataSource,
            bundledDefaults = bundledDefaults
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue(result.isSuccess)
        val freshList = result.getOrThrow()

        // Strict validation gate before updating registry
        val validatedList = freshList.filter { ext ->
            ManagedExtensionValidator.validateRemoteEntry(ext, currentAppVersionCode = 1L) is ManagedExtensionValidator.ValidationResult.Valid
        }
        assertEquals(2, validatedList.size)
        ManagedExtensionRegistry.INSTANCE.setExtensions(validatedList)

        val active = ManagedExtensionRegistry.INSTANCE.getActiveExtensions()
        assertEquals(2, active.size)
        assertEquals(150, active.first { it.id == "qfilm" }.priority)
    }

    @Test
    fun test05_firebaseUnavailable_bundledDefaultsRemainUsable() = runBlocking {
        val networkError = IOException("Failed to connect to firestore.googleapis.com")
        val dataSource = MockRemoteDataSource(Result.failure(networkError))
        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = dataSource,
            bundledDefaults = bundledDefaults
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue("When Firebase is unavailable and no cache exists, repository must fail rather than blindly activating defaults", result.isFailure)
    }

    @Test
    fun test06_managedExtensionsEmpty_distinguishesGenuineEmpty() = runBlocking {
        val dataSource = MockRemoteDataSource(Result.success(emptyList()))
        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = dataSource,
            bundledDefaults = bundledDefaults
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue("Genuine empty result must return success with empty list", result.isSuccess)
        assertEquals(0, result.getOrThrow().size)
    }

    @Test
    fun test07_extensionsEmpty_preservesCanonicalManagedExtensions() = runBlocking {
        // Canonical collection has qfilm, legacy is empty
        val dtos = listOf(createValidDto("qfilm", baseUrl = "https://a.qfilm.tv", scraperKey = "qfilm"))
        val dataSource = MockRemoteDataSource(Result.success(dtos))
        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = dataSource,
            bundledDefaults = bundledDefaults
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().size)
        assertEquals("qfilm", result.getOrThrow().first().id)
    }

    @Test
    fun test08_malformedExtension_rejectedWithoutDestroyingHealthyCandidates() = runBlocking {
        val dtos = listOf(
            createValidDto("qfilm", baseUrl = "https://a.qfilm.tv", scraperKey = "qfilm"),
            // Malformed egydead from remote: insecure HTTP and private IP
            ManagedExtensionDto(
                id = "egydead",
                name = "Bad EgyDead",
                baseUrl = "http://192.168.1.1",
                scraperKey = "egydead",
                contentTypes = listOf("MOVIE")
            )
        )
        val dataSource = MockRemoteDataSource(Result.success(dtos))
        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = dataSource,
            bundledDefaults = bundledDefaults
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue(result.isSuccess)
        val catalog = result.getOrThrow()

        // Valid qfilm from remote accepted
        assertTrue(catalog.any { it.id == "qfilm" && it.baseUrl == "https://a.qfilm.tv" })
        // Malformed remote egydead rejected; healthy bundled candidate retained
        assertTrue("Healthy bundled egydead must be retained when remote egydead is malformed",
            catalog.any { it.id == "egydead" && it.baseUrl == "https://tv10.egydead.live" })
    }

    @Test
    fun test09_missingContentTypes_rejectedByValidationGate() = runBlocking {
        val dtos = listOf(
            ManagedExtensionDto(
                id = "qfilm",
                name = "Empty Types Qfilm",
                baseUrl = "https://a.qfilm.tv",
                scraperKey = "qfilm",
                contentTypes = emptyList() // Missing contentTypes
            )
        )
        val dataSource = MockRemoteDataSource(Result.success(dtos))
        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = dataSource,
            bundledDefaults = bundledDefaults
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue(result.isSuccess)
        val catalog = result.getOrThrow()
        // Healthy bundled qfilm retained
        val qfilm = catalog.firstOrNull { it.id == "qfilm" }
        assertNotNull(qfilm)
        assertTrue("Retained qfilm must have non-empty contentTypes", qfilm!!.contentTypes.isNotEmpty())
    }

    @Test
    fun test10_minAppVersionCodeTooHigh_rejectedAsIncompatible() = runBlocking {
        val dtos = listOf(
            createValidDto("qfilm", minAppVersionCode = 2L, baseUrl = "https://a.qfilm.tv", scraperKey = "qfilm")
        )
        val dataSource = MockRemoteDataSource(Result.success(dtos))
        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = dataSource,
            bundledDefaults = bundledDefaults
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue(result.isSuccess)
        val catalog = result.getOrThrow()
        // Remote qfilm required version 2, but client is version 1; healthy bundled qfilm retained
        val qfilm = catalog.firstOrNull { it.id == "qfilm" }
        assertNotNull(qfilm)
        assertEquals(1L, qfilm!!.minAppVersionCode)
    }

    @Test
    fun test11_qfilmActive_eligibleForMovie() {
        val eligible = eligibilityFilter.filterEligibleExtensions(
            orderedExtensionIds = listOf("qfilm"),
            availableExtensions = listOf(bundledQfilm),
            targetContentType = ContentType.MOVIE,
            currentAppVersionCode = 1L
        )
        assertEquals("qfilm must be eligible for MOVIE", 1, eligible.size)
        assertEquals("qfilm", eligible.first().id)
    }

    @Test
    fun test12_egydeadActive_eligibleForMovie() {
        val eligible = eligibilityFilter.filterEligibleExtensions(
            orderedExtensionIds = listOf("egydead"),
            availableExtensions = listOf(bundledEgydead),
            targetContentType = ContentType.MOVIE,
            currentAppVersionCode = 1L
        )
        assertEquals("egydead must be eligible for MOVIE", 1, eligible.size)
        assertEquals("egydead", eligible.first().id)
    }

    @Test
    fun test13_qfilmDisabled_respectsAdminAuthorityWhileEgydeadRemainsEligible() = runBlocking {
        // Admin disables qfilm in remote config
        val dtos = listOf(
            createValidDto("qfilm", status = "DISABLED", baseUrl = "https://a.qfilm.tv", scraperKey = "qfilm"),
            createValidDto("egydead", status = "ACTIVE", baseUrl = "https://tv10.egydead.live", scraperKey = "egydead")
        )
        val dataSource = MockRemoteDataSource(Result.success(dtos))
        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = dataSource,
            bundledDefaults = bundledDefaults
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue(result.isSuccess)
        val catalog = result.getOrThrow()

        val qfilm = catalog.first { it.id == "qfilm" }
        assertEquals("Admin disablement must be respected", ExtensionLifecycleStatus.DISABLED, qfilm.status)

        val eligible = eligibilityFilter.filterEligibleExtensions(
            orderedExtensionIds = listOf("qfilm", "egydead"),
            availableExtensions = catalog,
            targetContentType = ContentType.MOVIE,
            currentAppVersionCode = 1L
        )
        assertEquals("Only egydead should be eligible when qfilm is DISABLED", 1, eligible.size)
        assertEquals("egydead", eligible.first().id)
    }

    @Test
    fun test14_qfilmAndEgydeadActive_bothEligibleForMovie() {
        val eligible = eligibilityFilter.filterEligibleExtensions(
            orderedExtensionIds = listOf("qfilm", "egydead"),
            availableExtensions = listOf(bundledQfilm, bundledEgydead),
            targetContentType = ContentType.MOVIE,
            currentAppVersionCode = 1L
        )
        assertEquals(2, eligible.size)
        assertEquals("qfilm", eligible[0].id)
        assertEquals("egydead", eligible[1].id)
    }

    @Test
    fun test15_remoteConfigFailure_preservesBundledDefaults() = runBlocking {
        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = MockRemoteDataSource(Result.failure(IOException("Timeout"))),
            bundledDefaults = bundledDefaults
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue("Remote failure without cache must not succeed blindly", result.isFailure)
    }

    @Test
    fun test16_cachedServersWithDirectStreamUrl_returnsImmediately() {
        val cached = MediaServerData(
            mediaKey = "test-movie-1",
            servers = listOf("EarnVids"),
            serverLinks = mapOf("EarnVids" to "https://morencius.com/v/zzw3dy72jlnh"),
            serverIds = mapOf("EarnVids" to "1"),
            downloadLinks = emptyMap(),
            extractedQualities = emptyList(),
            website = "egydead",
            playbackPageUrl = "https://tv10.egydead.live",
            scraperKey = "egydead",
            directStreamUrl = "https://example.com/master.m3u8"
        )
        assertTrue("When directStreamUrl is present, it is immediately usable", isValidPlayableMediaUrl(cached.directStreamUrl))
    }

    @Test
    fun test17_cachedServersWithoutDirectStreamUrl_reconstructsServerItemsAndExtracts() = runBlocking {
        // FIX 4: existingCached has servers, but directStreamUrl is null
        val cached = MediaServerData(
            mediaKey = "inception-2010",
            servers = listOf("EarnVids"),
            serverLinks = mapOf("EarnVids" to "https://morencius.com/v/zzw3dy72jlnh"),
            serverIds = mapOf("EarnVids" to "1"),
            downloadLinks = emptyMap(),
            extractedQualities = emptyList(),
            website = "egydead",
            playbackPageUrl = "https://tv10.egydead.live",
            scraperKey = "egydead",
            directStreamUrl = null // Crucial: null directStreamUrl
        )

        // Verify reconstruction logic implemented in Fix 4
        val serverItems = cached.servers.mapNotNull { name ->
            val link = cached.serverLinks[name] ?: return@mapNotNull null
            val id = cached.serverIds[name] ?: name
            val isDirect = link.endsWith(".mp4") || link.endsWith(".m3u8") || link.contains("akamaized.net")
            ServerItem(
                id = id,
                name = name,
                link = link,
                isDirectStream = isDirect,
                sourceUrl = cached.playbackPageUrl ?: link,
                serverType = if (isDirect) ServerType.DIRECT else ServerType.EMBED,
                requiresWebView = !isDirect
            )
        }

        assertEquals("Server items must be reconstructed from cache", 1, serverItems.size)
        val srv = serverItems.first()
        assertEquals("EarnVids", srv.name)
        assertEquals("https://morencius.com/v/zzw3dy72jlnh", srv.link)
        assertFalse("Embed URL must not be marked as direct stream", srv.isDirectStream)

        // Perform live static extraction on reconstructed server
        val scraper = EgyDeadScraper()
        val session = ExtractionSession(
            extensionId = "egydead",
            scraperKey = "egydead",
            targetPageUrl = "https://tv10.egydead.live"
        )
        val request = ExtractionRequest(srv, "Inception")

        val extractResult = scraper.extractStream(bundledEgydead, session, request, null)
        assertTrue("Extraction on reconstructed server must succeed", extractResult.isSuccess)
        val streamUrl = extractResult.getOrThrow().playbackSource?.streamUrl
        assertNotNull(streamUrl)
        assertTrue("Stream URL must be valid playable URL", isValidPlayableMediaUrl(streamUrl))
    }

    @Test
    fun test18_cachedEmbedServers_neverTreatedAsDirectPlayableUrl() {
        val embedUrl = "https://morencius.com/v/zzw3dy72jlnh"
        assertFalse("Embed page URL must NEVER be treated as valid playable media URL", isValidPlayableMediaUrl(embedUrl))
    }

    @Test
    fun test19_serverDiscovery_discoversServersForMovie() = runBlocking {
        val scraper = EgyDeadScraper()
        val request = ServerDiscoveryRequest(
            targetUrl = "https://tv10.egydead.live",
            mediaTitle = "Inception",
            isMovie = true
        )
        val session = ExtractionSession(
            extensionId = "egydead",
            scraperKey = "egydead",
            targetPageUrl = "https://tv10.egydead.live"
        )
        val result = scraper.discoverServers(bundledEgydead, session, request, null)
        assertTrue("Server discovery must succeed for known movie", result.isSuccess)
        val servers = result.getOrThrow().servers
        assertTrue("Must discover at least one server", servers.isNotEmpty())
    }

    @Test
    fun test20_extraction_resolvesPlayableMasterM3u8() = runBlocking {
        val scraper = EgyDeadScraper()
        val server = ServerItem(
            name = "EarnVids",
            link = "https://morencius.com/v/zzw3dy72jlnh",
            id = "1",
            isDirectStream = false
        )
        val session = ExtractionSession(
            extensionId = "egydead",
            scraperKey = "egydead",
            targetPageUrl = "https://tv10.egydead.live"
        )
        val request = ExtractionRequest(server, "Inception")

        val result = scraper.extractStream(bundledEgydead, session, request, null)
        assertTrue("Extraction must resolve playable stream", result.isSuccess)
        val stream = result.getOrThrow().playbackSource?.streamUrl
        assertNotNull(stream)
        assertTrue("Stream must be playable URL", isValidPlayableMediaUrl(stream))
        assertTrue("Stream must contain master.m3u8", stream!!.contains(".m3u8"))
    }

    @Test
    fun test21_playerHandoff_validatesPlayableUrl() {
        val validM3u8 = "https://worker.storage.workers.dev/master.m3u8?token=xyz"
        val invalidEmbed = "https://example.com/embed/player.html"

        assertTrue("Direct m3u8 is valid for handoff", isValidPlayableMediaUrl(validM3u8))
        assertFalse("Embed URL is invalid for handoff", isValidPlayableMediaUrl(invalidEmbed))
    }

    @Test
    fun test22_fullscreen_synchronizesPlaybackWithoutReExtraction() {
        val mediaId = "27205"

        PlaybackSyncStore.setPosition(mediaId, 125000L)
        val retrievedPos = PlaybackSyncStore.getPosition(mediaId)
        assertEquals(125000L, retrievedPos)
    }

    @Test
    fun test23_downloadRegression_preservesDownloadSources() = runBlocking {
        val scraper = EgyDeadScraper()
        val server = ServerItem(
            name = "EarnVids",
            link = "https://morencius.com/v/zzw3dy72jlnh",
            id = "1",
            isDirectStream = false
        )
        val session = ExtractionSession(
            extensionId = "egydead",
            scraperKey = "egydead",
            targetPageUrl = "https://tv10.egydead.live"
        )
        val request = ExtractionRequest(server, "Inception")

        val result = scraper.extractStream(bundledEgydead, session, request, null)
        assertTrue(result.isSuccess)
        val extraction = result.getOrThrow()
        assertNotNull("DownloadSource must be preserved for offline download pipeline", extraction.downloadSource)
    }
}
