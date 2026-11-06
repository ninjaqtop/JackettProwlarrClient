package com.aggregatorx.app.data.repository

import android.util.Log
import com.aggregatorx.app.data.database.*
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.engine.analyzer.CandidateSource
import com.aggregatorx.app.engine.analyzer.PaginationHint
import com.aggregatorx.app.engine.analyzer.SearchCandidate
import com.aggregatorx.app.engine.analyzer.SearchConfigVerifier
import com.aggregatorx.app.engine.analyzer.SiteAnalyzerEngine
import com.aggregatorx.app.engine.analyzer.SiteRecon
import com.aggregatorx.app.engine.analyzer.SiteReconEngine
import com.aggregatorx.app.engine.analyzer.VerificationOutcome
import com.aggregatorx.app.engine.analyzer.VerifierCandidate
import com.aggregatorx.app.engine.nlp.NaturalLanguageQueryProcessor
import com.aggregatorx.app.engine.ranking.RankingEngine
import com.aggregatorx.app.engine.scraper.ScrapingEngine
import com.aggregatorx.app.engine.validator.ProviderConfigValidator
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
    private val nlpProcessor: NaturalLanguageQueryProcessor,
    private val siteReconEngine: SiteReconEngine,
    private val searchConfigVerifier: SearchConfigVerifier,
    private val database: AggregatorDatabase
) {
    private companion object {
        const val TAG = "AggregatorRepository"
        const val PROVIDER_ANALYSIS_TIMEOUT_MS = 60_000L
        const val RECON_TIMEOUT_MS = 45_000L
    }

    fun clearSearchCache() {
        scrapingEngine.clearCache()
    }
    // Providers
    fun getAllProviders(): Flow<List<Provider>> = providerDao.getAllProviders()
    fun getEnabledProviders(): Flow<List<Provider>> = providerDao.getEnabledProviders()
    suspend fun getEnabledProvidersSnapshot(): List<Provider> = providerDao.getEnabledProvidersSync()
    
    suspend fun getProviderById(id: String): Provider? = providerDao.getProviderById(id)
    
    suspend fun addProvider(url: String, name: String? = null): Provider {
        val normalizedUrl = normalizeUrl(url)
        val baseUrl = extractBaseUrl(normalizedUrl)
        
        val existingProvider = providerDao.getProviderByUrl(normalizedUrl)
            ?: providerDao.getProviderByBaseUrl(baseUrl)
        if (existingProvider != null) {
            val updated = existingProvider.copy(
                url = normalizedUrl,
                baseUrl = baseUrl,
                isEnabled = true,
                name = name ?: existingProvider.name
            )
            providerDao.updateProvider(updated)
            clearSearchCache()
            return updated
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
        clearSearchCache()
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

        // Generate + live-verify the scraping config. The provider itself
        // stays saved either way; a config that cannot be verified is not
        // saved, and the rejection reason is surfaced to the caller so the
        // Analyzer UI shows the specific failure instead of a silent pass.
        var configFailure: ConfigVerificationException? = null
        try {
            generateScrapingConfig(provider, analysis)
        } catch (e: ConfigVerificationException) {
            configFailure = e
        } catch (e: CancellationException) {
            // Cancellation is not a config outcome. The previous
            // runCatching swallowed it here and execution continued into
            // further writes on the cancelled scope, surfacing as a
            // confusing secondary failure — propagate it as-is instead.
            throw e
        } catch (e: Exception) {
            // Never report success when the config step itself crashed:
            // record the real reason and surface it like any other failure.
            Log.w(TAG, "Config generation failed for provider=${provider.name}: ${e.message}")
            val reason = "configuration step failed (${e.javaClass.simpleName}: ${e.message ?: "no detail"})"
            recordFailureInAnalysis(analysis, listOf(reason), null, scrapingConfigDao.getConfigForProvider(providerId))
            configFailure = ConfigVerificationException("Provider saved, but $reason", providerId)
        }

        // Update provider last analyzed timestamp
        providerDao.updateLastAnalyzed(providerId, System.currentTimeMillis())

        configFailure?.let { throw if (it.providerId == null) ConfigVerificationException(it.message ?: "", providerId) else it }

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
    
    fun searchAllProviders(query: String, pages: Map<String, Int> = emptyMap()): Flow<ProviderSearchResults> {
        // Always bypass cache from the UI search path so newly analyzed providers and
        // explicit page requests are reflected immediately.
        return scrapingEngine.searchAllProviders(query, pages, false)
    }

    suspend fun searchProviderPage(providerId: String, query: String, pageOffset: Int): ProviderSearchResults? {
        val provider = providerDao.getProviderById(providerId) ?: return null
        return scrapingEngine.searchProviderSmart(provider, query, pageOffset)
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
    private data class GeneratedSearchRequest(
        val urlTemplate: String,
        val postData: String? = null
    )

    /**
     * Ensure the provider has a config that is proven against the
     * site's live search right now. An existing config is re-verified
     * first (re-analyze = re-map + re-prove); a new or replacement
     * config is saved only when it passes BOTH gates:
     *
     *  1. the static [ProviderConfigValidator] gate (well-formed selectors
     *     and template), and
     *  2. live verification ([SearchConfigVerifier]): the candidate's own
     *     search route must return item results — not category pages —
     *     for a probe term taken from the site's own inventory, and
     *     ~nothing for a nonsense term.
     *
     * Candidates come from the site's own data: the Analyzer's analysis,
     * and the [SiteReconEngine] recon pass (its real search form,
     * sitemap-derived routes, live-verified endpoints, and probed JSON
     * APIs). No guessed URL shapes are saved anymore: if nothing
     * verifies, a [ConfigVerificationException] carries every specific
     * reason to the UI. The provider row itself is untouched here — it
     * stays saved, enabled, and visible regardless of the outcome.
     */
    private suspend fun generateScrapingConfig(provider: Provider, analysis: SiteAnalysis) {
        val existing = scrapingConfigDao.getConfigForProvider(provider.id)

        // Recon: (re-)map the site from its own data (bounded; never
        // throws out). This runs on re-analyze too — a site that changed
        // gets re-mapped, not just re-trusted.
        val recon = withTimeoutOrNull(RECON_TIMEOUT_MS) {
            try {
                siteReconEngine.mapSite(provider.baseUrl, analysis.rawHtml)
            } catch (e: CancellationException) {
                // The 20 s budget's own timeout is converted to null by
                // withTimeoutOrNull as before; an outer cancellation
                // propagates instead of being swallowed by a catch-all.
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val configTimeout = recon?.suggestedTimeoutMs ?: 30_000
        val paginationHint = PaginationHint(
            param = recon?.paginationParam,
            pathStyle = recon?.paginationPathStyle ?: false,
            inventoryEvidence = recon != null && (recon.paginationParam != null || recon.paginationPathStyle)
        )

        // Re-verification: an existing config must re-prove itself
        // against the site's live search. If it does (possibly with
        // repaired selectors), it stays — with its timeout refreshed
        // from the measured probe profile. If it does not, we look for
        // a proven replacement below; the old config is only deleted
        // once a replacement has actually verified, never on a bad day.
        var existingRejectionReasons: List<String> = emptyList()
        if (existing != null) {
            val reOutcome = searchConfigVerifier.verify(
                provider,
                listOf(
                    VerifierCandidate(
                        existing,
                        "Existing saved config",
                        isFormRoute = existing.postData != null
                    )
                ),
                recon?.probeQuery,
                paginationHint
            )
            if (reOutcome is VerificationOutcome.Verified) {
                val refreshed = reOutcome.config.copy(timeout = finalTimeoutMs(reOutcome, recon))
                // Single-row REPLACE keyed by provider ID: an atomic upsert.
                scrapingConfigDao.insertConfig(refreshed)
                if (recon != null && recon.medianResponseMs > 0) {
                    providerDao.updateProviderStats(provider.id, provider.healthScore, recon.medianResponseMs)
                }
                recordVerificationInAnalysis(analysis, reOutcome.copy(config = refreshed), recon)
                return
            }
            existingRejectionReasons = (reOutcome as VerificationOutcome.Rejected).reasons
        }

        val candidates = mutableListOf<VerifierCandidate>()
        fun addCandidate(config: ScrapingConfig, label: String, isFormRoute: Boolean = false) {
            if (candidates.none {
                    it.config.searchUrlTemplate == config.searchUrlTemplate &&
                        it.config.postData == config.postData &&
                        it.config.resultSelector == config.resultSelector
                }
            ) {
                candidates += VerifierCandidate(config, label, isFormRoute)
            }
        }

        // (a) The Analyzer's own analysis, as before — but now it must
        // also prove itself live instead of being trusted from one page.
        val analysisResultSelector = analysis.resultItemSelector?.takeIf { it.isNotBlank() }
        val analysisTitleSelector = analysis.titleSelector?.takeIf { it.isNotBlank() }
        if (analysisResultSelector != null && analysisTitleSelector != null) {
            val searchRequest = buildSearchRequestTemplate(provider.baseUrl, analysis)
            addCandidate(
                ScrapingConfig(
                    providerId = provider.id,
                    searchUrlTemplate = searchRequest.urlTemplate,
                    resultSelector = analysisResultSelector,
                    titleSelector = analysisTitleSelector,
                    urlSelector = "a[href]",
                    descriptionSelector = analysis.descriptionSelector,
                    thumbnailSelector = analysis.thumbnailSelector,
                    dateSelector = analysis.dateSelector,
                    ratingSelector = analysis.ratingSelector,
                    postData = searchRequest.postData,
                    timeout = configTimeout
                ),
                "Analyzer selectors + form",
                isFormRoute = analysis.searchFormSelector != null
            )
        }

        // (b) Recon candidates: real form, sitemap routes, live endpoints,
        // probed JSON APIs — every value derived from the site itself.
        for (candidate in recon?.searchCandidates.orEmpty()) {
            addCandidate(
                config = scrapingConfigForCandidate(provider, analysis, candidate, configTimeout),
                label = when (candidate.source) {
                    CandidateSource.FORM -> "Site search form"
                    CandidateSource.OPENSEARCH -> "OpenSearch template"
                    CandidateSource.SITEMAP_DERIVED -> "Sitemap-derived route"
                    CandidateSource.DISCOVERED_ENDPOINT -> "Discovered endpoint"
                    CandidateSource.JSON_API -> "JSON API endpoint"
                    CandidateSource.EMBEDDED_STATE -> "Embedded state endpoint"
                    CandidateSource.WORDLIST -> "Wordlist route (verified live)"
                } + if (candidate.isJson) " (JSON)" else "",
                isFormRoute = candidate.source == CandidateSource.FORM
            )
        }

        // Gate 1: static validation. Error-level failures never reach the
        // live probe; their reasons are kept for the final report.
        val staticFailures = mutableListOf<String>()
        val liveCandidates = candidates.filter { candidate ->
            val validation = ProviderConfigValidator.validateScrapingConfig(
                candidate.config, provider.baseUrl, null
            )
            if (validation.isValid) {
                true
            } else {
                staticFailures += "${candidate.sourceLabel}: invalid config — ${validation.errors.joinToString(", ")}"
                false
            }
        }
        if (liveCandidates.isEmpty()) {
            val reasons = staticFailures.ifEmpty {
                listOf("no candidate search route could be derived from the site's own data (no search form, sitemap route, or live endpoint found)")
            }
            recordFailureInAnalysis(analysis, reasons, recon, existing)
            throw ConfigVerificationException(
                "Provider saved, but no search configuration could be verified: " + reasons.joinToString("; ")
            )
        }

        // Gate 2: live verification against the site's own search.
        when (val outcome = searchConfigVerifier.verify(provider, liveCandidates, recon?.probeQuery, paginationHint)) {
            is VerificationOutcome.Verified -> {
                val finalConfig = outcome.config.copy(timeout = finalTimeoutMs(outcome, recon))
                // The row is keyed by provider ID, so this REPLACE is one
                // atomic upsert: a proven replacement swaps in without a
                // delete-then-insert window, and only after it verified.
                scrapingConfigDao.insertConfig(finalConfig)
                Log.i(
                    TAG,
                    "Verified config saved for provider=${provider.name}: ${outcome.verdict.reason}" +
                        if (outcome.repaired) " (repaired from live evidence)" else ""
                )
                if (recon != null && recon.medianResponseMs > 0) {
                    providerDao.updateProviderStats(provider.id, provider.healthScore, recon.medianResponseMs)
                }
                recordVerificationInAnalysis(analysis, outcome.copy(config = finalConfig), recon)
            }
            is VerificationOutcome.Rejected -> {
                recordFailureInAnalysis(
                    analysis,
                    existingRejectionReasons.map { "existing config: $it" } + staticFailures + outcome.reasons,
                    recon,
                    existing
                )
                val prefix = if (existingRejectionReasons.isNotEmpty()) {
                    "The existing config failed re-verification (" +
                        existingRejectionReasons.joinToString("; ") +
                        ") and no proven replacement was found — the previously saved config was left in place. "
                } else {
                    "Provider saved, but its search could not be verified: "
                }
                throw ConfigVerificationException(
                    prefix + (staticFailures + outcome.reasons).joinToString("; ")
                )
            }
        }
    }

    /**
     * The saved config's timeout, from measured evidence: the slowest
     * verification probe (with margin) and the recon median profile,
     * whichever demands more — clamped to the app's bounded limits so
     * slow sites stop dying on a generic timeout without any single
     * provider being able to hang a search.
     */
    private fun finalTimeoutMs(outcome: VerificationOutcome.Verified, recon: SiteRecon?): Int {
        val fromProbes = outcome.verdict.slowestProbeMs
            .takeIf { it > 0 }
            ?.let { (it * 2 + 2_000).toInt() }
        val parts = listOfNotNull(fromProbes, recon?.suggestedTimeoutMs)
        return (parts.maxOrNull() ?: 30_000).coerceIn(10_000, 45_000)
    }

    /**
     * Translate one recon candidate into a [ScrapingConfig]. For a
     * JSON-driven route the derived result-array path and field names are
     * recorded in the selector fields (the runtime's JSON extractor keys
     * off the payload the same way); for HTML routes the Analyzer's
     * selectors are preferred, with broad fallbacks the live verifier
     * can repair from the actual results page.
     */
    private fun scrapingConfigForCandidate(
        provider: Provider,
        analysis: SiteAnalysis,
        candidate: SearchCandidate,
        timeoutMs: Int
    ): ScrapingConfig {
        return if (candidate.isJson) {
            ScrapingConfig(
                providerId = provider.id,
                searchUrlTemplate = candidate.urlTemplate,
                resultSelector = candidate.jsonResultPath?.takeIf { it.isNotBlank() } ?: "results",
                titleSelector = candidate.jsonTitleField ?: "title",
                urlSelector = candidate.jsonUrlField ?: "url",
                descriptionSelector = analysis.descriptionSelector,
                thumbnailSelector = candidate.jsonThumbnailField ?: analysis.thumbnailSelector,
                dateSelector = analysis.dateSelector,
                ratingSelector = analysis.ratingSelector,
                postData = candidate.postData,
                timeout = timeoutMs,
                method = if (candidate.postData != null) "POST" else "GET",
                isJson = true,
                jsonResultPath = candidate.jsonResultPath,
                jsonTitleField = candidate.jsonTitleField,
                jsonUrlField = candidate.jsonUrlField,
                jsonThumbnailField = candidate.jsonThumbnailField
            )
        } else {
            ScrapingConfig(
                providerId = provider.id,
                searchUrlTemplate = candidate.urlTemplate,
                resultSelector = analysis.resultItemSelector?.takeIf { it.isNotBlank() }
                    ?: "article, .result, .item, .card, .post, .entry, li",
                titleSelector = analysis.titleSelector?.takeIf { it.isNotBlank() }
                    ?: "a, h1, h2, h3, h4, .title, [class*='title']",
                urlSelector = "a[href]",
                descriptionSelector = analysis.descriptionSelector,
                thumbnailSelector = analysis.thumbnailSelector,
                dateSelector = analysis.dateSelector,
                ratingSelector = analysis.ratingSelector,
                postData = candidate.postData,
                timeout = timeoutMs
            )
        }
    }

    /**
     * Persist the analysis record (spec §3) onto the stored SiteAnalysis row
     * (same primary key — the DAO replaces): the full site map, the full saved
     * config JSON, per-field confidence and the probe evidence, in
     * analysisMetadata — never in the HTTP `headers` column.
     */
    private suspend fun recordVerificationInAnalysis(
        analysis: SiteAnalysis,
        outcome: VerificationOutcome.Verified,
        recon: SiteRecon?
    ) {
        val matched = recon?.searchCandidates?.firstOrNull {
            it.urlTemplate == outcome.config.searchUrlTemplate && it.postData == outcome.config.postData
        }
        val endpointsJson = buildJsonArray {
            add(buildJsonObject {
                put("url", JsonPrimitive(outcome.config.searchUrlTemplate))
                put("source", JsonPrimitive(outcome.verdict.sourceLabel))
                put("verified", JsonPrimitive(true))
                put("itemCount", JsonPrimitive(outcome.verdict.itemCount))
                put("categoryCount", JsonPrimitive(outcome.verdict.categoryCount))
                put("nonsenseItemCount", JsonPrimitive(outcome.verdict.nonsenseItemCount))
                put("confidence", JsonPrimitive(outcome.verdict.confidence))
                put("paginationProven", JsonPrimitive(outcome.verdict.paginationProven))
            })
        }.toString()
        val metadata = AnalysisRecord.build(
            previous = analysis.analysisMetadata,
            recon = recon,
            config = outcome.config,
            verification = AnalysisRecord.verified(outcome, recon?.probeQuery)
        )
        runCatching {
            siteAnalysisDao.insertAnalysis(
                analysis.copy(
                    apiEndpoints = endpointsJson,
                    hasAPI = analysis.hasAPI || (matched?.isJson == true) || outcome.config.isJson,
                    analysisMetadata = metadata,
                    verificationStatus = "VERIFIED",
                    verificationReason = null
                )
            )
        }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "could not record analysis: ${it.message}") }
    }

    /** Record a failed verification with its specific reasons — the provider row stays saved. */
    private suspend fun recordFailureInAnalysis(
        analysis: SiteAnalysis,
        reasons: List<String>,
        recon: SiteRecon?,
        keptConfig: ScrapingConfig?
    ) {
        val metadata = AnalysisRecord.build(
            previous = analysis.analysisMetadata,
            recon = recon,
            config = keptConfig,
            verification = AnalysisRecord.failed(reasons, recon?.probeQuery, keptConfig != null)
        )
        runCatching {
            siteAnalysisDao.insertAnalysis(
                analysis.copy(
                    analysisMetadata = metadata,
                    verificationStatus = "FAILED",
                    verificationReason = reasons.joinToString("; ").take(4_000)
                )
            )
        }.onFailure { if (it is CancellationException) throw it; Log.w(TAG, "could not record analysis: ${it.message}") }
    }

    private fun buildSearchRequestTemplate(baseUrl: String, analysis: SiteAnalysis): GeneratedSearchRequest {
        val defaultTemplate = "${baseUrl.trimEnd('/')}/search?q={query}&page={page}"

        val analyzedDocument = analysis.rawHtml
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Jsoup.parse(it, analysis.url) }.getOrNull() }

        val form = analyzedDocument?.let { document ->
            analysis.searchFormSelector
                ?.let { selector -> runCatching { document.selectFirst(selector) }.getOrNull() }
                ?: document.selectFirst(
                    "form:has(input[type=search]), form:has(input[name=q]), " +
                        "form:has(input[name=query]), form:has(input[name=search]), form:has(input[name=s]), " +
                        "form:has(input[name=keyword]), form:has(input[name=term])"
                )
        } ?: return GeneratedSearchRequest(defaultTemplate)

        val action = form.absUrl("action").ifBlank { analysis.url.ifBlank { baseUrl } }
        if (!action.startsWith("http", ignoreCase = true)) {
            return GeneratedSearchRequest(defaultTemplate)
        }

        val fields = form.select("input[name], select[name], textarea[name]")
        val pairs = fields.mapNotNull { element ->
            val name = element.attr("name").trim()
            if (name.isBlank()) return@mapNotNull null
            val tag = element.tagName().lowercase()
            val type = element.attr("type").lowercase()
            if (tag == "input" && type in setOf("submit", "button", "image", "file", "password")) {
                return@mapNotNull null
            }
            if (tag == "input" && type in setOf("checkbox", "radio") && !element.hasAttr("checked")) {
                return@mapNotNull null
            }
            val isQueryField = name.equals("q", ignoreCase = true) ||
                name.equals("query", ignoreCase = true) ||
                name.equals("search", ignoreCase = true) ||
                name.equals("s", ignoreCase = true) ||
                name.equals("keyword", ignoreCase = true) ||
                name.equals("term", ignoreCase = true) ||
                type == "search" ||
                element.attr("placeholder").contains("search", ignoreCase = true)
            val isPageField = name.equals("page", ignoreCase = true) ||
                name.equals("paged", ignoreCase = true) ||
                name.equals("p", ignoreCase = true) ||
                name.equals("page_num", ignoreCase = true)
            val value = when {
                isQueryField -> "{query}"
                isPageField -> "{page}"
                tag == "select" -> element.selectFirst("option[selected]")?.attr("value")
                    ?: element.selectFirst("option")?.attr("value")
                    ?: ""
                tag == "textarea" -> element.text()
                else -> element.attr("value")
            }
            if (value.isNotBlank() || isQueryField || isPageField) name to value else null
        }.distinctBy { it.first }

        val queryName = pairs.firstOrNull { it.second == "{query}" }?.first ?: "q"
        val parts = pairs
            .filter { it.first != queryName }
            .map { (fieldName, fieldValue) -> "${encodeTemplateField(fieldName)}=${encodeTemplateField(fieldValue)}" }
            .toMutableList()
        parts.add("${encodeTemplateField(queryName)}={query}")
        if (pairs.none { it.second == "{page}" }) {
            parts.add("page={page}")
        }

        return if (!form.attr("method").equals("post", ignoreCase = true)) {
            val separator = if (action.contains('?')) "&" else "?"
            GeneratedSearchRequest(action + separator + parts.joinToString("&"))
        } else {
            GeneratedSearchRequest(urlTemplate = action, postData = parts.joinToString("&"))
        }
    }

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

/**
 * Raised when a provider was saved but none of its candidate search
 * configurations passed live verification. The message carries the
 * specific per-route reasons and is surfaced through the Analyzer UI's
 * existing error state — the provider stays saved, enabled, and
 * visible; only the unverified config is withheld.
 */
class ConfigVerificationException(
    message: String,
    /** The provider that WAS saved (its row stays, with this reason recorded). */
    val providerId: String? = null
) : Exception(message)
