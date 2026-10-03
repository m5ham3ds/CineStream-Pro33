package com.example.ui.screens.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.models.Movie
import com.example.domain.repository.MediaRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class MoviesUiState(
    val isLoading: Boolean = true,
    val trendingMovies: List<Movie> = emptyList(),
    val newReleasesMovies: List<Movie> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val upcomingMovies: List<Movie> = emptyList(),
    val error: String? = null
)

class MoviesViewModel(private val repository: MediaRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(MoviesUiState())
    val uiState: StateFlow<MoviesUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadMovies()
    }

    fun loadMovies(forceRefresh: Boolean = false) {
        val hasExistingData = _uiState.value.movies.isNotEmpty() || _uiState.value.trendingMovies.isNotEmpty()
        if (!hasExistingData || forceRefresh) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (forceRefresh) {
                repository.clearCache()
            }
            var arabicList: List<Movie> = emptyList()
            var trendingList: List<Movie> = emptyList()
            var newReleasesList: List<Movie> = emptyList()
            var allMoviesList: List<Movie> = emptyList()
            var upcomingList: List<Movie> = emptyList()

            fun updateCombined() {
                val combinedTrending = (trendingList.take(20) + arabicList.take(20)).sortedByDescending { it.rating }.distinctBy { it.id }
                val combinedNewReleases = (newReleasesList.take(20) + arabicList.take(20)).sortedByDescending { it.rating }.distinctBy { it.id }
                val combinedAllMovies = (allMoviesList.take(20) + arabicList.take(20)).sortedByDescending { it.rating }.distinctBy { it.id }
                val sortedUpcoming = upcomingList.sortedByDescending { it.rating }.distinctBy { it.id }

                val hasData = combinedTrending.isNotEmpty() || combinedAllMovies.isNotEmpty() || combinedNewReleases.isNotEmpty() || sortedUpcoming.isNotEmpty()
                _uiState.update {
                    it.copy(
                        trendingMovies = if (combinedTrending.isNotEmpty()) combinedTrending else it.trendingMovies,
                        newReleasesMovies = if (combinedNewReleases.isNotEmpty()) combinedNewReleases else it.newReleasesMovies,
                        movies = if (combinedAllMovies.isNotEmpty()) combinedAllMovies else it.movies,
                        upcomingMovies = if (sortedUpcoming.isNotEmpty()) sortedUpcoming else it.upcomingMovies,
                        isLoading = if (hasData) false else it.isLoading
                    )
                }
            }

            try {
                supervisorScope {
                    launch {
                        repository.getArabicMovies().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                arabicList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getTrendingMovies().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                trendingList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getNewReleasesMovies().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                newReleasesList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getMovies().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                allMoviesList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingMovies().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                upcomingList = list
                                updateCombined()
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
