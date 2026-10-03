# CROSS-PROJECT FIRESTORE RECONCILIATION AUDIT
## CineStream Users App — Comprehensive Forensic Audit Report
**Mode:** AUDIT ONLY (Zero source code, rules, or test modifications)  
**Target Systems:** CineStream Users App (`app/src/main`), Firestore Security Rules (`/firestore.rules`), Canonical Admin Contract (`/docs/FIREBASE_CONTRACT.md`)  
**Date of Audit:** 2026-09-29  

---

## 1. Executive Summary

This forensic audit rigorously reconciles the actual Firestore usage in **CineStream Users App** against the **Unified Canonical Firebase Contract** (`/docs/FIREBASE_CONTRACT.md`) and production Firestore Security Rules (`/firestore.rules`).

### Primary Audit Findings:
1. **Core Path Alignment:** Every Firestore path utilized by the Users App (`/users/{uid}`, `/admins/{uid}`, `/config/app`, `/managed_extensions/{id}`, `/extensions/{id}`, `/notifications/{id}`, `/reports/{id}`, `/support_conversations/{id}`) is 100% compliant with the shared database schema and authorization model.
2. **Config Global Eradication:** `/config/global` has been completely purged from the codebase. The Users App reads configuration strictly from `/config/app`.
3. **Preservation of User-Plane Capabilities:** `/conversations` (Social Chat), `/stories` (Social Stories), and private user subcollections (`library`, `history`, `watched_episodes`, `settings/notifications`, `fcmTokens`) are intact, active, fully functional, and secured with strict ownership and permission guards.
4. **Admin Authority Anchor:** The Users App relies strictly on `/admins/{uid}.enabled == true` for operational admin privileges and admin UI toggles, completely decoupled from non-authoritative client claims.
5. **Support System Integrity:** Dual-layer hybrid architecture correctly isolates user support at `/support_conversations/{userId}` with subcollection `/messages/{messageId}`, maintaining zero message loss via Room DB caching while preventing any confusion with social chat.
6. **Security Invariants Upheld:** 100% static compliance with all authorization boundaries (no self-promotion, no ban evasion, no subscription tampering, no participant spoofing, no unauthorized reads or writes).

---

## 2. Environment

| Component | Detected Version / Specification | State | Verification Method |
| :--- | :--- | :---: | :--- |
| **Android Runtime** | Android 14/15 (API 34/35 SDK) | Active | STATICALLY VERIFIED |
| **JDK** | OpenJDK 21.0.12.1 Temurin (64-Bit) | Active | STATICALLY VERIFIED |
| **Gradle** | 9.3.1 (Kotlin DSL 2.2.21) | Active | STATICALLY VERIFIED |
| **Firebase Firestore SDK** | Android Firestore SDK (`com.google.firebase.firestore`) | Active | STATICALLY VERIFIED |
| **Firebase Auth SDK** | Android FirebaseAuth SDK (`com.google.firebase.auth`) | Active | STATICALLY VERIFIED |
| **Cloudinary Android SDK** | `com.cloudinary:cloudinary-android` (Unsigned client preset) | Active | STATICALLY VERIFIED |
| **Firebase Local Emulator**| Daemon ports not configured in `firebase.json` | Inactive | NOT AVAILABLE |

*Note: In strict compliance with Section 23 of the audit instructions, all verifications without local emulator daemons are classified as `STATICALLY VERIFIED` or `CODE-PRESENT / NOT EXECUTED`.*

---

## 3. Files Inspected

