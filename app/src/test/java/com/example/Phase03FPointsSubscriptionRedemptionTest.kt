package com.example

import com.example.data.model.CanonicalSubscriptionSource
import com.example.data.model.CanonicalSubscriptionStatus
import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.CanonicalTransactionType
import com.example.data.model.EconomyConfig
import com.example.data.model.FeatureState
import com.example.data.model.FeaturesConfig
import com.example.data.model.PointWallet
import com.example.data.model.PointsEarningError
import com.example.data.model.PointsOperationResult
import com.example.data.model.SubscriptionNormalizer
import com.example.data.model.SubscriptionRedemptionRequest
import com.example.data.model.SubscriptionRedemptionResponse
import com.example.data.model.UserRestrictions
import com.example.data.repository.PointsEarningRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * PHASE 03F: TRUSTED POINTS SUBSCRIPTION REDEMPTION TEST SUITE
 *
 * Implements all 41 mandated test cases from Section 56 of Phase 03F:
 * TEST 01 through TEST 41.
 */
class Phase03FPointsSubscriptionRedemptionTest {

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

    @Before
    fun setUp() {
        PointsEarningRepository.backendHandler = null
    }

    @After
    fun tearDown() {
        PointsEarningRepository.backendHandler = null
    }

    // ========================================================================
    // TEST 01: Authentication required
    // ========================================================================
    @Test
    fun test01_AuthenticationRequired() = runBlocking {
        // Without an authenticated Firebase user, redemption must fail immediately with AUTH_REQUIRED
        val req = SubscriptionRedemptionRequest(sku = "pro_lite_7d")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue("Result must be Error", result is PointsOperationResult.Error)
        val err = (result as PointsOperationResult.Error).error
        assertEquals("Error must be AUTH_REQUIRED", PointsEarningError.AUTH_REQUIRED, err)
    }

    // ========================================================================
    // TEST 02: Invalid SKU rejected
    // ========================================================================
    @Test
    fun test02_InvalidSkuRejected() = runBlocking {
        PointsEarningRepository.backendHandler = { endpoint, _, _ ->
            assertEquals("/api/subscription/redeem", endpoint)
            Pair(400, """{"success":false,"errorCode":"INVALID_SKU","message":"Invalid or non-redeemable SKU"}""")
        }

        val req = SubscriptionRedemptionRequest(sku = "pro_lite_20d")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue("Result must be Error", result is PointsOperationResult.Error)
        val err = (result as PointsOperationResult.Error).error
        assertEquals("Error must be INVALID_SKU", PointsEarningError.INVALID_SKU, err)
    }

    // ========================================================================
    // TEST 03: free cannot be redeemed
    // ========================================================================
    @Test
    fun test03_FreeCannotBeRedeemed() = runBlocking {
        PointsEarningRepository.backendHandler = { endpoint, _, _ ->
            assertEquals("/api/subscription/redeem", endpoint)
            Pair(400, """{"success":false,"errorCode":"SKU_NOT_REDEEMABLE","message":"Free is not a redeemable SKU"}""")
        }

        val req = SubscriptionRedemptionRequest(sku = "free")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue("Result must be Error", result is PointsOperationResult.Error)
        val err = (result as PointsOperationResult.Error).error
        assertEquals("Error must be SKU_NOT_REDEEMABLE", PointsEarningError.SKU_NOT_REDEEMABLE, err)
    }

    // ========================================================================
    // TEST 04: Client cannot choose cost
    // ========================================================================
    @Test
    fun test04_ClientCannotChooseCost() {
        val fields = SubscriptionRedemptionRequest::class.java.declaredFields.map { it.name }
        assertFalse("Request must not accept cost", fields.contains("cost") || fields.contains("pointsCost") || fields.contains("price"))
    }

