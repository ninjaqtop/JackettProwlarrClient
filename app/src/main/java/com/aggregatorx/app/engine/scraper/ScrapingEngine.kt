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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.aggregatorx.app.engine.util.EngineUtils
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
            if (result.results.isNotEmpty()) {
                // Keep exactly what the provider's own search page returned, in the
                // provider's order. Re-filtering/re-sorting here is what made the app
                // diverge from the results a user sees when searching on the site.
                aiDecisionEngine.learnFromSuccess(
                    domain,
                    ScrapingStrategy.HYBRID,
                    null,
                    null,
                    null,
                    result.results.size,
                    System.currentTimeMillis() - startTime
                )
                result.copy(
                    totalResults = result.results.size,
                    success = true,
                    errorMessage = null,
                    status = ProviderSearchStatus.RESULTS,
                    // Preserve the engine's real next-page knowledge instead of
                    // forcing hasMore on for every provider.
                    hasMore = result.hasMore,
                    nextPageUrl = result.nextPageUrl
                )
            } else {
                result
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

    private suspend fun retryWithNlpQueries(provider: Provider, originalQuery: String, startTime: Long): ProviderSearchResults? {
        val processed = currentProcessedQuery ?: return null
        val variants = processed.searchQueries.filter { it.lowercase() != originalQuery.lowercase() }.take(3)
        if (variants.isEmpty()) return null

        val allResults = mutableListOf<SearchResult>()
        val seenUrls = mutableSetOf<String>()

        for (variant in variants) {
            try {
                val result = searchProviderSmart(provider, variant)
                if (result.success) {
                    val validated = validateAndFilterResults(result.results, originalQuery)
                    validated.forEach { if (seenUrls.add(it.url)) allResults.add(it) }
                }
                if (allResults.size >= TARGET_RESULTS_PER_PROVIDER) break
            } catch (e: Exception) { continue }
        }

        return if (allResults.isNotEmpty()) {
            ProviderSearchResults(provider, allResults.sortedByDescending { it.relevanceScore }, System.currentTimeMillis() - startTime, true)
        } else null
    }

    private fun validateAndFilterResults(results: List<SearchResult>, query: String): List<SearchResult> {
        return SearchResultFilter.filterResultsForDisplay(results, query, currentProcessedQuery, TARGET_RESULTS_PER_PROVIDER)
    }

    suspend fun searchProviderSmart(provider: Provider, query: String, pageOffset: Int = 0): ProviderSearchResults {
        val startTime = System.currentTimeMillis()
        // Send the user's query to the site exactly as typed, the same as typing it
        // into the site's own search box. NLP rewriting changed the results.
        val effectiveQuery = query.trim()

        return try {
            enforceRateLimit(provider.id)
            providerDao.incrementSearchCount(provider.id)
            val config = scrapingConfigDao.getConfigForProvider(provider.id)
            val analysis = siteAnalysisDao.getLatestAnalysis(provider.id)
            val outcome = searchBoundedCandidates(provider, effectiveQuery, pageOffset, config, analysis)
            val results = outcome.results
            val elapsed = System.currentTimeMillis() - startTime
            updateProviderHealth(provider.id, results.isNotEmpty(), elapsed)
            if (results.isEmpty()) providerDao.incrementFailedCount(provider.id)
            ProviderSearchResults(
                provider = provider,
                results = results,
                searchTime = elapsed,
                success = results.isNotEmpty(),
                errorMessage = if (results.isEmpty()) "No parseable results found on live pages" else null,
                totalResults = results.size,
                // Only claim there is another page when the provider's own page
                // actually linked to one, or the page came back completely full.
                // Guessing true here made Load More fetch duplicate/wrong pages.
                hasMore = outcome.hasMore,
                nextPageUrl = outcome.nextPageUrl,
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
                status = ProviderSearchStatus.FAILED
            )
        }
    }

    /**
     * Fetch one specific provider page by its exact URL — the real next-page
     * link discovered on the previous page — instead of guessing a page URL
     * from a template. The same verification/gating as a first-page search
     * applies, and the page's own next link is discovered in turn.
     */
    suspend fun searchProviderPageByUrl(provider: Provider, query: String, pageUrl: String): ProviderSearchResults {
        val startTime = System.currentTimeMillis()
        return try {
            enforceRateLimit(provider.id)
            providerDao.incrementSearchCount(provider.id)
            val config = scrapingConfigDao.getConfigForProvider(provider.id)
            val analysis = siteAnalysisDao.getLatestAnalysis(provider.id)
            val outcome = withTimeoutOrNull(FAST_CANDIDATE_TIMEOUT_MS + 1_000L) {
                fetchFastOutcome(pageUrl, config)
            }
            if (outcome == null || isHomepage(outcome.finalUrl, provider) || isNoResultsPage(outcome.document)) {
                return ProviderSearchResults(
                    provider = provider, results = emptyList(),
                    searchTime = System.currentTimeMillis() - startTime,
                    success = false, errorMessage = "Next page could not be loaded",
                    hasMore = false, status = ProviderSearchStatus.EMPTY
                )
            }
            val extracted = extractFromFetchedOutcome(outcome, provider, query, config, analysis)
            // This URL was either discovered as the site's own next-page link or
            // built from its learned template, so it is a trusted search request.
            val verified = isVerifiedSearchPage(outcome, pageUrl, query, config, analysis, trustedRequest = true)
            val gated = gateResultsForPage(extracted, query, verified)
            val currentPage = pageNumberOfUrl(pageUrl) ?: 1
            val nextPageUrl = if (gated.isNotEmpty()) discoverNextPageUrl(outcome, currentPage) else null
            val results = withSiteOrder(gated)
            ProviderSearchResults(
                provider = provider,
                results = results,
                searchTime = System.currentTimeMillis() - startTime,
                success = results.isNotEmpty(),
                errorMessage = if (results.isEmpty()) "No parseable results found on live pages" else null,
                totalResults = results.size,
                hasMore = nextPageUrl != null || gated.size >= TARGET_RESULTS_PER_PROVIDER,
                nextPageUrl = nextPageUrl,
                status = if (results.isNotEmpty()) ProviderSearchStatus.RESULTS else ProviderSearchStatus.EMPTY
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProviderSearchResults(
                provider = provider, results = emptyList(),
                searchTime = System.currentTimeMillis() - startTime,
                success = false, errorMessage = e.message ?: e.javaClass.simpleName,
                hasMore = false, status = ProviderSearchStatus.FAILED
            )
        }
    }

    private fun pageNumberOfUrl(url: String): Int? {
        Regex("[?&](?:page|paged|p)=(\\d+)").find(url)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("/page/(\\d+)").find(url)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    private data class BoundedSearchOutcome(
        val results: List<SearchResult>,
        val nextPageUrl: String?,
        /** True only when a real next page was linked, or the page came back
         *  completely full (a strong sign the provider has more pages). */
        val hasMore: Boolean = nextPageUrl != null
    )

    private suspend fun searchBoundedCandidates(
        provider: Provider,
        query: String,
        pageOffset: Int,
        config: ScrapingConfig?,
        analysis: SiteAnalysis?
    ): BoundedSearchOutcome {
        val candidates = buildSearchCandidates(provider, query, pageOffset, config, analysis)
            .take(MAX_SEARCH_CANDIDATES)
        val learnedUrls = buildLearnedSearchUrls(provider, query, pageOffset, config, analysis).toSet()

        // The site's own route comes first, in the site's own way:
        //
        // 1. POST search: if the analyzer saved a POST route (the site's search
        //    form submits POST), submit exactly that body to exactly that action.
        //    A POST route's results are the site's own — taken whole, in order.
        val postData = config?.postData?.takeIf { it.isNotBlank() }
        if (postData != null) {
            val actionUrl = candidates.firstOrNull()
            if (actionUrl != null) {
                val body = postData
                    .replace("{query}", URLEncoder.encode(query, "UTF-8"))
                    .replace("{page}", (pageOffset + 1).toString())
                val postOutcome = withTimeoutOrNull(FAST_CANDIDATE_TIMEOUT_MS + 1_000L) {
                    fetchPostOutcome(actionUrl, body, config)
                }
                if (postOutcome != null && !isHomepage(postOutcome.finalUrl, provider) &&
                    !isNoResultsPage(postOutcome.document)
                ) {
                    val extracted = extractFromFetchedOutcome(postOutcome, provider, query, config, analysis)
                    if (extracted.isNotEmpty()) {
                        val nextPageUrl = discoverNextPageUrl(postOutcome, pageOffset + 1)
                        return BoundedSearchOutcome(
                            withSiteOrder(extracted), nextPageUrl,
                            hasMore = nextPageUrl != null || extracted.size >= TARGET_RESULTS_PER_PROVIDER
                        )
                    }
                }
            }
        }

        // 2. JavaScript sites: if the analyzer found this site needs JavaScript,
        //    go straight to rendering the learned search URL in the WebView so
        //    the site's own scripts run its own search — the same code path a
        //    browser uses — instead of scraping a static shell page first.
        if (analysis?.requiresJavaScript == true) {
            val renderUrl = learnedUrls.firstOrNull() ?: candidates.firstOrNull()
            if (renderUrl != null) {
                val renderedHtml = withTimeoutOrNull(RENDERED_FALLBACK_TIMEOUT_MS) {
                    HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(
                        url = renderUrl,
                        waitSelector = analysis.resultItemSelector ?: analysis.resultContainerSelector ?: "body",
                        timeout = RENDERED_FALLBACK_TIMEOUT_MS.toInt()
                    )
                }
                if (!renderedHtml.isNullOrBlank()) {
                    val outcome = FetchOutcome(
                        document = Jsoup.parse(renderedHtml, renderUrl),
                        body = renderedHtml,
                        finalUrl = renderUrl,
                        statusCode = 200,
                        transport = "webview-first"
                    )
                    val extracted = extractFromFetchedOutcome(outcome, provider, query, config, analysis)
                    if (extracted.isNotEmpty() && !isNoResultsPage(outcome.document)) {
                        val verified = isVerifiedSearchPage(
                            outcome, renderUrl, query, config, analysis,
                            trustedRequest = renderUrl in learnedUrls
                        )
                        val gated = gateResultsForPage(extracted, query, verified)
                        if (gated.isNotEmpty()) {
                            val nextPageUrl = discoverNextPageUrl(outcome, pageOffset + 1)
                            return BoundedSearchOutcome(
                                withSiteOrder(gated), nextPageUrl,
                                hasMore = nextPageUrl != null || gated.size >= TARGET_RESULTS_PER_PROVIDER
                            )
                        }
                    }
                }
            }
        }

        // Candidates are ordered best-first (the site's real search URL comes first).
        // We take the FIRST page that is verified as a real search-results page for
        // this query and use only that page, in the site's own order. A page that
        // cannot be verified only contributes results that actually match the query.
        // A page with no matching results at all is NOT a fallback: returning its
        // links anyway is what produced the "random results" bug.
        for (batch in candidates.chunked(CANDIDATE_BATCH_SIZE)) {
            val outcomes = supervisorScope {
                batch.map { candidate ->
                    async(Dispatchers.IO) {
                        candidate to withTimeoutOrNull(FAST_CANDIDATE_TIMEOUT_MS + 1_000L) {
                            fetchFastOutcome(candidate, config)
                        }
                    }
                }.awaitAll()
            }

            for ((candidate, outcome) in outcomes) {
                if (outcome == null) continue
                if (isHomepage(outcome.finalUrl, provider)) {
                    Log.i(TAG, "provider=${provider.name} candidate redirected to homepage, skipping: $candidate")
                    continue
                }
                val extracted = extractFromFetchedOutcome(outcome, provider, query, config, analysis)
                Log.i(
                    TAG,
                    "provider=${provider.name} transport=${outcome.transport} status=${outcome.statusCode} " +
                        "bytes=${outcome.body.length} parsed=${extracted.size} url=${outcome.finalUrl.take(180)}"
                )
                if (extracted.isEmpty()) continue
                if (isNoResultsPage(outcome.document)) {
                    Log.i(TAG, "provider=${provider.name} candidate is a no-results page, skipping: ${outcome.finalUrl.take(180)}")
                    continue
                }
                val verified = isVerifiedSearchPage(
                    outcome, candidate, query, config, analysis,
                    trustedRequest = candidate in learnedUrls
                )
                val gated = gateResultsForPage(extracted, query, verified)
                if (gated.isNotEmpty()) {
                    val nextPageUrl = discoverNextPageUrl(outcome, pageOffset + 1)
                    return BoundedSearchOutcome(
                        withSiteOrder(gated),
                        nextPageUrl,
                        hasMore = nextPageUrl != null || gated.size >= TARGET_RESULTS_PER_PROVIDER
                    )
                }
                Log.i(
                    TAG,
                    "provider=${provider.name} candidate rejected: not a verified search page " +
                        "with results matching the query url=${outcome.finalUrl.take(180)}"
                )
            }
        }

        // Nothing matched over plain HTTP: render the real search page (or submit the
        // site's own search form) in the WebView, which handles JS-rendered results.
        val renderUrl = candidates.firstOrNull()
        val renderedHtml = renderUrl?.let {
            withTimeoutOrNull(RENDERED_FALLBACK_TIMEOUT_MS) {
                HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(
                    url = it,
                    waitSelector = analysis?.resultItemSelector ?: analysis?.resultContainerSelector ?: "body",
                    timeout = RENDERED_FALLBACK_TIMEOUT_MS.toInt()
                )
            }
        }
        if (renderUrl != null && !renderedHtml.isNullOrBlank()) {
            val outcome = FetchOutcome(
                document = Jsoup.parse(renderedHtml, renderUrl),
                body = renderedHtml,
                finalUrl = renderUrl,
                statusCode = 200,
                transport = "webview"
            )
            val extracted = extractFromFetchedOutcome(outcome, provider, query, config, analysis)
            Log.i(TAG, "provider=${provider.name} transport=webview bytes=${renderedHtml.length} parsed=${extracted.size}")
            if (extracted.isNotEmpty() && !isNoResultsPage(outcome.document)) {
                // The rendered fallback used to return any non-empty extraction
                // with no checks at all. Apply the same verification/gating as
                // the fast HTTP path so JS-rendered homepage/trending links are
                // not presented as search results either.
                val verified = isVerifiedSearchPage(
                    outcome, renderUrl, query, config, analysis,
                    trustedRequest = renderUrl in learnedUrls
                )
                val gated = gateResultsForPage(extracted, query, verified)
                if (gated.isNotEmpty()) {
                    val nextPageUrl = discoverNextPageUrl(outcome, pageOffset + 1)
                    return BoundedSearchOutcome(
                        withSiteOrder(gated), nextPageUrl,
                        hasMore = nextPageUrl != null || gated.size >= TARGET_RESULTS_PER_PROVIDER
                    )
                }
            }
        }

        val formUrl = runCatching { HeadlessBrowserHelper.searchViaHeadlessForm(provider.url, query) }.getOrNull()
        if (!formUrl.isNullOrBlank() && formUrl.startsWith("http") && formUrl != renderUrl) {
            val formHtml = withTimeoutOrNull(RENDERED_FALLBACK_TIMEOUT_MS) {
                HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(
                    url = formUrl,
                    waitSelector = analysis?.resultItemSelector ?: "body",
                    timeout = RENDERED_FALLBACK_TIMEOUT_MS.toInt()
                )
            }
            if (!formHtml.isNullOrBlank() && !isHomepage(formUrl, provider)) {
                val outcome = FetchOutcome(Jsoup.parse(formHtml, formUrl), formHtml, formUrl, 200, "webview-form")
                val extracted = extractFromFetchedOutcome(outcome, provider, query, config, analysis)
                if (extracted.isNotEmpty() && !isNoResultsPage(outcome.document)) {
                    // Submitting the site's own search form is strong evidence this
                    // is a real search page, unless it dumped us on the homepage.
                    val gated = gateResultsForPage(extracted, query, verified = true)
                    if (gated.isNotEmpty()) {
                        val nextPageUrl = discoverNextPageUrl(outcome, pageOffset + 1)
                        return BoundedSearchOutcome(
                            withSiteOrder(gated), nextPageUrl,
                            hasMore = nextPageUrl != null || gated.size >= TARGET_RESULTS_PER_PROVIDER
                        )
                    }
                }
            }
        }

        // No candidate produced a verified search page with matching results.
        // Report an honest empty result for this provider. Never substitute the
        // links from a non-matching page: that is the random-results bug.
        return BoundedSearchOutcome(emptyList(), null)
    }

    private fun queryTerms(query: String): List<String> =
        query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }

    /**
     * Decide whether a fetched page really is a search-results page for [query],
     * as opposed to a homepage, category, tag or "latest" page that merely
     * contains extractable links.
     */
    private fun isVerifiedSearchPage(
        outcome: FetchOutcome,
        requestedUrl: String,
        query: String,
        config: ScrapingConfig?,
        analysis: SiteAnalysis?,
        trustedRequest: Boolean = false
    ): Boolean {
        val terms = queryTerms(query)
        val finalUrl = outcome.finalUrl
        val finalLower = finalUrl.lowercase()
        val decodedFinal = runCatching { URLDecoder.decode(finalUrl, "UTF-8") }.getOrDefault(finalUrl).lowercase()

        // A category/genre landing is never a search page, even if it has links.
        if (CATEGORY_URL_PATTERNS.any { finalLower.contains(it) } &&
            terms.none { decodedFinal.contains(it) }
        ) return false

        // 1. The query survived into the final URL (possibly after redirects).
        if (terms.isNotEmpty() && terms.any { decodedFinal.contains(it) }) return true
        if (finalLower.contains("search") &&
            listOf("q=", "query=", "s=", "keyword", "search_query").any { finalLower.contains(it) }
        ) return true

        // 2. The redirect kept the requested search path (e.g. /search/...) even
        // though the query itself is not visible in the final URL.
        fun pathOf(u: String) = u.substringBefore('?').substringBefore('#')
            .removePrefix("https://").removePrefix("http://")
            .substringAfter('/', "").lowercase()
        if (pathOf(finalUrl).isNotBlank() && pathOf(finalUrl) == pathOf(requestedUrl) &&
            (pathOf(finalUrl).contains("search") || pathOf(finalUrl).contains("find"))
        ) return true

        val document = outcome.document

        // 3. The page's own search box still holds the user's query.
        val searchInputHasQuery = runCatching {
            document.select("input[type=search], input[name*=q], input[name*=query], input[name*=search], input[name=s], input[name=keyword]")
                .any { input ->
                    val value = input.attr("value").trim().lowercase()
                    value.isNotBlank() && (value == query.trim().lowercase() || terms.any { value.contains(it) })
                }
        }.getOrDefault(false)
        if (searchInputHasQuery) return true

        // 4. The learned result container/item structure is present on this page.
        // Only trusted for requests to a learned search URL (stored template or
        // the site's own search form): the same item markup also appears on
        // category and listing pages, so for a guessed URL this signal alone
        // must not verify the page.
        val containerSelectors = listOfNotNull(
            config?.resultContainerSelector, config?.resultItemSelector,
            analysis?.resultContainerSelector, analysis?.resultItemSelector
        ).filter { it.isNotBlank() }
        val hasContainer = containerSelectors.any { selector ->
            runCatching { document.select(selector).isNotEmpty() }.getOrDefault(false)
        }
        if (hasContainer && trustedRequest) return true

        // 5. Page title announces results for this query.
        val title = runCatching { document.title().lowercase() }.getOrDefault("")
        if (terms.isNotEmpty() && terms.any { title.contains(it) } &&
            (title.contains("search") || title.contains("result"))
        ) return true

        return false
    }

    /**
     * Results from a verified search page are kept whole, in site order: the
     * site's own ranking decides relevance there. Results from an unverified
     * page are only kept when they actually match the query, so generic page
     * links can never leak in as "random results".
     */
    private fun gateResultsForPage(
        extracted: List<SearchResult>,
        query: String,
        verified: Boolean
    ): List<SearchResult> {
        if (verified) return extracted
        val queryLower = query.trim().lowercase()
        return extracted.filter { result ->
            matchesQueryEnhanced(result, query) ||
                (queryLower.length >= 2 &&
                    "${result.title} ${result.url}".lowercase().contains(queryLower))
        }
    }

    private fun isNoResultsPage(document: Document): Boolean {
        val text = runCatching { document.body().text().lowercase() }.getOrDefault("")
        if (text.isBlank()) return false
        return listOf(
            "no results found", "no result found", "no matches found",
            "no items found", "nothing found for", "no search results",
            "0 results for", "we couldn't find", "we could not find"
        ).any { text.contains(it) }
    }

    /**
     * Find the provider page's real next-page URL: an explicit next link first,
     * then a numbered pagination link for exactly the following page. Returns
     * null when the page offers no next page, so Load More can honestly stop.
     */
    private fun discoverNextPageUrl(outcome: FetchOutcome, currentPage: Int): String? {
        val document = outcome.document
        val currentUrl = outcome.finalUrl
        fun usable(raw: String?): String? {
            val href = raw?.trim().orEmpty()
            if (href.isBlank() || href.startsWith("#") || href.startsWith("javascript:", true)) return null
            val absolute = runCatching { normalizeUrl(href, currentUrl) }.getOrNull() ?: return null
            return absolute.takeIf { it.startsWith("http") && it != currentUrl }
        }

        val explicitNext = runCatching {
            document.select(
                "a[rel~=next], a.next, .next a, .pagination .next a, " +
                    "a[aria-label*=Next i], [class*=pagination] a:contains(Next), .pagination a:contains(›), .pagination a:contains(»)"
            ).firstNotNullOfOrNull { usable(it.attr("href")) }
        }.getOrNull()
        if (explicitNext != null) return explicitNext

        val links = runCatching {
            smartNavigationEngine.getPaginationLinks(document, currentUrl)
        }.getOrDefault(emptyList())
        fun pageNumberOf(url: String): Int? {
            Regex("[?&](?:page|paged|p)=(\\d+)").find(url)?.let { return it.groupValues[1].toIntOrNull() }
            Regex("/page/(\\d+)").find(url)?.let { return it.groupValues[1].toIntOrNull() }
            return null
        }
        return links.firstOrNull { it != currentUrl && pageNumberOf(it) == currentPage + 1 }
    }

    /**
     * Stamp results with their position on the provider's page. Downstream code
     * (ranking, normalizer, AI tab) sorts by relevanceScore, so a position-based
     * score keeps the site's own order everywhere.
     */
    private fun withSiteOrder(results: List<SearchResult>): List<SearchResult> {
        val list = results.take(TARGET_RESULTS_PER_PROVIDER)
        return list.mapIndexed { index, result ->
            result.copy(
                relevanceScore = (100f - index * (50f / list.size.coerceAtLeast(1))).coerceIn(50f, 100f),
                metadata = result.metadata + ("siteRank" to (index + 1).toString())
            )
        }
    }

    private fun isHomepage(url: String, provider: Provider): Boolean {
        fun norm(u: String) = u.substringBefore('#').trimEnd('/').lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val target = norm(url)
        return target == norm(provider.baseUrl) || target == norm(provider.url)
    }

    /**
     * The search URLs actually learned from this provider: the stored template
     * and the URL built from the site's own search form. These are the only
     * candidates whose identity as search URLs is known rather than guessed.
     */
    private fun buildLearnedSearchUrls(
        provider: Provider,
        query: String,
        pageOffset: Int,
        config: ScrapingConfig?,
        analysis: SiteAnalysis?
    ): List<String> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val page = pageOffset + 1
        val base = provider.baseUrl.trimEnd('/')
        val learned = linkedSetOf<String>()

        // 1. The search URL learned from the site's own search form (most faithful).
        config?.searchUrlTemplate
            ?.let { template ->
                // Page 1 on a site is the URL without a page param; adding page=1
                // changes results on some sites.
                if (page == 1) template.replace("&page={page}", "").replace("?page={page}&", "?").replace("?page={page}", "")
                else template
            }
            ?.replace("{baseUrl}", base)
            ?.replace("{query}", encoded)
            ?.replace("{page}", page.toString())
            ?.takeIf { it.startsWith("http") }
            ?.let(learned::add)

        // 2. A URL built from the search form in the analyzed HTML.
        analysis?.rawHtml
            ?.takeIf { it.isNotBlank() }
            ?.let { html -> runCatching { Jsoup.parse(html, analysis.url.ifBlank { provider.url }) }.getOrNull() }
            ?.let(smartNavigationEngine::findSearchForm)
            ?.let { form -> smartNavigationEngine.buildSearchUrlFromForm(base, form, query) }
            ?.let { if (page > 1) "$it&page=$page" else it }
            ?.takeIf { it.startsWith("http") }
            ?.let(learned::add)

        return learned.toList()
    }

    private fun buildSearchCandidates(
        provider: Provider,
        query: String,
        pageOffset: Int,
        config: ScrapingConfig?,
        analysis: SiteAnalysis?
    ): List<String> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val page = pageOffset + 1
        val base = provider.baseUrl.trimEnd('/')
        val candidates = linkedSetOf<String>()
        candidates += buildLearnedSearchUrls(provider, query, pageOffset, config, analysis)

        // Common search URL shapes. The homepage is never a candidate: its links
        // are not search results and used to crowd out the real ones.
        val pageSuffix = if (page > 1) "&page=$page" else ""
        candidates += "$base/search?q=$encoded$pageSuffix"
        candidates += "$base/?s=$encoded${if (page > 1) "&paged=$page" else ""}"
        candidates += "$base/search?query=$encoded$pageSuffix"
        candidates += "$base/search/$encoded"
        candidates += "$base/?q=$encoded$pageSuffix"
        candidates += "$base/find?q=$encoded$pageSuffix"
        return candidates.toList()
    }

    private suspend fun fetchFastOutcome(url: String, config: ScrapingConfig?): FetchOutcome? = supervisorScope {
        val timeout = minOf(config?.timeout ?: DEFAULT_TIMEOUT, FAST_CANDIDATE_TIMEOUT_MS.toInt())
        val headers = mapOf(
            "User-Agent" to (config?.userAgent ?: EngineUtils.DEFAULT_USER_AGENT),
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "no-cache, no-store",
            "Pragma" to "no-cache"
        )
        val native = async(Dispatchers.IO) {
            if (!TlsClient.isAvailable) return@async null
            val response = TlsClient.execute(
                TlsRequest(
                    url = url,
                    headers = headers,
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
                val response = Jsoup.connect(url)
                    .headers(headers)
                    .timeout(timeout)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .maxBodySize(8 * 1024 * 1024)
                    .execute()
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

        // Prefer the response that stayed on the requested search URL. Picking
        // purely by body size let a fat landing/category page from one transport
        // beat the lean true search-results page from the other.
        fun stayedOnSearchPage(outcome: FetchOutcome): Boolean {
            fun pathOf(u: String) = u.substringBefore('?').substringBefore('#')
                .removePrefix("https://").removePrefix("http://")
                .substringAfter('/', "").lowercase()
            return outcome.finalUrl == url || pathOf(outcome.finalUrl) == pathOf(url)
        }
        listOf(native.await(), standard.await())
            .filterNotNull()
            .maxWithOrNull(
                compareBy<FetchOutcome> { stayedOnSearchPage(it) }
                    .thenBy { it.body.length }
                    .thenBy { it.transport == "native-tls" }
            )
    }

    /**
     * Submit a POST search exactly as the site's own form would: same action
     * URL, same fields, form-encoded body. Native TLS transport first-class,
     * Jsoup as the parallel fallback, same winner rules as the GET path.
     */
    private suspend fun fetchPostOutcome(url: String, formBody: String, config: ScrapingConfig?): FetchOutcome? = supervisorScope {
        val timeout = minOf(config?.timeout ?: DEFAULT_TIMEOUT, FAST_CANDIDATE_TIMEOUT_MS.toInt())
        val headers = mapOf(
            "User-Agent" to (config?.userAgent ?: EngineUtils.DEFAULT_USER_AGENT),
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Cache-Control" to "no-cache, no-store"
        )
        val native = async(Dispatchers.IO) {
            if (!TlsClient.isAvailable) return@async null
            val response = TlsClient.execute(
                TlsRequest(
                    url = url,
                    method = "POST",
                    headers = headers,
                    body = formBody,
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
                    transport = "native-tls-post"
                )
            }
        }
        val standard = async(Dispatchers.IO) {
            runCatching {
                val response = Jsoup.connect(url)
                    .headers(headers)
                    .timeout(timeout)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .requestBody(formBody)
                    .method(org.jsoup.Connection.Method.POST)
                    .maxBodySize(8 * 1024 * 1024)
                    .execute()
                val body = response.body()
                if (response.statusCode() !in 200..399 || !isUsableBody(body)) return@runCatching null
                val finalUrl = response.url().toString()
                FetchOutcome(
                    document = Jsoup.parse(body, finalUrl),
                    body = body,
                    finalUrl = finalUrl,
                    statusCode = response.statusCode(),
                    transport = "jsoup-post"
                )
            }.getOrNull()
        }
        listOf(native.await(), standard.await())
            .filterNotNull()
            .maxWithOrNull(compareBy<FetchOutcome> { it.body.length }.thenBy { it.transport == "native-tls-post" })
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

    private suspend fun extractFromFetchedOutcome(
        outcome: FetchOutcome,
        provider: Provider,
        query: String,
        config: ScrapingConfig?,
        analysis: SiteAnalysis?
    ): List<SearchResult> {
        fun clean(list: List<SearchResult>) = list
            .filter { it.title.length >= 3 && it.url.startsWith("http") }
            .distinctBy { it.url }

        // Use the most specific extractor that works and keep the page's order.
        // Mixing every extractor and sorting by our own score reshuffled results
        // and pulled in nav/sidebar links.
        val raw = outcome.body.trimStart()
        if (raw.startsWith('{') || raw.startsWith('[')) {
            clean(extractResultsFromJson(outcome.body, provider, query)).takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (config != null) {
            clean(runCatching { extractResultsWithConfig(outcome.document, provider, query, config) }
                .getOrDefault(emptyList())).takeIf { it.size >= 2 }?.let { return it }
        }
        if (analysis?.resultItemSelector != null) {
            clean(runCatching {
                outcome.document.select(analysis.resultItemSelector).mapNotNull { item ->
                    val title = extractBestTitle(item)
                    val resultUrl = extractUrlFromItem(item, provider.baseUrl)
                    if (title.length < 3 || resultUrl.isBlank()) null else SearchResult(
                        providerId = provider.id,
                        providerName = provider.name,
                        title = title,
                        url = resultUrl,
                        description = extractBestDescription(item),
                        thumbnailUrl = extractBestThumbnail(item, provider.baseUrl),
                        relevanceScore = calculateRelevanceScore(title, query, extractBestDescription(item), resultUrl)
                    )
                }
            }.getOrDefault(emptyList())).takeIf { it.size >= 2 }?.let { return it }
        }
        return clean(extractResultsGeneric(outcome.document, provider, query, keepPageOrder = true))
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
                "url", "link", "href", "permalink", "webpage_url", "canonicalUrl", "contentUrl"
            ).stringValue()?.trim().orEmpty()
            if (title.length < 3 || rawUrl.isBlank()) return@mapNotNull null
            val resultUrl = normalizeUrl(rawUrl, provider.baseUrl)
            if (!resultUrl.startsWith("http")) return@mapNotNull null
            val description = item.elementFor("description", "summary", "excerpt", "snippet").stringValue()
            SearchResult(
                providerId = provider.id,
                providerName = provider.name,
                title = title,
                url = resultUrl,
                description = description,
                thumbnailUrl = item.elementFor(
                    "thumbnail", "thumbnailUrl", "image", "poster", "cover", "artwork"
                ).stringValue()?.let { normalizeUrl(it, provider.baseUrl) },
                category = item.elementFor("category", "type", "genre").stringValue(),
                duration = item.elementFor("duration", "length").stringValue(),
                quality = item.elementFor("quality", "resolution").stringValue(),
                relevanceScore = calculateRelevanceScore(title, query, description, resultUrl)
            )
        }.distinctBy { it.url }.take(150)
    }

    private suspend fun scrapeWithSmartNavigation(provider: Provider, query: String, searchUrl: String, pageOffset: Int = 0): List<SearchResult> = withContext(Dispatchers.IO) {
        val document = fetchDocument(searchUrl)
        val (activeUrl, activeDoc) = if (smartNavigationEngine.isCategoryPage(searchUrl, document)) {
            smartNavigationEngine.navigatePastCategory(provider.baseUrl, document, query) ?: (searchUrl to document)
        } else searchUrl to document

        collectPagedResults(
            firstDoc = activeDoc,
            firstUrl = activeUrl,
            provider = provider,
            query = query,
            pageOffset = pageOffset,
            extractor = { doc -> extractResultsWithThumbnails(doc, provider, query) }
        )
    }

    private fun extractResultsWithThumbnails(document: Document, provider: Provider, query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val contentLinks = smartNavigationEngine.extractContentLinks(document, provider.baseUrl)

        for (link in contentLinks) {
            val title = link.title.takeIf { it.length > 2 } ?: extractTitleFromUrl(link.url) ?: continue
            val result = SearchResult(
                title = title,
                url = link.url,
                thumbnailUrl = link.thumbnail, // Fix: Use correct field
                description = findDescriptionInDocument(document, link.url),
                providerId = provider.id,
                providerName = provider.name,
                relevanceScore = calculateRelevanceScore(title, query, null, link.url)
            )
            if (matchesQueryEnhanced(result, query)) results.add(result)
        }
        
        return if (results.size < 5) results + extractResultsGeneric(document, provider, query) else results
    }

    private suspend fun fetchDocument(url: String, config: ScrapingConfig? = null): Document = withContext(Dispatchers.IO) {
        var lastEx: Exception? = null
        val timeout = config?.timeout ?: DEFAULT_TIMEOUT

        // 1. Standard Jsoup
        repeat(DEFAULT_RETRY_COUNT) { attempt ->
            try {
                val conn = Jsoup.connect(url)
                    .userAgent(getRandomUserAgent())
                    .timeout(timeout)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                val resp = conn.execute()
                val doc = resp.parse()
                if (resp.statusCode() in 200..299 && !doc.html().contains("cf_chl_opt")) return@withContext doc
            } catch (e: Exception) { lastEx = e; delay(DEFAULT_RETRY_DELAY * (attempt + 1)) }
        }

        // 2. CF Bypass
        cloudflareBypassEngine.fetchJsoupDocument(url, timeout)?.let { return@withContext it }

        // 3. Headless
        val headless = HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(url, null, timeout)
        if (!headless.isNullOrEmpty()) return@withContext Jsoup.parse(headless, url)

        throw lastEx ?: Exception("Fetch failed")
    }

    private fun calculateRelevanceScore(title: String, query: String, description: String? = null, url: String? = null): Float {
        val titleLower = title.lowercase()
        val queryLower = query.lowercase()
        val terms = queryLower.split(Regex("\\s+")).filter { it.length > 1 }
        if (terms.isEmpty()) return 0f

        var score = 0f
        if (titleLower.contains(queryLower)) score += 50f
        
        terms.forEach { term ->
            if (titleLower.contains(term)) {
                score += 10f
                if (titleLower.startsWith(term)) score += 5f
            }
        }

        // NLP semantic boost
        currentProcessedQuery?.let {
            val semantic = nlpProcessor.calculateSemanticRelevance(title, description, it.concepts)
            score += semantic * 0.5f
        }

        return score.coerceIn(0f, 100f)
    }

    private fun matchesQueryEnhanced(result: SearchResult, query: String): Boolean {
        val combined = "${result.title} ${result.description ?: ""} ${result.url}".lowercase()
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        if (terms.any { combined.contains(it) }) return true
        
        currentProcessedQuery?.let {
            if (it.conceptTerms.any { term -> combined.contains(term) }) return true
        }
        return false
    }

    /**
     * Standard provider search using stored configs or analysis
     */
    suspend fun searchProvider(provider: Provider, query: String, pageOffset: Int = 0): ProviderSearchResults {
        val startTime = System.currentTimeMillis()
        return try {
            enforceRateLimit(provider.id)
            providerDao.incrementSearchCount(provider.id)
            
            val config = scrapingConfigDao.getConfigForProvider(provider.id)
            val analysis = siteAnalysisDao.getLatestAnalysis(provider.id)
            
            val results = when {
                config != null -> scrapeWithConfig(provider, query, config, pageOffset)
                analysis != null -> scrapeWithAnalysis(provider, query, analysis, pageOffset)
                else -> scrapeGeneric(provider, query, pageOffset)
            }
            val expanded = expandResultsToTarget(provider, query, results)
            
            updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
            ProviderSearchResults(
                provider = provider,
                results = expanded,
                searchTime = System.currentTimeMillis() - startTime,
                success = expanded.isNotEmpty(),
                errorMessage = if (expanded.isEmpty()) "No parseable results found" else null,
                totalResults = expanded.size,
                hasMore = expanded.size >= TARGET_RESULTS_PER_PROVIDER
            )
        } catch (e: Exception) {
            tryFallbackScraping(provider, query, startTime, e)
        }
    }

    private suspend fun tryFallbackScraping(provider: Provider, query: String, start: Long, e: Exception): ProviderSearchResults {
        providerDao.incrementFailedCount(provider.id)
        val methods: List<suspend () -> List<SearchResult>> = listOf(
            { scrapeGeneric(provider, query) },
            { scrapeWithTabCrawl(provider, query) },
            { scrapeProviderHomepage(provider, query) }
        )

        for (method in methods) {
            try {
                val res = method()
                if (res.isNotEmpty()) {
                    val expanded = expandResultsToTarget(provider, query, res)
                    updateProviderHealth(provider.id, true, System.currentTimeMillis() - start)
                    return ProviderSearchResults(
                        provider = provider,
                        results = expanded,
                        searchTime = System.currentTimeMillis() - start,
                        success = expanded.isNotEmpty(),
                        totalResults = expanded.size,
                        hasMore = expanded.size >= TARGET_RESULTS_PER_PROVIDER
                    )
                }
            } catch (_: Exception) {}
        }
        
        return ProviderSearchResults(provider, emptyList(), System.currentTimeMillis() - start, false, e.message)
    }

    // --- Helper Scrapers & Logic ---

    private suspend fun scrapeWithConfig(p: Provider, q: String, c: ScrapingConfig, pageOffset: Int = 0): List<SearchResult> = withContext(Dispatchers.IO) {
        val requestedPage = pageOffset + 1
        val url = c.searchUrlTemplate
            .replace("{baseUrl}", p.baseUrl)
            .replace("{query}", URLEncoder.encode(q, c.encoding))
            .replace("{page}", requestedPage.toString())
        val doc = fetchDocument(url, c)
        collectPagedResults(
            firstDoc = doc,
            firstUrl = url,
            provider = p,
            query = q,
            pageOffset = pageOffset,
            extractor = { pageDoc -> extractResultsWithConfig(pageDoc, p, q, c) }
        )
    }

    private fun extractResultsWithConfig(doc: Document, p: Provider, q: String, c: ScrapingConfig): List<SearchResult> {
        return doc.select(c.resultSelector).mapNotNull { item ->
            val title = extractText(item, c.titleSelector)
            val url = extractUrl(item, c.urlSelector, p.baseUrl)
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
            SearchResult(
                providerId = p.id,
                providerName = p.name,
                title = title,
                url = url,
                description = c.descriptionSelector?.let { extractText(item, it) },
                thumbnailUrl = c.thumbnailSelector?.let { extractImageUrl(item, it, p.baseUrl) },
                relevanceScore = calculateRelevanceScore(title, q, url = url)
            )
        }
    }

    private suspend fun scrapeWithAnalysis(p: Provider, q: String, a: SiteAnalysis, pageOffset: Int = 0): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = buildSearchUrl(p.baseUrl, q, pageOffset + 1)
        val doc = fetchDocument(url)
        collectPagedResults(
            firstDoc = doc,
            firstUrl = url,
            provider = p,
            query = q,
            pageOffset = pageOffset,
            extractor = { pageDoc ->
                val selector = a.resultItemSelector ?: detectResultItemSelector(pageDoc)
                if (selector == null) {
                    extractResultsGeneric(pageDoc, p, q)
                } else {
                    pageDoc.select(selector).mapNotNull { item ->
                        val title = extractBestTitle(item)
                        val u = extractUrlFromItem(item, p.baseUrl)
                        if (title.isEmpty() || u.isEmpty() || u.contains("javascript:")) {
                            null
                        } else {
                            SearchResult(
                                providerId = p.id,
                                providerName = p.name,
                                title = title,
                                url = u,
                                description = extractBestDescription(item),
                                thumbnailUrl = extractBestThumbnail(item, p.baseUrl),
                                relevanceScore = calculateRelevanceScore(title, q, url = u)
                            )
                        }
                    }
                }
            }
        )
    }

    private suspend fun scrapeGeneric(p: Provider, q: String, pageOffset: Int = 0): List<SearchResult> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(q, "UTF-8")
        val requestedPage = pageOffset + 1
        val patterns = listOf(
            buildSearchUrl(p.baseUrl, q, requestedPage),
            "${p.baseUrl}/?s=$enc&paged=$requestedPage",
            "${p.baseUrl}/?q=$enc&page=$requestedPage",
            "${p.baseUrl}/search/$enc/page/$requestedPage",
            "${p.baseUrl}/search/$enc"
        )
        for (url in patterns) {
            try {
                val doc = fetchDocument(url)
                val results = collectPagedResults(
                    firstDoc = doc,
                    firstUrl = url,
                    provider = p,
                    query = q,
                    pageOffset = pageOffset,
                    extractor = { pageDoc -> extractResultsGeneric(pageDoc, p, q) }
                )
                if (results.isNotEmpty()) return@withContext results
            } catch (_: Exception) {}
        }
        HeadlessBrowserHelper.searchViaHeadlessForm(p.baseUrl, q)?.let { html ->
            val doc = Jsoup.parse(html, p.baseUrl)
            val results = extractResultsGeneric(doc, p, q)
            if (results.isNotEmpty()) return@withContext results
        }
        emptyList()
    }

    private fun extractResultsGeneric(doc: Document, p: Provider, q: String, keepPageOrder: Boolean = false): List<SearchResult> {
        val selectorResults = detectResultItemSelector(doc)?.let { selector ->
            doc.select(selector).mapNotNull { item ->
                val title = extractBestTitle(item)
                val u = extractUrlFromItem(item, p.baseUrl)
                if (title.isEmpty() || u.isEmpty() || u.contains("javascript:")) null
                else SearchResult(
                    providerId = p.id,
                    providerName = p.name,
                    title = title,
                    url = u,
                    description = extractBestDescription(item),
                    thumbnailUrl = extractBestThumbnail(item, p.baseUrl),
                    relevanceScore = calculateRelevanceScore(title, q, extractBestDescription(item), u)
                )
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

        if (keepPageOrder) {
            // Result-item selector output is the page's result list in order; only
            // fall back to loose link scraping when no list was detected.
            val primary = selectorResults.distinctBy { it.url }
            if (primary.size >= 3) return primary
            return (primary + parsedResults + contentLinkResults).distinctBy { it.url }
        }
        return (selectorResults + contentLinkResults + parsedResults)
            .distinctBy { it.url }
            .sortedByDescending { it.relevanceScore }
    }

    private suspend fun expandResultsToTarget(
        provider: Provider,
        query: String,
        seedResults: List<SearchResult>
    ): List<SearchResult> {
        if (seedResults.size >= TARGET_RESULTS_PER_PROVIDER) {
            return seedResults.distinctBy { it.url }.take(TARGET_RESULTS_PER_PROVIDER)
        }

        val combined = linkedMapOf<String, SearchResult>()

        fun addAll(results: List<SearchResult>) {
            results.forEach { result ->
                if (combined.size >= TARGET_RESULTS_PER_PROVIDER) return
                if (result.url.isNotBlank() && !result.url.contains("javascript:", ignoreCase = true)) {
                    combined.putIfAbsent(result.url, result)
                }
            }
        }

        addAll(seedResults)
        if (combined.size >= 12) {
            return combined.values
                .sortedByDescending { it.relevanceScore }
                .take(TARGET_RESULTS_PER_PROVIDER)
        }

        val queryVariants = buildList {
            add(query)
            currentProcessedQuery?.searchQueries
                ?.filter { it.isNotBlank() && !contains(it) }
                ?.take(1)
                ?.let { addAll(it) }
        }

        for (variant in queryVariants) {
            if (combined.size >= TARGET_RESULTS_PER_PROVIDER) break
            withTimeoutOrNull(4_000L) {
                runCatching { addAll(scrapeWithTabCrawl(provider, variant)) }
            }
        }

        return combined.values
            .sortedByDescending { it.relevanceScore }
            .take(TARGET_RESULTS_PER_PROVIDER)
    }

    private suspend fun collectPagedResults(
        firstDoc: Document,
        firstUrl: String,
        provider: Provider,
        query: String,
        pageOffset: Int,
        extractor: (Document) -> List<SearchResult>
    ): List<SearchResult> {
        val combined = linkedMapOf<String, SearchResult>()

        fun addResults(results: List<SearchResult>) {
            results.forEach { result ->
                if (result.url.isNotBlank() && !result.url.contains("javascript:")) {
                    combined.putIfAbsent(result.url, result)
                }
            }
        }

        addResults(extractor(firstDoc))

        val pageLinks = smartNavigationEngine
            .getPaginationLinks(firstDoc, provider.baseUrl, MAX_PAGES_PER_PROVIDER_SEARCH + pageOffset)
            .drop(pageOffset)
            .take(MAX_PAGES_PER_PROVIDER_SEARCH)
            .toMutableList()

        if (pageLinks.isEmpty()) {
            pageLinks.addAll(generatePageCandidates(firstUrl, pageOffset + 2, MAX_PAGES_PER_PROVIDER_SEARCH))
        }

        for (url in pageLinks.distinct()) {
            if (combined.size >= TARGET_RESULTS_PER_PROVIDER) break
            try {
                val doc = fetchDocument(url)
                addResults(extractor(doc))
            } catch (_: Exception) {}
        }

        return combined.values
            .sortedByDescending { it.relevanceScore }
            .take(TARGET_RESULTS_PER_PROVIDER)
    }

    private fun generatePageCandidates(firstUrl: String, startPage: Int, maxPages: Int): List<String> {
        if (!firstUrl.startsWith("http")) return emptyList()
        val pageNumbers = startPage until (startPage + maxPages)
        return pageNumbers.mapNotNull { page ->
            when {
                Regex("([?&]page=)\\d+").containsMatchIn(firstUrl) ->
                    firstUrl.replace(Regex("([?&]page=)\\d+"), "\$1$page")
                Regex("([?&]paged=)\\d+").containsMatchIn(firstUrl) ->
                    firstUrl.replace(Regex("([?&]paged=)\\d+"), "\$1$page")
                firstUrl.contains("?") -> "$firstUrl&page=$page"
                else -> "${firstUrl.trimEnd('/')}/page/$page"
            }
        }
    }

    private fun buildSearchUrl(baseUrl: String, query: String, page: Int): String {
        val enc = URLEncoder.encode(query, "UTF-8")
        return "${baseUrl.trimEnd('/')}/search?q=$enc&page=$page"
    }

    private fun extractResultsFromItems(doc: Document, p: Provider, q: String): List<SearchResult> {
        val selector = detectResultItemSelector(doc) ?: return emptyList()
        return doc.select(selector).mapNotNull { item ->
            val title = extractBestTitle(item)
            val u = extractUrlFromItem(item, p.baseUrl)
            if (title.isEmpty() || u.isEmpty() || u.contains("javascript:")) null
            else SearchResult(
                providerId = p.id,
                providerName = p.name,
                title = title,
                url = u,
                description = extractBestDescription(item),
                thumbnailUrl = extractBestThumbnail(item, p.baseUrl),
                relevanceScore = calculateRelevanceScore(title, q, extractBestDescription(item), u)
            )
        }
    }

    private suspend fun scrapeWithTabCrawl(p: Provider, q: String): List<SearchResult> {
        val links = smartNavigationEngine.crawlCategoryTabsForQuery(p.baseUrl, q, 10)
        return links.take(TARGET_RESULTS_PER_PROVIDER).map {
            SearchResult(
                providerId = p.id,
                providerName = p.name,
                title = it.title,
                url = it.url,
                thumbnailUrl = it.thumbnail,
                duration = it.duration,
                relevanceScore = calculateRelevanceScore(it.title, q, url = it.url)
            )
        }
    }

    private suspend fun scrapeProviderHomepage(p: Provider, q: String): List<SearchResult> {
        val doc = fetchDocument(p.baseUrl)
        return extractAllContentFromPage(doc, p).filter { matchesQueryEnhanced(it, q) }
    }

    private fun extractAllContentFromPage(doc: Document, p: Provider): List<SearchResult> {
        return smartNavigationEngine.extractContentLinks(doc, p.baseUrl).map { 
            SearchResult(
                providerId = p.id,
                providerName = p.name,
                title = it.title,
                url = it.url,
                thumbnailUrl = it.thumbnail,
                duration = it.duration,
                relevanceScore = 0f
            )
        }
    }

    // --- Utility Methods ---

    private fun detectResultItemSelector(doc: Document): String? {
        val candidates = listOf(".result", ".item", ".card", "article", ".video-item", ".movie-item")
        return candidates.map { it to doc.select(it).size }.filter { it.second >= 2 }.maxByOrNull { it.second }?.first
    }

    private fun extractBestTitle(item: Element): String = item.select("h1, h2, h3, .title, .name, a").firstOrNull()?.text()?.trim() ?: ""
    private fun extractUrlFromItem(item: Element, base: String): String {
        val href = item.select("a[href]").firstOrNull()?.attr("href")?.takeIf { it.isNotBlank() } ?: return ""
        return normalizeUrl(href, base)
    }
    private fun extractBestDescription(item: Element): String? = item.select(".description, .desc, p").firstOrNull()?.text()?.take(200)
    private fun extractBestThumbnail(item: Element, base: String): String? {
        val imageElement = item.select("img, source, video, [data-thumb], [data-thumbnail], [data-poster], [data-image], [style*='background-image']").firstOrNull()
            ?: return null
        return extractImageCandidate(imageElement, base)
    }
    private fun extractText(el: Element, sel: String): String = el.select(sel).text().trim()
    private fun extractUrl(el: Element, sel: String, base: String): String = normalizeUrl(el.select(sel).attr("href"), base)
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
    private fun extractTitleFromUrl(url: String): String? = try {
        url.substringAfterLast("/")
            .substringBefore("?")
            .replace("-", " ")
            .replace("_", " ")
            .trim()
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .takeIf { it.length > 2 }
    } catch (_: Exception) { null }
    private fun findDescriptionInDocument(doc: Document, url: String): String? = null // Simplified for brevity
    private fun normalizeUrl(url: String, base: String): String = EngineUtils.normalizeUrl(url, base)
    private fun extractDomain(url: String): String = EngineUtils.extractDomain(url)
    private fun getRandomUserAgent(): String = EngineUtils.getRandomUserAgent()
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
