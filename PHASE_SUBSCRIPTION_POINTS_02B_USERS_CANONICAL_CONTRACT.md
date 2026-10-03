# PHASE SUBSCRIPTION-POINTS-02B: CANONICAL ECONOMY CONTRACT RECONCILIATION
**Application:** CineStream Users App (`com.aistudio.cinestream.xyzabc`)  
**Phase:** SUBSCRIPTION-POINTS-02B  
**Phase Type:** Canonical Contract Reconciliation / Users-Side Architecture Design  
**Implementation Status:** Design & Reconciliation Only — Zero Production Code Modified  
**Contract Baseline:** Admin App Phase SUBSCRIPTION-POINTS-02A Approved Canonical Contract  

---

## 1. Executive Summary

Phase **SUBSCRIPTION-POINTS-02B** performs the formal architectural reconciliation between the external **Admin App Canonical Subscription + Points Economy Contract (Phase 02A)** and the **CineStream Users App execution plane**.

The Users App represents the *trusted execution plane* of the CineStream ecosystem: it delivers authorized content, plays media, enforces playback constraints, and presents user interfaces. However, **the Users App is not an economic authority**. It cannot mint points, grant subscriptions, validate payments, settle leaderboards, or self-assign privileges. All economic state, transaction ledgers, and entitlement lifecycles must originate from trusted server authority (Admin App or Cloud Functions / Trusted Backend) and be consumed by the Users App via authenticated, read-only, real-time Firestore synchronization.

This document establishes the precise consumption design, state machines, offline trust boundaries, legacy backward compatibility mappings, and migration safety guarantees required before any implementation begins.

---

## 2. Zero-Modification Verification

In strict accordance with the mandatory rules of Phase SUBSCRIPTION-POINTS-02B:
* **Kotlin Source Files Modified:** 0
* **Compose UI Files Modified:** 0
* **XML Resources / Strings Modified:** 0
* **Gradle Build Scripts / Dependencies Modified:** 0
* **Firestore Security Rules (`firestore.rules`) Modified:** 0
* **Firestore Database Documents / Collections Modified:** 0
* **Billing / Economy SDKs Added:** 0
* **Production Code Status:** 100% untouched.

All technical specifications herein define future-phase contracts and architectural blueprints without introducing premature code changes.

---

## 3. Current Subscription Architecture (Users App Baseline)

Based on the forensic audit completed in Phase 01B, the current Users App subscription architecture operates as follows:
1. **Document Storage:** Entitlement data is stored exclusively on the user's primary profile document: `/users/{userId}`.
2. **Current Read Fields:**
   * `subscriptionTier`: String (`"free"`, `"premium"`, `"vip"`). Default `"free"`.
   * `plan`: String (historical fallback alias for `subscriptionTier`).
   * `isPremium`: Boolean (flag indicating VIP/Premium status).
   * `subscriptionStatus`: String (`"none"`, etc.).
   * `subscriptionExpiresAt`: Timestamp or Long epoch millis.
   * `forcedAdsOverride`: Long/Int (overrides global ad counts).
3. **Entitlement Evaluation:**
   * Managed centrally by `UserSecurityManager.kt` via real-time Firestore snapshot listener on `/users/{uid}`.
   * An active subscription (`isVip()`) is resolved if:
     `(isPremium == true || subscriptionTier in ["premium", "vip"]) && (subscriptionExpiresAt == null || subscriptionExpiresAt > System.currentTimeMillis())`.
4. **Current Entitlement Benefits:**
   * **Ad Suppression:** `AdManager.preload()` and `AdManager.showInterstitial()` bypass ads when `isVip() == true`.
   * **Quality Override:** `SmartDownloadQualityDialog.kt` and `UnifiedDownloadCoordinator.kt` bypass `allowedQuality` restrictions when `isPremium == true`.
5. **Absence of Economy Subsystems:**
   * No points wallet, points ledger, daily login, rewarded ads, tasks, or leaderboards exist in the current Users App.
   * Action buttons on `SubscriptionScreen.kt` are dead no-ops (`onClick = { }`).

---

## 4. Admin 02A External Contract Input

The Admin App Canonical Contract (Phase 02A) establishes the following specifications:
* **Canonical Tiers:** `FREE`, `PRO_LITE`, `PRO`.
* **Canonical SKUs:** `free`, `pro_lite_1d`, `pro_lite_7d`, `pro_lite_10d`, `pro_30d`.
* **Canonical Durations:**
  * `PRO_LITE`: 1 Day (24h), 7 Days (168h), 10 Days (240h).
  * `PRO`: 30 Days (720h).
  * `FREE`: Permanent (no expiration).
* **Canonical Entitlement Fields on `/users/{uid}`:**
  * `subscriptionTier`: `"FREE"` | `"PRO_LITE"` | `"PRO"`
  * `planId`: `"free"` | `"pro_lite_1d"` | `"pro_lite_7d"` | `"pro_lite_10d"` | `"pro_30d"`
  * `durationDays`: Int (0, 1, 7, 10, 30)
  * `subscriptionStatus`: `"ACTIVE"` | `"EXPIRED"` | `"CANCELED"` | `"PENDING"`
  * `subscriptionSource`: `"MONEY"` | `"POINTS"` | `"ADMIN_GRANT"` | `"LEGACY"`
  * `subscriptionReferenceId`: String (ledger txId, receipt ID, or grant ID)
  * `subscriptionStartedAt`: Timestamp / Epoch millis
  * `subscriptionExpiresAt`: Timestamp / Epoch millis
* **Legacy Compatibility Fields:** `isPremium` (Boolean), `isPro` (Boolean), `plan` (String), `proPlan` (String), `proExpiresAt` (Timestamp/Long).
* **Canonical Primary Benefit:** `REMOVE_ADS` during active subscription duration.
* **Separation of Concerns:** Video quality (`allowedQuality`) and download allowances (`downloadLimit`) are decoupled from subscription tier.
* **Points Economy:** Ledger-backed (`/users/{uid}/point_transactions/{txId}`), daily logins, rewarded ads (max 5/day, 5-min cooldown), reward tasks, and weekly leaderboard (Monday 00:00 UTC to Sunday 23:59 UTC).

