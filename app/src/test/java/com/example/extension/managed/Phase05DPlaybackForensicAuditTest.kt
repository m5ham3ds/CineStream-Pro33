package com.example.extension.managed

import com.example.extension.managed.model.*
import com.example.extension.managed.playback.PlaybackSession
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.scraper.EgyDeadScraper
import com.example.extension.managed.scraper.QfilmScraper
import com.example.extension.managed.scraper.WitanimeScraper
import com.example.extension.managed.scraper.Anime4UpScraper
import com.example.extension.managed.scraper.AnimeBlkomScraper
import com.example.extension.managed.web.StaticMediaExtractor
import com.example.extension.orchestrator.ManagedMediaOrchestrator
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.UUID

class Phase05DPlaybackForensicAuditTest {

    private val traceId = "audit_" + UUID.randomUUID().toString().take(8)

    private fun log(stage: String, message: String) {
        println("[PLAY][$traceId][$stage] $message")
    }

    @Test
    fun forensicAuditLiveE2E() = runBlocking {
        println("\n=======================================================")
        println("=== PHASE 05D FORENSIC AUDIT: REAL E2E TRACE ($traceId) ===")
        println("=======================================================\n")

        // ---------------------------------------------------------
        // 1. EXTENSION CATALOG & SCRAPER REGISTRY AUDIT
        // ---------------------------------------------------------
        log("REGISTRY", "Checking ScraperRegistry mappings...")
        val allScrapers = ScraperRegistry.INSTANCE.allScrapers()
        log("REGISTRY", "Available scrapers count: ${allScrapers.size}")
        for (s in allScrapers) {
            log("REGISTRY", "Registered scraper: key='${s.scraperKey}', version=${s.implementationVersion}, caps=${s.supportedCapabilities}, types=${s.supportedContentTypes}")
        }

        // ---------------------------------------------------------
        // 2. QFILM FORENSIC AUDIT (MOVIE)
        // ---------------------------------------------------------
        val qfilmExt = ManagedExtension(
            id = "qfilm",
            name = "كيو فيلم",
            baseUrl = "https://a.qfilm.tv",
            scraperKey = "qfilm",
            status = ExtensionLifecycleStatus.ACTIVE,
            contentTypes = setOf(ContentType.MOVIE, ContentType.ANIME)
        )
        val qfilmScraper = QfilmScraper()

        log("QFILM", "Starting live forensic audit for Qfilm with query='محمود التاني'...")
        val qSearchRes = qfilmScraper.search(qfilmExt, SearchRequest(query = "محمود التاني", contentType = ContentType.MOVIE))
        if (qSearchRes.isSuccess) {
            val sData = qSearchRes.getOrThrow()
            log("QFILM_SEARCH", "PASS - Found ${sData.items.size} search results")
            val topItem = sData.items.firstOrNull()
            if (topItem != null) {
                log("QFILM_SEARCH", "Top item: title='${topItem.title}', url='${topItem.url}'")

                // Server discovery
                val session = com.example.extension.managed.runtime.ExtractionSession(
                    extensionId = qfilmExt.id,
                    scraperKey = qfilmExt.scraperKey,
                    targetPageUrl = topItem.url
                )
                val discoveryReq = ServerDiscoveryRequest(
                    targetUrl = topItem.url,
                    mediaTitle = topItem.title,
                    isMovie = true
                )
                log("QFILM_DISCOVERY", "Discovering servers from ${topItem.url}...")
                val discRes = qfilmScraper.discoverServers(qfilmExt, session, discoveryReq, webEngine = null)
                if (discRes.isSuccess) {
                    val dData = discRes.getOrThrow()
                    log("QFILM_DISCOVERY", "PASS - Discovered ${dData.servers.size} servers")
                    dData.servers.forEachIndexed { i, srv ->
                        log("QFILM_SERVER", "Server #$i: name='${srv.name}', link='${srv.link}', isDirect=${srv.isDirectStream}")
                    }

                    // Attempt extraction on discovered servers
                    for (srv in dData.servers.take(3)) {
                        log("QFILM_EXTRACTION", "Attempting extraction on server '${srv.name}' (${srv.link})...")
                        val extReq = ExtractionRequest(serverItem = srv, mediaTitle = topItem.title)
                        val extRes = qfilmScraper.extractStream(qfilmExt, session, extReq, webEngine = null)
                        if (extRes.isSuccess) {
                            val stream = extRes.getOrThrow().playbackSource?.streamUrl
                            log("QFILM_EXTRACTION", "PASS - Extracted stream: $stream")
                        } else {
                            log("QFILM_EXTRACTION", "FAIL - Error: ${extRes.exceptionOrNull()?.message}")
                        }
                    }
                } else {
                    log("QFILM_DISCOVERY", "FAIL - Error: ${discRes.exceptionOrNull()?.message}")
                }
            }
        } else {
            log("QFILM_SEARCH", "FAIL - Error: ${qSearchRes.exceptionOrNull()?.message}")
        }

        // ---------------------------------------------------------
        // 3. EGYDEAD FORENSIC AUDIT (MOVIE)
        // ---------------------------------------------------------
        val egydeadExt = ManagedExtension(
            id = "egydead",
            name = "EgyDead (Managed)",
            baseUrl = "https://tv10.egydead.live",
            scraperKey = "egydead",
            status = ExtensionLifecycleStatus.ACTIVE,
            contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES, ContentType.ANIME)
        )
        val egydeadScraper = EgyDeadScraper()

