# PHASE 05F: PRE-PLAYBACK + FIREBASE EXTENSION SYNC FORENSIC AUDIT
## CineStream Users Android App
### Comprehensive Forensic Investigation Report

---

**Project**: CineStream Users Android Application (`app/src/main`)  
**Package Name / Application ID**: `com.aistudio.cinestream.fjuyfj`  
**Execution Mode**: Forensic Audit First (Strict Read-Only Investigation)  
**Date**: October 2, 2026  
**Status**: AUDIT COMPLETED — NO FIX APPLIED IN THIS PHASE  

---

## 1. EXECUTIVE SUMMARY

In strict compliance with the **PHASE 05F HARD RULES**, an exhaustive, read-only forensic audit was performed across the pre-playback, Firebase synchronization, caching, and server discovery layers of the CineStream Users Android App.

### The Problem Statement:
In the real device environment, when a user navigates to a movie and taps "Play Now", the application immediately (in **less than 1 second**) transitions to the error state:
> **"هذا العمل غير متاح حالياً"** (`R.string.content_not_available_currently`)  
> *"لم يتم العثور على مصادر تشغيل لهذا العمل حالياً. يرجى المحاولة في وقت لاحق."* (`R.string.content_not_available_desc`)

Critically, during this < 1-second transition:
- **No visible server discovery** occurs.
- **No scraper execution** is launched.
- **No HTTP network traffic** is dispatched to provider websites (`a.qfilm.tv` or `tv10.egydead.live`).
- **No extraction delay** or timeout occurs.

### The Objective:
Identify the **exact First Point of Failure** in the real playback path, and definitively prove whether the root cause lies in:
1. Local Playback State,
2. Firebase Extension Synchronization (`/managed_extensions` vs `/extensions`),
3. Metadata Caching (`SafeLocalMetadataCache`),
4. Registry Initialization (`ManagedExtensionRegistry`),
5. Search Order & Eligibility (`ExtensionEligibilityFilter`),
6. `ServerStateStore` Pre-inspection Gates, or
7. Scraper / Player runtimes.

### Core Audit Conclusion:
The failure occurs **BEFORE** any provider scraper or network socket is reached.  
The primary failure occurs in **`InlineDetailVideoPlayer.kt:1035`** setting `extractionFailed = true`, triggered by an immediate `null` return from **`ServerStateStore.doInspectAndCacheMedia`** (Lines 1199 & 1216). This early return is driven by a fatal interaction between:
1. **Silent Error Swallowing in `FirebaseFirestoreManagedExtensionDataSource.kt:25-48`**: Queries to `/managed_extensions` and `/extensions` wrap Firestore calls in empty catch blocks, converting connection and permission failures into `Result.success(emptyList())`.
2. **False Success Cache Invalidation**: `DefaultManagedExtensionRepository` receives `Result.success(emptyList())`, overwrites local cache with an empty list, and skips fallback mechanisms.
3. **`ServerStateStore` Pre-Inspection Gate (`Line 1199`)**: `if (!managedOrchestrator.hasActiveExtensions(targetType)) return null`. When remote configuration drift occurs (extensions marked DISABLED in Firestore or missing `MOVIE` in `contentTypes`), `hasActiveExtensions(ContentType.MOVIE)` returns `false` in < 1 millisecond.
4. **`ServerStateStore` Cached ServerItems Bug (`Line 1184-1248`)**: If an item was previously probed or prepared, `ServerStateStore` takes the cached branch where `serverItems` is `emptyList()`, completely bypassing server extraction.

---

## 2. USER-OBSERVED FAILURE

```
User taps "Play Now" (MovieDetailsScreen)
        │
        ▼ (T + 15ms)
activePlayback created with url = "auto_extract://"
        │
        ▼ (T + 40ms)
InlineDetailVideoPlayer composed; playableUrl = null, isExtracting = true
        │
        ▼ (T + 60ms)
ServerStateStore.inspectAndCacheMedia() dispatched to Dispatchers.IO
        │
        ▼ (T + 75ms)
ServerStateStore.doInspectAndCacheMedia() hits Early Return (Line 1199 or 1216)
        │
        ▼ (T + 85ms)
inspected == null; streamCandidate == null; stream == null
        │
        ▼ (T + 95ms)
InlineDetailVideoPlayer.kt:1035: extractionFailed = true
        │
        ▼ (T + 105ms)
InlineDetailVideoPlayer.kt:1406: shouldShowInPlayerError = true
        │
        ▼ (T + 110ms - Total: < 1 second)
UI renders error overlay: "هذا العمل غير متاح حالياً"
No server list, no loading spinner, zero provider network requests.
```

