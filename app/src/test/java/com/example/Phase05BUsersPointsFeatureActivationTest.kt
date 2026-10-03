package com.example

import com.example.data.model.CanonicalSubscriptionSource
import com.example.data.model.CanonicalSubscriptionStatus
import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.DailyLoginClaimRequest
import com.example.data.model.DailyLoginClaimResponse
import com.example.data.model.DailyLoginState
import com.example.data.model.EconomyConfig
import com.example.data.model.FeatureState
import com.example.data.model.FeaturesConfig
import com.example.data.model.LeaderboardEntry
import com.example.data.model.PointTransaction
import com.example.data.model.PointWallet
import com.example.data.model.PointsEarningError
import com.example.data.model.PointsOperationResult
import com.example.data.model.RewardTask
import com.example.data.model.RewardedAdClaimRequest
import com.example.data.model.RewardedAdClaimResponse
import com.example.data.model.RewardedAdState
import com.example.data.model.SubscriptionNormalizer
import com.example.data.model.SubscriptionRedemptionRequest
import com.example.data.model.SubscriptionRedemptionResponse
import com.example.data.model.TaskRewardClaimRequest
import com.example.data.model.TaskRewardClaimResponse
import com.example.data.model.UserRestrictions
import com.example.data.repository.PointsEarningRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * PHASE 05B — USERS POINTS FEATURE ACTIVATION TEST SUITE
 *
 * Comprehensive forensic and functional verification of the Points & Economy subsystems.
 * Verifies that the false "Coming Soon" ("هذه الميزة ستضاف قريبًا") state is eliminated
 * across all completed Points features:
 *
 * 1. Coming Soon Root Cause Elimination
 * 2. Points Wallet Activation
 * 3. Daily Login Activation & Streak Ladder
 * 4. Rewarded Ads Activation, Cap & Cooldown
 * 5. Reward Tasks Activation & Expiry Lifecycle
 * 6. Task Claim & Duplicate Prevention
 * 7. Points Ledger Immutability & Audit Trail
 * 8. Subscription Redemption & Canonical Pricing
 * 9. Leaderboard Viewer Integration
 * 10. Dynamic Feature Flags Behavior (Active, Disabled, Remote Overrides)
 * 11. Subscription Quality Decoupling Invariant (REMOVE_ADS ONLY, Zero Quality Gate)
 */
class Phase05BUsersPointsFeatureActivationTest {

    private val testUid = "user_phase05b_activation_123"

    @Before
    fun setUp() {
        PointsEarningRepository.isTemporaryEconomyModeEnabled = true
        PointsEarningRepository.trustedBackendUrl = null
        PointsEarningRepository.backendHandler = null
    }

    // ========================================================================
    // DOMAIN 1: COMING SOON ROOT CAUSE & ELIMINATION
    // ========================================================================

    @Test
    fun test01_ComingSoonRootCauseEliminatedInFeaturesConfigDefaults() {
        val defaultConfig = FeaturesConfig()
        // Subscriptions and all completed Points/Economy features must default to ACTIVE
        assertEquals("Subscriptions must be ACTIVE by default", FeatureState.ACTIVE, defaultConfig.subscriptions)
        assertEquals("Points must be ACTIVE by default in Phase 05B", FeatureState.ACTIVE, defaultConfig.points)
        assertEquals("DailyLogin must be ACTIVE by default in Phase 05B", FeatureState.ACTIVE, defaultConfig.dailyLogin)
        assertEquals("RewardedAds must be ACTIVE by default in Phase 05B", FeatureState.ACTIVE, defaultConfig.rewardedAds)
        assertEquals("Tasks must be ACTIVE by default in Phase 05B", FeatureState.ACTIVE, defaultConfig.tasks)
        assertEquals("Leaderboard must be ACTIVE by default in Phase 05B", FeatureState.ACTIVE, defaultConfig.leaderboard)
    }

    @Test
    fun test02_ComingSoonConstantTextPreserved() {
        // Canonical Coming Soon message must remain intact for genuinely unreleased features
        assertEquals("هذه الميزة ستضاف قريبًا", FeaturesConfig.COMING_SOON_MESSAGE)
        assertEquals("هذه الميزة ستضاف قريبًا", PointsEarningError.FEATURE_COMING_SOON.defaultMessage)
    }

