package com.example.extension.managed

import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.EpisodeItem
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ExtractionResult
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.MediaDetailsResult
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchMediaItem
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.SearchResult
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.model.ServerDiscoveryResult
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.runtime.DefaultControlledManagedExtensionRuntime
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.runtime.FallbackManager
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackManagerTest {

    private class FakeTestScraper(
        override val scraperKey: String,
        private val searchBehavior: (SearchRequest) -> Result<SearchResult>
    ) : BaseSiteScraper {
        override val implementationVersion: Int = 1
        override val supportedCapabilities: Set<ScraperCapability> = setOf(ScraperCapability.SEARCH)
        override val supportedContentTypes: Set<ContentType> = setOf(ContentType.MOVIE)

        override suspend fun search(extension: ManagedExtension, request: SearchRequest): Result<SearchResult> {
            return searchBehavior(request)
        }

        override suspend fun getDetails(extension: ManagedExtension, url: String): Result<MediaDetailsResult> {
            return Result.failure(Exception("Not implemented"))
        }

        override suspend fun getEpisodes(extension: ManagedExtension, seriesUrl: String, season: Int): Result<List<EpisodeItem>> {
            return Result.failure(Exception("Not implemented"))
        }

        override suspend fun discoverServers(extension: ManagedExtension, session: ExtractionSession, request: ServerDiscoveryRequest, webEngine: WebExtractionEngine?): Result<ServerDiscoveryResult> {
            return Result.failure(Exception("Not implemented"))
        }

        override suspend fun extractStream(extension: ManagedExtension, session: ExtractionSession, request: ExtractionRequest, webEngine: WebExtractionEngine?): Result<ExtractionResult> {
            return Result.failure(Exception("Not implemented"))
        }
    }

    private fun createExtension(id: String, priority: Int, key: String): ManagedExtension {
        return ManagedExtension(
            id = id,
            name = id,
            baseUrl = "https://example.com/$id",
            scraperKey = key,
            priority = priority,
            contentTypes = setOf(ContentType.MOVIE),
            status = ExtensionLifecycleStatus.ACTIVE
        )
    }

    @Test
    fun priorityOrdering_higherNumberFirst() {
        val extLow = createExtension("low", priority = 5, key = "egydead")
        val extHigh = createExtension("high", priority = 20, key = "egydead")
        val extMed = createExtension("med", priority = 10, key = "egydead")

        val fallbackManager = FallbackManager(ScraperRegistry.INSTANCE)
        val sorted = fallbackManager.filterAndSortCandidates(
            listOf(extLow, extHigh, extMed),
            ContentType.MOVIE,
            ScraperCapability.SEARCH,
            1L,
            1
        )

        assertEquals(3, sorted.size)
        assertEquals("high", sorted[0].id)
        assertEquals("med", sorted[1].id)
        assertEquals("low", sorted[2].id)
    }

    @Test
    fun fallbackChain_firstFailsSecondSucceeds() = runBlocking {
        var firstCalled = false
        var secondCalled = false

        val scraper1 = FakeTestScraper("scraper1") {
            firstCalled = true
            Result.failure(Exception(ExtensionError.Timeout(5000L).message))
        }

        val scraper2 = FakeTestScraper("scraper2") {
            secondCalled = true
            Result.success(
                SearchResult(
                    items = listOf(SearchMediaItem("1", "Found", null, "https://example.com", ContentType.MOVIE)),
                    page = 1
                )
            )
        }

        val testRegistry = ScraperRegistry(
            mapOf<String, BaseSiteScraper>("scraper1" to scraper1, "scraper2" to scraper2)
        )
        val testFallback = FallbackManager(testRegistry)
        val runtime = DefaultControlledManagedExtensionRuntime(
            scraperRegistry = testRegistry,
            fallbackManager = testFallback
        )

        val ext1 = createExtension("ext1", priority = 20, key = "scraper1")
        val ext2 = createExtension("ext2", priority = 10, key = "scraper2")

        val result = runtime.search(listOf(ext1, ext2), SearchRequest("test"))

        assertTrue("Second scraper should have succeeded", result.isSuccess)
        assertTrue("First scraper was called", firstCalled)
        assertTrue("Second scraper was called after fallback", secondCalled)
        assertEquals("Found", result.getOrNull()?.items?.get(0)?.title)
    }

    @Test
    fun fallbackChain_noInternetStopsFallbackImmediately() = runBlocking {
        var firstCalled = false
        var secondCalled = false

        val scraper1 = FakeTestScraper("scraper1") {
            firstCalled = true
            Result.failure(ExtensionError.NoInternet())
        }

        val scraper2 = FakeTestScraper("scraper2") {
            secondCalled = true
            Result.success(SearchResult(items = emptyList(), page = 1))
        }

        val testRegistry = ScraperRegistry(
            mapOf<String, BaseSiteScraper>("scraper1" to scraper1, "scraper2" to scraper2)
        )
        val testFallback = FallbackManager(testRegistry)
        val runtime = DefaultControlledManagedExtensionRuntime(
            scraperRegistry = testRegistry,
            fallbackManager = testFallback
        )

        val ext1 = createExtension("ext1", priority = 20, key = "scraper1")
        val ext2 = createExtension("ext2", priority = 10, key = "scraper2")

        val result = runtime.search(listOf(ext1, ext2), SearchRequest("test"))

        assertTrue("Search should fail", result.isFailure)
        assertTrue("First scraper called", firstCalled)
        assertFalse("Second scraper must NOT be called when NO_INTERNET occurs", secondCalled)
    }

    @Test
    fun fallbackChain_allFailReturnsFailure() = runBlocking {
        val scraper1 = FakeTestScraper("scraper1") {
            Result.failure(Exception("Error 1"))
        }
        val scraper2 = FakeTestScraper("scraper2") {
            Result.failure(Exception("Error 2"))
        }

        val testRegistry = ScraperRegistry(mapOf<String, BaseSiteScraper>("scraper1" to scraper1, "scraper2" to scraper2))
        val runtime = DefaultControlledManagedExtensionRuntime(
            scraperRegistry = testRegistry,
            fallbackManager = FallbackManager(testRegistry)
        )

        val ext1 = createExtension("ext1", priority = 20, key = "scraper1")
        val ext2 = createExtension("ext2", priority = 10, key = "scraper2")

        val result = runtime.search(listOf(ext1, ext2), SearchRequest("test"))
        assertTrue("Search should fail when all candidates fail", result.isFailure)
    }
}
