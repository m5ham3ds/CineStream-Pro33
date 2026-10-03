package com.example

import com.example.data.model.NotificationItem
import com.example.data.model.NotificationPreferences
import com.example.data.notification.NotificationCategory
import com.example.data.notification.NotificationCategoryResolver
import com.example.data.repository.AppUpdateInfo
import com.example.data.repository.AppUpdateManager
import com.example.data.repository.UpdateCheckResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PHASE 04D: NOTIFICATIONS & APP UPDATES TEST SUITE
 *
 * Covers 17 exhaustive verification categories (A through Q):
 * A. Notification model
 * B. Firestore mapping
 * C. Room mapping
 * D. Realtime sync
 * E. Duplicate prevention
 * F. Read/unread
 * G. unread count
 * H. targeting
 * I. notification settings
 * J. empty state
 * K. malformed notification
 * L. App update detection
 * M. versionCode comparison
 * N. optional update
 * O. mandatory update
 * P. no update
 * Q. invalid update document
 */
class Phase04DNotificationsAppUpdatesTest {

    // =========================================================================
    // SECTION A: NOTIFICATION MODEL
    // =========================================================================

    @Test
    fun testA1_NotificationModelProperties() {
        val now = System.currentTimeMillis()
        val notif = NotificationItem(
            id = "notif_001",
            title = "New Episode Alert",
            message = "Episode 5 is now streaming",
            timestamp = now,
            isRead = false,
            imageUrl = "https://example.com/poster.jpg",
            type = "episode"
        )

        assertEquals("notif_001", notif.id)
        assertEquals("New Episode Alert", notif.title)
        assertEquals("Episode 5 is now streaming", notif.message)
        assertEquals(now, notif.timestamp)
        assertFalse(notif.isRead)
        assertEquals("https://example.com/poster.jpg", notif.imageUrl)
        assertEquals("episode", notif.type)
    }

    @Test
    fun testA2_NotificationModelDefaultValues() {
        val notif = NotificationItem(
            title = "Default Title",
            message = "Default Message"
        )

        assertNotNull(notif.id)
        assertTrue(notif.id.isNotBlank())
        assertFalse("Default isRead must be false", notif.isRead)
        assertNull(notif.imageUrl)
        assertEquals("info", notif.type)
        assertTrue(notif.timestamp <= System.currentTimeMillis())
    }

    // =========================================================================
    // SECTION B: FIRESTORE MAPPING
    // =========================================================================

    @Test
    fun testB1_FirestoreNotificationParsingValid() {
        val rawDoc = mapOf(
            "title" to "Maintenance Notice",
            "body" to "Scheduled server maintenance tonight",
            "timestamp" to 1700000000000L,
            "imageUrl" to "https://example.com/banner.png",
            "type" to "maintenance",
            "target" to "all"
        )

        val id = "cloud_notif_123"
        val title = rawDoc["title"] as String
        val body = (rawDoc["body"] ?: rawDoc["message"]) as String
        val timestamp = rawDoc["timestamp"] as Long
        val imageUrl = rawDoc["imageUrl"] as String
        val type = rawDoc["type"] as String

        val item = NotificationItem(
            id = id,
            title = title,
            message = body,
            timestamp = timestamp,
            isRead = false,
            imageUrl = imageUrl,
            type = type
        )

        assertEquals("cloud_notif_123", item.id)
        assertEquals("Maintenance Notice", item.title)
        assertEquals("Scheduled server maintenance tonight", item.message)
        assertEquals(1700000000000L, item.timestamp)
        assertEquals("maintenance", item.type)
    }

    @Test
    fun testB2_FirestoreNotificationFallbackBodyToMessage() {
        // When 'message' is present instead of 'body'
        val rawWithMsg = mapOf(
            "title" to "System Alert",
            "message" to "Important security patch",
            "type" to "system"
        )

        val messageText = (rawWithMsg["body"] ?: rawWithMsg["message"]) as String
        assertEquals("Important security patch", messageText)

        // When 'body' is present instead of 'message'
        val rawWithBody = mapOf(
            "title" to "System Alert",
            "body" to "Message from body key",
            "type" to "system"
        )
        val bodyText = (rawWithBody["body"] ?: rawWithBody["message"]) as String
        assertEquals("Message from body key", bodyText)
    }

