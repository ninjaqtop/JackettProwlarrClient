package com.aggregatorx.app.engine.scraper

import android.util.Log
import com.aggregatorx.app.data.database.ProviderDao
import com.aggregatorx.app.data.database.ScrapingConfigDao
import com.aggregatorx.app.data.database.SiteAnalysisDao
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.engine.analyzer.SmartContentClassifier
import com.aggregatorx.app.engine.analyzer.PageType
import com.aggregatorx.app.engine.analyzer.ContainerType
import com.aggregatorx.app.engine.analyzer.EndpointDiscoveryEngine
import com.aggregatorx.app.engine.analyzer.UniversalFormatParser
import com.aggregatorx.app.engine.ai.AIDecisionEngine
import com.aggregatorx.app.engine.nlp.NaturalLanguageQueryProcessor
import com.aggregatorx.app.engine.nlp.ProcessedQuery
import com.aggregatorx.app.engine.scraper.SearchResultFilter
import com.aggregatorx.app.engine.network.CloudflareBypassEngine
import com.aggregatorx.app.engine.network.TlsClient
import com.aggregatorx.app.engine.network.TlsRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.aggregatorx.app.engine.util.EngineUtils
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Advanced Multi-Provider Scraping Engine
 *
 * Features:
 * - Concurrent resilient scraping (one failure never stops the engine)
 * - NLP-powered query understanding and semantic result matching
 * - Smart Navigation to bypass category/genre landing pages
 * - AI Learning integration for adaptive strategy selection
 * - Multi-layer bypass (Standard -> Cloudflare Bypass -> Headless)
 */
