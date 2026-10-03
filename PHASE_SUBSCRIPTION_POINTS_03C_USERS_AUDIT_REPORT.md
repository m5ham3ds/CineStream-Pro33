# PHASE SUBSCRIPTION-POINTS-03C — CINESTREAM USERS APP
# CROSS-APP FIRESTORE CONSUMPTION & SECURITY AUDIT
# USERS CONSUMPTION PLANE — AUDIT ONLY

============================================================
1. EXECUTIVE SUMMARY
============================================================

* **Audit Name:** Cross-App Firestore Consumption & Security Audit
* **Project Target:** CineStream Users App
* **Plane Identity:** Execution / Consumption Plane
* **Phase Identity:** SUBSCRIPTION-POINTS-03C
* **Audit Methodology:** Static Code Scan, Security Rules Analysis, Data Layer Trace, Component Independence Audit, Test Execution Verification.
* **Audit Mode:** STRICT READ-ONLY / AUDIT ONLY (Zero source code modifications, zero rule edits, zero bug fixes performed).

### Summary of Audit Findings:
1. **Canonical Contract Alignment:** The CineStream Users App successfully operates as a pure **Execution / Consumption Plane**. It reads subscription and economic state established by the Admin App (Management Plane) through Firestore.
2. **Quality Decoupling Complete:** The core business invariant `SUBSCRIPTION BENEFIT = REMOVE_ADS ONLY` is fully enforced. Video playback quality and download quality are strictly governed by source availability and independent technical restrictions. There is **ZERO** subscription-to-quality coupling across the entire codebase.
3. **Zero Economic Authority:** The Users App possesses zero authority to mint points, modify ledgers, approve Pro requests, or self-activate subscriptions. All points balance fields (`pointsBalance`, `totalPointsEarned`, `totalPointsSpent`) are read-only.
4. **Discovered Firestore Rules / Client Contract Mismatches:**
   * `/config/{document=**}` explicitly denies read access to `/config/features` and `/config/economy`.
   * `/pro_requests` lacks Firestore security rules, blocking pending client requests.
   * `/users/{uid}/point_transactions` lacks Firestore security rules, blocking transaction history reads.
   * `/reward_tasks` and `/leaderboard` lack security rules in the current `firestore.rules`.
   * Current `/users/{uid}` update rule does not include `pointsBalance` in its restricted field blacklist (though the client code never issues such writes).
   * Thanks to defensive repository design, the Users App falls back safely to default models without crashing, but cloud synchronization for these paths remains blocked at the rules boundary.
5. **Final Verdict:** **PASS WITH LIMITATIONS** (Codebase architecture passes all consumption and decoupling requirements; backend/rules alignment is required to unlock full cloud synchronization).

============================================================
2. SCOPE OF AUDIT
============================================================

The audit inspected the complete CineStream Users App repository across the following planes:
* **Configuration & Security Rules:** `/firestore.rules`, `app/google-services.json`, `metadata.json`.
* **Domain & Data Layer:** `SubscriptionModels.kt`, `EconomyModels.kt`, `UserRestrictions.kt`, `UserSecurityManager.kt`, `AuthRepository.kt`, `PointsRepository.kt`, `EconomyConfigRepository.kt`.
* **UI Components & Screens:** `SubscriptionScreen.kt`, `AccountSettingsScreen.kt`, `ProfileScreen.kt`, `PlayerScreen.kt`, `InlineDetailVideoPlayer.kt`, `SmartDownloadQualityDialog.kt`.
* **Playback & Extension Framework:** `PlaybackOrchestrator.kt`, `UnifiedDownloadCoordinator.kt`, `ControlledManagedExtensionRuntime.kt`, `WebExtractionEngine.kt`, and the 5 scrapers (`Anime4UpScraper.kt`, `EgyDeadScraper.kt`, `QfilmScraper.kt`, `AnimeBlkomScraper.kt`, `WitanimeScraper.kt`).
* **Utility Layer:** `AdManager.kt`, `AppStartupManager.kt`.
* **Test Suite:** `SubscriptionQualityDecouplingUnitTest.kt`, `FirestoreRulesAlignmentTest.kt`, and all scraper/managed extension unit and Robolectric tests.

============================================================
3. CANONICAL SUBSCRIPTION AUDIT
============================================================

