# CineStream Managed Extension Runtime
## Official Extension Developer Contract (Phase 6.6)

---

## 0. Executive Summary & Purpose

This document establishes the **authoritative, enforceable developer contract** for building and bundling managed scrapers in the **CineStream Users App**.

### The Core Architectural Principle
> **The Scraper is a Site Adapter Only.**
>
> A scraper must only answer one single question:
> ```text
> "How does this specific website expose its content?"
> ```
> Everything else—network checks, lifecycle management, user preferences, candidate fallback, session tracking, security policies, challenge negotiation, player preparation, download task dispatching, and UI rendering—belongs entirely to the **Managed Runtime**.

---

## 1. Architectural Boundary & Control Flow

```text
┌──────────────────────────────────────────────────────────────────────────────────┐
│                                   CineStream UI                                  │
│             (Compose screens, Search bar, Details screen, Dialogs)               │
└────────────────────────────────────────┬─────────────────────────────────────────┘
                                         │ Requests (query / url / media ID)
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                            ManagedMediaOrchestrator                              │
│         (High-level orchestration, Firestore candidate retrieval, outcomes)      │
└────────────────────────────────────────┬─────────────────────────────────────────┘
                                         │ Normalized Requests
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                   DefaultControlledManagedExtensionRuntime                       │
│    (Network fast-fail, Compatibility gates, Candidate prioritization, Fallback)  │
└────────────────────────────────────────┬─────────────────────────────────────────┘
                                         │ Injects ExtensionContext
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                     ExtensionContext (Sandboxed Environment)                     │
│    (Base URL, Session ID, Connectivity, Host Policy, WebEngine, ChallengeHandler)│
└────────────────────────────────────────┬─────────────────────────────────────────┘
                                         │ Calls Contract Operations
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                      BaseSiteScraper Implementation (YOU)                        │
│             (Translates website DOM / JSON into Canonical Data Models)           │
└────────────────────────────────────────┬─────────────────────────────────────────┘
                                         │ Returns Canonical Results
                                         ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                         Handoff Adapters (Sanitization)                          │
│     PlayerHandoffAdapter               │      DownloaderHandoffAdapter           │
│     (Prepares PlayerInput)             │      (Strips cookies, sanitizes)        │
└────────────────────────────────────────┴─────────────────────────────────────────┘
```

---

## 2. What the Scraper Owns vs. What the Managed Runtime Owns

| Responsibility | Scraper Role | Managed Runtime Role |
| :--- | :--- | :--- |
| **Search** | Generates search URL, parses results DOM/JSON, maps to `SearchResult` | Connectivity check, candidate sorting, fallback iteration |
| **Media Details** | Fetches media page, parses title, poster, year, rating, description | Caching, error classification, model normalization |
| **Episodes List** | Parses season tabs and episode links, maps to `List<EpisodeItem>` | Content type compatibility, validation |
| **Server Discovery**| Extracts embed/direct links, assigns server names, tags embed vs direct | Host validation (`TrustedEmbedHostPolicy`), fallback routing |
| **Media Extraction** | Resolves actual stream (`.m3u8` or `.mp4`), parses quality variants | Safe bridge messaging (`SafeScraperBridge`), timeout handling |
| **Cloudflare Handling** | Reports `ExtensionError.CloudflareChallenge` or uses `context.challengeHandler` | Intercepts challenge, coordinates solver WebView, updates cookies |
| **Video Playback** | **FORBIDDEN** (Scraper produces only `PlaybackSource`) | `PlayerHandoffAdapter` maps source to `PlayerInput` for ExoPlayer |
| **File Downloading** | **FORBIDDEN** (Scraper produces only `DownloadSource`) | `DownloaderHandoffAdapter` sanitizes headers and triggers download |
| **Network Gatekeeping**| **FORBIDDEN** (Must not bypass offline fast-fail) | `ConnectivityMonitor` fast-fails before invoking scraper |
| **Firebase / Cloud** | **FORBIDDEN** (Zero Firebase/Cloudinary imports) | App repositories manage Firestore and Cloudinary completely |
| **UI Components** | **FORBIDDEN** (Zero Compose, Activity, Dialog, Toast imports) | App UI layer handles presentation |
| **Dynamic Code Loading**| **FORBIDDEN** (Zero `DexClassLoader`, `PathClassLoader`, reflection) | APK bundling only; code is statically compiled and audited |

