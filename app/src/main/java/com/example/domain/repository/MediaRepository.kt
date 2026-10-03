package com.example.domain.repository

import com.example.domain.models.Movie
import com.example.domain.models.Series
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    fun getMovies(): Flow<List<Movie>>
    fun getSeries(): Flow<List<Series>>
    fun getTrendingMovies(): Flow<List<Movie>>
    fun getTrendingSeries(): Flow<List<Series>>
    fun getTrendingAnime(): Flow<List<Series>>

    fun getArabicMovies(): Flow<List<Movie>>
    fun getArabicSeries(): Flow<List<Series>>

    fun getUpcomingMovies(): Flow<List<Movie>>
    fun getUpcomingSeries(): Flow<List<Series>>
    fun getUpcomingAnime(): Flow<List<Series>>
    fun getAnimeSeries(): Flow<List<Series>>
    fun getAnimeMovies(): Flow<List<Movie>>

    fun getNewReleasesMovies(): Flow<List<Movie>>
    fun getNewReleasesSeries(): Flow<List<Series>>
    fun getNewReleasesAnime(): Flow<List<Series>>


    suspend fun getMovieById(id: String): Movie?
    suspend fun getSeriesById(id: String): Series?
    suspend fun searchMulti(query: String): Pair<List<Movie>, List<Series>>
    suspend fun getSeasonEpisodes(seriesId: String, seasonNumber: Int): List<com.example.domain.models.Episode>
    suspend fun getPersonDetails(personId: String): com.example.domain.models.PersonDetails?
    fun clearCache() {}
}