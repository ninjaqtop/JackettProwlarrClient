package com.aggregatorx.app.engine.scraper

import android.util.Log
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * HeadlessBrowserHelper — Android-native headless scraping engine.
 *
 * Replaces Playwright with a layered native stack:
 *   Layer 1 — OkHttp with full browser-grade headers + persistent cookie jar
 *   Layer 2 — Jsoup DOM parsing with shadow-DOM attribute expansion
 *   Layer 3 — JS regex extraction for video/stream URLs
 *   Layer 4 — Form discovery + POST/GET submission for search endpoints
 *   Layer 5 — Tab/category link crawling
 *   Layer 6 — REST/JSON/GraphQL API endpoint probing
 *
 * All public method signatures are identical to the original Playwright-based
 * version so ScrapingEngine, FallbackEngine, and EndpointDiscoveryEngine
 * compile without modification.
 */
object HeadlessBrowserHelper {

    private const val TAG = "HeadlessBrowserHelper"

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
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Ch-Ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"")
                    .header("Sec-Ch-Ua-Mobile", "?1")
                    .header("Upgrade-Insecure-Requests", "1")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    // ── JS Deobfuscation ──────────────────────────────────────────────────────

    /**
     * Unpacks eval(p,a,c,k,e,d) packed JavaScript and decodes atob() calls.
     * Returns the deobfuscated source, or the original if not packed.
     */
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
        // Decode atob() calls
        result = Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=]{20,})['"]""").replace(result) { m ->
            try {
                val decoded = String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))
                "\"$decoded\""
            } catch (_: Exception) { m.value }
        }
        // Decode unicode escapes
        result = Regex("""\\u([0-9a-fA-F]{4})""").replace(result) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
        // Decode hex-encoded strings
        result = Regex("""(?:unescape|decodeURIComponent)\s*\(\s*['"](%[0-9A-Fa-f]{2}(?:%[0-9A-Fa-f]{2})+)['"]""").replace(result) { m ->
            try { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (_: Exception) { m.value }
        }
        return result
    }

    private fun unpackPacked(p: String, a: Int, c: Int, k: List<String>): String {
        var result = p
        var i = c - 1
        while (i >= 0) {
            if (k.getOrNull(i)?.isNotEmpty() == true) {
                result = result.replace(Regex("\\b${toBase(i, a)}\\b"), k[i])
            }
            i--
        }
        return result
    }

    private fun toBase(num: Int, base: Int): String {
        if (num == 0) return "0"
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        var n = num
        val sb = StringBuilder()
        while (n > 0) { sb.insert(0, chars[n % base]); n /= base }
        return sb.toString()
    }

    private val VIDEO_SOURCE_PATTERNS = listOf(
        Regex("""(?:src|file|source|url|video_url|videoUrl|stream)['":\s]+['"]?(https?://[^'">\s]+\.(?:mp4|m3u8|webm|mpd)[^'">\s]*)['"]?""", RegexOption.IGNORE_CASE),
        Regex("""['"]?(https?://[^'">\s]*\.(?:mp4|m3u8|webm|mpd|flv|mkv)[^'">\s]*)['"]?""", RegexOption.IGNORE_CASE),
        Regex("""file:\s*['"](https?://[^'"]+)['"]""", RegexOption.IGNORE_CASE),
        Regex("""sources:\s*\[\s*\{\s*(?:file|src):\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        Regex("""player\.src\(\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        Regex("""hls\.loadSource\(['"]([^'"]+)['"]\)""", RegexOption.IGNORE_CASE),
        Regex("""dash\.initialize\([^,]+,\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        Regex(""""contentUrl"\s*:\s*"([^"]+)""""),
        Regex(""""embedUrl"\s*:\s*"([^"]+)""""),
        Regex("""jwplayer\([^)]+\)\.setup\(\{[^}]*file:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
        Regex("""<source[^>]+src=['"]([^'"]+\.(?:mp4|m3u8|webm|mpd)[^'"]*)['"]""", RegexOption.IGNORE_CASE)
    )

    // ── Stub page object (replaces com.microsoft.playwright.Page) ────────────

    class NativePage(val pageUrl: String = "") {
        private var _html: String = ""
        fun html(): String = _html
        internal fun setHtml(h: String) { _html = h }
        fun navigate(url: String, options: Any? = null): NativePage =
            runBlocking { fetchNativePage(url) ?: this@NativePage }
        fun waitForLoadState(state: Any? = null, options: Any? = null) {}
        fun content(): String = _html
        fun evaluate(script: String): Any? = null
        fun querySelectorAll(selector: String): List<Any> = emptyList()
        fun close() {}
    }

    fun createAntiDetectionPage(): NativePage = NativePage()
    fun getBrowser(): Any? = null
    fun close() { cookieJar.clear() }

    // ── Core fetch ────────────────────────────────────────────────────────────

    fun fetchPageContent(url: String, waitSelector: String? = null, timeout: Int = 15000): String? =
        runBlocking { fetchRaw(url) }

    fun fetchPageContentWithShadowAndAdSkip(
        url: String,
        waitSelector: String? = null,
        timeout: Int = 15000
    ): String? = runBlocking {
        val raw = fetchRaw(url) ?: return@runBlocking null
        val doc = Jsoup.parse(raw, url)
        stripOverlays(doc)
        expandLazyAttributes(doc)
        doc.outerHtml()
    }

    // ── Video extraction ──────────────────────────────────────────────────────

    fun extractVideoUrls(url: String, timeout: Int = 20000): List<String> {
        val html = fetchPageContent(url, timeout = timeout) ?: return emptyList()
        return extractVideoUrlsFromHtml(html, url)
    }

    data class VideoUrlInfo(
        val url: String,
        val type: String,
        val quality: String = "",
        val isStream: Boolean = false
    )

    fun extractVideoUrlsDetailed(url: String, timeout: Int = 20000): List<VideoUrlInfo> =
        extractVideoUrls(url, timeout).map { videoUrl ->
            VideoUrlInfo(
                url      = videoUrl,
                type     = when {
                    videoUrl.contains(".m3u8") -> "hls"
                    videoUrl.contains(".mpd")  -> "dash"
                    videoUrl.contains(".mp4")  -> "mp4"
                    else                       -> "unknown"
                },
                isStream = videoUrl.contains(".m3u8") || videoUrl.contains(".mpd")
            )
        }

    // ── Tab / category crawling ───────────────────────────────────────────────

    fun fetchContentByClickingTabs(baseUrl: String, query: String, timeout: Int = 25000): String? =
        runBlocking {
            val baseHtml = fetchRaw(baseUrl) ?: return@runBlocking null
            val doc = Jsoup.parse(baseHtml, baseUrl)
            val tabLinks = doc.select(
                "nav a[href], .tabs a[href], .tab a[href], .categories a[href], " +
                ".menu a[href], [role=tab], .tab-item a[href], .nav-item a[href]"
            ).map { it.absUrl("href") }
                .filter { it.isNotEmpty() && it != baseUrl }
                .distinct().take(8)

            val sb = StringBuilder(baseHtml)
            for (link in tabLinks) {
                try {
                    val tabHtml = fetchRaw(link) ?: continue
                    if (tabHtml.contains(query, ignoreCase = true)) {
                        sb.append("\n<!-- TAB: $link -->\n").append(tabHtml)
                    }
                } catch (e: Exception) { Log.v(TAG, "Tab: $link — ${e.message}") }
            }
            sb.toString()
        }

    // ── Form-based search ─────────────────────────────────────────────────────

                fun searchViaHeadlessForm(baseUrl: String, query: String, timeout: Int = 25000): String? =
        runBlocking {
            val html = fetchRaw(baseUrl) ?: return@runBlocking null
            val doc  = Jsoup.parse(html, baseUrl)
            val form = doc.select("form").firstOrNull { f ->
                f.select("input[type=text], input[type=search], input[name*=q], input[name*=search], input[name*=query]").isNotEmpty()
            } ?: return@runBlocking html

            val action = form.absUrl("action").ifEmpty { baseUrl }
            val method = form.attr("method").lowercase().ifEmpty { "get" }
            val fields = mutableMapOf<String, String>()
            
            // Refactored to avoid 'continue' which triggers the Version 2.2 error
            val inputs = form.select("input, select, textarea")
            for (input in inputs) {
                val name = input.attr("name")
                if (name.isNotEmpty()) {
                    val type = input.attr("type").lowercase()
                    if (type != "submit") {
                        fields[name] = if (type == "search" || name.contains("q", true) ||
                            name.contains("search", true) || name.contains("query", true)) {
                            query
                        } else {
                            input.attr("value")
                        }
                    }
                }
            }

            try {
                if (method == "post") {
                    val bodyBuilder = FormBody.Builder()
                    for (entry in fields) {
                        bodyBuilder.add(entry.key, entry.value)
                    }
                    val body = bodyBuilder.build()
                    val req = Request.Builder().url(action).post(body).header("Referer", baseUrl).build()
                    client.newCall(req).execute().use { it.body?.string() }
                } else {
                    val qs = fields.entries.joinToString("&") {
                        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
                    }
                    val getUrl = if (action.contains("?")) "$action&$qs" else "$action?$qs"
                    fetchRaw(getUrl)
                }
            } catch (e: Exception) { 
                Log.w(TAG, "Form submit: ${e.message}")
                html 
            }
        }



    // ── API endpoint discovery ────────────────────────────────────────────────

    fun discoverSearchAPIEndpoints(baseUrl: String, query: String, timeout: Int = 20000): List<String> =
        runBlocking {
            val host = extractHost(baseUrl)
            val eq   = URLEncoder.encode(query, "UTF-8")
            val candidates = listOf(
                "$host/api/search?q=$eq", "$host/api/v1/search?q=$eq",
                "$host/api/v2/search?q=$eq", "$host/search.json?q=$eq",
                "$host/search?q=$eq&format=json", "$host/api/videos?q=$eq",
                "$host/api/content?search=$eq", "$host/graphql",
                "$host/api?query=$eq", "$host/wp-json/wp/v2/posts?search=$eq"
            )
            val found = mutableListOf<String>()
            for (url in candidates) {
                try {
                    val req = Request.Builder().url(url)
                        .header("Accept", "application/json, */*")
                        .header("X-Requested-With", "XMLHttpRequest").build()
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        if (resp.isSuccessful && (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")))
                            found.add(url)
                    }
                } catch (_: Exception) {}
            }
            found
        }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun fetchRaw(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url)
                .header("Referer", extractHost(url) + "/").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { Log.v(TAG, "HTTP ${resp.code} $url"); return@withContext null }
                resp.body?.string()
            }
        } catch (e: Exception) { Log.w(TAG, "fetchRaw $url: ${e.message}"); null }
    }

    private suspend fun fetchNativePage(url: String): NativePage? {
        val html = fetchRaw(url) ?: return null
        return NativePage(url).also { it.setHtml(html) }
    }

        private fun extractVideoUrlsFromHtml(html: String, baseUrl: String): List<String> {
        val found = mutableSetOf<String>()
        // Also run on deobfuscated version
        val deobHtml = deobfuscateJs(html)
        for (source in listOf(html, deobHtml)) {
            for (pattern in VIDEO_SOURCE_PATTERNS) {
                for (m in pattern.findAll(source)) {
                    val u = (m.groupValues.getOrNull(1) ?: m.value).trim().trimEnd('"', '\'')
                    if (u.startsWith("http") && u.length > 15) found.add(u)
                }
            }
        }
        try {
            val elements = Jsoup.parse(html, baseUrl).select("video[src], source[src], video > source")
            for (el in elements) {
                val src = el.absUrl("src").ifEmpty { el.attr("src") }
                if (src.startsWith("http")) found.add(src)
            }
        } catch (_: Exception) {}
        // Also check data-* attributes
        try {
            val doc = Jsoup.parse(html, baseUrl)
            listOf("data-src","data-url","data-video","data-stream","data-file","data-hls","data-mp4").forEach { attr ->
                doc.select("[$attr]").forEach { el ->
                    val v = el.attr(attr)
                    if (v.startsWith("http")) found.add(v)
                }
            }
        } catch (_: Exception) {}
        return found.toList()
    }

    private fun stripOverlays(doc: Document) {
        listOf(".ad-overlay",".popup-overlay",".modal-overlay",".cookie-banner",
               "#cookie-consent",".gdpr-banner",".ad-container","[class*='popup']",
               "[class*='overlay']","[id*='cookie']","[id*='consent']",".sticky-ad"
        ).forEach { sel -> try { doc.select(sel).remove() } catch (_: Exception) {} }
    }

    private fun expandLazyAttributes(doc: Document) {
        listOf("data-src","data-lazy-src","data-original","data-url").forEach { attr ->
            doc.select("[$attr]").forEach { el ->
                if (el.attr("src").isEmpty()) el.attr("src", el.attr(attr))
            }
        }
    }

    private fun extractHost(url: String): String = try {
        val uri = java.net.URI(url); "${uri.scheme}://${uri.host}"
    } catch (_: Exception) { url }
}

private class InMemoryCookieJar : okhttp3.CookieJar {
    private val store = mutableMapOf<String, MutableList<okhttp3.Cookie>>()
    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
    }
    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> = store[url.host] ?: emptyList()
    fun clear() = store.clear()
}