        log("EGYDEAD", "Starting live forensic audit for EgyDead with query='Inception'...")
        val egySearchRes = egydeadScraper.search(egydeadExt, SearchRequest(query = "Inception", contentType = ContentType.MOVIE))
        if (egySearchRes.isSuccess) {
            val sData = egySearchRes.getOrThrow()
            log("EGYDEAD_SEARCH", "PASS - Found ${sData.items.size} search results")
            val topItem = sData.items.firstOrNull()
            if (topItem != null) {
                log("EGYDEAD_SEARCH", "Top item: title='${topItem.title}', url='${topItem.url}'")

                // Server discovery
                val session = com.example.extension.managed.runtime.ExtractionSession(
                    extensionId = egydeadExt.id,
                    scraperKey = egydeadExt.scraperKey,
                    targetPageUrl = topItem.url
                )
                val discoveryReq = ServerDiscoveryRequest(
                    targetUrl = topItem.url,
                    mediaTitle = topItem.title,
                    isMovie = true
                )
                log("EGYDEAD_DISCOVERY", "Discovering servers from ${topItem.url}...")
                val discRes = egydeadScraper.discoverServers(egydeadExt, session, discoveryReq, webEngine = null)
                if (discRes.isSuccess) {
                    val dData = discRes.getOrThrow()
                    log("EGYDEAD_DISCOVERY", "PASS - Discovered ${dData.servers.size} servers")
                    dData.servers.forEachIndexed { i, srv ->
                        log("EGYDEAD_SERVER", "Server #$i: name='${srv.name}', link='${srv.link}', isDirect=${srv.isDirectStream}")
                    }

                    // Attempt extraction on discovered servers
                    for (srv in dData.servers.take(3)) {
                        log("EGYDEAD_EXTRACTION", "Attempting extraction on server '${srv.name}' (${srv.link})...")
                        val extReq = ExtractionRequest(serverItem = srv, mediaTitle = topItem.title)
                        val extRes = egydeadScraper.extractStream(egydeadExt, session, extReq, webEngine = null)
                        if (extRes.isSuccess) {
                            val stream = extRes.getOrThrow().playbackSource?.streamUrl
                            log("EGYDEAD_EXTRACTION", "PASS - Extracted stream: $stream")
                        } else {
                            log("EGYDEAD_EXTRACTION", "FAIL - Scraper error: ${extRes.exceptionOrNull()?.message}")
                            // Probe with StaticMediaExtractor directly to see HTML / packer content
                            val staticStream = StaticMediaExtractor.extract(srv.link, referer = topItem.url)
                            log("EGYDEAD_STATIC_PROBE", "StaticMediaExtractor on '${srv.name}' (${srv.link}) -> result: $staticStream")
                        }
                    }
                } else {
                    log("EGYDEAD_DISCOVERY", "FAIL - Error: ${discRes.exceptionOrNull()?.message}")
                }
            }
        } else {
            log("EGYDEAD_SEARCH", "FAIL - Error: ${egySearchRes.exceptionOrNull()?.message}")
        }

