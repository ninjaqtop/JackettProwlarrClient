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

    // ───────────────────────────────────────────────────────────────────────────
    // SUPPORT FUNCTIONS (unchanged)
    // ───────────────────────────────────────────────────────────────────────────

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

private class InMemoryCookieJar : okhttp3.CookieJar {
    private val store = mutableMapOf<String, MutableList<okhttp3.Cookie>>()
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
    }
    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = store[url.host] ?: emptyList()
    fun clear() = store.clear()
}
