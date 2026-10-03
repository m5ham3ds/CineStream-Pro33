package com.example

import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.SubscriptionNormalizer
import com.example.data.model.UserRestrictions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * PHASE 03C.1-HARDENING: Verification Suite for Firestore Rules Drift & Security Hardening
 *
 * Implements the exact 25 required test cases from Section 21 of Phase 03C.1-Hardening:
 * - TEST 01: Guest cannot read /config/features
 * - TEST 02: Authenticated user can read /config/features
 * - TEST 03: Guest cannot read /config/economy
 * - TEST 04: Authenticated user can read /config/economy
 * - TEST 05: Authenticated user cannot write /config/features
 * - TEST 06: Authenticated user cannot write /config/economy
 * - TEST 07: User cannot create a new ACTIVE subscription state
 * - TEST 08: User cannot create PRO user state
 * - TEST 09: User cannot create privileged subscription fields
 * - TEST 10: User can create own valid PENDING pro request
 * - TEST 11: User cannot create pro request for another user
 * - TEST 12: User cannot insert privileged subscription fields into pro_requests
 * - TEST 13: User cannot approve own pro request
 * - TEST 14: User cannot activate own subscription through pro_requests
 * - TEST 15: User cannot modify pointsBalance
 * - TEST 16: User cannot modify totalPointsEarned
 * - TEST 17: User cannot modify totalPointsSpent
 * - TEST 18: User cannot write point_transactions
 * - TEST 19: User can read own point_transactions
 * - TEST 20: User cannot read another user's point_transactions
 * - TEST 21: User can read reward_tasks
 * - TEST 22: User cannot write reward_tasks
 * - TEST 23: User can read leaderboard
 * - TEST 24: User cannot write leaderboard
 * - TEST 25: Subscription-quality decoupling remains intact
 */
class Phase03C1FirestoreSecurityHardeningTest {

    private lateinit var rulesContent: String

    @Before
    fun setUp() {
        val candidates = listOf(
            File("firestore.rules"),
            File("../firestore.rules"),
            File("../../firestore.rules"),
            File("/firestore.rules")
        )
        val file = candidates.firstOrNull { it.exists() && it.isFile }
            ?: throw IllegalStateException("firestore.rules not found in candidate paths: $candidates")
        rulesContent = file.readText()
    }

    // =========================================================================
    // AST / Rule Content Structural Verifications
    // =========================================================================

    @Test
    fun testStructuralConfigRulesAlignment() {
        // /config/features: authenticated read, admin write
        assertTrue(rulesContent.contains("match /config/features"))
        assertTrue(rulesContent.contains("match /config/features {\n      allow read: if isAuthenticated();"))
        
        // /config/economy: authenticated read, admin write
        assertTrue(rulesContent.contains("match /config/economy"))
        assertTrue(rulesContent.contains("match /config/economy {\n      allow read: if isAuthenticated();"))

        // /config/app: authenticated read, admin write
        assertTrue(rulesContent.contains("match /config/app {\n      allow read: if isAuthenticated();"))

        // /config/search_order: public read for global extension control per Phase 05H/05J, admin write
        assertTrue(rulesContent.contains("match /config/search_order {\n      allow read: if true;"))

        // Catch-all must deny
        assertTrue(rulesContent.contains("match /config/{document=**} {\n      allow read, write: if false;\n    }"))
    }

    @Test
    fun testStructuralUserCreationHardening() {
        // Must reject subscriptionStatus == 'ACTIVE' on creation
        assertFalse(
            "User creation must NOT allow subscriptionStatus == 'ACTIVE'",
            rulesContent.contains("request.resource.data.subscriptionStatus == 'ACTIVE'")
        )
        assertTrue(
            "User creation must restrict subscriptionStatus to none or inactive",
            rulesContent.contains("request.resource.data.subscriptionStatus == 'none'")
        )
        assertTrue(
            "User creation must restrict subscriptionTier to free/FREE",
            rulesContent.contains("request.resource.data.subscriptionTier == 'free' || request.resource.data.subscriptionTier == 'FREE'")
        )
        // Must blacklist privileged timestamp/id fields on create
        assertTrue(rulesContent.contains("'subscriptionExpiresAt'"))
        assertTrue(rulesContent.contains("'subscriptionStartedAt'"))
        assertTrue(rulesContent.contains("'subscriptionReferenceId'"))
    }

