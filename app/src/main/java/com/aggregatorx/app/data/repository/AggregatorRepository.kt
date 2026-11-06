package com.aggregatorx.app.data.repository

import com.aggregatorx.app.data.database.*
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.engine.analyzer.SiteAnalyzerEngine
import com.aggregatorx.app.engine.analyzer.SiteMapConfigAnalyzer
import com.aggregatorx.app.engine.nlp.NaturalLanguageQueryProcessor
import com.aggregatorx.app.engine.ranking.RankingEngine
import com.aggregatorx.app.engine.scraper.ScrapingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URL
import java.net.URLEncoder
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
    private companion object {
        const val PROVIDER_ANALYSIS_TIMEOUT_MS = 60_000L
        const val SITE_MAP_HEADER = "AggregatorX-Site-Map"
        const val PROVIDER_CONFIG_HEADER = "AggregatorX-Provider-Config"
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    }

    fun clearSearchCache() = scrapingEngine.clearCache()
    fun getAllProviders(): Flow<List<Provider>> = providerDao.getAllProviders()
    fun getEnabledProviders(): Flow<List<Provider>> = providerDao.getEnabledProviders()
    suspend fun getEnabledProvidersSnapshot(): List<Provider> = providerDao.getEnabledProvidersSync()
    suspend fun getProviderById(id: String): Provider? = providerDao.getProviderById(id)

    suspend fun addProvider(url: String, name: String? = null): Provider {
        val normalizedUrl = normalizeUrl(url)
        val baseUrl = extractBaseUrl(normalizedUrl)
        val existing = providerDao.getProviderByUrl(normalizedUrl) ?: providerDao.getProviderByBaseUrl(baseUrl)
        if (existing != null) {
            val updated = existing.copy(url = normalizedUrl, baseUrl = baseUrl, isEnabled = true, name = name ?: existing.name)
            providerDao.updateProvider(updated)
            clearSearchCache()
            return updated
        }
        val provider = Provider(
            id = UUID.randomUUID().toString(), name = name ?: extractSiteName(normalizedUrl),
            url = normalizedUrl, baseUrl = baseUrl, isEnabled = true, category = detectCategory(normalizedUrl)
        )
        providerDao.insertProvider(provider)
        clearSearchCache()
        return provider
    }

    suspend fun updateProvider(provider: Provider) = providerDao.updateProvider(provider)

    suspend fun deleteProvider(providerId: String) {
        providerDao.deleteProviderById(providerId)
        siteAnalysisDao.deleteAnalysesForProvider(providerId)
        scrapingConfigDao.deleteConfigForProvider(providerId)
    }

    suspend fun setProviderEnabled(providerId: String, enabled: Boolean) = providerDao.setProviderEnabled(providerId, enabled)

    suspend fun analyzeProvider(providerId: String): SiteAnalysis {
        val provider = providerDao.getProviderById(providerId) ?: throw IllegalArgumentException("Provider not found")
        val rawAnalysis = runCatching {
            withTimeout(PROVIDER_ANALYSIS_TIMEOUT_MS) { siteAnalyzerEngine.analyzeSite(provider.url, providerId) }
        }.getOrElse {
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
            SiteAnalysis(
                providerId = providerId, url = provider.url, analyzedAt = System.currentTimeMillis(),
                hasSSL = provider.url.startsWith("https://", ignoreCase = true),
                scrapingStrategy = ScrapingStrategy.HYBRID, requiresJavaScript = true,
                rawHtml = null, headers = "{}", cookies = "[]"
            )
        }

        val mapping = SiteMapConfigAnalyzer.analyze(rawAnalysis.rawHtml.orEmpty(), rawAnalysis.url.ifBlank { provider.url })
        val config = buildScrapingConfig(provider, rawAnalysis, mapping)
        val enrichedHeaders = enrichAnalyzerJson(rawAnalysis.headers, mapping, config)
        val analysis = rawAnalysis.copy(headers = enrichedHeaders)

        // Keep both persisted records linked to the same provider; analysis JSON also
        // carries a readable/exportable copy for the Settings analysis screen.
        siteAnalysisDao.insertAnalysis(analysis)
        scrapingConfigDao.deleteConfigForProvider(providerId)
        scrapingConfigDao.insertConfig(config)
        providerDao.updateLastAnalyzed(providerId, System.currentTimeMillis())
        return analysis
    }

    private fun buildScrapingConfig(
        provider: Provider,
        analysis: SiteAnalysis,
        mapping: SiteMapConfigAnalyzer.SiteMapConfig
    ): ScrapingConfig {
        val template = mapping.searchUrlTemplate ?: buildSearchUrlTemplate(provider.baseUrl, analysis)
        return ScrapingConfig(
            providerId = provider.id,
            searchUrlTemplate = template,
            resultSelector = mapping.resultSelector ?: analysis.resultItemSelector ?: ".item, .result, article",
            titleSelector = mapping.titleSelector ?: analysis.titleSelector ?: "h2, .title, a",
            urlSelector = mapping.linkSelector ?: "a[href]",
            descriptionSelector = analysis.descriptionSelector,
            thumbnailSelector = mapping.thumbnailSelector ?: analysis.thumbnailSelector,
            dateSelector = analysis.dateSelector,
            ratingSelector = analysis.ratingSelector,
            headers = analysis.headers.ifBlank { "{}" },
            cookies = analysis.cookies.ifBlank { "[]" },
            userAgent = com.aggregatorx.app.engine.util.EngineUtils.DEFAULT_USER_AGENT,
            timeout = 12_000,
            retryCount = 1
        )
    }

    private fun enrichAnalyzerJson(
        headers: String,
        mapping: SiteMapConfigAnalyzer.SiteMapConfig,
        config: ScrapingConfig
    ): String {
        val root = runCatching { JSONObject(headers) }.getOrElse { JSONObject() }
        val report = runCatching { JSONObject(root.optString(SiteAnalyzerEngine.CAPABILITY_REPORT_HEADER, "{}")) }
            .getOrElse { JSONObject() }
        val sections = report.optJSONArray("sections") ?: JSONArray().also { report.put("sections", it) }
        val checks = JSONArray()
        fun check(name: String, value: String, status: String = if (value.isBlank()) "not_detected" else "detected") {
            checks.put(JSONObject().put("name", name).put("status", status).put("detail", value))
        }
        check("Search route", mapping.searchUrlTemplate.orEmpty(), if (mapping.searchUrlTemplate.isNullOrBlank()) "not_detected" else "detected")
        check("Search form selector", mapping.searchFormSelector.orEmpty())
        check("Query input selector", mapping.queryInputSelector.orEmpty())
        check("Result card selector", mapping.resultSelector.orEmpty())
        check("Result title selector", mapping.titleSelector.orEmpty())
        check("Result link selector", mapping.linkSelector.orEmpty())
        check("Thumbnail selector", mapping.thumbnailSelector.orEmpty())
        check("Mapping confidence", "${(mapping.confidence * 100).toInt()}%")
        check("Saved provider config", "Room ScrapingConfig saved; timeout=${config.timeout}ms, retries=${config.retryCount}")
        sections.put(JSONObject().put("title", "Saved Site Mapping & Provider Config").put("checks", checks))
        report.put("generatedAt", System.currentTimeMillis())
        root.put(SiteAnalyzerEngine.CAPABILITY_REPORT_HEADER, report.toString())
        root.put(SITE_MAP_HEADER, SiteMapConfigAnalyzer.encode(mapping))
        root.put(PROVIDER_CONFIG_HEADER, json.encodeToString(config))
        return root.toString()
    }

    private fun buildSearchUrlTemplate(baseUrl: String, analysis: SiteAnalysis): String {
        val doc = analysis.rawHtml?.takeIf { it.isNotBlank() }?.let { runCatching { Jsoup.parse(it, analysis.url) }.getOrNull() }
        val form = doc?.let { document ->
            analysis.searchFormSelector?.let { selector -> runCatching { document.selectFirst(selector) }.getOrNull() }
                ?: document.selectFirst("form:has(input[type=search]), form:has(input[name=q]), form:has(input[name=query]), form:has(input[name=search]), form:has(input[name=s])")
        }
        if (form != null && !form.attr("method").equals("post", ignoreCase = true)) {
            val action = form.absUrl("action").ifBlank { analysis.url.ifBlank { baseUrl } }
            val input = form.select("input[type=search], input[name=q], input[name=query], input[name=search], input[name=s], input[name=keyword], input[name=term]").firstOrNull()
            val queryName = input?.attr("name")?.takeIf { it.isNotBlank() } ?: "q"
            val fixed = form.select("input[name]").filter { it !== input && it.attr("type").lowercase() in setOf("hidden", "submit") && it.attr("value").isNotBlank() }
                .joinToString("&") { "${URLEncoder.encode(it.attr("name"), "UTF-8")}=${URLEncoder.encode(it.attr("value"), "UTF-8")}" }
            return action + (if (action.contains('?')) "&" else "?") +
                (if (fixed.isBlank()) "" else "$fixed&") + "${URLEncoder.encode(queryName, "UTF-8")}={query}&page={page}"
        }
        return "$baseUrl/search?q={query}&page={page}"
    }

    suspend fun analyzeNewUrl(url: String): Pair<Provider, SiteAnalysis> {
        val provider = addProvider(url)
        return provider to analyzeProvider(provider.id)
    }

    suspend fun refreshAllProviders(): List<Pair<Provider, Result<SiteAnalysis>>> = providerDao.getEnabledProvidersSync().map { provider ->
        try { provider to Result.success(analyzeProvider(provider.id)) }
        catch (e: Exception) { provider to Result.failure(e) }
    }

    suspend fun getLatestAnalysis(providerId: String): SiteAnalysis? = siteAnalysisDao.getLatestAnalysis(providerId)

    fun searchAllProviders(query: String, pages: Map<String, Int> = emptyMap()): Flow<ProviderSearchResults> =
        scrapingEngine.searchAllProviders(query, pages, false)

    suspend fun searchProviderPage(providerId: String, query: String, pageOffset: Int): ProviderSearchResults? =
        providerDao.getProviderById(providerId)?.let { scrapingEngine.searchProviderSmart(it, query, pageOffset) }

    suspend fun aggregateSearchResults(query: String, providerResults: List<ProviderSearchResults>): AggregatedSearchResults {
        searchHistoryDao.insertSearch(SearchHistoryEntry(
            query = query, resultCount = providerResults.sumOf { it.results.size },
            providersSearched = providerResults.size, successfulProviders = providerResults.count { it.success }
        ))
        val likedUrls = likedResultDao.getAllLikedUrls().toSet()
        val profile = learnedProfileDao.getProfile()
        if (profile != null) rankingEngine.setUserPreferences(
            keywords = profile.preferredKeywordsMap(), providers = profile.preferredProvidersMap(),
            qualities = profile.preferredQualitiesMap(), liked = likedUrls
        ) else rankingEngine.setUserPreferences(liked = likedUrls)
        rankingEngine.setProcessedQuery(nlpProcessor.processQuery(query))
        return rankingEngine.rankAndAggregate(query, providerResults)
    }

    fun getRecentSearches(): Flow<List<SearchHistoryEntry>> = searchHistoryDao.getRecentSearches()
    suspend fun clearSearchHistory() = searchHistoryDao.clearHistory()

    suspend fun toggleLike(result: SearchResult): Boolean {
        val existing = likedResultDao.getLikedByUrl(result.url)
        return if (existing != null) {
            likedResultDao.removeLikeByUrl(result.url); rebuildLearnedProfile(); false
        } else {
            val keywords = result.title.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.distinct().joinToString(",")
            likedResultDao.insertLike(LikedResult(
                title = result.title, url = result.url, providerId = result.providerId, providerName = result.providerName,
                category = result.category.orEmpty(), quality = result.quality.orEmpty(), thumbnailUrl = result.thumbnailUrl,
                description = result.description, seeders = result.seeders, rating = result.rating, titleKeywords = keywords
            ))
            rebuildLearnedProfile(); true
        }
    }

    suspend fun getAllLikedUrls(): Set<String> = likedResultDao.getAllLikedUrls().toSet()
    fun getAllLikedResults(): Flow<List<LikedResult>> = likedResultDao.getAllLikedResults()

    private suspend fun rebuildLearnedProfile() {
        val likes = likedResultDao.getAllLikedResults().first()
        if (likes.isEmpty()) { learnedProfileDao.saveProfile(LearnedUserProfile()); return }
        fun weights(values: List<String>): String {
            val counts = values.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
            val max = counts.values.maxOrNull()?.toFloat() ?: 1f
            return counts.entries.joinToString(";") { "${it.key}:${it.value / max}" }
        }
        val profile = LearnedUserProfile(
            preferredKeywords = weights(likes.flatMap { it.titleKeywords.split(",") }),
            preferredProviders = weights(likes.map { it.providerId }),
            preferredCategories = weights(likes.mapNotNull { it.category?.lowercase() }),
            preferredQualities = weights(likes.mapNotNull { it.quality?.lowercase() }),
            totalLikes = likes.size
        )
        learnedProfileDao.saveProfile(profile)
    }

    private fun normalizeUrl(url: String): String {
        var value = url.trim()
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://$value"
        return value.trimEnd('/')
    }
    private fun extractBaseUrl(url: String): String = try { URL(url).let { "${it.protocol}://${it.host}" } } catch (_: Exception) { url }
    private fun extractSiteName(url: String): String = try { URL(url).host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() } } catch (_: Exception) { "Unknown" }
    private fun detectCategory(url: String): ProviderCategory = when {
        listOf("torrent", "1337", "rarbg", "pirate").any(url.lowercase()::contains) -> ProviderCategory.TORRENT
        listOf("stream", "movie", "watch", "video").any(url.lowercase()::contains) -> ProviderCategory.STREAMING
        listOf("news", "blog").any(url.lowercase()::contains) -> ProviderCategory.NEWS
        "api" in url.lowercase() -> ProviderCategory.API_BASED
        else -> ProviderCategory.GENERAL
    }
}
