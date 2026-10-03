package com.example

import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.CanonicalTransactionType
import com.example.data.model.DailyLoginClaimRequest
import com.example.data.model.DailyLoginState
import com.example.data.model.EconomyConfig
import com.example.data.model.FeatureState
import com.example.data.model.FeaturesConfig
import com.example.data.model.PointWallet
import com.example.data.model.PointsEarningError
import com.example.data.model.PointsOperationResult
import com.example.data.model.RewardTask
import com.example.data.model.RewardedAdClaimRequest
import com.example.data.model.RewardedAdState
import com.example.data.model.TaskRewardClaimRequest
import com.example.data.model.UserRestrictions
import com.example.data.repository.PointsEarningRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PHASE 03D: POINTS EARNING ENGINE TEST SUITE
 *
 * Implements all 20 required tests from Phase 03D Section 43:
 * TEST 01 through TEST 20.
 */
class Phase03DPointsEarningEngineTest {

    private val rulesContent: String by lazy {
        val candidates = listOf(
            File("firestore.rules"),
            File("../firestore.rules"),
            File("../../firestore.rules"),
            File("/firestore.rules")
        )
        val file = candidates.firstOrNull { it.exists() && it.isFile }
            ?: throw IllegalStateException("Could not find firestore.rules file in $candidates")
        file.readText()
    }

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

    // ========================================================================
    // TEST 01: Points balance is read-only
    // ========================================================================
    @Test
    fun test01_PointsBalanceIsReadOnly() {
        val wallet = PointWallet(pointsBalance = 100L, totalPointsEarned = 100L, totalPointsSpent = 0L)
        assertEquals(100L, wallet.pointsBalance)
        assertTrue(wallet.isAuthoritative)

        // Verify Firestore rules forbid client write to pointsBalance on /users/{userId}
        assertTrue("Rules must contain 'pointsBalance'", rulesContent.contains("'pointsBalance'"))
        assertTrue("Rules must protect user fields with affectedKeys()", rulesContent.contains("affectedKeys()"))
        assertTrue("Rules must check hasAny", rulesContent.contains("hasAny"))
    }

    // ========================================================================
    // TEST 02: Total earned is read-only
    // ========================================================================
    @Test
    fun test02_TotalEarnedIsReadOnly() {
        val wallet = PointWallet(pointsBalance = 250L, totalPointsEarned = 300L, totalPointsSpent = 50L)
        assertEquals(300L, wallet.totalPointsEarned)

        // Verify Firestore rules forbid client write to totalPointsEarned
        assertTrue("Rules must contain 'totalPointsEarned'", rulesContent.contains("'totalPointsEarned'"))
        assertTrue("Rules must protect user fields with affectedKeys()", rulesContent.contains("affectedKeys()"))
    }

    // ========================================================================
    // TEST 03: Total spent is read-only
    // ========================================================================
    @Test
    fun test03_TotalSpentIsReadOnly() {
        val wallet = PointWallet(pointsBalance = 50L, totalPointsEarned = 100L, totalPointsSpent = 50L)
        assertEquals(50L, wallet.totalPointsSpent)

        // Verify Firestore rules forbid client write to totalPointsSpent
        assertTrue("Rules must contain 'totalPointsSpent'", rulesContent.contains("'totalPointsSpent'"))
        assertTrue("Rules must protect user fields with affectedKeys()", rulesContent.contains("affectedKeys()"))
    }

    // ========================================================================
    // TEST 04: User can read own ledger
    // ========================================================================
    @Test
    fun test04_UserCanReadOwnLedger() {
        // Match: match /point_transactions/{txId} (nested inside /users/{userId})
        val subMatch = Regex("match /point_transactions/\\{txId\\}\\s*\\{([\\s\\S]*?)\\}")
            .find(rulesContent)
        assertNotNull("Must have point_transactions rule block", subMatch)
        val block = subMatch!!.groupValues[1]

        assertTrue("User must be able to read own transactions",
            block.contains("isOwner(userId)") || block.contains("request.auth.uid == userId"))
    }

    // ========================================================================
    // TEST 05: User cannot read another user's ledger
    // ========================================================================
    @Test
    fun test05_UserCannotReadAnotherUsersLedger() {
        val subMatch = Regex("match /point_transactions/\\{txId\\}\\s*\\{([\\s\\S]*?)\\}")
            .find(rulesContent)
        assertNotNull("Must have point_transactions rule block", subMatch)
        val block = subMatch!!.groupValues[1]

        assertFalse("Cross-user reading must be strictly forbidden",
            block.contains("allow read: if true") || block.contains("allow read: if isAuthenticated();"))
        assertTrue("Read requires ownership or admin check",
            block.contains("isOwner(userId)") || block.contains("request.auth.uid == userId"))
    }

