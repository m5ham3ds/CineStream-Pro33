# PHASE C7.2: USERS APP ↔ ADMIN APP FIRESTORE CONTRACT RECONCILIATION AUDIT REPORT

============================================================
STATUS: AUDIT ONLY — NO IMPLEMENTATION PERFORMED
============================================================

## 1. Executive Summary

This forensic reconciliation audit was conducted to rigorously verify whether the **CineStream Users Android App**, **Firebase/Firestore Security Rules**, and the canonical **CineStream Admin Contract** operate against a unified, compatible, and secure Firestore Contract.

### Core Discoveries:
1. **Canonical Path Alignment**: Primary collections (`/users`, `/admins`, `/config/app`, `/managed_extensions`, `/notifications`, `/reports`, `/auditLogs`) are structurally aligned between Users App implementation and `firestore.rules`.
2. **Support Chat Reality**: The CineStream Users App does **NOT** use Firestore for Support. Support messaging is implemented entirely as a **Local Room Database** (`support_messages`) with an automated local bot (`SupportViewModel.kt`). There is **zero** Firestore integration for `/support_conversations`, and `firestore.rules` contains **zero** rules for `/support_conversations`.
3. **Social Chat Independence**: Social Chat is fully implemented in Users App (`SocialRepository.kt`) using `/conversations/{conversationId}` and subcollection `/conversations/{conversationId}/messages/{messageId}` with `participants: List<String>`. Firestore rules strictly enforce participant-based authorization and ban restrictions (`canChat != false`, `chatBan != true`, `isBanned != true`). However, `/conversations` is **completely undocumented** in `/docs/FIREBASE_CONTRACT.md`.
4. **Config Fallback Discrepancy**: While `AppStartupManager.kt` reads strictly from `/config/app`, `AppUpdateManager.kt` contains dead fallback code attempting to read `/config/global`. Firestore security rules explicitly forbid access to `/config/{document=**}` (`allow read, write: if false;`), causing silent `SecurityException` catches on fallback.
5. **Admin Authority**: Admin privileges are strictly derived from `/admins/{uid}.enabled == true` in both Firestore Rules (`isAdmin()`) and client runtime (`UserSecurityManager.isAdminDocFlow`). The legacy field `users/{uid}.role` is never used as an authentication or authorization authority.
6. **Non-Existent Collections**: `/pro_requests`, `/app_updates`, and `/extension_updates` do not exist in Users App code, in `firestore.rules`, or in `/docs/FIREBASE_CONTRACT.md`.
7. **Extension Catalog Migration**: Users App exclusively queries `/managed_extensions`, which matches `docs/EXTENSION_DEVELOPER_CONTRACT.md` and `firestore.rules`. The path `/extensions` in `docs/FIREBASE_CONTRACT.md` is a legacy catalog preserved in rules for backward compatibility.

---

## 2. Audit Scope

- **Scope Mandate**: AUDIT ONLY.
- **Rule of Engagement**: Absolute zero modifications to source code, models, repositories, ViewModels, Firebase rules, configurations, strings, Gradle build files, or UI.
- **Verification Type**: Code and Rules static forensic analysis and cross-contract reconciliation.

---

## 3. No-Modification Confirmation

In strict compliance with the **HARD RULE — AUDIT ONLY** directives of Phase C7.2:
- **No Kotlin files** were created, updated, or removed.
- **No Compose UI screens** were touched.
- **No ViewModels or Repositories** were modified.
- **No Firestore Security Rules** were edited.
- **No Gradle files** were touched.
- **No tests or builds** were executed during this turn.
- Only read-only search and inspection tools were employed to produce this audit report.

---

## 4. Source Artifacts Inspected

| Artifact Type | Path | Purpose |
| :--- | :--- | :--- |
| **Firestore Security Rules** | `/firestore.rules` | Canonical server-side authorization rules (240 lines) |
| **Shared Canonical Contract** | `/docs/FIREBASE_CONTRACT.md` | Formal Admin Dashboard ↔ User App specification (257 lines) |
| **Extension Developer Contract** | `/docs/EXTENSION_DEVELOPER_CONTRACT.md` | Managed Scraper runtime contract specification (678 lines) |
| **User Identity & Security** | `app/src/main/java/com/example/data/repository/AuthRepository.kt` | User model, authentication, profile merge persistence |
| **Security Enforcement** | `app/src/main/java/com/example/data/repository/UserSecurityManager.kt` | Live listeners to `/admins/{uid}` and `/users/{uid}` |
| **Restrictions Model** | `app/src/main/java/com/example/data/model/UserRestrictions.kt` | Account ban and feature permission representation |
| **Social Repository** | `app/src/main/java/com/example/data/repository/SocialRepository.kt` | Implementation of `/conversations`, `/messages`, `/stories` |
| **Social ViewModels** | `app/src/main/java/com/example/ui/screens/social/ChatViewModel.kt`, `SocialViewModel.kt` | Social chat & story presentation logic |
| **Support ViewModel** | `app/src/main/java/com/example/ui/screens/profile/SupportViewModel.kt` | Local Room support chat implementation |
| **Support Model** | `app/src/main/java/com/example/data/model/SupportMessage.kt` | Room entity `support_messages` |
| **App Configuration** | `app/src/main/java/com/example/data/repository/AppStartupManager.kt` | `/config/app` maintenance and version verification |
| **App Updates** | `app/src/main/java/com/example/data/repository/AppUpdateManager.kt` | OTA update checker via `/config/app` |
| **Config Model** | `app/src/main/java/com/example/data/model/AppConfig.kt` | App configuration schema & document parser |
| **Managed Extensions Remote**| `app/src/main/java/com/example/extension/managed/repository/FirebaseFirestoreManagedExtensionDataSource.kt` | Queries `/managed_extensions` |
| **Notifications** | `app/src/main/java/com/example/data/repository/NotificationRepository.kt` | Reads `/notifications` |
| **Notification Preferences** | `app/src/main/java/com/example/data/repository/NotificationPreferencesRepository.kt` | Reads/writes `/users/{uid}/settings/notifications` |
| **Reports** | `app/src/main/java/com/example/data/repository/ReportRepository.kt`, `Report.kt` | Creates and reads `/reports` |
| **Subcollection Sync** | `app/src/main/java/com/example/data/sync/CloudSyncManager.kt` | Syncs `library`, `history`, `watched_episodes` |
| **Device FCM** | `app/src/main/java/com/example/data/notification/FcmTokenManager.kt` | Manages `/users/{uid}/fcmTokens/{installationId}` |
| **UI Ban Presentation** | `app/src/main/java/com/example/MainActivity.kt`, `BannedScreen.kt` | Full-screen account ban interceptor |

