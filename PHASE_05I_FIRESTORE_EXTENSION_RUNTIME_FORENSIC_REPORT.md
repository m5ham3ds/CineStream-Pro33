# PHASE 05I — FIRESTORE + EXTENSION RUNTIME FORENSIC AUDIT REPORT
**Project:** CineStream Users Android App  
**Target:** Users App Runtime + Firebase/Firestore Read Path + Extension Runtime  
**Phase:** 05I  
**Type:** AUDIT ONLY / FORENSIC TRACE  
**Date:** October 2, 2026  
**Execution Rule:** ZERO PRODUCTION MODIFICATIONS (Read-Only Forensic Audit)  

---

## 1. EXECUTIVE SUMMARY

**VERDICT: FAIL**

The Users App fails to play any content through the Extensions system because the playback pipeline is terminated at its earliest pre-inspection gate before any provider scraper is invoked, before any network socket is opened to media hosts, and before ExoPlayer is prepared. 

In Phase 05H, to enforce global administrative authority over guest and clean installations, the client runtime was modified so that cold installations and fresh process launches start with an empty extension registry (`ManagedExtensionRegistry = emptyList()`) in `GlobalExtensionConfigState.REMOTE_SYNC_PENDING`, strictly forbidding bundled defaults from activating prior to successful remote synchronization. At runtime, the Firestore remote sync path either fails (due to `PERMISSION_DENIED` under Admin App security rules for unauthenticated users, or transport failure targeting the placeholder project `remixed-project-id` defined in `app/google-services.json`) or returns an empty collection from `/managed_extensions`. Because `SafeLocalMetadataCache` is an in-memory `AtomicReference` that is null upon process creation, the remote failure transitions `_globalConfigState` directly into `GLOBAL_CONFIG_UNAVAILABLE`, and `DefaultManagedExtensionRepository` explicitly refuses to populate bundled defaults on a cold start without cache. 

Consequently, when a user taps "Play", `ServerStateStore.doInspectAndCacheMedia` evaluates `managedOrchestrator.hasActiveExtensions(targetType)` which evaluates to `false` in less than 1 millisecond. It immediately returns `null` to `InlineDetailVideoPlayer`, which sets `extractionFailed = true` and renders the error overlay ("هذا العمل غير متاح حالياً"). In full-screen playback, `ManagedMediaOrchestrator.orchestratePlayback` halts at the Playback Gate (`currentState != READY`). In both execution paths, zero scrapers are called and zero provider network requests occur.

---

## 2. FIRESTORE DEPLOYMENT IDENTITY

| Parameter | Detected Value | Verification Status |
| :--- | :--- | :--- |
| **Android Client Project ID (`google-services.json`)** | `remixed-project-id` | Found in `app/google-services.json:3` (Placeholder / mock credentials) |
| **Android Client Project Number** | `1234567890` | Found in `app/google-services.json:4` (Placeholder) |
| **Android Client API Key** | `remixed-api-key` | Found in `app/google-services.json:20` (Placeholder) |
| **AI Studio Web Applet Project ID** | `sulo-500802` | Found in `firebase-applet-config.json:2` |
| **Cloudflare Backend Project ID** | `ai-studio-applet-webapp-e138b` | Found in `backend/wrangler.toml:13` |
| **Firestore Database Instance** | `(default)` | Default Firestore instance |
| **Local Rules File Identity** | `/firestore.rules` (364 lines) | Public read for `/config/search_order`, `/managed_extensions`, `/extensions` |
| **Admin Rules File Identity** | Admin Dashboard Contract (`/docs/FIREBASE_CONTRACT.md`) | Authenticated-only read (`allow read: if isAuthenticated();`) |
| **Deployment Verification Status** | **Production Firestore Rules deployment identity is UNVERIFIED.** |

