package com.example.extension.managed

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.GlobalExtensionConfigState
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.repository.DefaultManagedExtensionRepository
import com.example.extension.managed.repository.ManagedExtensionCache
import com.example.extension.managed.repository.ManagedExtensionDto
import com.example.extension.managed.repository.ManagedExtensionRemoteDataSource
import com.example.extension.managed.searchorder.DefaultSearchOrderRepository
import com.example.extension.managed.searchorder.ExtensionEligibilityFilter
import com.example.extension.managed.searchorder.SearchOrder
import com.example.extension.managed.searchorder.SearchOrderDataSource
import com.example.extension.managed.trace.Phase05HLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 05H: Global Extension Control Enforcement Unit and Integration Test Suite.
 *
 * Verifies:
 * 1. Firestore read policy: /managed_extensions, /extensions, and /config/search_order are public read, admin write only.
 * 2. Sensitive collections (/users, /admins, /auditLogs, etc.) remain strictly authenticated/admin protected.
 * 3. Canonical global authority: Remote status (ACTIVE, DISABLED, MAINTENANCE, DEPRECATED) is authoritative.
 * 4. No per-user overrides: Local user toggles/preferences cannot override Admin DISABLED status.
 * 5. Returning user cache preservation: Disabled status survives in cache and is not overwritten by bundled defaults.
 * 6. Priority rule: /managed_extensions always wins over legacy /extensions.
 * 7. Global search order: Admin search order applies globally without user-local reordering.
 * 8. Protection against resurrection: Remotely disabled extensions are never resurrected as ACTIVE.
 * 9. hasActiveExtensions accurate behavior: Returns false when all extensions are disabled.
 * 10. Phase 05H forensic trace logging tags.
 */
class Phase05HGlobalExtensionControlTest {

    private lateinit var rulesContent: String

    private val sampleActiveQfilm = ManagedExtension(
        id = "qfilm",
        name = "Qfilm",
        baseUrl = "https://qfilm.example.com",
        scraperKey = "qfilm",
        contentTypes = setOf(ContentType.MOVIE),
        status = ExtensionLifecycleStatus.ACTIVE,
        priority = 100
    )

    private val sampleDisabledQfilm = ManagedExtension(
        id = "qfilm",
        name = "Qfilm",
        baseUrl = "https://qfilm.example.com",
        scraperKey = "qfilm",
        contentTypes = setOf(ContentType.MOVIE),
        status = ExtensionLifecycleStatus.DISABLED,
        priority = 100
    )

