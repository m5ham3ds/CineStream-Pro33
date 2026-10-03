# CineStream Unified Canonical Contract (Cross-Platform Source of Truth)

This document establishes the official **CANONICAL SHARED CONTRACT** between:
1. **CineStream User App**
2. **CineStream Admin Dashboard**

Both projects must strictly adhere to the exact paths, document IDs, field names, data types, ownership rules, and media delivery architectures defined herein.

---

## 1. Architectural Overview & Separation of Concerns

```
                ┌──────────────────────┐
                │   Firebase Auth      │
                │  User/Admin Identity │
                └──────────┬───────────┘
                           │
                           ▼
                ┌──────────────────────┐
                │ Firebase Firestore   │
                │                      │
                │ Users                │
                │ Admins               │
                │ App Config            │
                │ Extensions           │
                │ Notifications        │
                │ Audit Logs           │
                │ Reports              │
                │ Media URLs/Metadata  │
                └──────────┬───────────┘
                           │
                           │ URL / Metadata
                           ▼
                ┌──────────────────────┐
                │      Cloudinary      │
                │                      │
                │ Images              │
                │ Videos              │
                │ Audio               │
                │ Posters             │
                │ Backdrops            │
                │ Profile Images       │
                │ Other Media          │
                └──────────────────────┘
```

### Storage Roles
1. **Firebase Authentication**: User & Admin authentication and identity provider.
2. **Firebase Firestore**: Database for application state, permissions, metadata, configurations, references, and secure URLs. Firestore **does NOT** store raw binary media.
3. **Cloudinary**: Official **MEDIA STORAGE + MEDIA DELIVERY** infrastructure for CineStream. All application media (images, posters, backdrops, profile photos, audio, video files, thumbnails) reside in Cloudinary.
4. **Firebase Storage**: **NOT USED** for CineStream media. No Firebase Storage SDK, StorageReference, or storage buckets are utilized.

---

## 2. Official Firestore Paths

Only the following canonical collections and paths are permitted:

- `/users/{uid}`
- `/admins/{uid}`
- `/config/app`
- `/extensions/{extensionId}`
- `/notifications/{notificationId}`
- `/auditLogs/{logId}`
- `/reports/{reportId}`

---

## 3. Data Models & Field Specifications

### 3.1 Admins: `/admins/{uid}`
- **Path**: `/admins/{uid}`
- **Document ID**: `FirebaseAuth.currentUser.uid`
- **Verification Principle**: Server-side Firestore Rules check:
  `request.auth.uid -> /admins/{request.auth.uid} -> enabled == true`

| Field Name | Type | Description |
| :--- | :--- | :--- |
| `uid` | `String` | Admin UID matching Firebase Auth UID |
| `email` | `String` | Admin email address |
| `role` | `String` | `"admin"` or `"superadmin"` |
| `enabled` | `Boolean` | Must be `true` for active administrative privileges |
| `createdAt` | `Timestamp` | Timestamp when admin account was provisioned |

---

### 3.2 Users: `/users/{uid}`
- **Path**: `/users/{uid}`
- **Document ID**: `FirebaseAuth.currentUser.uid`
- **Ownership**: User-managed non-privileged profile data; Admin-managed access, subscriptions, bans, and overrides.
- **Safety Mandate**: The User App **MUST NEVER** invoke destructive `set(doc)` without `SetOptions.merge()`.