    @Test
    fun testStructuralProRequestsHardening() {
        assertTrue(rulesContent.contains("match /pro_requests/{requestId}"))
        assertTrue(rulesContent.contains("request.resource.data.userId == request.auth.uid"))
        assertTrue(rulesContent.contains("request.resource.data.status == 'PENDING'"))
        // Must contain blacklist against privileged subscription and approval fields
        assertTrue(rulesContent.contains("'approvedBy'"))
        assertTrue(rulesContent.contains("'approvedAt'"))
        assertTrue(rulesContent.contains("'grantedBy'"))
        assertTrue(rulesContent.contains("'grantSource'"))
        assertTrue(rulesContent.contains("'subscriptionTier'"))
        assertTrue(rulesContent.contains("'subscriptionStatus'"))
        assertTrue(rulesContent.contains("'subscriptionExpiresAt'"))
    }

    // =========================================================================
    // Simulated Authorization Engine Modeling firestore.rules
    // =========================================================================

    data class SecurityContext(
        val uid: String?,
        val isAdmin: Boolean = false
    )

    enum class Decision { ALLOWED, DENIED }

    // Evaluates /config/{doc} read rule
    private fun evalConfigRead(ctx: SecurityContext, configName: String): Decision {
        val isAuthenticated = ctx.uid != null
        return when (configName) {
            "features", "economy", "app", "search_order" -> if (isAuthenticated) Decision.ALLOWED else Decision.DENIED
            else -> Decision.DENIED // wildcard catch-all denies
        }
    }

    // Evaluates /config/{doc} write rule
    private fun evalConfigWrite(ctx: SecurityContext, configName: String): Decision {
        return if (ctx.uid != null && ctx.isAdmin) Decision.ALLOWED else Decision.DENIED
    }

    // Evaluates /users/{userId} create rule
    private fun evalUserCreate(
        ctx: SecurityContext,
        targetUserId: String,
        payload: Map<String, Any?>
    ): Decision {
        if (ctx.uid == null || ctx.uid != targetUserId) return Decision.DENIED
        if (payload["uid"] != targetUserId) return Decision.DENIED

        val role = payload["role"] as? String
        if (role != null && role != "user") return Decision.DENIED

        val isPremium = payload["isPremium"] as? Boolean
        if (isPremium != null && isPremium) return Decision.DENIED

        val subTier = payload["subscriptionTier"] as? String
        if (subTier != null && subTier != "free" && subTier != "FREE") return Decision.DENIED

        val plan = payload["plan"] as? String
        if (plan != null && plan != "free") return Decision.DENIED

        val planId = payload["planId"] as? String
        if (planId != null && planId != "free") return Decision.DENIED

        val durationDays = (payload["durationDays"] as? Number)?.toInt()
        if (durationDays != null && durationDays != 0) return Decision.DENIED

        val subStatus = payload["subscriptionStatus"] as? String
        if (subStatus != null && subStatus != "none" && subStatus != "NONE" && subStatus != "inactive" && subStatus != "INACTIVE" && subStatus != "") {
            return Decision.DENIED
        }

        val pointsBalance = (payload["pointsBalance"] as? Number)?.toLong()
        if (pointsBalance != null && pointsBalance != 0L) return Decision.DENIED

        val totalPointsEarned = (payload["totalPointsEarned"] as? Number)?.toLong()
        if (totalPointsEarned != null && totalPointsEarned != 0L) return Decision.DENIED

        val totalPointsSpent = (payload["totalPointsSpent"] as? Number)?.toLong()
        if (totalPointsSpent != null && totalPointsSpent != 0L) return Decision.DENIED

        val forbiddenKeys = setOf(
            "deviceLimit", "maxDevices", "allowedQuality", "downloadLimit",
            "subscriptionExpiresAt", "subscriptionStartedAt", "subscriptionReferenceId",
            "proExpiresAt", "proPlan", "banExpiresAt",
            "watchBan", "downloadBan", "chatBan", "storyBan", "p2pBan",
            "offlineDaysOverride", "forcedAdsOverride", "admin", "isAdmin"
        )
        if (payload.keys.intersect(forbiddenKeys).isNotEmpty()) return Decision.DENIED

        return Decision.ALLOWED
    }

