package com.example.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.di.AppContainer
import com.example.domain.models.Episode
import com.example.domain.models.Season
import com.example.domain.models.Series
import com.example.domain.repository.MediaRepository
import com.example.utils.MediaDetailsCacheManager
import com.example.utils.NetworkUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SeriesDetailsUiState(
    val isLoading: Boolean = false,
    val series: Series? = null,
    val error: String? = null,
    val selectedSeason: Season? = null,
    val episodes: List<Episode> = emptyList(),
    val isEpisodesLoading: Boolean = false,
    val visibleEpisodesCount: Int = 10,
    val isLoadingMore: Boolean = false
)

class SeriesDetailsViewModel(
    private val repository: MediaRepository
) : ViewModel() {
    companion object {
        private val cache = mutableMapOf<String, SeriesDetailsUiState>()
        fun getCachedState(seriesId: String): SeriesDetailsUiState? = cache[seriesId]
        fun saveState(seriesId: String, state: SeriesDetailsUiState) {
            cache[seriesId] = state
        }
    }

    private val _uiState = MutableStateFlow(SeriesDetailsUiState())
    val uiState: StateFlow<SeriesDetailsUiState> = _uiState.asStateFlow()
    private var networkObserverJob: Job? = null

    fun loadSeries(seriesId: String, forceRefresh: Boolean = false) {
        val targetId = if (seriesId.contains("_")) seriesId.substringBeforeLast("_") else seriesId
        val cached = getCachedState(targetId)
        if (cached != null && !forceRefresh) {
            _uiState.value = cached
        }

        val app = try { AppContainer.application } catch (_: Exception) { null }
        val persistentCachedSeries = if (app != null) MediaDetailsCacheManager.getSeriesDetails(app, targetId) else null
        if (persistentCachedSeries != null && _uiState.value.series == null) {
            val initialSeason = persistentCachedSeries.seasons.firstOrNull { it.seasonNumber > 0 } ?: persistentCachedSeries.seasons.firstOrNull()
            val initialEpisodes = if (app != null && initialSeason != null) {
                MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, initialSeason.seasonNumber)
            } else emptyList()
            val state = SeriesDetailsUiState(
                series = persistentCachedSeries,
                selectedSeason = initialSeason,
                episodes = initialEpisodes,
                isLoading = false
            )
            _uiState.value = state
            saveState(targetId, state)
        }

        viewModelScope.launch {
            if (_uiState.value.series == null) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            try {
                val series = repository.getSeriesById(targetId)
                if (series != null) {
                    val initialSeason = series.seasons.firstOrNull { it.seasonNumber > 0 } ?: series.seasons.firstOrNull()
                    val newState = _uiState.value.copy(series = series, isLoading = false, selectedSeason = initialSeason, error = null)
                    _uiState.value = newState
                    saveState(seriesId, newState)
                    networkObserverJob?.cancel()
                    if (app != null) {
                        MediaDetailsCacheManager.saveSeriesDetails(app, series, _uiState.value.episodes)
                    }
                    if (initialSeason != null && _uiState.value.episodes.isEmpty()) {
                        loadEpisodes(series.id, initialSeason.seasonNumber)
                    }
                } else if (_uiState.value.series == null) {
                    val fallback = if (app != null) MediaDetailsCacheManager.getSeriesDetails(app, targetId) else null
                    if (fallback != null) {
                        val initialSeason = fallback.seasons.firstOrNull { it.seasonNumber > 0 } ?: fallback.seasons.firstOrNull()
                        val newState = _uiState.value.copy(series = fallback, isLoading = false, selectedSeason = initialSeason, error = null)
                        _uiState.value = newState
                        saveState(targetId, newState)
                        networkObserverJob?.cancel()
                    } else {
                        // Keep loading skeleton active and wait for internet connection
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        startNetworkAutoReload(targetId)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                if (_uiState.value.series == null) {
                    val fallback = if (app != null) MediaDetailsCacheManager.getSeriesDetails(app, targetId) else null
                    if (fallback != null) {
                        val initialSeason = fallback.seasons.firstOrNull { it.seasonNumber > 0 } ?: fallback.seasons.firstOrNull()
                        val newState = _uiState.value.copy(series = fallback, isLoading = false, selectedSeason = initialSeason, error = null)
                        _uiState.value = newState
                        saveState(targetId, newState)
                        networkObserverJob?.cancel()
                    } else {
                        // Keep skeleton active and wait for internet
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        startNetworkAutoReload(targetId)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun startNetworkAutoReload(seriesId: String) {
        if (networkObserverJob?.isActive == true) return
        val appContext = try { com.example.MyApplication.appContext } catch (_: Throwable) { null } ?: return
        networkObserverJob = viewModelScope.launch {
            val observer = com.example.utils.NetworkConnectivityObserver(appContext)
            observer.observe().collect { isConnected ->
                if (isConnected && _uiState.value.series == null) {
                    delay(500)
                    loadSeries(seriesId, forceRefresh = true)
                }
            }
        }
    }

    fun triggerInitialEpisodesLoad() {
        val currentSeries = _uiState.value.series ?: return
        val currentSeason = _uiState.value.selectedSeason ?: return
        if (_uiState.value.episodes.isEmpty() && !_uiState.value.isEpisodesLoading) {
            loadEpisodes(currentSeries.id, currentSeason.seasonNumber)
        }
    }

    private fun updateAndCache(seriesId: String, transform: (SeriesDetailsUiState) -> SeriesDetailsUiState) {
        _uiState.update { transform(it) }
        saveState(seriesId, _uiState.value)
    }

    fun selectSeason(season: Season) {
        val currentSeries = _uiState.value.series ?: return
        updateAndCache(currentSeries.id) { it.copy(selectedSeason = season, visibleEpisodesCount = 10, episodes = emptyList()) }
        loadEpisodes(currentSeries.id, season.seasonNumber)
    }

    fun loadMoreEpisodes(context: android.content.Context) {
        val currentSeries = _uiState.value.series ?: return
        if (_uiState.value.isLoadingMore) return
        
        viewModelScope.launch {
            updateAndCache(currentSeries.id) { it.copy(isLoadingMore = true) }
            
            // Check internet connection to ensure we only load if online
            if (!NetworkUtils.isInternetAvailable(context)) {
                kotlinx.coroutines.delay(300)
                updateAndCache(currentSeries.id) { it.copy(isLoadingMore = false, error = context.getString(com.example.R.string.no_internet_load_episodes)) }
                return@launch
            }
            
            kotlinx.coroutines.delay(350) // Simulate network delay for UI feedback
            updateAndCache(currentSeries.id) { it.copy(visibleEpisodesCount = it.visibleEpisodesCount + 10, isLoadingMore = false) }
        }
    }

    private fun loadEpisodes(seriesId: String, seasonNumber: Int) {
        val app = try { AppContainer.application } catch (_: Exception) { null }
        viewModelScope.launch {
            updateAndCache(seriesId) { it.copy(isEpisodesLoading = true, visibleEpisodesCount = 10) }
            try {
                val episodes = repository.getSeasonEpisodes(seriesId, seasonNumber)
                if (episodes.isNotEmpty()) {
                    updateAndCache(seriesId) { it.copy(episodes = episodes, isEpisodesLoading = false) }
                    val curr = _uiState.value.series
                    if (curr != null && app != null) {
                        MediaDetailsCacheManager.saveSeriesDetails(app, curr, episodes)
                    }
                } else {
                    val cachedEp = if (app != null) MediaDetailsCacheManager.getSeriesEpisodes(app, seriesId, seasonNumber) else emptyList()
                    updateAndCache(seriesId) { it.copy(episodes = cachedEp, isEpisodesLoading = false) }
                }
            } catch (e: Exception) {
                val cachedEp = if (app != null) MediaDetailsCacheManager.getSeriesEpisodes(app, seriesId, seasonNumber) else emptyList()
                updateAndCache(seriesId) { it.copy(episodes = cachedEp, isEpisodesLoading = false) }
            }
        }
    }
}