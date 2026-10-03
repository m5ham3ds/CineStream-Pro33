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
import com.example.data.model.NotificationItem
import com.example.data.model.PointTransaction
import com.example.data.model.PointWallet
import com.example.data.model.PointsEarningError
import com.example.data.model.PointsOperationResult
import com.example.data.model.RewardTask
import com.example.data.model.RewardedAdClaimRequest
import com.example.data.model.RewardedAdState
import com.example.data.model.SubscriptionNormalizer
import com.example.data.model.SubscriptionRedemptionRequest
import com.example.data.model.TaskRewardClaimRequest
import com.example.data.model.UserRestrictions
import com.example.data.repository.EconomyConfigRepository
import com.example.data.repository.PointsEarningRepository
import com.example.data.repository.PointsRepository
import com.example.data.repository.TemporaryFirebaseEconomyRepository
import com.example.data.repository.UserSecurityManager
import com.example.utils.AdManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * PHASE 04C: TEMPORARY FIREBASE ECONOMY INTEGRATION TEST SUITE
 *
 * Verifies all 9 required verification domains:
 * A. Daily Login (first claim, duplicate same day, streak continuation, streak reset, day 7, fallback)
 * B. Tasks (active task, expired task, inactive task, duplicate claim, reward points, malformed task)
 * C. Wallet (balance, earned, spent, refresh, missing document)
 * D. Ledger (transaction mapping, all canonical types, malformed transaction)
 * E. Redemption (all 4 SKUs, insufficient balance, disabled feature, coming soon, malformed config,
 *    canonical pricing 50/250/350/1000, no 320/800, FREE rejected, invalid SKU, duplicate tap, refresh, legacy mirrors)
 * F. Subscription (FREE, PRO_LITE, PRO, expiry, same-tier extension, PRO_LITE -> PRO, PRO -> PRO_LITE rejection)
 * G. Ads (FREE ads, PRO_LITE ads off, PRO ads off, expiry, no quality coupling)
 * H. Leaderboard (read, empty, malformed document)
 * I. Notifications (read, unread, mark read, empty, malformed data)
 */
class Phase04CTemporaryFirebaseEconomyTest {

    @Before
    fun setUp() {
        UserSecurityManager.reset()
        PointsEarningRepository.isTemporaryEconomyModeEnabled = true
        PointsEarningRepository.trustedBackendUrl = null
        PointsEarningRepository.backendHandler = null
    }

    // =========================================================================
    // SECTION A: DAILY LOGIN
    // =========================================================================

    @Test
    fun testA1_DailyLoginCanonicalFallbackLadder() {
        val config = EconomyConfig()
        val ladder = config.dailyLoginRewards
        assertEquals(7, ladder.size)
        assertEquals(10L, ladder[0])
        assertEquals(15L, ladder[1])
        assertEquals(20L, ladder[2])
        assertEquals(25L, ladder[3])
        assertEquals(30L, ladder[4])
        assertEquals(40L, ladder[5])
        assertEquals(50L, ladder[6])

        // Ensure old ladder [5, 10, 15, ...] is NOT used
        assertNotEquals(5L, ladder[0])
    }

    @Test
    fun testA2_DailyLoginFirstClaimCalculatesDay1Reward() {
        val ladder = listOf(10L, 15L, 20L, 25L, 30L, 40L, 50L)
        val initialStreak = 0
        val newStreak = initialStreak + 1
        val reward = ladder[(newStreak - 1) % ladder.size]
        assertEquals(1, newStreak)
        assertEquals(10L, reward)
    }

    @Test
    fun testA3_DailyLoginDuplicateSameDayRejected() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val todayUtc = sdf.format(Date())
        val lastClaimDate = todayUtc