---

## 5. Subscription Model Reconciliation

The Users App model layer (`User.kt` and `UserRestrictions.kt`) must map canonical and legacy values into a unified domain representation:

```
[Firestore /users/{uid}]
       │
       ▼
[Users App Ingestion & Normalizer]
       │
       ├─► 1. Canonical Tier Resolution:
       │      Read `subscriptionTier` -> Uppercase normalized -> "FREE" | "PRO_LITE" | "PRO"
       │      Fallback if missing:
       │        if (plan in ["vip", "premium"] || isPremium == true) -> "PRO"
       │        else -> "FREE"
       │
       ├─► 2. Plan SKU Resolution:
       │      Read `planId` -> "free" | "pro_lite_1d" | "pro_lite_7d" | "pro_lite_10d" | "pro_30d"
       │      Fallback if missing:
       │        derive from `subscriptionTier` + `durationDays`
       │
       ├─► 3. Expiration Resolution:
       │      Read `subscriptionExpiresAt` (Timestamp or Long)
       │      Fallback: read `proExpiresAt`
       │
       ├─► 4. Active Status Calculation:
       │      isActive = (subscriptionTier != "FREE") && (expiresAt == null || expiresAt > now)
       │
       ▼
[Domain Entitlement State: SubscriptionState]
```

### Target Domain Model (Future Implementation):
```kotlin
enum class CanonicalSubscriptionTier { FREE, PRO_LITE, PRO }

data class UserSubscriptionState(
    val tier: CanonicalSubscriptionTier = CanonicalSubscriptionTier.FREE,
    val planId: String = "free",
    val durationDays: Int = 0,
    val status: String = "ACTIVE",
    val source: String = "LEGACY",
    val referenceId: String? = null,
    val startedAt: Long? = null,
    val expiresAt: Long? = null,
    val isActive: Boolean = false,
    val isAdFree: Boolean = false
)
```

---

## 6. Legacy Read Compatibility Matrix

To prevent breaking existing user accounts during phased rollout, the Users App must apply strict priority ordering when parsing documents:

| Field | Canonical Source (Priority 1) | Secondary Source (Priority 2) | Fallback Source (Priority 3) | Default Value |
| :--- | :--- | :--- | :--- | :--- |
| **Tier** | `doc.getString("subscriptionTier")` | `doc.getString("plan")` / `doc.getString("proPlan")` | `if (doc.getBoolean("isPremium") == true) "PRO" else "FREE"` | `"FREE"` |
| **Plan ID** | `doc.getString("planId")` | Synthetic from tier + duration (e.g., `"pro_30d"`) | Default SKU for tier (`"free"`) | `"free"` |
| **Duration Days** | `doc.getLong("durationDays")?.toInt()` | Derived from `(expiresAt - startedAt) / 86400000` | Tier default (30 for PRO, 0 for FREE) | `0` |
| **Status** | `doc.getString("subscriptionStatus")` | Derived from expiration check: `ACTIVE` vs `EXPIRED` | `"ACTIVE"` if unexpired, else `"EXPIRED"` | `"ACTIVE"` |
| **Source** | `doc.getString("subscriptionSource")` | Legacy check: `"LEGACY"` | `"LEGACY"` | `"LEGACY"` |
| **Expires At** | `doc.get("subscriptionExpiresAt")` | `doc.get("proExpiresAt")` | `null` | `null` |

### Conflict Resolution Rules:
1. **Conflict: `subscriptionTier == "FREE"`, but `isPremium == true`:**
   * *Resolution:* Canonical takes precedence. If `subscriptionTier` is explicitly written as `"FREE"` by modern Admin, user is treated as `FREE` (`isPremium` is flagged as stale legacy).
2. **Conflict: `subscriptionTier == "PRO"`, but `subscriptionExpiresAt <= System.currentTimeMillis()`:**
   * *Resolution:* Expiration always governs runtime entitlement. The user's status is normalized to `EXPIRED`, and active benefits (ad removal) are immediately revoked.
3. **Missing `planId` with active `PRO_LITE`:**
   * *Resolution:* If `durationDays` is known (e.g. 7), synthesize `planId = "pro_lite_7d"`. If duration is unknown, default to `pro_lite_1d` safe fallback.

---

## 7. Subscription Benefit Reconciliation

The canonical benefit defined in Phase 02A is exclusively **`REMOVE_ADS` during active subscription duration**.

### Entitlement Decision Matrix:

| Scenario / Tier | Current Users App Behavior | Canonical Contract (02A) | Transformed Users Behavior | Future Implementation Phase |
| :--- | :--- | :--- | :--- | :--- |
| **FREE** | Ads shown (subject to forced ads count); quality capped if `allowedQuality` set | Ads shown; quality governed by account permissions | Ads shown; quality governed by account permissions | Phase 03B (Core Sub) |
| **PRO_LITE (Active)** | Not present | Ads removed entirely | Ads removed entirely | Phase 03B (Core Sub) |
| **PRO (Active)** | Ads removed; quality cap bypassed | Ads removed entirely | Ads removed entirely | Phase 03B (Core Sub) |
| **Legacy VIP (Active)** | Ads removed; quality cap bypassed | Mapped to PRO (Ads removed) | Ads removed; quality cap governed by account permissions | Phase 03B (Core Sub) |
| **Legacy Premium (Active)** | Ads removed; quality cap bypassed | Mapped to PRO (Ads removed) | Ads removed; quality cap governed by account permissions | Phase 03B (Core Sub) |
| **Expired Subscription** | Ads resumed; quality cap restored | Ads resumed | Ads resumed; status displayed as EXPIRED | Phase 03B (Core Sub) |
| **Admin Grant (Active)** | Ads removed | Ads removed | Ads removed; source displayed as "Admin Grant" | Phase 03B (Core Sub) |
| **Points Subscription (Active)**| Not present | Ads removed | Ads removed; source displayed as "Redeemed with Points" | Phase 04B (Points) |

