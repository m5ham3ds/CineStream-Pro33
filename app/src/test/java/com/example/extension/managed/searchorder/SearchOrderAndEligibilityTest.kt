package com.example.extension.managed.searchorder

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.registry.ScraperRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchOrderAndEligibilityTest {

    private lateinit var eligibilityFilter: ExtensionEligibilityFilter
    private lateinit var scraperRegistry: ScraperRegistry

    private val witanimeExt = ManagedExtension(
        id = "witanime",
        name = "WitAnime",
        baseUrl = "https://witanime.com",
        scraperKey = "witanime",
        priority = 90,
        contentTypes = setOf(ContentType.ANIME),
        status = ExtensionLifecycleStatus.ACTIVE,
        userEnabled = true
    )

    private val anime4upExt = ManagedExtension(
        id = "anime4up",
        name = "Anime4Up",
        baseUrl = "https://w1.anime4up.rest",
        scraperKey = "anime4up",
        priority = 80,
        contentTypes = setOf(ContentType.ANIME),
        status = ExtensionLifecycleStatus.ACTIVE,
        userEnabled = true
    )

    private val egydeadExt = ManagedExtension(
        id = "egydead",
        name = "EgyDead",
        baseUrl = "https://tv10.egydead.live",
        scraperKey = "egydead",
        priority = 100,
        contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES, ContentType.ANIME),
        status = ExtensionLifecycleStatus.ACTIVE,
        userEnabled = true
    )

    private val qfilmExt = ManagedExtension(
        id = "qfilm",
        name = "Qfilm",
        baseUrl = "https://a.qfilm.tv",
        scraperKey = "qfilm",
        priority = 110,
        contentTypes = setOf(ContentType.MOVIE, ContentType.ANIME),
        status = ExtensionLifecycleStatus.ACTIVE,
        userEnabled = true
    )

    @Before
    fun setUp() {
        scraperRegistry = ScraperRegistry.INSTANCE
        eligibilityFilter = ExtensionEligibilityFilter(scraperRegistry = scraperRegistry)
    }

    @Test
    fun searchOrder_resolvesCorrectListPerContentType() {
        val searchOrder = SearchOrder(
            movie = listOf("egydead", "qfilm"),
            tv = listOf("egydead"),
            series = listOf("legacy_series"),
            anime = listOf("witanime", "anime4up", "qfilm", "egydead")
        )

        assertEquals(listOf("egydead", "qfilm"), searchOrder.getOrderForContentType(ContentType.MOVIE))
        assertEquals(listOf("egydead"), searchOrder.getOrderForContentType(ContentType.SERIES))
        assertEquals(listOf("witanime", "anime4up", "qfilm", "egydead"), searchOrder.getOrderForContentType(ContentType.ANIME))
        assertFalse(searchOrder.isEmpty)
    }

    @Test
    fun searchOrder_fallsBackToSeriesIfTvIsNull() {
        val searchOrder = SearchOrder(
            movie = listOf("qfilm"),
            tv = null,
            series = listOf("egydead"),
            anime = listOf("witanime")
        )

        assertEquals(listOf("egydead"), searchOrder.getOrderForContentType(ContentType.SERIES))
    }

    @Test
    fun eligibilityFilter_preservesExactSearchOrderDeterministically() {
        val searchOrderIds = listOf("anime4up", "witanime", "egydead")
        val allExtensions = listOf(witanimeExt, anime4upExt, egydeadExt)

        val result = eligibilityFilter.filterEligibleExtensions(
            orderedExtensionIds = searchOrderIds,
            availableExtensions = allExtensions,
            targetContentType = ContentType.ANIME
        )

        assertEquals(3, result.size)
        // Order must match searchOrderIds: anime4up first, then witanime, then egydead (NOT by priority)
        assertEquals("anime4up", result[0].id)
        assertEquals("witanime", result[1].id)
        assertEquals("egydead", result[2].id)
    }

    @Test
    fun eligibilityFilter_enforcesHardContentTypeIsolation_animeOnlyNeverRunsForMovies() {
        val searchOrderIds = listOf("witanime", "anime4up", "egydead", "qfilm")
        val allExtensions = listOf(witanimeExt, anime4upExt, egydeadExt, qfilmExt)

        val result = eligibilityFilter.filterEligibleExtensions(
            orderedExtensionIds = searchOrderIds,
            availableExtensions = allExtensions,
            targetContentType = ContentType.MOVIE
        )

        val resultIds = result.map { it.id }
        assertFalse("witanime must be excluded for movies", resultIds.contains("witanime"))
        assertFalse("anime4up must be excluded for movies", resultIds.contains("anime4up"))
        assertTrue("egydead supports movies", resultIds.contains("egydead"))
        assertTrue("qfilm supports movies", resultIds.contains("qfilm"))
    }

    @Test
    fun eligibilityFilter_enforcesHardContentTypeIsolation_seriesExcludesQfilmAndAnimeOnly() {
        val searchOrderIds = listOf("witanime", "qfilm", "egydead")
        val allExtensions = listOf(witanimeExt, qfilmExt, egydeadExt)

        val result = eligibilityFilter.filterEligibleExtensions(
            orderedExtensionIds = searchOrderIds,
            availableExtensions = allExtensions,
            targetContentType = ContentType.SERIES
        )

        val resultIds = result.map { it.id }
        assertFalse("witanime must be excluded for series", resultIds.contains("witanime"))
        assertFalse("qfilm must be excluded for series as it only supports movie and anime", resultIds.contains("qfilm"))
        assertEquals(listOf("egydead"), resultIds)
    }

    @Test
    fun eligibilityFilter_excludesDisabledAndInactiveExtensions() {
        // Admin status = DISABLED is excluded even if userEnabled = true
        val disabledExt = egydeadExt.copy(status = ExtensionLifecycleStatus.DISABLED, userEnabled = true)
        val inactiveExt = witanimeExt.copy(status = ExtensionLifecycleStatus.MAINTENANCE, userEnabled = true)
        // ACTIVE extension is eligible even if userEnabled = false
        val activeExt = anime4upExt.copy(status = ExtensionLifecycleStatus.ACTIVE, userEnabled = false)
        val allExtensions = listOf(disabledExt, inactiveExt, activeExt)

        val result = eligibilityFilter.filterEligibleExtensions(
            orderedExtensionIds = listOf("egydead", "witanime", "anime4up"),
            availableExtensions = allExtensions,
            targetContentType = ContentType.ANIME
        )

        assertEquals(1, result.size)
        assertEquals("anime4up", result[0].id)
    }

    @Test
    fun eligibilityFilter_deduplicatesIds() {
        val searchOrderIds = listOf("egydead", "egydead", "witanime", "egydead")
        val allExtensions = listOf(egydeadExt, witanimeExt)

        val result = eligibilityFilter.filterEligibleExtensions(
            orderedExtensionIds = searchOrderIds,
            availableExtensions = allExtensions,
            targetContentType = ContentType.ANIME
        )

        assertEquals(2, result.size)
        assertEquals("egydead", result[0].id)
        assertEquals("witanime", result[1].id)
    }
}