### 3.1 Data Source Path
* **Path:** `/users/{uid}`
* **Listener:** Attached via `UserSecurityManager.listenToUserSecurity(uid)` and synchronized with `AuthRepository.currentUserFlow`.
* **Mode:** Real-time Firestore snapshot listener.

### 3.2 Canonical Fields Consumed
| Field | Type | Expected Values | Audit Result |
| :--- | :--- | :--- | :--- |
| `subscriptionTier` | String | `FREE`, `PRO_LITE`, `PRO` | Correctly normalized via `SubscriptionNormalizer` |
| `planId` | String | `free`, `pro_lite_1d`, `pro_lite_7d`, `pro_lite_10d`, `pro_30d` | Read directly, falls back to tier SKU |
| `durationDays` | Int | `0`, `1`, `7`, `10`, `30` | Read directly, defaults based on `planId` |
| `subscriptionStatus` | String | `ACTIVE`, `EXPIRED`, `CANCELED`, `PENDING` | Normalized; overridden by expiration logic |
| `subscriptionSource` | String | `MONEY`, `POINTS`, `ADMIN_GRANT`, `LEGACY` | Normalized to `CanonicalSubscriptionSource` |
| `subscriptionReferenceId` | String? | Transaction or redemption ID | Read and retained in domain model |
| `subscriptionStartedAt` | Long? | Epoch timestamp millis | Supports `Timestamp` or numeric epoch |
| `subscriptionExpiresAt` | Long? | Epoch timestamp millis | Strictly authoritative for active checks |

### 3.3 Normalization Precedence
`SubscriptionNormalizer.normalizeSubscriptionTier` strictly enforces canonical precedence:
1. Canonical `subscriptionTier` is evaluated first. If populated, it takes absolute precedence over all legacy fields.
2. If `subscriptionTier` is absent or null, legacy `plan`/`proPlan` is checked.
3. If still unresolved, legacy boolean `isPremium` is checked (`true` -> `PRO`).
4. If still unresolved, user `role == "vip"` is checked (`true` -> `PRO`).
5. Default fallback: `CanonicalSubscriptionTier.FREE`.

**Verified Scenario:**
A document with `subscriptionTier = "FREE"` and stale legacy `isPremium = true` resolves to **`FREE`**. Stale legacy flags cannot override canonical state.

============================================================
4. EXPIRATION AUDIT
============================================================

* **Authoritative Field:** `subscriptionExpiresAt` (with legacy fallback to `proExpiresAt`).
* **Evaluation Point:** Evaluated in `SubscriptionNormalizer.normalizeSubscriptionStatus()` and `UserRestrictions.isSubscriptionExpired`.
* **Behavior:**
  * If `subscriptionExpiresAt <= System.currentTimeMillis()`, status resolves to `CanonicalSubscriptionStatus.EXPIRED`.
  * `UserRestrictions.isAdFree` evaluates to `false`.
  * `UserRestrictions.isSubscriptionActive` evaluates to `false`.
* **Decoupling Guarantee:**
  * Expiration triggers ad restoration immediately.
  * Expiration does **NOT** downgrade or alter media playback or download quality. All source-available resolutions remain selectable.

============================================================
5. IS_AD_FREE AUDIT
============================================================

### 5.1 Entitlement Rule
```kotlin
val isAdFree: Boolean
    get() = (canonicalTier != CanonicalSubscriptionTier.FREE) &&
            !isSubscriptionExpired &&
            !subscriptionStatus.equals("EXPIRED", ignoreCase = true) &&
            !subscriptionStatus.equals("CANCELED", ignoreCase = true)
```

### 5.2 Verification Matrix
| Subscription Tier | Status | Expiration State | Ads Expected | Audit Status |
| :--- | :--- | :--- | :--- | :--- |
| `FREE` | `ACTIVE` | N/A | **ON** | VERIFIED |
| `PRO_LITE` | `ACTIVE` | Unexpired (`expiresAt > now`) | **OFF** | VERIFIED |
| `PRO` | `ACTIVE` | Unexpired (`expiresAt > now`) | **OFF** | VERIFIED |
| `PRO_LITE` | `EXPIRED` | Expired (`expiresAt <= now`) | **ON** | VERIFIED |
| `PRO` | `EXPIRED` | Expired (`expiresAt <= now`) | **ON** | VERIFIED |
| `PRO` / `PRO_LITE` | `CANCELED` | Any | **ON** | VERIFIED |