---

## 8. Quality / Download Decoupling Design

In the legacy implementation, `restrictions.isPremium` was directly leveraged to bypass video resolution limits in `SmartDownloadQualityDialog.kt` and `UnifiedDownloadCoordinator.kt`. The Canonical Contract 02A mandates that subscription tiers do not inherently authorize video bandwidth or download storage.

### Consumption Decoupling Architecture:
1. **Ad Gating:** Strictly tied to `UserSubscriptionState.isAdFree` (`isSubscriptionActive`).
2. **Feature Access:**
   * `canWatch`: Governed by `UserRestrictions.canWatch` and `watchBan`.
   * `canDownload`: Governed by `UserRestrictions.canDownload` and `downloadBan`.
   * `allowedQuality`: Governed solely by `UserRestrictions.allowedQuality` (e.g. `"1080p"`, `"4K"`).
   * `downloadLimit`: Governed solely by `UserRestrictions.downloadLimit` (e.g. 5, 20).
3. **Transitional Compatibility Layer:**
   * To satisfy Admin App transitional provisioning (where an Admin may grant 4K to PRO users or 1080p to PRO_LITE users), the Admin App sets `allowedQuality = "4K"` or `"1080p"` directly on the user's document.
   * The Users App simply checks `UserRestrictions.isQualityAllowed(quality)` against the profile's `allowedQuality` without inspecting the subscription tier.

---

## 9. Subscription Source Handling

The canonical field `subscriptionSource` records origin: `"MONEY"`, `"POINTS"`, `"ADMIN_GRANT"`, or `"LEGACY"`.

### UI and Operational Visibility:
* **User UI Display:**
  * In `SubscriptionScreen` and `AccountSettingsScreen`, the source is displayed as user-friendly contextual metadata:
    * `"MONEY"` -> "Purchased Subscription"
    * `"POINTS"` -> "Redeemed with CineStream Points"
    * `"ADMIN_GRANT"` -> "Special VIP Pass / Grant"
    * `"LEGACY"` -> "Active VIP Membership"
* **Operational Rules:**
  * `subscriptionSource` is **informational only**; it does not alter ad suppression or expiration calculation.
  * A 7-day `PRO_LITE` from points has the exact same ad-free entitlement as a 7-day `PRO_LITE` from money.
  * The Users App **must never modify** `subscriptionSource`.

---

## 10. Pro Request Reconciliation (`/pro_requests`)

### Workflow Reconciliation:
While the current Users codebase has not yet exposed a live Pro Request UI, the Admin 02A Contract standardizes this workflow as an atomic administrative approval process.

```
Users App                                           Admin App / Backend
   │                                                         │
   ├────── Writes /pro_requests/{requestId} ───────────────►│ (Status: PENDING)
   │       (planId, paymentRef, receiptUrl, userUid)         │
   │                                                         │
   │◄───── Snapshot Listener on /pro_requests/{id} ──────────┤ [Waiting / Under Review]
   │                                                         │
   │                                                         ▼
   │                                                 Atomic Transaction:
   │                                                 1. /pro_requests/{id}.status = "APPROVED"
   │                                                 2. /users/{uid} updated (tier, planId, exp)
   │                                                 3. /auditLogs created
   │                                                         │
   │◄───── /users/{uid} updated snapshot fires ──────────────┤
   ▼                                                         ▼
Entitlement Activates Instantly                   Request Archived
(AdManager bypasses ads)                          (Audit logged)
```

### Users-Side States:
1. **Before Approval (Pending):** UI displays "Subscription Request Pending Review". Purchase actions disabled.
2. **During Approval:** Users App listens to `/users/{uid}`. Activation is driven exclusively by the `/users/{uid}` document update, **not** by the request document.
3. **After Approval:** `subscriptionTier` transitions to `PRO`/`PRO_LITE`; UI renders active crown and expiry date.
4. **Rejected:** `/pro_requests/{id}.status` becomes `"REJECTED"` with `rejectionReason`. UI shows informative dismissal message and re-enables purchase/redemption options.

---

## 11. Points Wallet Contract (`/users/{uid}`)

### Authoritative Model:
The user document `/users/{uid}` maintains cached summary balance fields updated solely by trusted server authority:
* `pointsBalance: Long` (Current spendable points)
* `totalPointsEarned: Long` (Lifetime earnings)
* `totalPointsSpent: Long` (Lifetime spend)

### Client Ingestion & State:
```kotlin
data class UserPointsWallet(
    val balance: Long = 0L,
    val totalEarned: Long = 0L,
    val totalSpent: Long = 0L,
    val isSyncing: Boolean = false,
    val lastSyncTimestamp: Long = 0L
)
```

### Client Permissions:
* **Users App MAY:** Read its own wallet balance via `/users/{uid}` snapshot listener.
* **Users App MUST NEVER:** Increment, decrement, write, or set `pointsBalance`, `totalPointsEarned`, or `totalPointsSpent`.

---

## 12. Points Ledger Architecture (`/users/{uid}/point_transactions/{txId}`)

### Canonical Schema:
```json
{
  "txId": "tx_1727712000000_abc123",
  "userId": "user_uid_here",
  "type": "DAILY_LOGIN | REWARDED_AD | TASK_REWARD | GAME_REWARD | LEADERBOARD_REWARD | SUBSCRIPTION_REDEMPTION | ADMIN_GRANT | ADMIN_ADJUSTMENT | REVERSAL",
  "amount": 15,
  "balanceBefore": 100,
  "balanceAfter": 115,
  "referenceId": "daily_2026-09-30",
  "description": "Daily login streak reward (Day 3)",
  "createdAt": "2026-09-30T12:00:00Z",
  "serverTimestamp": 1727712000000
}
```

