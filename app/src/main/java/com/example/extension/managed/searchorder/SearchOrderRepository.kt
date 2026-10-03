package com.example.extension.managed.searchorder

import com.example.extension.managed.model.ContentType
import java.util.concurrent.atomic.AtomicReference

interface SearchOrderRepository {
    suspend fun getSearchOrder(forceRefresh: Boolean = false): SearchOrder?
    suspend fun getOrderForContentType(contentType: ContentType, forceRefresh: Boolean = false): List<String>
    fun clearCache()
}

class DefaultSearchOrderRepository(
    private val dataSource: SearchOrderDataSource = FirebaseSearchOrderDataSource(),
    private val cacheTtlMillis: Long = 10 * 60 * 1000L // 10 minutes
) : SearchOrderRepository {

    private data class CachedSearchOrder(
        val order: SearchOrder,
        val timestamp: Long
    )

    private val cache = AtomicReference<CachedSearchOrder?>(null)

    override suspend fun getSearchOrder(forceRefresh: Boolean): SearchOrder? {
        val currentCache = cache.get()
        val now = System.currentTimeMillis()

        if (!forceRefresh && currentCache != null && (now - currentCache.timestamp) < cacheTtlMillis) {
            return currentCache.order
        }

        val remoteOrder = dataSource.fetchSearchOrder()
        if (remoteOrder != null) {
            cache.set(CachedSearchOrder(remoteOrder, now))
            return remoteOrder
        }

        // Return cached value if available during temporary network failure
        return currentCache?.order
    }

    override suspend fun getOrderForContentType(
        contentType: ContentType,
        forceRefresh: Boolean
    ): List<String> {
        val searchOrder = getSearchOrder(forceRefresh) ?: return emptyList()
        return searchOrder.getOrderForContentType(contentType)
    }

    override fun clearCache() {
        cache.set(null)
    }
}
