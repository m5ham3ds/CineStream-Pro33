package com.example

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.example.data.notification.FcmNotificationPayload
import com.example.data.notification.FcmPayloadConstants
import com.example.data.notification.FcmPayloadParser
import com.example.data.notification.FcmTargetValidator
import com.example.data.notification.NotificationChannels
import com.example.data.notification.NotificationDeduplicator
import com.example.services.AppFirebaseMessagingService
import com.example.utils.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FcmNotificationDeliveryUnitTest {

    private lateinit var context: Context
    private lateinit var deduplicator: NotificationDeduplicator
    private lateinit var service: AppFirebaseMessagingService

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences("test_dedup_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        deduplicator = NotificationDeduplicator(
            context = context,
            sharedPreferences = prefs,
            notificationDaoProvider = { null }
        )
        deduplicator.clearForTesting()

        service = Robolectric.buildService(AppFirebaseMessagingService::class.java).create().get()
    }

    // 1. Valid FCM data message
    @Test
    fun test01_validFcmDataMessage() {
        val data = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "notif_valid_1",
            FcmPayloadConstants.KEY_TITLE to "Movie Night",
            FcmPayloadConstants.KEY_BODY to "Grab your popcorn and stream now!",
            FcmPayloadConstants.KEY_TARGET to FcmPayloadConstants.TARGET_ALL
        )
        val payload = FcmPayloadParser.parseRaw(data)
        assertTrue(payload.isValid)

        val handled = service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator
        )
        assertTrue("Valid FCM payload should be accepted and delivered", handled)
        assertTrue("ID should be marked in deduplicator", deduplicator.isDuplicate("notif_valid_1"))
    }

    // 2. Invalid payload (e.g. flagged invalid by parser)
    @Test
    fun test02_invalidPayload() {
        val data = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "notif_invalid_#@",
            FcmPayloadConstants.KEY_TITLE to "Invalid Notif",
            "role" to "admin" // Forbidden security key invalidates payload
        )
        val payload = FcmPayloadParser.parseRaw(data)
        assertFalse(payload.isValid)

        val handled = service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator
        )
        assertFalse("Invalid payload should be rejected", handled)
        assertFalse("Deduplicator must not record invalid payload", deduplicator.isDuplicate("notif_invalid_#@"))
    }

    // 3. Missing title and body
    @Test
    fun test03_missingTitleAndBody() {
        val data = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "notif_empty_content",
            FcmPayloadConstants.KEY_TITLE to "   ",
            FcmPayloadConstants.KEY_BODY to ""
        )
        val payload = FcmPayloadParser.parseRaw(data)

        val handled = service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator
        )
        assertFalse("Payload missing both title and body must be rejected", handled)
    }

    // 4. Target all
    @Test
    fun test04_targetAll() {
        val payload = FcmNotificationPayload(
            notificationId = "notif_target_all",
            title = "Public Announcement",
            body = "Broadcast to all",
            target = FcmPayloadConstants.TARGET_ALL,
            targetUid = null
        )

        // Guest user
        assertTrue(FcmTargetValidator.isTargetValid(payload, currentUid = null, isAnonymous = true))
        // Authenticated user
        assertTrue(FcmTargetValidator.isTargetValid(payload, currentUid = "user_999", isAnonymous = false))

        val delivered = service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = true,
            deduplicatorOverride = deduplicator
        )
        assertTrue("target=all should be delivered to any user", delivered)
    }

    // 5. Target user matching current user
    @Test
    fun test05_targetUser_matchingCurrentUser() {
        val payload = FcmNotificationPayload(
            notificationId = "notif_target_user_match",
            title = "Personal Alert",
            body = "Only for you",
            target = FcmPayloadConstants.TARGET_USER,
            targetUid = "user_alice_42"
        )

        assertTrue(
            FcmTargetValidator.isTargetValid(
                payload = payload,
                currentUid = "user_alice_42",
                isAnonymous = false
            )
        )

        val delivered = service.handlePayload(
            payload = payload,
            currentUidOverride = "user_alice_42",
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator
        )
        assertTrue("target=user should be delivered when UID matches", delivered)
    }

    // 6. Target user mismatching current user
    @Test
    fun test06_targetUser_mismatchingCurrentUser() {
        val payload = FcmNotificationPayload(
            notificationId = "notif_target_user_mismatch",
            title = "Personal Alert",
            body = "Only for you",
            target = FcmPayloadConstants.TARGET_USER,
            targetUid = "user_alice_42"
        )

        // Mismatched UID
        assertFalse(
            FcmTargetValidator.isTargetValid(
                payload = payload,
                currentUid = "user_bob_99",
                isAnonymous = false
            )
        )
        // Guest user with matching targetUid
        assertFalse(
            FcmTargetValidator.isTargetValid(
                payload = payload,
                currentUid = "user_alice_42",
                isAnonymous = true
            )
        )

        val deliveredMismatch = service.handlePayload(
            payload = payload,
            currentUidOverride = "user_bob_99",
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator
        )
        assertFalse("Mismatched user must not receive notification", deliveredMismatch)
    }

    // 7. Unknown target
    @Test
    fun test07_unknownTarget() {
        val payloadTopic = FcmNotificationPayload(
            notificationId = "notif_topic",
            title = "Topic Notif",
            body = "Msg",
            target = FcmPayloadConstants.TARGET_TOPIC
        )
        assertFalse("Topic target must be rejected in Phase 2B", FcmTargetValidator.isTargetValid(payloadTopic, "uid", false))

        val payloadUnknown = FcmNotificationPayload(
            notificationId = "notif_unknown",
            title = "Unknown Target Notif",
            body = "Msg",
            target = "super_admin_group"
        )
        assertFalse("Unknown target must be rejected", FcmTargetValidator.isTargetValid(payloadUnknown, "uid", false))

        val delivered = service.handlePayload(
            payload = payloadUnknown,
            currentUidOverride = "uid",
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator
        )
        assertFalse("Payload with unknown target must not be delivered", delivered)
    }

    // 8. Duplicate notificationId
    @Test
    fun test08_duplicateNotificationId() {
        deduplicator.markProcessed("existing_id_555")
        assertTrue(deduplicator.isDuplicate("existing_id_555"))

        val payload = FcmNotificationPayload(
            notificationId = "existing_id_555",
            title = "Duplicate Test",
            body = "Should not display",
            target = FcmPayloadConstants.TARGET_ALL
        )

        val delivered = service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator
        )
        assertFalse("Duplicate notificationId must be suppressed", delivered)
    }

    // 9. First notificationId
    @Test
    fun test09_firstNotificationId() {
        assertFalse(deduplicator.isDuplicate("fresh_id_101"))

        val payload = FcmNotificationPayload(
            notificationId = "fresh_id_101",
            title = "First Arrival",
            body = "Fresh alert",
            target = FcmPayloadConstants.TARGET_ALL
        )

        val delivered = service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator
        )
        assertTrue("First arrival must be delivered", delivered)
        assertTrue("ID must now be recorded in deduplicator", deduplicator.isDuplicate("fresh_id_101"))
    }

    // 10. Same notificationId twice
    @Test
    fun test10_sameNotificationIdTwice() {
        val payload = FcmNotificationPayload(
            notificationId = "twice_id_777",
            title = "Idempotent Alert",
            body = "Test body",
            target = FcmPayloadConstants.TARGET_ALL
        )

        val firstArrival = service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator
        )
        assertTrue("First delivery should succeed", firstArrival)

        val secondArrival = service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator
        )
        assertFalse("Second arrival of identical ID must be rejected as duplicate", secondArrival)
    }

    // 11. PendingIntent configuration
    @Test
    fun test11_pendingIntentConfiguration() {
        val builder = NotificationHelper.buildGeneralNotification(
            context = context,
            id = "test_pi_id",
            title = "PendingIntent Test",
            message = "Check intent flags"
        )
        assertNotNull("Builder should not be null", builder)

        val notification = builder!!.build()
        assertNotNull("Notification contentIntent must be set", notification.contentIntent)

        val shadowPendingIntent = Shadows.shadowOf(notification.contentIntent)
        assertTrue("PendingIntent must target an Activity", shadowPendingIntent.isActivityIntent)

        val savedIntent = shadowPendingIntent.savedIntent
        assertNotNull("Saved intent must not be null", savedIntent)
        assertEquals(
            "Target activity must strictly be MainActivity",
            "com.example.MainActivity",
            savedIntent.component?.className
        )
        assertEquals("notificationId must be forwarded in intent extras", "test_pi_id", savedIntent.getStringExtra("notificationId"))
    }

    // 12. Notification channel
    @Test
    fun test12_notificationChannel() {
        val builder = NotificationHelper.buildGeneralNotification(
            context = context,
            id = "test_channel_id",
            title = "Channel Test",
            message = "Verify announcements channel"
        )
        assertNotNull(builder)

        val notification = builder!!.build()
        assertEquals(
            "Channel ID must match centralized CHANNEL_ANNOUNCEMENTS",
            NotificationChannels.CHANNEL_ANNOUNCEMENTS,
            notification.channelId
        )
        assertEquals(
            "Notification priority should be HIGH",
            NotificationCompat.PRIORITY_HIGH,
            notification.priority
        )
    }

    // 13. Long body
    @Test
    fun test13_longBody() {
        val longText = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(10).trim()
        val builder = NotificationHelper.buildGeneralNotification(
            context = context,
            id = "test_long_body",
            title = "Long Announcement",
            message = longText
        )
        assertNotNull(builder)

        val notification = builder!!.build()
        val bigText = notification.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)
        assertEquals("BigTextStyle should preserve complete long message body", longText, bigText?.toString())
    }

    // 14. Malformed navigateTo
    @Test
    fun test14_malformedNavigateTo() {
        val data = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "notif_malicious_nav",
            FcmPayloadConstants.KEY_TITLE to "Nav Alert",
            FcmPayloadConstants.KEY_BODY to "Testing bad navigateTo",
            FcmPayloadConstants.KEY_NAVIGATE_TO to "android.intent.action.VIEW_MALICIOUS_ACTIVITY"
        )
        val payload = FcmPayloadParser.parseRaw(data)
        assertNull("Disallowed navigation target must be rejected/nulled by parser", payload.navigateTo)
    }

    // 15. Forbidden security key
    @Test
    fun test15_forbiddenSecurityKey() {
        val data = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "sec_test_01",
            FcmPayloadConstants.KEY_TITLE to "Privilege Escalation Attempt",
            FcmPayloadConstants.KEY_BODY to "Attaching admin key",
            "isAdmin" to "true"
        )
        val payload = FcmPayloadParser.parseRaw(data)
        assertFalse("Payload with forbidden security key must be invalid", payload.isValid)
        assertTrue("Validation errors should mention forbidden key", payload.validationErrors.any { it.contains("isAdmin") })

        val delivered = service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator
        )
        assertFalse("Payload containing forbidden security keys must be dropped", delivered)
    }

    // 16. ImageUrl ignored in Phase 2B
    @Test
    fun test16_imageUrlIgnoredInPhase2B() {
        val imageUrl = "https://example.com/movie_backdrop.png"
        val data = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "notif_with_image",
            FcmPayloadConstants.KEY_TITLE to "New Poster",
            FcmPayloadConstants.KEY_BODY to "Check out the poster",
            FcmPayloadConstants.KEY_IMAGE_URL to imageUrl
        )
        val payload = FcmPayloadParser.parseRaw(data)
        assertEquals("ImageUrl should be retained in payload model for future phases", imageUrl, payload.imageUrl)

        val builder = NotificationHelper.buildGeneralNotification(
            context = context,
            id = payload.notificationId,
            title = payload.title,
            message = payload.body
        )
        assertNotNull(builder)

        val notification = builder!!.build()
        // Ensure BigPictureStyle is NOT attached to the notification in Phase 2B
        val pictureExtra = notification.extras.get(NotificationCompat.EXTRA_PICTURE)
        assertNull("Phase 2B must not load or attach BigPicture images to notification", pictureExtra)
    }

    // 17. Atomic Deduplication checkAndMarkProcessed prevents race conditions
    @Test
    fun test17_atomicCheckAndMarkProcessed_preventsRaceConditions() {
        val testId = "atomic_race_check_id_999"
        assertFalse("First call to checkAndMarkProcessed must return false (not duplicate)", deduplicator.checkAndMarkProcessed(testId))
        assertTrue("Subsequent call to checkAndMarkProcessed must return true (duplicate detected)", deduplicator.checkAndMarkProcessed(testId))
        assertTrue("Standard isDuplicate query must now return true", deduplicator.isDuplicate(testId))
    }

    // 18. Shared deduplication prevents FCM <-> Firestore race conditions
    @Test
    fun test18_fcmFirestoreSharedDeduplication_preventsCrossSourceDuplicate() {
        val sharedId = "shared_fcm_firestore_id_101"

        // Simulate Firestore processing and marking ID first
        val isDuplicateForFirestore = deduplicator.checkAndMarkProcessed(sharedId)
        assertFalse("Firestore first check must return false (not duplicate yet)", isDuplicateForFirestore)

        // Now FCM arrives with the same ID
        val data = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to sharedId,
            FcmPayloadConstants.KEY_TITLE to "Movie Update",
            FcmPayloadConstants.KEY_BODY to "New episodes added",
            FcmPayloadConstants.KEY_TARGET to FcmPayloadConstants.TARGET_ALL
        )
        val payload = FcmPayloadParser.parseRaw(data)
        val deliveredByFcm = service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator
        )

        assertFalse("FCM must detect that Firestore has already marked this ID and skip delivery", deliveredByFcm)
    }
}