        // ---------------------------------------------------------
        // 4. ANIME SITES FORENSIC AUDIT (WitAnime, Anime4Up, AnimeBlkom)
        // ---------------------------------------------------------
        val witanimeExt = ManagedMediaOrchestrator.DEFAULT_WITANIME_MANAGED_EXTENSION
        val anime4upExt = ManagedMediaOrchestrator.DEFAULT_ANIME4UP_MANAGED_EXTENSION
        val animeblkomExt = ManagedMediaOrchestrator.DEFAULT_ANIMEBLKOM_MANAGED_EXTENSION

        val witanimeScraper = WitanimeScraper()
        val anime4upScraper = Anime4UpScraper()
        val animeblkomScraper = AnimeBlkomScraper()

        val animeQuery = "Naruto"
        log("WITANIME", "Testing WitAnime with '$animeQuery'...")
        val witRes = witanimeScraper.search(witanimeExt, SearchRequest(query = animeQuery, contentType = ContentType.ANIME))
        log("WITANIME", "Result: success=${witRes.isSuccess}, items=${witRes.getOrNull()?.items?.size}, error=${witRes.exceptionOrNull()?.message}")

        log("ANIME4UP", "Testing Anime4Up with '$animeQuery'...")
        val a4uRes = anime4upScraper.search(anime4upExt, SearchRequest(query = animeQuery, contentType = ContentType.ANIME))
        log("ANIME4UP", "Result: success=${a4uRes.isSuccess}, items=${a4uRes.getOrNull()?.items?.size}, error=${a4uRes.exceptionOrNull()?.message}")

        log("ANIMEBLKOM", "Testing AnimeBlkom with '$animeQuery'...")
        val blkRes = animeblkomScraper.search(animeblkomExt, SearchRequest(query = animeQuery, contentType = ContentType.ANIME))
        log("ANIMEBLKOM", "Result: success=${blkRes.isSuccess}, items=${blkRes.getOrNull()?.items?.size}, error=${blkRes.exceptionOrNull()?.message}")

        // ---------------------------------------------------------
        // 5. MANAGED MEDIA ORCHESTRATOR & PLAYBACK ORCHESTRATOR E2E
        // ---------------------------------------------------------
        log("ORCHESTRATOR", "Testing ManagedMediaOrchestrator.discoverServers and orchestratePlayback...")
        val testRegistry = ManagedExtensionRegistry.INSTANCE
        testRegistry.setExtensions(
            listOf(
                ManagedMediaOrchestrator.DEFAULT_QFILM_MANAGED_EXTENSION,
                ManagedMediaOrchestrator.DEFAULT_EGYDEAD_MANAGED_EXTENSION,
                ManagedMediaOrchestrator.DEFAULT_WITANIME_MANAGED_EXTENSION,
                ManagedMediaOrchestrator.DEFAULT_ANIME4UP_MANAGED_EXTENSION,
                ManagedMediaOrchestrator.DEFAULT_ANIMEBLKOM_MANAGED_EXTENSION
            )
        )
        val mockRepo = object : com.example.extension.managed.repository.ManagedExtensionRepository {
            override suspend fun getExtensions(forceRefresh: Boolean): Result<List<ManagedExtension>> {
                return Result.success(testRegistry.getAllExtensions())
            }
            override suspend fun getExtensionById(id: String, forceRefresh: Boolean): Result<ManagedExtension> {
                val found = testRegistry.getExtension(id)
                return if (found != null) Result.success(found) else Result.failure(Exception("Unknown extension $id"))
            }
        }
        val testRuntime = com.example.extension.managed.runtime.DefaultControlledManagedExtensionRuntime(
            managedExtensionRegistry = testRegistry
        )
        val orchestrator = ManagedMediaOrchestrator(
            repository = mockRepo,
            registry = testRegistry,
            runtime = testRuntime,
            userPreferences = com.example.extension.managed.repository.InMemoryExtensionUserPreferences(),
            isOnlineChecker = { true }
        )

