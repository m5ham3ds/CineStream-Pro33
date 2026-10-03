package com.example.ui.screens.player

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import com.example.data.repository.TmdbMediaRepositoryImpl
import com.example.domain.models.Episode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

data class PlayerUiState(
    val isLoading: Boolean = true,
    val mediaId: String = "",
    val isMovie: Boolean = true,
    val isAnime: Boolean = false,
    val contentType: com.example.extension.managed.model.ContentType = if (isMovie) com.example.extension.managed.model.ContentType.MOVIE else com.example.extension.managed.model.ContentType.SERIES,
    val isOffline: Boolean = false,
    val title: String = "",
    val originalTitle: String = "",
    val releaseYear: String = "",

    // Website (Provider)
    val availableWebsites: List<String> = emptyList(),
    val currentWebsite: String = "",
    val fallbackWebsites: List<String> = emptyList(),

    // Server
    val availableServers: List<String> = emptyList(),
    val currentServer: String = "",
    val availableServerLinks: Map<String, String> = emptyMap(),
    val availableServerIds: Map<String, String> = emptyMap(),
    val serverIdToChange: String? = null,

    // Quality
    val availableQualities: List<String> = listOf("Auto"),
    val currentQuality: String = "Auto",
    val extractedQualitiesInfo: List<com.example.utils.M3U8Parser.QualityInfo> = emptyList(),

    // Episodes
    val episodes: List<Episode> = emptyList(),
    val currentEpisodeId: String = "",
    val currentSeasonNumber: Int = 1,
    val currentEpisodeNumber: Int = 1,
    val visibleEpisodesCount: Int = 10,

    // Extracted URL
    val currentVideoUrl: String? = null,
    val extractionUrl: String? = null, // The URL to feed to the hidden WebView
    val pendingSeekPosition: Long? = null,
    val currentPositionMillis: Long = 0L,
    val durationMillis: Long = 0L
)

class PlayerViewModel : ViewModel() {
    private val tmdbRepo = TmdbMediaRepositoryImpl()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var extractionTimeoutJob: kotlinx.coroutines.Job? = null
    private var currentPlaybackSession: com.example.extension.managed.playback.PlaybackSession? = null
    private var lastMediaId: String? = null
    private var lastIsMovie: Boolean = true
    private var lastTitle: String? = null
    private var lastDirectUrl: String? = null
    private var lastTargetServer: String? = null
    private var lastWebsite: String? = null
    private var lastEpisodeId: String? = null
    private var lastContentType: String? = null

    fun stopPlayback() {
        currentPlaybackSession?.cancel()
        currentPlaybackSession = null
        extractionTimeoutJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        stopPlayback()
    }

    init {
        viewModelScope.launch {
            com.example.ui.screens.player.ServerStateStore.extractedQualitiesFlow.collect { newQualities ->
                if (newQualities.isNotEmpty()) {
                    val canonicalList = com.example.ui.screens.player.filterCanonicalQualities(newQualities)
                    val standardList = listOf("1080p", "720p", "480p", "360p", "240p", "144p")
                    val explicitQualities = canonicalList
                        .mapNotNull { com.example.ui.screens.player.normalizeCanonicalQualityName(it.name) }
                        .filter { it in standardList }
                        .distinct()
                        .sortedWith(Comparator { a, b -> standardList.indexOf(a).compareTo(standardList.indexOf(b)) })
                    val allQualitiesList = listOf("Auto") + explicitQualities
                    val currentQ = com.example.ui.screens.player.normalizeCanonicalQualityName(_uiState.value.currentQuality) ?: "Auto"
                    _uiState.value = _uiState.value.copy(
                        extractedQualitiesInfo = canonicalList,
                        availableQualities = allQualitiesList,
                        currentQuality = if (currentQ in allQualitiesList) currentQ else "Auto"
                    )
                }
            }
        }
        viewModelScope.launch {
            com.example.ui.screens.player.ServerStateStore.serversFlow.collect { newServers ->
                if (newServers.isNotEmpty()) {
                    val currentS = _uiState.value.currentServer
                    val cached = com.example.ui.screens.player.ServerStateStore.getCachedData(_uiState.value.mediaId)
                    _uiState.value = _uiState.value.copy(
                        availableServers = newServers,
                        availableServerLinks = cached?.serverLinks ?: _uiState.value.availableServerLinks,
                        availableServerIds = cached?.serverIds ?: _uiState.value.availableServerIds,
                        currentServer = if (currentS in newServers) currentS else if (currentS.isBlank()) newServers.first() else currentS
                    )
                }
            }
        }
    }