### 5.3 Contaminant Check
Audit verified that `isAdFree` is checked exclusively for:
1. `AdManager.preload()` (skips preloading if `isAdFree`)
2. `AdManager.showInterstitial()` (bypasses ad display if `isAdFree`)
3. `UserSecurityManager.getForcedAdsRequired()` (returns `0` if `isAdFree`)
4. UI Badges in `SubscriptionScreen`, `AccountSettingsScreen`, `ProfileScreen`

`isAdFree` is **NEVER** referenced in video player track selection, ExoPlayer parameters, download candidacy, or scraper execution.

============================================================
6. QUALITY DECOUPLING AUDIT
============================================================

### 6.1 Static Code Scan Results
Static grep scan executed across all source files for patterns coupling subscription status/tier to video resolution:
* Query: `(subscriptionTier.*(quality|resolution|allowedQuality|4K|1080)|(quality|resolution|allowedQuality|4K|1080).*subscriptionTier|isPremium.*(quality|resolution|allowedQuality)|(quality|resolution|allowedQuality).*isPremium|isPro.*(quality|resolution)|(quality|resolution).*isPro)`
* Results: **0 matches** found.
* Query: `(subscription.*downloadLimit|downloadLimit.*subscription|tier.*downloadLimit|downloadLimit.*tier)`
* Results: **0 matches** found.

### 6.2 Component Audit
1. **`UserSecurityManager` & `UserRestrictions`:**
   * `isQualityAllowed(quality: String)` relies exclusively on the independent `allowedQuality` technical field.
   * Contains zero logic checking `subscriptionTier`, `isPremium`, or `isAdFree`.
2. **`PlayerScreen`:**
   * Populates quality list directly from scraper media variants.
   * `isQAllowed` checks `restrictions.isQualityAllowed(canonicalQ)`. If account has no technical cap, all qualities (including 4K, 1440p, 1080p) are unlocked for FREE, PRO_LITE, and PRO.
3. **`InlineDetailVideoPlayer`:**
   * Quality selection passes through to ExoPlayer `TrackSelectionParameters`.
   * Height constraints (4K = 2160, 1440p = 1440, 1080p = 1080, etc.) are applied solely based on the user's selected resolution.
4. **`PlaybackOrchestrator`:**
   * Aggregates media variants without inspecting user tier. `QualitySufficiencyPolicy` evaluates stream completeness, not user entitlement.

============================================================
7. DOWNLOAD DECOUPLING AUDIT
============================================================

1. **`SmartDownloadQualityDialog`:**
   * Offers 8 standard download qualities: `4K` (2160p), `1440` (2K), `1080` (FHD), `720` (HD), `480` (SD), `360`, `240`, `144`.
   * Quality availability is determined strictly by source variant detection (`hasScanned && isFound`).
   * No subscription upgrade prompts or tier locks exist.
2. **`UnifiedDownloadCoordinator`:**
   * Line 85: `if (qCandidate != "Auto" && !UserSecurityManager.restrictions.isQualityAllowed(qCandidate))` checks technical `allowedQuality` only.
   * Line 94: `val limit = UserSecurityManager.restrictions.downloadLimit` checks independent device download limit only.
   * No bypass exists for PRO users; no penalties exist for FREE users.

============================================================
8. TECHNICAL ACCOUNT RESTRICTIONS AUDIT
============================================================

The audit confirmed that technical account restrictions are preserved as **INDEPENDENT ACCOUNT RESTRICTIONS**:
* **`allowedQuality`:** An administrative cap (e.g., `"720p"`). If present on a user document, it restricts playback and download above that resolution. **Crucially:** This restriction applies equally to FREE, PRO_LITE, and PRO users. PRO status cannot bypass this cap.
* **`downloadLimit`:** Maximum concurrent/stored offline downloads. Applies equally across all subscription tiers.
* **`downloadBan` & `watchBan`:** Feature bans set by moderation. Enforced strictly regardless of subscription status.
* **`isBanned`:** Account suspension. Blocks all playback, downloads, and social features across all tiers.