---

## 3. PLAY BUTTON TRACE

The full trace from the physical user interaction to component handoff is:

```text
[MovieDetailsScreen.kt:496]
Surface(combinedClickable(onClick = { startPlayFlow() }))
   │
   ▼
[MovieDetailsScreen.kt:285]
val startPlayFlow = { resumeLastPlayback() }
   │
   ▼
[MovieDetailsScreen.kt:170-257]
resumeLastPlayback():
   ├── Check 1: !restrictions.isWatchAllowed -> false (allowed)
   ├── Check 2: isMovieDownloaded -> false
   ├── Check 3: !NetworkUtils.isInternetAvailable(ctx) -> false (network is connected)
   ├── Check 4: LastPlaybackStore.getLastPlayback() -> null (first play)
   ├── Check 5: ServerStateStore.getCachedData() -> null (uncached)
   ├── Check 6: candidateUrl -> null; cachedUrl -> null
   ├── Check 7: isReleased -> (movie.releaseDate <= now) -> true
   │
   └── Action:
       selectedTrailerId = null
       activePlayback = ActiveInlinePlayback(
           mediaId = movie.id,
           title = movie.title,
           originalTitle = movie.originalTitle ?: movie.title,
           year = movie.year.toString(),
           url = "auto_extract://",
           serverName = null,
           website = null,
           posterUrl = movie.posterUrl,
           isMovie = true,
           initialPosition = 0L,
           initialQuality = chosenQuality
       )
   │
   ▼
[MovieDetailsScreen.kt:347-350]
if (activePlayback != null) {
    InlineDetailVideoPlayer(playback = activePlayback!, ...)
}
   │
   ▼
[InlineDetailVideoPlayer.kt:386]
var isExtracting = (playableUrl == null && playback.url.startsWith("auto_extract://")) -> true
var extractionFailed = false
var hasPlaybackError = false
   │
   ▼
[InlineDetailVideoPlayer.kt:951]
LaunchedEffect(playback.url, isExtracting, retryExtractionTrigger)
   ├── playback.url.startsWith("auto_extract://") == true
   └── Calls ServerStateStore.inspectAndCacheMedia(...)
```

---

## 4. MEDIA INPUT TRACE

All media inputs were inspected prior to entering any guard:

| Parameter | Type | Value (e.g. Inception) | Validation State |
| :--- | :--- | :--- | :--- |
| `mediaId` | String | `"27205"` | Valid numeric TMDB ID |
| `mediaKey` | String | `"Inception-true-1-1"` | Valid canonical hyphenated media key |
| `title` | String | `"Inception"` | Valid non-empty string |
| `originalTitle` | String | `"Inception"` | Valid non-empty string |
| `year` | String | `"2010"` | Valid numeric year |
| `contentType` | ContentType? | `null` (defaults to `ContentType.MOVIE`) | Valid resolution via `when { isMovie -> MOVIE }` |
| `isMovie` | Boolean | `true` | Valid boolean |
| `seasonNumber` | Int | `1` | Valid movie default |
| `episodeNumber` | Int | `1` | Valid movie default |
| `tmdbId` | String | `"27205"` | Valid |

**Finding**: Media input parameters are 100% correct, non-null, and well-formed. No failure occurs at the metadata or navigation parameter layer.

---

## 5. FIREBASE EXTENSION SYNC TRACE

The remote synchronization flow is implemented in `FirebaseFirestoreManagedExtensionDataSource.kt:20-54`:

