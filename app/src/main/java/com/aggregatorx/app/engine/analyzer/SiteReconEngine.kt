package com.aggregatorx.app.engine.analyzer

import android.util.Log
import com.aggregatorx.app.engine.network.CloudflareBypassEngine
import com.aggregatorx.app.engine.network.TlsClient
import com.aggregatorx.app.engine.network.TlsRequest
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

    suspend fun mapSite(baseUrl: String, homepageHtml: String?): SiteRecon = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val baseHost = runCatching { URL(base).host }.getOrNull().orEmpty()
        val timings = mutableListOf<Long>()
        val startedAt = System.currentTimeMillis()

        fun timedFetch(url: String, timeoutMs: Int, acceptJson: Boolean = false): TimedFetch {
            val t0 = System.currentTimeMillis()
            val response = runCatching {
                TlsClient.execute(
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

        var sitemapUrlCount = 0
        for (sitemapUrl in sitemapLocations.distinct().take(MAX_SITEMAP_FILES)) {
            val xml = timedFetch(sitemapUrl, SITEMAP_TIMEOUT_MS).body ?: continue
            val locs = LOC_REGEX.findAll(xml).map { it.groupValues[1].trim() }.toList()
            if (xml.contains("<sitemapindex", ignoreCase = true)) {
                for (child in locs.filter { it.endsWith(".xml") }.take(2)) {
                    val childXml = timedFetch(child, SITEMAP_TIMEOUT_MS).body ?: continue
                    val childLocs = LOC_REGEX.findAll(childXml).map { it.groupValues[1].trim() }.toList()
                    sitemapUrlCount += childLocs.size
                    urls += childLocs.take(MAX_SITEMAP_URLS - urls.size)
                    if (urls.size >= MAX_SITEMAP_URLS) break
                }
            } else {
                sitemapUrlCount += locs.size
                urls += locs.take(MAX_SITEMAP_URLS - urls.size)
            }
            if (urls.size >= MAX_SITEMAP_URLS) break
        }

        // ── 2. Homepage: links, anchors, the real search form ─────────
        val homepageDoc: Document? = when {
            !homepageHtml.isNullOrBlank() -> runCatching { Jsoup.parse(homepageHtml, base) }.getOrNull()
            else -> {
                val t0 = System.currentTimeMillis()
                val doc = runCatching { cloudflareBypassEngine.fetchJsoupDocument(base, HOMEPAGE_TIMEOUT_MS) }.getOrNull()
                timings += System.currentTimeMillis() - t0
                doc
            }
        }
        val anchorTexts = mutableListOf<String>()
        if (homepageDoc != null) {
            runCatching {
                homepageDoc.select("a[href]").forEach { link ->
                    val href = link.absUrl("href")
                    if (href.startsWith("http")) urls += href
                    val text = link.text().trim()
                    if (text.length >= 4) anchorTexts += text
                }
            }
        }

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

        // ── Candidates ─────────────────────────────────────────────────
        val candidates = mutableListOf<SearchCandidate>()

        // The site's real search form — highest trust, ranked first.
        if (homepageDoc != null) {
            runCatching { harvestSearchForm(homepageDoc, base, pagination.param) }
                .getOrNull()
                ?.let { candidates += it }
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

        // ── 4. JSON endpoint detection: probe and derive field paths ──
        val elapsed = System.currentTimeMillis() - startedAt
        if (probeQuery != null && elapsed < JSON_PROBE_DEADLINE_MS) {
            var probes = 0
            val probed = candidates.map { candidate ->
                val looksJson = candidate.urlTemplate.contains("/api/", ignoreCase = true) ||
                    candidate.urlTemplate.contains("wp-json", ignoreCase = true) ||
                    candidate.urlTemplate.contains("/ajax/", ignoreCase = true) ||
                    candidate.urlTemplate.substringBefore('?').endsWith(".json", ignoreCase = true)
                if (!looksJson || probes >= MAX_JSON_PROBES || candidate.postData != null) {
                    candidate
                } else {
                    probes++
                    probeJsonCandidate(candidate, probeQuery) { url, timeout ->
                        timedFetch(url, timeout, acceptJson = true)
                    }
                }
            }
            // probeJsonCandidate returns null for "JSON but no result array" → drop.
            candidates.clear()
            candidates += probed.filterNotNull()
        }

        // ── Ordering / dedup / caps ────────────────────────────────────
        val ordered = candidates
            .distinctBy { it.urlTemplate to it.postData }
            .sortedWith(
                compareBy<SearchCandidate> { if (it.source == CandidateSource.FORM) 0 else 1 }
                    .thenBy { if (it.isJson) 0 else 1 }
                    .thenBy { if (it.source == CandidateSource.SITEMAP_DERIVED) 0 else 1 }
            )
            .take(MAX_CANDIDATES)

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
            suggestedTimeoutMs = suggestedTimeout
        )
    }

    // ─── Search form harvesting ───────────────────────────────────────

    private fun harvestSearchForm(doc: Document, base: String, paginationParam: String?): SearchCandidate? {
        val forms = doc.select("form")
        val form = forms.firstOrNull { candidate ->
            candidate.selectFirst("input[type=search]") != null ||
                candidate.select("input[name]").any { it.attr("name").lowercase() in SEARCH_FIELD_NAMES } ||
                candidate.attr("action").contains("search", ignoreCase = true) ||
                candidate.attr("role").equals("search", ignoreCase = true)
        } ?: return null

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
        fetch: (String, Int) -> TimedFetch
    ): SearchCandidate? {
        val url = candidate.urlTemplate
            .replace("{query}", URLEncoder.encode(probeQuery, "UTF-8"))
            .replace("{page}", "1")
        val response = fetch(url, JSON_PROBE_TIMEOUT_MS)
        val body = response.body ?: return candidate
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return candidate

        val found = findResultArray(root, path = "", depth = 0) ?: return null
        val (path, firstObject) = found
        val titleField = firstKeyOf(firstObject, TITLE_KEYS)
        val urlField = firstKeyOf(firstObject, URL_KEYS)
        if (titleField == null || urlField == null) return null
        return candidate.copy(
            isJson = true,
            jsonResultPath = path,
            jsonTitleField = titleField,
            jsonUrlField = urlField,
            jsonThumbnailField = firstKeyOf(firstObject, THUMB_KEYS)
        )
    }

    /** Locate the first array (root-first, then object values in order)
     *  whose object elements carry both a title-ish and a url-ish key. */
    private fun findResultArray(element: JsonElement, path: String, depth: Int): Pair<String, JsonObject>? {
        if (depth > 8) return null
        when (element) {
            is JsonArray -> {
                val firstObject = element.firstOrNull { it is JsonObject } as? JsonObject
                if (firstObject != null &&
                    firstKeyOf(firstObject, TITLE_KEYS) != null &&
                    firstKeyOf(firstObject, URL_KEYS) != null
                ) {
                    return path to firstObject
                }
                for (child in element) {
                    findResultArray(child, path, depth + 1)?.let { return it }
                }
            }
            is JsonObject -> {
                for ((key, value) in element) {
                    val childPath = if (path.isEmpty()) key else "$path.$key"
                    findResultArray(value, childPath, depth + 1)?.let { return it }
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
        private const val JSON_PROBE_DEADLINE_MS = 25_000L
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
        private const val MAX_SITEMAP_FILES = 3
        private const val MAX_SITEMAP_URLS = 1_500
        private const val MAX_TEMPLATES = 6
        private const val MAX_CANDIDATES = 6
        private const val MAX_JSON_PROBES = 3
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
    val suggestedTimeoutMs: Int
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
    /** True when a live probe proved this route returns JSON search results. */
    val isJson: Boolean = false,
    /** Dot path of the result array in the JSON payload ("" = the root is the array). */
    val jsonResultPath: String? = null,
    val jsonTitleField: String? = null,
    val jsonUrlField: String? = null,
    val jsonThumbnailField: String? = null
)

enum class CandidateSource { FORM, SITEMAP_DERIVED, DISCOVERED_ENDPOINT, JSON_API }
