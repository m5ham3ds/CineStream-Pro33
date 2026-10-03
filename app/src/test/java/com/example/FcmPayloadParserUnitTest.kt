package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.notification.FcmNotificationPayload
import com.example.data.notification.FcmPayloadConstants
import com.example.data.notification.FcmPayloadParser
import com.example.data.notification.FcmPayloadType
import com.example.data.notification.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FcmPayloadParserUnitTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun test1_validCompletePayload() {
        val data = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "notif_1001",
            FcmPayloadConstants.KEY_TITLE to "New Movie Release",
            FcmPayloadConstants.KEY_BODY to "Watch the latest blockbuster now!",
            FcmPayloadConstants.KEY_TYPE to "movie",
            FcmPayloadConstants.KEY_TARGET to "user",
            FcmPayloadConstants.KEY_TARGET_UID to "user_abc_123",
            FcmPayloadConstants.KEY_IMAGE_URL to "https://example.com/poster.jpg",
            FcmPayloadConstants.KEY_MOVIE_ID to "movie_999",
            FcmPayloadConstants.KEY_NAVIGATE_TO to "movie_details"
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertTrue(payload.isValid)
        assertTrue(payload.validationErrors.isEmpty())
        assertEquals("notif_1001", payload.notificationId)
        assertEquals("New Movie Release", payload.title)
        assertEquals("Watch the latest blockbuster now!", payload.body)
        assertEquals("movie", payload.type)
        assertEquals("user", payload.target)
        assertEquals("user_abc_123", payload.targetUid)
        assertEquals("https://example.com/poster.jpg", payload.imageUrl)
        assertEquals("movie_999", payload.movieId)
        assertEquals("movie_details", payload.navigateTo)
        assertEquals(FcmPayloadType.DATA_ONLY, payload.payloadType)
    }

    @Test
    fun test2_missingNotificationId_generatesSafeFallback() {
        val data = mapOf(
            FcmPayloadConstants.KEY_TITLE to "Flash Alert",
            FcmPayloadConstants.KEY_BODY to "Server maintenance scheduled."
        )

        val payload = FcmPayloadParser.parseRaw(data, messageId = "fcm_msg_id_888")

        assertTrue(payload.isValid)
        assertEquals("fcm_msg_id_888", payload.notificationId)

        // When neither notificationId nor messageId is available, generates a non-empty UUID
        val payloadNoId = FcmPayloadParser.parseRaw(data, messageId = null)
        assertTrue(payloadNoId.isValid)
        assertTrue(payloadNoId.notificationId.isNotBlank())
    }

    @Test
    fun test3_missingTitle_withValidBody() {
        val data = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "id_notitle",
            FcmPayloadConstants.KEY_BODY to "Body content without title."
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertTrue(payload.isValid)
        assertEquals("", payload.title)
        assertEquals("Body content without title.", payload.body)
    }

    @Test
    fun test4_missingBody_withValidTitle() {
        val data = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "id_nobody",
            FcmPayloadConstants.KEY_TITLE to "Only Title"
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertTrue(payload.isValid)
        assertEquals("Only Title", payload.title)
        assertEquals("", payload.body)
    }

    @Test
    fun test5_emptyTitleAndEmptyBody_flagsValidationError() {
        val data = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "id_empty",
            FcmPayloadConstants.KEY_TITLE to "   ",
            FcmPayloadConstants.KEY_BODY to ""
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertFalse(payload.isValid)
        assertTrue(payload.validationErrors.any { it.contains("Both title and body are empty") })
    }

    @Test
    fun test6_unknownType_fallsBackSafely() {
        val data = mapOf(
            FcmPayloadConstants.KEY_TITLE to "Hello",
            FcmPayloadConstants.KEY_BODY to "World",
            FcmPayloadConstants.KEY_TYPE to "UNKNOWN_SPECIAL_TYPE!@#"
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertTrue(payload.isValid)
        // Sanitized to alphanumeric or safe fallback
        assertEquals("unknown_special_type", payload.type)
    }

    @Test
    fun test7_unknownTarget_defaultsToAll() {
        val data = mapOf(
            FcmPayloadConstants.KEY_TITLE to "Update",
            FcmPayloadConstants.KEY_BODY to "System update ready",
            FcmPayloadConstants.KEY_TARGET to "alien_species"
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertTrue(payload.isValid)
        assertEquals(FcmPayloadConstants.TARGET_ALL, payload.target)
    }

    @Test
    fun test8_optionalImageUrl_validHttpsAccepted() {
        val data = mapOf(
            FcmPayloadConstants.KEY_TITLE to "Poster",
            FcmPayloadConstants.KEY_BODY to "New banner",
            FcmPayloadConstants.KEY_IMAGE_URL to "https://cdn.example.com/banner.png"
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertEquals("https://cdn.example.com/banner.png", payload.imageUrl)
    }

    @Test
    fun test9_optionalImageUrl_invalidSchemeRejected() {
        val data = mapOf(
            FcmPayloadConstants.KEY_TITLE to "Poster",
            FcmPayloadConstants.KEY_BODY to "Malicious image test",
            FcmPayloadConstants.KEY_IMAGE_URL to "javascript:alert('attack')"
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertNull(payload.imageUrl)
    }

    @Test
    fun test10_optionalMediaIds_seriesAndEpisode() {
        val data = mapOf(
            FcmPayloadConstants.KEY_TITLE to "Episode Ready",
            FcmPayloadConstants.KEY_BODY to "Episode 5 is now available",
            FcmPayloadConstants.KEY_SERIES_ID to "series_breaking_bad",
            FcmPayloadConstants.KEY_EPISODE_ID to "ep_501"
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertEquals("series_breaking_bad", payload.seriesId)
        assertEquals("ep_501", payload.episodeId)
        assertNull(payload.movieId)
    }

    @Test
    fun test11_optionalNavigateTo_whitelistedDestinationAccepted() {
        val data = mapOf(
            FcmPayloadConstants.KEY_TITLE to "Direct link",
            FcmPayloadConstants.KEY_BODY to "Go to notifications",
            FcmPayloadConstants.KEY_NAVIGATE_TO to "notifications"
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertTrue(payload.isValid)
        assertEquals("notifications", payload.navigateTo)
    }

    @Test
    fun test12_optionalNavigateTo_disallowedDestinationRejected() {
        val data = mapOf(
            FcmPayloadConstants.KEY_TITLE to "Attack link",
            FcmPayloadConstants.KEY_BODY to "Open arbitrary screen",
            FcmPayloadConstants.KEY_NAVIGATE_TO to "android.intent.action.VIEW_MALICIOUS"
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertFalse(payload.isValid)
        assertNull(payload.navigateTo)
        assertTrue(payload.validationErrors.any { it.contains("Disallowed navigateTo destination") })
    }

    @Test
    fun test13_malformedId_sanitizesCharacters() {
        val data = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "../../../etc/passwd!!",
            FcmPayloadConstants.KEY_TITLE to "Directory traversal test",
            FcmPayloadConstants.KEY_BODY to "Safe check"
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertFalse(payload.notificationId.contains("/"))
        assertFalse(payload.notificationId.contains("!"))
        assertTrue(payload.notificationId.isNotBlank())
    }

    @Test
    fun test14_notificationOnlyPayload() {
        val payload = FcmPayloadParser.parseRaw(
            data = null,
            notificationTitle = "Notification Only Title",
            notificationBody = "Notification Only Body",
            notificationImageUrl = "https://example.com/icon.png",
            messageId = "msg_notif_only_123"
        )

        assertEquals(FcmPayloadType.NOTIFICATION_ONLY, payload.payloadType)
        assertEquals("Notification Only Title", payload.title)
        assertEquals("Notification Only Body", payload.body)
        assertEquals("https://example.com/icon.png", payload.imageUrl)
        assertEquals("msg_notif_only_123", payload.notificationId)
        assertTrue(payload.isValid)
    }

    @Test
    fun test15_dataOnlyPayload() {
        val data = mapOf(
            "title" to "Data Title",
            "body" to "Data Body"
        )
        val payload = FcmPayloadParser.parseRaw(data)

        assertEquals(FcmPayloadType.DATA_ONLY, payload.payloadType)
        assertEquals("Data Title", payload.title)
        assertEquals("Data Body", payload.body)
        assertTrue(payload.isValid)
    }

    @Test
    fun test16_notificationAndDataPayload() {
        val data = mapOf(
            "title" to "Data Title Override",
            "body" to "Data Body Override",
            "movieId" to "12345"
        )
        val payload = FcmPayloadParser.parseRaw(
            data = data,
            notificationTitle = "Notification Title",
            notificationBody = "Notification Body",
            messageId = "msg_combined_999"
        )

        assertEquals(FcmPayloadType.NOTIFICATION_AND_DATA, payload.payloadType)
        // Data takes precedence over display notification block for custom targeting
        assertEquals("Data Title Override", payload.title)
        assertEquals("Data Body Override", payload.body)
        assertEquals("12345", payload.movieId)
        assertTrue(payload.isValid)
    }

    @Test
    fun test17_forbiddenSecurityKeys_rejectedWithValidationError() {
        val data = mapOf(
            "title" to "Privilege escalation attempt",
            "body" to "Grant admin",
            "role" to "superadmin",
            "password" to "secret123"
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertFalse(payload.isValid)
        assertTrue(payload.validationErrors.any { it.contains("Forbidden security key") })
    }

    @Test
    fun test18_toNotificationItem_mapsCorrectlyToRoomModel() {
        val payload = FcmNotificationPayload(
            notificationId = "room_id_456",
            title = "Database Ready",
            body = "Item stored in Room",
            type = "announcement",
            imageUrl = "https://example.com/pic.png",
            timestamp = 1715000000000L
        )

        val roomItem = payload.toNotificationItem()

        assertEquals("room_id_456", roomItem.id)
        assertEquals("Database Ready", roomItem.title)
        assertEquals("Item stored in Room", roomItem.message)
        assertEquals("announcement", roomItem.type)
        assertEquals("https://example.com/pic.png", roomItem.imageUrl)
        assertEquals(1715000000000L, roomItem.timestamp)
        assertFalse(roomItem.isRead)
    }

    @Test
    fun test19_backwardCompatibleAliases() {
        val data = mapOf(
            "id" to "alias_id_1",
            "message" to "alias_body_text",
            "targetType" to "user",
            "image_url" to "https://img.example.com/alias.jpg",
            "movie_id" to "mov_77",
            "series_id" to "ser_88",
            "episode_id" to "ep_99",
            "navigate_to" to "downloads"
        )

        val payload = FcmPayloadParser.parseRaw(data)

        assertEquals("alias_id_1", payload.notificationId)
        assertEquals("alias_body_text", payload.body)
        assertEquals("user", payload.target)
        assertEquals("https://img.example.com/alias.jpg", payload.imageUrl)
        assertEquals("mov_77", payload.movieId)
        assertEquals("ser_88", payload.seriesId)
        assertEquals("ep_99", payload.episodeId)
        assertEquals("downloads", payload.navigateTo)
    }

    @Test
    fun test20_notificationChannels_constantsAndCreation() {
        assertEquals("announcements_channel", NotificationChannels.CHANNEL_ANNOUNCEMENTS)
        assertEquals("download_channel", NotificationChannels.CHANNEL_DOWNLOADS)
        assertEquals("stream_download_channel", NotificationChannels.CHANNEL_STREAM_DOWNLOADS)
        assertEquals("p2p_transfer_channel", NotificationChannels.CHANNEL_P2P_TRANSFERS)

        // Safe execution without crashing
        NotificationChannels.createAllChannels(context)
    }
}