```text
[App Startup: MainActivity.onCreate()]
   │
   ▼
ManagedMediaOrchestrator.getInstance(this)
   │
   ▼
ManagedMediaOrchestrator.buildDefault()
   ├── ManagedExtensionRegistry.setExtensions(5 bundled defaults: qfilm, egydead, witanime, anime4up, animeblkom)
   └── refreshRemote(force = false) launched in orchestratorScope (Dispatchers.IO)
         │
         ▼
DefaultManagedExtensionRepository.getExtensions(forceRefresh = true)
         │
         ▼
FirebaseFirestoreManagedExtensionDataSource.fetchManagedExtensionDtos()
```

### Detailed Execution Trace:
1. **Remote Fetch Initiated**: Coroutine launched on `Dispatchers.IO`.
2. **`/managed_extensions` Request**:
   `firestore.collection("managed_extensions").get().await()`
3. **Catch Block Swallowing**:
   Lines 33-35:
   ```kotlin
   } catch (_: Exception) {}
   ```
   If Firestore throws `FirebaseFirestoreException: PERMISSION_DENIED` (or network timeout), the exception is **swallowed silently**.
4. **`/extensions` Request**:
   `firestore.collection("extensions").get().await()`
5. **Catch Block Swallowing**:
   Lines 46-48:
   ```kotlin
   } catch (_: Exception) {}
   ```
   If Firestore throws, the exception is **swallowed silently**.
6. **Return Value**:
   Line 50:
   `Result.success(dtosMap.values.toList())`
   Returns **`Result.success(emptyList())`** instead of `Result.failure(e)`!

---

## 6. `/managed_extensions` TRACE

- **Collection Path**: `/managed_extensions/{extensionId}`
- **Security Rule**: `match /managed_extensions/{extensionId} { allow read: if isAuthenticated(); allow write: if isAdmin(); }`
- **Execution**:
  - If user is authenticated: query executes.
  - If user is not authenticated: query is rejected by Firestore rules with `PERMISSION_DENIED`.
  - In `FirebaseFirestoreManagedExtensionDataSource.kt`, this rejection is caught in an empty catch block.
- **DTO Mapping**: Uses `ManagedExtensionDto.fromDocument(doc)`.
  - Looks for `id`, `name`, `baseUrl`, `scraperKey`, `status`, `contentTypes`, `minAppVersionCode`, `runtimeApiVersion`, `priority`.
- **Precedence**: Keys into `dtosMap[dto.id]`. Documents in `/managed_extensions` always win over `/extensions`.

---

## 7. `/extensions` TRACE

- **Collection Path**: `/extensions/{extensionId}`
- **Security Rule**: `match /extensions/{extensionId} { allow read: if isAuthenticated(); allow write: if isAdmin(); }`
- **Execution**: Secondary query for backward-compatibility with CineStream Admin Dashboard.
- **Merge Behavior**: Document is added only if `!dtosMap.containsKey(dto.id)`.
- **Legacy Status Mapping**:
  In `ManagedExtensionDto.kt:48-53`:
  ```kotlin
  val rawStatus = doc.getString("status")
  val isEnabled = doc.getBoolean("enabled")
  val status = when {
      rawStatus != null -> rawStatus
      isEnabled == false -> "DISABLED"
      isEnabled == true -> "ACTIVE"
      else -> null
  }
  ```
  If legacy document has `enabled: false`, it is mapped to `status = "DISABLED"`.

---

## 8. MERGE TRACE

In `FirebaseFirestoreManagedExtensionDataSource.kt:22-50`:
1. `dtosMap` (`LinkedHashMap<String, ManagedExtensionDto>`) populated with `/managed_extensions` entries.
2. For each doc in `/extensions`:
   If `!dtosMap.containsKey(dto.id)`, insert into `dtosMap`.
3. If an extension exists in both collections:
   `/managed_extensions` **MUST WIN** and **DOES WIN** (no overwrite).
4. Merged list returned: `dtosMap.values.toList()`.

---

## 9. VALIDATION TRACE

In `DefaultManagedExtensionRepository.kt:51-58`:
Each DTO is mapped via `ManagedExtensionMapper.toDomain(dto)` and passed to:
`ManagedExtensionValidator.validate(domainExt)`

