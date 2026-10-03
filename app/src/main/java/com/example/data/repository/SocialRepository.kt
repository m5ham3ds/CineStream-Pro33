package com.example.data.repository

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.net.Uri

data class UserProfile(
    val uid: String = "",
    val username: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val photoUrl: String = "",
    val isOnline: Boolean = false,
    val isProfilePublic: Boolean = true,
    val bio: String = "",
    val isPremium: Boolean = false
) {
    @get:com.google.firebase.firestore.Exclude
    val displayName: String
        get() = "${firstName} ${lastName}".trim().takeIf { it.isNotBlank() } ?: username
}

data class PrivateMessage(
    val id: String = "",
    val senderId: String = "",
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    @get:com.google.firebase.firestore.PropertyName("isDeleted")
    val isDeleted: Boolean = false,
    @get:com.google.firebase.firestore.PropertyName("isEdited")
    val isEdited: Boolean = false,
    @get:com.google.firebase.firestore.PropertyName("isVoice")
    val isVoice: Boolean = false,
    val mediaUrl: String? = null,
    val deletedFor: List<String> = emptyList(),
    val reactions: Map<String, String> = emptyMap()
)

data class Conversation(
    val id: String = "",
    val participants: List<String> = emptyList(),
    val participantNames: Map<String, String> = emptyMap(),
    val lastMessage: String = "",
    val lastMessageTime: Long = 0L,
    val unreadCounts: Map<String, Int> = emptyMap(),
    val isGroup: Boolean = false,
    val isRequest: Boolean = false
)

