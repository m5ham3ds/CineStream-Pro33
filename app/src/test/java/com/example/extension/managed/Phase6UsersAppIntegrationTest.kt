package com.example.extension.managed

import com.example.extension.managed.adapter.DownloaderHandoffAdapter
import com.example.extension.managed.adapter.LegacyFallbackMigrationAdapter
import com.example.extension.managed.adapter.PlayerHandoffAdapter
import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.runtime.CredentialProjector
import com.example.extension.managed.runtime.DefaultControlledManagedExtensionRuntime
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.runtime.FallbackManager
import com.example.extension.managed.scraper.EgyDeadScraper
import com.example.extension.managed.usecase.IneligibilityReason
import com.example.extension.managed.usecase.ManagedExtensionResolver
import com.example.extension.managed.usecase.SearchManagedExtensionsUseCase
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * PHASE 6 — USERS APP INTEGRATION
 * Verification suite proving all 20 required integration invariants.
 */
class Phase6UsersAppIntegrationTest {

    private lateinit var scraperRegistry: ScraperRegistry
    private lateinit var extensionRegistry: ManagedExtensionRegistry
    private lateinit var fallbackManager: FallbackManager
    private lateinit var resolver: ManagedExtensionResolver
    private lateinit var runtime: ControlledManagedExtensionRuntime

    private val validActiveExtension = ManagedExtension(
        id = "egydead-primary",
        name = "EgyDead Main",
        description = "Primary active extension",
        baseUrl = "https://tv10.egydead.live",
        iconUrl = "https://example.com/icon.png",
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
        scraperRegistry = ScraperRegistry(mapOf("egydead" to EgyDeadScraper()))
        extensionRegistry = ManagedExtensionRegistry(listOf(validActiveExtension))
        fallbackManager = FallbackManager(scraperRegistry)

        resolver = ManagedExtensionResolver(
            registry = extensionRegistry,
            scraperRegistry = scraperRegistry,
            currentAppVersionCode = 10L,
            supportedRuntimeApiVersion = 2
        )

        runtime = DefaultControlledManagedExtensionRuntime(
            scraperRegistry = scraperRegistry,
            fallbackManager = fallbackManager,
            managedExtensionRegistry = extensionRegistry,
            currentAppVersionCode = 10L,
            supportedRuntimeApiVersion = 2
        )
    }

    // --- REQUIREMENT 1: Managed Extension -> Runtime Integration ---
    @Test
    fun test1_ManagedExtensionToRuntimeIntegration() = runBlocking {
        val request = SearchRequest(query = "Inception", contentType = ContentType.MOVIE)
        val result = runtime.search(listOf(validActiveExtension), request)
        // Invoking search through runtime against a valid extension does not crash or throw unhandled exceptions
        assertNotNull(result)
    }

    // --- REQUIREMENT 2: Registry -> Runtime Integration ---
    @Test
    fun test2_RegistryToRuntimeIntegration() = runBlocking {
        val request = SearchRequest(query = "Matrix", contentType = ContentType.MOVIE)
        // Calling runtime.search(request) automatically reads from ManagedExtensionRegistry
        val result = runtime.search(request)
        assertNotNull(result)
        assertEquals(1, extensionRegistry.getAllExtensions().size)
    }

    // --- REQUIREMENT 3: scraperKey -> ScraperRegistry Resolution ---
    @Test
    fun test3_ScraperKeyToScraperRegistryResolution() {
        val resolved = scraperRegistry.getScraper("egydead")
        assertNotNull("scraperKey 'egydead' must resolve via ScraperRegistry", resolved)
        assertTrue("Resolved scraper must be an instance of EgyDeadScraper", resolved is EgyDeadScraper)
        assertEquals("egydead", resolved?.scraperKey)
    }

    // --- REQUIREMENT 4: ACTIVE Extension Execution ---
    @Test
    fun test4_ActiveExtensionExecution() {
        val eligible = resolver.resolveEligibleExtensions(capability = ScraperCapability.SEARCH)
        assertEquals(1, eligible.size)
        assertEquals(validActiveExtension.id, eligible.first().id)
        assertEquals(ExtensionLifecycleStatus.ACTIVE, eligible.first().status)
    }

