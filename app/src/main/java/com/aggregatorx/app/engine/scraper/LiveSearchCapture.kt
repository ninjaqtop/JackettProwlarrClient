package com.aggregatorx.app.engine.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Burp-proxy-style live capture of the site's OWN search request.
 *
 * Instead of guessing how a site searches, this drives the site's real search
 * UI in the WebView and watches the wire:
 *
 *  1. Load the site, find its search control the way a user would — a form, an
 *     `input[type=search]`, a `[role=search]` container, an input whose
 *     placeholder/aria-label mentions search, or a search ICON button that has
 *     to be clicked before the input exists.
 *  2. Type a real probe term taken from the site's own content, then submit
 *     (requestSubmit / submit button / Enter).
 *  3. Intercept EVERY request the page makes while it does so, from two sides:
 *     `shouldInterceptRequest` on the WebViewClient (catches navigations,
 *     documents, XHR and fetch alike, with method and request headers) and
 *     injected JS hooks on `window.fetch` and `XMLHttpRequest` (which add the
 *     request BODY that the native callback cannot see). The final navigated
 *     URL is captured too.
 *  4. Work out which captured request actually carries the probe term and
 *     returns results, and hand it back as a template with {query} in place of
 *     the term.
 *
 * Requests made BEFORE the submit (while the term is being typed) are recorded
 * separately as autocomplete/suggest traffic: a suggest endpoint looks exactly
 * like a search endpoint and saving one as the search route produces a config
 * that can never paginate.
 *
 * Nothing here saves anything. The captured request is the top-priority
 * candidate, and it still has to be replayed and fully verified.
 */
object LiveSearchCapture {

    private const val TAG = "LiveSearchCapture"
    private val captureMutex = Mutex()
    @Volatile private var appContext: Context? = null

    fun configure(context: Context) { appContext = context.applicationContext }

    /** One request the page made, as seen on the wire. */
    @Serializable
    data class CapturedRequest(
        val url: String,
        val method: String,
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
        /** "intercept" (native callback) or "fetch"/"xhr" (injected JS hook). */
        val via: String = "intercept",
        /** True when this fired before the search was submitted (typing phase). */
        val beforeSubmit: Boolean = false,
        val resourceType: String = ""
    ) {
        val isJsonish: Boolean
            get() = url.contains("/api", true) || url.contains(".json", true) ||
                url.contains("/ajax", true) || url.contains("graphql", true) ||
                headers.entries.any { it.key.equals("Accept", true) && it.value.contains("json", true) }
    }

    /** The request identified as the site's real search, as a reusable template. */
    @Serializable
    data class SearchRequestTemplate(
        /** URL with the probe term replaced by {query}. */
        val urlTemplate: String,
        val method: String,
        /** POST body with the probe term replaced by {query}; null for GET. */
        val bodyTemplate: String? = null,
        val headers: Map<String, String> = emptyMap(),
        /** Cookies the WebView held for the site when the request was made. */
        val cookie: String? = null,
        /** The query parameter the term travelled in, when it was a parameter. */
        val queryParam: String? = null,
        val isJson: Boolean = false,
        val via: String = "",
        val evidence: String = ""
    )

    /** Everything one capture run saw. */
    @Serializable
    data class CaptureResult(
        val submitted: Boolean,
        /** How the search control was found and driven, or why it was not. */
        val detail: String,
        /** The URL the page ended on after submitting (often the results page). */
        val finalUrl: String? = null,
        /** Rendered DOM of the results page, for extraction-selector derivation. */
        val resultsHtml: String? = null,
        /** The best search-request candidates, highest confidence first. */
        val searchRequests: List<SearchRequestTemplate> = emptyList(),
        /** Autocomplete/suggest traffic, recorded apart and never used as search. */
        val suggestRequests: List<SearchRequestTemplate> = emptyList(),
        /** Everything captured, for the recon map. */
        val allRequests: List<CapturedRequest> = emptyList(),
        val probeTerm: String = ""
    )