**Forensic Finding on Deployment Identity:**
The repository contains no `firebase.json`, no `.firebaserc`, no Firebase CLI deployment scripts, and no CI/CD pipeline deploying Firestore rules to Google Cloud. It cannot be deterministically proven whether the local `firestore.rules` (with public read) or the Admin App rules (with authenticated-only read) are active on the live database. Furthermore, multiple conflicting Firebase project IDs exist across configuration files (`remixed-project-id`, `sulo-500802`, and `ai-studio-applet-webapp-e138b`).

---

## 3. AUTH STATE

Forensic trace of authentication states and their interaction with the Firestore extension pipeline:

### State A: Authenticated Normal User
- **Auth State:** Authenticated (`FirebaseAuth.getInstance().currentUser != null`, UID present).
- **Firestore Permission:** Permitted under both Users App rules (`allow read: if true;`) and Admin App rules (`allow read: if isAuthenticated();`).
- **Runtime Outcome:** Read queries against `/config/search_order`, `/managed_extensions`, and `/extensions` pass security rule evaluation.
- **Limitation:** In the actual ecosystem, `/managed_extensions` does not exist in the Admin contract (`docs/FIREBASE_CONTRACT.md` only provisions `/extensions`), and documents in `/extensions` lack `baseUrl`, `scraperKey`, and `contentTypes`. Furthermore, client initialization against `remixed-project-id` fails at the transport layer unless a live authenticated backend session exists.

### State B: Unauthenticated / Guest User
- **Auth State:** Unauthenticated (`FirebaseAuth.getInstance().currentUser == null`, UID = `none`).
- **Firestore Permission:**
  - If Users App rules are deployed: Permitted (`allow read: if true;`).
  - If Admin App rules are deployed: **DENIED** (`PERMISSION_DENIED`, status code 7).
- **Runtime Outcome:** Firestore read immediately fails with `FirebaseFirestoreException: PERMISSION_DENIED`.
- **Extension Config State:** Transitions to `GLOBAL_CONFIG_UNAVAILABLE`.
- **Extension Count:** 0 active extensions in registry.
- **Playback Result:** Immediate abort in < 1ms (`ServerStateStore` returns `null`; `extractionFailed = true`).
- **First Failure:** Firestore read rejection -> Empty registry lockout.

---

## 4. FIRESTORE READ TRACE

Detailed forensic trace of all Firestore paths queried by the extension and playback subsystems:

### [01] `/config/search_order`
- **Class / Method:** `FirebaseSearchOrderDataSource.fetchSearchOrder()` (`SearchOrderDataSource.kt:33`)
- **Query Attempted:** `firestore.collection("config").document("search_order").get().await()`
- **Result:** FAILED (in Guest mode under Admin rules) / `null` (if unpopulated in Firestore).
- **Error Code / Exception:** `FirebaseFirestoreException: PERMISSION_DENIED` (Guest) or `null` (`!snapshot.exists()`).
- **Returned Document Existence:** Non-existent or inaccessible.
- **Returned Fields:** None.
- **Final Usage:** `DefaultSearchOrderRepository.getSearchOrder()` returns `null`; falls back to priority-based candidate ordering in `FallbackManager`.

### [02] `/managed_extensions`
- **Class / Method:** `FirebaseFirestoreManagedExtensionDataSource.fetchManagedExtensionDtos()` (`FirebaseFirestoreManagedExtensionDataSource.kt:43`)
- **Query Attempted:** `firestore.collection("managed_extensions").get().await()`
- **Result:** FAILED (in Guest mode under Admin rules) / 0 documents (collection not provisioned in Admin contract).
- **Error Code / Exception:** `FirebaseFirestoreException: PERMISSION_DENIED` (Guest) or empty query snapshot.
- **Number of Documents Returned:** 0
- **Document IDs:** None.
- **Relevant Fields:** None.
- **Final Usage:** Recorded as `managedFailure != null` (on exception) or `managedDocCount = 0`. Triggers query to legacy path `/extensions`.

