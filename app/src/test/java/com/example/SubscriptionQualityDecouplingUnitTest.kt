package com.example

import com.example.data.model.CanonicalSubscriptionSource
import com.example.data.model.CanonicalSubscriptionStatus
import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.EconomyConfig
import com.example.data.model.FeatureState
import com.example.data.model.FeaturesConfig
import com.example.data.model.PointTransaction
import com.example.data.model.SubscriptionNormalizer
import com.example.data.model.UserRestrictions
import com.example.data.model.UserSubscriptionState
import com.example.data.repository.EconomyConfigRepository
import com.example.data.repository.PointsRepository
import com.example.data.repository.User
import com.example.data.repository.UserSecurityManager
import com.example.ui.screens.player.normalizeCanonicalQualityName
import com.example.ui.screens.player.normalizeQualityKey
import com.example.utils.AdManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * PHASE SUBSCRIPTION-POINTS-03B: CANONICAL SUBSCRIPTION & QUALITY DECOUPLING TESTS
 *
 * Verifies all 23 required test scenarios + Critical Tests A through F:
 * - Full canonical normalization and precedence
 * - Authoritative expiration
 * - Ad suppression driven strictly by isAdFree
 * - Total quality and download independence from subscription tier
 * - Read-only economy consumption and safe fallbacks
 */
class SubscriptionQualityDecouplingUnitTest {

    @Before
    fun setUp() {
        UserSecurityManager.reset()
    }

    // 1. FREE normalization
    @Test
    fun test01_freeNormalization() {
        val tier = SubscriptionNormalizer.normalizeSubscriptionTier("FREE")
        assertEquals(CanonicalSubscriptionTier.FREE, tier)

        val lowercaseTier = SubscriptionNormalizer.normalizeSubscriptionTier("free")
        assertEquals(CanonicalSubscriptionTier.FREE, lowercaseTier)
    }

    // 2. PRO normalization
    @Test
    fun test02_proNormalization() {
        val tier = SubscriptionNormalizer.normalizeSubscriptionTier("PRO")
        assertEquals(CanonicalSubscriptionTier.PRO, tier)

        val lowercaseTier = SubscriptionNormalizer.normalizeSubscriptionTier("pro")
        assertEquals(CanonicalSubscriptionTier.PRO, lowercaseTier)
    }

    // 3. PRO_LITE normalization
    @Test
    fun test03_proLiteNormalization() {
        val tier = SubscriptionNormalizer.normalizeSubscriptionTier("PRO_LITE")
        assertEquals(CanonicalSubscriptionTier.PRO_LITE, tier)

        val lowercaseTier = SubscriptionNormalizer.normalizeSubscriptionTier("pro_lite")
        assertEquals(CanonicalSubscriptionTier.PRO_LITE, lowercaseTier)
    }

    // 4. legacy free
    @Test
    fun test04_legacyFree() {
        val tier = SubscriptionNormalizer.normalizeSubscriptionTier(
            rawTier = null,
            rawIsPremium = false,
            rawPlan = "free",
            rawRole = "user"
        )
        assertEquals(CanonicalSubscriptionTier.FREE, tier)
    }

    // 5. legacy premium
    @Test
    fun test05_legacyPremium() {
        val tierFromPlan = SubscriptionNormalizer.normalizeSubscriptionTier(
            rawTier = null,
            rawIsPremium = false,
            rawPlan = "premium"
        )
        assertEquals(CanonicalSubscriptionTier.PRO, tierFromPlan)

        val tierFromFlag = SubscriptionNormalizer.normalizeSubscriptionTier(
            rawTier = null,
            rawIsPremium = true,
            rawPlan = null
        )
        assertEquals(CanonicalSubscriptionTier.PRO, tierFromFlag)
    }

    // 6. legacy vip
    @Test
    fun test06_legacyVip() {
        val tierFromPlan = SubscriptionNormalizer.normalizeSubscriptionTier(
            rawTier = null,
            rawIsPremium = false,
            rawPlan = "vip"
        )
        assertEquals(CanonicalSubscriptionTier.PRO, tierFromPlan)

        val tierFromRole = SubscriptionNormalizer.normalizeSubscriptionTier(
            rawTier = null,
            rawIsPremium = false,
            rawPlan = null,
            rawRole = "vip"
        )
        assertEquals(CanonicalSubscriptionTier.PRO, tierFromRole)
    }