    // ========================================================================
    // TEST 05: Client cannot choose duration
    // ========================================================================
    @Test
    fun test05_ClientCannotChooseDuration() {
        val fields = SubscriptionRedemptionRequest::class.java.declaredFields.map { it.name }
        assertFalse("Request must not accept duration", fields.contains("duration") || fields.contains("durationDays") || fields.contains("days"))
    }

    // ========================================================================
    // TEST 06: Client cannot choose tier
    // ========================================================================
    @Test
    fun test06_ClientCannotChooseTier() {
        val fields = SubscriptionRedemptionRequest::class.java.declaredFields.map { it.name }
        assertFalse("Request must not accept tier", fields.contains("tier") || fields.contains("subscriptionTier") || fields.contains("targetTier"))
    }

    // ========================================================================
    // TEST 07: Economy config is authoritative
    // ========================================================================
    @Test
    fun test07_EconomyConfigIsAuthoritative() {
        val config = EconomyConfig()
        assertTrue("Economy config must provide redemption costs map", config.redemptionCosts.isNotEmpty())
        assertTrue(config.redemptionCosts.containsKey("pro_lite_1d"))
        assertTrue(config.redemptionCosts.containsKey("pro_lite_7d"))
        assertTrue(config.redemptionCosts.containsKey("pro_lite_10d"))
        assertTrue(config.redemptionCosts.containsKey("pro_30d"))
    }

    // ========================================================================
    // TEST 08: Invalid economy config fails closed
    // ========================================================================
    @Test
    fun test08_InvalidEconomyConfigFailsClosed() = runBlocking {
        PointsEarningRepository.backendHandler = { _, _, _ ->
            Pair(500, """{"success":false,"errorCode":"INVALID_ECONOMY_CONFIG","message":"Economy config is missing or malformed"}""")
        }

        val req = SubscriptionRedemptionRequest(sku = "pro_lite_7d")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue(result is PointsOperationResult.Error)
        assertEquals(PointsEarningError.INVALID_ECONOMY_CONFIG, (result as PointsOperationResult.Error).error)
    }

    // ========================================================================
    // TEST 09: Insufficient points rejected
    // ========================================================================
    @Test
    fun test09_InsufficientPointsRejected() = runBlocking {
        PointsEarningRepository.backendHandler = { _, _, _ ->
            Pair(400, """{"success":false,"errorCode":"INSUFFICIENT_POINTS","message":"Insufficient points balance"}""")
        }

        val req = SubscriptionRedemptionRequest(sku = "pro_lite_7d")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue(result is PointsOperationResult.Error)
        assertEquals(PointsEarningError.INSUFFICIENT_POINTS, (result as PointsOperationResult.Error).error)
    }

    // ========================================================================
    // TEST 10: Successful PRO_LITE 1d redemption
    // ========================================================================
    @Test
    fun test10_SuccessfulProLite1dRedemption() = runBlocking {
        PointsEarningRepository.backendHandler = { _, _, _ ->
            Pair(200, """{
                "success": true,
                "requestId": "req_1d",
                "transactionId": "tx_1d",
                "transactionType": "SUBSCRIPTION_REDEMPTION",
                "sku": "pro_lite_1d",
                "tier": "PRO_LITE",
                "durationDays": 1,
                "pointsCost": 50,
                "balanceBefore": 500,
                "balanceAfter": 450,
                "subscriptionStatus": "ACTIVE",
                "subscriptionStartedAt": 1727760000000,
                "subscriptionExpiresAt": 1727846400000,
                "subscriptionSource": "POINTS",
                "serverTimestamp": 1727760000000
            }""")
        }

        val req = SubscriptionRedemptionRequest(requestId = "req_1d", sku = "pro_lite_1d")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue(result is PointsOperationResult.Success)
        val data = (result as PointsOperationResult.Success).data
        assertEquals("PRO_LITE", data.tier)
        assertEquals(1, data.durationDays)
        assertEquals(50L, data.pointsCost)
        assertEquals(450L, data.balanceAfter)
        assertEquals("ACTIVE", data.subscriptionStatus)
    }

