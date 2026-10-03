package com.example.extension.managed.playback

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.PlaybackSource
import com.example.extension.managed.model.QualitySource
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.searchorder.ExtensionEligibilityFilter
import com.example.extension.managed.searchorder.SearchOrderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface PlaybackOrchestratorOutcome {
    data class Started(
        val firstSource: PlaybackSource,
        val session: PlaybackSession
    ) : PlaybackOrchestratorOutcome

    data class Failure(
        val message: String,
        val cause: Throwable? = null
    ) : PlaybackOrchestratorOutcome

    object Cancelled : PlaybackOrchestratorOutcome
}

/**
 * Orchestrates extension selection, early playback start, and bounded background quality discovery.
 *
 * Invariants:
 * 1. Consumes canonical Search Order from SearchOrderRepository.
 * 2. Filters extensions through ExtensionEligibilityFilter enforcing hard ContentType isolation.
 * 3. Preserves Admin Search Order sequence deterministically (no priority sorting, no shuffling).
 * 4. Starts playback as soon as the first valid playable source is discovered.
 * 5. Bounded background discovery continues only until QualitySufficiencyPolicy is satisfied.
 * 6. Early-stops and cancels pending operations once sufficient qualities are obtained.
 * 7. Background quality discovery failures never terminate or interrupt active playback.
 * 8. Never exposes internal extension names, server names, or technical errors to users.
 */
