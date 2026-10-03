package com.example.extension.managed.usecase

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.SearchResult
import com.example.extension.managed.runtime.FallbackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case executing search queries across eligible Managed Extensions.
 *
 * Flow:
 * Search Query
 *   -> Network Check
 *   -> ManagedExtensionResolver (resolves ACTIVE, userEnabled, version-compatible, capability-supporting)
 *   -> Priority ordering (e.g. 100 > 50 > 10)
 *   -> ControlledManagedExtensionRuntime
 *   -> Bundled Scraper execution
 *   -> Normalized SearchResult output
 *
 * Rejects DISABLED, MAINTENANCE, DEPRECATED, user-disabled, or unknown scrapers.
 */
class SearchManagedExtensionsUseCase(
    private val resolver: ManagedExtensionResolver,
    private val runtime: ControlledManagedExtensionRuntime,
    private val fallbackManager: FallbackManager,
    private val isOnlineChecker: () -> Boolean = { true }
) {

    suspend fun execute(
        query: String,
        contentType: ContentType? = null,
        page: Int = 1
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext Result.success(SearchResult(items = emptyList(), page = page, hasNextPage = false))
        }

        if (!isOnlineChecker()) {
            return@withContext Result.failure(ExtensionError.NoInternet())
        }

        // 1. Resolve eligible candidates with SEARCH capability
        val candidates = resolver.resolveEligibleExtensions(
            capability = ScraperCapability.SEARCH,
            contentType = contentType,
            allowDeprecated = false
        )

        if (candidates.isEmpty()) {
            return@withContext Result.failure(
                ExtensionError.MediaNotFound("No eligible managed extensions available for search")
            )
        }

        val cleanQuery = query.trim()
        val searchRequest = SearchRequest(
            query = cleanQuery,
            contentType = contentType,
            page = page
        )

        var lastRecoverableError: Throwable? = null

        // 2. Execute search in priority order (HIGHER NUMBER = HIGHER PRIORITY)
        for (candidate in candidates) {
            val result = runtime.search(listOf(candidate), searchRequest)
            if (result.isSuccess) {
                val searchResult = result.getOrThrow()
                if (searchResult.items.isNotEmpty()) {
                    return@withContext Result.success(searchResult)
                }
            } else {
                val error = result.exceptionOrNull()
                lastRecoverableError = error

                val isRecoverable = when (error) {
                    is ExtensionError -> fallbackManager.isRecoverable(error)
                    else -> true
                }

                if (!isRecoverable) {
                    return@withContext Result.failure(error ?: Exception("Unrecoverable search error"))
                }
            }
        }

        // All candidates returned empty or recoverable error
        Result.failure(
            lastRecoverableError ?: ExtensionError.MediaNotFound("No results found for '$cleanQuery'")
        )
    }
}
