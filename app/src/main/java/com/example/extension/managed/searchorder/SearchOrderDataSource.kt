package com.example.extension.managed.searchorder

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Data source abstraction for reading Search Order configuration.
 */
interface SearchOrderDataSource {
    suspend fun fetchSearchOrder(): SearchOrder?
}

/**
 * Canonical Firestore implementation reading from `/config/search_order`.
 *
 * Rules:
 * - Read-only. Users App MUST NEVER write to `/config/search_order`.
 * - Admin order is authoritative.
 * - Extracts fields strictly preserving order.
 */
class FirebaseSearchOrderDataSource(
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() }
) : SearchOrderDataSource {

    companion object {
        const val CONFIG_COLLECTION = "config"
        const val SEARCH_ORDER_DOC = "search_order"
    }

    override suspend fun fetchSearchOrder(): SearchOrder? {
        return try {
            try {
                val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                if (auth.currentUser == null) {
                    auth.signInAnonymously().await()
                }
            } catch (_: Exception) {}

            val firestore = firestoreProvider()
            val snapshot = firestore.collection(CONFIG_COLLECTION)
                .document(SEARCH_ORDER_DOC)
                .get()
                .await()

            if (!snapshot.exists()) {
                return null
            }

            val data = snapshot.data ?: return null

            fun parseStringList(key: String): List<String>? {
                if (!data.containsKey(key) || data[key] == null) return null
                val raw = data[key] as? List<*> ?: return emptyList()
                return raw.mapNotNull { item ->
                    val str = item?.toString()?.trim()
                    if (!str.isNullOrBlank()) str else null
                }
            }

            SearchOrder(
                movie = parseStringList("movie") ?: emptyList(),
                tv = parseStringList("tv"),
                series = parseStringList("series") ?: emptyList(),
                anime = parseStringList("anime") ?: emptyList()
            )
        } catch (_: Exception) {
            null
        }
    }
}