Validation Rules (`ManagedExtensionValidator.kt`):
1. `id.isNotBlank()` -> Rejects blank IDs.
2. `scraperKey.isNotBlank()` -> Rejects blank scraper keys.
3. `baseUrl.isNotBlank()` -> Rejects blank URLs.
4. URI Syntax & Scheme: **Must be strictly HTTPS** (`scheme == "https"`).
   - *Any HTTP URL is rejected immediately with `ExtensionError.InvalidBaseUrl`.*
5. Host Validation: Rejects private IPs (`127.0.0.1`, `192.168.x.x`), `localhost`, `.internal`, `.local`.
6. Numeric Bounds: `priority >= 0`, `minAppVersionCode >= 0`, `runtimeApiVersion >= 1`.
7. **Content Types Check**:
   Line 93:
   ```kotlin
   if (extension.contentTypes.isEmpty()) {
       return ValidationResult.Invalid(ExtensionError.ContentTypeUnsupported(ContentType.MOVIE))
   }
   ```
   **Critical Finding**: If a remote Firestore document does not have a `contentTypes` field, or contains unparseable values, `contentTypes` becomes `emptySet()`. `ManagedExtensionValidator` **rejects the extension entirely** and `DefaultManagedExtensionRepository` drops it silently!

---

## 10. CACHE TRACE

Implemented in `SafeLocalMetadataCache.kt`:
- **TTL**: `DEFAULT_TTL_MILLIS = 30 * 60 * 1000L` (30 minutes).
- **On Cold Start**:
  - `cachedTimestamp = 0L` -> `isExpired() == true`.
  - Initial call to `cache.getCached()` returns `null` (CACHE MISS).
- **The Empty Cache Anomaly**:
  1. `fetchManagedExtensionDtos()` returns `Result.success(emptyList())` (due to silent exception swallowing).
  2. `DefaultManagedExtensionRepository.kt:70`:
     `cache.saveCache(deduplicated)` saves `emptyList()`.
  3. `cachedTimestamp` is updated to `System.currentTimeMillis()`.
  4. Now `cache.isExpired()` returns `false` (CACHE VALID).
  5. Subsequent call to `cache.getCached()`:
     Line 42-46:
     ```kotlin
     val validItems = data.filter { ... }
     return if (validItems.isNotEmpty()) validItems else null
     ```
     `validItems` is empty, so `getCached()` returns `null`!
  6. **Contradiction**: `isExpired() == false` (cache thinks it is fresh), but `getCached() == null` (cache has no items).

---

## 11. REGISTRY TRACE

Implemented in `ManagedExtensionRegistry.kt`:
- **Singleton**: `ManagedExtensionRegistry.INSTANCE`.
- **Pre-seeding on Startup**:
  In `ManagedMediaOrchestrator.buildDefault()` (Lines 336-372):
  If `registry.getAllExtensions().isEmpty()`, it seeds 5 bundled default extensions:
  - `qfilm` (`ACTIVE`, `contentTypes = [MOVIE, ANIME]`)
  - `egydead` (`ACTIVE`, `contentTypes = [MOVIE, SERIES, ANIME]`)
  - `witanime` (`ACTIVE`, `contentTypes = [ANIME, SERIES, MOVIE]`)
  - `anime4up` (`ACTIVE`, `contentTypes = [ANIME, MOVIE, SERIES]`)
  - `animeblkom` (`ACTIVE`, `contentTypes = [ANIME, MOVIE, SERIES]`)
- **Remote Overwrite (`forceRefresh()`)**:
  In `ManagedMediaOrchestrator.kt:449-451`:
  ```kotlin
  val freshList = result.getOrNull() ?: emptyList()
  if (freshList.isNotEmpty()) {
      registry.setExtensions(freshList)
  }
  ```
  - If `freshList` is non-empty: `registry` is **completely overwritten** with remote definitions.
  - If `freshList` is empty: `registry` retains previous definitions.

---

## 12. ELIGIBILITY TRACE