============================================================
9. POINTS READ-ONLY AUDIT
============================================================

* **Fields Monitored:** `pointsBalance`, `totalPointsEarned`, `totalPointsSpent` in `/users/{uid}`.
* **Audit Findings:**
  * Users App performs **ZERO** client-side mutations to points fields on existing user documents.
  * New user creation initializes points to `0L`.
  * No `FieldValue.increment()` calls exist for points in the Users App.
  * No arbitrary point transactions are created by the client.
  * The Users App acts strictly as a viewer of points balances.

============================================================
10. POINT TRANSACTION LEDGER AUDIT
============================================================

* **Subcollection:** `/users/{uid}/point_transactions/{txId}`
* **Access Class:** `PointsRepository.observePointTransactions(uid)`
* **Audit Findings:**
  * Exclusively monitored via a read-only snapshot listener.
  * Supports parsing transaction types: `DAILY_LOGIN`, `REWARDED_AD`, `TASK_REWARD`, `GAME_REWARD`, `LEADERBOARD_REWARD`, `SUBSCRIPTION_REDEMPTION`, `ADMIN_GRANT`, `ADMIN_ADJUSTMENT`, `REVERSAL`.
  * **ZERO** `.set()`, `.add()`, `.update()`, or `.delete()` calls exist for `point_transactions` in the entire codebase.

============================================================
11. PRO REQUEST AUDIT
============================================================

* **Collection:** `/pro_requests/{reqId}`
* **Method:** `PointsRepository.submitProRequest(planId, durationDays, paymentReference, notes)`
* **Audit Findings:**
  * When a user requests a Pro subscription via payment receipt/notes, a document is created in `/pro_requests`.
  * Status is hardcoded to `"PENDING"`.
  * The Users App **CANNOT**:
    * Approve requests.
    * Modify request status to `"APPROVED"` or `"ACTIVE"`.
    * Grant points.
    * Mutate `/users/{uid}` subscription fields upon submission.
  * Subscription activation remains the sole authority of the Admin App / Cloud Functions.

============================================================
12. CONFIG FEATURES CONSUMPTION AUDIT
============================================================

* **Document:** `/config/features`
* **Access Class:** `EconomyConfigRepository.featuresConfig`
* **Features Observed:** `subscriptions`, `points`, `dailyLogin`, `rewardedAds`, `tasks`, `leaderboard`.
* **Allowed States:** `ACTIVE`, `DISABLED`, `COMING_SOON`.
* **Resilience Audit:**
  * Missing Document: Defaults safely to `subscriptions: ACTIVE`, all other features `COMING_SOON`.
  * Permission Denied: Logs warning, retains safe defaults.
  * Malformed Document: Caught by try-catch, uses safe defaults.
  * Offline Mode: Emits default state.
  * **No fake points, fake rewards, or unauthorized subscription unlocks occur under any failure condition.**

============================================================
13. CONFIG ECONOMY CONSUMPTION AUDIT
============================================================

* **Document:** `/config/economy`
* **Access Class:** `EconomyConfigRepository.economyConfig`
* **Fields Consumed:** `redemptionCosts`, `dailyLoginRewards`, `rewardedAdPoints`, `rewardedAdDailyCap`, `rewardedAdCooldownSeconds`.
* **Audit Findings:**
  * The Users App is **READ-ONLY** for economic parameters.
  * No code paths exist to modify redemption costs, daily login rewards, cooldowns, or ad reward points.
  * Resilient fallbacks ensure reasonable defaults (`pro_lite_1d: 50`, `pro_lite_7d: 250`, `pro_lite_10d: 320`, `pro_30d: 800`) if the document is unreachable.

============================================================
14. FIRESTORE RULES COMPATIBILITY AUDIT
============================================================

A comprehensive audit of `/firestore.rules` revealed multiple **RULES / CLIENT CONTRACT MISMATCHES**:

### 14.1 `/config/features` & `/config/economy` Access Blocked
* **Client Expectation:** `EconomyConfigRepository` listens to `/config/features` and `/config/economy`.
* **Actual Rule:**
  ```rules
  match /config/app { allow read: if true; }
  match /config/{document=**} { allow read, write: if false; }
  ```
* **Impact:** Any document under `/config` other than `app` is explicitly rejected with `PERMISSION_DENIED`. The Users App listener receives a permission error and falls back to hardcoded defaults.

