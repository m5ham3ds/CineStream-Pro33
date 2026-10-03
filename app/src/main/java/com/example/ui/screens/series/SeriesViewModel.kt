package com.example.ui.screens.series

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

data class SeriesUiState(
    val isLoading: Boolean = true,
    val trendingSeries: List<Series> = emptyList(),
    val newEpisodes: List<Series> = emptyList(),
    val series: List<Series> = emptyList(),
    val upcomingSeries: List<Series> = emptyList(),
    val error: String? = null
)

class SeriesViewModel(private val repository: MediaRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(SeriesUiState())
    val uiState: StateFlow<SeriesUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadSeries()
    }

    fun loadSeries(forceRefresh: Boolean = false) {
        val hasExistingData = _uiState.value.series.isNotEmpty() || _uiState.value.trendingSeries.isNotEmpty()
        if (!hasExistingData || forceRefresh) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (forceRefresh) {
                repository.clearCache()
            }
            var arabicList: List<Series> = emptyList()
            var trendingList: List<Series> = emptyList()
            var newReleasesList: List<Series> = emptyList()
            var allSeriesList: List<Series> = emptyList()
            var upcomingList: List<Series> = emptyList()

            fun updateCombined() {
                val combinedTrending = (trendingList.take(20) + arabicList.take(20)).sortedByDescending { it.rating }.distinctBy { it.id }
                val combinedNewReleases = (newReleasesList.take(20) + arabicList.take(20)).sortedByDescending { it.rating }.distinctBy { it.id }
                val combinedAllSeries = (allSeriesList.take(20) + arabicList.take(20)).sortedByDescending { it.rating }.distinctBy { it.id }
                val sortedUpcoming = upcomingList.sortedByDescending { it.rating }.distinctBy { it.id }

                val hasData = combinedTrending.isNotEmpty() || combinedAllSeries.isNotEmpty() || combinedNewReleases.isNotEmpty() || sortedUpcoming.isNotEmpty()
                _uiState.update {
                    it.copy(
                        trendingSeries = if (combinedTrending.isNotEmpty()) combinedTrending else it.trendingSeries,
                        newEpisodes = if (combinedNewReleases.isNotEmpty()) combinedNewReleases else it.newEpisodes,
                        series = if (combinedAllSeries.isNotEmpty()) combinedAllSeries else it.series,
                        upcomingSeries = if (sortedUpcoming.isNotEmpty()) sortedUpcoming else it.upcomingSeries,
                        isLoading = if (hasData) false else it.isLoading
                    )
                }
            }

            try {
                supervisorScope {
                    launch {
                        repository.getArabicSeries().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                arabicList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getTrendingSeries().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                trendingList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getNewReleasesSeries().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                newReleasesList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getSeries().catch { }.collect { list ->
                            if (list.isNotEmpty()) {
                                allSeriesList = list
                                updateCombined()
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingSeries().catch { }.collect { list ->
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