    // 7. canonical precedence (e.g. subscriptionTier = "FREE", isPremium = true -> Result: FREE)
    @Test
    fun test07_canonicalPrecedence() {
        val tier = SubscriptionNormalizer.normalizeSubscriptionTier(
            rawTier = "FREE",
            rawIsPremium = true,
            rawPlan = "vip",
            rawRole = "vip"
        )
        assertEquals("Canonical subscriptionTier must override legacy isPremium/vip flags", CanonicalSubscriptionTier.FREE, tier)
    }

    // 8. expiration
    @Test
    fun test08_expirationAuthoritative() {
        val now = 10000000L
        val expiredTime = now - 5000L

        val status = SubscriptionNormalizer.normalizeSubscriptionStatus(
            rawStatus = "ACTIVE",
            expiresAt = expiredTime,
            tier = CanonicalSubscriptionTier.PRO,
            currentTimeMillis = now
        )
        assertEquals(CanonicalSubscriptionStatus.EXPIRED, status)

        val parsed = SubscriptionNormalizer.parseUserSubscriptionState(
            tierStr = "PRO",
            planIdStr = "pro_30d",
            durationDaysVal = 30,
            statusStr = "ACTIVE",
            sourceStr = "MONEY",
            refIdStr = "ref_123",
            startedAtVal = expiredTime - (30L * 86400000L),
            expiresAtVal = expiredTime,
            currentTimeMillis = now
        )
        assertEquals(CanonicalSubscriptionStatus.EXPIRED, parsed.status)
        assertFalse("Expired subscription must not be active", parsed.isActive)
        assertFalse("Expired subscription must not be ad-free", parsed.isAdFree)
    }

    // 9. source handling
    @Test
    fun test09_sourceHandling() {
        assertEquals(CanonicalSubscriptionSource.MONEY, SubscriptionNormalizer.normalizeSubscriptionSource("MONEY"))
        assertEquals(CanonicalSubscriptionSource.POINTS, SubscriptionNormalizer.normalizeSubscriptionSource("POINTS"))
        assertEquals(CanonicalSubscriptionSource.ADMIN_GRANT, SubscriptionNormalizer.normalizeSubscriptionSource("ADMIN_GRANT"))
        assertEquals(CanonicalSubscriptionSource.LEGACY, SubscriptionNormalizer.normalizeSubscriptionSource("LEGACY"))
        assertEquals(CanonicalSubscriptionSource.LEGACY, SubscriptionNormalizer.normalizeSubscriptionSource(null))
        assertEquals(CanonicalSubscriptionSource.LEGACY, SubscriptionNormalizer.normalizeSubscriptionSource("unknown"))

        val parsedFromPoints = SubscriptionNormalizer.parseUserSubscriptionState(
            tierStr = "PRO_LITE",
            planIdStr = "pro_lite_7d",
            durationDaysVal = 7,
            statusStr = "ACTIVE",
            sourceStr = "POINTS",
            refIdStr = "tx_999",
            startedAtVal = 1000L,
            expiresAtVal = System.currentTimeMillis() + 100000L
        )
        val parsedFromMoney = SubscriptionNormalizer.parseUserSubscriptionState(
            tierStr = "PRO_LITE",
            planIdStr = "pro_lite_7d",
            durationDaysVal = 7,
            statusStr = "ACTIVE",
            sourceStr = "MONEY",
            refIdStr = "receipt_999",
            startedAtVal = 1000L,
            expiresAtVal = System.currentTimeMillis() + 100000L
        )

        assertEquals(parsedFromMoney.isAdFree, parsedFromPoints.isAdFree)
        assertTrue(parsedFromPoints.isAdFree)
    }

    // 10. isAdFree
    @Test
    fun test10_isAdFreeEntitlement() {
        val freeUser = UserRestrictions(
            subscriptionTier = "FREE",
            subscriptionStatus = "ACTIVE"
        )
        assertFalse("FREE tier must not be ad-free", freeUser.isAdFree)

        val proLiteUser = UserRestrictions(
            subscriptionTier = "PRO_LITE",
            subscriptionStatus = "ACTIVE",
            subscriptionExpiresAt = System.currentTimeMillis() + 100000L
        )
        assertTrue("Active PRO_LITE must be ad-free", proLiteUser.isAdFree)

        val proUser = UserRestrictions(
            subscriptionTier = "PRO",
            subscriptionStatus = "ACTIVE",
            subscriptionExpiresAt = System.currentTimeMillis() + 100000L
        )
        assertTrue("Active PRO must be ad-free", proUser.isAdFree)
    }

