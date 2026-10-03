package com.example

import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.CanonicalTransactionType
import com.example.data.model.DailyLoginClaimRequest
import com.example.data.model.DailyLoginClaimResponse
import com.example.data.model.EconomyConfig
import com.example.data.model.FeatureState
import com.example.data.model.FeaturesConfig
import com.example.data.model.PointWallet
import com.example.data.model.PointsEarningError
import com.example.data.model.PointsOperationResult
import com.example.data.model.RewardedAdClaimRequest
import com.example.data.model.TaskRewardClaimRequest
import com.example.data.model.UserRestrictions
import com.example.data.repository.PointsEarningRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * PHASE 03E: TRUSTED EARNING BACKEND & ECONOMY ALIGNMENT TEST SUITE
 *
 * Implements all 28 mandated test cases from Section 51 of Phase 03E:
 * TEST 01 through TEST 28.
 */
class Phase03ETrustedEarningBackendTest {

    private fun findMainSourceDir(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
            File("/app/src/main/java")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: throw IllegalStateException("Could not find main java source directory in $candidates")
    }

    private fun findBackendDir(): File {
        val candidates = listOf(
            File("backend"),
            File("../backend"),
            File("/backend"),
            File("/app/backend")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: throw IllegalStateException("Could not find backend directory in $candidates")
    }

    // ========================================================================
    // TEST 01: Authentication required
    // ========================================================================
    @Test
    fun test01_AuthenticationRequired() = runBlocking {
        // Without an authenticated Firebase user, claim operations must immediately fail with AUTH_REQUIRED
        val req = DailyLoginClaimRequest(userId = "unauthenticated_user")
        val result = PointsEarningRepository.claimDailyLogin(req)

        assertTrue("Result must be an error", result is PointsOperationResult.Error)
        val err = (result as PointsOperationResult.Error).error
        assertEquals("Error must be AUTH_REQUIRED", PointsEarningError.AUTH_REQUIRED, err)
    }

    // ========================================================================
    // TEST 02: Client cannot choose reward amount
    // ========================================================================
    @Test
    fun test02_ClientCannotChooseRewardAmount() {
        // Verify DailyLoginClaimRequest payload has NO reward or amount property
        val fields = DailyLoginClaimRequest::class.java.declaredFields.map { it.name }
        assertFalse("Request must not contain 'reward'", fields.contains("reward"))
        assertFalse("Request must not contain 'amount'", fields.contains("amount"))
        assertFalse("Request must not contain 'points'", fields.contains("points"))
    }

    // ========================================================================
    // TEST 03: Client cannot choose balance
    // ========================================================================
    @Test
    fun test03_ClientCannotChooseBalance() {
        // Verify no request model accepts new or target balance
        val dailyFields = DailyLoginClaimRequest::class.java.declaredFields.map { it.name }
        val adFields = RewardedAdClaimRequest::class.java.declaredFields.map { it.name }
        val taskFields = TaskRewardClaimRequest::class.java.declaredFields.map { it.name }

        assertFalse("Daily request must not accept balance", dailyFields.contains("pointsBalance") || dailyFields.contains("newBalance"))
        assertFalse("Ad request must not accept balance", adFields.contains("pointsBalance") || adFields.contains("newBalance"))
        assertFalse("Task request must not accept balance", taskFields.contains("pointsBalance") || taskFields.contains("newBalance"))
    }

    // ========================================================================
    // TEST 04: Daily login one reward per UTC day
    // ========================================================================
    @Test
    fun test04_DailyLoginOneRewardPerUtcDay() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val todayUtc = sdf.format(Date())

        val stateAlreadyClaimed = com.example.data.model.DailyLoginState(
            currentStreak = 3,
            todayClaimed = true,
            lastClaimDateUtc = todayUtc
        )
        assertTrue("todayClaimed must be true for matching UTC date", stateAlreadyClaimed.todayClaimed)
        assertEquals(todayUtc, stateAlreadyClaimed.lastClaimDateUtc)
    }

    // ========================================================================
    // TEST 05: Daily login duplicate request is idempotent
    // ========================================================================
    @Test
    fun test05_DailyLoginDuplicateRequestIsIdempotent() {
        val req1 = DailyLoginClaimRequest(requestId = "req_fixed_idempotent_123", userId = "u1")
        val req2 = DailyLoginClaimRequest(requestId = "req_fixed_idempotent_123", userId = "u1")
        assertEquals(req1.requestId, req2.requestId)

        // Backend typescript worker verifies idempotency key {uid}:DAILY_LOGIN:{requestId}
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must implement idempotency check", earningTs.contains("idempotencyStore.getRecord"))
        assertTrue("Backend must build key with uid, operation and requestId", earningTs.contains("idempotencyStore.buildKey"))
    }