---

## 3. The `BaseSiteScraper` Contract Interface

Every scraper bundled into CineStream must implement `com.example.extension.managed.contract.BaseSiteScraper`:

```kotlin
package com.example.extension.managed.contract

import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.runtime.context.ExtensionContext
import com.example.extension.managed.web.WebExtractionEngine

interface BaseSiteScraper {
    /** Unique lowercase key matching Firestore extension configuration (e.g. "egydead") */
    val scraperKey: String

    /** Monotonically increasing implementation version for APK version tracking */
    val implementationVersion: Int

    /** Set of capabilities supported by this scraper implementation */
    val supportedCapabilities: Set<ScraperCapability>

    /** Set of media content types supported (MOVIE, SERIES, ANIME) */
    val supportedContentTypes: Set<ContentType>

    // --- Core Operations ---

    suspend fun search(
        extension: ManagedExtension,
        request: SearchRequest
    ): Result<SearchResult>

    suspend fun getDetails(
        extension: ManagedExtension,
        url: String
    ): Result<MediaDetailsResult>

    suspend fun getEpisodes(
        extension: ManagedExtension,
        seriesUrl: String,
        season: Int
    ): Result<List<EpisodeItem>>

    suspend fun discoverServers(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ServerDiscoveryRequest,
        webEngine: WebExtractionEngine? = null
    ): Result<ServerDiscoveryResult>

    suspend fun extractStream(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ExtractionRequest,
        webEngine: WebExtractionEngine? = null
    ): Result<ExtractionResult>

    // --- Modern Ergonomic Context-Based Overloads (Provided by interface defaults) ---

    suspend fun search(
        context: ExtensionContext,
        request: SearchRequest
    ): Result<SearchResult>

    suspend fun getDetails(
        context: ExtensionContext,
        request: DetailsRequest
    ): Result<MediaDetailsResult>

    suspend fun getEpisodes(
        context: ExtensionContext,
        request: EpisodesRequest
    ): Result<EpisodeList>

    suspend fun discoverServers(
        context: ExtensionContext,
        request: ServerDiscoveryRequest
    ): Result<ServerDiscoveryResult>

    suspend fun extractStream(
        context: ExtensionContext,
        request: ExtractionRequest
    ): Result<ExtractionResult>
}
```

---

## 4. The Canonical Data Contract

All inputs and outputs are strictly typed canonical data classes located in `com.example.extension.managed.model`:

### 4.1 Search Contract
```kotlin
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
```

### 4.2 Details & Episodes Contract
```kotlin
data class DetailsRequest(
    val url: String,
    val id: String = "",
    val contentType: ContentType? = null,
    val headers: Map<String, String> = emptyMap()
)

data class MediaDetailsResult(
    val id: String,
    val title: String,
    val description: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val contentType: ContentType,
    val episodes: List<EpisodeItem> = emptyList(),
    val seasons: List<Int> = emptyList(),
    val url: String,
    val releaseDate: String? = null,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val runtime: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class EpisodesRequest(
    val seriesUrl: String,
    val season: Int = 1,
    val seriesId: String = "",
    val headers: Map<String, String> = emptyMap()
)

data class EpisodeItem(
    val id: String,
    val title: String,
    val episodeNumber: Int,
    val seasonNumber: Int = 1,
    val url: String,
    val thumbnailUrl: String? = null,
    val detailReference: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
```

