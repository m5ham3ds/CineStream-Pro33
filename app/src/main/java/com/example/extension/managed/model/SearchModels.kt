package com.example.extension.managed.model

data class SearchRequest(
    val query: String,
    val contentType: ContentType? = null,
    val page: Int = 1,
    val language: String = "ar"
)

data class SearchMediaItem(
    val id: String,
    val title: String,
    val posterUrl: String? = null,
    val url: String,
    val contentType: ContentType,
    val year: String? = null,
    val extraMetadata: Map<String, String> = emptyMap()
)

data class SearchResult(
    val items: List<SearchMediaItem>,
    val page: Int = 1,
    val hasNextPage: Boolean = false,
    val metadata: Map<String, String> = emptyMap()
)
