package com.example.extension.managed

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.ServerItem
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.repository.DefaultManagedExtensionRepository
import com.example.extension.managed.repository.ManagedExtensionDto
import com.example.extension.managed.repository.ManagedExtensionRemoteDataSource
import com.example.extension.managed.runtime.DefaultControlledManagedExtensionRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3EndToEndPipelineTest {

    private class SingleSourceDataSource(private val dtos: List<ManagedExtensionDto>) : ManagedExtensionRemoteDataSource {
        override suspend fun fetchManagedExtensionDtos(): Result<List<ManagedExtensionDto>> = Result.success(dtos)
    }

    @Test
    fun endToEndPipeline_validRemoteEgyDead_reachesRuntimeAndExecutesExtraction() = runBlocking {
        // 1. Remote Firestore DTO
        val egydeadDto = ManagedExtensionDto(
            id = "egydead-cloud-v1",
            name = "EgyDead Official",
            description = "Stream movies & series",
            baseUrl = "https://tv10.egydead.live",
            scraperKey = "egydead",
            definitionVersion = 1L,
            minAppVersionCode = 1L,
            runtimeApiVersion = 1L,
            priority = 100L,
            contentTypes = listOf("MOVIE", "SERIES"),
            status = "ACTIVE"
        )

        // 2. Repository fetches, validates, and produces domain ManagedExtension
        val repository = DefaultManagedExtensionRepository(SingleSourceDataSource(listOf(egydeadDto)))
        val fetchResult = repository.getExtensions()
        assertTrue("Repository fetch must succeed", fetchResult.isSuccess)
        val extensions = fetchResult.getOrNull() ?: emptyList()
        assertEquals(1, extensions.size)
        assertEquals("egydead-cloud-v1", extensions[0].id)

        // 3. Populate ManagedExtensionRegistry with validated definitions
        val managedRegistry = ManagedExtensionRegistry()
        managedRegistry.setExtensions(extensions)
        assertEquals(1, managedRegistry.getActiveExtensions().size)

        // 4. Runtime backed by ManagedExtensionRegistry and static ScraperRegistry
        val runtime = DefaultControlledManagedExtensionRuntime(
            scraperRegistry = ScraperRegistry.INSTANCE,
            managedExtensionRegistry = managedRegistry,
            currentAppVersionCode = 1L,
            supportedRuntimeApiVersion = 1
        )

        // 5. Execute stream extraction through the runtime
        val server = ServerItem(
            id = "direct-srv-1",
            name = "Direct Server",
            link = "https://cdn.egydead.com/hls/sample.m3u8",
            isDirectStream = true
        )
        val extractionRequest = ExtractionRequest(serverItem = server)
        val extractionResult = runtime.extractStream(extractionRequest)

        assertTrue("Extraction through runtime must succeed", extractionResult.isSuccess)
        val playback = extractionResult.getOrNull()?.playbackSource
        assertNotNull("PlaybackSource must be produced", playback)
        assertEquals("https://cdn.egydead.com/hls/sample.m3u8", playback?.streamUrl)
        assertEquals("application/x-mpegURL", playback?.mimeType)
        assertEquals("https://tv10.egydead.live", playback?.headers?.get("Referer"))
    }

    @Test
    fun endToEndPipeline_unknownScraperKey_neverExecutesDynamicCode() = runBlocking {
        // Remote DTO with unknown scraper key
        val unknownDto = ManagedExtensionDto(
            id = "unknown-cloud-ext",
            name = "Unknown Site",
            baseUrl = "https://unknown.movie.com",
            scraperKey = "non_existent_site_key", // Not in ScraperRegistry
            definitionVersion = 1L,
            minAppVersionCode = 1L,
            runtimeApiVersion = 1L,
            priority = 50L,
            contentTypes = listOf("MOVIE"),
            status = "ACTIVE"
        )

        val directExt = com.example.extension.managed.repository.ManagedExtensionMapper.toDomain(unknownDto)
        val managedRegistry = ManagedExtensionRegistry(listOf(directExt))
        val runtime = DefaultControlledManagedExtensionRuntime(
            scraperRegistry = ScraperRegistry.INSTANCE,
            managedExtensionRegistry = managedRegistry
        )

        // Try searching with runtime
        val searchResult = runtime.search(SearchRequest(query = "Matrix", contentType = ContentType.MOVIE))

        // Must fail safely without crashing, without reflection, without dynamic APK loading
        assertTrue("Execution must fail when scraperKey is unknown to ScraperRegistry", searchResult.isFailure)
        val errMessage = searchResult.exceptionOrNull()?.message ?: ""
        assertTrue(
            "Error must indicate candidate failure or incompatibility",
            errMessage.contains("No compatible candidate", ignoreCase = true) ||
                    errMessage.contains("Unknown", ignoreCase = true)
        )
    }

    @Test
    fun endToEndPipeline_insecureHttpBaseUrl_rejectedAtRepositoryGate() = runBlocking {
        val insecureDto = ManagedExtensionDto(
            id = "insecure-ext",
            name = "Insecure Site",
            baseUrl = "http://insecure.egydead.live", // HTTP instead of HTTPS
            scraperKey = "egydead",
            contentTypes = listOf("MOVIE"),
            status = "ACTIVE"
        )

        val repository = DefaultManagedExtensionRepository(SingleSourceDataSource(listOf(insecureDto)))
        val extensions = repository.getExtensions().getOrNull() ?: emptyList()

        assertTrue("Insecure HTTP extension must be rejected by validator and omitted from repository", extensions.isEmpty())

        val managedRegistry = ManagedExtensionRegistry(extensions)
        assertEquals("ManagedExtensionRegistry must remain empty", 0, managedRegistry.getAllExtensions().size)
    }

    @Test
    fun endToEndPipeline_globallyDisabledStatus_excludedFromActiveExecution() = runBlocking {
        val disabledDto = ManagedExtensionDto(
            id = "disabled-ext",
            name = "Disabled Site",
            baseUrl = "https://tv10.egydead.live",
            scraperKey = "egydead",
            contentTypes = listOf("MOVIE"),
            status = "DISABLED"
        )

        val repository = DefaultManagedExtensionRepository(SingleSourceDataSource(listOf(disabledDto)))
        val extensions = repository.getExtensions().getOrNull() ?: emptyList()
        assertEquals(1, extensions.size)

        val managedRegistry = ManagedExtensionRegistry(extensions)
        assertTrue("Disabled extension must not appear in active extensions", managedRegistry.getActiveExtensions().isEmpty())

        val runtime = DefaultControlledManagedExtensionRuntime(
            scraperRegistry = ScraperRegistry.INSTANCE,
            managedExtensionRegistry = managedRegistry
        )

        val searchResult = runtime.search(SearchRequest(query = "Batman", contentType = ContentType.MOVIE))
        assertTrue("Disabled extensions cannot be used in runtime fallback", searchResult.isFailure)
    }
}