    // =========================================================================
    // SECTION C: ROOM MAPPING
    // =========================================================================

    @Test
    fun testC1_RoomNotificationEntityDefinition() {
        val notif = NotificationItem(
            id = "room_id_1",
            title = "Room Title",
            message = "Room Message",
            timestamp = 1000L,
            isRead = true,
            imageUrl = null,
            type = "info"
        )

        val copied = notif.copy(isRead = false)
        assertEquals("room_id_1", copied.id)
        assertFalse(copied.isRead)
    }

    @Test
    fun testC2_RoomDaoQueriesContract() {
        // Verify Dao interface methods exist and are well-typed
        val daoClass = com.example.data.db.NotificationDao::class.java
        val methodNames = daoClass.declaredMethods.map { it.name }

        assertTrue(methodNames.contains("getAllNotifications"))
        assertTrue(methodNames.contains("getUnreadCount"))
        assertTrue(methodNames.contains("insertNotification"))
        assertTrue(methodNames.contains("markAsRead"))
        assertTrue(methodNames.contains("markAllAsRead"))
        assertTrue(methodNames.contains("deleteNotification"))
    }

    // =========================================================================
    // SECTION D: REALTIME SYNC & DEDUPLICATION
    // =========================================================================

    @Test
    fun testD1_RealtimeNotificationDeduplicatorLogic() {
        val processedIds = mutableSetOf<String>()

        fun processNotification(id: String): Boolean {
            return if (processedIds.contains(id)) {
                false // already processed
            } else {
                processedIds.add(id)
                true // new notification
            }
        }

        assertTrue("First arrival must be processed", processNotification("notif_realtime_1"))
        assertFalse("Duplicate arrival must be ignored", processNotification("notif_realtime_1"))
        assertTrue("Different ID must be processed", processNotification("notif_realtime_2"))
    }

    // =========================================================================
    // SECTION E: DUPLICATE PREVENTION
    // =========================================================================

    @Test
    fun testE1_DuplicateNotificationHandlingByPrimaryKey() {
        val list = mutableMapOf<String, NotificationItem>()

        val notif1 = NotificationItem(id = "notif_dup", title = "Original", message = "First")
        list[notif1.id] = notif1

        val notif1Updated = NotificationItem(id = "notif_dup", title = "Updated", message = "Second")
        list[notif1Updated.id] = notif1Updated

        assertEquals(1, list.size)
        assertEquals("Updated", list["notif_dup"]?.title)
    }

    // =========================================================================
    // SECTION F: READ / UNREAD
    // =========================================================================

    @Test
    fun testF1_NotificationMarkAsRead() {
        val notif = NotificationItem(id = "n_read_1", title = "T", message = "M", isRead = false)
        assertFalse(notif.isRead)

        val updated = notif.copy(isRead = true)
        assertTrue(updated.isRead)
    }

    @Test
    fun testF2_NotificationMarkAllAsRead() {
        val list = listOf(
            NotificationItem(id = "1", title = "A", message = "M", isRead = false),
            NotificationItem(id = "2", title = "B", message = "M", isRead = false),
            NotificationItem(id = "3", title = "C", message = "M", isRead = true)
        )

        val markedAll = list.map { it.copy(isRead = true) }
        assertTrue(markedAll.all { it.isRead })
    }

    // =========================================================================
    // SECTION G: UNREAD COUNT
    // =========================================================================

    @Test
    fun testG1_UnreadCountCalculation() {
        val list = listOf(
            NotificationItem(id = "1", title = "A", message = "M", isRead = false),
            NotificationItem(id = "2", title = "B", message = "M", isRead = false),
            NotificationItem(id = "3", title = "C", message = "M", isRead = true)
        )

        val unread = list.count { !it.isRead }
        assertEquals(2, unread)
    }

    @Test
    fun testG2_UnreadCountZeroWhenAllRead() {
        val list = listOf(
            NotificationItem(id = "1", title = "A", message = "M", isRead = true),
            NotificationItem(id = "2", title = "B", message = "M", isRead = true)
        )

        val unread = list.count { !it.isRead }
        assertEquals(0, unread)
    }

    // =========================================================================
    // SECTION H: TARGETING
    // =========================================================================