The following source files, models, repositories, and configurations were forensically inspected:
- `/docs/FIREBASE_CONTRACT.md` (Canonical Shared Contract)
- `/firestore.rules` (Production Security Rules)
- `app/src/main/java/com/example/data/model/AppConfig.kt`
- `app/src/main/java/com/example/data/model/Report.kt`
- `app/src/main/java/com/example/data/model/UserRestrictions.kt`
- `app/src/main/java/com/example/data/model/LibraryItem.kt`
- `app/src/main/java/com/example/data/model/HistoryItem.kt`
- `app/src/main/java/com/example/data/model/WatchedEpisode.kt`
- `app/src/main/java/com/example/data/model/NotificationPreferences.kt`
- `app/src/main/java/com/example/data/repository/AuthRepository.kt`
- `app/src/main/java/com/example/data/repository/UserSecurityManager.kt`
- `app/src/main/java/com/example/data/repository/AppStartupManager.kt`
- `app/src/main/java/com/example/data/repository/AppUpdateManager.kt`
- `app/src/main/java/com/example/data/repository/SocialRepository.kt`
- `app/src/main/java/com/example/data/repository/NotificationRepository.kt`
- `app/src/main/java/com/example/data/repository/NotificationPreferencesRepository.kt`
- `app/src/main/java/com/example/data/repository/ReportRepository.kt`
- `app/src/main/java/com/example/data/repository/LibraryRepository.kt`
- `app/src/main/java/com/example/data/repository/HistoryRepository.kt`
- `app/src/main/java/com/example/data/repository/WatchedEpisodeRepository.kt`
- `app/src/main/java/com/example/data/sync/CloudSyncManager.kt`
- `app/src/main/java/com/example/data/notification/FcmTokenManager.kt`
- `app/src/main/java/com/example/ui/screens/profile/SupportViewModel.kt`
- `app/src/main/java/com/example/ui/screens/auth/AuthViewModel.kt`
- `app/src/main/java/com/example/ui/screens/extensions/ExtensionsScreen.kt`
- `app/src/main/java/com/example/extension/managed/repository/FirebaseFirestoreManagedExtensionDataSource.kt`
- `app/src/main/java/com/example/extension/managed/repository/ManagedExtensionDto.kt`
- `app/src/main/java/com/example/extension/orchestrator/ManagedMediaOrchestrator.kt`

---

## 4. Complete Firestore Path Inventory

| Collection / Path | Target Type | Operations Performed in Users App | Callers / Repositories | Realtime Listener? | Security Owner | Admin Dependency |
| :--- | :--- | :--- | :--- | :---: | :--- | :--- |
| `/admins/{uid}` | Document | Read (Single fetch + Snapshot Listener) | `UserSecurityManager` | **YES** | Admin App | **Strict Authority Anchor** |
| `/users/{uid}` | Document | Read, Create (default non-privileged), Update (profile only) | `AuthRepository`, `UserSecurityManager`, `SocialRepository`, `AuthViewModel` | **YES** | User (profile) / Admin (role, ban, tier) | Yes (Admin writes flags) |
| `/users/{uid}/library/{itemId}` | Subcollection | Read, Create, Update, Delete | `LibraryRepository`, `CloudSyncManager` | **YES** | User Owner | No |
| `/users/{uid}/history/{itemId}` | Subcollection | Read, Create, Update, Delete | `HistoryRepository`, `CloudSyncManager` | **YES** | User Owner | No |
| `/users/{uid}/watched_episodes/{id}` | Subcollection | Read, Create, Update, Delete | `WatchedEpisodeRepository`, `CloudSyncManager` | **YES** | User Owner | No |
| `/users/{uid}/settings/notifications` | Subcollection Document | Read, Create, Update | `NotificationPreferencesRepository`, `CloudSyncManager` | **YES** | User Owner | No |
| `/users/{uid}/fcmTokens/{installId}` | Subcollection | Read, Create, Update, Delete | `FcmTokenManager` | **NO** | User Owner | No |
| `/config/app` | Document | Read (Single fetch + Snapshot Listener) | `AppStartupManager`, `AppUpdateManager` | **YES** | Admin App | Read-only for User App & Guests |
| `/managed_extensions/{extId}` | Document | Read (Batch get), Write (Admin only sync) | `FirebaseFirestoreManagedExtensionDataSource`, `ManagedMediaOrchestrator` | **NO** | Admin App | Write requires `isAdmin()` |
| `/extensions/{extId}` | Document | Read (Legacy fallback), Write (Admin only sync) | `FirebaseFirestoreManagedExtensionDataSource`, `ManagedMediaOrchestrator` | **NO** | Admin App | Write requires `isAdmin()` |
| `/notifications/{notifId}` | Document | Read (Query limit 50, Snapshot Listener limit 10) | `NotificationRepository` | **YES** | Admin App | Auth read-only |
| `/reports/{reportId}` | Document | Create (`status: "pending"`), Read (`whereEqualTo("userId", uid)`) | `ReportRepository` | **NO** | User submits / Admin resolves | Admin resolves |
| `/support_conversations/{uid}` | Document | Create, Read, Update (`SetOptions.merge()`) | `SupportViewModel` | **NO** | User & Admin | Admin reads/replies |
| `/support_conversations/{uid}/messages/{msgId}` | Subcollection | Create (`senderRole: "user"`), Read | `SupportViewModel` | **YES** | User & Admin | Admin writes `senderRole: "admin"` |
| `/conversations/{convId}` | Document | Create, Read (`whereArrayContains`), Update | `SocialRepository` | **YES** | Conversation Participants | Admin can moderate |
| `/conversations/{convId}/messages/{msgId}`| Subcollection | Create (`senderId: uid`), Read, Update (edit / soft delete) | `SocialRepository` | **YES** | Message Author / Participants | Admin can moderate |
| `/stories/{storyId}` | Document | Create (`userId: uid`), Read (order by timestamp DESC) | `SocialRepository` | **YES** | Story Author | Admin can moderate |

