package com.aggregatorx.app.engine.analyzer

import android.util.Log
import com.aggregatorx.app.engine.network.HttpTransport
import com.aggregatorx.app.engine.network.TransportRequest
import com.aggregatorx.app.engine.network.TransportResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/** httpx-style triage record for one fetched endpoint. */
@Serializable
data class EndpointTriage(
    val url: String,
    val status: Int,
    val finalUrl: String,
    val redirected: Boolean,
    val contentType: String? = null,
    val bytes: Int = 0,
    val elapsedMs: Long = 0,
    val engine: String = "",
    val tech: List<String> = emptyList(),
    val purpose: String = ""
)

/** One recon technique's outcome, recorded with its evidence. */
@Serializable
data class ReconCheck(
    val technique: String,
    val status: String, // "ok" | "empty" | "skipped" | "failed"
    val evidence: String
)

/** A same-host wordlist route hit that was verified live (status + echo/JSON). */
@Serializable
data class WordlistHit(
    val template: String,
    val status: Int,
    val evidence: String
)

/** Everything the heavy recon techniques recorded for one site. */
@Serializable
data class ReconMap(
    val techFingerprint: List<String> = emptyList(),
    val crawledPages: List<String> = emptyList(),
    val minedParameters: Map<String, Int> = emptyMap(),
    val searchParameters: List<String> = emptyList(),
    val jsEndpoints: List<String> = emptyList(),
    val archiveUrlCount: Int = 0,
    val archiveSamples: List<String> = emptyList(),
    val wordlistProbed: Int = 0,
    val wordlistHits: List<WordlistHit> = emptyList(),
    val triage: List<EndpointTriage> = emptyList(),
    val checks: List<ReconCheck> = emptyList()
)

/**
 * The Analyzer's heavy recon techniques (spec §1), implemented on-device in
 * Kotlin over [HttpTransport]:
 *
 *  - Deep crawl (Katana / Burp crawler style): bounded same-host BFS over the
 *    site's own link graph.
 *  - Content / route discovery (ffuf / gobuster / dirsearch style): a small,
 *    bounded, same-host wordlist of search / API / feed routes, prioritized by
 *    the tech fingerprint; every hit is verified live (HTTP 2xx and the probe
 *    term echoed back, or a JSON payload) before it may become a candidate —
 *    and candidates must still pass full verification before anything is saved.
 *  - Hidden parameter discovery (Arjun / ParamSpider style): parameter names
 *    mined from URLs, forms and scripts.
 *  - JS endpoint mining (LinkFinder / subjs style): endpoint paths in inline and
 *    same-host scripts.
 *  - Technology fingerprinting (WhatWeb / Wappalyzer style): headers, cookies,
 *    meta generator and markup signatures.
 *  - Historical URL mining (gau / waybackurls style): the Wayback CDX index for
 *    the site's own host, filtered back to same-host URLs.
 *  - Live probing / triage (httpx style): status, redirect, content type, size,
 *    timing and tech for every endpoint fetched.
 *
 * Discipline: same host only (the one archive lookup asks the archive ABOUT the
 * host and keeps only same-host URLs), sequential requests with a minimum gap,
 * and hard URL / wordlist / time budgets. Nothing here saves anything; it feeds
 * candidates and evidence into the map → verify → save pipeline.
 */
