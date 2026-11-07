package com.aggregatorx.app.engine.analyzer

import com.aggregatorx.app.engine.network.HttpTransport

import android.util.Log
import com.aggregatorx.app.engine.network.CloudflareBypassEngine
import com.aggregatorx.app.engine.network.TlsClient
import com.aggregatorx.app.engine.network.TlsRequest
import com.aggregatorx.app.engine.scraper.LiveSearchCapture
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Site recon mapper for the Analyzer — the "red team" pass that runs when a
 * new provider is added.
 *
 * Instead of guessing how a site might be searched, this maps how it
 * actually is, from the site's own data only:
 *
 *  1. `robots.txt` is read for the sitemaps the site itself publishes, and
 *     those sitemaps are read for the site's real URL inventory.
 *  2. The homepage's real links and its real search form (action, method,
 *     the actual query parameter name, hidden/fixed fields, page field)
 *     are harvested.
 *  3. The existing [EndpointDiscoveryEngine] recon (inline-JS analysis,
 *     CMS/API probing, robots/sitemap mining) contributes the search and
 *     API endpoints it verified live.
 *  4. Candidate JSON endpoints are probed with a real query; when the
 *     response is JSON, the result array's location and its title / url /
 *     thumbnail field names are derived from the actual payload.
 *
 * From the real URL inventory we also derive the pagination style the
 * site itself uses (page parameter name, or /page/N/ paths), a probe
 * query taken from the site's own content words (never an invented term),
 * and the site's measured response timing, which becomes the saved
 * config's timeout — so a slow site gets a sane budget and a fast site
 * is not waited on pointlessly.
 *
 * Every fetch is bounded and same-host. This maps one site at add time;
 * it does not crawl the web, and [mapSite] never throws: on total
 * failure it returns an empty recon and the caller rejects the config
 * with a specific reason.
 */