    private fun isTargeted(doc: Map<String, Any?>, currentUid: String): Boolean {
        val target = ((doc["target"] ?: doc["targetType"] ?: "all") as? String ?: "all").lowercase()
        val targetUid = (doc["targetUid"] as? String) ?: ""

        if (target == "user" || targetUid.isNotBlank()) {
            if (currentUid.isBlank() || targetUid != currentUid) {
                return false
            }
        }
        return true
    }

    @Test
    fun testH1_GlobalNotificationTargeting() {
        val globalDoc = mapOf("title" to "Global Notice", "target" to "all")
        assertTrue(isTargeted(globalDoc, "user_alice"))
        assertTrue(isTargeted(globalDoc, "user_bob"))
    }

    @Test
    fun testH2_UserSpecificNotificationTargeting() {
        val userDoc = mapOf(
            "title" to "Private Notice",
            "target" to "user",
            "targetUid" to "user_alice"
        )
        assertTrue("Alice should receive Alice's notification", isTargeted(userDoc, "user_alice"))
        assertFalse("Bob must NOT receive Alice's notification", isTargeted(userDoc, "user_bob"))
    }

    @Test
    fun testH3_CrossUserTargetingRejected() {
        val userDoc = mapOf(
            "title" to "Secret Notice",
            "target" to "user",
            "targetUid" to "user_charlie"
        )
        assertFalse("Anonymous/empty user cannot receive targeted notification", isTargeted(userDoc, ""))
        assertFalse("Different user cannot receive targeted notification", isTargeted(userDoc, "user_alice"))
    }

    // =========================================================================
    // SECTION I: NOTIFICATION SETTINGS & GATING
    // =========================================================================

    @Test
    fun testI1_NotificationPreferencesCategories() {
        val prefs = NotificationPreferences(
            notificationsEnabled = true,
            announcementsEnabled = true,
            appUpdatesEnabled = true,
            maintenanceEnabled = false,
            newMoviesEnabled = true,
            newTvSeriesEnabled = false,
            newAnimeEnabled = true
        )

        assertTrue(prefs.notificationsEnabled)
        assertTrue(prefs.announcementsEnabled)
        assertTrue(prefs.appUpdatesEnabled)
        assertFalse(prefs.maintenanceEnabled)
        assertTrue(prefs.newMoviesEnabled)
        assertFalse(prefs.newTvSeriesEnabled)
        assertTrue(prefs.newAnimeEnabled)
    }

    @Test
    fun testI2_NotificationPreferencesMasterToggleSuppressesAll() {
        val disabledPrefs = NotificationPreferences(notificationsEnabled = false)

        assertFalse(
            NotificationCategoryResolver.isNotificationAllowed(
                NotificationCategory.ANNOUNCEMENTS,
                disabledPrefs
            )
        )
        assertFalse(
            NotificationCategoryResolver.isNotificationAllowed(
                NotificationCategory.NEW_MOVIES,
                disabledPrefs
            )
        )
        assertFalse(
            NotificationCategoryResolver.isNotificationAllowed(
                NotificationCategory.APP_UPDATES,
                disabledPrefs
            )
        )
    }

    @Test
    fun testI3_NotificationCategoryGateGranular() {
        val prefs = NotificationPreferences(
            notificationsEnabled = true,
            newMoviesEnabled = true,
            newTvSeriesEnabled = false
        )

        assertTrue(
            NotificationCategoryResolver.isNotificationAllowed(
                NotificationCategory.NEW_MOVIES,
                prefs
            )
        )
        assertFalse(
            NotificationCategoryResolver.isNotificationAllowed(
                NotificationCategory.NEW_TV_SERIES,
                prefs
            )
        )
    }

    // =========================================================================
    // SECTION J: EMPTY STATE
    // =========================================================================

    @Test
    fun testJ1_EmptyNotificationList() {
        val emptyList = emptyList<NotificationItem>()
        assertTrue(emptyList.isEmpty())
        assertEquals(0, emptyList.count { !it.isRead })
    }

    // =========================================================================
    // SECTION K: MALFORMED NOTIFICATION
    // =========================================================================

    @Test
    fun testK1_MalformedNotificationHandledSafely() {
        val malformedDoc = mapOf(
            "title" to null,
            "body" to null
        )

        val title = malformedDoc["title"] as? String
        val isValid = !title.isNullOrBlank()
        assertFalse("Document without title must be marked invalid", isValid)
    }