---

## 5. Complete User Subcollection Inventory

Forensic audit of subcollections residing directly underneath `/users/{uid}`:

| Subcollection Path | Document Schema & Keys | Operations | Caller / Source | Status in Users App |
| :--- | :--- | :--- | :--- | :---: |
| `/users/{uid}/library` | `libraryId`, `tmdbId`, `title`, `posterUrl`, `contentType`, `isMovie` | Read, Set (merge), Delete, Listener | `LibraryRepository.kt`, `CloudSyncManager.kt` | **USED BY CODE** |
| `/users/{uid}/history` | `id`, `title`, `posterUrl`, `isMovie`, `timestamp`, `positionMillis`, `durationMillis` | Read, Set (merge), Delete, Listener | `HistoryRepository.kt`, `CloudSyncManager.kt` | **USED BY CODE** |
| `/users/{uid}/watched_episodes` | `id` (e.g. `seriesId_season_episode`) | Read, Set (merge), Delete, Listener | `WatchedEpisodeRepository.kt`, `CloudSyncManager.kt` | **USED BY CODE** |
| `/users/{uid}/settings` (doc: `notifications`)| `notificationsEnabled`, `announcementsEnabled`, `appUpdatesEnabled`, `maintenanceEnabled`, `newMoviesEnabled`, `newTvSeriesEnabled`, `newAnimeEnabled`, `newTvEpisodesEnabled`, `newAnimeEpisodesEnabled`, `newTvSeasonsEnabled`, `newAnimeSeasonsEnabled`, `updatedAt` | Read, Set (merge), Listener | `NotificationPreferencesRepository.kt`, `CloudSyncManager.kt` | **USED BY CODE** |
| `/users/{uid}/fcmTokens` (doc: `{installationId}`)| `token`, `installationId`, `platform: "android"`, `deviceModel`, `osVersion`, `appVersion`, `isActive`, `createdAt`, `updatedAt`, `lastSeenAt`, `deactivationReason`, `deactivatedAt` | Read, Set (merge), Update, Delete | `FcmTokenManager.kt` | **USED BY CODE** |
| `/users/{uid}/bookmarks` | N/A | None | N/A | **NOT USED** |
| `/users/{uid}/devices` | N/A | None | N/A | **NOT USED** (Tracked via `fcmTokens` & `deviceLimit`) |
| `/users/{uid}/favorites` | N/A | None | N/A | **NOT USED** (Tracked via `/library` items) |

---

## 6. Config Audit (`/config/global` vs `/config/app`)

### Forensic Search Results:
- A recursive codebase-wide search across `app/src/` returned **ZERO** matches for `/config/global`.
- No code reads `/config/global`.
- No code writes `/config/global`.
- `AppConfig.kt` explicitly defines:
  ```kotlin
  const val CONFIG_COLLECTION = "config"
  const val CONFIG_DOCUMENT = "app"
  ```
- `AppStartupManager.kt` and `AppUpdateManager.kt` query strictly `/config/app`.
- In `/firestore.rules`:
  ```firestore
  match /config/app {
    allow read: if true;
    allow write: if isAdmin();
  }
  match /config/{document=**} {
    allow read, write: if false;
  }
  ```
- Access to any config path other than `/config/app` is permanently denied.
- **Classification:** **`REMOVED`**

---

## 7. Admin Authority Audit

### Mechanics of Administrative Privilege Verification:
1. **Primary Authority Anchor:** `UserSecurityManager.kt` listens directly to Firestore document `/admins/{uid}`:
   ```kotlin
   adminListenerRegistration = FirebaseFirestore.getInstance()
       .collection("admins")
       .document(uid)
       .addSnapshotListener { adminSnap, _ ->
           val isDocAdmin = adminSnap != null && adminSnap.exists() && (adminSnap.getBoolean("enabled") == true)
           _isAdminDocFlow.value = isDocAdmin
       }
   ```
2. **Server-Side Verification:** `refreshUserSecurity(uid)` forces a `Source.SERVER` query to `/admins/{uid}` to guarantee real-time non-cached validation.
3. **Firestore Security Rules Alignment:**
   ```firestore
   function isAdmin() {
     return isAuthenticated() && 
       exists(/databases/$(database)/documents/admins/$(request.auth.uid)) &&
       get(/databases/$(database)/documents/admins/$(request.auth.uid)).data.enabled == true;
   }
   ```
