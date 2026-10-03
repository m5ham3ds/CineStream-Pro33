package com.example

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.NotificationDao
import com.example.data.model.NotificationItem
import com.example.data.model.NotificationPreferences
import com.example.data.notification.FcmNotificationPayload
import com.example.data.notification.FcmPayloadConstants
import com.example.data.notification.FcmPayloadParser
import com.example.data.notification.NotificationCategory
import com.example.data.notification.NotificationCategoryResolver
import com.example.data.notification.NotificationDeduplicator
import com.example.data.repository.NotificationPreferencesRepository
import com.example.services.AppFirebaseMessagingService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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

/**
 * In-memory test implementation of [NotificationDao] for verifying Room persistence
 * and Notification Center behavior during unit testing without SQLite overhead.
 */
class FakeNotificationDao : NotificationDao {
    private val records = mutableMapOf<String, NotificationItem>()

    override fun getAllNotifications(): Flow<List<NotificationItem>> {
        return flowOf(records.values.toList().sortedByDescending { it.timestamp })
    }

    override fun getUnreadCount(): Flow<Int> {
        return flowOf(records.values.count { !it.isRead })
    }

    override suspend fun getNotificationById(id: String): NotificationItem? {
        return records[id]
    }

    override suspend fun insertNotification(notification: NotificationItem) {
        records[notification.id] = notification
    }

    override suspend fun markAllAsRead() {
        records.replaceAll { _, item -> item.copy(isRead = true) }
    }

    override suspend fun markAsRead(id: String) {
        records[id]?.let { records[id] = it.copy(isRead = true) }
    }

    override suspend fun deleteNotification(id: String) {
        records.remove(id)
    }

    fun size(): Int = records.size
    fun clear() = records.clear()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPreferencesUnitTest {

    private lateinit var context: Context
    private lateinit var deduplicator: NotificationDeduplicator
    private lateinit var service: AppFirebaseMessagingService
    private lateinit var fakeDao: FakeNotificationDao

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(context)
        }
        val prefs = context.getSharedPreferences("test_notif_pref_dedup", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        fakeDao = FakeNotificationDao()
        deduplicator = NotificationDeduplicator(
            context = context,
            sharedPreferences = prefs,
            notificationDaoProvider = { fakeDao }
        )
        deduplicator.clearForTesting()
        service = Robolectric.buildService(AppFirebaseMessagingService::class.java).create().get()
    }

    // ==========================================
    // 1. NotificationPreferences Model & Serialization
    // ==========================================

    @Test
    fun test01_defaultValues() {
        val prefs = NotificationPreferences()
        assertTrue("Master switch must be true by default", prefs.notificationsEnabled)
        assertTrue("Announcements must be true by default", prefs.announcementsEnabled)
        assertTrue("App updates must be true by default", prefs.appUpdatesEnabled)
        assertTrue("Maintenance must be true by default", prefs.maintenanceEnabled)
        assertTrue("New movies must be true by default", prefs.newMoviesEnabled)
        assertTrue("New TV series must be true by default", prefs.newTvSeriesEnabled)
        assertTrue("New anime must be true by default", prefs.newAnimeEnabled)
        assertTrue("New TV episodes must be true by default", prefs.newTvEpisodesEnabled)
        assertTrue("New anime episodes must be true by default", prefs.newAnimeEpisodesEnabled)
        assertTrue("New TV seasons must be true by default", prefs.newTvSeasonsEnabled)
        assertTrue("New anime seasons must be true by default", prefs.newAnimeSeasonsEnabled)
    }

    @Test
    fun test02_toFirestoreMap() {
        val prefs = NotificationPreferences(
            notificationsEnabled = true,
            announcementsEnabled = false,
            appUpdatesEnabled = true,
            maintenanceEnabled = false,
            newMoviesEnabled = true,
            newTvSeriesEnabled = false,
            newAnimeEnabled = true,
            newTvEpisodesEnabled = false,
            newAnimeEpisodesEnabled = true,
            newTvSeasonsEnabled = false,
            newAnimeSeasonsEnabled = true
        )
        val map = prefs.toFirestoreMap()
        assertEquals(true, map["notificationsEnabled"])
        assertEquals(false, map["announcementsEnabled"])
        assertEquals(true, map["appUpdatesEnabled"])
        assertEquals(false, map["maintenanceEnabled"])
        assertEquals(true, map["newMoviesEnabled"])
        assertEquals(false, map["newTvSeriesEnabled"])
        assertEquals(true, map["newAnimeEnabled"])
        assertEquals(false, map["newTvEpisodesEnabled"])
        assertEquals(true, map["newAnimeEpisodesEnabled"])
        assertEquals(false, map["newTvSeasonsEnabled"])
        assertEquals(true, map["newAnimeSeasonsEnabled"])
        assertTrue("updatedAt must be present in firestore map", map.containsKey("updatedAt"))
    }

    @Test
    fun test03_fromFirestoreMap_full() {
        val map = mapOf(
            "notificationsEnabled" to false,
            "announcementsEnabled" to true,
            "appUpdatesEnabled" to false,
            "maintenanceEnabled" to true,
            "newMoviesEnabled" to false,
            "newTvSeriesEnabled" to true,
            "newAnimeEnabled" to false,
            "newTvEpisodesEnabled" to true,
            "newAnimeEpisodesEnabled" to false,
            "newTvSeasonsEnabled" to true,
            "newAnimeSeasonsEnabled" to false
        )
        val prefs = NotificationPreferences.fromFirestoreMap(map)
        assertFalse(prefs.notificationsEnabled)
        assertTrue(prefs.announcementsEnabled)
        assertFalse(prefs.appUpdatesEnabled)
        assertTrue(prefs.maintenanceEnabled)
        assertFalse(prefs.newMoviesEnabled)
        assertTrue(prefs.newTvSeriesEnabled)
        assertFalse(prefs.newAnimeEnabled)
        assertTrue(prefs.newTvEpisodesEnabled)
        assertFalse(prefs.newAnimeEpisodesEnabled)
        assertTrue(prefs.newTvSeasonsEnabled)
        assertFalse(prefs.newAnimeSeasonsEnabled)
    }