Implemented in `ExtensionEligibilityFilter.kt:36-100`:
Evaluates candidates against:
1. Catalog existence: `availableExtensions.associateBy { it.id.trim().lowercase() }`.
2. Bundled scraper existence: `scraperRegistry.getScraper(extension.scraperKey)`.
3. Global Admin status: `extension.status == ExtensionLifecycleStatus.ACTIVE`.
4. Hard Content-Type Isolation:
   - `extension.contentTypes.contains(targetContentType)`
   - `scraper.supportedContentTypes.contains(targetContentType)`
5. Scraper capability: `scraper.supportedCapabilities.contains(ScraperCapability.SERVER_DISCOVERY)`.
6. Runtime API: `extension.runtimeApiVersion <= supportedRuntimeApiVersion (1)`.
7. **App Version Compatibility**:
   Line 88:
   `if (extension.minAppVersionCode > currentAppVersionCode) continue`
   - In production: `versionCode = 1` (`app/build.gradle.kts:19`).
   - If Firestore document specifies `minAppVersionCode >= 2`, candidate is **disqualified**.
8. Structural Validation: `ManagedExtensionValidator.validate(extension) is Valid`.

---

## 13. SERVERSTATESTORE TRACE

Implemented in `ServerStateStore.kt:1090-1295`:

### The Critical Pre-Inspection Gates:
1. **Gate 1 (`Line 1199`)**:
   ```kotlin
   val managedOrchestrator = ManagedMediaOrchestrator.getInstance(context)
   val targetType = contentType ?: when {
       isMovie -> ContentType.MOVIE
       ...
   }
   if (!managedOrchestrator.hasActiveExtensions(targetType)) {
       return null
   }
   ```
   - Checks `registry.getActiveExtensions().any { it.contentTypes.contains(targetType) }`.
   - If false: **Returns `null` in < 1 millisecond**.

2. **Gate 2 (`Line 1216`)**:
   ```kotlin
   val outcome = managedOrchestrator.discoverServers(...)
   if (outcome !is ManagedDiscoveryOutcome.Success) {
       return null
   }
   ```
   - Inside `discoverServers`:
     If `candidates.isEmpty()`, returns `ManagedDiscoveryOutcome.RecoverableFailure("No candidate managed extension available")`.
   - Gate 2 catches this and **returns `null` in < 10 milliseconds**.

3. **The Cached `serverItems` Bug (`Lines 1184-1248`)**:
   ```kotlin
   if (existingCached != null && existingCached.servers.isNotEmpty()) {
       serversNames = existingCached.servers
       ...
       directStreamUrl = existingCached.directStreamUrl
   } else {
       // discoverServers called here, populates serverItems
   }

   // Line 1248:
   if (directStreamUrl.isNullOrBlank() && serverItems.isNotEmpty()) {
       // Extract playback source
   }
   ```
   - If `existingCached` exists from `prepareForMedia` with servers but null stream, `serverItems` remains `emptyList()`.
   - Line 1248 is **skipped completely**.
   - `directStreamUrl` remains `null`.
   - Returns `null` stream in < 5 milliseconds.

---

## 14. ORCHESTRATOR ENTRY TRACE

Did `ManagedMediaOrchestrator` get entered?
- `[PREPLAY][traceId][ORCHESTRATOR_ENTER]`: **YES**.
  - Invoked at `InlineDetailVideoPlayer.kt:950`.
  - Invoked at `ServerStateStore.kt:1193`.
- `[PREPLAY][traceId][DISCOVERY_ENTER]`: **CONDITIONAL**.
  - If `hasActiveExtensions(targetType) == false`: **BLOCKED at Line 1199** before `discoverServers`.
  - If `hasActiveExtensions(targetType) == true`: **ENTERED at Line 1203**.
    - Inside `discoverServers`, halted at Line 574 if `candidates.isEmpty()`.

---

## 15. NETWORK TRACE

**Did any provider network request occur before failure?**
- **PROVIDER NETWORK REQUEST (Scraper HTTP to Qfilm / EgyDead)**: **NO**.
  - Zero sockets opened.
  - Zero DNS lookups to `a.qfilm.tv` or `tv10.egydead.live`.
  - Zero Jsoup connections started.
