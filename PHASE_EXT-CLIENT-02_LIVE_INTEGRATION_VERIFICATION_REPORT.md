# PHASE EXT-CLIENT-02: CINESTREAM USERS APP
## LIVE INTEGRATION VERIFICATION REPORT

---

### 1. EXECUTIVE STATUS
**PASS WITH LIMITATIONS**

- **Verification Phase**: This phase audited the real execution environment, static source code, security boundaries, and runtime contracts following PHASE EXT-CLIENT-01.
- **Critical Finding**: Core application contracts, Search Order consumption logic, Content-Type isolation, early playback initiation, and quality sufficiency policies are verified via comprehensive JVM tests (301/301 passing), static code analysis, and successful debug APK build.
- **Environment & Live Limitations**:
  1. **Physical Android Device**: Not attached in this cloud container environment (`adb devices` is empty).
  2. **Android Emulator**: Not available (`which emulator` returned `EMULATOR_NOT_FOUND`).
  3. **Live Production Firebase Credentials**: `app/google-services.json` contains placeholder configuration (`"project_id": "remixed-project-id"`). Live production Firestore cannot be queried directly in this headless environment.
  4. **Firestore Security Rules Defect Identified**: In `firestore.rules`, lines 33-35 contain `match /config/{document=**} { allow read, write: if false; }`. There is currently **no explicit read rule for `/config/search_order`**. Consequently, in live production, non-admin clients attempting to read `/config/search_order` will be rejected with `PERMISSION_DENIED` until a rule matching `/config/search_order` with `allow read: if isAuthenticated();` (or `allow read: if true;`) is deployed. Per RULE 1, RULE 2, and RULE 11, **this defect is documented and not patched in this phase**.

---

### 2. ENVIRONMENT AUDIT
- **JDK Version**: OpenJDK `21.0.12.1` (Temurin build 21.0.12.1+1-LTS, 64-Bit Server VM).
- **Gradle Version**: `Gradle 9.3.1` (Kotlin DSL 2.2.21, Groovy 4.0.29).
- **Android SDK / Compilation**:
  - `compileSdk`: 35
  - `minSdk`: 24
  - `targetSdk`: 36
- **ADB Availability**: Available at `/opt/android/sdk/platform-tools/adb`. Daemon started at tcp:5037; attached devices count: **0** (`List of devices attached` is empty).
- **Physical Android Device Availability**: **NONE** (**NOT VERIFIED**).
- **Emulator Availability**: **NONE** (`which emulator` returned `EMULATOR_NOT_FOUND`) (**NOT VERIFIED**).
- **Firebase CLI Version**: `15.30.0` (located at `/usr/local/bin/firebase`).
- **Firebase Project Identity**: `remixed-project-id` (from `app/google-services.json`).
- **Firebase Authentication Availability**: Mock / Local JVM only; live production Auth is unprovisioned in the container environment.
- **Firestore Availability**: Unprovisioned in local environment; live production Firestore is **NOT VERIFIED**.
- **Network Connectivity**: Outbound HTTP/HTTPS is operational (verified via HTTP/2 200 response from Google endpoints).
- **Production Firebase Credentials**: Placeholder credentials only (`remixed-api-key`).

---

### 3. PREVIOUS CHECKPOINT
- **PHASE EXT-CANONICAL-01**: CLOSED
- **PHASE EXT-CLIENT-01**: PASS WITH LIMITATIONS — CLOSED
- **Established Invariants**:
  - `/config/search_order` is READ-ONLY from Users App.
  - `/managed_extensions` is READ-ONLY from Users App.
  - Search Order is controlled by Admin.
  - Users App preserves Admin order deterministically (no shuffle, no priority sorting, no default injection).
  - Content-Type isolation enforced at filter and scraper levels.
  - Early playback on first playable source.
  - Background quality discovery with early stop.
  - Manual Upload/Sync removed from runtime playback dependencies.
  - Users App contains zero writes to `/extensions`.
  - 301/301 tests passed, build successful.
  - Physical Android, headless emulator, and live production Firestore were classified as NOT VERIFIED.

---

### 4. SOURCE-OF-TRUTH VERIFICATION

