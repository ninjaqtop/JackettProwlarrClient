package com.aggregatorx.app.data.repository

import com.aggregatorx.app.data.database.*
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.engine.analyzer.SiteAnalyzerEngine
import com.aggregatorx.app.engine.nlp.NaturalLanguageQueryProcessor
import com.aggregatorx.app.engine.ranking.RankingEngine
import com.aggregatorx.app.engine.scraper.ScrapingEngine
import kotlinx.coroutines.flow.*
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AggregatorRepository @Inject constructor(
    private val providerDao: ProviderDao,
    private val siteAnalysisDao: SiteAnalysisDao,
    private val scrapingConfigDao: ScrapingConfigDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val likedResultDao: LikedResultDao,
    private val learnedProfileDao: LearnedProfileDao,
    private val siteAnalyzerEngine: SiteAnalyzerEngine,
    private val scrapingEngine: ScrapingEngine,
    private val rankingEngine: RankingEngine,
    private val nlpProcessor: NaturalLanguageQueryProcessor
) {
    fun clearSearchCache() {
        scrapingEngine.clearCache()
    }
    // Providers
    fun getAllProviders(): Flow<List<Provider>> = providerDao.getAllProviders()
    fun getEnabledProviders(): Flow<List<Provider>> = providerDao.getEnabledProviders()
    
    suspend fun getProviderById(id: String): Provider? = providerDao.getProviderById(id)
    
    suspend fun addProvider(url: String, name: String? = null): Provider {
        val normalizedUrl = normalizeUrl(url)
        val baseUrl = extractBaseUrl(normalizedUrl)
        
        val existingProvider = providerDao.getProviderByUrl(normalizedUrl)
        if (existingProvider != null) {
            return existingProvider
        }
        
        val provider = Provider(
            id = UUID.randomUUID().toString(),
            name = name ?: extractSiteName(normalizedUrl),
            url = normalizedUrl,
            baseUrl = baseUrl,
            isEnabled = true,
            category = detectCategory(normalizedUrl)
        )
        
        providerDao.insertProvider(provider)
        return provider
    }
    
    suspend fun updateProvider(provider: Provider) {
        providerDao.updateProvider(provider)
    }
    
    suspend fun deleteProvider(providerId: String) {
        providerDao.deleteProviderById(providerId)
        siteAnalysisDao.deleteAnalysesForProvider(providerId)
        scrapingConfigDao.deleteConfigForProvider(providerId)
    }
    
    suspend fun setProviderEnabled(providerId: String, enabled: Boolean) {
        providerDao.setProviderEnabled(providerId, enabled)
    }
    
    // Site Analysis
    suspend fun analyzeProvider(providerId: String): SiteAnalysis {
        val provider = providerDao.getProviderById(providerId)
            ?: throw IllegalArgumentException("Provider not found")
        
        val analysis = siteAnalyzerEngine.analyzeSite(provider.url, providerId)
        siteAnalysisDao.insertAnalysis(analysis)
        
        // Generate scraping config from analysis
        generateScrapingConfig(provider, analysis)
        
        // Update provider last analyzed timestamp
        providerDao.updateLastAnalyzed(providerId, System.currentTimeMillis())
        
        return analysis
    }
    
    suspend fun analyzeNewUrl(url: String): Pair<Provider, SiteAnalysis> {
        val provider = addProvider(url)
        val analysis = analyzeProvider(provider.id)
        return Pair(provider, analysis)
    }
    
    suspend fun refreshAllProviders(): List<Pair<Provider, Result<SiteAnalysis>>> {
        val providers = providerDao.getEnabledProvidersSync()
        return providers.map { provider ->
            try {
                val analysis = analyzeProvider(provider.id)
                Pair(provider, Result.success(analysis))
            } catch (e: Exception) {
                Pair(provider, Result.failure(e))
            }
        }
    }
    
    suspend fun getLatestAnalysis(providerId: String): SiteAnalysis? {
        return siteAnalysisDao.getLatestAnalysis(providerId)
    }
    
    // BUILD FIX: Added `pages` parameter to match ViewModel call.
    fun searchAllProviders(query: String, pages: Map<String, Int> = emptyMap()): Flow<ProviderSearchResults> {
        // Always pass false for cache to ensure fresh results for each unique query
        // The cache is cleared before each search anyway, so this ensures no stale results
        // Note: If ScrapingEngine supports pagination in the future, pass 'pages' to it.
        return scrapingEngine.searchAllProviders(query, false)
    }
    
    suspend fun aggregateSearchResults(
        query: String,
        providerResults: List<ProviderSearchResults>
    ): AggregatedSearchResults {
        // Save to search history
        searchHistoryDao.insertSearch(SearchHistoryEntry(
            query = query,
            resultCount = providerResults.sumOf { it.results.size },
            providersSearched = providerResults.size,
            successfulProviders = providerResults.count { it.success }
        ))

        // Inject learned user preferences into the ranking engine
        // so they influence Top Results scoring only.
        val likedUrls = likedResultDao.getAllLikedUrls().toSet()
        val profile = learnedProfileDao.getProfile()
        if (profile != null) {
            rankingEngine.setUserPreferences(
                keywords = profile.preferredKeywordsMap(),
                providers = profile.preferredProvidersMap(),
                qualities = profile.preferredQualitiesMap(),
                liked = likedUrls
            )
        } else {
            rankingEngine.setUserPreferences(liked = likedUrls)
        }

        // Pass NLP-processed query to ranking engine for semantic scoring
        val processedQuery = nlpProcessor.processQuery(query)
        rankingEngine.setProcessedQuery(processedQuery)

        return rankingEngine.rankAndAggregate(query, providerResults)
    }
    
    // Search History
    fun getRecentSearches(): Flow<List<SearchHistoryEntry>> = searchHistoryDao.getRecentSearches()
    
    suspend fun clearSearchHistory() {
        searchHistoryDao.clearHistory()
    }
    
    // Helper methods
    private suspend fun generateScrapingConfig(provider: Provider, analysis: SiteAnalysis) {
        val searchUrlTemplate = buildSearchUrlTemplate(provider.baseUrl, analysis)
        val config = ScrapingConfig(
            providerId = provider.id,
            searchUrlTemplate = searchUrlTemplate,
            resultSelector = analysis.resultItemSelector ?: ".item, .result, article",
            titleSelector = analysis.titleSelector ?: "h2, .title, a",
            urlSelector = "a[href]",
            descriptionSelector = analysis.descriptionSelector,
            thumbnailSelector = analysis.thumbnailSelector,
            dateSelector = analysis.dateSelector,
            ratingSelector = analysis.ratingSelector
        )
        scrapingConfigDao.insertConfig(config)
    }
    
    private fun buildSearchUrlTemplate(baseUrl: String, analysis: SiteAnalysis): String {
        return when {
            analysis.searchFormSelector != null -> "$baseUrl/search?q={query}&page={page}"
            analysis.hasAPI -> "$baseUrl/api/search?query={query}&page={page}"
            else -> "$baseUrl/search?q={query}"
        }
    }
    
    private fun normalizeUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized.trimEnd('/')
    }
    
    private fun extractBaseUrl(url: String): String {
        return try {
            val u = URL(url)
            "${u.protocol}://${u.host}"
        } catch (e: Exception) {
            url
        }
    }
    
    private fun extractSiteName(url: String): String {
        return try {
            val u = URL(url)
            val host = u.host.removePrefix("www.")
            host.split(".").first().replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    private fun detectCategory(url: String): ProviderCategory {
        val urlLower = url.lowercase()
        return when {
            urlLower.contains("torrent") || urlLower.contains("1337") || 
            urlLower.contains("rarbg") || urlLower.contains("pirate") -> ProviderCategory.TORRENT
            
            urlLower.contains("stream") || urlLower.contains("movie") || 
            urlLower.contains("watch") || urlLower.contains("video") -> ProviderCategory.STREAMING
            
            urlLower.contains("news") || urlLower.contains("blog") -> ProviderCategory.NEWS
            
            urlLower.contains("api") -> ProviderCategory.API_BASED
            
            else -> ProviderCategory.GENERAL
        }
    }

    // ── Like / preference learning ─────────────────────────────────────

    suspend fun toggleLike(result: SearchResult): Boolean {
        val existing = likedResultDao.getLikedByUrl(result.url)
        return if (existing != null) {
            likedResultDao.removeLikeByUrl(result.url)
            rebuildLearnedProfile()
            false
        } else {
            val titleKeywords = result.title.lowercase()
                .split(Regex("\\W+"))
                .filter { it.length > 2 }
                .distinct()
                .joinToString(",")

            likedResultDao.insertLike(
                LikedResult(
                    title = result.title,
                    url = result.url,
                    providerId = result.providerId,
                    providerName = result.providerName,
                    category = result.category.orEmpty(),
                    quality = result.quality.orEmpty(),
                    thumbnailUrl = result.thumbnailUrl,
                    description = result.description,
                    seeders = result.seeders,
                    rating = result.rating,
                    titleKeywords = titleKeywords
                )
            )
            rebuildLearnedProfile()
            true
        }
    }

    suspend fun getAllLikedUrls(): Set<String> =
        likedResultDao.getAllLikedUrls().toSet()

    fun getAllLikedResults(): Flow<List<LikedResult>> =
        likedResultDao.getAllLikedResults()

    private suspend fun rebuildLearnedProfile() {
        val likes = likedResultDao.getAllLikedResults().first()
        if (likes.isEmpty()) {
            learnedProfileDao.saveProfile(LearnedUserProfile())
            return
        }

        val keywordCounts = mutableMapOf<String, Int>()
        likes.forEach { like ->
            like.titleKeywords.split(",").filter { it.isNotBlank() }.forEach { kw ->
                keywordCounts[kw] = (keywordCounts[kw] ?: 0) + 1
            }
        }
        val maxKwCount = keywordCounts.values.maxOrNull()?.toFloat() ?: 1f
        val keywordWeights = keywordCounts.mapValues { (_, count) ->
            count.toFloat() / maxKwCount
        }

        val providerCounts = likes.groupingBy { it.providerId }.eachCount()
        val maxProvCount = providerCounts.values.maxOrNull()?.toFloat() ?: 1f
        val providerWeights = providerCounts.mapValues { (_, count) ->
            count.toFloat() / maxProvCount
        }

        val qualityCounts = likes.filter { !it.quality.isNullOrBlank() }
            .groupingBy { it.quality!!.lowercase() }.eachCount()
        val maxQCount = qualityCounts.values.maxOrNull()?.toFloat() ?: 1f
        val qualityWeights = qualityCounts.mapValues { (_, count) ->
            count.toFloat() / maxQCount
        }

        val categoryCounts = likes.filter { !it.category.isNullOrBlank() }
            .groupingBy { it.category!!.lowercase() }.eachCount()
        val maxCatCount = categoryCounts.values.maxOrNull()?.toFloat() ?: 1f
        val categoryWeights = categoryCounts.mapValues { (_, count) ->
            count.toFloat() / maxCatCount
        }

        val profile = LearnedUserProfile(
            preferredKeywords = keywordWeights.entries.joinToString(";") { "${it.key}:${it.value}" },
            preferredProviders = providerWeights.entries.joinToString(";") { "${it.key}:${it.value}" },
            preferredCategories = categoryWeights.entries.joinToString(";") { "${it.key}:${it.value}" },
            preferredQualities = qualityWeights.entries.joinToString(";") { "${it.key}:${it.value}" },
            totalLikes = likes.size
        )
        learnedProfileDao.saveProfile(profile)
    }
}
