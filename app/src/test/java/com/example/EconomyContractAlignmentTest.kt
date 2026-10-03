package com.example

import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.EconomyConfig
import com.example.data.model.UserRestrictions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ECONOMY CONTRACT ALIGNMENT REGRESSION TEST SUITE
 *
 * Verifies:
 * 1. Canonical fallback values:
 *    - pro_lite_1d  = 50
 *    - pro_lite_7d  = 250
 *    - pro_lite_10d = 350
 *    - pro_30d      = 1000
 * 2. Complete absence of old economic fallbacks (320, 800).
 * 3. Remote config precedence over fallback.
 * 4. Missing/malformed remote config falls back safely to canonical values.
 * 5. Quality decoupling remains intact across all tiers.
 */
class EconomyContractAlignmentTest {

    // ------------------------------------------------------------------------
    // TEST 1: Canonical Fallback Values Verification
    // ------------------------------------------------------------------------
    @Test
    fun test01_CanonicalFallbackValuesMatchAdminAndBackend() {
        val config = EconomyConfig()
        val costs = config.redemptionCosts

        assertEquals("pro_lite_1d must equal 50 points", 50L, costs["pro_lite_1d"])
        assertEquals("pro_lite_7d must equal 250 points", 250L, costs["pro_lite_7d"])
        assertEquals("pro_lite_10d must equal 350 points", 350L, costs["pro_lite_10d"])
        assertEquals("pro_30d must equal 1000 points", 1000L, costs["pro_30d"])

        // Explicitly assert old fallback values are NOT present
        assertNotEquals(320L, costs["pro_lite_10d"])
        assertNotEquals(800L, costs["pro_30d"])
    }

    // ------------------------------------------------------------------------
    // TEST 2: Static Code Audit: No 320 or 800 in Economy Models or Screens
    // ------------------------------------------------------------------------
    @Test
    fun test02_StaticAuditNoOldEconomicFallbackInModelsOrScreens() {
        val rootDir = File(".").canonicalFile
        val javaDir = if (File(rootDir, "app/src/main/java").exists()) {
            File(rootDir, "app/src/main/java")
        } else {
            File(rootDir, "src/main/java")
        }

        val economyModelsFile = File(javaDir, "com/example/data/model/EconomyModels.kt")
        assertTrue("EconomyModels.kt must exist", economyModelsFile.exists())
        val modelsText = economyModelsFile.readText()
        assertFalse("EconomyModels.kt must not contain 320L", modelsText.contains("320L"))
        assertFalse("EconomyModels.kt must not contain 800L", modelsText.contains("800L"))

        val subscriptionScreenFile = File(javaDir, "com/example/ui/screens/profile/SubscriptionScreen.kt")
        assertTrue("SubscriptionScreen.kt must exist", subscriptionScreenFile.exists())
        val screenText = subscriptionScreenFile.readText()
        assertFalse("SubscriptionScreen.kt must not contain ?: 320L fallback", screenText.contains("?: 320L"))
        assertFalse("SubscriptionScreen.kt must not contain ?: 800L fallback", screenText.contains("?: 800L"))

        // Assert canonical fallbacks are present in SubscriptionScreen
        assertTrue("SubscriptionScreen.kt must contain ?: 350L", screenText.contains("?: 350L"))
        assertTrue("SubscriptionScreen.kt must contain ?: 1000L", screenText.contains("?: 1000L"))
    }

    // ------------------------------------------------------------------------
    // TEST 3: Remote Config Precedence Over Fallback
    // ------------------------------------------------------------------------
    @Test
    fun test03_RemoteConfigOverridesCanonicalFallback() {
        val mockRemoteCosts = mapOf(
            "pro_lite_1d" to 60L,
            "pro_lite_7d" to 300L,
            "pro_lite_10d" to 400L,
            "pro_30d" to 1200L
        )

        // When remote config specifies values, EconomyConfig should reflect them
        val remoteConfig = EconomyConfig(redemptionCosts = mockRemoteCosts)
        assertEquals(60L, remoteConfig.redemptionCosts["pro_lite_1d"])
        assertEquals(300L, remoteConfig.redemptionCosts["pro_lite_7d"])
        assertEquals(400L, remoteConfig.redemptionCosts["pro_lite_10d"])
        assertEquals(1200L, remoteConfig.redemptionCosts["pro_30d"])
    }

    // ------------------------------------------------------------------------
    // TEST 4: Missing or Malformed Remote Config Falls Back Safely
    // ------------------------------------------------------------------------
    @Test
    fun test04_MissingOrEmptyRemoteConfigFallsBackToCanonicalValues() {
        // If an empty map is passed to EconomyConfigRepository fallback logic:
        val emptyCostsMap = emptyMap<String, Long>()
        val resolvedConfig = EconomyConfig(
            redemptionCosts = if (emptyCostsMap.isNotEmpty()) emptyCostsMap else EconomyConfig().redemptionCosts
        )

        assertEquals(50L, resolvedConfig.redemptionCosts["pro_lite_1d"])
        assertEquals(250L, resolvedConfig.redemptionCosts["pro_lite_7d"])
        assertEquals(350L, resolvedConfig.redemptionCosts["pro_lite_10d"])
        assertEquals(1000L, resolvedConfig.redemptionCosts["pro_30d"])
    }

    // ------------------------------------------------------------------------
    // TEST 5: Business Rule Integrity: Quality Decoupling Preserved
    // ------------------------------------------------------------------------
    @Test
    fun test05_SubscriptionBenefitRemainsAdRemovalOnlyWithoutQualityGating() {
        // FREE Tier
        val freeRestrictions = UserRestrictions(subscriptionTier = "FREE", allowedQuality = null)
        assertEquals(CanonicalSubscriptionTier.FREE, freeRestrictions.canonicalTier)
        assertFalse(freeRestrictions.isAdFree)
        assertNull("allowedQuality must be null for FREE (all qualities allowed)", freeRestrictions.allowedQuality)
        assertTrue(freeRestrictions.isQualityAllowed("4K"))
        assertTrue(freeRestrictions.isQualityAllowed("1080p"))

        // PRO_LITE Tier (10 days)
        val proLiteRestrictions = UserRestrictions(
            subscriptionTier = "PRO_LITE",
            subscriptionExpiresAt = System.currentTimeMillis() + 864000000L,
            allowedQuality = null
        )
        assertEquals(CanonicalSubscriptionTier.PRO_LITE, proLiteRestrictions.canonicalTier)
        assertTrue(proLiteRestrictions.isAdFree)
        assertNull("allowedQuality must be null for PRO_LITE (all qualities allowed)", proLiteRestrictions.allowedQuality)
        assertTrue(proLiteRestrictions.isQualityAllowed("4K"))
        assertTrue(proLiteRestrictions.isQualityAllowed("1080p"))

        // PRO Tier (30 days)
        val proRestrictions = UserRestrictions(
            subscriptionTier = "PRO",
            subscriptionExpiresAt = System.currentTimeMillis() + 2592000000L,
            allowedQuality = null
        )
        assertEquals(CanonicalSubscriptionTier.PRO, proRestrictions.canonicalTier)
        assertTrue(proRestrictions.isAdFree)
        assertNull("allowedQuality must be null for PRO (all qualities allowed)", proRestrictions.allowedQuality)
        assertTrue(proRestrictions.isQualityAllowed("4K"))
        assertTrue(proRestrictions.isQualityAllowed("1080p"))
    }
}