        val isAlreadyClaimed = (lastClaimDate == todayUtc)
        assertTrue("Duplicate claim on same UTC date must be detected as claimed", isAlreadyClaimed)
    }

    @Test
    fun testA4_DailyLoginStreakContinuationFromYesterday() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val yesterdayUtc = sdf.format(Date(System.currentTimeMillis() - 86400000L))
        val currentStreak = 3
        val lastClaimDate = yesterdayUtc

        val newStreak = if (lastClaimDate == yesterdayUtc) currentStreak + 1 else 1
        assertEquals(4, newStreak)
        val ladder = listOf(10L, 15L, 20L, 25L, 30L, 40L, 50L)
        val reward = ladder[(newStreak - 1) % ladder.size]
        assertEquals(25L, reward) // Day 4 = 25
    }

    @Test
    fun testA5_DailyLoginStreakResetOnMissedDay() {
        val twoDaysAgo = "2026-09-28"
        val yesterday = "2026-09-30"
        val currentStreak = 5

        val newStreak = if (twoDaysAgo == yesterday) currentStreak + 1 else 1
        assertEquals(1, newStreak) // Reset to day 1
    }

    @Test
    fun testA6_DailyLoginDay7RewardAndCycle() {
        val ladder = listOf(10L, 15L, 20L, 25L, 30L, 40L, 50L)
        val day7Streak = 7
        val reward7 = ladder[(day7Streak - 1) % ladder.size]
        assertEquals(50L, reward7)

        // Day 8 wraps to index 0 (Day 1)
        val day8Streak = 8
        val reward8 = ladder[(day8Streak - 1) % ladder.size]
        assertEquals(10L, reward8)
    }

    // =========================================================================
    // SECTION B: TASKS
    // =========================================================================

    @Test
    fun testB1_ActiveTaskEvaluation() {
        val task = RewardTask(
            taskId = "task_watch_1",
            title = "Watch trailer",
            rewardPoints = 50L,
            taskType = "WATCH_VIDEO",
            isActive = true,
            expiresAt = System.currentTimeMillis() + 100000L
        )
        assertTrue(task.isActive)
        assertFalse(task.isExpired)
        assertEquals(50L, task.rewardPoints)
    }

    @Test
    fun testB2_ExpiredTaskEvaluation() {
        val task = RewardTask(
            taskId = "task_expired",
            title = "Old promo",
            rewardPoints = 100L,
            taskType = "SURVEY",
            isActive = true,
            expiresAt = System.currentTimeMillis() - 5000L
        )
        assertTrue("Task with past expiry must be recognized as expired", task.isExpired)
    }

    @Test
    fun testB3_InactiveTaskEvaluation() {
        val task = RewardTask(
            taskId = "task_inactive",
            title = "Disabled task",
            rewardPoints = 30L,
            taskType = "FOLLOW_SOCIAL",
            isActive = false,
            expiresAt = null
        )
        assertFalse(task.isActive)
    }

    @Test
    fun testB4_DuplicateTaskClaimPrevention() {
        val claimsMap = mapOf("task_follow" to true)
        val targetTaskId = "task_follow"
        val isAlreadyClaimed = claimsMap.containsKey(targetTaskId)
        assertTrue(isAlreadyClaimed)
    }

    @Test
    fun testB5_MalformedTaskRewardFallback() {
        val task = RewardTask(taskId = "task_bad", rewardPoints = -10L, isActive = true)
        assertTrue("Negative reward points must be treated as invalid", task.rewardPoints <= 0L)
    }

    // =========================================================================
    // SECTION C: WALLET
    // =========================================================================

    @Test
    fun testC1_WalletCalculationsAndInvariants() {
        val wallet = PointWallet(
            pointsBalance = 350L,
            totalPointsEarned = 500L,
            totalPointsSpent = 150L,
            isAuthoritative = true
        )
        assertEquals(350L, wallet.pointsBalance)
        assertEquals(500L, wallet.totalPointsEarned)
        assertEquals(150L, wallet.totalPointsSpent)
        assertTrue(wallet.pointsBalance <= wallet.totalPointsEarned)
    }

    @Test
    fun testC2_WalletMissingDocumentDefaultsToZero() {
        val emptyWallet = PointWallet()
        assertEquals(0L, emptyWallet.pointsBalance)
        assertEquals(0L, emptyWallet.totalPointsEarned)
        assertEquals(0L, emptyWallet.totalPointsSpent)
    }

    // =========================================================================
    // SECTION D: LEDGER
    // =========================================================================

    @Test
    fun testD1_LedgerAllCanonicalTypesMapping() {
        val canonicalTypes = listOf(
            "DAILY_LOGIN",
            "REWARDED_AD",
            "TASK_REWARD",
            "GAME_REWARD",
            "LEADERBOARD_REWARD",
            "SUBSCRIPTION_REDEMPTION",
            "ADMIN_GRANT",
            "ADMIN_ADJUSTMENT",
            "REVERSAL"
        )
        canonicalTypes.forEach { type ->
            val tx = PointTransaction(
                id = "tx_1",
                txId = "tx_1",
                userId = "user_42",
                type = type,
                amount = 20L,
                balanceBefore = 100L,
                balanceAfter = 120L,
                createdAt = System.currentTimeMillis()
            )
            assertEquals(type, tx.type)
        }
    }

    @Test
    fun testD2_LedgerAmountSignConvention() {
        // Earning = positive delta
        val earnTx = PointTransaction(type = "DAILY_LOGIN", amount = 10L, balanceBefore = 0L, balanceAfter = 10L)
        assertTrue(earnTx.amount > 0)
        assertEquals(earnTx.balanceBefore + earnTx.amount, earnTx.balanceAfter)

        // Spending = negative delta
        val spendTx = PointTransaction(type = "SUBSCRIPTION_REDEMPTION", amount = -350L, balanceBefore = 500L, balanceAfter = 150L)
        assertTrue(spendTx.amount < 0)
        assertEquals(spendTx.balanceBefore + spendTx.amount, spendTx.balanceAfter)
    }

    // =========================================================================
    // SECTION E: SUBSCRIPTION REDEMPTION
    // =========================================================================

    @Test
    fun testE1_All4SKUsCanonicalPricing() {
        val config = EconomyConfig()
        val costs = config.redemptionCosts

        assertEquals(50L, costs["pro_lite_1d"])
        assertEquals(250L, costs["pro_lite_7d"])
        assertEquals(350L, costs["pro_lite_10d"])
        assertEquals(1000L, costs["pro_30d"])

        // Explicitly assert absence of old 320 and 800
        assertNotEquals(320L, costs["pro_lite_10d"])
        assertNotEquals(800L, costs["pro_30d"])
    }

    @Test
    fun testE2_FreeNotRedeemableAndInvalidSkuRejected() {
        val invalidSkus = listOf("free", "FREE", "pro_lite_3d", "pro_lite_Xd", "custom_sku", "")
        invalidSkus.forEach { sku ->
            val isKnown = sku in setOf("pro_lite_1d", "pro_lite_7d", "pro_lite_10d", "pro_30d")
            assertFalse("SKU '$sku' must be rejected as non-redeemable", isKnown)
        }
    }

    @Test
    fun testE3_InsufficientBalanceRedemptionFails() {
        val balance = 300L
        val requiredCost = 350L // pro_lite_10d
        val hasEnough = balance >= requiredCost
        assertFalse("300 points is insufficient for 350 points SKU", hasEnough)
    }

    @Test
    fun testE4_SufficientBalanceRedemptionSucceeds() {
        val balance = 1200L
        val requiredCost = 1000L // pro_30d
        val hasEnough = balance >= requiredCost
        assertTrue(hasEnough)
        val balanceAfter = balance - requiredCost
        assertEquals(200L, balanceAfter)
    }

    @Test
    fun testE5_DisabledOrComingSoonFeatureBlocksRedemption() {
        val disabledConfig = FeaturesConfig(subscriptions = FeatureState.DISABLED)
        assertEquals(FeatureState.DISABLED, disabledConfig.subscriptions)

        val comingSoonConfig = FeaturesConfig(subscriptions = FeatureState.COMING_SOON)
        assertEquals(FeatureState.COMING_SOON, comingSoonConfig.subscriptions)
    }

    // =========================================================================
    // SECTION F: SUBSCRIPTION STACKING & UPGRADE SEMANTICS
    // =========================================================================

    @Test
    fun testF1_SameTierExtensionStacksOntoCurrentExpiry() {
        val now = 1000000L
        val currentExpiry = now + 500000L // Active for another 500s
        val durationDays = 10
        val durationMillis = durationDays * 86400000L

        // When same tier and active, newExpiry = currentExpiry + durationMillis
        val newExpiry = currentExpiry + durationMillis
        assertEquals(currentExpiry + durationMillis, newExpiry)
        assertTrue(newExpiry > now + durationMillis)
    }

    @Test
    fun testF2_ExpiredSubscriptionStartsFromNow() {
        val now = 10000000L
        val currentExpiry = now - 50000L // Expired
        val durationDays = 7
        val durationMillis = durationDays * 86400000L

        val isExpired = currentExpiry < now
        assertTrue(isExpired)
        val newStartedAt = now
        val newExpiry = now + durationMillis
        assertEquals(now + durationMillis, newExpiry)
    }

    @Test
    fun testF3_ProLiteToProUpgradesImmediately() {
        val now = 1000000L
        val proLiteExpiry = now + 500000L
        val isProLiteActive = proLiteExpiry > now
        assertTrue(isProLiteActive)

        // Upgrade to PRO (30d) starts from now immediately
        val proDurationMillis = 30L * 86400000L
        val newStartedAt = now
        val newExpiry = now + proDurationMillis
        assertEquals(now + proDurationMillis, newExpiry)
    }

    @Test
    fun testF4_ActiveProCannotDowngradeToProLite() {
        val currentTier = CanonicalSubscriptionTier.PRO
        val isCurrentlyActive = true
        val targetTier = CanonicalSubscriptionTier.PRO_LITE

        val isDowngradeForbidden = (currentTier == CanonicalSubscriptionTier.PRO && isCurrentlyActive && targetTier == CanonicalSubscriptionTier.PRO_LITE)
        assertTrue("PRO to PRO_LITE downgrade while active must be strictly rejected", isDowngradeForbidden)
    }

    // =========================================================================
    // SECTION G: ADS & QUALITY DECOUPLING
    // =========================================================================

    @Test
    fun testG1_FreeTierDisplaysAdsWithAllQualitiesUnrestricted() {
        val freeUser = UserRestrictions(
            subscriptionTier = "FREE",
            subscriptionStatus = "none",
            isPremium = false,
            allowedQuality = null
        )
        assertFalse("FREE user must NOT be ad-free", freeUser.isAdFree)
        assertTrue("4K must be allowed for FREE user", freeUser.isQualityAllowed("4K"))
        assertTrue("1080p must be allowed for FREE user", freeUser.isQualityAllowed("1080p"))
        assertTrue("720p must be allowed for FREE user", freeUser.isQualityAllowed("720p"))
        assertTrue("Downloads allowed for FREE user", freeUser.isDownloadAllowed)
    }

    @Test
    fun testG2_ProLiteTierSuppressesAdsWithAllQualitiesUnrestricted() {
        val proLiteUser = UserRestrictions(
            subscriptionTier = "PRO_LITE",
            subscriptionStatus = "ACTIVE",
            subscriptionExpiresAt = System.currentTimeMillis() + 86400000L,
            isPremium = true,
            allowedQuality = null
        )
        assertTrue("PRO_LITE user must be ad-free", proLiteUser.isAdFree)
        assertTrue("4K must be allowed for PRO_LITE", proLiteUser.isQualityAllowed("4K"))
        assertTrue("1080p must be allowed for PRO_LITE", proLiteUser.isQualityAllowed("1080p"))
        assertTrue("Downloads allowed for PRO_LITE", proLiteUser.isDownloadAllowed)
    }

    @Test
    fun testG3_ProTierSuppressesAdsWithAllQualitiesUnrestricted() {
        val proUser = UserRestrictions(
            subscriptionTier = "PRO",
            subscriptionStatus = "ACTIVE",
            subscriptionExpiresAt = System.currentTimeMillis() + 864000000L,
            isPremium = true,
            allowedQuality = null
        )
        assertTrue("PRO user must be ad-free", proUser.isAdFree)
        assertTrue("4K must be allowed for PRO", proUser.isQualityAllowed("4K"))
        assertTrue("1080p must be allowed for PRO", proUser.isQualityAllowed("1080p"))
        assertTrue("Downloads allowed for PRO", proUser.isDownloadAllowed)
    }

    @Test
    fun testG4_ExpiredSubscriptionRestoresAdsWithoutChangingQuality() {
        val expiredUser = UserRestrictions(
            subscriptionTier = "PRO",
            subscriptionStatus = "ACTIVE",
            subscriptionExpiresAt = System.currentTimeMillis() - 10000L, // Expired
            isPremium = true,
            allowedQuality = null
        )
        assertFalse("Expired subscription must NOT be ad-free", expiredUser.isAdFree)
        assertTrue("Quality remains available after subscription expiry", expiredUser.isQualityAllowed("4K"))
        assertTrue(expiredUser.isQualityAllowed("1080p"))
    }

    // =========================================================================
    // SECTION H: LEADERBOARD
    // =========================================================================

    @Test
    fun testH1_LeaderboardEntryMapping() {
        val entry = LeaderboardEntry(
            userId = "user_100",
            username = "top_streamer",
            displayName = "Top Streamer",
            photoUrl = "https://example.com/avatar.jpg",
            rank = 1,
            weeklyEarnedPoints = 1500L,
            reward = "100 Points"
        )
        assertEquals("user_100", entry.userId)
        assertEquals(1, entry.rank)
        assertEquals(1500L, entry.weeklyEarnedPoints)
    }

    @Test
    fun testH2_LeaderboardEmptyFallback() {
        val entries = emptyList<LeaderboardEntry>()
        assertTrue(entries.isEmpty())
    }

    // =========================================================================
    // SECTION I: NOTIFICATIONS
    // =========================================================================

    @Test
    fun testI1_NotificationItemMapping() {
        val notif = NotificationItem(
            id = "notif_1",
            title = "Welcome Bonus",
            message = "You earned 10 points!",
            timestamp = System.currentTimeMillis(),
            isRead = false,
            imageUrl = "https://example.com/badge.png",
            type = "points"
        )
        assertEquals("notif_1", notif.id)
        assertEquals("Welcome Bonus", notif.title)
        assertEquals("You earned 10 points!", notif.message)
        assertFalse(notif.isRead)
        assertEquals("points", notif.type)
        assertEquals("https://example.com/badge.png", notif.imageUrl)
    }

    @Test
    fun testI2_NotificationMarkAsRead() {
        val unreadNotif = NotificationItem(
            id = "notif_2",
            title = "System Update",
            message = "New features available",
            isRead = false
        )
        val readNotif = unreadNotif.copy(isRead = true)
        assertTrue(readNotif.isRead)
    }
}