### 4.3 Server Discovery Contract
```kotlin
enum class ServerType {
    DIRECT,
    EMBED,
    HLS,
    DASH,
    UNKNOWN
}

data class ServerItem(
    val id: String,
    val name: String,
    val link: String,
    val isDirectStream: Boolean = false,
    val extraData: Map<String, String> = emptyMap(),
    val sourceUrl: String = link,
    val serverType: ServerType = if (isDirectStream) ServerType.DIRECT else ServerType.EMBED,
    val requiresWebView: Boolean = !isDirectStream,
    val capabilities: Set<String> = emptySet(),
    val metadata: Map<String, String> = extraData
)

data class ServerDiscoveryRequest(
    val targetUrl: String,
    val mediaTitle: String = "",
    val isMovie: Boolean = true,
    val season: Int = 1,
    val episode: Int = 1,
    val headers: Map<String, String> = emptyMap()
)

data class ServerDiscoveryResult(
    val servers: List<ServerItem>,
    val sourcePageUrl: String,
    val metadata: Map<String, String> = emptyMap()
)
```

### 4.4 Media Extraction & Quality Variants Contract
```kotlin
data class MediaVariant(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val label: String? = null,
    val mimeType: String? = null,
    val protocol: StreamProtocol = StreamProtocol.UNKNOWN,
    val headers: Map<String, String> = emptyMap(),
    val isDefault: Boolean = false
) {
    val displayQuality: String
        get() = when {
            height != null && height > 0 -> "${height}p"
            !label.isNullOrBlank() -> label
            else -> "Auto"
        }
}

data class PlaybackSource(
    val streamUrl: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val drmScheme: String? = null,
    val protocol: StreamProtocol = StreamProtocol.UNKNOWN,
    val variants: List<MediaVariant> = emptyList(),
    val qualities: List<QualitySource> = if (variants.isNotEmpty()) variants.map { it.toQualitySource() } else emptyList(),
    val durationMs: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class DownloadSource(
    val url: String,
    val filename: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val protocol: StreamProtocol = StreamProtocol.DIRECT_FILE,
    val estimatedBytes: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class ExtractionRequest(
    val serverItem: ServerItem,
    val mediaTitle: String = "",
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 15_000L
)

data class ExtractionResult(
    val playbackSource: PlaybackSource?,
    val downloadTask: DownloadTaskRequest? = null,
    val downloadSource: DownloadSource? = null,
    val variants: List<MediaVariant> = playbackSource?.variants ?: emptyList(),
    val metadata: Map<String, String> = emptyMap()
)
```

---

## 5. Strict Quality Resolution Invariant

> [!CRITICAL]
> **NEVER FABRICATE "1080p" AS A DEFAULT RESOLUTION.**
>
> If a video stream does not provide explicit height metadata (e.g. `1080`, `720`, `480`), the label **MUST** resolve to `"Auto"` or null.
>
> ❌ **FORBIDDEN**: Defaulting unknown master playlist streams to `height = 1080` or label `"1080p"`.
>
> ✅ **REQUIRED**: `MediaVariantParser.fromQualityInfo(info)` or setting `height = null`, giving display quality `"Auto"`.

---

## 6. The Execution Environment: `ExtensionContext`

When the Managed Runtime executes a scraper, it passes an `ExtensionContext` containing sandboxed runtime services:

```kotlin
data class ExtensionContext(
    val extensionId: String,
    val scraperKey: String,
    val baseUrl: String,
    val runtimeApiVersion: Int,
    val connectivity: ConnectivityMonitor,
    val session: ExtractionSession,
    val webEngine: WebExtractionEngine? = null,
    val challengeHandler: ChallengeHandler? = null,
    val hostPolicy: TrustedEmbedHostPolicy = TrustedEmbedHostPolicy
)
```

### Services Available to the Scraper:
1. `baseUrl`: The verified origin URL configured for this extension. Scrapers must use `baseUrl` rather than hardcoding domain names that might rotate.
2. `session`: Unique extraction session with tracking metadata.
3. `connectivity`: Allows checking network availability before heavy parsing (`connectivity.isConnected()`).
4. `webEngine`: Optional headless WebView engine for pages requiring JavaScript execution.
5. `challengeHandler`: Solution coordinator when Cloudflare challenges are detected.
6. `hostPolicy`: Evaluates destination URLs against the security whitelist (`hostPolicy.isNavigationAllowed(uri)`).

