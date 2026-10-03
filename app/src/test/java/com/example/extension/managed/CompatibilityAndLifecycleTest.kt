package com.example.extension.managed

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.runtime.DefaultControlledManagedExtensionRuntime
import com.example.extension.managed.runtime.FallbackManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityAndLifecycleTest {

    private val registry = ScraperRegistry.INSTANCE
    private val fallbackManager = FallbackManager(registry)
    private val runtime = DefaultControlledManagedExtensionRuntime(
        scraperRegistry = registry,
        fallbackManager = fallbackManager,
        currentAppVersionCode = 10L,
        supportedRuntimeApiVersion = 2
    )

    private fun sampleExtension(
        status: ExtensionLifecycleStatus = ExtensionLifecycleStatus.ACTIVE,
        userEnabled: Boolean = true,
        minAppVersion: Long = 5L,
        runtimeApi: Int = 1,
        contentTypes: Set<ContentType> = setOf(ContentType.MOVIE, ContentType.SERIES)
    ): ManagedExtension {
        return ManagedExtension(
            id = "test-egydead",
            name = "EgyDead Test",
            baseUrl = "https://tv10.egydead.live",
            scraperKey = "egydead",
            priority = 10,
            minAppVersionCode = minAppVersion,
            runtimeApiVersion = runtimeApi,
            contentTypes = contentTypes,
            status = status,
            userEnabled = userEnabled
        )
    }

    @Test
    fun contentTypeMismatch_rejectedByFallback() {
        val ext = sampleExtension(contentTypes = setOf(ContentType.ANIME))
        val candidates = fallbackManager.filterAndSortCandidates(
            candidates = listOf(ext),
            contentType = ContentType.MOVIE,
            capability = ScraperCapability.SEARCH,
            appVersionCode = 10L,
            supportedRuntimeApi = 2
        )
        assertTrue("Extension without MOVIE content type should be rejected", candidates.isEmpty())
    }

    @Test
    fun capabilityMismatch_rejectedByFallback() {
        val ext = sampleExtension()
        val candidates = fallbackManager.filterAndSortCandidates(
            candidates = listOf(ext),
            contentType = ContentType.MOVIE,
            capability = ScraperCapability.DIRECT_DOWNLOAD, // EgyDead does not declare DIRECT_DOWNLOAD
            appVersionCode = 10L,
            supportedRuntimeApi = 2
        )
        assertTrue("Extension without DIRECT_DOWNLOAD capability should be rejected", candidates.isEmpty())
    }

    @Test
    fun matchingContentTypeAndCapability_accepted() {
        val ext = sampleExtension()
        val candidates = fallbackManager.filterAndSortCandidates(
            candidates = listOf(ext),
            contentType = ContentType.SERIES,
            capability = ScraperCapability.EPISODES,
            appVersionCode = 10L,
            supportedRuntimeApi = 2
        )
        assertEquals(1, candidates.size)
        assertEquals(ext.id, candidates[0].id)
    }

    @Test
    fun lifecycleStatuses_handledCorrectly() {
        // ACTIVE -> Accepted
        val active = sampleExtension(status = ExtensionLifecycleStatus.ACTIVE)
        assertEquals(1, fallbackManager.filterAndSortCandidates(listOf(active), ContentType.MOVIE, ScraperCapability.SEARCH, 10L, 2).size)

        // MAINTENANCE -> Excluded from auto-fallback
        val maintenance = sampleExtension(status = ExtensionLifecycleStatus.MAINTENANCE)
        assertTrue(fallbackManager.filterAndSortCandidates(listOf(maintenance), ContentType.MOVIE, ScraperCapability.SEARCH, 10L, 2).isEmpty())

        // DISABLED -> Excluded
        val disabled = sampleExtension(status = ExtensionLifecycleStatus.DISABLED)
        assertTrue(fallbackManager.filterAndSortCandidates(listOf(disabled), ContentType.MOVIE, ScraperCapability.SEARCH, 10L, 2).isEmpty())

        // DEPRECATED -> Excluded from auto fallback
        val deprecated = sampleExtension(status = ExtensionLifecycleStatus.DEPRECATED)
        assertTrue(fallbackManager.filterAndSortCandidates(listOf(deprecated), ContentType.MOVIE, ScraperCapability.SEARCH, 10L, 2).isEmpty())
    }

    @Test
    fun adminStatus_hasSoleAuthorityOverUserPreference() {
        // ACTIVE + enabled -> Accepted
        val activeEnabled = sampleExtension(status = ExtensionLifecycleStatus.ACTIVE, userEnabled = true)
        assertEquals(1, fallbackManager.filterAndSortCandidates(listOf(activeEnabled), ContentType.MOVIE, ScraperCapability.SEARCH, 10L, 2).size)

        // ACTIVE + disabled -> Accepted (ACTIVE does not require userEnabled)
        val activeDisabled = sampleExtension(status = ExtensionLifecycleStatus.ACTIVE, userEnabled = false)
        assertEquals(1, fallbackManager.filterAndSortCandidates(listOf(activeDisabled), ContentType.MOVIE, ScraperCapability.SEARCH, 10L, 2).size)

        // DISABLED + enabled -> Rejected (Admin DISABLED wins globally, userEnabled cannot make it eligible)
        val disabledEnabled = sampleExtension(status = ExtensionLifecycleStatus.DISABLED, userEnabled = true)
        assertTrue(fallbackManager.filterAndSortCandidates(listOf(disabledEnabled), ContentType.MOVIE, ScraperCapability.SEARCH, 10L, 2).isEmpty())
    }

    @Test
    fun versionCompatibility_enforced() {
        // App version < minAppVersionCode -> Incompatible
        val oldApp = sampleExtension(minAppVersion = 15L) // app is 10L
        assertTrue(fallbackManager.filterAndSortCandidates(listOf(oldApp), ContentType.MOVIE, ScraperCapability.SEARCH, 10L, 2).isEmpty())

        // App version >= minAppVersionCode -> Compatible
        val compatibleApp = sampleExtension(minAppVersion = 10L)
        assertEquals(1, fallbackManager.filterAndSortCandidates(listOf(compatibleApp), ContentType.MOVIE, ScraperCapability.SEARCH, 10L, 2).size)

        // Runtime API > supported -> Incompatible
        val higherRuntime = sampleExtension(runtimeApi = 3) // supported is 2
        assertTrue(fallbackManager.filterAndSortCandidates(listOf(higherRuntime), ContentType.MOVIE, ScraperCapability.SEARCH, 10L, 2).isEmpty())

        // Runtime API <= supported -> Compatible
        val compatibleRuntime = sampleExtension(runtimeApi = 2)
        assertEquals(1, fallbackManager.filterAndSortCandidates(listOf(compatibleRuntime), ContentType.MOVIE, ScraperCapability.SEARCH, 10L, 2).size)
    }

    @Test
    fun directDetailsCall_validatesFullCompatibility() = runBlocking {
        // Direct call on disabled extension fails with SOURCE_DISABLED
        val disabled = sampleExtension(status = ExtensionLifecycleStatus.DISABLED)
        val result = runtime.getDetails(disabled, "https://tv10.egydead.live/movie/test")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("disabled globally") == true)

        // Direct call on maintenance extension fails with MAINTENANCE_HOLD
        val maint = sampleExtension(status = ExtensionLifecycleStatus.MAINTENANCE)
        val maintResult = runtime.getDetails(maint, "https://tv10.egydead.live/movie/test")
        assertTrue(maintResult.isFailure)
        assertTrue(maintResult.exceptionOrNull()?.message?.contains("maintenance") == true)

        // Direct call on old app version fails with INCOMPATIBLE_APP_VERSION
        val oldVer = sampleExtension(minAppVersion = 20L)
        val oldResult = runtime.getDetails(oldVer, "https://tv10.egydead.live/movie/test")
        assertTrue(oldResult.isFailure)
        assertTrue(oldResult.exceptionOrNull()?.message?.contains("below required minimum") == true)
    }
}
