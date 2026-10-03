package com.example

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.navigation.NavigationIntentHandler
import com.example.navigation.NotificationIntentParser
import com.example.navigation.NotificationNavigationContract
import com.example.navigation.Screen
import com.example.utils.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationNavigationUnitTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        NavigationIntentHandler.clear()
    }

    // 1. home
    @Test
    fun test01_navigateToHome_resolvesCorrectly() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "home")
        assertEquals(Screen.Home.route, result)
    }

    // 2. movies
    @Test
    fun test02_navigateToMovies_resolvesCorrectly() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "movies")
        assertEquals(Screen.Movies.route, result)
    }

    // 3. series
    @Test
    fun test03_navigateToSeries_resolvesCorrectly() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "series")
        assertEquals(Screen.Series.route, result)
    }

    // 4. search
    @Test
    fun test04_navigateToSearch_resolvesCorrectly() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "search")
        assertEquals(Screen.Search.route, result)
    }

    // 5. library
    @Test
    fun test05_navigateToLibrary_resolvesCorrectly() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "library")
        assertEquals(Screen.Library.route, result)
    }

    // 6. downloads
    @Test
    fun test06_navigateToDownloads_resolvesCorrectly() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "downloads")
        assertEquals(Screen.Downloads.route, result)
    }

    // 7. notifications
    @Test
    fun test07_navigateToNotifications_resolvesCorrectly() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "notifications")
        assertEquals(Screen.Notifications.route, result)
    }

    // 8. profile
    @Test
    fun test08_navigateToProfile_resolvesCorrectly() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "profile")
        assertEquals(Screen.Profile.route, result)
    }

    // 9. settings
    @Test
    fun test09_navigateToSettings_resolvesCorrectly() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "settings")
        assertEquals(Screen.Settings.route, result)
    }

    // 10. support -> help_support
    @Test
    fun test10_navigateToSupport_resolvesToHelpSupport() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "support")
        assertEquals(Screen.HelpSupport.route, result)
        assertEquals("help_support", result)
    }

    // 11. anime
    @Test
    fun test11_navigateToAnime_resolvesCorrectly() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "anime")
        assertEquals(Screen.Anime.route, result)
    }

    // 12. share
    @Test
    fun test12_navigateToShare_resolvesCorrectly() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "share")
        assertEquals(Screen.Share.route, result)
    }

    // 13. movie detail
    @Test
    fun test13_movieDetail_resolvesToMovieDetailsRoute() {
        val result = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = "m_12345"
        )
        assertEquals(Screen.MovieDetails.createRoute("m_12345", autoPlay = false), result)
    }

    // 14. series detail
    @Test
    fun test14_seriesDetail_resolvesToSeriesDetailsRoute() {
        val result = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "series",
            seriesId = "s_67890"
        )
        assertEquals(Screen.SeriesDetails.createRoute("s_67890", autoPlay = false), result)
    }

    // 15. missing movieId
    @Test
    fun test15_movieDetail_missingMovieId_rejected() {
        val result = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = null
        )
        assertNull(result)
    }

    // 16. missing seriesId
    @Test
    fun test16_seriesDetail_missingSeriesId_rejected() {
        val result = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "series",
            seriesId = null
        )
        assertNull(result)
    }

    // 17. invalid route
    @Test
    fun test17_invalidRoute_rejected() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "non_existent_destination")
        assertNull(result)
    }

    // 18. malicious route
    @Test
    fun test18_maliciousRoute_rejected() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "com.malicious.Activity")
        assertNull(result)
    }

    // 19. javascript
    @Test
    fun test19_javascriptUri_rejected() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "javascript:alert(1)")
        assertNull(result)
    }

    // 20. intent://
    @Test
    fun test20_intentUri_rejected() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "intent://#Intent;action=VIEW;end")
        assertNull(result)
    }

    // 21. http://
    @Test
    fun test21_httpUri_rejected() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "http://malicious.domain.com")
        assertNull(result)
    }

    // 22. https://
    @Test
    fun test22_httpsUri_rejected() {
        val result = NotificationIntentParser.parseRaw(navigateTo = "https://phishing.site.org")
        assertNull(result)
    }

    // 23. slash injection
    @Test
    fun test23_slashInjectionInIdOrRoute_rejected() {
        val routeResult = NotificationIntentParser.parseRaw(navigateTo = "movies/hack")
        assertNull(routeResult)

        val idResult = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = "movie/123"
        )
        assertNull(idResult)
    }

    // 24. query injection
    @Test
    fun test24_queryInjectionInId_rejected() {
        val result = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = "123?admin=true&autoPlay=true"
        )
        assertNull(result)
    }

    // 25. path traversal
    @Test
    fun test25_pathTraversal_rejected() {
        val traversal1 = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = "../../etc/passwd"
        )
        assertNull(traversal1)

        val traversal2 = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = ".."
        )
        assertNull(traversal2)

        assertFalse(NotificationIntentParser.isValidId(".."))
        assertFalse(NotificationIntentParser.isValidId("../secret"))
    }

    // 26. empty ID
    @Test
    fun test26_emptyId_rejected() {
        val result = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = ""
        )
        assertNull(result)
        assertFalse(NotificationIntentParser.isValidId(""))
    }

    // 27. whitespace ID
    @Test
    fun test27_whitespaceId_rejected() {
        val result = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = "    "
        )
        assertNull(result)
        assertFalse(NotificationIntentParser.isValidId("   "))
    }

    // 28. >64 chars
    @Test
    fun test28_overlyLongId_rejected() {
        val longId = "a".repeat(65)
        val result = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = longId
        )
        assertNull(result)
        assertFalse(NotificationIntentParser.isValidId(longId))

        // Exactly 64 chars is allowed
        val exact64 = "a".repeat(64)
        assertTrue(NotificationIntentParser.isValidId(exact64))
    }

    // 29. invalid characters
    @Test
    fun test29_invalidCharacters_rejected() {
        val withHash = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = "movie#tag"
        )
        assertNull(withHash)

        val withColon = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = "movie:123"
        )
        assertNull(withColon)

        val withSpace = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = "movie 123"
        )
        assertNull(withSpace)
    }

    // 30. conflicting movieId + seriesId
    @Test
    fun test30_conflictingMovieAndSeriesIds_rejected() {
        val result = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            movieId = "movie_123",
            seriesId = "series_456"
        )
        assertNull(result)
    }

    // 31. type mismatch
    @Test
    fun test31_typeMismatch_rejected() {
        // Type is movie, but only seriesId is passed
        val resultMovieWithSeries = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "movie",
            seriesId = "series_456"
        )
        assertNull(resultMovieWithSeries)

        // Type is series, but only movieId is passed
        val resultSeriesWithMovie = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "series",
            movieId = "movie_123"
        )
        assertNull(resultSeriesWithMovie)

        // Unsupported type (e.g. announcement) with movieId
        val resultAnnouncement = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "announcement",
            movieId = "movie_123"
        )
        assertNull(resultAnnouncement)
    }

    // 32. missing type
    @Test
    fun test32_missingTypeForDetail_rejected() {
        val result = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = null,
            movieId = "movie_123"
        )
        assertNull(result)

        val resultBlankType = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "   ",
            movieId = "movie_123"
        )
        assertNull(resultBlankType)
    }

    // 33. episodeId without valid route
    @Test
    fun test33_episodeIdWithoutValidRoute_rejected() {
        // App has no independent episode route
        val resultEpisodeNav = NotificationIntentParser.parseRaw(
            navigateTo = "episode",
            episodeId = "ep_101"
        )
        assertNull(resultEpisodeNav)

        val resultDetailEpisode = NotificationIntentParser.parseRaw(
            navigateTo = "detail",
            type = "episode",
            episodeId = "ep_101"
        )
        assertNull(resultDetailEpisode)
    }

    // 34. cold start
    @Test
    fun test34_coldStart_preservesPendingNavigationDestination() {
        NavigationIntentHandler.clear()
        assertNull(NavigationIntentHandler.targetDestination.value)

        val intent = Intent().apply {
            putExtra(NotificationNavigationContract.EXTRA_NAVIGATE_TO, "movies")
        }
        val target = NotificationIntentParser.parse(intent)
        assertNotNull(target)
        NavigationIntentHandler.navigateTo(target!!)

        assertEquals(Screen.Movies.route, NavigationIntentHandler.targetDestination.value)
    }

    // 35. warm start
    @Test
    fun test35_warmStart_deliversDestinationToHandler() {
        NavigationIntentHandler.clear()
        val intent = Intent().apply {
            putExtra(NotificationNavigationContract.EXTRA_NAVIGATE_TO, "detail")
            putExtra(NotificationNavigationContract.EXTRA_NOTIFICATION_TYPE, "movie")
            putExtra(NotificationNavigationContract.EXTRA_MOVIE_ID, "m_cold99")
        }
        val route = NotificationIntentParser.parse(intent)
        assertNotNull(route)
        NavigationIntentHandler.navigateTo(route!!)

        assertEquals(Screen.MovieDetails.createRoute("m_cold99", autoPlay = false), NavigationIntentHandler.targetDestination.value)
    }

    // 36. onNewIntent
    @Test
    fun test36_onNewIntent_updatesDestinationAndExtractsNotificationId() {
        val intent = Intent().apply {
            putExtra(NotificationNavigationContract.EXTRA_NOTIFICATION_ID, "notif_9988")
            putExtra(NotificationNavigationContract.EXTRA_NAVIGATE_TO, "library")
        }
        val notifId = NotificationIntentParser.extractNotificationId(intent)
        val route = NotificationIntentParser.parse(intent)

        assertEquals("notif_9988", notifId)
        assertEquals(Screen.Library.route, route)
    }

    // 37. duplicate tap
    @Test
    fun test37_duplicateTap_consumedIntentIsNotRehandled() {
        val intent = Intent().apply {
            putExtra(NotificationNavigationContract.EXTRA_NAVIGATE_TO, "home")
        }
        assertFalse(NotificationIntentParser.isIntentConsumed(intent))

        // First parse succeeds
        val route1 = NotificationIntentParser.parse(intent)
        assertEquals(Screen.Home.route, route1)

        // Mark consumed as MainActivity does
        NotificationIntentParser.markIntentConsumed(intent)
        assertTrue(NotificationIntentParser.isIntentConsumed(intent))

        // Second parse fails because extras were cleaned and consumed flag is set
        val route2 = NotificationIntentParser.parse(intent)
        assertNull(route2)
    }

    // 38. rotation/recreation
    @Test
    fun test38_activityRotationRecreation_consumedIntentIgnored() {
        val intent = Intent().apply {
            putExtra(NotificationNavigationContract.EXTRA_NAVIGATE_TO, "settings")
        }
        // First execution consumes the intent
        NotificationIntentParser.markIntentConsumed(intent)

        // On configuration change, intent is already consumed
        assertTrue(NotificationIntentParser.isIntentConsumed(intent))
    }

    // 39. guest navigation
    @Test
    fun test39_guestNavigation_publicRoutesAllowed() {
        val routes = listOf("home", "movies", "series", "search", "library", "downloads", "settings", "support")
        for (r in routes) {
            val resolved = NotificationIntentParser.parseRaw(navigateTo = r)
            assertNotNull("Route $r should resolve for guest", resolved)
        }
    }

    // 40. authenticated navigation
    @Test
    fun test40_authenticatedNavigation_resolvesAllContractRoutes() {
        val allStatic = NotificationNavigationContract.STATIC_ROUTE_MAPPING.keys
        for (target in allStatic) {
            val resolved = NotificationIntentParser.parseRaw(navigateTo = target)
            assertEquals(NotificationNavigationContract.STATIC_ROUTE_MAPPING[target], resolved)
        }
    }

    // 41. legacy downloads intent
    @Test
    fun test41_legacyDownloadsIntent_resolvesSeamlessly() {
        // StreamDownloaderService uses putExtra("navigate_to", "downloads")
        val legacyIntent = Intent().apply {
            putExtra("navigate_to", "downloads")
        }
        val resolved = NotificationIntentParser.parse(legacyIntent)
        assertEquals(Screen.Downloads.route, resolved)
    }

    // 42. legacy share intent
    @Test
    fun test42_legacyShareIntent_resolvesSeamlessly() {
        // TransferNotificationHelper uses putExtra("navigate_to", "share")
        val legacyIntent = Intent().apply {
            putExtra("navigate_to", "share")
        }
        val resolved = NotificationIntentParser.parse(legacyIntent)
        assertEquals(Screen.Share.route, resolved)
    }

    // 43. notificationId propagation
    @Test
    fun test43_notificationIdPropagation_extractedAccurately() {
        val intent = Intent().apply {
            putExtra(NotificationNavigationContract.EXTRA_NOTIFICATION_ID, "notif_abc-123_45")
        }
        val extractedId = NotificationIntentParser.extractNotificationId(intent)
        assertEquals("notif_abc-123_45", extractedId)

        val invalidIntent = Intent().apply {
            putExtra(NotificationNavigationContract.EXTRA_NOTIFICATION_ID, "bad/id/with/slashes")
        }
        assertNull(NotificationIntentParser.extractNotificationId(invalidIntent))
    }

    // 44. PendingIntent flags
    @Test
    fun test44_notificationPendingIntent_hasImmutableAndSingleTopFlags() {
        val builder = NotificationHelper.buildGeneralNotification(
            context = context,
            id = "notif_flags_test",
            title = "Test Title",
            message = "Test Message",
            navigateTo = "home"
        )
        assertNotNull(builder)

        val notification = builder!!.build()
        val contentIntent = notification.contentIntent
        assertNotNull(contentIntent)

        val shadowPendingIntent = Shadows.shadowOf(contentIntent)
        val savedIntent = shadowPendingIntent.savedIntent
        assertNotNull(savedIntent)

        val flags = savedIntent.flags
        assertTrue("Intent must have FLAG_ACTIVITY_SINGLE_TOP", (flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
        assertTrue("Intent must have FLAG_ACTIVITY_CLEAR_TOP", (flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val piFlags = shadowPendingIntent.flags
            assertTrue("PendingIntent must have FLAG_IMMUTABLE", (piFlags and PendingIntent.FLAG_IMMUTABLE) != 0)
        }
    }

    // 45. explicit MainActivity target
    @Test
    fun test45_notificationIntent_explicitlyTargetsMainActivity() {
        val builder = NotificationHelper.buildGeneralNotification(
            context = context,
            id = "notif_target_test",
            title = "Target Title",
            message = "Target Message",
            navigateTo = "detail",
            movieId = "movie_explicit",
            type = "movie"
        )
        assertNotNull(builder)

        val notification = builder!!.build()
        val shadowPendingIntent = Shadows.shadowOf(notification.contentIntent)
        val targetIntent = shadowPendingIntent.savedIntent

        assertNotNull(targetIntent)
        assertEquals(MainActivity::class.java.name, targetIntent.component?.className)
        assertEquals("detail", targetIntent.getStringExtra(NotificationNavigationContract.EXTRA_NAVIGATE_TO))
        assertEquals("movie", targetIntent.getStringExtra(NotificationNavigationContract.EXTRA_NOTIFICATION_TYPE))
        assertEquals("movie_explicit", targetIntent.getStringExtra(NotificationNavigationContract.EXTRA_MOVIE_ID))

        val resolved = NotificationIntentParser.parse(targetIntent)
        assertEquals(Screen.MovieDetails.createRoute("movie_explicit", autoPlay = false), resolved)
    }
}