        // Test 5A: discoverServers for Qfilm match
        log("ORCHESTRATOR_DISCOVER", "discoverServers for 'محمود التاني'...")
        val discOutcomeQ = orchestrator.discoverServers(
            title = "محمود التاني",
            year = "2026",
            isMovie = true,
            contentType = ContentType.MOVIE
        )
        log("ORCHESTRATOR_DISCOVER", "Outcome type: ${discOutcomeQ.javaClass.simpleName}")
        if (discOutcomeQ is com.example.extension.orchestrator.ManagedDiscoveryOutcome.Success) {
            log("ORCHESTRATOR_DISCOVER", "PASS - Website: ${discOutcomeQ.website}, Servers count: ${discOutcomeQ.servers.size}")
            discOutcomeQ.servers.forEach { s ->
                log("ORCHESTRATOR_DISCOVER", "Server: '${s.name}' -> ${s.link}")
            }
        } else if (discOutcomeQ is com.example.extension.orchestrator.ManagedDiscoveryOutcome.RecoverableFailure) {
            log("ORCHESTRATOR_DISCOVER", "FAIL - Recoverable: ${discOutcomeQ.error.message}")
        } else if (discOutcomeQ is com.example.extension.orchestrator.ManagedDiscoveryOutcome.SecurityFailure) {
            log("ORCHESTRATOR_DISCOVER", "FAIL - Security: ${discOutcomeQ.error.message}")
        }

        // Test 5B: orchestratePlayback for Qfilm match
        log("ORCHESTRATOR_PLAYBACK", "orchestratePlayback for 'محمود التاني'...")
        val session = orchestrator.startPlaybackSession(
            mediaId = "test_qfilm_1",
            title = "محمود التاني",
            year = "2026",
            isMovie = true,
            contentType = ContentType.MOVIE
        )
        var firstPlayableUrl: String? = null
        val playbackOutcome = orchestrator.orchestratePlayback(
            session = session,
            onFirstPlayableSource = { src ->
                firstPlayableUrl = src.streamUrl
                log("ORCHESTRATOR_PLAYBACK", "onFirstPlayableSource received! url='${src.streamUrl}', mime='${src.mimeType}', headers=${src.headers.keys}")
            },
            onQualitiesDiscovered = { qList ->
                log("ORCHESTRATOR_PLAYBACK", "onQualitiesDiscovered: count=${qList.size} (${qList.map { it.label }})")
            },
            onError = { err ->
                log("ORCHESTRATOR_PLAYBACK", "onError: $err")
            }
        )
        log("ORCHESTRATOR_PLAYBACK", "Playback outcome: ${playbackOutcome.javaClass.simpleName}")

        // Test 5C: discoverServers for Inception (EgyDead candidate)
        log("ORCHESTRATOR_DISCOVER", "discoverServers for 'Inception'...")
        val discOutcomeEgy = orchestrator.discoverServers(
            title = "Inception",
            year = "2010",
            isMovie = true,
            contentType = ContentType.MOVIE
        )
        log("ORCHESTRATOR_DISCOVER", "Inception Outcome type: ${discOutcomeEgy.javaClass.simpleName}")
        if (discOutcomeEgy is com.example.extension.orchestrator.ManagedDiscoveryOutcome.Success) {
            log("ORCHESTRATOR_DISCOVER", "PASS - Website: ${discOutcomeEgy.website}, Servers: ${discOutcomeEgy.servers.size}")
        } else if (discOutcomeEgy is com.example.extension.orchestrator.ManagedDiscoveryOutcome.RecoverableFailure) {
            log("ORCHESTRATOR_DISCOVER", "FAIL - Recoverable: ${discOutcomeEgy.error.message}")
        }

        // Test 5D: orchestratePlayback for Inception
        log("ORCHESTRATOR_PLAYBACK", "orchestratePlayback for 'Inception'...")
        val sessionEgy = orchestrator.startPlaybackSession(
            mediaId = "27205",
            title = "Inception",
            year = "2010",
            isMovie = true,
            contentType = ContentType.MOVIE
        )
        var firstPlayableEgyUrl: String? = null
        val playbackOutcomeEgy = orchestrator.orchestratePlayback(
            session = sessionEgy,
            onFirstPlayableSource = { src ->
                firstPlayableEgyUrl = src.streamUrl
                log("ORCHESTRATOR_PLAYBACK", "Inception onFirstPlayableSource: url='${src.streamUrl}'")
            },
            onQualitiesDiscovered = { qList ->
                log("ORCHESTRATOR_PLAYBACK", "Inception qualities: ${qList.map { it.label }}")
            },
            onError = { err ->
                log("ORCHESTRATOR_PLAYBACK", "Inception onError: $err")
            }
        )
        log("ORCHESTRATOR_PLAYBACK", "Inception Playback outcome: ${playbackOutcomeEgy.javaClass.simpleName}")

        println("\n=======================================================")
        println("=== PHASE 05D FORENSIC AUDIT COMPLETED ===")
        println("=======================================================\n")
    }
}
