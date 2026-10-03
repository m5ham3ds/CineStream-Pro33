package com.example.extension.managed

import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.scraper.WitanimeScraper
import com.example.extension.managed.web.MediaStreamDetector
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Complete unit test suite verifying the Witanime + 4shared Proof of Concept implementation.
 * Covers all 24 required test vectors specified in Section 37.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WitanimeScraperUnitTest {

    private val scraper = WitanimeScraper()
    private val testBaseUrl = "https://witanime.com"
    private val testEpisodeUrl = "https://witanime.com/episode/mushoku-tensei-s3-episode-14"
    private val dummyExtension = ManagedExtension(
        id = "witanime",
        name = "WitAnime",
        baseUrl = testBaseUrl,
        scraperKey = "witanime",
        contentTypes = setOf(ContentType.ANIME, ContentType.SERIES, ContentType.MOVIE),
        status = ExtensionLifecycleStatus.ACTIVE
    )

    // --- 1. Valid /sources JSON ---
    @Test
    fun test01_validSourcesJson() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "synthetic_token_fhd", "label": "4shared", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertNotNull(servers)
        assertEquals(1, servers.size)
        assertEquals("4shared (FHD)", servers[0].name)
    }

    // --- 2. Players parsing ---
    @Test
    fun test02_playersParsing() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "token_fhd_1", "label": "4shared", "version": "sub", "lang": "jp" }
                    ],
                    "HD": [
                        { "token": "token_hd_1", "label": "4shared", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertEquals(2, servers.size)
        val qualities = servers.map { it.metadata["quality"] }.toSet()
        assertTrue(qualities.contains("FHD"))
        assertTrue(qualities.contains("HD"))
    }

    // --- 3. Dynamic quality keys ---
    @Test
    fun test03_dynamicQualityKeys() {
        val json = """
            {
                "players": {
                    "2160p_UHD": [
                        { "token": "token_uhd", "label": "4shared", "version": "sub", "lang": "jp" }
                    ],
                    "480p_Custom": [
                        { "token": "token_480", "label": "4shared", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertEquals(2, servers.size)
        assertEquals("4shared (2160p_UHD)", servers[0].name)
        assertEquals("2160p_UHD", servers[0].metadata["quality"])
        assertEquals("480p_Custom", servers[1].metadata["quality"])
    }

    // --- 4. 4shared discovery ---
    @Test
    fun test04_fourSharedDiscovery() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "synth_token_1", "label": "4shared", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertEquals(1, servers.size)
        assertEquals("4shared", servers[0].metadata["provider"])
        assertEquals(ServerType.EMBED, servers[0].serverType)
        assertTrue(servers[0].requiresWebView)
    }

    // --- 5. 4shared in FHD ---
    @Test
    fun test05_fourSharedInFhd() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "synth_fhd", "label": "4shared", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertEquals(1, servers.size)
        assertEquals("FHD", servers[0].metadata["quality"])
        assertEquals("4shared (FHD)", servers[0].name)
    }

    // --- 6. 4shared in HD ---
    @Test
    fun test06_fourSharedInHd() {
        val json = """
            {
                "players": {
                    "HD": [
                        { "token": "synth_hd", "label": "4shared", "version": "dub", "lang": "ar" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertEquals(1, servers.size)
        assertEquals("HD", servers[0].metadata["quality"])
        assertEquals("dub", servers[0].metadata["version"])
        assertEquals("ar", servers[0].metadata["lang"])
    }

    // --- 7. 4shared missing ---
    @Test
    fun test07_fourSharedMissing() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "tok1", "label": "mega", "version": "sub", "lang": "jp" },
                        { "token": "tok2", "label": "videa", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertTrue("When 4shared is missing, parsed list must be empty", servers.isEmpty())
    }

    // --- 8. 4shared only in one quality ---
    @Test
    fun test08_fourSharedOnlyInOneQuality() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "tok1", "label": "mega", "version": "sub", "lang": "jp" }
                    ],
                    "HD": [
                        { "token": "synth_hd", "label": "4shared", "version": "sub", "lang": "jp" }
                    ],
                    "SD": [
                        { "token": "tok3", "label": "videa", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertEquals(1, servers.size)
        assertEquals("HD", servers[0].metadata["quality"])
    }

    // --- 9. Multiple 4shared qualities ---
    @Test
    fun test09_multipleFourSharedQualities() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "fhd_tok", "label": "4shared", "version": "sub", "lang": "jp" }
                    ],
                    "HD": [
                        { "token": "hd_tok", "label": "4shared", "version": "sub", "lang": "jp" }
                    ],
                    "SD": [
                        { "token": "sd_tok", "label": "4shared", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertEquals(3, servers.size)
        assertEquals("FHD", servers[0].metadata["quality"])
        assertEquals("HD", servers[1].metadata["quality"])
        assertEquals("SD", servers[2].metadata["quality"])
    }

    // --- 10. Ignore downloads ---
    @Test
    fun test10_ignoreDownloads() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "fhd_tok", "label": "4shared", "version": "sub", "lang": "jp" }
                    ]
                },
                "downloads": {
                    "FHD": [
                        { "token": "dl_4shared", "label": "4shared", "url": "https://4shared.com/download/123" },
                        { "token": "dl_gofile", "label": "gofile", "url": "https://gofile.io/123" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertEquals(1, servers.size)
        assertEquals("witanime:4shared:fhd:sub:jp", servers[0].id)
    }

    // --- 11. Ignore Mega ---
    @Test
    fun test11_ignoreMega() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "mega_tok", "label": "mega", "version": "sub", "lang": "jp" },
                        { "token": "mega_tok2", "label": "MEGA", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertTrue(servers.isEmpty())
    }

    // --- 12. Ignore Videa ---
    @Test
    fun test12_ignoreVidea() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "videa_tok", "label": "videa", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertTrue(servers.isEmpty())
    }

    // --- 13. Ignore OK ---
    @Test
    fun test13_ignoreOk() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "ok_tok", "label": "ok", "version": "sub", "lang": "jp" },
                        { "token": "ok_tok2", "label": "ok.ru", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertTrue(servers.isEmpty())
    }

    // --- 14. Ignore HGCloud ---
    @Test
    fun test14_ignoreHgCloud() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "hg_tok", "label": "hgcloud", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertTrue(servers.isEmpty())
    }

    // --- 15. Malformed JSON ---
    @Test
    fun test15_malformedJson() {
        val malformed = "{ this is not valid json <><> "
        val servers = scraper.parseSourcesJson(malformed, testEpisodeUrl, testBaseUrl)
        assertNotNull(servers)
        assertTrue(servers.isEmpty())
    }

    // --- 16. Missing players ---
    @Test
    fun test16_missingPlayers() {
        val json = """
            {
                "status": "success",
                "message": "no players here"
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertTrue(servers.isEmpty())
    }

    // --- 17. Missing token ---
    @Test
    fun test17_missingToken() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "label": "4shared", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertTrue(servers.isEmpty())
    }

    // --- 18. Missing label ---
    @Test
    fun test18_missingLabel() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "some_token", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertTrue(servers.isEmpty())
    }

    // --- 19. Empty token ---
    @Test
    fun test19_emptyToken() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "   ", "label": "4shared", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertTrue(servers.isEmpty())
    }

    // --- 20. Normalized ServerItem ---
    @Test
    fun test20_normalizedServerItem() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "synth_123", "label": "4shared", "version": "sub", "lang": "jp" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertEquals(1, servers.size)
        val item = servers[0]
        assertEquals("4shared (FHD)", item.name)
        assertEquals("https://www.4shared.com/video/synth_123", item.link)
        assertEquals(ServerType.EMBED, item.serverType)
        assertTrue(item.requiresWebView)
        assertFalse(item.isDirectStream)
        assertEquals(testEpisodeUrl, item.sourceUrl)
        assertEquals("4shared", item.metadata["provider"])
        assertEquals("FHD", item.metadata["quality"])
        assertEquals("sub", item.metadata["version"])
        assertEquals("jp", item.metadata["lang"])
    }

    // --- 21. Deterministic server ID ---
    @Test
    fun test21_deterministicServerId() {
        val json = """
            {
                "players": {
                    "FHD": [
                        { "token": "synth_abc", "label": "4shared", "version": "SUB", "lang": "JP" }
                    ]
                }
            }
        """.trimIndent()

        val servers = scraper.parseSourcesJson(json, testEpisodeUrl, testBaseUrl)
        assertEquals(1, servers.size)
        assertEquals("witanime:4shared:fhd:sub:jp", servers[0].id)
    }

    // --- 22. Extraction failure on unsupported server ---
    @Test
    fun test22_extractionFailure() = runBlocking {
        val unsupportedServer = ServerItem(
            id = "witanime:mega:fhd:sub:jp",
            name = "Mega (FHD)",
            link = "https://mega.nz/embed/test",
            metadata = mapOf("provider" to "mega")
        )
        val request = ExtractionRequest(serverItem = unsupportedServer)
        val session = ExtractionSession(extensionId = "witanime", scraperKey = "witanime", targetPageUrl = unsupportedServer.link)

        val result = scraper.extractStream(dummyExtension, session, request, null)
        assertTrue("Extracting unsupported server must fail", result.isFailure)
    }

    // --- 23. Media URL validation ---
    @Test
    fun test23_mediaUrlValidation() {
        // 4shared preview.mp4 URL
        val valid4SharedStream = "https://dc773.4shared.com/download/synthetic_id/preview.mp4"
        assertTrue(MediaStreamDetector.isMediaUrl(valid4SharedStream))
        assertTrue(MediaStreamDetector.validateMediaUrl(valid4SharedStream))

        // Rejection of non-https, non-media, or ad hosts
        assertFalse(MediaStreamDetector.validateMediaUrl("http://dc773.4shared.com/preview.mp4"))
        assertFalse(MediaStreamDetector.validateMediaUrl("https://dc773.4shared.com/preview.jpg"))
        assertFalse(MediaStreamDetector.validateMediaUrl("https://googleads.g.doubleclick.net/video.mp4"))
    }

    // --- 24. Successful normalized PlaybackSource ---
    @Test
    fun test24_successfulNormalizedPlaybackSource() = runBlocking {
        val direct4SharedStream = "https://dc773.4shared.com/download/synthetic_id/preview.mp4"
        val serverItem = ServerItem(
            id = "witanime:4shared:fhd:sub:jp",
            name = "4shared (FHD)",
            link = direct4SharedStream,
            metadata = mapOf("provider" to "4shared", "quality" to "FHD")
        )
        val request = ExtractionRequest(serverItem = serverItem)
        val session = ExtractionSession(extensionId = "witanime", scraperKey = "witanime", targetPageUrl = direct4SharedStream)

        val result = scraper.extractStream(dummyExtension, session, request, null)
        assertTrue(result.isSuccess)
        val extraction = result.getOrThrow()
        assertNotNull(extraction.playbackSource)
        val playback = extraction.playbackSource!!
        assertEquals(direct4SharedStream, playback.streamUrl)
        assertEquals("video/mp4", playback.mimeType)
        assertEquals(StreamProtocol.DIRECT_FILE, playback.protocol)
        assertEquals("https://www.4shared.com/", playback.headers["Referer"])
        assertNull("DownloadSource must remain null for playback-only proof of concept", extraction.downloadSource)
        assertNull("DownloadTask must remain null for playback-only proof of concept", extraction.downloadTask)
    }

    // --- Scraper Contract & HTML Parser Tests ---

    @Test
    fun scraperMetadata_isCorrect() {
        assertEquals("witanime", scraper.scraperKey)
        assertEquals(1, scraper.implementationVersion)
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.SEARCH))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.DETAILS))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.EPISODES))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.SERVER_DISCOVERY))
        assertTrue(scraper.supportedCapabilities.contains(ScraperCapability.VIDEO_EXTRACTION))
        assertFalse("Direct download capability must NOT be declared", scraper.supportedCapabilities.contains(ScraperCapability.DIRECT_DOWNLOAD))

        assertTrue(scraper.supportedContentTypes.contains(ContentType.ANIME))
        assertTrue(scraper.supportedContentTypes.contains(ContentType.SERIES))
        assertTrue(scraper.supportedContentTypes.contains(ContentType.MOVIE))
    }

    @Test
    fun parseSearchHtml_extractsAnimeItems() {
        val sampleHtml = """
            <html>
                <body>
                    <div class="anime-card-container">
                        <div class="anime-card">
                            <a href="https://witanime.com/anime/mushoku-tensei-iii/" title="Mushoku Tensei III">
                                <img src="https://witanime.com/posters/mushoku.jpg" />
                                <h3 class="anime-title">Mushoku Tensei III</h3>
                            </a>
                        </div>
                        <div class="anime-card">
                            <a href="/anime/bleach-sennen-kessen-hen/" title="Bleach">
                                <img src="/posters/bleach.jpg" />
                                <h3 class="anime-title">Bleach</h3>
                            </a>
                        </div>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleHtml, testBaseUrl)
        val results = scraper.parseSearchDocument(doc, testBaseUrl)
        assertEquals(2, results.size)
        assertEquals("Mushoku Tensei III", results[0].title)
        assertEquals("https://witanime.com/anime/mushoku-tensei-iii/", results[0].url)
        assertEquals("https://witanime.com/posters/mushoku.jpg", results[0].posterUrl)
        assertEquals(ContentType.ANIME, results[0].contentType)

        assertEquals("Bleach", results[1].title)
        assertEquals("https://witanime.com/anime/bleach-sennen-kessen-hen/", results[1].url)
        assertEquals("https://witanime.com/posters/bleach.jpg", results[1].posterUrl)
    }

    @Test
    fun parseDetailsHtml_extractsAnimeMetadataAndEpisodes() {
        val sampleHtml = """
            <html>
                <head><title>Mushoku Tensei III - WitAnime</title></head>
                <body>
                    <h1 class="anime-details-title">Mushoku Tensei III</h1>
                    <div class="anime-story">قصة الأنمي الموسم الثالث</div>
                    <img class="thumbnail" src="https://witanime.com/posters/mushoku.jpg" />
                    <div class="anime-genres">
                        <a href="/genre/action">Action</a>
                        <a href="/genre/fantasy">Fantasy</a>
                    </div>
                    <div class="all-episodes">
                        <a href="https://witanime.com/episode/mushoku-tensei-iii-episode-14/">الحلقة 14</a>
                        <a href="https://witanime.com/episode/mushoku-tensei-iii-episode-13/">الحلقة 13</a>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(sampleHtml, testBaseUrl)
        val details = scraper.parseDetailsDocument(doc, "https://witanime.com/anime/mushoku-tensei-iii/", testBaseUrl)
        assertEquals("Mushoku Tensei III", details.title)
        assertEquals("قصة الأنمي الموسم الثالث", details.description)
        assertEquals(2, details.genres.size)
        assertEquals(2, details.episodes.size)
        assertEquals(14, details.episodes[0].episodeNumber)
        assertEquals("الحلقة 14", details.episodes[0].title)
    }
}