| Field Name | Type | Owner | Description |
| :--- | :--- | :--- | :--- |
| `uid` | `String` | System | Firebase Auth UID |
| `email` | `String` | System | Email address |
| `displayName` | `String` | User | Full display name |
| `username` | `String` | User | Unique public username |
| `photoUrl` | `String` | User | Cloudinary secure delivery URL (e.g. `https://res.cloudinary.com/...`) |
| `createdAt` | `Timestamp` | System | Account creation timestamp |
| `updatedAt` | `Timestamp` | Dual | Profile / state last modified timestamp |
| `lastLoginAt` | `Timestamp` | User App | Last login timestamp |
| `isActive` | `Boolean` | Admin | Account active state |
| `isPremium` | `Boolean` | Admin | Fast check for premium access |
| `subscriptionTier` | `String` | Admin | `"free"`, `"vip"`, `"premium"` |
| `subscriptionExpiresAt` | `Timestamp / null` | Admin | Subscription expiration date |
| `role` | `String` | Admin | `"user"`, `"vip"`, `"admin"`, `"superadmin"` |
| `canWatch` | `Boolean` | Admin | Playback permission flag |
| `canDownload` | `Boolean` | Admin | Offline download permission flag |
| `canChat` | `Boolean` | Admin | Social messaging permission flag |
| `canStory` | `Boolean` | Admin | Story creation permission flag |
| `canP2P` | `Boolean` | Admin | Offline P2P sharing permission flag |
| `watchBan` | `Boolean` | Admin | Specific playback suspension |
| `downloadBan` | `Boolean` | Admin | Specific download suspension |
| `chatBan` | `Boolean` | Admin | Specific chat suspension |
| `storyBan` | `Boolean` | Admin | Specific story posting suspension |
| `p2pBan` | `Boolean` | Admin | Specific P2P sharing suspension |
| `deviceLimit` | `Number / null` | Admin | Maximum concurrent authorized devices |
| `offlineDaysOverride` | `Number / null` | Admin | Override for local DRM / offline retention |
| `forcedAdsOverride` | `Number / null` | Admin | Override for mandatory ad count |
| `appVersion` | `String` | User App | App version string reported on login |

---

### 3.3 App Configuration: `/config/app`
- **Path**: `/config/app`
- **Document ID**: `app`
- **Owner**: Admin Dashboard (Read-only for User App and guests)

| Field Name | Type | Description |
| :--- | :--- | :--- |
| `maintenanceEnabled` | `Boolean` | Activates global maintenance mode in User App |
| `maintenanceTitle` | `String` | Dialog header text during maintenance |
| `maintenanceMessage` | `String` | Explanatory message displayed to users |
| `minimumVersionCode` | `Number` | Hard cutoff app version code |
| `latestVersionCode` | `Number` | Latest published app version code |
| `latestVersionName` | `String` | Semver version string (e.g. `"1.2.0"`) |
| `apkUrl` | `String` | Direct download link for latest APK |
| `apkSha256` | `String` | SHA-256 integrity checksum for downloaded APK |
| `mandatoryUpdate` | `Boolean` | Flag enforcing compulsory update |
| `releaseNotes` | `String` | Changelog details for the update |
| `providersJson` | `String` | Dynamic providers JSON configuration string |
| `defaultOfflineDays` | `Number` | Default duration before offline content expires (default: 2) |
| `defaultForcedAds` | `Number` | Default required ads for free-tier users (default: 5) |
| `updatedAt` | `Timestamp` | Configuration modification timestamp |

---

### 3.4 Extensions: `/extensions/{extensionId}`
- **Path**: `/extensions/{extensionId}`
- **Document ID**: Extension identifier (`String`)
- **Owner**: Admin Dashboard (Read-only for User App)

| Field Name | Type | Description |
| :--- | :--- | :--- |
| `id` | `String` | Unique extension ID |
| `name` | `String` | Extension display name |
| `packageName` | `String` | Android plugin package identifier |
| `versionCode` | `Number` | Extension version code |
| `versionName` | `String` | Extension version string |
| `apkUrl` | `String` | Hosted APK URL (Cloudinary or CDN) |
| `apkSha256` | `String` | Integrity checksum of plugin APK |
| `minAppVersionCode` | `Number` | Minimum CineStream app version required |
| `enabled` | `Boolean` | **Enforcement**: When `false`, User App must immediately disable local extension |
| `mandatory` | `Boolean` | If required for app startup |
| `createdAt` | `Timestamp` | Registration timestamp |
| `updatedAt` | `Timestamp` | Modification timestamp |

---

### 3.5 Notifications: `/notifications/{notificationId}`
- **Path**: `/notifications/{notificationId}`
- **Owner**: Admin Dashboard (Read-only for User App)
- **FCM Status**: Firestore in-app snapshot listener notifications are supported. FCM Push Messaging is **NOT IMPLEMENTED** (no FCM token storage or server push mechanism).