### 14.2 `/pro_requests` Creation Blocked
* **Client Expectation:** Users submit subscription requests via `PointsRepository.submitProRequest()`.
* **Actual Rule:** `/firestore.rules` has **NO rule** matching `/pro_requests`.
* **Impact:** In Firestore, unmatched collections default to `allow read, write: if false;`. Any client write to `/pro_requests` will be rejected by security rules.

### 14.3 `/users/{userId}/point_transactions` Read Blocked
* **Client Expectation:** Users read their own transaction ledger via `PointsRepository.observePointTransactions()`.
* **Actual Rule:** Subcollections defined under `/users/{userId}` are: `library`, `history`, `watched_episodes`, `settings`, `fcmTokens`. No rule exists for `point_transactions`.
* **Impact:** Unmatched subcollection defaults to `deny`. Point transaction history reads fail and return empty list.

### 14.4 `/reward_tasks` & `/leaderboard` Blocked
* **Client Expectation:** Read active reward tasks and weekly leaderboard.
* **Actual Rule:** No match rules exist in `/firestore.rules` for `/reward_tasks` or `/leaderboard`.
* **Impact:** Reads are rejected by default Firestore deny policy.

### 14.5 `/users/{userId}` Update Rule Key Whitelist Gap
* **Actual Rule:**
  `!request.resource.data.diff(resource.data).affectedKeys().hasAny([...])`
* **Impact:** The blocked key list blocks `role`, `isPremium`, `subscriptionTier`, `plan`, `subscriptionStatus`, `subscriptionExpiresAt`, `allowedQuality`, etc., but **does not include `pointsBalance`, `totalPointsEarned`, `totalPointsSpent`**. While Users App client code never attempts to update these fields, the security rules currently leave them vulnerable to direct client SDK manipulation by a malicious actor.

============================================================
15. FIRESTORE WRITE SURFACE AUDIT
============================================================

Inventory of all Firestore write operations in the Users App:

| Path | Collection / Doc | Operation | Purpose | Allowed by Rules? | Sensitive Fields Written? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `/users/{uid}` | `users` | `set(..., merge)` (on create) | Initial account bootstrap | YES (lines 43-67) | Initializes `role='user'`, `tier='FREE'`, `points=0` |
| `/users/{uid}` | `users` | `update` / `set(merge)` (on login/edit) | Update lastActive, profile name, photo | YES (lines 70-81) | None. Only non-sensitive profile fields |
| `/users/{uid}/library/{id}` | `library` | `set` / `delete` | Save watchlist/bookmarks | YES (lines 87-89) | None |
| `/users/{uid}/history/{id}` | `history` | `set` / `delete` | Save watch history | YES (lines 91-93) | None |
| `/users/{uid}/watched_episodes/{id}` | `watched_episodes` | `set` / `delete` | Track watched episodes | YES (lines 95-97) | None |
| `/users/{uid}/settings/notifications` | `settings` | `set` | Notification toggles | YES (lines 100-104)| Validated non-sensitive |
| `/users/{uid}/fcmTokens/{instId}` | `fcmTokens` | `set` / `delete` / `update` | FCM push token registration | YES (lines 111-149)| Validated schema |
| `/reports/{id}` | `reports` | `set` | User content/abuse reports | YES (lines 171-179)| Validated `status='pending'` |
| `/support_conversations/{id}` | `support_conversations` | `set` | Customer support ticket | YES (lines 189-200)| Validated `status='open'` |
| `/support_conversations/{id}/messages/{id}` | `messages` | `set` | Support message text | YES (lines 206-225)| Validated `senderRole='user'` |
| `/conversations/{id}` | `conversations` | `set` / `update` | Direct messaging metadata | YES (lines 229-245)| Requires unbanned user |
| `/conversations/{id}/messages/{id}` | `messages` | `set` / `update` | Direct chat message text | YES (lines 247-262)| Requires unbanned user |
| `/stories/{id}` | `stories` | `set` / `delete` | Social stories media | YES (lines 266-274)| Requires unbanned user |
| `/pro_requests/{id}` | `pro_requests` | `set` | Pro subscription request | **NO (Rule Missing)** | Status is PENDING |