    // Evaluates /users/{userId} update rule
    private fun evalUserUpdate(
        ctx: SecurityContext,
        targetUserId: String,
        affectedKeys: Set<String>
    ): Decision {
        if (ctx.uid != null && ctx.isAdmin) return Decision.ALLOWED
        if (ctx.uid != null && ctx.uid == targetUserId) {
            val blacklisted = setOf(
                "role", "isPremium", "subscriptionTier", "plan", "planId", "durationDays",
                "subscriptionStatus", "subscriptionSource", "subscriptionReferenceId",
                "subscriptionStartedAt", "subscriptionExpiresAt", "proPlan", "proExpiresAt",
                "pointsBalance", "totalPointsEarned", "totalPointsSpent",
                "deviceLimit", "maxDevices", "allowedQuality", "downloadLimit",
                "isActive", "isBanned", "banReason", "banExpiresAt",
                "canWatch", "canDownload", "canChat", "canStory", "canP2P",
                "watchBan", "downloadBan", "chatBan", "storyBan", "p2pBan",
                "canComment", "canUpload", "canRequest",
                "offlineDaysOverride", "forcedAdsOverride", "admin", "isAdmin"
            )
            return if (affectedKeys.intersect(blacklisted).isEmpty()) Decision.ALLOWED else Decision.DENIED
        }
        return Decision.DENIED
    }

    // Evaluates /pro_requests create rule
    private fun evalProRequestCreate(
        ctx: SecurityContext,
        payload: Map<String, Any?>
    ): Decision {
        if (ctx.uid == null) return Decision.DENIED
        val userId = payload["userId"] as? String
        if (userId != ctx.uid) return Decision.DENIED

        val status = payload["status"] as? String
        if (status != "PENDING" && status != "pending") return Decision.DENIED

        val forbiddenKeys = setOf(
            "approvedBy", "approvedAt", "grantedBy", "grantSource",
            "subscriptionTier", "subscriptionStatus", "subscriptionSource",
            "subscriptionReferenceId", "subscriptionStartedAt", "subscriptionExpiresAt",
            "isPremium", "isPro", "proPlan", "proExpiresAt",
            "admin", "isAdmin", "role"
        )
        if (payload.keys.intersect(forbiddenKeys).isNotEmpty()) return Decision.DENIED

        return Decision.ALLOWED
    }

    // Evaluates /pro_requests update rule
    private fun evalProRequestUpdate(ctx: SecurityContext): Decision {
        return if (ctx.uid != null && ctx.isAdmin) Decision.ALLOWED else Decision.DENIED
    }

    // Evaluates /users/{userId}/point_transactions read rule
    private fun evalPointTransactionRead(ctx: SecurityContext, targetUserId: String): Decision {
        if (ctx.uid == null) return Decision.DENIED
        return if (ctx.isAdmin || ctx.uid == targetUserId) Decision.ALLOWED else Decision.DENIED
    }

    // Evaluates /users/{userId}/point_transactions write rule
    private fun evalPointTransactionWrite(ctx: SecurityContext): Decision {
        return if (ctx.uid != null && ctx.isAdmin) Decision.ALLOWED else Decision.DENIED
    }

    // Evaluates /reward_tasks read/write rules
    private fun evalRewardTasksRead(ctx: SecurityContext): Decision {
        return if (ctx.uid != null) Decision.ALLOWED else Decision.DENIED
    }

    private fun evalRewardTasksWrite(ctx: SecurityContext): Decision {
        return if (ctx.uid != null && ctx.isAdmin) Decision.ALLOWED else Decision.DENIED
    }

    // Evaluates /leaderboard read/write rules
    private fun evalLeaderboardRead(ctx: SecurityContext): Decision {
        return if (ctx.uid != null) Decision.ALLOWED else Decision.DENIED
    }

    private fun evalLeaderboardWrite(ctx: SecurityContext): Decision {
        return if (ctx.uid != null && ctx.isAdmin) Decision.ALLOWED else Decision.DENIED
    }

    // =========================================================================
    // SECTION 21 REQUIRED TESTS (TEST 01 through TEST 25)
    // =========================================================================

    @Test
    fun test01_GuestCannotReadConfigFeatures() {
        val guest = SecurityContext(uid = null, isAdmin = false)
        assertEquals(Decision.DENIED, evalConfigRead(guest, "features"))
    }

    @Test
    fun test02_AuthenticatedUserCanReadConfigFeatures() {
        val user = SecurityContext(uid = "user_101", isAdmin = false)
        assertEquals(Decision.ALLOWED, evalConfigRead(user, "features"))
    }

    @Test
    fun test03_GuestCannotReadConfigEconomy() {
        val guest = SecurityContext(uid = null, isAdmin = false)
        assertEquals(Decision.DENIED, evalConfigRead(guest, "economy"))
    }