### Users-Side Display Specifications:

| Transaction Type | Amount Display | Display Label | Context Icon | Reference Identifier |
| :--- | :---: | :--- | :--- | :--- |
| `DAILY_LOGIN` | `+X` (Green) | Daily Check-in | Calendar / Star | Date (`YYYY-MM-DD`) |
| `REWARDED_AD` | `+X` (Green) | Video Ad Bonus | PlayCircle / Sparkles| Ad Impression ID |
| `TASK_REWARD` | `+X` (Green) | Task Completed | CheckCircle | Task ID |
| `GAME_REWARD` | `+X` (Green) | Mini-Game Reward | SportsEsports | Match/Game Session ID |
| `LEADERBOARD_REWARD` | `+X` (Green) | Weekly Rank Prize | EmojiEvents / Trophy | Week Cycle ID |
| `SUBSCRIPTION_REDEMPTION`| `-X` (Red) | Pass Redemption | CardMembership | Plan ID (`pro_lite_7d`) |
| `ADMIN_GRANT` | `+X` (Green) | VIP Bonus Gift | CardGiftcard | Support Ticket / Note |
| `ADMIN_ADJUSTMENT` | `±X` | Account Adjustment | ManageAccounts | Admin Audit ID |
| `REVERSAL` | `-X` (Red) | Chargeback / Correction | Undo | Original Transaction ID |

---

## 13. Daily Login Subsystem

### Canonical Rules:
* **Cycle:** UTC date (`YYYY-MM-DD`), resets at 00:00 UTC.
* **Streak:** 7-day recurring cycle (Day 1 through Day 7). Missing a calendar day resets streak to Day 1.
* **Server Authority:** The server evaluates UTC timestamp; client local clock is disregarded.

### Users App Architecture:
1. **State Consumption:**
   * Current streak and last claim date stored on user profile: `dailyLoginStreak: Int`, `lastDailyLoginDate: String` (`"YYYY-MM-DD"`).
2. **Claim Action:**
   * Client issues an authoritative claim request (via Cloud Function `claimDailyLogin` or atomic transaction).
   * Client provides: `uid`, local timestamp (for logging only).
   * Server validates that `lastDailyLoginDate != todayUtc`.
3. **Error & Offline Handling:**
   * Offline: Claim button is disabled ("Requires internet connection").
   * `ALREADY_CLAIMED_TODAY`: UI reflects claimed state (checkmark) with countdown timer to next 00:00 UTC.
   * `FEATURE_DISABLED` or `COMING_SOON`: UI shows standardized placeholder: *"هذه الميزة ستضاف قريبًا"*.

---

## 14. Rewarded Ads Subsystem

### Differentiation:
* **Forced Ads (Interstitials):** Compulsory playback triggers for Free users. Zero points awarded.
* **Rewarded Ads:** Optional user-initiated video views. Awards points only upon 100% verified completion.

### Canonical Constraints (02A):
* **Default Reward:** 15 points.
* **Daily Cap:** Maximum 5 rewarded ads per calendar day.
* **Cooldown:** Minimum 5 minutes (300 seconds) between rewarded ad completions.
* **Verification:** Server or verified Ad SDK completion callback required.

### Users App State Flow:
```
[User Clicks "Watch Ad for 15 Points"]
       │
       ├─► 1. Check Cooldown (< 300s since last claim) ──► UI Shows Cooldown Timer
       ├─► 2. Check Daily Cap (>= 5 today)             ──► UI Shows Daily Cap Reached
       ├─► 3. Check Network (Offline)                 ──► UI Shows Offline Warning
       │
       ▼ (All Valid)
[AdManager Loads Rewarded Ad via StartApp SDK]
       │
       ├─► Ad Fails / Dismissed Early ──► No points awarded, user notified
       │
       ▼ (100% Completed)
[Client Sends Completion Token to Server/Cloud Function]
       │
       ▼
[Server Writes Transaction & Increments pointsBalance]
       │
       ▼
[Snapshot Listener Updates Wallet UI]
```

---

## 15. Task Reward Subsystem

### Schemas:
1. **Catalog Path:** `/reward_tasks/{taskId}` (Read-only for users).
   * Fields: `taskId`, `title`, `description`, `rewardPoints`, `taskType`, `actionUrl`, `isActive`, `expiresAt`.
2. **Claim Record Path:** `/users/{uid}/task_claims/{taskId}` (Read-only for users).
   * Fields: `taskId`, `claimedAt`, `pointsAwarded`, `status: "COMPLETED"`.

### Users App State Machine:
* `AVAILABLE`: Task exists in `/reward_tasks`, `isActive == true`, not found in user's `/task_claims`.
* `IN_PROGRESS`: For multi-step tasks (if tracking client progress locally).
* `CLAIMABLE`: Verification requirements met; claim request enabled.
* `COMPLETED`: Present in `/users/{uid}/task_claims/{taskId}`; points already awarded.
* `EXPIRED`: Task expiration date passed before completion.

---

## 16. Game Reward Extension

### Contract Specifications:
The Users App may in the future include gamification or interactive mini-games.
* **Client Trust Boundary:** The client must **never** send arbitrary reward amounts to Firestore (e.g. `sendPoints(50)`).
* **Game Session Reference:** The client initiates a session with a unique `gameSessionId`.
* **Authoritative Verification:** Game completion triggers a server-side verification check (score limits, duration plausibility, rate limits).
* **Wallet Credit:** Upon validation, the server commits a `GAME_REWARD` transaction to the user's ledger and updates `pointsBalance`.

---

## 17. Leaderboard Subsystem

