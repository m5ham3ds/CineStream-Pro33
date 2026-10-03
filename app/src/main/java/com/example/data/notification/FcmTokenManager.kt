package com.example.data.notification

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.repository.AppStartupManager
import com.example.data.repository.dataStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Manages FCM registration tokens, persistent installation IDs,
 * local token caching via DataStore, and synchronization with Firestore
 * under `/users/{uid}/fcmTokens/{installationId}`.
 */
class FcmTokenManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "FcmTokenManager"
        private val KEY_INSTALLATION_ID = stringPreferencesKey("fcm_installation_id")
        private val KEY_CACHED_TOKEN = stringPreferencesKey("fcm_cached_token")

        @Volatile
        private var instance: FcmTokenManager? = null

        fun getInstance(context: Context): FcmTokenManager {
            return instance ?: synchronized(this) {
                instance ?: FcmTokenManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Obtains or generates a stable, persistent Installation ID for this device.
     * This ensures multi-device support: each physical device/app instance
     * has exactly one document under /users/{uid}/fcmTokens/{installationId}.
     */
    suspend fun getInstallationId(): String {
        return try {
            val existing = appContext.dataStore.data.map { it[KEY_INSTALLATION_ID] }.firstOrNull()
            if (!existing.isNullOrBlank()) {
                existing
            } else {
                val newId = UUID.randomUUID().toString()
                appContext.dataStore.edit { it[KEY_INSTALLATION_ID] = newId }
                newId
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read installationId from DataStore, generating fallback: ${e.message}")
            UUID.randomUUID().toString()
        }
    }

    /**
     * Reads the cached FCM token from DataStore.
     */
    suspend fun getCachedToken(): String? {
        return try {
            appContext.dataStore.data.map { it[KEY_CACHED_TOKEN] }.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached token: ${e.message}")
            null
        }
    }

    /**
     * Saves the token locally in DataStore.
     */
    suspend fun saveCachedToken(token: String) {
        try {
            appContext.dataStore.edit { it[KEY_CACHED_TOKEN] = token }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cached token: ${e.message}")
        }
    }

    /**
     * Synchronizes the FCM token with the device cache and, if a user is authenticated,
     * uploads or updates the document at `/users/{uid}/fcmTokens/{installationId}`.
     *
     * @param token Optional token if already known (e.g. from onNewToken).
     *              If null, it fetches the current token from FirebaseMessaging.
     */
    suspend fun syncToken(token: String? = null): Boolean {
        return try {
            val resolvedToken = if (!token.isNullOrBlank()) {
                token
            } else {
                try {
                    FirebaseMessaging.getInstance().token.await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to retrieve token from FirebaseMessaging: ${e.message}")
                    getCachedToken()
                }
            }

            if (resolvedToken.isNullOrBlank()) {
                Log.d(TAG, "No valid FCM token available to sync.")
                return false
            }

            saveCachedToken(resolvedToken)

            val currentUid = FirebaseAuth.getInstance().currentUser?.uid
            if (currentUid.isNullOrBlank()) {
                Log.d(TAG, "User is not logged in; token cached locally for future login.")
                return true
            }

            val installationId = getInstallationId()
            val appVersion = AppStartupManager.getCurrentVersionName(appContext)
            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

            val doc = FcmTokenDocument(
                token = resolvedToken,
                installationId = installationId,
                platform = "android",
                deviceModel = deviceModel,
                osVersion = osVersion,
                appVersion = appVersion
            )

            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("users")
                .document(currentUid)
                .collection("fcmTokens")
                .document(installationId)
                .set(doc.toFirestoreMap(), SetOptions.merge())
                .await()

            Log.d(TAG, "Successfully synced FCM token for user $currentUid on installation $installationId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing FCM token: ${e.message}")
            false
        }
    }

    /**
     * Asynchronously triggers a sync from a non-coroutine context.
     */
    fun syncTokenAsync(token: String? = null) {
        scope.launch {
            syncToken(token)
        }
    }

    /**
     * Called when a user successfully signs in. Associates the current device's
     * FCM token with the newly authenticated user.
     */
    suspend fun onUserSignedIn(uid: String) {
        if (uid.isBlank()) return
        syncToken()
    }

    /**
     * Called when a user signs out.
     * Dissociates the device's token document from the old user in Firestore,
     * but retains the token locally so the next user or guest can use it.
     */
    suspend fun onUserSignedOut(oldUid: String) {
        if (oldUid.isBlank()) return
        try {
            val installationId = getInstallationId()
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("users")
                .document(oldUid)
                .collection("fcmTokens")
                .document(installationId)
                .delete()
                .await()
            Log.d(TAG, "Successfully dissociated FCM token from user $oldUid for installation $installationId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dissociate FCM token on logout: ${e.message}")
        }
    }

    /**
     * Deletes the token both from FirebaseMessaging and locally, if a full reset is requested.
     */
    suspend fun deleteToken(): Boolean {
        return try {
            val currentUid = FirebaseAuth.getInstance().currentUser?.uid
            val installationId = getInstallationId()

            if (!currentUid.isNullOrBlank()) {
                try {
                    FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(currentUid)
                        .collection("fcmTokens")
                        .document(installationId)
                        .delete()
                        .await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete token document from Firestore: ${e.message}")
                }
            }

            try {
                FirebaseMessaging.getInstance().deleteToken().await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to deleteToken from FirebaseMessaging: ${e.message}")
            }

            appContext.dataStore.edit { it.remove(KEY_CACHED_TOKEN) }
            Log.d(TAG, "FCM token deleted successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting FCM token: ${e.message}")
            false
        }
    }

    /**
     * Marks a device token document as inactive in Firestore when unrecoverable
     * delivery errors occur (e.g. UNREGISTERED, INVALID_ARGUMENT, SENDER_ID_MISMATCH).
     */
    suspend fun markTokenInactive(uid: String, installationId: String, reason: String = "UNREGISTERED"): Boolean {
        if (uid.isBlank() || installationId.isBlank()) return false
        return try {
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("users")
                .document(uid)
                .collection("fcmTokens")
                .document(installationId)
                .update(
                    mapOf(
                        "isActive" to false,
                        "deactivationReason" to reason,
                        "deactivatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )
                )
                .await()
            Log.d(TAG, "Marked token as inactive for user $uid on installation $installationId (reason=$reason)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark token inactive: ${e.message}")
            false
        }
    }

    /**
     * Determines whether an FCM error code represents a recoverable network/transient issue
     * or a permanent invalidation that requires token deactivation.
     */
    fun isRecoverableFcmError(errorCode: String): Boolean {
        return when (errorCode.uppercase()) {
            "UNREGISTERED", "INVALID_ARGUMENT", "SENDER_ID_MISMATCH" -> false
            "QUOTA_EXCEEDED", "UNAVAILABLE", "INTERNAL" -> true
            else -> false
        }
    }
}
