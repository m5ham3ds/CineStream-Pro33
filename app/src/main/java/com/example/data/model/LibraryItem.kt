package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
@Entity(tableName = "library_items")
data class LibraryItem(
    @PrimaryKey
    val libraryId: String = "",
    val tmdbId: String = "",
    val title: String = "",
    val posterUrl: String = "",
    val contentType: String = ContentType.MOVIE,
    val isMovie: Boolean = (contentType == ContentType.MOVIE)
) {
    /**
     * Backward-compatibility property:
     * Historically, components referenced `item.id` to get the TMDB ID.
     * This getter returns `tmdbId` if present, or extracts it from `libraryId`.
     */
    val id: String
        get() = tmdbId.ifEmpty {
            LibraryIdentity.parseLibraryId(libraryId)?.second ?: libraryId
        }

    init {
        // Enforce invariants when non-empty instances are constructed
        if (libraryId.isNotBlank() && tmdbId.isNotBlank()) {
            LibraryIdentity.validate(
                libraryId = libraryId,
                tmdbId = tmdbId,
                contentType = contentType,
                isMovie = isMovie
            )
        }
    }

    fun toFirestoreMap(): Map<String, Any> {
        return mapOf(
            "libraryId" to libraryId,
            "tmdbId" to tmdbId,
            "title" to title,
            "posterUrl" to posterUrl,
            "contentType" to contentType,
            "isMovie" to isMovie
        )
    }

    companion object {
        /**
         * Canonical factory method.
         */
        fun create(
            contentType: String,
            tmdbId: String,
            title: String,
            posterUrl: String
        ): LibraryItem {
            val normalizedType = ContentType.normalize(contentType)
            val cleanTmdbId = tmdbId.trim()
            val libraryId = LibraryIdentity.createLibraryId(normalizedType, cleanTmdbId)
            val isMovie = (normalizedType == ContentType.MOVIE)
            return LibraryItem(
                libraryId = libraryId,
                tmdbId = cleanTmdbId,
                title = title.trim(),
                posterUrl = posterUrl.trim(),
                contentType = normalizedType,
                isMovie = isMovie
            )
        }

        /**
         * Converts legacy (id, isMovie) representation into canonical LibraryItem.
         * Legacy Rule:
         * - isMovie == true -> contentType = "movie"
         * - isMovie == false -> contentType = "tv" (NEVER assumed anime)
         */
        fun fromLegacy(
            id: String,
            title: String,
            posterUrl: String,
            isMovie: Boolean
        ): LibraryItem {
            val cleanId = id.trim()
            val contentType = if (isMovie) ContentType.MOVIE else ContentType.TV
            val libraryId = LibraryIdentity.createLibraryId(contentType, cleanId)
            return LibraryItem(
                libraryId = libraryId,
                tmdbId = cleanId,
                title = title.trim(),
                posterUrl = posterUrl.trim(),
                contentType = contentType,
                isMovie = isMovie
            )
        }

        /**
         * Safely parses a Firestore document map, seamlessly supporting both
         * canonical documents and legacy documents.
         */
        fun fromFirestoreMap(docId: String, data: Map<String, Any?>): LibraryItem? {
            try {
                val title = (data["title"] as? String) ?: ""
                val posterUrl = (data["posterUrl"] as? String) ?: ""

                // 1. Check if canonical fields exist
                val rawContentType = data["contentType"] as? String
                val rawTmdbId = (data["tmdbId"] as? String)?.trim()
                val rawLibraryId = (data["libraryId"] as? String)?.trim()

                if (rawContentType != null && ContentType.isValid(rawContentType) && !rawTmdbId.isNullOrBlank()) {
                    val libId = rawLibraryId ?: LibraryIdentity.createLibraryId(rawContentType, rawTmdbId)
                    val isMovie = (rawContentType == ContentType.MOVIE)
                    return LibraryItem(
                        libraryId = libId,
                        tmdbId = rawTmdbId,
                        title = title,
                        posterUrl = posterUrl,
                        contentType = rawContentType,
                        isMovie = isMovie
                    )
                }

                // 2. Check if docId itself is a namespaced libraryId (e.g. "movie_550", "anime_1399")
                val parsedFromDocId = LibraryIdentity.parseLibraryId(docId)
                if (parsedFromDocId != null) {
                    val (type, tmdbId) = parsedFromDocId
                    val isMovie = (type == ContentType.MOVIE)
                    return LibraryItem(
                        libraryId = docId,
                        tmdbId = tmdbId,
                        title = title,
                        posterUrl = posterUrl,
                        contentType = type,
                        isMovie = isMovie
                    )
                }

                // 3. Fallback: Legacy Document format (where docId or "id" is raw TMDB ID)
                val legacyRawId = rawTmdbId ?: (data["id"] as? String) ?: docId
                if (legacyRawId.isBlank()) return null

                val legacyIsMovie = (data["isMovie"] as? Boolean) ?: true
                val legacyContentType = if (legacyIsMovie) ContentType.MOVIE else ContentType.TV
                val canonicalLibraryId = LibraryIdentity.createLibraryId(legacyContentType, legacyRawId)

                return LibraryItem(
                    libraryId = canonicalLibraryId,
                    tmdbId = legacyRawId,
                    title = title,
                    posterUrl = posterUrl,
                    contentType = legacyContentType,
                    isMovie = legacyIsMovie
                )
            } catch (e: Exception) {
                return null
            }
        }
    }
}