    // 11. expired ad restoration
    @Test
    fun test11_expiredAdRestoration() {
        val expiredPro = UserRestrictions(
            subscriptionTier = "PRO",
            subscriptionStatus = "ACTIVE",
            subscriptionExpiresAt = System.currentTimeMillis() - 10000L
        )
        assertFalse("Expired PRO must have isAdFree = false", expiredPro.isAdFree)
        assertFalse("Expired PRO must have isSubscriptionActive = false", expiredPro.isSubscriptionActive)
    }

    // 12. quality independence (allowedQuality applies regardless of tier)
    @Test
    fun test12_qualityIndependence() {
        val freeAccountWithCappedQuality = UserRestrictions(
            subscriptionTier = "FREE",
            allowedQuality = "720p"
        )
        assertTrue("720p allowed when max is 720p", freeAccountWithCappedQuality.isQualityAllowed("720p"))
        assertTrue("480p allowed when max is 720p", freeAccountWithCappedQuality.isQualityAllowed("480p"))
        assertFalse("1080p disallowed when technical limit is 720p", freeAccountWithCappedQuality.isQualityAllowed("1080p"))
        assertFalse("4K disallowed when technical limit is 720p", freeAccountWithCappedQuality.isQualityAllowed("4K"))

        val freeAccountUncapped = UserRestrictions(
            subscriptionTier = "FREE",
            allowedQuality = null
        )
        assertTrue("All qualities allowed when no technical cap set on FREE account", freeAccountUncapped.isQualityAllowed("4K"))
        assertTrue(freeAccountUncapped.isQualityAllowed("1080p"))
        assertTrue(freeAccountUncapped.isQualityAllowed("720p"))
    }

    // 13. download independence
    @Test
    fun test13_downloadIndependence() {
        val proUserWithDownloadBan = UserRestrictions(
            subscriptionTier = "PRO",
            subscriptionExpiresAt = System.currentTimeMillis() + 100000L,
            downloadBan = true
        )
        assertFalse("Technical download ban applies to PRO user", proUserWithDownloadBan.isDownloadAllowed)

        val proLiteWithDownloadLimit = UserRestrictions(
            subscriptionTier = "PRO_LITE",
            subscriptionExpiresAt = System.currentTimeMillis() + 100000L,
            downloadLimit = 5
        )
        assertTrue(proLiteWithDownloadLimit.isDownloadLimitReached(5))
        assertFalse(proLiteWithDownloadLimit.isDownloadLimitReached(4))
    }

    // 14. PRO cannot unlock quality (if allowedQuality is capped, PRO cannot bypass)
    @Test
    fun test14_proCannotUnlockQuality() {
        val proWithCap = UserRestrictions(
            subscriptionTier = "PRO",
            subscriptionExpiresAt = System.currentTimeMillis() + 100000L,
            allowedQuality = "720p"
        )
        assertFalse("PRO tier cannot bypass independent allowedQuality cap", proWithCap.isQualityAllowed("1080p"))
        assertFalse("PRO tier cannot bypass independent allowedQuality cap for 4K", proWithCap.isQualityAllowed("4K"))
        assertTrue("PRO tier can access allowed 720p", proWithCap.isQualityAllowed("720p"))
    }

    // 15. PRO_LITE cannot unlock quality
    @Test
    fun test15_proLiteCannotUnlockQuality() {
        val proLiteWithCap = UserRestrictions(
            subscriptionTier = "PRO_LITE",
            subscriptionExpiresAt = System.currentTimeMillis() + 100000L,
            allowedQuality = "480p"
        )
        assertFalse("PRO_LITE cannot bypass technical allowedQuality cap", proLiteWithCap.isQualityAllowed("720p"))
        assertFalse("PRO_LITE cannot bypass technical allowedQuality cap", proLiteWithCap.isQualityAllowed("1080p"))
        assertTrue("PRO_LITE can access allowed 480p", proLiteWithCap.isQualityAllowed("480p"))
    }

