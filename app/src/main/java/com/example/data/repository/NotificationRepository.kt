package com.example.data.repository

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.model.NotificationItem
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class NotificationRepository(context: Context) {
    private val appContext = context.applicationContext
    private val notificationDao = AppDatabase.getDatabase(appContext).notificationDao()

    fun getAllNotifications(): Flow<List<NotificationItem>> = notificationDao.getAllNotifications()

    fun getUnreadCount(): Flow<Int> = notificationDao.getUnreadCount()

    suspend fun syncCloudNotifications(context: Context, currentUid: String = "") {
        try {
            val resolvedUid = if (currentUid.isNotBlank()) {
                currentUid
            } else {
                com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
            }

            val snapshot = FirebaseFirestore.getInstance()
                .collection("notifications")
                .limit(50)
                .get()
                .await()

            val now = System.currentTimeMillis()

            // Load latest preferences once before processing notifications
            val prefs = NotificationPreferencesRepository(context).getPreferences()
            val deduplicator = com.example.data.notification.NotificationDeduplicator.getInstance(context)

            for (doc in snapshot.documents) {
                if (!isNotificationValidAndTargeted(doc, resolvedUid, now)) {
                    continue
                }

                val id = doc.id
                val title = doc.getString("title") ?: continue
                val body = doc.getString("body") ?: doc.getString("message") ?: ""
                val timestamp = when (val c = doc.get("createdAt")) {
                    is Timestamp -> c.toDate().time
                    is Number -> c.toLong()
                    else -> doc.getLong("timestamp") ?: now
                }
                val imageUrl = doc.getString("imageUrl") ?: doc.getString("image_url")
                val type = doc.getString("type") ?: "info"
                val event = doc.getString("event") ?: doc.getString("eventType") ?: doc.getString("event_type")
                val isAnime = when (val v = doc.get("isAnime") ?: doc.get("is_anime")) {
                    is Boolean -> v
                    is String -> v.trim().lowercase() in listOf("true", "1")
                    is Number -> v.toInt() == 1
                    else -> type.equals("anime", ignoreCase = true)
                }

                // Preference Gate: evaluate before Room insertion, before deduplication mark, and before tray alert
                val category = com.example.data.notification.NotificationCategoryResolver.resolveCategory(
                    type = type,
                    event = event,
                    isAnime = isAnime
                )

                if (!com.example.data.notification.NotificationCategoryResolver.isNotificationAllowed(category, prefs)) {
                    android.util.Log.d("NotificationRepo", "Cloud notification '$id' ($category) completely suppressed by user preferences. Not saved to Room.")
                    deduplicator.checkAndMarkProcessed(id)
                    continue
                }

                if (deduplicator.checkAndMarkProcessed(id)) {
                    continue
                }

                val existing = notificationDao.getNotificationById(id)
                if (existing == null) {
                    val item = NotificationItem(
                        id = id,
                        title = title,
                        message = body,
                        timestamp = timestamp,
                        isRead = false,
                        imageUrl = imageUrl,
                        type = type
                    )
                    notificationDao.insertNotification(item)
                    com.example.utils.NotificationHelper.showGeneralNotification(context, id, title, body)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        private var listenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
        private var lastAlertTimestamp: Long = System.currentTimeMillis()

        private fun isNotificationValidAndTargeted(doc: DocumentSnapshot, currentUid: String, currentTime: Long): Boolean {
            // 1. Check isActive (default: true)
            val isActive = doc.getBoolean("isActive") ?: true
            if (!isActive) return false

            // 2. Check expiresAt
            val expiresAt = when (val exp = doc.get("expiresAt")) {
                is Timestamp -> exp.toDate().time
                is Number -> exp.toLong()
                else -> null
            }
            if (expiresAt != null && expiresAt < currentTime) {
                return false
            }

            // 3. Check targeting: "all" or "user"
            val target = (doc.getString("target") ?: doc.getString("targetType") ?: "all").lowercase()
            val targetUid = doc.getString("targetUid") ?: ""

            if (target == "user" || targetUid.isNotBlank()) {
                if (currentUid.isBlank() || targetUid != currentUid) {
                    return false
                }
            }

            return true
        }

        fun listenForAnnouncements(
            context: Context? = null,
            currentUid: String,
            onNewAlert: (title: String, message: String) -> Unit
        ): com.google.firebase.firestore.ListenerRegistration? {
            listenerRegistration?.remove()

            listenerRegistration = FirebaseFirestore.getInstance().collection("notifications")
                .limit(10)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        return@addSnapshotListener
                    }
                    val now = System.currentTimeMillis()
                    val docs = snapshot?.documents ?: return@addSnapshotListener
                    for (doc in docs) {
                        if (!isNotificationValidAndTargeted(doc, currentUid, now)) {
                            continue
                        }

                        // Check user preferences if context is available
                        if (context != null) {
                            try {
                                val prefs = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                                    NotificationPreferencesRepository(context).getPreferences()
                                }
                                val category = com.example.data.notification.NotificationCategoryResolver.resolveCategory(
                                    type = doc.getString("type") ?: "announcement"
                                )
                                if (!com.example.data.notification.NotificationCategoryResolver.isNotificationAllowed(category, prefs)) {
                                    continue
                                }
                            } catch (_: Exception) {}
                        }

                        val createdAt = when (val c = doc.get("createdAt")) {
                            is Timestamp -> c.toDate().time
                            is Number -> c.toLong()
                            else -> doc.getLong("timestamp") ?: 0L
                        }

                        val notifId = doc.id
                        val title = doc.getString("title") ?: ""
                        val body = doc.getString("body") ?: doc.getString("message") ?: ""
                        val imageUrl = doc.getString("imageUrl") ?: doc.getString("image_url")
                        val type = doc.getString("type") ?: "info"

                        if (context != null && (title.isNotBlank() || body.isNotBlank())) {
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                try {
                                    val dao = AppDatabase.getDatabase(context).notificationDao()
                                    val existing = dao.getNotificationById(notifId)
                                    if (existing == null) {
                                        val item = NotificationItem(
                                            id = notifId,
                                            title = title,
                                            message = body,
                                            timestamp = createdAt,
                                            isRead = false,
                                            imageUrl = imageUrl,
                                            type = type
                                        )
                                        dao.insertNotification(item)
                                    }
                                } catch (_: Exception) {}
                            }
                        }

                        if (createdAt > lastAlertTimestamp) {
                            lastAlertTimestamp = createdAt
                            if (title.isNotBlank() || body.isNotBlank()) {
                                onNewAlert(title, body)
                            }
                            break
                        }
                    }
                }
            return listenerRegistration
        }

        fun stopListeningAnnouncements() {
            listenerRegistration?.remove()
            listenerRegistration = null
        }
    }

    suspend fun addNotification(
        title: String,
        message: String,
        imageUrl: String? = null,
        type: String = "info",
        event: String? = null,
        isAnime: Boolean = false
    ) {
        val prefs = NotificationPreferencesRepository(appContext).getPreferences()
        val category = com.example.data.notification.NotificationCategoryResolver.resolveCategory(
            type = type,
            event = event,
            isAnime = isAnime
        )
        if (!com.example.data.notification.NotificationCategoryResolver.isNotificationAllowed(category, prefs)) {
            return
        }
        val notification = NotificationItem(
            title = title,
            message = message,
            imageUrl = imageUrl,
            type = type
        )
        notificationDao.insertNotification(notification)
    }

    suspend fun markAllAsRead() {
        notificationDao.markAllAsRead()
    }
    
    suspend fun markAsRead(id: String) {
        notificationDao.markAsRead(id)
    }

    suspend fun deleteNotification(id: String) {
        notificationDao.deleteNotification(id)
    }
}