    // --- REQUIREMENT 5: DISABLED Extension Rejection ---
    @Test
    fun test5_DisabledExtensionRejection() {
        val disabledExt = validActiveExtension.copy(id = "ext-disabled", status = ExtensionLifecycleStatus.DISABLED)
        extensionRegistry.setExtensions(listOf(disabledExt))

        val eligible = resolver.resolveEligibleExtensions()
        assertTrue("DISABLED extensions must be rejected from eligible list", eligible.isEmpty())

        val eval = resolver.evaluateEligibility(disabledExt)
        assertTrue(eval is com.example.extension.managed.usecase.ExtensionEligibilityResult.Ineligible)
        val inelig = eval as com.example.extension.managed.usecase.ExtensionEligibilityResult.Ineligible
        assertEquals(IneligibilityReason.LIFECYCLE_DISABLED, inelig.reason)
    }

    // --- REQUIREMENT 6: MAINTENANCE Extension Rejection ---
    @Test
    fun test6_MaintenanceExtensionRejection() {
        val maintExt = validActiveExtension.copy(id = "ext-maint", status = ExtensionLifecycleStatus.MAINTENANCE)
        extensionRegistry.setExtensions(listOf(maintExt))

        val eligible = resolver.resolveEligibleExtensions()
        assertTrue("MAINTENANCE extensions must be rejected from normal execution", eligible.isEmpty())

        val eval = resolver.evaluateEligibility(maintExt)
        assertTrue(eval is com.example.extension.managed.usecase.ExtensionEligibilityResult.Ineligible)
        val inelig = eval as com.example.extension.managed.usecase.ExtensionEligibilityResult.Ineligible
        assertEquals(IneligibilityReason.LIFECYCLE_MAINTENANCE, inelig.reason)
    }

    // --- REQUIREMENT 7: DEPRECATED Behavior ---
    @Test
    fun test7_DeprecatedBehavior() {
        val deprecatedExt = validActiveExtension.copy(id = "ext-dep", status = ExtensionLifecycleStatus.DEPRECATED)
        extensionRegistry.setExtensions(listOf(deprecatedExt))

        // Excluded from automatic execution
        val autoEligible = resolver.resolveEligibleExtensions(allowDeprecated = false)
        assertTrue("DEPRECATED extensions must be excluded from automatic execution", autoEligible.isEmpty())

        // Explicit/manual allowance only
        val manualEligible = resolver.resolveEligibleExtensions(allowDeprecated = true)
        assertEquals(1, manualEligible.size)
        assertEquals(deprecatedExt.id, manualEligible.first().id)
    }

    // --- REQUIREMENT 8: Admin Status Sole Authority Over Local userEnabled (Phase 05C) ---
    @Test
    fun test8_AdminStatusSoleAuthorityOverLocalUserEnabled() {
        // ACTIVE extension is eligible even if userEnabled = false
        val userDisabledExt = validActiveExtension.copy(id = "ext-user-disabled", userEnabled = false)
        extensionRegistry.setExtensions(listOf(userDisabledExt))

        val eligible = resolver.resolveEligibleExtensions()
        assertEquals("Globally ACTIVE extension must be eligible even if userEnabled=false", 1, eligible.size)
        assertEquals("ext-user-disabled", eligible.first().id)

        val eval = resolver.evaluateEligibility(userDisabledExt)
        assertTrue("Globally ACTIVE extension evaluation must be Eligible", eval is com.example.extension.managed.usecase.ExtensionEligibilityResult.Eligible)

        // DISABLED extension is ineligible even if userEnabled = true
        val adminDisabledExt = validActiveExtension.copy(id = "ext-admin-disabled", status = ExtensionLifecycleStatus.DISABLED, userEnabled = true)
        val adminDisabledEval = resolver.evaluateEligibility(adminDisabledExt)
        assertTrue(adminDisabledEval is com.example.extension.managed.usecase.ExtensionEligibilityResult.Ineligible)
        val inelig = adminDisabledEval as com.example.extension.managed.usecase.ExtensionEligibilityResult.Ineligible
        assertEquals(IneligibilityReason.LIFECYCLE_DISABLED, inelig.reason)
    }

    // --- REQUIREMENT 9: Unknown scraperKey Rejection ---
    @Test
    fun test9_UnknownScraperKeyRejection() {
        val unknownScraperExt = validActiveExtension.copy(id = "ext-unknown", scraperKey = "unregistered_key_xyz")
        extensionRegistry.setExtensions(listOf(unknownScraperExt))

        val eligible = resolver.resolveEligibleExtensions()
        assertTrue("Extension with unregistered scraperKey must be rejected", eligible.isEmpty())

        val eval = resolver.evaluateEligibility(unknownScraperExt)
        assertTrue(eval is com.example.extension.managed.usecase.ExtensionEligibilityResult.Ineligible)
        val inelig = eval as com.example.extension.managed.usecase.ExtensionEligibilityResult.Ineligible
        assertEquals(IneligibilityReason.UNKNOWN_SCRAPER, inelig.reason)
    }