| Field Name | Type | Description |
| :--- | :--- | :--- |
| `id` | `String` | Notification ID |
| `title` | `String` | Notification header |
| `body` | `String` | Notification message text |
| `type` | `String` | `"info"`, `"warning"`, `"update"`, `"promo"` |
| `target` | `String` | `"all"` or `"user"` |
| `targetUid` | `String` | Recipient UID when `target == "user"` |
| `createdBy` | `String` | Admin UID who authored notification |
| `createdAt` | `Timestamp` | Creation timestamp |
| `expiresAt` | `Timestamp / null` | Expiration timestamp |
| `isActive` | `Boolean` | When false, ignored by clients |
| `status` | `String` | `"active"` or `"inactive"` |

---

### 3.6 Audit Logs: `/auditLogs/{logId}`
- **Path**: `/auditLogs/{logId}` (Strictly forbidden: `/audit_logs`)
- **Owner**: Admin Dashboard only (Client User App has NO read or write access)

| Field Name | Type | Description |
| :--- | :--- | :--- |
| `id` | `String` | Log document ID |
| `actorUid` | `String` | Firebase Auth UID of the actor |
| `adminEmail` | `String` | Email address of the administrator |
| `action` | `String` | Action performed (e.g. `UPDATE_CONFIG`, `BAN_USER`, `RESOLVE_REPORT`) |
| `targetType` | `String` | Target collection (`"user"`, `"config"`, `"extension"`, etc.) |
| `targetId` | `String` | ID of target entity |
| `details` | `Map / String` | Structured change details or log payload |
| `createdAt` | `Timestamp` | Timestamp when action occurred |

---

### 3.7 Reports: `/reports/{reportId}`
- **Path**: `/reports/{reportId}`
- **Owner**: User App creates (`status == "pending"`); Admin Dashboard inspects and updates (`status`, `resolvedAt`, `resolvedBy`)

| Field Name | Type | Description |
| :--- | :--- | :--- |
| `reportId` | `String` | Report document ID |
| `userId` | `String` | Submitter's Auth UID (must match `auth.uid`) |
| `contentId` | `String` | Content identifier or URL |
| `contentType` | `String` | `"movie"`, `"series"`, `"anime"`, `"playback"`, `"app"` |
| `reason` | `String` | `"broken_stream"`, `"wrong_metadata"`, `"offensive"`, `"bug"`, `"other"` |
| `details` | `String` | User-provided text description |
| `status` | `String` | `"pending"`, `"in_review"`, `"resolved"`, `"rejected"` |
| `createdAt` | `Timestamp` | Timestamp of report submission |
| `resolvedAt` | `Timestamp / null` | Timestamp when resolved by Admin |
| `resolvedBy` | `String / null` | Admin UID who resolved the report |

---

## 4. Cloudinary Media Storage Contract

### 4.1 Storage & Delivery Model
- **Cloudinary** is the primary media storage and delivery network for all images, videos, audio, posters, backdrops, avatars, and attachments.
- **Firestore** stores only URLs (`url`), Public IDs (`publicId`), and optional lightweight metadata (`resourceType`, `format`, `duration`, `width`, `height`).

### 4.2 Security Rules
- **ZERO API SECRETS IN CLIENT**: The Cloudinary API Secret is strictly forbidden from being embedded in the Android APK, BuildConfig, XML, SharedPreferences, or Git repository.
- **Direct Client Uploads**: Performed securely using an **Unsigned Upload Preset** via `com.cloudinary.android.MediaManager.get().upload().unsigned(...)`.
- **Signed Operations**: Any sensitive administrative deletions or bulk transformations requiring signature must be handled through a secure backend or Admin Dashboard.

### 4.3 Media Schema in Firestore Documents
When storing Cloudinary media references in Firestore documents (e.g. in stories, chat, or user profiles):
```json
{
  "url": "https://res.cloudinary.com/<cloud_name>/image/upload/v1234567890/cinestream/avatar.jpg",
  "publicId": "cinestream/avatar",
  "resourceType": "image",
  "format": "jpg"
}
```
For simple single-URL requirements (such as `photoUrl` in `/users/{uid}`), the HTTPS Cloudinary delivery URL is directly stored.

---

## 5. Timestamp Rules
- All newly created or updated Firestore records must write `FieldValue.serverTimestamp()` (Firestore `Timestamp`).
- Read operations support both `com.google.firebase.Timestamp` and legacy `Number / Long` values gracefully to ensure zero downtime and 100% backwards compatibility during transition.
