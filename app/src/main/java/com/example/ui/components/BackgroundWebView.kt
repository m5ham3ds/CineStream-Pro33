package com.example.ui.components

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.utils.NetworkUtils
import kotlinx.coroutines.delay

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BackgroundWebView(
    urls: List<String>,
    onProgress: (String) -> Unit,
    onSiteVerified: (String) -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    var isInternetAvailable by remember { mutableStateOf(NetworkUtils.isInternetAvailable(context)) }
    
    if (!isInternetAvailable) {
        // Do not even start if no internet. Just wait or complete immediately.
        LaunchedEffect(Unit) {
            onComplete()
        }
        return
    }

    if (urls.isEmpty()) {
        LaunchedEffect(Unit) {
            onComplete()
        }
        return
    }

    var currentIndex by remember { mutableStateOf(0) }
    val currentUrl = if (currentIndex < urls.size) urls[currentIndex] else null

    LaunchedEffect(currentUrl) {
        if (currentUrl != null) {
            delay(20000) // 20 seconds maximum per URL
            onSiteVerified(currentUrl) // Skip it so we don't get stuck forever
            currentIndex++
        }
    }

    
    // We will use a state to force reload if needed
    var reloadTrigger by remember { mutableStateOf(0) }

    if (currentUrl != null) {
        AndroidView(
            // Make it occupy full size but completely transparent so it acts like a real browser
            // Cloudflare Turnstile explicitly checks for screen visibility and rects
            modifier = Modifier.fillMaxSize().alpha(0.01f), 
            factory = { ctx ->
                WebView(ctx).apply {
                    setInitialScale(1)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        javaScriptCanOpenWindowsAutomatically = true
                        // Using the system default user agent is the most reliable way to avoid Cloudflare bot detection
                        val originalUserAgent = WebSettings.getDefaultUserAgent(ctx)
                    userAgentString = originalUserAgent.replace("; wv", "").replace("Version/4.0 ", "")
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    allowFileAccess = true
                    allowContentAccess = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mediaPlaybackRequiresUserGesture = false
                    }
                    
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        private var timeoutHandler = Handler(Looper.getMainLooper())
                        private var checkRunnable: Runnable? = null
                        private var isBypassed = false

                                                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                            handler?.cancel()
                        }
                        
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            view?.evaluateJavascript("""
                                Object.defineProperty(navigator, 'webdriver', { get: () => false });
                                Object.defineProperty(navigator, 'languages', { get: () => ['ar', 'en-US', 'en'] });
                                Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
                                window.chrome = { runtime: {} };
                            """.trimIndent(), null)
                            super.onPageStarted(view, url, favicon)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            cookieManager.flush()
                            isBypassed = false
                            
                            // Cancel any existing runnables
                            checkRunnable?.let { timeoutHandler.removeCallbacks(it) }
                            
                            val jsCheck = """
                                (function() {
                                    // Keep clicking challenge boxes if they exist
                                    var cf = document.querySelector('.cf-turnstile-wrapper, #challenge-stage, input[type="checkbox"], #challenge-form');
                                    if (cf) { cf.click(); }
                                    
                                    try {
                                        var iframes = document.querySelectorAll('iframe');
                                        for (var i = 0; i < iframes.length; i++) {
                                            var innerBtn = iframes[i].contentWindow.document.querySelector('input[type="checkbox"]');
                                            if (innerBtn) innerBtn.click();
                                        }
                                    } catch(e) {}

                                    var title = document.title.toLowerCase();
                                    var body = document.body.innerText.toLowerCase();
                                    var hasCloudflare = title.includes('just a moment') || 
                                                        title.includes('attention required') ||
                                                         body.includes('cloudflare') ||
                                                         body.includes('security check') ||
                                                         body.includes('تأكد من أنك لست روبوت') ||
                                                         body.includes('robot');
                                    
                                    if (hasCloudflare) {
                                        return 'CAPTCHA';
                                    } else if (body.length > 50) {
                                        return 'SUCCESS';
                                    }
                                    return 'UNKNOWN';
                                })();
                            """.trimIndent()
                            
                            // Check continuously every 2 seconds because Cloudflare can auto-redirect or load components asynchronously
                            checkRunnable = object : Runnable {
                                override fun run() {
                                    if (isBypassed) return
                                    
                                    view.evaluateJavascript(jsCheck) { result ->
                                        val res = result?.replace("\"", "") ?: "UNKNOWN"
                                        if (res == "SUCCESS") {
                                            isBypassed = true
                                            cookieManager.flush()
                                            onSiteVerified(currentUrl)
                                            currentIndex++
                                        } else {
                                            // It's a captcha or unknown. Schedule another check.
                                            timeoutHandler.postDelayed(this, 2000)
                                        }
                                    }
                                }
                            }
                            timeoutHandler.postDelayed(checkRunnable!!, 1000)
                        }
                    }
                }
            },
            onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        },
        update = { webView ->
                if (webView.url != currentUrl) {
                    onProgress(currentUrl)
                    val extraHeaders = mutableMapOf<String, String>()
                    extraHeaders["Accept-Language"] = "ar,en-US;q=0.9,en;q=0.8"
                    extraHeaders["DNT"] = "1"
                    extraHeaders["Upgrade-Insecure-Requests"] = "1"
                    webView.loadUrl(currentUrl, extraHeaders)
                } else if (reloadTrigger > 0) {
                    webView.reload()
                }
            }
        )
    } else {
        LaunchedEffect(Unit) {
            onComplete()
        }
    }
}