| Verification Item | Target | File & Line Reference | Finding | Classification |
| :--- | :--- | :--- | :--- | :--- |
| **A) Search Order Read** | `/config/search_order` | `SearchOrderDataSource.kt:27, 33` | Explicitly reads document `search_order` in collection `config`. | **STATICALLY VERIFIED** |
| **B) Managed Extensions Read** | `/managed_extensions` | `FirebaseFirestoreManagedExtensionDataSource.kt:16, 25` | Explicitly queries collection `managed_extensions`. | **STATICALLY VERIFIED** |
| **C) Search Order Zero Writes** | `/config/search_order` | Project-wide source audit | Zero occurrences of `.set()`, `.update()`, or `.delete()` targeting `search_order`. | **STATICALLY VERIFIED** |
| **D) Managed Catalog Zero Writes** | `/managed_extensions` | Project-wide source audit | Zero write operations targeting collection `managed_extensions`. | **STATICALLY VERIFIED** |
| **E) Legacy Catalog Zero Writes** | `/extensions` | Project-wide source audit | Zero write operations targeting collection `extensions`. | **STATICALLY VERIFIED** |
| **F) Reseed Prevention** | Firestore Auto-seeding | `UserSecurityManager.kt:260-285` | Legacy automatic publishing of bundled defaults during admin login is completely removed. | **STATICALLY VERIFIED** |
| **G) No Unconfigured Appending** | Search Order Appends | `PlaybackOrchestrator.kt:72-85` | Evaluates only extensions explicitly returned by `SearchOrderRepository.getOrderForContentType()`. No default extensions are ever appended. | **STATICALLY VERIFIED** |
| **H) Manual Sync Independence** | Runtime Execution | `ExtensionsScreen.kt:70-75` | Refresh button only forces a reload of remote metadata into local cache; no manual publishing required for playback. | **STATICALLY VERIFIED** |

---

### 5. FIRESTORE CONNECTIVITY
- **Status**: **NOT VERIFIED** (Live Production Firestore) / **UNIT TEST VERIFIED** (In-Memory Mock DataSource).
- **Evidence**: `app/google-services.json` contains:
  ```json
  "project_info": {
    "project_id": "remixed-project-id",
    "project_number": "1234567890"
  }
  ```
- Because credentials are placeholder values and no Google Cloud production service account is configured in the cloud container, live Firestore queries against a production cluster cannot be executed.

---

### 6. SEARCH ORDER LIVE VERIFICATION
- **Status**: **NOT VERIFIED** (Live Production Firestore) / **UNIT TEST VERIFIED** (Local Invariants).
- **Unit Test Evidence**:
  - `SearchOrderAndEligibilityTest.searchOrder_resolvesCorrectListPerContentType`: Verified that `SearchOrder` resolves `movie`, `tv`, and `anime` as independent lists without cross-contamination.
  - `SearchOrderAndEligibilityTest.searchOrder_fallsBackToSeriesIfTvIsNull`: Verified that if `tv` is absent or null, the documented backward-compatibility alias `series` is used.
  - `SearchOrderAndEligibilityTest.eligibilityFilter_preservesExactSearchOrderDeterministically`: Verified that Admin sequence `[A, B, C]` is preserved exactly (no sorting by priority, no shuffling, no local defaults).

---

### 7. SEARCH ORDER MUTATION VERIFICATION
- **Status**: **NOT VERIFIED** (Live Production Firestore) / **UNIT TEST VERIFIED** (In-Memory Cache Invalidation).
- **Evidence**: Live mutation requires an active production Firestore instance with an Admin dashboard writing changes to `/config/search_order`. Because live production credentials are unavailable in this environment, this test cannot be performed live.
- **Code Audit**: `SearchOrderRepository.clearCache()` and `getSearchOrder(forceRefresh = true)` successfully invalidate the 10-minute cache and reload fresh data on demand.

---