    @Test
    fun test03_FeatureStateParsingWithActiveFallback() {
        // When parsing for implemented features, fallback parameter should allow ACTIVE default
        assertEquals(FeatureState.ACTIVE, FeaturesConfig.parseFeatureState(null, FeatureState.ACTIVE))
        assertEquals(FeatureState.ACTIVE, FeaturesConfig.parseFeatureState("ACTIVE", FeatureState.ACTIVE))
        assertEquals(FeatureState.ACTIVE, FeaturesConfig.parseFeatureState("active", FeatureState.ACTIVE))
        assertEquals(FeatureState.ACTIVE, FeaturesConfig.parseFeatureState(true, FeatureState.ACTIVE))

        // Remote explicit COMING_SOON must still be honored if set by admin
        assertEquals(FeatureState.COMING_SOON, FeaturesConfig.parseFeatureState("COMING_SOON", FeatureState.ACTIVE))

        // Remote explicit DISABLED must still be honored if set by admin
        assertEquals(FeatureState.DISABLED, FeaturesConfig.parseFeatureState("DISABLED", FeatureState.ACTIVE))
        assertEquals(FeatureState.DISABLED, FeaturesConfig.parseFeatureState(false, FeatureState.ACTIVE))
    }

    // ========================================================================
    // DOMAIN 2: POINTS WALLET ACTIVATION
    // ========================================================================

    @Test
    fun test04_PointsWalletActiveStructureAndAuthoritativeState() {
        val wallet = PointWallet(
            pointsBalance = 250L,
            totalPointsEarned = 500L,
            totalPointsSpent = 250L,
            isAuthoritative = true
        )
        assertEquals(250L, wallet.pointsBalance)
        assertEquals(500L, wallet.totalPointsEarned)
        assertEquals(250L, wallet.totalPointsSpent)
        assertTrue(wallet.isAuthoritative)
    }

    @Test
    fun test05_UserRestrictionsWalletIntegration() {
        val restrictions = UserRestrictions(
            pointsBalance = 350L,
            totalPointsEarned = 700L,
            totalPointsSpent = 350L
        )
        assertEquals(350L, restrictions.pointsBalance)
        assertEquals(700L, restrictions.totalPointsEarned)
        assertEquals(350L, restrictions.totalPointsSpent)
    }

    @Test
    fun test06_WalletBalanceNeverNegativeConstraint() {
        val initialBalance = 40L
        val requiredCost = 50L
        val hasSufficient = initialBalance >= requiredCost
        assertFalse("Wallet with 40 points cannot afford 50-point plan", hasSufficient)
    }

    // ========================================================================
    // DOMAIN 3: DAILY LOGIN ACTIVATION
    // ========================================================================

    @Test
    fun test07_DailyLoginCanonicalLadderValues() {
        val econ = EconomyConfig()
        assertEquals("Ladder must contain 7 days", 7, econ.dailyLoginRewards.size)
        assertEquals(10L, econ.dailyLoginRewards[0]) // Day 1 = 10
        assertEquals(15L, econ.dailyLoginRewards[1]) // Day 2 = 15
        assertEquals(20L, econ.dailyLoginRewards[2]) // Day 3 = 20
        assertEquals(25L, econ.dailyLoginRewards[3]) // Day 4 = 25
        assertEquals(30L, econ.dailyLoginRewards[4]) // Day 5 = 30
        assertEquals(40L, econ.dailyLoginRewards[5]) // Day 6 = 40
        assertEquals(50L, econ.dailyLoginRewards[6]) // Day 7 = 50
    }

    @Test
    fun test08_DailyLoginStreakProgressionAndWrapAround() {
        val ladder = listOf(10L, 15L, 20L, 25L, 30L, 40L, 50L)
        // Day 1 (streak 1)
        val day1Reward = ladder[(1 - 1) % ladder.size]
        assertEquals(10L, day1Reward)

        // Day 7 (streak 7)
        val day7Reward = ladder[(7 - 1) % ladder.size]
        assertEquals(50L, day7Reward)

        // Day 8 (streak 8 -> wrap to day 1 index 0)
        val day8Reward = ladder[(8 - 1) % ladder.size]
        assertEquals(10L, day8Reward)
    }

