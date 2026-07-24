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
import com.aggregatorx.app.engine.network.CloudflareBypassEngine
import com.aggregatorx.app.engine.network.TlsClient
import com.aggregatorx.app.engine.network.TlsRequest
import com.aggregatorx.app.engine.network.NetworkFetcher
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
                val validated = validateAndFilterResults(result.results, query)
                if (validated.isNotEmpty()) {
                    aiDecisionEngine.learnFromSuccess(
                        domain,
                        ScrapingStrategy.HYBRID,
                        null,
                        null,
                        null,
                        validated.size,
                        System.currentTimeMillis() - startTime
                    )
                    result.copy(
                        results = validated,
                        totalResults = validated.size,
                        success = true,
                        status = ProviderSearchStatus.RESULTS,
                        hasMore = result.hasMore || validated.isNotEmpty()
                    )
                } else {
                    result.copy(
                        results = emptyList(),
                        totalResults = 0,
                        success = false,
                        errorMessage = "Fetched pages contained no usable result links",
                        status = ProviderSearchStatus.EMPTY
                    )
                }
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
        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        val processed = currentProcessedQuery

        val structurallyValid = results.filter { result ->
            val titleLower = result.title.lowercase()
            val urlLower = result.url.lowercase()

            if (result.title.length < 3) return@filter false
            
            // Filter categories
            if (CATEGORY_URL_PATTERNS.any { urlLower.contains(it) }) return@filter false
            if (titleLower.trim() in GENERIC_CATEGORY_NAMES && result.thumbnailUrl.isNullOrEmpty()) return@filter false
            
            true
        }.distinctBy { it.url }

        if (structurallyValid.isEmpty()) return emptyList()

        val relevant = structurallyValid.filter { result ->
            val titleLower = result.title.lowercase()
            val combined = "$titleLower ${result.description?.lowercase() ?: ""} ${result.url.lowercase()}"

            val hasKeyword = queryWords.any { combined.contains(it) }
            val hasConcept = processed?.conceptTerms?.any { combined.contains(it) } ?: false
            val semanticScore = processed?.let { nlpProcessor.calculateSemanticRelevance(result.title, result.description, it.concepts) } ?: 0f

            hasKeyword || hasConcept || semanticScore >= 15f
        }

        val selected = relevant.ifEmpty { structurallyValid }
        return selected
            .sortedByDescending { it.relevanceScore }
            .take(TARGET_RESULTS_PER_PROVIDER)
    }

    suspend fun searchProviderSmart(provider: Provider, query: String, pageOffset: Int = 0): ProviderSearchResults {
        val startTime = System.currentTimeMillis()
        val processed = currentProcessedQuery
        val effectiveQuery = if (processed != null && processed.isNaturalLanguage && query == processed.originalQuery) {
            processed.searchQueries.firstOrNull() ?: query
        } else query

        return try {
            enforceRateLimit(provider.id)
            providerDao.incrementSearchCount(provider.id)
            val config = scrapingConfigDao.getConfigForProvider(provider.id)
            val analysis = siteAnalysisDao.getLatestAnalysis(provider.id)
            val results = searchBoundedCandidates(provider, effectiveQuery, pageOffset, config, analysis)
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
                status = ProviderSearchStatus.FAILED
            )
        }
    }

    private suspend fun searchBoundedCandidates(
        provider: Provider,
        query: String,
        pageOffset: Int,
        config: ScrapingConfig?,
        analysis: SiteAnalysis?
    ): List<SearchResult> {
        val candidates = buildSearchCandidates(provider, query, pageOffset, config, analysis)
            .take(MAX_SEARCH_CANDIDATES)
        val combined = linkedMapOf<String, SearchResult>()
        var hasRelevantResults = false

        for (batch in candidates.chunked(CANDIDATE_BATCH_SIZE)) {
            val outcomes = supervisorScope {
                batch.map { candidate ->
                    async(Dispatchers.IO) {
                        withTimeoutOrNull(FAST_CANDIDATE_TIMEOUT_MS + 1_000L) {
                            fetchFastOutcome(candidate, config, provider.impersonationEnabled)
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            for (outcome in outcomes) {
                val extracted = extractFromFetchedOutcome(outcome, provider, query, config, analysis)
                extracted.forEach { result -> combined.putIfAbsent(result.url, result) }
                hasRelevantResults = hasRelevantResults || extracted.any { matchesQueryEnhanced(it, query) }
                Log.i(
                    TAG,
                    "provider=${provider.name} transport=${outcome.transport} status=${outcome.statusCode} " +
                        "bytes=${outcome.body.length} parsed=${extracted.size} url=${outcome.finalUrl.take(180)}"
                )
            }
            if (hasRelevantResults) break
        }

        if (!hasRelevantResults) {
            val renderUrl = if (analysis?.requiresJavaScript == true && analysis.searchFormSelector == null) {
                provider.url
            } else {
                candidates.firstOrNull() ?: provider.url
            }
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
                extracted.forEach { result -> combined.putIfAbsent(result.url, result) }
                Log.i(TAG, "provider=${provider.name} transport=webview bytes=${renderedHtml.length} parsed=${extracted.size}")
            }
        }

        return combined.values
            .sortedByDescending { it.relevanceScore }
            .take(TARGET_RESULTS_PER_PROVIDER)
    }

    private suspend fun fetchFastOutcome(url: String, config: ScrapingConfig?, allowNative: Boolean): FetchOutcome? = supervisorScope {
        val timeout = minOf(config?.timeout ?: DEFAULT_TIMEOUT, FAST_CANDIDATE_TIMEOUT_MS.toInt())
        val headers = mapOf(
            "User-Agent" to (config?.userAgent ?: EngineUtils.DEFAULT_USER_AGENT),
            "Accept" to "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Cache-Control" to "no-cache, no-store",
            "Pragma" to "no-cache"
        )

        val outcome = try {
            NetworkFetcher.fetch(url, headers, timeout, allowNative)
        } catch (t: Throwable) {
            Log.w(TAG, "NetworkFetcher.fetch threw for $url: ${t.message}")
            null
        }

        outcome?.let {
            return@supervisorScope FetchOutcome(
                document = it.document,
                body = it.body,
                finalUrl = it.finalUrl,
                statusCode = it.statusCode,
                transport = it.transport
            )
        }

        return@supervisorScope null
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
        val results = mutableListOf<SearchResult>()
        val raw = outcome.body.trimStart()
        if (raw.startsWith('{') || raw.startsWith('[')) {
            results += extractResultsFromJson(outcome.body, provider, query)
        }
        if (config != null) results += runCatching {
            extractResultsWithConfig(outcome.document, provider, query, config)
        }.getOrDefault(emptyList())
        if (analysis?.resultItemSelector != null) {
            results += runCatching {
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
            }.getOrDefault(emptyList())
        }
        results += extractResultsGeneric(outcome.document, provider, query)
        return results
            .filter { it.title.length >= 3 && it.url.startsWith("http") }
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

    private fun extractResultsGeneric(doc: Document, p: Provider, q: String): List<SearchResult> {
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
... (truncated for brevity)
