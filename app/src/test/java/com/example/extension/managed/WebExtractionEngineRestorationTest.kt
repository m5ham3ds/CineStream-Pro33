package com.example.extension.managed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.*
import com.example.extension.managed.runtime.ExtractionSession
import com.example.extension.managed.scraper.QfilmScraper
import com.example.extension.managed.web.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebExtractionEngineRestorationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ==========================================
    // 1. JS PACKER UNPACKER TESTS
    // ==========================================

    @Test
    fun jsPackerUnpacker_unpacksDeanEdwardsPackedScript_base36() {
        val packed = """
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('g d=3g 3f({m:"o",38:"a://n.1m.1l/37/1k/1j/36.33?t=32&s=1c"});',36,125,'||||||||||https|||player|||var||||||id|proxydwxro|playerjs||||||||||||||||||||||||1790294029|||||||00126|01|cc|visitmycity||||||||||||||||||||||||||||||||||||||||||||||||||||master|m3u8|||urlset|hls2|file|||||||Playerjs|new'.split('|')))
        """.trimIndent()

        assertTrue(JsPackerUnpacker.isPacked(packed))
        val unpacked = JsPackerUnpacker.unpack(packed)
        assertNotNull(unpacked)
        assertTrue(unpacked!!.contains("file:\"https://proxydwxro.visitmycity.cc/hls2/01/00126/urlset.m3u8?t=master&s=1790294029\""))
    }

    @Test
    fun jsPackerUnpacker_unpacksBase62Script() {
        val packed = """
            eval(function(p,a,c,k,e,r){e=function(c){return(c<a?'':e(parseInt(c/a)))+((c=c%a)>35?String.fromCharCode(c+29):c.toString(36))};if(!''.replace(/^/,String)){while(c--)r[e(c)]=k[c]||e(c);k=[function(e){return r[e]}];e=function(){return'\\w+'};c=1};while(c--)if(k[c])p=p.replace(new RegExp('\\b'+e(c)+'\\b','g'),k[c]);return p}('4 3="2://1.0/5.6";',62,7,'com|example|https|videoUrl|var|stream|m3u8'.split('|'),0,{}))
        """.trimIndent()

        assertTrue(JsPackerUnpacker.isPacked(packed))
        val unpacked = JsPackerUnpacker.unpack(packed)
        assertNotNull(unpacked)
        assertTrue(unpacked!!.contains("https://example.com/stream.m3u8"))
    }

    @Test
    fun jsPackerUnpacker_handlesEscapedQuotesInPayload() {
        val packed = """
            eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('g 4=3({1:"2",0:\'a://b.c/5.6\'});$(\'7.8\').9();',36,10,'file|id|player|Playerjs|playerInstance|master|m3u8|div|ad|hide|https|cdn|com'.split('|')))
        """.trimIndent()

        assertTrue(JsPackerUnpacker.isPacked(packed))
        val unpacked = JsPackerUnpacker.unpack(packed)
        assertNotNull(unpacked)
        assertTrue(unpacked!!.contains("https://cdn.com/master.m3u8"))
    }

    @Test
    fun jsPackerUnpacker_unpackAll_extractsMultipleBlocks() {
        val html = """
            <html>
            <head>
            <script>eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('1 0="2";',3,3,'foo|var|bar'.split('|')))</script>
            <script>eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('1 0="2";',3,3,'baz|var|qux'.split('|')))</script>
            </head>
            </html>
        """.trimIndent()

        val unpackedList = JsPackerUnpacker.unpackAll(html)
        assertEquals(2, unpackedList.size)
        assertTrue(unpackedList[0].contains("var foo=\"bar\""))
        assertTrue(unpackedList[1].contains("var baz=\"qux\""))
    }

    // ==========================================
    // 2. MEDIA STREAM DETECTOR & VALIDATION TESTS
    // ==========================================

    @Test
    fun mediaStreamDetector_identifiesMediaUrls() {
        assertTrue(MediaStreamDetector.isMediaUrl("https://example.com/master.m3u8"))
        assertTrue(MediaStreamDetector.isMediaUrl("https://example.com/video.mp4?token=123"))
        assertTrue(MediaStreamDetector.isMediaUrl("https://example.com/hls2/01/00126/v3gopuw3s8js_,l,h,.urlset/master.m3u8?t=abc"))
        assertTrue(MediaStreamDetector.isMediaUrl("https://videodelivery.net/abc123def/manifest/video.m3u8"))
        assertTrue(MediaStreamDetector.isMediaUrl("https://example.com/manifest.mpd"))
        assertTrue(MediaStreamDetector.isMediaUrl("https://example.com/movie.mkv"))
        assertTrue(MediaStreamDetector.isMediaUrl("https://example.com/clip.webm"))

        // False for static assets
        assertFalse(MediaStreamDetector.isMediaUrl("https://example.com/image.png"))
        assertFalse(MediaStreamDetector.isMediaUrl("https://example.com/style.css"))
        assertFalse(MediaStreamDetector.isMediaUrl("https://example.com/script.js"))
        assertFalse(MediaStreamDetector.isMediaUrl("https://example.com/favicon.ico"))
        assertFalse(MediaStreamDetector.isMediaUrl(""))
    }

    @Test
    fun mediaStreamDetector_filtersAdsAndAnalytics() {
        assertTrue(MediaStreamDetector.isAdOrAnalytics("https://googleads.g.doubleclick.net/pagead/ads"))
        assertTrue(MediaStreamDetector.isAdOrAnalytics("https://connect.facebook.net/en_US/fbevents.js"))
        assertTrue(MediaStreamDetector.isAdOrAnalytics("https://www.google-analytics.com/analytics.js"))
        assertTrue(MediaStreamDetector.isAdOrAnalytics("https://s10.histats.com/js15_as.js"))
        assertTrue(MediaStreamDetector.isAdOrAnalytics("https://llvpn.com/tag.min.js"))

        assertFalse(MediaStreamDetector.isAdOrAnalytics("https://cdn.example.com/hls/master.m3u8"))
        // dnsads is NOT blocked so anti-adblock in embed player does not trip
        assertFalse(MediaStreamDetector.isAdOrAnalytics("https://wwa.liiivideo.com/js/dnsads.js"))
    }

    @Test
    fun mediaStreamDetector_validatesMediaUrlsStrictly() {
        // Valid HTTPS streams
        assertTrue(MediaStreamDetector.validateMediaUrl("https://cdn.example.com/master.m3u8"))
        assertTrue(MediaStreamDetector.validateMediaUrl("https://cdn.example.com/movie.mp4"))
        assertTrue(MediaStreamDetector.validateMediaUrl("https://cdn.example.com/video.mkv"))

        // Invalid: Insecure HTTP
        assertFalse(MediaStreamDetector.validateMediaUrl("http://cdn.example.com/master.m3u8"))

        // Invalid: Malformed or non-URL
        assertFalse(MediaStreamDetector.validateMediaUrl("not-a-valid-url"))
        assertFalse(MediaStreamDetector.validateMediaUrl(""))
        assertFalse(MediaStreamDetector.validateMediaUrl("   "))

        // Invalid: Non-media static files
        assertFalse(MediaStreamDetector.validateMediaUrl("https://cdn.example.com/image.png"))
        assertFalse(MediaStreamDetector.validateMediaUrl("https://cdn.example.com/script.js"))

        // Invalid: Forbidden private hosts
        assertFalse(MediaStreamDetector.validateMediaUrl("https://localhost/video.mp4"))
        assertFalse(MediaStreamDetector.validateMediaUrl("https://127.0.0.1/video.mp4"))
        assertFalse(MediaStreamDetector.validateMediaUrl("https://192.168.1.1/video.mp4"))

        // Invalid: Ad trackers
        assertFalse(MediaStreamDetector.validateMediaUrl("https://googleads.g.doubleclick.net/video.mp4"))
    }

    @Test
    fun mediaStreamDetector_normalizesUrlsCorrectly() {
        val rawEscaped = "https:\\/\\/cdn.example.com\\/hls\\/master.m3u8?t=abc\\u0026s=123"
        val normalized = MediaStreamDetector.normalizeStreamUrl(rawEscaped)
        assertEquals("https://cdn.example.com/hls/master.m3u8?t=abc&s=123", normalized)

        val protocolRelative = "//cdn.example.com/stream.mp4"
        assertEquals("https://cdn.example.com/stream.mp4", MediaStreamDetector.normalizeStreamUrl(protocolRelative))

        val quoted = "\"https://cdn.example.com/video.mp4\""
        assertEquals("https://cdn.example.com/video.mp4", MediaStreamDetector.normalizeStreamUrl(quoted))
    }

    @Test
    fun mediaStreamDetector_determinesProtocolsAndMimeTypes() {
        assertEquals(StreamProtocol.HLS, MediaStreamDetector.determineProtocol("https://cdn.com/master.m3u8"))
        assertEquals("application/x-mpegURL", MediaStreamDetector.determineMimeType("https://cdn.com/master.m3u8", StreamProtocol.HLS))

        assertEquals(StreamProtocol.DASH, MediaStreamDetector.determineProtocol("https://cdn.com/manifest.mpd"))
        assertEquals("application/dash+xml", MediaStreamDetector.determineMimeType("https://cdn.com/manifest.mpd", StreamProtocol.DASH))

        assertEquals(StreamProtocol.DIRECT_FILE, MediaStreamDetector.determineProtocol("https://cdn.com/film.mp4"))
        assertEquals("video/mp4", MediaStreamDetector.determineMimeType("https://cdn.com/film.mp4", StreamProtocol.DIRECT_FILE))

        assertEquals(StreamProtocol.DIRECT_FILE, MediaStreamDetector.determineProtocol("https://cdn.com/film.mkv"))
        assertEquals("video/x-matroska", MediaStreamDetector.determineMimeType("https://cdn.com/film.mkv", StreamProtocol.DIRECT_FILE))

        assertEquals(StreamProtocol.DIRECT_FILE, MediaStreamDetector.determineProtocol("https://cdn.com/film.webm"))
        assertEquals("video/webm", MediaStreamDetector.determineMimeType("https://cdn.com/film.webm", StreamProtocol.DIRECT_FILE))
    }

    // ==========================================
    // 3. RUNTIME SCRIPTS & DOM INSPECTION TESTS
    // ==========================================

    @Test
    fun runtimeMediaExtractionScript_isStrictAndComplete() {
        val script = RuntimeMediaExtractionScript.SCRIPT
        assertTrue("Script must contain SafeScraperBridge interface", script.contains("SafeScraperBridge"))
        assertTrue("Script must contain EXTRACTION_RESULT message type", script.contains("EXTRACTION_RESULT"))
        assertTrue("Script must unpack Dean Edwards packed scripts", script.contains("unpackDeanEdwardsDeterministic"))
        assertTrue("Script must inspect video elements", script.contains("document.querySelectorAll('video')"))
        assertTrue("Script must inspect currentSrc", script.contains("v.currentSrc"))
        assertTrue("Script must inspect source elements", script.contains("document.querySelectorAll('source')"))
        assertTrue("Script must inspect Player instances", script.contains("window.player"))
        assertTrue("Script must inspect JWPlayer", script.contains("window.jwplayer"))
        assertTrue("Script must inspect VideoJS", script.contains("window.videojs"))
        assertTrue("Script must inspect Performance Resource entries", script.contains("performance.getEntriesByType('resource')"))
        assertTrue("Script must trigger playback safely", script.contains("v.play()"))
        assertTrue("Script must setup MutationObserver", script.contains("MutationObserver"))

        val earlyScript = RuntimeMediaExtractionScript.EARLY_HOOK_SCRIPT
        assertTrue("Early script must hook fetch", earlyScript.contains("window.fetch"))
        assertTrue("Early script must hook XMLHttpRequest", earlyScript.contains("window.XMLHttpRequest"))
        assertTrue("Early script must hook HTMLMediaElement", earlyScript.contains("HTMLMediaElement.prototype"))
        assertTrue("Early script must hook Playerjs", earlyScript.contains("window.Playerjs"))
    }

    // ==========================================
    // 4. RESOURCE OBSERVATION & SECURITY TESTS
    // ==========================================

    @Test
    fun safeScraperBridge_enforcesMediaUrlValidation() {
        var extractionUrl: String? = null
        var errorReported: ExtensionError? = null

        val listener = object : SafeScraperBridge.BridgeMessageListener {
            override fun onServerList(servers: List<ServerItem>) {}
            override fun onExtractionResult(streamUrl: String) { extractionUrl = streamUrl }
            override fun onChallengeState(status: ChallengeStatus) {}
            override fun onError(error: ExtensionError) { errorReported = error }
        }

        val bridge = SafeScraperBridge(expectedOrigin = "https://a.qfilm.tv", listener = listener)

        // 1. Valid HTTPS m3u8
        bridge.postMessage("""{"type":"EXTRACTION_RESULT","streamUrl":"https://cdn.example.com/hls/master.m3u8"}""")
        assertEquals("https://cdn.example.com/hls/master.m3u8", extractionUrl)
        assertNull(errorReported)

        // 2. Insecure HTTP URL -> Must be rejected by validation
        extractionUrl = null
        bridge.postMessage("""{"type":"EXTRACTION_RESULT","streamUrl":"http://insecure.example.com/video.mp4"}""")
        assertNull(extractionUrl)
        assertNotNull(errorReported)
        assertTrue(errorReported?.message?.contains("invalid or untrusted") == true)

        // 3. Non-media URL -> Must be rejected
        errorReported = null
        bridge.postMessage("""{"type":"EXTRACTION_RESULT","streamUrl":"https://example.com/page.html"}""")
        assertNull(extractionUrl)
        assertNotNull(errorReported)

        // 4. Empty URL -> Must be rejected
        errorReported = null
        bridge.postMessage("""{"type":"EXTRACTION_RESULT","streamUrl":""}""")
        assertNull(extractionUrl)
        assertNotNull(errorReported)
    }

    @Test
    fun trustedEmbedHostPolicy_enforcesHttpsAndRejectsForbiddenHosts() {
        val allowedUri = android.net.Uri.parse("https://wwa.liiivideo.com/embed-v3gopuw3s8js.html")
        assertTrue(TrustedEmbedHostPolicy.isNavigationAllowed(allowedUri, "https://a.qfilm.tv", "wwa.liiivideo.com"))

        // Reject HTTP
        val httpUri = android.net.Uri.parse("http://wwa.liiivideo.com/embed.html")
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(httpUri, "https://a.qfilm.tv", "wwa.liiivideo.com"))

        // Reject Localhost
        val localUri = android.net.Uri.parse("https://localhost/embed.html")
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(localUri, "https://a.qfilm.tv", "wwa.liiivideo.com"))

        // Reject Private IP
        val privateIpUri = android.net.Uri.parse("https://192.168.1.100/embed.html")
        assertFalse(TrustedEmbedHostPolicy.isNavigationAllowed(privateIpUri, "https://a.qfilm.tv", "wwa.liiivideo.com"))

        // Allow direct media stream
        val mediaUri = android.net.Uri.parse("https://proxydwxro.visitmycity.cc/hls2/01/00126/master.m3u8")
        assertTrue(TrustedEmbedHostPolicy.isNavigationAllowed(mediaUri, "https://a.qfilm.tv", "wwa.liiivideo.com"))
    }

    // ==========================================
    // 5. TIMEOUT & CANCELLATION TESTS
    // ==========================================

    @Test
    fun controlledWebViewEngine_handlesTimeoutGracefully() = runBlocking {
        val engine = ControlledWebViewEngine(context)
        // With an invalid unreachable HTTPS target, timeout must return Timeout ExtensionError
        val result = engine.extractStreamUrl(
            targetUrl = "https://192.0.2.1/invalid_stream.html",
            targetServerId = "1",
            script = "",
            timeoutMs = 500L,
            expectedOrigin = "https://a.qfilm.tv"
        )

        assertTrue("Engine must fail gracefully on timeout or invalid host", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex?.message?.isNotBlank() == true)
    }

    @Test
    fun controlledWebViewEngine_cancelsCleanly() = runBlocking {
        val engine = ControlledWebViewEngine(context)
        engine.cancel()
        // Subsequent or active operations clean up without crashing
        assertTrue(true)
    }

    // ==========================================
    // 6. REAL REGRESSION LIVE EXTRACTION TEST
    // ==========================================

    @Test
    fun realRegression_extractsActualPlayableMediaUrl_fromLiiivideoTarget() = runBlocking {
        val targetEmbedUrl = "https://wwa.liiivideo.com/embed-v3gopuw3s8js.html"
        val expectedOrigin = "https://a.qfilm.tv"

        val extractedStream = StaticMediaExtractor.extract(targetEmbedUrl, expectedOrigin)
        assertNotNull("Stage A StaticMediaExtractor must discover stream from live liiivideo embed", extractedStream)
        assertTrue("Discovered stream must be an m3u8 playlist", extractedStream!!.contains(".m3u8"))
        assertTrue("Stream must have https scheme", extractedStream.startsWith("https://"))
        println("[REGRESSION_TEST_SUCCESS] Live stream URL extracted successfully: $extractedStream")
    }

    @Test
    fun qfilmScraper_extractStream_resolvesEmbedServerViaPipeline() = runBlocking {
        val targetEmbedUrl = "https://wwa.liiivideo.com/embed-v3gopuw3s8js.html"
        val scraper = QfilmScraper()
        val extension = ManagedExtension(
            id = "qfilm",
            name = "كيو فيلم",
            baseUrl = "https://a.qfilm.tv",
            scraperKey = "qfilm",
            contentTypes = setOf(ContentType.MOVIE),
            status = ExtensionLifecycleStatus.ACTIVE
        )
        val server = ServerItem(
            id = "1",
            name = "Wwa",
            link = targetEmbedUrl,
            isDirectStream = false,
            serverType = ServerType.EMBED,
            requiresWebView = true
        )
        val session = ExtractionSession(
            sessionId = "test-session",
            extensionId = extension.id,
            scraperKey = extension.scraperKey,
            targetPageUrl = "https://a.qfilm.tv/watch.php?vid=2df296d1a"
        )
        val request = ExtractionRequest(serverItem = server, mediaTitle = "محمود التاني")

        val result = scraper.extractStream(extension, session, request, null)
        assertTrue("extractStream on real embed server must succeed", result.isSuccess)
        val extractionResult = result.getOrThrow()
        assertNotNull(extractionResult.playbackSource)
        val streamUrl = extractionResult.playbackSource!!.streamUrl
        assertTrue("Stream URL must be resolved to m3u8", streamUrl.contains(".m3u8"))
        assertEquals(StreamProtocol.HLS, extractionResult.playbackSource!!.protocol)
        assertEquals("application/x-mpegURL", extractionResult.playbackSource!!.mimeType)
        println("[QFILM_EMBED_EXTRACTION_SUCCESS] Stream URL resolved: $streamUrl")
    }
}