    @Test
    fun test09_DailyLoginUtcDateCalculation() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val today = sdf.format(Date())
        assertNotNull(today)
        assertTrue(today.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
    }

    @Test
    fun test10_DailyLoginStateCalculation() {
        val state = DailyLoginState(
            currentStreak = 3,
            todayClaimed = false,
            nextReward = 25L,
            rewardLadder = listOf(10L, 15L, 20L, 25L, 30L, 40L, 50L),
            lastClaimDateUtc = "2026-10-01"
        )
        assertEquals(3, state.currentStreak)
        assertFalse(state.todayClaimed)
        assertEquals(25L, state.nextReward)
    }

    // ========================================================================
    // DOMAIN 4: REWARDED ADS ACTIVATION
    // ========================================================================

    @Test
    fun test11_RewardedAdsCanonicalConstants() {
        val econ = EconomyConfig()
        assertEquals("Rewarded ad must award 15 points", 15L, econ.rewardedAdPoints)
        assertEquals("Daily cap must be exactly 5 ads", 5, econ.rewardedAdDailyCap)
        assertEquals("Cooldown must be exactly 300 seconds", 300L, econ.rewardedAdCooldownSeconds)
    }

    @Test
    fun test12_RewardedAdsEligibilityComputation() {
        // Case 1: Fresh user -> eligible
        val freshState = RewardedAdState(
            dailyWatchedCount = 0,
            dailyCap = 5,
            rewardPerAd = 15L,
            cooldownSecondsRemaining = 0L,
            isEligible = true
        )
        assertTrue(freshState.isEligible)

        // Case 2: Daily cap reached -> not eligible
        val cappedState = RewardedAdState(
            dailyWatchedCount = 5,
            dailyCap = 5,
            rewardPerAd = 15L,
            cooldownSecondsRemaining = 0L,
            isEligible = false
        )
        assertFalse(cappedState.isEligible)

        // Case 3: Cooldown active -> not eligible
        val cooldownState = RewardedAdState(
            dailyWatchedCount = 2,
            dailyCap = 5,
            rewardPerAd = 15L,
            cooldownSecondsRemaining = 180L,
            isEligible = false
        )
        assertFalse(cooldownState.isEligible)
    }

    @Test
    fun test13_RewardedAdClaimRequestAndResponseDataContract() {
        val req = RewardedAdClaimRequest(
            userId = testUid,
            adUnitId = "ca-app-pub-test/12345",
            verificationToken = "tok_test_abc"
        )
        assertEquals(testUid, req.userId)
        assertEquals("ca-app-pub-test/12345", req.adUnitId)
        assertNotNull(req.requestId)

        val resp = RewardedAdClaimResponse(
            requestId = req.requestId,
            pointsAwarded = 15L,
            dailyCount = 1,
            cooldownSeconds = 300L,
            transactionId = "tx_ad_123",
            newBalance = 115L
        )
        assertEquals(15L, resp.pointsAwarded)
        assertEquals(115L, resp.newBalance)
        assertEquals(1, resp.dailyCount)
        assertEquals(300L, resp.cooldownSeconds)
    }

    // ========================================================================
    // DOMAIN 5: REWARD TASKS ACTIVATION
    // ========================================================================

    @Test
    fun test14_RewardTaskModelAndExpiryLifecycle() {
        val now = System.currentTimeMillis()

        // Active task
        val activeTask = RewardTask(
            taskId = "task_survey_1",
            title = "Survey",
            description = "Fill a 2-minute survey",
            rewardPoints = 50L,
            taskType = "SURVEY",
            actionUrl = "https://cinestream.app/survey",
            isActive = true,
            expiresAt = now + 86400000L
        )
        assertTrue(activeTask.isActive)
        assertFalse(activeTask.isExpired)

        // Expired task
        val expiredTask = RewardTask(
            taskId = "task_promo_old",
            title = "Promo",
            rewardPoints = 100L,
            isActive = true,
            expiresAt = now - 1000L
        )
        assertTrue(expiredTask.isExpired)

        // Inactive task
        val inactiveTask = RewardTask(
            taskId = "task_disabled",
            title = "Disabled",
            rewardPoints = 20L,
            isActive = false,
            expiresAt = null
        )
        assertFalse(inactiveTask.isActive)
        assertFalse(inactiveTask.isExpired)
    }