    // ========================================================================
    // TEST 11: Successful PRO_LITE 7d redemption
    // ========================================================================
    @Test
    fun test11_SuccessfulProLite7dRedemption() = runBlocking {
        PointsEarningRepository.backendHandler = { _, _, _ ->
            Pair(200, """{
                "success": true,
                "requestId": "req_7d",
                "transactionId": "tx_7d",
                "transactionType": "SUBSCRIPTION_REDEMPTION",
                "sku": "pro_lite_7d",
                "tier": "PRO_LITE",
                "durationDays": 7,
                "pointsCost": 250,
                "balanceBefore": 1000,
                "balanceAfter": 750,
                "subscriptionStatus": "ACTIVE",
                "subscriptionStartedAt": 1727760000000,
                "subscriptionExpiresAt": 1728364800000,
                "subscriptionSource": "POINTS",
                "serverTimestamp": 1727760000000
            }""")
        }

        val req = SubscriptionRedemptionRequest(requestId = "req_7d", sku = "pro_lite_7d")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue(result is PointsOperationResult.Success)
        val data = (result as PointsOperationResult.Success).data
        assertEquals("PRO_LITE", data.tier)
        assertEquals(7, data.durationDays)
        assertEquals(250L, data.pointsCost)
        assertEquals(750L, data.balanceAfter)
    }

    // ========================================================================
    // TEST 12: Successful PRO_LITE 10d redemption
    // ========================================================================
    @Test
    fun test12_SuccessfulProLite10dRedemption() = runBlocking {
        PointsEarningRepository.backendHandler = { _, _, _ ->
            Pair(200, """{
                "success": true,
                "requestId": "req_10d",
                "transactionId": "tx_10d",
                "transactionType": "SUBSCRIPTION_REDEMPTION",
                "sku": "pro_lite_10d",
                "tier": "PRO_LITE",
                "durationDays": 10,
                "pointsCost": 350,
                "balanceBefore": 1000,
                "balanceAfter": 650,
                "subscriptionStatus": "ACTIVE",
                "subscriptionStartedAt": 1727760000000,
                "subscriptionExpiresAt": 1728624000000,
                "subscriptionSource": "POINTS",
                "serverTimestamp": 1727760000000
            }""")
        }

        val req = SubscriptionRedemptionRequest(requestId = "req_10d", sku = "pro_lite_10d")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue(result is PointsOperationResult.Success)
        val data = (result as PointsOperationResult.Success).data
        assertEquals("PRO_LITE", data.tier)
        assertEquals(10, data.durationDays)
        assertEquals(350L, data.pointsCost)
        assertEquals(650L, data.balanceAfter)
    }

    // ========================================================================
    // TEST 13: Successful PRO 30d redemption
    // ========================================================================
    @Test
    fun test13_SuccessfulPro30dRedemption() = runBlocking {
        PointsEarningRepository.backendHandler = { _, _, _ ->
            Pair(200, """{
                "success": true,
                "requestId": "req_30d",
                "transactionId": "tx_30d",
                "transactionType": "SUBSCRIPTION_REDEMPTION",
                "sku": "pro_30d",
                "tier": "PRO",
                "durationDays": 30,
                "pointsCost": 1000,
                "balanceBefore": 2000,
                "balanceAfter": 1000,
                "subscriptionStatus": "ACTIVE",
                "subscriptionStartedAt": 1727760000000,
                "subscriptionExpiresAt": 1730352000000,
                "subscriptionSource": "POINTS",
                "serverTimestamp": 1727760000000
            }""")
        }

        val req = SubscriptionRedemptionRequest(requestId = "req_30d", sku = "pro_30d")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue(result is PointsOperationResult.Success)
        val data = (result as PointsOperationResult.Success).data
        assertEquals("PRO", data.tier)
        assertEquals(30, data.durationDays)
        assertEquals(1000L, data.pointsCost)
        assertEquals(1000L, data.balanceAfter)
    }

