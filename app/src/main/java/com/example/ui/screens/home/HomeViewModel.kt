package com.example.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.models.Movie
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

data class HomeUiState(
    val isLoading: Boolean = true,
    val trendingMovies: List<Movie> = emptyList(),
    val trendingSeries: List<Series> = emptyList(),
    val actionMovies: List<Movie> = emptyList(),
    val allMovies: List<Movie> = emptyList(),
    val allSeries: List<Series> = emptyList(),
    val animeSeries: List<Series> = emptyList(),
    val trendingAnime: List<Series> = emptyList(),
    val popularMovies: List<Movie> = emptyList(),
    val popularSeries: List<Series> = emptyList(),
    val popularAnime: List<Series> = emptyList(),
    val upcomingSeries: List<Series> = emptyList(),
    val upcomingAnime: List<Series> = emptyList(),
    val newReleasesAnime: List<Series> = emptyList(),
    val upcomingMovies: List<Movie> = emptyList(),
    val newReleasesMovies: List<Movie> = emptyList(),
    val newReleasesSeries: List<Series> = emptyList(),
    val arabicMovies: List<Movie> = emptyList(),
    val arabicSeries: List<Series> = emptyList(),
    val error: String? = null
)

class HomeViewModel(
    private val repository: MediaRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadData()
    }

    fun loadData(forceRefresh: Boolean = false) {
        val hasExistingData = _uiState.value.trendingMovies.isNotEmpty() || _uiState.value.allMovies.isNotEmpty()
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
                        repository.getTrendingMovies().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(trendingMovies = list.take(15), isLoading = false) }
                            }
                        }
                    }
                    launch {
                        repository.getAnimeSeries().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(animeSeries = list.take(15), isLoading = false) }
                            }
                        }
                    }
                    launch {
                        repository.getTrendingSeries().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(trendingSeries = list.take(15), isLoading = false) }
                            }
                        }
                    }
                    launch {
                        repository.getMovies().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                val action = list.filter { m -> m.genres.contains("Action") }
                                _uiState.update {
                                    it.copy(
                                        allMovies = list.take(15),
                                        popularMovies = list.take(15),
                                        actionMovies = action.take(15),
                                        isLoading = false
                                    )
                                }
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingMovies().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(upcomingMovies = list.take(15), isLoading = false) }
                            }
                        }
                    }
                    launch {
                        repository.getNewReleasesMovies().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(newReleasesMovies = list.take(15), isLoading = false) }
                            }
                        }
                    }
                    launch {
                        repository.getNewReleasesSeries().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(newReleasesSeries = list.take(15), isLoading = false) }
                            }
                        }
                    }
                    launch {
                        repository.getTrendingAnime().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(trendingAnime = list.take(15), isLoading = false) }
                            }
                        }
                    }
                    launch {
                        repository.getSeries().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(allSeries = list.take(15), popularSeries = list.take(15), isLoading = false) }
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingSeries().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(upcomingSeries = list.take(15)) }
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingAnime().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(upcomingAnime = list.take(15)) }
                            }
                        }
                    }
                    launch {
                        repository.getNewReleasesAnime().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(newReleasesAnime = list.take(15)) }
                            }
                        }
                    }
                    launch {
                        repository.getArabicMovies().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(arabicMovies = list.take(15)) }
                            }
                        }
                    }
                    launch {
                        repository.getArabicSeries().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                _uiState.update { it.copy(arabicSeries = list.take(15)) }
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