### 8. CONTENT TYPE ISOLATION
- **Status**: **UNIT TEST VERIFIED** and **STATICALLY VERIFIED**.
- **Evidence**:
  1. `ContentTypeResolver.kt`:
     - Resolves TMDB Movies to `ContentType.MOVIE`.
     - Resolves TMDB TV Series with Animation genre (`16`) AND Japanese origin (`ja`/`JP`) to `ContentType.ANIME`.
     - Resolves other TV Series to `ContentType.SERIES`.
  2. `ExtensionEligibilityFilter.kt`:
     - Cross-references `extension.contentTypes` AND `scraper.supportedContentTypes`.
     - `eligibilityFilter_enforcesHardContentTypeIsolation_animeOnlyNeverRunsForMovies`: PASSED (Anime-only scrapers like `witanime`, `anime4up`, `animeblkom` are rejected for movies).
     - `eligibilityFilter_enforcesHardContentTypeIsolation_seriesExcludesQfilmAndAnimeOnly`: PASSED (Movie/Anime scraper `qfilm` is rejected for series).
     - Multi-type scraper `egydead` is eligible for MOVIE, SERIES, and ANIME.

---

### 9. EXTENSION ELIGIBILITY
- **Status**: **UNIT TEST VERIFIED** and **STATICALLY VERIFIED**.
- **Criteria Verified** in `ExtensionEligibilityFilter.filterEligibleExtensions`:
  1. Exists in managed extension catalog / registry: **VERIFIED**.
  2. Has non-blank `scraperKey`: **VERIFIED**.
  3. Bundled scraper exists in `ScraperRegistry.INSTANCE`: **VERIFIED**.
  4. Lifecycle status == `ExtensionLifecycleStatus.ACTIVE`: **VERIFIED** (DISABLED and INACTIVE are excluded).
  5. `userEnabled == true`: **VERIFIED** (user preferences respected).
  6. Extension metadata supports target `ContentType`: **VERIFIED**.
  7. Bundled scraper supports target `ContentType`: **VERIFIED**.
  8. Scraper supports `ScraperCapability.SERVER_DISCOVERY`: **VERIFIED**.
  9. `runtimeApiVersion <= supportedRuntimeApiVersion`: **VERIFIED**.
  10. `minAppVersionCode <= currentAppVersionCode`: **VERIFIED**.
  11. Metadata validation via `ManagedExtensionValidator.validate()`: **VERIFIED**.

---

### 10. ADMIN METADATA MUTATION
- **Status**: **NOT VERIFIED** (Live Production Firestore) / **UNIT TEST VERIFIED** (Model & Filter Pipeline).
- **Behavioral Confirmation in Unit Tests**:
  - When extension status is mutated from `ACTIVE` to `DISABLED` / `INACTIVE`: `eligibilityFilter_excludesDisabledAndInactiveExtensions` verifies immediate exclusion from candidate execution.
  - When user toggles extension off: `testUserDisablesExtension` in `ManagedMediaOrchestratorTest` verifies immediate exclusion.

---

### 11. PLAYBACK E2E
- **Status**: **NOT VERIFIED** (Physical Android Device) / **INTEGRATION TEST VERIFIED** (PlaybackOrchestrator Pipeline).
- **Physical Device**: No device connected (`adb devices` is empty).
- **Orchestration Flow Verification**: Verified in `PlaybackOrchestratorTest.orchestratePlayback_emitsFirstPlayableEarlyAndSucceeds`:
  - `session` initialized with title, mediaId, isMovie, contentType.
  - `orchestratePlayback` triggers search, discovers servers, extracts stream.
  - `onFirstPlayableSource` emits playable source immediately.
  - `session.firstPlayableDeferred` completes immediately without awaiting complete quality enumeration.

---

### 12. FIRST PLAYABLE SOURCE
- **Status**: **INTEGRATION TEST VERIFIED** and **STATICALLY VERIFIED**.
- **Evidence**:
  - `PlaybackOrchestrator.kt:180-188`:
    ```kotlin
    if (candidatePrimarySource != null && !candidatePrimarySource.streamUrl.isNullOrBlank()) {
        if (!firstPlayableEmitted) {
            firstPlayableEmitted = true
            session.discoveredSources.add(candidatePrimarySource)
            session.firstPlayableDeferred.complete(candidatePrimarySource)
            onFirstPlayableSource(candidatePrimarySource)
        }
    ...
    ```
  - Playback begins as soon as the first server on the first successful candidate yields a playable URL. Downstream candidate extensions are not queried prior to starting playback.