---

## 5. Collection Discovery Matrix

| Collection / Path | Users App | Admin Contract | Rules | Status | Notes |
| :--- | :---: | :---: | :---: | :---: | :--- |
| `/users/{uid}` | YES (R/W) | YES (R/W) | YES | **ALIGNED** | Document ID = Auth UID; strict field segregation |
| `/users/{uid}/library` | YES (R/W) | N/A | YES | **ALIGNED** | User media library items |
| `/users/{uid}/history` | YES (R/W) | N/A | YES | **ALIGNED** | User playback progress |
| `/users/{uid}/watched_episodes` | YES (R/W) | N/A | YES | **ALIGNED** | Episode completion records |
| `/users/{uid}/settings/notifications` | YES (R/W) | N/A | YES | **ALIGNED** | User notification toggles |
| `/users/{uid}/fcmTokens` | YES (R/W) | N/A | YES | **ALIGNED** | Device FCM push tokens |
| `/admins/{uid}` | YES (R) | YES (R/W) | YES | **ALIGNED** | Sole admin authority (`enabled == true`) |
| `/config/app` | YES (R) | YES (W) | YES | **ALIGNED** | Read-only for all; write for admins |
| `/config/global` | YES (Fallback) | NO | BLOCKED | **MISMATCH** | Rules block `/config/{document=**}`; fallback fails silently |
| `/managed_extensions/{id}`| YES (R) | In Ext Contract | YES | **ALIGNED** | Queried by Users App runtime; admin write in rules |
| `/extensions/{id}` | NO | YES (Legacy) | YES | **RULE_ONLY** | Preserved for legacy scraper catalog compatibility |
| `/notifications/{id}` | YES (R) | YES (W) | YES | **ALIGNED** | Read-only for users; write for admins |
| `/reports/{id}` | YES (C/R) | YES (R/W) | YES | **ALIGNED** | Users create `pending`; Admins inspect & resolve |
| `/conversations/{id}` | YES (R/W) | NO | YES | **MISMATCH** | Fully implemented in code & rules; **missing from `FIREBASE_CONTRACT.md`** |
| `/conversations/{id}/messages` | YES (R/W) | NO | YES | **MISMATCH** | Fully implemented in code & rules; **missing from `FIREBASE_CONTRACT.md`** |
| `/stories/{id}` | YES (C/R) | NO | YES | **MISMATCH** | Fully implemented in code & rules; **missing from `FIREBASE_CONTRACT.md`** |
| `/support_conversations` | NO | NO | NO | **ORPHANED** | **Users App uses local Room DB only!** No Firestore integration exists |
| `/pro_requests` | NO | NO | NO | **ORPHANED** | Does not exist in Users App, rules, or documentation |
| `/app_updates` | NO | NO | NO | **ORPHANED** | Updates delivered via `/config/app`; collection does not exist |
| `/extension_updates` | NO | NO | NO | **ORPHANED** | Collection does not exist anywhere |
| `/auditLogs/{id}` | NO | YES (W/R) | YES | **ALIGNED** | Strictly Admin-only; Users App has zero access |
| `/audit_logs` | NO | FORBIDDEN | BLOCKED | **ALIGNED** | Explicitly blocked in rules (`allow read, write: if false;`) |

---

## 6. Canonical Contract Matrix

| Canonical Path | Producer | Consumer | Membership / Identity | Authorization Rules |
| :--- | :--- | :--- | :--- | :--- |
| `/users/{uid}` | Dual (User profile / Admin controls) | Users App & Admin App | `doc.id == request.auth.uid` | Owner non-sensitive update; Admin all-field update |
| `/admins/{uid}` | Firebase Console / Superadmin | Users App & Admin App | `doc.id == request.auth.uid` | Admin read/write; User read-own only |
| `/config/app` | Admin App | Users App & Guests | Singleton doc: `app` | Public read (`if true`); Admin write |
| `/managed_extensions/{id}` | Admin App | Users App | Extension ID | Authenticated read; Admin write |
| `/notifications/{id}` | Admin App | Users App | Notification ID | Authenticated read; Admin write |
| `/reports/{id}` | Users App (`pending`) | Admin App (`resolved`) | `reportId`, submitter `userId` | Submitter create/read-own; Admin read/update/delete |
| `/auditLogs/{id}` | Admin App / Backend | Admin App | Log ID | Admin read/write only; Users denied |
| `/conversations/{id}` | Users App | Users App & Admin App | `participants: List<String>` | Participant read/create/update/delete; Admin read/delete |
| `/stories/{id}` | Users App | Users App | `userId: String` | Authenticated read; Owner create/update/delete; Admin delete |

---

## 7. Users Contract (`/users/{uid}`)

### 7.1 UID Identity Invariant
- **Proven in Code**:
  - `AuthRepository.kt`: Line 268 (`uid = if (user.uid.isNotBlank()) user.uid else auth.currentUser?.uid`), Line 272 (`db.collection("users").document(uid)`), Line 276-277 (`"uid" to uid, "id" to uid`).
  - `User.fromDocument`: Line 95 (`val uid = doc.getString("uid") ?: doc.id`).
  - **Verdict**: `Document ID == User.uid == User.id == FirebaseAuth.currentUser.uid`. Strictly verified.

### 7.2 Field Inventory & Provenance

