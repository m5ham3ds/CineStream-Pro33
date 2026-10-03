package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.SupportDao
import com.example.data.model.SupportMessage
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ServerItem
import com.example.extension.orchestrator.ManagedDiscoveryOutcome
import com.example.ui.screens.profile.SupportViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PHASE 05A: USERS CORE HARDENING TEST SUITE
 *
 * Verifies:
 * 1. MediaActionBottomSheet removes static sample streams and integrates ManagedMediaOrchestrator.
 * 2. MediaActionBottomSheet maintains the SUBSCRIPTION = REMOVE ADS ONLY invariant (no quality gating).
 * 3. MediaActionBottomSheet handles empty sources, multiple servers, and failures gracefully.
 * 4. Callers in HomeScreen, MoviesScreen, SeriesScreen, and AnimeScreen pass media identity.
 * 5. HelpSupportScreen replaces empty onClick with active options dropdown menu.
 * 6. SupportDao and SupportViewModel clearChat operation resets conversation to initial greeting safely.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase05ACoreHardeningTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var supportDao: SupportDao

    private fun findSourceFile(relativePath: String): File {
        val cleanPath = relativePath.removePrefix("app/")
        val candidates = listOf(
            File(relativePath),
            File(cleanPath),
            File("app", cleanPath),
            File("..", relativePath),
            File("../app", cleanPath)
        )
        return candidates.firstOrNull { it.exists() } ?: File(relativePath)
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(context)
        }
        database = AppDatabase.getDatabase(context)
        supportDao = database.supportDao()
    }

    // ==========================================
    // 1. MediaActionBottomSheet Verification
    // ==========================================

    @Test
    fun test01_mediaActionBottomSheet_noStaticSampleStreams() {
        val file = findSourceFile("app/src/main/java/com/example/ui/components/MediaActionBottomSheet.kt")
        assertTrue("MediaActionBottomSheet.kt must exist", file.exists())
        val content = file.readText()

        assertFalse("Must not contain hardcoded Server 1 (HighSpeed)", content.contains("Server 1 (HighSpeed)"))
        assertFalse("Must not contain hardcoded Server 2 (Backup)", content.contains("Server 2 (Backup)"))
        assertFalse("Must not contain hardcoded VideoStream mock model", content.contains("VideoStream("))
        assertFalse("Must not import com.example.domain.models.VideoStream", content.contains("com.example.domain.models.VideoStream"))
    }

    @Test
    fun test02_mediaActionBottomSheet_usesManagedMediaOrchestrator() {
        val file = findSourceFile("app/src/main/java/com/example/ui/components/MediaActionBottomSheet.kt")
        val content = file.readText()

        assertTrue("Must reference ManagedMediaOrchestrator", content.contains("ManagedMediaOrchestrator"))
        assertTrue("Must call discoverServers on orchestrator", content.contains("discoverServers("))
        assertTrue("Must handle ManagedDiscoveryOutcome.Success", content.contains("ManagedDiscoveryOutcome.Success"))
        assertTrue("Must handle ManagedDiscoveryOutcome.RecoverableFailure", content.contains("ManagedDiscoveryOutcome.RecoverableFailure"))
        assertTrue("Must handle ManagedDiscoveryOutcome.SecurityFailure", content.contains("ManagedDiscoveryOutcome.SecurityFailure"))
    }

    @Test
    fun test03_mediaActionBottomSheet_preservesQualityPolicyWithoutSubscriptionCoupling() {
        val file = findSourceFile("app/src/main/java/com/example/ui/components/MediaActionBottomSheet.kt")
        val content = file.readText()

        assertFalse("Must not gate quality on PRO tier", content.contains("if (isPro)") || content.contains("if (pro)"))
        assertFalse("Must not gate quality on PRO_LITE tier", content.contains("PRO_LITE"))
        assertFalse("Must not gate quality on isPremium", content.contains("isPremium"))
        assertFalse("Must not contain artificial quality caps for free users", content.contains("premiumQuality"))
    }

    @Test
    fun test04_mediaActionBottomSheet_callersPassMediaIdentity() {
        val homeFile = findSourceFile("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt")
        val moviesFile = findSourceFile("app/src/main/java/com/example/ui/screens/movies/MoviesScreen.kt")
        val seriesFile = findSourceFile("app/src/main/java/com/example/ui/screens/series/SeriesScreen.kt")
        val animeFile = findSourceFile("app/src/main/java/com/example/ui/screens/anime/AnimeScreen.kt")

        assertTrue("HomeScreen must pass mediaId", homeFile.readText().contains("mediaId = selectedMediaId"))
        assertTrue("MoviesScreen must pass mediaId", moviesFile.readText().contains("mediaId = selectedMediaId"))
        assertTrue("SeriesScreen must pass mediaId", seriesFile.readText().contains("mediaId = selectedMediaId"))
        assertTrue("AnimeScreen must pass mediaId", animeFile.readText().contains("mediaId = selectedMediaId"))
    }

    @Test
    fun test05_mediaDiscoveryOutcome_dataContractsIntegrity() {
        val servers = listOf(
            ServerItem(id = "srv_1", name = "EgyDead 1080p", link = "https://cdn.example.com/hls.m3u8"),
            ServerItem(id = "srv_2", name = "Qfilm 720p", link = "https://cdn.example.com/direct.mp4", isDirectStream = true)
        )
        val success = ManagedDiscoveryOutcome.Success(
            servers = servers,
            website = "https://tv10.egydead.live",
            sourceUrl = "https://tv10.egydead.live/movie/123"
        )

        assertEquals("Server list size must be 2", 2, success.servers.size)
        assertEquals("Server 1 name matches", "EgyDead 1080p", success.servers[0].name)
        assertTrue("Server 2 is direct stream", success.servers[1].isDirectStream)

        val failure = ManagedDiscoveryOutcome.RecoverableFailure(
            error = ExtensionError.MediaNotFound("movie123", "No servers found")
        )
        assertEquals("Error message matches", "No servers found", failure.error.message)
    }

    // ==========================================
    // 2. HelpSupportScreen & SupportViewModel
    // ==========================================

    @Test
    fun test06_helpSupportScreen_noEmptyOnClick() {
        val file = findSourceFile("app/src/main/java/com/example/ui/screens/profile/HelpSupportScreen.kt")
        assertTrue("HelpSupportScreen.kt must exist", file.exists())
        val content = file.readText()

        assertFalse("Must not contain empty /* TODO */ in TopAppBar actions", content.contains("/* TODO */"))
        assertTrue("Must contain options DropdownMenu", content.contains("DropdownMenu("))
        assertTrue("Must contain contact support option", content.contains("R.string.contact_support"))
        assertTrue("Must contain clear chat history option", content.contains("R.string.clear_history"))
    }

    @Test
    fun test07_supportDao_clearMessages() = runBlocking {
        supportDao.insertMessage(SupportMessage(id = "msg1", text = "Test message 1", isFromUser = true))
        supportDao.insertMessage(SupportMessage(id = "msg2", text = "Test message 2", isFromUser = false))

        val countBefore = supportDao.getMessageCount()
        assertTrue("Count before must be at least 2", countBefore >= 2)

        supportDao.clearMessages()

        val countAfter = supportDao.getMessageCount()
        assertEquals("Count after clearMessages must be 0", 0, countAfter)
    }

    @Test
    fun test08_supportViewModel_clearChat_resetsToGreeting() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = SupportViewModel(app)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Add a message
        vm.sendMessage("Need help with streaming")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Trigger clearChat
        vm.clearChat()

        // Wait for coroutine and Room DB insertion to complete
        var msgs = supportDao.getAllMessages().first()
        var attempts = 0
        while (msgs.isEmpty() && attempts < 20) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            kotlinx.coroutines.delay(50)
            msgs = supportDao.getAllMessages().first()
            attempts++
        }

        // Verify messages in DB has 1 message (the greeting)
        assertTrue("Messages list must not be empty after clearChat", msgs.isNotEmpty())
        assertFalse("Greeting message must not be from user", msgs.first().isFromUser)
    }
}