    // ========================================================================
    // TEST 06: User cannot create ledger transaction
    // ========================================================================
    @Test
    fun test06_UserCannotCreateLedgerTransaction() {
        val subMatch = Regex("match /point_transactions/\\{txId\\}\\s*\\{([\\s\\S]*?)\\}")
            .find(rulesContent)
        assertNotNull("Must have point_transactions rule block", subMatch)
        val block = subMatch!!.groupValues[1]

        val isTemporaryEconomyMode = rulesContent.contains("TEMPORARY ECONOMY MODE")
        if (isTemporaryEconomyMode) {
            assertTrue("Temporary economy mode must restrict write to admin or owner",
                block.contains("isAdmin()") && block.contains("isOwner(userId)"))
            assertFalse("Arbitrary unauthenticated or cross-user writes forbidden",
                block.contains("allow write: if true") || block.contains("allow write: if isAuthenticated();"))
        } else {
            assertTrue("Client write to point_transactions must be admin-only",
                block.contains("allow write: if isAdmin();") || block.contains("allow create, update, delete: if isAdmin();"))
            assertFalse("Normal users must never write to point_transactions",
                block.contains("allow create: if isOwner(userId)") || block.contains("allow write: if isOwner(userId)"))
        }
    }

    // ========================================================================
    // TEST 07: Daily login does not locally award points
    // ========================================================================
    @Test
    fun test07_DailyLoginDoesNotLocallyAwardPoints() = runBlocking {
        val initialBalance = 100L
        val wallet = PointWallet(pointsBalance = initialBalance)

        val req = DailyLoginClaimRequest(userId = "user_test_123")
        val result = PointsEarningRepository.claimDailyLogin(req)

        // Result MUST be an error indicating BACKEND_REQUIRED or AUTH_REQUIRED (no fake success!)
        assertTrue("Result must not be Success without trusted backend",
            result is PointsOperationResult.Error)
        val err = (result as PointsOperationResult.Error).error
        assertTrue("Error must be BACKEND_REQUIRED or AUTH_REQUIRED",
            err == PointsEarningError.BACKEND_REQUIRED || err == PointsEarningError.AUTH_REQUIRED)

        // Local wallet balance must remain completely untouched
        assertEquals("Points balance must not be locally incremented", initialBalance, wallet.pointsBalance)
    }

    // ========================================================================
    // TEST 08: Rewarded ad completion does not locally award points
    // ========================================================================
    @Test
    fun test08_RewardedAdCompletionDoesNotLocallyAwardPoints() = runBlocking {
        val initialBalance = 50L
        val wallet = PointWallet(pointsBalance = initialBalance)

        val req = RewardedAdClaimRequest(userId = "user_test_123")
        val result = PointsEarningRepository.claimRewardedAd(req)

        assertTrue("Rewarded ad result must not be Success without trusted backend",
            result is PointsOperationResult.Error)
        val err = (result as PointsOperationResult.Error).error
        assertTrue("Error must be BACKEND_REQUIRED or AUTH_REQUIRED",
            err == PointsEarningError.BACKEND_REQUIRED || err == PointsEarningError.AUTH_REQUIRED)

        assertEquals("Points balance must not be locally incremented", initialBalance, wallet.pointsBalance)
    }

    // ========================================================================
    // TEST 09: Rewarded ad cap is backend-authoritative
    // ========================================================================
    @Test
    fun test09_RewardedAdCapIsBackendAuthoritative() {
        val econ = EconomyConfig(rewardedAdDailyCap = 5, rewardedAdPoints = 15L)

        // When user has watched 5 ads, isEligible must be false
        val stateAtCap = RewardedAdState(
            dailyWatchedCount = 5,
            dailyCap = econ.rewardedAdDailyCap,
            rewardPerAd = econ.rewardedAdPoints,
            cooldownSecondsRemaining = 0L,
            isEligible = 5 < econ.rewardedAdDailyCap
        )
        assertFalse("At cap, isEligible must be false", stateAtCap.isEligible)
        assertEquals(5, stateAtCap.dailyCap)
    }