| Field Name | Type | Classification | Owner / Authority | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `uid` | `String` | **Canonical** | System / Auth | Matches Auth UID |
| `id` | `String` | **Legacy Compatibility** | System | Alias for `uid` |
| `email` | `String` | **Canonical** | System / User | Registered email address |
| `displayName` | `String` | **Canonical** | User | Profile display name |
| `firstName` | `String` | **Legacy / UI** | User | Derived component of `displayName` |
| `lastName` | `String` | **Legacy / UI** | User | Derived component of `displayName` |
| `username` | `String` | **Canonical** | User | Unique public handle |
| `photoUrl` | `String` | **Canonical** | User | Cloudinary HTTPS secure delivery URL |
| `bio` | `String` | **UI-Only** | User | Profile biography text |
| `isProfilePublic` | `Boolean` | **UI-Only** | User | Visibility toggle in search |
| `createdAt` | `Timestamp / Long` | **Canonical** | System | Account creation timestamp (`FieldValue.serverTimestamp()`) |
| `updatedAt` | `Timestamp / Long` | **Canonical** | Dual | Profile modification timestamp |
| `lastLoginAt` | `Timestamp` | **Canonical** | User App | `FieldValue.serverTimestamp()` on login |
| `lastLoginTimestamp` | `Long` | **Legacy Compatibility** | User App | `System.currentTimeMillis()` mirror |
| `lastActiveAt` | `Long` | **Telemetry** | User App | Timestamp tracking recent app usage |
| `appVersion` | `String` | **Telemetry** | User App | App version string reported on login |
| `isActive` | `Boolean` | **Canonical** | Admin | Account active status (default: `true`) |
| `isPremium` | `Boolean` | **Canonical** | Admin | Fast-check flag for VIP/Premium entitlement |
| `subscriptionTier` | `String` | **Canonical** | Admin | `"free"`, `"vip"`, `"premium"` |
| `plan` | `String` | **Legacy Compatibility** | Admin | Alias for `subscriptionTier` |
| `subscriptionStatus` | `String` | **Canonical** | Admin | `"none"`, `"active"`, `"expired"`, `"cancelled"` |
| `subscriptionExpiresAt`| `Timestamp / Long` | **Canonical** | Admin | Expiration epoch timestamp |
| `role` | `String` | **Metadata** | Admin | `"user"`, `"vip"`, `"admin"`, `"superadmin"` |
| `deviceLimit` | `Int / null` | **Canonical** | Admin | Concurrent device authorization threshold |
| `maxDevices` | `Int / null` | **Legacy Compatibility** | Admin | Alias for `deviceLimit` |
| `allowedQuality` | `String / null` | **Canonical** | Admin | Playback quality cap (e.g. `"1080p"`, `"4K"`) |
| `downloadLimit` | `Int / null` | **Canonical** | Admin | Maximum simultaneous downloads |
| `isBanned` | `Boolean` | **Canonical** | Admin | Global account suspension flag |
| `banReason` | `String` | **Canonical** | Admin | Explanation for suspension |
| `banExpiresAt` | `Timestamp / Long` | **Canonical** | Admin | Suspension end epoch timestamp |
| `canWatch` | `Boolean` | **Canonical** | Admin | Feature capability: Video playback |
| `watchBan` | `Boolean` | **Canonical** | Admin | Feature restriction: Playback ban |
| `canDownload` | `Boolean` | **Canonical** | Admin | Feature capability: Media downloads |
| `downloadBan` | `Boolean` | **Canonical** | Admin | Feature restriction: Download ban |
| `canChat` | `Boolean` | **Canonical** | Admin | Feature capability: Social messaging |
| `chatBan` | `Boolean` | **Canonical** | Admin | Feature restriction: Chat ban |
| `canStory` | `Boolean` | **Canonical** | Admin | Feature capability: Story publishing |
| `storyBan` | `Boolean` | **Canonical** | Admin | Feature restriction: Story ban |
| `canP2P` | `Boolean` | **Canonical** | Admin | Feature capability: Local P2P sharing |
| `p2pBan` | `Boolean` | **Canonical** | Admin | Feature restriction: P2P ban |
| `canComment` | `Boolean` | **Canonical** | Admin | Feature capability: User comments |
| `canUpload` | `Boolean` | **Canonical** | Admin | Feature capability: Content upload |
| `canRequest` | `Boolean` | **Canonical** | Admin | Feature capability: Content request |
| `offlineDaysOverride` | `Int / null` | **Canonical** | Admin | Custom offline retention days |
| `forcedAdsOverride` | `Int / null` | **Canonical** | Admin | Custom required ads count |

---

## 8. Admin Authority Audit

### 8.1 Authority Source of Truth
- **Server Rules Enforcement**:
  `firestore.rules` line 14-18 defines `isAdmin()`:
  ```javascript
  function isAdmin() {
    return isAuthenticated() && 
      exists(/databases/$(database)/documents/admins/$(request.auth.uid)) &&
      get(/databases/$(database)/documents/admins/$(request.auth.uid)).data.enabled == true;
  }
  ```
  Admin authority is **never** granted by `users/{uid}.role`.
- **Client Enforcement**:
  `UserSecurityManager.kt` lines 37-48 maintains a live snapshot listener on `/admins/{uid}`:
  ```kotlin
  val isDocAdmin = adminSnap != null && adminSnap.exists() && (adminSnap.getBoolean("enabled") == true)
  _isAdminDocFlow.value = isDocAdmin
  ```
  `UserSecurityManager.isAdmin()` returns `_isAdminDocFlow.value`.

### 8.2 Comprehensive `users.role` Classification