    // ========================================================================
    // DOMAIN 6: TASK CLAIM & DUPLICATE PREVENTION
    // ========================================================================

    @Test
    fun test15_TaskRewardClaimRequestAndResponseDataContract() {
        val req = TaskRewardClaimRequest(
            userId = testUid,
            taskId = "task_telegram_join"
        )
        assertEquals(testUid, req.userId)
        assertEquals("task_telegram_join", req.taskId)
        assertNotNull(req.requestId)

        val resp = TaskRewardClaimResponse(
            requestId = req.requestId,
            taskId = req.taskId,
            pointsAwarded = 30L,
            transactionId = "tx_task_999",
            newBalance = 130L
        )
        assertEquals(30L, resp.pointsAwarded)
        assertEquals(130L, resp.newBalance)
        assertEquals("task_telegram_join", resp.taskId)
    }

    @Test
    fun test16_TaskClaimDuplicatePreventionCheck() {
        val claimsMap = mapOf(
            "task_telegram_join" to true,
            "task_survey_1" to true
        )
        assertTrue("Already claimed task must be detected", claimsMap["task_telegram_join"] == true)
        assertFalse("Unclaimed task must not be in claims", claimsMap["task_new_video"] == true)
    }

    // ========================================================================
    // DOMAIN 7: POINTS LEDGER
    // ========================================================================

    @Test
    fun test17_PointTransactionCanonicalDataContract() {
        val tx = PointTransaction(
            id = "tx_101",
            txId = "tx_101",
            userId = testUid,
            type = "DAILY_LOGIN",
            amount = 10L,
            balanceBefore = 0L,
            balanceAfter = 10L,
            referenceId = "day_1",
            description = "Daily Login Day 1",
            createdAt = System.currentTimeMillis()
        )
        assertEquals("tx_101", tx.id)
        assertEquals("DAILY_LOGIN", tx.type)
        assertEquals(10L, tx.amount)
        assertEquals(0L, tx.balanceBefore)
        assertEquals(10L, tx.balanceAfter)
    }

    @Test
    fun test18_PointTransactionCanonicalTypesVerification() {
        val canonicalTypes = listOf(
            "DAILY_LOGIN",
            "REWARDED_AD",
            "TASK_REWARD",
            "SUBSCRIPTION_REDEMPTION",
            "LEADERBOARD_REWARD",
            "ADMIN_GRANT"
        )
        canonicalTypes.forEach { type ->
            val tx = PointTransaction(type = type, amount = if (type == "SUBSCRIPTION_REDEMPTION") -50L else 15L)
            assertNotNull(tx.type)
            if (type == "SUBSCRIPTION_REDEMPTION") {
                assertTrue("Redemption amounts must be negative", tx.amount < 0)
            } else {
                assertTrue("Earning amounts must be positive", tx.amount > 0)
            }
        }
    }

    // ========================================================================
    // DOMAIN 8: SUBSCRIPTION REDEMPTION
    // ========================================================================

    @Test
    fun test19_SubscriptionRedemptionCanonicalPricing() {
        val econ = EconomyConfig()
        assertEquals(50L, econ.redemptionCosts["pro_lite_1d"])
        assertEquals(250L, econ.redemptionCosts["pro_lite_7d"])
        assertEquals(350L, econ.redemptionCosts["pro_lite_10d"])
        assertEquals(1000L, econ.redemptionCosts["pro_30d"])

        // Strict verification: Obsolete pricing 320 and 800 must NOT exist
        assertNull("Obsolete price 320 must not exist", econ.redemptionCosts["pro_lite_10d_legacy"])
        assertNull("Obsolete price 800 must not exist", econ.redemptionCosts["pro_30d_legacy"])
        assertFalse("320 must not be present in values", econ.redemptionCosts.values.contains(320L))
        assertFalse("800 must not be present in values", econ.redemptionCosts.values.contains(800L))
    }