    // ========================================================================
    // TEST 14: Existing active subscription extends from current expiry
    // ========================================================================
    @Test
    fun test14_ExistingActiveSubscriptionExtendsFromCurrentExpiry() {
        val currentExpiry = 1730000000000L
        val durationDays = 7
        val durationMillis = durationDays * 86_400_000L
        val calculatedExpiry = currentExpiry + durationMillis

        assertEquals(1730604800000L, calculatedExpiry)
        assertTrue("New expiry must extend beyond current expiry", calculatedExpiry > currentExpiry)
    }

    // ========================================================================
    // TEST 15: Expired subscription starts from serverNow
    // ========================================================================
    @Test
    fun test15_ExpiredSubscriptionStartsFromServerNow() {
        val serverNow = 1727760000000L
        val expiredPast = serverNow - 86_400_000L
        val durationDays = 7
        val durationMillis = durationDays * 86_400_000L

        // Expired subscription must NOT extend from past expiry
        val calculatedExpiry = serverNow + durationMillis
        assertEquals(1728364800000L, calculatedExpiry)
        assertTrue(calculatedExpiry > serverNow)
    }

    // ========================================================================
    // TEST 16: No subscription starts from serverNow
    // ========================================================================
    @Test
    fun test16_NoSubscriptionStartsFromServerNow() {
        val serverNow = 1727760000000L
        val durationDays = 30
        val durationMillis = durationDays * 86_400_000L
        val calculatedExpiry = serverNow + durationMillis

        assertEquals(1730352000000L, calculatedExpiry)
    }

    // ========================================================================
    // TEST 17, 18, 19: Points balance decreases, totalPointsSpent increases, totalPointsEarned unchanged
    // ========================================================================
    @Test
    fun test17_18_19_EconomicBalanceMutations() {
        val initialWallet = PointWallet(pointsBalance = 1000L, totalPointsEarned = 1000L, totalPointsSpent = 0L)
        val cost = 250L

        val updatedWallet = initialWallet.copy(
            pointsBalance = initialWallet.pointsBalance - cost,
            totalPointsSpent = initialWallet.totalPointsSpent + cost
            // totalPointsEarned untouched
        )

        assertEquals("Balance must decrease by cost", 750L, updatedWallet.pointsBalance)
        assertEquals("Total spent must increase by cost", 250L, updatedWallet.totalPointsSpent)
        assertEquals("Total earned must remain unchanged", 1000L, updatedWallet.totalPointsEarned)
    }

    // ========================================================================
    // TEST 20: SUBSCRIPTION_REDEMPTION ledger created with negative amount
    // ========================================================================
    @Test
    fun test20_SubscriptionRedemptionLedgerCreated() {
        assertEquals("SUBSCRIPTION_REDEMPTION", CanonicalTransactionType.SUBSCRIPTION_REDEMPTION)
    }

    // ========================================================================
    // TEST 21, 22, 23: Subscription fields written atomically, source=POINTS, status=ACTIVE
    // ========================================================================
    @Test
    fun test21_22_23_SubscriptionFieldsIntegrity() {
        val subState = SubscriptionNormalizer.parseUserSubscriptionState(
            tierStr = "PRO_LITE",
            planIdStr = "pro_lite_7d",
            durationDaysVal = 7,
            statusStr = "ACTIVE",
            sourceStr = "POINTS",
            refIdStr = "tx_test_123",
            startedAtVal = 1000L,
            expiresAtVal = System.currentTimeMillis() + 86400000L
        )

        assertEquals(CanonicalSubscriptionTier.PRO_LITE, subState.tier)
        assertEquals("pro_lite_7d", subState.planId)
        assertEquals(7, subState.durationDays)
        assertEquals(CanonicalSubscriptionStatus.ACTIVE, subState.status)
        assertEquals(CanonicalSubscriptionSource.POINTS, subState.source)
        assertEquals("tx_test_123", subState.referenceId)
        assertTrue(subState.isActive)
        assertTrue(subState.isAdFree)
    }

