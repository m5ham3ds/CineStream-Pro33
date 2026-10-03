package com.example.data.sync

import android.content.Context
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.model.HistoryItem
import com.example.data.model.LibraryItem
import com.example.data.model.NotificationPreferences
import com.example.data.model.WatchedEpisode
import com.example.data.repository.NotificationPreferencesRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CloudSyncManager(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG = "CloudSyncManager"
        private var libraryListener: ListenerRegistration? = null
        private var historyListener: ListenerRegistration? = null
        private var watchedEpisodesListener: ListenerRegistration? = null
        private var notificationPrefsListener: ListenerRegistration? = null
        private val syncScope = CoroutineScope(Dispatchers.IO)

        fun stopRealtimeSync() {
            libraryListener?.remove()
            libraryListener = null
            historyListener?.remove()
            historyListener = null
            watchedEpisodesListener?.remove()
            watchedEpisodesListener = null
            notificationPrefsListener?.remove()
            notificationPrefsListener = null
            Log.d(TAG, "Stopped all real-time cloud sync listeners")
        }
    }

    /**
     * Starts persistent real-time snapshot listeners for library, history, watched episodes, and preferences.
     * Guarantees that cloud changes appear inside the app immediately without requiring app restart.
     */
    fun startRealtimeSync(userId: String) {
        if (userId.isBlank()) {
            stopRealtimeSync()
            return
        }

        stopRealtimeSync()
        val userDoc = firestore.collection("users").document(userId)

        // 1. Real-time Library Sync
        libraryListener = userDoc.collection("library")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Library listener error: ", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val libraryItems = snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        LibraryItem.fromFirestoreMap(doc.id, data)
                    }
                    syncScope.launch {
                        libraryItems.forEach { db.libraryDao().insertItem(it) }
                    }
                }
            }

        // 2. Real-time History Sync
        historyListener = userDoc.collection("history")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "History listener error: ", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val historyItems = snapshot.toObjects(HistoryItem::class.java)
                    syncScope.launch {
                        historyItems.filter { it.durationMillis > 0L && it.positionMillis >= 10000L }
                            .forEach { db.historyDao().insertHistory(it) }
                    }
                }
            }

        // 3. Real-time Watched Episodes Sync
        watchedEpisodesListener = userDoc.collection("watched_episodes")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Watched episodes listener error: ", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val episodes = snapshot.documents.mapNotNull { doc ->
                        try {
                            val ep = doc.toObject(WatchedEpisode::class.java)
                            if (ep != null && ep.id.isNotBlank()) ep else WatchedEpisode(id = doc.id)
                        } catch (e: Exception) {
                            WatchedEpisode(id = doc.id)
                        }
                    }
                    syncScope.launch {
                        episodes.forEach { db.watchedEpisodeDao().insert(it) }
                    }
                }
            }

        // 4. Real-time Notification Preferences Sync
        notificationPrefsListener = userDoc.collection("settings")
            .document("notifications")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Notification preferences listener error: ", error)
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val cloudPrefs = NotificationPreferences.fromFirestoreMap(snapshot.data)
                    syncScope.launch {
                        try {
                            NotificationPreferencesRepository(context).updateFromCloud(cloudPrefs)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed updating preferences from realtime sync: ${e.message}")
                        }
                    }
                }
            }

        Log.d(TAG, "Started real-time cloud sync listeners for user $userId")
    }

    suspend fun syncFromCloud(userId: String) {
        val userDoc = firestore.collection("users").document(userId)
        
        try {
            // 1. Push local data to cloud (in case they used the app as guest)
            val localLibrary = db.libraryDao().getAllItems().firstOrNull() ?: emptyList()
            localLibrary.forEach { userDoc.collection("library").document(it.libraryId).set(it.toFirestoreMap(), com.google.firebase.firestore.SetOptions.merge()) }

            val localHistory = db.historyDao().getAllHistory().firstOrNull() ?: emptyList()
            localHistory.forEach { userDoc.collection("history").document(it.id).set(it, com.google.firebase.firestore.SetOptions.merge()) }

            val localEpisodes = db.watchedEpisodeDao().getAllWatched().firstOrNull() ?: emptyList()
            localEpisodes.forEach { userDoc.collection("watched_episodes").document(it.id).set(it, com.google.firebase.firestore.SetOptions.merge()) }

            // 2. Pull cloud data to local with robust backward compatibility
            val librarySnapshot = userDoc.collection("library").get().await()
            val libraryItems = librarySnapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                LibraryItem.fromFirestoreMap(doc.id, data)
            }
            libraryItems.forEach { db.libraryDao().insertItem(it) }

            // History
            val historySnapshot = userDoc.collection("history").get().await()
            val historyItems = historySnapshot.toObjects(HistoryItem::class.java)
            historyItems.filter { it.durationMillis > 0L && it.positionMillis >= 10000L }.forEach { db.historyDao().insertHistory(it) }

            // Watched Episodes
            val episodesSnapshot = userDoc.collection("watched_episodes").get().await()
            val episodes = episodesSnapshot.documents.mapNotNull { doc ->
                try {
                    val ep = doc.toObject(WatchedEpisode::class.java)
                    if (ep != null && ep.id.isNotBlank()) ep else WatchedEpisode(id = doc.id)
                } catch (e: Exception) {
                    WatchedEpisode(id = doc.id)
                }
            }
            episodes.forEach { db.watchedEpisodeDao().insert(it) }

            // 3. Notification Preferences Sync
            try {
                com.example.data.repository.NotificationPreferencesRepository(context).syncWithFirestore(userId)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 4. Start real-time listeners right after initial sync
            startRealtimeSync(userId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun clearLocalData() {
        stopRealtimeSync()
        db.libraryDao().clearAll()
        db.historyDao().clearAll()
        db.watchedEpisodeDao().clearAll()
        try {
            com.example.data.repository.NotificationPreferencesRepository(context).resetToDefaults()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
