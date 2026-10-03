package com.example.extension.managed.scraper

import android.util.Log
import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.CredentialProjector
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.web.ControlledWebViewEngine
import com.example.extension.managed.web.MediaStreamDetector
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Witanime Managed Site Scraper (Proof of Concept).
 * Implements the Witanime -> /sources -> dynamic players -> 4shared embed -> PlaybackSource pipeline.
 *
 * Supported scope:
 * - Capabilities: SEARCH, DETAILS, EPISODES, SERVER_DISCOVERY, VIDEO_EXTRACTION.
 * - Providers: 4shared ONLY (Mega, Videa, OK, HGCloud, etc. ignored).
 * - Direct download: Strictly DISABLED (downloads block in /sources ignored).
 * - Decoupled: Zero reflection, zero dynamic loading, zero credentials in player.
 */
class WitanimeScraper : BaseSiteScraper {

    override val scraperKey: String = "witanime"
    override val implementationVersion: Int = 1

    override val supportedCapabilities: Set<ScraperCapability> = setOf(
        ScraperCapability.SEARCH,
        ScraperCapability.DETAILS,
        ScraperCapability.EPISODES,
        ScraperCapability.SERVER_DISCOVERY,
        ScraperCapability.VIDEO_EXTRACTION
    )

    override val supportedContentTypes: Set<ContentType> = setOf(
        ContentType.ANIME,
        ContentType.SERIES,
        ContentType.MOVIE
    )

    private val userAgent = MediaStreamDetector.STANDARD_USER_AGENT

    private fun logDiag(msg: String) {
        try {
            Log.i("WITANIME_DIAG", msg)
        } catch (_: Throwable) {
            println("DIAG: [WITANIME] $msg")
        }
    }

    private fun isCloudflareDocument(doc: org.jsoup.nodes.Document): Boolean {
        val title = doc.title().lowercase()
        return title.contains("just a moment") ||
                title.contains("cloudflare") ||
                title.contains("attention required") ||
                doc.select(".cf-browser-verification, #cf-wrapper, #challenge-form, #turnstile-wrapper").isNotEmpty()
    }

    private fun isCloudflareException(e: Throwable): Boolean {
        if (e is org.jsoup.HttpStatusException && (e.statusCode == 403 || e.statusCode == 503)) {
            return true
        }
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("403") || msg.contains("503") || msg.contains("cloudflare") || msg.contains("just a moment")
    }

    private fun resolveUrl(baseUrl: String, pathOrUrl: String): String {
        val clean = pathOrUrl.trim()
        if (clean.startsWith("http://") || clean.startsWith("https://")) {
            return clean
        }
        if (clean.startsWith("//")) {
            return "https:$clean"
        }
        val base = baseUrl.trimEnd('/')
        return if (clean.startsWith("/")) "$base$clean" else "$base/$clean"
    }

    // --- Search ---

