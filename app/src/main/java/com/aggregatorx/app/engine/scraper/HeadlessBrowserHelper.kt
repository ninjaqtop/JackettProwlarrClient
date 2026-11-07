package com.aggregatorx.app.engine.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aggregatorx.app.engine.network.HttpTransport
import com.aggregatorx.app.engine.network.TransportRequest
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object HeadlessBrowserHelper {

    private const val TAG = "HeadlessBrowserHelper"
    @Volatile private var appContext: Context? = null
    private val renderMutex = Mutex()
    // No private HTTP client: every HTTP request this helper makes (raw fetch,
    // endpoint probes, the form submission itself) rides the app's single
    // transport, HttpTransport (native TLS primary, logged OkHttp degradation),
    // per spec §6. The WebView is a browser engine, not an HTTP stack.

    /** Result of a real search-form submission: the response page and where it landed. */
    data class SearchFormPage(
        val html: String,
        val finalUrl: String,
        val statusCode: Int
    )

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

    private fun safeDestroy(webView: WebView?) {
        if (webView == null) return
        val destroy = {
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            } catch (_: Exception) {}
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroy()
        } else {
            webView.post { destroy() }
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // RAW FETCHING
    // ───────────────────────────────────────────────────────────────────────────

    suspend fun fetchRaw(url: String): String? {
        val response = HttpTransport.fetch(
            TransportRequest(
                url = url,
                headers = HttpTransport.defaultHeaders() + ("Referer" to extractHost(url) + "/")
            )
        )
        return if (response.isHttpOk) response.body else {
            Log.w(TAG, "Fetch error: ${response.error ?: "HTTP ${response.statusCode}"}")
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
        timeout: Int,
        headers: Map<String, String>
    ): String? = renderMutex.withLock {

        val context = appContext ?: return null

        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->

                val completed = AtomicBoolean(false)
                var webView: WebView? = null

                fun finish(value: String?) {
                    if (completed.compareAndSet(false, true)) {
                        safeDestroy(webView)
                        if (cont.isActive) cont.resume(value)
                    }
                }

                cont.invokeOnCancellation {
                    if (completed.compareAndSet(false, true)) {
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
                                    finish(html)
                                }
                            }, 800L)
                        }
                    }

                    val mergedHeaders = browserHeaders(url).toMutableMap()
                    for ((key, value) in headers) {
                        val existing = mergedHeaders.keys.firstOrNull { it.equals(key, ignoreCase = true) }
                        if (existing != null) mergedHeaders.remove(existing)
                        mergedHeaders[key] = value
                    }
                    activeWebView.loadUrl(url, mergedHeaders)

                    activeWebView.postDelayed({
                        finish(null)
                    }, timeout.toLong())

                } catch (e: Exception) {
                    Log.w(TAG, "WebView render failed: ${e.message}")
                    finish(null)
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
        timeout: Int = 20000,
        headers: Map<String, String> = emptyMap()
    ): String? = fetchRenderedPageContent(url, waitSelector, timeout, headers)

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
            val response = HttpTransport.fetch(
                TransportRequest(url = "$baseUrl$pattern", headers = HttpTransport.defaultHeaders(acceptJson = true), timeoutMs = 10_000)
            )
            if (response.isHttpOk) discovered.add(pattern)
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
    ): String? = submitSearchForm(baseUrl, query)?.html

    /**
     * Find the site's own search form on its rendered homepage, fill it with the
     * query exactly as the site's form defines it (hidden fields included), and
     * submit it the way a browser would. Returns the response page, or null when
     * the site has no usable form or the submission fails.
     */
    suspend fun submitSearchForm(
        baseUrl: String,
        query: String,
        headers: Map<String, String> = emptyMap(),
        renderTimeout: Int = 15000,
        requestTimeout: Int = 10000
    ): SearchFormPage? = withContext(Dispatchers.IO) {
        try {
            val pageContent = fetchPageContentWithShadowAndAdSkip(
                baseUrl,
                "form, [role='search']",
                renderTimeout,
                headers
            ) ?: return@withContext null

            val doc = Jsoup.parse(pageContent, baseUrl)
            val form = doc.selectFirst("form[method=get], form[method=post], [role='search'] form")
                ?: return@withContext null

            val action = form.absUrl("action").ifBlank { baseUrl }
            val method = form.attr("method").ifBlank { "get" }

            // Find the search input field
            val searchInput = form.selectFirst(
                "input[type=text], input[type=search], input[name*=q], input[name*=query], input[name*=search]"
            ) ?: return@withContext null

            val inputName = searchInput.attr("name").ifBlank {
                searchInput.id().ifBlank { "q" }
            }

            // Collect every field the form would send, with the query typed into
            // the search input.
            val fields = mutableListOf<Pair<String, String>>()
            for (field in form.select("input[name], select[name], textarea[name]")) {
                if (field.hasAttr("disabled")) continue
                val rawName = field.attr("name").trim()
                val name = when {
                    rawName.isNotBlank() -> rawName
                    field == searchInput -> inputName
                    else -> continue
                }
                when (field.tagName().lowercase()) {
                    "textarea" -> fields += name to field.text()
                    "select" -> {
                        val options = field.select("option")
                        val chosen = options.filter { it.hasAttr("selected") }
                            .ifEmpty { options.take(1) }
                        chosen.forEach { option ->
                            fields += name to option.attr("value").ifBlank { option.text() }
                        }
                    }
                    "input" -> {
                        val type = field.attr("type").lowercase().ifBlank { "text" }
                        if (type in setOf("button", "reset", "submit", "image", "file")) continue
                        if (type in setOf("checkbox", "radio") && !field.hasAttr("checked")) continue
                        val value = if (field == searchInput) query else field.attr("value")
                        fields += name to value
                    }
                }
            }
            if (fields.none { it.first == inputName }) {
                fields += inputName to query
            }

            // Configured headers (cookies, user agent) only go to the site's own
            // origin; a form posting elsewhere only gets the generic ones.
            val effectiveHeaders = if (haveSameOrigin(baseUrl, action)) {
                headers
            } else {
                headers.filterKeys {
                    it.equals("User-Agent", ignoreCase = true) ||
                        it.equals("Accept", ignoreCase = true) ||
                        it.equals("Accept-Language", ignoreCase = true)
                }
            }
            val requestHeaders = linkedMapOf<String, String>()
            requestHeaders.putAll(HttpTransport.defaultHeaders())
            requestHeaders["Referer"] = baseUrl
            for ((key, value) in effectiveHeaders) {
                if (key.isBlank() || value.isBlank()) continue
                if (key.equals("Host", ignoreCase = true) ||
                    key.equals("Content-Length", ignoreCase = true)
                ) continue
                requestHeaders.keys.firstOrNull { it.equals(key, ignoreCase = true) }?.let { requestHeaders.remove(it) }
                requestHeaders[key] = value
            }
            // The session the WebView just established on the site (its cookies
            // from rendering the homepage) goes with the submission, as a
            // browser's would; configured cookies win on name clashes.
            if (haveSameOrigin(baseUrl, action)) {
                val webCookies = runCatching { CookieManager.getInstance().getCookie(action) }.getOrNull()
                if (!webCookies.isNullOrBlank()) {
                    val existingKey = requestHeaders.keys.firstOrNull { it.equals("Cookie", ignoreCase = true) }
                    requestHeaders["Cookie"] = if (existingKey != null) {
                        val configured = requestHeaders.remove(existingKey).orEmpty()
                        if (configured.isBlank()) webCookies else "$webCookies; $configured"
                    } else webCookies
                }
            }

            fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
            val isPost = method.equals("post", ignoreCase = true)
            val request = if (isPost) {
                TransportRequest(
                    url = action,
                    method = "POST",
                    headers = requestHeaders,
                    body = fields.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" },
                    timeoutMs = requestTimeout,
                    contentType = "application/x-www-form-urlencoded"
                )
            } else {
                val httpUrl = action.toHttpUrlOrNull() ?: return@withContext null
                val urlBuilder = httpUrl.newBuilder()
                for ((name, values) in fields.groupBy { it.first }) {
                    urlBuilder.removeAllQueryParameters(name)
                    values.forEach { urlBuilder.addQueryParameter(name, it.second) }
                }
                TransportRequest(
                    url = urlBuilder.build().toString(),
                    headers = requestHeaders,
                    timeoutMs = requestTimeout
                )
            }

            // Spec §6: the submission rides the one transport (native TLS
            // primary; any OkHttp degradation is logged and surfaced there).
            val response = HttpTransport.fetch(request)
            // A 404/410 with a body is the site's own "nothing found" page (some
            // sites answer an empty search that way) — return it so callers
            // parse it, instead of mistaking it for "no page returned".
            val emptyResultStatus = response.statusCode == 404 || response.statusCode == 410
            if (response.error != null || (response.statusCode !in 200..299 && !emptyResultStatus)) {
                Log.w(TAG, "Form submission failed: ${response.error ?: "HTTP ${response.statusCode}"}")
                return@withContext null
            }
            if (response.body.isBlank()) return@withContext null
            SearchFormPage(response.body, response.finalUrl.ifBlank { request.url }, response.statusCode)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Form submission failed: ${e.message}")
            null
        }
    }

    /** One page captured during a scripted WebView form search. */
    data class RenderedStepPage(val html: String, val url: String)

    /**
     * Outcome of [renderFormSearchPages]: page 1 (the site's own form filled and
     * submitted inside the WebView) followed by each page reached by clicking the
     * site's own next-page control.
     */
    data class FormSearchRender(
        val pages: List<RenderedStepPage>,
        /** Replayable descriptor of the next-page control that was clicked, if any. */
        val nextControl: String?,
        /** Why fewer pages than requested were captured; null when all were. */
        val stopReason: String?
    )

    /**
     * Drive the site's own search UI in the WebView the way a user would: load
     * [baseUrl], type [query] into its search form and submit it, then click the
     * site's own next-page control [nextClicks] times, capturing the rendered DOM
     * after every step. Works for POST and JavaScript-driven forms whose result
     * pages have no URL that could be fetched directly.
     *
     * [nextControl] is a descriptor returned by an earlier run ("css:<selector>",
     * "text:<label>", "link-next", "pagenum"); it is tried first, and the next
     * control is re-detected on the page when it is not found. Bounded by
     * [timeout] overall; returns whatever pages were captured with the reason it
     * stopped short, or null when no WebView is available.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun renderFormSearchPages(
        baseUrl: String,
        query: String,
        nextClicks: Int,
        nextControl: String? = null,
        timeout: Int = 45_000
    ): FormSearchRender? = renderMutex.withLock {
        val context = appContext ?: return null
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val completed = AtomicBoolean(false)
                var webView: WebView? = null
                val pages = mutableListOf<RenderedStepPage>()
                var control: String? = nextControl
                var loads = 0
                var submitted = false

                fun finish(stopReason: String?) {
                    if (completed.compareAndSet(false, true)) {
                        safeDestroy(webView)
                        if (cont.isActive) cont.resume(FormSearchRender(pages.toList(), control, stopReason))
                    }
                }

                cont.invokeOnCancellation {
                    if (completed.compareAndSet(false, true)) safeDestroy(webView)
                }

                // After an action: wait for a navigation (onPageFinished) or, for
                // in-page (AJAX) updates, a fixed settle window — then [next].
                fun afterAction(view: WebView, next: () -> Unit) {
                    val loadsAtAction = loads
                    val startedAt = System.currentTimeMillis()
                    fun poll() {
                        if (completed.get()) return
                        if (loads > loadsAtAction || System.currentTimeMillis() - startedAt > STEP_NAV_WAIT_MS) {
                            view.postDelayed({ if (!completed.get()) next() }, STEP_SETTLE_MS)
                        } else {
                            view.postDelayed({ poll() }, 300L)
                        }
                    }
                    view.postDelayed({ poll() }, 300L)
                }

                fun capture(view: WebView, onCaptured: (RenderedStepPage?) -> Unit) {
                    captureRenderedHtml(view) { html ->
                        onCaptured(html?.let { RenderedStepPage(it, view.url ?: baseUrl) })
                    }
                }

                fun clickNext(view: WebView) {
                    val targetPage = pages.size + 1
                    view.evaluateJavascript(clickNextJs(control, targetPage)) { raw ->
                        if (completed.get()) return@evaluateJavascript
                        val used = decodeJsString(raw)
                        if (used.isNullOrBlank() || used == "none") {
                            finish("no next-page control found on page ${pages.size} of the site's own form search")
                            return@evaluateJavascript
                        }
                        control = used
                        afterAction(view) {
                            capture(view) { page ->
                                if (page == null) {
                                    finish("page ${pages.size + 1} could not be captured after clicking the next control")
                                    return@capture
                                }
                                if (page.html == pages.last().html) {
                                    finish("clicking the next control ($used) did not change the page")
                                    return@capture
                                }
                                pages += page
                                if (pages.size > nextClicks) finish(null) else clickNext(view)
                            }
                        }
                    }
                }

                try {
                    val activeWebView = WebView(context)
                    webView = activeWebView
                    activeWebView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = false
                        userAgentString = EngineUtils.DEFAULT_USER_AGENT
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(activeWebView, true)
                    activeWebView.webChromeClient = WebChromeClient()
                    activeWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            loads++
                            if (submitted || completed.get()) return
                            submitted = true
                            // The homepage is up: fill and submit the site's own form.
                            view.postDelayed({
                                if (completed.get()) return@postDelayed
                                injectFingerprintEvasion(view)
                                view.evaluateJavascript(fillAndSubmitFormJs(query)) { raw ->
                                    if (completed.get()) return@evaluateJavascript
                                    if (decodeJsString(raw) == "noform") {
                                        finish("the site's search form was not found in the WebView")
                                        return@evaluateJavascript
                                    }
                                    afterAction(view) {
                                        capture(view) { page ->
                                            if (page == null) {
                                                finish("the form's results page could not be captured")
                                                return@capture
                                            }
                                            pages += page
                                            if (nextClicks <= 0) finish(null) else clickNext(view)
                                        }
                                    }
                                }
                            }, 800L)
                        }
                    }
                    activeWebView.loadUrl(baseUrl, browserHeaders(baseUrl))
                    activeWebView.postDelayed({
                        finish("timed out after ${timeout} ms with ${pages.size} page(s) captured")
                    }, timeout.toLong())
                } catch (e: Exception) {
                    Log.w(TAG, "WebView form run failed: ${e.message}")
                    finish("WebView error: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    private const val STEP_NAV_WAIT_MS = 5_000L
    private const val STEP_SETTLE_MS = 1_200L

    /** Fill the site's search input with [query] and submit its form like a user. */
    private fun fillAndSubmitFormJs(query: String): String = """
        (function(q){
          var inputSel = 'input[type=search],input[name=q],input[name=s],input[name*=query],input[name*=search],input[name*=keyword],input[type=text]';
          var form = null, inp = null;
          var forms = document.querySelectorAll('[role=search] form, form[role=search], form');
          for (var i = 0; i < forms.length && !inp; i++) {
            var candidate = forms[i].querySelector(inputSel);
            if (candidate) { form = forms[i]; inp = candidate; }
          }
          if (!inp) { inp = document.querySelector('input[type=search]'); form = inp ? inp.form : null; }
          if (!inp) return 'noform';
          try {
            var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
            setter.call(inp, q);
          } catch (e) { inp.value = q; }
          inp.dispatchEvent(new Event('input', {bubbles: true}));
          inp.dispatchEvent(new Event('change', {bubbles: true}));
          if (form) {
            var btn = form.querySelector('button[type=submit],input[type=submit],button:not([type])');
            if (form.requestSubmit) { try { form.requestSubmit(btn || undefined); return 'submitted'; } catch (e) {} }
            if (btn) { btn.click(); return 'clicked'; }
            form.submit();
            return 'submitted';
          }
          inp.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', keyCode: 13, which: 13, bubbles: true}));
          return 'enter';
        })(${JSONObject.quote(query)});
    """.trimIndent()

    /**
     * Click the page's own next-page control. Tries the saved descriptor first,
     * then detects one: rel=next, a control labelled next/»/›/older/load more, a
     * class containing "next", or the link to page [targetPage] in a pager.
     * Returns the descriptor of what it clicked, or "none".
     */
    private fun clickNextJs(descriptor: String?, targetPage: Int): String = """
        (function(desc, target){
          var NEXT = ['next','next »','next ›','next >','next page','»','›','>','older posts','older entries','more results','load more','show more'];
          var CLICKABLE = 'a,button,input[type=submit],input[type=button],[role=button]';
          function usable(e){
            if (!e) return false;
            if (e.disabled || e.getAttribute('aria-disabled') == 'true') return false;
            var cls = (e.className && e.className.baseVal !== undefined) ? e.className.baseVal : (e.className || '');
            return !/(^|\s)disabled(\s|$)/i.test(cls);
          }
          function label(e){ return ((e.innerText || e.value || '') + '').replace(/\s+/g, ' ').trim().toLowerCase(); }
          function aria(e){ return ((e.getAttribute('aria-label') || e.getAttribute('title') || '') + '').trim().toLowerCase(); }
          function pageNum(){
            var all = document.querySelectorAll('[class*=pag] a,[class*=pag] button,nav a,nav button');
            for (var i = 0; i < all.length; i++) if (label(all[i]) == String(target) && usable(all[i])) return all[i];
            return null;
          }
          function byDesc(d){
            if (!d) return null;
            if (d == 'link-next') { var l = document.querySelector('link[rel=next][href]'); return l ? l : null; }
            if (d == 'pagenum') return pageNum();
            if (d.indexOf('css:') == 0) { try { var e = document.querySelector(d.substring(4)); return usable(e) ? e : null; } catch (x) { return null; } }
            if (d.indexOf('text:') == 0) {
              var t = d.substring(5), all = document.querySelectorAll(CLICKABLE);
              for (var i = 0; i < all.length; i++) if ((label(all[i]) == t || aria(all[i]) == t) && usable(all[i])) return all[i];
            }
            return null;
          }
          function detect(){
            var a = document.querySelector('a[rel=next][href]');
            if (usable(a)) return ['css:a[rel=next][href]', a];
            var all = document.querySelectorAll(CLICKABLE);
            for (var i = 0; i < all.length; i++) {
              var l = label(all[i]), r = aria(all[i]);
              if (usable(all[i]) && (NEXT.indexOf(l) >= 0 || NEXT.indexOf(r) >= 0)) return ['text:' + (NEXT.indexOf(l) >= 0 ? l : r), all[i]];
            }
            for (var j = 0; j < all.length; j++) {
              var cls = (all[j].getAttribute('class') || '');
              if (/(^|[\s_-])next([\s_-]|$)/i.test(cls) && usable(all[j])) {
                if (all[j].id) return ['css:#' + CSS.escape(all[j].id), all[j]];
                var token = cls.split(/\s+/).filter(function(c){ return /next/i.test(c); })[0];
                return ['css:' + all[j].tagName.toLowerCase() + '.' + CSS.escape(token), all[j]];
              }
            }
            var link = document.querySelector('link[rel=next][href]');
            if (link) return ['link-next', link];
            var p = pageNum();
            if (p) return ['pagenum', p];
            return null;
          }
          var el = byDesc(desc), used = desc;
          if (!el) { var found = detect(); if (!found) return 'none'; used = found[0]; el = found[1]; }
          if (el.tagName && el.tagName.toLowerCase() == 'link') { window.location.href = el.href; return used; }
          try { el.scrollIntoView(); } catch (x) {}
          el.click();
          return used;
        })(${if (descriptor == null) "null" else JSONObject.quote(descriptor)}, $targetPage);
    """.trimIndent()

    fun haveSameOrigin(first: String, second: String): Boolean = runCatching {
        fun origin(value: String): Triple<String, String, Int> {
            val uri = URI(value)
            val scheme = uri.scheme.lowercase()
            val port = uri.port.takeIf { it >= 0 } ?: if (scheme == "https") 443 else 80
            return Triple(scheme, uri.host.lowercase(), port)
        }
        origin(first) == origin(second)
    }.getOrDefault(false)

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