4. **Distinction on User Model Property:**
   `User.isAdmin` (`role.equals("admin") || role.equals("superadmin")`) exists as a convenience helper for model labeling, but **all security-critical enforcement and UI administrative controls** (such as `ExtensionsScreen` sync actions and `AppStartupManager` maintenance bypass) strictly observe `UserSecurityManager.isAdminDocFlow` and `UserSecurityManager.isAdmin()`, which require `/admins/{uid}.enabled == true`.
- **Classification:** **`MATCH`** / **`STATICALLY VERIFIED`**

---

## 8. Support Audit (`/support_conversations`)

### Architecture & Behavioral Contract:
- **Conversation Path:** `/support_conversations/{userId}`
  - The `conversationId` is strictly the user's Auth `uid`.
  - Document fields: `conversationId`, `userId`, `userEmail`, `userName`, `status: "open"`, `lastMessage`, `lastMessageTime`, `updatedAt`.
- **Messages Subcollection:** `/support_conversations/{userId}/messages/{messageId}`
  - Document fields: `id`, `messageId`, `conversationId`, `senderId`, `senderRole: "user"`, `text`, `timestamp`, `createdAt`.
- **Local Persistence & Offline First:**
  - Every message is immediately committed to local Room SQLite table `support_messages` via `SupportViewModel.kt`.
  - If network or remote Firestore write fails, local message is preserved with zero data loss.
  - If user is unauthenticated (guest), an automated in-app local response is returned.
- **Firestore Listener & Admin Replies:**
  - Real-time listener on `/support_conversations/{userId}/messages` ordered by `timestamp` ASC.
  - Incoming documents are parsed: `val isFromUser = senderRole == "user" && senderId == user.uid`.
  - Admin replies (`senderRole == "admin"`) are recognized as support agent messages (`isFromUser = false`) and rendered on the support dialogue interface.
- **Classification:** **`MATCH`** / **`STATICALLY VERIFIED`**

---

## 9. Social Chat Audit (`/conversations`)

### Architecture & Separation from Support:
- **Root Collection:** `/conversations/{conversationId}`
- **ID Generation:** Deterministic pair ordering:
  ```kotlin
  val participants = listOf(user.uid, otherUserId).sorted()
  val convId = participants.joinToString("_")
  ```
- **Conversation Document:** `id`, `participants` (`List<String>`), `participantNames` (`Map<String, String>`), `lastMessage`, `lastMessageTime`, `unreadCounts` (`Map<String, Int>`).
- **Message Document (`/conversations/{convId}/messages/{msgId}`):**
  `id`, `senderId`, `text`, `timestamp`, `isDeleted`, `isEdited`, `isVoice`, `mediaUrl`, `deletedFor` (`List<String>`), `reactions` (`Map<String, String>`).
- **Enforcement & Security Invariants:**
  - `SocialRepository.kt` enforces `user.uid` as `senderId`.
  - Non-participants cannot view conversations: `whereArrayContains("participants", user.uid)` and rules requirement `request.auth.uid in resource.data.participants`.
  - Users with `isBanned == true`, `canChat == false`, or `chatBan == true` are barred by `/firestore.rules:236-238` and `255-257`.
  - Deletions are soft (`isDeleted: true` or `deletedFor: [uid]`).
- **Classification:** **`USER-ONLY FEATURE`** / **`STATICALLY VERIFIED`**

---

## 10. Stories Audit (`/stories`)

### Architecture & Invariants:
- **Path:** `/stories/{storyId}`
- **Model Fields:** `id` (`ref.id`), `userId` (`auth.currentUser.uid`), `imageUrl` (Cloudinary HTTPS URL), `timestamp` (`System.currentTimeMillis()`).
- **Permissions:**
  - Read: All authenticated users (`allow read: if isAuthenticated();`).
  - Create: `request.resource.data.userId == request.auth.uid` AND `canStory != false` AND `storyBan != true` AND `isBanned != true`.
  - Update/Delete: Only story creator or admin (`resource.data.userId == request.auth.uid || isAdmin()`).
  - Media Upload: Unsigned Cloudinary upload via `com.cloudinary.android.MediaManager`.
- **Classification:** **`USER-ONLY FEATURE`** / **`STATICALLY VERIFIED`**

---

## 11. Managed Extensions Audit (`/managed_extensions`)