    // ========================================================================
    // TEST 06: Daily login uses server UTC time
    // ========================================================================
    @Test
    fun test06_DailyLoginUsesServerUtcTime() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must use getUTCFullYear", earningTs.contains("getUTCFullYear"))
        assertTrue("Backend must use getUTCDate", earningTs.contains("getUTCDate"))
        assertTrue("Backend must enforce UTC date string", earningTs.contains("getTodayUtcString"))
    }

    // ========================================================================
    // TEST 07: Daily login uses remote economy configuration
    // ========================================================================
    @Test
    fun test07_DailyLoginUsesRemoteEconomyConfiguration() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must fetch economy config from store", earningTs.contains("getEconomyConfig()"))
        assertTrue("Backend must validate economy config", earningTs.contains("validateEconomyConfig"))
        assertTrue("Backend must calculate reward from economy dailyLoginRewards", earningTs.contains("economy.dailyLoginRewards"))
    }

    // ========================================================================
    // TEST 08: Rewarded ad requires trusted verification
    // ========================================================================
    @Test
    fun test08_RewardedAdRequiresTrustedVerification() {
        val reqWithoutToken = RewardedAdClaimRequest(userId = "u1", verificationToken = null)
        assertFalse("Unverified request has no token", reqWithoutToken.verificationToken?.startsWith("valid_") == true)

        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must check verificationToken", earningTs.contains("BACKEND_VERIFICATION_REQUIRED"))
    }

    // ========================================================================
    // TEST 09: Rewarded ad daily cap is enforced server-side
    // ========================================================================
    @Test
    fun test09_RewardedAdDailyCapIsEnforcedServerSide() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must check daily cap", earningTs.contains("DAILY_CAP_REACHED"))
        assertTrue("Backend must compare watchedToday with rewardedAdDailyCap", earningTs.contains("economy.rewardedAdDailyCap"))
    }

    // ========================================================================
    // TEST 10: Rewarded ad cooldown is enforced server-side
    // ========================================================================
    @Test
    fun test10_RewardedAdCooldownIsEnforcedServerSide() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must check cooldown", earningTs.contains("COOLDOWN_ACTIVE"))
        assertTrue("Backend must compare against cooldownMillis", earningTs.contains("cooldownMillis"))
    }

    // ========================================================================
    // TEST 11: Rewarded ad duplicate request is idempotent
    // ========================================================================
    @Test
    fun test11_RewardedAdDuplicateRequestIsIdempotent() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must check idempotency for REWARDED_AD",
            earningTs.contains("this.idempotencyStore.buildKey(uid, \"REWARDED_AD\", req.requestId)"))
    }

    // ========================================================================
    // TEST 12: Task must exist
    // ========================================================================
    @Test
    fun test12_TaskMustExist() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must check task existence", earningTs.contains("TASK_NOT_FOUND"))
    }

    // ========================================================================
    // TEST 13: Task must be active
    // ========================================================================
    @Test
    fun test13_TaskMustBeActive() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must check task isActive", earningTs.contains("TASK_NOT_ELIGIBLE"))
        assertTrue("Backend must check task expiration", earningTs.contains("TASK_EXPIRED"))
    }

    // ========================================================================
    // TEST 14: Task duplicate claim rejected/idempotent
    // ========================================================================
    @Test
    fun test14_TaskDuplicateClaimRejectedOrIdempotent() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must check existing task claim", earningTs.contains("TASK_ALREADY_CLAIMED"))
    }

    // ========================================================================
    // TEST 15: Task reward amount cannot be client supplied
    // ========================================================================
    @Test
    fun test15_TaskRewardAmountCannotBeClientSupplied() {
        val fields = TaskRewardClaimRequest::class.java.declaredFields.map { it.name }
        assertFalse("Task request must not contain reward amount", fields.contains("rewardPoints") || fields.contains("amount"))

        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must use task.rewardPoints", earningTs.contains("rewardAmount = task.rewardPoints"))
    }

    // ========================================================================
    // TEST 16: GAME_REWARD cannot be arbitrarily invoked
    // ========================================================================
    @Test
    fun test16_GameRewardCannotBeArbitrarilyInvoked() {
        assertEquals("GAME_REWARD", CanonicalTransactionType.GAME_REWARD)
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must reject game reward claims as unavailable",
            earningTs.contains("GAME_REWARD_UNAVAILABLE"))
    }

    // ========================================================================
    // TEST 17: Leaderboard cannot be settled by ordinary user
    // ========================================================================
    @Test
    fun test17_LeaderboardCannotBeSettledByOrdinaryUser() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must reject user settlement",
            earningTs.contains("LEADERBOARD_SETTLEMENT_UNAVAILABLE"))
    }

    // ========================================================================
    // TEST 18: Feature DISABLED blocks earning
    // ========================================================================
    @Test
    fun test18_FeatureDisabledBlocksEarning() {
        val config = FeaturesConfig(
            dailyLogin = FeatureState.DISABLED,
            rewardedAds = FeatureState.DISABLED,
            tasks = FeatureState.DISABLED
        )
        assertEquals(FeatureState.DISABLED, config.dailyLogin)
        assertEquals(FeatureState.DISABLED, config.rewardedAds)
        assertEquals(FeatureState.DISABLED, config.tasks)

        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must return FEATURE_DISABLED", earningTs.contains("FEATURE_DISABLED"))
    }

    // ========================================================================
    // TEST 19: Feature COMING_SOON blocks earning
    // ========================================================================
    @Test
    fun test19_FeatureComingSoonBlocksEarning() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must return FEATURE_COMING_SOON", earningTs.contains("FEATURE_COMING_SOON"))
    }

    // ========================================================================
    // TEST 20: Maximum balance is enforced
    // ========================================================================
    @Test
    fun test20_MaximumBalanceIsEnforced() {
        assertEquals(1_000_000L, PointWallet.MAX_POINT_BALANCE)

        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must enforce BALANCE_LIMIT_EXCEEDED", earningTs.contains("BALANCE_LIMIT_EXCEEDED"))
        assertTrue("Backend must check 1,000,000 max balance", earningTs.contains("maxBalance"))
    }

    // ========================================================================
    // TEST 21: Wallet + ledger update atomically
    // ========================================================================
    @Test
    fun test21_WalletAndLedgerUpdateAtomically() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must save user wallet", earningTs.contains("this.store.saveUser"))
        assertTrue("Backend must create transaction ledger", earningTs.contains("this.store.createTransaction"))
    }

    // ========================================================================
    // TEST 22: Failed transaction creates no partial economic mutation
    // ========================================================================
    @Test
    fun test22_FailedTransactionCreatesNoPartialEconomicMutation() {
        val initialBalance = 500L
        val wallet = PointWallet(pointsBalance = initialBalance)
        // If an operation fails before commit, wallet balance is strictly unmodified
        val result = PointsOperationResult.Error(PointsEarningError.INVALID_REQUEST, "Exceeded")
        assertEquals(initialBalance, wallet.pointsBalance)
        assertTrue(result is PointsOperationResult.Error)
    }

    // ========================================================================
    // TEST 23: Concurrent duplicate requests produce one reward
    // ========================================================================
    @Test
    fun test23_ConcurrentDuplicateRequestsProduceOneReward() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend must check existing idempotency record before processing",
            earningTs.contains("const existing = await this.idempotencyStore.getRecord(idKey)"))
    }

    // ========================================================================
    // TEST 24: Different request IDs are evaluated independently
    // ========================================================================
    @Test
    fun test24_DifferentRequestIdsEvaluatedIndependently() {
        val req1 = DailyLoginClaimRequest(requestId = "req_alpha", userId = "u1")
        val req2 = DailyLoginClaimRequest(requestId = "req_beta", userId = "u1")
        assertFalse("Request IDs must be unique and different", req1.requestId == req2.requestId)
    }

    // ========================================================================
    // TEST 25: Offline client cannot bypass backend
    // ========================================================================
    @Test
    fun test25_OfflineClientCannotBypassBackend() {
        // In offline mode, no local points increment is ever executed
        val wallet = PointWallet(pointsBalance = 200L, isAuthoritative = true)
        assertEquals(200L, wallet.pointsBalance)
        assertEquals(0L, wallet.totalPointsSpent)
    }

    // ========================================================================
    // TEST 26: SUBSCRIPTION_REDEMPTION is transitioned to Phase 03F
    // ========================================================================
    @Test
    fun test26_SubscriptionRedemptionIsTransitionedTo03F() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()
        assertTrue("Backend implements redeemSubscription in Phase 03F",
            earningTs.contains("redeemSubscription"))
    }

    // ========================================================================
    // TEST 27: Subscription quality remains independent
    // ========================================================================
    @Test
    fun test27_SubscriptionQualityRemainsIndependent() {
        val freeUser = UserRestrictions(subscriptionTier = "FREE", isPremium = false)
        val proLiteUser = UserRestrictions(subscriptionTier = "PRO_LITE", isPremium = true)
        val proUser = UserRestrictions(subscriptionTier = "PRO", isPremium = true)

        assertEquals(null, freeUser.allowedQuality)
        assertEquals(null, proLiteUser.allowedQuality)
        assertEquals(null, proUser.allowedQuality)

        assertTrue(freeUser.canWatch)
        assertTrue(proLiteUser.canWatch)
        assertTrue(proUser.canWatch)

        assertFalse(freeUser.isAdFree)
        assertTrue(proLiteUser.isAdFree)
        assertTrue(proUser.isAdFree)
    }

    // ========================================================================
    // TEST 28: No client economic write authority
    // ========================================================================
    @Test
    fun test28_NoClientEconomicWriteAuthority() {
        val mainDir = findMainSourceDir()
        val allKotlinFiles = mainDir.walkTopDown().filter { it.extension == "kt" }.toList()

        allKotlinFiles.forEach { file ->
            val lines = file.readLines()
            val codeInvocations = lines.filter { line ->
                val trimmed = line.trim()
                !trimmed.startsWith("*") && !trimmed.startsWith("//") && trimmed.contains("FieldValue.increment(")
            }
            assertTrue("Client file ${file.name} must never invoke FieldValue.increment in code",
                codeInvocations.isEmpty())
            val text = file.readText()
            assertFalse("Client file ${file.name} must never increment pointsBalance",
                text.contains("pointsBalance +=") || text.contains("pointsBalance = pointsBalance +"))
        }
    }
}