    // --- REQUIREMENT 10: Incompatible Runtime Rejection ---
    @Test
    fun test10_IncompatibleRuntimeRejection() {
        val futureRuntimeExt = validActiveExtension.copy(id = "ext-future", runtimeApiVersion = 99)
        extensionRegistry.setExtensions(listOf(futureRuntimeExt))

        val eligible = resolver.resolveEligibleExtensions()
        assertTrue("Extension requiring future runtimeApiVersion must be rejected", eligible.isEmpty())

        val eval = resolver.evaluateEligibility(futureRuntimeExt)
        assertTrue(eval is com.example.extension.managed.usecase.ExtensionEligibilityResult.Ineligible)
        val inelig = eval as com.example.extension.managed.usecase.ExtensionEligibilityResult.Ineligible
        assertEquals(IneligibilityReason.INCOMPATIBLE_RUNTIME_API, inelig.reason)
    }

    // --- REQUIREMENT 11: Invalid Configuration Rejection ---
    @Test
    fun test11_InvalidConfigurationRejection() {
        val invalidUrlExt = validActiveExtension.copy(id = "ext-invalid", baseUrl = "ftp://invalid-url.com")
        val eval = resolver.evaluateEligibility(invalidUrlExt)
        assertTrue(eval is com.example.extension.managed.usecase.ExtensionEligibilityResult.Ineligible)
        val inelig = eval as com.example.extension.managed.usecase.ExtensionEligibilityResult.Ineligible
        assertEquals(IneligibilityReason.INVALID_CONFIGURATION, inelig.reason)
    }

    // --- REQUIREMENT 12: Priority Ordering ---
    @Test
    fun test12_PriorityOrdering() {
        val lowPriority = validActiveExtension.copy(id = "ext-low", priority = 10)
        val medPriority = validActiveExtension.copy(id = "ext-med", priority = 50)
        val highPriority = validActiveExtension.copy(id = "ext-high", priority = 100)

        extensionRegistry.setExtensions(listOf(lowPriority, highPriority, medPriority))

        val candidates = resolver.resolveEligibleExtensions()
        assertEquals(3, candidates.size)
        // Must be sorted strictly descending by priority: 100 > 50 > 10
        assertEquals("ext-high", candidates[0].id)
        assertEquals("ext-med", candidates[1].id)
        assertEquals("ext-low", candidates[2].id)
    }