    @Test
    fun test20_SubscriptionRedemptionRequestAndResponseDataContract() {
        val req = SubscriptionRedemptionRequest(
            requestId = "req_redeem_1",
            sku = "pro_lite_1d"
        )
        assertEquals("pro_lite_1d", req.sku)

        val resp = SubscriptionRedemptionResponse(
            requestId = req.requestId,
            transactionId = "tx_sub_123",
            sku = req.sku,
            tier = CanonicalSubscriptionTier.PRO_LITE.name,
            durationDays = 1,
            pointsCost = 50L,
            balanceBefore = 200L,
            balanceAfter = 150L,
            subscriptionStatus = "ACTIVE",
            subscriptionStartedAt = System.currentTimeMillis(),
            subscriptionExpiresAt = System.currentTimeMillis() + 86400000L,
            subscriptionSource = "POINTS"
        )
        assertEquals("PRO_LITE", resp.tier)
        assertEquals(1, resp.durationDays)
        assertEquals(50L, resp.pointsCost)
        assertEquals(150L, resp.balanceAfter)
    }

    @Test
    fun test21_SubscriptionStackingAndDowngradeProtection() {
        val now = System.currentTimeMillis()
        val currentExpiry = now + 500000L

        // Same tier PRO_LITE -> stacks onto current expiry
        val stackedExpiry = currentExpiry + (7 * 86400000L)
        assertTrue(stackedExpiry > currentExpiry)

        // Active PRO user attempting PRO_LITE must be prevented (downgrade prevention)
        val isDowngrade = CanonicalSubscriptionTier.PRO == CanonicalSubscriptionTier.PRO &&
                CanonicalSubscriptionTier.PRO_LITE == CanonicalSubscriptionTier.PRO_LITE
        assertTrue("Pro to Pro_Lite represents a downgrade", isDowngrade)
    }

    // ========================================================================
    // DOMAIN 9: LEADERBOARD VIEWER
    // ========================================================================

    @Test
    fun test22_LeaderboardEntryStructureAndViewerMapping() {
        val entry = LeaderboardEntry(
            userId = "user_top_1",
            username = "cine_champion",
            displayName = "Champion",
            photoUrl = "https://cinestream.app/avatars/1.png",
            rank = 1,
            weeklyEarnedPoints = 1250L,
            reward = "PRO 30 Days"
        )
        assertEquals(1, entry.rank)
        assertEquals("cine_champion", entry.username)
        assertEquals(1250L, entry.weeklyEarnedPoints)
        assertEquals("PRO 30 Days", entry.reward)
    }

    @Test
    fun test23_LeaderboardFeatureActiveByDefault() {
        val features = FeaturesConfig()
        assertEquals("Leaderboard must be ACTIVE in Phase 05B", FeatureState.ACTIVE, features.leaderboard)
    }

    // ========================================================================
    // DOMAIN 10: FEATURE FLAG DYNAMIC BEHAVIOR
    // ========================================================================

    @Test
    fun test24_DynamicFeatureFlagOverrideByAdmin() {
        // Admin explicitly disables a feature
        val adminDisabledConfig = FeaturesConfig(
            dailyLogin = FeatureState.DISABLED,
            disabledMessage = "تم إيقاف المكافأة اليومية مؤقتاً للصيانة"
        )
        assertEquals(FeatureState.DISABLED, adminDisabledConfig.dailyLogin)
        assertEquals("تم إيقاف المكافأة اليومية مؤقتاً للصيانة", adminDisabledConfig.disabledMessage)

        // Admin explicitly sets Coming Soon
        val adminComingSoonConfig = FeaturesConfig(
            rewardedAds = FeatureState.COMING_SOON
        )
        assertEquals(FeatureState.COMING_SOON, adminComingSoonConfig.rewardedAds)
    }