class ReconToolkit(
    private val base: String,
    private val baseHost: String,
    private val deadlineAt: Long
) {
    private val triage = mutableListOf<EndpointTriage>()
    private val checks = mutableListOf<ReconCheck>()
    private var lastRequestAt = 0L

    fun timeLeft(): Long = deadlineAt - System.currentTimeMillis()

    fun record(check: ReconCheck) { checks += check }

    fun isSameHost(url: String): Boolean =
        runCatching { URI(url).host.equals(baseHost, ignoreCase = true) }.getOrDefault(false)

    /** Rate-limited, bounded, recorded fetch. */
    fun fetch(url: String, timeoutMs: Int, purpose: String, acceptJson: Boolean = false): TransportResponse? {
        if (timeLeft() < 500) return null
        val gap = System.currentTimeMillis() - lastRequestAt
        if (gap < MIN_GAP_MS) Thread.sleep(MIN_GAP_MS - gap)
        lastRequestAt = System.currentTimeMillis()
        val bounded = timeoutMs.coerceAtMost(timeLeft().toInt().coerceAtLeast(1_000))
        val response = HttpTransport.fetchBlocking(
            TransportRequest(url = url, headers = HttpTransport.defaultHeaders(acceptJson), timeoutMs = bounded)
        )
        triage += EndpointTriage(
            url = url,
            status = response.statusCode,
            finalUrl = response.finalUrl,
            redirected = response.finalUrl.isNotBlank() && response.finalUrl.trimEnd('/') != url.trimEnd('/'),
            contentType = response.contentType()?.substringBefore(';'),
            bytes = response.body.length,
            elapsedMs = response.elapsedMs,
            engine = response.engine.name + (response.degradedReason?.let { " (degraded: ${it.take(80)})" } ?: ""),
            tech = if (response.isHttpOk) fingerprintSignals(response, null).take(6) else emptyList(),
            purpose = purpose
        )
        return response
    }

    // ── Technology fingerprinting ──────────────────────────────────────

    fun fingerprint(homepage: TransportResponse?, doc: Document?): List<String> {
        val signals = fingerprintSignals(homepage, doc)
        record(ReconCheck("tech-fingerprint", if (signals.isEmpty()) "empty" else "ok",
            if (signals.isEmpty()) "no stack signature in headers, cookies or markup" else signals.joinToString(", ")))
        return signals
    }

    private fun fingerprintSignals(response: TransportResponse?, doc: Document?): List<String> {
        val found = linkedSetOf<String>()
        if (response != null) {
            response.header("Server")?.let { found += "server:${it.take(40).lowercase()}" }
            response.header("X-Powered-By")?.let { found += "powered-by:${it.take(40).lowercase()}" }
            if (response.header("cf-ray") != null) found += "cdn:cloudflare"
            if (response.header("x-shopify-stage") != null || response.header("x-shopid") != null) found += "shopify"
            if (response.header("x-drupal-cache") != null || response.header("x-generator")?.contains("drupal", true) == true) found += "drupal"
            if (response.header("x-magento-tags") != null) found += "magento"
            val cookieNames = response.cookies().keys.map { it.lowercase() }
            if (cookieNames.any { it.startsWith("wordpress") || it.startsWith("wp-") }) found += "wordpress"
            if (cookieNames.any { it == "laravel_session" || it == "xsrf-token" }) found += "laravel"
            if (cookieNames.any { it == "phpsessid" }) found += "php"
            if (cookieNames.any { it == "frontend" || it.startsWith("mage-") }) found += "magento"
        }
        val html = response?.body?.take(400_000) ?: doc?.outerHtml()?.take(400_000).orEmpty()
        val lower = html.lowercase()
        val generator = doc?.selectFirst("meta[name=generator]")?.attr("content")
            ?: Regex("<meta[^>]+name=[\"']generator[\"'][^>]+content=[\"']([^\"']+)", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
        generator?.takeIf { it.isNotBlank() }?.let { found += "generator:${it.take(40).lowercase()}" }
        if ("/wp-content/" in lower || "/wp-includes/" in lower || "wp-json" in lower) found += "wordpress"
        if ("drupal.settings" in lower || "/sites/default/files" in lower || "data-drupal" in lower) found += "drupal"
        if ("mage/cookies" in lower || "/static/version" in lower || "magento" in lower) found += "magento"
        if ("cdn.shopify.com" in lower || "shopify.theme" in lower) found += "shopify"
        if ("__next_data__" in lower || "/_next/static" in lower) found += "next.js"
        if ("__nuxt" in lower || "/_nuxt/" in lower) found += "nuxt"
        if ("/media/jui/" in lower || "joomla" in lower) found += "joomla"
        if ("mediawiki" in lower || "/load.php?" in lower) found += "mediawiki"
        if ("discourse" in lower && "data-discourse" in lower) found += "discourse"
        if ("ghost-" in lower && "content/themes" in lower) found += "ghost"
        if ("data-reactroot" in lower || "react-dom" in lower) found += "react"
        if ("ng-version" in lower) found += "angular"
        if ("vue" in lower && ("data-v-" in lower || "__vue__" in lower)) found += "vue"
        if ("cf_chl_opt" in lower || "challenge-platform" in lower) found += "anti-bot:cloudflare-challenge"
        return found.toList()
    }

    // ── Deep crawl (bounded same-host BFS) ─────────────────────────────

    data class CrawlResult(val pages: List<String>, val links: Set<String>, val docs: List<Document>)

    /**
     * Katana-style bounded BFS over the site's own link graph (same host only).
     * A crawled page that is a JavaScript app shell is re-read through the
     * embedded WebView so JS-rendered routes and their links are mapped too
     * (at most [MAX_RENDERED_CRAWL_PAGES] renders, each bounded, within budget).
     */
    suspend fun crawl(start: Document?, maxPages: Int = MAX_CRAWL_PAGES, maxDepth: Int = 2): CrawlResult {
        if (start == null) {
            record(ReconCheck("deep-crawl", "skipped", "no homepage document to start from"))
            return CrawlResult(emptyList(), emptySet(), emptyList())
        }
        val links = linkedSetOf<String>()
        val visited = linkedSetOf(normalize(start.location().ifBlank { base }))
        val docs = mutableListOf<Document>()
        var frontier = linksOf(start).also { links += it }
        var depth = 1
        var renderedPages = 0
        val renderedUrls = mutableListOf<String>()
        while (depth <= maxDepth && frontier.isNotEmpty() && visited.size < maxPages + 1 && timeLeft() > 3_000) {
            val next = linkedSetOf<String>()
            // Search/browse-looking pages first: they carry the forms and
            // pagination conventions the config needs.
            val ordered = frontier.filter { normalize(it) !in visited }
                .sortedBy { url -> if (CRAWL_PRIORITY.any { url.lowercase().contains(it) }) 0 else 1 }
            for (url in ordered) {
                if (visited.size >= maxPages + 1 || timeLeft() < 3_000) break
                visited += normalize(url)
                val response = fetch(url, CRAWL_TIMEOUT_MS, "crawl") ?: continue
                if (!response.isHttpOk) continue
                val type = response.contentType().orEmpty()
                if (type.isNotBlank() && !type.contains("html", true)) continue
                var doc = runCatching { Jsoup.parse(response.body, response.finalUrl.ifBlank { url }) }.getOrNull() ?: continue
                if (renderedPages < MAX_RENDERED_CRAWL_PAGES && timeLeft() > RENDERED_CRAWL_TIMEOUT_MS + 4_000 &&
                    com.aggregatorx.app.engine.scraper.JsRenderDetector.looksJavaScriptRendered(doc)
                ) {
                    renderedPages++
                    val rendered = try {
                        kotlinx.coroutines.withTimeoutOrNull(RENDERED_CRAWL_TIMEOUT_MS + 2_000) {
                            com.aggregatorx.app.engine.scraper.HeadlessBrowserHelper
                                .fetchPageContent(url, RENDERED_CRAWL_TIMEOUT_MS.toInt())
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    if (!rendered.isNullOrBlank()) {
                        doc = Jsoup.parse(rendered, response.finalUrl.ifBlank { url })
                        renderedUrls += url
                        Log.i("ReconToolkit", "crawl FALLBACK webview-rendered JS page $url")
                    }
                }
                docs += doc
                val found = linksOf(doc)
                links += found
                next += found
            }
            frontier = next
            depth++
        }
        record(ReconCheck("deep-crawl", if (docs.isEmpty()) "empty" else "ok",
            "crawled ${docs.size} same-host pages (depth <= $maxDepth, cap $maxPages), ${links.size} same-host links mapped"))
        record(ReconCheck("js-rendered-crawl", if (renderedUrls.isEmpty()) "skipped" else "ok",
            if (renderedUrls.isEmpty()) "no crawled page needed JavaScript rendering"
            else "rendered ${renderedUrls.size} JavaScript-only pages in the WebView: ${renderedUrls.take(3).joinToString()}"))
        return CrawlResult(visited.drop(1), links, docs)
    }

    private fun linksOf(doc: Document): Set<String> = doc.select("a[href]")
        .map { it.absUrl("href").substringBefore('#') }
        .filter { it.startsWith("http") && isSameHost(it) && !STATIC_ASSET.containsMatchIn(it) }
        .toSet()

    private fun normalize(url: String) = url.substringBefore('#').trimEnd('/').lowercase()

    // ── Historical URL mining (Wayback CDX) ────────────────────────────

    fun mineArchive(): List<String> {
        if (timeLeft() < 4_000) {
            record(ReconCheck("archive-url-mining", "skipped", "time budget exhausted"))
            return emptyList()
        }
        val cdx = "https://web.archive.org/cdx/search/cdx?url=" +
            URLEncoder.encode("$baseHost/*", "UTF-8") +
            "&output=json&fl=original&collapse=urlkey&filter=statuscode:200&limit=$ARCHIVE_LIMIT"
        val response = fetch(cdx, ARCHIVE_TIMEOUT_MS, "archive-cdx", acceptJson = true)
        if (response == null || !response.isHttpOk) {
            record(ReconCheck("archive-url-mining", "failed",
                "Wayback CDX lookup failed (${response?.error ?: "HTTP ${response?.statusCode ?: 0}"})"))
            return emptyList()
        }
        val urls = runCatching {
            (json.parseToJsonElement(response.body) as JsonArray)
                .drop(1) // header row
                .mapNotNull { row -> ((row as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull }
        }.getOrDefault(emptyList())
            .map { if (it.startsWith("http")) it else "https://$it" }
            .filter { isSameHost(it) && !STATIC_ASSET.containsMatchIn(it) }
            .distinct()
        record(ReconCheck("archive-url-mining", if (urls.isEmpty()) "empty" else "ok",
            "${urls.size} archived same-host URLs mined from the Wayback CDX index"))
        return urls
    }

    // ── Hidden parameter discovery ─────────────────────────────────────

    fun mineParameters(urls: Collection<String>, docs: List<Document>, scripts: List<String>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        fun add(name: String) {
            val key = name.trim()
            if (key.isEmpty() || key.length > 40 || !PARAM_NAME.matches(key)) return
            counts[key] = (counts[key] ?: 0) + 1
        }
        for (url in urls) {
            url.substringAfter('?', "").substringBefore('#').split('&')
                .forEach { if (it.contains('=')) add(it.substringBefore('=')) }
        }
        for (doc in docs) {
            doc.select("form input[name], form select[name], form textarea[name]").forEach { add(it.attr("name")) }
        }
        for (text in scripts) {
            SCRIPT_PARAM.findAll(text).forEach { add(it.groupValues[1]) }
        }
        val top = counts.entries.sortedByDescending { it.value }.take(MAX_PARAMS).associate { it.key to it.value }
        val search = top.keys.filter { it.lowercase() in SEARCH_PARAM_NAMES }
        record(ReconCheck("parameter-discovery", if (top.isEmpty()) "empty" else "ok",
            "${counts.size} parameter names mined from URLs, forms and scripts" +
                if (search.isNotEmpty()) "; search-like: ${search.joinToString()}" else ""))
        return top
    }

    // ── JS endpoint mining ─────────────────────────────────────────────

    fun scriptTexts(doc: Document?): List<String> {
        if (doc == null) return emptyList()
        val texts = mutableListOf<String>()
        doc.select("script:not([src])").forEach { s -> s.data().takeIf { it.isNotBlank() }?.let { texts += it } }
        doc.select("script[src]").map { it.absUrl("src") }
            .filter { it.startsWith("http") && isSameHost(it) }
            .distinct().take(MAX_SCRIPT_FILES)
            .forEach { src ->
                val r = fetch(src, SCRIPT_TIMEOUT_MS, "script")
                if (r != null && r.isHttpOk) texts += r.body.take(1_500_000)
            }
        return texts
    }

    fun mineJsEndpoints(scripts: List<String>): List<String> {
        val endpoints = linkedSetOf<String>()
        for (text in scripts) {
            for (m in LINKFINDER.findAll(text)) {
                val raw = m.groupValues[1]
                if (STATIC_ASSET.containsMatchIn(raw)) continue
                val absolute = when {
                    raw.startsWith("http") -> raw
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("/") -> base.trimEnd('/') + raw
                    else -> continue
                }
                if (isSameHost(absolute)) endpoints += absolute
                if (endpoints.size >= MAX_JS_ENDPOINTS) break
            }
        }
        record(ReconCheck("js-endpoint-mining", if (endpoints.isEmpty()) "empty" else "ok",
            "${endpoints.size} same-host endpoint paths extracted from ${scripts.size} scripts"))
        return endpoints.toList()
    }

    /** httpx-style triage of mined JS endpoints (bounded). */
    fun triageEndpoints(endpoints: List<String>) {
        val targets = endpoints.filter { !it.contains("{") }.take(MAX_TRIAGE)
        var ok = 0
        for (url in targets) {
            val r = fetch(url, TRIAGE_TIMEOUT_MS, "triage", acceptJson = true) ?: break
            if (r.isHttpOk) ok++
        }
        record(ReconCheck("httpx-triage", if (targets.isEmpty()) "skipped" else "ok",
            "${targets.size} mined endpoints probed, $ok answered 2xx/3xx; ${triage.size} endpoints triaged in total"))
    }

    // ── Wordlist route discovery ───────────────────────────────────────

    /**
     * Probe a bounded same-host wordlist of search routes with the real probe
     * term. A route is a HIT only when it answers 2xx AND either echoes the
     * probe term in the page or returns a JSON payload.
     */
    fun probeWordlist(probeQuery: String?, tech: List<String>): List<WordlistHit> {
        if (probeQuery.isNullOrBlank()) {
            record(ReconCheck("wordlist-route-discovery", "skipped", "no probe term from the site's own content"))
            return emptyList()
        }
        val routes = linkedSetOf<String>()
        tech.forEach { signal -> STACK_ROUTES.entries.filter { signal.contains(it.key) }.forEach { routes += it.value } }
        routes += GENERIC_SEARCH_ROUTES
        val encoded = URLEncoder.encode(probeQuery, "UTF-8")
        val hits = mutableListOf<WordlistHit>()
        var probed = 0
        for (route in routes.take(MAX_WORDLIST)) {
            if (timeLeft() < 2_000) break
            val template = base.trimEnd('/') + route
            val url = template.replace("{query}", encoded).replace("{page}", "1")
            probed++
            val r = fetch(url, WORDLIST_TIMEOUT_MS, "wordlist") ?: break
            if (!r.isHttpOk || !isSameHost(r.finalUrl.ifBlank { url })) continue
            val body = r.body
            val isJson = body.trimStart().let { it.startsWith("{") || it.startsWith("[") }
            val echoes = body.contains(probeQuery, ignoreCase = true)
            if (isJson || echoes) {
                hits += WordlistHit(template, r.statusCode,
                    "HTTP ${r.statusCode}, ${body.length} bytes, " + if (isJson) "JSON payload" else "probe term echoed")
            }
        }
        wordlistProbed = probed
        record(ReconCheck("wordlist-route-discovery", if (hits.isEmpty()) "empty" else "ok",
            "$probed same-host routes probed with \"$probeQuery\"; ${hits.size} verified live hits"))
        return hits
    }

    private var wordlistProbed = 0

    fun buildMap(
        tech: List<String>,
        crawl: CrawlResult,
        params: Map<String, Int>,
        jsEndpoints: List<String>,
        archive: List<String>,
        hits: List<WordlistHit>
    ) = ReconMap(
        techFingerprint = tech,
        crawledPages = crawl.pages.take(40),
        minedParameters = params,
        searchParameters = params.keys.filter { it.lowercase() in SEARCH_PARAM_NAMES },
        jsEndpoints = jsEndpoints.take(40),
        archiveUrlCount = archive.size,
        archiveSamples = archive.filter { it.contains('?') }.take(20),
        wordlistProbed = wordlistProbed,
        wordlistHits = hits,
        triage = triage.toList().takeLast(MAX_TRIAGE_RECORDS),
        checks = checks.toList()
    ).also {
        Log.i(TAG, "recon map $baseHost: tech=$tech crawled=${crawl.pages.size} params=${params.size} " +
            "js=${jsEndpoints.size} archive=${archive.size} wordlistHits=${hits.size} triage=${triage.size}")
    }

    companion object {
        private const val TAG = "ReconToolkit"
        private const val MIN_GAP_MS = 150L
        private const val MAX_CRAWL_PAGES = 20
        private const val MAX_RENDERED_CRAWL_PAGES = 3
        private const val RENDERED_CRAWL_TIMEOUT_MS = 10_000L
        private const val CRAWL_TIMEOUT_MS = 6_000
        private const val ARCHIVE_TIMEOUT_MS = 8_000
        private const val ARCHIVE_LIMIT = 600
        private const val SCRIPT_TIMEOUT_MS = 6_000
        private const val TRIAGE_TIMEOUT_MS = 5_000
        private const val WORDLIST_TIMEOUT_MS = 6_000
        private const val MAX_SCRIPT_FILES = 3
        private const val MAX_JS_ENDPOINTS = 60
        private const val MAX_TRIAGE = 6
        private const val MAX_TRIAGE_RECORDS = 80
        private const val MAX_WORDLIST = 14
        private const val MAX_PARAMS = 60

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val STATIC_ASSET = Regex(
            "\\.(png|jpe?g|gif|webp|svg|ico|css|woff2?|ttf|eot|mp4|webm|mp3|pdf|zip|map)(\\?|$)",
            RegexOption.IGNORE_CASE
        )
        private val PARAM_NAME = Regex("^[A-Za-z_][A-Za-z0-9_\\-\\[\\]]*$")
        private val SCRIPT_PARAM = Regex("[?&]([A-Za-z_][A-Za-z0-9_]{0,30})=")
        private val LINKFINDER = Regex(
            "[\"'`]((?:https?:)?//[^\"'`\\s]{3,200}|/(?:api|ajax|v\\d|graphql|search|wp-json|rest|json|feeds?|rss)[^\"'`\\s]{0,200}|/[A-Za-z0-9_\\-/]{1,120}\\.(?:json|php|aspx?|jsp|do|action)(?:\\?[^\"'`\\s]{0,200})?)[\"'`]"
        )
        private val CRAWL_PRIORITY = listOf("search", "find", "browse", "results", "latest", "videos", "movies", "list")
        val SEARCH_PARAM_NAMES = setOf(
            "q", "s", "query", "search", "search_query", "keyword", "keywords", "k", "kw", "term", "text", "searchword", "keys", "find"
        )

        /** Known search-route shapes per fingerprinted stack — probed live, never trusted blind. */
        private val STACK_ROUTES = mapOf(
            "wordpress" to "/?s={query}",
            "drupal" to "/search/node?keys={query}",
            "magento" to "/catalogsearch/result/?q={query}",
            "shopify" to "/search?q={query}&type=product",
            "joomla" to "/index.php?option=com_search&searchword={query}",
            "mediawiki" to "/index.php?search={query}&fulltext=1",
            "discourse" to "/search.json?q={query}",
            "ghost" to "/ghost/api/content/posts/?filter=title:~'{query}'"
        )

        private val GENERIC_SEARCH_ROUTES = listOf(
            "/search?q={query}",
            "/search?query={query}",
            "/search/{query}",
            "/?s={query}",
            "/search?keyword={query}",
            "/search.php?q={query}",
            "/api/search?q={query}",
            "/wp-json/wp/v2/search?search={query}",
            "/find?q={query}",
            "/results?search_query={query}"
        )
    }
}