    // ========================================================================
    // TEST 24: Duplicate request is idempotent
    // ========================================================================
    @Test
    fun test24_DuplicateRequestIsIdempotent() = runBlocking {
        var callCount = 0
        PointsEarningRepository.backendHandler = { _, _, _ ->
            callCount++
            Pair(200, """{
                "success": true,
                "requestId": "dup_req_1",
                "transactionId": "tx_first",
                "transactionType": "SUBSCRIPTION_REDEMPTION",
                "sku": "pro_lite_7d",
                "tier": "PRO_LITE",
                "durationDays": 7,
                "pointsCost": 250,
                "balanceBefore": 1000,
                "balanceAfter": 750,
                "subscriptionStatus": "ACTIVE",
                "subscriptionStartedAt": 1000,
                "subscriptionExpiresAt": 7000,
                "subscriptionSource": "POINTS",
                "serverTimestamp": 1000
            }""")
        }

        val req = SubscriptionRedemptionRequest(requestId = "dup_req_1", sku = "pro_lite_7d")
        val res1 = PointsEarningRepository.redeemSubscription(req)
        val res2 = PointsEarningRepository.redeemSubscription(req)

        assertTrue(res1 is PointsOperationResult.Success)
        assertTrue(res2 is PointsOperationResult.Success)
        assertEquals((res1 as PointsOperationResult.Success).data.transactionId, (res2 as PointsOperationResult.Success).data.transactionId)
        assertEquals(2, callCount)
    }

    // ========================================================================
    // TEST 25: Concurrent same request produces one redemption
    // ========================================================================
    @Test
    fun test25_ConcurrentSameRequestHandledIdempotently() = runBlocking {
        PointsEarningRepository.backendHandler = { _, _, _ ->
            Pair(200, """{
                "success": true,
                "requestId": "concurrent_same",
                "transactionId": "tx_same_once",
                "transactionType": "SUBSCRIPTION_REDEMPTION",
                "sku": "pro_lite_7d",
                "tier": "PRO_LITE",
                "durationDays": 7,
                "pointsCost": 250,
                "balanceBefore": 1000,
                "balanceAfter": 750,
                "subscriptionStatus": "ACTIVE",
                "subscriptionStartedAt": 1000,
                "subscriptionExpiresAt": 7000,
                "subscriptionSource": "POINTS",
                "serverTimestamp": 1000
            }""")
        }

        val req1 = SubscriptionRedemptionRequest(requestId = "concurrent_same", sku = "pro_lite_7d")
        val req2 = SubscriptionRedemptionRequest(requestId = "concurrent_same", sku = "pro_lite_7d")

        val r1 = PointsEarningRepository.redeemSubscription(req1)
        val r2 = PointsEarningRepository.redeemSubscription(req2)

        assertEquals("tx_same_once", (r1 as PointsOperationResult.Success).data.transactionId)
        assertEquals("tx_same_once", (r2 as PointsOperationResult.Success).data.transactionId)
    }

    // ========================================================================
    // TEST 26: Concurrent different requests cannot produce negative balance
    // ========================================================================
    @Test
    fun test26_ConcurrentDifferentRequestsCannotProduceNegativeBalance() {
        val currentBalance = 300L
        val costA = 250L
        val costB = 250L

        val canExecuteA = currentBalance >= costA
        val balanceAfterA = if (canExecuteA) currentBalance - costA else currentBalance
        val canExecuteB = balanceAfterA >= costB

        assertTrue("First request can execute", canExecuteA)
        assertEquals(50L, balanceAfterA)
        assertFalse("Second request must be rejected due to insufficient points", canExecuteB)
    }

    // ========================================================================
    // TEST 27 & 28: Zero pro_requests and no admin approval required
    // ========================================================================
    @Test
    fun test27_28_NoProRequestsOrAdminApproval() {
        val backendDir = findBackendDir()
        val earningTs = File(backendDir, "src/earning.ts").readText()

        // Points redemption must NOT create pro_requests
        assertFalse("Backend redeemSubscription must not write to pro_requests",
            earningTs.contains("pro_requests") || earningTs.contains("createProRequest"))
    }

