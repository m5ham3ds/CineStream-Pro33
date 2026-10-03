# PHASE SUBSCRIPTION-POINTS-03B: CINESTREAM USERS APP — CANONICAL SUBSCRIPTION & QUALITY DECOUPLING
## Execution & Consumption Plane Verification Report

---

### Executive Summary

In accordance with Phase **SUBSCRIPTION-POINTS-03B**, the CineStream Users App has been fully aligned with the canonical Subscription and Economy Contract established by the Admin App. All historical coupling between subscription status and video/download quality has been completely removed.

**Core Principle Enforced:**
> **SUBSCRIPTION BENEFIT = REMOVE_ADS ONLY.**
> Subscription does NOT unlock video quality.
> Subscription does NOT restrict video quality.
> Subscription does NOT increase or decrease download quality.
> Source availability dictates playable and downloadable quality for ALL subscription tiers.

---

### 1. Canonical Contract Implemented

The Users App operates exclusively as the **Execution / Consumption Plane**, reading state established by the Admin App (Management Plane) through Firestore:

#### 1.1 Canonical Tiers
* `FREE` — Default tier for all standard users.
* `PRO_LITE` — Ad-free tier available for durations of 1 day, 7 days, or 10 days.
* `PRO` — Premium ad-free tier available for 30 days.

#### 1.2 Canonical SKUs
* `free`
* `pro_lite_1d`
* `pro_lite_7d`
* `pro_lite_10d`
* `pro_30d`

#### 1.3 Canonical Sources
* `MONEY` — Acquired via direct payment/store purchase.
* `POINTS` — Acquired via points redemption.
* `ADMIN_GRANT` — Granted directly by an Administrator.
* `LEGACY` — Derived from legacy system migrations.

#### 1.4 Canonical Statuses
* `ACTIVE` — Subscription active and unexpired.
* `EXPIRED` — Subscription timestamp has elapsed.
* `CANCELED` — Subscription revoked or canceled.

#### 1.5 Contract Fields Read from `/users/{uid}`
```kotlin
subscriptionTier: String           // "FREE" | "PRO_LITE" | "PRO"
planId: String                     // "free" | "pro_lite_1d" | "pro_lite_7d" | "pro_lite_10d" | "pro_30d"
durationDays: Int                  // e.g. 0, 1, 7, 10, 30
subscriptionStatus: String         // "ACTIVE" | "EXPIRED" | "CANCELED"
subscriptionSource: String         // "MONEY" | "POINTS" | "ADMIN_GRANT" | "LEGACY"
subscriptionReferenceId: String?   // Transaction ID, receipt, or redemption ref
subscriptionStartedAt: Long?       // Epoch timestamp millis
subscriptionExpiresAt: Long?       // Epoch timestamp millis (AUTHORITATIVE)
```

---

### 2. Quality Decoupling Confirmation

#### 2.1 Complete Removal of Quality Gates
* **Player UI (`PlayerScreen.kt` & `InlineDetailVideoPlayer.kt`)**: Quality selector lists all qualities returned from the scraper/source (including 4K, 1440p/2K, 1080p FHD, 720p HD, 480p, 360p, 240p, 144p). No quality option is locked or suppressed due to subscription tier.
* **Smart Download Coordinator (`UnifiedDownloadCoordinator.kt`)**: Downloads are evaluated against independent account limits (`allowedQuality`, `downloadLimit`, `downloadBan`) only. No subscription tier check exists.
* **Download Dialog (`SmartDownloadQualityDialog.kt`)**: Displays all source qualities (from 144p up to 4K). Copy referencing "Upgrade required" or "Upgrade to unlock 4K" has been removed.
* **Account Settings & Profile UI (`AccountSettingsScreen.kt`, `ProfileScreen.kt`, `SubscriptionScreen.kt`)**: Display banner:
  > **ميزة الاشتراك: إزالة الإعلانات فقط** (Subscription Benefit: Remove Ads Only)
  > All source qualities (1080p, 4K) are available across all plans.

#### 2.2 Independent Technical Account Permissions
Independent administrative account restrictions remain strictly preserved:
* `allowedQuality`: Technical cap applied uniformly regardless of tier. If an administrator caps an account to 720p, that technical cap applies whether the user is FREE, PRO_LITE, or PRO. PRO cannot bypass technical caps.
* `downloadLimit`: Maximum concurrent/completed offline downloads permitted by policy.
* `downloadBan`, `watchBan`, `isBanned`: Technical restrictions enforced regardless of subscription status.

