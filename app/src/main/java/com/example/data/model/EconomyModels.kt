package com.example.data.model

import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * PHASE SUBSCRIPTION-POINTS-03B: CANONICAL ECONOMY & FEATURE CONFIG MODELS
 *
 * Invariants:
 * 1. Users App is the Execution / Consumption plane only.
 * 2. It may READ features, economy configs, points balance, and transactions.
 * 3. It MUST NEVER write or mutate economic balances, ledgers, or configs.
 */
enum class FeatureState {
    ACTIVE,
    COMING_SOON,
    DISABLED
}

@IgnoreExtraProperties
data class FeaturesConfig(
    val subscriptions: FeatureState = FeatureState.ACTIVE,
    val points: FeatureState = FeatureState.ACTIVE,
    val dailyLogin: FeatureState = FeatureState.ACTIVE,
    val rewardedAds: FeatureState = FeatureState.ACTIVE,
    val tasks: FeatureState = FeatureState.ACTIVE,
    val leaderboard: FeatureState = FeatureState.ACTIVE,
    val disabledMessage: String = "هذه الميزة غير متوفرة حالياً"
) {
    companion object {
        const val COMING_SOON_MESSAGE = "هذه الميزة ستضاف قريبًا"

        fun parseFeatureState(raw: Any?, fallback: FeatureState = FeatureState.COMING_SOON): FeatureState {
            if (raw == null) return fallback
            val str = raw.toString().trim().uppercase()
            return when (str) {
                "ACTIVE", "TRUE" -> FeatureState.ACTIVE
                "DISABLED", "FALSE" -> FeatureState.DISABLED
                "COMING_SOON" -> FeatureState.COMING_SOON
                else -> fallback
            }
        }
    }
}

@IgnoreExtraProperties
data class EconomyConfig(
    val redemptionCosts: Map<String, Long> = mapOf(
        "pro_lite_1d" to 50L,
        "pro_lite_7d" to 250L,
        "pro_lite_10d" to 350L,
        "pro_30d" to 1000L
    ),
    val dailyLoginRewards: List<Long> = listOf(10L, 15L, 20L, 25L, 30L, 40L, 50L),
    val rewardedAdPoints: Long = 15L,
    val rewardedAdDailyCap: Int = 5,
    val rewardedAdCooldownSeconds: Long = 300L
)

@IgnoreExtraProperties
data class PointTransaction(
    val id: String = "",
    val txId: String = "",
    val userId: String = "",
    val type: String = "", // DAILY_LOGIN, REWARDED_AD, TASK_REWARD, etc.
    val amount: Long = 0L,
    val balanceBefore: Long = 0L,
    val balanceAfter: Long = 0L,
    val referenceId: String? = null,
    val description: String = "",
    val createdAt: Long = 0L
)

@IgnoreExtraProperties
data class RewardTask(
    val taskId: String = "",
    val title: String = "",
    val description: String = "",
    val rewardPoints: Long = 0L,
    val taskType: String = "",
    val actionUrl: String = "",
    val isActive: Boolean = true,
    val expiresAt: Long? = null
) {
    val isExpired: Boolean
        @Exclude get() = expiresAt != null && expiresAt < System.currentTimeMillis()
}

@IgnoreExtraProperties
data class LeaderboardEntry(
    val userId: String = "",
    val username: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    val rank: Int = 0,
    val weeklyEarnedPoints: Long = 0L,
    val reward: String = ""
)

@IgnoreExtraProperties
data class ProRequest(
    val requestId: String = "",
    val userId: String = "",
    val planId: String = "",
    val durationDays: Int = 0,
    val status: String = "PENDING", // PENDING, APPROVED, REJECTED
    val paymentReference: String = "",
    val notes: String = "",
    val createdAt: Long = 0L
)