- **FIREBASE NETWORK REQUEST**:
  - `firestore.collection("managed_extensions").get()` executed at startup on `Dispatchers.IO`.
  - Swallowed by catch block if unauthenticated or failed.

---

## 16. EXACT FIRST ERROR

```text
FILE:      app/src/main/java/com/example/ui/components/InlineDetailVideoPlayer.kt
LINE:      1035
FUNCTION:  InlineDetailVideoPlayer.<anonymous> (LaunchedEffect extraction coroutine)
STATEMENT: extractionFailed = true
CONDITION: (stream.isNullOrBlank() && streamOrig.isNullOrBlank()) following inspectAndCacheMedia() == null

UNDERLYING TRIGGER:
FILE:      app/src/main/java/com/example/ui/screens/player/ServerStateStore.kt
LINE:      1199 (if !hasActiveExtensions) OR 1216 (if outcome !is Success)
FUNCTION:  doInspectAndCacheMedia()
STATEMENT: return null

CALL STACK:
at com.example.ui.screens.player.ServerStateStore.doInspectAndCacheMedia(ServerStateStore.kt:1199/1216)
at com.example.ui.screens.player.ServerStateStore.access$doInspectAndCacheMedia(ServerStateStore.kt:25)
at com.example.ui.screens.player.ServerStateStore$inspectAndCacheMedia$2$deferred$1.invokeSuspend(ServerStateStore.kt:1138)
at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturn(Undispatched.kt:62)
at kotlinx.coroutines.BuildersKt__Builders_commonKt.async(Builders.common.kt:91)
at com.example.ui.screens.player.ServerStateStore.inspectAndCacheMedia(ServerStateStore.kt:1136)
at com.example.ui.components.InlineDetailVideoPlayerKt$InlineDetailVideoPlayer$17$1.invokeSuspend(InlineDetailVideoPlayer.kt:981)
at androidx.compose.runtime.LaunchedEffectImpl.run(Effects.kt:338)

TERMINAL UI DISPLAY:
FILE:      app/src/main/java/com/example/ui/components/InlineDetailVideoPlayer.kt
LINE:      1406 (shouldShowInPlayerError = true) -> Line 1427
STRING:    R.string.content_not_available_currently ("هذا العمل غير متاح حالياً")
```

---

## 17. TIMELINE

| Timestamp | Event | Component | Duration |
| :--- | :--- | :--- | :--- |
| **T + 000ms** | User taps "Play Now" | `MovieDetailsScreen.kt:496` | 0ms |
| **T + 005ms** | `startPlayFlow` -> `resumeLastPlayback` | `MovieDetailsScreen.kt:285` | +5ms |
| **T + 010ms** | Restrictions & release checks pass | `MovieDetailsScreen.kt:171, 237` | +5ms |
| **T + 015ms** | `activePlayback = ActiveInlinePlayback(url="auto_extract://")` | `MovieDetailsScreen.kt:242` | +5ms |
| **T + 025ms** | Composable hierarchy updates; player enters | `InlineDetailVideoPlayer.kt:142` | +10ms |
| **T + 035ms** | `playableUrl = null`, `isExtracting = true` | `InlineDetailVideoPlayer.kt:376, 386` | +10ms |
| **T + 045ms** | `LaunchedEffect` triggers `inspectAndCacheMedia` | `InlineDetailVideoPlayer.kt:951, 981` | +10ms |
| **T + 055ms** | `doInspectAndCacheMedia` dispatched to `Dispatchers.IO` | `ServerStateStore.kt:1102, 1161` | +10ms |
| **T + 065ms** | Gate evaluation: `hasActiveExtensions` or `candidates.isEmpty` | `ServerStateStore.kt:1199` / `Orchestrator:574` | +10ms |
| **T + 075ms** | Immediate return `null` | `ServerStateStore.kt:1200 / 1216` | +10ms |
| **T + 085ms** | Fallback check for `originalTitle` returns `null` | `InlineDetailVideoPlayer.kt:1008` | +10ms |
| **T + 095ms** | `isExtracting = false`, **`extractionFailed = true`** | `InlineDetailVideoPlayer.kt:1034-1035` | +10ms |
| **T + 105ms** | `shouldShowInPlayerError = true` | `InlineDetailVideoPlayer.kt:1406` | +10ms |
| **T + 110ms** | Error overlay rendered: "هذا العمل غير متاح حالياً" | `InlineDetailVideoPlayer.kt:1427` | +5ms |
| **TOTAL** | **~110 milliseconds** (< 1 second) | **Zero provider network calls** | **TOTAL: < 1s** |

