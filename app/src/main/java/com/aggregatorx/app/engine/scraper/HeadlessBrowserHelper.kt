package com.aggregatorx.app.engine.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * HeadlessBrowserHelper — hybrid Android browser stack.
 *
 * Uses a real Chromium WebView when an application Context is configured, with
 * OkHttp + Jsoup retained as a fallback for non-UI/background paths.
 */
object HeadlessBrowserHelper {

    private const val TAG = "HeadlessBrowserHelper"
    @Volatile private var appContext: Context? = null
    private val cookieJar = InMemoryCookieJar()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", EngineUtils.DEFAULT_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Sec-Ch-Ua-Mobile", "?1")
                    .header("Upgrade-Insecure-Requests", "1")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    fun configure(context: Context) {
        appContext = context.applicationContext
        try {
            WebView.setWebContentsDebuggingEnabled(false)
            CookieManager.getInstance().setAcceptCookie(true)
        } catch (_: Exception) {}
    }

    // ── JS Deobfuscation ──────────────────────────────────────────────────────

    fun deobfuscateJs(js: String): String {
        var result = js
        var iterations = 0
        while (iterations++ < 5) {
            val packed = Regex(
                """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)\s*\{.+?\}\s*\(\s*'([\s\S]+?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([\s\S]+?)'\.split\s*\(""",
                RegexOption.DOT_MATCHES_ALL
            ).find(result) ?: break
            try {
                val p = packed.groupValues[1]
                val a = packed.groupValues[2].toIntOrNull() ?: 36
                val c = packed.groupValues[3].toIntOrNull() ?: 0
                val k = packed.groupValues[4].split("|")
                val unpacked = unpackPacked(p, a, c, k)
                if (unpacked.length > 50) result = result.replace(packed.value, unpacked) else break
            } catch (_: Exception) { break }
        }
        return result
    }

    private fun unpackPacked(p: String, a: Int, c: Int, k: List<String>): String {
        var result = p
        var i = c - 1
        while (i >= 0) {
            val word = k.getOrNull(i)
            if (!word.isNullOrEmpty()) {
                result = result.replace(Regex("\\b${toBase(i, a)}\\b"), word)
            }
            i--
        }
        return result
    }

    private fun toBase(num: Int, base: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) { sb.insert(0, chars[n % base]); n /= base }
        return sb.toString()
    }

    // ── Native Page Stub (Playwright Compatibility) ──────────────────────────

    class NativePage(val pageUrl: String = "") {
        private var _html: String = ""
        fun html(): String = _html
        internal fun setHtml(h: String) { _html = h }
        fun navigate(url: String): NativePage = runBlocking { fetchNativePage(url) ?: this@NativePage }
        fun content(): String = _html
        fun waitForLoadState() {}
        fun evaluate(jsCode: String): Any? = extractVideoUrlsFromHtml(_html, pageUrl)
        fun close() {}
    }

    fun createAntiDetectionPage(): NativePage = NativePage()
    fun close() { cookieJar.clear() }

    // ── Core Fetching ────────────────────────────────────────────────────────

    suspend fun fetchRaw(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("Referer", extractHost(url) + "/").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            }
        } catch (e: Exception) { Log.w(TAG, "Fetch error: ${e.message}"); null }
    }

    suspend fun fetchPageContent(url: String, timeout: Int = 20000): String? =
        fetchRenderedPageContent(url = url, waitSelector = null, timeout = timeout)
            ?: fetchWithTimeout(url, timeout)

    suspend fun fetchPageContentWithShadowAndAdSkip(
        url: String,
        waitSelector: String? = null,
        timeout: Int = 20000
    ): String? {
        val html = fetchRenderedPageContent(url, waitSelector, timeout)
            ?: fetchWithTimeout(url, timeout)
            ?: return null
        val doc = Jsoup.parse(html, url)
        removeObstructiveElements(doc)
        return doc.html()
    }

    suspend fun fetchContentByClickingTabs(baseUrl: String, query: String, timeout: Int = 20000): String? =
        withContext(Dispatchers.IO) {
            val html = fetchRenderedPageContent(baseUrl, null, timeout)
                ?: fetchWithTimeout(baseUrl, timeout)
                ?: return@withContext null
            val doc = Jsoup.parse(html, baseUrl)
            val queryTerms = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
            val tabUrl = doc.select("a[href]").firstOrNull { link ->
                val combined = "${link.text()} ${link.attr("href")}".lowercase()
                queryTerms.any { combined.contains(it) } ||
                    listOf("search", "video", "movie", "latest", "popular").any { combined.contains(it) }
            }?.let { normalizeUrl(it.attr("href"), baseUrl) }

            if (tabUrl == null) {
                html
            } else {
                fetchRenderedPageContent(tabUrl, null, timeout) ?: fetchWithTimeout(tabUrl, timeout) ?: html
            }
        }

    suspend fun discoverSearchAPIEndpoints(baseUrl: String, sampleQuery: String = "test"): List<String> =
        withContext(Dispatchers.IO) {
            val html = fetchWithTimeout(baseUrl, 20000).orEmpty()
            val discovered = linkedSetOf<String>()
            val patterns = listOf(
                Regex("""(?:fetch|axios\.[a-z]+|\$\.ajax|\$\.get|\$\.post)\s*\(\s*['"`]([^'"`]+)['"`]""", RegexOption.IGNORE_CASE),
                Regex("""['"`]([^'"`]*(?:api|ajax|graphql|search|suggest|autocomplete)[^'"`]*)['"`]""", RegexOption.IGNORE_CASE),
                Regex("""url\s*:\s*['"`]([^'"`]+)['"`]""", RegexOption.IGNORE_CASE)
            )

            patterns.forEach { pattern ->
                pattern.findAll(html).forEach { match ->
                    val candidate = match.groupValues.getOrNull(1).orEmpty()
                    if (candidate.isNotBlank() && !candidate.startsWith("data:")) {
                        discovered.add(normalizeUrl(candidate.replace("{query}", sampleQuery), baseUrl))
                    }
                }
            }

            val encoded = URLEncoder.encode(sampleQuery, "UTF-8")
            listOf(
                "/api/search?q=$encoded",
                "/ajax/search?q=$encoded",
                "/suggest?q=$encoded",
                "/autocomplete?q=$encoded",
                "/wp-json/wp/v2/search?search=$encoded"
            ).forEach { discovered.add(normalizeUrl(it, baseUrl)) }

            discovered.take(30)
        }

    fun extractVideoUrls(pageUrl: String): List<String> = runBlocking {
        val html = fetchRenderedPageContent(pageUrl, "video, source, iframe, [data-video-url]", 25000)
            ?: fetchWithTimeout(pageUrl, 25000)
            ?: return@runBlocking emptyList()
        extractVideoUrlsFromHtml(html, pageUrl)
    }

    private suspend fun fetchNativePage(url: String): NativePage? {
        val html = fetchPageContentWithShadowAndAdSkip(url, timeout = 25000) ?: return null
        return NativePage(url).also { it.setHtml(html) }
    }

    fun searchViaHeadlessForm(baseUrl: String, query: String): String? = runBlocking {
        val html = fetchRaw(baseUrl) ?: return@runBlocking null
        val doc = Jsoup.parse(html, baseUrl)
        val form = doc.select("form").firstOrNull { f ->
            f.select("input[type=text], input[type=search], input[name*=q]").isNotEmpty()
        } ?: return@runBlocking html

        val action = form.absUrl("action").ifEmpty { baseUrl }
        val method = form.attr("method").lowercase().ifEmpty { "get" }
        val fields = mutableMapOf<String, String>()
        
        form.select("input, select, textarea").forEach { input ->
            val name = input.attr("name")
            if (name.isNotEmpty()) {
                val type = input.attr("type").lowercase()
                if (type != "submit") {
                    fields[name] = if (name.contains("q") || name.contains("query") || type == "search") query else input.attr("value")
                }
            }
        }

        try {
            if (method == "post") {
                val body = FormBody.Builder().apply { fields.forEach { add(it.key, it.value) } }.build()
                val req = Request.Builder().url(action).post(body).header("Referer", baseUrl).build()
                client.newCall(req).execute().use { it.body?.string() }
            } else {
                val qs = fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
                fetchRaw(if (action.contains("?")) "$action&$qs" else "$action?$qs")
            }
        } catch (e: Exception) { html }
    }

    private fun extractHost(url: String): String = try {
        val uri = java.net.URI(url); "${uri.scheme}://${uri.host}"
    } catch (_: Exception) { url }

    private suspend fun fetchWithTimeout(url: String, timeout: Int): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("Referer", extractHost(url) + "/")
                .build()
            client.newBuilder()
                .connectTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS)
                .build()
                .newCall(req)
                .execute()
                .use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
        } catch (e: Exception) {
            Log.w(TAG, "Fetch error: ${e.message}")
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchRenderedPageContent(
        url: String,
        waitSelector: String?,
        timeout: Int
    ): String? {
        val context = appContext ?: return null
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                var completed = false
                var webView: WebView? = null

                fun finish(value: String?) {
                    if (completed) return
                    completed = true
                    try {
                        webView?.stopLoading()
                        webView?.loadUrl("about:blank")
                        webView?.destroy()
                    } catch (_: Exception) {}
                    if (cont.isActive) cont.resume(value)
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
                                waitForSelectorThenCapture(view, waitSelector, timeout / 3, ::finish)
                            }, 800L)
                        }
                    }
                    cont.invokeOnCancellation {
                        try { webView?.destroy() } catch (_: Exception) {}
                    }
                    activeWebView.loadUrl(url, browserHeaders(url))
                    activeWebView.postDelayed({ finish(null) }, timeout.toLong())
                } catch (e: Exception) {
                    Log.w(TAG, "WebView render failed: ${e.message}")
                    try { webView?.destroy() } catch (_: Exception) {}
                    finish(null)
                }
            }
        }
    }

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

    private fun injectFingerprintEvasion(webView: WebView) {
        webView.evaluateJavascript(
            """
            (function(){
              try {
                Object.defineProperty(navigator, 'webdriver', {get: function(){return false;}});
                Object.defineProperty(navigator, 'platform', {get: function(){return 'Win32';}});
                Object.defineProperty(navigator, 'hardwareConcurrency', {get: function(){return 8;}});
                Object.defineProperty(navigator, 'deviceMemory', {get: function(){return 8;}});
                const originalToDataURL = HTMLCanvasElement.prototype.toDataURL;
                HTMLCanvasElement.prototype.toDataURL = function(){
                  const ctx = this.getContext('2d');
                  if (ctx) { ctx.fillStyle = 'rgba(1,1,1,0.01)'; ctx.fillRect(0,0,1,1); }
                  return originalToDataURL.apply(this, arguments);
                };
                const originalGetParameter = WebGLRenderingContext.prototype.getParameter;
                WebGLRenderingContext.prototype.getParameter = function(parameter) {
                  if (parameter === 37445) return 'Intel Inc.';
                  if (parameter === 37446) return 'Intel Iris OpenGL Engine';
                  return originalGetParameter.apply(this, arguments);
                };
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

    private fun removeObstructiveElements(document: Document) {
        document.select(
            ".ad, .ads, .advertisement, .popup, .modal, .overlay, " +
                "[class*='cookie'], [id*='cookie'], [class*='banner'], iframe[src*='ad']"
        ).remove()
    }

    private fun extractVideoUrlsFromHtml(html: String, baseUrl: String): List<String> {
        val urls = linkedSetOf<String>()
        val doc = Jsoup.parse(html, baseUrl)

        doc.select("video[src], source[src], a[href], iframe[src]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            if (raw.isLikelyVideoUrl()) urls.add(normalizeUrl(raw, baseUrl))
        }

        val scriptText = doc.select("script").joinToString("\n") { it.html() }
        val patterns = listOf(
            Regex("""https?:\\/\\/[^'"\\\s]+?\.(?:m3u8|mpd|mp4|webm)(?:\?[^'"\\\s]*)?""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^'"\s]+?\.(?:m3u8|mpd|mp4|webm)(?:\?[^'"\s]*)?""", RegexOption.IGNORE_CASE),
            Regex("""['"`]([^'"`]+?\.(?:m3u8|mpd|mp4|webm)(?:\?[^'"`]*)?)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source|url)\s*[:=]\s*['"`]([^'"`]+)['"`]""", RegexOption.IGNORE_CASE)
        )

        patterns.forEach { pattern ->
            pattern.findAll("$html\n$scriptText").forEach { match ->
                val raw = (match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value)
                    .replace("\\/", "/")
                    .trim('\'', '"', '`')
                if (raw.isLikelyVideoUrl()) urls.add(normalizeUrl(raw, baseUrl))
            }
        }

        return urls.sortedByDescending { url ->
            when {
                url.contains("1080") -> 100
                url.contains("720") -> 80
                url.contains(".m3u8", ignoreCase = true) -> 90
                url.contains(".mpd", ignoreCase = true) -> 85
                else -> 50
            }
        }
    }

    private fun String.isLikelyVideoUrl(): Boolean {
        val lower = lowercase()
        return listOf(".m3u8", ".mpd", ".mp4", ".webm", "videoplayback", "manifest").any { lower.contains(it) }
    }

    private fun normalizeUrl(url: String, baseUrl: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "${extractHost(baseUrl).trimEnd('/')}$url"
        else -> "${baseUrl.substringBefore("?").substringBeforeLast("/", baseUrl).trimEnd('/')}/$url"
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
