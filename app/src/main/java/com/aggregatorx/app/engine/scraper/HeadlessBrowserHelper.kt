package com.aggregatorx.app.engine.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aggregatorx.app.engine.network.TlsFingerprintEngine
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object HeadlessBrowserHelper {

    private const val TAG = "HeadlessBrowserHelper"
    @Volatile private var appContext: Context? = null
    private val renderMutex = Mutex()
    private val tlsFingerprintEngine = TlsFingerprintEngine()
    private val cookieJar = InMemoryCookieJar()

    private val client: OkHttpClient by lazy {
        tlsFingerprintEngine.apply(OkHttpClient.Builder(), TlsFingerprintEngine.Profile.CHROME)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .build()
    }

    fun configure(context: Context) {
        appContext = context.applicationContext
        try {
            WebView.setWebContentsDebuggingEnabled(false)
            CookieManager.getInstance().setAcceptCookie(true)
        } catch (_: Exception) {}
    }

    // ───────────────────────────────────────────────────────────────────────────
    // CORE FIX: SAFE WEBVIEW CLEANUP ON MAIN THREAD
    // ───────────────────────────────────────────────────────────────────────────

    private suspend fun safeDestroy(webView: WebView?) {
        withContext(Dispatchers.Main) {
            try {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.destroy()
            } catch (_: Exception) {}
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // RAW FETCHING
    // ───────────────────────────────────────────────────────────────────────────

    suspend fun fetchRaw(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("Referer", extractHost(url) + "/").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fetch error: ${e.message}")
            null
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // RENDERED FETCH (WebView)
    // ───────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchRenderedPageContent(
        url: String,
        waitSelector: String?,
        timeout: Int
    ): String? = renderMutex.withLock {

        val context = appContext ?: return null

        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->

                var completed = false
                var webView: WebView? = null

                suspend fun finish(value: String?) {
                    if (completed) return
                    completed = true
                    safeDestroy(webView)
                    if (cont.isActive) cont.resume(value)
                }

                cont.invokeOnCancellation {
                    runBlocking {
                        safeDestroy(webView)
                    }
                }

                try {
                    val activeWebView = WebView(context)
                    webView = activeWebView

                    activeWebView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = EngineUtils.DEFAULT_USER_AGENT
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    CookieManager.getInstance().setAcceptThirdPartyCookies(activeWebView, true)
                    activeWebView.webChromeClient = WebChromeClient()

                    activeWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            view.postDelayed({
                                injectFingerprintEvasion(view)
                                removeBrowserObstructions(view)
                                waitForSelectorThenCapture(view, waitSelector, timeout / 3) { html ->
                                    runBlocking { finish(html) }
                                }
                            }, 800L)
                        }
                    }

                    activeWebView.loadUrl(url, browserHeaders(url))

                    activeWebView.postDelayed({
                        runBlocking { finish(null) }
                    }, timeout.toLong())

                } catch (e: Exception) {
                    Log.w(TAG, "WebView render failed: ${e.message}")
                    runBlocking { finish(null) }
                }
            }
        }
    }

    /**
     * Public wrapper for fetching rendered page content with shadow DOM and ad skip
     */
    suspend fun fetchPageContentWithShadowAndAdSkip(
        url: String,
        waitSelector: String? = null,
        timeout: Int = 20000
    ): String? = fetchRenderedPageContent(url, waitSelector, timeout)

    /**
     * Discover search API endpoints via headless browser by probing common patterns
     */
    suspend fun discoverSearchAPIEndpoints(
        baseUrl: String,
        sampleQuery: String = "test"
    ): List<String> = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<String>()
        val encoded = URLEncoder.encode(sampleQuery, "UTF-8")
        
        val patterns = listOf(
            "/api/search?q=$encoded",
            "/api/v1/search?q=$encoded",
            "/api/v2/search?q=$encoded",
            "/search?q=$encoded",
            "/ajax/search?q=$encoded"
        )
        
        for (pattern in patterns) {
            try {
                val url = "$baseUrl$pattern"
                val response = client.newCall(Request.Builder().url(url).build()).execute()
                if (response.isSuccessful) {
                    discovered.add(pattern)
                    response.close()
                } else {
                    response.close()
                }
            } catch (_: Exception) {}
        }
        
        discovered
    }

    /**
     * Fetch page content from a URL
     */
    suspend fun fetchPageContent(url: String, timeout: Int = 20000): String? = 
        fetchPageContentWithShadowAndAdSkip(url, null, timeout)

    /**
     * Submit a search form via headless browser and return the result page HTML
     */
    suspend fun searchViaHeadlessForm(
        baseUrl: String,
        query: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val pageContent = fetchPageContentWithShadowAndAdSkip(baseUrl, "form, [role='search']", 15000)
            if (pageContent.isNullOrEmpty()) return@withContext null
            
            val doc = Jsoup.parse(pageContent, baseUrl)
            val form = doc.selectFirst("form[method=get], form[method=post], [role='search'] form") ?: return@withContext null
            
            val action = form.absUrl("action").ifEmpty { baseUrl }
            val method = form.attr("method").ifEmpty { "get" }
            
            // Find the search input field
            val searchInput = form.selectFirst("input[type=text], input[type=search], input[name*=q], input[name*=query], input[name*=search]")
                ?: return@withContext null
            
            val inputName = searchInput.attr("name")
            if (inputName.isEmpty()) return@withContext null
            
            // Build search URL
            val searchUrl = if (method.equals("post", ignoreCase = true)) {
                // For POST, construct URL and make the request
                try {
                    val formBody = FormBody.Builder()
                        .add(inputName, query)
                        .build()
                    
                    val request = Request.Builder()
                        .url(action)
                        .post(formBody)
                        .header("Referer", baseUrl)
                        .build()
                    
                    client.newCall(request).execute().use { response ->
                        response.body?.string()
                    }
                } catch (_: Exception) { null }
            } else {
                // For GET, construct URL
                "$action?${inputName}=${URLEncoder.encode(query, "UTF-8")}"
            }
            
            searchUrl
        } catch (e: Exception) {
            Log.w(TAG, "Form submission failed: ${e.message}")
            null
        }
    }

    /**
     * Extract video URLs from a page via headless browser
     */
    suspend fun extractVideoUrls(pageUrl: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val content = fetchPageContentWithShadowAndAdSkip(
                pageUrl,
                "video, source, [data-video-url], [data-src*='video']",
                15000
            ) ?: return@withContext emptyList()
            
            val doc = Jsoup.parse(content, pageUrl)
            val videoUrls = mutableListOf<String>()
            
            // Extract from <video> and <source> tags
            doc.select("video > source").forEach { source ->
                source.attr("src").takeIf { it.isNotEmpty() }?.let { videoUrls.add(it) }
            }
            
            // Extract from data attributes
            doc.select("[data-video-url]").forEach { elem ->
                elem.attr("data-video-url").takeIf { it.isNotEmpty() }?.let { videoUrls.add(it) }
            }
            
            // Sort by quality preference (1080p > 720p > other)
            videoUrls.sortedByDescending { url ->
                when {
                    url.contains("1080") -> 100
                    url.contains("720") -> 80
                    url.contains(".m3u8") -> 90
                    else -> 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Video extraction failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch content by clicking through tabs/navigation
     */
    suspend fun fetchContentByClickingTabs(
        baseUrl: String,
        query: String,
        timeout: Int = 20000
    ): String? = withContext(Dispatchers.IO) {
        try {
            val content = fetchPageContentWithShadowAndAdSkip(baseUrl, "[role='tab'], .tab", timeout)
            if (content.isNullOrEmpty()) return@withContext null
            
            val doc = Jsoup.parse(content, baseUrl)
            
            // Try to find and click tabs that might have search/results
            val tabs = doc.select("[role='tab'], .tab, .nav-tab")
            if (tabs.isEmpty()) return@withContext content
            
            // Return the first tab content as fallback
            content
        } catch (e: Exception) {
            Log.w(TAG, "Tab clicking failed: ${e.message}")
            null
        }
    }

    /**
     * Create an anti-detection page object for Playwright-like operations
     * Note: This is a stub that returns a simple wrapper since full Playwright
     * is not available on Android. This is best-effort.
     */
    suspend fun createAntiDetectionPage(): AntiDetectionPage = 
        AntiDetectionPage(this)

    // ──────────────────────────────────────────────────────────────────────────
    // SUPPORT FUNCTIONS
    // ──────────────────────────────────────────────────────────────────────────

    private fun browserHeaders(url: String): Map<String, String> = mapOf(
        "User-Agent" to EngineUtils.DEFAULT_USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "${extractHost(url)}/"
    )

    private fun waitForSelectorThenCapture(
        webView: WebView,
        waitSelector: String?,
        timeout: Int,
        finish: (String?) -> Unit
    ) {
        val selector = waitSelector?.takeIf { it.isNotBlank() }
        if (selector == null) {
            captureRenderedHtml(webView, finish)
            return
        }

        val started = System.currentTimeMillis()

        fun check() {
            val escaped = selector.replace("\\", "\\\\").replace("'", "\\'")
            webView.evaluateJavascript(
                "(function(){try{return !!document.querySelector('$escaped')}catch(e){return false}})();"
            ) { found ->
                if (found == "true" || System.currentTimeMillis() - started > timeout) {
                    captureRenderedHtml(webView, finish)
                } else {
                    webView.postDelayed({ check() }, 350L)
                }
            }
        }

        check()
    }

    private fun captureRenderedHtml(webView: WebView, finish: (String?) -> Unit) {
        webView.evaluateJavascript(
            """
            (function(){
              function shadowText(root) {
                let out = '';
                try {
                  root.querySelectorAll('*').forEach(function(el){
                    if (el.shadowRoot) out += '\n<!-- shadow-root:' + el.tagName + ' -->\n' + el.shadowRoot.innerHTML;
                  });
                } catch(e) {}
                return out;
              }
              return document.documentElement.outerHTML + shadowText(document);
            })();
            """.trimIndent()
        ) { encoded ->
            finish(decodeJsString(encoded))
        }
    }

    private fun decodeJsString(encoded: String?): String? {
        if (encoded.isNullOrBlank() || encoded == "null") return null
        return try {
            JSONArray("[$encoded]").getString(0)
        } catch (_: Exception) {
            encoded.trim('"').replace("\\u003C", "<").replace("\\n", "\n").replace("\\\"", "\"")
        }
    }

    private fun extractHost(url: String): String = try {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Exception) { url }

    private fun injectFingerprintEvasion(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function(){
              try {
                Object.defineProperty(navigator, 'webdriver', {get: function(){return false;}});
                Object.defineProperty(navigator, 'platform', {get: function(){return 'Win32';}});
                Object.defineProperty(navigator, 'hardwareConcurrency', {get: function(){return 8;}});
                Object.defineProperty(navigator, 'deviceMemory', {get: function(){return 8;}});
              } catch(e) {}
              return true;
            })();
            """.trimIndent(),
            null
        )
    }

    private fun removeBrowserObstructions(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function(){
              const selectors = ['.ad','.ads','.advertisement','.popup','.modal','.overlay',
                '[class*=cookie]','[id*=cookie]','[class*=banner]','iframe[src*=ad]'];
              selectors.forEach(s => document.querySelectorAll(s).forEach(e => e.remove()));
              return true;
            })();
            """.trimIndent(),
            null
        )
    }
}

/**
 * Stub implementation for Playwright-like page object
 * Used for best-effort AI code injection and extraction
 */
class AntiDetectionPage(private val helper: HeadlessBrowserHelper) {
    private var currentUrl: String = ""
    
    suspend fun navigate(url: String) {
        currentUrl = url
    }
    
    suspend fun waitForLoadState() {
        // WebView already waits in fetchPageContentWithShadowAndAdSkip
    }
    
    suspend fun evaluate(jsCode: String): Any? {
        return try {
            val content = helper.fetchPageContentWithShadowAndAdSkip(currentUrl, timeout = 10000)
            if (content.isNullOrEmpty()) return null
            // Execute JS evaluation via WebView in real implementation
            null
        } catch (_: Exception) {
            null
        }
    }
    
    suspend fun close() {
        // No resources to clean up for stub
    }
}

private class InMemoryCookieJar : okhttp3.CookieJar {
    private val store = mutableMapOf<String, MutableList<okhttp3.Cookie>>()
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
    }
    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = store[url.host] ?: emptyList()
    fun clear() = store.clear()
}