| File Location | Code Snippet | Classification | Provenance / Analysis |
| :--- | :--- | :--- | :--- |
| `firestore.rules:45` | `(!('role' in request.resource.data) || request.resource.data.role == 'user')` | **METADATA** | Enforces default role on account creation |
| `firestore.rules:73` | `!affectedKeys().hasAny(['role', ...])` | **METADATA** | Prevents client from escalating role |
| `firestore.rules:103, 126, 145` | `!keys().hasAny(['role', ...])` | **METADATA** | Blocks role injection into subcollections |
| `UserSecurityManager.kt:61` | `val rawRole = snapshot.getString("role") ?: "user"` | **METADATA** | Ingested for user profile display |
| `UserSecurityManager.kt:92` | `role = rawRole` | **METADATA** | Stored in `UserRestrictions` data class |
| `UserSecurityManager.kt:127` | `role = updated.role` | **METADATA** | Propagated to `currentUserFlow` |
| `UserRestrictions.kt:9` | `val role: String = "user"` | **METADATA** | Non-privileged model property |
| `UserRestrictions.kt:64` | `val isAdmin: Boolean get() = role.equals("admin", true)...` | **LEGACY / UNUSED** | Dead helper property; not referenced in codebase |
| `AuthRepository.kt:34, 148` | `val role: String = "user"` | **METADATA** | Model property |
| `AuthRepository.kt:91` | `val isAdmin: Boolean get() = role.equals("admin", true)...` | **LEGACY / UNUSED** | Dead model property |
| `AuthRepository.kt:102` | `?: (role.equals("vip", true))` | **DISPLAY / LEGACY** | Fallback for VIP badge display |
| `AuthRepository.kt:296` | `userMap["role"] = "user"` | **METADATA** | Initial default non-privileged value |
| `AppStartupManager.kt:193-198`| `if (currentRole.equals("admin"...)) return false` | **FILTER** | Explicitly prevents spoofed `role="admin"` from bypassing maintenance |
| `FcmPayloadContract.kt:81` | `"role"` in stripped keys | **FILTER** | Strips `role` from incoming push notifications |

**Verdict**: `users.role` is strictly **METADATA / DISPLAY / LEGACY** and has zero administrative authority.

---

## 9. Social Chat Contract

### 9.1 Architecture & Schema
Social Chat is a **User ↔ User** communication system.
- Canonical Conversation Path: `/conversations/{conversationId}`
- Canonical Messages Path: `/conversations/{conversationId}/messages/{messageId}`

#### Conversation Document Schema (`Conversation.kt`):
- `id`: `String` (Deterministic format: `listOf(uidA, uidB).sorted().joinToString("_")`)
- `participants`: `List<String>` (Sorted list of user UIDs)
- `participantNames`: `Map<String, String>` (UID to display name mapping)
- `lastMessage`: `String`
- `lastMessageTime`: `Long` (Epoch ms)
- `unreadCounts`: `Map<String, Int>` (UID to unread message count)
- `isGroup`: `Boolean` (Default: `false`)
- `isRequest`: `Boolean` (Default: `false`)

#### Message Document Schema (`PrivateMessage.kt`):
- `id`: `String` (Document ID)
- `senderId`: `String` (Auth UID of author)
- `text`: `String`
- `timestamp`: `Long` (Epoch ms)
- `isDeleted`: `Boolean` (Default: `false`)
- `isEdited`: `Boolean` (Default: `false`)
- `isVoice`: `Boolean` (Default: `false`)
- `mediaUrl`: `String?` (Cloudinary media URL)
- `deletedFor`: `List<String>` (List of user UIDs who deleted message locally)
- `reactions`: `Map<String, String>` (UID to emoji reaction mapping)

---

## 10. Social Chat Participants & Security

### 10.1 Participant Verification
- **Data Type**: `List<String>` containing Auth UIDs.
- **Query Mechanism**: `db.collection("conversations").whereArrayContains("participants", user.uid)` (`SocialRepository.kt:128`).
- **Authorization Enforcement (`firestore.rules`)**:
  - **Conversation Read**: `isAdmin() || (resource != null && request.auth.uid in resource.data.participants)`
  - **Conversation Create**: `(request.auth.uid in request.resource.data.participants) && canChat != false && chatBan != true && isBanned != true`
  - **Conversation Update**: `isAdmin() || (resource != null && request.auth.uid in resource.data.participants)`
  - **Message Read**: `isAdmin() || request.auth.uid in get(/databases/$(database)/documents/conversations/$(conversationId)).data.participants`
  - **Message Create**: `request.resource.data.senderId == request.auth.uid && (request.auth.uid in get(...).data.participants) && canChat != false && chatBan != true && isBanned != true`
  - **Message Update/Delete**: `isAdmin() || (resource != null && resource.data.senderId == request.auth.uid)`

### 10.2 Social Chat Security Evaluation Matrix
| Attack / Access Case | Scenario | Expected Behavior | Firestore Rules Result | Status |
| :--- | :--- | :--- | :--- | :--- |
| **Case A** | User A reads Conversation (A + B) | Allow | ALLOW (UID in `resource.data.participants`) | **ALIGNED** |
| **Case B** | User C reads Conversation (A + B) | Deny | DENIED (UID not in participants) | **SECURED** |
| **Case C** | User A sends message in (A + B) | Allow | ALLOW (`senderId == auth.uid` && in participants) | **ALIGNED** |
| **Case D** | User C sends message in (A + B) | Deny | DENIED (C not in parent conversation participants) | **SECURED** |
| **Case E** | User A attempts spoofed `senderId = B`| Deny | DENIED (`request.resource.data.senderId != request.auth.uid`)| **SECURED** |
| **Case F** | User C modifies conversation `participants`| Deny | DENIED (C not in resource participants) | **SECURED** |
| **Case G** | User B modifies User A's message | Deny | DENIED (`resource.data.senderId != request.auth.uid`) | **SECURED** |
| **Case H** | Admin reads/manages Social Chat | Allow | ALLOW (`isAdmin() == true`) | **ALIGNED** |

---

## 11. Social Chat + Ban System Reconciliation

### 11.1 Segregation of Ban Mechanisms
The system maintains strict architectural separation between **Global Account Bans** and **Feature Restrictions**:

1. **Global Account Ban**:
   - Fields: `isBanned: Boolean`, `banReason: String`, `banExpiresAt: Long? / Timestamp?`
   - UI Impact: When `isBanActive` is true, `MainActivity.kt` intercepts the entire navigation graph and displays `BannedScreen`, preventing access to all screens.
   - Database Enforcement: Both conversation and message creation require `get(.../users/$(request.auth.uid)).data.isBanned != true`.
2. **Chat Feature Restrictions**:
   - Positive Capability Flag: `canChat: Boolean` (Default: `true`)
   - Negative Restriction Flag: `chatBan: Boolean` (Default: `false`)
   - Independence: An administrator can set `chatBan: true` or `canChat: false` while `isBanned` remains `false`.
   - Result: The user can still log in, watch media, download, and browse, but `ChatViewModel.sendMessage()` returns immediately without executing network calls, and `firestore.rules` rejects any create operations at the database level.
   - **No Unintended Merge**: Neither the data models (`UserRestrictions.kt`), nor the managers (`UserSecurityManager.kt`), nor `firestore.rules` conflate `isBanned` with `canChat` or `chatBan`.