============================================================
16. AD MANAGER AUDIT
============================================================

* **File:** `app/src/main/java/com/example/utils/AdManager.kt`
* **Audit Confirmation:**
  * Line 42: `if (UserSecurityManager.isAdFree() || UserSecurityManager.getForcedAdsRequired() == 0) return`
  * Line 54: `if (UserSecurityManager.isAdFree() || UserSecurityManager.getForcedAdsRequired() == 0) { onDismissed?.invoke(); return }`
  * `AdManager` never inspects `subscriptionTier`, `allowedQuality`, `downloadLimit`, or scraper results.
  * Ads are suppressed if and only if `UserSecurityManager.isAdFree() == true`.

============================================================
17. SCRAPER INDEPENDENCE AUDIT
============================================================

Audit of Scrapers (`Anime4Up`, `EgyDead`, `Qfilm`, `AnimeBlkom`, `Witanime`) and `ControlledManagedExtensionRuntime`:
* Search for any reference to `subscription`, `isPremium`, `tier`, or `UserSecurityManager`: **0 occurrences**.
* All scrapers extract whatever qualities and servers the source website provides.
* No source is filtered, downgraded, or unlocked based on user subscription tier.
* Complete scraper independence is **VERIFIED**.

============================================================
18. BACKWARD COMPATIBILITY AUDIT
============================================================

* Legacy user migration verified:
  1. `subscriptionTier = "FREE"` + `isPremium = true`: Normalizes to `FREE` (canonical precedence).
  2. Missing `subscriptionTier` + `isPremium = true`: Normalizes to `PRO` (graceful legacy grandfathering).
  3. Missing `subscriptionTier` + `role = "vip"`: Normalizes to `PRO` (graceful legacy grandfathering).
  4. Expired `subscriptionExpiresAt <= now` + legacy `isPremium = true`: Resolves to `EXPIRED` status with `isAdFree = false` (expiration takes absolute priority).
  5. In all cases, media quality is never affected.

============================================================
19. EXTENSION / PLAYBACK REGRESSION AUDIT
============================================================

* Verified pipeline:
  `ManagedExtension` → `ControlledManagedExtensionRuntime` → `PlaybackOrchestrator` → `PlayerScreen` / `InlineDetailVideoPlayer` → `UnifiedDownloadCoordinator`.
* The Phase 03B decoupling changes did not break:
  * Scraper selection and discovery.
  * Media variant extraction (HLS M3U8, direct MP4).
  * Video quality selection (Auto, 144p to 4K).
  * Background revalidation.
  * Download inspection and isolation.

============================================================
20. TEST RESULTS
============================================================

### 20.1 Unit & Robolectric Suite Status
* **Core Subscription & Decoupling Suite (`SubscriptionQualityDecouplingUnitTest.kt`):**
  * Total Tests: 29
  * PASSED: **29**
  * FAILED: **0**
  * SKIPPED: **0**
  * Includes all 23 canonical test scenarios and all 6 Critical Tests (A through F).
* **Managed Extension & Playback Suites:**
  * `Phase718DownloadInspectionAndIsolationTest`: **PASSED** (10s)
  * `Phase717UnifiedDownloadFlowTest`: **PASSED**
  * `Phase716PlaybackResumeAndBackgroundRevalidationTest`: **PASSED**
  * `Phase715MultiServerMultiQualityExtractionTest`: **PASSED**
  * `PlaybackOrchestratorTest`: **PASSED**
  * `SearchOrderAndEligibilityTest`: **PASSED**
  * `SafeScraperBridgeTest`: **PASSED**
  * `WitanimeScraperUnitTest`: **PASSED**
  * `ScraperRegistryTest`: **PASSED**
  * `WebExtractionEngineRestorationTest`: **PASSED**
  * `Qfilm*` Tests: **PASSED**
  * `Anime4Up*` Tests: **PASSED**
  * `EgyDead*` Tests: **PASSED**
  * `AnimeBlkom*` Tests: **PASSED**
  * `FirestoreRulesAlignmentTest`: **PASSED**
  * `Notification*` & `Fcm*` Tests: **PASSED**
