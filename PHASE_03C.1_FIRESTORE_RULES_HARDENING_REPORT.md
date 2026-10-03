# PHASE 03C.1-HARDENING: FIRESTORE RULES DRIFT + SECURITY HARDENING REPORT
## CineStream Users App — Final Rules Hardening & Points Subscription Contract Baseline

---

### 1. Executive Status
* **Phase:** PHASE 03C.1-HARDENING (Firestore Rules Drift + Security Hardening + Instant Points Subscription Contract)
* **Application:** CineStream Users App (`com.aistudio.cinestream.odtfwm`)
* **Execution Date:** 2026-09-30 / 2026-10-01
* **Status:** **PASS WITH LIMITATIONS**
  * *Reason for Limitations:* All Firestore security rules, contract assertions, and local unit test suites passed completely (67/67 tests passing), but live production Firebase deployment is not verified (`PRODUCTION = NOT VERIFIED`), and Economy Config SKU costs between Admin App (350/1000) and Users fallback (320/800) remain formally recorded as `ECONOMY CONTRACT = UNRESOLVED` pending Phase 03D trusted backend alignment.

---

### 2. Exact Files Changed

1. `/firestore.rules`:
   - Updated `/config/features` read condition from `allow read: if true;` to `allow read: if isAuthenticated();`.
   - Updated `/config/economy` read condition from `allow read: if true;` to `allow read: if isAuthenticated();`.
   - Updated `/config/app` read condition from `allow read: if true;` to `allow read: if isAuthenticated();`.
   - Added explicit match for `/config/search_order` (`allow read: if isAuthenticated(); allow write: if isAdmin();`).
   - Hardened `/users/{userId}` create rule: completely removed `subscriptionStatus == 'ACTIVE'`. Restrained status on create to `'none'`, `'NONE'`, `'inactive'`, `'INACTIVE'`, `''` or omitted.
   - Hardened `/pro_requests/{requestId}` create rule: added strict blacklist preventing privileged fields (`approvedBy`, `approvedAt`, `grantedBy`, `grantSource`, `subscriptionTier`, `subscriptionStatus`, `subscriptionSource`, `subscriptionReferenceId`, `subscriptionStartedAt`, `subscriptionExpiresAt`, `isPremium`, `isPro`, `proPlan`, `proExpiresAt`, `admin`, `isAdmin`, `role`) while preserving required user submission fields (`requestId`, `userId`, `planId`, `durationDays`, `status`, `paymentReference`, `notes`, `createdAt`).
2. `/app/src/test/java/com/example/Phase03C1FirestoreSecurityHardeningTest.kt`:
   - Expanded test suite to implement all 25 specific test cases mandated by Phase 03C.1-Hardening Section 21 plus 3 structural AST verification tests (total 28 tests).
3. `/PHASE_03C.1_FIRESTORE_RULES_HARDENING_REPORT.md`:
   - Comprehensive documentation of security hardening, contracts, decoupling, and verification results.

---

### 3. Rules Before/After Matrix

| Path / Collection | Operation | Previous Rule (Phase 03C.1) | Hardened Rule (Phase 03C.1-Hardening) | Security Impact |
| :--- | :--- | :--- | :--- | :--- |
| `/config/features` | READ | `allow read: if true;` | `allow read: if isAuthenticated();` | Prevents unauthenticated scraping of feature flags; guests denied. |
| `/config/economy` | READ | `allow read: if true;` | `allow read: if isAuthenticated();` | Prevents unauthenticated scraping of economy configuration; guests denied. |
| `/config/app` | READ | `allow read: if true;` | `allow read: if isAuthenticated();` | Aligns app configuration with authenticated client access contract. |
| `/config/search_order` | READ | Unspecified (denied by catch-all) | `allow read: if isAuthenticated();` | Explicitly enables authenticated users to read search priority; admin write only. |
| `/config/{document=**}` | READ/WRITE | `allow read, write: if false;` | `allow read, write: if false;` | Unchanged. Deprecated/unknown config paths remain strictly blocked. |
| `/users/{userId}` | CREATE | Allowed `subscriptionStatus == 'ACTIVE'` | **DENIED** `subscriptionStatus == 'ACTIVE'`. Only `'none'`, `'inactive'`, `''` permitted. | Prevents new users from creating themselves as active subscribers. |
| `/pro_requests/{requestId}` | CREATE | Checked `userId` match and `status == 'PENDING'` | Added `!request.resource.data.keys().hasAny(['approvedBy', 'approvedAt', 'grantedBy', 'grantSource', 'subscriptionTier', ...])` | Prevents injection of self-approval or privileged subscription grant fields. |
| `/pro_requests/{requestId}` | UPDATE/DELETE | `allow update, delete: if isAdmin();` | `allow update, delete: if isAdmin();` | Unchanged. Normal users cannot approve, modify, or delete requests. |
| `/users/{userId}` | UPDATE | Blacklists points & subscription fields | Blacklists points & subscription fields | Unchanged. Zero client economic authority. |
| `/users/{userId}/point_transactions/{txId}` | WRITE | `allow write: if isAdmin();` | `allow write: if isAdmin();` | Unchanged. Client writes to ledger strictly blocked. |

