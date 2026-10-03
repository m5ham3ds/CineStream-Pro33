package com.example.extension.managed.searchorder

import com.example.extension.managed.model.ContentType

/**
 * Canonical Search Order Model.
 *
 * Represents the configuration stored at `/config/search_order`:
 * - movie: List of extension IDs for Movies.
 * - tv: List of extension IDs for Television series.
 * - series: Compatibility alias for tv list.
 * - anime: List of extension IDs for Japanese Anime.
 *
 * The Admin order is authoritative and deterministic.
 */
data class SearchOrder(
    val movie: List<String> = emptyList(),
    val tv: List<String>? = null,
    val series: List<String> = emptyList(),
    val anime: List<String> = emptyList()
) {
    /**
     * Resolves the authoritative ordered extension IDs for a requested ContentType.
     * Enforces canonical read behavior:
     * - MOVIE -> movie
     * - SERIES -> tv (primary); if tv is absent/null, fallback to series alias
     * - ANIME -> anime
     */
    fun getOrderForContentType(contentType: ContentType): List<String> {
        return when (contentType) {
            ContentType.MOVIE -> movie
            ContentType.SERIES -> tv ?: series
            ContentType.ANIME -> anime
        }
    }

    val isEmpty: Boolean
        get() = movie.isEmpty() && (tv?.isEmpty() ?: series.isEmpty()) && anime.isEmpty()
}
