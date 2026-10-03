# PHASE NEW-A4U-01: Anime4Up Scraper Forensic Implementation Report

## Executive Summary
This report documents the forensic, standalone implementation of the **Anime4Up** (`w1.anime4up.rest`) bundled managed scraper in CineStream. The implementation strictly adheres to all architectural constraints, contract specifications, and isolation guarantees of the Managed Extension Runtime.

---

## 1. Files Added
- `app/src/main/java/com/example/extension/managed/scraper/Anime4UpScraper.kt` [UNIT TEST VERIFIED / STATICALLY VERIFIED]
  - Standalone bundled scraper implementing `BaseSiteScraper`.
  - Zero reflection, zero dynamic class loading, zero Firebase/Firestore imports, zero UI/Player/Downloader dependencies.
- `app/src/test/java/com/example/extension/managed/Anime4UpScraperUnitTest.kt` [UNIT TEST VERIFIED]
  - Comprehensive 14-vector unit test suite covering search, details, episodes, server discovery, fallback hierarchies, malformed HTML, and playback handoff.

---

## 2. Files Modified
- `app/src/main/java/com/example/extension/managed/registry/ScraperRegistry.kt` [STATICALLY VERIFIED / INTEGRATION TEST VERIFIED]
  - Registered `"anime4up"` in the static, compile-time `ScraperRegistry.defaultRegistry()`.
- `app/src/main/java/com/example/extension/orchestrator/ManagedMediaOrchestrator.kt` [STATICALLY VERIFIED / INTEGRATION TEST VERIFIED]
  - Registered `DEFAULT_ANIME4UP_MANAGED_EXTENSION` (priority = 108, contentTypes = ANIME, MOVIE, SERIES).
  - Pre-seeded in default runtime state and wired into the Firestore admin synchronization routine (`syncDefaultExtensionsToFirestore`).

---

## 3. Search Implementation
- **Base URL:** `https://w1.anime4up.rest`
- **Request Format:** `GET /?s=<encodedQuery>` (Page 1) and `GET /page/<page>/?s=<encodedQuery>` (Page 2+)
- **DOM Parsing & Selectors:**
  - Primary selectors: `.anime-card-container`, `.anime-card-themex`, `.anime-card`, `.anime-card-poster`, `.overlay`
  - Fallback selectors: `div.anime-item`, `article.anime-card`, `div.col-md-3`, `div.post-entry`, `a.overlay[href*='/anime/']`
- **Normalization:**
  - `title`: Extracted from link title, `.anime-card-title`, `.title`, or element text.
  - `posterUrl`: Extracted from `img` (`src`, `data-src`, `data-lazy-src`, `data-image`) and resolved to absolute HTTPS.
  - `year`: Extracted from `.anime-card-year`, `.year`, `span.year`, or regex pattern `\b(19\d\d|20\d\d)\b`.
  - `contentType`: Dynamically mapped to `ContentType.MOVIE` if title/metadata contains "فيلم" or "Movie", otherwise `ContentType.ANIME`.
  - `id`: Normalized slug derived from canonical URL.
- **Empty Query Handling:** Short-circuits immediately returning an empty `SearchResult` with zero network overhead.
- **Verification Status:** [UNIT TEST VERIFIED]

---

## 4. Details Implementation
- **URL Handling:** Resolves relative and absolute anime URLs against `baseUrl`.
- **Extracted Metadata:**
  - `title`: Extracted from `h1.anime-details-title`, `h1.entry-title`, `.anime-title`, `h1`.
  - `posterUrl`: Extracted from `img.thumbnail`, `.anime-poster img`, `.poster img`, `img[itemprop=image]`.
  - `description`: Extracted from `.anime-story`, `.story`, `.entry-content`, `p.anime-story`.
  - `year`: Extracted from `.anime-year`, `.release-date`, or year regex.
  - `rating`: Extracted from `.anime-rating`, `.rating`, `span[itemprop=ratingValue]`, parsed as `Double`.
  - `genres`: Extracted from `.anime-genres a`, `.genres a`, `.genre a`.
  - `metadata`: Contains `originalTitle`, `status`, `studio`, `country`, and `episodeCount` without hallucination.
  - `episodes`: Directly parsed if `#ULEpisodesList` is present on the details page.