---

## 12. Support Contract Audit

### 12.1 Canonical Path vs Actual Implementation
- **Canonical Expectation in Phase Prompt**:
  `/support_conversations/{conversationId}`
  `/support_conversations/{conversationId}/messages/{messageId}`
  Ownership: `userId == request.auth.uid`
  Admin: `/admins/{uid}.enabled == true`

- **ACTUAL IMPLEMENTATION IN USERS APP**:
  - **Firestore Path**: **NONE**. The Users App contains **zero** queries, references, listeners, or writes to `/support_conversations`.
  - **Local Room Storage**: `app/src/main/java/com/example/data/model/SupportMessage.kt` is a Room Entity:
    ```kotlin
    @Entity(tableName = "support_messages")
    data class SupportMessage(
        @PrimaryKey val id: String = UUID.randomUUID().toString(),
        val text: String,
        val isFromUser: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )
    ```
  - **ViewModel Implementation**: `app/src/main/java/com/example/ui/screens/profile/SupportViewModel.kt`:
    Interacts with `AppDatabase.getDatabase(application).supportDao()`.
    When the user sends a message, it is inserted into the local SQLite database, followed by a simulated 1-second delay, after which a local keyword-matching algorithm (`generateResponse()`) generates an automated canned bot reply.
  - **Firestore Rules**: `firestore.rules` contains **zero** rules for `/support_conversations`.

**Verdict**: The Users App Help & Support feature is currently an offline-only automated bot. No remote Firestore backend exists.

---

## 13. Social vs Support Separation Test

| Property | Social Chat | Support Chat |
| :--- | :--- | :--- |
| **Participants** | User ↔ User | User ↔ Automated Local Bot (Simulated) |
| **Backend Storage** | Firebase Firestore | Local SQLite / Room Database (`support_messages`) |
| **Canonical Path** | `/conversations/{conversationId}/messages` | `N/A` (Local Table `support_messages`) |
| **Membership Contract**| `participants: List<String>` | `isFromUser: Boolean` |
| **Admin Authority** | Admin can inspect/moderate via `/admins` | `N/A` (No remote admin connection) |
| **Chat Permissions** | Governed by `canChat`, `chatBan`, `isBanned` | Ungated local Room access |
| **Remote Media** | Uploads images/audio to Cloudinary | Text-only local strings |
| **Rules Enforcement** | Rigorously enforced in `firestore.rules` | No Firestore rules exist |

---

## 14. Managed Extensions Contract

### 14.1 Remote Data Source Implementation
- Queried Path: `/managed_extensions/{extensionId}` (`FirebaseFirestoreManagedExtensionDataSource.kt:16`)
- `firestore.rules:153-156`:
  ```javascript
  match /managed_extensions/{extensionId} {
    allow read: if isAuthenticated();
    allow write: if isAdmin();
  }
  ```
- Legacy Path: `/extensions/{extensionId}` (`firestore.rules:159-162`):
  Preserved in rules for backward compatibility, but not actively queried by `DefaultManagedExtensionRepository.kt`.

### 14.2 Schema Alignment (`ManagedExtensionDto.kt` ↔ Remote Contract)

| Field Name | DTO Type | Contract Type | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `id` | `String` | `String` | **ALIGNED** | Extension unique ID |
| `name` | `String` | `String` | **ALIGNED** | Display name |
| `packageName` | `String` | `String` | **ALIGNED** | Android package namespace |
| `versionCode` | `Int` | `Number` | **ALIGNED** | Extension version code |
| `versionName` | `String` | `String` | **ALIGNED** | Semantic version string |
| `baseUrl` | `String` | `String` | **ALIGNED** | Target website base URL |
| `scraperKey` | `String` | `String` | **ALIGNED** | Unique scraper identifier (e.g. `"egydead"`) |
| `enabled` | `Boolean` | `Boolean` | **ALIGNED** | Remote enable/disable switch |
| `mandatory` | `Boolean` | `Boolean` | **ALIGNED** | Required startup flag |
| `priority` | `Int` | `Number` | **ALIGNED** | Candidate prioritization order |
| `runtimeApiVersion` | `Int` | `Number` | **ALIGNED** | API compatibility version |
| `supportedCapabilities`| `List<String>`| `Set<String>` | **ALIGNED** | Mapped to `ScraperCapability` enum |
| `supportedContentTypes`| `List<String>`| `Set<String>` | **ALIGNED** | Mapped to `ContentType` enum |
| `status` | `String` | `String` | **ALIGNED** | `"ACTIVE"`, `"MAINTENANCE"`, `"DISABLED"` |

---

## 15. Pro Requests Audit

- **Audit Findings**:
  - The path `/pro_requests/{requestId}` was searched across all Kotlin source files, layout/string resources, and `firestore.rules`.
  - **Result**: Zero occurrences in Users App code.
  - **Result**: Zero occurrences in `firestore.rules`.
  - **Result**: Zero occurrences in `/docs/FIREBASE_CONTRACT.md`.
  - **Verdict**: `/pro_requests` is completely unreferenced and unimplemented in the CineStream codebase.

---

## 16. Audit Logs Audit

- **Canonical Path**: `/auditLogs/{logId}`
- **Security Rule Enforcement (`firestore.rules:230-237`)**:
  ```javascript
  match /auditLogs/{logId} {
    allow read, write: if isAdmin();
  }

  match /audit_logs/{document=**} {
    allow read, write: if false;
  }
  ```
- **Users App Isolation**:
  Users App contains zero references to `auditLogs` or `audit_logs`. Normal users cannot read or write audit logs under any circumstances.

---

## 17. Config Provenance Audit

### 17.1 Configuration Documents
1. **`/config/app`**:
   - Producer: CineStream Admin Dashboard
   - Consumer: Users App (`AppStartupManager.kt`, `AppUpdateManager.kt`)
   - Security Rules: `allow read: if true; allow write: if isAdmin();`
   - Executable Content Check: `providersJson` is a JSON string describing provider metadata; no DEX, APK, bytecode, class names, or reflective loading hooks exist.