---

### 4. Config Security

* **Access Profile:**
  - `/config/app`: READ authenticated, WRITE admin.
  - `/config/search_order`: READ authenticated, WRITE admin.
  - `/config/features`: READ authenticated, WRITE admin.
  - `/config/economy`: READ authenticated, WRITE admin.
  - `/config/{document=**}`: Catch-all DENIED for all read/write operations.
  - `/config/global`: **NOT** resurrected.
* **Integrity:** Guest / unauthenticated requests to configuration documents are rejected.

---

### 5. User Creation Security

* **Zero-Privilege Baseline:** Normal client accounts can only be created with default non-privileged state:
  - `role`: must be `'user'` (or omitted).
  - `isPremium`: must be `false` (or omitted).
  - `subscriptionTier`: must be `'free'` / `'FREE'` (or omitted). `PRO` and `PRO_LITE` are explicitly rejected.
  - `plan` & `planId`: must be `'free'` (or omitted).
  - `durationDays`: must be `0` (or omitted).
  - `subscriptionStatus`: **`ACTIVE` is strictly rejected**. Only `'none'`, `'NONE'`, `'inactive'`, `'INACTIVE'`, or `''` allowed.
  - `subscriptionSource`: must be `'LEGACY'` (or omitted).
  - `pointsBalance`: must be `0` (or omitted).
  - `totalPointsEarned` & `totalPointsSpent`: must be `0` (or omitted).
  - Privileged fields (`subscriptionExpiresAt`, `subscriptionStartedAt`, `subscriptionReferenceId`, `proExpiresAt`, `proPlan`, etc.) are blacklisted on creation via `!request.resource.data.keys().hasAny([...])`.

---

### 6. Pro Request Security

* **Creation Constraint:**
  - `request.resource.data.userId == request.auth.uid`
  - `request.resource.data.status in ['PENDING', 'pending']`
  - Strict blacklist on payload keys prevents self-granting:
    - Forbidden: `approvedBy`, `approvedAt`, `grantedBy`, `grantSource`
    - Forbidden: `subscriptionTier`, `subscriptionStatus`, `subscriptionSource`, `subscriptionReferenceId`, `subscriptionStartedAt`, `subscriptionExpiresAt`
    - Forbidden: `isPremium`, `isPro`, `proPlan`, `proExpiresAt`, `admin`, `isAdmin`, `role`
  - Permitted request metadata preserved: `requestId`, `userId`, `planId`, `durationDays`, `status`, `paymentReference`, `notes`, `createdAt`.
* **Resolution Constraint:**
  - `allow update, delete: if isAdmin();`
  - Normal users have zero authority to update status to `APPROVED`, change plans, or self-activate subscriptions.

---

### 7. Point Ledger Security

* **Subcollection:** `/users/{userId}/point_transactions/{txId}`
* **Read Policy:** `isOwner(userId) || isAdmin()` (Users can read only their own transaction history; cross-user reading is denied).
* **Write Policy:** `isAdmin()` (Normal users are completely blocked from inserting, modifying, or deleting transaction records).
* **Authority:** The client SDK never writes to `point_transactions`.

