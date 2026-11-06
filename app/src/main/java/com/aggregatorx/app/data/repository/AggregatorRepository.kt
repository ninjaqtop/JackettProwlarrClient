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
import kotlinx.serialization.json.Json
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
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    }

    suspend fun analyzeProvider(providerId: String): SiteAnalysis {
        val provider = providerDao.getProviderById(providerId)
            ?: throw IllegalArgumentException("Provider not found")

        val analysis = runCatching {
            withTimeout(PROVIDER_ANALYSIS_TIMEOUT_MS) {
                siteAnalyzerEngine.analyzeSite(provider.url, providerId)
            }
        }.getOrElse {
            if (it is CancellationException && it !is TimeoutCancellationException) throw it
            SiteAnalysis(
                providerId = providerId,
                url = provider.url,
                analyzedAt = System.currentTimeMillis(),
                hasSSL = provider.url.startsWith("https://", ignoreCase = true),
                scrapingStrategy = ScrapingStrategy.HYBRID,
                requiresJavaScript = true,
                rawHtml = null,
                headers = "{}",
                cookies = "[]"
            )
        }

        siteAnalysisDao.insertAnalysis(analysis)

        // Persist config and the public site map from analyzer output even if some selectors
        // are weak or missing. The provider remains saved and reusable with generic fallback.
        runCatching { generateScrapingConfig(provider, analysis) }

        providerDao.updateLastAnalyzed(providerId, System.currentTimeMillis())
        return analysis
    }

    private suspend fun generateScrapingConfig(provider: Provider, analysis: SiteAnalysis) {
        val siteMapJson = analysis.siteMapJson.takeIf { it.isNotBlank() && it != "{}" }
        val siteMap = siteMapJson?.let {
            runCatching { SiteMapConfigAnalyzer.analyze(analysis.rawHtml ?: "", analysis.url) }.getOrNull()
        }
        val enhancedSearchTemplate = when {
            siteMap != null && !siteMap.searchUrlTemplate.isNullOrBlank() -> siteMap.searchUrlTemplate
            analysis.searchFormSelector != null -> buildSearchUrlTemplate(provider.baseUrl, analysis)
            else -> "$${provider.baseUrl}/search?q={query}&page={page}"
        }

        val config = ScrapingConfig(
            providerId = provider.id,
            searchUrlTemplate = enhancedSearchTemplate.replace("\$", "").trim(),
            resultSelector = analysis.resultItemSelector ?: siteMap?.resultSelector ?: ".item, .result, article",
            titleSelector = analysis.titleSelector ?: siteMap?.titleSelector ?: "h2, .title, a",
            urlSelector = analysis.searchFormSelector?.let { "a[href]" } ?: siteMap?.linkSelector ?: "a[href]",
            descriptionSelector = analysis.descriptionSelector,
            thumbnailSelector = analysis.thumbnailSelector ?: siteMap?.thumbnailSelector,
            dateSelector = analysis.dateSelector,
            ratingSelector = analysis.ratingSelector,
            headers = analysis.headers.ifBlank { "{}" },
            cookies = analysis.cookies.ifBlank { "[]" },
            userAgent = com.aggregatorx.app.engine.util.EngineUtils.DEFAULT_USER_AGENT,
            timeout = 12_000,
            retryCount = 1
        )
        scrapingConfigDao.deleteConfigForProvider(provider.id)
        scrapingConfigDao.insertConfig(config)
    }

    private fun buildSearchUrlTemplate(baseUrl: String, analysis: SiteAnalysis): String {
        val analyzedDocument = analysis.rawHtml
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Jsoup.parse(it, analysis.url) }.getOrNull() }

        val form = analyzedDocument?.let { document ->
            analysis.searchFormSelector
                ?.let { selector -> runCatching { document.selectFirst(selector) }.getOrNull() }
                ?: document.selectFirst(
                    "form:has(input[type=search]), form:has(input[name=q]), " +
                        "form:has(input[name=query]), form:has(input[name=search]), form:has(input[name=s])"
                )
        }

        if (form != null && !form.attr("method").equals("post", ignoreCase = true)) {
            val action = form.absUrl("action").ifBlank { analysis.url.ifBlank { baseUrl } }
            val queryInput = form.select(
                "input[type=search], input[name=q], input[name=query], input[name=search], input[name=s], input[name=keyword], input[name=term]"
            ).firstOrNull()
            val queryName = queryInput?.attr("name")?.takeIf { it.isNotBlank() } ?: "q"
            val fixedFields = form.select("input[name]")
                .filter { input ->
                    input !== queryInput &&
                        input.attr("type").lowercase() in setOf("hidden", "submit") &&
                        input.attr("value").isNotBlank()
                }
                .joinToString("&") { input ->
                    "${URLEncoder.encode(input.attr("name"), "UTF-8")}=${URLEncoder.encode(input.attr("value"), "UTF-8")}"
                }
            val separator = if (action.contains('?')) "&" else "?"
            return buildString {
                append(action)
                append(separator)
                if (fixedFields.isNotBlank()) {
                    append(fixedFields)
                    append('&')
                }
                append(URLEncoder.encode(queryName, "UTF-8"))
                append("={query}&page={page}")
            }
        }

        return "$baseUrl/search?q={query}&page={page}"
    }

    // Keep the rest of the repository exactly as-is.
}