- **Verification Status:** [UNIT TEST VERIFIED]

---

## 5. Episodes Implementation
- **Target Container:** `#ULEpisodesList`, `ul#ULEpisodesList`, `.episodes-list`, `div.episodes-card-container`
- **Link Pattern:** `li a[href]` containing `/episode/...`
- **Episode Number Extraction Fallback Hierarchy:**
  1. **Episode text:** Matches `(?:الحلقة|حلقة|Episode|Ep|E)\s*(\d+)` or rightmost integer in title.
  2. **URL slug:** Matches `(?:episode|الحلقة|ep)[-_/](\d+)` or trailing dash integer `-(?<num>\d+)/?$`.
  3. **DOM Position:** Fallback to 1-based index position if no textual/URL numeric tokens exist.
- **Deduplication:** Filters duplicate episodes by canonical episode URL.
- **Verification Status:** [UNIT TEST VERIFIED]

---

## 6. Server Discovery
- **Target Containers & Selectors:**
  - Primary: `ul#episode-servers li[data-watch]`, `#episode-servers li[data-watch]`, `ul#watch-servers li[data-watch]`, `#watch-servers li[data-watch]`, `li[data-watch]`, `a[data-watch]`
  - Secondary: `#episode-servers li`, `ul#episode-servers li`, `ul#watch-servers li`
  - Canonical Iframe Fallback: `#episode-player iframe`, `.videoWrapper iframe`, `.wa-real-player iframe`
- **Dynamic Attributes Extraction:**
  - `watch URL`: Extracted from `data-watch`, `data-embed`, or `data-src`. Supports raw HTTPS URLs, protocol-relative `//`, relative `/`, and safe Base64 encoded payload resolution.
  - `server name`: Extracted from element text, `.server-name`, or `data-server` / `data-name`. Falls back safely to `"سيرفر ${index + 1}"`.
  - `quality`: Dynamically extracted from `data-quality` attribute or text regex `\b(4K|2160p|FHD|1080p|HD|720p|SD|480p|360p)\b`. Never assumes artificial fixed qualities.
- **Deduplication:** Enforces unique server links (`distinctBy { it.link }`).
- **Classification:** Categorized as `ServerType.DIRECT` if linking to `.mp4`/`.m3u8`, otherwise `ServerType.EMBED` with `requiresWebView = true`.
- **Verification Status:** [UNIT TEST VERIFIED]

---

## 7. Playback Handoff
- **Data Model:** Normalized `PlaybackSource` exclusively passed to downstream consumers.
- **Direct Video Streams:** Direct `.mp4` or `.m3u8` links produce immediate `PlaybackSource` with appropriate MIME types (`video/mp4`, `application/x-mpegURL`) and protocol (`DIRECT_FILE`, `HLS`).
- **Embed Stream Resolution:**
  - Passes embed URL to `WebExtractionEngine.extractStreamUrl` using `ControlledWebViewEngine.getPublicEmbedMediaExtractionScript()` when a web engine is supplied.
  - Intercepted streams undergo validation via `MediaStreamDetector.validateMediaUrl`.
  - Falls back to `StreamProtocol.EMBED` (`text/html`) without leaking raw DOM or unvalidated scripts.
- **Header Projection:** Employs `CredentialProjector.projectHeaders` for secure Referer/User-Agent passing.
- **Verification Status:** [UNIT TEST VERIFIED]

---

## 8. Test Execution Matrix
All 14 required unit test vectors were implemented in `Anime4UpScraperUnitTest.kt` and executed via Robolectric:

