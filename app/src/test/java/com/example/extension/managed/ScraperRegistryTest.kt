package com.example.extension.managed

import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.scraper.Anime4UpScraper
import com.example.extension.managed.scraper.AnimeBlkomScraper
import com.example.extension.managed.scraper.EgyDeadScraper
import com.example.extension.managed.scraper.QfilmScraper
import com.example.extension.managed.scraper.WitanimeScraper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScraperRegistryTest {

    private val registry = ScraperRegistry.INSTANCE

    @Test
    fun knownScraperKey_resolvesStatically() {
        val scraper = registry.getScraper("egydead")
        assertNotNull("egydead key must resolve to an in-app scraper", scraper)
        assertTrue("Scraper must be an instance of EgyDeadScraper", scraper is EgyDeadScraper)
        assertEquals("egydead", scraper?.scraperKey)
        assertEquals(1, scraper?.implementationVersion)

        val qfilmScraper = registry.getScraper("qfilm")
        assertNotNull("qfilm key must resolve to an in-app scraper", qfilmScraper)
        assertTrue("Scraper must be an instance of QfilmScraper", qfilmScraper is QfilmScraper)
        assertEquals("qfilm", qfilmScraper?.scraperKey)
        assertEquals(1, qfilmScraper?.implementationVersion)

        val witanimeScraper = registry.getScraper("witanime")
        assertNotNull("witanime key must resolve to an in-app scraper", witanimeScraper)
        assertTrue("Scraper must be an instance of WitanimeScraper", witanimeScraper is WitanimeScraper)
        assertEquals("witanime", witanimeScraper?.scraperKey)
        assertEquals(1, witanimeScraper?.implementationVersion)

        val anime4upScraper = registry.getScraper("anime4up")
        assertNotNull("anime4up key must resolve to an in-app scraper", anime4upScraper)
        assertTrue("Scraper must be an instance of Anime4UpScraper", anime4upScraper is Anime4UpScraper)
        assertEquals("anime4up", anime4upScraper?.scraperKey)
        assertEquals(1, anime4upScraper?.implementationVersion)

        val animeblkomScraper = registry.getScraper("animeblkom")
        assertNotNull("animeblkom key must resolve to an in-app scraper", animeblkomScraper)
        assertTrue("Scraper must be an instance of AnimeBlkomScraper", animeblkomScraper is AnimeBlkomScraper)
        assertEquals("animeblkom", animeblkomScraper?.scraperKey)
        assertEquals(1, animeblkomScraper?.implementationVersion)
    }

    @Test
    fun caseInsensitiveLookup_succeeds() {
        val scraper = registry.getScraper("EgyDead")
        assertNotNull(scraper)
        assertEquals("egydead", scraper?.scraperKey)

        val qfilm = registry.getScraper("QFilm")
        assertNotNull(qfilm)
        assertEquals("qfilm", qfilm?.scraperKey)

        val witanime = registry.getScraper("WitAnime")
        assertNotNull(witanime)
        assertEquals("witanime", witanime?.scraperKey)

        val anime4up = registry.getScraper("Anime4Up")
        assertNotNull(anime4up)
        assertEquals("anime4up", anime4up?.scraperKey)

        val animeblkom = registry.getScraper("AnimeBlkom")
        assertNotNull(animeblkom)
        assertEquals("animeblkom", animeblkom?.scraperKey)
    }

    @Test
    fun unknownScraperKey_returnsNull() {
        val scraper = registry.getScraper("non_existent_site")
        assertNull(scraper)
        assertFalse(registry.hasScraper("non_existent_site"))
    }

    @Test
    fun allScrapers_containsOnlyBundledScrapers() {
        val all = registry.allScrapers()
        assertEquals(5, all.size)
        val keys = all.map { it.scraperKey }.toSet()
        assertTrue(keys.contains("egydead"))
        assertTrue(keys.contains("qfilm"))
        assertTrue(keys.contains("witanime"))
        assertTrue(keys.contains("anime4up"))
        assertTrue(keys.contains("animeblkom"))
    }
}
