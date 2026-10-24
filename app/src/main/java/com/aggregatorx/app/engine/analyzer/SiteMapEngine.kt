package com.aggregatorx.app.engine.analyzer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Site mapper for the Analyzer — the "recon" pass.
 *
 * Instead of guessing how a site might be searched, this maps how it
 * actually is:
 *
 *  1. `robots.txt` is read for the sitemaps the site itself publishes.
 *  2. Those sitemaps (plus the usual sitemap locations) are read for the
 *     site's real URL inventory.
 *  3. The homepage's real links are harvested.
 *  4. The existing [EndpointDiscoveryEngine] recon (JS analysis, CMS/API
 *     probing, robots/sitemap mining) contributes its search endpoints.
 *
 * From that real URL inventory we derive the search routes the site itself
 * uses — the actual query parameter (`q`, `s`, `query`, `keyword`, …) or
 * path style (`/search/{query}`) — and how it really paginates (which page
 * parameter, or `/page/N/` paths). Everything derived here came from the
 * site's own data; generic guessed shapes are only a last resort, and the
 * caller still probes whatever is saved before trusting it.
 *
 * All fetches are bounded and same-host: this maps one site at add time,
 * it does not crawl the web.
 */
@Singleton
class SiteMapEngine @Inject constructor(
    private val endpointDiscoveryEngine: EndpointDiscoveryEngine
) {
    data class SiteMap(
        /** Search URL templates derived from the site's own URLs, best first. */
        val searchUrlTemplates: List<String>,
        /** The page parameter this site actually uses ("page", "paged", "p"), if seen. */
        val paginationParam: String?,
        /** True when the site paginates with /page/N/ style paths. */
        val paginationPathStyle: Boolean,
        val sitemapUrlCount: Int,
        val mappedUrlCount: Int
    )

    suspend fun mapSite(baseUrl: String, homepageHtml: String?): SiteMap = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')
        val baseHost = runCatching { URL(base).host }.getOrNull().orEmpty()
        val urls = linkedSetOf<String>()

        // 1 + 2. robots.txt sitemaps, then the sitemaps themselves.
        val sitemapLocations = mutableListOf<String>()
        val robotsText = fetchText("$base/robots.txt", ROBOTS_TIMEOUT_MS)
        if (robotsText != null) {
            robotsText.lineSequence()
                .map { it.trim() }
                .filter { it.lowercase().startsWith("sitemap:") }
                .map { it.substringAfter(':').trim() }
                .filter { it.startsWith("http") }
                .forEach { sitemapLocations += it }
        }
        sitemapLocations += DEFAULT_SITEMAP_PATHS.map { "$base$it" }

        var sitemapUrlCount = 0
        for (sitemapUrl in sitemapLocations.distinct().take(MAX_SITEMAP_FILES)) {
            val xml = fetchText(sitemapUrl, SITEMAP_TIMEOUT_MS) ?: continue
            val locs = LOC_REGEX.findAll(xml).map { it.groupValues[1].trim() }.toList()
            if (xml.contains("<sitemapindex", ignoreCase = true)) {
                // One level of index only, and only a couple of children.
                for (child in locs.filter { it.endsWith(".xml") }.take(2)) {
                    val childXml = fetchText(child, SITEMAP_TIMEOUT_MS) ?: continue
                    val childLocs = LOC_REGEX.findAll(childXml).map { it.groupValues[1].trim() }.toList()
                    sitemapUrlCount += childLocs.size
                    urls += childLocs.take(MAX_SITEMAP_URLS - urls.size)
                }
            } else {
                sitemapUrlCount += locs.size
                urls += locs.take(MAX_SITEMAP_URLS - urls.size)
            }
            if (urls.size >= MAX_SITEMAP_URLS) break
        }

        // 3. The homepage's real links.
        if (!homepageHtml.isNullOrBlank()) {
            runCatching {
                val doc = Jsoup.parse(homepageHtml, base)
                doc.select("a[href]").forEach { link ->
                    val href = link.absUrl("href")
                    if (href.startsWith("http")) urls += href
                }
            }
        }

        // 4. Endpoint recon the app already had but never wired into analysis:
        // JS-discovered and CMS/API search endpoints are real site routes.
        val discovered = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            runCatching { endpointDiscoveryEngine.discoverEndpoints(base) }.getOrNull()
        }
        discovered?.searchEndpoints.orEmpty().forEach { endpoint ->
            urls += if (endpoint.startsWith("http")) endpoint else "$base$endpoint"
        }

        val sameHostUrls = urls.filter { url ->
            runCatching { URL(url).host == baseHost }.getOrDefault(false)
        }
        val pagination = detectPagination(sameHostUrls)
        val templates = deriveSearchTemplates(sameHostUrls, pagination)

        Log.i(
            TAG,
            "mapped $base: sitemapUrls=$sitemapUrlCount urls=${sameHostUrls.size} " +
                "templates=${templates.size} pagination=${pagination.param ?: (if (pagination.pathStyle) "/page/N/" else "unknown")}"
        )
        SiteMap(
            searchUrlTemplates = templates,
            paginationParam = pagination.param,
            paginationPathStyle = pagination.pathStyle,
            sitemapUrlCount = sitemapUrlCount,
            mappedUrlCount = sameHostUrls.size
        )
    }

    private fun fetchText(url: String, timeoutMs: Int): String? = runCatching {
        Jsoup.connect(url)
            .userAgent(com.aggregatorx.app.engine.util.EngineUtils.DEFAULT_USER_AGENT)
            .timeout(timeoutMs)
            .followRedirects(true)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .execute()
            .body()
    }.getOrNull()

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
     * Fixed parameters the site uses (categories, safe-search flags, …) are kept.
     */
    private fun deriveSearchTemplates(urls: List<String>, pagination: PaginationStyle): List<String> {
        val templates = linkedSetOf<String>()
        for (raw in urls) {
            val url = runCatching { URL(raw) }.getOrNull() ?: continue
            val origin = "${url.protocol}://${url.host}" + if (url.port > 0) ":${url.port}" else ""

            // Query-parameter style: a real URL whose query carries a search key.
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

            // Path style: /search/<something> or /find/<something>.
            val segments = url.path.split('/').filter { it.isNotBlank() }
            val markerIndex = segments.indexOfFirst { it.lowercase() in SEARCH_PATH_MARKERS }
            if (markerIndex >= 0 && segments.size > markerIndex + 1) {
                val prefix = segments.take(markerIndex + 1).joinToString("/", prefix = "/")
                templates += "$origin$prefix/{query}"
            }
        }
        return templates.take(MAX_TEMPLATES)
    }

    companion object {
        private const val TAG = "SiteMapEngine"
        private const val ROBOTS_TIMEOUT_MS = 4_000
        private const val SITEMAP_TIMEOUT_MS = 5_000
        private const val DISCOVERY_TIMEOUT_MS = 15_000L
        private const val MAX_SITEMAP_FILES = 3
        private const val MAX_SITEMAP_URLS = 1_500
        private const val MAX_TEMPLATES = 6

        private val LOC_REGEX = Regex("<loc>\\s*([^<]+?)\\s*</loc>", RegexOption.IGNORE_CASE)
        private val DEFAULT_SITEMAP_PATHS = listOf("/sitemap.xml", "/sitemap_index.xml", "/wp-sitemap.xml")

        private val SEARCH_KEYS = setOf(
            "q", "s", "query", "keyword", "keywords", "term", "k",
            "search", "search_query", "wd", "name", "title"
        )
        private val PAGE_PARAMS = setOf("page", "paged", "p")
        private val SEARCH_PATH_MARKERS = setOf("search", "find", "results")
    }
}
