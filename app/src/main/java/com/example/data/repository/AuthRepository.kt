package com.example.data.repository

import android.net.Uri
import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.SubscriptionNormalizer
import com.example.data.model.UserRestrictions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@IgnoreExtraProperties
data class User(
    val uid: String = "",
    val id: String = "",
    val email: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val displayName: String = "",
    val username: String = "",
    val photoUrl: String = "",
    val bio: String = "",
    val isProfilePublic: Boolean = true,
    
    // Subscriptions & Roles (Admin managed)
    val role: String = "user",
    val subscriptionTier: String = "FREE",
    val isPremium: Boolean = false, // Kept for backward compatibility
    val subscriptionStatus: String = "none",
    val subscriptionExpiresAt: Long? = null,
    val planId: String = "free",
    val durationDays: Int = 0,
    val subscriptionSource: String = "LEGACY",
    val subscriptionReferenceId: String? = null,
    val subscriptionStartedAt: Long? = null,

    // Independent Technical Account Restrictions
    val deviceLimit: Int? = null,
    val allowedQuality: String? = null,
    val downloadLimit: Int? = null,
    
    // Status & Bans (Admin managed)
    val isActive: Boolean = true,
    val isBanned: Boolean = false,
    val banReason: String = "",
    val banExpiresAt: Long? = null,
    
    // Feature Permissions (Admin managed)
    val canWatch: Boolean = true,
    val watchBan: Boolean = false,
    val canDownload: Boolean = true,
    val downloadBan: Boolean = false,
    val canChat: Boolean = true,
    val chatBan: Boolean = false,
    val canStory: Boolean = true,
    val storyBan: Boolean = false,
    val canP2P: Boolean = true,
    val p2pBan: Boolean = false,
    val canComment: Boolean = true,
    val canUpload: Boolean = true,
    val canRequest: Boolean = true,
    
    val offlineDaysOverride: Int? = null,
    val forcedAdsOverride: Int? = null,

    // Points balance summary (Read-only from /users/{uid})
    val pointsBalance: Long = 0L,
    val totalPointsEarned: Long = 0L,
    val totalPointsSpent: Long = 0L,
    
    // Timestamps
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastLoginAt: Long = 0L,
    val lastActiveAt: Long = 0L,
    val appVersion: String = ""
) {
    // Backwards-compatible aliases
    val plan: String get() = subscriptionTier
    val maxDevices: Int? get() = deviceLimit
    val lastLoginTimestamp: Long get() = lastLoginAt

    val canonicalTier: CanonicalSubscriptionTier
        get() = SubscriptionNormalizer.normalizeSubscriptionTier(
            rawTier = subscriptionTier,
            rawIsPremium = isPremium,
            rawPlan = plan,
            rawRole = role
        )

    val isSubscriptionExpired: Boolean
        get() = subscriptionExpiresAt != null && subscriptionExpiresAt <= System.currentTimeMillis()

    /**
     * Canonical entitlement rule: Subscription grants ad removal ONLY.
     */
    val isAdFree: Boolean
        get() = (canonicalTier != CanonicalSubscriptionTier.FREE) &&
                !isSubscriptionExpired &&
                !subscriptionStatus.equals("EXPIRED", ignoreCase = true) &&
                !subscriptionStatus.equals("CANCELED", ignoreCase = true)

    val isSubscriptionActive: Boolean
        get() = isAdFree

    val isBanActive: Boolean
        get() = (!isActive || isBanned) && (banExpiresAt == null || banExpiresAt > System.currentTimeMillis())

    val isWatchAllowed: Boolean get() = !isBanActive && canWatch && !watchBan
    val isDownloadAllowed: Boolean get() = !isBanActive && canDownload && !downloadBan
    val isChatAllowed: Boolean get() = !isBanActive && canChat && !chatBan
    val isStoryAllowed: Boolean get() = !isBanActive && canStory && !storyBan
    val isP2PAllowed: Boolean get() = !isBanActive && canP2P && !p2pBan
    val isCommentAllowed: Boolean get() = !isBanActive && canComment
    val isUploadAllowed: Boolean get() = !isBanActive && canUpload
    val isRequestAllowed: Boolean get() = !isBanActive && canRequest

    val isAdmin: Boolean get() = role.equals("admin", true) || role.equals("superadmin", true)

    /**
     * Quality checks are decoupled from subscription tier.
     */
    fun isQualityAllowed(quality: String): Boolean {
        val maxQuality = allowedQuality?.let { UserRestrictions.extractResolutionNumber(it) } ?: return true
        val currentQ = UserRestrictions.extractResolutionNumber(quality) ?: return true
        return currentQ <= maxQuality
    }

    fun getMaxAllowedResolution(): Int? {
        return allowedQuality?.let { UserRestrictions.extractResolutionNumber(it) }
    }

    fun isDownloadLimitReached(currentCompletedDownloads: Int): Boolean {
        val limit = downloadLimit ?: return false
        return currentCompletedDownloads >= limit
    }

    companion object {
        fun fromDocument(doc: DocumentSnapshot, existingUser: User? = null): User {
            val uid = doc.getString("uid") ?: doc.id
            val role = doc.getString("role") ?: existingUser?.role ?: "user"

            val subState = SubscriptionNormalizer.fromDocument(doc)

            val rawSubTier = doc.getString("subscriptionTier")
            val legacyPlan = doc.getString("plan") ?: doc.getString("proPlan")
            val effectiveTierString = if (!rawSubTier.isNullOrBlank()) {
                rawSubTier.trim().uppercase()
            } else if (!legacyPlan.isNullOrBlank()) {
                legacyPlan.trim().uppercase()
            } else {
                subState.tier.name
            }

            val subExp = when (val s = doc.get("subscriptionExpiresAt") ?: doc.get("proExpiresAt")) {
                is Timestamp -> s.toDate().time
                is Number -> s.toLong()
                else -> existingUser?.subscriptionExpiresAt
            }

            val subStarted = when (val st = doc.get("subscriptionStartedAt")) {
                is Timestamp -> st.toDate().time
                is Number -> st.toLong()
                else -> existingUser?.subscriptionStartedAt
            }

            val explicitPrem = doc.getBoolean("isPremium") ?: (subState.tier != CanonicalSubscriptionTier.FREE)

            val created = when (val c = doc.get("createdAt")) {
                is Timestamp -> c.toDate().time
                is Number -> c.toLong()
                else -> existingUser?.createdAt ?: 0L
            }

            val updated = when (val u = doc.get("updatedAt")) {
                is Timestamp -> u.toDate().time
                is Number -> u.toLong()
                else -> existingUser?.updatedAt ?: 0L
            }

            val lastLogin = when (val l = doc.get("lastLoginAt") ?: doc.get("lastLoginTimestamp")) {
                is Timestamp -> l.toDate().time
                is Number -> l.toLong()
                else -> existingUser?.lastLoginAt ?: 0L
            }

            val banExp = when (val b = doc.get("banExpiresAt")) {
                is Timestamp -> b.toDate().time
                is Number -> b.toLong()
                else -> existingUser?.banExpiresAt
            }

            val deviceLimit = doc.getLong("deviceLimit")?.toInt()
                ?: doc.getLong("maxDevices")?.toInt()
                ?: existingUser?.deviceLimit

            val pointsBal = doc.getLong("pointsBalance") ?: existingUser?.pointsBalance ?: 0L
            val totalEarned = doc.getLong("totalPointsEarned") ?: existingUser?.totalPointsEarned ?: 0L
            val totalSpent = doc.getLong("totalPointsSpent") ?: existingUser?.totalPointsSpent ?: 0L

            val fbAuthUser = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
            val authEmail = if (uid == fbAuthUser?.uid) (fbAuthUser.email ?: "") else ""
            val authDisplayName = if (uid == fbAuthUser?.uid) (fbAuthUser.displayName ?: "") else ""
            val authPhoto = if (uid == fbAuthUser?.uid) (fbAuthUser.photoUrl?.toString() ?: "") else ""

            val firstName = (if (doc.contains("firstName")) doc.getString("firstName") else null)
                ?: existingUser?.firstName
                ?: ""
            val lastName = (if (doc.contains("lastName")) doc.getString("lastName") else null)
                ?: existingUser?.lastName
                ?: ""
            val rawDisplayName = doc.getString("displayName")?.takeIf { it.isNotBlank() }
                ?: doc.getString("name")?.takeIf { it.isNotBlank() }
                ?: "${firstName} ${lastName}".trim().takeIf { it.isNotBlank() }
                ?: existingUser?.displayName?.takeIf { it.isNotBlank() }
                ?: authDisplayName.takeIf { it.isNotBlank() }
                ?: ""

            val rawUsername = (if (doc.contains("username")) doc.getString("username") else null)
                ?: existingUser?.username
                ?: ""

            val rawEmail = doc.getString("email")?.takeIf { it.isNotBlank() }
                ?: existingUser?.email?.takeIf { it.isNotBlank() }
                ?: authEmail

            val rawPhotoUrl = (if (doc.contains("photoUrl")) doc.getString("photoUrl") else if (doc.contains("avatarUrl")) doc.getString("avatarUrl") else null)?.takeIf { it.isNotBlank() }
                ?: existingUser?.photoUrl?.takeIf { it.isNotBlank() }
                ?: authPhoto

            val rawBio = (if (doc.contains("bio")) doc.getString("bio") else null)
                ?: existingUser?.bio
                ?: ""

            return User(
                uid = uid,
                id = uid,
                email = rawEmail,
                firstName = firstName,
                lastName = lastName,
                displayName = rawDisplayName,
                username = rawUsername,
                photoUrl = rawPhotoUrl,
                bio = rawBio,
                isProfilePublic = doc.getBoolean("isProfilePublic") ?: existingUser?.isProfilePublic ?: true,
                role = role,
                subscriptionTier = effectiveTierString,
                isPremium = explicitPrem,
                subscriptionStatus = subState.status.name,
                subscriptionExpiresAt = subExp,
                planId = subState.planId,
                durationDays = subState.durationDays,
                subscriptionSource = subState.source.name,
                subscriptionReferenceId = subState.referenceId,
                subscriptionStartedAt = subStarted,
                deviceLimit = deviceLimit,
                allowedQuality = doc.getString("allowedQuality") ?: existingUser?.allowedQuality,
                downloadLimit = doc.getLong("downloadLimit")?.toInt() ?: existingUser?.downloadLimit,
                isActive = doc.getBoolean("isActive") ?: existingUser?.isActive ?: true,
                isBanned = doc.getBoolean("isBanned") ?: existingUser?.isBanned ?: false,
                banReason = doc.getString("banReason") ?: existingUser?.banReason ?: "",
                banExpiresAt = banExp,
                canWatch = doc.getBoolean("canWatch") ?: existingUser?.canWatch ?: true,
                watchBan = doc.getBoolean("watchBan") ?: existingUser?.watchBan ?: false,
                canDownload = doc.getBoolean("canDownload") ?: existingUser?.canDownload ?: true,
                downloadBan = doc.getBoolean("downloadBan") ?: existingUser?.downloadBan ?: false,
                canChat = doc.getBoolean("canChat") ?: existingUser?.canChat ?: true,
                chatBan = doc.getBoolean("chatBan") ?: existingUser?.chatBan ?: false,
                canStory = doc.getBoolean("canStory") ?: existingUser?.canStory ?: true,
                storyBan = doc.getBoolean("storyBan") ?: existingUser?.storyBan ?: false,
                canP2P = doc.getBoolean("canP2P") ?: existingUser?.canP2P ?: true,
                p2pBan = doc.getBoolean("p2pBan") ?: existingUser?.p2pBan ?: false,
                canComment = doc.getBoolean("canComment") ?: existingUser?.canComment ?: true,
                canUpload = doc.getBoolean("canUpload") ?: existingUser?.canUpload ?: true,
                canRequest = doc.getBoolean("canRequest") ?: existingUser?.canRequest ?: true,
                offlineDaysOverride = doc.getLong("offlineDaysOverride")?.toInt() ?: existingUser?.offlineDaysOverride,
                forcedAdsOverride = doc.getLong("forcedAdsOverride")?.toInt() ?: existingUser?.forcedAdsOverride,
                pointsBalance = pointsBal,
                totalPointsEarned = totalEarned,
                totalPointsSpent = totalSpent,
                createdAt = created,
                updatedAt = updated,
                lastLoginAt = lastLogin,
                lastActiveAt = doc.getLong("lastActiveAt") ?: existingUser?.lastActiveAt ?: 0L,
                appVersion = doc.getString("appVersion") ?: existingUser?.appVersion ?: ""
            )
        }
    }
}