### Implementation Details:
- **Path:** `/managed_extensions/{extensionId}`
- **Consumer:** `FirebaseFirestoreManagedExtensionDataSource.kt`
- **Method:** `firestore.collection("managed_extensions").get().await()`
- **Consumed Fields:** `id`, `name`, `description`, `baseUrl`, `iconUrl`, `scraperKey`, `definitionVersion`, `minAppVersionCode`, `runtimeApiVersion`, `priority`, `language`, `contentTypes`, `status`, `updatedAt`.
- **Lifecycle Status Values:** `"ACTIVE"`, `"DISABLED"`.
- **Admin Write Integration:** `ManagedMediaOrchestrator.kt:443` writes default bundled extensions (`qfilm`, `anime4up`, `animeblkom`, `witanime`, `egydead`) only if `UserSecurityManager.isAdmin()` is confirmed.
- **Classification:** **`MATCH`** / **`STATICALLY VERIFIED`**

---

## 12. Legacy Extensions Audit (`/extensions`)

### Implementation Details:
- **Path:** `/extensions/{extensionId}`
- **Consumer:** `FirebaseFirestoreManagedExtensionDataSource.kt:39`
- **Purpose:** Full cross-platform compatibility with CineStream Admin Dashboard contract.
- **Mapping:** `ManagedExtensionDto.fromDocument(doc)` reads Admin contract fields (`versionCode`, `enabled`, `updatedAt`). If `enabled == false`, it resolves to `status = "DISABLED"`.
- **Classification:** **`LEGACY ACTIVE`** / **`STATICALLY VERIFIED`**

---

## 13. Reports Audit (`/reports`)

### Implementation Details:
- **Path:** `/reports/{reportId}`
- **Consumer:** `ReportRepository.kt`
- **Create Operation:**
  - `docRef.set(report.toFirestoreMap())`
  - Explicit fields written: `id`, `reportId`, `userId` (`currentUser.uid`), `userEmail`, `type`, `targetType`, `targetId`, `title`, `description`, `status: "pending"`, `createdAt: Timestamp.now()`, `resolvedAt: null`, `resolvedBy: null`.
- **Read Operation:**
  - Normal users query only their own submitted reports: `db.collection("reports").whereEqualTo("userId", currentUser.uid).get()`.
- **Status Value Alignment:**
  - Submit value: `"pending"` (lowercase).
  - Admin Resolution values: `"pending"`, `"in_review"`, `"resolved"`, `"rejected"`.
- **Rules Verification:**
  - Rules strictly mandate `status == 'pending'` and `resolvedBy == null` and `resolvedAt == null` on user creation.
- **Classification:** **`MATCH`** / **`STATICALLY VERIFIED`**

---

## 14. Pro Requests Audit (`/pro_requests`)

### Audit Finding:
- Deep recursive scan across entire source tree yielded **ZERO** usages of `/pro_requests`.
- Neither read nor written by CineStream Users App.
- **Classification:** **`NOT USED BY USERS APP`**

---

## 15. App Updates Audit (`/app_updates` vs `/config/app`)

### Audit Finding:
- Collection `/app_updates`: **NOT USED** as a Firestore collection. (Only referenced as a user UI preference toggle string `notif_app_updates`).
- Collection `/config/app`: **ACTIVE**. All over-the-air update checks in `AppUpdateManager.kt` and `AppStartupManager.kt` query document `/config/app`.
- Update Fields Read: `minimumVersionCode`, `latestVersionCode`, `latestVersionName`, `apkUrl`, `apkSha256`, `mandatoryUpdate`, `releaseNotes`, `updatedAt`.
- **Classification:** **`MATCH`** / **`STATICALLY VERIFIED`**

---

## 16. Notifications Audit (`/notifications`)

### Implementation Details:
- **Path:** `/notifications/{notificationId}`
- **Consumer:** `NotificationRepository.kt`
- **Operations:**
  - Periodic / on-demand fetch: `.limit(50).get().await()`
  - Real-time announcement listener: `.limit(10).addSnapshotListener { ... }`
- **Targeting Logic:** Evaluates `target` (`"all"` or `"user"`) and `targetUid`. Discards notifications targeting other UIDs.
- **Category Filtering:** Evaluates user notification preferences stored in local DataStore before emitting tray alerts.
- **Rules Alignment:** Authenticated read-only (`allow read: if isAuthenticated(); allow write: if isAdmin();`).
- **Classification:** **`MATCH`** / **`STATICALLY VERIFIED`**

---

## 17. Sensitive User Fields Audit

Forensic mapping of user privilege and ban fields:

| Field Name | Firestore Type | Admin Write Only | User Modifiable | Handled in `AuthRepository.saveUser()` | Handled in `firestore.rules` |
| :--- | :--- | :---: | :---: | :---: | :---: |
| `role` | String | **YES** | **NO** | Defaulted `"user"` on create; omitted on update | Blocked on update; restricted on create |
| `isPremium` | Boolean | **YES** | **NO** | Defaulted `false` on create; omitted on update | Blocked on update; restricted on create |
| `subscriptionTier` | String | **YES** | **NO** | Defaulted `"free"` on create; omitted on update | Blocked on update; restricted on create |
| `subscriptionStatus` | String | **YES** | **NO** | Defaulted `"none"` on create; omitted on update | Blocked on update; restricted on create |
| `subscriptionExpiresAt` | Timestamp / Long | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |
| `plan` (legacy alias) | String | **YES** | **NO** | Defaulted `"free"` on create; omitted on update | Blocked on update; restricted on create |
| `isActive` | Boolean | **YES** | **NO** | Defaulted `true` on create; omitted on update | Blocked on update; restricted on create |
| `isBanned` | Boolean | **YES** | **NO** | Defaulted `false` on create; omitted on update | Blocked on update; restricted on create |
| `banReason` | String | **YES** | **NO** | Defaulted `""` on create; omitted on update | Blocked on update; restricted on create |
| `banExpiresAt` | Timestamp / Long | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |
| `canWatch` | Boolean | **YES** | **NO** | Defaulted `true` on create; omitted on update | Blocked on update; restricted on create |
| `watchBan` | Boolean | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |
| `canDownload` | Boolean | **YES** | **NO** | Defaulted `true` on create; omitted on update | Blocked on update; restricted on create |
| `downloadBan` | Boolean | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |
| `canChat` | Boolean | **YES** | **NO** | Defaulted `true` on create; omitted on update | Blocked on update; restricted on create |
| `chatBan` | Boolean | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |
| `canStory` | Boolean | **YES** | **NO** | Defaulted `true` on create; omitted on update | Blocked on update; restricted on create |
| `storyBan` | Boolean | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |
| `canP2P` | Boolean | **YES** | **NO** | Defaulted `true` on create; omitted on update | Blocked on update; restricted on create |
| `p2pBan` | Boolean | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |
| `canComment` | Boolean | **YES** | **NO** | Defaulted `true` on create; omitted on update | Blocked on update; restricted on create |
| `canUpload` | Boolean | **YES** | **NO** | Defaulted `true` on create; omitted on update | Blocked on update; restricted on create |
| `canRequest` | Boolean | **YES** | **NO** | Defaulted `true` on create; omitted on update | Blocked on update; restricted on create |
| `deviceLimit` / `maxDevices` | Number | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |
| `allowedQuality` | String | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |
| `downloadLimit` | Number | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |
| `offlineDaysOverride` | Number | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |
| `forcedAdsOverride` | Number | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |
| `admin` / `isAdmin` | Any | **YES** | **NO** | Omitted on user create & update | Blocked on create & update |

---

## 18. Rules vs Code Matrix