---

## 7. Error Handling Taxonomy: `ExtensionError`

Scrapers must wrap failures in standardized `ExtensionError` instances so the `FallbackManager` can make optimal failover decisions:

```kotlin
sealed class ExtensionError(
    val code: String,
    override val message: String,
    val isRecoverable: Boolean = true
) : Exception("[$code] $message")
```

### Classification Reference:

| Error Class | Code | Recoverable? | Recommended Action by Runtime |
| :--- | :--- | :--- | :--- |
| `NoInternet` | `NO_INTERNET` | **No** (Fatal) | Fast-fails the entire pipeline; informs user to check connection. |
| `Timeout` | `TIMEOUT` | **Yes** | Attempts fallback extension or alternative server. |
| `MediaNotFound` | `MEDIA_NOT_FOUND` | **Yes** | Attempts fallback extension. |
| `ExtractionFailed` | `EXTRACTION_FAILED` | **Yes** | Attempts next server item or fallback extension. |
| `ParseError` | `PARSE_ERROR` | **Yes** | Website DOM changed; logs warning, tries alternative source. |
| `CloudflareChallenge`| `CLOUDFLARE_CHALLENGE`| **Yes** | Hands off to `ChallengeHandler` or prompts user verification. |
| `InvalidMedia` | `INVALID_MEDIA` | **Yes** | Stream protocol or codec unsupported; skips to next server. |
| `IncompatibleAppVersion`| `INCOMPATIBLE_APP_VERSION`| **No** (Fatal) | Rejects extension; requires app update. |
| `IncompatibleRuntime` | `INCOMPATIBLE_RUNTIME` | **No** (Fatal) | Rejects extension; requires runtime API update. |
| `SecurityViolation` | `SECURITY_VIOLATION` | **No** (Fatal) | Blocks execution immediately; logs security incident. |
| `InvalidConfiguration` | `INVALID_CONFIGURATION` | **No** (Fatal) | Extension metadata in Firestore is invalid. |
| `UserDisabled` | `USER_DISABLED` | **No** (Fatal) | User toggled extension off in settings. |

---

## 8. Security & Sandbox Boundary Guarantees

All scrapers must operate strictly within the security sandbox:

### 8.1 TrustedEmbedHostPolicy
- **HTTPS Only**: Cleartext HTTP (`http://`) is rejected for embeds and navigations.
- **Forbidden Schemes**: `file://`, `content://`, `javascript://`, `data:`, `intent:` are strictly blocked.
- **No Localhost / Private IPs**: `127.0.0.1`, `localhost`, `10.0.0.0/8`, `192.168.0.0/16`, `172.16.0.0/12`, `169.254.169.254`, `*.local`, `*.internal` are strictly blocked (prevents SSRF attacks).

### 8.2 SafeScraperBridge
- Injected into WebViews only with `expectedOrigin` verification.
- Enforces a maximum JSON message payload size of 64 KB (`MAX_PAYLOAD_BYTES`).
- Evaluates incoming message schemas strictly (`SERVER_LIST`, `EXTRACTION_RESULT`, `CHALLENGE_STATE`).

### 8.3 Zero Cookie Leakage on Handoff
- `DownloaderHandoffAdapter` strips all `Cookie` and `Set-Cookie` headers prior to dispatching to download services.
- `PlayerHandoffAdapter` packages only necessary stream headers (e.g. `User-Agent`, `Referer`), preventing arbitrary session leakage.

### 8.4 Prohibition of Dynamic Code Loading
- **NEVER** use `DexClassLoader`, `PathClassLoader`, `dalvik.system`, `loadDex`, or reflective `Class.forName` to load remote code.
- All scrapers are statically compiled into the APK and verified via automated test suites.

---

## 9. Canonical Reference Implementation Pattern

Below is the canonical template showing how a standard site scraper should be structured:

