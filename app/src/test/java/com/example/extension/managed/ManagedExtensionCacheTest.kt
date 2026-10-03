package com.example.extension.managed

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.repository.SafeLocalMetadataCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedExtensionCacheTest {

    private fun validExtension(id: String = "test-1") = ManagedExtension(
        id = id,
        name = "Valid Extension",
        baseUrl = "https://tv10.egydead.live",
        scraperKey = "egydead",
        contentTypes = setOf(ContentType.MOVIE),
        status = ExtensionLifecycleStatus.ACTIVE
    )

    @Test
    fun validCache_savedAndRetrievedCorrectly() = runBlocking {
        val cache = SafeLocalMetadataCache()
        val ext = validExtension("ext-100")
        cache.saveCache(listOf(ext))

        assertFalse(cache.isExpired())
        val retrieved = cache.getCached()
        assertNotNull(retrieved)
        assertEquals(1, retrieved?.size)
        assertEquals("ext-100", retrieved?.first()?.id)
    }

    @Test
    fun expiredCache_returnsNull() = runBlocking {
        var currentTime = 1000L
        val cache = SafeLocalMetadataCache(ttlMillis = 500L, clock = { currentTime })

        cache.saveCache(listOf(validExtension("ext-200")))
        assertNotNull(cache.getCached())

        // Advance time past TTL
        currentTime += 600L
        assertTrue(cache.isExpired())
        assertNull("Expired cache must return null", cache.getCached())
    }

    @Test
    fun corruptedOrInvalidItems_areFilteredOutUponRetrieval() = runBlocking {
        val cache = SafeLocalMetadataCache()
        val valid = validExtension("valid-1")
        val invalidHttp = ManagedExtension(
            id = "invalid-http",
            name = "Insecure",
            baseUrl = "http://tv10.egydead.live", // Insecure HTTP
            scraperKey = "egydead",
            contentTypes = setOf(ContentType.MOVIE)
        )
        val invalidPrivateIp = ManagedExtension(
            id = "invalid-ip",
            name = "Private IP",
            baseUrl = "https://192.168.1.1", // Private IP
            scraperKey = "egydead",
            contentTypes = setOf(ContentType.MOVIE)
        )

        // Save list containing both valid and invalid
        cache.saveCache(listOf(valid, invalidHttp, invalidPrivateIp))

        val retrieved = cache.getCached()
        assertNotNull(retrieved)
        assertEquals(1, retrieved?.size)
        assertEquals("valid-1", retrieved?.first()?.id)
    }

    @Test
    fun cacheClear_removesAllData() = runBlocking {
        val cache = SafeLocalMetadataCache()
        cache.saveCache(listOf(validExtension()))
        assertNotNull(cache.getCached())

        cache.clear()
        assertNull(cache.getCached())
        assertTrue(cache.isExpired())
    }
}
