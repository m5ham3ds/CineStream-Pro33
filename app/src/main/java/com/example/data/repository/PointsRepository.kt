package com.example.data.repository

import android.util.Log
import com.example.data.model.LeaderboardEntry
import com.example.data.model.PointTransaction
import com.example.data.model.ProRequest
import com.example.data.model.RewardTask
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * PHASE SUBSCRIPTION-POINTS-03B: POINTS & LEDGER REPOSITORY (READ-ONLY)
 *
 * Absolute rule:
 * Users App NEVER mutates pointsBalance, totalPointsEarned, totalPointsSpent,
 * or writes to point_transactions directly.
 * All mutations are server-authoritative.
 */
object PointsRepository {
    private const val TAG = "PointsRepository"
    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()

    /**
     * Observes the user's personal point transactions from:
     * /users/{uid}/point_transactions/{txId}
     *
     * Handles permission denied or missing subcollection safely by emitting empty list.
     */
    fun observePointTransactions(uid: String): Flow<List<PointTransaction>> = callbackFlow {
        if (uid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = db.collection("users").document(uid).collection("point_transactions")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Point transactions listen error (safe fallback): ${error.message}")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val items = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val amount = doc.getLong("amount") ?: 0L
                        val created = when (val c = doc.get("createdAt") ?: doc.get("serverTimestamp")) {
                            is Timestamp -> c.toDate().time
                            is Number -> c.toLong()
                            else -> 0L
                        }
                        PointTransaction(
                            id = doc.id,
                            txId = doc.getString("txId") ?: doc.id,
                            userId = doc.getString("userId") ?: uid,
                            type = doc.getString("type") ?: "UNKNOWN",
                            amount = amount,
                            balanceBefore = doc.getLong("balanceBefore") ?: 0L,
                            balanceAfter = doc.getLong("balanceAfter") ?: 0L,
                            referenceId = doc.getString("referenceId"),
                            description = doc.getString("description") ?: "",
                            createdAt = created
                        )
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()

                trySend(items)
            }

        awaitClose { listener.remove() }
    }

    /**
     * Observes active reward tasks from /reward_tasks
     */
    fun observeRewardTasks(): Flow<List<RewardTask>> = callbackFlow {
        val listener = db.collection("reward_tasks")
            .whereEqualTo("isActive", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Reward tasks listen error: ${error.message}")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val tasks = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val exp = when (val e = doc.get("expiresAt")) {
                            is Timestamp -> e.toDate().time
                            is Number -> e.toLong()
                            else -> null
                        }
                        RewardTask(
                            taskId = doc.id,
                            title = doc.getString("title") ?: "",
                            description = doc.getString("description") ?: "",
                            rewardPoints = doc.getLong("rewardPoints") ?: 0L,
                            taskType = doc.getString("taskType") ?: "GENERAL",
                            actionUrl = doc.getString("actionUrl") ?: "",
                            isActive = doc.getBoolean("isActive") ?: true,
                            expiresAt = exp
                        )
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()

                trySend(tasks)
            }

        awaitClose { listener.remove() }
    }

    /**
     * Observes weekly leaderboard from /leaderboard/weekly_current
     */
    fun observeWeeklyLeaderboard(): Flow<List<LeaderboardEntry>> = callbackFlow {
        val listener = db.collection("leaderboard").document("weekly_current")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Leaderboard listen error: ${error.message}")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val rawList = snapshot.get("rankings") as? List<*>
                    val entries = rawList?.mapNotNull { item ->
                        val map = item as? Map<*, *> ?: return@mapNotNull null
                        LeaderboardEntry(
                            userId = map["userId"]?.toString() ?: "",
                            username = map["username"]?.toString() ?: "",
                            displayName = map["displayName"]?.toString() ?: "",
                            photoUrl = map["photoUrl"]?.toString() ?: "",
                            rank = (map["rank"] as? Number)?.toInt() ?: 0,
                            weeklyEarnedPoints = (map["weeklyEarnedPoints"] as? Number)?.toLong() ?: 0L,
                            reward = map["reward"]?.toString() ?: ""
                        )
                    } ?: emptyList()
                    trySend(entries)
                } else {
                    trySend(emptyList())
                }
            }

        awaitClose { listener.remove() }
    }

    /**
     * Submits a pending Pro subscription request to /pro_requests/{id}.
     * The client NEVER approves itself or activates subscriptions directly.
     * Activation occurs only when Admin updates /users/{uid}.
     */
    suspend fun submitProRequest(
        planId: String,
        durationDays: Int,
        paymentReference: String,
        notes: String
    ): Result<String> {
        val currentUser = auth.currentUser ?: return Result.failure(IllegalStateException("User not signed in"))
        return try {
            val reqId = "req_${System.currentTimeMillis()}_${currentUser.uid.take(6)}"
            val payload = mapOf(
                "requestId" to reqId,
                "userId" to currentUser.uid,
                "planId" to planId,
                "durationDays" to durationDays,
                "status" to "PENDING",
                "paymentReference" to paymentReference,
                "notes" to notes,
                "createdAt" to System.currentTimeMillis()
            )
            db.collection("pro_requests").document(reqId).set(payload).await()
            Result.success(reqId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit pro request", e)
            Result.failure(e)
        }
    }
}