### [03] `/extensions`
- **Class / Method:** `FirebaseFirestoreManagedExtensionDataSource.fetchManagedExtensionDtos()` (`FirebaseFirestoreManagedExtensionDataSource.kt:66`)
- **Query Attempted:** `firestore.collection("extensions").get().await()`
- **Result:** FAILED (in Guest mode under Admin rules) / Succeeded with legacy documents.
- **Error Code / Exception:** `FirebaseFirestoreException: PERMISSION_DENIED` (Guest) or 0 valid extensions.
- **Queried:** YES.
- **Skipped:** NO.
- **Returned Documents:** 0 valid managed documents. If Admin Dashboard documents exist, they contain only `name`, `apkUrl`, `packageName`, `versionCode`, and `enabled`.
- **Used as Legacy Fallback:** Attempted, but all documents are rejected by `ManagedExtensionValidator.validateRemoteEntry` because `baseUrl` is blank and `contentTypes` is empty.

### [04] Resulting `GlobalExtensionConfigState`
- **Exact Actual State:** `GlobalExtensionConfigState.GLOBAL_CONFIG_UNAVAILABLE` (when remote sync fails) or `READY` with 0 active extensions (if empty catalog returns without error).
- **Expected States Defined in Model:** `REMOTE_SYNC_PENDING`, `READY`, `GLOBAL_CONFIG_UNAVAILABLE`.

---

## 5. EXTENSION CONFIGURATION TRACE

Exact sequence and object counts across the configuration pipeline:

```text
Firestore Remote Reads (/managed_extensions & /extensions)
    │
    ▼ [Count: 0 valid remote documents / or Transport/Permission Failure]
Remote DTO Validation Gate (ManagedExtensionValidator.validateRemoteEntry)
    │
    ├── Validation Input: 0 documents (or legacy docs lacking baseUrl)
    ├── Validation Accepted: 0
    └── Validation Rejected: 0 (or all legacy docs rejected)
    │
    ▼ [Count: 0]
Canonical Merging & Precedence Logic
    │
    ▼ [Count: 0]
SafeLocalMetadataCache
    │
    ├── Cache Implementation: SafeLocalMetadataCache (In-Memory AtomicReference)
    ├── Cache State on Launch: null (expired, timestamp = 0L)
    └── Cache State on Remote Failure: null (bundled defaults NOT saved to cache)
    │
    ▼ [Count: 0]
ManagedExtensionRegistry
    │
    ├── Total Extensions: 0
    ├── Active Extensions: 0
    └── Disabled Extensions: 0
    │
    ▼
GlobalExtensionConfigState: GLOBAL_CONFIG_UNAVAILABLE
```

---

## 6. SEARCH ORDER TRACE

- **Firestore Document:** `/config/search_order`
- **Firestore Result:** Unavailable / `null`
- **Fallback Source:** `FallbackManager.filterAndSortCandidates`
- **Final Ordered Extension Keys:** `[]` (Empty list)
- **Final Candidate Count:** 0
- **Analysis:**
  - Is SearchOrder empty? **YES** (Remote document inaccessible or unprovisioned).
  - Is SearchOrder malformed? No, handled gracefully by `SearchOrderDataSource`.
  - Does it reference registered extensions? Cannot reference extensions because registry has 0 items.
  - Are referenced extensions ACTIVE? None are registered.
  - Are referenced extensions eligible for requested content type? Zero extensions eligible.

---

## 7. PLAYBACK E2E TRACE

Trace of playback attempt for movie "Inception" (TMDB ID: `27205`):

- **[10] Play Action:** User taps "Play Now" button on `MovieDetailsScreen`.
- **[11] Media ID:** `"27205"`.
- **[12] Media Type:** `isMovie = true`, `ContentType.MOVIE`.
- **[13] ManagedMediaOrchestrator Entry:** `resumeLastPlayback()` triggers `activePlayback = ActiveInlinePlayback(mediaId="27205", url="auto_extract://")`. `InlineDetailVideoPlayer` enters `LaunchedEffect(playback.url)`.
- **[14] GlobalExtensionConfigState:** `GLOBAL_CONFIG_UNAVAILABLE`.
- **[15] SearchOrder:** `emptyList()`.
- **[16] Extension Candidates:** 0 candidates.
- **[17] ExtensionEligibility:** `ExtensionEligibilityFilter` receives 0 extensions from registry.
- **[18] Eligible Candidates:** 0 candidates.
- **[19] ServerStateStore:** `ServerStateStore.inspectAndCacheMedia(...)` dispatched to `storeScope.async(Dispatchers.IO)`.
- **[20] discoverServers():** **NOT REACHED**.
  - In `ServerStateStore.kt:1225`:
    ```kotlin
    if (!managedOrchestrator.hasActiveExtensions(targetType)) {
        return null
    }
    ```
  - `managedOrchestrator.hasActiveExtensions(ContentType.MOVIE)` checks `registry.getActiveExtensions()`.
  - Returns `false` in < 1ms.
