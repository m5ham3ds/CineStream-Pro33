package com.example.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.di.AppContainer
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.domain.repository.MediaRepository
import com.example.extension.managed.model.ContentType
import com.example.extension.orchestrator.ManagedMediaOrchestrator
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val movieResults: List<Movie> = emptyList(),
    val seriesResults: List<Series> = emptyList(),
    val trendingNow: List<Movie> = emptyList()
)

@OptIn(FlowPreview::class)
class SearchViewModel(private val repository: MediaRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(500)
                .collect { q ->
                    if (q.isBlank()) {
                        _uiState.update { it.copy(movieResults = emptyList(), seriesResults = emptyList(), isSearching = false) }
                    } else {
                        performSearch(q)
                    }
                }
        }
        
        loadTrending()
    }
    
    private fun loadTrending() {
        viewModelScope.launch {
            repository.getTrendingMovies()
                .catch { }
                .collect { movies ->
                    _uiState.update { it.copy(trendingNow = movies.take(10)) }
                }
        }
    }

    fun refresh() {
        if (queryFlow.value.isNotBlank()) {
            performSearch(queryFlow.value)
        } else {
            loadTrending()
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query, isSearching = true) }
        queryFlow.value = query
    }

    private fun performSearch(query: String) {
        viewModelScope.launch {
            // 1. Fetch TMDB results
            val (tmdbMovies, tmdbSeries) = repository.searchMulti(query)

            // 2. Query active Managed Extensions for additional content
            val app = try { AppContainer.application } catch (_: Exception) { null }
            val managedOrchestrator = app?.let { ManagedMediaOrchestrator.getInstance(it) }
            val managedResults = if (managedOrchestrator != null && managedOrchestrator.hasActiveExtensions()) {
                try {
                    managedOrchestrator.searchMedia(query).getOrNull()?.items ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val additionalMovies = managedResults
                .filter { it.contentType == ContentType.MOVIE }
                .filterNot { item -> tmdbMovies.any { it.title.equals(item.title, ignoreCase = true) } }
                .map { item ->
                    Movie(
                        id = item.id.ifBlank { "managed_${item.title.hashCode()}" },
                        title = item.title,
                        overview = "",
                        posterUrl = item.posterUrl ?: "",
                        backdropUrl = "",
                        year = item.year?.toIntOrNull() ?: 2024,
                        releaseDate = item.year,
                        rating = 8.0,
                        genres = emptyList(),
                        runtime = 120
                    )
                }

            val additionalSeries = managedResults
                .filter { it.contentType == ContentType.SERIES || it.contentType == ContentType.ANIME }
                .filterNot { item -> tmdbSeries.any { it.title.equals(item.title, ignoreCase = true) } }
                .map { item ->
                    Series(
                        id = item.id.ifBlank { "managed_${item.title.hashCode()}" },
                        title = item.title,
                        originalTitle = item.title,
                        overview = "",
                        posterUrl = item.posterUrl ?: "",
                        backdropUrl = "",
                        year = item.year?.toIntOrNull() ?: 2024,
                        firstAirDate = item.year,
                        rating = 8.0,
                        genres = emptyList(),
                        seasons = emptyList()
                    )
                }

            val finalMovies = tmdbMovies + additionalMovies
            val finalSeries = tmdbSeries + additionalSeries

            _uiState.update { it.copy(movieResults = finalMovies, seriesResults = finalSeries, isSearching = false) }
        }
    }
}