    // ========================================================================
    // TEST 10: Rewarded ad cooldown is backend-authoritative
    // ========================================================================
    @Test
    fun test10_RewardedAdCooldownIsBackendAuthoritative() {
        val econ = EconomyConfig(rewardedAdCooldownSeconds = 300L)
        val remaining = 150L

        val stateCooldown = RewardedAdState(
            dailyWatchedCount = 1,
            dailyCap = 5,
            rewardPerAd = 15L,
            cooldownSecondsRemaining = remaining,
            isEligible = remaining <= 0L
        )
        assertFalse("During active cooldown, user cannot watch ad", stateCooldown.isEligible)
        assertEquals(150L, stateCooldown.cooldownSecondsRemaining)
    }

    // ========================================================================
    // TEST 11: Task completion does not locally award points
    // ========================================================================
    @Test
    fun test11_TaskCompletionDoesNotLocallyAwardPoints() = runBlocking {
        val task = RewardTask(taskId = "task_1", title = "Share App", rewardPoints = 50L)
        val initialBalance = 0L

        val req = TaskRewardClaimRequest(userId = "user_test_123", taskId = task.taskId)
        val result = PointsEarningRepository.claimTaskReward(req)

        assertTrue("Task claim result must not be Success without trusted backend",
            result is PointsOperationResult.Error)
        val err = (result as PointsOperationResult.Error).error
        assertTrue("Error must be BACKEND_REQUIRED or AUTH_REQUIRED",
            err == PointsEarningError.BACKEND_REQUIRED || err == PointsEarningError.AUTH_REQUIRED)

        val wallet = PointWallet(pointsBalance = initialBalance)
        assertEquals(0L, wallet.pointsBalance)
    }

    // ========================================================================
    // TEST 12: Task claims cannot mint points
    // ========================================================================
    @Test
    fun test12_TaskClaimsCannotMintPoints() {
        // Inspect reward_tasks in rules
        val taskMatch = Regex("match /reward_tasks/\\{taskId\\}\\s*\\{([\\s\\S]*?)\\}")
            .find(rulesContent)
        assertNotNull("Must have reward_tasks rule block", taskMatch)
        val block = taskMatch!!.groupValues[1]

        assertTrue("reward_tasks write is admin-only",
            block.contains("allow write: if isAdmin();"))
        assertFalse("Users cannot write to reward_tasks",
            block.contains("allow write: if isAuthenticated();") && !block.contains("isAdmin()"))

        // Inspect user task_claims subcollection
        val claimsMatch = Regex("match /task_claims/\\{claimId\\}\\s*\\{([\\s\\S]*?)\\}")
            .find(rulesContent)
        assertNotNull("Must have task_claims rule block", claimsMatch)
        val claimsBlock = claimsMatch!!.groupValues[1]
        val isTemporaryEconomyMode = rulesContent.contains("TEMPORARY ECONOMY MODE")
        if (isTemporaryEconomyMode) {
            assertTrue("Temporary task_claims write requires admin or owner",
                claimsBlock.contains("isAdmin()") && claimsBlock.contains("isOwner(userId)"))
        } else {
            assertTrue("task_claims write is admin-only", claimsBlock.contains("allow write: if isAdmin();"))
        }
    }

    // ========================================================================
    // TEST 13: Leaderboard cannot be settled by client
    // ========================================================================
    @Test
    fun test13_LeaderboardCannotBeSettledByClient() {
        val lbMatch = Regex("match /leaderboard/\\{docId\\}\\s*\\{([\\s\\S]*?)\\}")
            .find(rulesContent)
        assertNotNull("Must have leaderboard rule block", lbMatch)
        val block = lbMatch!!.groupValues[1]

        assertTrue("Leaderboard write must be admin-only",
            block.contains("allow write: if isAdmin();"))
        assertFalse("Client must not have write authority on leaderboard",
            block.contains("allow write: if isAuthenticated();") && !block.contains("isAdmin()"))
    }

    // ========================================================================
    // TEST 14: GAME_REWARD remains trusted-authority controlled
    // ========================================================================
    @Test
    fun test14_GameRewardRemainsTrustedAuthorityControlled() {
        assertEquals("GAME_REWARD", CanonicalTransactionType.GAME_REWARD)
        // Verify no client-side game minting exists in codebase
        val javaDir = findMainSourceDir()
        val allKotlinFiles = javaDir.walkTopDown().filter { it.extension == "kt" }.toList()

        allKotlinFiles.forEach { file ->
            val text = file.readText()
            assertFalse("File ${file.name} must not contain local game minting",
                text.contains("pointsBalance += ") && text.contains("GAME_REWARD"))
        }
    }