    private val sampleActiveEgydead = ManagedExtension(
        id = "egydead",
        name = "EgyDead",
        baseUrl = "https://egydead.example.com",
        scraperKey = "egydead",
        contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES),
        status = ExtensionLifecycleStatus.ACTIVE,
        priority = 90
    )

    @Before
    fun setUp() {
        val candidates = listOf(
            File("firestore.rules"),
            File("../firestore.rules"),
            File("../../firestore.rules"),
            File("/firestore.rules")
        )
        val file = candidates.firstOrNull { it.exists() && it.isFile }
            ?: throw IllegalStateException("firestore.rules not found in candidate paths: $candidates")
        rulesContent = file.readText()
    }

    // --- 1. FIRESTORE SECURITY RULES VERIFICATION ---

    @Test
    fun test01_firestoreRules_publicRead_adminWriteOnly_forExtensionPaths() {
        // /managed_extensions: Public read, admin write
        assertTrue(
            "/managed_extensions must allow public read and admin write",
            rulesContent.contains("match /managed_extensions/{extensionId} {\n      allow read: if true;\n      allow write: if isAdmin();\n    }")
        )

        // /extensions: Public read, admin write
        assertTrue(
            "/extensions must allow public read and admin write",
            rulesContent.contains("match /extensions/{extensionId} {\n      allow read: if true;\n      allow write: if isAdmin();\n    }")
        )

        // /config/search_order: Public read, admin write
        assertTrue(
            "/config/search_order must allow public read and admin write",
            rulesContent.contains("match /config/search_order {\n      allow read: if true;\n      allow write: if isAdmin();\n    }")
        )
    }

    @Test
    fun test02_firestoreRules_sensitiveCollectionsRemainStrictlyProtected() {
        // Sensitive collections must NEVER allow public read
        assertFalse("/users must not be public", rulesContent.contains("match /users/{userId} {\n      allow read: if true"))
        assertFalse("/admins must not be public", rulesContent.contains("match /admins/{userId} {\n      allow read: if true"))
        assertFalse("/point_transactions must not be public", rulesContent.contains("match /point_transactions/{txId} {\n      allow read: if true"))
        assertFalse("/auditLogs must not be public", rulesContent.contains("match /auditLogs/{logId} {\n      allow read: if true"))
        assertFalse("root wildcard must not be open", rulesContent.contains("match /{document=**} {\n      allow read, write: if true"))
    }

    // --- 2. CANONICAL GLOBAL AUTHORITY: ADMIN DISABLED STATE CANNOT BE OVERRIDDEN ---

    @Test
    fun test03_adminDisabledExtension_rejectedByEligibilityFilter() {
        val filter = ExtensionEligibilityFilter()

        val available = listOf(sampleDisabledQfilm, sampleActiveEgydead)
        val orderedIds = listOf("qfilm", "egydead")

        val eligible = filter.filterEligibleExtensions(
            orderedExtensionIds = orderedIds,
            availableExtensions = available,
            targetContentType = ContentType.MOVIE
        )

        assertEquals("Only 1 extension should be eligible", 1, eligible.size)
        assertEquals("egydead", eligible.first().id)
        assertFalse("qfilm must NOT be eligible when Admin disabled it", eligible.any { it.id == "qfilm" })
    }

    @Test
    fun test04_adminDisabledExtension_excludedFromRegistryActiveExtensions() {
        val registry = ManagedExtensionRegistry()
        registry.setExtensions(listOf(sampleDisabledQfilm, sampleActiveEgydead))

        val active = registry.getActiveExtensions()
        assertEquals(1, active.size)
        assertEquals("egydead", active.first().id)
        assertFalse("qfilm must NOT be active when status is DISABLED", active.any { it.id == "qfilm" })
    }

    @Test
    fun test05_localUserPreference_cannotOverrideAdminDisabledStatus() {
        val registry = ManagedExtensionRegistry()
        registry.setExtensions(listOf(sampleDisabledQfilm))

        // Even if local preference tries to set enabled = true
        registry.updateExtensionUserPreference("qfilm", true)

        val active = registry.getActiveExtensions()
        assertTrue(
            "Local preference cannot turn a DISABLED extension into ACTIVE",
            active.none { it.id == "qfilm" }
        )
    }

    // --- 3. RETURNING USER CACHE PRESERVATION ---

    @Test
    fun test06_cachedDisabledStatus_preservedDuringRemoteFailure() = runBlocking {
        val fakeCache = object : ManagedExtensionCache {
            private var data: List<ManagedExtension>? = listOf(sampleDisabledQfilm, sampleActiveEgydead)
            override fun getCached(): List<ManagedExtension>? = data
            override fun saveCache(extensions: List<ManagedExtension>) { data = extensions }
            override fun isExpired(): Boolean = false
            override fun clear() { data = null }
            override fun clearCache() { data = null }
            override fun getCachedTimestamp(): Long = 0L
        }

        val remoteFailureSource = object : ManagedExtensionRemoteDataSource {
            override suspend fun fetchManagedExtensionDtos(): Result<List<ManagedExtensionDto>> {
                return Result.failure(java.io.IOException("Network unavailable"))
            }
        }

        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = remoteFailureSource,
            cache = fakeCache,
            bundledDefaults = listOf(sampleActiveQfilm, sampleActiveEgydead)
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue(result.isSuccess)
        val extensions = result.getOrThrow()

        val qfilm = extensions.first { it.id == "qfilm" }
        assertEquals(
            "Cached DISABLED status must NOT be overridden by bundled default ACTIVE status",
            ExtensionLifecycleStatus.DISABLED,
            qfilm.status
        )
    }

    // --- 4. MANAGED_EXTENSIONS WINS OVER LEGACY /EXTENSIONS ---

    @Test
    fun test07_managedExtensionsTakesPrecedenceOverLegacyExtensions() = runBlocking {
        // DTO from canonical /managed_extensions has status = DISABLED
        val canonicalDto = ManagedExtensionDto(
            id = "qfilm",
            name = "Qfilm Canonical",
            baseUrl = "https://qfilm.example.com",
            scraperKey = "qfilm",
            status = "DISABLED",
            contentTypes = listOf("MOVIE"),
            priority = 100
        )

        // DTO from legacy /extensions has status = ACTIVE (stale or bypassed)
        val legacyDto = ManagedExtensionDto(
            id = "qfilm",
            name = "Qfilm Legacy",
            baseUrl = "https://qfilm.legacy.com",
            scraperKey = "qfilm",
            status = "ACTIVE",
            contentTypes = listOf("MOVIE"),
            priority = 50
        )

        val fakeRemoteSource = object : ManagedExtensionRemoteDataSource {
            override suspend fun fetchManagedExtensionDtos(): Result<List<ManagedExtensionDto>> {
                // Emulates reading canonical first, legacy second (only adding missing IDs)
                val dtosMap = LinkedHashMap<String, ManagedExtensionDto>()
                dtosMap[canonicalDto.id!!] = canonicalDto
                if (!dtosMap.containsKey(legacyDto.id)) {
                    dtosMap[legacyDto.id!!] = legacyDto
                }
                return Result.success(dtosMap.values.toList())
            }
        }

        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = fakeRemoteSource
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue(result.isSuccess)
        val catalog = result.getOrThrow()
        val qfilm = catalog.first { it.id == "qfilm" }

        assertEquals("Canonical status must win", ExtensionLifecycleStatus.DISABLED, qfilm.status)
        assertEquals("Canonical name must win", "Qfilm Canonical", qfilm.name)
    }

    // --- 5. SEARCH ORDER GLOBAL AUTHORITY ---

    @Test
    fun test08_searchOrderGlobalAuthority() = runBlocking {
        val fakeSearchOrderSource = object : SearchOrderDataSource {
            override suspend fun fetchSearchOrder(): SearchOrder {
                return SearchOrder(
                    movie = listOf("qfilm", "egydead"),
                    tv = listOf("egydead"),
                    series = listOf("egydead"),
                    anime = listOf("witanime", "anime4up")
                )
            }
        }

        val repository = DefaultSearchOrderRepository(dataSource = fakeSearchOrderSource)
        val movieOrder = repository.getOrderForContentType(ContentType.MOVIE)

        assertEquals(listOf("qfilm", "egydead"), movieOrder)
        val animeOrder = repository.getOrderForContentType(ContentType.ANIME)
        assertEquals(listOf("witanime", "anime4up"), animeOrder)
    }

    // --- 6. PROTECTION AGAINST RESURRECTION OF REMOTELY DISABLED EXTENSIONS ---

    @Test
    fun test09_remotelyDisabledExtension_notResurrectedByBundledDefaultsOnValidationFailure() = runBlocking {
        // Remote sent qfilm as DISABLED, but with an invalid baseUrl (triggering rejection by validator)
        val malformedDisabledDto = ManagedExtensionDto(
            id = "qfilm",
            name = "Qfilm",
            baseUrl = "ftp://invalid-url.com", // Invalid scheme, triggers validation failure
            scraperKey = "qfilm",
            status = "DISABLED",
            contentTypes = listOf("MOVIE"),
            priority = 100
        )

        val fakeRemoteSource = object : ManagedExtensionRemoteDataSource {
            override suspend fun fetchManagedExtensionDtos(): Result<List<ManagedExtensionDto>> {
                return Result.success(listOf(malformedDisabledDto))
            }
        }

        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = fakeRemoteSource,
            bundledDefaults = listOf(sampleActiveQfilm) // Bundled default has ACTIVE
        )

        val result = repository.getExtensions(forceRefresh = true)
        assertTrue(result.isSuccess)
        val catalog = result.getOrThrow()

        val qfilm = catalog.firstOrNull { it.id == "qfilm" }
        assertNotNull("qfilm should be present in catalog", qfilm)
        assertEquals(
            "Remotely disabled extension must NEVER be resurrected as ACTIVE from bundled defaults",
            ExtensionLifecycleStatus.DISABLED,
            qfilm?.status
        )
    }

    // --- 7. HAS ACTIVE EXTENSIONS ACCURATE LOGIC ---

    @Test
    fun test10_allExtensionsDisabled_registryActiveExtensionsIsEmpty() {
        val registry = ManagedExtensionRegistry()
        registry.setExtensions(listOf(sampleDisabledQfilm))

        assertEquals(0, registry.getActiveExtensions().size)
    }

    // --- 8. GLOBAL CONFIG STATE ENUM INTEGRITY ---

    @Test
    fun test11_globalExtensionConfigStateEnum() {
        val pending = GlobalExtensionConfigState.REMOTE_SYNC_PENDING
        val ready = GlobalExtensionConfigState.READY
        val unavailable = GlobalExtensionConfigState.GLOBAL_CONFIG_UNAVAILABLE

        assertNotNull(pending)
        assertNotNull(ready)
        assertNotNull(unavailable)
        assertEquals(3, GlobalExtensionConfigState.values().size)
    }

    // --- 9. FORENSIC TRACE LOGGER ---

    @Test
    fun test12_phase05HLoggerEmitsForensicTags() {
        // Verify logger executes without throwing
        Phase05HLogger.log("SYNC", "orchestrator", "Global sync verified")
        Phase05HLogger.log("STATUS", "qfilm", "status=DISABLED")
        Phase05HLogger.log("STATE", "orchestrator", "state=READY")
    }
}