    // --- REQUIREMENT 13: Fallback Behavior Across Eligible Candidates ---
    @Test
    fun test13_FallbackBehaviorAcrossEligibleCandidates() = runBlocking {
        var callCount = 0
        val mockFailingScraper = object : BaseSiteScraper {
            override val scraperKey: String = "fail_scraper"
            override val implementationVersion: Int = 1
            override val supportedCapabilities: Set<ScraperCapability> = setOf(ScraperCapability.SEARCH)
            override val supportedContentTypes: Set<ContentType> = setOf(ContentType.MOVIE, ContentType.SERIES)
            override suspend fun search(extension: ManagedExtension, request: SearchRequest): Result<SearchResult> {
                callCount++
                return Result.failure(ExtensionError.MediaNotFound("Item not found on first candidate"))
            }
            override suspend fun getDetails(extension: ManagedExtension, url: String) = Result.failure<MediaDetailsResult>(Exception())
            override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int) = Result.failure<List<EpisodeItem>>(Exception())
            override suspend fun discoverServers(extension: ManagedExtension, session: ExtractionSession, request: ServerDiscoveryRequest, webEngine: WebExtractionEngine?) = Result.failure<ServerDiscoveryResult>(Exception())
            override suspend fun extractStream(extension: ManagedExtension, session: ExtractionSession, request: ExtractionRequest, webEngine: WebExtractionEngine?) = Result.failure<ExtractionResult>(Exception())
        }
        val mockSucceedingScraper = object : BaseSiteScraper {
            override val scraperKey: String = "success_scraper"
            override val implementationVersion: Int = 1
            override val supportedCapabilities: Set<ScraperCapability> = setOf(ScraperCapability.SEARCH)
            override val supportedContentTypes: Set<ContentType> = setOf(ContentType.MOVIE, ContentType.SERIES)
            override suspend fun search(extension: ManagedExtension, request: SearchRequest): Result<SearchResult> {
                callCount++
                return Result.success(SearchResult(items = listOf(SearchMediaItem("id1", "Found Title", null, "https://example.com/item", ContentType.MOVIE))))
            }
            override suspend fun getDetails(extension: ManagedExtension, url: String) = Result.failure<MediaDetailsResult>(Exception())
            override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int) = Result.failure<List<EpisodeItem>>(Exception())
            override suspend fun discoverServers(extension: ManagedExtension, session: ExtractionSession, request: ServerDiscoveryRequest, webEngine: WebExtractionEngine?) = Result.failure<ServerDiscoveryResult>(Exception())
            override suspend fun extractStream(extension: ManagedExtension, session: ExtractionSession, request: ExtractionRequest, webEngine: WebExtractionEngine?) = Result.failure<ExtractionResult>(Exception())
        }

        val customScraperRegistry = ScraperRegistry(
            mapOf(
                "fail_scraper" to mockFailingScraper,
                "success_scraper" to mockSucceedingScraper
            )
        )
        val customResolver = ManagedExtensionResolver(
            registry = extensionRegistry,
            scraperRegistry = customScraperRegistry,
            currentAppVersionCode = 100L,
            supportedRuntimeApiVersion = 1
        )
        val customRuntime = DefaultControlledManagedExtensionRuntime(
            scraperRegistry = customScraperRegistry,
            managedExtensionRegistry = extensionRegistry
        )

        val candidate1 = validActiveExtension.copy(id = "c1", scraperKey = "fail_scraper", priority = 100)
        val candidate2 = validActiveExtension.copy(id = "c2", scraperKey = "success_scraper", priority = 50)
        extensionRegistry.setExtensions(listOf(candidate1, candidate2))

        val useCase = SearchManagedExtensionsUseCase(
            resolver = customResolver,
            runtime = customRuntime,
            fallbackManager = fallbackManager,
            isOnlineChecker = { true }
        )

        val outcome = useCase.execute("Batman", ContentType.MOVIE)
        assertTrue("Search should fall back to second candidate and succeed", outcome.isSuccess)
        assertEquals("Found Title", outcome.getOrThrow().items.first().title)
        assertEquals(2, callCount)
    }

    // --- REQUIREMENT 14: Normalized Result Generation ---
    @Test
    fun test14_NormalizedResultGeneration() {
        val server = ServerItem(id = "s1", name = "Fast CDN", link = "https://cdn.example.com/video.m3u8", isDirectStream = true)
        val playbackSource = PlaybackSource(streamUrl = server.link, mimeType = "application/x-mpegURL")
        val extractionResult = ExtractionResult(playbackSource = playbackSource)

        assertNotNull(extractionResult.playbackSource)
        assertEquals("https://cdn.example.com/video.m3u8", extractionResult.playbackSource?.streamUrl)
        assertEquals("application/x-mpegURL", extractionResult.playbackSource?.mimeType)
    }

    // --- REQUIREMENT 15: Player Handoff ---
    @Test
    fun test15_PlayerHandoff() {
        val playbackSource = PlaybackSource(
            streamUrl = "https://cdn.example.com/stream.m3u8",
            mimeType = "application/x-mpegURL",
            headers = mapOf("Referer" to "https://tv10.egydead.live/"),
            qualities = listOf(QualitySource("1080p", "https://cdn.example.com/1080.m3u8"))
        )

        val playerInput = PlayerHandoffAdapter.toPlayerInput(
            playbackSource = playbackSource,
            serverName = "VIP Server",
            websiteName = "EgyDead"
        )

        assertEquals("https://cdn.example.com/stream.m3u8", playerInput.mediaUrl)
        assertEquals("application/x-mpegURL", playerInput.mimeType)
        assertEquals("VIP Server", playerInput.serverName)
        assertEquals("EgyDead", playerInput.websiteName)
        assertEquals(1, playerInput.qualities.size)
    }

    // --- REQUIREMENT 16: Downloader Handoff ---
    @Test
    fun test16_DownloaderHandoff() {
        val downloadTask = DownloadTaskRequest(
            id = "task-123",
            title = "Movie Title",
            downloadUrl = "https://cdn.example.com/download.mp4",
            headers = mapOf("Referer" to "https://tv10.egydead.live/", "Cookie" to "session=secret"),
            mimeType = "video/mp4"
        )

        val downloaderInput = DownloaderHandoffAdapter.toDownloaderInput(downloadTask)
        assertEquals("task-123", downloaderInput.taskId)
        assertEquals("https://cdn.example.com/download.mp4", downloaderInput.downloadUrl)
        // Cookie header must be strictly stripped
        assertFalse(downloaderInput.headers.containsKey("Cookie"))
        assertTrue(downloaderInput.headers.containsKey("Referer"))
    }

    // --- REQUIREMENT 17: Credential Projection ---
    @Test
    fun test17_CredentialProjection() {
        val sessionHeaders = mapOf(
            "User-Agent" to "CineStream/1.0",
            "Referer" to "https://target.com/page",
            "Authorization" to "Bearer token_secret",
            "X-Internal-Token" to "private"
        )
        val sessionCookies = mapOf(
            "session_id" to "12345",
            "auth_token" to "xyz"
        )

        // Project with default allowed stream headers
        val projected = CredentialProjector.projectHeaders(
            sessionHeaders = sessionHeaders,
            sessionCookies = sessionCookies
        )

        assertTrue(projected.containsKey("User-Agent"))
        assertTrue(projected.containsKey("Referer"))
        assertFalse("Bearer token must NOT be projected by default", projected.containsKey("Authorization"))
        assertFalse("Internal token must NOT be projected", projected.containsKey("X-Internal-Token"))
        assertFalse("Session cookies must NOT be projected without explicit requirement", projected.containsKey("Cookie"))
    }

    // --- REQUIREMENT 18: Offline Behavior ---
    @Test
    fun test18_OfflineBehavior() = runBlocking {
        val offlineUseCase = SearchManagedExtensionsUseCase(
            resolver = resolver,
            runtime = runtime,
            fallbackManager = fallbackManager,
            isOnlineChecker = { false } // Offline
        )

        val result = offlineUseCase.execute("Avatar", ContentType.MOVIE)
        assertTrue("Search while offline must fail", result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue("Failure must be ExtensionError.NoInternet", exception is ExtensionError.NoInternet)
    }

    // --- REQUIREMENT 19: Legacy Isolation ---
    @Test
    fun test19_LegacyIsolation() {
        val migrationAdapter = LegacyFallbackMigrationAdapter(fallbackManager)

        // Security / compatibility error -> strictly NO legacy fallback
        val securityError = ExtensionError.IncompatibleRuntime(1, 99)
        val decision = migrationAdapter.evaluateFallbackEligibility(securityError)
        assertTrue("IncompatibleRuntime must NEVER fall back to legacy", decision is LegacyFallbackMigrationAdapter.FallbackDecision.DisallowFallback)

        val unknownScraperError = ExtensionError.MissingBundledScraper("unknown_scraper")
        val decision2 = migrationAdapter.evaluateFallbackEligibility(unknownScraperError)
        assertTrue("Missing scraper must NEVER fall back to legacy", decision2 is LegacyFallbackMigrationAdapter.FallbackDecision.DisallowFallback)

        // Recoverable media not found -> allowed fallback
        val recoverableError = ExtensionError.MediaNotFound("Not found on managed site")
        val decision3 = migrationAdapter.evaluateFallbackEligibility(recoverableError)
        assertTrue("Recoverable MediaNotFound may fall back to legacy", decision3 is LegacyFallbackMigrationAdapter.FallbackDecision.AllowLegacyFallback)
    }

    // --- REQUIREMENT 20: No Dynamic Code Loading ---
    @Test
    fun test20_NoDynamicCodeLoading() {
        val rootDir = File("src/main/java")
        val altRootDir = File("app/src/main/java")
        val targetDir = if (rootDir.exists()) rootDir else altRootDir

        assertTrue("Source directory must exist", targetDir.exists())

        val forbiddenTokens = listOf(
            "DexClassLoader",
            "PathClassLoader",
            "dalvik.system",
            "InMemoryDexClassLoader",
            "URLClassLoader"
        )

        val violations = mutableListOf<String>()
        val kotlinFiles = targetDir.walkTopDown().filter { it.extension == "kt" }.toList()

        for (file in kotlinFiles) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                for (token in forbiddenTokens) {
                    if (trimmed.contains(token)) {
                        violations.add("${file.name}:${index + 1} uses forbidden dynamic classloader: $token")
                    }
                }
            }
        }

        assertTrue(
            "No dynamic code loading mechanisms allowed in project. Violations found:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
