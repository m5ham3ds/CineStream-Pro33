package com.example.data.repository

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.model.ContentType
import com.example.data.model.LibraryIdentity
import com.example.data.model.LibraryItem
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.Flow

class LibraryRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val libraryDao = db.libraryDao()
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    /**
     * Checks if an item is in the library.
     * Supports both namespaced libraryId (e.g. "movie_550", "anime_1399")
     * and fallback TMDB ID.
     */
    fun isItemInLibrary(idOrLibraryId: String): Flow<Boolean> {
        return if (idOrLibraryId.contains("_")) {
            libraryDao.isItemInLibrary(idOrLibraryId)
        } else {
            libraryDao.isItemInLibraryByTmdbId(idOrLibraryId)
        }
    }

    /**
     * Exact check for a specific media item by its TMDB ID and canonical contentType.
     * Guarantees movie_550 != tv_550.
     */
    fun isItemInLibrary(tmdbId: String, contentType: String): Flow<Boolean> {
        val normalizedType = ContentType.normalize(contentType)
        val libraryId = LibraryIdentity.createLibraryId(normalizedType, tmdbId)
        return libraryDao.isItemInLibrary(libraryId)
    }

    fun isSeriesInLibrary(tmdbId: String): Flow<Boolean> {
        return libraryDao.isSeriesInLibrary(tmdbId)
    }

    fun getLibraryItems(): Flow<List<LibraryItem>> {
        return libraryDao.getAllItems()
    }

    suspend fun addToLibrary(item: LibraryItem) {
        LibraryIdentity.validate(item)
        libraryDao.insertItem(item)

        auth.currentUser?.uid?.let { uid ->
            try {
                // Canonical Firestore path uses item.libraryId as document ID
                firestore.collection("users").document(uid).collection("library")
                    .document(item.libraryId)
                    .set(item.toFirestoreMap(), SetOptions.merge())
            } catch (e: Exception) {
                // Non-blocking offline-first sync
            }
        }
    }

    suspend fun removeFromLibrary(item: LibraryItem) {
        LibraryIdentity.validate(item)
        libraryDao.deleteItem(item)

        auth.currentUser?.uid?.let { uid ->
            try {
                val libCol = firestore.collection("users").document(uid).collection("library")
                // Delete canonical namespaced document
                libCol.document(item.libraryId).delete()
                // Also clean up legacy un-namespaced document if it existed to prevent phantom items
                libCol.document(item.tmdbId).delete()
            } catch (e: Exception) {
                // Non-blocking offline-first sync
            }
        }
    }

    suspend fun removeFromLibrary(libraryId: String) {
        val item = libraryDao.getItemById(libraryId)
        if (item != null) {
            removeFromLibrary(item)
        } else {
            libraryDao.deleteByLibraryId(libraryId)
        }
    }
}