---

### 8. Subscription Field Security

* **User Document Protection:**
  - Normal users are blocked from updating `subscriptionTier`, `planId`, `durationDays`, `subscriptionStatus`, `subscriptionSource`, `subscriptionReferenceId`, `subscriptionStartedAt`, `subscriptionExpiresAt`, `isPremium`, `isPro`, `proPlan`, `proExpiresAt`, `pointsBalance`, `totalPointsEarned`, and `totalPointsSpent` via the update `affectedKeys().hasAny([...])` blacklist.
  - Any attempt by client code or a compromised client to alter these fields results in immediate `PERMISSION_DENIED`.

---

### 9. Instant Points Redemption Contract (Future Backend Contract vs. Implemented Now)

> [!CRITICAL]
> **DISTINCTION:**
> - **IMPLEMENTED NOW:** Zero client-side mutation, read-only UI model compatibility, security rules denying client economic writes, test suite validating zero economic mutation.
> - **CONTRACT DEFINED FOR FUTURE BACKEND:** The atomic redemption sequence, Cloud Function/Worker execution, and instant activation described below are **CONTRACT SPECIFICATIONS ONLY**. No backend or redemption engine was implemented in this phase.

#### Canonical Backend Operation: Atomic Points Subscription Redemption
When implemented in a future phase by the trusted server authority (Cloud Functions / Cloudflare Worker):
1. **Authentication:** Authenticate `request.auth.uid`.
2. **SKU Validation:** Validate selected canonical SKU (`pro_lite_1d`, `pro_lite_7d`, `pro_lite_10d`, `pro_30d`) against `/config/economy` and `/config/features`.
3. **Balance Check:** Read current user `pointsBalance`; verify `pointsBalance >= requiredCost`.
4. **Current Subscription Inspection:** Read current `/users/{uid}` subscription state.
5. **Period Calculation:** If existing subscription is active and unexpired, extend from `current subscriptionExpiresAt`. Otherwise, start from `currentTimeMillis()`.
6. **Atomic Transaction:** Within a single Firestore transaction:
   - Decrement `pointsBalance` by `requiredCost`.
   - Increment `totalPointsSpent` by `requiredCost`.
   - Create ledger document in `/users/{uid}/point_transactions/{txId}` with:
     - `transactionType = "SUBSCRIPTION_REDEMPTION"`
     - `amount = -requiredCost`
     - `referenceId = generatedTxId`
     - `createdAt = serverTimestamp()`
   - Update canonical subscription fields on `/users/{uid}`:
     - `subscriptionTier = [PRO_LITE | PRO]`
     - `planId = [sku]`
     - `durationDays = [days]`
     - `subscriptionStatus = "ACTIVE"`
     - `subscriptionSource = "POINTS"`
     - `subscriptionReferenceId = generatedTxId`
     - `subscriptionStartedAt = serverTimestamp()`
     - `subscriptionExpiresAt = calculatedExpirationTimestamp`
7. **Idempotency:** Request must carry an idempotency key to prevent duplicate redemptions, double-debiting, or multiple subscription activations.
8. **All-or-Nothing:** If any step fails, the transaction rolls back completely. Zero partial state allowed.

---

### 10. Pro Requests vs. Points Redemption (Two Separate Flows)

| Dimension | Flow A: Manual Request (`pro_requests`) | Flow B: Points Redemption (Future Trusted Backend) |
| :--- | :--- | :--- |
| **Mechanism** | Submits document to `/pro_requests/{requestId}` | Calls trusted backend API / Cloud Function |
| **Initial Status** | `PENDING` | `ACTIVE` immediately upon transaction commit |
| **Review Required** | Yes — Admin / manual operator reviews proof of payment | None — Fully automated self-service |
| **Collection Used** | `/pro_requests/{requestId}` | `/users/{uid}` & `/users/{uid}/point_transactions/{txId}` |
| **Points Deducted** | None (typically money/manual transfer) | Atomically debited by trusted backend |
| **pro_requests Doc Created**| **YES** | **NO** (Flow B MUST NOT create a `pro_requests` document) |

---

### 11. Subscription Extension Contract