---

### 13. BACKGROUND QUALITY DISCOVERY
- **Status**: **INTEGRATION TEST VERIFIED** and **STATICALLY VERIFIED**.
- **Evidence**:
  - `PlaybackOrchestrator.kt:190-205`: Discovered qualities from the primary source are accumulated into `session.discoveredQualities`.
  - If additional qualities are needed to satisfy sufficiency, subsequent candidate extensions in the Admin Search Order are inspected in the background without interrupting or restarting active playback.

---

### 14. QUALITY SUFFICIENCY
- **Status**: **UNIT TEST VERIFIED**.
- **Evidence**:
  - `QualitySufficiencyPolicy.classifyQuality`:
    - HIGH: `1080p`, `FHD`, `4K`, `2K`, `1440p`
    - MEDIUM: `720p`, `HD`
    - LOW: `480p`, `360p`, `240p`, `144p`, `SD`
    - AUTO: `Auto`
  - `QualitySufficiencyPolicy.isSufficient`:
    - Requires: HIGH && (MEDIUM || LOW).
    - Verified in `PlaybackOrchestratorTest.qualitySufficiencyPolicy_evaluatesSufficiencyCorrectly`:
      - `[]` -> false
      - `["480p"]` -> false
      - `["1080p"]` -> false
      - `["1080p", "720p"]` -> true
      - `["1080p", "480p"]` -> true
      - `["1080p", "720p", "480p"]` -> true

---

### 15. CANCELLATION
- **Status**: **INTEGRATION TEST VERIFIED** and **STATICALLY VERIFIED**.
- **Evidence**:
  1. **Early Stop Cancellation**:
     - `PlaybackOrchestrator.kt:107-111`: Once `firstPlayableEmitted` is true AND `sufficiencyPolicy.isSufficient(currentQualities)` is satisfied, the loop immediately executes `break`, cancelling any pending discovery on remaining extensions.
  2. **Player Screen Disposal**:
     - `PlayerScreen.kt:290-305`: On Composable disposal (`onDispose`), `viewModel.stopPlayback()` is called.
     - `PlayerViewModel.stopPlayback()` cancels `sessionJob`, instantly cancelling all active coroutines running in `Dispatchers.IO + session.sessionJob`.

---

### 16. USER EXPERIENCE
- **Status**: **STATICALLY VERIFIED**.
- **Evidence**:
  - **Loading UI**: Clean loader with string resource `R.string.loading` ("جاري التحميل..."). No scraper name, extension ID, or internal URL is displayed.
  - **Error Prompt**: When discovery fails completely or no candidate matches, the error message returned to the user is strictly "تعذر تشغيل هذا المحتوى" (localized) with retry affordance ("إعادة المحاولة").
  - **Zero Technical Leakage**: Scraper keys (`egydead`, `witanime`, `qfilm`), HTTP codes (403, 502), and internal exceptions are logged solely via internal Android `Log.d` / `Log.w` with no display on Compose UI.

---

### 17. LEGACY `/extensions`
- **Status**: **STATICALLY VERIFIED** and **UNIT TEST VERIFIED**.
- **Evidence**:
  - **Zero Writes**: `FirebaseFirestoreManagedExtensionDataSource.kt` contains zero `.set()`, `.add()`, `.update()`, or `.delete()` calls to `extensions`.
  - **Read Fallback Only**: `extensions` is queried only as a secondary read fallback if an extension is absent from `managed_extensions`.
  - **Search Order Isolation**: `/config/search_order` is an independent document in `/config`. Legacy documents in `/extensions` cannot alter or inject themselves into the Search Order.
  - **Integrity**: Collection `/extensions` has **not been deleted**, its rules have not been modified, and its Firestore documents remain intact.

---

### 18. BUNDLED EXTENSION RESURRECTION
- **Status**: **STATICALLY VERIFIED**.
- **Evidence**:
  - In `UserSecurityManager.kt`, lines 260-285: The legacy method that automatically re-seeded bundled extensions to Firestore upon Admin authentication has been completely removed.
  - No code in the application writes default scrapers to Firestore automatically. Admin configuration is authoritative and cannot be overridden by bundled client defaults.

