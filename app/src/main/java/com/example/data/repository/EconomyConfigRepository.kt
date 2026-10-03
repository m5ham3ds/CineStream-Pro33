package com.example.data.repository

import android.util.Log
import com.example.data.model.EconomyConfig
import com.example.data.model.FeatureState
import com.example.data.model.FeaturesConfig
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * PHASE SUBSCRIPTION-POINTS-03B: ECONOMY & FEATURE CONFIG CONSUMPTION
 *
 * Invariants:
 * 1. Read-only consumption of /config/features and /config/economy.
 * 2. Zero client-side configuration writes.
 * 3. Graceful fallback on missing document, permission denied, offline, or malformed data.
 */
object EconomyConfigRepository {
    private const val TAG = "EconomyConfigRepository"

    private val _featuresConfig = MutableStateFlow(FeaturesConfig())
    val featuresConfig: StateFlow<FeaturesConfig> = _featuresConfig.asStateFlow()

    private val _economyConfig = MutableStateFlow(EconomyConfig())
    val economyConfig: StateFlow<EconomyConfig> = _economyConfig.asStateFlow()

    private var featuresListener: ListenerRegistration? = null
    private var economyListener: ListenerRegistration? = null

    fun startListening() {
        stopListening()
        val db = FirebaseFirestore.getInstance()

        // 1. Listen to /config/features safely
        try {
            featuresListener = db.collection("config").document("features")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Features config listen error (using safe default): ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        _featuresConfig.value = parseFeaturesConfig(snapshot)
                    } else {
                        Log.d(TAG, "Features config document does not exist, using safe defaults")
                        _featuresConfig.value = FeaturesConfig()
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Exception attaching features listener: ${e.message}")
        }

        // 2. Listen to /config/economy safely
        try {
            economyListener = db.collection("config").document("economy")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Economy config listen error (using safe default): ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        _economyConfig.value = parseEconomyConfig(snapshot)
                    } else {
                        Log.d(TAG, "Economy config document does not exist, using safe defaults")
                        _economyConfig.value = EconomyConfig()
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Exception attaching economy listener: ${e.message}")
        }
    }

    fun stopListening() {
        featuresListener?.remove()
        featuresListener = null
        economyListener?.remove()
        economyListener = null
    }

    suspend fun refreshConfigOnce() {
        val db = FirebaseFirestore.getInstance()
        try {
            val featDoc = db.collection("config").document("features").get().await()
            if (featDoc != null && featDoc.exists()) {
                _featuresConfig.value = parseFeaturesConfig(featDoc)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh features config: ${e.message}")
        }

        try {
            val econDoc = db.collection("config").document("economy").get().await()
            if (econDoc != null && econDoc.exists()) {
                _economyConfig.value = parseEconomyConfig(econDoc)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh economy config: ${e.message}")
        }
    }

    fun parseFeaturesConfig(doc: DocumentSnapshot): FeaturesConfig {
        return try {
            FeaturesConfig(
                subscriptions = FeaturesConfig.parseFeatureState(doc.get("subscriptions"), FeatureState.ACTIVE),
                points = FeaturesConfig.parseFeatureState(doc.get("points"), FeatureState.ACTIVE),
                dailyLogin = FeaturesConfig.parseFeatureState(doc.get("dailyLogin"), FeatureState.ACTIVE),
                rewardedAds = FeaturesConfig.parseFeatureState(doc.get("rewardedAds"), FeatureState.ACTIVE),
                tasks = FeaturesConfig.parseFeatureState(doc.get("tasks"), FeatureState.ACTIVE),
                leaderboard = FeaturesConfig.parseFeatureState(doc.get("leaderboard"), FeatureState.ACTIVE),
                disabledMessage = doc.getString("disabledMessage") ?: "هذه الميزة غير متوفرة حالياً"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Malformed features config, using defaults", e)
            FeaturesConfig()
        }
    }

    fun parseEconomyConfig(doc: DocumentSnapshot): EconomyConfig {
        return try {
            val rawCosts = doc.get("redemptionCosts") as? Map<*, *>
            val costsMap = mutableMapOf<String, Long>()
            rawCosts?.forEach { (k, v) ->
                if (k is String && v is Number) {
                    costsMap[k] = v.toLong()
                }
            }

            val rawRewards = doc.get("dailyLoginRewards") as? List<*>
            val rewardsList = rawRewards?.mapNotNull { (it as? Number)?.toLong() }

            val rewardedPoints = doc.getLong("rewardedAdPoints") ?: 15L
            val dailyCap = doc.getLong("rewardedAdDailyCap")?.toInt() ?: 5
            val cooldown = doc.getLong("rewardedAdCooldownSeconds") ?: 300L

            EconomyConfig(
                redemptionCosts = if (costsMap.isNotEmpty()) costsMap else EconomyConfig().redemptionCosts,
                dailyLoginRewards = if (!rewardsList.isNullOrEmpty()) rewardsList else EconomyConfig().dailyLoginRewards,
                rewardedAdPoints = rewardedPoints,
                rewardedAdDailyCap = dailyCap,
                rewardedAdCooldownSeconds = cooldown
            )
        } catch (e: Exception) {
            Log.e(TAG, "Malformed economy config, using defaults", e)
            EconomyConfig()
        }
    }
}