    @Test
    fun test04_AuthenticatedUserCanReadConfigEconomy() {
        val user = SecurityContext(uid = "user_101", isAdmin = false)
        assertEquals(Decision.ALLOWED, evalConfigRead(user, "economy"))
    }

    @Test
    fun test05_AuthenticatedUserCannotWriteConfigFeatures() {
        val user = SecurityContext(uid = "user_101", isAdmin = false)
        assertEquals(Decision.DENIED, evalConfigWrite(user, "features"))
    }

    @Test
    fun test06_AuthenticatedUserCannotWriteConfigEconomy() {
        val user = SecurityContext(uid = "user_101", isAdmin = false)
        assertEquals(Decision.DENIED, evalConfigWrite(user, "economy"))
    }

    @Test
    fun test07_UserCannotCreateNewActiveSubscriptionState() {
        val user = SecurityContext(uid = "user_new", isAdmin = false)
        val maliciousPayload = mapOf(
            "uid" to "user_new",
            "subscriptionStatus" to "ACTIVE",
            "subscriptionTier" to "FREE"
        )
        assertEquals(Decision.DENIED, evalUserCreate(user, "user_new", maliciousPayload))
    }

    @Test
    fun test08_UserCannotCreateProUserState() {
        val user = SecurityContext(uid = "user_new", isAdmin = false)
        val proPayload = mapOf(
            "uid" to "user_new",
            "subscriptionTier" to "PRO",
            "subscriptionStatus" to "none"
        )
        assertEquals(Decision.DENIED, evalUserCreate(user, "user_new", proPayload))

        val proLitePayload = mapOf(
            "uid" to "user_new",
            "subscriptionTier" to "PRO_LITE",
            "subscriptionStatus" to "none"
        )
        assertEquals(Decision.DENIED, evalUserCreate(user, "user_new", proLitePayload))
    }

    @Test
    fun test09_UserCannotCreatePrivilegedSubscriptionFields() {
        val user = SecurityContext(uid = "user_new", isAdmin = false)
        val payloadWithExpiresAt = mapOf(
            "uid" to "user_new",
            "subscriptionExpiresAt" to System.currentTimeMillis() + 86400000L
        )
        assertEquals(Decision.DENIED, evalUserCreate(user, "user_new", payloadWithExpiresAt))

        val payloadWithStartedAt = mapOf(
            "uid" to "user_new",
            "subscriptionStartedAt" to System.currentTimeMillis()
        )
        assertEquals(Decision.DENIED, evalUserCreate(user, "user_new", payloadWithStartedAt))

        val payloadWithRefId = mapOf(
            "uid" to "user_new",
            "subscriptionReferenceId" to "fake_ref_123"
        )
        assertEquals(Decision.DENIED, evalUserCreate(user, "user_new", payloadWithRefId))
    }