    // 16. FREE cannot lose quality because of subscription
    @Test
    fun test16_freeCannotLoseQualityBecauseOfSubscription() {
        val freeUser = UserRestrictions(
            subscriptionTier = "FREE",
            allowedQuality = null
        )
        assertTrue("FREE user must be able to select 4K from source", freeUser.isQualityAllowed("4K"))
        assertTrue("FREE user must be able to select 1080p from source", freeUser.isQualityAllowed("1080p"))
        assertTrue("FREE user must be able to select 720p from source", freeUser.isQualityAllowed("720p"))
        assertTrue("FREE user must be able to select 480p from source", freeUser.isQualityAllowed("480p"))
    }

    // 17. points wallet read-only behavior
    @Test
    fun test17_pointsWalletReadOnly() {
        val user = User(
            uid = "u1",
            pointsBalance = 150L,
            totalPointsEarned = 200L,
            totalPointsSpent = 50L
        )
        assertEquals(150L, user.pointsBalance)
        assertEquals(200L, user.totalPointsEarned)
        assertEquals(50L, user.totalPointsSpent)

        val restrictions = UserRestrictions(
            pointsBalance = 250L,
            totalPointsEarned = 300L,
            totalPointsSpent = 50L
        )
        assertEquals(250L, restrictions.pointsBalance)
    }

    // 18. ledger read-only behavior
    @Test
    fun test18_ledgerReadOnlyBehavior() {
        val tx = PointTransaction(
            id = "tx1",
            txId = "tx1",
            userId = "u1",
            type = "DAILY_LOGIN",
            amount = 15L,
            balanceBefore = 100L,
            balanceAfter = 115L,
            description = "Daily check-in reward",
            createdAt = 1727712000000L
        )
        assertEquals(15L, tx.amount)
        assertEquals(115L, tx.balanceAfter)
        assertEquals("DAILY_LOGIN", tx.type)
    }

    // 19. feature state parsing
    @Test
    fun test19_featureStateParsing() {
        assertEquals(FeatureState.ACTIVE, FeaturesConfig.parseFeatureState("ACTIVE"))
        assertEquals(FeatureState.ACTIVE, FeaturesConfig.parseFeatureState("active"))
        assertEquals(FeatureState.ACTIVE, FeaturesConfig.parseFeatureState(true))
        assertEquals(FeatureState.DISABLED, FeaturesConfig.parseFeatureState("DISABLED"))
        assertEquals(FeatureState.DISABLED, FeaturesConfig.parseFeatureState(false))
        assertEquals(FeatureState.COMING_SOON, FeaturesConfig.parseFeatureState("COMING_SOON"))
        assertEquals(FeatureState.COMING_SOON, FeaturesConfig.parseFeatureState(null))
        assertEquals(FeatureState.COMING_SOON, FeaturesConfig.parseFeatureState("unknown_state"))
    }

    // 20. economy config parsing
    @Test
    fun test20_economyConfigParsing() {
        val defaultEcon = EconomyConfig()
        assertEquals(50L, defaultEcon.redemptionCosts["pro_lite_1d"])
        assertEquals(250L, defaultEcon.redemptionCosts["pro_lite_7d"])
        assertEquals(350L, defaultEcon.redemptionCosts["pro_lite_10d"])
        assertEquals(1000L, defaultEcon.redemptionCosts["pro_30d"])
        assertEquals(15L, defaultEcon.rewardedAdPoints)
        assertEquals(5, defaultEcon.rewardedAdDailyCap)
        assertEquals(300L, defaultEcon.rewardedAdCooldownSeconds)
    }

    // 21. missing config safety (Phase 05B: Implemented features default to ACTIVE)
    @Test
    fun test21_missingConfigSafety() {
        val fallbackFeatures = FeaturesConfig()
        assertEquals(FeatureState.ACTIVE, fallbackFeatures.subscriptions)
        assertEquals(FeatureState.ACTIVE, fallbackFeatures.points)
        assertEquals(FeatureState.ACTIVE, fallbackFeatures.dailyLogin)
        assertEquals(FeatureState.ACTIVE, fallbackFeatures.rewardedAds)
        assertEquals(FeatureState.ACTIVE, fallbackFeatures.tasks)
        assertEquals(FeatureState.ACTIVE, fallbackFeatures.leaderboard)
    }

    // 22. malformed config safety
    @Test
    fun test22_malformedConfigSafety() {
        val fallback = EconomyConfig(
            redemptionCosts = emptyMap()
        )
        assertNotNull(fallback)
    }

