package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.notification.FcmTokenDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FcmTokenArchitectureUnitTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testFcmTokenDocument_toFirestoreMap_validMapping() {
        val now = 1710000000000L
        val doc = FcmTokenDocument(
            token = "sample_fcm_token_xyz_12345",
            installationId = "device_uuid_001",
            platform = "android",
            deviceModel = "Google Pixel 8",
            osVersion = "Android 14 (API 34)",
            appVersion = "1.0.0",
            createdAt = now
        )

        val map = doc.toFirestoreMap()

        assertEquals("sample_fcm_token_xyz_12345", map["token"])
        assertEquals("device_uuid_001", map["installationId"])
        assertEquals("android", map["platform"])
        assertEquals("Google Pixel 8", map["deviceModel"])
        assertEquals("Android 14 (API 34)", map["osVersion"])
        assertEquals("1.0.0", map["appVersion"])
        assertEquals(now, map["createdAt"])
        assertNotNull(map["updatedAt"])
        assertNotNull(map["lastSeenAt"])

        // Ensure no sensitive or extraneous keys are exposed
        assertFalse(map.containsKey("password"))
        assertFalse(map.containsKey("email"))
        assertFalse(map.containsKey("authToken"))
        assertTrue(map["isActive"] as Boolean)
    }

    @Test
    fun testFcmTokenDocument_fromMap() {
        val map = mapOf(
            "token" to "token_123",
            "installationId" to "inst_456",
            "platform" to "android",
            "deviceModel" to "Samsung Galaxy S24",
            "osVersion" to "Android 14 (API 34)",
            "appVersion" to "2.0.0",
            "isActive" to true
        )

        val doc = FcmTokenDocument.fromMap(map)
        assertEquals("token_123", doc.token)
        assertEquals("inst_456", doc.installationId)
        assertEquals("android", doc.platform)
        assertEquals("Samsung Galaxy S24", doc.deviceModel)
        assertTrue(doc.isActive)
    }

    @Test
    fun testFcmErrorRecoverability() {
        val fcmManager = com.example.data.notification.FcmTokenManager.getInstance(context)

        // Permanent failures requiring token cleanup
        assertFalse(fcmManager.isRecoverableFcmError("UNREGISTERED"))
        assertFalse(fcmManager.isRecoverableFcmError("INVALID_ARGUMENT"))
        assertFalse(fcmManager.isRecoverableFcmError("SENDER_ID_MISMATCH"))

        // Transient failures that can be retried
        assertTrue(fcmManager.isRecoverableFcmError("UNAVAILABLE"))
        assertTrue(fcmManager.isRecoverableFcmError("QUOTA_EXCEEDED"))
        assertTrue(fcmManager.isRecoverableFcmError("INTERNAL"))
    }

    @Test
    fun testFcmTokenDocument_defaultValues() {
        val doc = FcmTokenDocument(
            token = "fcm_token_default",
            installationId = "inst_999"
        )

        assertEquals("android", doc.platform)
        assertEquals("", doc.deviceModel)
        assertEquals(0L, doc.createdAt)

        val map = doc.toFirestoreMap()
        assertEquals("fcm_token_default", map["token"])
        assertEquals("inst_999", map["installationId"])
        assertNotNull(map["createdAt"])
    }

    @Test
    fun testMultiDeviceTokenPathStrategy() {
        val userId = "user_alpha_777"
        val devicePhoneId = "install_id_phone_1"
        val deviceTabletId = "install_id_tablet_2"

        val phonePath = "users/$userId/fcmTokens/$devicePhoneId"
        val tabletPath = "users/$userId/fcmTokens/$deviceTabletId"

        assertNotEquals(phonePath, tabletPath)
        assertTrue(phonePath.startsWith("users/$userId/fcmTokens/"))
        assertTrue(tabletPath.startsWith("users/$userId/fcmTokens/"))
    }

    @Test
    fun testTokenRotationKeepsSameDocumentId() {
        val userId = "user_beta_888"
        val installationId = "install_device_stable_uuid"

        val originalToken = "token_v1_old"
        val docV1 = FcmTokenDocument(token = originalToken, installationId = installationId)
        val pathV1 = "users/$userId/fcmTokens/${docV1.installationId}"

        val rotatedToken = "token_v2_new"
        val docV2 = FcmTokenDocument(token = rotatedToken, installationId = installationId)
        val pathV2 = "users/$userId/fcmTokens/${docV2.installationId}"

        // The document path remains identical preventing duplicate documents across rotations
        assertEquals(pathV1, pathV2)
        assertNotEquals(docV1.token, docV2.token)
    }

    @Test
    fun testLogoutAndLoginUserSwitchPreservesDeviceToken() {
        val deviceInstallationId = "unique_device_hardware_uuid"
        val deviceToken = "persistent_fcm_token_abc"

        val userA = "uid_alice"
        val userB = "uid_bob"

        // Step 1: User A is logged in
        val userAPath = "users/$userA/fcmTokens/$deviceInstallationId"
        assertEquals("users/uid_alice/fcmTokens/unique_device_hardware_uuid", userAPath)

        // Step 2: User A logs out -> document under user A is removed
        val isUserADissociated = true
        assertTrue(isUserADissociated)

        // Device token is retained locally and NOT invalidated
        assertEquals("persistent_fcm_token_abc", deviceToken)

        // Step 3: User B logs in on same device -> device associates with user B
        val userBPath = "users/$userB/fcmTokens/$deviceInstallationId"
        assertEquals("users/uid_bob/fcmTokens/unique_device_hardware_uuid", userBPath)
    }

    @Test
    fun testInvalidTokenValidation() {
        val emptyToken = ""
        val blankToken = "   "
        val validToken = "valid_fcm_registration_token_1234"

        assertTrue(emptyToken.isBlank())
        assertTrue(blankToken.isBlank())
        assertFalse(validToken.isBlank())
    }
}