```kotlin
package com.example.extension.managed.scraper

import com.example.extension.managed.contract.BaseSiteScraper
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.runtime.context.ExtensionContext
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

class TemplateSiteScraper : BaseSiteScraper {

    override val scraperKey: String = "template_site"
    override val implementationVersion: Int = 1

    override val supportedCapabilities: Set<ScraperCapability> = setOf(
        ScraperCapability.SEARCH,
        ScraperCapability.DETAILS,
        ScraperCapability.EPISODES,
        ScraperCapability.SERVER_DISCOVERY,
        ScraperCapability.VIDEO_EXTRACTION
    )

    override val supportedContentTypes: Set<ContentType> = setOf(
        ContentType.MOVIE,
        ContentType.SERIES
    )

    private val userAgent = "Mozilla/5.0 (Linux; Android 14) CineStream/2.0"

    override suspend fun search(
        extension: ManagedExtension,
        request: SearchRequest
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        try {
            val encodedQuery = URLEncoder.encode(request.query.trim(), "UTF-8")
            val searchUrl = "${extension.baseUrl.trimEnd('/')}/search?q=$encodedQuery&page=${request.page}"

            val doc = Jsoup.connect(searchUrl)
                .userAgent(userAgent)
                .timeout(10_000)
                .get()

            val items = doc.select(".search-card").mapNotNull { card ->
                val title = card.selectFirst(".title")?.text()?.trim() ?: return@mapNotNull null
                val link = card.selectFirst("a")?.absUrl("href") ?: return@mapNotNull null
                val poster = card.selectFirst("img")?.absUrl("src")
                val year = card.selectFirst(".year")?.text()?.trim()

                SearchMediaItem(
                    id = link.hashCode().toString(),
                    title = title,
                    posterUrl = poster,
                    url = link,
                    contentType = request.contentType ?: ContentType.MOVIE,
                    year = year
                )
            }

            Result.success(
                SearchResult(
                    items = items,
                    page = request.page,
                    hasNextPage = doc.selectFirst(".pagination-next") != null
                )
            )
        } catch (e: Exception) {
            Result.failure(ExtensionError.ExtractionFailed("Search failed for ${request.query}: ${e.message}", e))
        }
    }

    override suspend fun getDetails(
        extension: ManagedExtension,
        url: String
    ): Result<MediaDetailsResult> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url).userAgent(userAgent).timeout(10_000).get()
            val title = doc.selectFirst("h1.media-title")?.text()?.trim()
                ?: return@withContext Result.failure(ExtensionError.ParseError("Missing media title on $url"))

            val description = doc.selectFirst(".overview")?.text()?.trim()
            val posterUrl = doc.selectFirst(".poster img")?.absUrl("src")
            val rating = doc.selectFirst(".rating")?.text()?.toDoubleOrNull()
            val year = doc.selectFirst(".release-year")?.text()?.trim()

            Result.success(
                MediaDetailsResult(
                    id = url.hashCode().toString(),
                    title = title,
                    description = description,
                    posterUrl = posterUrl,
                    contentType = ContentType.MOVIE,
                    url = url,
                    year = year,
                    rating = rating
                )
            )
        } catch (e: Exception) {
            Result.failure(ExtensionError.ExtractionFailed("Details failed for $url: ${e.message}", e))
        }
    }

    override suspend fun getEpisodes(
        extension: ManagedExtension,
        seriesUrl: String,
        season: Int
    ): Result<List<EpisodeItem>> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = "$seriesUrl?season=$season"
            val doc = Jsoup.connect(targetUrl).userAgent(userAgent).timeout(10_000).get()

            val episodes = doc.select(".episode-item").mapIndexed { index, el ->
                val epNumber = el.selectFirst(".ep-num")?.text()?.toIntOrNull() ?: (index + 1)
                val epTitle = el.selectFirst(".ep-title")?.text()?.trim() ?: "Episode $epNumber"
                val epLink = el.selectFirst("a")?.absUrl("href") ?: seriesUrl

                EpisodeItem(
                    id = epLink.hashCode().toString(),
                    title = epTitle,
                    episodeNumber = epNumber,
                    seasonNumber = season,
                    url = epLink
                )
            }

            Result.success(episodes)
        } catch (e: Exception) {
            Result.failure(ExtensionError.ExtractionFailed("Episodes failed for $seriesUrl season $season: ${e.message}", e))
        }
    }

    override suspend fun discoverServers(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ServerDiscoveryRequest,
        webEngine: WebExtractionEngine?
    ): Result<ServerDiscoveryResult> = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(request.targetUrl).userAgent(userAgent).timeout(10_000).get()

            val servers = doc.select(".server-btn").mapNotNull { btn ->
                val name = btn.text().trim()
                val link = btn.attr("data-embed-url").ifBlank { btn.absUrl("href") }
                if (link.isBlank()) return@mapNotNull null

                val isDirect = link.endsWith(".mp4") || link.endsWith(".m3u8")
                ServerItem(
                    id = link.hashCode().toString(),
                    name = name,
                    link = link,
                    isDirectStream = isDirect,
                    serverType = if (isDirect) ServerType.DIRECT else ServerType.EMBED,
                    requiresWebView = !isDirect
                )
            }

            Result.success(
                ServerDiscoveryResult(
                    servers = servers,
                    sourcePageUrl = request.targetUrl
                )
            )
        } catch (e: Exception) {
            Result.failure(ExtensionError.ExtractionFailed("Server discovery failed: ${e.message}", e))
        }
    }

    override suspend fun extractStream(
        extension: ManagedExtension,
        session: ExtractionSession,
        request: ExtractionRequest,
        webEngine: WebExtractionEngine?
    ): Result<ExtractionResult> = withContext(Dispatchers.IO) {
        try {
            val streamUrl = request.serverItem.link
            val isHls = streamUrl.contains(".m3u8")

            // Guarantees zero artificial 1080p defaults
            val variant = MediaVariant(
                url = streamUrl,
                protocol = if (isHls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE
            )

            val playbackSource = PlaybackSource(
                streamUrl = streamUrl,
                mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                protocol = if (isHls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE,
                variants = listOf(variant)
            )

            val downloadSource = DownloadSource(
                url = streamUrl,
                mimeType = if (isHls) "application/x-mpegURL" else "video/mp4",
                protocol = if (isHls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE
            )

            Result.success(
                ExtractionResult(
                    playbackSource = playbackSource,
                    downloadSource = downloadSource,
                    variants = listOf(variant)
                )
            )
        } catch (e: Exception) {
            Result.failure(ExtensionError.ExtractionFailed("Extraction failed: ${e.message}", e))
        }
    }
}
```