---

### 3. Final User Experience Across All Three Tiers

| Dimension | FREE | PRO_LITE | PRO |
| :--- | :--- | :--- | :--- |
| **Ads** | **ON** (Interstitial & Startup per forced ads policy) | **OFF** (Fully Suppressed) | **OFF** (Fully Suppressed) |
| **Video Playback Quality** | **ALL** qualities exposed by source (144p – 4K) | **ALL** qualities exposed by source (144p – 4K) | **ALL** qualities exposed by source (144p – 4K) |
| **Download Quality** | **ALL** qualities exposed by source (144p – 4K) | **ALL** qualities exposed by source (144p – 4K) | **ALL** qualities exposed by source (144p – 4K) |
| **Available Durations** | Permanent | 1 day, 7 days, 10 days | 30 days |
| **Expiration Effect** | N/A | Ads return immediately; quality remains unchanged | Ads return immediately; quality remains unchanged |

---

### 4. Points Read-Only Consumption Audit

The Users App strictly enforces zero-authority over points:

1. **User Balance (`/users/{uid}`)**:
   * Reads `pointsBalance`, `totalPointsEarned`, and `totalPointsSpent` in real-time.
   * Never mutates points directly via client writes.
2. **Transaction Ledger (`/users/{uid}/point_transactions`)**:
   * Observed via `PointsRepository.observePointTransactions(uid)`.
   * Displays the read-only audit trail (rewarded ads, daily check-in, redemptions).
   * Users App performs ZERO client writes to `point_transactions`.
3. **Pro Subscription Requests (`/pro_requests`)**:
   * Users can submit payment verification requests via `PointsRepository.submitProRequest(...)`.
   * Created with status `"PENDING"`.
   * Client NEVER self-activates subscriptions; activation is exclusively performed by Admin App or Cloud Functions.

---

### 5. Economy & Feature Config State Audit

Managed via `EconomyConfigRepository`:

#### 5.1 `/config/features`
Consumed to enable/disable UI modules dynamically:
* `subscriptions` (`ACTIVE` | `DISABLED` | `COMING_SOON`)
* `points` (`ACTIVE` | `DISABLED` | `COMING_SOON`)
* `dailyLogin` (`ACTIVE` | `DISABLED` | `COMING_SOON`)
* `rewardedAds` (`ACTIVE` | `DISABLED` | `COMING_SOON`)
* `tasks` (`ACTIVE` | `DISABLED` | `COMING_SOON`)
* `leaderboard` (`ACTIVE` | `DISABLED` | `COMING_SOON`)

#### 5.2 `/config/economy`
Authoritative redemption costs & ad economy parameters:
* `redemptionCosts`:
  * `pro_lite_1d`: 50 points
  * `pro_lite_7d`: 250 points
  * `pro_lite_10d`: 320 points
  * `pro_30d`: 800 points
* `dailyLoginRewards`: `[10, 15, 20, 25, 35, 50, 100]`
* `rewardedAdPoints`: 15 points
* `rewardedAdDailyCap`: 5 ads/day
* `rewardedAdCooldownSeconds`: 300 seconds (5 min)

#### 5.3 Resilience & Fallbacks
* If `/config/features` or `/config/economy` documents are missing, permission-denied, or malformed, the app gracefully falls back to predefined safe defaults without crashing.
* In offline mode, defaults ensure subscriptions and media playback continue uninterrupted.

---

### 6. Firestore Security Alignment

* **Zero Rule Weakening**: Existing `firestore.rules` remain intact.
* **No Client Privilege Escalation**:
  * `/users/{uid}` rules disallow client-side modification of `subscriptionTier`, `subscriptionStatus`, `subscriptionExpiresAt`, `pointsBalance`, `role`, or `allowedQuality`.
  * Users can only modify authorized profile fields (`username`, `displayName`, `photoUrl`, `bio`).
  * `/config/*` collections remain strictly read-only for client applications.

---

### 7. Scraper & Provider Independence Confirmation

All 5 core scrapers operate with complete autonomy from subscription state:
1. `Anime4UpScraper`
2. `EgyDeadScraper`
3. `QfilmScraper`
4. `AnimeBlkomScraper`
5. `WitanimeScraper`

* Extracted media streams and quality labels (`4K`, `2160p`, `1440p`, `1080p`, `720p`, `480p`, `360p`, `240p`) flow through `WebExtractionEngine`, `ControlledManagedExtensionRuntime`, and `PlaybackOrchestrator` directly into the player without filtering based on subscription tier.