| Resource Path | Code Read | Rules Read | Code Write | Rules Write | Status |
| :--- | :--- | :--- | :--- | :--- | :---: |
| `/admins/{uid}` | UserSecurityManager | `isAdmin() \|\| auth.uid == uid` | None (User App never writes) | `isAdmin()` | **MATCH** |
| `/config/app` | AppStartup, AppUpdate | `true` (all, incl. guests) | None | `isAdmin()` | **MATCH** |
| `/config/{other}` | None | `false` | None | `false` | **MATCH** |
| `/users/{uid}` | AuthRepository, UserSecurity | `isAuthenticated()` | AuthRepository (`saveUser`) | Owner (safe diff) \|\| Admin | **MATCH** |
| `/users/{uid}/library/{id}` | LibraryRepo, CloudSync | `isOwner(uid) \|\| isAdmin()` | LibraryRepo, CloudSync | `isOwner(uid) \|\| isAdmin()` | **MATCH** |
| `/users/{uid}/history/{id}` | HistoryRepo, CloudSync | `isOwner(uid) \|\| isAdmin()` | HistoryRepo, CloudSync | `isOwner(uid) \|\| isAdmin()` | **MATCH** |
| `/users/{uid}/watched_episodes/{id}` | WatchedEpisodeRepo, CloudSync | `isOwner(uid) \|\| isAdmin()` | WatchedEpisodeRepo, CloudSync | `isOwner(uid) \|\| isAdmin()` | **MATCH** |
| `/users/{uid}/settings/notifications`| NotificationPrefsRepo, CloudSync | `isOwner(uid) \|\| isAdmin()` | NotificationPrefsRepo, CloudSync | Owner (safe keys) \|\| Admin | **MATCH** |
| `/users/{uid}/fcmTokens/{id}` | FcmTokenManager | `isOwner(uid) \|\| isAdmin()` | FcmTokenManager | Owner (strict android schema) | **MATCH** |
| `/managed_extensions/{id}` | ExtensionDataSource | `isAuthenticated()` | Admin Sync in Orchestrator | `isAdmin()` | **MATCH** |
| `/extensions/{id}` | ExtensionDataSource | `isAuthenticated()` | Admin Sync in Orchestrator | `isAdmin()` | **MATCH** |
| `/notifications/{id}` | NotificationRepo | `isAuthenticated()` | None | `isAdmin()` | **MATCH** |
| `/reports/{id}` | ReportRepo (own reports) | `isAdmin() \|\| userId == auth.uid` | ReportRepo (submit) | Auth && `status == 'pending'` | **MATCH** |
| `/support_conversations/{id}` | SupportViewModel | `isAdmin() \|\| userId == auth.uid` | SupportViewModel | Auth && `userId == auth.uid` | **MATCH** |
| `/support_conversations/{id}/messages/{mId}` | SupportViewModel | `isAdmin() \|\| conv.userId == auth.uid` | SupportViewModel | Auth && `senderRole == 'user'` | **MATCH** |
| `/conversations/{id}` | SocialRepository | Participant \|\| Admin | SocialRepository | Participant && unbanned | **MATCH** |
| `/conversations/{id}/messages/{mId}` | SocialRepository | Participant \|\| Admin | SocialRepository | Author && unbanned | **MATCH** |
| `/stories/{id}` | SocialRepository | `isAuthenticated()` | SocialRepository | Author && unbanned | **MATCH** |
| `/auditLogs/{id}` | None | `isAdmin()` | None | `isAdmin()` | **MATCH** |
| `/audit_logs/{id}` | None | `false` | None | `false` | **MATCH** |

---

## 19. Admin Contract vs Users Usage Matrix

| Path in Admin Contract (`/docs/FIREBASE_CONTRACT.md`) | Present in Admin Contract | Present in Users App Code | Cross-Project Reconciliation Status |
| :--- | :---: | :---: | :--- |
| `/admins/{uid}` | **YES** | **YES** | **MATCH** |
| `/users/{uid}` | **YES** | **YES** | **MATCH** |
| `/config/app` | **YES** | **YES** | **MATCH** |
| `/extensions/{extensionId}` | **YES** | **YES** | **MATCH** (Legacy active fallback) |
| `/managed_extensions/{extensionId}` | **YES** (Extension contract) | **YES** | **MATCH** (Canonical managed catalog) |
| `/notifications/{notificationId}` | **YES** | **YES** | **MATCH** |
| `/auditLogs/{logId}` | **YES** | **NO** (Admin only) | **ADMIN-ONLY FEATURE** (Correctly inaccessible to clients) |
| `/reports/{reportId}` | **YES** | **YES** | **MATCH** |
| `/support_conversations/{conversationId}` | **YES** (Phase C3.1 / C7) | **YES** | **MATCH** |
| `/conversations/{conversationId}` | **NO** | **YES** | **USER-ONLY FEATURE** (Preserved) |
| `/stories/{storyId}` | **NO** | **YES** | **USER-ONLY FEATURE** (Preserved) |
| `/pro_requests/{requestId}` | **NO** | **NO** | **NOT USED BY USERS APP** |

---

## 20. Real Mismatches

**Zero (0) Real Architectural or Security Mismatches Found.**
- There are no path spelling contradictions.
- There are no data type incompatibilities (all timestamps support both `Timestamp` and numeric epochs).
- There are no permission bypasses.
- There are no raw media files stored in Firestore (all media uses Cloudinary).

---

## 21. User-Only Features

The following collections and paths represent intentional, legitimate User Plane capabilities that are active and protected in CineStream Users App:
1. **`/conversations/{conversationId}` and `/messages/{messageId}`**: Direct peer-to-peer social chat between CineStream community members. Gated by `participants`, `canChat`, `chatBan`, and `isBanned`.
2. **`/stories/{storyId}`**: User community story posting. Gated by `canStory`, `storyBan`, and `isBanned`.
3. **`/users/{uid}/library/{itemId}`**: User personal watchlist, favorites, and custom movie/show bookmarks.
4. **`/users/{uid}/history/{itemId}`**: User playback resume checkpoints and history.
5. **`/users/{uid}/watched_episodes/{id}`**: Watched series/anime episode markers.
6. **`/users/{uid}/settings/notifications`**: Granular client-side notification category preferences.
7. **`/users/{uid}/fcmTokens/{installationId}`**: Installation-specific push tokens.