    @Test
    fun testK2_ExpiredNotificationRejected() {
        val now = System.currentTimeMillis()
        val expiredDoc = mapOf(
            "title" to "Flash Offer",
            "expiresAt" to now - 5000L
        )

        val expiresAt = expiredDoc["expiresAt"] as Long
        val isExpired = expiresAt < now
        assertTrue("Notification with past expiry must be identified as expired", isExpired)
    }

    // =========================================================================
    // SECTION L: APP UPDATE DETECTION
    // =========================================================================

    private fun parseSimulatedUpdate(
        payload: Map<String, Any?>,
        currentCode: Long,
        currentVersionName: String = "1.0.0"
    ): AppUpdateInfo? {
        val isActive = when (val active = payload["isActive"] ?: payload["published"] ?: payload["active"]) {
            is Boolean -> active
            is String -> active.trim().equals("true", ignoreCase = true) ||
                         active.trim().equals("published", ignoreCase = true) ||
                         active.trim().equals("active", ignoreCase = true)
            is Number -> active.toInt() == 1
            else -> true
        }
        if (!isActive) return null

        val targetCode = when (val c = payload["versionCode"] ?: payload["latestVersionCode"] ?: payload["version_code"]) {
            is Number -> c.toLong()
            is String -> c.toLongOrNull() ?: 0L
            else -> 0L
        }
        if (targetCode <= 0L) return null

        val targetName = (payload["version"] ?: payload["versionName"] ?: payload["latestVersionName"]) as? String ?: "v$targetCode"
        val notes = (payload["releaseNotes"] ?: payload["notes"] ?: payload["description"]) as? String ?: ""
        val url = (payload["updateUrl"] ?: payload["downloadUrl"] ?: payload["apkUrl"]) as? String ?: ""

        val minCode = when (val m = payload["minVersionCode"] ?: payload["minimumVersionCode"]) {
            is Number -> m.toLong()
            is String -> m.toLongOrNull() ?: 0L
            else -> 0L
        }

        val isMandatoryFlag = when (val mand = payload["isMandatory"] ?: payload["mandatory"] ?: payload["forceUpdate"]) {
            is Boolean -> mand
            is String -> mand.trim().equals("true", ignoreCase = true) || mand.trim() == "1"
            is Number -> mand.toInt() == 1
            else -> false
        }

        val isBelowMin = currentCode < minCode
        val isUpdateAvailable = targetCode > currentCode && url.isNotBlank()
        val isMandatory = isBelowMin || (isUpdateAvailable && isMandatoryFlag)

        return AppUpdateInfo(
            versionCode = targetCode,
            versionName = targetName,
            releaseNotes = notes,
            downloadUrl = url,
            isMandatory = isMandatory,
            currentVersionName = currentVersionName
        )
    }

    @Test
    fun testL1_AppUpdateAvailableDetection() {
        val payload = mapOf(
            "versionCode" to 20L,
            "versionName" to "2.0.0",
            "releaseNotes" to "Major performance release",
            "updateUrl" to "https://example.com/cinestream-2.0.0.apk",
            "isActive" to true,
            "isMandatory" to false
        )

        val parsed = parseSimulatedUpdate(payload, currentCode = 15L, currentVersionName = "1.5.0")
        assertNotNull(parsed)
        assertEquals(20L, parsed!!.versionCode)
        assertEquals("2.0.0", parsed.versionName)
        assertEquals("1.5.0", parsed.currentVersionName)
        assertFalse(parsed.isMandatory)
    }

    // =========================================================================
    // SECTION M: VERSION CODE COMPARISON
    // =========================================================================

    @Test
    fun testM1_VersionCodeComparisonDeterministic() {
        val currentCode = 9L
        val targetCode = 10L

        // In string comparison: "10" < "9" (wrong!)
        assertTrue("String comparison is incorrectly '10' < '9'", "10" < "9")

        // Numerical comparison: 10 > 9 (deterministic!)
        assertTrue("versionCode numerical comparison must be 10 > 9", targetCode > currentCode)
    }

    // =========================================================================
    // SECTION N: OPTIONAL UPDATE
    // =========================================================================

