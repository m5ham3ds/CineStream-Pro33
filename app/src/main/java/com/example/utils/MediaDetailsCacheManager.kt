package com.example.utils

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.domain.models.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MediaDetailsCacheManager {

    private const val CACHE_DIR_NAME = "media_details_cache"

    private fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getMovieFile(context: Context, movieId: String): File {
        val safeId = movieId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return File(getCacheDir(context), "movie_${safeId}.json")
    }

    private fun getSeriesFile(context: Context, seriesId: String): File {
        val safeId = seriesId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return File(getCacheDir(context), "series_${safeId}.json")
    }

    fun hasDetails(context: Context, mediaId: String, isMovie: Boolean): Boolean {
        val file = if (isMovie) getMovieFile(context, mediaId) else getSeriesFile(context, mediaId)
        return file.exists() && file.length() > 0
    }

    fun saveMovieDetails(context: Context, movie: Movie) {
        try {
            val json = JSONObject().apply {
                put("id", movie.id)
                put("title", movie.title)
                put("originalTitle", movie.originalTitle)
                put("overview", movie.overview)
                put("posterUrl", movie.posterUrl)
                put("backdropUrl", movie.backdropUrl)
                put("year", movie.year)
                put("releaseDate", movie.releaseDate)
                put("rating", movie.rating)
                put("genres", JSONArray(movie.genres))
                put("runtime", movie.runtime)
                put("language", movie.language)
                
                val castArray = JSONArray()
                movie.cast.forEach { member ->
                    castArray.put(JSONObject().apply {
                        put("id", member.id)
                        put("name", member.name)
                        put("character", member.character)
                        put("profileUrl", member.profileUrl)
                    })
                }
                put("cast", castArray)

                val trailerArray = JSONArray()
                movie.trailers.forEach { trailer ->
                    trailerArray.put(JSONObject().apply {
                        put("name", trailer.name)
                        put("key", trailer.key)
                        put("type", trailer.type)
                    })
                }
                put("trailers", trailerArray)
            }
            getMovieFile(context, movie.id).writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getMovieDetails(context: Context, movieId: String): Movie? {
        val file = getMovieFile(context, movieId)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val genresList = mutableListOf<String>()
            val genresJson = json.optJSONArray("genres")
            if (genresJson != null) {
                for (i in 0 until genresJson.length()) {
                    genresList.add(genresJson.getString(i))
                }
            }

            val castList = mutableListOf<CastMember>()
            val castJson = json.optJSONArray("cast")
            if (castJson != null) {
                for (i in 0 until castJson.length()) {
                    val c = castJson.getJSONObject(i)
                    castList.add(CastMember(
                        id = c.optString("id", ""),
                        name = c.optString("name", ""),
                        character = c.optString("character", ""),
                        profileUrl = c.optString("profileUrl", "")
                    ))
                }
            }

            val trailerList = mutableListOf<VideoTrailer>()
            val trailerJson = json.optJSONArray("trailers")
            if (trailerJson != null) {
                for (i in 0 until trailerJson.length()) {
                    val t = trailerJson.getJSONObject(i)
                    trailerList.add(VideoTrailer(
                        name = t.optString("name", ""),
                        key = t.optString("key", ""),
                        type = t.optString("type", "Trailer")
                    ))
                }
            }

            Movie(
                id = json.optString("id", movieId),
                title = json.optString("title", "Unknown"),
                originalTitle = json.optString("originalTitle", ""),
                overview = json.optString("overview", ""),
                posterUrl = json.optString("posterUrl", ""),
                backdropUrl = json.optString("backdropUrl", ""),
                year = json.optInt("year", 2024),
                releaseDate = json.optString("releaseDate", ""),
                rating = json.optDouble("rating", 0.0),
                genres = genresList,
                runtime = json.optInt("runtime", 0),
                language = json.optString("language", "ar"),
                cast = castList,
                trailers = trailerList
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveSeriesDetails(context: Context, series: Series, episodes: List<Episode> = emptyList()) {
        try {
            val json = JSONObject().apply {
                put("id", series.id)
                put("title", series.title)
                put("overview", series.overview)
                put("posterUrl", series.posterUrl)
                put("backdropUrl", series.backdropUrl)
                put("year", series.year)
                put("firstAirDate", series.firstAirDate)
                put("rating", series.rating)
                put("genres", JSONArray(series.genres))
                put("originalLanguage", series.originalLanguage ?: "")
                val originCountryArray = JSONArray()
                series.originCountry.forEach { originCountryArray.put(it) }
                put("originCountry", originCountryArray)
                put("creator", series.creator ?: "")
                put("status", series.status)

                val seasonsArray = JSONArray()
                series.seasons.forEach { season ->
                    seasonsArray.put(JSONObject().apply {
                        put("id", season.id)
                        put("seriesId", season.seriesId)
                        put("seasonNumber", season.seasonNumber)
                        put("title", season.title)
                        put("episodeCount", season.episodeCount)
                        put("posterUrl", season.posterUrl)
                    })
                }
                put("seasons", seasonsArray)

                val castArray = JSONArray()
                series.cast.forEach { member ->
                    castArray.put(JSONObject().apply {
                        put("id", member.id)
                        put("name", member.name)
                        put("character", member.character)
                        put("profileUrl", member.profileUrl)
                    })
                }
                put("cast", castArray)

                val episodesArray = JSONArray()
                episodes.forEach { ep ->
                    episodesArray.put(JSONObject().apply {
                        put("id", ep.id)
                        put("episodeNumber", ep.episodeNumber)
                        put("title", ep.title)
                        put("overview", ep.overview)
                        put("thumbnailUrl", ep.thumbnailUrl)
                        put("rating", ep.rating)
                        put("duration", ep.duration)
                    })
                }
                put("episodes", episodesArray)
            }
            getSeriesFile(context, series.id).writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getSeriesDetails(context: Context, seriesId: String): Series? {
        val file = getSeriesFile(context, seriesId)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val genresList = mutableListOf<String>()
            val genresJson = json.optJSONArray("genres")
            if (genresJson != null) {
                for (i in 0 until genresJson.length()) {
                    genresList.add(genresJson.getString(i))
                }
            }

            val castList = mutableListOf<CastMember>()
            val castJson = json.optJSONArray("cast")
            if (castJson != null) {
                for (i in 0 until castJson.length()) {
                    val c = castJson.getJSONObject(i)
                    castList.add(CastMember(
                        id = c.optString("id", ""),
                        name = c.optString("name", ""),
                        character = c.optString("character", ""),
                        profileUrl = c.optString("profileUrl", "")
                    ))
                }
            }

            val seasonsList = mutableListOf<Season>()
            val seasonsJson = json.optJSONArray("seasons")
            if (seasonsJson != null) {
                for (i in 0 until seasonsJson.length()) {
                    val s = seasonsJson.getJSONObject(i)
                    seasonsList.add(Season(
                        id = s.optString("id", ""),
                        seriesId = s.optString("seriesId", seriesId),
                        seasonNumber = s.optInt("seasonNumber", 1),
                        title = s.optString("title", "Season ${s.optInt("seasonNumber", 1)}"),
                        episodeCount = s.optInt("episodeCount", 10),
                        posterUrl = s.optString("posterUrl", "")
                    ))
                }
            }

            val originCountryList = mutableListOf<String>()
            val originCountryJson = json.optJSONArray("originCountry")
            if (originCountryJson != null) {
                for (i in 0 until originCountryJson.length()) {
                    originCountryList.add(originCountryJson.getString(i))
                }
            }
            val origLang = json.optString("originalLanguage", "").ifEmpty { null }

            Series(
                id = json.optString("id", seriesId),
                title = json.optString("title", "Unknown"),
                overview = json.optString("overview", ""),
                posterUrl = json.optString("posterUrl", ""),
                backdropUrl = json.optString("backdropUrl", ""),
                year = json.optInt("year", 2024),
                firstAirDate = json.optString("firstAirDate", ""),
                rating = json.optDouble("rating", 0.0),
                genres = genresList,
                cast = castList,
                trailers = emptyList(),
                seasons = seasonsList,
                creator = json.optString("creator", null),
                status = json.optString("status", "Ongoing"),
                originalLanguage = origLang,
                originCountry = originCountryList
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getSeriesEpisodes(context: Context, seriesId: String, seasonNumber: Int): List<Episode> {
        val file = getSeriesFile(context, seriesId)
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            val episodesJson = json.optJSONArray("episodes") ?: return emptyList()
            val result = mutableListOf<Episode>()
            for (i in 0 until episodesJson.length()) {
                val ep = episodesJson.getJSONObject(i)
                result.add(Episode(
                    id = ep.optString("id", ""),
                    episodeNumber = ep.optInt("episodeNumber", 1),
                    title = ep.optString("title", ""),
                    overview = ep.optString("overview", ""),
                    thumbnailUrl = ep.optString("thumbnailUrl", ""),
                    rating = ep.optDouble("rating", 0.0),
                    duration = ep.optInt("duration", 0)
                ))
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun getDetailsJson(context: Context, mediaId: String, isMovie: Boolean): String? {
        val file = if (isMovie) getMovieFile(context, mediaId) else getSeriesFile(context, mediaId)
        return if (file.exists()) file.readText() else null
    }

    fun saveDetailsFromJson(context: Context, mediaId: String, isMovie: Boolean, jsonString: String) {
        try {
            val file = if (isMovie) getMovieFile(context, mediaId) else getSeriesFile(context, mediaId)
            file.writeText(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Clear cached media details ONLY for items that have NOT been downloaded.
     * Information of any downloaded work is strictly preserved!
     */
    suspend fun clearNonDownloadedCache(context: Context) {
        try {
            val db = AppDatabase.getDatabase(context)
            val downloadedItems = db.downloadDao().getAllItemsSync()
            val protectedMediaIds = downloadedItems.map { it.mediaId }.toSet()

            val dir = getCacheDir(context)
            val files = dir.listFiles() ?: return
            for (f in files) {
                val name = f.name
                val isMovieFile = name.startsWith("movie_")
                val isSeriesFile = name.startsWith("series_")
                if (!isMovieFile && !isSeriesFile) continue

                val idPart = name.removePrefix("movie_").removePrefix("series_").removeSuffix(".json")
                val isProtected = protectedMediaIds.any { idPart.startsWith(it) || it.startsWith(idPart) }
                if (!isProtected) {
                    f.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
