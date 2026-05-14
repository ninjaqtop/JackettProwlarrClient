package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.data.database.ProviderDao
import com.aggregatorx.app.data.database.ScrapingConfigDao
import com.aggregatorx.app.data.database.SiteAnalysisDao
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.engine.analyzer.SmartContentClassifier
import com.aggregatorx.app.engine.analyzer.PageType
import com.aggregatorx.app.engine.analyzer.ContainerType
import com.aggregatorx.app.engine.analyzer.EndpointDiscoveryEngine
import com.aggregatorx.app.engine.ai.AIDecisionEngine
import com.aggregatorx.app.engine.nlp.NaturalLanguageQueryProcessor
import com.aggregatorx.app.engine.nlp.ProcessedQuery
import com.aggregatorx.app.engine.network.CloudflareBypassEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val nlpProcessor: NaturalLanguageQueryProcessor
) {
    @Volatile
    private var currentProcessedQuery: ProcessedQuery? = null
    
    private val providerHealthMap = ConcurrentHashMap<String, ProviderHealth>()
    private val lastRequestTime = ConcurrentHashMap<String, Long>()
    
    companion object {
        private const val DEFAULT_TIMEOUT = 30000
        private const val DEFAULT_RETRY_COUNT = 3
        private const val DEFAULT_RETRY_DELAY = 800L
        private const val DEFAULT_RATE_LIMIT_MS = 50L
        private const val MAX_CONCURRENT_PROVIDERS = 20
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        
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
    fun searchAllProviders(query: String, cache: Boolean = cacheResults): Flow<ProviderSearchResults> = flow {
        val processedQuery = nlpProcessor.processQuery(query)
        currentProcessedQuery = processedQuery

        if (cache) {
            val cachedEntry = synchronized(resultCache) { resultCache[query] }
            if (cachedEntry != null && System.currentTimeMillis() - cachedEntry.timestamp < CACHE_TTL_MS) {
                cachedEntry.results.forEach { emit(it) }
                return@flow
            }
        }

        var enabledProviders = providerDao.getEnabledProvidersSync()
        if (enabledProviders.isEmpty()) return@flow

        enabledProviders = enabledProviders.sortedWith(
            compareByDescending<Provider> { it.successRate }
                .thenBy { it.avgResponseTime }
        )
        
        val processedProviders = mutableSetOf<String>()
        val semaphore = Semaphore(MAX_CONCURRENT_PROVIDERS)
        val results = mutableListOf<ProviderSearchResults>()
        val perProviderTimeoutMs = 90_000L

        coroutineScope {
            val deferredResults = enabledProviders.map { provider ->
                async {
                    semaphore.withPermit {
                        processedProviders.add(provider.id)
                        try {
                            withTimeoutOrNull(perProviderTimeoutMs) {
                                safeSearchProvider(provider, query)
                            } ?: ProviderSearchResults(
                                provider = provider,
                                results = emptyList(),
                                searchTime = perProviderTimeoutMs,
                                success = false,
                                errorMessage = "Timed out"
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ProviderSearchResults(
                                provider = provider,
                                results = emptyList(),
                                searchTime = 0L,
                                success = false,
                                errorMessage = "Error: ${e.message?.take(100)}"
                            )
                        }
                    }
                }
            }

            deferredResults.forEachIndexed { index, deferred ->
                val provider = enabledProviders.getOrNull(index)
                try {
                    val result = deferred.await()
                    results.add(result)
                    emit(result)
                } catch (e: CancellationException) {
                    if (provider != null) {
                        val res = ProviderSearchResults(provider, emptyList(), 0L, false, "Cancelled")
                        results.add(res)
                        emit(res)
                    }
                } catch (e: Exception) {
                    provider?.let {
                        val res = ProviderSearchResults(it, emptyList(), 0L, false, "Internal Error")
                        results.add(res)
                        emit(res)
                    }
                }
            }
        }

        if (cache && results.any { it.success }) {
            synchronized(resultCache) { resultCache[query] = CacheEntry(results) }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun safeSearchProvider(provider: Provider, query: String): ProviderSearchResults {
        val startTime = System.currentTimeMillis()
        val domain = extractDomain(provider.baseUrl)
        
        val isCoolingDown = provider.failedSearches > 5 && 
            (provider.failedSearches.toFloat() / maxOf(provider.totalSearches, 1)) > 0.7f
        
        return try {
            if (isCoolingDown) {
                val fallback = tryFallbackScraping(provider, query, startTime, Exception("Cooldown"))
                if (fallback.success && fallback.results.isNotEmpty()) {
                    val validated = validateAndFilterResults(fallback.results, query)
                    if (validated.isNotEmpty()) return fallback.copy(results = validated)
                }
                retryWithNlpQueries(provider, query, startTime) ?: fallback
            } else {
                val result = searchProviderSmart(provider, query)
                if (result.success && result.results.isNotEmpty()) {
                    val validated = validateAndFilterResults(result.results, query)
                    if (validated.isEmpty()) {
                        aiDecisionEngine.learnFromFailure(domain, "CATEGORY_PAGE", "Invalid results", ScrapingStrategy.HTML_PARSING, null, provider.baseUrl)
                        retryWithNlpQueries(provider, query, startTime) ?: result.copy(results = emptyList(), success = false, errorMessage = "Category results filtered")
                    } else {
                        aiDecisionEngine.learnFromSuccess(domain, ScrapingStrategy.HTML_PARSING, null, null, null, validated.size, System.currentTimeMillis() - startTime)
                        result.copy(results = validated)
                    }
                } else {
                    retryWithNlpQueries(provider, query, startTime) ?: result
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aiDecisionEngine.learnFromFailure(domain, "EXCEPTION", e.message, ScrapingStrategy.HTML_PARSING, null, provider.baseUrl)
            val fallback = tryFallbackScraping(provider, query, startTime, e)
            if (fallback.success && fallback.results.isNotEmpty()) {
                val validated = validateAndFilterResults(fallback.results, query)
                if (validated.isNotEmpty()) return fallback.copy(results = validated)
            }
            retryWithNlpQueries(provider, query, startTime) ?: fallback
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
                if (allResults.size >= 10) break
            } catch (e: Exception) { continue }
        }

        return if (allResults.isNotEmpty()) {
            ProviderSearchResults(provider, allResults.sortedByDescending { it.relevanceScore }, System.currentTimeMillis() - startTime, true)
        } else null
    }

    private fun validateAndFilterResults(results: List<SearchResult>, query: String): List<SearchResult> {
        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        val processed = currentProcessedQuery

        return results.filter { result ->
            val titleLower = result.title.lowercase()
            val urlLower = result.url.lowercase()

            if (result.title.length < 3) return@filter false
            
            // Filter categories
            if (CATEGORY_URL_PATTERNS.any { urlLower.contains(it) }) return@filter false
            if (titleLower.trim() in GENERIC_CATEGORY_NAMES && result.thumbnailUrl.isNullOrEmpty()) return@filter false
            
            // NLP-Enhanced Relevance Gate
            val combined = "$titleLower ${result.description?.lowercase() ?: ""} ${result.url.lowercase()}"
            val hasKeyword = queryWords.any { combined.contains(it) }
            val hasConcept = processed?.conceptTerms?.any { combined.contains(it) } ?: false
            val semanticScore = processed?.let { nlpProcessor.calculateSemanticRelevance(result.title, result.description, it.concepts) } ?: 0f

            hasKeyword || hasConcept || semanticScore >= 15f
        }
    }

    suspend fun searchProviderSmart(provider: Provider, query: String): ProviderSearchResults {
        val startTime = System.currentTimeMillis()
        val processed = currentProcessedQuery
        val effectiveQuery = if (processed != null && processed.isNaturalLanguage && query == processed.originalQuery) {
            processed.searchQueries.firstOrNull() ?: query
        } else query

        return try {
            enforceRateLimit(provider.id)
            providerDao.incrementSearchCount(provider.id)

            // 1. Search URL Discovery
            val smartSearchUrl = smartNavigationEngine.findSearchUrl(provider.baseUrl, effectiveQuery)
            if (smartSearchUrl != null) {
                val results = scrapeWithSmartNavigation(provider, query, smartSearchUrl)
                if (results.isNotEmpty()) {
                    updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
                    return ProviderSearchResults(provider, results, System.currentTimeMillis() - startTime, true)
                }
            }

            // 2. Tab Crawling (for sites without search)
            val tabResults = scrapeWithTabCrawl(provider, effectiveQuery)
            if (tabResults.isNotEmpty()) {
                updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
                return ProviderSearchResults(provider, tabResults, System.currentTimeMillis() - startTime, true)
            }

            // 3. Fallback to generic search
            searchProvider(provider, effectiveQuery)
        } catch (e: Exception) {
            searchProvider(provider, effectiveQuery)
        }
    }

    private suspend fun scrapeWithSmartNavigation(provider: Provider, query: String, searchUrl: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val document = fetchDocument(searchUrl)
        val (activeUrl, activeDoc) = if (smartNavigationEngine.isCategoryPage(searchUrl, document)) {
            smartNavigationEngine.navigatePastCategory(provider.baseUrl, document, query) ?: (searchUrl to document)
        } else searchUrl to document

        val results = extractResultsWithThumbnails(activeDoc, provider, query)
        if (results.size < 10) {
            val pages = smartNavigationEngine.getPaginationLinks(activeDoc, provider.baseUrl, 2)
            val combined = results.toMutableList()
            pages.forEach { url ->
                try {
                    val pDoc = fetchDocument(url)
                    extractResultsWithThumbnails(pDoc, provider, query).forEach { r ->
                        if (combined.none { it.url == r.url }) combined.add(r)
                    }
                } catch (_: Exception) {}
            }
            return@withContext combined.sortedByDescending { it.relevanceScore }
        }
        results
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
                val conn = Jsoup.connect(url).userAgent(getRandomUserAgent()).timeout(timeout).followRedirects(true)
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
    suspend fun searchProvider(provider: Provider, query: String): ProviderSearchResults {
        val startTime = System.currentTimeMillis()
        return try {
            enforceRateLimit(provider.id)
            providerDao.incrementSearchCount(provider.id)
            
            val config = scrapingConfigDao.getConfigForProvider(provider.id)
            val analysis = siteAnalysisDao.getLatestAnalysis(provider.id)
            
            val results = when {
                config != null -> scrapeWithConfig(provider, query, config)
                analysis != null -> scrapeWithAnalysis(provider, query, analysis)
                else -> scrapeGeneric(provider, query)
            }
            
            updateProviderHealth(provider.id, true, System.currentTimeMillis() - startTime)
            ProviderSearchResults(provider, results, System.currentTimeMillis() - startTime, true)
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
                    updateProviderHealth(provider.id, true, System.currentTimeMillis() - start)
                    return ProviderSearchResults(provider, res, System.currentTimeMillis() - start, true)
                }
            } catch (_: Exception) {}
        }
        
        return ProviderSearchResults(provider, emptyList(), System.currentTimeMillis() - start, false, e.message)
    }

    // --- Helper Scrapers & Logic ---

    private suspend fun scrapeWithConfig(p: Provider, q: String, c: ScrapingConfig): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = c.searchUrlTemplate.replace("{baseUrl}", p.baseUrl).replace("{query}", URLEncoder.encode(q, c.encoding))
        val doc = fetchDocument(url, c)
        extractResultsWithConfig(doc, p, q, c)
    }

    private fun extractResultsWithConfig(doc: Document, p: Provider, q: String, c: ScrapingConfig): List<SearchResult> {
        return doc.select(c.resultSelector).mapNotNull { item ->
            val title = extractText(item, c.titleSelector)
            val url = extractUrl(item, c.urlSelector, p.baseUrl)
            if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
            SearchResult(p.id, p.name, title, url, c.descriptionSelector?.let { extractText(item, it) }, 
                c.thumbnailSelector?.let { extractImageUrl(item, it, p.baseUrl) }, relevanceScore = calculateRelevanceScore(title, q))
        }
    }

    private suspend fun scrapeWithAnalysis(p: Provider, q: String, a: SiteAnalysis): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = "${p.baseUrl}/search?q=${URLEncoder.encode(q, "UTF-8")}"
        val doc = fetchDocument(url)
        val selector = a.resultItemSelector ?: detectResultItemSelector(doc) ?: return@withContext emptyList()
        doc.select(selector).mapNotNull { item ->
            val title = extractBestTitle(item)
            val u = extractUrlFromItem(item, p.baseUrl)
            if (title.isEmpty()) null else SearchResult(p.id, p.name, title, u, relevanceScore = calculateRelevanceScore(title, q))
        }
    }

    private suspend fun scrapeGeneric(p: Provider, q: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val enc = URLEncoder.encode(q, "UTF-8")
        val patterns = listOf("${p.baseUrl}/search?q=$enc", "${p.baseUrl}/?s=$enc", "${p.baseUrl}/search/$enc")
        for (url in patterns) {
            try {
                val doc = fetchDocument(url)
                val results = extractResultsGeneric(doc, p, q)
                if (results.isNotEmpty()) return@withContext results
            } catch (_: Exception) {}
        }
        emptyList()
    }

    private fun extractResultsGeneric(doc: Document, p: Provider, q: String): List<SearchResult> {
        val selector = detectResultItemSelector(doc) ?: return emptyList()
        return doc.select(selector).mapNotNull { item ->
            val title = extractBestTitle(item)
            val u = extractUrlFromItem(item, p.baseUrl)
            if (title.isEmpty() || u.contains("javascript:")) null 
            else SearchResult(p.id, p.name, title, u, extractBestDescription(item), extractBestThumbnail(item, p.baseUrl), relevanceScore = calculateRelevanceScore(title, q))
        }
    }

    private suspend fun scrapeWithTabCrawl(p: Provider, q: String): List<SearchResult> {
        val links = smartNavigationEngine.crawlCategoryTabsForQuery(p.baseUrl, q, 5)
        return links.map { 
            SearchResult(p.id, p.name, it.title, it.url, null, it.thumbnail, relevanceScore = calculateRelevanceScore(it.title, q))
        }
    }

    private suspend fun scrapeProviderHomepage(p: Provider, q: String): List<SearchResult> {
        val doc = fetchDocument(p.baseUrl)
        return extractAllContentFromPage(doc, p).filter { matchesQueryEnhanced(it, q) }
    }

    private fun extractAllContentFromPage(doc: Document, p: Provider): List<SearchResult> {
        return smartNavigationEngine.extractContentLinks(doc, p.baseUrl).map { 
            SearchResult(p.id, p.name, it.title, it.url, null, it.thumbnail, 0f)
        }
    }

    // --- Utility Methods ---

    private fun detectResultItemSelector(doc: Document): String? {
        val candidates = listOf(".result", ".item", ".card", "article", ".video-item", ".movie-item")
        return candidates.map { it to doc.select(it).size }.filter { it.second >= 2 }.maxByOrNull { it.second }?.first
    }

    private fun extractBestTitle(item: Element): String = item.select("h1, h2, h3, .title, .name, a").firstOrNull()?.text()?.trim() ?: ""
    private fun extractUrlFromItem(item: Element, base: String): String = normalizeUrl(item.select("a[href]").firstOrNull()?.attr("href") ?: "", base)
    private fun extractBestDescription(item: Element): String? = item.select(".description, .desc, p").firstOrNull()?.text()?.take(200)
    private fun extractBestThumbnail(item: Element, base: String): String? = item.select("img").firstOrNull()?.let { normalizeUrl(it.attr("src"), base) }
    private fun extractText(el: Element, sel: String): String = el.select(sel).text().trim()
    private fun extractUrl(el: Element, sel: String, base: String): String = normalizeUrl(el.select(sel).attr("href"), base)
    private fun extractImageUrl(el: Element, sel: String, base: String): String = normalizeUrl(el.select(sel).attr("src"), base)
    private fun extractTitleFromUrl(url: String): String? = try { url.substringAfterLast("/").replace("-", " ").replace("_", " ").capitalize() } catch (_: Exception) { null }
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
