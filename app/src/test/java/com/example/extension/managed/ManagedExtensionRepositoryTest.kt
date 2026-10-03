package com.example.extension.managed

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.repository.DefaultManagedExtensionRepository
import com.example.extension.managed.repository.ExtensionUserPreferences
import com.example.extension.managed.repository.InMemoryExtensionUserPreferences
import com.example.extension.managed.repository.ManagedExtensionCache
import com.example.extension.managed.repository.ManagedExtensionDto
import com.example.extension.managed.repository.ManagedExtensionRemoteDataSource
import com.example.extension.managed.repository.SafeLocalMetadataCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ManagedExtensionRepositoryTest {

    private class FakeRemoteDataSource(
        var result: Result<List<ManagedExtensionDto>> = Result.success(emptyList())
    ) : ManagedExtensionRemoteDataSource {
        override suspend fun fetchManagedExtensionDtos(): Result<List<ManagedExtensionDto>> = result
    }

    private fun validDto(
        id: String = "egydead-prod",
        priority: Long = 10L,
        updatedAt: Long = 1000L
    ) = ManagedExtensionDto(
        id = id,
        name = "EgyDead Provider",
        description = "Movies and series",
        baseUrl = "https://tv10.egydead.live",
        scraperKey = "egydead",
        definitionVersion = 1L,
        minAppVersionCode = 1L,
        runtimeApiVersion = 1L,
        priority = priority,
        language = "ar",
        contentTypes = listOf("MOVIE", "SERIES"),
        status = "ACTIVE",
        updatedAt = updatedAt
    )

    @Test
    fun getExtensions_validDtos_returnsValidatedListSortedByPriority() = runBlocking {
        val dtos = listOf(
            validDto("ext-low", priority = 5L),
            validDto("ext-high", priority = 20L),
            validDto("ext-mid", priority = 10L)
        )
        val dataSource = FakeRemoteDataSource(Result.success(dtos))
        val repository = DefaultManagedExtensionRepository(dataSource)

        val result = repository.getExtensions()
        assertTrue(result.isSuccess)
        val list = result.getOrNull() ?: emptyList()
        assertEquals(3, list.size)
        assertEquals("ext-high", list[0].id)
        assertEquals("ext-mid", list[1].id)
        assertEquals("ext-low", list[2].id)
    }

    @Test
    fun getExtensions_malformedDocument_skippedWithoutCrashingEntireList() = runBlocking {
        val dtos = listOf(
            validDto("valid-ext"),
            ManagedExtensionDto(
                id = "insecure-http",
                name = "Bad HTTP",
                baseUrl = "http://tv10.egydead.live", // Insecure HTTP
                scraperKey = "egydead",
                contentTypes = listOf("MOVIE")
            ),
            ManagedExtensionDto(
                id = "private-ip",
                name = "Private IP",
                baseUrl = "https://127.0.0.1", // Private loopback
                scraperKey = "egydead",
                contentTypes = listOf("MOVIE")
            ),
            ManagedExtensionDto(
                id = "blank-scraper",
                name = "Blank Scraper",
                baseUrl = "https://tv10.egydead.live",
                scraperKey = "", // Blank scraperKey
                contentTypes = listOf("MOVIE")
            ),
            ManagedExtensionDto(
                id = "empty-types",
                name = "No Types",
                baseUrl = "https://tv10.egydead.live",
                scraperKey = "egydead",
                contentTypes = emptyList() // Empty content types
            )
        )

        val dataSource = FakeRemoteDataSource(Result.success(dtos))
        val repository = DefaultManagedExtensionRepository(dataSource)

        val result = repository.getExtensions()
        assertTrue(result.isSuccess)
        val list = result.getOrNull() ?: emptyList()
        assertEquals("Only the valid extension should survive validation gating", 1, list.size)
        assertEquals("valid-ext", list[0].id)
    }

    @Test
    fun getExtensions_duplicateIds_deduplicatesDeterministically() = runBlocking {
        val dtos = listOf(
            validDto("duplicate-id", priority = 5L, updatedAt = 1000L),
            validDto("duplicate-id", priority = 10L, updatedAt = 2000L) // Newer
        )
        val dataSource = FakeRemoteDataSource(Result.success(dtos))
        val repository = DefaultManagedExtensionRepository(dataSource)

        val result = repository.getExtensions()
        assertTrue(result.isSuccess)
        val list = result.getOrNull() ?: emptyList()
        assertEquals(1, list.size)
        assertEquals(2000L, list[0].updatedAt)
        assertEquals(10, list[0].priority)
    }

    @Test
    fun getExtensions_offlineWithCachedData_returnsCachedData() = runBlocking {
        val cache = SafeLocalMetadataCache()
        val userPrefs = InMemoryExtensionUserPreferences()
        val dtos = listOf(validDto("cached-ext"))

        // Initial online fetch
        val onlineDataSource = FakeRemoteDataSource(Result.success(dtos))
        val repository = DefaultManagedExtensionRepository(onlineDataSource, cache, userPrefs)
        val firstResult = repository.getExtensions()
        assertTrue(firstResult.isSuccess)

        // Remote now fails with network error
        val offlineDataSource = FakeRemoteDataSource(Result.failure(IOException("Network unreachable")))
        val offlineRepo = DefaultManagedExtensionRepository(offlineDataSource, cache, userPrefs)

        val offlineResult = offlineRepo.getExtensions(forceRefresh = true)
        assertTrue("Offline query must fall back to valid cache", offlineResult.isSuccess)
        assertEquals(1, offlineResult.getOrNull()?.size)
        assertEquals("cached-ext", offlineResult.getOrNull()?.first()?.id)
    }

    @Test
    fun getExtensions_offlineNoCache_returnsNoInternetError() = runBlocking {
        val emptyCache = SafeLocalMetadataCache()
        val offlineDataSource = FakeRemoteDataSource(Result.failure(IOException("No route to host")))
        val repository = DefaultManagedExtensionRepository(offlineDataSource, emptyCache)

        val result = repository.getExtensions()
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("Must map network failure without cache to ExtensionError.NoInternet", error is ExtensionError.NoInternet)
    }

    @Test
    fun userPreferences_respectsLocalDisablement() = runBlocking {
        val dtos = listOf(validDto("ext-pref-1"))
        val userPrefs = InMemoryExtensionUserPreferences()
        userPrefs.setExtensionEnabled("ext-pref-1", false) // User disabled locally

        val repository = DefaultManagedExtensionRepository(
            remoteDataSource = FakeRemoteDataSource(Result.success(dtos)),
            userPreferences = userPrefs
        )

        val result = repository.getExtensions()
        assertTrue(result.isSuccess)
        val ext = result.getOrNull()?.first()
        assertNotNull(ext)
        assertFalse("Local user preference disablement must be reflected in userEnabled", ext!!.userEnabled)
    }

    @Test
    fun getExtensionById_findsExistingAndRejectsMissing() = runBlocking {
        val dtos = listOf(validDto("ext-target"))
        val repository = DefaultManagedExtensionRepository(FakeRemoteDataSource(Result.success(dtos)))

        val successResult = repository.getExtensionById("ext-target")
        assertTrue(successResult.isSuccess)
        assertEquals("ext-target", successResult.getOrNull()?.id)

        val failResult = repository.getExtensionById("non-existent-id")
        assertTrue(failResult.isFailure)
        assertTrue(failResult.exceptionOrNull() is ExtensionError.ExtractionFailed)
    }
}