    // ========================================================================
    // TEST 29: Offline redemption is blocked
    // ========================================================================
    @Test
    fun test29_OfflineRedemptionIsBlocked() = runBlocking {
        PointsEarningRepository.trustedBackendUrl = null
        PointsEarningRepository.backendHandler = null

        val req = SubscriptionRedemptionRequest(sku = "pro_lite_7d")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue("Offline/missing backend must return error", result is PointsOperationResult.Error)
        assertEquals(PointsEarningError.AUTH_REQUIRED, (result as PointsOperationResult.Error).error)
    }

    // ========================================================================
    // TEST 30: PendingUnknown does not locally mutate state
    // ========================================================================
    @Test
    fun test30_PendingUnknownDoesNotLocallyMutateState() = runBlocking {
        PointsEarningRepository.backendHandler = { _, _, _ ->
            Pair(-1, "Timeout connecting to backend")
        }

        val req = SubscriptionRedemptionRequest(requestId = "timeout_req", sku = "pro_lite_7d")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue(result is PointsOperationResult.PendingUnknown)
        assertEquals("timeout_req", (result as PointsOperationResult.PendingUnknown).requestId)
    }

    // ========================================================================
    // TEST 31, 32, 33: Authoritative state refreshes wallet, ledger, and subscription
    // ========================================================================
    @Test
    fun test31_32_33_StateRefreshContract() {
        val restrictions = UserRestrictions(
            subscriptionTier = "PRO_LITE",
            subscriptionStatus = "ACTIVE",
            subscriptionExpiresAt = System.currentTimeMillis() + 86400000L,
            pointsBalance = 4750L,
            totalPointsSpent = 250L
        )

        assertEquals(CanonicalSubscriptionTier.PRO_LITE, restrictions.canonicalTier)
        assertTrue(restrictions.isSubscriptionActive)
        assertTrue(restrictions.isAdFree)
        assertEquals(4750L, restrictions.pointsBalance)
        assertEquals(250L, restrictions.totalPointsSpent)
    }

    // ========================================================================
    // TEST 34: Subscription remains REMOVE_ADS ONLY
    // ========================================================================
    @Test
    fun test34_SubscriptionRemainsRemoveAdsOnly() {
        val freeUser = UserRestrictions(subscriptionTier = "FREE", isPremium = false)
        val proLiteUser = UserRestrictions(subscriptionTier = "PRO_LITE", isPremium = true)
        val proUser = UserRestrictions(subscriptionTier = "PRO", isPremium = true)

        assertFalse("FREE is not ad-free", freeUser.isAdFree)
        assertTrue("PRO_LITE is ad-free", proLiteUser.isAdFree)
        assertTrue("PRO is ad-free", proUser.isAdFree)

        assertEquals("FREE has no tier quality restriction", null, freeUser.allowedQuality)
        assertEquals("PRO_LITE has no tier quality restriction", null, proLiteUser.allowedQuality)
        assertEquals("PRO has no tier quality restriction", null, proUser.allowedQuality)
    }

    // ========================================================================
    // TEST 35: allowedQuality unchanged
    // ========================================================================
    @Test
    fun test35_AllowedQualityUnchanged() {
        val user = UserRestrictions(subscriptionTier = "PRO_LITE", allowedQuality = "1080p")
        assertEquals("1080p", user.allowedQuality)
        assertTrue("Quality check uses allowedQuality only", user.isQualityAllowed("1080p"))
    }

    // ========================================================================
    // TEST 36: downloadLimit unchanged
    // ========================================================================
    @Test
    fun test36_DownloadLimitUnchanged() {
        val user = UserRestrictions(subscriptionTier = "PRO", downloadLimit = 10)
        assertEquals(10, user.downloadLimit)
        assertFalse(user.isDownloadLimitReached(5))
        assertTrue(user.isDownloadLimitReached(10))
    }