* **Rule:**
  - If existing subscription is **ACTIVE** (`isSubscriptionActive == true` and `subscriptionExpiresAt > now`):
    `newExpiresAt = existingExpiresAt + (durationDays * 86,400,000L)`
  - If existing subscription is **INACTIVE** or **EXPIRED** (`subscriptionExpiresAt <= now`):
    `newExpiresAt = now + (durationDays * 86,400,000L)`
* **Example:**
  - Existing expiry: 2026-10-10
  - User redeems: `pro_lite_7d` (7 days)
  - Resulting expiry: 2026-10-17 (NOT 2026-10-07)
* *Note: This rule is documented for future backend implementation. Users App does not calculate or write expiration dates.*

---

### 12. Economy Config Discrepancy

* **Status:** **ECONOMY CONTRACT = UNRESOLVED**
* **Discrepancy Inventory:**
  1. **SKU Redemption Costs:**
     - Admin App Phase 02A documented:
       - `pro_lite_1d`: 50
       - `pro_lite_7d`: 250
       - `pro_lite_10d`: 350
       - `pro_30d`: 1000
     - Users App baseline (`EconomyModels.kt`):
       - `pro_lite_1d`: 50
       - `pro_lite_7d`: 250
       - `pro_lite_10d`: 320 (Discrepancy: -30 points)
       - `pro_30d`: 800 (Discrepancy: -200 points)
  2. **Daily Login Ladder:**
     - Users App default ladder: `[5, 10, 15, 20, 25, 30, 50]`
  3. **Consumption Behavior:**
     - Users App `EconomyConfigRepository` dynamically consumes remote `/config/economy` when available.
     - Local values serve strictly as fallback defaults in offline/missing document states.
* **Resolution Strategy:**
  - Per Phase 03C.1-Hardening Rule 18, local values were **NOT** silently overwritten, and no arbitrary intermediate values were invented.
  - Formal alignment of authoritative economy parameters will occur when the trusted backend is deployed in a future phase.

---

### 13. Quality Decoupling Verification

* **Business Invariant:** `SUBSCRIPTION = REMOVE_ADS ONLY`.
* **Verification Results:**
  - `FREE`: Ads active. Full unrestricted access to all source-available video resolutions (144p through 4K) and download formats.
  - `PRO_LITE`: Ads suppressed (`isAdFree == true`). Full unrestricted access to all source-available video resolutions (144p through 4K) and download formats.
  - `PRO`: Ads suppressed (`isAdFree == true`). Full unrestricted access to all source-available video resolutions (144p through 4K) and download formats.
  - Subscription status/tier has zero coupling to:
    - `allowedQuality`
    - `downloadLimit`
    - Scraper extraction capabilities
    - Stream resolution detection or selection
    - Player track parameters

---

### 14. Test Results

Executed via Gradle local unit testing (`gradle :app:testDebugUnitTest`):

| Test Suite Class | Tests Run | Passed | Failed | Skipped | Notes |
| :--- | :---: | :---: | :---: | :---: | :--- |
| `Phase03C1FirestoreSecurityHardeningTest` | 28 | 28 | 0 | 0 | Implements all 25 Section 21 tests + 3 structural rule checks |
| `SubscriptionQualityDecouplingUnitTest` | 29 | 29 | 0 | 0 | Validates quality decoupling across all 23 scenarios + Critical A-F |
| `FirestoreRulesAlignmentTest` | 10 | 10 | 0 | 0 | Validates managed extension collection rules and admin matrix |
| **TOTAL** | **67** | **67** | **0** | **0** | **100% Pass Rate across all security and decoupling suites** |