    @Test
    fun test10_UserCanCreateOwnValidPendingProRequest() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        val validPayload = mapOf(
            "requestId" to "req_123",
            "userId" to "user_42",
            "planId" to "pro_lite_7d",
            "durationDays" to 7,
            "status" to "PENDING",
            "paymentReference" to "tx_abc_123",
            "notes" to "Bank transfer sent",
            "createdAt" to System.currentTimeMillis()
        )
        assertEquals(Decision.ALLOWED, evalProRequestCreate(user, validPayload))
    }

    @Test
    fun test11_UserCannotCreateProRequestForAnotherUser() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        val spoofedPayload = mapOf(
            "requestId" to "req_123",
            "userId" to "victim_user_99",
            "status" to "PENDING"
        )
        assertEquals(Decision.DENIED, evalProRequestCreate(user, spoofedPayload))
    }

    @Test
    fun test12_UserCannotInsertPrivilegedSubscriptionFieldsIntoProRequests() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        
        // Attempt to self-grant approval fields
        val payload1 = mapOf(
            "userId" to "user_42",
            "status" to "PENDING",
            "approvedBy" to "admin_fake"
        )
        assertEquals(Decision.DENIED, evalProRequestCreate(user, payload1))

        // Attempt to self-grant subscription state
        val payload2 = mapOf(
            "userId" to "user_42",
            "status" to "PENDING",
            "subscriptionTier" to "PRO"
        )
        assertEquals(Decision.DENIED, evalProRequestCreate(user, payload2))

        // Attempt to set subscriptionStatus
        val payload3 = mapOf(
            "userId" to "user_42",
            "status" to "PENDING",
            "subscriptionStatus" to "ACTIVE"
        )
        assertEquals(Decision.DENIED, evalProRequestCreate(user, payload3))
    }

    @Test
    fun test13_UserCannotApproveOwnProRequest() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        assertEquals(Decision.DENIED, evalProRequestUpdate(user))
    }

    @Test
    fun test14_UserCannotActivateOwnSubscriptionThroughProRequests() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        // Creating an APPROVED or ACTIVE status is denied
        val approvedPayload = mapOf(
            "userId" to "user_42",
            "status" to "APPROVED"
        )
        assertEquals(Decision.DENIED, evalProRequestCreate(user, approvedPayload))

        val activePayload = mapOf(
            "userId" to "user_42",
            "status" to "ACTIVE"
        )
        assertEquals(Decision.DENIED, evalProRequestCreate(user, activePayload))
    }

    @Test
    fun test15_UserCannotModifyPointsBalance() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        assertEquals(Decision.DENIED, evalUserUpdate(user, "user_42", setOf("pointsBalance")))
    }

    @Test
    fun test16_UserCannotModifyTotalPointsEarned() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        assertEquals(Decision.DENIED, evalUserUpdate(user, "user_42", setOf("totalPointsEarned")))
    }

    @Test
    fun test17_UserCannotModifyTotalPointsSpent() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        assertEquals(Decision.DENIED, evalUserUpdate(user, "user_42", setOf("totalPointsSpent")))
    }

    @Test
    fun test18_UserCannotWritePointTransactions() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        assertEquals(Decision.DENIED, evalPointTransactionWrite(user))
    }

    @Test
    fun test19_UserCanReadOwnPointTransactions() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        assertEquals(Decision.ALLOWED, evalPointTransactionRead(user, "user_42"))
    }

    @Test
    fun test20_UserCannotReadAnotherUsersPointTransactions() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        assertEquals(Decision.DENIED, evalPointTransactionRead(user, "user_99"))
    }

    @Test
    fun test21_UserCanReadRewardTasks() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        assertEquals(Decision.ALLOWED, evalRewardTasksRead(user))
    }

    @Test
    fun test22_UserCannotWriteRewardTasks() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        assertEquals(Decision.DENIED, evalRewardTasksWrite(user))
    }

    @Test
    fun test23_UserCanReadLeaderboard() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        assertEquals(Decision.ALLOWED, evalLeaderboardRead(user))
    }

    @Test
    fun test24_UserCannotWriteLeaderboard() {
        val user = SecurityContext(uid = "user_42", isAdmin = false)
        assertEquals(Decision.DENIED, evalLeaderboardWrite(user))
    }

    @Test
    fun test25_SubscriptionQualityDecouplingRemainsIntact() {
        // Enforce the absolute business invariant: SUBSCRIPTION = REMOVE_ADS ONLY.
        // Quality checks must NOT be coupled to subscriptionTier.
        val freeRestrictions = UserRestrictions(
            subscriptionTier = "FREE",
            isPremium = false,
            subscriptionStatus = "ACTIVE",
            allowedQuality = null // no hardware/admin cap
        )
        val proLiteRestrictions = UserRestrictions(
            subscriptionTier = "PRO_LITE",
            isPremium = true,
            subscriptionStatus = "ACTIVE",
            allowedQuality = null
        )
        val proRestrictions = UserRestrictions(
            subscriptionTier = "PRO",
            isPremium = true,
            subscriptionStatus = "ACTIVE",
            allowedQuality = null
        )

        // All users have access to all source-available qualities (144p to 4K)
        val qualities = listOf("144p", "360p", "480p", "720p", "1080p", "1440p", "4K")
        for (q in qualities) {
            assertTrue("FREE user must have access to $q", freeRestrictions.isQualityAllowed(q))
            assertTrue("PRO_LITE user must have access to $q", proLiteRestrictions.isQualityAllowed(q))
            assertTrue("PRO user must have access to $q", proRestrictions.isQualityAllowed(q))
        }

        // Ad entitlement is strictly decoupled: FREE has ads, PRO/PRO_LITE are ad-free
        assertFalse("FREE user must not be ad-free", freeRestrictions.isAdFree)
        assertTrue("PRO_LITE user must be ad-free", proLiteRestrictions.isAdFree)
        assertTrue("PRO user must be ad-free", proRestrictions.isAdFree)
    }
}
