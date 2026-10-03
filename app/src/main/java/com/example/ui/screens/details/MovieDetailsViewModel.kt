package com.example.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.di.AppContainer
import com.example.domain.models.Movie
import com.example.domain.repository.MediaRepository
import com.example.utils.MediaDetailsCacheManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MovieDetailsUiState(
    val isLoading: Boolean = false,
    val movie: Movie? = null,
    val similarMovies: List<Movie> = emptyList(),
    val error: String? = null
)

class MovieDetailsViewModel(
    private val repository: MediaRepository
) : ViewModel() {
    companion object {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, MovieDetailsUiState>()
        fun getCachedState(movieId: String): MovieDetailsUiState? = cache[movieId]
        fun saveState(movieId: String, state: MovieDetailsUiState) {
            cache[movieId] = state
        }
    }

    private val _uiState = MutableStateFlow(MovieDetailsUiState())
    val uiState: StateFlow<MovieDetailsUiState> = _uiState.asStateFlow()
    private var networkObserverJob: Job? = null

    fun loadMovie(movieId: String, forceRefresh: Boolean = false) {
        val cached = getCachedState(movieId)
        if (cached != null && !forceRefresh) {
            _uiState.value = cached
        }

        val app = try { AppContainer.application } catch (_: Exception) { null }
        val cachedMovie = if (app != null) MediaDetailsCacheManager.getMovieDetails(app, movieId) else null
        if (cachedMovie != null && _uiState.value.movie == null) {
            _uiState.update { it.copy(movie = cachedMovie, isLoading = false, error = null) }
        }

        viewModelScope.launch {
            if (_uiState.value.movie == null) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            try {
                val movie = repository.getMovieById(movieId)
                if (movie != null) {
                    if (app != null) {
                        MediaDetailsCacheManager.saveMovieDetails(app, movie)
                    }
                    val newState = _uiState.value.copy(movie = movie, isLoading = false, error = null)
                    _uiState.value = newState
                    saveState(movieId, newState)
                    networkObserverJob?.cancel()
                } else if (_uiState.value.movie == null) {
                    val fallback = if (app != null) MediaDetailsCacheManager.getMovieDetails(app, movieId) else null
                    if (fallback != null) {
                        val newState = _uiState.value.copy(movie = fallback, isLoading = false, error = null)
                        _uiState.value = newState
                        saveState(movieId, newState)
                        networkObserverJob?.cancel()
                    } else {
                        // Keep loading skeleton active and wait for internet connection
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        startNetworkAutoReload(movieId)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                if (_uiState.value.movie == null) {
                    val fallback = if (app != null) MediaDetailsCacheManager.getMovieDetails(app, movieId) else null
                    if (fallback != null) {
                        val newState = _uiState.value.copy(movie = fallback, isLoading = false, error = null)
                        _uiState.value = newState
                        saveState(movieId, newState)
                        networkObserverJob?.cancel()
                    } else {
                        // Keep loading skeleton active and wait for internet connection
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        startNetworkAutoReload(movieId)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun startNetworkAutoReload(movieId: String) {
        if (networkObserverJob?.isActive == true) return
        val appContext = try { com.example.MyApplication.appContext } catch (_: Throwable) { null } ?: return
        networkObserverJob = viewModelScope.launch {
            val observer = com.example.utils.NetworkConnectivityObserver(appContext)
            observer.observe().collect { isConnected ->
                if (isConnected && _uiState.value.movie == null) {
                    delay(500)
                    loadMovie(movieId, forceRefresh = true)
                }
            }
        }
    }
}