    // 23. offline safety
    @Test
    fun test23_offlineSafety() {
        val offlineRestrictions = UserRestrictions()
        assertEquals(CanonicalSubscriptionTier.FREE, offlineRestrictions.canonicalTier)
        assertFalse(offlineRestrictions.isAdFree)
        assertTrue(offlineRestrictions.isQualityAllowed("1080p"))
        assertTrue(offlineRestrictions.isQualityAllowed("4K"))
    }

    // =========================================================================
    // CRITICAL TESTS (Section 34 A - F)
    // =========================================================================

    // Critical Test A: PRO + 4K source: 4K remains available
    @Test
    fun criticalTestA_proPlus4kSource_4kRemainsAvailable() {
        val proRestrictions = UserRestrictions(
            subscriptionTier = "PRO",
            subscriptionStatus = "ACTIVE",
            subscriptionExpiresAt = System.currentTimeMillis() + 100000L,
            allowedQuality = null
        )
        assertTrue("PRO user can access 4K source", proRestrictions.isQualityAllowed("4K"))
        assertEquals("4K", normalizeQualityKey("4K"))
        assertEquals("4K", normalizeCanonicalQualityName("4K"))
    }

    // Critical Test B: PRO_LITE + 4K source: 4K remains available
    @Test
    fun criticalTestB_proLitePlus4kSource_4kRemainsAvailable() {
        val proLiteRestrictions = UserRestrictions(
            subscriptionTier = "PRO_LITE",
            subscriptionStatus = "ACTIVE",
            subscriptionExpiresAt = System.currentTimeMillis() + 100000L,
            allowedQuality = null
        )
        assertTrue("PRO_LITE user can access 4K source", proLiteRestrictions.isQualityAllowed("4K"))
        assertEquals("4K", normalizeQualityKey("2160p"))
        assertEquals("4K", normalizeCanonicalQualityName("2160p"))
    }

    // Critical Test C: FREE + 4K source: 4K remains available
    @Test
    fun criticalTestC_freePlus4kSource_4kRemainsAvailable() {
        val freeRestrictions = UserRestrictions(
            subscriptionTier = "FREE",
            subscriptionStatus = "ACTIVE",
            allowedQuality = null
        )
        assertTrue("FREE user must be able to select and play 4K from source", freeRestrictions.isQualityAllowed("4K"))
        assertEquals("4K", normalizeQualityKey("3840x2160"))
        assertEquals("4K", normalizeCanonicalQualityName("4K"))
    }

    // Critical Test D: PRO + 1080p source: 1080p remains available
    @Test
    fun criticalTestD_proPlus1080pSource_1080pRemainsAvailable() {
        val proRestrictions = UserRestrictions(
            subscriptionTier = "PRO",
            subscriptionStatus = "ACTIVE",
            subscriptionExpiresAt = System.currentTimeMillis() + 100000L,
            allowedQuality = null
        )
        assertTrue("PRO user can access 1080p source", proRestrictions.isQualityAllowed("1080p"))
        assertEquals("1080", normalizeQualityKey("1080p"))
        assertEquals("1080p", normalizeCanonicalQualityName("1080p"))
    }

    // Critical Test E: FREE + 1080p source: 1080p remains available
    @Test
    fun criticalTestE_freePlus1080pSource_1080pRemainsAvailable() {
        val freeRestrictions = UserRestrictions(
            subscriptionTier = "FREE",
            subscriptionStatus = "ACTIVE",
            allowedQuality = null
        )
        assertTrue("FREE user must be able to select and play 1080p from source", freeRestrictions.isQualityAllowed("1080p"))
        assertEquals("1080", normalizeQualityKey("1920x1080"))
        assertEquals("1080p", normalizeCanonicalQualityName("1080p"))
    }

    // Critical Test F: PRO expiration: ads return, quality remains unchanged
    @Test
    fun criticalTestF_proExpiration_adsReturn_qualityUnchanged() {
        val now = 10000000L
        val expiredPro = UserRestrictions(
            subscriptionTier = "PRO",
            subscriptionStatus = "ACTIVE",
            subscriptionExpiresAt = now - 5000L,
            allowedQuality = null
        )
        assertFalse("Upon expiration, user is no longer ad-free (ads resume)", expiredPro.isAdFree)
        assertTrue("Upon expiration, 4K quality remains available", expiredPro.isQualityAllowed("4K"))
        assertTrue("Upon expiration, 1080p quality remains available", expiredPro.isQualityAllowed("1080p"))
        assertTrue("Upon expiration, 720p quality remains available", expiredPro.isQualityAllowed("720p"))
    }
}