    @Test
    fun test04_fromFirestoreMap_partial_fallsBackToDefaults() {
        val map = mapOf(
            "newMoviesEnabled" to false
        )
        val prefs = NotificationPreferences.fromFirestoreMap(map)
        assertFalse("Explicitly set false value must be preserved", prefs.newMoviesEnabled)
        assertTrue("Missing notificationsEnabled must fall back to true", prefs.notificationsEnabled)
        assertTrue("Missing announcementsEnabled must fall back to true", prefs.announcementsEnabled)
        assertTrue("Missing newTvSeriesEnabled must fall back to true", prefs.newTvSeriesEnabled)
    }

    @Test
    fun test05_fromFirestoreMap_invalidTypes_gracefulFallback() {
        val map = mapOf(
            "notificationsEnabled" to "invalid_string_instead_of_bool",
            "newMoviesEnabled" to 12345,
            "appUpdatesEnabled" to false
        )
        val prefs = NotificationPreferences.fromFirestoreMap(map)
        assertTrue("Invalid type for notificationsEnabled must gracefully fall back to true", prefs.notificationsEnabled)
        assertTrue("Invalid type for newMoviesEnabled must gracefully fall back to true", prefs.newMoviesEnabled)
        assertFalse("Valid boolean must be correctly read", prefs.appUpdatesEnabled)
    }

    @Test
    fun test06_fromFirestoreMap_nullMap() {
        val prefs = NotificationPreferences.fromFirestoreMap(null)
        assertEquals(NotificationPreferences(), prefs)
    }

    // ==========================================
    // 2. Parser & Contract Verification (event & isAnime)
    // ==========================================