### Canonical Rules:
* **Metric:** `weeklyEarnedPoints` (sum of points earned during the active week).
* **Isolation:** Total wallet `pointsBalance` is **never** used for leaderboard rankings to prevent balance-hoarding dominance.
* **Cycle:** Weekly, starting Monday 00:00 UTC and concluding Sunday 23:59 UTC.
* **Active Leaderboard Path:** `/leaderboard/weekly_current`
* **Settled History Path:** `/leaderboard_history/{cycleId}`

### Users App Responsibilities:
* Display top ranks (e.g. Top 50).
* Display current authenticated user's rank and score.
* Cached read: If offline, display last-cached leaderboard with "Cached - Connect to refresh" indicator.
* **Absolute Restriction:** Users App never calculates ranks, updates positions, or settles outcomes.

---

## 18. Top-3 Rewards Contract

Configured on the server plane with the following Phase 02A canonical defaults:
* **1st Place:** 500 Points + 7-Day `PRO_LITE` Pass
* **2nd Place:** 300 Points + 1-Day `PRO_LITE` Pass
* **3rd Place:** 150 Points

### Users App Presentation Rules:
* Defaults are treated as **contract defaults**, not hardcoded UI strings.
* When presenting the leaderboard header, rewards are read dynamically from `/config/economy` (or settlement document).
* When a cycle concludes, the backend awards points and passes. The Users App detects the incoming `LEADERBOARD_REWARD` in the transaction history and displays a congratulatory dialog.

---

## 19. Feature Control Reconciliation (`/config/features`)

The Admin App proposes controlling economy rollouts via `/config/features`.

### Supported States:
* `ACTIVE`: Feature is fully functional.
* `COMING_SOON`: Feature UI is visible or accessible, but actions trigger the mandatory message:
  **"هذه الميزة ستضاف قريبًا"**
* `DISABLED`: Feature is completely hidden from UI or disabled with an inactive notice.

### Controlled Features:
1. `subscriptions` (Pro / Pro Lite purchases)
2. `points` (Wallet display & ledger)
3. `dailyLogin` (Daily check-in streak)
4. `rewardedAds` (Video ad rewards)
5. `tasks` (Task catalog & claims)
6. `leaderboard` (Weekly rankings)

---

## 20. Economy Configuration Consumption (`/config/economy`)

The Users App must dynamically read pricing and reward parameters from `/config/economy` rather than relying on hardcoded numbers:
* `redemptionCosts`: Points needed for `pro_lite_1d`, `pro_lite_7d`, `pro_lite_10d`, `pro_30d`.
* `dailyLoginRewards`: Points awarded for Day 1 through Day 7.
* `rewardedAdPoints`: Default 15 points.
* `rewardedAdDailyCap`: Default 5.
* `rewardedAdCooldownSeconds`: Default 300.

---

## 21. Offline Trust Model

| Domain | Read Behavior | Write Behavior | Cache Mechanism | Authoritative Source | Offline Fallback Behavior |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Subscription** | Cached read allowed | Write forbidden | In-memory `UserRestrictions` | Firestore `/users/{uid}` | Retains last received tier for active session; defaults to `FREE` on fresh cold start. |
| **Points Wallet** | Cached read allowed | Write forbidden | In-memory / Room cache | Firestore `/users/{uid}.pointsBalance` | Displays last known balance with "Offline" badge. |
| **Points Spending** | ONLINE ONLY | Write forbidden | None (Forbidden offline) | Server Transaction | Button disabled offline. No offline queuing permitted. |
| **Daily Login** | Cached status read | ONLINE ONLY | None | Server Timestamp (UTC) | Button disabled offline ("Internet required"). |
| **Rewarded Ads** | ONLINE ONLY | ONLINE ONLY | None | Ad Network + Server | Disabled offline. Ads cannot load or credit without connectivity. |
| **Tasks** | Cached catalog read | ONLINE ONLY | Room / Memory | `/reward_tasks` + `/task_claims` | View-only; "Claim" button disabled offline. |
| **Leaderboard** | Cached read allowed | Write forbidden | Room / Memory | `/leaderboard/weekly_current` | Shows last synced rankings with "Offline Cache" indicator. |

---

## 22. Client Trust Boundary

