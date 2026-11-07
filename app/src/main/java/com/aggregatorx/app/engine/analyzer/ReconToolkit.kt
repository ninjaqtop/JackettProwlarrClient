package com.aggregatorx.app.engine.analyzer

import android.util.Log
import com.aggregatorx.app.engine.network.HttpTransport
import com.aggregatorx.app.engine.network.TransportRequest
import com.aggregatorx.app.engine.network.TransportResponse
import com.aggregatorx.app.engine.scraper.LiveSearchCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import kotlin.math.abs

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

/** A same-host wordlist route hit that was verified live and beat the soft-404 baseline. */
@Serializable
data class WordlistHit(
    val template: String,
    val status: Int,
    val evidence: String,
    val isJson: Boolean = false
)

/**
 * The ffuf `-ac` style soft-404 calibration: what a page that does NOT exist
 * looks like on this site, so a catch-all page never counts as a route hit.
 */
@Serializable
data class SoftNotFoundBaseline(
    val probedUrls: List<String> = emptyList(),
    val status: Int = 0,
    val medianBytes: Int = 0,
    val title: String = "",
    /** True when the site answers unknown paths with a 200 page (a catch-all). */
    val catchAll: Boolean = false,
    val evidence: String = ""
)

/** An Arjun-style parameter that demonstrably changes the response. */
@Serializable
data class ActiveParam(
    val path: String,
    val param: String,
    val evidence: String
)

/** Everything the heavy recon techniques recorded for one site. */
@Serializable
data class ReconMap(
    val techFingerprint: List<String> = emptyList(),
    val stackLabels: List<String> = emptyList(),
    val crawledPages: List<String> = emptyList(),
    val crawlDepth: Int = 0,
    val minedParameters: Map<String, Int> = emptyMap(),
    val searchParameters: List<String> = emptyList(),
    val activeParams: List<ActiveParam> = emptyList(),
    val jsEndpoints: List<String> = emptyList(),
    val sourceMapsRead: Int = 0,
    val archiveUrlCount: Int = 0,
    val archiveSamples: List<String> = emptyList(),
    val commonCrawlCount: Int = 0,
    val wordlistProbed: Int = 0,
    val wordlistHits: List<WordlistHit> = emptyList(),
    val softNotFound: SoftNotFoundBaseline = SoftNotFoundBaseline(),
    val suggestEndpoints: List<String> = emptyList(),
    val hostedSearch: List<StackSignatures.HostedSearch> = emptyList(),
    val capturedRequests: List<LiveSearchCapture.CapturedRequest> = emptyList(),
    val liveCaptureDetail: String = "",
    val liveCaptureRequests: List<LiveSearchCapture.SearchRequestTemplate> = emptyList(),
    val definitionMatch: IndexerDefinitionLibrary.DefinitionMatch? = null,
    val triage: List<EndpointTriage> = emptyList(),
    val checks: List<ReconCheck> = emptyList(),
    /** Every candidate considered, in rank order, with why it won or lost. */
    val candidateLedger: List<CandidateLedgerEntry> = emptyList()
)

/** One candidate's place in the ranking and what became of it. */
@Serializable
data class CandidateLedgerEntry(
    val rank: Int,
    val source: String,
    val template: String,
    val outcome: String = "not reached",
    val reason: String = ""
)

/**
 * The Analyzer's heavy recon techniques (spec §1), implemented on-device in
 * Kotlin over [HttpTransport] (native TLS) and the embedded WebView:
 *
 *  - Deep crawl (Katana / Burp crawler): bounded same-host BFS over the site's
 *    own link graph, depth 3 and up to [MAX_CRAWL_PAGES] pages, honouring a
 *    sane robots crawl-delay, with JS-rendered routes read through the WebView.
 *  - Route discovery (ffuf / gobuster / dirsearch): the ~250-route
 *    [ReconWordlist], prioritized by the [StackSignatures] fingerprint, probed
 *    at concurrency <= [MAX_CONCURRENCY] and calibrated against a soft-404
 *    baseline so a catch-all page is never counted as a hit.
 *  - Parameter discovery (Arjun / ParamSpider): names mined from URLs, forms,
 *    scripts and the archive, PLUS active discovery — candidate parameter names
 *    are actually sent and kept only when the response changes in a
 *    query-dependent way (real term vs nonsense).
 *  - JS endpoint mining (LinkFinder / subjs): inline and same-host scripts, and
 *    their source maps when the site publishes them.
 *  - Fingerprinting (WhatWeb / Wappalyzer): see [StackSignatures].
 *  - Historical URLs (gau / waybackurls): Wayback CDX plus the CommonCrawl
 *    index, filtered to same-host search-shaped URLs.
 *  - Live probing / triage (httpx): status, redirect, content type, size,
 *    timing and tech for every endpoint fetched.
 *
 * Discipline: same host only (the archive, CommonCrawl and the indexer
 * definition repositories are the named exceptions, and they are asked ABOUT
 * the host); a minimum gap between requests; concurrency capped; and hard URL,
 * wordlist and time budgets. Nothing here saves anything — it produces
 * candidates and evidence for the map → verify → save pipeline.
 */