---

### 19. MANUAL SYNC
- **Status**: **STATICALLY VERIFIED**.
- **Evidence**:
  - Runtime playback does not require invoking "رفع للإدارة" or manual sync actions.
  - The refresh action in `ExtensionsScreen.kt` calls `viewModel.refreshExtensions(force = true)`, which strictly pulls the latest documents from Firestore into local cache without writing or uploading anything to Firestore.

---

### 20. REAL SCRAPER VERIFICATION

| Scraper Key | Implementation Class | Supported ContentTypes | Status / Test Suite |
| :--- | :--- | :--- | :--- |
| `egydead` | `EgyDeadScraper` | `MOVIE`, `SERIES`, `ANIME` | **UNIT TEST VERIFIED** (`EgyDeadScraperUnitTest`: 15/15 passed) |
| `witanime` | `WitanimeScraper` | `ANIME` | **UNIT TEST VERIFIED** (`WitanimeScraperUnitTest`: 27/27 passed) |
| `anime4up` | `Anime4UpScraper` | `ANIME` | **UNIT TEST VERIFIED** (`Anime4UpScraperUnitTest`: 14/14 passed) |
| `animeblkom` | `AnimeBlkomScraper` | `ANIME` | **UNIT TEST VERIFIED** (`AnimeBlkomScraperUnitTest`: 14/14 passed) |
| `qfilm` | `QfilmScraper` | `MOVIE`, `ANIME` | **UNIT TEST VERIFIED** (`QfilmScraperUnitTest`: 24/24 passed) |

*Note: Live network extraction against 3rd-party websites is dependent on external DNS, Cloudflare challenges, and live host availability. All bundled scrapers have full offline unit tests with mock HTML payloads verifying selectors, regex parsers, and server decoders.*

---

### 21. REGRESSION TESTS
- **Task**: `gradle :app:testDebugUnitTest --tests "com.example.extension.managed.*"`
- **Total Tests Executed**: **301**
- **Passed**: **301** (100%)
- **Failed**: **0**
- **Skipped**: **0**
- **Ignored**: **0**
- **Comparison Against Checkpoint**: Count remains exactly **301 / 301**, matching the checkpoint at the conclusion of PHASE EXT-CLIENT-01 with zero regressions.

---

### 22. BUILD VERIFICATION
- **Gradle Task**: `:app:assembleDebug`
- **Build Status**: **SUCCESSFUL**
- **APK Path**: `app/build/outputs/apk/debug/app-debug.apk`
- **APK Size**: `40,494,565 bytes` (~38.6 MB)
- **SHA-256**: `0cfc8963ed264da5ebdbc63b69400f9eed410024b24d21cc4bb911f1fcf833c4`

---

### 23. SECURITY VERIFICATION
- **Dynamic Code Loading Scan**: **STATICALLY VERIFIED** (CLEAN). Zero occurrences of `DexClassLoader`, `PathClassLoader`, `dalvik.system`, or dynamic bytecode loading.
- **Reflection Scan**: Zero dynamic reflection-based scraper instantiations. All scrapers are statically registered in `ScraperRegistry.INSTANCE`.
- **Search Order Write Scan**: **STATICALLY VERIFIED** (CLEAN). Users App contains zero writes to `/config/search_order`.
- **Managed Extensions Write Scan**: **STATICALLY VERIFIED** (CLEAN). Users App contains zero writes to `/managed_extensions`.
- **Legacy Extensions Write Scan**: **STATICALLY VERIFIED** (CLEAN). Users App contains zero writes to `/extensions`.

---

### 24. FIRESTORE RULES AUDIT
- **Inspection of `firestore.rules`**:
  - `match /managed_extensions/{extensionId}`: `allow read: if isAuthenticated(); allow write: if isAdmin();` -> **READ-ONLY for Users App**.
  - `match /extensions/{extensionId}`: `allow read: if isAuthenticated(); allow write: if isAdmin();` -> **READ-ONLY for Users App**.
  - `match /config/app`: `allow read: if true; allow write: if isAdmin();` -> **READ-ONLY for Users App**.
  - `match /config/{document=**}`: `allow read, write: if false;` -> **DENIES ALL ACCESS**.