```
┌────────────────────────────────────────────────────────────────────────┐
│                        CINESTREAM USERS APP                            │
│                      (Trusted Execution Plane)                         │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
       CAN ONLY READ               │         CAN REQUEST (VIA FUNCTIONS)
       ─────────────────────────   │         ───────────────────────────
       • /users/{uid}              │         • Request Pro Plan (Proof)
       • /users/{uid}/point_tx/*   │         • Request Daily Login Claim
       • /users/{uid}/task_claims  │         • Request Rewarded Ad Credit
       • /reward_tasks/*           │         • Request Task Claim
       • /leaderboard/*            │         • Request Subscription Redeem
       • /config/app               │
       • /config/features          │
       • /config/economy           │
                                   ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   STRICT CLIENT WRITE PROHIBITIONS                     │
│  The Users App MUST NEVER under any circumstances write or modify:     │
│                                                                        │
│  ✖ subscriptionTier          ✖ subscriptionStatus                      │
│  ✖ planId                    ✖ subscriptionExpiresAt                   │
│  ✖ durationDays              ✖ isPremium / isPro                       │
│  ✖ pointsBalance             ✖ totalPointsEarned / totalPointsSpent    │
│  ✖ point_transactions/*      ✖ task_claims/*                           │
│  ✖ reward_tasks/*            ✖ leaderboard/*                           │
│  ✖ /config/*                 ✖ /auditLogs/*                            │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 23. Current Code Reuse Analysis

| Component | Current File | Recommendation | Planned Architectural Evolution |
| :--- | :--- | :---: | :--- |
| `User` | `AuthRepository.kt` | **EXTEND** | Add `planId`, `durationDays`, `subscriptionSource`, `pointsBalance`. Maintain legacy backward compatibility fields. |
| `UserRestrictions` | `UserRestrictions.kt` | **EXTEND** | Update `isSubscriptionActive` to recognize `PRO` and `PRO_LITE`. Decouple `allowedQuality` from subscription checks. |
| `UserSecurityManager` | `UserSecurityManager.kt` | **EXTEND** | Update snapshot listener to parse canonical tier and expiry. Keep existing listener pattern on `/users/{uid}`. |
| `AuthRepository` | `AuthRepository.kt` | **EXTEND** | Retain user session management; incorporate wallet summary balance sync. |
| `SubscriptionScreen` | `SubscriptionScreen.kt` | **REPLACE** | Replace static 2-card mock with dynamic catalog supporting `PRO_LITE` (1, 7, 10 days) and `PRO` (30 days), points redemption, and status indicators. |
| `AdManager` | `AdManager.kt` | **EXTEND** | Keep StartApp integration. Reconnect `showInterstitial()` to playback/details navigation for Free users. Add rewarded ad support. |
| `AppStartupManager` | `AppStartupManager.kt` | **EXTEND** | Expand startup config loading to listen to `/config/features` and `/config/economy` once rules allow. |
| `AppConfig` | `AppConfig.kt` | **KEEP** | Retain `/config/app` mapping for OTA updates, maintenance, and global defaults. |
| `ProfileScreen` | `ProfileScreen.kt` | **EXTEND** | Add Points Balance widget and tier badge (`PRO` / `PRO LITE` / `FREE`). |
| `AccountSettingsScreen` | `AccountSettingsScreen.kt` | **EXTEND** | Update subscription settings row to display active tier name and days remaining. |
| `SmartDownloadQualityDialog` | `SmartDownloadQualityDialog.kt` | **EXTEND** | Decouple quality resolution checks from `restrictions.isPremium`. Rely strictly on `restrictions.isQualityAllowed()`. |
| `UnifiedDownloadCoordinator` | `UnifiedDownloadCoordinator.kt` | **EXTEND** | Decouple quality checks from `restrictions.isPremium`. |

---

## 24. Security Rule Compatibility Analysis (`firestore.rules`)

Inspection of the current production `firestore.rules` reveals significant gaps with the proposed 02A architecture:

| Firestore Path | Current Rule Status in `firestore.rules` | Future Required Rule | Security & Execution Risk |
| :--- | :--- | :--- | :--- |
| `/config/features` | **DENIED** (`match /config/{document=**}` `allow read, write: if false;`) | `allow read: if true; allow write: if isAdmin();` | **BLOCKING:** Any client read currently throws `PERMISSION_DENIED`. Must be updated before client consumes it. |
| `/config/economy` | **DENIED** (`match /config/{document=**}` `allow read, write: if false;`) | `allow read: if true; allow write: if isAdmin();` | **BLOCKING:** Any client read currently throws `PERMISSION_DENIED`. Must be updated before client consumes it. |
| `/users/{uid}/point_transactions` | **DENIED** (No matching rule under `/users/{userId}`) | `allow read: if isOwner(userId) \|\| isAdmin(); allow write: if false;` | **BLOCKING:** Users cannot read their own transaction history until subcollection rule is added. |
| `/users/{uid}/task_claims` | **DENIED** (No matching rule under `/users/{userId}`) | `allow read: if isOwner(userId) \|\| isAdmin(); allow write: if false;` | **BLOCKING:** Users cannot read claimed tasks. |
| `/reward_tasks/{taskId}` | **DENIED** (No rule exists) | `allow read: if isAuthenticated(); allow write: if isAdmin();` | **BLOCKING:** Task catalog unreadable by users. |
| `/leaderboard/{doc}` | **DENIED** (No rule exists) | `allow read: if isAuthenticated(); allow write: if isAdmin();` | **BLOCKING:** Leaderboard unreadable by users. |
| `/leaderboard_history/{id}` | **DENIED** (No rule exists) | `allow read: if isAuthenticated(); allow write: if isAdmin();` | **BLOCKING:** Historical leaderboard unreadable by users. |
| `/pro_requests/{id}` | **DENIED** (No rule exists) | `allow create: if isAuthenticated() && isOwner(request.resource.data.userId); allow read: if isOwner(resource.data.userId) \|\| isAdmin(); allow update, delete: if isAdmin();` | **BLOCKING:** Pro request submissions denied. |

---

## 25. Canonical Path Consumption Matrix

```
==================================================================================================================================================
PATH                                | CURRENT USERS USAGE | CANONICAL FUTURE USAGE | USERS READ | USERS WRITE | SERVER WRITE | RULES STATUS   | VERIFIED?
==================================================================================================================================================
/users/{uid}                        | Read / Update non-sens| Read Profile + Wallet  | YES        | Non-sens    | Full Auth    | VERIFIED       | PROD-VERIFIED
/users/{uid}/point_transactions/{id}| None                | Read Ledger History    | YES        | NO          | YES (Only)   | DENIED         | CONTRACT ONLY
/users/{uid}/task_claims/{id}       | None                | Read Claimed State     | YES        | NO          | YES (Only)   | DENIED         | CONTRACT ONLY
/pro_requests/{requestId}           | None                | Submit Upgrade Request | YES (Own)  | Create Only | Update/Appr  | DENIED         | CONTRACT ONLY
/reward_tasks/{taskId}              | None                | Read Active Tasks      | YES        | NO          | YES (Admin)  | DENIED         | CONTRACT ONLY
/leaderboard/weekly_current         | None                | Read Weekly Rankings   | YES        | NO          | YES (Server) | DENIED         | CONTRACT ONLY
/leaderboard_history/{cycleId}      | None                | Read Settled Archives  | YES        | NO          | YES (Server) | DENIED         | CONTRACT ONLY
/config/app                         | Read App Config     | Read OTA / Maintenance | YES        | NO          | YES (Admin)  | VERIFIED       | PROD-VERIFIED
/config/features                    | None                | Read Feature Flags     | YES        | NO          | YES (Admin)  | EXPLICIT DENY  | CONTRACT ONLY
/config/economy                     | None                | Read Pricing / Rewards | YES        | NO          | YES (Admin)  | EXPLICIT DENY  | CONTRACT ONLY
/auditLogs/{logId}                  | None                | None (Admin Internal)  | NO         | NO          | YES (Admin)  | ADMIN ONLY     | PROD-VERIFIED
==================================================================================================================================================
```

---

## 26. Users-Side State Machines

### 1. Subscription Lifecycle State Machine:
```
           ┌──────────────────────────────────────────────┐
           │                     FREE                     │
           └───────┬──────────────────────────────▲───────┘
                   │                              │
         Purchase / Redeem / Grant                │ Expiration (expiresAt <= now)
                   │                              │
                   ▼                              │
           ┌──────────────────────────────┐       │
           │      PRO / PRO_LITE          ├───────┘
           │         (ACTIVE)             │
           └──────────────┬───────────────┘
                          │
                   Renewal / Extend
                          │
                          ▼
           ┌──────────────────────────────┐
           │      PRO / PRO_LITE          │
           │         (EXTENDED)           │
           └──────────────────────────────┘