@Singleton
class SiteReconEngine @Inject constructor(
    private val endpointDiscoveryEngine: EndpointDiscoveryEngine,
    private val cloudflareBypassEngine: CloudflareBypassEngine
) {

    /** One timed fetch result; [body] is null when the fetch failed. */
    private data class TimedFetch(val body: String?, val finalUrl: String, val statusCode: Int)

    suspend fun mapSite(baseUrl: String, homepageHtml: String?, providerId: String = ""): SiteRecon = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val baseHost = runCatching { URL(base).host }.getOrNull().orEmpty()
        val timings = mutableListOf<Long>()
        val startedAt = System.currentTimeMillis()
        val toolkit = ReconToolkit(base, baseHost, startedAt + RECON_BUDGET_MS)
        val sitemapsRead = mutableListOf<String>()
        ReconProgress.start(providerId.ifBlank { baseHost }, RECON_BUDGET_MS)

        fun timedFetch(url: String, timeoutMs: Int, acceptJson: Boolean = false): TimedFetch {
            val t0 = System.currentTimeMillis()
            val response = runCatching {
                HttpTransport.executeTls(
                    TlsRequest(
                        url = url,
                        headers = buildMap {
                            put("User-Agent", EngineUtils.DEFAULT_USER_AGENT)
                            if (acceptJson) put("Accept", "application/json")
                        },
                        timeoutMs = timeoutMs
                    )
                )
            }.getOrNull()
            timings += System.currentTimeMillis() - t0
            return if (response != null && response.error == null && response.statusCode in 200..399) {
                TimedFetch(response.body, response.finalUrl.ifBlank { url }, response.statusCode)
            } else {
                TimedFetch(null, url, response?.statusCode ?: 0)
            }
        }

        val urls = linkedSetOf<String>()

        // ── 1. robots.txt sitemaps, then the sitemaps themselves ──────
        val sitemapLocations = mutableListOf<String>()
        val robots = timedFetch("$base/robots.txt", ROBOTS_TIMEOUT_MS)
        robots.body?.let { text ->
            text.lineSequence()
                .map { it.trim() }
                .filter { it.lowercase().startsWith("sitemap:") }
                .map { it.substringAfter(':').trim() }
                .filter { it.startsWith("http") }
                .forEach { sitemapLocations += it }
        }
        sitemapLocations += DEFAULT_SITEMAP_PATHS.map { "$base$it" }

        // Crawl-rule paths are also the site's own route data: a Disallow
        // for "/search/" or "/?s=" reveals the search route's shape.
        robots.body?.let { text ->
            text.lineSequence()
                .map { it.trim() }
                .filter {
                    it.lowercase().startsWith("disallow:") || it.lowercase().startsWith("allow:")
                }
                .map { it.substringAfter(':').trim() }
                .filter { it.startsWith("/") && !it.contains('*') && !it.endsWith("$") }
                .forEach { path -> urls += "$base$path" }
        }

        ReconProgress.stage(ReconProgress.Stage.ROBOTS, "reading robots.txt and the sitemaps it declares")
        var sitemapUrlCount = 0
        for (sitemapUrl in sitemapLocations.distinct().take(MAX_SITEMAP_FILES)) {
            val xml = timedFetch(sitemapUrl, SITEMAP_TIMEOUT_MS).body ?: continue
            sitemapsRead += sitemapUrl
            val locs = LOC_REGEX.findAll(xml).map { it.groupValues[1].trim() }.toList()
            if (xml.contains("<sitemapindex", ignoreCase = true)) {
                for (child in locs.filter { it.endsWith(".xml") }.take(4)) {
                    val childXml = timedFetch(child, SITEMAP_TIMEOUT_MS).body ?: continue
                    val childLocs = LOC_REGEX.findAll(childXml).map { it.groupValues[1].trim() }.toList()
                    sitemapUrlCount += childLocs.size
                    // coerceAtLeast(0): robots crawl-rule paths are added
                    // to the inventory uncapped, so the remaining room
                    // can be negative — take() would throw and take the
                    // whole recon pass down with it.
                    urls += childLocs.take((MAX_SITEMAP_URLS - urls.size).coerceAtLeast(0))
                    if (urls.size >= MAX_SITEMAP_URLS) break
                }
            } else {
                sitemapUrlCount += locs.size
                urls += locs.take((MAX_SITEMAP_URLS - urls.size).coerceAtLeast(0))
            }
            if (urls.size >= MAX_SITEMAP_URLS) break
        }

        toolkit.record(ReconCheck("robots-and-sitemaps", if (sitemapUrlCount > 0) "ok" else "empty",
            "robots.txt ${if (robots.body != null) "read" else "unavailable"}; ${sitemapsRead.size} sitemap files read, $sitemapUrlCount URLs"))

        // Historical URLs (gau / waybackurls style): the site's own past
        // URLs carry long-lived search and pagination conventions.
        ReconProgress.stage(ReconProgress.Stage.ARCHIVE, "mining the Wayback and CommonCrawl indexes")
        val archived = runCatching { toolkit.mineArchive() }
            .getOrDefault(ReconToolkit.ArchiveResult(emptyList(), 0, 0))
        urls += archived.urls.take(MAX_ARCHIVE_URLS)

        // ── 2. Homepage: links, anchors, the real search form ─────────
        // One recorded, triaged fetch of the root gives headers for the
        // tech fingerprint (and the document when none was handed in).
        ReconProgress.stage(ReconProgress.Stage.FETCH, "fetching the homepage")
        val homeResponse = runCatching { toolkit.fetch(base, HOMEPAGE_TIMEOUT_MS, "homepage") }.getOrNull()
        val homepageDoc: Document? = when {
            !homepageHtml.isNullOrBlank() -> runCatching { Jsoup.parse(homepageHtml, base) }.getOrNull()
            homeResponse != null && homeResponse.isHttpOk && homeResponse.body.isNotBlank() ->
                runCatching { homeResponse.parse() }.getOrNull()
            else -> {
                val t0 = System.currentTimeMillis()
                val doc = runCatching { cloudflareBypassEngine.fetchJsoupDocument(base, HOMEPAGE_TIMEOUT_MS) }.getOrNull()
                timings += System.currentTimeMillis() - t0
                doc
            }
        }
        val anchorTexts = mutableListOf<String>()
        var openSearchCandidate: SearchCandidate? = null
        var wpJsonBase: String? = null
        var scriptCandidates: List<SearchCandidate> = emptyList()
        var stateFindings = StateBlobFindings(emptyList(), null)
        if (homepageDoc != null) {
            runCatching {
                homepageDoc.select("a[href]").forEach { link ->
                    val href = link.absUrl("href")
                    if (href.startsWith("http")) urls += href
                    val text = link.text().trim()
                    if (text.length >= 4) anchorTexts += text
                }
            }
            // Feed entries are real content URLs — they enrich the
            // inventory (probe terms, item shapes) before derivation.
            runCatching { harvestFeedLinks(homepageDoc, base) { url, t -> timedFetch(url, t) } }
                .getOrDefault(emptyList())
                .forEach { urls += it }
            // OpenSearch description: the site literally publishes its
            // search template there.
            openSearchCandidate = runCatching {
                parseOpenSearch(homepageDoc, base) { url, t -> timedFetch(url, t) }
            }.getOrNull()
            // WordPress REST root advertised by the page itself.
            wpJsonBase = runCatching {
                val href = homepageDoc.selectFirst("link[rel='https://api.w.org/']")?.attr("href")
                href?.takeIf { it.startsWith("http") }
            }.getOrNull()
            // Endpoints named by the page's own scripts (inline + src
            // files) and by its embedded state blobs.
            scriptCandidates = runCatching {
                mineScriptEndpoints(homepageDoc, base, baseHost) { url, t -> timedFetch(url, t) }
            }.getOrDefault(emptyList())
            stateFindings = runCatching { parseStateBlobs(homepageDoc) }
                .getOrDefault(StateBlobFindings(emptyList(), null))
        }

        // ── 2b. Heavy recon: fingerprint, deep crawl, scripts, params ──
        ReconProgress.stage(ReconProgress.Stage.FINGERPRINT, "identifying the CMS / framework / search stack")
        val tech = runCatching { toolkit.fingerprint(homeResponse, homepageDoc) }.getOrDefault(emptyList())

        ReconProgress.stage(ReconProgress.Stage.CRAWL, "crawling the site's own link graph")
        val crawl = try {
            toolkit.crawl(homepageDoc)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ReconToolkit.CrawlResult(emptyList(), emptySet(), emptyList())
        }
        urls += crawl.links

        ReconProgress.stage(ReconProgress.Stage.SCRIPTS, "mining JavaScript and source maps for endpoints")
        val harvest = runCatching { toolkit.scriptTexts(homepageDoc, crawl.docs.take(3)) }
            .getOrDefault(ReconToolkit.ScriptHarvest(emptyList(), 0))
        val scripts = harvest.texts
        val jsEndpoints = runCatching { toolkit.mineJsEndpoints(scripts) }.getOrDefault(emptyList())
        val hostedSearch = runCatching { toolkit.mineHostedSearch(scripts) }.getOrDefault(emptyList())

        ReconProgress.stage(ReconProgress.Stage.TRIAGE, "triaging discovered endpoints")
        runCatching {
            toolkit.triageEndpoints(jsEndpoints.filter { e ->
                listOf("/api", "/ajax", "search", ".json", "graphql", "wp-json").any { e.contains(it, ignoreCase = true) }
            })
        }
        val minedParams = runCatching {
            toolkit.mineParameters(urls, listOfNotNull(homepageDoc) + crawl.docs, scripts)
        }.getOrDefault(emptyMap())

        // ── 3. Existing endpoint recon (JS analysis, CMS/API probes) ──
        val discovered = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            runCatching { endpointDiscoveryEngine.discoverEndpoints(base) }.getOrNull()
        }

        // Same-host URL inventory drives everything derived below.
        val sameHostUrls = urls.filter { raw ->
            runCatching { URL(raw).host == baseHost }.getOrDefault(false)
        }
        val pagination = detectPagination(sameHostUrls)
        val probeQuery = deriveProbeQuery(sameHostUrls, homepageDoc, anchorTexts)

        // ── 2c. Burp-style live capture of the site's OWN search ──────
        // Highest-value technique: drive the site's real search UI in the
        // WebView with the probe term and watch what request it makes.
        ReconProgress.stage(
            ReconProgress.Stage.LIVE_CAPTURE,
            "typing \"${'$'}{probeQuery ?: "?"}\" into the site's own search and capturing the request"
        )
        val capture = if (probeQuery != null && toolkit.timeLeft() > LIVE_CAPTURE_RESERVE_MS) {
            try {
                withTimeoutOrNull(LIVE_CAPTURE_TIMEOUT_MS + 5_000) {
                    LiveSearchCapture.capture(base, probeQuery, LIVE_CAPTURE_TIMEOUT_MS.toInt())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        } else null
        toolkit.record(
            ReconCheck(
                "live-search-capture",
                when {
                    capture == null -> "skipped"
                    capture.searchRequests.isNotEmpty() -> "ok"
                    else -> "empty"
                },
                when {
                    probeQuery == null -> "no probe term from the site's own content, so its search could not be driven"
                    capture == null -> "the WebView capture could not run inside the remaining recon budget"
                    else -> capture.detail + "; " +
                        "${'$'}{capture.allRequests.size} requests intercepted, " +
                        "${'$'}{capture.searchRequests.size} carried the probe term, " +
                        "${'$'}{capture.suggestRequests.size} were autocomplete traffic"
                }
            )
        )

        // ── 2d. Indexer definition lookup (Jackett / Prowlarr) ────────
        ReconProgress.stage(ReconProgress.Stage.INDEXER_DEFINITION, "looking this host up in the Cardigann definitions")
        val definition = runCatching {
            IndexerDefinitionLibrary.lookup(baseHost, startedAt + RECON_BUDGET_MS - DEFINITION_RESERVE_MS)
        }.getOrNull()
        toolkit.record(
            ReconCheck(
                "indexer-definition-lookup",
                if (definition != null) "ok" else "empty",
                definition?.evidence
                    ?: "no Jackett or Prowlarr Cardigann definition declares ${'$'}baseHost in its links/legacylinks"
            )
        )

        // ── 2e. Soft-404 calibration, then the wordlist and params ────
        val baseline = runCatching { toolkit.calibrateSoftNotFound() }
            .getOrDefault(SoftNotFoundBaseline())

        // ── Candidates ─────────────────────────────────────────────────
        val candidates = mutableListOf<SearchCandidate>()

        // (0) The request the site's own search actually made, captured live.
        // Nothing beats watching the site do it: route, method, body, headers
        // and cookies are observed rather than inferred.
        capture?.searchRequests?.forEach { captured ->
            candidates += SearchCandidate(
                urlTemplate = captured.urlTemplate,
                postData = captured.bodyTemplate,
                source = CandidateSource.LIVE_CAPTURE,
                headers = captured.headers,
                cookie = captured.cookie,
                queryParam = captured.queryParam,
                isJson = captured.isJson,
                evidence = captured.evidence
            )
        }

        // (0b) A matching Cardigann indexer definition: the search path,
        // parameters and row selectors hundreds of trackers are known by.
        definition?.let { d ->
            candidates += SearchCandidate(
                urlTemplate = d.urlTemplate.replace("{baseUrl}", base),
                postData = d.postBody,
                source = CandidateSource.INDEXER_DEFINITION,
                queryParam = d.queryParam,
                rowsSelector = d.rowsSelector,
                definitionTitleSelector = d.titleSelector,
                definitionLinkSelector = d.linkSelector,
                evidence = d.evidence
            )
        }

        // The site's real search forms — highest trust, ranked first.
        // Sites often carry several (header + dedicated search page
        // forms, GET + POST variants); each distinct one is a candidate.
        if (homepageDoc != null) {
            runCatching { harvestSearchForms(homepageDoc, base, pagination.param) }
                .getOrDefault(emptyList())
                .forEach { candidates += it }
        }
        // Forms on crawled inner pages (a dedicated /search page, etc.).
        for (doc in crawl.docs) {
            runCatching { harvestSearchForms(doc, base, pagination.param) }
                .getOrDefault(emptyList())
                .forEach { candidates += it }
        }

        // The site's own published OpenSearch template.
        openSearchCandidate?.let { candidates += it }

        // WordPress REST search collection under the advertised root.
        wpJsonBase?.let { wpBase ->
            candidates += SearchCandidate(
                urlTemplate = wpBase.trimEnd('/') + "/wp/v2/search?search={query}&per_page=20&page={page}",
                source = CandidateSource.JSON_API
            )
        }

        // Endpoints the discovery engine already verified live.
        val discoveredTemplates = linkedSetOf<String>()
        discovered?.let { d ->
            (d.searchEndpoints + d.apiEndpoints).forEach { endpoint ->
                val absolute = if (endpoint.startsWith("http")) endpoint else "$base$endpoint"
                if (absolute.contains("{query}") || absolute.contains("search", ignoreCase = true) || absolute.contains("q=")) {
                    discoveredTemplates += absolute
                }
            }
        }
        discoveredTemplates.forEach {
            candidates += SearchCandidate(urlTemplate = it, source = CandidateSource.DISCOVERED_ENDPOINT)
        }

        // Templates derived from the site's own URL inventory.
        deriveSearchTemplates(sameHostUrls, pagination).forEach {
            candidates += SearchCandidate(urlTemplate = it, source = CandidateSource.SITEMAP_DERIVED)
        }

        // Hosted search services the page published credentials for.
        hostedSearch.forEach { hosted ->
            candidates += SearchCandidate(
                urlTemplate = hosted.url,
                postData = hosted.body,
                source = CandidateSource.HOSTED_SEARCH,
                headers = hosted.headers,
                isJson = true,
                evidence = hosted.evidence
            )
        }

        // Wordlist routes (ffuf / gobuster / dirsearch style), fingerprint
        // prioritized and soft-404 calibrated — only live-verified hits. A hit
        // whose shape came from the stack fingerprint is labelled as such.
        ReconProgress.stage(ReconProgress.Stage.WORDLIST, "probing search routes against the soft-404 baseline")
        val stackRoutes = runCatching { StackSignatures.routesFor(tech) }.getOrDefault(emptyList())
            .map { base.trimEnd('/') + it }.toSet()
        val wordlist = runCatching { toolkit.probeWordlist(probeQuery, tech, baseline) }
            .getOrDefault(ReconToolkit.WordlistOutcome(emptyList(), emptyList(), 0))
        wordlist.hits.forEach { hit ->
            candidates += SearchCandidate(
                urlTemplate = hit.template,
                source = if (hit.template in stackRoutes) CandidateSource.STACK_SIGNATURE else CandidateSource.WORDLIST,
                isJson = hit.isJson,
                evidence = hit.evidence
            )
        }

        // Active parameter discovery (Arjun style) on the paths the site's own
        // pages and the wordlist pointed at.
        ReconProgress.stage(ReconProgress.Stage.PARAMS, "testing which query parameter the site's search really uses")
        val paramPaths = buildList {
            wordlist.hits.forEach { add(it.template.substringBefore('?')) }
            add("${'$'}base/search")
            add(base)
        }.distinct().filter { it.startsWith("http") }
        val activeParams = runCatching { toolkit.discoverActiveParams(paramPaths, probeQuery, baseline) }
            .getOrDefault(emptyList())
        activeParams.forEach { found ->
            val sep = if (found.path.contains('?')) "&" else "?"
            candidates += SearchCandidate(
                urlTemplate = "${'$'}{found.path}${'$'}sep${'$'}{found.param}={query}&${'$'}{pagination.param ?: "page"}={page}",
                source = CandidateSource.ACTIVE_PARAM,
                queryParam = found.param,
                evidence = found.evidence
            )
        }

        // Search-shaped URLs the site itself used in the past.
        archived.urls.asSequence()
            .filter { it.contains('?') }
            .mapNotNull { archivedSearchTemplate(it) }
            .distinct()
            .take(MAX_ARCHIVE_CANDIDATES)
            .forEach { template ->
                candidates += SearchCandidate(
                    urlTemplate = template,
                    source = CandidateSource.ARCHIVE,
                    evidence = "a search-shaped URL this site used in the past, from the web archives"
                )
            }

        // Endpoints named by the page's own scripts and state blobs.
        candidates += scriptCandidates
        stateFindings.endpoints.forEach { endpoint ->
            candidates += SearchCandidate(urlTemplate = endpoint, source = CandidateSource.EMBEDDED_STATE)
        }

        // ── 4. JSON endpoint detection: probe and derive field paths ──
        val elapsed = System.currentTimeMillis() - startedAt
        if (probeQuery != null && elapsed < JSON_PROBE_DEADLINE_MS) {
            var probes = 0
            val probed = candidates.map { candidate ->
                val looksJson = candidate.urlTemplate.contains("/api/", ignoreCase = true) ||
                    candidate.urlTemplate.contains("wp-json", ignoreCase = true) ||
                    candidate.urlTemplate.contains("/ajax/", ignoreCase = true) ||
                    candidate.urlTemplate.substringBefore('?').endsWith(".json", ignoreCase = true) ||
                    candidate.source == CandidateSource.JSON_API ||
                    candidate.source == CandidateSource.EMBEDDED_STATE
                if (!looksJson || probes >= MAX_JSON_PROBES || candidate.postData != null) {
                    candidate
                } else {
                    probes++
                    probeJsonCandidate(candidate, probeQuery, stateFindings.hints) { url, timeout ->
                        timedFetch(url, timeout, acceptJson = true)
                    }
                }
            }
            // probeJsonCandidate returns null for "JSON but no result array" → drop.
            candidates.clear()
            candidates += probed.filterNotNull()
        }

        // ── Ordering / dedup / caps ────────────────────────────────────
        ReconProgress.stage(ReconProgress.Stage.RANK, "ranking and de-duplicating candidates")
        val ordered = candidates
            .distinctBy { it.urlTemplate to it.postData }
            .sortedWith(
                compareBy<SearchCandidate> { sourceRank(it.source) }
                    .thenBy { if (it.isJson) 0 else 1 }
                    .thenBy { if (it.source == CandidateSource.SITEMAP_DERIVED) 0 else 1 }
            )
            .take(MAX_CANDIDATES)

        // The ledger records every candidate in rank order; the verifier fills
        // in each one's outcome, so the report can say why the losers lost.
        val ledger = ordered.mapIndexed { index, candidate ->
            CandidateLedgerEntry(
                rank = index + 1,
                source = candidate.source.name,
                template = candidate.postData?.let { "${'$'}{candidate.urlTemplate} [POST ${'$'}it]" } ?: candidate.urlTemplate,
                outcome = "not reached",
                reason = candidate.evidence
            )
        }

        val median = medianOf(timings)
        val suggestedTimeout = if (timings.isEmpty()) DEFAULT_TIMEOUT_MS
            else (median * 3 + 2000).toInt().coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

        Log.i(
            TAG,
            "recon $base: sitemapUrls=$sitemapUrlCount urls=${sameHostUrls.size} " +
                "candidates=${ordered.size} probe=$probeQuery medianMs=$median timeoutMs=$suggestedTimeout"
        )
        SiteRecon(
            baseUrl = base,
            searchCandidates = ordered,
            paginationParam = pagination.param,
            paginationPathStyle = pagination.pathStyle,
            probeQuery = probeQuery,
            sampleItemUrls = sampleItemUrls(sameHostUrls),
            sitemapUrlCount = sitemapUrlCount,
            mappedUrlCount = sameHostUrls.size,
            fetchTimingsMs = timings.toList(),
            medianResponseMs = median,
            suggestedTimeoutMs = suggestedTimeout,
            sitemapsRead = sitemapsRead.toList(),
            reconMap = toolkit.buildMap(
                tech = tech,
                crawl = crawl,
                params = minedParams,
                activeParams = activeParams,
                jsEndpoints = jsEndpoints,
                sourceMapsRead = harvest.sourceMapsRead,
                archive = archived,
                wordlist = wordlist,
                baseline = baseline,
                hostedSearch = hostedSearch,
                capture = capture,
                definition = definition,
                ledger = ledger
            ),
            liveCapture = capture,
            definitionMatch = definition
        ).also { ReconProgress.stage(ReconProgress.Stage.VERIFY, "verifying ${'$'}{ordered.size} candidates live") }
    }

    // ─── Search form harvesting ───────────────────────────────────────

    /**
     * Candidate priority (spec: live capture > indexer definition > the site's
     * own form/OpenSearch > JSON API from JS or a stack signature >
     * wordlist/param discovery > archive). Rank only decides the ORDER in which
     * candidates are proven; nothing is saved without passing verification.
     */
    private fun sourceRank(source: CandidateSource): Int = when (source) {
        CandidateSource.LIVE_CAPTURE -> 0
        CandidateSource.INDEXER_DEFINITION -> 1
        CandidateSource.FORM -> 2
        CandidateSource.OPENSEARCH -> 3
        CandidateSource.JSON_API -> 4
        CandidateSource.HOSTED_SEARCH -> 5
        CandidateSource.EMBEDDED_STATE -> 6
        CandidateSource.DISCOVERED_ENDPOINT -> 7
        CandidateSource.STACK_SIGNATURE -> 8
        CandidateSource.WORDLIST -> 9
        CandidateSource.ACTIVE_PARAM -> 10
        CandidateSource.SITEMAP_DERIVED -> 11
        CandidateSource.ARCHIVE -> 12
    }

    /** All distinct search forms on the page (GET and POST variants). */
    private fun harvestSearchForms(doc: Document, base: String, paginationParam: String?): List<SearchCandidate> {
        val forms = doc.select("form").filter { form ->
            form.selectFirst("input[type=search]") != null ||
                form.select("input[name]").any { it.attr("name").lowercase() in SEARCH_FIELD_NAMES } ||
                form.attr("action").contains("search", ignoreCase = true) ||
                form.attr("role").equals("search", ignoreCase = true)
        }
        return forms.mapNotNull { form -> buildFormCandidate(form, base, paginationParam) }
            .distinctBy { it.urlTemplate to it.postData }
            .take(MAX_FORM_CANDIDATES)
    }

    private fun buildFormCandidate(form: org.jsoup.nodes.Element, base: String, paginationParam: String?): SearchCandidate? {
        val action = form.absUrl("action").ifBlank { base }
        if (!action.startsWith("http", ignoreCase = true)) return null

        val fields = form.select("input[name], select[name], textarea[name]")
        var querySeen = false
        val pairs = mutableListOf<Pair<String, String>>()
        for (element in fields) {
            val name = element.attr("name").trim()
            if (name.isBlank()) continue
            val tag = element.tagName().lowercase()
            val type = element.attr("type").lowercase()
            if (tag == "input" && type in setOf("submit", "button", "image", "file", "password")) continue
            if (tag == "input" && type in setOf("checkbox", "radio") && !element.hasAttr("checked")) continue

            val isQueryField = !querySeen && (
                name.lowercase() in SEARCH_FIELD_NAMES ||
                    type == "search" ||
                    element.attr("placeholder").contains("search", ignoreCase = true)
                )
            val isPageField = name.lowercase() in PAGE_FIELD_NAMES
            val value = when {
                isQueryField -> "{query}"
                isPageField -> "{page}"
                tag == "select" -> element.selectFirst("option[selected]")?.attr("value")
                    ?: element.selectFirst("option")?.attr("value") ?: ""
                tag == "textarea" -> element.text()
                else -> element.attr("value")
            }
            if (isQueryField) querySeen = true
            if (value.isNotBlank() || isQueryField || isPageField) pairs += name to value
        }
        if (!querySeen) return null

        val distinctPairs = pairs.distinctBy { it.first }
        val hasPage = distinctPairs.any { it.second == "{page}" }
        val rendered = distinctPairs.map { (name, value) ->
            "${encodeTemplateField(name)}=${encodeTemplateField(value)}"
        }.toMutableList()
        if (!hasPage) rendered += "${paginationParam ?: "page"}={page}"
        val body = rendered.joinToString("&")

        return if (form.attr("method").equals("post", ignoreCase = true)) {
            SearchCandidate(urlTemplate = action, postData = body, source = CandidateSource.FORM)
        } else {
            val separator = if (action.contains('?')) "&" else "?"
            SearchCandidate(urlTemplate = action + separator + body, source = CandidateSource.FORM)
        }
    }

    // ─── OpenSearch / feeds / WordPress ───────────────────────────────

    /**
     * The site's published OpenSearch description: its Url template uses
     * {searchTerms} for the query. Optional OpenSearch parameters
     * ({count?}, {startPage?}, …) are dropped along with their key=value
     * pair — a template that still has unresolved placeholders after
     * conversion is rejected rather than guessed at.
     */
    private fun parseOpenSearch(
        doc: Document,
        base: String,
        fetch: (String, Int) -> TimedFetch
    ): SearchCandidate? {
        val linkHref = doc.selectFirst("link[rel='search'][type='application/opensearchdescription+xml']")
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() }
        val descriptionUrl = linkHref ?: "$base/opensearch.xml"
        val xml = fetch(descriptionUrl, OPENSEARCH_TIMEOUT_MS).body ?: return null
        if (!xml.contains("opensearch", ignoreCase = true) && !xml.contains("Url", ignoreCase = true)) {
            return null
        }
        val urlElements = runCatching { Jsoup.parse(xml, base).select("Url") }.getOrDefault(emptyList())
        if (urlElements.isEmpty()) return null
        val chosen = urlElements.firstOrNull { it.attr("type").equals("text/html", ignoreCase = true) }
            ?: urlElements.first()
        var template = chosen.attr("template")
        if (!template.contains("{searchTerms}")) return null
        template = template.replace("{searchTerms}", "{query}")
        // Drop pairs whose value is an optional placeholder: &key={x?}
        template = template.replace(Regex("[&?][^&{]*=\\{[^}]*\\?\\}"), "")
        template = template.replace(Regex("\\{[^}]*\\?\\}"), "")
        if (template.contains('{') && !template.contains("{query}")) return null
        if (template.contains(Regex("\\{(?!query\\}|page\\})"))) return null
        val absolute = when {
            template.startsWith("http") -> template
            template.startsWith("/") -> base.trimEnd('/') + template
            else -> return null
        }
        return SearchCandidate(urlTemplate = absolute, source = CandidateSource.OPENSEARCH)
    }

    /** Entry links from the site's first advertised RSS/Atom feed. */
    private fun harvestFeedLinks(
        doc: Document,
        base: String,
        fetch: (String, Int) -> TimedFetch
    ): List<String> {
        val feedUrl = doc.select("link[rel='alternate']")
            .firstOrNull {
                val type = it.attr("type").lowercase()
                type.contains("rss") || type.contains("atom")
            }
            ?.absUrl("href")
            ?.takeIf { it.isNotBlank() } ?: return emptyList()
        val xml = fetch(feedUrl, FEED_TIMEOUT_MS).body ?: return emptyList()
        val links = mutableListOf<String>()
        Regex("<link>\\s*(https?://[^<]+?)\\s*</link>", RegexOption.IGNORE_CASE)
            .findAll(xml).forEach { links += it.groupValues[1].trim() }
        Regex("<link[^>]+href=\"(https?://[^\"]+)\"", RegexOption.IGNORE_CASE)
            .findAll(xml).forEach { links += it.groupValues[1].trim() }
        // The first RSS <link> is the channel's own URL, not an entry.
        return links.drop(1).distinct().take(MAX_FEED_LINKS)
    }

    // ─── Script & embedded-state mining ───────────────────────────────

    /**
     * Search endpoints named by the page's own JavaScript: string
     * literals in inline scripts and in same-host script files. Only
     * literals that already carry a search parameter are converted
     * (that parameter's value becomes {query}); bare API bases are not
     * turned into guesses.
     */
    private fun mineScriptEndpoints(
        doc: Document,
        base: String,
        baseHost: String,
        fetch: (String, Int) -> TimedFetch
    ): List<SearchCandidate> {
        val scriptTexts = mutableListOf<String>()
        doc.select("script:not([src])").forEach { script ->
            val html = script.html()
            if (html.isNotBlank()) scriptTexts += html
        }
        doc.select("script[src]").map { it.absUrl("src") }
            .filter { it.startsWith("http") && runCatching { URL(it).host == baseHost }.getOrDefault(false) }
            .distinct().take(MAX_SCRIPT_FILES)
            .forEach { src ->
                fetch(src, SCRIPT_TIMEOUT_MS).body?.let { scriptTexts += it }
            }

        val templates = linkedSetOf<String>()
        for (text in scriptTexts) {
            for (match in SCRIPT_URL_LITERAL_REGEX.findAll(text)) {
                val literal = match.groupValues[1]
                templateizeEndpointLiteral(literal, base)?.let { templates += it }
            }
            for (match in SCRIPT_ASSIGN_REGEX.findAll(text)) {
                val literal = match.groupValues[1]
                templateizeEndpointLiteral(literal, base)?.let { templates += it }
            }
        }
        return templates.take(MAX_SCRIPT_CANDIDATES).map {
            SearchCandidate(urlTemplate = it, source = CandidateSource.DISCOVERED_ENDPOINT)
        }
    }

    /** Field names observed in an embedded state blob's result array. */
    private data class JsonFieldHints(
        val titleField: String?,
        val urlField: String?,
        val thumbnailField: String?
    )

    private data class StateBlobFindings(
        val endpoints: List<String>,
        val hints: JsonFieldHints?
    )

    /**
     * Embedded state: `application/json` script tags, `__NEXT_DATA__`,
     * and `window.__INITIAL_STATE__`-style assignments. The blob is the
     * site's own data: endpoint URL strings inside it become candidates,
     * and a result-shaped array inside it yields the field-name hints
     * used when probing JSON endpoints.
     */
    private fun parseStateBlobs(doc: Document): StateBlobFindings {
        val endpoints = linkedSetOf<String>()
        var hints: JsonFieldHints? = null

        val blobs = mutableListOf<String>()
        doc.select("script[type='application/json'], script#__NEXT_DATA__").forEach { script ->
            val data = script.data()
            if (data.isNotBlank()) blobs += data
        }
        doc.select("script:not([src])").forEach { script ->
            val html = script.html()
            if (html.contains("__INITIAL_STATE__") || html.contains("__PRELOADED_STATE__") ||
                html.contains("__APOLLO_STATE__") || html.contains("window.__DATA__")
            ) {
                val start = html.indexOf('{')
                val end = html.lastIndexOf('}')
                if (start >= 0 && end > start) blobs += html.substring(start, end + 1)
            }
        }

        for (blob in blobs) {
            val root = runCatching { json.parseToJsonElement(blob) }.getOrNull() ?: continue
            // Endpoint strings named by the blob itself.
            val strings = mutableListOf<String>()
            fun collectStrings(element: JsonElement, depth: Int) {
                if (depth > 8 || strings.size >= 200) return
                when (element) {
                    is JsonPrimitive -> element.contentOrNull?.let { strings += it }
                    is JsonArray -> element.forEach { collectStrings(it, depth + 1) }
                    is JsonObject -> element.values.forEach { collectStrings(it, depth + 1) }
                    else -> Unit
                }
            }
            collectStrings(root, 0)
            for (value in strings) {
                if ((value.startsWith("/") || value.startsWith("http")) &&
                    value.length < 300 && !value.contains(' ')
                ) {
                    templateizeEndpointLiteral(value, doc.baseUri())?.let { endpoints += it }
                }
            }
            // A result-shaped array proves the site's field names.
            if (hints == null) {
                findResultArray(root, "", 0)?.let { (_, firstObject) ->
                    hints = JsonFieldHints(
                        titleField = firstKeyOf(firstObject, TITLE_KEYS),
                        urlField = firstKeyOf(firstObject, URL_KEYS),
                        thumbnailField = firstKeyOf(firstObject, THUMB_KEYS)
                    )
                }
            }
        }
        return StateBlobFindings(endpoints.take(MAX_SCRIPT_CANDIDATES), hints)
    }

    /**
     * Convert a script/blob URL literal into a search template when —
     * and only when — it already carries a search parameter: that
     * parameter's value (including ${…} interpolations) becomes {query},
     * a page parameter's value becomes {page}, and any other unresolved
     * interpolation rejects the literal.
     */
    private fun templateizeEndpointLiteral(literal: String, base: String): String? {
        var candidate = literal.trim()
        if (candidate.isBlank() || candidate.length > 300) return null
        if (!candidate.contains('?')) return null
        val searchParam = SEARCH_PARAM_REGEX.find(candidate) ?: return null
        val paramName = searchParam.groupValues[1]
        candidate = candidate.replace(
            Regex("([?&]" + Regex.escape(paramName) + "=)[^&]*"),
            "$1{query}"
        )
        candidate = candidate.replace(Regex("([?&](?:page|paged|p)=)[^&]*"), "$1{page}")
        if (candidate.contains("${'$'}") || candidate.contains(Regex("\\{(?!query\\}|page\\})"))) {
            return null
        }
        return when {
            candidate.startsWith("http") -> candidate
            candidate.startsWith("/") -> base.trimEnd('/') + candidate
            else -> null
        }
    }

    /** URLEncoder that preserves the {query}/{page} template tokens. */
    private fun encodeTemplateField(value: String): String {
        val encoded = URLEncoder.encode(
            value
                .replace("{query}", "__AGGREGATOR_QUERY_TOKEN__")
                .replace("{page}", "__AGGREGATOR_PAGE_TOKEN__"),
            "UTF-8"
        )
        return encoded
            .replace("__AGGREGATOR_QUERY_TOKEN__", "{query}")
            .replace("__AGGREGATOR_PAGE_TOKEN__", "{page}")
    }

    // ─── JSON endpoint probing ────────────────────────────────────────

    /**
     * Probe a JSON-looking candidate with the real probe query.
     * Returns the candidate enriched with the derived result-array path and
     * field names, the candidate unchanged when the response is not JSON,
     * or null when the response IS JSON but holds no result array (a dead
     * or non-search endpoint — dropped rather than saved on a guess).
     */
    private fun probeJsonCandidate(
        candidate: SearchCandidate,
        probeQuery: String,
        hints: JsonFieldHints?,
        fetch: (String, Int) -> TimedFetch
    ): SearchCandidate? {
        val url = candidate.urlTemplate
            .replace("{query}", URLEncoder.encode(probeQuery, "UTF-8"))
            .replace("{page}", "1")
        val response = fetch(url, JSON_PROBE_TIMEOUT_MS)
        val body = response.body ?: return candidate
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return candidate

        val titleKeys = TITLE_KEYS + listOfNotNull(hints?.titleField)
        val urlKeys = URL_KEYS + listOfNotNull(hints?.urlField)
        val thumbKeys = THUMB_KEYS + listOfNotNull(hints?.thumbnailField)
        val found = findResultArray(root, path = "", depth = 0, titleKeys, urlKeys) ?: return null
        val (path, firstObject) = found
        val titleField = firstKeyOf(firstObject, titleKeys)
        val urlField = firstKeyOf(firstObject, urlKeys)
        if (titleField == null || urlField == null) return null
        return candidate.copy(
            isJson = true,
            jsonResultPath = path,
            jsonTitleField = titleField,
            jsonUrlField = urlField,
            jsonThumbnailField = firstKeyOf(firstObject, thumbKeys)
        )
    }

    /** Locate the first array (root-first, then object values in order)
     *  whose object elements carry both a title-ish and a url-ish key. */
    private fun findResultArray(
        element: JsonElement,
        path: String,
        depth: Int,
        titleKeys: List<String> = TITLE_KEYS,
        urlKeys: List<String> = URL_KEYS
    ): Pair<String, JsonObject>? {
        if (depth > 8) return null
        when (element) {
            is JsonArray -> {
                val firstObject = element.firstOrNull { it is JsonObject } as? JsonObject
                if (firstObject != null &&
                    firstKeyOf(firstObject, titleKeys) != null &&
                    firstKeyOf(firstObject, urlKeys) != null
                ) {
                    return path to firstObject
                }
                for (child in element) {
                    findResultArray(child, path, depth + 1, titleKeys, urlKeys)?.let { return it }
                }
            }
            is JsonObject -> {
                for ((key, value) in element) {
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    findResultArray(value, childPath, depth + 1, titleKeys, urlKeys)?.let { return it }
                }
            }
            else -> Unit
        }
        return null
    }

    private fun firstKeyOf(obj: JsonObject, keys: List<String>): String? =
        obj.entries.firstOrNull { entry -> keys.any { it.equals(entry.key, ignoreCase = true) } }?.key

    // ─── Derivation from the URL inventory ────────────────────────────

    private data class PaginationStyle(val param: String?, val pathStyle: Boolean)

    private fun detectPagination(urls: List<String>): PaginationStyle {
        val votes = mutableMapOf<String, Int>()
        var pathStyle = false
        for (raw in urls) {
            val url = runCatching { URL(raw) }.getOrNull() ?: continue
            if (Regex("/page/\\d+").containsMatchIn(url.path)) pathStyle = true
            url.query?.split('&')?.forEach { pair ->
                val key = pair.substringBefore('=').lowercase()
                if (key in PAGE_PARAMS) votes[key] = (votes[key] ?: 0) + 1
            }
        }
        return PaginationStyle(votes.maxByOrNull { it.value }?.key, pathStyle)
    }

    /**
     * Turn real site URLs into search templates:
     *  - `https://site/find?k=some+title&p=2` → `https://site/find?k={query}&p={page}`
     *  - `https://site/search/some-title`     → `https://site/search/{query}`
     * Fixed parameters the site itself uses are kept verbatim.
     */
    private fun deriveSearchTemplates(urls: List<String>, pagination: PaginationStyle): List<String> {
        val templates = linkedSetOf<String>()
        for (raw in urls) {
            val url = runCatching { URL(raw) }.getOrNull() ?: continue
            val origin = "${url.protocol}://${url.host}" + if (url.port > 0) ":${url.port}" else ""

            val pairs = url.query?.split('&')
                ?.map { it.substringBefore('=') to it.substringAfter('=', "") }
                .orEmpty()
            val searchPair = pairs.firstOrNull { it.first.lowercase() in SEARCH_KEYS }
            if (searchPair != null) {
                val rebuilt = pairs.joinToString("&") { (key, value) ->
                    when {
                        key == searchPair.first -> "$key={query}"
                        key.lowercase() in PAGE_PARAMS -> "$key={page}"
                        else -> "$key=$value"
                    }
                }
                val hasPage = pairs.any { it.first.lowercase() in PAGE_PARAMS }
                val suffix = if (hasPage) "" else "&${pagination.param ?: "page"}={page}"
                templates += "$origin${url.path}?$rebuilt$suffix"
                continue
            }

            val segments = url.path.split('/').filter { it.isNotBlank() }
            val markerIndex = segments.indexOfFirst { it.lowercase() in SEARCH_PATH_MARKERS }
            if (markerIndex >= 0 && segments.size > markerIndex + 1) {
                val prefix = segments.take(markerIndex + 1).joinToString("/", prefix = "/")
                templates += "$origin$prefix/{query}"
            } else if (markerIndex >= 0 && markerIndex == segments.lastIndex) {
                // The route root itself is the search marker (seen in
                // robots.txt crawl rules and nav links): /search/{query}.
                templates += "$origin${segments.joinToString("/", prefix = "/")}/{query}"
            }
        }
        return templates.take(MAX_TEMPLATES)
    }

    /**
     * A real probe term taken from the site's own content: the most
     * frequent meaningful word in its deep URL slugs, falling back to
     * its page title, then to its own link texts. Null when the site
     * offers nothing — verification then refuses rather than guessing.
     */
    private fun deriveProbeQuery(urls: List<String>, homepageDoc: Document?, anchorTexts: List<String>): String? {
        val slugWords = mutableMapOf<String, Int>()
        for (raw in urls) {
            val url = runCatching { URL(raw) }.getOrNull() ?: continue
            val segments = url.path.split('/').filter { it.isNotBlank() }
            if (segments.size < 2) continue
            for (segment in segments) {
                for (word in segment.lowercase().split(Regex("[^a-z0-9]+"))) {
                    if (isProbeWord(word)) slugWords[word] = (slugWords[word] ?: 0) + 1
                }
            }
        }
        mostFrequentWord(slugWords)?.let { return it }

        val titleWords = mutableMapOf<String, Int>()
        homepageDoc?.title()?.lowercase()?.split(Regex("[^a-z0-9]+"))?.forEach { word ->
            if (isProbeWord(word)) titleWords[word] = (titleWords[word] ?: 0) + 1
        }
        mostFrequentWord(titleWords)?.let { return it }

        val anchorWords = mutableMapOf<String, Int>()
        for (text in anchorTexts) {
            for (word in text.lowercase().split(Regex("[^a-z0-9]+"))) {
                if (word.length >= 4 && isProbeWord(word)) anchorWords[word] = (anchorWords[word] ?: 0) + 1
            }
        }
        return mostFrequentWord(anchorWords)
    }

    private fun isProbeWord(word: String): Boolean =
        word.length >= 3 && !word.all { it.isDigit() } && word !in PROBE_STOPWORDS

    private fun mostFrequentWord(counts: Map<String, Int>): String? =
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .firstOrNull()?.key

    /** Deep same-host URLs that look like item/detail pages, kept as
     *  shape references for what a real result link looks like. */
    private fun sampleItemUrls(urls: List<String>): List<String> =
        urls.filter { raw ->
            val url = runCatching { URL(raw) }.getOrNull() ?: return@filter false
            val path = url.path.lowercase()
            url.path.split('/').count { it.isNotBlank() } >= 2 &&
                !path.contains("/search") &&
                !path.contains("/category") &&
                !path.contains("/tag/") &&
                !path.endsWith(".xml")
        }.take(10)

    /**
     * Turn an archived URL that carried a search parameter into a template.
     * Only URLs whose parameter name is a known search parameter qualify —
     * an arbitrary old URL is not a search route.
     */
    private fun archivedSearchTemplate(raw: String): String? {
        val url = runCatching { URL(raw) }.getOrNull() ?: return null
        val pairs = url.query?.split('&')?.map { it.substringBefore('=') to it.substringAfter('=', "") }
            ?: return null
        val search = pairs.firstOrNull { it.first.lowercase() in SEARCH_KEYS && it.second.isNotBlank() } ?: return null
        val origin = "${'$'}{url.protocol}://${'$'}{url.host}" + if (url.port > 0) ":${'$'}{url.port}" else ""
        var sawPage = false
        val rebuilt = pairs.joinToString("&") { (key, value) ->
            when {
                key == search.first -> "${'$'}key={query}"
                key.lowercase() in PAGE_PARAMS -> { sawPage = true; "${'$'}key={page}" }
                else -> "${'$'}key=${'$'}value"
            }
        }
        return "${'$'}origin${'$'}{url.path}?${'$'}rebuilt" + if (sawPage) "" else "&page={page}"
    }

    private fun medianOf(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    companion object {
        private const val TAG = "SiteReconEngine"
        private const val ROBOTS_TIMEOUT_MS = 4_000
        private const val SITEMAP_TIMEOUT_MS = 5_000
        private const val HOMEPAGE_TIMEOUT_MS = 8_000
        private const val JSON_PROBE_TIMEOUT_MS = 6_000
        private const val OPENSEARCH_TIMEOUT_MS = 5_000
        private const val FEED_TIMEOUT_MS = 5_000
        private const val SCRIPT_TIMEOUT_MS = 6_000
        private const val JSON_PROBE_DEADLINE_MS = 25_000L
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
        private const val MAX_SITEMAP_FILES = 6
        private const val MAX_SITEMAP_URLS = 1_500
        private const val MAX_TEMPLATES = 6
        private const val MAX_CANDIDATES = 14
        private const val MAX_FORM_CANDIDATES = 3
        private const val MAX_SCRIPT_FILES = 3
        private const val MAX_SCRIPT_CANDIDATES = 4
        private const val MAX_FEED_LINKS = 50
        private const val MAX_JSON_PROBES = 3
        /**
         * Whole-recon time budget (the repository's outer timeout is a backstop
         * above it). The heavy techniques — a 60-page crawl, a ~250-route
         * calibrated wordlist sweep, the WebView search capture, active
         * parameter discovery and the definition lookup — need real room; the
         * analyzer UI observes the run to completion rather than racing it.
         */
        private const val RECON_BUDGET_MS = 75_000L
        /** Don't start the WebView capture without room for it to finish. */
        private const val LIVE_CAPTURE_RESERVE_MS = 34_000L
        private const val LIVE_CAPTURE_TIMEOUT_MS = 28_000L
        /** Leave the definition lookup out of the last stretch of the budget. */
        private const val DEFINITION_RESERVE_MS = 25_000L
        private const val MAX_ARCHIVE_CANDIDATES = 3
        private const val MAX_ARCHIVE_URLS = 400

        private val SCRIPT_URL_LITERAL_REGEX =
            Regex("""['"`](/(?:api|ajax|search|wp-json)[^'"`\s]*?)['"`]""", RegexOption.IGNORE_CASE)
        private val SCRIPT_ASSIGN_REGEX =
            Regex("""(?:apiUrl|searchUrl|searchEndpoint|ajaxUrl)\s*[:=]\s*['"`]([^'"`]+)['"`]""", RegexOption.IGNORE_CASE)
        private val SEARCH_PARAM_REGEX =
            Regex("""[?&](q|s|query|keyword|keywords|term|search|search_query|wd)=([^&]*)""", RegexOption.IGNORE_CASE)
        private const val MIN_TIMEOUT_MS = 10_000
        private const val MAX_TIMEOUT_MS = 45_000
        private const val DEFAULT_TIMEOUT_MS = 25_000

        private val LOC_REGEX = Regex("<loc>\\s*([^<]+?)\\s*</loc>", RegexOption.IGNORE_CASE)
        private val DEFAULT_SITEMAP_PATHS = listOf("/sitemap.xml", "/sitemap_index.xml", "/sitemap-index.xml", "/wp-sitemap.xml")

        private val SEARCH_KEYS = setOf(
            "q", "s", "query", "keyword", "keywords", "term", "k",
            "search", "search_query", "wd", "name", "title"
        )
        private val SEARCH_FIELD_NAMES = setOf(
            "q", "query", "search", "s", "keyword", "keywords", "term", "search_query", "wd"
        )
        private val PAGE_FIELD_NAMES = setOf("page", "paged", "p", "page_num")
        private val PAGE_PARAMS = setOf("page", "paged", "p")
        private val SEARCH_PATH_MARKERS = setOf("search", "find", "results")

        private val TITLE_KEYS = listOf("title", "name", "headline", "label")
        private val URL_KEYS = listOf("url", "link", "href", "permalink", "webpage_url", "canonicalUrl")
        private val THUMB_KEYS = listOf("thumbnail", "thumbnailUrl", "image", "poster", "cover", "artwork")

        private val PROBE_STOPWORDS = setOf(
            "www", "com", "net", "org", "html", "htm", "php", "index", "page",
            "search", "category", "categories", "tag", "tags", "archive", "archives",
            "feed", "rss", "sitemap", "uploads", "content", "wp", "images", "img",
            "css", "js", "static", "assets", "api", "json", "xml", "id"
        )

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/**
 * Everything the recon pass learned about one site, from its own data.
 */
data class SiteRecon(
    val baseUrl: String,
    /** Search route candidates, best first. */
    val searchCandidates: List<SearchCandidate>,
    /** The page parameter this site actually uses ("page", "paged", "p"), if seen. */
    val paginationParam: String?,
    /** True when the site paginates with /page/N/ style paths. */
    val paginationPathStyle: Boolean,
    /** A real content term from the site's own inventory, for probe searches. */
    val probeQuery: String?,
    /** Real detail-page URLs from the inventory (shape references). */
    val sampleItemUrls: List<String>,
    val sitemapUrlCount: Int,
    val mappedUrlCount: Int,
    /** Elapsed ms of every fetch made during mapping. */
    val fetchTimingsMs: List<Long>,
    val medianResponseMs: Long,
    /** Timeout to record on the saved config, derived from measured timing. */
    val suggestedTimeoutMs: Int,
    /** Sitemap files actually read (robots-declared and conventional). */
    val sitemapsRead: List<String> = emptyList(),
    /** Heavy-recon evidence: fingerprint, crawl, params, JS endpoints, archive, wordlist, triage, checks. */
    val reconMap: ReconMap = ReconMap(),
    /** The WebView capture of the site's own search, when one ran. */
    val liveCapture: LiveSearchCapture.CaptureResult? = null,
    /** The matching Cardigann indexer definition, when one was found. */
    val definitionMatch: IndexerDefinitionLibrary.DefinitionMatch? = null
)

/**
 * One search route candidate, derived from the site's own data.
 */
data class SearchCandidate(
    /** URL template containing {query} (and {page} when paginated). */
    val urlTemplate: String,
    /** Non-null for POST search forms; the encoded body containing {query}. */
    val postData: String? = null,
    val source: CandidateSource,
    /** Request headers this route needs (captured live, or a definition's). */
    val headers: Map<String, String> = emptyMap(),
    /** Cookie header the site required when this route was observed working. */
    val cookie: String? = null,
    /** The query parameter the term travels in, when it is known. */
    val queryParam: String? = null,
    /** Extraction selectors a matched indexer definition supplies. */
    val rowsSelector: String? = null,
    val definitionTitleSelector: String? = null,
    val definitionLinkSelector: String? = null,
    /** Why this candidate exists — recorded in the candidate ledger. */
    val evidence: String = "",
    /** True when a live probe proved this route returns JSON search results. */
    val isJson: Boolean = false,
    /** Dot path of the result array in the JSON payload ("" = the root is the array). */
    val jsonResultPath: String? = null,
    val jsonTitleField: String? = null,
    val jsonUrlField: String? = null,
    val jsonThumbnailField: String? = null
)

enum class CandidateSource {
    /** The site's own search request, captured on the wire in the WebView. */
    LIVE_CAPTURE,
    /** A matching Jackett/Prowlarr Cardigann indexer definition. */
    INDEXER_DEFINITION,
    FORM,
    OPENSEARCH,
    JSON_API,
    /** A hosted search service whose public credentials the page published. */
    HOSTED_SEARCH,
    EMBEDDED_STATE,
    DISCOVERED_ENDPOINT,
    /** A route shape implied by the fingerprinted stack and proven live. */
    STACK_SIGNATURE,
    WORDLIST,
    /** A parameter that proved query-dependent under active probing. */
    ACTIVE_PARAM,
    SITEMAP_DERIVED,
    /** A search-shaped URL the site used in the past (Wayback/CommonCrawl). */
    ARCHIVE
}