---

### 8. Test Results & Verification

Comprehensive test suite implemented in `SubscriptionQualityDecouplingUnitTest.kt`:

#### 8.1 Required Scenarios (23 / 23 Passed)
1. `test01_freeNormalization` — PASSED
2. `test02_proNormalization` — PASSED
3. `test03_proLiteNormalization` — PASSED
4. `test04_legacyFree` — PASSED
5. `test05_legacyPremium` — PASSED
6. `test06_legacyVip` — PASSED
7. `test07_canonicalPrecedence` — PASSED
8. `test08_expirationAuthoritative` — PASSED
9. `test09_sourceHandling` — PASSED
10. `test10_isAdFreeEntitlement` — PASSED
11. `test11_expiredAdRestoration` — PASSED
12. `test12_qualityIndependence` — PASSED
13. `test13_downloadIndependence` — PASSED
14. `test14_proCannotUnlockQuality` — PASSED
15. `test15_proLiteCannotUnlockQuality` — PASSED
16. `test16_freeCannotLoseQualityBecauseOfSubscription` — PASSED
17. `test17_pointsWalletReadOnly` — PASSED
18. `test18_ledgerReadOnlyBehavior` — PASSED
19. `test19_featureStateParsing` — PASSED
20. `test20_economyConfigParsing` — PASSED
21. `test21_missingConfigSafety` — PASSED
22. `test22_malformedConfigSafety` — PASSED
23. `test23_offlineSafety` — PASSED

#### 8.2 Critical Tests (Section 34 A – F)
* **Critical Test A**: PRO + 4K source -> 4K remains available — **VERIFIED**
* **Critical Test B**: PRO_LITE + 4K source -> 4K remains available — **VERIFIED**
* **Critical Test C**: FREE + 4K source -> 4K remains available — **VERIFIED**
* **Critical Test D**: PRO + 1080p source -> 1080p remains available — **VERIFIED**
* **Critical Test E**: FREE + 1080p source -> 1080p remains available — **VERIFIED**
* **Critical Test F**: PRO expiration -> Ads return, quality unchanged — **VERIFIED**

#### 8.3 Static Code Scan
* Scanned for patterns: `(subscriptionTier.*allowedQuality|allowedQuality.*subscriptionTier|isPremium.*allowedQuality|allowedQuality.*isPremium|subscription.*downloadLimit|downloadLimit.*subscription|PRO.*4K|PRO_LITE.*1080)`
* Result: **0 Violations Found**.

---

### 9. Backward Compatibility Verification

The normalizer logic strictly implements the canonical precedence order:
1. If `subscriptionTier` is populated (e.g., `"FREE"`), it overrides stale legacy flags (`isPremium = true`, `plan = "vip"`).
2. If `subscriptionTier` is missing, legacy fields (`isPremium`, `plan`, `proPlan`, `role`) are mapped conservatively to ensure existing active users are not abruptly downgraded.
3. Expiration timestamps take absolute precedence: if `subscriptionExpiresAt <= now`, the status is `EXPIRED` regardless of any boolean flags.

---

### 10. Final Architectural State

```
┌─────────────────────────────────────────────────────────────┐
│                    ADMIN MANAGEMENT PLANE                   │
│         Authoritative: Subscriptions, Points, Configs       │
└──────────────────────────────┬──────────────────────────────┘
                               │ Writes to Firestore
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                   FIREBASE CONTROL PLANE                    │
│      /users/{uid}   /config/economy   /config/features      │
└──────────────────────────────┬──────────────────────────────┘
                               │ Real-time Snapshot Listeners
                               ▼
┌─────────────────────────────────────────────────────────────┐
│               CINESTREAM USERS CONSUMPTION PLANE            │
│                                                             │
│   UserSecurityManager            EconomyConfigRepository    │
│   ├── isAdFree = (tier != FREE   ├── featuresConfig Flow    │
│   │    && !isExpired)            └── economyConfig Flow     │
│   └── pointsBalance (read-only)                             │
│                                                             │
│   AdManager                      Player / Download Pipeline │
│   └── Suppresses ads if          └── Plays / Downloads ANY  │
│       isAdFree == true               quality from source    │
│                                      (144p to 4K)           │
└─────────────────────────────────────────────────────────────┘
```

The Users App operates in full contract harmony with the Admin App and Firestore backend.