object AuthRepository {

    val currentUserFlow = MutableStateFlow<User?>(null)
    val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null) {
                UserSecurityManager.listenToUserSecurity(user.uid)
                EconomyConfigRepository.startListening()
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        getCurrentUser()
                    } catch (_: Exception) {}
                }
            } else {
                currentUserFlow.value = null
                UserSecurityManager.reset()
                EconomyConfigRepository.stopListening()
            }
        }
    }

    suspend fun uploadProfilePicture(uid: String, uri: Uri): String {
        val cloudName = com.example.BuildConfig.CLOUDINARY_CLOUD_NAME
        val uploadPreset = com.example.BuildConfig.CLOUDINARY_UPLOAD_PRESET
        
        if (cloudName.isEmpty() || uploadPreset.isEmpty()) {
            throw Exception("Cloudinary configuration is missing. Please set CLOUDINARY_CLOUD_NAME and CLOUDINARY_UPLOAD_PRESET.")
        }

        try {
            com.cloudinary.android.MediaManager.get()
        } catch (e: Exception) {
            throw Exception("Cloudinary MediaManager not initialized.")
        }
        
        return suspendCancellableCoroutine<String> { continuation ->
            com.cloudinary.android.MediaManager.get().upload(uri)
                .unsigned(uploadPreset)
                .callback(object : com.cloudinary.android.callback.UploadCallback {
                    override fun onSuccess(requestId: String?, resultData: Map<*, *>?) {
                        val secureUrl = resultData?.get("secure_url") as? String
                        if (secureUrl != null) {
                            continuation.resume(secureUrl)
                        } else {
                            continuation.resumeWithException(Exception("Secure URL not found in Cloudinary response"))
                        }
                    }
                    
                    override fun onStart(requestId: String?) {}
                    override fun onProgress(requestId: String?, bytes: Long, totalBytes: Long) {}
                    override fun onError(requestId: String?, error: com.cloudinary.android.callback.ErrorInfo?) {
                        continuation.resumeWithException(Exception(error?.description ?: "Cloudinary upload error"))
                    }
                    override fun onReschedule(requestId: String?, error: com.cloudinary.android.callback.ErrorInfo?) {
                        continuation.resumeWithException(Exception("Upload rescheduled"))
                    }
                }).dispatch()
        }
    }

    suspend fun getCurrentUser(): User? {
        val firebaseUser = auth.currentUser
        if (firebaseUser == null) {
            currentUserFlow.value = null
            UserSecurityManager.reset()
            return null
        }
        return try {
            val snapshot = kotlinx.coroutines.withTimeout(15000) { db.collection("users").document(firebaseUser.uid).get().await() }
            if (snapshot.exists()) {
                val user = User.fromDocument(snapshot)
                currentUserFlow.value = user
                UserSecurityManager.listenToUserSecurity(firebaseUser.uid)
                // Keep activity timestamp updated
                try {
                    val appVersion = AppStartupManager.getCurrentVersionName(com.example.MyApplication.appContext)
                    db.collection("users").document(firebaseUser.uid).update(
                        mapOf(
                            "lastActiveAt" to System.currentTimeMillis(),
                            "appVersion" to appVersion
                        )
                    )
                } catch (_: Exception) {}
                user
            } else {
                currentUserFlow.value = null
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Saves or updates user document in /users/{uid} using SetOptions.merge().
     * Strictly protects Admin-managed and economic fields from being overwritten.
     */
    suspend fun saveUser(user: User) {
        val uid = if (user.uid.isNotBlank()) user.uid else (auth.currentUser?.uid ?: return)
        val appVersion = AppStartupManager.getCurrentVersionName(com.example.MyApplication.appContext)
        val resolvedName = "${user.firstName} ${user.lastName}".trim().ifBlank { user.username }

        val docRef = db.collection("users").document(uid)
        val existingDoc = try { docRef.get().await() } catch (_: Exception) { null }

        val userMap = mutableMapOf<String, Any>(
            "uid" to uid,
            "id" to uid,
            "email" to user.email,
            "firstName" to user.firstName,
            "lastName" to user.lastName,
            "displayName" to (if (user.displayName.isNotBlank()) user.displayName else resolvedName),
            "username" to user.username,
            "photoUrl" to user.photoUrl,
            "isProfilePublic" to user.isProfilePublic,
            "lastLoginAt" to FieldValue.serverTimestamp(),
            "lastLoginTimestamp" to System.currentTimeMillis(),
            "lastActiveAt" to System.currentTimeMillis(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "appVersion" to appVersion
        )

        // Only for brand new user documents, initialize default non-privileged state
        if (existingDoc == null || !existingDoc.exists()) {
            userMap["createdAt"] = FieldValue.serverTimestamp()
            userMap["isActive"] = true
            userMap["role"] = "user"
            userMap["subscriptionTier"] = "FREE"
            userMap["planId"] = "free"
            userMap["plan"] = "free"
            userMap["durationDays"] = 0
            userMap["isPremium"] = false
            userMap["subscriptionStatus"] = "none"
            userMap["subscriptionSource"] = "LEGACY"
            userMap["pointsBalance"] = 0L
            userMap["totalPointsEarned"] = 0L
            userMap["totalPointsSpent"] = 0L
            userMap["isBanned"] = false
            userMap["banReason"] = ""
            userMap["canWatch"] = true
            userMap["canDownload"] = true
            userMap["canChat"] = true
            userMap["canStory"] = true
            userMap["canP2P"] = true
            userMap["canComment"] = true
            userMap["canUpload"] = true
            userMap["canRequest"] = true
        }

        docRef.set(userMap, SetOptions.merge()).await()

        val updatedUser = if (existingDoc != null && existingDoc.exists()) {
            User.fromDocument(existingDoc).copy(
                uid = uid,
                email = user.email,
                firstName = user.firstName,
                lastName = user.lastName,
                displayName = userMap["displayName"] as String,
                username = user.username,
                photoUrl = user.photoUrl,
                isProfilePublic = user.isProfilePublic,
                updatedAt = System.currentTimeMillis(),
                lastActiveAt = System.currentTimeMillis()
            )
        } else {
            user.copy(uid = uid, role = "user", subscriptionTier = "FREE", planId = "free", isPremium = false, isBanned = false, isActive = true)
        }

        currentUserFlow.value = updatedUser
        UserSecurityManager.listenToUserSecurity(uid)
    }

    suspend fun isUsernameTaken(username: String, currentUid: String): Boolean {
        val snapshot = db.collection("users")
            .whereEqualTo("username", username)
            .get()
            .await()
            
        for (doc in snapshot.documents) {
            if (doc.id != currentUid) return true
        }
        return false
    }

    suspend fun generateUniqueUsername(baseName: String): String {
        var base = baseName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
        if (base.isEmpty()) base = "user"
        
        var attempt = base
        var isTaken = isUsernameTaken(attempt, "")
        var count = 1
        
        while (isTaken) {
            attempt = "${base}${count}"
            isTaken = isUsernameTaken(attempt, "")
            count++
        }
        return attempt
    }

    fun signOut() {
        try {
            auth.signOut()
        } catch (_: Exception) {}
        currentUserFlow.value = null
        UserSecurityManager.reset()
        EconomyConfigRepository.stopListening()
    }
}