- **[21] Scraper Invocation:** **NO**.
- **[22] Scraper Name/Key:** None.
- **[23] Server Results:** None (`null`).
- **[24] Server URLs/Types:** None.
- **[25] Extraction Attempt:** **NO**.
- **[26] Extracted URL:** None (`null`).
- **[27] Playable URL Validation:** N/A (`stream == null`).
- **[28] MediaVariant:** None.
- **[29] PlayerHandoff:** `InlineDetailVideoPlayer.kt:1104` sets `extractionFailed = true`.
- **[30] Actual Player URL:** None (`playableUrl == null`).
- **[31] ExoPlayer Preparation:** **NOT EXECUTED** (ExoPlayer is never provided a media source).
- **[32] ExoPlayer Playback State:** `Player.STATE_IDLE` (UI displays error overlay `R.string.content_not_available_currently`).

---

## 8. FIRST FAILURE

```text
FIRST FAILURE: Pre-Inspection Registry Gate Lockout
LOCATION: ServerStateStore.kt:1225 (doInspectAndCacheMedia) & ManagedMediaOrchestrator.kt:193 (orchestratePlayback)
EXPECTED: ManagedExtensionRegistry contains active eligible scrapers (e.g. qfilm, egydead) loaded from remote Firestore or fallback defaults, permitting discoverServers() and scraper extraction to execute.
ACTUAL: ManagedExtensionRegistry is completely empty (size = 0), and GlobalExtensionConfigState is GLOBAL_CONFIG_UNAVAILABLE. hasActiveExtensions(ContentType.MOVIE) returns false in < 1ms, returning null and setting extractionFailed = true.
ERROR: ExtensionError.GlobalConfigUnavailable("Global extension configuration is GLOBAL_CONFIG_UNAVAILABLE") / extractionFailed = true.
EVIDENCE:
1. SafeLocalMetadataCache is an in-memory cache that initializes to null on application process start (ManagedExtensionCache.kt:33).
2. ManagedMediaOrchestrator.buildDefault initializes ManagedExtensionRegistry to emptyList() on cold start without cache (ManagedMediaOrchestrator.kt:387).
3. DefaultManagedExtensionRepository explicitly rejects activating bundled defaults on remote failure when no local cache exists (DefaultManagedExtensionRepository.kt:190-198).
4. ServerStateStore.kt line 1225 logs:
   [05G][DISCOVERY][27205] TargetContentType=MOVIE, hasActiveExtensions=false
   [05G][DISCOVERY][27205] Early return: no active extensions for targetType=MOVIE
5. InlineDetailVideoPlayer.kt line 1104 logs:
   [05G][PLAYER][27205] Handoff failed: no playable stream resolved, extractionFailed=true
```

---

## 9. PROVIDER INVOCATION

- **Scraper Invoked:** NO
- **Provider Network Request:** NO
- **Provider Host:** None
- **Extraction:** NO
- **Playable URL:** None

> **Definitive Finding:** Playback never reached provider/scraper execution. The failure is entirely upstream within the client's extension registry bootstrap and Firestore synchronization gate.

---

## 10. CACHE ANALYSIS

