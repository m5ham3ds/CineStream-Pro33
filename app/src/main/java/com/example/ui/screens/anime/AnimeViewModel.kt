package com.example.ui.screens.anime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.models.Series
import com.example.domain.repository.MediaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class AnimeUiState(
    val isLoading: Boolean = true,
    val trendingAnime: List<Series> = emptyList(),
    val newEpisodes: List<Series> = emptyList(),
    val series: List<Series> = emptyList(),
    val upcomingAnime: List<Series> = emptyList(),
    val error: String? = null
)

class AnimeViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnimeUiState())
    val uiState: StateFlow<AnimeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadData()
    }

    fun loadData(forceRefresh: Boolean = false) {
        val hasExistingData = _uiState.value.series.isNotEmpty() || _uiState.value.trendingAnime.isNotEmpty()
        if (!hasExistingData || forceRefresh) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (forceRefresh) {
                repository.clearCache()
            }
            try {
                supervisorScope {
                    launch {
                        repository.getTrendingAnime().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(trendingAnime = list, isLoading = false) }
                            }
                        }
                    }
                    launch {
                        repository.getNewReleasesAnime().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(newEpisodes = list, isLoading = false) }
                            }
                        }
                    }
                    launch {
                        repository.getAnimeSeries().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(series = list, isLoading = false) }
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingAnime().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(upcomingAnime = list, isLoading = false) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
