package com.example.extension.managed.repository

import com.example.extension.managed.trace.Phase05GLogger
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Production implementation of ManagedExtensionRemoteDataSource querying Firestore at canonical path:
 * /managed_extensions/{extensionId} with seamless backward/cross-platform compatibility for
 * /extensions/{extensionId} (CineStream Admin Dashboard contract).
 *
 * Enforces Phase 05G requirements:
 * A. /managed_extensions read failure -> preserve the failure
 * B. /extensions read failure -> preserve the failure
 * C. both succeed with zero documents -> genuine EMPTY result
 * D. one succeeds and the other fails -> canonical /managed_extensions has priority;
 *    never silently classify failure as empty success.
 */
class FirebaseFirestoreManagedExtensionDataSource(
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() }
) : ManagedExtensionRemoteDataSource {

    companion object {
        const val COLLECTION_PATH = "managed_extensions"
        const val LEGACY_COLLECTION_PATH = "extensions"
    }

    override suspend fun fetchManagedExtensionDtos(): Result<List<ManagedExtensionDto>> {
        val firestore = try {
            firestoreProvider()
        } catch (e: Exception) {
            Phase05GLogger.log("FIREBASE", "init", "Firestore provider initialization failed: ${e.message}")
            return Result.failure(e)
        }

        val dtosMap = LinkedHashMap<String, ManagedExtensionDto>()
        var managedSuccess = false
        var managedFailure: Throwable? = null
        var managedDocCount = 0

        // Ensure active auth session exists (e.g. for guest users to satisfy production Firestore rules)
        try {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously().await()
                Phase05GLogger.log("AUTH", "bootstrap", "Anonymous session established for extension sync: uid=${auth.currentUser?.uid}")
            }
        } catch (e: Exception) {
            Phase05GLogger.log("AUTH", "bootstrap", "Auth check/anonymous sign-in note: ${e.message}")
        }

        // 1. Fetch from canonical managed_extensions collection
        try {
            val managedSnap = firestore.collection(COLLECTION_PATH).get().await()
            managedSuccess = true
            managedDocCount = managedSnap.documents.size
            for (doc in managedSnap.documents) {
                try {
                    val dto = ManagedExtensionDto.fromDocument(doc)
                    if (dto.id != null) {
                        dtosMap[dto.id] = dto
                    }
                } catch (_: Exception) {}
            }
            Phase05GLogger.log("FIREBASE", "managed", "Read /managed_extensions: success, docs=$managedDocCount, parsed=${dtosMap.size}")
        } catch (e: Exception) {
            managedFailure = e
            Phase05GLogger.log("FIREBASE", "managed", "Read /managed_extensions: failed with ${e.javaClass.simpleName}: ${e.message}")
        }

        var legacySuccess = false
        var legacyFailure: Throwable? = null
        var legacyDocCount = 0

        // 2. Fetch from shared extensions collection (for Admin Dashboard contract compatibility)
        try {
            val legacySnap = firestore.collection(LEGACY_COLLECTION_PATH).get().await()
            legacySuccess = true
            legacyDocCount = legacySnap.documents.size
            for (doc in legacySnap.documents) {
                try {
                    val dto = ManagedExtensionDto.fromDocument(doc)
                    if (dto.id != null && !dtosMap.containsKey(dto.id)) {
                        dtosMap[dto.id] = dto
                    }
                } catch (_: Exception) {}
            }
            Phase05GLogger.log("FIREBASE", "legacy", "Read /extensions: success, docs=$legacyDocCount, totalMerged=${dtosMap.size}")
        } catch (e: Exception) {
            legacyFailure = e
            Phase05GLogger.log("FIREBASE", "legacy", "Read /extensions: failed with ${e.javaClass.simpleName}: ${e.message}")
        }

        // Evaluate outcome strictly per Phase 05G requirements:
        return when {
            // Case A: Canonical /managed_extensions failed
            managedFailure != null -> {
                Result.failure(managedFailure)
            }
            // Case C: Both succeed (can be genuine empty if 0 docs)
            managedSuccess && legacySuccess -> {
                Result.success(dtosMap.values.toList())
            }
            // Case D: Canonical succeeded, legacy failed
            managedSuccess && legacyFailure != null -> {
                if (managedDocCount > 0 && dtosMap.isNotEmpty()) {
                    // Canonical collection succeeded with non-empty results -> canonical has priority
                    Result.success(dtosMap.values.toList())
                } else {
                    // Canonical had 0 documents, but legacy failed -> do not silently classify as empty success
                    Result.failure(legacyFailure)
                }
            }
            // Case B: Legacy failed and managed not successful
            legacyFailure != null -> {
                Result.failure(legacyFailure)
            }
            else -> {
                Result.success(dtosMap.values.toList())
            }
        }
    }
}