    override suspend fun search(
        extension: ManagedExtension,
        request: SearchRequest
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        if (request.query.isBlank()) {
            return@withContext Result.success(SearchResult(items = emptyList(), page = request.page))
        }

        val baseUrl = extension.baseUrl.trimEnd('/')
        val encodedQuery = URLEncoder.encode(request.query.trim(), "UTF-8")
        val searchUrl = if (request.page > 1) {
            "$baseUrl/page/${request.page}/?search_param=animes&s=$encodedQuery"
        } else {
            "$baseUrl/?search_param=animes&s=$encodedQuery"
        }

        try {
            val doc = Jsoup.connect(searchUrl)
                .userAgent(userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer("$baseUrl/")
                .timeout(10000)
                .get()

            if (isCloudflareDocument(doc)) {
                logDiag("CLOUDFLARE_DETECTED on search: $searchUrl")
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on Witanime search"))
            }

            val items = parseSearchDocument(doc, baseUrl)
            val hasNextPage = doc.select("a.next, .pagination .next, a.next-page, .nav-previous a").isNotEmpty()

            Result.success(
                SearchResult(
                    items = items,
                    page = request.page,
                    hasNextPage = hasNextPage
                )
            )
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                logDiag("CLOUDFLARE_DETECTED on search exception: ${e.message}")
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on Witanime search: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("Witanime search failed: ${e.message}", e))
            }
        }
    }

    internal fun parseSearchDocument(doc: org.jsoup.nodes.Document, baseUrl: String): List<SearchMediaItem> {
        val elements = doc.select(
            "div.anime-card-container, " +
            "div.anime-card, " +
            "div.anime-item, " +
            "div.post, " +
            "div.featured-anime-wrapper .anime-info, " +
            "li.anime-item, " +
            "div.col-md-3, " +
            "div.anime-list-content, " +
            "div.anime-container, " +
            "a[href*='/anime/']"
        )

        val items = mutableListOf<SearchMediaItem>()
        for (el in elements) {
            val linkEl = if (el.tagName() == "a") el else el.selectFirst("a[href*='/anime/'], a") ?: continue
            val href = linkEl.attr("href").trim()
            if (href.isBlank()) continue
            val fullUrl = resolveUrl(baseUrl, href)

            val title = linkEl.attr("title").trim()
                .ifBlank { el.selectFirst(".anime-title, .title, h3, h2, h1")?.text()?.trim() }
                ?: linkEl.text().trim()

            if (title.isBlank()) continue

            val poster = el.selectFirst("img")?.let { img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { img.attr("data-lazy-src") }
                if (src.isNotBlank()) resolveUrl(baseUrl, src) else null
            }

            val slug = fullUrl.trimEnd('/').substringAfterLast('/')
            val id = if (slug.isNotBlank()) slug else "witanime-${items.size + 1}"

            items.add(
                SearchMediaItem(
                    id = id,
                    title = title,
                    posterUrl = poster,
                    url = fullUrl,
                    contentType = ContentType.ANIME
                )
            )
        }
        return items.distinctBy { it.url }
    }

    // --- Details ---

    override suspend fun getDetails(
        extension: ManagedExtension,
        url: String
    ): Result<MediaDetailsResult> = withContext(Dispatchers.IO) {
        val baseUrl = extension.baseUrl.trimEnd('/')
        val targetUrl = resolveUrl(baseUrl, url)

        try {
            val doc = Jsoup.connect(targetUrl)
                .userAgent(userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer("$baseUrl/")
                .timeout(10000)
                .get()

            if (isCloudflareDocument(doc)) {
                logDiag("CLOUDFLARE_DETECTED on details: $targetUrl")
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on Witanime details"))
            }

            val details = parseDetailsDocument(doc, targetUrl, baseUrl)
            Result.success(details)
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                logDiag("CLOUDFLARE_DETECTED on details exception: ${e.message}")
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on Witanime details: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("Witanime getDetails failed: ${e.message}", e))
            }
        }
    }

    internal fun parseDetailsDocument(doc: org.jsoup.nodes.Document, targetUrl: String, baseUrl: String): MediaDetailsResult {
        val title = doc.selectFirst("h1.anime-details-title, h1.entry-title, .anime-title, h1")?.text()?.trim()
            ?: doc.title().substringBefore("-").trim()

        val description = doc.selectFirst(".anime-story, .story, .entry-content, p.anime-story")?.text()?.trim()

        val poster = doc.selectFirst("img.thumbnail, .anime-poster img, .anime-thumbnail img, .anime-info-thumbnail img, .poster img")?.let { img ->
            val src = img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { img.attr("data-lazy-src") }
            if (src.isNotBlank()) resolveUrl(baseUrl, src) else null
        }

        val genres = doc.select(".anime-genres a, .genres a, .genre a").map { it.text().trim() }.filter { it.isNotBlank() }

        val ratingText = doc.selectFirst(".anime-rating, .rating, .rate")?.text()?.trim()
        val rating = ratingText?.toDoubleOrNull()

        val yearText = doc.selectFirst(".anime-year, .year, .release-date")?.text()?.trim()

        val episodes = parseEpisodesDocument(doc, baseUrl, seasonNumber = 1)

        val slug = targetUrl.trimEnd('/').substringAfterLast('/')
        val id = if (slug.isNotBlank()) slug else "witanime-details"

        return MediaDetailsResult(
            id = id,
            title = title,
            description = description,
            posterUrl = poster,
            contentType = ContentType.ANIME,
            episodes = episodes,
            seasons = listOf(1),
            url = targetUrl,
            year = yearText,
            genres = genres,
            rating = rating
        )
    }

    // --- Episodes ---

    override suspend fun getEpisodes(
        extension: ManagedExtension,
        seriesUrl: String,
        season: Int
    ): Result<List<EpisodeItem>> = withContext(Dispatchers.IO) {
        val baseUrl = extension.baseUrl.trimEnd('/')
        val targetUrl = resolveUrl(baseUrl, seriesUrl)

        try {
            val doc = Jsoup.connect(targetUrl)
                .userAgent(userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .referrer("$baseUrl/")
                .timeout(10000)
                .get()

            if (isCloudflareDocument(doc)) {
                logDiag("CLOUDFLARE_DETECTED on episodes: $targetUrl")
                return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge detected on Witanime episodes"))
            }

            val episodes = parseEpisodesDocument(doc, baseUrl, seasonNumber = season)
            Result.success(episodes)
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                logDiag("CLOUDFLARE_DETECTED on episodes exception: ${e.message}")
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on Witanime episodes: ${e.message}"))
            } else {
                Result.failure(ExtensionError.ExtractionFailed("Witanime getEpisodes failed: ${e.message}", e))
            }
        }
    }

    internal fun parseEpisodesDocument(doc: org.jsoup.nodes.Document, baseUrl: String, seasonNumber: Int): List<EpisodeItem> {
        val links = doc.select(
            "div.episodes-card-container a, " +
            "div.all-episodes a, " +
            "div.episodes-list a, " +
            "div.episodes-container a, " +
            "ul.episodes-list li a, " +
            "a[href*='/episode/'], " +
            "a[href*='/watch/']"
        )

        val episodes = mutableListOf<EpisodeItem>()
        for (a in links) {
            val href = a.attr("href").trim()
            if (href.isBlank()) continue
            val fullUrl = resolveUrl(baseUrl, href)

            val rawTitle = a.text().trim().ifBlank { a.attr("title").trim() }
            val epNum = Regex("""(?:\D|^)(\d+)(?:\D|$)""").findAll(rawTitle)
                .lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-(\d+)/?$""").find(fullUrl)?.groupValues?.get(1)?.toIntOrNull()
                ?: (episodes.size + 1)

            val title = if (rawTitle.isNotBlank()) rawTitle else "الحلقة $epNum"
            val id = fullUrl.trimEnd('/').substringAfterLast('/')

            episodes.add(
                EpisodeItem(
                    id = id,
                    title = title,
                    episodeNumber = epNum,
                    seasonNumber = seasonNumber,
                    url = fullUrl
                )
            )
        }
        return episodes.distinctBy { it.url }
    }

    // --- Server Discovery & /sources Parsing ---

    override suspend fun discoverServers(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ServerDiscoveryRequest,
        webEngine: WebExtractionEngine?
    ): Result<ServerDiscoveryResult> = withContext(Dispatchers.IO) {
        val baseUrl = extension.baseUrl.trimEnd('/')
        val targetUrl = request.targetUrl.trim()
        val sourcesUrl = if (targetUrl.trimEnd('/').endsWith("/sources")) {
            targetUrl
        } else {
            "${targetUrl.trimEnd('/')}/sources"
        }

        logDiag("SOURCES_REQUESTED: url='$sourcesUrl'")

        try {
            val responseText = Jsoup.connect(sourcesUrl)
                .ignoreContentType(true)
                .userAgent(userAgent)
                .header("Accept", "application/json, text/plain, */*")
                .referrer(targetUrl)
                .timeout(10000)
                .execute()
                .body()

            val discovered = parseSourcesJson(
                jsonString = responseText,
                episodeUrl = targetUrl,
                baseUrl = baseUrl
            )

            if (discovered.isEmpty()) {
                logDiag("FOURSHARED_NOT_FOUND: No 4shared player sources discovered in /sources response")
                return@withContext Result.failure(ExtensionError.MediaNotFound(sourcesUrl))
            }

            Result.success(ServerDiscoveryResult(discovered, request.targetUrl))
        } catch (e: Exception) {
            if (isCloudflareException(e)) {
                logDiag("CLOUDFLARE_DETECTED on /sources: ${e.message}")
                Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on Witanime /sources: ${e.message}"))
            } else {
                logDiag("FOURSHARED_NOT_FOUND: Error requesting /sources: ${e.message}")
                Result.failure(ExtensionError.ExtractionFailed("Witanime discoverServers failed: ${e.message}", e))
            }
        }
    }

    /**
     * Parses dynamic /sources JSON response.
     * Enforces canonical rules:
     * - Only players block inspected.
     * - downloads block strictly ignored.
     * - Qualities read dynamically (never assumed static FHD/HD/SD).
     * - Only label.equalsIgnoreCase("4shared") discovered.
     * - Providers Mega, Videa, OK, HGCloud ignored.
     * - Token resolved to embed URL; token is NEVER logged.
     * - Deterministic ServerItem IDs generated.
     */
    internal fun parseSourcesJson(
        jsonString: String,
        episodeUrl: String,
        baseUrl: String
    ): List<ServerItem> {
        val root = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            logDiag("SOURCES_PARSED: Malformed JSON encountered: ${e.message}")
            return emptyList()
        }

        if (!root.has("players")) {
            logDiag("SOURCES_PARSED: No 'players' object found in JSON")
            return emptyList()
        }

        val playersObj = root.optJSONObject("players")
        if (playersObj == null) {
            logDiag("SOURCES_PARSED: 'players' field is not a valid JSONObject")
            return emptyList()
        }

        val discovered = mutableListOf<ServerItem>()
        val parsedQualities = mutableListOf<String>()
        val keys = playersObj.keys()

        while (keys.hasNext()) {
            val qualityKey = keys.next()
            parsedQualities.add(qualityKey)

            val sourcesArray = playersObj.optJSONArray(qualityKey) ?: continue
            for (i in 0 until sourcesArray.length()) {
                val sourceObj = sourcesArray.optJSONObject(i) ?: continue

                // Check label existence & value
                if (!sourceObj.has("label")) continue
                val label = sourceObj.optString("label", "").trim()

                // Rule: Strictly 4shared only
                if (!label.equals("4shared", ignoreCase = true)) {
                    continue
                }

                // Check token existence & validity
                if (!sourceObj.has("token")) continue
                val token = sourceObj.optString("token", "").trim()
                if (token.isBlank()) continue

                val version = sourceObj.optString("version", "sub").trim()
                val lang = sourceObj.optString("lang", "jp").trim()

                // Deterministic Server ID
                val deterministicId = "witanime:4shared:${qualityKey.lowercase()}:${version.lowercase()}:${lang.lowercase()}"

                // Token resolution to 4shared embed URL
                val resolvedEmbedUrl = resolve4SharedEmbedUrl(token, baseUrl)

                // Canonical metadata (Strictly no sensitive token in metadata/logs)
                val metadata = mapOf(
                    "provider" to "4shared",
                    "quality" to qualityKey,
                    "version" to version,
                    "lang" to lang
                )

                val serverItem = ServerItem(
                    id = deterministicId,
                    name = "4shared ($qualityKey)",
                    link = resolvedEmbedUrl,
                    isDirectStream = false,
                    sourceUrl = episodeUrl,
                    serverType = ServerType.EMBED,
                    requiresWebView = true,
                    extraData = metadata,
                    metadata = metadata
                )

                discovered.add(serverItem)
                logDiag("FOURSHARED_FOUND: quality=$qualityKey, version=$version, lang=$lang (Server ID=$deterministicId)")
            }
        }

        logDiag("SOURCES_PARSED: Discovered ${discovered.size} 4shared sources across qualities: $parsedQualities")
        return discovered
    }

    /**
     * Resolves Witanime source token to normalized 4shared embed URL.
     * Safely handles IDs, relative paths, full URLs, and base64 payloads without leaking credentials.
     */
    internal fun resolve4SharedEmbedUrl(token: String, baseUrl: String): String {
        val clean = token.trim()
        val resolved = when {
            clean.startsWith("https://www.4shared.com/") || clean.startsWith("https://4shared.com/") -> clean
            clean.startsWith("http://www.4shared.com/") || clean.startsWith("http://4shared.com/") -> clean.replace("http://", "https://")
            clean.startsWith("//www.4shared.com/") || clean.startsWith("//4shared.com/") -> "https:$clean"
            clean.startsWith("/video/") || clean.startsWith("video/") -> "https://www.4shared.com/${clean.removePrefix("/")}"
            clean.startsWith("/web/embed/file/") || clean.startsWith("web/embed/file/") -> "https://www.4shared.com/${clean.removePrefix("/")}"
            clean.startsWith("/embed/") || clean.startsWith("embed/") -> "https://www.4shared.com/${clean.removePrefix("/")}"
            clean.startsWith("/") -> "https://www.4shared.com$clean"
            else -> {
                val decoded = safeBase64Decode(clean)
                if (decoded != null && (decoded.startsWith("https://") || decoded.contains("4shared.com"))) {
                    resolve4SharedEmbedUrl(decoded, baseUrl)
                } else {
                    "https://www.4shared.com/video/$clean"
                }
            }
        }
        logDiag("FOURSHARED_EMBED_RESOLVED: embedUrl='$resolved'")
        return resolved
    }

    private fun safeBase64Decode(str: String): String? {
        return try {
            val bytes = java.util.Base64.getDecoder().decode(str)
            String(bytes, Charsets.UTF_8).trim()
        } catch (_: Throwable) {
            try {
                val bytes = android.util.Base64.decode(str, android.util.Base64.DEFAULT)
                String(bytes, Charsets.UTF_8).trim()
            } catch (_: Throwable) {
                null
            }
        }
    }

    // --- Stream Extraction ---

    override suspend fun extractStream(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ExtractionRequest,
        webEngine: WebExtractionEngine?
    ): Result<ExtractionResult> = withContext(Dispatchers.IO) {
        val serverItem = request.serverItem
        val provider = serverItem.metadata["provider"] ?: ""
        val is4Shared = provider.equals("4shared", ignoreCase = true) || serverItem.id.contains("4shared")

        if (!is4Shared) {
            logDiag("EXTRACTION_FAILED: Unsupported server provider '${serverItem.name}'. Only 4shared is supported.")
            return@withContext Result.failure(ExtensionError.ExtractionFailed("Only 4shared is supported in this phase"))
        }

        val embedUrl = serverItem.link
        logDiag("FOURSHARED_EMBED_RESOLVED: targetEmbedUrl='$embedUrl'")

        // Fast path: if link is already a direct media file
        if (MediaStreamDetector.isMediaUrl(embedUrl)) {
            val normalized = MediaStreamDetector.normalizeStreamUrl(embedUrl)
            if (MediaStreamDetector.validateMediaUrl(normalized)) {
                logDiag("FOURSHARED_MEDIA_DETECTED: Direct media stream discovered: $normalized")
                val headers = mapOf("Referer" to "https://www.4shared.com/")
                val playbackSource = MediaStreamDetector.createPlaybackSource(normalized, headers)
                logDiag("PLAYBACK_SOURCE_CREATED: protocol=${playbackSource.protocol}, mimeType=${playbackSource.mimeType}")
                return@withContext Result.success(ExtractionResult(playbackSource = playbackSource))
            }
        }

        // Headless Controlled WebView extraction
        if (webEngine != null) {
            val script = ControlledWebViewEngine.getPublicEmbedMediaExtractionScript()
            val streamResult = webEngine.extractStreamUrl(
                targetUrl = embedUrl,
                targetServerId = serverItem.id,
                script = script,
                timeoutMs = request.timeoutMs,
                expectedOrigin = "https://www.4shared.com"
            )

            if (streamResult.isSuccess) {
                val detected = streamResult.getOrThrow()
                val normalized = MediaStreamDetector.normalizeStreamUrl(detected)
                if (MediaStreamDetector.validateMediaUrl(normalized)) {
                    logDiag("FOURSHARED_MEDIA_DETECTED: Intercepted media request: $normalized")
                    val headers = mapOf("Referer" to "https://www.4shared.com/")
                    val playbackSource = MediaStreamDetector.createPlaybackSource(normalized, headers)
                    logDiag("PLAYBACK_SOURCE_CREATED: streamUrl=$normalized, mimeType=${playbackSource.mimeType}")
                    return@withContext Result.success(ExtractionResult(playbackSource = playbackSource))
                } else {
                    logDiag("FOURSHARED_MEDIA_NOT_DETECTED: Candidate rejected by validateMediaUrl: '$detected'")
                    return@withContext Result.failure(ExtensionError.ExtractionFailed("Discovered media stream URL failed validation: $detected"))
                }
            } else {
                val err = streamResult.exceptionOrNull()
                logDiag("FOURSHARED_MEDIA_NOT_DETECTED: extractStreamUrl failed: ${err?.message}")
                if (err != null && isCloudflareException(err)) {
                    logDiag("CLOUDFLARE_DETECTED during 4shared embed extraction")
                    return@withContext Result.failure(ExtensionError.CloudflareChallenge("Cloudflare challenge encountered on 4shared: ${err.message}"))
                }
                return@withContext Result.failure(err ?: ExtensionError.ExtractionFailed("4shared media stream extraction failed"))
            }
        }

        logDiag("FOURSHARED_EMBED_FAILED: WebExtractionEngine is null")
        Result.failure(ExtensionError.ExtractionFailed("WebExtractionEngine is required for 4shared embed extraction"))
    }
}