    @Test
    fun test07_parser_extractsEventAndIsAnimeCorrectly() {
        val rawData = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "parser_test_1",
            FcmPayloadConstants.KEY_TITLE to "Solo Leveling",
            FcmPayloadConstants.KEY_BODY to "Episode 12 released",
            FcmPayloadConstants.KEY_TYPE to "series",
            FcmPayloadConstants.KEY_EVENT to "episode",
            FcmPayloadConstants.KEY_IS_ANIME to "true"
        )
        val payload = FcmPayloadParser.parseRaw(rawData)
        assertTrue(payload.isValid)
        assertEquals("series", payload.type)
        assertEquals("episode", payload.event)
        assertTrue("isAnime must be true when explicitly 'true'", payload.isAnime)
    }

    @Test
    fun test08_parser_isMovieFalseDoesNotInferAnime() {
        val rawData = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "parser_test_not_anime",
            FcmPayloadConstants.KEY_TITLE to "Breaking Bad",
            FcmPayloadConstants.KEY_BODY to "New Season",
            FcmPayloadConstants.KEY_TYPE to "series",
            "isMovie" to "false" // Should NEVER make isAnime true!
        )
        val payload = FcmPayloadParser.parseRaw(rawData)
        assertTrue(payload.isValid)
        assertEquals("series", payload.type)
        assertFalse("isMovie=false must NOT make isAnime true", payload.isAnime)
    }

    @Test
    fun test09_parser_animeTypeDefaultsIsAnimeToTrue() {
        val rawData = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "parser_test_anime_type",
            FcmPayloadConstants.KEY_TITLE to "Attack on Titan",
            FcmPayloadConstants.KEY_BODY to "Final Season Part 3",
            FcmPayloadConstants.KEY_TYPE to "anime"
        )
        val payload = FcmPayloadParser.parseRaw(rawData)
        assertTrue(payload.isValid)
        assertEquals("anime", payload.type)
        assertTrue("type=anime must automatically default isAnime to true", payload.isAnime)
    }

    @Test
    fun test10_parser_eventAndIsAnimeAliases() {
        val rawData = mapOf(
            FcmPayloadConstants.KEY_NOTIFICATION_ID to "parser_test_aliases",
            FcmPayloadConstants.KEY_TITLE to "One Piece",
            FcmPayloadConstants.KEY_BODY to "Episode 1100",
            FcmPayloadConstants.KEY_TYPE to "series",
            "eventType" to "new_episode",
            "is_anime" to "1"
        )
        val payload = FcmPayloadParser.parseRaw(rawData)
        assertEquals("new_episode", payload.event)
        assertTrue("is_anime=1 alias must resolve to true", payload.isAnime)
    }

    // ==========================================
    // 3. NotificationCategoryResolver (10 Required Cases)
    // ==========================================

    @Test
    fun test11_resolver_tenRequiredCases() {
        // Case 1: movie -> NEW_MOVIES
        assertEquals(
            NotificationCategory.NEW_MOVIES,
            NotificationCategoryResolver.resolveCategory(type = "movie", event = null, isAnime = false)
        )

        // Case 2: tv series -> NEW_TV_SERIES
        assertEquals(
            NotificationCategory.NEW_TV_SERIES,
            NotificationCategoryResolver.resolveCategory(type = "series", event = null, isAnime = false)
        )

        // Case 3: anime -> NEW_ANIME
        assertEquals(
            NotificationCategory.NEW_ANIME,
            NotificationCategoryResolver.resolveCategory(type = "anime", event = null, isAnime = true)
        )

        // Case 4: tv episode -> NEW_TV_EPISODES
        assertEquals(
            NotificationCategory.NEW_TV_EPISODES,
            NotificationCategoryResolver.resolveCategory(type = "series", event = "episode", isAnime = false)
        )

        // Case 5: anime episode -> NEW_ANIME_EPISODES
        assertEquals(
            NotificationCategory.NEW_ANIME_EPISODES,
            NotificationCategoryResolver.resolveCategory(type = "anime", event = "episode", isAnime = true)
        )
        assertEquals(
            NotificationCategory.NEW_ANIME_EPISODES,
            NotificationCategoryResolver.resolveCategory(type = "series", event = "episode", isAnime = true)
        )

        // Case 6: tv season -> NEW_TV_SEASONS
        assertEquals(
            NotificationCategory.NEW_TV_SEASONS,
            NotificationCategoryResolver.resolveCategory(type = "series", event = "season", isAnime = false)
        )

        // Case 7: anime season -> NEW_ANIME_SEASONS
        assertEquals(
            NotificationCategory.NEW_ANIME_SEASONS,
            NotificationCategoryResolver.resolveCategory(type = "anime", event = "season", isAnime = true)
        )
        assertEquals(
            NotificationCategory.NEW_ANIME_SEASONS,
            NotificationCategoryResolver.resolveCategory(type = "series", event = "season", isAnime = true)
        )

        // Case 8: series + isAnime=false -> NEW_TV_SERIES
        assertEquals(
            NotificationCategory.NEW_TV_SERIES,
            NotificationCategoryResolver.resolveCategory(type = "series", event = null, isAnime = false)
        )

        // Case 9: series + isAnime=true -> NEW_ANIME
        assertEquals(
            NotificationCategory.NEW_ANIME,
            NotificationCategoryResolver.resolveCategory(type = "series", event = null, isAnime = true)
        )

        // Case 10: Event variants (new_episode, episodes, new_season, seasons)
        assertEquals(
            NotificationCategory.NEW_TV_EPISODES,
            NotificationCategoryResolver.resolveCategory(type = "series", event = "new_episode", isAnime = false)
        )
        assertEquals(
            NotificationCategory.NEW_TV_EPISODES,
            NotificationCategoryResolver.resolveCategory(type = "series", event = "episodes", isAnime = false)
        )
        assertEquals(
            NotificationCategory.NEW_TV_SEASONS,
            NotificationCategoryResolver.resolveCategory(type = "series", event = "new_season", isAnime = false)
        )
        assertEquals(
            NotificationCategory.NEW_TV_SEASONS,
            NotificationCategoryResolver.resolveCategory(type = "series", event = "seasons", isAnime = false)
        )
        assertEquals(
            NotificationCategory.NEW_ANIME_EPISODES,
            NotificationCategoryResolver.resolveCategory(type = "anime", event = "new_episode", isAnime = true)
        )
        assertEquals(
            NotificationCategory.NEW_ANIME_SEASONS,
            NotificationCategoryResolver.resolveCategory(type = "anime", event = "new_season", isAnime = true)
        )
    }

    @Test
    fun test12_resolver_fallbacksAndSystemTypes() {
        assertEquals(NotificationCategory.ANNOUNCEMENTS, NotificationCategoryResolver.resolveCategory("announcement"))
        assertEquals(NotificationCategory.ANNOUNCEMENTS, NotificationCategoryResolver.resolveCategory("announcements"))
        assertEquals(NotificationCategory.APP_UPDATES, NotificationCategoryResolver.resolveCategory("update"))
        assertEquals(NotificationCategory.APP_UPDATES, NotificationCategoryResolver.resolveCategory("app_update"))
        assertEquals(NotificationCategory.MAINTENANCE, NotificationCategoryResolver.resolveCategory("maintenance"))
        assertEquals(NotificationCategory.MAINTENANCE, NotificationCategoryResolver.resolveCategory("system"))
        assertEquals(NotificationCategory.GENERAL, NotificationCategoryResolver.resolveCategory("unknown_type"))
        assertEquals(NotificationCategory.GENERAL, NotificationCategoryResolver.resolveCategory(null))
    }

    @Test
    fun test13_isNotificationAllowed_masterSwitchOff_blocksAll() {
        val masterOffPrefs = NotificationPreferences(
            notificationsEnabled = false,
            announcementsEnabled = true,
            appUpdatesEnabled = true,
            maintenanceEnabled = true,
            newMoviesEnabled = true,
            newTvSeriesEnabled = true,
            newAnimeEnabled = true,
            newTvEpisodesEnabled = true,
            newAnimeEpisodesEnabled = true,
            newTvSeasonsEnabled = true,
            newAnimeSeasonsEnabled = true
        )

        NotificationCategory.values().forEach { category ->
            assertFalse(
                "When master switch is OFF, category $category must NOT be allowed",
                NotificationCategoryResolver.isNotificationAllowed(category, masterOffPrefs)
            )
        }
    }

    @Test
    fun test14_isNotificationAllowed_eachCategoryIndependent() {
        val basePrefs = NotificationPreferences(
            notificationsEnabled = true,
            announcementsEnabled = false,
            appUpdatesEnabled = false,
            maintenanceEnabled = false,
            newMoviesEnabled = false,
            newTvSeriesEnabled = false,
            newAnimeEnabled = false,
            newTvEpisodesEnabled = false,
            newAnimeEpisodesEnabled = false,
            newTvSeasonsEnabled = false,
            newAnimeSeasonsEnabled = false
        )

        // Turning on only newMoviesEnabled allows only NEW_MOVIES
        val movieOnly = basePrefs.copy(newMoviesEnabled = true)
        assertTrue(NotificationCategoryResolver.isNotificationAllowed(NotificationCategory.NEW_MOVIES, movieOnly))
        assertFalse(NotificationCategoryResolver.isNotificationAllowed(NotificationCategory.NEW_TV_SERIES, movieOnly))
        assertFalse(NotificationCategoryResolver.isNotificationAllowed(NotificationCategory.ANNOUNCEMENTS, movieOnly))

        // Turning on only newAnimeEpisodesEnabled allows only NEW_ANIME_EPISODES
        val animeEpOnly = basePrefs.copy(newAnimeEpisodesEnabled = true)
        assertTrue(NotificationCategoryResolver.isNotificationAllowed(NotificationCategory.NEW_ANIME_EPISODES, animeEpOnly))
        assertFalse(NotificationCategoryResolver.isNotificationAllowed(NotificationCategory.NEW_TV_EPISODES, animeEpOnly))
        assertFalse(NotificationCategoryResolver.isNotificationAllowed(NotificationCategory.NEW_ANIME, animeEpOnly))

        // GENERAL is always allowed if master is on
        assertTrue(NotificationCategoryResolver.isNotificationAllowed(NotificationCategory.GENERAL, basePrefs))
    }

    // ==========================================
    // 4. Master Switch Preservation
    // ==========================================

    @Test
    fun test15_masterSwitch_preservesChildToggles() {
        val initialPrefs = NotificationPreferences(
            notificationsEnabled = true,
            newMoviesEnabled = false,
            newAnimeEpisodesEnabled = true,
            newTvSeriesEnabled = false
        )

        // Toggling master off
        val switchedOff = initialPrefs.copy(notificationsEnabled = false)
        assertFalse(switchedOff.notificationsEnabled)
        assertFalse("Child toggles must retain their original state", switchedOff.newMoviesEnabled)
        assertTrue("Child toggles must retain their original state", switchedOff.newAnimeEpisodesEnabled)
        assertFalse("Child toggles must retain their original state", switchedOff.newTvSeriesEnabled)

        // Toggling master back on
        val switchedOn = switchedOff.copy(notificationsEnabled = true)
        assertTrue(switchedOn.notificationsEnabled)
        assertFalse("Child toggles must still have original state after re-enabling", switchedOn.newMoviesEnabled)
        assertTrue("Child toggles must still have original state after re-enabling", switchedOn.newAnimeEpisodesEnabled)
        assertFalse("Child toggles must still have original state after re-enabling", switchedOn.newTvSeriesEnabled)
    }

    // ==========================================
    // 5. Complete Suppression: Preference Gate BEFORE Room Persistence
    // ==========================================

    @Test
    fun test16_gate_whenCategoryDisabled_roomRecordIsNotCreated() {
        val animeEpPayload = FcmNotificationPayload(
            notificationId = "suppress_anime_ep_1",
            title = "Jujutsu Kaisen",
            body = "Season 2 Episode 15 is out!",
            type = "anime",
            event = "episode",
            isAnime = true,
            target = FcmPayloadConstants.TARGET_ALL
        )

        val prefsDisabled = NotificationPreferences(
            notificationsEnabled = true,
            newAnimeEpisodesEnabled = false // Disabled!
        )

        val handled = service.handlePayload(
            payload = animeEpPayload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = prefsDisabled,
            notificationDaoOverride = fakeDao
        )

        assertTrue("Payload handled safely", handled)
        // Complete suppression assertions:
        runBlocking {
            assertNull("Room database record MUST NOT be created when preference is OFF", fakeDao.getNotificationById("suppress_anime_ep_1"))
            assertEquals("Notification center count must be 0", 0, fakeDao.size())
        }
        assertTrue("Deduplicator must still record ID to prevent duplicate cross-source delivery", deduplicator.isDuplicate("suppress_anime_ep_1"))
    }

    @Test
    fun test17_gate_whenMasterDisabled_roomRecordIsNotCreated() {
        val moviePayload = FcmNotificationPayload(
            notificationId = "suppress_master_off_movie",
            title = "Dune: Part Two",
            body = "Now streaming in 4K",
            type = "movie",
            target = FcmPayloadConstants.TARGET_ALL
        )

        val masterOffPrefs = NotificationPreferences(
            notificationsEnabled = false,
            newMoviesEnabled = true // Individual toggle is true, but master is false
        )

        val handled = service.handlePayload(
            payload = moviePayload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = masterOffPrefs,
            notificationDaoOverride = fakeDao
        )

        assertTrue("Payload handled safely", handled)
        runBlocking {
            assertNull("Room database record MUST NOT be created when master switch is OFF", fakeDao.getNotificationById("suppress_master_off_movie"))
            assertEquals("Notification center must remain empty", 0, fakeDao.size())
        }
        assertTrue("Deduplicator must record ID to avoid reprocessing", deduplicator.isDuplicate("suppress_master_off_movie"))
    }

    @Test
    fun test18_gate_whenCategoryEnabled_roomRecordIsCreated() {
        val animeEpPayload = FcmNotificationPayload(
            notificationId = "allow_anime_ep_1",
            title = "Demon Slayer",
            body = "Hashira Training Arc Episode 1",
            type = "anime",
            event = "episode",
            isAnime = true,
            target = FcmPayloadConstants.TARGET_ALL
        )

        val prefsEnabled = NotificationPreferences(
            notificationsEnabled = true,
            newAnimeEpisodesEnabled = true // Allowed!
        )

        val handled = service.handlePayload(
            payload = animeEpPayload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = prefsEnabled,
            notificationDaoOverride = fakeDao
        )

        assertTrue("Payload delivered", handled)
        runBlocking {
            val record = fakeDao.getNotificationById("allow_anime_ep_1")
            assertNotNull("Room database record MUST be created when preference is ON", record)
            assertEquals("Demon Slayer", record?.title)
            assertEquals("Hashira Training Arc Episode 1", record?.message)
            assertEquals(1, fakeDao.size())
        }
        assertTrue("Deduplicator recorded ID", deduplicator.isDuplicate("allow_anime_ep_1"))
    }

    @Test
    fun test19_gate_deduplicationPreventsDuplicateInserts() {
        val seriesPayload = FcmNotificationPayload(
            notificationId = "dedup_series_ep_1",
            title = "The Last of Us",
            body = "Episode 4",
            type = "series",
            event = "episode",
            isAnime = false,
            target = FcmPayloadConstants.TARGET_ALL
        )

        val prefs = NotificationPreferences(
            notificationsEnabled = true,
            newTvEpisodesEnabled = true
        )

        // First delivery: succeeds and inserts into Room
        val firstResult = service.handlePayload(
            payload = seriesPayload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = prefs,
            notificationDaoOverride = fakeDao
        )
        assertTrue("First delivery must succeed", firstResult)
        runBlocking {
            assertEquals("Only 1 item should be inserted", 1, fakeDao.size())
        }

        // Second delivery of same payload: deduplicator detects duplicate and returns false
        val secondResult = service.handlePayload(
            payload = seriesPayload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = prefs,
            notificationDaoOverride = fakeDao
        )
        assertFalse("Second delivery must be rejected as duplicate", secondResult)
        runBlocking {
            assertEquals("Room size must still be 1 (no duplicate insertion)", 1, fakeDao.size())
        }
    }

    // ==========================================
    // 6. Account Isolation & Default Recovery
    // ==========================================

    @Test
    fun test20_accountIsolation_resetToDefaultsClearsPreferences() = runBlocking {
        val repo = NotificationPreferencesRepository(context)

        // User sets custom preference
        repo.updateNewAnimeEpisodesEnabled(false)
        assertFalse("Anime episodes should now be false", repo.getPreferences().newAnimeEpisodesEnabled)

        // User logs out -> resetToDefaults() is called
        repo.resetToDefaults()

        // Verify DataStore reverted cleanly to all true defaults
        val restored = repo.getPreferences()
        assertTrue("Master notifications must be true after reset", restored.notificationsEnabled)
        assertTrue("New anime episodes must revert to default true after reset", restored.newAnimeEpisodesEnabled)
        assertTrue("New movies must revert to default true after reset", restored.newMoviesEnabled)
    }

    @Test
    fun test21_accountIsolation_userAToUserB_noLeakage() = runBlocking {
        val repo = NotificationPreferencesRepository(context)

        // Scenario: User A has custom preferences
        repo.updateNewAnimeEpisodesEnabled(false)
        repo.updateNewMoviesEnabled(false)
        repo.updateAnnouncementsEnabled(false)

        val userAPrefs = repo.getPreferences()
        assertFalse(userAPrefs.newAnimeEpisodesEnabled)
        assertFalse(userAPrefs.newMoviesEnabled)
        assertFalse(userAPrefs.announcementsEnabled)

        // User A logs out -> system executes resetToDefaults()
        repo.resetToDefaults()

        // User B starts as guest / new login: must have clean defaults, no leakage from User A
        val userBPrefs = repo.getPreferences()
        assertTrue("User B must NOT inherit User A's anime preference", userBPrefs.newAnimeEpisodesEnabled)
        assertTrue("User B must NOT inherit User A's movies preference", userBPrefs.newMoviesEnabled)
        assertTrue("User B must NOT inherit User A's announcements preference", userBPrefs.announcementsEnabled)
        assertTrue("User B must have master switch ON", userBPrefs.notificationsEnabled)
    }

    // ==========================================
    // 7. Individual Category Suppression Suite (All Categories)
    // ==========================================

    @Test
    fun test22_suppression_allIndividualCategoriesBlockRoomInsertion() {
        val testCases = listOf(
            Triple(
                FcmNotificationPayload(notificationId = "suppress_movie_1", title = "New Movie", body = "Movie body", type = "movie"),
                NotificationPreferences(newMoviesEnabled = false),
                "Movie"
            ),
            Triple(
                FcmNotificationPayload(notificationId = "suppress_tv_series_1", title = "New Series", body = "Series body", type = "series", isAnime = false),
                NotificationPreferences(newTvSeriesEnabled = false),
                "TV Series"
            ),
            Triple(
                FcmNotificationPayload(notificationId = "suppress_anime_1", title = "New Anime", body = "Anime body", type = "anime", isAnime = true),
                NotificationPreferences(newAnimeEnabled = false),
                "Anime"
            ),
            Triple(
                FcmNotificationPayload(notificationId = "suppress_tv_ep_1", title = "New TV Ep", body = "TV Ep body", type = "series", event = "episode", isAnime = false),
                NotificationPreferences(newTvEpisodesEnabled = false),
                "TV Episode"
            ),
            Triple(
                FcmNotificationPayload(notificationId = "suppress_anime_ep_2", title = "New Anime Ep", body = "Anime Ep body", type = "anime", event = "episode", isAnime = true),
                NotificationPreferences(newAnimeEpisodesEnabled = false),
                "Anime Episode"
            ),
            Triple(
                FcmNotificationPayload(notificationId = "suppress_tv_season_1", title = "New TV Season", body = "TV Season body", type = "series", event = "season", isAnime = false),
                NotificationPreferences(newTvSeasonsEnabled = false),
                "TV Season"
            ),
            Triple(
                FcmNotificationPayload(notificationId = "suppress_anime_season_1", title = "New Anime Season", body = "Anime Season body", type = "anime", event = "season", isAnime = true),
                NotificationPreferences(newAnimeSeasonsEnabled = false),
                "Anime Season"
            ),
            Triple(
                FcmNotificationPayload(notificationId = "suppress_announcement_1", title = "Announcement", body = "System announcement", type = "announcement"),
                NotificationPreferences(announcementsEnabled = false),
                "Announcement"
            ),
            Triple(
                FcmNotificationPayload(notificationId = "suppress_update_1", title = "App Update", body = "Update available", type = "update"),
                NotificationPreferences(appUpdatesEnabled = false),
                "App Update"
            ),
            Triple(
                FcmNotificationPayload(notificationId = "suppress_maintenance_1", title = "Maintenance", body = "Scheduled maintenance", type = "maintenance"),
                NotificationPreferences(maintenanceEnabled = false),
                "Maintenance"
            )
        )

        testCases.forEach { (payload, prefs, label) ->
            fakeDao.clear()
            val handled = service.handlePayload(
                payload = payload,
                currentUidOverride = null,
                isAnonymousOverride = false,
                deduplicatorOverride = deduplicator,
                preferencesOverride = prefs,
                notificationDaoOverride = fakeDao
            )

            assertTrue("$label payload should be processed safely", handled)
            runBlocking {
                assertNull(
                    "$label must NOT create Room record when preference is OFF",
                    fakeDao.getNotificationById(payload.notificationId)
                )
                assertEquals(
                    "Notification Center must have 0 records for disabled $label",
                    0,
                    fakeDao.size()
                )
            }
            assertTrue(
                "$label notification ID must be recorded in deduplicator to prevent resurfacing",
                deduplicator.isDuplicate(payload.notificationId)
            )
        }
    }

    // ==========================================
    // 8. Future Backend Contract Compatibility (13 Fields)
    // ==========================================

    @Test
    fun test23_parser_futureBackendContractWithAll13Fields() {
        val rawData = mapOf(
            "notificationId" to "backend_notif_13_fields",
            "type" to "series",
            "event" to "episode",
            "isAnime" to "true",
            "movieId" to "",
            "seriesId" to "series_9988",
            "episodeId" to "ep_77",
            "target" to "user",
            "targetUid" to "user_12345",
            "title" to "Chainsaw Man",
            "body" to "Episode 12 is now streaming!",
            "imageUrl" to "https://image.tmdb.org/t/p/w500/sample.jpg",
            "createdAt" to "1720000000000"
        )

        val payload = FcmPayloadParser.parseRaw(rawData)
        assertTrue("13-field future backend payload must be valid", payload.isValid)
        assertEquals("backend_notif_13_fields", payload.notificationId)
        assertEquals("series", payload.type)
        assertEquals("episode", payload.event)
        assertTrue("isAnime must be true", payload.isAnime)
        assertEquals("series_9988", payload.seriesId)
        assertEquals("ep_77", payload.episodeId)
        assertEquals("user", payload.target)
        assertEquals("user_12345", payload.targetUid)
        assertEquals("Chainsaw Man", payload.title)
        assertEquals("Episode 12 is now streaming!", payload.body)
        assertEquals("https://image.tmdb.org/t/p/w500/sample.jpg", payload.imageUrl)
        assertEquals(1720000000000L, payload.timestamp)

        // Verify it resolves deterministically to NEW_ANIME_EPISODES
        val category = NotificationCategoryResolver.resolveCategory(
            type = payload.type,
            event = payload.event,
            isAnime = payload.isAnime
        )
        assertEquals(NotificationCategory.NEW_ANIME_EPISODES, category)
    }

    // ==========================================
    // 9. Cross-Source Deduplication & Resurface Prevention
    // ==========================================

    @Test
    fun test24_crossSourceDeduplication_fcmThenFirestoreSuppressed() {
        val notifId = "cross_source_notif_1"
        val payload = FcmNotificationPayload(
            notificationId = notifId,
            title = "Attack on Titan",
            body = "Final Chapter Released",
            type = "anime",
            event = "episode",
            isAnime = true,
            target = FcmPayloadConstants.TARGET_ALL
        )

        val prefs = NotificationPreferences(
            notificationsEnabled = true,
            newAnimeEpisodesEnabled = true
        )

        // 1. FCM delivers first
        val fcmResult = service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = prefs,
            notificationDaoOverride = fakeDao
        )
        assertTrue("FCM delivery should succeed", fcmResult)
        runBlocking {
            assertEquals(1, fakeDao.size())
            assertNotNull(fakeDao.getNotificationById(notifId))
        }

        // 2. Later, deduplicator detects that the notification was already processed
        val isDuplicate = deduplicator.checkAndMarkProcessed(notifId)
        assertTrue("Firestore sync deduplication check must detect this ID as already processed", isDuplicate)

        // Ensure Room still has exactly 1 record
        runBlocking {
            assertEquals("No duplicate record created in Room", 1, fakeDao.size())
        }
    }

    @Test
    fun test25_suppressionPreventsLaterFirestoreResurface() {
        val notifId = "suppress_never_resurface_id"
        val payload = FcmNotificationPayload(
            notificationId = notifId,
            title = "Jujutsu Kaisen",
            body = "Season 2 Finale",
            type = "anime",
            event = "episode",
            isAnime = true,
            target = FcmPayloadConstants.TARGET_ALL
        )

        val disabledPrefs = NotificationPreferences(
            notificationsEnabled = true,
            newAnimeEpisodesEnabled = false // Disabled by user
        )

        // 1. FCM arrives while preference is OFF
        val handled = service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = disabledPrefs,
            notificationDaoOverride = fakeDao
        )
        assertTrue("Suppression handled cleanly", handled)
        runBlocking {
            assertNull("Never in Room", fakeDao.getNotificationById(notifId))
            assertEquals("Notification center remains empty", 0, fakeDao.size())
        }

        // 2. ID was recorded in deduplicator during suppression:
        assertTrue("Deduplicator must track suppressed notification ID", deduplicator.isDuplicate(notifId))

        // 3. If Firestore sync or re-delivery runs later for the same ID:
        val duplicateCheck = deduplicator.checkAndMarkProcessed(notifId)
        assertTrue("Subsequent Firestore sync must be flagged as duplicate and skipped", duplicateCheck)

        runBlocking {
            assertNull("Notification must NEVER resurface in Room", fakeDao.getNotificationById(notifId))
            assertEquals(0, fakeDao.size())
        }
    }

    // ==========================================
    // 10. Extended Hardening & Verification Suite
    // ==========================================

    @Test
    fun test26_unreadCount_remainsZeroWhenSuppressed() = runBlocking {
        fakeDao.clear()
        val notifId = "suppressed_unread_test_1"
        val payload = FcmNotificationPayload(
            notificationId = notifId,
            title = "New Movie Premier",
            body = "Watch now",
            type = "movie"
        )
        val disabledPrefs = NotificationPreferences(
            notificationsEnabled = true,
            newMoviesEnabled = false // Disabled
        )

        service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = disabledPrefs,
            notificationDaoOverride = fakeDao
        )

        val unreadCount = fakeDao.getUnreadCount().first()
        assertEquals("Unread notification count must remain 0 when suppressed", 0, unreadCount)
        assertEquals("Room record count must be 0", 0, fakeDao.size())
    }

    @Test
    fun test27_reEnablingMasterSwitch_restoresIndependentTogglesAndBehavior() {
        fakeDao.clear()
        // 1. User has Master OFF, with Movies OFF and Anime ON
        val masterOffPrefs = NotificationPreferences(
            notificationsEnabled = false,
            newMoviesEnabled = false,
            newAnimeEpisodesEnabled = true
        )

        val animePayload = FcmNotificationPayload(
            notificationId = "anime_ep_restore_test",
            title = "Demon Slayer",
            body = "New Episode",
            type = "anime",
            event = "episode",
            isAnime = true
        )
        val moviePayload = FcmNotificationPayload(
            notificationId = "movie_restore_test",
            title = "Inception",
            body = "New Movie",
            type = "movie"
        )

        // With Master OFF: both must be completely suppressed
        service.handlePayload(
            payload = animePayload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = masterOffPrefs,
            notificationDaoOverride = fakeDao
        )
        service.handlePayload(
            payload = moviePayload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = masterOffPrefs,
            notificationDaoOverride = fakeDao
        )

        runBlocking {
            assertEquals("Both suppressed while Master is OFF", 0, fakeDao.size())
        }

        // 2. Re-enable Master ON: child toggles remain preserved
        val masterOnPrefs = masterOffPrefs.copy(notificationsEnabled = true)
        assertFalse("Movies remains independent OFF", masterOnPrefs.newMoviesEnabled)
        assertTrue("Anime remains independent ON", masterOnPrefs.newAnimeEpisodesEnabled)

        // Re-attempt delivery with fresh IDs
        val animePayload2 = animePayload.copy(notificationId = "anime_ep_restore_test_2")
        val moviePayload2 = moviePayload.copy(notificationId = "movie_restore_test_2")

        service.handlePayload(
            payload = animePayload2,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = masterOnPrefs,
            notificationDaoOverride = fakeDao
        )
        service.handlePayload(
            payload = moviePayload2,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = masterOnPrefs,
            notificationDaoOverride = fakeDao
        )

        runBlocking {
            assertEquals("Only the enabled anime episode should be inserted into Room", 1, fakeDao.size())
            assertNotNull(fakeDao.getNotificationById("anime_ep_restore_test_2"))
            assertNull(fakeDao.getNotificationById("movie_restore_test_2"))
        }
    }

    @Test
    fun test28_categoryResolution_exhaustiveDeterministicMatrix() {
        // 1. movie -> NEW_MOVIES
        assertEquals(NotificationCategory.NEW_MOVIES, NotificationCategoryResolver.resolveCategory("movie"))

        // 2. tv series -> NEW_TV_SERIES
        assertEquals(NotificationCategory.NEW_TV_SERIES, NotificationCategoryResolver.resolveCategory("series", isAnime = false))
        assertEquals(NotificationCategory.NEW_TV_SERIES, NotificationCategoryResolver.resolveCategory("tv", isAnime = false))
        assertEquals(NotificationCategory.NEW_TV_SERIES, NotificationCategoryResolver.resolveCategory("tv_series", isAnime = false))

        // 3. anime -> NEW_ANIME
        assertEquals(NotificationCategory.NEW_ANIME, NotificationCategoryResolver.resolveCategory("anime", isAnime = true))
        assertEquals(NotificationCategory.NEW_ANIME, NotificationCategoryResolver.resolveCategory("series", isAnime = true))

        // 4. tv episode -> NEW_TV_EPISODES
        assertEquals(NotificationCategory.NEW_TV_EPISODES, NotificationCategoryResolver.resolveCategory("series", event = "episode", isAnime = false))
        assertEquals(NotificationCategory.NEW_TV_EPISODES, NotificationCategoryResolver.resolveCategory("series", event = "new_episode", isAnime = false))
        assertEquals(NotificationCategory.NEW_TV_EPISODES, NotificationCategoryResolver.resolveCategory("series", event = "episodes", isAnime = false))

        // 5. anime episode -> NEW_ANIME_EPISODES
        assertEquals(NotificationCategory.NEW_ANIME_EPISODES, NotificationCategoryResolver.resolveCategory("anime", event = "episode", isAnime = true))
        assertEquals(NotificationCategory.NEW_ANIME_EPISODES, NotificationCategoryResolver.resolveCategory("series", event = "episode", isAnime = true))
        assertEquals(NotificationCategory.NEW_ANIME_EPISODES, NotificationCategoryResolver.resolveCategory("anime", event = "new_episode", isAnime = true))

        // 6. tv season -> NEW_TV_SEASONS
        assertEquals(NotificationCategory.NEW_TV_SEASONS, NotificationCategoryResolver.resolveCategory("series", event = "season", isAnime = false))
        assertEquals(NotificationCategory.NEW_TV_SEASONS, NotificationCategoryResolver.resolveCategory("series", event = "new_season", isAnime = false))
        assertEquals(NotificationCategory.NEW_TV_SEASONS, NotificationCategoryResolver.resolveCategory("series", event = "seasons", isAnime = false))

        // 7. anime season -> NEW_ANIME_SEASONS
        assertEquals(NotificationCategory.NEW_ANIME_SEASONS, NotificationCategoryResolver.resolveCategory("anime", event = "season", isAnime = true))
        assertEquals(NotificationCategory.NEW_ANIME_SEASONS, NotificationCategoryResolver.resolveCategory("series", event = "season", isAnime = true))
        assertEquals(NotificationCategory.NEW_ANIME_SEASONS, NotificationCategoryResolver.resolveCategory("anime", event = "new_season", isAnime = true))

        // 8. System & announcements
        assertEquals(NotificationCategory.ANNOUNCEMENTS, NotificationCategoryResolver.resolveCategory("announcement"))
        assertEquals(NotificationCategory.ANNOUNCEMENTS, NotificationCategoryResolver.resolveCategory("announcements"))
        assertEquals(NotificationCategory.APP_UPDATES, NotificationCategoryResolver.resolveCategory("update"))
        assertEquals(NotificationCategory.APP_UPDATES, NotificationCategoryResolver.resolveCategory("app_update"))
        assertEquals(NotificationCategory.MAINTENANCE, NotificationCategoryResolver.resolveCategory("maintenance"))
        assertEquals(NotificationCategory.MAINTENANCE, NotificationCategoryResolver.resolveCategory("system"))

        // 9. Safe fallbacks: unknown / null / empty defaults to GENERAL and NEVER infers anime
        assertEquals(NotificationCategory.GENERAL, NotificationCategoryResolver.resolveCategory(null))
        assertEquals(NotificationCategory.GENERAL, NotificationCategoryResolver.resolveCategory(""))
        assertEquals(NotificationCategory.GENERAL, NotificationCategoryResolver.resolveCategory("unknown_custom_type"))
    }

    @Test
    fun test29_masterSwitchOff_noSystemNotificationPostedInTray() {
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowManager = Shadows.shadowOf(notifManager)
        notifManager.cancelAll()

        fakeDao.clear()
        val payload = FcmNotificationPayload(
            notificationId = "tray_master_off_test",
            title = "Test Tray",
            body = "Should not appear in tray",
            type = "movie"
        )
        val masterOffPrefs = NotificationPreferences(notificationsEnabled = false)

        service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = masterOffPrefs,
            notificationDaoOverride = fakeDao
        )

        assertEquals("No notification should be posted to Android tray when Master is OFF", 0, shadowManager.allNotifications.size)
        runBlocking {
            assertEquals("No notification stored in Room", 0, fakeDao.size())
        }
    }

    @Test
    fun test30_categoryOff_noSystemNotificationPostedInTray() {
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowManager = Shadows.shadowOf(notifManager)
        notifManager.cancelAll()

        fakeDao.clear()
        val payload = FcmNotificationPayload(
            notificationId = "tray_category_off_test",
            title = "Jujutsu Kaisen",
            body = "Episode 23 is out!",
            type = "anime",
            event = "episode",
            isAnime = true
        )
        val categoryOffPrefs = NotificationPreferences(
            notificationsEnabled = true,
            newAnimeEpisodesEnabled = false // OFF
        )

        service.handlePayload(
            payload = payload,
            currentUidOverride = null,
            isAnonymousOverride = false,
            deduplicatorOverride = deduplicator,
            preferencesOverride = categoryOffPrefs,
            notificationDaoOverride = fakeDao
        )

        assertEquals("No notification should be posted to Android tray when category is OFF", 0, shadowManager.allNotifications.size)
        runBlocking {
            assertEquals("No notification stored in Room", 0, fakeDao.size())
        }
    }
}