    /** JS → Kotlin sink for the injected fetch/XHR hooks.
     *  Public on purpose: the WebView bridge reaches it by reflection. */
    class NetSink {
        val queue = ConcurrentLinkedQueue<CapturedRequest>()
        @Volatile var beforeSubmit = true

        @JavascriptInterface
        fun record(json: String) {
            runCatching {
                val o = JSONObject(json)
                val headers = mutableMapOf<String, String>()
                o.optJSONObject("headers")?.let { h ->
                    h.keys().forEach { k -> headers[k] = h.optString(k) }
                }
                queue += CapturedRequest(
                    url = o.optString("url"),
                    method = o.optString("method", "GET").ifBlank { "GET" }.uppercase(),
                    body = o.optString("body").takeIf { it.isNotBlank() && it != "null" },
                    headers = headers,
                    via = o.optString("via", "fetch"),
                    beforeSubmit = beforeSubmit,
                    resourceType = "xhr"
                )
            }
        }
    }

    /**
     * Drive [baseUrl]'s own search with [probeTerm] in the WebView and capture
     * the request it makes. Bounded by [timeoutMs]; returns null when no
     * WebView is available.
     */
    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    suspend fun capture(
        baseUrl: String,
        probeTerm: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): CaptureResult? = captureMutex.withLock {
        val context = appContext ?: return null
        val host = runCatching { URI(baseUrl).host }.getOrNull().orEmpty()

        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val completed = AtomicBoolean(false)
                var webView: WebView? = null
                val sink = NetSink()
                val intercepted = ConcurrentLinkedQueue<CapturedRequest>()
                var submitDetail = "the site's search control was never reached"
                var submitted = false

                fun destroy() {
                    val view = webView ?: return
                    runCatching {
                        view.stopLoading(); view.loadUrl("about:blank"); view.destroy()
                    }
                }

                fun finish(detail: String, finalUrl: String?, html: String?) {
                    if (!completed.compareAndSet(false, true)) return
                    val cookie = runCatching { CookieManager.getInstance().getCookie(baseUrl) }.getOrNull()
                    val all = (intercepted.toList() + sink.queue.toList())
                        .distinctBy { it.method + " " + it.url + (it.body ?: "") }
                        .take(MAX_CAPTURED)
                    destroy()
                    val analysed = analyse(all, probeTerm, finalUrl, cookie, host)
                    if (cont.isActive) {
                        cont.resume(
                            CaptureResult(
                                submitted = submitted,
                                detail = detail,
                                finalUrl = finalUrl,
                                resultsHtml = html,
                                searchRequests = analysed.first,
                                suggestRequests = analysed.second,
                                allRequests = all,
                                probeTerm = probeTerm
                            )
                        )
                    }
                }

                cont.invokeOnCancellation { if (completed.compareAndSet(false, true)) destroy() }

                try {
                    val view = WebView(context)
                    webView = view
                    view.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = false
                        userAgentString = EngineUtils.DEFAULT_USER_AGENT
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                    view.addJavascriptInterface(sink, "AggXNet")
                    view.webChromeClient = WebChromeClient()

                    var pageLoads = 0
                    var hookedOnce = false

                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean = false

                        override fun shouldInterceptRequest(
                            v: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            // Watch only; never substitute a response.
                            runCatching {
                                val url = request.url.toString()
                                if (!STATIC_ASSET.containsMatchIn(url) && intercepted.size < MAX_CAPTURED) {
                                    intercepted += CapturedRequest(
                                        url = url,
                                        method = request.method?.uppercase() ?: "GET",
                                        headers = request.requestHeaders ?: emptyMap(),
                                        via = "intercept",
                                        beforeSubmit = sink.beforeSubmit,
                                        resourceType = if (request.isForMainFrame) "document" else "sub"
                                    )
                                }
                            }
                            return null
                        }

                        override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            // Hook as early as the engine allows, so the page's
                            // own fetch/XHR calls are seen with their bodies.
                            v.evaluateJavascript(HOOK_JS, null)
                        }

                        override fun onPageFinished(v: WebView, url: String) {
                            pageLoads++
                            if (completed.get()) return
                            v.evaluateJavascript(HOOK_JS, null)
                            if (hookedOnce) {
                                // A navigation AFTER the submit: this is the
                                // results page. Settle, then capture the DOM.
                                v.postDelayed({
                                    if (completed.get()) return@postDelayed
                                    captureHtml(v) { html ->
                                        finish(submitDetail, v.url ?: url, html)
                                    }
                                }, SETTLE_MS)
                                return
                            }
                            hookedOnce = true
                            // Give the page a moment, then drive its search UI.
                            v.postDelayed({
                                if (completed.get()) return@postDelayed
                                v.evaluateJavascript(findAndTypeJs(probeTerm)) { typedRaw ->
                                    if (completed.get()) return@evaluateJavascript
                                    val typed = decode(typedRaw)
                                    if (typed == null || typed.startsWith("no")) {
                                        submitDetail = "no search control could be found on the page " +
                                            "(looked for a form, input[type=search], [role=search], a search-labelled " +
                                            "input and search icon buttons that reveal one)"
                                        captureHtml(v) { html -> finish(submitDetail, v.url ?: url, html) }
                                        return@evaluateJavascript
                                    }
                                    // Typing phase recorded; everything from
                                    // here belongs to the submitted search.
                                    v.postDelayed({
                                        if (completed.get()) return@postDelayed
                                        sink.beforeSubmit = false
                                        v.evaluateJavascript(submitJs()) { subRaw ->
                                            if (completed.get()) return@evaluateJavascript
                                            val how = decode(subRaw) ?: "unknown"
                                            submitted = how != "nosubmit"
                                            submitDetail = if (submitted) {
                                                "typed \"$probeTerm\" into the site's own search control ($typed) and submitted it ($how)"
                                            } else {
                                                "found the site's search input ($typed) but could not submit it"
                                            }
                                            // Wait for either a navigation
                                            // (handled above) or an in-page
                                            // (AJAX) update.
                                            val loadsAtSubmit = pageLoads
                                            val startedAt = System.currentTimeMillis()
                                            fun poll() {
                                                if (completed.get()) return
                                                val navigated = pageLoads > loadsAtSubmit
                                                val waited = System.currentTimeMillis() - startedAt
                                                when {
                                                    navigated -> Unit // onPageFinished finishes the run
                                                    waited > AJAX_WAIT_MS -> captureHtml(v) { html ->
                                                        finish(submitDetail, v.url ?: url, html)
                                                    }
                                                    else -> v.postDelayed({ poll() }, 400L)
                                                }
                                            }
                                            v.postDelayed({ poll() }, 600L)
                                        }
                                    }, TYPE_SETTLE_MS)
                                }
                            }, 900L)
                        }
                    }

