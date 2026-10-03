package com.example.extension.managed

import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ChallengeStatus
import com.example.extension.managed.model.ServerItem
import com.example.extension.managed.web.SafeScraperBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafeScraperBridgeTest {

    open class RecordingListener : SafeScraperBridge.BridgeMessageListener {
        var servers: List<ServerItem>? = null
        var extractionUrl: String? = null
        var challengeStatus: ChallengeStatus? = null
        var error: ExtensionError? = null

        override fun onServerList(servers: List<ServerItem>) {
            this.servers = servers
        }

        override fun onExtractionResult(streamUrl: String) {
            this.extractionUrl = streamUrl
        }

        override fun onChallengeState(status: ChallengeStatus) {
            this.challengeStatus = status
        }

        override fun onError(error: ExtensionError) {
            this.error = error
        }
    }

    @Test
    fun validServerListMessage_parsesCorrectly() {
        val listener = RecordingListener()
        val bridge = SafeScraperBridge("https://tv10.egydead.live", listener)

        val json = """
            {
                "type": "SERVER_LIST",
                "origin": "https://tv10.egydead.live/watch",
                "servers": [
                    { "name": "سيرفر 1", "link": "https://server1.com/embed", "id": "srv1" },
                    { "name": "سيرفر مباشر", "link": "https://server2.com/video.mp4", "id": "srv2" }
                ]
            }
        """.trimIndent()

        bridge.postMessage(json)

        assertNotNull(listener.servers)
        assertEquals(2, listener.servers?.size)
        assertEquals("سيرفر 1", listener.servers?.get(0)?.name)
        assertEquals("https://server1.com/embed", listener.servers?.get(0)?.link)
        assertEquals(false, listener.servers?.get(0)?.isDirectStream)
        assertEquals(true, listener.servers?.get(1)?.isDirectStream)
    }

    @Test
    fun validExtractionResultMessage_parsesCorrectly() {
        val listener = RecordingListener()
        val bridge = SafeScraperBridge("https://tv10.egydead.live", listener)

        val json = """
            {
                "type": "EXTRACTION_RESULT",
                "origin": "https://tv10.egydead.live",
                "streamUrl": "https://storage.provider.com/hls/master.m3u8"
            }
        """.trimIndent()

        bridge.postMessage(json)

        assertEquals("https://storage.provider.com/hls/master.m3u8", listener.extractionUrl)
    }

    @Test
    fun challengeStateMessage_parsesCorrectly() {
        val listener = RecordingListener()
        val bridge = SafeScraperBridge("https://tv10.egydead.live", listener)

        val json = """
            {
                "type": "CHALLENGE_STATE",
                "status": "DETECTED"
            }
        """.trimIndent()

        bridge.postMessage(json)

        assertEquals(ChallengeStatus.DETECTED, listener.challengeStatus)
    }

    @Test
    fun untrustedOrigin_rejected() {
        val listener = RecordingListener()
        val bridge = SafeScraperBridge("https://tv10.egydead.live", listener)

        val json = """
            {
                "type": "EXTRACTION_RESULT",
                "origin": "https://malicious-attacker.com",
                "streamUrl": "https://malicious-attacker.com/payload.js"
            }
        """.trimIndent()

        bridge.postMessage(json)

        assertNotNull(listener.error)
        assertTrue(listener.error?.message?.contains("Untrusted message origin") == true)
    }

    @Test
    fun oversizedPayload_rejected() {
        val listener = RecordingListener()
        val bridge = SafeScraperBridge("https://tv10.egydead.live", listener)

        // Generate a payload exceeding 64KB
        val largeData = "a".repeat(70 * 1024)
        val json = """{"type":"SERVER_LIST","data":"$largeData"}"""

        bridge.postMessage(json)

        assertNotNull(listener.error)
        assertTrue(listener.error?.message?.contains("Payload size exceeds maximum allowed limit") == true)
    }

    @Test
    fun malformedJson_rejected() {
        val listener = RecordingListener()
        val bridge = SafeScraperBridge("https://tv10.egydead.live", listener)

        bridge.postMessage("this is not json {")

        assertNotNull(listener.error)
        assertTrue(listener.error?.message?.contains("Malformed JSON") == true)
    }
}