2. **`/config/global`**:
   - Status: **DEPRECATED / PROHIBITED**
   - Rules: `firestore.rules:33-35` (`match /config/{document=**} { allow read, write: if false; }`)
   - Users App Code: `AppUpdateManager.kt:80` attempts fallback read:
     ```kotlin
     val globalDoc = firestore.collection("config").document("global").get(Source.SERVER).await()
     ```
     Because of the catch block, it fails silently, but this represents dead code and an architectural inconsistency.

---

## 18. Stories Contract

- **Path**: `/stories/{storyId}`
- **Schema (`Story.kt`)**:
  - `id`: `String` (Document ID)
  - `userId`: `String` (Author UID)
  - `imageUrl`: `String` (Cloudinary HTTPS URL)
  - `timestamp`: `Long` (Epoch ms)
- **Rules Enforcement (`firestore.rules:219-227`)**:
  - Authenticated read for all users.
  - Create requires `request.resource.data.userId == request.auth.uid` AND `canStory != false` AND `storyBan != true` AND `isBanned != true`.
  - Update/delete restricted to story owner or admin.
- **Contract Status**: Fully functioning in Users App and rules, but **omitted from `FIREBASE_CONTRACT.md`**.

---

## 19. Reports Contract

- **Path**: `/reports/{reportId}`
- **Users App Implementation**: `ReportRepository.kt` writes document with `status = "pending"`, `resolvedAt = null`, `resolvedBy = null`.
- **Rules Enforcement (`firestore.rules:171-179`)**:
  - `allow create: if isAuthenticated() && request.resource.data.userId == request.auth.uid && request.resource.data.status == 'pending' && resolvedBy == null && resolvedAt == null;`
  - Submitter can read own reports; Admin can read all reports and resolve/update them.
- **Status**: **ALIGNED**.

---

## 20. Notifications Contract

- **Path**: `/notifications/{notificationId}`
- **Users App Implementation**: `NotificationRepository.kt` reads up to 50 active notifications via `get()` and listens to updates via snapshot listener. Client-side preferences filter categories (`announcements`, `app_updates`, `maintenance`, `new_movies`, etc.) before alerting the user.
- **Rules Enforcement**: Authenticated read; Admin write.
- **Status**: **ALIGNED**.

---

## 21. App Updates Contract

- **Architecture**: App updates are served directly via `/config/app` (`latestVersionCode`, `latestVersionName`, `apkUrl`, `apkSha256`, `mandatoryUpdate`, `releaseNotes`).
- **Collection `/app_updates`**: Does not exist in Users App, rules, or contracts.
- **Status**: **ALIGNED** with `/config/app`.

---

## 22. Legacy Contract Audit

| Legacy Field / Path | Current Usage | Authoritative? | Migration Risk |
| :--- | :--- | :--- | :--- |
| `/extensions` | Rules compatibility | NO (Users queries `/managed_extensions`) | Low; dual rules permit both |
| `users.role` | Display / Fallback | NO (Authority is `/admins/{uid}`) | Low; client ignores role for admin |
| `users.plan` | Model compatibility alias | NO (`subscriptionTier` is canonical) | Low; both written in sync |
| `users.maxDevices` | Model compatibility alias | NO (`deviceLimit` is canonical) | Low; both supported in parser |
| `users.lastLoginTimestamp` | Model compatibility alias | NO (`lastLoginAt` is canonical) | Low; both written on login |
| `users.watchBan` | Negative restriction | YES (Evaluated alongside `canWatch`) | None; intentional defense-in-depth |
| `users.chatBan` | Negative restriction | YES (Evaluated alongside `canChat`) | None; intentional defense-in-depth |
| `users.storyBan` | Negative restriction | YES (Evaluated alongside `canStory`) | None; intentional defense-in-depth |
| `users.downloadBan` | Negative restriction | YES (Evaluated alongside `canDownload`)| None; intentional defense-in-depth |
| `users.p2pBan` | Negative restriction | YES (Evaluated alongside `canP2P`) | None; intentional defense-in-depth |

---

## 23. Dual-Write Audit

1. **`lastLoginAt` vs `lastLoginTimestamp`**:
   `AuthRepository.saveUser()` writes both:
   `"lastLoginAt" to FieldValue.serverTimestamp()` (Firestore Timestamp)
   `"lastLoginTimestamp" to System.currentTimeMillis()` (Long)
   *Reading*: `User.fromDocument` reads `lastLoginAt` first, falling back to `lastLoginTimestamp`.
2. **`subscriptionTier` vs `plan`**:
   `AuthRepository.saveUser()` initializes `"subscriptionTier" to "free", "plan" to "free"`.
   *Reading*: `doc.getString("subscriptionTier") ?: doc.getString("plan") ?: "free"`.
3. **`isPremium` vs Subscription Tier**:
   `UserSecurityManager.kt` checks `isPremium` boolean first; if absent, computes whether tier is `"premium"` or `"vip"`.

---

## 24. Firestore Rules Reconciliation Matrix

