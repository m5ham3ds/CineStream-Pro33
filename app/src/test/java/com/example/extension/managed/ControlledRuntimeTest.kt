package com.example.extension.managed

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.model.ServerItem
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.runtime.DefaultControlledManagedExtensionRuntime
import com.example.extension.managed.runtime.FallbackManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlledRuntimeTest {

    private val runtime: ControlledManagedExtensionRuntime = DefaultControlledManagedExtensionRuntime(
        scraperRegistry = ScraperRegistry.INSTANCE,
        fallbackManager = FallbackManager(ScraperRegistry.INSTANCE),
        currentAppVersionCode = 1L,
        supportedRuntimeApiVersion = 1
    )

    private val validEgyDeadExt = ManagedExtension(
        id = "egydead-v1",
        name = "إيجي ديد",
        baseUrl = "https://tv10.egydead.live",
        scraperKey = "egydead",
        priority = 10,
        contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES, ContentType.ANIME),
        status = ExtensionLifecycleStatus.ACTIVE
    )

    @Test
    fun directStreamExtraction_projectsMinimalRefererAndUserAgent() = runBlocking {
        val server = ServerItem(
            id = "direct-1",
            name = "سيرفر سريع",
            link = "https://cdn.streamprovider.com/video.mp4",
            isDirectStream = true
        )

        val request = ExtractionRequest(serverItem = server)
        val result = runtime.extractStream(listOf(validEgyDeadExt), request)

        assertTrue("Extraction should succeed for direct stream", result.isSuccess)
        val playback = result.getOrNull()?.playbackSource
        assertNotNull(playback)
        assertEquals("https://cdn.streamprovider.com/video.mp4", playback?.streamUrl)
        assertEquals("video/mp4", playback?.mimeType)

        // Verify minimal credential projection:
        // Referer should be set to baseUrl, but no arbitrary cookies or internal headers
        assertNotNull(playback?.headers?.get("Referer"))
        assertEquals("https://tv10.egydead.live", playback?.headers?.get("Referer"))
        assertNotNull(playback?.headers?.get("User-Agent"))
    }

    @Test
    fun runtimeEnforcesCompatibility_onInvalidExtension() = runBlocking {
        val invalidExt = validEgyDeadExt.copy(
            minAppVersionCode = 999L // App is on version 1L
        )

        val searchResult = runtime.search(listOf(invalidExt), SearchRequest("test"))
        assertTrue("Search must fail when extension requires newer app version", searchResult.isFailure)
    }
}
