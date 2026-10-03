package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.YouTubePlayerState
import com.example.ui.components.openYouTubeVideo
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YouTubePlayerUnitTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `test error state message mapping for embedding restricted`() {
        val msg = context.getString(R.string.trailer_error_embedding_restricted)
        val state = YouTubePlayerState.Error(
            error = PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER,
            message = msg
        )
        assertEquals(PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER, state.error)
        assertTrue(state.message.isNotEmpty())
    }

    @Test
    fun `test error state message mapping for video not found`() {
        val msg = context.getString(R.string.trailer_error_not_found)
        val state = YouTubePlayerState.Error(
            error = PlayerConstants.PlayerError.VIDEO_NOT_FOUND,
            message = msg
        )
        assertEquals(PlayerConstants.PlayerError.VIDEO_NOT_FOUND, state.error)
        assertTrue(state.message.isNotEmpty())
    }

    @Test
    fun `test error state message mapping for invalid param`() {
        val msg = context.getString(R.string.trailer_error_invalid_id)
        val state = YouTubePlayerState.Error(
            error = PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST,
            message = msg
        )
        assertEquals(PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST, state.error)
        assertTrue(state.message.isNotEmpty())
    }

    @Test
    fun `test openYouTubeVideo triggers correct intent`() {
        val testVideoId = "dQw4w9WgXcQ"
        openYouTubeVideo(context, testVideoId)

        val shadowApp = shadowOf(context as android.app.Application)
        val nextStartedIntent = shadowApp.nextStartedActivity
        assertNotNull(nextStartedIntent)
        val dataUri = nextStartedIntent.data
        assertNotNull(dataUri)
        val uriStr = dataUri.toString()
        assertTrue(
            "URI should be vnd.youtube or youtube.com web URL",
            uriStr.contains(testVideoId)
        )
    }

    @Test
    fun `test state transitions`() {
        var state: YouTubePlayerState = YouTubePlayerState.Idle
        assertEquals(YouTubePlayerState.Idle, state)

        state = YouTubePlayerState.Loading
        assertEquals(YouTubePlayerState.Loading, state)

        state = YouTubePlayerState.Ready
        assertEquals(YouTubePlayerState.Ready, state)

        val playingState = YouTubePlayerState.Playing("test_id")
        assertEquals("test_id", playingState.videoId)

        state = YouTubePlayerState.Paused
        assertEquals(YouTubePlayerState.Paused, state)

        state = YouTubePlayerState.Ended
        assertEquals(YouTubePlayerState.Ended, state)
    }
}