- **Defect Identified**:
  - Document `/config/search_order` has **no explicit allow rule** and falls under `match /config/{document=**}`, which denies all reads and writes.
  - In a live deployment with these exact security rules, any client attempt to fetch `/config/search_order` will fail with Firestore `PERMISSION_DENIED`.
  - In accordance with RULE 2 and RULE 11, **the rules file was NOT modified in this phase**. This defect is documented for remediation in the Security Rules phase.

---

### 25. VERIFICATION MATRIX

| Area | Evidence | Classification | Result |
| :--- | :--- | :--- | :--- |
| **Search Order read** | `FirebaseSearchOrderDataSource.kt` line 33 | **STATICALLY VERIFIED** | **PASS** |
| **Search Order order preservation** | `SearchOrderAndEligibilityTest.kt` line 80 | **UNIT TEST VERIFIED** | **PASS** |
| **Search Order mutation** | `SearchOrderRepository.kt` cache invalidation | **UNIT TEST VERIFIED** | **PASS** |
| **Movie isolation** | `ExtensionEligibilityFilter.kt` / `SearchOrderAndEligibilityTest.kt` | **UNIT TEST VERIFIED** | **PASS** |
| **Series isolation** | `ExtensionEligibilityFilter.kt` / `SearchOrderAndEligibilityTest.kt` | **UNIT TEST VERIFIED** | **PASS** |
| **Anime isolation** | `ExtensionEligibilityFilter.kt` / `SearchOrderAndEligibilityTest.kt` | **UNIT TEST VERIFIED** | **PASS** |
| **Extension eligibility** | `ExtensionEligibilityFilter.kt` lines 30-100 | **UNIT TEST VERIFIED** | **PASS** |
| **Admin status mutation** | `ExtensionEligibilityFilter.kt` inactive status check | **UNIT TEST VERIFIED** | **PASS** |
| **Admin baseUrl mutation** | `ManagedExtension.baseUrl` passed to scraper | **STATICALLY VERIFIED** | **PASS** |
| **Admin contentTypes mutation** | Filter re-evaluates `targetContentType in extension.contentTypes` | **UNIT TEST VERIFIED** | **PASS** |
| **First playable source** | `PlaybackOrchestratorTest.kt` line 180 | **INTEGRATION TEST VERIFIED** | **PASS** |
| **Early playback** | `PlaybackOrchestrator.kt` line 186 | **INTEGRATION TEST VERIFIED** | **PASS** |
| **Background discovery** | `PlaybackOrchestrator.kt` line 190 | **INTEGRATION TEST VERIFIED** | **PASS** |
| **Quality sufficiency** | `PlaybackOrchestratorTest.kt` line 45 | **UNIT TEST VERIFIED** | **PASS** |
| **Cancellation** | `PlaybackOrchestrator.kt` line 110 (loop break) | **INTEGRATION TEST VERIFIED** | **PASS** |
| **Player disposal** | `PlayerScreen.kt` `onDispose { viewModel.stopPlayback() }` | **STATICALLY VERIFIED** | **PASS** |
| **User-facing errors** | `PlayerViewModel.kt` localized error strings | **STATICALLY VERIFIED** | **PASS** |
| **`/extensions` zero-write** | Grep audit across `app/src/main/` | **STATICALLY VERIFIED** | **PASS** |
| **`/extensions` non-authoritative** | Secondary fallback only, never injected to Search Order | **STATICALLY VERIFIED** | **PASS** |
| **Bundled reseed prevention** | `UserSecurityManager.kt` legacy code removed | **STATICALLY VERIFIED** | **PASS** |
| **Manual sync independence** | Playback independent of manual publishing | **STATICALLY VERIFIED** | **PASS** |
| **Real scraper playback** | 5 bundled scraper test suites (94 total tests) | **UNIT TEST VERIFIED** | **PASS** |
| **Full test suite** | 34 test suites, 301 total tests | **UNIT TEST VERIFIED** | **PASS** |
| **Build** | Gradle task `:app:assembleDebug` | **INTEGRATION TEST VERIFIED** | **PASS** |
| **Security scan** | Zero dynamic loaders, zero reflection | **STATICALLY VERIFIED** | **PASS** |
| **Production Firestore** | Placeholder credentials in `google-services.json` | **NOT VERIFIED** | **LIMITATION** |
| **Physical Android** | Zero devices connected via ADB | **NOT VERIFIED** | **LIMITATION** |
| **Emulator** | No emulator binary or instance in container | **NOT VERIFIED** | **LIMITATION** |