| Path | Users Read | Users Write | Admin Read | Admin Write | Rules Definition | Result |
| :--- | :---: | :---: | :---: | :---: | :--- | :--- |
| `/admins/{adminId}` | Read Own | DENIED | YES | YES | `isAdmin() \|\| isOwner(adminId)` | **ALIGNED** |
| `/config/app` | YES | DENIED | YES | YES | `allow read: if true; allow write: if isAdmin();` | **ALIGNED** |
| `/config/{doc=**}` | DENIED | DENIED | DENIED | DENIED | `allow read, write: if false;` | **ALIGNED** |
| `/users/{userId}` | YES | Owner (Restricted) | YES | YES | `allow read: if isAuthenticated(); allow create/update...` | **ALIGNED** |
| `/users/{uid}/library` | Owner | Owner | YES | YES | `isOwner(userId) \|\| isAdmin()` | **ALIGNED** |
| `/users/{uid}/history` | Owner | Owner | YES | YES | `isOwner(userId) \|\| isAdmin()` | **ALIGNED** |
| `/users/{uid}/watched_episodes`| Owner | Owner | YES | YES | `isOwner(userId) \|\| isAdmin()` | **ALIGNED** |
| `/users/{uid}/settings`| Owner | Owner | YES | YES | `isOwner(userId) \|\| isAdmin()` | **ALIGNED** |
| `/users/{uid}/fcmTokens`| Owner | Owner | YES | YES | `isOwner(userId) \|\| isAdmin()` | **ALIGNED** |
| `/managed_extensions/{id}`| YES | DENIED | YES | YES | `read: if isAuthenticated(); write: if isAdmin();` | **ALIGNED** |
| `/extensions/{id}` | YES | DENIED | YES | YES | `read: if isAuthenticated(); write: if isAdmin();` | **ALIGNED** |
| `/notifications/{id}` | YES | DENIED | YES | YES | `read: if isAuthenticated(); write: if isAdmin();` | **ALIGNED** |
| `/reports/{id}` | Read Own | Create `pending` | YES | YES | `allow create: if isOwner; update: if isAdmin();` | **ALIGNED** |
| `/conversations/{id}` | Participants | Participants | YES | YES | `read/create/update: if in participants;` | **ALIGNED** |
| `/conversations/{id}/messages`| Participants| Sender in part.| YES | YES | `read/create: if in parent participants;` | **ALIGNED** |
| `/stories/{id}` | YES | Owner | YES | YES | `read: if auth; create: if owner; delete: if owner\|admin;`| **ALIGNED** |
| `/auditLogs/{id}` | DENIED | DENIED | YES | YES | `allow read, write: if isAdmin();` | **ALIGNED** |
| `/audit_logs/{doc=**}`| DENIED | DENIED | DENIED | DENIED | `allow read, write: if false;` | **ALIGNED** |
| `/support_conversations`| N/A | N/A | N/A | N/A | **NO RULES DEFINED (DEFAULT DENIED)** | **MISSING_RULE** |

---

## 25. Cross-App Field Compatibility Matrix

| Domain Model | Field Name | Users App Type | Contract / Admin Type | Nullable | Notes |
| :--- | :--- | :--- | :--- | :---: | :--- |
| **User** | `uid` | `String` | `String` | No | Auth UID |
| **User** | `displayName` | `String` | `String` | No | Display name |
| **User** | `subscriptionTier`| `String` | `String` | No | `"free"`, `"vip"`, `"premium"` |
| **User** | `canChat` | `Boolean` | `Boolean` | No | Default `true` |
| **User** | `chatBan` | `Boolean` | `Boolean` | No | Default `false` |
| **User** | `isBanned` | `Boolean` | `Boolean` | No | Default `false` |
| **Conversation** | `participants`| `List<String>` | Undocumented | No | UIDs of members |
| **PrivateMessage**| `senderId` | `String` | Undocumented | No | Author UID |
| **PrivateMessage**| `mediaUrl` | `String?` | Undocumented | Yes | Cloudinary media link |
| **Report** | `reportId` | `String` | `String` | No | Auto-generated ID |
| **Report** | `status` | `String` | `String` | No | `"pending"`, `"resolved"` |
| **AppConfig** | `maintenanceEnabled`| `Boolean`| `Boolean` | No | Global maintenance toggle |
| **AppConfig** | `minimumVersionCode`| `Long` | `Number` | No | Enforced minimum version |
| **ManagedExtension**| `scraperKey`| `String` | `String` | No | Scraper identifier |

---

## 26. Enum Reconciliation

| Enum Domain | Users App Values | Contract / Remote Values | Verdict |
| :--- | :--- | :--- | :--- |
| **ContentType** | `MOVIE`, `SERIES`, `ANIME` | `movie`, `series`, `anime` | **ALIGNED** (Normalized via `ContentType.normalize()`) |
| **ReportStatus**| `"pending"`, `"resolved"`, `"rejected"` | `"pending"`, `"in_review"`, `"resolved"`, `"rejected"` | **ALIGNED** |
| **ReportReason**| `"broken_stream"`, `"wrong_metadata"`, `"offensive"`, `"bug"`, `"other"` | `"broken_stream"`, `"wrong_metadata"`, `"offensive"`, `"bug"`, `"other"` | **ALIGNED** |
| **ServerType** | `DIRECT`, `EMBED`, `HLS`, `DASH`, `UNKNOWN` | Direct vs Embed links | **ALIGNED** |
| **ExtensionStatus**| `ACTIVE`, `MAINTENANCE`, `DISABLED` | `ACTIVE`, `MAINTENANCE`, `DISABLED` | **ALIGNED** |
| **NotificationTarget**| `"all"`, `"user"` | `"all"`, `"user"` | **ALIGNED** |

---

## 27. Timestamp Reconciliation

| Model / Path | Firestore Stored Type | Users App Parser | Robustness |
| :--- | :--- | :--- | :--- |
| `/users/{uid}.createdAt` | Firestore `Timestamp` | Handles `Timestamp` & `Number` | **ROBUST** |
| `/users/{uid}.lastLoginAt` | Firestore `Timestamp` | Handles `Timestamp` & `Number` | **ROBUST** |
| `/config/app.updatedAt` | Firestore `Timestamp` | Handles `Timestamp` & `Number` | **ROBUST** |
| `/notifications/{id}.createdAt`| Firestore `Timestamp` | Handles `Timestamp` & `Number` | **ROBUST** |
| `/conversations/{id}/messages.timestamp`| `Number` (Epoch ms) | Reads as `Long` | **ALIGNED** |
| `/stories/{id}.timestamp` | `Number` (Epoch ms) | Reads as `Long` | **ALIGNED** |

---

## 28. Security Scan Results

1. **Dynamic Code Loading (DCL)**:
   - Search for `DexClassLoader`, `PathClassLoader`, `InMemoryDexClassLoader`: **ZERO** instances in production code (`app/src/main`). Only found in defensive unit tests verifying their prohibition.
   - Search for `Class.forName`: **ZERO** instances in production code.
2. **WebView SSL Error Bypasses**:
   - Search for `handler.proceed()` or `handler?.proceed()`: **ZERO** occurrences in `app/src/main`.