---

## 10. Developer Pre-Submission Checklist

Before submitting a new Scraper for bundling into CineStream, verify every item below:

- [ ] **Contract Compliance**: Implements `BaseSiteScraper` completely.
- [ ] **Deterministic Identity**: `scraperKey` is unique, lowercase, matching remote Firestore configuration.
- [ ] **Zero UI Imports**: No references to `com.example.ui`, `androidx.compose`, `Activity`, `Fragment`, `Dialog`, or `Toast`.
- [ ] **Zero Firebase/Cloudinary Imports**: Scraper interacts only with target website and runtime context.
- [ ] **Zero Dynamic Code Loading**: No `DexClassLoader`, `PathClassLoader`, or `Class.forName`.
- [ ] **Quality Invariant**: Does **not** invent `"1080p"` for unmeasured streams; uses `"Auto"` or exact integer height.
- [ ] **Security Compliance**: Respects `TrustedEmbedHostPolicy`; never loads `http://`, `file://`, or private IP addresses.
- [ ] **Standardized Error Propagation**: Employs `ExtensionError` subtypes; sets `isRecoverable` appropriately.
- [ ] **Offline Fast-Fail Compatibility**: Relies on Managed Runtime to check network connectivity before execution.
- [ ] **Unit Tests Passed**: Unit tests verify search, details, episodes, server discovery, and media extraction using local mock fixtures (zero live network calls in CI).