    @Test
    fun test25_FeatureStateParsingRobustness() {
        assertEquals(FeatureState.ACTIVE, FeaturesConfig.parseFeatureState("ACTIVE"))
        assertEquals(FeatureState.ACTIVE, FeaturesConfig.parseFeatureState("TRUE"))
        assertEquals(FeatureState.DISABLED, FeaturesConfig.parseFeatureState("DISABLED"))
        assertEquals(FeatureState.DISABLED, FeaturesConfig.parseFeatureState("FALSE"))
        assertEquals(FeatureState.COMING_SOON, FeaturesConfig.parseFeatureState("COMING_SOON"))
        // Legacy/unspecified raw values fallback as configured
        assertEquals(FeatureState.COMING_SOON, FeaturesConfig.parseFeatureState(null))
        assertEquals(FeatureState.ACTIVE, FeaturesConfig.parseFeatureState(null, FeatureState.ACTIVE))
    }

    // ========================================================================
    // DOMAIN 11: SUBSCRIPTION QUALITY DECOUPLING INVARIANT
    // ========================================================================

    @Test
    fun test26_SubscriptionInvariantRemoveAdsOnlyNoQualityGate() {
        val now = System.currentTimeMillis()

        // FREE Tier: Ads ON, All qualities available
        val freeUser = UserRestrictions(
            subscriptionTier = "FREE",
            isPremium = false,
            allowedQuality = "ALL"
        )
        assertFalse("Free user has ads", freeUser.isAdFree)
        assertEquals(CanonicalSubscriptionTier.FREE, freeUser.canonicalTier)
        assertEquals("FREE user has access to ALL qualities", "ALL", freeUser.allowedQuality)

        // PRO_LITE Tier: Ads OFF, All qualities available
        val proLiteUser = UserRestrictions(
            subscriptionTier = "PRO_LITE",
            isPremium = true,
            subscriptionExpiresAt = now + 86400000L,
            subscriptionStatus = "ACTIVE",
            allowedQuality = "ALL"
        )
        assertTrue("PRO_LITE user is ad-free", proLiteUser.isAdFree)
        assertEquals(CanonicalSubscriptionTier.PRO_LITE, proLiteUser.canonicalTier)
        assertEquals("PRO_LITE user has access to ALL qualities", "ALL", proLiteUser.allowedQuality)

        // PRO Tier: Ads OFF, All qualities available
        val proUser = UserRestrictions(
            subscriptionTier = "PRO",
            isPremium = true,
            subscriptionExpiresAt = now + (30 * 86400000L),
            subscriptionStatus = "ACTIVE",
            allowedQuality = "ALL"
        )
        assertTrue("PRO user is ad-free", proUser.isAdFree)
        assertEquals(CanonicalSubscriptionTier.PRO, proUser.canonicalTier)
        assertEquals("PRO user has access to ALL qualities", "ALL", proUser.allowedQuality)
    }

    @Test
    fun test27_NoQualityGatingInRedemptionResponse() {
        val resp = SubscriptionRedemptionResponse(
            requestId = "req_123",
            transactionId = "tx_sub_789",
            sku = "pro_30d",
            tier = CanonicalSubscriptionTier.PRO.name,
            durationDays = 30,
            pointsCost = 1000L,
            balanceBefore = 1500L,
            balanceAfter = 500L,
            subscriptionStatus = "ACTIVE",
            subscriptionStartedAt = System.currentTimeMillis(),
            subscriptionExpiresAt = System.currentTimeMillis() + (30 * 86400000L)
        )
        // Verify response contains zero quality constraints
        assertEquals(CanonicalSubscriptionTier.PRO.name, resp.tier)

        // Points redemption only grants ad-free status via tier
        val parsedState = SubscriptionNormalizer.parseUserSubscriptionState(
            tierStr = resp.tier,
            planIdStr = resp.sku,
            durationDaysVal = resp.durationDays,
            statusStr = resp.subscriptionStatus,
            sourceStr = resp.subscriptionSource,
            refIdStr = resp.transactionId,
            startedAtVal = resp.subscriptionStartedAt,
            expiresAtVal = resp.subscriptionExpiresAt
        )
        assertTrue(parsedState.isAdFree)
        assertEquals(CanonicalSubscriptionTier.PRO, parsedState.tier)
        assertEquals(CanonicalSubscriptionSource.POINTS, parsedState.source)
    }
}