#### Section 21 Test Matrix Coverage in `Phase03C1FirestoreSecurityHardeningTest`:
- **TEST 01:** `test01_GuestCannotReadConfigFeatures` -> **PASS**
- **TEST 02:** `test02_AuthenticatedUserCanReadConfigFeatures` -> **PASS**
- **TEST 03:** `test03_GuestCannotReadConfigEconomy` -> **PASS**
- **TEST 04:** `test04_AuthenticatedUserCanReadConfigEconomy` -> **PASS**
- **TEST 05:** `test05_AuthenticatedUserCannotWriteConfigFeatures` -> **PASS**
- **TEST 06:** `test06_AuthenticatedUserCannotWriteConfigEconomy` -> **PASS**
- **TEST 07:** `test07_UserCannotCreateNewActiveSubscriptionState` -> **PASS**
- **TEST 08:** `test08_UserCannotCreateProUserState` -> **PASS**
- **TEST 09:** `test09_UserCannotCreatePrivilegedSubscriptionFields` -> **PASS**
- **TEST 10:** `test10_UserCanCreateOwnValidPendingProRequest` -> **PASS**
- **TEST 11:** `test11_UserCannotCreateProRequestForAnotherUser` -> **PASS**
- **TEST 12:** `test12_UserCannotInsertPrivilegedSubscriptionFieldsIntoProRequests` -> **PASS**
- **TEST 13:** `test13_UserCannotApproveOwnProRequest` -> **PASS**
- **TEST 14:** `test14_UserCannotActivateOwnSubscriptionThroughProRequests` -> **PASS**
- **TEST 15:** `test15_UserCannotModifyPointsBalance` -> **PASS**
- **TEST 16:** `test16_UserCannotModifyTotalPointsEarned` -> **PASS**
- **TEST 17:** `test17_UserCannotModifyTotalPointsSpent` -> **PASS**
- **TEST 18:** `test18_UserCannotWritePointTransactions` -> **PASS**
- **TEST 19:** `test19_UserCanReadOwnPointTransactions` -> **PASS**
- **TEST 20:** `test20_UserCannotReadAnotherUsersPointTransactions` -> **PASS**
- **TEST 21:** `test21_UserCanReadRewardTasks` -> **PASS**
- **TEST 22:** `test22_UserCannotWriteRewardTasks` -> **PASS**
- **TEST 23:** `test23_UserCanReadLeaderboard` -> **PASS**
- **TEST 24:** `test24_UserCannotWriteLeaderboard` -> **PASS**
- **TEST 25:** `test25_SubscriptionQualityDecouplingRemainsIntact` -> **PASS**

*Applet compilation:* `compile_applet` executed cleanly with zero errors.

---

### 15. Static Scan Results

Static security scans executed across all source files for unauthorized economic mutation paths:
* `pointsBalance` / `totalPointsEarned` / `totalPointsSpent`: No client code calls `FieldValue.increment()` or mutates balances. Handled strictly read-only by `PointsRepository` and `EconomyConfigRepository`.
* `subscriptionTier` / `subscriptionStatus` / `subscriptionExpiresAt`: No client write path exists for existing user profiles. `AuthRepository.saveUser()` only populates default free fields upon initial new user document creation.
* `allowedQuality` / `downloadLimit`: Zero coupling to subscription fields.

---

### 16. Production Verification

* **Status:** **NOT VERIFIED**
* **Finding:** No live production Firebase credentials or live production backend environments were available in this sandbox environment. All rules, matrix evaluations, and contracts were verified locally against the official Firestore rule specifications and unit tests.

---

### 17. Out-of-Scope Items (Preserved for Future Phases)

The following items were strictly excluded from Phase 03C.1-Hardening per project instructions:
* Points Earning Engine (Phase 03D)
* Daily Login Engine (Phase 03D)
* Rewarded Ads Verification & Cooldowns (Phase 03D)
* Task Reward Engine (Phase 03D)
* Leaderboard Settlement Engine
* Cloud Functions & Cloudflare Workers
* In-app Billing & Payment Gateways
* Actual server-side points redemption backend implementation

---

### 18. Final Verdict

```
============================================================
PHASE 03C.1-HARDENING VERDICT:
PASS WITH LIMITATIONS
============================================================
```
* **Security & Drift Hardening:** 100% COMPLETE.
* **Rules Hardening:** Verified for config, user creation, pro requests, and point transactions.
* **Instant Points Subscription Contract:** Formally defined and decoupled from manual request flows.
* **Decoupling Invariant:** 100% intact (`SUBSCRIPTION = REMOVE_ADS ONLY`).
* **Limitations Documented:** Production deployment not verified; Economy SKU redemption costs recorded as `ECONOMY CONTRACT = UNRESOLVED`.