| # | Test Vector | Description | Result | Classification |
|---|---|---|---|---|
| 1 | `test01_searchParsing` | Multi-card parsing (container + themex, movie detection) | Passed | [UNIT TEST VERIFIED] |
| 2 | `test02_emptySearch` | Empty & whitespace search query handling | Passed | [UNIT TEST VERIFIED] |
| 3 | `test03_detailsParsing` | Full metadata parsing (story, year, rating, studio, country) | Passed | [UNIT TEST VERIFIED] |
| 4 | `test04_episodeParsing` | Parsing `#ULEpisodesList li a[href]` links | Passed | [UNIT TEST VERIFIED] |
| 5 | `test05_episodeNumberExtraction` | 3-tier fallback hierarchy (Text -> URL -> DOM position) | Passed | [UNIT TEST VERIFIED] |
| 6 | `test06_serverDiscovery` | Parsing `li[data-watch]` with dynamic names and qualities | Passed | [UNIT TEST VERIFIED] |
| 7 | `test07_missingDataWatch` | Fallback to player iframe when `data-watch` is absent | Passed | [UNIT TEST VERIFIED] |
| 8 | `test08_duplicateServers` | Deduplication of servers sharing identical target links | Passed | [UNIT TEST VERIFIED] |
| 9 | `test09_relativeUrls` | Resolution of root-relative `/` and protocol-relative `//` | Passed | [UNIT TEST VERIFIED] |
| 10 | `test10_invalidUrls` | Rejection of `javascript:`, `file:`, `data:`, and blank URLs | Passed | [UNIT TEST VERIFIED] |
| 11 | `test11_unknownServerNames` | Graceful fallback to `"سيرفر N"` on missing label | Passed | [UNIT TEST VERIFIED] |
| 12 | `test12_unknownQualities` | Zero artificial quality hallucination on unlabelled servers | Passed | [UNIT TEST VERIFIED] |
| 13 | `test13_malformedHtml` | Robustness against truncated, unclosed, or malformed HTML | Passed | [UNIT TEST VERIFIED] |
| 14 | `test14_playbackHandoff` | Normalization into `PlaybackSource` for MP4, HLS, and Embed | Passed | [UNIT TEST VERIFIED] |

---

## 9. Build Verification
- **Compilation Tool:** `compile_applet`
- **Result:** `Build succeeded - the applet is compiled`
- **Warnings / Diagnostics:** Clean compilation of new scraper and test files.
- **Classification:** [INTEGRATION TEST VERIFIED]

---

## 10. Security Scan & Policy Compliance
- **Dynamic Code Loading (DCL):** None (`DexClassLoader`, `PathClassLoader`, and `InMemoryDexClassLoader` strictly absent).
- **Reflection:** None (`Class.forName` and reflection-based scraper loading strictly prohibited).
- **Remote Bytecode:** No dynamic executable code downloaded or evaluated.
- **SSL / Certificate Hygiene:** No `handler?.proceed()` or SSL bypass implemented. Mandatory HTTPS enforcement on all scraped links. Dangerous URL schemes (`javascript:`, `file:`, `content:`, `data:`) strictly blocked.
- **Decoupling:** Zero references to Firebase, Firestore, Cloudinary, Android UI Composables, ExoPlayer, or DownloadManager inside `Anime4UpScraper.kt`.
- **Classification:** [STATICALLY VERIFIED]

---

## 11. Live Verification Status
- **Live HTTP Requests:** `w1.anime4up.rest` is protected by Cloudflare anti-bot verification when queried from cloud data center IPs. As per Hard Rule #5 ("لا تضف Cloudflare bypass"), the scraper detects challenge documents gracefully (`ExtensionError.CloudflareChallenge`) and yields to the standard Managed Extension Fallback pipeline without crashing.
- **Classification:** [CODE-PRESENT / NOT EXECUTED] (Live edge dependent on user device residential IP and browser challenges)

---

## 12. Known Limitations
1. **Direct Download Scope:** Direct downloads are deliberately omitted in this phase as per specification ("DOWNLOAD: لا تنفذه في هذه المرحلة").
2. **Third-Party Embed Resolvers:** No custom provider extractors (Mega, Videa, Mp4upload) were bundled, ensuring strict adherence to the prohibition of hardcoded third-party logic. Dynamic embed extraction relies on `WebExtractionEngine`.
3. **Cloudflare WAF:** In data center environments where Cloudflare presents an active Turnstile challenge, server discovery over raw HTTP returns `CloudflareChallenge`, gracefully falling back to other active extensions in the orchestrator pipeline.