```

### 2. Points Redemption State Machine:
```
[User Selects Plan in Wallet]
       │
       ├─► Balance < Cost ────────────► "Insufficient Points" Warning
       ├─► Feature DISABLED/COMING_SOON► "هذه الميزة ستضاف قريبًا"
       ├─► Network Offline ───────────► "Connection Required"
       │
       ▼ (Valid & Online)
[User Confirms Redemption] ──► Call Trusted Backend
                                      │
       ┌──────────────────────────────┴──────────────────────────────┐
       │                                                             │
       ▼ Success                                                     ▼ Failure
  /users/{uid} updated:                                        Error Toast:
  - pointsBalance deducted                                     "Redemption Failed"
  - subscriptionTier = "PRO_LITE"                              (No points deducted)
  - subscriptionExpiresAt extended
  - point_transactions logged
```

---

## 27. UI Contract Specifications

### 1. Subscription Screen (`SubscriptionScreen.kt`):
* **Header:** Displays current tier badge (`FREE`, `PRO_LITE`, `PRO`) with dynamic crown graphic and remaining days countdown.
* **Catalog Grid:**
  * `PRO_LITE` 1 Day
  * `PRO_LITE` 7 Days
  * `PRO_LITE` 10 Days
  * `PRO` 30 Days
* **Card Attributes:** Shows price in local currency / USD and equivalent Points redemption cost.
* **Action Buttons:** "Upgrade with Cash" (or Pro Request) and "Redeem with X Points".

### 2. Points Hub (`PointsScreen.kt` / Future Composable):
* **Hero Card:** Spendable points balance with animated counter.
* **Quick Actions:** Daily Check-in button, Watch Video Ad button, View Tasks button.
* **History Feed:** Chronological list of point transactions with colored positive/negative indicators and timestamps.

### 3. Daily Login Section:
* 7-day visual step track.
* Checkmarks on completed days.
* Highlight on current day with "Claim +X Points" or "Claimed" status.
* Countdown timer to next UTC reset.

### 4. Leaderboard View:
* Top 3 podium display (Gold, Silver, Bronze) with award badges.
* Ranks 4–50 scrollable list.
* Persistent bottom bar showing authenticated user's current rank, avatar, and weekly points.

---

## 28. Migration Safety Directives

To guarantee zero regression for existing installed apps and live users:
1. **Never Delete Legacy Fields:** Do not delete or remove parsing for `isPremium`, `plan`, `proPlan`, or `proExpiresAt`.
2. **Preserve Fallbacks:** If modern fields (`subscriptionTier`, `planId`) are absent on a legacy document, the Users App must continue to parse `isPremium` and `subscriptionExpiresAt`.
3. **No Batch Client Migrations:** The Users App must never attempt to migrate documents in Firestore on the client side. Schema migrations must be performed by Admin scripts or Cloud Functions.
4. **Preserve Current Ad Behavior:** Do not prematurely re-enable ads on accounts currently treated as VIP under legacy logic until explicit migration has occurred.

---

## 29. CONTRACT CONFLICT REGISTER

The following register details all architectural discrepancies identified between the Current Users App, the Admin 02A Contract, and the current Firestore Rules.

```
========================================================================================================================
ID       | CONFLICT TITLE               | CURRENT FACT            | ADMIN 02A CONTRACT      | RESOLUTION & PHASE
========================================================================================================================
CONF-001 | /config/features Blocked     | firestore.rules denies  | Admin specifies dynamic | RESOLUTION: Update rules in
         | by Firestore Rules           | read access to          | feature states          | Rules Phase to allow read:
         |                              | /config/{document=**}   | (ACTIVE/COMING_SOON).   | if true for /config/features.
         |                              |                         |                         | Phase: 02C (Rules Alignment)
---------+------------------------------+-------------------------+-------------------------+---------------------------
CONF-002 | /config/economy Blocked      | firestore.rules denies  | Admin specifies dynamic | RESOLUTION: Update rules in
         | by Firestore Rules           | read access to          | pricing & reward params | Rules Phase to allow read:
         |                              | /config/{document=**}   | via /config/economy.    | if true for /config/economy.
         |                              |                         |                         | Phase: 02C (Rules Alignment)
---------+------------------------------+-------------------------+-------------------------+---------------------------
CONF-003 | Legacy VIP / Premium Naming  | Users App expects       | Admin 02A specifies     | RESOLUTION: Normalizer in
         | vs Canonical Tiers           | "free", "premium", "vip"| "FREE", "PRO_LITE",     | Users App parses canonical
         |                              | lowercase.              | "PRO" uppercase.        | first, maps legacy to PRO.
         |                              |                         |                         | Phase: 03B (Core Sub)
