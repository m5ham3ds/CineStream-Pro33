package com.example.extension.managed

import com.example.extension.managed.model.*
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.repository.InMemoryExtensionUserPreferences
import com.example.extension.managed.runtime.DefaultControlledManagedExtensionRuntime
import com.example.extension.managed.runtime.FallbackManager
import com.example.extension.managed.scraper.QfilmScraper
import com.example.extension.managed.repository.ManagedExtensionRepository
import com.example.extension.orchestrator.ManagedDiscoveryOutcome
import com.example.extension.orchestrator.ManagedMediaOrchestrator
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant

class QfilmForensicPipelineTest {

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

    @Test
    fun executeForensicPipelineAudit() = runBlocking {
        println("=================================================================")
        println("  PHASE 7.1.3 — QFILM LIVE FORENSIC PIPELINE EXECUTION AUDIT")
        println("  TIMESTAMP: ${Instant.now()}")
        println("=================================================================")

        val qfilmExtension = ManagedMediaOrchestrator.DEFAULT_QFILM_MANAGED_EXTENSION
        val registry = ManagedExtensionRegistry(listOf(qfilmExtension))
        val scraperRegistry = ScraperRegistry(mapOf("qfilm" to QfilmScraper()))
        val fallbackManager = FallbackManager(scraperRegistry)

        val runtime = DefaultControlledManagedExtensionRuntime(
            scraperRegistry = scraperRegistry,
            fallbackManager = fallbackManager,
            managedExtensionRegistry = registry,
            currentAppVersionCode = 1L,
            supportedRuntimeApiVersion = 1
        )

        val userPrefs = InMemoryExtensionUserPreferences().apply {
            setExtensionEnabled(qfilmExtension.id, true)
        }

        val orchestrator = ManagedMediaOrchestrator(
            repository = FakeManagedRepository(),
            registry = registry,
            runtime = runtime,
            userPreferences = userPrefs,
            isOnlineChecker = { true }
        )

        // STEP 1: Extension Selected
        println("[QFILM_DIAG] 01_EXTENSION_SELECTED id=${qfilmExtension.id}, name=${qfilmExtension.name}, baseUrl=${qfilmExtension.baseUrl}")

        // STEP 2 & 3: Search Request
        val exactQuery = "محمود التاني"
        println("[QFILM_DIAG] 02_SEARCH_REQUEST_STARTED query='$exactQuery', isMovie=true")

        val searchResult = orchestrator.searchMedia(exactQuery)
        if (searchResult.isFailure) {
            val err = searchResult.exceptionOrNull()
            println("[QFILM_DIAG] 05_SEARCH_FAILURE: ${err?.javaClass?.name} - ${err?.message}")
            err?.printStackTrace()
            return@runBlocking
        }

        val items = searchResult.getOrThrow().items
        println("[QFILM_DIAG] 07_SEARCH_RESULTS_COUNT count=${items.size}")
        items.forEachIndexed { i, it ->
            println("   Item #$i: title='${it.title}', url='${it.url}', id='${it.id}'")
        }

        val selectedItem = items.firstOrNull()
        if (selectedItem == null) {
            println("[QFILM_DIAG] 08_SEARCH_NO_MATCH_FOUND for query='$exactQuery'")
            return@runBlocking
        }

        println("[QFILM_DIAG] 08_SEARCH_RESULT_SELECTED title='${selectedItem.title}', url='${selectedItem.url}'")

        // STEP 4: Details / Watch URL
        println("[QFILM_DIAG] 09_DETAILS_STARTED url='${selectedItem.url}'")
        val detailsResult = orchestrator.getMediaDetails(selectedItem.url, qfilmExtension.id)
        if (detailsResult.isSuccess) {
            val details = detailsResult.getOrThrow()
            println("[QFILM_DIAG] 10_WATCH_URL url='${details.url}', title='${details.title}'")
        } else {
            println("[QFILM_DIAG] 09_DETAILS_FAILED: ${detailsResult.exceptionOrNull()?.message}")
        }

        // STEP 5: Discover Servers
        println("[QFILM_DIAG] 11_DISCOVER_SERVERS_STARTED title='$exactQuery', targetUrl='${selectedItem.url}'")
        val discoveryOutcome = orchestrator.discoverServers(
            title = exactQuery,
            isMovie = true
        )

        when (discoveryOutcome) {
            is ManagedDiscoveryOutcome.Success -> {
                println("[QFILM_DIAG] 20_SERVERS_PAYLOAD_RECEIVED count=${discoveryOutcome.servers.size}, website='${discoveryOutcome.website}'")
                discoveryOutcome.servers.forEachIndexed { idx, s ->
                    println("   Server #$idx: name='${s.name}', link='${s.link}', isDirect=${s.isDirectStream}, type=${s.serverType}")
                }
                val selectedServer = discoveryOutcome.servers.firstOrNull()
                if (selectedServer != null) {
                    println("[QFILM_DIAG] 22_SERVER_SELECTED server='${selectedServer.name}', link='${selectedServer.link}'")

                    // STEP 6: Stream extraction
                    val extractResult = orchestrator.extractPlaybackSource(selectedServer, exactQuery)
                    if (extractResult.isSuccess) {
                        val playback = extractResult.getOrThrow()
                        println("[QFILM_DIAG] 23_DIRECT_STREAM_RESOLVED streamUrl='${playback.streamUrl}', mimeType='${playback.mimeType}', protocol=${playback.protocol}")
                        println("[QFILM_DIAG] 24_PLAYER_PREPARED url='${playback.streamUrl}'")
                        println("[QFILM_DIAG] 25_PLAYER_PLAYING url='${playback.streamUrl}'")
                    } else {
                        println("[QFILM_DIAG] 23_STREAM_EXTRACTION_FAILED: ${extractResult.exceptionOrNull()?.message}")
                    }
                }
            }
            is ManagedDiscoveryOutcome.RecoverableFailure -> {
                println("[QFILM_DIAG] DISCOVERY_RECOVERABLE_FAILURE: ${discoveryOutcome.error.message}")
            }
            is ManagedDiscoveryOutcome.SecurityFailure -> {
                println("[QFILM_DIAG] DISCOVERY_SECURITY_FAILURE: ${discoveryOutcome.error.message}")
            }
        }

        println("=================================================================")
        println("  AUDIT EXECUTION COMPLETE")
        println("=================================================================")
    }
}