    fun updatePlaybackPosition(currentPos: Long, totalDuration: Long) {
        _uiState.value = _uiState.value.copy(
            currentPositionMillis = currentPos,
            durationMillis = totalDuration
        )
    }

    fun initialize(
        mediaId: String,
        isMovie: Boolean,
        initialTitle: String,
        directUrl: String? = null,
        targetServer: String? = null,
        website: String? = null,
        episodeId: String? = null,
        contentType: String? = null
    ) {
        lastMediaId = mediaId
        lastIsMovie = isMovie
        lastTitle = initialTitle
        lastDirectUrl = directUrl
        lastTargetServer = targetServer
        lastWebsite = website
        lastEpisodeId = episodeId
        lastContentType = contentType

        val hasArabic = initialTitle.any { it in '؀'..'ۿ' }
        val isAnime = initialTitle.contains("anime", ignoreCase = true) ||
            initialTitle.contains("أنمي", ignoreCase = true) ||
            contentType.equals("anime", ignoreCase = true)

        val resolvedContentType = when {
            !contentType.isNullOrBlank() -> com.example.extension.managed.model.ContentType.from(contentType, isMovieFallback = isMovie)
            isMovie -> com.example.extension.managed.model.ContentType.MOVIE
            isAnime -> com.example.extension.managed.model.ContentType.ANIME
            else -> com.example.extension.managed.model.ContentType.SERIES
        }
        
        val activeManagedNames = com.example.extension.orchestrator.ManagedMediaOrchestrator.getActiveExtensionNames()
        val defaultActive = if (activeManagedNames.isNotEmpty()) activeManagedNames else listOf("EgyDead (Managed)")
        val bestWebsite = website ?: defaultActive.firstOrNull() ?: ""
        val remainingFallbacks = defaultActive.filter { it != bestWebsite }

        val isDownloaded = directUrl != null && (
            directUrl.startsWith("local_offline_file") ||
            directUrl.startsWith("file://") ||
            directUrl.startsWith("content://")
        )
        val ctx = com.example.MyApplication.appContext
        val localFile = if (isMovie) {
            com.example.utils.MediaStorageUtils.findMediaFile(ctx, mediaId)
        } else {
            com.example.utils.MediaStorageUtils.findMediaFile(ctx, mediaId)
                ?: com.example.utils.MediaStorageUtils.findMediaFile(ctx, "${mediaId}_1")
        }
        val isOffline = !com.example.utils.NetworkUtils.isInternetAvailable(ctx)
        val isOfflineOrDownloaded = isDownloaded || (localFile != null && localFile.exists()) || isOffline

        if (isOfflineOrDownloaded) {
            var localVideoUrl: String? = null
            if (directUrl != null) {
                if (directUrl.startsWith("local_offline_file://")) {
                    val fileId = directUrl.removePrefix("local_offline_file://")
                    val file = com.example.utils.MediaStorageUtils.findMediaFile(ctx, fileId)
                    if (file != null && file.exists()) {
                        localVideoUrl = android.net.Uri.fromFile(file).toString()
                    }
                } else if (directUrl.startsWith("file://") || directUrl.startsWith("content://") || directUrl.contains(".mp4") || directUrl.contains(".mkv")) {
                    localVideoUrl = directUrl
                }
            } else if (localFile != null && localFile.exists()) {
                localVideoUrl = android.net.Uri.fromFile(localFile).toString()
            }

            _uiState.value = _uiState.value.copy(
                mediaId = mediaId,
                isMovie = isMovie,
                isAnime = isAnime,
                isOffline = true,
                title = initialTitle,
                availableWebsites = emptyList(),
                currentWebsite = "",
                fallbackWebsites = emptyList(),
                availableServers = emptyList(),
                currentServer = "",
                availableServerLinks = emptyMap(),
                availableServerIds = emptyMap(),
                serverIdToChange = null,
                availableQualities = emptyList(),
                currentQuality = "",
                extractedQualitiesInfo = emptyList(),
                currentVideoUrl = localVideoUrl,
                extractionUrl = null,
                isLoading = false
            )

            if (!isMovie) {
                viewModelScope.launch {
                    try {
                        val downloadRepo = com.example.data.repository.DownloadRepository(ctx)
                        val allDownloads = downloadRepo.getAllItemsSync()
                        val seriesDownloads = allDownloads.filter {
                            (it.mediaId == mediaId || it.id.startsWith("${mediaId}_")) && it.isCompleted
                        }
                        if (seriesDownloads.isNotEmpty()) {
                            val offlineEpisodes = seriesDownloads.mapIndexed { index, dl ->
                                val epNum = dl.id.substringAfterLast("_").toIntOrNull() ?: (index + 1)
                                Episode(
                                    id = dl.id,
                                    episodeNumber = epNum,
                                    title = dl.title,
                                    overview = "",
                                    thumbnailUrl = dl.posterUrl,
                                    duration = 0,
                                    rating = 0.0
                                )
                            }
                            _uiState.value = _uiState.value.copy(
                                episodes = offlineEpisodes,
                                currentEpisodeId = offlineEpisodes.firstOrNull()?.id ?: mediaId
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            return
        }

        val serverIds = com.example.ui.screens.player.ServerStateStore.extractedServerIds
        val effectiveEpId = episodeId?.takeIf { it.isNotBlank() } ?: com.example.utils.LastPlaybackStore.getLastEpisodeId(ctx, mediaId)
        val fileId = if (effectiveEpId.isNullOrBlank()) mediaId else "${mediaId}_$effectiveEpId"
        val lastPlayback = com.example.utils.LastPlaybackStore.getLastPlayback(ctx, mediaId, effectiveEpId)
        val savedQuality = lastPlayback?.quality?.ifBlank { "Auto" } ?: "Auto"
        var savedPosition = lastPlayback?.positionMillis ?: 0L
        if (savedPosition <= 0L) {
            savedPosition = com.example.ui.screens.player.PlaybackSyncStore.getPosition(fileId)
        }
        if (savedPosition <= 0L && fileId.contains("_")) {
            savedPosition = com.example.ui.screens.player.PlaybackSyncStore.getPosition(mediaId)
        }
        val currentS = targetServer ?: lastPlayback?.serverName ?: ""

        _uiState.value = _uiState.value.copy(
            mediaId = mediaId,
            isMovie = isMovie,
            isAnime = isAnime || resolvedContentType == com.example.extension.managed.model.ContentType.ANIME,
            contentType = resolvedContentType,
            title = initialTitle,
            availableWebsites = (defaultActive + listOf(bestWebsite)).distinct(),
            currentWebsite = lastPlayback?.website ?: bestWebsite,
            fallbackWebsites = remainingFallbacks,
            currentServer = currentS,
            availableServers = com.example.ui.screens.player.ServerStateStore.extractedServers,
            availableServerLinks = com.example.ui.screens.player.ServerStateStore.extractedServerLinks,
            availableServerIds = serverIds,
            serverIdToChange = if (currentS.isNotBlank()) serverIds[currentS] else null,
            extractedQualitiesInfo = com.example.ui.screens.player.filterCanonicalQualities(com.example.ui.screens.player.ServerStateStore.extractedQualities),
            availableQualities = let {
                val standardList = listOf("1080p", "720p", "480p", "360p", "240p", "144p")
                val canonical = com.example.ui.screens.player.filterCanonicalQualities(com.example.ui.screens.player.ServerStateStore.extractedQualities)
                val explicit = canonical
                    .mapNotNull { com.example.ui.screens.player.normalizeCanonicalQualityName(it.name) }
                    .filter { it in standardList }
                    .distinct()
                    .sortedWith(Comparator { a, b -> standardList.indexOf(a).compareTo(standardList.indexOf(b)) })
                listOf("Auto") + explicit
            },
            currentQuality = com.example.ui.screens.player.normalizeCanonicalQualityName(savedQuality) ?: "Auto",
            pendingSeekPosition = savedPosition,
            currentEpisodeId = effectiveEpId ?: ""
        )

        if (isMovie) {
            viewModelScope.launch {
                try {
                    val movie = tmdbRepo.getMovieById(mediaId)
                    if (movie != null) {
                        val mYear = if (movie.year > 0) movie.year.toString() else ""
                        val mOriginal = movie.originalTitle ?: movie.title
                        _uiState.value = _uiState.value.copy(
                            releaseYear = mYear,
                            originalTitle = mOriginal
                        )
                    }
                } catch (_: Exception) {}
            }
        }

        if (savedPosition <= 0L) {
            viewModelScope.launch {
                try {
                    val hist = com.example.data.repository.HistoryRepository(ctx).getHistoryItem(fileId)
                    if (hist != null && hist.positionMillis > 0L) {
                        _uiState.value = _uiState.value.copy(pendingSeekPosition = hist.positionMillis)
                    }
                } catch (_: Exception) {}
            }
        }

        var effectiveDirectUrl = directUrl ?: lastPlayback?.url
        if (savedQuality != "Auto") {
            val matchedQ = com.example.ui.screens.player.ServerStateStore.extractedQualities.find { it.name == savedQuality }
            if (matchedQ != null && matchedQ.url.isNotBlank()) {
                effectiveDirectUrl = matchedQ.url
            }
        }
        if (!effectiveDirectUrl.isNullOrEmpty() && (effectiveDirectUrl.contains(".mp4") || effectiveDirectUrl.contains(".m3u8") || effectiveDirectUrl.startsWith("local_offline_file") || effectiveDirectUrl.startsWith("file://"))) {
            var finalDirectUrl = effectiveDirectUrl
            if (effectiveDirectUrl.startsWith("local_offline_file://")) {
                val localFileId = effectiveDirectUrl.removePrefix("local_offline_file://")
                val file = com.example.utils.MediaStorageUtils.findMediaFile(ctx, localFileId)
                    ?: com.example.utils.MediaStorageUtils.findMediaFile(ctx, mediaId)
                if (file != null && file.exists()) {
                    finalDirectUrl = android.net.Uri.fromFile(file).toString()
                }
            }
            _uiState.value = _uiState.value.copy(
                currentVideoUrl = finalDirectUrl, 
                isLoading = false
            )
            val knownUrl = lastPlayback?.playbackPageUrl 
                ?: com.example.ui.screens.player.ServerStateStore.getDataForMedia(mediaId)?.playbackPageUrl 
                ?: finalDirectUrl
            val sKey = lastPlayback?.scraperKey 
                ?: com.example.ui.screens.player.ServerStateStore.getDataForMedia(mediaId)?.scraperKey 
                ?: ""
            val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(ctx)
            managedOrchestrator.revalidateMediaInBackground(
                mediaId = mediaId,
                episodeId = effectiveEpId,
                mediaTitle = initialTitle,
                isMovie = isMovie,
                knownPlaybackUrl = knownUrl,
                scraperKey = sKey,
                currentServerName = currentS,
                currentQuality = savedQuality,
                altKeys = listOf(mediaId, fileId)
            )
        } else if (!effectiveDirectUrl.isNullOrEmpty() && !effectiveDirectUrl.startsWith("auto_extract")) {
            // It's a watch url (webpage), we need to extract from it
            _uiState.value = _uiState.value.copy(extractionUrl = effectiveDirectUrl, isLoading = true)
            startExtractionTimeout()
        } else if (!isMovie) {
            loadEpisodes(mediaId, 1) // Default to season 1
        } else {
            generateExtractionUrl()
        }
    }

    fun loadMoreEpisodes() {
        _uiState.value = _uiState.value.copy(visibleEpisodesCount = _uiState.value.visibleEpisodesCount + 10)
    }

    private fun loadEpisodes(seriesId: String, seasonNumber: Int) {
        viewModelScope.launch {
            try {
                // Fetch full series details to get episodes for the season
                val series = tmdbRepo.getSeriesById(seriesId)
                if (series != null) {
                    val sYear = if (series.year > 0) series.year.toString() else ""
                    val sOriginal = series.originalTitle ?: series.title
                    val resolvedTypeStr = com.example.data.util.ContentTypeResolver.resolveSeries(series)
                    val resolvedContentType = com.example.extension.managed.model.ContentType.from(resolvedTypeStr, isMovieFallback = false)
                    _uiState.value = _uiState.value.copy(
                        releaseYear = sYear,
                        originalTitle = sOriginal,
                        contentType = resolvedContentType,
                        isAnime = resolvedContentType == com.example.extension.managed.model.ContentType.ANIME
                    )
                }
                val season = series?.seasons?.find { it.seasonNumber == seasonNumber }
                if (season != null) {
                    val fullSeason = tmdbRepo.getSeasonEpisodes(seriesId, seasonNumber)
                    _uiState.value = _uiState.value.copy(
                        episodes = fullSeason,
                        currentEpisodeId = fullSeason.firstOrNull()?.id ?: "",
                        currentSeasonNumber = seasonNumber,
                        currentEpisodeNumber = fullSeason.firstOrNull()?.episodeNumber ?: 1
                    )
                }
                generateExtractionUrl()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun selectWebsite(website: String) {
        com.example.ui.screens.player.ServerStateStore.clear()
        _uiState.value = _uiState.value.copy(
            currentWebsite = website, 
            isLoading = true, 
            currentVideoUrl = null, 
            fallbackWebsites = emptyList(),
            availableServers = emptyList(),
            availableServerLinks = emptyMap(),
            availableServerIds = emptyMap(),
            currentServer = ""
        )
        generateExtractionUrl()
    }

    fun selectQuality(qualityName: String, currentPos: Long = 0L) {
        val canonicalChoice = com.example.ui.screens.player.normalizeCanonicalQualityName(qualityName) ?: "Auto"
        val qInfo = _uiState.value.extractedQualitiesInfo.find { com.example.ui.screens.player.normalizeCanonicalQualityName(it.name) == canonicalChoice }
        val targetPos = if (currentPos > 0L) currentPos else _uiState.value.currentPositionMillis
        if (qInfo != null && qInfo.url.isNotEmpty() && qInfo.url != _uiState.value.currentVideoUrl && canonicalChoice != "Auto") {
            _uiState.value = _uiState.value.copy(
                currentQuality = canonicalChoice,
                currentVideoUrl = qInfo.url,
                pendingSeekPosition = targetPos
            )
        } else {
            _uiState.value = _uiState.value.copy(
                currentQuality = canonicalChoice
            )
        }
        val ctx = com.example.MyApplication.appContext
        val cached = com.example.ui.screens.player.ServerStateStore.getDataForMedia(_uiState.value.mediaId)
        com.example.utils.LastPlaybackStore.savePlayback(
            context = ctx,
            mediaId = _uiState.value.mediaId,
            url = _uiState.value.currentVideoUrl ?: "",
            quality = canonicalChoice,
            serverName = _uiState.value.currentServer,
            website = _uiState.value.currentWebsite,
            episodeId = _uiState.value.currentEpisodeId.takeIf { it.isNotBlank() },
            positionMillis = targetPos,
            durationMillis = _uiState.value.durationMillis,
            playbackPageUrl = cached?.playbackPageUrl,
            scraperKey = cached?.scraperKey
        )
    }

    fun selectServer(server: String) {
        val link = _uiState.value.availableServerLinks[server]
        val id = _uiState.value.availableServerIds[server]
        
        var nextExtractionUrl = _uiState.value.extractionUrl
        if (link != null && link.isNotEmpty()) {
            nextExtractionUrl = link
        }
        
        _uiState.value = _uiState.value.copy(
            currentServer = server,
            isLoading = true,
            currentVideoUrl = null,
            extractionUrl = nextExtractionUrl,
            serverIdToChange = id
        )
        
        if (nextExtractionUrl != null) {
            if (nextExtractionUrl.contains(".m3u8") || nextExtractionUrl.contains(".mp4") || nextExtractionUrl.contains("akamaized.net")) {
                setFinalVideoUrl(nextExtractionUrl)
            } else {
                startExtractionTimeout()
            }
        } else {
            generateExtractionUrl()
        }
    }

    fun selectEpisode(episode: com.example.domain.models.Episode) {
        if (_uiState.value.isOffline) {
            val ctx = com.example.MyApplication.appContext
            val file = com.example.utils.MediaStorageUtils.findMediaFile(ctx, episode.id)
            val fileUrl = if (file != null && file.exists()) android.net.Uri.fromFile(file).toString() else null
            _uiState.value = _uiState.value.copy(
                currentEpisodeId = episode.id,
                currentEpisodeNumber = episode.episodeNumber,
                title = episode.title,
                currentVideoUrl = fileUrl,
                isLoading = false
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            currentEpisodeId = episode.id,
            currentEpisodeNumber = episode.episodeNumber,
            title = episode.title,
            isLoading = true,
            currentVideoUrl = null
        )
        generateExtractionUrl()
    }

    fun setFinalVideoUrl(url: String) {
        extractionTimeoutJob?.cancel()
        
        // Immediately stop extraction to prevent multiple calls
        _uiState.value = _uiState.value.copy(extractionUrl = null)
        
        viewModelScope.launch {
            try {
                val mediaKey = com.example.ui.screens.player.ServerStateStore.currentMediaKey ?: url
                val sortedQualities = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.ui.screens.player.ServerStateStore.resolveAndCacheAllQualities(
                        mediaKey = mediaKey,
                        serversNames = com.example.ui.screens.player.ServerStateStore.extractedServers,
                        serversMap = com.example.ui.screens.player.ServerStateStore.extractedServerLinks,
                        downloadsMap = com.example.ui.screens.player.ServerStateStore.extractedDownloadLinks,
                        currentStreamUrl = url
                    )
                }

                val canonicalList = com.example.ui.screens.player.filterCanonicalQualities(sortedQualities)
                val standardList = listOf("1080p", "720p", "480p", "360p", "240p", "144p")
                val explicitQualities = canonicalList
                    .mapNotNull { com.example.ui.screens.player.normalizeCanonicalQualityName(it.name) }
                    .filter { it in standardList }
                    .distinct()
                    .sortedWith(Comparator { a, b -> standardList.indexOf(a).compareTo(standardList.indexOf(b)) })
                val allQualitiesList = listOf("Auto") + explicitQualities

                val currentQ = com.example.ui.screens.player.normalizeCanonicalQualityName(_uiState.value.currentQuality) ?: "Auto"
                _uiState.value = _uiState.value.copy(
                    extractedQualitiesInfo = canonicalList,
                    availableQualities = allQualitiesList,
                    currentQuality = if (currentQ in allQualitiesList) currentQ else "Auto",
                    currentVideoUrl = url,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    currentVideoUrl = url,
                    isLoading = false
                )
            }
        }
    }

    fun setIframeUrl(url: String) {
        extractionTimeoutJob?.cancel()
        _uiState.value = _uiState.value.copy(
            extractionUrl = url,
            isLoading = true,
            currentVideoUrl = null
        )
        // Only wait 3 seconds to see if a direct video can be extracted from this iframe
        extractionTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            if (_uiState.value.currentVideoUrl == null) {
                // If no direct video found, just use the iframe as the final video URL
                setFinalVideoUrl(url)
            }
        }
    }

    fun updateServers(servers: List<String>) {
        val isOffline = !com.example.utils.NetworkUtils.isInternetAvailable(com.example.MyApplication.appContext)
        val isLocal = _uiState.value.currentVideoUrl?.let { it.startsWith("file://") || it.startsWith("content://") } ?: false
        if (isOffline || isLocal) {
            _uiState.value = _uiState.value.copy(availableServers = emptyList())
            return
        }
        if (_uiState.value.availableServers != servers && servers.isNotEmpty()) {
            val firstServer = servers.first()
            val link = com.example.ui.screens.player.ServerStateStore.extractedServerLinks[firstServer]
            val id = com.example.ui.screens.player.ServerStateStore.extractedServerIds[firstServer]
            
            var nextExtractionUrl = _uiState.value.extractionUrl
            if (link != null && link.isNotEmpty()) {
                nextExtractionUrl = link
            }
            
            _uiState.value = _uiState.value.copy(
                availableServers = servers,
                currentServer = firstServer,
                extractionUrl = nextExtractionUrl,
                serverIdToChange = id
            )
            
            if (nextExtractionUrl != null) {
                startExtractionTimeout()
            }
        }
    }

    private fun startExtractionTimeout() {
        extractionTimeoutJob?.cancel()
        extractionTimeoutJob = viewModelScope.launch {
            delay(300000) // 5 minutes timeout to allow for manual Cloudflare bypass
            if (_uiState.value.currentVideoUrl == null) {
                tryNextFallback()
            }
        }
    }
    
    fun tryNextFallback() {
        val fallbacks = _uiState.value.fallbackWebsites
        if (fallbacks.isNotEmpty()) {
            val nextSite = fallbacks.first()
            _uiState.value = _uiState.value.copy(
                currentWebsite = nextSite,
                fallbackWebsites = fallbacks.drop(1),
                isLoading = true,
                currentVideoUrl = null
            )
            generateExtractionUrl()
        } else {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    private fun generateExtractionUrl() {
        val state = _uiState.value
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, extractionUrl = null)
            val ctx = com.example.MyApplication.appContext
            val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(ctx)

            currentPlaybackSession?.cancel()
            val session = managedOrchestrator.startPlaybackSession(
                mediaId = state.mediaId,
                title = state.title,
                originalTitle = state.originalTitle,
                year = state.releaseYear,
                isMovie = state.isMovie,
                season = state.currentSeasonNumber,
                episode = state.currentEpisodeNumber,
                contentType = state.contentType
            )
            currentPlaybackSession = session

            val outcome = managedOrchestrator.orchestratePlayback(
                session = session,
                onFirstPlayableSource = { firstSource ->
                    // First playable source: starts playback immediately without waiting for full discovery
                    setFinalVideoUrl(firstSource.streamUrl)
                },
                onQualitiesDiscovered = { qualities ->
                    val qualityLabels = qualities.map { it.label }
                    mergeDetectedQualities(qualityLabels)
                },
                onError = { _ ->
                    // Clean user error: stop loading indicator
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            )

            when (outcome) {
                is com.example.extension.managed.playback.PlaybackOrchestratorOutcome.Started -> {
                    // Playback already initiated early via onFirstPlayableSource
                }
                is com.example.extension.managed.playback.PlaybackOrchestratorOutcome.Failure -> {
                    // All eligible candidates failed or empty search order: clean playback failure without exposing technical details
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        extractionUrl = null,
                        currentVideoUrl = null
                    )
                }
                is com.example.extension.managed.playback.PlaybackOrchestratorOutcome.Cancelled -> {
                    // Session was cancelled
                }
            }
        }
    }

    private suspend fun handleDiscoveryOutcome(
        outcome: com.example.extension.orchestrator.ManagedDiscoveryOutcome,
        managedOrchestrator: com.example.extension.orchestrator.ManagedMediaOrchestrator,
        mediaTitle: String
    ) {
        val state = _uiState.value
        when (outcome) {
            is com.example.extension.orchestrator.ManagedDiscoveryOutcome.Success -> {
                val streamServers = outcome.servers.filter {
                    !it.name.contains("(تحميل)") && !it.link.endsWith(".mp4") && !it.link.endsWith(".mkv")
                }
                val streamList = if (streamServers.isNotEmpty()) streamServers.map { it.name } else outcome.servers.map { it.name }
                val linksMap = outcome.servers.associate { it.name to it.link }
                val idsMap = outcome.servers.associate { it.name to it.id }
                val chosenServer = if (state.currentServer in streamList) state.currentServer else streamList.firstOrNull() ?: ""
                val directUrl = outcome.directStream?.streamUrl ?: linksMap[chosenServer] ?: outcome.servers.firstOrNull()?.link

                _uiState.value = _uiState.value.copy(
                    availableServers = streamList,
                    availableServerLinks = linksMap,
                    availableServerIds = idsMap,
                    currentServer = chosenServer,
                    currentWebsite = outcome.website
                )

                if (directUrl != null && (directUrl.contains(".mp4") || directUrl.contains(".m3u8") || directUrl.contains("akamaized.net"))) {
                    setFinalVideoUrl(directUrl)
                } else if (directUrl != null) {
                    _uiState.value = _uiState.value.copy(extractionUrl = directUrl, isLoading = true)
                    val serverItem = com.example.extension.managed.model.ServerItem(
                        id = idsMap[chosenServer] ?: chosenServer,
                        name = chosenServer,
                        link = directUrl
                    )
                    val extractResult = managedOrchestrator.extractPlaybackSource(serverItem, mediaTitle)
                    if (extractResult.isSuccess) {
                        setFinalVideoUrl(extractResult.getOrThrow().streamUrl)
                    } else {
                        setFinalVideoUrl(directUrl)
                    }
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
            is com.example.extension.orchestrator.ManagedDiscoveryOutcome.RecoverableFailure -> {
                tryNextFallback()
            }
            is com.example.extension.orchestrator.ManagedDiscoveryOutcome.SecurityFailure -> {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun retryPlayback() {
        val mid = lastMediaId ?: return
        val tit = lastTitle ?: return
        initialize(
            mediaId = mid,
            isMovie = lastIsMovie,
            initialTitle = tit,
            directUrl = lastDirectUrl,
            targetServer = lastTargetServer,
            website = lastWebsite,
            episodeId = lastEpisodeId,
            contentType = lastContentType
        )
    }

    fun mergeDetectedQualities(detected: List<String>) {
        val standardList = listOf("1080p", "720p", "480p", "360p", "240p", "144p")
        val normalizedDetected = detected
            .mapNotNull { com.example.ui.screens.player.normalizeCanonicalQualityName(it) }
            .filter { it in standardList }
        if (normalizedDetected.isEmpty()) return
        val currentAvailable = _uiState.value.availableQualities.filter { it in standardList }
        val merged = (currentAvailable + normalizedDetected)
            .distinct()
            .sortedWith(Comparator { a, b -> standardList.indexOf(a).compareTo(standardList.indexOf(b)) })
        val allList = listOf("Auto") + merged
        if (allList != _uiState.value.availableQualities) {
            _uiState.value = _uiState.value.copy(availableQualities = allList)
        }
    }
}