3. **Wildcard Firestore Permissions**:
   - `allow read: if true`: Present only for `/config/app`.
   - `allow write: if true`: **ZERO** occurrences in `firestore.rules`.
4. **Admin Escalation via Profile**:
   - `firestore.rules` lines 61-67 and 72-81 explicitly forbid writing or updating `role`, `isAdmin`, `isPremium`, `subscriptionTier`, `isBanned`, `canChat`, etc., preventing privilege escalation.

---

## 29. Findings Catalog

### FINDING C7.2-01: Support Chat Backend Missing in Users App
- **Severity**: **HIGH**
- **Component**: Help & Support
- **Path**: `/support_conversations`
- **Users Evidence**: `SupportViewModel.kt` uses Room DB table `support_messages` with local canned response generator.
- **Admin Evidence**: Phase prompt assumes canonical path `/support_conversations/{conversationId}`.
- **Rules Evidence**: No rule exists in `firestore.rules`.
- **Impact**: User support requests never leave the device; administrators cannot see or reply to user support inquiries.
- **Confidence**: 100%
- **Recommended Next Phase**: Design remote Firestore support schema and rules in Phase C7.3.

### FINDING C7.2-02: Social Chat and Stories Missing from Shared Contract
- **Severity**: **MEDIUM**
- **Component**: Documentation / Shared Contract
- **Path**: `/conversations` & `/stories`
- **Users Evidence**: `SocialRepository.kt` fully implements `/conversations`, `/messages`, and `/stories`.
- **Admin Evidence**: `/docs/FIREBASE_CONTRACT.md` omits `/conversations` and `/stories` from canonical paths.
- **Rules Evidence**: `firestore.rules` lines 181-227 contain rules for both.
- **Impact**: Admin Dashboard developers have no specification for building social chat moderation tools.
- **Confidence**: 100%
- **Recommended Next Phase**: Update `/docs/FIREBASE_CONTRACT.md` in Phase C7.3 to document Social Chat and Stories.

### FINDING C7.2-03: Dead Fallback to `/config/global` in `AppUpdateManager`
- **Severity**: **LOW**
- **Component**: OTA Updates
- **Path**: `/config/global`
- **Users Evidence**: `AppUpdateManager.kt:80` attempts to fetch `/config/global` on `/config/app` failure.
- **Admin Evidence**: `/docs/FIREBASE_CONTRACT.md` only specifies `/config/app`.
- **Rules Evidence**: `firestore.rules:33-35` strictly blocks `/config/{document=**}` (`allow read, write: if false;`).
- **Impact**: Fallback request always triggers a Firestore permission denied error, caught silently.
- **Confidence**: 100%
- **Recommended Next Phase**: Remove `/config/global` fallback from `AppUpdateManager.kt` in Phase C7.3.

### FINDING C7.2-04: Non-Existent Collections in Contract Prompt
- **Severity**: **LOW**
- **Component**: System Scope
- **Path**: `/pro_requests`, `/app_updates`, `/extension_updates`
- **Users Evidence**: Not implemented.
- **Admin Evidence**: Not in `/docs/FIREBASE_CONTRACT.md`.
- **Rules Evidence**: Not in `firestore.rules`.
- **Impact**: Conceptual divergence in audit prompt vs actual architecture.
- **Confidence**: 100%
- **Recommended Next Phase**: Acknowledge these collections as out of scope.

---

## 30. Severity Summary

| Severity Level | Count | Finding IDs |
| :--- | :---: | :--- |
| **CRITICAL** | 0 | None |
| **HIGH** | 1 | FINDING C7.2-01 (Support Chat Backend Missing) |
| **MEDIUM** | 1 | FINDING C7.2-02 (Social Chat / Stories Undocumented in Shared Contract) |
| **LOW** | 2 | FINDING C7.2-03 (`/config/global` dead code), FINDING C7.2-04 (Non-existent collections) |
| **INFO** | 0 | None |

---

## 31. Regression Evidence

- The BOLA/IDOR protection on `/conversations/{conversationId}/messages` remains intact in `firestore.rules`.
- The `isBanned` account suspension flow in `MainActivity.kt` and `BannedScreen.kt` remains intact and fully functional.
- The separation between `isBanned` and `canChat`/`chatBan` is preserved.

---

## 32. Test Status

- **Tests executed during Phase C7.2**: **0**
- *Historical Context*: Previous automated tests (Phase 6.5, Phase 6.6) passed locally in earlier phases, but were not executed during this turn in strict adherence to the **AUDIT ONLY** mandate.

---

## 33. Build Status

- **BUILD VERIFICATION**: **NOT PERFORMED**
- No build command was initiated during Phase C7.2.

---

## 34. Live Verification Status

- **LIVE FIRESTORE VERIFICATION**: **NOT PERFORMED**
- All evaluations were conducted via static code, model, and security rules inspection.

---

## 35. Final Contract Verdict

============================================================
FINAL VERDICT: **CONTRACT MISMATCH / SECURITY GAP**
============================================================

### Justification:
While core services (Authentication, Profile Management, Admin Authority, App Configuration, Managed Extensions, Notifications, and Reports) are fully aligned and secured:
1. **Contract Mismatch**: Support messaging is entirely local in the Users App, meaning there is no remote Firestore contract matching the expected Admin Support specifications.
2. **Documentation Gap**: Social Chat (`/conversations`) and Stories (`/stories`) are implemented in code and rules, but omitted from `/docs/FIREBASE_CONTRACT.md`.
3. **Dead Code Gap**: `/config/global` fallback in `AppUpdateManager.kt` fails silently against security rules.

---

## 36. Required Next Phase (C7.3 Recommendations)

In Phase C7.3 (Implementation & Remediation), the following tasks should be addressed:
1. **Support Chat Remote Integration**: If remote Admin-User support is desired, implement `/support_conversations/{conversationId}` and `/messages` in `SupportViewModel` and define corresponding rules in `firestore.rules`.
2. **Documentation Synchronization**: Update `/docs/FIREBASE_CONTRACT.md` to formally document `/conversations` and `/stories`.
3. **Clean Up Fallbacks**: Remove dead fallback code referencing `/config/global` from `AppUpdateManager.kt`.

============================================================
STOP. END OF PHASE C7.2 REPORT.
============================================================