---

### 26. FAILURES & LIMITATIONS

#### Finding 1: Firestore Security Rules Rule Missing for `/config/search_order`
- **What**: `firestore.rules` blocks `/config/search_order`.
- **Where**: `firestore.rules:33-35`.
- **How Verified**: Static inspection of security rules.
- **Expected**: `match /config/search_order { allow read: if isAuthenticated(); allow write: if isAdmin(); }` or `allow read: if true;`.
- **Actual**: `match /config/{document=**} { allow read, write: if false; }` denies access to `/config/search_order`.
- **Severity**: **HIGH (BLOCKER FOR LIVE PRODUCTION)**. In local JVM unit tests, mock sources bypass rules; but against real Firestore, clients will receive `PERMISSION_DENIED`.
- **Recommended Action**: Update `firestore.rules` in a subsequent rules phase to explicitly permit authenticated reads on `/config/search_order`. Per RULE 2, **NOT patched in this phase**.

#### Finding 2: Unprovisioned Production Credentials in Container
- **What**: `app/google-services.json` contains placeholder configuration (`remixed-project-id`).
- **Where**: `app/google-services.json:3`.
- **How Verified**: Inspection of JSON configuration.
- **Severity**: **MEDIUM (ENVIRONMENT LIMITATION)**.
- **Recommended Action**: Configure real Firebase credentials when ready for staging/production deployment.

#### Finding 3: Absence of Physical Android Device & Emulator
- **What**: Headless cloud container environment without display, physical hardware, or emulator.
- **Where**: Container OS environment.
- **How Verified**: `adb devices` (empty), `which emulator` (not found).
- **Severity**: **ENVIRONMENT LIMITATION**.
- **Recommended Action**: Hardware testing on physical devices should occur during device qualification.

---

### 27. OUT OF SCOPE
- Modifying `firestore.rules` (strictly forbidden by RULE 2).
- Modifying source code (strictly forbidden by RULE 1).
- Modifying or deleting Firestore collections/documents (strictly forbidden by RULE 4 and RULE 5).
- Removing legacy `/extensions` collection (forbidden by RULE 6).
- Modifying Admin App code or configuration.

---

### 28. FINAL VERDICT
**PASS WITH LIMITATIONS**

- **Justification**:
  1. All 301 unit and integration tests covering the Search Order, Content-Type isolation, and playback orchestration pass without errors or regressions.
  2. The Android debug APK builds cleanly with zero errors.
  3. Security analysis confirms zero dynamic code execution, zero reflection, and zero writes to `/config/search_order`, `/managed_extensions`, or `/extensions`.
  4. Core client contracts are fully verified. However, physical Android playback, emulator execution, and live production Firestore queries are classified as **NOT VERIFIED** due to cloud container environment constraints.
  5. A security rule gap in `firestore.rules` blocking `/config/search_order` was identified and documented for remediation prior to live production release.

---

### 29. CHECKPOINT STATUS

- **PHASE EXT-CANONICAL-01**: **CLOSED**
- **PHASE EXT-CLIENT-01**: **CLOSED**
- **PHASE EXT-CLIENT-02**: **PASS WITH LIMITATIONS — CLOSED**

#### Final Determinations:
- **Is Users App ready for EXT-CUTOVER-01?**: **YES, WITH RULES PREREQUISITE**. The client-side code adheres strictly to canonical Search Order contracts, hard Content-Type isolation, and early playback orchestration.
- **Can `/extensions` safely proceed toward deprecation?**: **YES**. The Users App has zero write operations to `/extensions` and only reads it as a non-authoritative fallback if an extension is absent from `/managed_extensions`.
- **Remaining Production/Runtime Blocker**:
  - The Firestore Security Rule for `/config/search_order` must be added before enabling live production traffic to prevent `PERMISSION_DENIED` errors on client startup.
