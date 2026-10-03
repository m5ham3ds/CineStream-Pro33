package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TmdbResponse<T>(
    @Json(name = "page") val page: Int,
    @Json(name = "results") val results: List<T>,
    @Json(name = "total_pages") val totalPages: Int,
    @Json(name = "total_results") val totalResults: Int
)

@JsonClass(generateAdapter = true)
data class TmdbMovie(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String?,
    @Json(name = "overview") val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "release_date") val releaseDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?,
    @Json(name = "genre_ids") val genreIds: List<Int>? = emptyList()
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: ""
        
    val fullBackdropUrl: String
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbSeries(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String?,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "overview") val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "first_air_date") val firstAirDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?,
    @Json(name = "genre_ids") val genreIds: List<Int>? = emptyList(),
    @Json(name = "origin_country") val originCountry: List<String>? = emptyList(),
    @Json(name = "original_language") val originalLanguage: String? = null
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: ""
        
    val fullBackdropUrl: String
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbMulti(
    @Json(name = "id") val id: Int,
    @Json(name = "media_type") val mediaType: String,
    @Json(name = "title") val title: String?, // Used for movies
    @Json(name = "name") val name: String?,
    @Json(name = "original_name") val originalName: String? = null,   // Used for series
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "release_date") val releaseDate: String?,
    @Json(name = "first_air_date") val firstAirDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: ""
        
    val fullBackdropUrl: String
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" } ?: ""
}


@JsonClass(generateAdapter = true)
data class TmdbGenre(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String
)

@JsonClass(generateAdapter = true)
data class TmdbCast(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String,
    @Json(name = "character") val character: String?,
    @Json(name = "profile_path") val profilePath: String?
) {
    val fullProfileUrl: String?
        get() = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
}

@JsonClass(generateAdapter = true)
data class TmdbCrew(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String,
    @Json(name = "job") val job: String
)

@JsonClass(generateAdapter = true)
data class TmdbCredits(
    @Json(name = "cast") val cast: List<TmdbCast>,
    @Json(name = "crew") val crew: List<TmdbCrew>
)

@JsonClass(generateAdapter = true)
data class TmdbVideo(
    @Json(name = "id") val id: String,
    @Json(name = "key") val key: String,
    @Json(name = "name") val name: String,
    @Json(name = "site") val site: String,
    @Json(name = "type") val type: String
)

@JsonClass(generateAdapter = true)
data class TmdbVideos(
    @Json(name = "results") val results: List<TmdbVideo>
)

@JsonClass(generateAdapter = true)
data class TmdbSeason(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String,
    @Json(name = "season_number") val seasonNumber: Int,
    @Json(name = "episode_count") val episodeCount: Int,
    @Json(name = "poster_path") val posterPath: String?
) {
    val fullPosterUrl: String?
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
}

@JsonClass(generateAdapter = true)
data class TmdbCreator(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String
)

@JsonClass(generateAdapter = true)
data class TmdbMovieDetails(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String?,
    @Json(name = "original_title") val originalTitle: String?,
    @Json(name = "overview") val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "release_date") val releaseDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?,
    @Json(name = "runtime") val runtime: Int?,
    @Json(name = "original_language") val originalLanguage: String?,
    @Json(name = "genres") val genres: List<TmdbGenre>?,
    @Json(name = "credits") val credits: TmdbCredits?,
    @Json(name = "videos") val videos: TmdbVideos?
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: ""
        
    val fullBackdropUrl: String
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbSeriesDetails(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String?,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "overview") val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "first_air_date") val firstAirDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?,
    @Json(name = "genres") val genres: List<TmdbGenre>?,
    @Json(name = "credits") val credits: TmdbCredits?,
    @Json(name = "videos") val videos: TmdbVideos?,
    @Json(name = "seasons") val seasons: List<TmdbSeason>?,
    @Json(name = "created_by") val createdBy: List<TmdbCreator>?,
    @Json(name = "status") val status: String?,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "origin_country") val originCountry: List<String>? = emptyList()
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: ""
        
    val fullBackdropUrl: String
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbEpisode(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String?,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "overview") val overview: String?,
    @Json(name = "episode_number") val episodeNumber: Int,
    @Json(name = "still_path") val stillPath: String?,
    @Json(name = "vote_average") val voteAverage: Double?,
    @Json(name = "runtime") val runtime: Int?
) {
    val fullStillUrl: String?
        get() = stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }
}

@JsonClass(generateAdapter = true)
data class TmdbSeasonDetails(
    @Json(name = "id") val id: Int,
    @Json(name = "season_number") val seasonNumber: Int,
    @Json(name = "episodes") val episodes: List<TmdbEpisode>?
)


@JsonClass(generateAdapter = true)
data class TmdbPersonMovieCredit(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String?,
    @Json(name = "overview") val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "release_date") val releaseDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbPersonSeriesCredit(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String?,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "overview") val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "first_air_date") val firstAirDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbPersonCombinedCredits(
    @Json(name = "cast") val cast: List<TmdbMulti> // We can reuse TmdbMulti since it handles both movie/tv
)

@JsonClass(generateAdapter = true)
data class TmdbPersonDetails(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String?,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "biography") val biography: String?,
    @Json(name = "profile_path") val profilePath: String?,
    @Json(name = "birthday") val birthday: String?,
    @Json(name = "place_of_birth") val placeOfBirth: String?,
    @Json(name = "known_for_department") val knownForDepartment: String?,
    @Json(name = "combined_credits") val combinedCredits: TmdbPersonCombinedCredits?
) {
    val fullProfileUrl: String?
        get() = profilePath?.let { "https://image.tmdb.org/t/p/w500$it" }
}