* **Mass Execution Worker Limitation:**
  * When executing all 44 test classes simultaneously in a single Gradle task invocation, the Gradle test worker process encounters an out-of-memory / process termination (`Gradle Test Executor finished with non-zero exit value 1`) due to combined Robolectric JVM memory consumption.
  * When run in targeted batches, **100% of test classes execute and pass**.

============================================================
21. PRODUCTION VERIFICATION
============================================================

```
============================================================
PRODUCTION VERIFICATION:
NOT VERIFIED
============================================================
```
* **Explanation:** All testing and verification were conducted in local JVM and Robolectric testing environments. No live connection to a production Firebase instance was established or validated during this phase.

============================================================
22. DISCOVERED ISSUES
============================================================

1. **[CRITICAL RULES MISMATCH] `/config/features` & `/config/economy` Read Access Denied:**
   * Rule `match /config/{document=**} { allow read, write: if false; }` prevents Users App from reading feature and economy configurations from Firestore.
2. **[CRITICAL RULES MISMATCH] `/pro_requests` Creation Denied:**
   * `/firestore.rules` has no rule matching `/pro_requests`, preventing users from submitting subscription requests to Firestore.
3. **[CRITICAL RULES MISMATCH] `/users/{userId}/point_transactions` Read Denied:**
   * Subcollection `point_transactions` is not declared under `/users/{userId}` in `/firestore.rules`, preventing users from reading their ledger history.
4. **[CRITICAL RULES MISMATCH] `/reward_tasks` & `/leaderboard` Access Denied:**
   * No match rules exist in `/firestore.rules` for reward tasks or leaderboard documents.
5. **[SECURITY VULNERABILITY] `/users/{userId}` Update Key Blacklist Incomplete:**
   * `/firestore.rules` update rule does not blacklist `pointsBalance`, `totalPointsEarned`, or `totalPointsSpent`, leaving points mutable via direct Firestore client SDK calls if rules are deployed as-is.

============================================================
23. REQUIRED FOLLOW-UP (MANAGEMENT / BACKEND PLANE)
============================================================

The following updates must be applied to `firestore.rules` by the Admin / Backend team:
1. Allow public or authenticated read access to `/config/features` and `/config/economy`:
   ```rules
   match /config/features { allow read: if isAuthenticated(); allow write: if isAdmin(); }
   match /config/economy { allow read: if isAuthenticated(); allow write: if isAdmin(); }
   ```
2. Add security rule for `/pro_requests/{reqId}`:
   ```rules
   match /pro_requests/{reqId} {
     allow create: if isAuthenticated() &&
       request.resource.data.userId == request.auth.uid &&
       request.resource.data.status == 'PENDING';
     allow read: if isAdmin() || (isAuthenticated() && resource.data.userId == request.auth.uid);
     allow update, delete: if isAdmin();
   }
   ```
3. Add security rule for `/users/{userId}/point_transactions/{txId}`:
   ```rules
   match /users/{userId}/point_transactions/{txId} {
     allow read: if isOwner(userId) || isAdmin();
     allow write: if isAdmin();
   }
   ```
4. Add security rules for `/reward_tasks` and `/leaderboard`:
   ```rules
   match /reward_tasks/{taskId} { allow read: if isAuthenticated(); allow write: if isAdmin(); }
   match /leaderboard/{docId} { allow read: if isAuthenticated(); allow write: if isAdmin(); }
   ```
5. Add `pointsBalance`, `totalPointsEarned`, `totalPointsSpent` to the `/users/{userId}` update rule affectedKeys blacklist:
   ```rules
   'pointsBalance', 'totalPointsEarned', 'totalPointsSpent'
   ```

============================================================
24. FINAL VERDICT
============================================================

```
============================================================
FINAL VERDICT:
PASS WITH LIMITATIONS
============================================================
```

* **Justification:**
  * **PASS:** The CineStream Users App codebase has fully and cleanly implemented the canonical contract, achieved 100% video/download quality decoupling (`SUBSCRIPTION BENEFIT = REMOVE_ADS ONLY`), and strictly maintains zero economic authority (pure read-only consumption). All 29 decoupling unit tests pass.
  * **LIMITATIONS:** The current Firestore security rules (`firestore.rules`) contain rule mismatches that block client access to `/config/features`, `/config/economy`, `/pro_requests`, and `point_transactions`, requiring the Users App to operate in defensive fallback mode until the backend rules are aligned.