data class Story(
    val id: String = "",
    val userId: String = "",
    val imageUrl: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

class SocialRepository {

    companion object {
        private val userProfileCache = mutableMapOf<String, UserProfile>()
    }

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    fun getCurrentUser(): UserProfile? {
        val user = auth.currentUser ?: return null
        return UserProfile(uid = user.uid)
    }

    suspend fun getUserProfile(uid: String, forceRefresh: Boolean = false): UserProfile? {
        if (!forceRefresh && userProfileCache.containsKey(uid)) return userProfileCache[uid]
        return try {
            val doc = db.collection("users").document(uid).get().await()
            val p = doc.toObject(UserProfile::class.java)
            if (p != null) userProfileCache[uid] = p
            p
        } catch (e: Exception) {
            null
        }
    }

    fun getUserProfileFlow(userId: String): Flow<UserProfile?> = callbackFlow {
        if (userProfileCache.containsKey(userId)) {
            trySend(userProfileCache[userId])
        }
        val listener = db.collection("users").document(userId).addSnapshotListener { snapshot, _ ->
            if (snapshot != null) {
                val profile = snapshot.toObject(UserProfile::class.java)
                if (profile != null) {
                    userProfileCache[userId] = profile
                    trySend(profile)
                }
            }
        }
        awaitClose { listener?.remove() }
    }

    fun getStories(): Flow<List<Story>> = callbackFlow {
        val listener = db.collection("stories")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val stories = snapshot.toObjects(Story::class.java)
                    trySend(stories)
                }
            }
        awaitClose { listener.remove() }
    }

    fun getConversations(): Flow<List<Conversation>> = callbackFlow {
        val user = auth.currentUser
        if (user == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val listener = db.collection("conversations")
            .whereArrayContains("participants", user.uid)
            .orderBy("lastMessageTime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val convs = snapshot.toObjects(Conversation::class.java)
                    trySend(convs)
                }
            }
        awaitClose { listener.remove() }
    }

    suspend fun getConversation(conversationId: String): Conversation? {
        return try {
            val doc = db.collection("conversations").document(conversationId).get().await()
            doc.toObject(Conversation::class.java)
        } catch (e: Exception) {
            null
        }
    }

        suspend fun uploadMedia(uri: Uri): String? {
        return suspendCancellableCoroutine { continuation ->
            try {
                com.cloudinary.android.MediaManager.get().upload(uri)
                    .unsigned(com.example.BuildConfig.CLOUDINARY_UPLOAD_PRESET)
                    .callback(object : com.cloudinary.android.callback.UploadCallback {
                        override fun onStart(requestId: String?) {}
                        override fun onProgress(requestId: String?, bytes: Long, totalBytes: Long) {}
                        override fun onSuccess(requestId: String?, resultData: MutableMap<Any?, Any?>?) {
                            val secureUrl = resultData?.get("secure_url") as? String
                            continuation.resume(secureUrl)
                        }
                        override fun onError(requestId: String?, error: com.cloudinary.android.callback.ErrorInfo?) {
                            android.util.Log.e("SocialRepository", "Upload Error: ${error?.description}")
                            continuation.resume(null)
                        }
                        override fun onReschedule(requestId: String?, error: com.cloudinary.android.callback.ErrorInfo?) {
                            continuation.resume(null)
                        }
                    }).dispatch()
            } catch (e: Exception) {
                android.util.Log.e("SocialRepository", "Exception during upload", e)
                continuation.resume(null)
            }
        }
    }

    suspend fun uploadMedia(filePath: String): String? {
        return suspendCancellableCoroutine { continuation ->
            try {
                com.cloudinary.android.MediaManager.get().upload(filePath)
                    .unsigned(com.example.BuildConfig.CLOUDINARY_UPLOAD_PRESET)
                    .callback(object : com.cloudinary.android.callback.UploadCallback {
                        override fun onStart(requestId: String?) {}
                        override fun onProgress(requestId: String?, bytes: Long, totalBytes: Long) {}
                        override fun onSuccess(requestId: String?, resultData: MutableMap<Any?, Any?>?) {
                            val secureUrl = resultData?.get("secure_url") as? String
                            var format = resultData?.get("format") as? String
                            var finalUrl = secureUrl
                            // Cloudinary sometimes returns .m4a as video, it's fine, the URL works.
                            continuation.resume(finalUrl)
                        }
                        override fun onError(requestId: String?, error: com.cloudinary.android.callback.ErrorInfo?) {
                            android.util.Log.e("SocialRepository", "Upload Error: ${error?.description}")
                            continuation.resume(null)
                        }
                        override fun onReschedule(requestId: String?, error: com.cloudinary.android.callback.ErrorInfo?) {
                            continuation.resume(null)
                        }
                    }).dispatch()
            } catch (e: Exception) {
                android.util.Log.e("SocialRepository", "Exception during upload", e)
                continuation.resume(null)
            }
        }
    }

    fun getMessages(conversationId: String): Flow<List<PrivateMessage>> = callbackFlow {
        val listener = db.collection("conversations").document(conversationId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val msgs = snapshot.toObjects(PrivateMessage::class.java)
                    trySend(msgs)
                }
            }
        awaitClose { listener.remove() }
    }

    fun markConversationAsRead(conversationId: String) {
        val user = auth.currentUser ?: return
        val docRef = db.collection("conversations").document(conversationId)
        db.runTransaction { transaction ->
            val snapshot = transaction.get(docRef)
            if (snapshot.exists()) {
                val currentCountsAny = snapshot.get("unreadCounts")
                val currentCounts = if (currentCountsAny is Map<*, *>) {
                    currentCountsAny.entries.associate { it.key.toString() to (it.value.toString().toIntOrNull() ?: 0) }
                } else emptyMap()
                
                val newCounts = currentCounts.toMutableMap()
                newCounts[user.uid] = 0
                transaction.update(docRef, "unreadCounts", newCounts)
            }
        }
    }

    suspend fun createOrGetConversation(otherUserId: String, otherUserName: String): String {
        val user = getCurrentUser() ?: return ""
        val dbUserDoc = db.collection("users").document(user.uid).get().await()
        val realUser = dbUserDoc.toObject(UserProfile::class.java) ?: user
        val participants = listOf(user.uid, otherUserId).sorted()
        val convId = participants.joinToString("_")
        
        val docRef = db.collection("conversations").document(convId)
        val doc = docRef.get().await()
        
        if (!doc.exists()) {
            val conv = Conversation(
                id = convId,
                participants = participants,
                participantNames = mapOf(user.uid to realUser.displayName, otherUserId to otherUserName)
            )
            docRef.set(conv).await()
        }
        return convId
    }

    fun sendMessage(conversationId: String, text: String) {
        val user = getCurrentUser() ?: return
        val docRef = db.collection("conversations").document(conversationId)
        
        val msgRef = docRef.collection("messages").document()
        val msg = PrivateMessage(msgRef.id, user.uid, text, System.currentTimeMillis())
        msgRef.set(msg)
        
        db.runTransaction { transaction ->
            val convSnapshot = transaction.get(docRef)
            if (convSnapshot.exists()) {
                val currentCountsAny = convSnapshot.get("unreadCounts")
                val currentCounts = if (currentCountsAny is Map<*, *>) {
                    currentCountsAny.entries.associate { it.key.toString() to (it.value.toString().toIntOrNull() ?: 0) }
                } else emptyMap()
                
                val newCounts = currentCounts.toMutableMap()
                
                val participantsAny = convSnapshot.get("participants")
                val participants = if (participantsAny is List<*>) {
                    participantsAny.map { it.toString() }
                } else emptyList()
                
                participants.forEach { p ->
                    if (p != user.uid) {
                        newCounts[p] = (newCounts[p] ?: 0) + 1
                    }
                }
                
                transaction.update(docRef, "lastMessage", text, "lastMessageTime", System.currentTimeMillis(), "unreadCounts", newCounts)
            }
        }
    }

    fun searchUsers(query: String): Flow<List<UserProfile>> = callbackFlow {
        if (query.isBlank()) {
            trySend(emptyList())
            return@callbackFlow
        }
        val listener = db.collection("users")
            .whereGreaterThanOrEqualTo("username", query)
            .whereLessThanOrEqualTo("username", query + "\uf8ff")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val result = snapshot.toObjects(UserProfile::class.java)
                    trySend(result.filter { it.uid != auth.currentUser?.uid })
                }
            }
        awaitClose { listener?.remove() }
    }

    suspend fun saveUserProfile() {
        val user = auth.currentUser ?: return
        val profileMap = mapOf<String, Any>(
            "uid" to user.uid,
            "id" to user.uid,
            "username" to (user.displayName ?: "User"),
            "photoUrl" to (user.photoUrl?.toString() ?: ""),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        db.collection("users").document(user.uid)
            .set(profileMap, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    suspend fun startConversation(otherUserId: String, otherUserName: String): String {
        return createOrGetConversation(otherUserId, otherUserName)
    }

    suspend fun addStory(imageUrl: String) {
        val user = auth.currentUser ?: return
        val story = Story(id = "", userId = user.uid, imageUrl = imageUrl, timestamp = System.currentTimeMillis())
        val ref = db.collection("stories").document()
        ref.set(story.copy(id = ref.id)).await()
    }


    fun editMessage(conversationId: String, msgId: String, newText: String) {
        val msgRef = db.collection("conversations").document(conversationId).collection("messages").document(msgId)
        msgRef.update("text", newText, "isEdited", true)
    }

    fun deleteMessage(conversationId: String, msgId: String, forEveryone: Boolean) {
        val user = getCurrentUser() ?: return
        val msgRef = db.collection("conversations").document(conversationId).collection("messages").document(msgId)
        if (forEveryone) {
            msgRef.update("isDeleted", true)
        } else {
            db.runTransaction { transaction ->
                val snapshot = transaction.get(msgRef)
                if (snapshot.exists()) {
                    val msg = snapshot.toObject(PrivateMessage::class.java)
                    if (msg != null) {
                        val newDeletedFor = msg.deletedFor.toMutableList()
                        if (!newDeletedFor.contains(user.uid)) {
                            newDeletedFor.add(user.uid)
                            transaction.update(msgRef, "deletedFor", newDeletedFor)
                        }
                    }
                }
            }
        }
    }

    fun reactToMessage(conversationId: String, msgId: String, emoji: String) {
        val user = getCurrentUser() ?: return
        val msgRef = db.collection("conversations").document(conversationId).collection("messages").document(msgId)
        db.runTransaction { transaction ->
            val snapshot = transaction.get(msgRef)
            if (snapshot.exists()) {
                val msg = snapshot.toObject(PrivateMessage::class.java)
                if (msg != null) {
                    val newReactions = msg.reactions.toMutableMap()
                    if (newReactions[user.uid] == emoji) {
                        newReactions.remove(user.uid)
                    } else {
                        newReactions[user.uid] = emoji
                    }
                    transaction.update(msgRef, "reactions", newReactions)
                }
            }
        }
    }

    fun sendMediaMessage(conversationId: String, text: String, mediaUrl: String) {
        val user = getCurrentUser() ?: return
        val docRef = db.collection("conversations").document(conversationId)
        val msgRef = docRef.collection("messages").document()
        val msg = PrivateMessage(msgRef.id, user.uid, text, System.currentTimeMillis(), mediaUrl = mediaUrl)
        msgRef.set(msg)
        
        db.runTransaction { transaction ->
            val convSnapshot = transaction.get(docRef)
            if (convSnapshot.exists()) {
                val displayMsg = if (text.isNotBlank()) text else "📷 Media"
                transaction.update(docRef, "lastMessage", displayMsg)
                transaction.update(docRef, "lastMessageTime", System.currentTimeMillis())
            }
        }
    }

    fun sendRealVoiceMessage(conversationId: String, voiceUrl: String) {
        val user = getCurrentUser() ?: return
        val docRef = db.collection("conversations").document(conversationId)
        val msgRef = docRef.collection("messages").document()
        val msg = PrivateMessage(msgRef.id, user.uid, "Voice Message", System.currentTimeMillis(), isVoice = true, mediaUrl = voiceUrl)
        msgRef.set(msg)
        
        db.runTransaction { transaction ->
            val convSnapshot = transaction.get(docRef)
            if (convSnapshot.exists()) {
                transaction.update(docRef, "lastMessage", "🎤 Voice Message")
                transaction.update(docRef, "lastMessageTime", System.currentTimeMillis())
            }
        }
    }

    fun sendVoiceMessage(conversationId: String, voicePath: String) {
        val user = getCurrentUser() ?: return
        val docRef = db.collection("conversations").document(conversationId)
        
        val msgRef = docRef.collection("messages").document()
        val msg = PrivateMessage(msgRef.id, user.uid, "Voice Message", System.currentTimeMillis(), isVoice = true, mediaUrl = voicePath)
        msgRef.set(msg)
        
        db.runTransaction { transaction ->
            val convSnapshot = transaction.get(docRef)
            if (convSnapshot.exists()) {
                val currentCountsAny = convSnapshot.get("unreadCounts")
                val currentCounts = if (currentCountsAny is Map<*, *>) {
                    currentCountsAny.entries.associate { it.key.toString() to (it.value.toString().toIntOrNull() ?: 0) }
                } else emptyMap()
                
                val newCounts = currentCounts.toMutableMap()
                val participantsAny = convSnapshot.get("participants")
                val participants = if (participantsAny is List<*>) {
                    participantsAny.map { it.toString() }
                } else emptyList()
                
                participants.forEach { p ->
                    if (p != user.uid) {
                        newCounts[p] = (newCounts[p] ?: 0) + 1
                    }
                }
                
                transaction.update(docRef, "lastMessage", "Voice Message", "lastMessageTime", System.currentTimeMillis(), "unreadCounts", newCounts)
            }
        }
    }
}