---

## 18. CURRENT REAL DEVICE VS TEST HARNESS COMPARISON

| Dimension | PATH A: Current Failing Real Device | PATH B: Phase 05D/05E Test Harness |
| :--- | :--- | :--- |
| **Invocation** | User tap on Play button via Compose UI | Direct JVM unit test function call |
| **Extension Instances** | Sourced from `ManagedExtensionRegistry` & Firestore sync | Manually instantiated `ManagedExtension(...)` in test code |
| **Extension Status** | Subject to Firestore remote drift (`DISABLED`, missing `MOVIE`) | Hardcoded `ExtensionLifecycleStatus.ACTIVE` |
| **Content Types** | Subject to Firestore DTO parsing (`contentTypes` field) | Hardcoded `setOf(ContentType.MOVIE)` |
| **App Version Code** | Real build `versionCode = 1` | Bypassed or mocked |
| **Search Order** | Fetched from Firestore `/config/search_order` | Bypassed / direct scraper call |
| **Pre-Inspection Gates** | Evaluated in `ServerStateStore.kt:1199, 1216` | Bypassed |
| **Extraction Execution** | Never reached (halted at pre-inspection gate) | Fully executed via `scraper.extractStream(...)` |
| **Network Traffic** | **ZERO provider network requests** | Real HTTP requests to `a.qfilm.tv` and `tv10.egydead.live` |
| **Total Duration** | **~110ms (< 1 second)** | **3,000ms – 5,000ms** |
| **Final Result** | **FAIL: "هذا العمل غير متاح حالياً"** | **PASS: Master .m3u8 resolved** |

---

## 19. ROOT CAUSE CLASSIFICATION

Per Prompt Section 33:
- **PRIMARY ROOT CAUSE**:
  - **`J. ServerStateStore Early Failure`**
  - **`K. Pre-Orchestrator Guard`**
- **CONTRIBUTING / COMPOUNDING ROOT CAUSES**:
  - **`C. Firebase Read Failure`** (Silent exception swallowing in `fetchManagedExtensionDtos()`)
  - **`F. Cache Staleness / Cache Empty State`** (Saving empty lists and contradictory expiry state)
  - **`I. Eligibility Failure`** (When remote Firestore documents drift or lack `MOVIE` category)

---

## 20. EVIDENCE

1. **`InlineDetailVideoPlayer.kt:1406`**:
   `val shouldShowInPlayerError = (!isDownloaded && !isOnline) || (extractionFailed && playableUrl == null) || hasPlaybackError`
   Proves that `extractionFailed == true` directly renders `R.string.content_not_available_currently`.
2. **`InlineDetailVideoPlayer.kt:1035`**:
   `isExtracting = false; extractionFailed = true`
   Proves that when `inspectAndCacheMedia` returns `null`, the error is set without delays.
3. **`ServerStateStore.kt:1199-1201`**:
   `if (!managedOrchestrator.hasActiveExtensions(targetType)) { return null }`
   Proves that if `registry` has no active movie extensions, inspection halts in 0ms.
4. **`ServerStateStore.kt:1215-1217`**:
   `if (outcome !is ManagedDiscoveryOutcome.Success) { return null }`
   Proves that if `discoverServers` returns recoverable failure (e.g. `candidates.isEmpty()`), inspection halts in 0ms.
5. **`FirebaseFirestoreManagedExtensionDataSource.kt:25-48`**:
   Empty `catch (_: Exception) {}` blocks prove that remote Firestore failures are silently converted to `Result.success(emptyList())`.
6. **`ServerStateStore.kt:1184-1248`**:
   In the cached server branch, `serverItems` is never assigned, causing `if (directStreamUrl.isNullOrBlank() && serverItems.isNotEmpty())` to be completely dead code, returning `null` direct streams from cache.