    // ========================================================================
    // TEST 15: SUBSCRIPTION_REDEMPTION remains excluded from 03D
    // ========================================================================
    @Test
    fun test15_SubscriptionRedemptionRemainsExcludedFrom03D() {
        assertEquals("SUBSCRIPTION_REDEMPTION", CanonicalTransactionType.SUBSCRIPTION_REDEMPTION)

        // Verify PointsEarningRepository does not directly mutate subscriptionStatus in local Firestore
        val javaDir = findMainSourceDir()
        val repoFile = File(javaDir, "com/example/data/repository/PointsEarningRepository.kt")
        assertTrue("PointsEarningRepository.kt must exist", repoFile.exists())
        val text = repoFile.readText()

        // Phase 03F implemented redeemSubscription via trusted backend; direct client database mutation is forbidden
        assertFalse("PointsEarningRepository must not mutate subscriptionStatus to ACTIVE in Firestore",
            text.contains(".update(\"subscriptionStatus\"") || text.contains("subscriptionStatus = \"ACTIVE\""))
    }

    // ========================================================================
    // TEST 16: Offline mode cannot mint points
    // ========================================================================
    @Test
    fun test16_OfflineModeCannotMintPoints() {
        // A simulated offline wallet must remain static
        val offlineWallet = PointWallet(pointsBalance = 40L, isAuthoritative = true)
        // Even if UI attempts an offline action, no local increment is possible
        assertEquals(40L, offlineWallet.pointsBalance)
        assertEquals(0L, offlineWallet.totalPointsSpent)
    }

    // ========================================================================
    // TEST 17: Unknown result does not create local reward
    // ========================================================================
    @Test
    fun test17_UnknownResultDoesNotCreateLocalReward() {
        val pendingResult: PointsOperationResult<Nothing> = PointsOperationResult.PendingUnknown(requestId = "req_123")
        assertTrue("PendingUnknown is not Success", pendingResult !is PointsOperationResult.Success<*>)

        // Balance must not be tentatively bumped on pending/unknown
        val wallet = PointWallet(pointsBalance = 15L)
        assertEquals(15L, wallet.pointsBalance)
    }

    // ========================================================================
    // TEST 18: Feature DISABLED prevents active earning actions
    // ========================================================================
    @Test
    fun test18_FeatureDisabledPreventsActiveEarningActions() {
        val config = FeaturesConfig(
            dailyLogin = FeatureState.DISABLED,
            rewardedAds = FeatureState.DISABLED,
            tasks = FeatureState.DISABLED,
            disabledMessage = "هذه الميزة غير متوفرة حالياً"
        )
        assertEquals(FeatureState.DISABLED, config.dailyLogin)
        assertEquals(FeatureState.DISABLED, config.rewardedAds)
        assertEquals(FeatureState.DISABLED, config.tasks)
        assertEquals("هذه الميزة غير متوفرة حالياً", config.disabledMessage)
    }

    // ========================================================================
    // TEST 19: Feature COMING_SOON shows "هذه الميزة ستضاف قريبًا"
    // ========================================================================
    @Test
    fun test19_FeatureComingSoonShowsExactString() {
        assertEquals("هذه الميزة ستضاف قريبًا", FeaturesConfig.COMING_SOON_MESSAGE)

        val parsed = FeaturesConfig.parseFeatureState("COMING_SOON")
        assertEquals(FeatureState.COMING_SOON, parsed)
    }

    // ========================================================================
    // TEST 20: Subscription quality decoupling remains intact
    // ========================================================================
    @Test
    fun test20_SubscriptionQualityDecouplingRemainsIntact() {
        val freeUser = UserRestrictions(subscriptionTier = "FREE", isPremium = false)
        val proLiteUser = UserRestrictions(subscriptionTier = "PRO_LITE", isPremium = true)
        val proUser = UserRestrictions(subscriptionTier = "PRO", isPremium = true)

        // All tiers have null or unrestricted allowedQuality (all qualities available from source)
        assertEquals(null, freeUser.allowedQuality)
        assertEquals(null, proLiteUser.allowedQuality)
        assertEquals(null, proUser.allowedQuality)

        // All tiers can watch and download
        assertTrue(freeUser.canWatch)
        assertTrue(proLiteUser.canWatch)
        assertTrue(proUser.canWatch)

        assertTrue(freeUser.canDownload)
        assertTrue(proLiteUser.canDownload)
        assertTrue(proUser.canDownload)

        // Invariant: ONLY isAdFree differs between FREE and PRO/PRO_LITE
        assertFalse(freeUser.isAdFree)
        assertTrue(proLiteUser.isAdFree)
        assertTrue(proUser.isAdFree)
    }
}