---------+------------------------------+-------------------------+-------------------------+---------------------------
CONF-004 | Subscription Benefit vs      | Users App historically  | Canonical benefit is    | RESOLUTION: Decouple quality
         | Quality Capping Bypass       | bypasses allowedQuality | REMOVE_ADS only; quality| checks from subscriptionTier;
         |                              | if isPremium == true.   | is decoupled.           | use allowedQuality field.
         |                              |                         |                         | Phase: 03B (Core Sub)
---------+------------------------------+-------------------------+-------------------------+---------------------------
CONF-005 | Trusted Backend Availability | No Cloud Functions or   | Relies on "Trusted      | RESOLUTION: Classify backend
         |                              | server authority        | Backend" for atomic     | as EXTERNAL DEPENDENCY. Client
         |                              | currently implemented.  | transactions.           | cannot self-award points.
         |                              |                         |                         | Phase: 02D (Backend Service)
---------+------------------------------+-------------------------+-------------------------+---------------------------
CONF-006 | Points Ledger Subcollection  | /users/{uid}/point_tx   | User reads own ledger   | RESOLUTION: Add rule:
         | Rules Unmatched              | has no match rule;      | history.                | allow read: if isOwner;
         |                              | denied by default.      |                         | allow write: if false.
         |                              |                         |                         | Phase: 02C (Rules Alignment)
---------+------------------------------+-------------------------+-------------------------+---------------------------
CONF-007 | Task Catalog & Claims        | /reward_tasks and       | User reads catalog &    | RESOLUTION: Add read rules
         | Rules Unmatched              | task_claims denied.     | own completed claims.   | for catalog & claims.
         |                              |                         |                         | Phase: 02C (Rules Alignment)
---------+------------------------------+-------------------------+-------------------------+---------------------------
CONF-008 | Leaderboard Collections      | /leaderboard and        | User reads weekly &     | RESOLUTION: Add read rules
         | Rules Unmatched              | _history denied.        | historical rankings.    | for leaderboard paths.
         |                              |                         |                         | Phase: 02C (Rules Alignment)
========================================================================================================================
```

---

## 30. External Dependencies

Implementation of the reconciled architecture cannot proceed until the following external dependencies are satisfied:
1. **Dependency DEP-01: Firestore Security Rules Alignment (Phase 02C):**
   * Firestore rules must be updated and deployed to permit authenticated read access to `/config/features`, `/config/economy`, `/reward_tasks`, `/leaderboard`, and user subcollections (`point_transactions`, `task_claims`).
2. **Dependency DEP-02: Trusted Server Authority / Cloud Functions (Phase 02D):**
   * A secure backend service must exist to execute:
     * `claimDailyLogin` (validates UTC date, awards points, increments streak).
     * `verifyRewardedAd` (validates ad completion token, enforces 5-min cooldown and 5/day cap).
     * `claimTaskReward` (verifies task completion, writes claim record, awards points).
     * `redeemSubscription` (validates balance >= cost, deducts points, sets `subscriptionTier` & `subscriptionExpiresAt`).
     * `weeklyLeaderboardSettlement` (cron job settling ranks, awarding prizes).
3. **Dependency DEP-03: Production Data Seeding (Phase 02E):**
   * Creation of `/config/features` with default states.
   * Creation of `/config/economy` with canonical reward amounts and costs.

---

## 31. Future Implementation Roadmap

```
Phase 02B (THIS PHASE) ────────► Contract Reconciliation & Users Architecture (Complete)
       │
       ▼
Phase 02C (Rules Dependency) ──► Align firestore.rules for economy & config paths
       │
       ▼
Phase 02D (Backend Dependency) ─► Deploy Trusted Server Functions for atomic operations
       │
       ▼
Phase 03B (Users App Core Sub) ─► Implement Canonical Tiers (FREE, PRO_LITE, PRO) & Decouple Quality
       │
       ▼
Phase 04B (Users App Points) ──► Implement Points Wallet, Ledger UI, Daily Login, & Rewarded Ads
       │
       ▼
Phase 05B (Tasks & Leaderboard) ► Implement Task Catalog & Weekly Leaderboard Presentation
       │
       ▼
Phase 06B (E2E Verification) ──► Cross-application end-to-end testing with Admin App
```

---

## 32. Verification

### 1. Build Verification:
* Project compiled with `./gradlew compileDebugSources`: **SUCCESSFUL (0 errors)**.

### 2. Existing Tests Audit:
* Existing unit and integration tests covering managed extensions, TMDB repositories, player states, and FCM token architectures remain green and unaffected.

### 3. Rules Static Audit:
* Confirmed that current `firestore.rules` blocks `/config/features`, `/config/economy`, `/users/{uid}/point_transactions`, `/reward_tasks`, and `/leaderboard`. Tracked in Conflict Register (CONF-001, CONF-002, CONF-006, CONF-007, CONF-008).

### 4. Security Audit:
* Re-verified that client write permissions in `firestore.rules` prohibit normal users from mutating their own subscription, tier, status, or administrative override fields.

---

## 33. Final Status

In accordance with Phase Directive 39:

### **CANONICAL CONTRACT — ALIGNED WITH CONDITIONS**

**Conditions for Implementation:**
1. **Condition 1 (Rules Deployment):** Deployment of updated `firestore.rules` permitting authenticated reads on `/config/features`, `/config/economy`, `/reward_tasks`, `/leaderboard`, `/users/{uid}/point_transactions`, and `/users/{uid}/task_claims`.
2. **Condition 2 (Trusted Backend Availability):** Deployment of authoritative server endpoints / Cloud Functions to execute atomic point minting, daily logins, ad verifications, and subscription redemptions.
3. **Condition 3 (Config Seeding):** Verification that `/config/features` and `/config/economy` documents exist in the active Firestore database.

*Zero production code modifications were made during this phase. Ready to proceed to Rules Alignment upon directive.*