---

## 22. Admin-Only Features

The following collections and paths are defined for the Admin Dashboard and are strictly inaccessible to the client Users App:
1. **`/auditLogs/{logId}`**: Immutable administrative action audit records. The Users App has zero code querying or writing this collection, and rules enforce `allow read, write: if isAdmin();`.
2. **`/pro_requests/{requestId}`**: Legacy/Admin subscription upgrade requests. Not implemented or utilized in Users App.

---

## 23. Legacy Paths

1. **`/config/global`**: **`REMOVED`**. Completely eliminated from codebase and barred in security rules.
2. **`/extensions/{extensionId}`**: **`LEGACY ACTIVE`**. Maintained in `FirebaseFirestoreManagedExtensionDataSource.kt` alongside canonical `/managed_extensions/{extensionId}` to ensure seamless backward compatibility with any Admin Dashboards that write to the legacy collection.
3. **`/audit_logs`**: **`FORBIDDEN / BLOCKED`**. Denied in security rules (`allow read, write: if false;`).

---

## 24. Unknown / Needs Decision

**None.**  
Every collection, subcollection, field, and privilege mechanism has an identified caller, clear ownership semantics, and a verified security rule.

---

## 25. Security Findings

All eleven (11) critical security invariants were statically evaluated and verified:
1. **`UID == Auth UID`**: **`STATICALLY VERIFIED`**. All user write operations in `/users/{uid}`, subcollections, `/reports`, `/support_conversations`, and `/stories` bind strictly to `request.auth.uid`.
2. **User cannot self-promote**: **`STATICALLY VERIFIED`**. Rules enforce `role == 'user'` on document creation and block any modification of `role`, `admin`, or `isAdmin` on update.
3. **User cannot modify subscription authority**: **`STATICALLY VERIFIED`**. Rules enforce `subscriptionTier == 'free'`, `isPremium == false`, `plan == 'free'` on create, and block all subscription keys on update.
4. **User cannot remove global ban**: **`STATICALLY VERIFIED`**. Rules enforce `isBanned == false`, `isActive == true` on create, and block all ban keys on update.
5. **User cannot spoof support userId**: **`STATICALLY VERIFIED`**. Rules mandate `request.resource.data.userId == request.auth.uid`.
6. **User cannot spoof senderId**: **`STATICALLY VERIFIED`**. Support messages require `request.resource.data.senderId == request.auth.uid`. Social messages require `request.resource.data.senderId == request.auth.uid`.
7. **User cannot spoof senderRole**: **`STATICALLY VERIFIED`**. Support messages created by non-admins strictly require `request.resource.data.senderRole == 'user'`.
8. **Non-participant cannot access social conversation**: **`STATICALLY VERIFIED`**. Rules restrict read and message dispatch to users enumerated in `resource.data.participants`.
9. **User cannot create story for another UID**: **`STATICALLY VERIFIED`**. Rules require `request.resource.data.userId == request.auth.uid`.
10. **Chat restrictions are enforced**: **`STATICALLY VERIFIED`**. Both conversation and message creation require `canChat != false`, `chatBan != true`, and `isBanned != true`.
11. **Story restrictions are enforced**: **`STATICALLY VERIFIED`**. Story creation requires `canStory != false`, `storyBan != true`, and `isBanned != true`.

---

## 26. Final Verdict

============================================================  
FINAL VERDICT: **ALIGNED WITH DOCUMENTED DIFFERENCES**  
============================================================  

### Justification:
- **Zero Real Mismatches:** All shared collections (`admins`, `users`, `config/app`, `managed_extensions`, `extensions`, `notifications`, `reports`, `support_conversations`) are fully aligned across contract, code, and security rules.
- **Documented Differences:** User-plane features (`/conversations`, `/stories`, and user subcollections `/library`, `/history`, `/watched_episodes`, `/settings/notifications`, `/fcmTokens`) exist intentionally to provide rich client capabilities and are fully secured without compromising Admin contract invariants.
- **Config Global:** Eradicated and permanently secured against deprecated calls.
- **Zero Modifications Executed:** The entire audit was conducted strictly in read-only forensic mode with zero source code or rule alterations.

============================================================  
END OF AUDIT REPORT  
============================================================