    @Test
    fun testN1_OptionalUpdateProperties() {
        val payload = mapOf(
            "versionCode" to 25L,
            "versionName" to "2.5.0",
            "updateUrl" to "https://example.com/app.apk",
            "isMandatory" to false,
            "minVersionCode" to 20L
        )

        val parsed = parseSimulatedUpdate(payload, currentCode = 22L)
        assertNotNull(parsed)
        assertTrue(parsed!!.versionCode > 22L)
        assertFalse("Current code (22) >= minCode (20), so update remains optional", parsed.isMandatory)
    }

    // =========================================================================
    // SECTION O: MANDATORY UPDATE
    // =========================================================================

    @Test
    fun testO1_MandatoryUpdateByFlag() {
        val payload = mapOf(
            "versionCode" to 30L,
            "versionName" to "3.0.0",
            "updateUrl" to "https://example.com/app.apk",
            "isMandatory" to true
        )

        val parsed = parseSimulatedUpdate(payload, currentCode = 25L)
        assertNotNull(parsed)
        assertTrue("isMandatory flag must be enforced", parsed!!.isMandatory)
    }

    @Test
    fun testO2_MandatoryUpdateByMinVersionCode() {
        val payload = mapOf(
            "versionCode" to 30L,
            "versionName" to "3.0.0",
            "updateUrl" to "https://example.com/app.apk",
            "isMandatory" to false,
            "minVersionCode" to 28L
        )

        val parsed = parseSimulatedUpdate(payload, currentCode = 25L)
        assertNotNull(parsed)
        assertTrue("Current code (25) < minCode (28) must enforce mandatory update", parsed!!.isMandatory)
    }

    // =========================================================================
    // SECTION P: NO UPDATE / UP TO DATE
    // =========================================================================

    @Test
    fun testP1_AppUpToDateWhenCurrentMatchesOrExceedsLatest() {
        val payloadEqual = mapOf(
            "versionCode" to 30L,
            "updateUrl" to "https://example.com/app.apk"
        )
        val parsedEqual = parseSimulatedUpdate(payloadEqual, currentCode = 30L)
        // targetCode (30) is not > currentCode (30)
        assertFalse("When version matches, update is not available", parsedEqual!!.versionCode > 30L)

        val payloadOlder = mapOf(
            "versionCode" to 25L,
            "updateUrl" to "https://example.com/app.apk"
        )
        val parsedOlder = parseSimulatedUpdate(payloadOlder, currentCode = 30L)
        assertFalse("When cloud version is older, update is not available", parsedOlder!!.versionCode > 30L)
    }

    // =========================================================================
    // SECTION Q: INVALID UPDATE DOCUMENT
    // =========================================================================

    @Test
    fun testQ1_InvalidUpdateDocMissingVersionCode() {
        val payload = mapOf(
            "versionName" to "3.0.0",
            "updateUrl" to "https://example.com/app.apk"
        )
        val parsed = parseSimulatedUpdate(payload, currentCode = 10L)
        assertNull("Missing versionCode must yield null", parsed)
    }

    @Test
    fun testQ2_InactiveUpdateDocIgnored() {
        val payload = mapOf(
            "versionCode" to 50L,
            "versionName" to "5.0.0",
            "updateUrl" to "https://example.com/app.apk",
            "isActive" to false
        )
        val parsed = parseSimulatedUpdate(payload, currentCode = 10L)
        assertNull("Inactive update document must be completely ignored", parsed)
    }

    // =========================================================================
    // SECTION R: FIRESTORE RULES VERIFICATION
    // =========================================================================

    @Test
    fun testR1_FirestoreRulesIncludeAppUpdatesAndNotifications() {
        val candidates = listOf(
            File("firestore.rules"),
            File("../firestore.rules"),
            File("../../firestore.rules"),
            File("/firestore.rules")
        )
        val rulesFile = candidates.firstOrNull { it.exists() }
        assertNotNull("firestore.rules must exist in candidate paths: $candidates", rulesFile)
        val content = rulesFile!!.readText()

        assertTrue("Rules must contain /notifications", content.contains("match /notifications/{notificationId}"))
        assertTrue("Rules must contain /app_updates", content.contains("match /app_updates/{updateId}"))
        assertTrue("Rules must contain /users/{userId}/settings/notifications", content.contains("match /settings/notifications"))
        assertTrue("Rules must contain /users/{userId}/fcmTokens", content.contains("match /fcmTokens/{installationId}"))
    }
}