class PlaybackOrchestrator(
    private val searchOrderRepository: SearchOrderRepository,
    private val eligibilityFilter: ExtensionEligibilityFilter,
    private val extensionRegistry: ManagedExtensionRegistry,
    private val runtime: ControlledManagedExtensionRuntime,
    private val sufficiencyPolicy: QualitySufficiencyPolicy = QualitySufficiencyPolicy(),
    private val isOnlineChecker: () -> Boolean = { true },
    private val fallbackManager: com.example.extension.managed.runtime.FallbackManager = com.example.extension.managed.runtime.FallbackManager(com.example.extension.managed.registry.ScraperRegistry.INSTANCE)
) {

    suspend fun orchestratePlayback(
        session: PlaybackSession,
        onFirstPlayableSource: (PlaybackSource) -> Unit,
        onQualitiesDiscovered: (List<QualitySource>) -> Unit = {},
        onError: (String) -> Unit = {}
    ): PlaybackOrchestratorOutcome = withContext(Dispatchers.IO + session.sessionJob) {
        if (!session.isActive) {
            return@withContext PlaybackOrchestratorOutcome.Cancelled
        }

        if (!isOnlineChecker()) {
            val userMsg = "لا يوجد اتصال بالإنترنت"
            onError(userMsg)
            return@withContext PlaybackOrchestratorOutcome.Failure(userMsg)
        }

        val targetContentType = session.contentType

        // 1. Fetch canonical Search Order for requested content type
        val searchOrderIds = searchOrderRepository.getOrderForContentType(targetContentType)

        // 2. Fetch all registered managed extensions
        val allExtensions = extensionRegistry.getAllExtensions()

        // 3. Filter candidates through eligibility pipeline & hard content-type isolation
        val initialCandidates = if (searchOrderIds.isNotEmpty()) {
            eligibilityFilter.filterEligibleExtensions(
                orderedExtensionIds = searchOrderIds,
                availableExtensions = allExtensions,
                targetContentType = targetContentType,
                capability = ScraperCapability.SERVER_DISCOVERY,
                currentAppVersionCode = runtime.currentAppVersionCode,
                supportedRuntimeApiVersion = runtime.supportedRuntimeApiVersion
            )
        } else emptyList()

        val eligibleCandidates = if (initialCandidates.isNotEmpty()) {
            initialCandidates
        } else {
            // When search order is empty or unprovisioned, fall back to eligible active candidates
            fallbackManager.filterAndSortCandidates(
                candidates = allExtensions,
                contentType = targetContentType,
                capability = ScraperCapability.SERVER_DISCOVERY,
                appVersionCode = runtime.currentAppVersionCode,
                supportedRuntimeApi = runtime.supportedRuntimeApiVersion
            )
        }

        if (eligibleCandidates.isEmpty()) {
            val userMsg = "تعذر تشغيل هذا المحتوى"
            onError(userMsg)
            return@withContext PlaybackOrchestratorOutcome.Failure(
                "No eligible managed extensions found for $targetContentType with search order: $searchOrderIds"
            )
        }

        session.orderedCandidates.addAll(eligibleCandidates)

        val cleanTitle = session.title.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        val cleanTitleWithYear = if (session.year.isNotBlank() && session.year != "0") "$cleanTitle ${session.year}" else cleanTitle

        val cleanOriginalTitle = session.originalTitle?.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")?.replace(Regex("\\s+"), " ")?.trim()
        val cleanOriginalTitleWithYear = if (!cleanOriginalTitle.isNullOrBlank() && session.year.isNotBlank() && session.year != "0") "$cleanOriginalTitle ${session.year}" else cleanOriginalTitle

        var firstPlayableEmitted = false

        // 4. Iterate over eligible candidates in exact Admin order
        for (candidate in eligibleCandidates) {
            if (!session.isActive) break

            // Check if quality sufficiency was already achieved by previous candidates
            val currentQualities = session.discoveredQualities.map { it.label }
            if (firstPlayableEmitted && sufficiencyPolicy.isSufficient(currentQualities)) {
                // STOP DISCOVERY: Early stop satisfied
                break
            }

            try {
                // Step A: Search title on candidate extension
                val searchReq1 = SearchRequest(query = cleanTitle, contentType = targetContentType)
                var searchResult = runtime.search(listOf(candidate), searchReq1).getOrNull()?.items?.firstOrNull()

                if (searchResult == null) {
                    val searchReq2 = SearchRequest(query = cleanTitleWithYear, contentType = targetContentType)
                    searchResult = runtime.search(listOf(candidate), searchReq2).getOrNull()?.items?.firstOrNull()
                }

                // Fallback to originalTitle if not found
                if (searchResult == null && !cleanOriginalTitle.isNullOrBlank() && cleanOriginalTitle != cleanTitle) {
                    val searchReqOrig1 = SearchRequest(query = cleanOriginalTitle, contentType = targetContentType)
                    searchResult = runtime.search(listOf(candidate), searchReqOrig1).getOrNull()?.items?.firstOrNull()

                    if (searchResult == null && !cleanOriginalTitleWithYear.isNullOrBlank()) {
                        val searchReqOrig2 = SearchRequest(query = cleanOriginalTitleWithYear, contentType = targetContentType)
                        searchResult = runtime.search(listOf(candidate), searchReqOrig2).getOrNull()?.items?.firstOrNull()
                    }
                }

                if (searchResult == null) {
                    continue
                }

                if (!session.isActive) break

                // Step B: Resolve episode target URL if series/anime
                val targetUrl = if (session.isMovie) {
                    searchResult.url
                } else {
                    val epResult = runtime.getEpisodes(candidate, searchResult.url, session.season)
                    val epList = epResult.getOrNull() ?: emptyList()
                    epList.firstOrNull { it.episodeNumber == session.episode }?.url ?: searchResult.url
                }

                if (!session.isActive) break

                // Step C: Discover servers for the target page
                val discoveryRequest = ServerDiscoveryRequest(
                    targetUrl = targetUrl,
                    mediaTitle = session.title,
                    isMovie = session.isMovie,
                    season = session.season,
                    episode = session.episode,
                    contentType = targetContentType
                )

                val serverDiscoveryResult = runtime.discoverServers(listOf(candidate), discoveryRequest)
                val servers = serverDiscoveryResult.getOrNull()?.servers ?: emptyList()
                if (servers.isEmpty()) {
                    continue
                }

                if (!session.isActive) break

                // Step D: Extract First Playable Source
                var candidatePrimarySource: PlaybackSource? = null

                // Check for direct stream on servers first
                val directServer = servers.firstOrNull { it.isDirectStream }
                if (directServer != null) {
                    candidatePrimarySource = PlaybackSource(
                        streamUrl = directServer.link,
                        mimeType = if (directServer.link.contains(".m3u8")) "application/x-mpegURL" else "video/mp4"
                    )
                } else {
                    // Try top candidate servers for extraction
                    for (srv in servers.take(3)) {
                        val extractResult = runtime.extractStream(
                            listOf(candidate),
                            ExtractionRequest(serverItem = srv, mediaTitle = session.title)
                        )
                        if (extractResult.isSuccess) {
                            val src = extractResult.getOrThrow().playbackSource
                            if (src != null && !src.streamUrl.isNullOrBlank()) {
                                candidatePrimarySource = src
                                break
                            }
                        }
                    }
                }

                // If candidate provided a playable source, emit it immediately if not already emitted
                if (candidatePrimarySource != null && !candidatePrimarySource.streamUrl.isNullOrBlank()) {
                    if (!firstPlayableEmitted) {
                        firstPlayableEmitted = true
                        session.discoveredSources.add(candidatePrimarySource)
                        session.firstPlayableDeferred.complete(candidatePrimarySource)
                        onFirstPlayableSource(candidatePrimarySource)
                    }

                    // Extract and record qualities from primary source
                    val newQualities = mutableListOf<QualitySource>()
                    if (candidatePrimarySource.qualities.isNotEmpty()) {
                        newQualities.addAll(candidatePrimarySource.qualities)
                    } else if (candidatePrimarySource.variants.isNotEmpty()) {
                        newQualities.addAll(candidatePrimarySource.variants.map { it.toQualitySource() })
                    } else {
                        newQualities.add(QualitySource(label = "Auto", url = candidatePrimarySource.streamUrl))
                    }

                    for (q in newQualities) {
                        if (session.discoveredQualities.none { it.label.equals(q.label, ignoreCase = true) }) {
                            session.discoveredQualities.add(q)
                        }
                    }
                    onQualitiesDiscovered(session.discoveredQualities.toList())

                    // Check if sufficient quality set achieved
                    if (sufficiencyPolicy.isSufficient(session.discoveredQualities.map { it.label })) {
                        // Sufficiency met! Stop discovery immediately
                        break
                    }

                    // Step E: Bounded background extraction for remaining 1-2 servers to fill missing qualities
                    val remainingServers = servers.drop(1).take(2)
                    for (server in remainingServers) {
                        if (!session.isActive) break
                        if (sufficiencyPolicy.isSufficient(session.discoveredQualities.map { it.label })) break

                        try {
                            val nextExtract = runtime.extractStream(
                                listOf(candidate),
                                ExtractionRequest(serverItem = server, mediaTitle = session.title)
                            )
                            val secondarySource = nextExtract.getOrNull()?.playbackSource
                            if (secondarySource != null) {
                                val sQualities = secondarySource.qualities.ifEmpty {
                                    secondarySource.variants.map { it.toQualitySource() }
                                }
                                for (q in sQualities) {
                                    if (session.discoveredQualities.none { it.label.equals(q.label, ignoreCase = true) }) {
                                        session.discoveredQualities.add(q)
                                    }
                                }
                                onQualitiesDiscovered(session.discoveredQualities.toList())
                            }
                        } catch (_: Exception) {
                            // Secondary background failure never stops playback
                        }
                    }
                }
            } catch (_: Exception) {
                // Background candidate failure is swallowed if playback already started
                if (!firstPlayableEmitted) {
                    // Try next candidate
                }
            }
        }

        return@withContext if (firstPlayableEmitted && session.discoveredSources.isNotEmpty()) {
            PlaybackOrchestratorOutcome.Started(session.discoveredSources.first(), session)
        } else {
            val userMsg = "تعذر تشغيل هذا المحتوى"
            onError(userMsg)
            PlaybackOrchestratorOutcome.Failure("All eligible candidates failed to produce a playable source")
        }
    }
}
