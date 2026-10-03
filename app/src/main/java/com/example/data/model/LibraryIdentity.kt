package com.example.data.model

/**
 * Single source of truth for constructing, parsing, and validating
 * Canonical Library Identities: {contentType}_{tmdbId}
 */
object LibraryIdentity {

    /**
     * Creates a namespaced libraryId from contentType and tmdbId.
     * Example: createLibraryId("anime", "1399") -> "anime_1399"
     */
    fun createLibraryId(contentType: String, tmdbId: String): String {
        require(ContentType.isValid(contentType)) {
            "Invalid contentType: '$contentType'. Allowed values: ${ContentType.VALID_TYPES}"
        }
        val cleanTmdbId = tmdbId.trim()
        require(cleanTmdbId.isNotBlank()) {
            "tmdbId must not be blank"
        }
        return "${contentType}_$cleanTmdbId"
    }

    /**
     * Parses a namespaced libraryId into (contentType, tmdbId).
     * Returns null if the libraryId is not a valid namespaced format.
     */
    fun parseLibraryId(libraryId: String): Pair<String, String>? {
        if (!libraryId.contains("_")) return null
        val parts = libraryId.split("_", limit = 2)
        if (parts.size != 2) return null
        val type = parts[0].trim()
        val tmdbId = parts[1].trim()
        if (!ContentType.isValid(type) || tmdbId.isBlank()) return null
        return Pair(type, tmdbId)
    }

    /**
     * Validates all canonical invariants:
     * 1. contentType in {"movie", "tv", "anime"}
     * 2. tmdbId is not blank
     * 3. libraryId == "${contentType}_${tmdbId}"
     * 4. isMovie == (contentType == "movie")
     */
    fun validate(libraryId: String, tmdbId: String, contentType: String, isMovie: Boolean) {
        require(ContentType.isValid(contentType)) {
            "Invariant violation: contentType '$contentType' is invalid. Must be one of ${ContentType.VALID_TYPES}"
        }
        require(tmdbId.isNotBlank()) {
            "Invariant violation: tmdbId must not be blank"
        }
        val expectedLibraryId = "${contentType}_${tmdbId.trim()}"
        require(libraryId == expectedLibraryId) {
            "Invariant violation: libraryId '$libraryId' does not match expected '$expectedLibraryId'"
        }
        val expectedIsMovie = (contentType == ContentType.MOVIE)
        require(isMovie == expectedIsMovie) {
            "Invariant violation: isMovie ($isMovie) must be $expectedIsMovie for contentType '$contentType'"
        }
    }

    /**
     * Overload to validate a LibraryItem instance.
     */
    fun validate(item: LibraryItem) {
        validate(
            libraryId = item.libraryId,
            tmdbId = item.tmdbId,
            contentType = item.contentType,
            isMovie = item.isMovie
        )
    }
}
