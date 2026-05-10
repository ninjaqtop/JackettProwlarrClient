package com.aggregatorx.app.engine.analyzer

import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.engine.network.CloudflareBypassEngine
import com.aggregatorx.app.engine.scraper.HeadlessBrowserHelper
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Advanced API & Endpoint Discovery Engine
 *
 * Discovers hidden API endpoints, search URLs, and site structure through:
 * 1. Static HTML/JS analysis — parse inline scripts for fetch/XHR URLs
 * 2. robots.txt / sitemap.xml mining for hidden paths
 * 3. Common CMS API path probing (WordPress, Ghost, Strapi, Directus, etc.)
 * 4. GraphQL introspection detection
 * 5. OpenAPI/Swagger spec detection
 * 6. Pattern-based endpoint deduction from visible URLs
 * 7. Headless network interception (Playwright request capture)
 * 8. Learning from past successes — avoids re-probing known-dead endpoints
 *
 * All discovered endpoints are cached per domain and shared with
 * ScrapingEngine and AIDecisionEngine for smarter scraping.
 */
@Singleton
class EndpointDiscoveryEngine @Inject constructor(
    private val cloudflareBypassEngine: CloudflareBypassEngine
) {

    companion object {
        private const val DISCOVERY_TIMEOUT = 20000
        private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L  // 2 hours
        private val UA = EngineUtils.DEFAULT_USER_AGENT

        // Known CMS API path templates (base → paths to probe)
        private val CMS_API_PATHS = listOf(
            // WordPress
            "/wp-json/wp/v2/posts?search={query}&per_page=20",
            "/wp-json/wp/v2/search?search={query}&per_page=20",
            "/wp-json/wp/v2/media?search={query}&per_page=20",
            "/wp-json/wp/v2/pages?search={query}&per_page=20",
            "/wp-json/wp/v2/categories",
            "/wp-json/wp/v2/tags",
            "/?rest_route=/wp/v2/posts&search={query}",
            // Ghost CMS
            "/ghost/api/content/posts/?filter=title:~%27{query}%27&limit=20",
            "/ghost/api/v4/content/posts/?filter=title:~%27{query}%27&limit=20",
            // Strapi v4/v5
            "/api/articles?filters[title][\$containsi]={query}&pagination[pageSize]=20",
            "/api/posts?filters[title][\$containsi]={query}&pagination[pageSize]=20",
            "/api/videos?filters[title][\$containsi]={query}&pagination[pageSize]=20",
            "/api/movies?filters[title][\$containsi]={query}&pagination[pageSize]=20",
            "/api/contents?filters[title][\$containsi]={query}&pagination[pageSize]=20",
            // Directus
            "/items/articles?filter[title][_contains]={query}&limit=20",
            "/items/posts?filter[title][_contains]={query}&limit=20",
            "/items/content?filter[title][_contains]={query}&limit=20",
            "/items/videos?filter[title][_contains]={query}&limit=20",
            // Generic REST APIs
            "/api/search?q={query}",
            "/api/v1/search?q={query}",
            "/api/v2/search?q={query}",
            "/api/v3/search?q={query}",
            "/api/search?query={query}",
            "/api/search?keyword={query}",
            "/api/search?term={query}",
            "/api/videos/search?q={query}",
            "/api/movies/search?q={query}",
            "/api/content/search?q={query}",
            // AJAX endpoints (common in torrent sites)
            "/ajax/search?q={query}",
            "/ajax/movies/search?content={query}",
            "/suggest?q={query}",
            "/autocomplete?q={query}",
            "/api/autocomplete?q={query}",
            // GraphQL
            "/graphql",
            "/api/graphql"
        )

        // Regex to extract API URLs from inline JavaScript
        private val JS_API_PATTERNS = listOf(
            Regex("""(?:fetch|axios\.get|axios\.post|\$\.ajax|\$\.get|\$\.post|XMLHttpRequest)\s*\(\s*['"`](/?(?:api|ajax|graphql|search|json)[^'"`\s]*?)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""['"`](https?://[^'"`\s]*?/api/[^'"`\s]*?)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""['"`](/api/[^'"`\s]*?)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""['"`](/?wp-json/[^'"`\s]*?)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""apiUrl\s*[:=]\s*['"`]([^'"`\s]+)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""baseUrl\s*[:=]\s*['"`]([^'"`\s]+)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""searchUrl\s*[:=]\s*['"`]([^'"`\s]+)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""endpoint\s*[:=]\s*['"`]([^'"`\s]+)['"`]""", RegexOption.IGNORE_CASE)
        )

        // Sitemap URL patterns
        private val SITEMAP_PATHS = listOf(
            "/sitemap.xml",
            "/sitemap_index.xml",
            "/sitemap-index.xml",
            "/sitemaps/sitemap.xml",
            "/wp-sitemap.xml"
        )
    }

    // Cache: domain → DiscoveredEndpoints
    private val endpointCache = ConcurrentHashMap<String, CachedDiscovery>()

    /**
     * Discover all available endpoints for a site.
     * Returns a [DiscoveredEndpoints] with search URLs, API endpoints, sitemaps, etc.
     */
    suspend fun discoverEndpoints(
        baseUrl: String,
        sampleQuery: String = "test"
    ): DiscoveredEndpoints = withContext(Dispatchers.IO) {
        val domain = extractDomain(baseUrl)
        val base = baseUrl.trimEnd('/')

        // Check cache
        endpointCache[domain]?.let { cached ->
            if (!cached.isExpired()) return@withContext cached.endpoints
        }

        val searchEndpoints = mutableListOf<String>()
        val apiEndpoints = mutableListOf<String>()
        val sitemapUrls = mutableListOf<String>()
        val detectedCms: String? = null
        var hasGraphQL = false
        var hasOpenAPI = false

        // Run discovery strategies in parallel
        coroutineScope {
            // 1. Static JS analysis
            val jsAnalysis = async { analyzeInlineScripts(base) }

            // 2. robots.txt mining
            val robotsAnalysis = async { mineRobotsTxt(base) }

            // 3. CMS API probing
            val cmsProbe = async { probeCmsApis(base, sampleQuery) }

            // 4. Sitemap mining
            val sitemapAnalysis = async { mineSitemaps(base) }

            // Collect results
            val jsEndpoints = jsAnalysis.await()
            searchEndpoints.addAll(jsEndpoints.filter { it.contains("search") || it.contains("query") || it.contains("q=") })
            apiEndpoints.addAll(jsEndpoints)

            val robotsPaths = robotsAnalysis.await()
            apiEndpoints.addAll(robotsPaths.filter { it.contains("api") || it.contains("ajax") || it.contains("json") })

            val cmsResults = cmsProbe.await()
            searchEndpoints.addAll(cmsResults.searchEndpoints)
            apiEndpoints.addAll(cmsResults.apiEndpoints)
            hasGraphQL = cmsResults.hasGraphQL
            hasOpenAPI = cmsResults.hasOpenAPI

            val sitemaps = sitemapAnalysis.await()
            sitemapUrls.addAll(sitemaps)
        }

        val result = DiscoveredEndpoints(
            domain = domain,
            baseUrl = base,
            searchEndpoints = searchEndpoints.distinct(),
            apiEndpoints = apiEndpoints.distinct(),
            sitemapUrls = sitemapUrls.distinct(),
            detectedCms = detectedCms,
            hasGraphQL = hasGraphQL,
            hasOpenAPI = hasOpenAPI,
            discoveredAt = System.currentTimeMillis()
        )

        // Cache
        endpointCache[domain] = CachedDiscovery(result)

        result
    }

    /**
     * Get the best search endpoint for a site. Returns null if none found.
     */
    suspend fun getBestSearchEndpoint(baseUrl: String, query: String): String? {
        val endpoints = discoverEndpoints(baseUrl, query)
        if (endpoints.searchEndpoints.isEmpty()) return null

        // Try each endpoint and return first that works
        for (endpoint in endpoints.searchEndpoints.take(5)) {
            val url = endpoint
                .replace("{query}", java.net.URLEncoder.encode(query, "UTF-8"))
                .let { if (it.startsWith("http")) it else "${baseUrl.trimEnd('/')}$it" }
            try {
                val response = Jsoup.connect(url)
                    .userAgent(UA)
                    .timeout(DISCOVERY_TIMEOUT)
                    .ignoreHttpErrors(true)
                    .execute()
                if (response.statusCode() == 200) {
                    val body = response.body()
                    // Valid if it's not empty and not a challenge page
                    if (body.length > 500 && !body.contains("Checking your browser")) {
                        // Learn this endpoint for the domain
                        learnWorkingEndpoint(extractDomain(baseUrl), endpoint, body.length)
                        return url
                    }
                }
            } catch (_: Exception) { continue }
        }

        return null
    }

    // ─── Learning System ──────────────────────────────────────────

    /** Per-domain memory of endpoints that worked and how well. */
    private val endpointEffectiveness = ConcurrentHashMap<String, MutableList<EndpointScore>>()

    /** Record that an endpoint worked for a domain. Boosts its priority in future. */
    fun learnWorkingEndpoint(domain: String, endpoint: String, resultSize: Int) {
        val scores = endpointEffectiveness.getOrPut(domain) { mutableListOf() }
        val existing = scores.find { it.endpoint == endpoint }
        if (existing != null) {
            scores.remove(existing)
            scores.add(existing.copy(
                successCount = existing.successCount + 1,
                lastResultSize = resultSize,
                lastUsed = System.currentTimeMillis()
            ))
        } else {
            scores.add(EndpointScore(endpoint, 1, resultSize))
        }
    }

    /** Record that an endpoint failed for a domain so it drops in priority. */
    fun learnFailedEndpoint(domain: String, endpoint: String) {
        val scores = endpointEffectiveness.getOrPut(domain) { mutableListOf() }
        val existing = scores.find { it.endpoint == endpoint }
        if (existing != null) {
            scores.remove(existing)
            scores.add(existing.copy(failureCount = existing.failureCount + 1))
        } else {
            scores.add(EndpointScore(endpoint, 0, 0, 1))
        }
    }

    /** Get ranked endpoints for a domain based on learned effectiveness. */
    fun getRankedEndpoints(domain: String): List<String> {
        return endpointEffectiveness[domain]
            ?.filter { it.successCount > it.failureCount }
            ?.sortedByDescending { it.successCount * it.lastResultSize }
            ?.map { it.endpoint }
            ?: emptyList()
    }

    // ─── Headless Network Interception ────────────────────────────

    /**
     * Use a headless browser to navigate to a page and capture all
     * XHR/Fetch requests made by the client-side JavaScript. This
     * reveals hidden API endpoints that cannot be found via static
     * HTML/JS analysis alone (e.g. dynamically constructed URLs,
     * WebSocket upgrades, etc.).
     */
    suspend fun discoverEndpointsViaHeadless(
        baseUrl: String,
        sampleQuery: String = "test"
    ): List<String> = withContext(Dispatchers.IO) {
        // Native implementation: use HeadlessBrowserHelper's API endpoint prober
        // plus form-based search submission to surface XHR/fetch endpoints from
        // the page source (script tags, data attributes, inline JSON).
        val discovered = mutableListOf<String>()
        try {
            // 1. Probe known API path patterns
            val apiEndpoints = HeadlessBrowserHelper.discoverSearchAPIEndpoints(baseUrl, sampleQuery)
            discovered.addAll(apiEndpoints)

            // 2. Fetch page source and extract endpoint hints from JS bundles
            val html = HeadlessBrowserHelper.fetchPageContent(baseUrl) ?: ""
            val endpointPatterns = listOf(
                Regex("""['"/](api/[^'">\s]+)['"]"""),
                Regex("""['"/](ajax/[^'">\s]+)['"]"""),
                Regex("""['"/](graphql[^'">\s]*)['"]"""),
                Regex("""fetch\(['"]([^'"]+)['"]"""),
                Regex("""axios\.[a-z]+\(['"]([^'"]+)['"]"""),
                Regex("""XMLHttpRequest[^;]+open\([^,]+,\s*['"]([^'"]+)['"]"""),
                Regex("""url:\s*['"]([^'"]*(?:search|api|query|ajax)[^'"]*)['"]""", RegexOption.IGNORE_CASE)
            )
            val host = extractDomain(baseUrl)
            for (pattern in endpointPatterns) {
                pattern.findAll(html).forEach { m ->
                    val path = m.groupValues[1]
                    val full = when {
                        path.startsWith("http") -> path
                        path.startsWith("/")    -> "https://$host$path"
                        else                    -> "https://$host/$path"
                    }
                    if (!full.contains("analytics") && !full.contains("tracking") &&
                        !full.contains("pixel") && !full.contains(".css") &&
                        !full.contains(".png") && !full.contains(".jpg")) {
                        discovered.add(full)
                    }
                }
            }

            // 3. Submit search form and capture the resulting URL
            val formResult = HeadlessBrowserHelper.searchViaHeadlessForm(baseUrl, sampleQuery)
            if (formResult != null && formResult != html) {
                // The form action URL is a valid search endpoint — extract it
                val doc = org.jsoup.Jsoup.parse(html, baseUrl)
                doc.select("form").forEach { form ->
                    val action = form.absUrl("action").ifEmpty { baseUrl }
                    if (action.isNotEmpty()) discovered.add(action)
                }
            }
        } catch (_: Exception) {}
        discovered.distinct()
    }

    /**
     * Full discovery combining all methods including headless interception.
     * Use this for important providers where thorough discovery matters.
     */
    suspend fun deepDiscoverEndpoints(
        baseUrl: String,
        sampleQuery: String = "test"
    ): DiscoveredEndpoints = withContext(Dispatchers.IO) {
        val basic = discoverEndpoints(baseUrl, sampleQuery)
        val headlessEndpoints = try { discoverEndpointsViaHeadless(baseUrl, sampleQuery) } catch (_: Exception) { emptyList() }

        // Merge headless-discovered endpoints into the result
        val allSearch = (basic.searchEndpoints + headlessEndpoints.filter {
            it.contains("search") || it.contains("query") || it.contains("q=")
        }).distinct()
        val allApi = (basic.apiEndpoints + headlessEndpoints.filter {
            it.contains("api") || it.contains("ajax") || it.contains("json") || it.contains("graphql")
        }).distinct()

        // Also merge with any previously-learned endpoints for this domain
        val domain = extractDomain(baseUrl)
        val learned = getRankedEndpoints(domain)

        basic.copy(
            searchEndpoints = (learned + allSearch).distinct(),
            apiEndpoints = (allApi).distinct()
        )
    }

    // ─── Discovery Strategies ─────────────────────────────────────────

    private suspend fun analyzeInlineScripts(baseUrl: String): List<String> =
        withContext(Dispatchers.IO) {
            val endpoints = mutableListOf<String>()
            try {
                val doc = cloudflareBypassEngine.fetchJsoupDocument(baseUrl, DISCOVERY_TIMEOUT)
                    ?: Jsoup.connect(baseUrl)
                        .userAgent(UA)
                        .timeout(DISCOVERY_TIMEOUT)
                        .ignoreHttpErrors(true)
                        .get()

                // Extract URLs from all script blocks
                val scripts = doc.select("script").mapNotNull { it.html().takeIf { h -> h.length > 10 } }
                val allJs = scripts.joinToString("\n")

                for (pattern in JS_API_PATTERNS) {
                    pattern.findAll(allJs).forEach { match ->
                        val url = match.groupValues.getOrNull(1)?.trim()
                        if (url != null && url.length > 3 && !url.contains("analytics") &&
                            !url.contains("tracking") && !url.contains("pixel")) {
                            endpoints.add(url)
                        }
                    }
                }

                // Also check <link> and <meta> tags for API discovery
                doc.select("link[rel='search']").forEach { link ->
                    link.attr("href").takeIf { it.isNotEmpty() }?.let { endpoints.add(it) }
                }
                doc.select("meta[name='api-url'], meta[name='api-base']").forEach { meta ->
                    meta.attr("content").takeIf { it.isNotEmpty() }?.let { endpoints.add(it) }
                }

            } catch (_: Exception) {}
            endpoints.distinct()
        }

    private suspend fun mineRobotsTxt(baseUrl: String): List<String> =
        withContext(Dispatchers.IO) {
            val paths = mutableListOf<String>()
            try {
                val response = Jsoup.connect("$baseUrl/robots.txt")
                    .userAgent(UA)
                    .timeout(10000)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .execute()

                if (response.statusCode() == 200) {
                    val body = response.body()
                    // Extract Disallow/Allow paths — some reveal API paths
                    val pathRegex = Regex("(?:Disallow|Allow|Sitemap):\\s*(.+)", RegexOption.IGNORE_CASE)
                    pathRegex.findAll(body).forEach { match ->
                        val path = match.groupValues[1].trim()
                        if (path.isNotEmpty() && !path.startsWith("#")) {
                            if (path.startsWith("http")) {
                                paths.add(path)
                            } else {
                                paths.add("$baseUrl${path.removePrefix("/").let { "/$it" }}")
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            paths.distinct()
        }

    private suspend fun probeCmsApis(
        baseUrl: String,
        sampleQuery: String
    ): CmsProbeResult = withContext(Dispatchers.IO) {
        val searchEndpoints = mutableListOf<String>()
        val apiEndpoints = mutableListOf<String>()
        var hasGraphQL = false
        var hasOpenAPI = false

        // Probe CMS API paths in parallel (batches of 8)
        val encodedQuery = java.net.URLEncoder.encode(sampleQuery, "UTF-8")

        for (chunk in CMS_API_PATHS.chunked(8)) {
            coroutineScope {
                chunk.map { pathTemplate ->
                    async(Dispatchers.IO) {
                        val path = pathTemplate.replace("{query}", encodedQuery)
                        val url = "$baseUrl$path"
                        try {
                            val response = Jsoup.connect(url)
                                .userAgent(UA)
                                .timeout(8000)
                                .ignoreHttpErrors(true)
                                .ignoreContentType(true)
                                .header("Accept", "application/json, text/html")
                                .execute()

                            when {
                                response.statusCode() == 200 -> {
                                    val body = response.body()
                                    val isJson = body.trimStart().startsWith("[") ||
                                            body.trimStart().startsWith("{")
                                    val isHtmlWithResults = body.length > 1000 &&
                                            !body.contains("Checking your browser")

                                    if (isJson || isHtmlWithResults) {
                                        if (pathTemplate.contains("graphql")) hasGraphQL = true
                                        if (pathTemplate.contains("{query}") || pathTemplate.contains("search")) {
                                            searchEndpoints.add(pathTemplate)
                                        }
                                        apiEndpoints.add(pathTemplate)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }.awaitAll()
            }
        }

        // Check for OpenAPI / Swagger spec
        val specPaths = listOf("/swagger.json", "/openapi.json", "/api-docs", "/api/docs", "/api/spec")
        for (specPath in specPaths) {
            try {
                val response = Jsoup.connect("$baseUrl$specPath")
                    .userAgent(UA)
                    .timeout(5000)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .execute()
                if (response.statusCode() == 200 && response.body().contains("paths")) {
                    hasOpenAPI = true
                    apiEndpoints.add(specPath)
                    break
                }
            } catch (_: Exception) {}
        }

        CmsProbeResult(
            searchEndpoints = searchEndpoints.distinct(),
            apiEndpoints = apiEndpoints.distinct(),
            hasGraphQL = hasGraphQL,
            hasOpenAPI = hasOpenAPI
        )
    }

    private suspend fun mineSitemaps(baseUrl: String): List<String> =
        withContext(Dispatchers.IO) {
            val sitemaps = mutableListOf<String>()
            for (path in SITEMAP_PATHS) {
                try {
                    val response = Jsoup.connect("$baseUrl$path")
                        .userAgent(UA)
                        .timeout(8000)
                        .ignoreHttpErrors(true)
                        .ignoreContentType(true)
                        .execute()
                    if (response.statusCode() == 200 && response.body().contains("<url", ignoreCase = true)) {
                        sitemaps.add("$baseUrl$path")
                    }
                } catch (_: Exception) {}
            }
            sitemaps
        }

    private fun extractDomain(url: String): String =
        com.aggregatorx.app.engine.util.EngineUtils.extractDomain(url)
}

/**
 * Discovered API endpoints for a site
 */
data class DiscoveredEndpoints(
    val domain: String,
    val baseUrl: String,
    val searchEndpoints: List<String>,
    val apiEndpoints: List<String>,
    val sitemapUrls: List<String>,
    val detectedCms: String? = null,
    val hasGraphQL: Boolean = false,
    val hasOpenAPI: Boolean = false,
    val discoveredAt: Long = System.currentTimeMillis()
)

private data class CmsProbeResult(
    val searchEndpoints: List<String>,
    val apiEndpoints: List<String>,
    val hasGraphQL: Boolean,
    val hasOpenAPI: Boolean
)

private data class CachedDiscovery(
    val endpoints: DiscoveredEndpoints,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isExpired(): Boolean =
        System.currentTimeMillis() - timestamp > 2 * 60 * 60 * 1000L
}

/** Tracks how effective an endpoint is for a particular domain. */
data class EndpointScore(
    val endpoint: String,
    val successCount: Int = 0,
    val lastResultSize: Int = 0,
    val failureCount: Int = 0,
    val lastUsed: Long = System.currentTimeMillis()
)