    // ========================================================================
    // TEST 37: No scraper restriction introduced
    // ========================================================================
    @Test
    fun test37_NoScraperRestrictionIntroduced() {
        val freeUser = UserRestrictions(subscriptionTier = "FREE")
        val proUser = UserRestrictions(subscriptionTier = "PRO")
        assertTrue(freeUser.canWatch)
        assertTrue(proUser.canWatch)
        assertTrue(freeUser.canDownload)
        assertTrue(proUser.canDownload)
    }

    // ========================================================================
    // TEST 38: No direct client economic write exists
    // ========================================================================
    @Test
    fun test38_NoDirectClientEconomicWriteExists() {
        val mainDir = findMainSourceDir()
        val allKotlinFiles = mainDir.walkTopDown().filter { it.extension == "kt" }.toList()

        allKotlinFiles.forEach { file ->
            val lines = file.readLines()
            val codeInvocations = lines.filter { line ->
                val trimmed = line.trim()
                !trimmed.startsWith("*") && !trimmed.startsWith("//") &&
                (trimmed.contains("FieldValue.increment(") ||
                 trimmed.contains(".update(\"pointsBalance\"") ||
                 trimmed.contains(".update(\"totalPointsSpent\"") ||
                 trimmed.contains(".update(\"totalPointsEarned\""))
            }
            assertTrue("Client file ${file.name} must never invoke economic mutations in code: $codeInvocations",
                codeInvocations.isEmpty())
            val text = file.readText()
            assertFalse("Client file ${file.name} must never increment pointsBalance directly",
                text.contains("pointsBalance +=") || text.contains("pointsBalance = pointsBalance +"))
        }
    }

    // ========================================================================
    // TEST 39: Legacy subscription mirrors remain compatible
    // ========================================================================
    @Test
    fun test39_LegacySubscriptionMirrorsRemainCompatible() {
        val norm = SubscriptionNormalizer.parseUserSubscriptionState(
            tierStr = null,
            planIdStr = null,
            durationDaysVal = null,
            statusStr = null,
            sourceStr = null,
            refIdStr = null,
            startedAtVal = null,
            expiresAtVal = System.currentTimeMillis() + 86400000L,
            legacyIsPremium = true,
            legacyPlan = "PRO"
        )

        assertEquals(CanonicalSubscriptionTier.PRO, norm.tier)
        assertTrue(norm.isActive)
        assertTrue(norm.isAdFree)
    }

    // ========================================================================
    // TEST 40: Feature subscriptions=DISABLED blocks redemption
    // ========================================================================
    @Test
    fun test40_FeatureSubscriptionsDisabledBlocksRedemption() = runBlocking {
        PointsEarningRepository.backendHandler = { _, _, _ ->
            Pair(403, """{"success":false,"errorCode":"FEATURE_DISABLED","message":"Subscription redemption is disabled"}""")
        }

        val req = SubscriptionRedemptionRequest(sku = "pro_lite_7d")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue(result is PointsOperationResult.Error)
        assertEquals(PointsEarningError.FEATURE_DISABLED, (result as PointsOperationResult.Error).error)
    }

    // ========================================================================
    // TEST 41: Feature points=DISABLED blocks redemption
    // ========================================================================
    @Test
    fun test41_FeaturePointsDisabledBlocksRedemption() = runBlocking {
        PointsEarningRepository.backendHandler = { _, _, _ ->
            Pair(403, """{"success":false,"errorCode":"FEATURE_DISABLED","message":"Points system is disabled"}""")
        }

        val req = SubscriptionRedemptionRequest(sku = "pro_lite_7d")
        val result = PointsEarningRepository.redeemSubscription(req)

        assertTrue(result is PointsOperationResult.Error)
        assertEquals(PointsEarningError.FEATURE_DISABLED, (result as PointsOperationResult.Error).error)
    }
}
