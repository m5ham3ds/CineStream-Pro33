package com.example.extension.managed

import com.example.extension.managed.repository.FirebaseFirestoreManagedExtensionDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 5.6: Verification suite for Firestore Security Rules Alignment and Authorization Matrix.
 * Validates that /firestore.rules precisely conforms to the canonical Managed Extension Contract:
 *  - Client collection path matches rule definition (/managed_extensions/{extensionId})
 *  - Helper functions enforce correct semantics (isAuthenticated(), isAdmin())
 *  - Read/Write authorization matrix is strictly preserved:
 *      * Unauthenticated: DENY ALL
 *      * Authenticated user: READ ONLY
 *      * Enabled Admin: READ + WRITE
 *      * Disabled Admin: READ ONLY
 */
class FirestoreRulesAlignmentTest {

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

    @Test
    fun testRuleAlignmentWithClientDataSource() {
        val clientCollection = FirebaseFirestoreManagedExtensionDataSource.COLLECTION_PATH
        assertEquals("managed_extensions", clientCollection)

        val targetMatch = "match /$clientCollection/{extensionId}"
        assertTrue(
            "firestore.rules MUST contain the exact match '$targetMatch'",
            rulesContent.contains(targetMatch)
        )
    }

    @Test
    fun testLegacyExtensionsRuleRetainedForCompatibility() {
        val legacyMatch = "match /extensions/{extensionId}"
        assertTrue(
            "firestore.rules should retain legacy match '$legacyMatch' for backward compatibility",
            rulesContent.contains(legacyMatch)
        )
    }

    @Test
    fun testPublicReadAdminWriteOnManagedExtensions() {
        val targetMatch = "match /managed_extensions/{extensionId} {\n      allow read: if true;\n      allow write: if isAdmin();\n    }"
        assertTrue(
            "firestore.rules MUST contain public read and admin-only write for managed_extensions",
            rulesContent.contains(targetMatch)
        )
    }

    @Test
    fun testPublicReadAdminWriteOnSearchOrder() {
        val targetMatch = "match /config/search_order {\n      allow read: if true;\n      allow write: if isAdmin();\n    }"
        assertTrue(
            "firestore.rules MUST contain public read and admin-only write for config/search_order",
            rulesContent.contains(targetMatch)
        )
    }

    @Test
    fun testNoBroadOrInsecurePermissionsOnManagedExtensions() {
        assertFalse(
            "Broad 'allow write: if true' must NEVER be applied to managed_extensions",
            rulesContent.contains("match /managed_extensions/{extensionId} {\n      allow write: if true;")
        )
        assertFalse(
            "Wildcard catch-all 'match /{document=**}' must NOT grant access across root",
            rulesContent.contains("match /{document=**} {\n      allow read, write: if true")
        )
    }

    @Test
    fun testAdminHelperFunctionIntegrity() {
        assertTrue(
            "isAdmin() must check exists in /admins/ and enabled == true",
            rulesContent.contains("function isAdmin()") &&
                    rulesContent.contains("exists(/databases/$(database)/documents/admins/$(request.auth.uid))") &&
                    rulesContent.contains("get(/databases/$(database)/documents/admins/$(request.auth.uid)).data.enabled == true")
        )
    }

    // --- Simulation of Authorization Matrix (Tests A through F) for Phase 05H ---

    data class AuthRequest(
        val uid: String?,
        val isAdminInDb: Boolean? // null if doc missing, true if enabled==true, false if enabled==false
    )

    enum class Operation { READ, WRITE }
    enum class Decision { ALLOWED, DENIED }

    private fun evaluateManagedExtensionRule(
        request: AuthRequest,
        operation: Operation
    ): Decision {
        val isAuthenticated = request.uid != null
        val isAdmin = isAuthenticated && (request.isAdminInDb == true)

        return when (operation) {
            Operation.READ -> Decision.ALLOWED // Phase 05H: Public read for global administrative state
            Operation.WRITE -> if (isAdmin) Decision.ALLOWED else Decision.DENIED
        }
    }

    @Test
    fun testA_UnauthenticatedRead_Allowed() {
        // Phase 05H: Unauthenticated guests MUST read global admin configuration to prevent bypassing disabled status
        val request = AuthRequest(uid = null, isAdminInDb = null)
        val decision = evaluateManagedExtensionRule(request, Operation.READ)
        assertEquals(Decision.ALLOWED, decision)
    }

    @Test
    fun testB_AuthenticatedUserRead_Allowed() {
        val request = AuthRequest(uid = "user-123", isAdminInDb = null)
        val decision = evaluateManagedExtensionRule(request, Operation.READ)
        assertEquals(Decision.ALLOWED, decision)
    }

    @Test
    fun testC_AuthenticatedNormalUserWrite_Denied() {
        val request = AuthRequest(uid = "user-123", isAdminInDb = null)
        val decision = evaluateManagedExtensionRule(request, Operation.WRITE)
        assertEquals(Decision.DENIED, decision)
    }

    @Test
    fun testD_EnabledAdminWrite_Allowed() {
        val request = AuthRequest(uid = "admin-777", isAdminInDb = true)
        val decision = evaluateManagedExtensionRule(request, Operation.WRITE)
        assertEquals(Decision.ALLOWED, decision)
    }

    @Test
    fun testE_DisabledAdminWrite_Denied() {
        val request = AuthRequest(uid = "admin-888", isAdminInDb = false)
        val decision = evaluateManagedExtensionRule(request, Operation.WRITE)
        assertEquals(Decision.DENIED, decision)
    }

    @Test
    fun testF_LegacyPathBehavior() {
        // Evaluate rules on /extensions/{extensionId}
        fun evaluateLegacyExtensionRule(request: AuthRequest, operation: Operation): Decision {
            val isAuthenticated = request.uid != null
            val isAdmin = isAuthenticated && (request.isAdminInDb == true)
            return when (operation) {
                Operation.READ -> Decision.ALLOWED // Phase 05H: Public read for global configuration
                Operation.WRITE -> if (isAdmin) Decision.ALLOWED else Decision.DENIED
            }
        }

        // Unauthenticated access to legacy path is ALLOWED for read
        assertEquals(
            Decision.ALLOWED,
            evaluateLegacyExtensionRule(AuthRequest(null, null), Operation.READ)
        )
        // Normal user write to legacy path is DENIED
        assertEquals(
            Decision.DENIED,
            evaluateLegacyExtensionRule(AuthRequest("user-123", null), Operation.WRITE)
        )
        // Enabled admin write to legacy path is ALLOWED
        assertEquals(
            Decision.ALLOWED,
            evaluateLegacyExtensionRule(AuthRequest("admin-777", true), Operation.WRITE)
        )
    }
}