@Singleton
class ScrapingEngine @Inject constructor(
    private val providerDao: ProviderDao,
    private val scrapingConfigDao: ScrapingConfigDao,
    private val siteAnalysisDao: SiteAnalysisDao,
    private val smartNavigationEngine: SmartNavigationEngine,
    private val smartContentClassifier: SmartContentClassifier,
    private val aiDecisionEngine: AIDecisionEngine,
    private val cloudflareBypassEngine: CloudflareBypassEngine,
    private val endpointDiscoveryEngine: EndpointDiscoveryEngine,
    private val universalFormatParser: UniversalFormatParser,
    private val nlpProcessor: NaturalLanguageQueryProcessor
) {
    @Volatile
    private var currentProcessedQuery: ProcessedQuery? = null

    private val providerHealthMap = ConcurrentHashMap<String, ProviderHealth>()
    private val lastRequestTime = ConcurrentHashMap<String, Long>()

    companion object {
        private const val DEFAULT_TIMEOUT = 12000
        private const val DEFAULT_RETRY_COUNT = 1
        private const val DEFAULT_RETRY_DELAY = 300L
        private const val DEFAULT_RATE_LIMIT_MS = 50L
        private const val MAX_CONCURRENT_PROVIDERS = 4
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val TARGET_RESULTS_PER_PROVIDER = 50
        private const val MAX_PAGES_PER_PROVIDER_SEARCH = 2
        private const val PER_PROVIDER_TIMEOUT_MS = 25_000L
        private const val FAST_CANDIDATE_TIMEOUT_MS = 7_000L
        private const val RENDERED_FALLBACK_TIMEOUT_MS = 9_000L
        private const val MAX_SEARCH_CANDIDATES = 6
        private const val CANDIDATE_BATCH_SIZE = 3
        private const val MIN_RELEVANT_RESULTS_BEFORE_STOP = 5
        private const val TAG = "AggregatorSearch"

        private val CATEGORY_URL_PATTERNS = listOf(
            "/genre/", "/category/", "/browse/", "/filter/", "/tags/",
            "/type/", "/sort/", "/order/", "?genre=", "?category=",
            "?type=", "/all-", "/list/genre", "/movies/genre"
        )

        private val GENERIC_CATEGORY_NAMES = setOf(
            "action", "comedy", "drama", "horror", "thriller", "romance",
            "sci-fi", "documentary", "animation", "anime", "sports", "news",
            "music", "kids", "family", "adventure", "fantasy", "crime",
            "mystery", "western", "war", "history", "biography", "all movies",
            "all videos", "trending", "popular", "latest", "new releases",
            "top rated", "most viewed", "recommended"
        )

        private val CONTENT_URL_PATTERNS = listOf(
            "/watch", "/video", "/movie/", "/episode/", "/play",
            "/stream", "/view", "/v/", "/e/", "-watch", "-online",
            "-full", "-hd", "-720p", "-1080p", "-episode-"
        )
    }

    private data class CacheEntry(
        val results: List<ProviderSearchResults>,
        val timestamp: Long = System.currentTimeMillis()
    )

    private data class FetchOutcome(
        val document: Document,
        val body: String,
        val finalUrl: String,
        val statusCode: Int,
        val transport: String
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val resultCache = object : LinkedHashMap<String, CacheEntry>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > 100
    }

    var cacheResults: Boolean = true

    fun clearCache() {
        synchronized(resultCache) { resultCache.clear() }
    }

    /**
     * Search across all enabled providers concurrently.
     * Guaranteed to process every provider; failures are caught and reported individually.
     */
    fun searchAllProviders(
        query: String,
        pages: Map<String, Int> = emptyMap(),
        cache: Boolean = cacheResults
    ): Flow<ProviderSearchResults> = channelFlow {
        val processedQuery = nlpProcessor.processQuery(query)
        currentProcessedQuery = processedQuery
        val cacheKey = buildString {
            append(query)
            if (pages.isNotEmpty()) {
                append('|')
                append(pages.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" })
            }
        }

        if (cache) {
            val cachedEntry = synchronized(resultCache) { resultCache[cacheKey] }
            if (cachedEntry != null && System.currentTimeMillis() - cachedEntry.timestamp < CACHE_TTL_MS) {
                cachedEntry.results.forEach { send(it) }
                return@channelFlow
            }
        }

        var enabledProviders = providerDao.getEnabledProvidersSync()
        if (enabledProviders.isEmpty()) {
            close()
            return@channelFlow
        }

        enabledProviders = enabledProviders.sortedWith(
            compareByDescending<Provider> { it.successRate }
                .thenBy { it.avgResponseTime }
        )

        val semaphore = Semaphore(MAX_CONCURRENT_PROVIDERS)
        val results = java.util.Collections.synchronizedList(mutableListOf<ProviderSearchResults>())

        val jobs = enabledProviders.map { provider ->
            launch(Dispatchers.IO) {
                val result = try {
                    semaphore.withPermit {
                        withTimeoutOrNull(PER_PROVIDER_TIMEOUT_MS) {
                            safeSearchProvider(provider, query, pages[provider.id] ?: 0)
                        } ?: ProviderSearchResults(
                            provider = provider,
                            results = emptyList(),
                            searchTime = PER_PROVIDER_TIMEOUT_MS,
                            success = false,
                            errorMessage = "Timed out"
                        )
                    }
                } catch (e: CancellationException) {
                    ProviderSearchResults(provider, emptyList(), 0L, false, "Cancelled")
                } catch (e: Exception) {
                    ProviderSearchResults(provider, emptyList(), 0L, false, "Error: ${e.message?.take(100)}")
                }
                results.add(result)
                send(result)
            }
        }

        jobs.joinAll()
        if (cache && results.any { it.success }) {
            synchronized(resultCache) { resultCache[cacheKey] = CacheEntry(results.toList()) }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun safeSearchProvider(provider: Provider, query: String, pageOffset: Int = 0): ProviderSearchResults {
        val startTime = System.currentTimeMillis()
        val domain = extractDomain(provider.baseUrl)

        return try {
            val result = searchProviderSmart(provider, query, pageOffset)
            if (result.results.isEmpty()) {
                result
            } else {
                // Phone-build behavior (sync part 3): the live results pass through
                // the display filter; if nothing query-matching survives, the
                // provider reports an honest EMPTY instead of off-query results.
                val displayResults = SearchResultFilter.filterResultsForDisplay(
                    result.results,
                    query,
                    currentProcessedQuery
                )
                if (displayResults.isEmpty()) {
                    result.copy(
                        results = emptyList(),
                        success = false,
                        errorMessage = "No query-matching media results found",
                        totalResults = 0,
                        hasMore = false,
                        status = ProviderSearchStatus.EMPTY
                    )
                } else {
                    aiDecisionEngine.learnFromSuccess(
                        domain,
                        ScrapingStrategy.HYBRID,
                        null,
                        null,
                        null,
                        displayResults.size,
                        System.currentTimeMillis() - startTime
                    )
                    result.copy(
                        results = displayResults,
                        totalResults = displayResults.size,
                        success = true,
                        status = ProviderSearchStatus.RESULTS,
                        hasMore = true
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aiDecisionEngine.learnFromFailure(domain, "EXCEPTION", e.message, ScrapingStrategy.HTML_PARSING, null, provider.baseUrl)
            ProviderSearchResults(
                provider = provider,
                results = emptyList(),
                searchTime = System.currentTimeMillis() - startTime,
                success = false,
                errorMessage = e.message ?: e.javaClass.simpleName,
                status = ProviderSearchStatus.FAILED
            )
        }
    }

    suspend fun searchProviderSmart(provider: Provider, query: String, pageOffset: Int = 0): ProviderSearchResults {
        val startTime = System.currentTimeMillis()
        val processed = currentProcessedQuery
        return try {
            enforceRateLimit(provider.id)
            providerDao.incrementSearchCount(provider.id)
            val config = scrapingConfigDao.getConfigForProvider(provider.id)
            val analysis = siteAnalysisDao.getLatestAnalysis(provider.id)
            val merged = LinkedHashMap<String, SearchResult>()
            val primary = searchBoundedCandidates(provider, query, pageOffset, config, analysis)
            for (result in primary) {
                merged[result.url] = result
            }
            val primaryMatches = merged.values.count {
                SearchResultFilter.hasQueryAffinity(it, query, processed)
            }
            if (primaryMatches < 5 && processed != null && processed.isNaturalLanguage &&
                query.equals(processed.originalQuery, ignoreCase = true)
            ) {
                val refinements = processed.searchQueries
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals(query, ignoreCase = true) }
                    .distinctBy { it.lowercase() }
                    .take(2)
                for (refinement in refinements) {
                    val refined = searchBoundedCandidates(
                        provider, refinement, pageOffset, config, analysis,
                        candidateLimit = 2, useExpensiveFallback = false
                    )
                    for (result in refined) {
                        val existing = merged[result.url]
                        if (existing == null || result.relevanceScore > existing.relevanceScore) {
                            merged[result.url] = result
                        }
                    }
                    val matches = merged.values.count {
                        SearchResultFilter.hasQueryAffinity(it, query, processed)
                    }
                    if (matches >= 5) break
                }
            }
            val results = merged.values
                .sortedByDescending { it.relevanceScore }
                .take(TARGET_RESULTS_PER_PROVIDER)
            val elapsed = System.currentTimeMillis() - startTime
            updateProviderHealth(provider.id, results.isNotEmpty(), elapsed)
            if (results.isEmpty()) {
                providerDao.incrementFailedCount(provider.id)
            }
            ProviderSearchResults(
                provider = provider,
                results = results,
                searchTime = elapsed,
                success = results.isNotEmpty(),
                errorMessage = if (results.isEmpty()) "No query-matching media results found" else null,
                totalResults = results.size,
                hasMore = results.isNotEmpty(),
                status = if (results.isNotEmpty()) ProviderSearchStatus.RESULTS else ProviderSearchStatus.EMPTY
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(TAG, "provider=${provider.name} failed after ${elapsed}ms: ${e.message}")
            updateProviderHealth(provider.id, false, elapsed)
            ProviderSearchResults(
                provider = provider,
                results = emptyList(),
                searchTime = elapsed,
                success = false,
                errorMessage = e.message ?: e.javaClass.simpleName,
                totalResults = 0,
                hasMore = false,
                status = ProviderSearchStatus.FAILED
            )
        }
    }

    /**
     * Bounded candidate search: try the provider's candidate search URLs in small
     * parallel batches and merge everything they return by URL (keeping the
     * highest-scored copy of each result). When too few results match the query
     * and [useExpensiveFallback] is on, fall back to the expensive paths: submit
     * the site's own search form when one is known to exist, otherwise render the
     * first candidate in the WebView.
     */
    private suspend fun searchBoundedCandidates(
        provider: Provider,
        query: String,
        pageOffset: Int,
        config: ScrapingConfig?,
        analysis: SiteAnalysis?,
        candidateLimit: Int = MAX_SEARCH_CANDIDATES,
        useExpensiveFallback: Boolean = true
    ): List<SearchResult> {
        val candidates = buildSearchCandidates(provider, query, pageOffset, config, analysis)
            .take(candidateLimit.coerceAtLeast(1))
        val merged = LinkedHashMap<String, SearchResult>()

        fun mergeKeepingBest(results: List<SearchResult>) {
            for (result in results) {
                val existing = merged[result.url]
                if (existing == null || result.relevanceScore > existing.relevanceScore) {
                    merged[result.url] = result
                }
            }
        }

        for (batch in candidates.chunked(CANDIDATE_BATCH_SIZE)) {
            val outcomes = supervisorScope {
                batch.map { candidate ->
                    async(Dispatchers.IO) {
                        withTimeoutOrNull(FAST_CANDIDATE_TIMEOUT_MS + 1_000L) {
                            fetchFastOutcome(candidate, config, query, pageOffset, provider.baseUrl)
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            for (outcome in outcomes) {
                val extracted = extractFromFetchedOutcome(outcome, provider, query, config, analysis)
                Log.i(
                    TAG,
                    "provider=${provider.name} transport=${outcome.transport} status=${outcome.statusCode} " +
                        "bytes=${outcome.body.length} parsed=${extracted.size} url=${outcome.finalUrl.take(180)}"
                )
                mergeKeepingBest(extracted)
            }
        }

        var queryMatches = merged.values.count {
            SearchResultFilter.hasQueryAffinity(it, query, currentProcessedQuery)
        }

        if (useExpensiveFallback && queryMatches < MIN_RELEVANT_RESULTS_BEFORE_STOP) {
            val hasSearchForm = when {
                analysis?.searchFormSelector != null -> true
                analysis?.rawHtml != null -> Regex("<form\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(analysis.rawHtml!!)
                else -> false
            }
            if (hasSearchForm) {
                val formPage = withTimeoutOrNull(10_000L) {
                    HeadlessBrowserHelper.submitSearchForm(
                        provider.baseUrl,
                        query,
                        buildRequestHeaders(config, provider.baseUrl, provider.baseUrl, isPost = false),
                        5000,
                        5000
                    )
                }
                if (formPage != null) {
                    val outcome = FetchOutcome(
                        document = Jsoup.parse(formPage.html, formPage.finalUrl),
                        body = formPage.html,
                        finalUrl = formPage.finalUrl,
                        statusCode = formPage.statusCode,
                        transport = "webview-form"
                    )
                    val extracted = extractFromFetchedOutcome(outcome, provider, query, config, analysis)
                    Log.i(
                        TAG,
                        "provider=${provider.name} transport=webview-form status=${formPage.statusCode} " +
                            "bytes=${formPage.html.length} parsed=${extracted.size} url=${formPage.finalUrl.take(180)}"
                    )
                    mergeKeepingBest(extracted)
                } else {
                    Log.i(TAG, "provider=${provider.name} transport=webview-form no usable form response")
                }
            } else {
                val renderUrl = candidates.firstOrNull() ?: provider.url
                val renderedHtml = withTimeoutOrNull(RENDERED_FALLBACK_TIMEOUT_MS) {
                    HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(
                        url = renderUrl,
                        waitSelector = analysis?.resultContainerSelector ?: "body",
                        timeout = RENDERED_FALLBACK_TIMEOUT_MS.toInt()
                    )
                }
                if (!renderedHtml.isNullOrBlank()) {
                    val outcome = FetchOutcome(
                        document = Jsoup.parse(renderedHtml, renderUrl),
                        body = renderedHtml,
                        finalUrl = renderUrl,
                        statusCode = 200,
                        transport = "webview"
                    )
                    val extracted = extractFromFetchedOutcome(outcome, provider, query, config, analysis)
                    Log.i(TAG, "provider=${provider.name} transport=webview bytes=${renderedHtml.length} parsed=${extracted.size}")
                    for (result in extracted) {
                        merged.putIfAbsent(result.url, result)
                    }
                }
            }
        }

        queryMatches = merged.values.count {
            SearchResultFilter.hasQueryAffinity(it, query, currentProcessedQuery)
        }
        Log.i(
            TAG,
            "provider=${provider.name} searchSummary candidates=${candidates.size} " +
                "unique=${merged.size} queryMatches=$queryMatches"
        )
        return merged.values
            .sortedByDescending { it.relevanceScore }
            .take(TARGET_RESULTS_PER_PROVIDER)
    }

    private fun buildSearchCandidates(
        provider: Provider,
        query: String,
        pageOffset: Int,
        config: ScrapingConfig?,
        analysis: SiteAnalysis?
    ): List<String> {
        val encoding = config?.encoding?.takeIf { it.isNotBlank() } ?: "UTF-8"
        val encoded = runCatching { URLEncoder.encode(query, encoding) }
            .getOrElse { URLEncoder.encode(query, "UTF-8") }
        val page = pageOffset + 1
        val base = provider.baseUrl.trimEnd('/')
        val candidates = linkedSetOf<String>()

        // 1. The search URL learned from the site's own search form (most faithful).
        config?.searchUrlTemplate
            ?.replace("{baseUrl}", base)
            ?.replace("{query}", encoded)
            ?.replace("{page}", page.toString())
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?.let(candidates::add)

        // 2. A URL built from the search form in the analyzed HTML.
        analysis?.rawHtml
            ?.takeIf { it.isNotBlank() }
            ?.let { html -> runCatching { Jsoup.parse(html, analysis.url.ifBlank { provider.url }) }.getOrNull() }
            ?.let(smartNavigationEngine::findSearchForm)
            ?.let { form -> smartNavigationEngine.buildSearchUrlFromForm(base, form, query) }
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?.let(candidates::add)

        // 3. Common search URL shapes, then the provider's own pages last.
        candidates += "$base/search?q=$encoded&page=$page"
        candidates += "$base/?s=$encoded&paged=$page"
        candidates += "$base/?q=$encoded&page=$page"
        candidates += "$base/search?query=$encoded&page=$page"
        candidates += "$base/search/$encoded"
        candidates += "$base/find?q=$encoded&page=$page"
        candidates += provider.url
        candidates += "$base/"
        return candidates.toList().take(MAX_SEARCH_CANDIDATES)
    }

    private suspend fun fetchFastOutcome(
        url: String,
        config: ScrapingConfig?,
        query: String,
        pageOffset: Int,
        baseUrl: String
    ): FetchOutcome? = supervisorScope {
        val timeout = minOf(config?.timeout ?: DEFAULT_TIMEOUT, FAST_CANDIDATE_TIMEOUT_MS.toInt())
        val postBody = configuredPostBody(config, url, query, pageOffset + 1, baseUrl)
        val headers = buildRequestHeaders(config, url, baseUrl, isPost = postBody != null)
        val native = async(Dispatchers.IO) {
            if (!TlsClient.isAvailable) return@async null
            val response = TlsClient.execute(
                TlsRequest(
                    url = url,
                    method = if (postBody == null) "GET" else "POST",
                    headers = headers,
                    body = postBody ?: "",
                    clientProfile = TlsClient.DEFAULT_PROFILE,
                    timeoutMs = timeout
                )
            )
            response.body.takeIf {
                response.error.isNullOrBlank() &&
                    response.statusCode in 200..399 &&
                    isUsableBody(it)
            }?.let { body ->
                val finalUrl = response.finalUrl.ifBlank { url }
                FetchOutcome(
                    document = Jsoup.parse(body, finalUrl),
                    body = body,
                    finalUrl = finalUrl,
                    statusCode = response.statusCode,
                    transport = "native-tls"
                )
            }
        }
        val standard = async(Dispatchers.IO) {
            runCatching {
                val connection = Jsoup.connect(url)
                    .headers(headers)
                    .timeout(timeout)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .maxBodySize(8 * 1024 * 1024)
                if (postBody != null) {
                    connection.method(Connection.Method.POST)
                    connection.requestBody(postBody)
                }
                val response = connection.execute()
                val body = response.body()
                if (response.statusCode() !in 200..399 || !isUsableBody(body)) return@runCatching null
                val finalUrl = response.url().toString()
                FetchOutcome(
                    document = Jsoup.parse(body, finalUrl),
                    body = body,
                    finalUrl = finalUrl,
                    statusCode = response.statusCode(),
                    transport = "jsoup"
                )
            }.getOrNull()
        }

        listOf(native.await(), standard.await())
            .filterNotNull()
            .maxWithOrNull(compareBy<FetchOutcome> { it.body.length }.thenBy { it.transport == "native-tls" })
    }

    private fun isUsableBody(body: String): Boolean {
        if (body.length < 80) return false
        val sample = body.take(20_000).lowercase()
        return listOf(
            "cf_chl_opt",
            "checking your browser",
            "just a moment...",
            "enable javascript and cookies to continue"
        ).none(sample::contains)
    }

    /**
     * Headers for a scraping request. The configured headers/cookies from the
     * provider's scraping config are only sent when the request stays on the
     * config's own origin.
     */
    internal fun buildRequestHeaders(
        config: ScrapingConfig?,
        requestUrl: String,
        baseUrl: String,
        isPost: Boolean
    ): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        headers["User-Agent"] = (config?.userAgent?.takeIf { it.isNotBlank() } ?: "")
            .ifBlank { EngineUtils.DEFAULT_USER_AGENT }
        headers["Accept"] = "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8"
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers["Cache-Control"] = "no-cache, no-store"
        headers["Pragma"] = "no-cache"

        if (config != null && isSameOrigin(requestUrl, baseUrl)) {
            parseConfigEntries(config.headers).forEach { (name, value) ->
                putHeader(headers, name, value)
            }
            if (headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                val cookieHeader = configCookies(config).entries
                    .joinToString("; ") { "${it.key}=${it.value}" }
                if (cookieHeader.isNotBlank()) {
                    putHeader(headers, "Cookie", cookieHeader)
                }
            }
        }

        if (isPost && headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            putHeader(headers, "Content-Type", "application/x-www-form-urlencoded")
        }
        return headers
    }

    private fun putHeader(headers: MutableMap<String, String>, name: String, value: String) {
        if (name.isBlank() || value.isBlank()) return
        val existing = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }
        if (existing != null) headers.remove(existing)
        headers[name] = value
    }

    /**
     * Parse a config JSON blob (headers or cookies) into name/value pairs.
     * Accepts a JSON object of primitives, or an array of {name, value} objects
     * (also under a "cookies" key of an object).
     */
    private fun parseConfigEntries(raw: String): Map<String, String> {
        val parsed = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return linkedMapOf()
        val entries = linkedMapOf<String, String>()

        fun addCookieArray(array: JsonArray) {
            for (element in array) {
                val obj = element as? JsonObject ?: continue
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["key"] as? JsonPrimitive)?.contentOrNull
                val value = (obj["value"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["content"] as? JsonPrimitive)?.contentOrNull
                if (!name.isNullOrBlank() && value != null) {
                    entries[name] = value
                }
            }
        }

        when (parsed) {
            is JsonObject -> {
                for ((key, value) in parsed) {
                    when {
                        value is JsonPrimitive -> value.contentOrNull?.let { entries[key] = it }
                        value is JsonArray && key.equals("cookies", ignoreCase = true) ->
                            addCookieArray(value)
                    }
                }
            }
            is JsonArray -> addCookieArray(parsed)
            else -> Unit
        }
        return entries
    }

    private fun configCookies(config: ScrapingConfig): Map<String, String> =
        parseConfigEntries(config.cookies)

    /**
     * The POST body for a configured search, when the provider's config carries
     * postData AND the request URL is exactly the config's search URL template
     * (so the body belongs to this request). Null means: plain GET.
     */
    internal fun configuredPostBody(
        config: ScrapingConfig?,
        url: String,
        query: String,
        page: Int,
        baseUrl: String
    ): String? {
        val postData = config?.postData?.takeIf { it.isNotBlank() } ?: return null
        val encoding = config.encoding.ifBlank { "UTF-8" }
        val encoded = runCatching { URLEncoder.encode(query, encoding) }
            .getOrElse { URLEncoder.encode(query, "UTF-8") }
        val base = baseUrl.trimEnd('/')
        val templateUrl = config.searchUrlTemplate
            .replace("{baseUrl}", base)
            .replace("{query}", encoded)
            .replace("{page}", page.toString())
        if (!url.equals(templateUrl, ignoreCase = true)) return null
        return postData
            .replace("{baseUrl}", base)
            .replace("{query}", encoded)
            .replace("{page}", page.toString())
    }

    private fun isSameOrigin(firstUrl: String, secondUrl: String): Boolean = runCatching {
        fun effectivePort(uri: URI): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            uri.scheme.equals("http", ignoreCase = true) -> 80
            else -> -1
        }
        val first = URI(firstUrl)
        val second = URI(secondUrl)
        first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)
    }.getOrDefault(false)

    private fun normalizeForMatching(value: String): String = value.lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()

    private suspend fun extractFromFetchedOutcome(
        outcome: FetchOutcome,
        provider: Provider,
        query: String,
        config: ScrapingConfig?,
        analysis: SiteAnalysis?
    ): List<SearchResult> {
        // Combine every extractor that applies to this page; the merge in
        // searchBoundedCandidates dedupes by URL across candidates.
        val combined = mutableListOf<SearchResult>()

        val raw = outcome.body.trimStart()
        if (raw.startsWith('{') || raw.startsWith('[')) {
            combined += extractResultsFromJson(outcome.body, provider, query)
        }
        combined += extractResultsFromJsonLd(outcome.document, provider, query)
        if (config != null) {
            combined += runCatching { extractResultsWithConfig(outcome.document, provider, query, config) }
                .getOrDefault(emptyList())
        }
        if (analysis?.resultItemSelector != null) {
            combined += runCatching {
                outcome.document.select(analysis.resultItemSelector).mapNotNull { item ->
                    val title = extractBestTitle(item, query)
                    val resultUrl = extractUrlFromItem(item, provider.baseUrl, title)
                    if (title.length < 3 || resultUrl.isBlank()) {
                        null
                    } else {
                        val cleaned = cleanExtractedTitle(title, resultUrl, query)
                        if (cleaned.isEmpty()) null else SearchResult(
                            providerId = provider.id,
                            providerName = provider.name,
                            title = cleaned,
                            url = resultUrl,
                            description = extractBestDescription(item),
                            thumbnailUrl = extractBestThumbnail(item, provider.baseUrl),
                            relevanceScore = calculateRelevanceScore(cleaned, query, extractBestDescription(item), resultUrl)
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }
        combined += extractResultsGeneric(outcome.document, provider, query)

        return combined
            .filter { it.title.length >= 3 && it.url.startsWith("http") }
            .filterNot { SearchResultFilter.isLikelyCategory(it.title) }
            .filterNot { SearchResultFilter.isLikelyDuration(it.title) }
            .distinctBy { it.url }
            .sortedByDescending { it.relevanceScore }
            .take(TARGET_RESULTS_PER_PROVIDER)
    }

    private fun extractResultsFromJson(body: String, provider: Provider, query: String): List<SearchResult> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val objects = mutableListOf<JsonObject>()

        fun walk(element: JsonElement, depth: Int) {
            if (depth > 8 || objects.size >= 500) return
            when (element) {
                is JsonObject -> {
                    objects += element
                    element.values.forEach { walk(it, depth + 1) }
                }
                is JsonArray -> element.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(root, 0)

        fun JsonObject.elementFor(vararg keys: String): JsonElement? = entries
            .firstOrNull { entry -> keys.any { it.equals(entry.key, ignoreCase = true) } }
            ?.value
        fun JsonElement?.stringValue(): String? = when (this) {
            is JsonPrimitive -> contentOrNull
            is JsonObject -> elementFor("url", "src", "href")?.stringValue()
            is JsonArray -> firstOrNull()?.stringValue()
            else -> null
        }

        return objects.mapNotNull { item ->
            val title = item.elementFor("title", "name", "headline", "label")
                .stringValue()?.trim().orEmpty()
            val rawUrl = item.elementFor(
                "url", "link", "href", "permalink", "webpage_url", "canonicalUrl"
            ).stringValue()?.trim().orEmpty()
            if (title.length < 3 || rawUrl.isBlank()) return@mapNotNull null
            val resultUrl = normalizeUrl(rawUrl, provider.baseUrl)
            if (!resultUrl.startsWith("http")) return@mapNotNull null
            val cleaned = cleanExtractedTitle(title, resultUrl, query)
            if (cleaned.isEmpty()) return@mapNotNull null
            val description = item.elementFor("description", "summary", "excerpt", "snippet").stringValue()
            SearchResult(
                providerId = provider.id,
                providerName = provider.name,
                title = cleaned,
                url = resultUrl,
                description = description,
                thumbnailUrl = item.elementFor(
                    "thumbnail", "thumbnailUrl", "image", "poster", "cover", "artwork"
                ).stringValue()?.let { normalizeUrl(it, provider.baseUrl) },
                category = item.elementFor("category", "type", "genre").stringValue(),
                duration = item.elementFor("duration", "length").stringValue(),
                quality = item.elementFor("quality", "resolution").stringValue(),
                relevanceScore = calculateRelevanceScore(cleaned, query, description, resultUrl)
            )
        }
            .filterNot { SearchResultFilter.isLikelyCategory(it.title) }
            .filterNot { SearchResultFilter.isLikelyDuration(it.title) }
            .distinctBy { it.url }
            .take(150)
    }

    /**
     * Extract results from schema.org JSON-LD blocks (VideoObject, Movie, ...)
     * embedded in the page. Many video sites describe their real results there
     * even when the visible list is rendered by script.
     */
    private fun extractResultsFromJsonLd(document: Document, provider: Provider, query: String): List<SearchResult> {
        val objects = mutableListOf<JsonObject>()

        fun walk(element: JsonElement, depth: Int) {
            if (depth > 12 || objects.size >= 1000) return
            when (element) {
                is JsonObject -> {
                    objects += element
                    element.values.forEach { walk(it, depth + 1) }
                }
                is JsonArray -> element.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }

        document.select("script[type=application/ld+json]").forEach { script ->
            val content = script.data().ifBlank { script.html() }
            if (content.isBlank()) return@forEach
            runCatching { json.parseToJsonElement(content) }.getOrNull()?.let { walk(it, 0) }
        }

        fun JsonObject.elementFor(vararg keys: String): JsonElement? = entries
            .firstOrNull { entry -> keys.any { it.equals(entry.key, ignoreCase = true) } }
            ?.value
        fun JsonElement?.textValue(): String? = when (this) {
            is JsonPrimitive -> contentOrNull
            is JsonObject -> elementFor("url", "@id", "href", "name")?.textValue()
            is JsonArray -> firstNotNullOfOrNull { it.textValue() }
            else -> null
        }
        fun JsonElement?.values(): List<String> = when (this) {
            is JsonPrimitive -> listOfNotNull(contentOrNull)
            is JsonArray -> flatMap { it.values() }
            else -> emptyList()
        }

        val contentTypes = setOf(
            "videoobject", "movie", "tvepisode", "tvseries", "clip",
            "musicvideoobject", "podcastepisode", "podcastseries", "creativework"
        )
        val mediaExtensions = setOf(".m3u8", ".mp4", ".m4v", ".webm", ".mov", ".mpd", ".ts")

        return objects.mapNotNull { item ->
            val types = item.elementFor("@type")?.values()
                ?.map { it.substringAfterLast('/').substringAfterLast('#').lowercase() }
                .orEmpty()
            if (types.none { it in contentTypes }) return@mapNotNull null

            val title = item.elementFor("name", "headline")?.textValue()?.trim().orEmpty()
            val rawUrl = item.elementFor("url", "mainEntityOfPage", "@id")?.textValue()?.trim().orEmpty()
            if (title.length < 3 || rawUrl.isBlank()) return@mapNotNull null
            val resultUrl = normalizeUrl(rawUrl, provider.baseUrl)
            if (!resultUrl.startsWith("http", ignoreCase = true)) return@mapNotNull null

            // Direct media files are not result pages.
            val path = runCatching { (URI(resultUrl).path ?: "").lowercase() }.getOrDefault("")
            if (mediaExtensions.any { path.endsWith(it) }) return@mapNotNull null

            val cleaned = cleanExtractedTitle(title, resultUrl, query)
            if (cleaned.isEmpty() ||
                SearchResultFilter.isLikelyCategory(cleaned) ||
                SearchResultFilter.isLikelyDuration(cleaned)
            ) return@mapNotNull null

            val description = item.elementFor("description", "abstract")?.textValue()
            SearchResult(
                providerId = provider.id,
                providerName = provider.name,
                title = cleaned,
                url = resultUrl,
                description = description,
                thumbnailUrl = item.elementFor("thumbnailUrl", "image")?.textValue()
                    ?.let { normalizeUrl(it, provider.baseUrl) },
                category = item.elementFor("genre")?.values()?.joinToString(", "),
                date = item.elementFor("uploadDate", "datePublished", "dateCreated")?.textValue(),
                duration = item.elementFor("duration")?.textValue(),
                relevanceScore = calculateRelevanceScore(cleaned, query, description, resultUrl)
            )
        }.distinctBy { it.url }
    }

    private fun calculateRelevanceScore(title: String, query: String, description: String? = null, url: String? = null): Float {
        val normalizedTitle = normalizeForMatching(title)
        val normalizedQuery = normalizeForMatching(query)
        val normalizedDescription = normalizeForMatching(description ?: "")
        val terms = normalizedQuery.split(' ').filter { it.length >= 2 }.distinct()
        if (terms.isEmpty()) return 0f

        var score = 0f
        if (normalizedQuery.length >= 3 && normalizedTitle.contains(normalizedQuery)) score += 50f
        if (normalizedQuery.length >= 3 && normalizedDescription.contains(normalizedQuery)) score += 12f

        val titleWords = normalizedTitle.split(' ')
        val descriptionWords = normalizedDescription.split(' ')
        val titleHits = terms.count { titleWords.contains(it) }
        val descriptionHits = terms.count { descriptionWords.contains(it) }
        score += titleHits * 12f
        score += descriptionHits * 4f
        if (titleHits == terms.size) score += 12f
        if (normalizedTitle.startsWith(terms.first())) score += 4f

        // NLP semantic boost
        currentProcessedQuery?.let {
            score += 0.5f * nlpProcessor.calculateSemanticRelevance(title, description, it.concepts)
        }

        return score.coerceIn(0f, 100f)
    }

    private fun matchesQueryEnhanced(result: SearchResult, query: String): Boolean {
        return SearchResultFilter.hasQueryAffinity(result, query, currentProcessedQuery)
    }

    private fun extractResultsWithConfig(doc: Document, p: Provider, q: String, c: ScrapingConfig): List<SearchResult> {
        return doc.select(c.resultSelector).mapNotNull { item ->
            val title = extractConfiguredTitle(item, c.titleSelector, q)
            val url = extractUrl(item, c.urlSelector, p.baseUrl)
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
            val cleaned = cleanExtractedTitle(title, url, q)
            if (cleaned.isEmpty()) return@mapNotNull null
            SearchResult(
                providerId = p.id,
                providerName = p.name,
                title = cleaned,
                url = url,
                description = c.descriptionSelector?.let { extractText(item, it) },
                thumbnailUrl = c.thumbnailSelector?.let { extractImageUrl(item, it, p.baseUrl) },
                relevanceScore = calculateRelevanceScore(cleaned, q, null, url)
            )
        }
    }

    private fun extractResultsGeneric(doc: Document, p: Provider, q: String): List<SearchResult> {
        val selectorResults = detectResultItemSelector(doc)?.let { selector ->
            doc.select(selector).mapNotNull { item ->
                val title = extractBestTitle(item, q)
                val u = extractUrlFromItem(item, p.baseUrl, title)
                if (title.isEmpty() || u.isEmpty() || u.contains("javascript:")) null
                else {
                    // extractBestDescription is a pure read of the item; compute
                    // once instead of once per use below.
                    val description = extractBestDescription(item)
                    SearchResult(
                        providerId = p.id,
                        providerName = p.name,
                        title = title,
                        url = u,
                        description = description,
                        thumbnailUrl = extractBestThumbnail(item, p.baseUrl),
                        relevanceScore = calculateRelevanceScore(title, q, description, u)
                    )
                }
            }
        }.orEmpty()

        val contentLinkResults = smartNavigationEngine.extractContentLinks(doc, p.baseUrl).mapNotNull { link ->
            val title = link.title.takeIf { it.length > 2 } ?: extractTitleFromUrl(link.url) ?: return@mapNotNull null
            SearchResult(
                providerId = p.id,
                providerName = p.name,
                title = title,
                url = link.url,
                thumbnailUrl = link.thumbnail,
                duration = link.duration,
                relevanceScore = calculateRelevanceScore(title, q, url = link.url)
            )
        }

        val parsedResults = runBlocking {
            try {
                universalFormatParser.parseContent(doc, doc.location().ifEmpty { p.baseUrl }).items
            } catch (_: Exception) {
                emptyList()
            }
        }.mapNotNull { item ->
            val title = item.title.trim()
            val u = normalizeUrl(item.url, p.baseUrl)
            if (title.length < 3 || u.isEmpty() || u.contains("javascript:")) null
            else SearchResult(
                providerId = p.id,
                providerName = p.name,
                title = title,
                url = u,
                description = item.description,
                thumbnailUrl = item.thumbnail?.let { normalizeUrl(it, p.baseUrl) },
                duration = item.duration,
                quality = item.quality,
                category = item.contentType.name.lowercase(),
                relevanceScore = calculateRelevanceScore(title, q, item.description, u)
            )
        }

        return (selectorResults + contentLinkResults + parsedResults)
            .distinctBy { it.url }
            .sortedByDescending { it.relevanceScore }
    }

    private fun detectResultItemSelector(doc: Document): String? {
        val candidates = listOf(
            ".video-item", ".movie-item", ".search-result-item", ".result-item",
            ".result", ".item", ".card", ".entry", "article"
        )
        val scored = candidates.mapNotNull { selector ->
            val elements = runCatching { doc.select(selector) }.getOrNull()
                ?: return@mapNotNull null
            val count = elements.count { element ->
                extractBestTitle(element, "").isNotBlank() &&
                    runCatching {
                        element.select("a[href]").any { link ->
                            val href = link.attr("href").trim()
                            href.isNotBlank() &&
                                !href.startsWith("javascript:", ignoreCase = true) &&
                                !href.startsWith("mailto:", ignoreCase = true)
                        }
                    }.getOrDefault(false)
            }
            if (count >= 2) selector to count else null
        }
        return scored.maxWithOrNull(
            compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length }
        )?.first
    }

    private fun extractBestTitle(item: Element, query: String): String {
        val selectors = listOf(
            "[itemprop=name]", "[data-title]", ".video-title", ".movie-title",
            ".item-title", ".entry-title", "h2", "h3", "h4", ".title", ".name",
            "[class*='title']", "a[title]", "a", "img[alt]"
        )
        val candidates = mutableListOf<Pair<String, Int>>()
        selectors.forEachIndexed { index, selector ->
            runCatching { item.select(selector).take(10) }.getOrDefault(emptyList())
                .forEach { element -> candidates += titleValues(element, index * 6) }
        }
        val linkTitles = runCatching { item.select("a[href]").take(5) }.getOrDefault(emptyList())
            .mapIndexedNotNull { index, element ->
                extractTitleFromUrl(element.absUrl("href"))?.let { it to (index + 80) }
            }
        return chooseBestTitle(candidates + linkTitles, query)
    }

    private fun extractConfiguredTitle(item: Element, selector: String, query: String): String {
        val candidates = mutableListOf<Pair<String, Int>>()
        runCatching { item.select(selector).take(20) }.getOrDefault(emptyList())
            .forEachIndexed { index, element -> candidates += titleValues(element, index * 6) }
        return chooseBestTitle(candidates, query).ifBlank { extractBestTitle(item, query) }
    }

    private fun titleValues(element: Element, priority: Int): List<Pair<String, Int>> {
        val itempropValue = if (element.attr("itemprop").equals("name", ignoreCase = true)) {
            element.attr("content").ifBlank { element.text() }
        } else {
            ""
        }
        return listOf(
            element.attr("data-title") to priority,
            itempropValue to (priority + 1),
            element.attr("title") to (priority + 2),
            element.attr("aria-label") to (priority + 3),
            element.attr("alt") to (priority + 4),
            element.text() to (priority + 5)
        ).filter { it.first.isNotBlank() }
    }

    private fun chooseBestTitle(candidates: List<Pair<String, Int>>, query: String): String {
        val normalizedQuery = normalizeForMatching(query)
        val queryTerms = normalizedQuery.split(' ').filter { it.length >= 2 }
        val conceptWords = currentProcessedQuery?.conceptTerms
            ?.flatMap { normalizeForMatching(it).split(' ') }
            ?.filter { it.length >= 3 }
            .orEmpty()

        val scored = candidates.mapNotNull { (rawTitle, priority) ->
            val cleaned = rawTitle.replace(Regex("\\s+"), " ").trim()
            if (cleaned.length < 3 ||
                SearchResultFilter.isLikelyCategory(cleaned) ||
                SearchResultFilter.isLikelyDuration(cleaned)
            ) {
                return@mapNotNull null
            }
            val normalized = normalizeForMatching(cleaned)
            val words = normalized.split(' ')
            val termHits = queryTerms.count { words.contains(it) }
            val conceptHits = conceptWords.count { normalized.contains(it) }
            val phraseBonus = if (normalizedQuery.length >= 3 && normalized.contains(normalizedQuery)) 80 else 0
            cleaned to ((termHits * 40 + phraseBonus + conceptHits * 12) - priority)
        }
        return scored.maxByOrNull { it.second }?.first ?: ""
    }

    private fun cleanExtractedTitle(title: String, url: String, query: String): String {
        val cleaned = title.replace(Regex("\\s+"), " ").trim()
        if (cleaned.length >= 3 &&
            !SearchResultFilter.isLikelyDuration(cleaned) &&
            !SearchResultFilter.isLikelyCategory(cleaned)
        ) {
            return cleaned
        }
        return extractTitleFromUrl(url)?.takeIf {
            it.length >= 3 &&
                !SearchResultFilter.isLikelyDuration(it) &&
                !SearchResultFilter.isLikelyCategory(it)
        } ?: ""
    }

    private fun extractUrlFromItem(item: Element, base: String, preferredTitle: String = ""): String {
        val links = runCatching { item.select("a[href]") }.getOrDefault(emptyList())
            .filter { element ->
                val href = element.attr("href").trim()
                href.isNotEmpty() &&
                    !href.startsWith("javascript:", ignoreCase = true) &&
                    !href.startsWith("mailto:", ignoreCase = true)
            }
        if (links.isEmpty()) return ""

        val normalizedTitle = normalizeForMatching(preferredTitle)
        val best = links.maxByOrNull { element ->
            val label = listOf(
                element.attr("title"),
                element.attr("aria-label"),
                element.text()
            ).firstOrNull { it.isNotBlank() } ?: ""
            val normalizedLabel = normalizeForMatching(label)
            var score = 0
            if (normalizedTitle.isNotBlank() && normalizedLabel.isNotBlank() &&
                (normalizedLabel.contains(normalizedTitle) || normalizedTitle.contains(normalizedLabel))
            ) {
                score += 100
            }
            val imgAlt = element.selectFirst("img[alt]")?.attr("alt")
                ?.let { normalizeForMatching(it) } ?: ""
            if (normalizedTitle.isNotBlank() && imgAlt.contains(normalizedTitle)) {
                score += 80
            }
            score
        } ?: links.first()
        return normalizeUrl(best.attr("href"), base)
    }

    private fun extractBestDescription(item: Element): String? = item.select(".description, .desc, p").firstOrNull()?.text()?.take(200)

    private fun extractBestThumbnail(item: Element, base: String): String? {
        val imageElement = item.select("img, source, video, [data-thumb], [data-thumbnail], [data-poster], [data-image], [style*='background-image']").firstOrNull()
            ?: return null
        return extractImageCandidate(imageElement, base)
    }

    private fun extractText(el: Element, sel: String): String =
        runCatching { el.select(sel).text().replace(Regex("\\s+"), " ").trim() }.getOrDefault("")

    private fun extractUrl(el: Element, sel: String, base: String): String {
        val href = runCatching { el.select(sel).firstOrNull()?.attr("href") ?: "" }.getOrDefault("")
        val resolved = href.ifBlank {
            runCatching { el.selectFirst("a[href]")?.attr("href") ?: "" }.getOrDefault("")
        }
        return normalizeUrl(resolved, base)
    }

    private fun extractImageUrl(el: Element, sel: String, base: String): String {
        val imageElement = el.select(sel).firstOrNull() ?: return ""
        return extractImageCandidate(imageElement, base).orEmpty()
    }

    private fun extractImageCandidate(element: Element, base: String): String? {
        val srcset = element.attr("srcset").takeIf { it.isNotBlank() }
            ?: element.attr("data-srcset").takeIf { it.isNotBlank() }
        val fromSrcset = srcset
            ?.split(",")
            ?.map { it.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }
            ?.firstOrNull { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }

        val raw = listOf(
            element.attr("src"),
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("data-original"),
            element.attr("data-lazy"),
            element.attr("data-thumb"),
            element.attr("data-thumbnail"),
            element.attr("data-poster"),
            element.attr("data-image"),
            element.attr("poster"),
            fromSrcset,
            extractBackgroundImageUrl(element.attr("style"))
        ).firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:", ignoreCase = true) } ?: return null

        return normalizeUrl(raw, base)
    }

    private fun extractBackgroundImageUrl(style: String): String? {
        if (style.isBlank()) return null
        return Regex("""background(?:-image)?\s*:\s*url\(['"]?([^'")]+)['"]?\)""", RegexOption.IGNORE_CASE)
            .find(style)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun extractTitleFromUrl(url: String): String? = runCatching {
        val rawPath = URI(url).rawPath ?: ""
        val path = rawPath.trimEnd('/')
        if (path.isBlank()) return@runCatching null
        val skipSegments = setOf(
            "watch", "video", "videos", "movie", "movies", "detail", "details",
            "view", "play", "embed", "download", "item", "result", "search",
            "index", "home", "page", "channel", "category", "genre"
        )
        val extensionRegex = Regex("(?i)\\.(html?|php|aspx?)$")
        val segment = path.split('/').asReversed().firstOrNull { seg ->
            val candidate = URLDecoder.decode(seg, "UTF-8")
                .replace(extensionRegex, "")
                .trim()
            candidate.isNotBlank() && candidate.lowercase() !in skipSegments
        } ?: return@runCatching null
        val title = URLDecoder.decode(segment, "UTF-8")
            .replace(extensionRegex, "")
            .replace(Regex("[-_]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (title.length >= 3 && title.any { it.isLetter() } &&
            !SearchResultFilter.isLikelyCategory(title) &&
            !SearchResultFilter.isLikelyDuration(title)
        ) {
            title.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        } else {
            null
        }
    }.getOrNull()

    private fun normalizeUrl(url: String, base: String): String = EngineUtils.normalizeUrl(url, base)

    private fun extractDomain(url: String): String = EngineUtils.extractDomain(url)

    private fun enforceRateLimit(id: String) {
        val last = lastRequestTime[id] ?: 0L
        val wait = DEFAULT_RATE_LIMIT_MS - (System.currentTimeMillis() - last)
        if (wait > 0) Thread.sleep(wait)
        lastRequestTime[id] = System.currentTimeMillis()
    }

    private fun updateProviderHealth(id: String, success: Boolean, time: Long) {
        val h = providerHealthMap.getOrPut(id) { ProviderHealth() }
        providerHealthMap[id] = if (success) h.copy(successCount = h.successCount + 1, avgResponseTime = (h.avgResponseTime + time) / 2)
        else h.copy(failureCount = h.failureCount + 1)
    }

    data class ProviderHealth(val successCount: Int = 0, val failureCount: Int = 0, val avgResponseTime: Long = 0)
}