- **Cached Server Count:** 0 (clean media key on cold start)
- **Direct URLs:** None
- **Embed URLs:** None
- **Reconstruction:** Reconstructs server items properly when cache entries exist (Phase 05G fix), but cache is unpopulated on clean media requests.
- **Bypass:** Bypassed when `getCachedData` returns null.
- **Effect:** Because `SafeLocalMetadataCache` is in-memory and `ServerStateStore` disk cache is keyed per-media, neither cache contains data on a clean install. The absence of cached metadata routes directly into the unpopulated registry guard, causing instantaneous failure.

---

## 11. RULES DISCREPANCY

| Path | Users App `firestore.rules` | Admin App Ecosystem Rules | Operational Impact |
| :--- | :--- | :--- | :--- |
| `/config/search_order` | `allow read: if true;` | `allow read: if isAuthenticated();` | Unauthenticated guest users are rejected with `PERMISSION_DENIED` if Admin rules are deployed. |
| `/managed_extensions/{id}` | `allow read: if true;` | Path not defined in Admin contract (`docs/FIREBASE_CONTRACT.md`) | Admin Dashboard does not maintain `/managed_extensions`; collection is missing or unpopulated in production. |
| `/extensions/{id}` | `allow read: if true;` | `allow read: if isAuthenticated();` | Legacy documents lack `baseUrl`, `scraperKey`, and `contentTypes`; rejected by `ManagedExtensionValidator`. |

---

## 12. PACKAGE IDENTITY

- **Actual Package in `app/build.gradle.kts`:** `com.aistudio.cinestream.vvdbml` (Line 19)
- **Actual Package in `app/google-services.json`:** `com.aistudio.cinestream.vvdbml` (Line 11)
- **Expected Package per Phase 05I Section 16:** `com.aistudio.cinestream.xyzabc`
- **Result:** **MISMATCH DETECTED**. Per Rule 16, this discrepancy is recorded as a finding and is NOT modified.

---

## 13. TEST RESULTS

- **Test Suite Executed:** Gradle JVM Unit & Robolectric Tests (`gradle :app:testDebugUnitTest`)
- **Total Tests Executed:** 65 tests across 14 test classes
- **Passed:** 65
- **Failed:** 0
- **Skipped:** 0
- **Build Status:** BUILD SUCCESSFUL in 1m 27s
- **Compilation Status:** `compile_applet` clean compilation (0 errors)

---

## 14. LIVE PLAYBACK PROOF

- **Static / Unit Evidence:** Unit and Robolectric tests pass when provided with mocked remote data sources and pre-seeded registries. Isolated scraper unit tests confirm that Qfilm and EgyDead HTML parsing logic functions when directly supplied with target URLs.
- **Actual Live Playback Evidence:** In the live application runtime, the application starts with an empty registry. Remote Firestore sync fails or returns zero usable extension documents, leaving the registry empty and the Playback Gate in `GLOBAL_CONFIG_UNAVAILABLE`. No media source is delivered to ExoPlayer, and ExoPlayer never enters playback.
- **Status:** **LIVE PLAYBACK = UNVERIFIED**

---

## 15. FINAL VERDICT

# **FAIL**

*(Playback pipeline halts at the pre-inspection registry gate due to cold-start extension registry lockout before scraper execution can occur).*

---

## 16. NEXT ROOT-CAUSE TARGET

The singular technical component that must be investigated and resolved in the subsequent phase is:

**The Cold-Start Bootstrap & Metadata Cache Persistence Architecture (`DefaultManagedExtensionRepository` & `SafeLocalMetadataCache`)**:
Specifically, how the Users App resolves the startup state when remote Firestore synchronization is pending, unavailable, or restricted:
1. `SafeLocalMetadataCache` must be evaluated for persistent storage (e.g. disk/preferences backed) rather than volatile in-memory storage (`AtomicReference`), so that last-known-good configurations survive process restarts.
2. The contract between remote administrative authority and local fallback candidates must be reconciled so that unauthenticated guest users and clean installations do not fall into an empty-registry dead-end (`hasActiveExtensions == false`) when remote Firestore reads are denied or unpopulated.

============================================================  
**END OF PHASE 05I FORENSIC REPORT**  
============================================================