class ReconToolkit(
    private val base: String,
    private val baseHost: String,
    private val deadlineAt: Long
) {
    private val triage: MutableList<EndpointTriage> = java.util.Collections.synchronizedList(mutableListOf())
    private val checks: MutableList<ReconCheck> = java.util.Collections.synchronizedList(mutableListOf())
    @Volatile private var lastRequestAt = 0L
    private val gate = Semaphore(MAX_CONCURRENCY)
    private val rateLock = Any()

    /** Crawl-delay the site asks for in robots.txt, clamped to something sane. */
    @Volatile var crawlDelayMs: Long = MIN_GAP_MS
        private set

    fun timeLeft(): Long = deadlineAt - System.currentTimeMillis()

    fun record(check: ReconCheck) { checks += check }

    fun isSameHost(url: String): Boolean =
        runCatching { URI(url).host.equals(baseHost, ignoreCase = true) }.getOrDefault(false)

    /** Apply a robots.txt Crawl-delay, clamped to [MIN_GAP_MS]..[MAX_GAP_MS]. */
    fun applyCrawlDelay(seconds: Double?) {
        if (seconds == null || seconds <= 0) return
        crawlDelayMs = (seconds * 1000).toLong().coerceIn(MIN_GAP_MS, MAX_GAP_MS)
        record(ReconCheck("robots-crawl-delay", "ok",
            "robots.txt asks for a ${seconds}s crawl delay; recon paced at ${crawlDelayMs} ms between requests"))
    }

    /** Rate-limited, bounded, recorded fetch. */
    fun fetch(
        url: String,
        timeoutMs: Int,
        purpose: String,
        acceptJson: Boolean = false,
        method: String = "GET",
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): TransportResponse? {
        if (timeLeft() < 500) return null
        synchronized(rateLock) {
            val gap = System.currentTimeMillis() - lastRequestAt
            if (gap < crawlDelayMs) Thread.sleep(crawlDelayMs - gap)
            lastRequestAt = System.currentTimeMillis()
        }
        val bounded = timeoutMs.coerceAtMost(timeLeft().toInt().coerceAtLeast(1_000))
        val response = HttpTransport.fetchBlocking(
            TransportRequest(
                url = url,
                method = method,
                headers = HttpTransport.defaultHeaders(acceptJson) + extraHeaders,
                body = body,
                timeoutMs = bounded
            )
        )
        if (triage.size < MAX_TRIAGE_RECORDS * 2) {
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
        }
        return response
    }

    /** [fetch] off the calling thread, under the concurrency cap. */
    private suspend fun fetchAsync(
        url: String,
        timeoutMs: Int,
        purpose: String,
        acceptJson: Boolean = false
    ): TransportResponse? = gate.withPermit {
        withContext(Dispatchers.IO) { fetch(url, timeoutMs, purpose, acceptJson) }
    }

    // ── Technology fingerprinting ──────────────────────────────────────

    fun fingerprint(homepage: TransportResponse?, doc: Document?): List<String> {
        val signals = fingerprintSignals(homepage, doc)
        record(ReconCheck("tech-fingerprint", if (signals.isEmpty()) "empty" else "ok",
            if (signals.isEmpty()) "no stack signature in headers, cookies or markup"
            else signals.joinToString(", ") { StackSignatures.labelFor(it) }))
        return signals
    }

    private fun fingerprintSignals(response: TransportResponse?, doc: Document?): List<String> =
        StackSignatures.detect(
            headerLookup = { name -> response?.header(name) },
            cookieNames = response?.cookies()?.keys.orEmpty(),
            html = response?.body ?: doc?.outerHtml().orEmpty(),
            doc = doc
        )

    // ── Deep crawl (bounded same-host BFS) ─────────────────────────────

    data class CrawlResult(
        val pages: List<String>,
        val links: Set<String>,
        val docs: List<Document>,
        val depthReached: Int = 0
    )

    /**
     * Katana-style bounded BFS over the site's own link graph (same host only),
     * to [maxDepth] and [maxPages] pages. A crawled page that is a JavaScript
     * app shell is re-read through the embedded WebView so JS-rendered routes
     * and their links are mapped too.
     */
    suspend fun crawl(start: Document?, maxPages: Int = MAX_CRAWL_PAGES, maxDepth: Int = MAX_CRAWL_DEPTH): CrawlResult {
        if (start == null) {
            record(ReconCheck("deep-crawl", "skipped", "no homepage document to start from"))
            return CrawlResult(emptyList(), emptySet(), emptyList())
        }
        val links = linkedSetOf<String>()
        val visited = linkedSetOf(normalize(start.location().ifBlank { base }))
        val docs = mutableListOf<Document>()
        var frontier = linksOf(start).also { links += it }
        var depth = 1
        var depthReached = 0
        var renderedPages = 0
        val renderedUrls = mutableListOf<String>()

        while (depth <= maxDepth && frontier.isNotEmpty() && visited.size < maxPages + 1 && timeLeft() > CRAWL_RESERVE_MS) {
            val ordered = frontier.filter { normalize(it) !in visited }
                .sortedBy { url -> if (CRAWL_PRIORITY.any { url.lowercase().contains(it) }) 0 else 1 }
            if (ordered.isEmpty()) break
            depthReached = depth
            val next = linkedSetOf<String>()
            // Fetch this depth's pages in bounded-concurrency batches.
            val batchLimit = (maxPages + 1 - visited.size).coerceAtLeast(0)
            val targets = ordered.take(batchLimit)
            targets.forEach { visited += normalize(it) }
            val fetched = coroutineScope {
                targets.chunked(MAX_CONCURRENCY).flatMap { chunk ->
                    if (timeLeft() < CRAWL_RESERVE_MS) return@flatMap emptyList<Pair<String, TransportResponse?>>()
                    chunk.map { url -> async { url to fetchAsync(url, CRAWL_TIMEOUT_MS, "crawl") } }.awaitAll()
                }
            }
            for ((url, response) in fetched) {
                if (response == null || !response.isHttpOk) continue
                val type = response.contentType().orEmpty()
                if (type.isNotBlank() && !type.contains("html", true)) continue
                var doc = runCatching { Jsoup.parse(response.body, response.finalUrl.ifBlank { url }) }.getOrNull() ?: continue
                if (renderedPages < MAX_RENDERED_CRAWL_PAGES && timeLeft() > RENDERED_CRAWL_TIMEOUT_MS + 6_000 &&
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
                        Log.i(TAG, "crawl FALLBACK webview-rendered JS page $url")
                    }
                }
                docs += doc
                val found = linksOf(doc)
                links += found
                next += found
            }
            ReconProgress.detail("crawled ${docs.size} pages, depth $depth of $maxDepth, ${links.size} links mapped")
            frontier = next
            depth++
        }
        record(ReconCheck("deep-crawl", if (docs.isEmpty()) "empty" else "ok",
            "crawled ${docs.size} same-host pages (depth reached $depthReached of $maxDepth, cap $maxPages), " +
                "${links.size} same-host links mapped, paced at ${crawlDelayMs} ms with concurrency $MAX_CONCURRENCY"))
        record(ReconCheck("js-rendered-crawl", if (renderedUrls.isEmpty()) "skipped" else "ok",
            if (renderedUrls.isEmpty()) "no crawled page needed JavaScript rendering"
            else "rendered ${renderedUrls.size} JavaScript-only pages in the WebView: ${renderedUrls.take(3).joinToString()}"))
        return CrawlResult(visited.drop(1).toList(), links, docs, depthReached)
    }

    private fun linksOf(doc: Document): Set<String> = doc.select("a[href]")
        .map { it.absUrl("href").substringBefore('#') }
        .filter { it.startsWith("http") && isSameHost(it) && !STATIC_ASSET.containsMatchIn(it) }
        .toSet()

    private fun normalize(url: String) = url.substringBefore('#').trimEnd('/').lowercase()

    // ── Soft-404 calibration (ffuf -ac) ───────────────────────────────

    /**
     * Fetch two random paths that cannot exist and record what "not found"
     * looks like here. Everything the wordlist finds is compared against this,
     * so a site that answers every unknown path with its homepage produces no
     * false hits.
     */
    fun calibrateSoftNotFound(): SoftNotFoundBaseline {
        val probes = listOf(
            "$base/aggx-${randomToken()}",
            "$base/aggx-${randomToken()}/${randomToken()}"
        )
        val seen = mutableListOf<TransportResponse>()
        for (url in probes) {
            if (timeLeft() < 4_000) break
            val response = fetch(url, CALIBRATION_TIMEOUT_MS, "soft-404-calibration") ?: continue
            seen += response
        }
        if (seen.isEmpty()) {
            val baseline = SoftNotFoundBaseline(evidence = "no calibration response; wordlist hits judged on status and echo alone")
            record(ReconCheck("soft-404-calibration", "failed", baseline.evidence))
            return baseline
        }
        val status = seen.first().statusCode
        val bytes = seen.map { it.body.length }.sorted().let { it[it.size / 2] }
        val title = runCatching { Jsoup.parse(seen.first().body).title().trim() }.getOrDefault("")
        val catchAll = seen.all { it.statusCode in 200..299 }
        val baseline = SoftNotFoundBaseline(
            probedUrls = probes.take(seen.size),
            status = status,
            medianBytes = bytes,
            title = title.take(120),
            catchAll = catchAll,
            evidence = if (catchAll)
                "the site answers unknown paths with HTTP $status (~$bytes bytes, title \"${title.take(60)}\") — " +
                    "a catch-all, so route hits must differ from this baseline in size, title and query echo"
            else "unknown paths answer HTTP $status (~$bytes bytes) — route hits are judged against that"
        )
        record(ReconCheck("soft-404-calibration", "ok", baseline.evidence))
        return baseline
    }

    private fun randomToken(): String =
        (1..12).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    // ── Historical URL mining (Wayback CDX + CommonCrawl) ──────────────

    data class ArchiveResult(val urls: List<String>, val waybackCount: Int, val commonCrawlCount: Int)

    fun mineArchive(): ArchiveResult {
        if (timeLeft() < 6_000) {
            record(ReconCheck("archive-url-mining", "skipped", "time budget exhausted"))
            return ArchiveResult(emptyList(), 0, 0)
        }
        val found = linkedSetOf<String>()
        var waybackCount = 0
        var commonCrawlCount = 0

        val cdx = "https://web.archive.org/cdx/search/cdx?url=" +
            URLEncoder.encode("$baseHost/*", "UTF-8") +
            "&output=json&fl=original&collapse=urlkey&filter=statuscode:200&limit=$ARCHIVE_LIMIT"
        val wayback = fetch(cdx, ARCHIVE_TIMEOUT_MS, "archive-cdx", acceptJson = true)
        if (wayback != null && wayback.isHttpOk) {
            val urls = runCatching {
                (json.parseToJsonElement(wayback.body) as JsonArray)
                    .drop(1)
                    .mapNotNull { row -> ((row as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull }
            }.getOrDefault(emptyList())
                .map { if (it.startsWith("http")) it else "https://$it" }
                .filter { isSameHost(it) && !STATIC_ASSET.containsMatchIn(it) }
            waybackCount = urls.size
            found += urls
        }

        // CommonCrawl's index is a second, independent view of the site's past
        // URLs; it often holds parameterized URLs the Wayback collapse drops.
        if (timeLeft() > 10_000) {
            val cc = "https://index.commoncrawl.org/CC-MAIN-2024-33-index?url=" +
                URLEncoder.encode("$baseHost/*", "UTF-8") + "&output=json&limit=$COMMONCRAWL_LIMIT"
            val response = fetch(cc, ARCHIVE_TIMEOUT_MS, "commoncrawl-index", acceptJson = true)
            if (response != null && response.isHttpOk) {
                val urls = response.body.lineSequence()
                    .mapNotNull { line -> CC_URL.find(line)?.groupValues?.get(1) }
                    .filter { isSameHost(it) && !STATIC_ASSET.containsMatchIn(it) }
                    .toList()
                commonCrawlCount = urls.size
                found += urls
            }
        }

        val urls = found.distinct()
        record(ReconCheck("archive-url-mining", if (urls.isEmpty()) "empty" else "ok",
            "${urls.size} archived same-host URLs mined ($waybackCount from the Wayback CDX index, " +
                "$commonCrawlCount from the CommonCrawl index); " +
                "${urls.count { it.contains('?') }} carry query parameters"))
        return ArchiveResult(urls, waybackCount, commonCrawlCount)
    }

    // ── Hidden parameter discovery (passive) ──────────────────────────

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
            JS_PARAM_LITERAL.findAll(text).forEach { add(it.groupValues[1]) }
        }
        val top = counts.entries.sortedByDescending { it.value }.take(MAX_PARAMS).associate { it.key to it.value }
        val search = top.keys.filter { it.lowercase() in SEARCH_PARAM_NAMES }
        record(ReconCheck("parameter-discovery", if (top.isEmpty()) "empty" else "ok",
            "${counts.size} parameter names mined from URLs, forms, scripts and the archive" +
                if (search.isNotEmpty()) "; search-like: ${search.joinToString()}" else ""))
        return top
    }

    // ── Active parameter discovery (Arjun) ────────────────────────────

    /**
     * On each candidate search path, send the real probe term under a batch of
     * candidate parameter names and keep a name only when the response is
     * query-dependent: the term comes back in the body AND the response
     * differs materially from the same path asked with nonsense.
     */
    suspend fun discoverActiveParams(
        paths: List<String>,
        probeQuery: String?,
        baseline: SoftNotFoundBaseline
    ): List<ActiveParam> {
        if (probeQuery.isNullOrBlank() || paths.isEmpty()) {
            record(ReconCheck("active-parameter-discovery", "skipped",
                if (probeQuery.isNullOrBlank()) "no probe term from the site's own content" else "no candidate path to test"))
            return emptyList()
        }
        val found = mutableListOf<ActiveParam>()
        val encoded = URLEncoder.encode(probeQuery, "UTF-8")
        var tried = 0
        outer@ for (path in paths.take(MAX_ACTIVE_PARAM_PATHS)) {
            for (batch in ACTIVE_PARAM_NAMES.chunked(MAX_CONCURRENCY)) {
                if (timeLeft() < ACTIVE_PARAM_RESERVE_MS || found.size >= MAX_ACTIVE_PARAMS) break@outer
                val results = coroutineScope {
                    batch.map { param ->
                        async {
                            val sep = if (path.contains('?')) "&" else "?"
                            val real = fetchAsync("$path$sep$param=$encoded", ACTIVE_PARAM_TIMEOUT_MS, "active-param")
                            Triple(param, real, sep)
                        }
                    }.awaitAll()
                }
                for ((param, real, sep) in results) {
                    tried++
                    if (real == null || !real.isHttpOk) continue
                    if (!real.body.contains(probeQuery, ignoreCase = true)) continue
                    // Query-dependent? Ask the same path with nonsense and
                    // require the answer to differ materially.
                    val control = fetchAsync(
                        "$path$sep$param=zqxv123nonexistent", ACTIVE_PARAM_TIMEOUT_MS, "active-param-control"
                    ) ?: continue
                    val delta = abs(real.body.length - control.body.length)
                    val relative = delta.toFloat() / real.body.length.coerceAtLeast(1)
                    if (relative < MIN_PARAM_DELTA) continue
                    found += ActiveParam(
                        path = path,
                        param = param,
                        evidence = "sending ?$param=\"$probeQuery\" to ${path.take(120)} echoed the term and returned a " +
                            "page ${delta} bytes (${(relative * 100).toInt()}%) different from the same request with nonsense"
                    )
                    if (found.size >= MAX_ACTIVE_PARAMS) break@outer
                }
            }
        }
        record(ReconCheck("active-parameter-discovery", if (found.isEmpty()) "empty" else "ok",
            "$tried parameter probes across ${paths.take(MAX_ACTIVE_PARAM_PATHS).size} candidate paths; " +
                "${found.size} parameter(s) proved query-dependent" +
                if (found.isNotEmpty()) ": " + found.joinToString { it.param } else ""))
        return found
    }

    // ── JS endpoint mining (LinkFinder / subjs, with source maps) ─────

    data class ScriptHarvest(val texts: List<String>, val sourceMapsRead: Int)

    fun scriptTexts(doc: Document?, extraDocs: List<Document> = emptyList()): ScriptHarvest {
        if (doc == null && extraDocs.isEmpty()) return ScriptHarvest(emptyList(), 0)
        val texts = mutableListOf<String>()
        val srcs = linkedSetOf<String>()
        for (d in listOfNotNull(doc) + extraDocs) {
            d.select("script:not([src])").forEach { s -> s.data().takeIf { it.isNotBlank() }?.let { texts += it } }
            d.select("script[src]").map { it.absUrl("src") }
                .filter { it.startsWith("http") && isSameHost(it) }
                .forEach { srcs += it }
        }
        var sourceMaps = 0
        for (src in srcs.take(MAX_SCRIPT_FILES)) {
            if (timeLeft() < 4_000) break
            val r = fetch(src, SCRIPT_TIMEOUT_MS, "script") ?: continue
            if (!r.isHttpOk) continue
            val bodyText = r.body.take(MAX_SCRIPT_BYTES)
            texts += bodyText
            // A published source map carries the original, unminified sources:
            // endpoint strings survive there that minification mangled.
            if (sourceMaps < MAX_SOURCE_MAPS && timeLeft() > 8_000) {
                val mapName = SOURCE_MAP.find(bodyText.takeLast(2_000))?.groupValues?.get(1)?.trim()
                if (!mapName.isNullOrBlank() && !mapName.startsWith("data:")) {
                    val mapUrl = runCatching { URI(src).resolve(mapName).toString() }.getOrNull()
                    if (mapUrl != null && isSameHost(mapUrl)) {
                        val mapResponse = fetch(mapUrl, SCRIPT_TIMEOUT_MS, "source-map", acceptJson = true)
                        if (mapResponse != null && mapResponse.isHttpOk) {
                            texts += mapResponse.body.take(MAX_SCRIPT_BYTES)
                            sourceMaps++
                        }
                    }
                }
            }
        }
        return ScriptHarvest(texts, sourceMaps)
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
            "${endpoints.size} same-host endpoint paths extracted from ${scripts.size} scripts and source maps"))
        return endpoints.toList()
    }

    /** Hosted search services whose public credentials the page published. */
    fun mineHostedSearch(scripts: List<String>): List<StackSignatures.HostedSearch> {
        val found = runCatching { StackSignatures.extractHostedSearch(scripts) }.getOrDefault(emptyList())
        record(ReconCheck("hosted-search-mining", if (found.isEmpty()) "empty" else "ok",
            if (found.isEmpty()) "no Algolia / Typesense / Meilisearch / Searchanise / Doofinder / Klevu / Swiftype credentials in the site's scripts"
            else found.joinToString("; ") { it.evidence }))
        return found
    }

    /** httpx-style triage of mined endpoints (bounded, concurrent). */
    suspend fun triageEndpoints(endpoints: List<String>) {
        val targets = endpoints.filter { !it.contains("{") }.take(MAX_TRIAGE)
        if (targets.isEmpty()) {
            record(ReconCheck("httpx-triage", "skipped", "no mined endpoint to triage"))
            return
        }
        var ok = 0
        for (chunk in targets.chunked(MAX_CONCURRENCY)) {
            if (timeLeft() < 4_000) break
            val results = coroutineScope {
                chunk.map { url -> async { fetchAsync(url, TRIAGE_TIMEOUT_MS, "triage", acceptJson = true) } }.awaitAll()
            }
            ok += results.count { it != null && it.isHttpOk }
        }
        record(ReconCheck("httpx-triage", "ok",
            "${targets.size} mined endpoints probed, $ok answered 2xx/3xx; ${triage.size} endpoints triaged in total"))
    }

    // ── Wordlist route discovery (ffuf / gobuster / dirsearch) ────────

    data class WordlistOutcome(
        val hits: List<WordlistHit>,
        val suggestEndpoints: List<String>,
        val probed: Int
    )

    /**
     * Probe the fingerprint-prioritized wordlist with the real probe term, at
     * concurrency <= [MAX_CONCURRENCY], and keep only routes that beat the
     * soft-404 baseline: 2xx, same host, and either a JSON payload or the probe
     * term echoed on a page that differs materially from the baseline.
     */
    suspend fun probeWordlist(
        probeQuery: String?,
        tech: List<String>,
        baseline: SoftNotFoundBaseline
    ): WordlistOutcome {
        if (probeQuery.isNullOrBlank()) {
            record(ReconCheck("wordlist-route-discovery", "skipped", "no probe term from the site's own content"))
            return WordlistOutcome(emptyList(), emptyList(), 0)
        }
        // Fingerprint-prioritized first, then the generic list.
        val routes = linkedSetOf<String>()
        routes += StackSignatures.routesFor(tech)
        routes += ReconWordlist.all()
        val encoded = URLEncoder.encode(probeQuery, "UTF-8")
        val hits: MutableList<WordlistHit> = java.util.Collections.synchronizedList(mutableListOf())
        var probed = 0

        for (chunk in routes.take(MAX_WORDLIST).chunked(MAX_CONCURRENCY)) {
            if (timeLeft() < WORDLIST_RESERVE_MS) break
            probed += chunk.size
            val results = coroutineScope {
                chunk.map { route ->
                    async {
                        val template = base.trimEnd('/') + route
                        val url = template.replace("{query}", encoded).replace("{page}", "1").replace("{page0}", "0")
                        route to (fetchAsync(url, WORDLIST_TIMEOUT_MS, "wordlist", ReconWordlist.isJsonRoute(route))
                            ?.let { url to it })
                    }
                }.awaitAll()
            }
            for ((route, pair) in results) {
                val (url, response) = pair ?: continue
                if (!response.isHttpOk || !isSameHost(response.finalUrl.ifBlank { url })) continue
                val body = response.body
                val isJson = body.trimStart().let { it.startsWith("{") || it.startsWith("[") } &&
                    body.contains(probeQuery, ignoreCase = true)
                val echoes = body.contains(probeQuery, ignoreCase = true)
                if (!isJson && !echoes) continue
                // Soft-404 calibration: a catch-all page that merely echoes a
                // term reflected into its own search box is not a hit.
                if (!isJson && baseline.catchAll) {
                    val delta = abs(body.length - baseline.medianBytes)
                    val relative = delta.toFloat() / body.length.coerceAtLeast(1)
                    val title = runCatching { Jsoup.parse(body).title().trim() }.getOrDefault("")
                    if (relative < MIN_BASELINE_DELTA && title.equals(baseline.title, ignoreCase = true)) {
                        continue
                    }
                }
                hits += WordlistHit(
                    template = base.trimEnd('/') + route,
                    status = response.statusCode,
                    evidence = "HTTP ${response.statusCode}, ${body.length} bytes, " +
                        (if (isJson) "JSON payload carrying the probe term" else "probe term echoed") +
                        (if (baseline.catchAll) "; differs from the site's catch-all baseline (~${baseline.medianBytes} bytes)" else ""),
                    isJson = isJson
                )
            }
            ReconProgress.detail("probed $probed of ${routes.take(MAX_WORDLIST).size} routes, ${hits.size} live hits")
        }

        // Suggest/autocomplete endpoints: probed and recorded, never promoted.
        val suggest = mutableListOf<String>()
        for (route in StackSignatures.suggestRoutesFor(tech) + ReconWordlist.SUGGEST) {
            if (timeLeft() < SUGGEST_RESERVE_MS || suggest.size >= MAX_SUGGEST_HITS) break
            val url = (base.trimEnd('/') + route).replace("{query}", encoded).replace("{page}", "1")
            val response = fetchAsync(url, WORDLIST_TIMEOUT_MS, "suggest", acceptJson = true) ?: continue
            if (response.isHttpOk && response.body.contains(probeQuery, ignoreCase = true)) {
                suggest += base.trimEnd('/') + route
            }
        }

        record(ReconCheck("wordlist-route-discovery", if (hits.isEmpty()) "empty" else "ok",
            "$probed same-host routes probed with \"$probeQuery\" at concurrency $MAX_CONCURRENCY " +
                "(fingerprint-prioritized, ${ReconWordlist.SIZE}-entry list, soft-404 calibrated); " +
                "${hits.size} verified live hits"))
        record(ReconCheck("suggest-endpoint-discovery", if (suggest.isEmpty()) "empty" else "ok",
            if (suggest.isEmpty()) "no autocomplete endpoint answered the probe term"
            else "${suggest.size} autocomplete endpoint(s) found and recorded separately (never used as the search route): " +
                suggest.joinToString()))
        return WordlistOutcome(hits.toList(), suggest, probed)
    }

    fun buildMap(
        tech: List<String>,
        crawl: CrawlResult,
        params: Map<String, Int>,
        activeParams: List<ActiveParam>,
        jsEndpoints: List<String>,
        sourceMapsRead: Int,
        archive: ArchiveResult,
        wordlist: WordlistOutcome,
        baseline: SoftNotFoundBaseline,
        hostedSearch: List<StackSignatures.HostedSearch>,
        capture: LiveSearchCapture.CaptureResult?,
        definition: IndexerDefinitionLibrary.DefinitionMatch?,
        ledger: List<CandidateLedgerEntry>
    ) = ReconMap(
        techFingerprint = tech,
        stackLabels = tech.map { StackSignatures.labelFor(it) },
        crawledPages = crawl.pages.take(60),
        crawlDepth = crawl.depthReached,
        minedParameters = params,
        searchParameters = params.keys.filter { it.lowercase() in SEARCH_PARAM_NAMES },
        activeParams = activeParams,
        jsEndpoints = jsEndpoints.take(60),
        sourceMapsRead = sourceMapsRead,
        archiveUrlCount = archive.urls.size,
        archiveSamples = archive.urls.filter { it.contains('?') }.take(25),
        commonCrawlCount = archive.commonCrawlCount,
        wordlistProbed = wordlist.probed,
        wordlistHits = wordlist.hits,
        softNotFound = baseline,
        suggestEndpoints = wordlist.suggestEndpoints,
        hostedSearch = hostedSearch,
        capturedRequests = capture?.allRequests?.take(MAX_CAPTURED_RECORDS).orEmpty(),
        liveCaptureDetail = capture?.detail.orEmpty(),
        liveCaptureRequests = capture?.searchRequests.orEmpty(),
        definitionMatch = definition,
        triage = triage.toList().takeLast(MAX_TRIAGE_RECORDS),
        checks = checks.toList(),
        candidateLedger = ledger
    ).also {
        Log.i(TAG, "recon map $baseHost: tech=$tech crawled=${crawl.pages.size} params=${params.size} " +
            "active=${activeParams.size} js=${jsEndpoints.size} archive=${archive.urls.size} " +
            "wordlistHits=${wordlist.hits.size} captured=${capture?.allRequests?.size ?: 0} " +
            "definition=${definition?.indexerId ?: "none"} triage=${triage.size}")
    }

    companion object {
        private const val TAG = "ReconToolkit"
        private const val MIN_GAP_MS = 120L
        private const val MAX_GAP_MS = 2_000L
        /** Requests in flight at once, across every technique (spec: <= 3). */
        const val MAX_CONCURRENCY = 3

        private const val MAX_CRAWL_PAGES = 60
        private const val MAX_CRAWL_DEPTH = 3
        private const val CRAWL_RESERVE_MS = 6_000
        private const val MAX_RENDERED_CRAWL_PAGES = 4
        private const val RENDERED_CRAWL_TIMEOUT_MS = 10_000L
        private const val CRAWL_TIMEOUT_MS = 8_000

        private const val ARCHIVE_TIMEOUT_MS = 10_000
        private const val ARCHIVE_LIMIT = 800
        private const val COMMONCRAWL_LIMIT = 400
        private const val CALIBRATION_TIMEOUT_MS = 6_000
        private const val SCRIPT_TIMEOUT_MS = 8_000
        private const val TRIAGE_TIMEOUT_MS = 6_000
        private const val WORDLIST_TIMEOUT_MS = 7_000
        private const val WORDLIST_RESERVE_MS = 8_000
        private const val SUGGEST_RESERVE_MS = 5_000
        private const val ACTIVE_PARAM_TIMEOUT_MS = 6_000
        private const val ACTIVE_PARAM_RESERVE_MS = 8_000

        private const val MAX_SCRIPT_FILES = 8
        private const val MAX_SCRIPT_BYTES = 1_500_000
        private const val MAX_SOURCE_MAPS = 2
        private const val MAX_JS_ENDPOINTS = 120
        private const val MAX_TRIAGE = 14
        private const val MAX_TRIAGE_RECORDS = 120
        private const val MAX_CAPTURED_RECORDS = 60
        /** The whole curated wordlist is in budget now, not a 14-route sample. */
        private const val MAX_WORDLIST = 260
        private const val MAX_SUGGEST_HITS = 4
        private const val MAX_PARAMS = 80
        private const val MAX_ACTIVE_PARAMS = 6
        private const val MAX_ACTIVE_PARAM_PATHS = 3
        /** How different a response must be to count as query-dependent. */
        private const val MIN_PARAM_DELTA = 0.02f
        private const val MIN_BASELINE_DELTA = 0.05f

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private val STATIC_ASSET = Regex(
            "\\.(png|jpe?g|gif|webp|svg|ico|css|woff2?|ttf|eot|mp4|webm|mp3|pdf|zip)(\\?|$)",
            RegexOption.IGNORE_CASE
        )
        private val PARAM_NAME = Regex("^[A-Za-z_][A-Za-z0-9_\\-\\[\\]]*$")
        private val SCRIPT_PARAM = Regex("[?&]([A-Za-z_][A-Za-z0-9_]{0,30})=")
        private val JS_PARAM_LITERAL = Regex("""["'`]([A-Za-z_][A-Za-z0-9_]{1,24})["'`]\s*:\s*(?:query|q|term|keyword|search)\b""")
        private val SOURCE_MAP = Regex("//# sourceMappingURL=(\\S+)")
        private val CC_URL = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
        private val LINKFINDER = Regex(
            "[\"'`]((?:https?:)?//[^\"'`\\s]{3,200}|/(?:api|ajax|v\\d|graphql|search|wp-json|rest|json|feeds?|rss)[^\"'`\\s]{0,200}|/[A-Za-z0-9_\\-/]{1,120}\\.(?:json|php|aspx?|jsp|do|action)(?:\\?[^\"'`\\s]{0,200})?)[\"'`]"
        )
        private val CRAWL_PRIORITY = listOf(
            "search", "find", "browse", "results", "latest", "videos", "movies", "list",
            "torrents", "products", "catalog", "shop", "forum", "category", "archive"
        )
        val SEARCH_PARAM_NAMES = setOf(
            "q", "s", "query", "search", "search_query", "keyword", "keywords", "k", "kw",
            "term", "text", "searchword", "keys", "find", "searchstr", "nm", "name", "title", "wd"
        )

        /** Arjun's parameter list, trimmed to the names a search route really uses. */
        val ACTIVE_PARAM_NAMES = listOf(
            "q", "s", "query", "search", "keyword", "keywords", "term", "text", "k", "kw",
            "search_query", "searchword", "searchstr", "keys", "find", "name", "title",
            "wd", "word", "p", "filter", "nm", "sq", "qs"
        )
    }
}