                    view.loadUrl(baseUrl, mapOf("Accept-Language" to "en-US,en;q=0.9"))
                    view.postDelayed({
                        if (completed.get()) return@postDelayed
                        captureHtml(view) { html ->
                            finish(
                                "$submitDetail (capture stopped at its ${timeoutMs} ms budget)",
                                view.url, html
                            )
                        }
                    }, timeoutMs.toLong())
                } catch (e: Exception) {
                    Log.w(TAG, "live capture failed: ${e.message}")
                    finish("WebView error: ${e.message ?: e.javaClass.simpleName}", null, null)
                }
            }
        }
    }

    private fun captureHtml(view: WebView, onDone: (String?) -> Unit) {
        runCatching {
            view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})();") { raw ->
                onDone(decode(raw))
            }
        }.onFailure { onDone(null) }
    }

    // ── Working out which captured request IS the search ──────────────

    /** Returns (search candidates, suggest candidates). */
    private fun analyse(
        all: List<CapturedRequest>,
        probeTerm: String,
        finalUrl: String?,
        cookie: String?,
        host: String
    ): Pair<List<SearchRequestTemplate>, List<SearchRequestTemplate>> {
        val forms = encodedForms(probeTerm)
        fun carriesTerm(r: CapturedRequest): Boolean =
            forms.any { f -> r.url.contains(f, true) || r.body?.contains(f, true) == true }

        val carrying = all.filter { carriesTerm(it) }
        val suggest = carrying.filter { it.beforeSubmit || SUGGEST_MARKER.containsMatchIn(it.url) }
        val search = carrying.filter { it !in suggest }

        // The final navigated URL is itself a search request when it carries
        // the term — often the whole answer for a classic GET form site.
        val navigation = finalUrl?.takeIf { url -> forms.any { url.contains(it, true) } }
            ?.let { url ->
                CapturedRequest(url = url, method = "GET", via = "navigation", resourceType = "document")
            }

        val ranked = (listOfNotNull(navigation) + search)
            .distinctBy { it.method + " " + it.url + (it.body ?: "") }
            .sortedWith(
                compareByDescending<CapturedRequest> { it.via == "navigation" }
                    .thenByDescending { it.isJsonish }
                    .thenByDescending { sameHost(it.url, host) }
                    .thenByDescending { it.url.length }
            )
            .take(MAX_SEARCH_CANDIDATES)

        return ranked.mapNotNull { templateize(it, probeTerm, cookie) } to
            suggest.mapNotNull { templateize(it, probeTerm, cookie) }.take(MAX_SUGGEST)
    }

    /** Replace the probe term with {query} in the URL and body. */
    private fun templateize(
        request: CapturedRequest,
        probeTerm: String,
        cookie: String?
    ): SearchRequestTemplate? {
        var url = request.url
        var body = request.body
        var replaced = false
        var queryParam: String? = null

        for (form in encodedForms(probeTerm)) {
            if (url.contains(form, true)) {
                // Remember which parameter carried it before substituting.
                queryParam = queryParam ?: paramCarrying(url, form)
                url = url.replace(form, "{query}", ignoreCase = true)
                replaced = true
            }
            if (body != null && body.contains(form, true)) {
                queryParam = queryParam ?: paramCarrying(body, form)
                body = body.replace(form, "{query}", ignoreCase = true)
                replaced = true
            }
        }
        if (!replaced) return null

        // A page parameter in the captured request becomes {page} so the saved
        // config can paginate the same route.
        url = url.replace(PAGE_PARAM_REGEX) { m -> "${m.groupValues[1]}${m.groupValues[2]}={page}" }
        // Only urlencoded bodies: rewriting "page": 1 inside a JSON body by
        // regex produces broken JSON, so a JSON body keeps its literal page.
        val trimmed = body?.trimStart().orEmpty()
        if (body != null && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            body = body.replace(PAGE_PARAM_BODY_REGEX) { m -> "${m.groupValues[1]}${m.groupValues[2]}={page}" }
        }

        val headers = request.headers.filterKeys { k ->
            k.lowercase() in KEEP_HEADERS
        }
        return SearchRequestTemplate(
            urlTemplate = url,
            method = request.method,
            bodyTemplate = body,
            headers = headers,
            cookie = cookie,
            queryParam = queryParam,
            isJson = request.isJsonish,
            via = request.via,
            evidence = "captured live in the WebView (${request.via}, ${request.method}) while the site's own " +
                "search ran for \"$probeTerm\""
        )
    }

    /** The parameter name whose value is [value], in a query string or JSON body. */
    private fun paramCarrying(text: String, value: String): String? =
        Regex("[?&]([A-Za-z0-9_\\[\\]\\-.]{1,40})=" + Regex.escape(value), RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
            ?: Regex("\"([A-Za-z0-9_\\-.]{1,40})\"\\s*:\\s*\"" + Regex.escape(value) + "\"", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1)

    private fun encodedForms(term: String): List<String> = listOf(
        term,
        URLEncoder.encode(term, "UTF-8"),
        URLEncoder.encode(term, "UTF-8").replace("+", "%20"),
        term.replace(" ", "-"),
        term.replace(" ", "_")
    ).distinct().filter { it.isNotBlank() }

    private fun sameHost(url: String, host: String): Boolean =
        runCatching { URI(url).host.equals(host, true) }.getOrDefault(false)

    private fun decode(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching { JSONArray("[$raw]").getString(0) }.getOrNull()
    }

    // ── Injected JavaScript ───────────────────────────────────────────

    /** Hook fetch and XMLHttpRequest so request bodies reach Kotlin. */
    private val HOOK_JS = """
        (function(){
          if (window.__aggxHooked) return 'already';
          window.__aggxHooked = true;
          function send(o){ try { if (window.AggXNet) AggXNet.record(JSON.stringify(o)); } catch(e){} }
          var of = window.fetch;
          if (of) {
            window.fetch = function(input, init){
              try {
                var url = (typeof input === 'string') ? input : (input && input.url) || '';
                var m = (init && init.method) || (input && input.method) || 'GET';
                var b = (init && init.body) || null;
                var h = {};
                try {
                  var hs = (init && init.headers) || (input && input.headers);
                  if (hs) {
                    if (typeof hs.forEach === 'function') hs.forEach(function(v,k){ h[k]=v; });
                    else for (var k in hs) h[k] = hs[k];
                  }
                } catch(e){}
                if (typeof b !== 'string') { try { b = JSON.stringify(b); } catch(e){ b = null; } }
                send({url: '' + url, method: ('' + m).toUpperCase(), body: b, headers: h, via: 'fetch'});
              } catch(e){}
              return of.apply(this, arguments);
            };
          }
          var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send,
              oh = XMLHttpRequest.prototype.setRequestHeader;
          XMLHttpRequest.prototype.open = function(m, u){
            this.__aggxM = ('' + m).toUpperCase(); this.__aggxU = '' + u; this.__aggxH = {};
            return oo.apply(this, arguments);
          };
          XMLHttpRequest.prototype.setRequestHeader = function(k, v){
            try { this.__aggxH = this.__aggxH || {}; this.__aggxH[k] = v; } catch(e){}
            return oh.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function(b){
            try {
              var body = b;
              if (body && typeof body !== 'string') { try { body = JSON.stringify(body); } catch(e){ body = null; } }
              send({url: this.__aggxU || '', method: this.__aggxM || 'GET', body: body,
                    headers: this.__aggxH || {}, via: 'xhr'});
            } catch(e){}
            return os.apply(this, arguments);
          };
          return 'hooked';
        })();
    """.trimIndent()

    /**
     * Find the site's search input the way a user would and type [query] into
     * it. Returns a short descriptor of what was found, or "no…" when nothing
     * search-like exists on the page.
     */
    private fun findAndTypeJs(query: String): String = """
        (function(q){
          function vis(e){
            if (!e) return false;
            var r = e.getBoundingClientRect();
            var s = window.getComputedStyle(e);
            return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none';
          }
          function searchy(e){
            var t = ((e.getAttribute('name')||'') + ' ' + (e.getAttribute('id')||'') + ' ' +
                     (e.getAttribute('placeholder')||'') + ' ' + (e.getAttribute('aria-label')||'') + ' ' +
                     (e.getAttribute('class')||'') + ' ' + (e.getAttribute('title')||'')).toLowerCase();
            return /search|suche|busca|recherche|query|find|keyword|cerca|поиск/.test(t);
          }
          function pick(){
            var c = document.querySelector('input[type=search]');
            if (vis(c)) return [c, 'input[type=search]'];
            var scoped = document.querySelectorAll('[role=search] input, form[role=search] input, form[action*=search i] input, form[id*=search i] input, form[class*=search i] input');
            for (var i=0;i<scoped.length;i++){
              var t = (scoped[i].getAttribute('type')||'text').toLowerCase();
              if (t !== 'hidden' && t !== 'submit' && t !== 'button' && vis(scoped[i])) return [scoped[i], 'role/form search input'];
            }
            var named = document.querySelectorAll('input[name=q],input[name=s],input[name=query],input[name=search],input[name=keyword],input[name=keywords],input[name=term],input[name=searchstr],input[name=search_query],input[name=nm],input[name=name]');
            for (var j=0;j<named.length;j++) if (vis(named[j])) return [named[j], 'named input ' + named[j].getAttribute('name')];
            var texts = document.querySelectorAll('input[type=text],input:not([type]),input[type=search]');
            for (var k=0;k<texts.length;k++) if (vis(texts[k]) && searchy(texts[k])) return [texts[k], 'search-labelled input'];
            return null;
          }
          var got = pick();
          if (!got) {
            // A search ICON that reveals the input only once clicked.
            var openers = document.querySelectorAll('button,a,[role=button],[class*=search i],[id*=search i],[aria-label*=search i]');
            for (var m=0;m<openers.length && m<40;m++){
              var o = openers[m];
              if (!vis(o)) continue;
              var t = ((o.getAttribute('aria-label')||'') + ' ' + (o.getAttribute('class')||'') + ' ' +
                       (o.getAttribute('id')||'') + ' ' + (o.getAttribute('title')||'')).toLowerCase();
              if (!/search|magnif|loupe|lupa/.test(t)) continue;
              try { o.click(); } catch(e){ continue; }
              got = pick();
              if (got) { got[1] = 'revealed by clicking ' + (o.getAttribute('aria-label') || o.className || o.tagName); break; }
            }
          }
          if (!got) return 'noinput';
          var inp = got[0];
          window.__aggxInput = inp;
          try { inp.focus(); } catch(e){}
          try {
            var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
            setter.call(inp, q);
          } catch(e){ inp.value = q; }
          inp.dispatchEvent(new Event('input', {bubbles:true}));
          inp.dispatchEvent(new Event('change', {bubbles:true}));
          inp.dispatchEvent(new KeyboardEvent('keyup', {key:'h', bubbles:true}));
          return got[1];
        })(${JSONObject.quote(query)});
    """.trimIndent()

    /** Submit the search the way the site expects: requestSubmit, button, Enter. */
    private fun submitJs(): String = """
        (function(){
          var inp = window.__aggxInput;
          if (!inp) return 'nosubmit';
          var form = inp.form || inp.closest('form');
          if (form) {
            var btn = form.querySelector('button[type=submit],input[type=submit],button:not([type])');
            if (form.requestSubmit) { try { form.requestSubmit(btn || undefined); return 'requestSubmit'; } catch(e){} }
            if (btn) { try { btn.click(); return 'submit button'; } catch(e){} }
            try { form.submit(); return 'form.submit'; } catch(e){}
          }
          var near = inp.parentElement ? inp.parentElement.querySelector('button,[role=button],[type=submit]') : null;
          if (near) { try { near.click(); return 'nearby button'; } catch(e){} }
          var ev = ['keydown','keypress','keyup'];
          for (var i=0;i<ev.length;i++){
            inp.dispatchEvent(new KeyboardEvent(ev[i], {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
          }
          return 'enter';
        })();
    """.trimIndent()

    private const val DEFAULT_TIMEOUT_MS = 30_000
    private const val SETTLE_MS = 1_800L
    private const val TYPE_SETTLE_MS = 1_200L
    private const val AJAX_WAIT_MS = 7_000L
    private const val MAX_CAPTURED = 160
    private const val MAX_SEARCH_CANDIDATES = 4
    private const val MAX_SUGGEST = 4

    private val STATIC_ASSET = Regex(
        "\\.(png|jpe?g|gif|webp|svg|ico|css|woff2?|ttf|eot|mp4|webm|mp3|avif)(\\?|$)",
        RegexOption.IGNORE_CASE
    )
    private val SUGGEST_MARKER = Regex(
        "(suggest|autocomplete|typeahead|instant|predict|/ac\\?|complete\\.json)",
        RegexOption.IGNORE_CASE
    )
    private val PAGE_PARAM_REGEX = Regex("([?&])(page|paged|pg|p|offset|start|from)=\\d+", RegexOption.IGNORE_CASE)
    private val PAGE_PARAM_BODY_REGEX = Regex("(^|&)(page|paged|pg|p|offset|start|from)=\\d+", RegexOption.IGNORE_CASE)
    private val KEEP_HEADERS = setOf(
        "accept", "content-type", "x-requested-with", "authorization",
        "x-csrf-token", "x-xsrf-token", "x-algolia-api-key", "x-algolia-application-id",
        "x-typesense-api-key", "x-api-key", "referer", "origin"
    )
}