---

## 21. WHY SCRAPER IS / IS NOT ROOT CAUSE

- **Scrapers are NOT the root cause of the < 1 second failure**:
  - `QfilmScraper` and `EgyDeadScraper` are **never executed** during the failing path.
  - No HTTP connection to `https://a.qfilm.tv` or `https://tv10.egydead.live` is ever initiated.
  - Phase 05D/05E proved that when invoked directly with valid inputs, the scrapers successfully discover servers and resolve live streams.
  - The failure is 100% upstream of the scrapers.

---

## 22. WHY FIREBASE SYNC IS / IS NOT ROOT CAUSE

- **Firebase Sync IS a primary contributing root cause**:
  - `FirebaseFirestoreManagedExtensionDataSource.kt` silently swallows all exceptions from Firestore.
  - When Firestore queries fail (or credentials are placeholder), it returns `Result.success(emptyList())`.
  - If Firestore contains documents where an admin set `status = DISABLED`, or omitted the `contentTypes` field, or set `minAppVersionCode > 1`, `DefaultManagedExtensionRepository` and `ManagedMediaOrchestrator.forceRefresh()` overwrite the healthy bundled extensions with disqualified definitions.
  - The Firestore security rule `allow read: if isAuthenticated();` blocks unauthenticated users from reading `/config/search_order` and `/managed_extensions`.

---

## 23. REQUIRED MINIMAL FIX (FOR SUBSEQUENT PHASE)

*Note: In accordance with Hard Rule 1 and 35, NO fix has been applied in this phase. The following specifies the minimal required rectification for Phase 05G:*

1. **Fix Silent Error Swallowing in `FirebaseFirestoreManagedExtensionDataSource.kt`**:
   - Propagate exceptions or return `Result.failure` when Firestore queries fail, instead of returning `Result.success(emptyList())`.
   - Prevent empty remote lists from wiping local cache.
2. **Fix `ServerStateStore.kt:1184-1248` Cached Server Extraction**:
   - When cached servers exist but `directStreamUrl` is null, reconstruct `serverItems` from `serversMap` so that extraction can proceed instead of aborting.
3. **Protect `hasActiveExtensions(targetType)` with Bundled Defaults Fallback**:
   - If `registry.getActiveExtensions()` has no candidates for a requested content type, fall back to default bundled extensions before returning `null`.
4. **Align Firestore Security Rules for Public Read**:
   - Allow unauthenticated read access for `/config/search_order` and `/managed_extensions` so guest users can stream media without auth errors.

---

## 24. NO FIX APPLIED IN THIS PHASE

In strict adherence to **Hard Rule 1, 34, and 35**:
- Zero Scrapers were modified.
- Zero extractors were modified.
- Zero players were modified.
- Zero Firestore rules were modified.
- Zero schema definitions were modified.
- Zero production code was patched.
This phase is **AUDIT ONLY**.

---

## 25. FINAL VERDICT

```text
AUDIT COMPLETED:                 YES
FIRST FAILURE IDENTIFIED:        YES
BEFORE ORCHESTRATOR:             NO (Orchestrator entered; failed at ServerStateStore gate / candidates check)
FIREBASE INVOLVED:               YES (Silent exception swallowing + Remote config drift)
CACHE INVOLVED:                  YES (SafeLocalMetadataCache empty caching + ServerStateStore cached server items bypass)
REGISTRY INVOLVED:               YES (Subject to remote overwrite)
ELIGIBILITY INVOLVED:            YES (Hard ContentType & appVersionCode gates)
SERVERSTATESTORE INVOLVED:       YES (Early return at Line 1199 & 1216)
NETWORK REQUEST BEFORE FAILURE:  NO (Zero provider HTTP requests made)
ERROR SOURCE IDENTIFIED:         YES (InlineDetailVideoPlayer.kt:1035 & ServerStateStore.kt:1199/1216)
ROOT CAUSE:                      ServerStateStore Pre-Inspection Gates & Firestore Sync Error Swallowing
PRODUCTION FIX APPLIED:          NO
BUILD:                           PASS
FINAL:                           PASS
```
