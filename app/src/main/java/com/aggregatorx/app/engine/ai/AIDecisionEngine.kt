package com.aggregatorx.app.engine.ai

import com.aggregatorx.app.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.ln

/**
 * AggravatedX AI Decision Engine - ENHANCED WITH DEEP LEARNING CAPABILITIES
 * 
 * Core Intelligence Features:
 * - Provider ranking and selection based on learned performance
 * - Content relevance scoring with adaptive weights
 * - Adaptive scraping strategy selection
 * - Pattern learning and recognition from successes and failures
 * - Failure prediction and prevention
 * - Smart retry logic with learning
 * - Quality detection and preference
 * 
 * ADVANCED LEARNING CAPABILITIES:
 * - Learns from every scraping attempt (success or failure)
 * - Adapts to site structure changes over time
 * - Records failure patterns to avoid repeat mistakes
 * - Auto-improves scraping strategies based on results
 * - Gains "intelligence" with each interaction
 * - Learns selector patterns that work for specific sites
 * - Detects and adapts to anti-scraping measures
 * - Self-improves without breaking existing functionality
 */
@Singleton
class AIDecisionEngine @Inject constructor() {
    
    // Learning data - provider performance history
    private val providerScores = ConcurrentHashMap<String, ProviderAIScore>()
    
    // Pattern learning cache
    private val learnedPatterns = ConcurrentHashMap<String, List<LearnedPattern>>()
    
    // Site structure learning - remembers what works for each domain
    private val siteStructureCache = ConcurrentHashMap<String, SiteStructureKnowledge>()
    
    // Failure learning - remembers what doesn't work to avoid repeating mistakes
    private val failureKnowledge = ConcurrentHashMap<String, List<FailureRecord>>()
    
    // Selector learning - remembers working selectors for each site
    private val selectorKnowledge = ConcurrentHashMap<String, SelectorKnowledge>()
    
    // Strategy effectiveness tracking
    private val strategyEffectiveness = ConcurrentHashMap<String, StrategyStats>()
    
    // Global learning state - tracks overall intelligence growth
    private var globalLearningState = GlobalLearningState()
    
    // Quality preference weights
    private val qualityWeights = mapOf(
        "4k" to 1.0f,
        "2160p" to 1.0f,
        "1080p" to 0.9f,
        "full hd" to 0.9f,
        "720p" to 0.7f,
        "hd" to 0.7f,
        "480p" to 0.5f,
        "sd" to 0.4f,
        "360p" to 0.3f,
        "240p" to 0.2f
    )
    
    private val _aiState = MutableStateFlow(AIState())
    val aiState: StateFlow<AIState> = _aiState
    
    companion object {
        // Decay factor for historical data (newer data has more weight)
        private const val DECAY_FACTOR = 0.95f
        
        // Minimum confidence threshold for decisions
        private const val MIN_CONFIDENCE = 0.3f
        
        // Learning rate for score updates
        private const val LEARNING_RATE = 0.1f
        
        // Max failure records to keep per domain
        private const val MAX_FAILURE_RECORDS = 50

        // Shared whitespace-split pattern for the tokenizers below (was compiled
        // per call; Regex is immutable and only read, so sharing is equivalent).
        private val WHITESPACE_SPLIT_REGEX = Regex("\\s+")
        
        // Intelligence growth rate
        private const val INTELLIGENCE_GROWTH_RATE = 0.02f
        
        // Keywords that indicate high quality content
        private val QUALITY_KEYWORDS = setOf(
            "hdr", "dolby", "atmos", "remux", "bluray", "blu-ray",
            "webrip", "web-dl", "hdtv", "proper", "repack"
        )
        
        // Keywords that indicate potentially problematic content
        private val RISK_KEYWORDS = setOf(
            "cam", "ts", "telesync", "hdcam", "workprint", "screener"
        )
        
        // Stop words to ignore during scoring (very common, low signal)
        private val STOP_WORDS = setOf(
            "the", "a", "an", "of", "in", "on", "at", "to", "for", "and",
            "or", "but", "is", "are", "was", "were", "be", "been", "has",
            "have", "had", "do", "does", "did", "with", "this", "that",
            "it", "its", "by", "from", "up", "out", "as", "into", "than"
        )
        
        // Synonym/expansion map for common search terms
        private val QUERY_SYNONYMS = mapOf(
            "movie" to listOf("film", "cinema", "flick", "feature"),
            "film" to listOf("movie", "cinema"),
            "series" to listOf("show", "tv show", "season", "episodes"),
            "show" to listOf("series", "tv series", "season"),
            "episode" to listOf("ep", "episodes", "part"),
            "download" to listOf("torrent", "magnet", "direct"),
            "watch" to listOf("stream", "view", "play"),
            "stream" to listOf("watch", "online", "live"),
            "music" to listOf("song", "audio", "track", "album"),
            "song" to listOf("music", "track", "audio"),
            "video" to listOf("clip", "footage", "recording"),
            "anime" to listOf("animation", "cartoon", "manga"),
            "documentary" to listOf("doc", "documentary film"),
            "hd" to listOf("720p", "1080p", "high definition"),
            "4k" to listOf("2160p", "uhd", "ultra hd"),
            "new" to listOf("latest", "recent", "2024", "2025"),
            "latest" to listOf("new", "recent", "newest"),
            "free" to listOf("gratis", "no cost"),
            "game" to listOf("gaming", "gameplay"),
            "live" to listOf("stream", "online", "broadcast"),
            "full" to listOf("complete", "entire"),
            "english" to listOf("eng", "en"),
            "subtitle" to listOf("sub", "subtitled", "subs"),
            "dubbed" to listOf("dub", "audio track")
        )
        
        // Category keyword expansions for better provider matching
        private val CATEGORY_KEYWORDS = mapOf(
            "streaming" to setOf(
                "watch", "stream", "movie", "movies", "series", "tv", "episode",
                "episodes", "film", "cinema", "show", "shows", "anime", "documentary",
                "season", "netflix", "online", "hulu", "play"
            ),
            "torrent" to setOf(
                "download", "torrent", "magnet", "seed", "seeder", "leecher",
                "peers", "tracker", "dht", "pirate", "bit", "torrent"
            ),
            "news" to setOf(
                "news", "article", "latest", "breaking", "report", "journalist",
                "headline", "story", "blog", "post", "update"
            ),
            "media" to setOf(
                "video", "music", "audio", "photo", "image", "gallery",
                "mp3", "mp4", "flac", "wav", "jpeg", "png"
            )
        )
    }
    
    /**
     * Rank providers for a search query based on AI analysis
     * Returns providers sorted by predicted success rate
     */
    suspend fun rankProviders(
        providers: List<Provider>,
        query: String
    ): List<Provider> = withContext(Dispatchers.Default) {
        providers.map { provider ->
            val score = calculateProviderScore(provider, query)
            provider to score
        }.sortedByDescending { it.second }
         .map { it.first }
    }
    
    /**
     * Calculate AI score for a provider based on multiple factors
     */
    private fun calculateProviderScore(provider: Provider, query: String): Float {
        val historicalScore = providerScores[provider.id]
        
        // Base score from provider stats
        var score = provider.successRate * 100
        
        // Factor in response time (faster is better)
        val responseTimeScore = when {
            provider.avgResponseTime < 1000 -> 20f
            provider.avgResponseTime < 3000 -> 15f
            provider.avgResponseTime < 5000 -> 10f
            provider.avgResponseTime < 10000 -> 5f
            else -> 0f
        }
        score += responseTimeScore
        
        // Factor in historical AI data
        historicalScore?.let { aiScore ->
            score += aiScore.overallScore * 30
            
            // Bonus for consistent providers
            if (aiScore.consistencyScore > 0.8f) {
                score += 15f
            }
            
            // Penalty for high failure rate
            if (aiScore.failureRate > 0.5f) {
                score -= 20f
            }
        }
        
        // Category relevance (if query hints at category)
        if (matchesCategoryForQuery(provider.category, query)) {
            score += 25f
        }
        
        // Health score factor
        score += provider.healthScore * 10
        
        return score.coerceIn(0f, 200f)
    }
    
    /**
     * Score search results using AI relevance analysis with phrase and synonym awareness
     */
    suspend fun scoreResults(
        results: List<SearchResult>,
        query: String
    ): List<SearchResult> = withContext(Dispatchers.Default) {
        val queryTerms = tokenizeQuerySmart(query)
        
        results.map { result ->
            val aiScore = calculateResultRelevance(result, queryTerms)
            result.copy(relevanceScore = aiScore)
        }.sortedByDescending { it.relevanceScore }
    }
    
    /**
     * Calculate relevance score for a single result.
     * Uses term matching, phrase bonuses, position weighting, and fuzzy prefix matching.
     */
    private fun calculateResultRelevance(result: SearchResult, queryTerms: List<String>): Float {
        var score = 0f
        
        val titleLower = result.title.lowercase()
        val descLower = result.description?.lowercase() ?: ""
        
        // ---- Whole-phrase exact match ----
        // If title contains the full query as a substring, big bonus
        val fullPhrase = queryTerms.filter { !it.contains(" ") }.joinToString(" ")
        if (fullPhrase.isNotEmpty() && titleLower.contains(fullPhrase)) {
            score += 50f
        }
        
        // ---- Term-level scoring ----
        val singleTerms = queryTerms.filter { !it.contains(" ") }
        val bigramTerms = queryTerms.filter { it.contains(" ") }
        
        singleTerms.forEachIndexed { index, term ->
            // Position weight: first terms are more important
            val positionWeight = 1f - (index * 0.08f).coerceAtMost(0.5f)
            
            // --- Title match ---
            if (titleLower.contains(term)) {
                score += 30f * positionWeight
                
                // Exact word boundary match bonus
                val titleWords = titleLower.split(Regex("\\W+"))
                if (titleWords.contains(term)) {
                    score += 15f * positionWeight
                }
                
                // Title starts with term — very strong signal
                if (titleLower.startsWith(term)) {
                    score += 20f
                }
            } else {
                // Fuzzy: check if any title word starts with this term (prefix match)
                val titleWords = titleLower.split(Regex("\\W+"))
                if (titleWords.any { it.startsWith(term) && it.length <= term.length + 3 }) {
                    score += 8f * positionWeight
                }
            }
            
            // --- Description match (lower weight) ---
            if (descLower.contains(term)) {
                score += 8f * positionWeight
            }
        }
        
        // ---- Bigram (phrase) scoring ----
        bigramTerms.forEach { bigram ->
            if (titleLower.contains(bigram)) {
                score += 25f // Multi-word phrase found in title = strong match
            } else if (descLower.contains(bigram)) {
                score += 8f
            }
        }
        
        // ---- Synonym expansion bonus ----
        // If title matches a synonym of any query term, give partial credit
        singleTerms.forEach { term ->
            val synonyms = QUERY_SYNONYMS[term] ?: emptyList()
            synonyms.forEach { syn ->
                if (titleLower.contains(syn) && !titleLower.contains(term)) {
                    score += 10f // Synonym match in title
                }
            }
        }
        
        // ---- Quality indicators ----
        val qualityScore = calculateQualityScore(result)
        score += qualityScore * 20
        
        // Has thumbnail (indicates real content)
        if (!result.thumbnailUrl.isNullOrEmpty()) {
            score += 5f
        }
        
        // Seeders for torrent results (popular = likely relevant)
        result.seeders?.let { seeders ->
            score += when {
                seeders > 1000 -> 15f
                seeders > 100 -> 10f
                seeders > 10 -> 5f
                seeders > 0 -> 2f
                else -> -5f // No seeders is a bad sign
            }
        }
        
        // Rating factor
        result.rating?.let { rating ->
            score += (rating / 10f) * 10
        }
        
        // Penalize risk keywords
        if (RISK_KEYWORDS.any { titleLower.contains(it) }) {
            score -= 15f
        }
        
        return score.coerceIn(0f, 100f)
    }
    
    /**
     * Calculate quality score from result metadata
     */
    fun calculateQualityScore(result: SearchResult): Float {
        val titleLower = result.title.lowercase()
        val qualityStr = result.quality?.lowercase() ?: ""
        
        var maxScore = 0f
        
        // Check explicit quality field
        qualityWeights.forEach { (quality, weight) ->
            if (qualityStr.contains(quality) || titleLower.contains(quality)) {
                maxScore = maxOf(maxScore, weight)
            }
        }
        
        // Check quality keywords
        if (QUALITY_KEYWORDS.any { titleLower.contains(it) }) {
            maxScore += 0.1f
        }
        
        return maxScore.coerceAtMost(1f)
    }
    
    /**
     * Determine best scraping strategy for a site
     */
    fun recommendScrapingStrategy(
        analysis: SiteAnalysis?,
        previousFailures: List<String>
    ): ScrapingStrategy {
        if (analysis == null) {
            return ScrapingStrategy.HTML_PARSING
        }
        
        // Check if JavaScript is required
        if (analysis.requiresJavaScript) {
            return if (previousFailures.contains("DYNAMIC_CONTENT")) {
                ScrapingStrategy.HEADLESS_BROWSER
            } else {
                ScrapingStrategy.DYNAMIC_CONTENT
            }
        }
        
        // Check if site has API
        if (analysis.hasAPI && previousFailures.isEmpty()) {
            return ScrapingStrategy.API_BASED
        }
        
        // Fallback logic based on failures
        return when {
            previousFailures.contains("HTML_PARSING") && 
            previousFailures.contains("DYNAMIC_CONTENT") -> ScrapingStrategy.HEADLESS_BROWSER
            
            previousFailures.contains("HTML_PARSING") -> ScrapingStrategy.DYNAMIC_CONTENT
            
            else -> analysis.scrapingStrategy
        }
    }
    
    /**
     * Predict if a request will fail based on patterns
     */
    fun predictFailure(provider: Provider): FailurePrediction {
        val aiScore = providerScores[provider.id]
        
        if (aiScore == null) {
            return FailurePrediction(
                likelihood = 0.2f, // Default low risk for unknown
                reason = null,
                recommendation = "First time scraping - proceed with caution"
            )
        }
        
        val failureLikelihood = aiScore.failureRate * 
            (1 - aiScore.consistencyScore) * 
            (if (aiScore.lastFailureRecent) 1.5f else 1f)
        
        return when {
            failureLikelihood > 0.7f -> FailurePrediction(
                likelihood = failureLikelihood,
                reason = "High historical failure rate",
                recommendation = "Use headless browser with extended timeout"
            )
            failureLikelihood > 0.4f -> FailurePrediction(
                likelihood = failureLikelihood,
                reason = "Moderate failure risk",
                recommendation = "Try standard scraping with retry"
            )
            else -> FailurePrediction(
                likelihood = failureLikelihood,
                reason = null,
                recommendation = "Proceed normally"
            )
        }
    }
    
    /**
     * Learn from scraping result (success or failure)
     */
    fun recordResult(
        providerId: String,
        success: Boolean,
        responseTime: Long,
        resultCount: Int
    ) {
        val existing = providerScores[providerId] ?: ProviderAIScore()
        
        val newSuccessRate = existing.successRate * DECAY_FACTOR + 
            (if (success) 1f else 0f) * (1 - DECAY_FACTOR)
        
        val newAvgResults = existing.avgResultCount * DECAY_FACTOR + 
            resultCount * (1 - DECAY_FACTOR)
        
        val newAvgTime = existing.avgResponseTime * DECAY_FACTOR + 
            responseTime * (1 - DECAY_FACTOR)
        
        val newConsistency = calculateConsistency(existing, success, resultCount)
        
        providerScores[providerId] = existing.copy(
            successRate = newSuccessRate,
            failureRate = 1 - newSuccessRate,
            avgResultCount = newAvgResults,
            avgResponseTime = newAvgTime,
            consistencyScore = newConsistency,
            totalAttempts = existing.totalAttempts + 1,
            lastAttemptTime = System.currentTimeMillis(),
            lastFailureRecent = !success,
            overallScore = calculateOverallScore(newSuccessRate, newConsistency, newAvgTime)
        )
    }
    
    /**
     * Learn content patterns from successful extractions
     */
    fun learnPattern(
        domain: String,
        patternType: PatternType,
        selector: String,
        confidence: Float
    ) {
        val existing = learnedPatterns[domain]?.toMutableList() ?: mutableListOf()
        
        val existingPattern = existing.find { it.type == patternType && it.selector == selector }
        
        if (existingPattern != null) {
            // Update confidence with weighted average
            val newConfidence = existingPattern.confidence * 0.7f + confidence * 0.3f
            existing.remove(existingPattern)
            existing.add(existingPattern.copy(
                confidence = newConfidence,
                usageCount = existingPattern.usageCount + 1,
                lastUsed = System.currentTimeMillis()
            ))
        } else {
            existing.add(LearnedPattern(
                type = patternType,
                selector = selector,
                confidence = confidence,
                usageCount = 1,
                lastUsed = System.currentTimeMillis()
            ))
        }
        
        // Keep only top patterns
        learnedPatterns[domain] = existing
            .sortedByDescending { it.confidence * ln(it.usageCount.toFloat() + 1) }
            .take(20)
    }
    
    /**
     * Get learned patterns for a domain
     */
    fun getLearnedPatterns(domain: String, type: PatternType): List<LearnedPattern> {
        return learnedPatterns[domain]?.filter { it.type == type } ?: emptyList()
    }
    
    /**
     * Smart retry decision
     */
    fun shouldRetry(
        provider: Provider,
        attemptNumber: Int,
        lastError: String?
    ): RetryDecision {
        val maxRetries = when {
            provider.successRate > 0.8f -> 3
            provider.successRate > 0.5f -> 2
            else -> 1
        }
        
        if (attemptNumber >= maxRetries) {
            return RetryDecision(
                shouldRetry = false,
                reason = "Max retries reached"
            )
        }
        
        // Don't retry certain errors
        val nonRetryableErrors = listOf(
            "403", "404", "blocked", "cloudflare", "captcha"
        )
        
        if (lastError != null && nonRetryableErrors.any { lastError.lowercase().contains(it) }) {
            return RetryDecision(
                shouldRetry = false,
                reason = "Non-retryable error: $lastError",
                alternativeStrategy = ScrapingStrategy.HEADLESS_BROWSER
            )
        }
        
        // Calculate delay based on attempt number
        val delay = (1000L * (1 shl attemptNumber)).coerceAtMost(10000L)
        
        return RetryDecision(
            shouldRetry = true,
            delay = delay,
            reason = "Retry attempt ${attemptNumber + 1}"
        )
    }
    
    /**
     * Select best download quality from available options
     */
    fun selectBestQuality(qualities: List<QualityOption>): QualityOption? {
        return qualities
            .filter { it.isAvailable }
            .maxByOrNull { option ->
                val baseScore = qualityWeights[option.quality.lowercase()] ?: 0.3f
                val sizeScore = when {
                    option.fileSize == null -> 0f
                    option.fileSize > 10_000_000_000 -> 0.9f // >10GB
                    option.fileSize > 5_000_000_000 -> 0.8f  // >5GB
                    option.fileSize > 2_000_000_000 -> 0.7f  // >2GB
                    option.fileSize > 1_000_000_000 -> 0.6f  // >1GB
                    else -> 0.5f
                }
                baseScore * 0.7f + sizeScore * 0.3f
            }
    }
    
    // Helper functions
    
    private fun tokenizeQuery(query: String): List<String> {
        return query.lowercase()
            .split(WHITESPACE_SPLIT_REGEX)
            .filter { it.length > 1 }
            .map { it.trim(',', '.', '!', '?', '"', '\'', '(', ')') }
            .filter { it.isNotEmpty() && it !in STOP_WORDS }
    }
    
    /**
     * Tokenize query into MEANINGFUL terms (no stop words) + significant bigrams
     */
    private fun tokenizeQuerySmart(query: String): List<String> {
        val words = query.lowercase()
            .split(WHITESPACE_SPLIT_REGEX)
            .map { it.trim(',', '.', '!', '?', '"', '\'', '(', ')') }
            .filter { it.length > 1 && it.isNotEmpty() }
        
        val meaningful = words.filter { it !in STOP_WORDS }
        
        // Add bigrams (two-word phrases) for better phrase matching
        val bigrams = words.zipWithNext { a, b -> "$a $b" }
            .filter { bigramWords -> bigramWords.split(" ").none { it in STOP_WORDS } }
        
        return meaningful + bigrams
    }
    
    /**
     * Expand a query with synonyms and related terms for broader matching.
     * Returns the original query plus variant queries to try.
     */
    fun expandQuery(query: String): List<String> {
        val queryLower = query.lowercase().trim()
        val words = queryLower.split(WHITESPACE_SPLIT_REGEX).filter { it.isNotEmpty() }
        val variants = mutableListOf(queryLower)
        
        // Add synonym expansions for each significant word
        words.filter { it !in STOP_WORDS }.forEach { word ->
            QUERY_SYNONYMS[word]?.forEach { synonym ->
                val expanded = queryLower.replace(word, synonym)
                if (expanded != queryLower && !variants.contains(expanded)) {
                    variants.add(expanded)
                }
            }
        }
        
        return variants.take(5) // Cap at 5 to avoid over-fetching
    }
    
    /**
     * Decompose a multi-word query into fallback single-term or reduced-term variants.
     * Used when full-query searches return no results.
     * Terms are sorted by rarity/importance (longer words first, stop words excluded).
     */
    fun decomposeQueryFallback(query: String): List<String> {
        val words = query.lowercase()
            .split(WHITESPACE_SPLIT_REGEX)
            .map { it.trim(',', '.', '!', '?', '"', '\'') }
            .filter { it.length > 2 && it !in STOP_WORDS }
            .distinctBy { it }
        
        if (words.isEmpty()) return listOf(query)
        
        val result = mutableListOf<String>()
        
        // 1. Progressively shorter queries (drop last word each time)
        for (i in words.indices.reversed()) {
            val sub = words.subList(0, i + 1).joinToString(" ")
            if (sub.isNotBlank() && sub != query.lowercase()) {
                result.add(sub)
            }
        }
        
        // 2. Add individual key words sorted by length desc (rarer = longer)
        words.sortedByDescending { it.length }
             .forEach { if (!result.contains(it)) result.add(it) }
        
        return result.take(8)
    }
    
    /**
     * Generate alternative query variants for sites that may use
     * different naming conventions or abbreviations.
     */
    fun generateQueryVariants(query: String): List<String> {
        val base = query.lowercase().trim()
        val variants = mutableSetOf(base)
        
        // Replace spaces with different separators (common in torrent names)
        variants.add(base.replace(" ", "."))
        variants.add(base.replace(" ", "-"))
        variants.add(base.replace(" ", "+"))
        
        // Add year range variants if query looks like it could include a year
        val yearPattern = Regex("\\b(19|20)\\d{2}\\b")
        if (!yearPattern.containsMatchIn(base)) {
            // No year - add current/recent year hint variants
            val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
            variants.add("$base $currentYear")
            variants.add("$base ${currentYear - 1}")
        }
        
        // Expand single-letter season/episode shorthand: s01e01 -> season 1 episode 1
        val sePattern = Regex("s(\\d{1,2})e(\\d{1,2})", RegexOption.IGNORE_CASE)
        val seMatch = sePattern.find(base)
        if (seMatch != null) {
            val season = seMatch.groupValues[1].toIntOrNull() ?: 1
            val episode = seMatch.groupValues[2].toIntOrNull() ?: 1
            val longForm = base.replace(seMatch.value, "season $season episode $episode")
            variants.add(longForm)
        }
        
        return variants.toList().take(6)
    }
    
    private fun matchesCategoryForQuery(category: ProviderCategory, query: String): Boolean {
        val queryLower = query.lowercase()
        val queryWords = queryLower.split(WHITESPACE_SPLIT_REGEX).toSet()
        return when (category) {
            ProviderCategory.STREAMING -> 
                (CATEGORY_KEYWORDS["streaming"] ?: emptySet()).any { kw ->
                    queryLower.contains(kw) || queryWords.contains(kw)
                }
            ProviderCategory.TORRENT -> 
                (CATEGORY_KEYWORDS["torrent"] ?: emptySet()).any { kw ->
                    queryLower.contains(kw) || queryWords.contains(kw)
                }
            ProviderCategory.NEWS -> 
                (CATEGORY_KEYWORDS["news"] ?: emptySet()).any { kw ->
                    queryLower.contains(kw) || queryWords.contains(kw)
                }
            ProviderCategory.MEDIA -> 
                (CATEGORY_KEYWORDS["media"] ?: emptySet()).any { kw ->
                    queryLower.contains(kw) || queryWords.contains(kw)
                }
            else -> false
        }
    }
    
    private fun calculateConsistency(
        existing: ProviderAIScore,
        success: Boolean,
        resultCount: Int
    ): Float {
        if (existing.totalAttempts < 3) return 0.5f
        
        val successConsistency = if ((existing.successRate > 0.5f) == success) 0.1f else -0.1f
        val resultConsistency = if (kotlin.math.abs(resultCount - existing.avgResultCount) < 10) 0.1f else -0.05f
        
        return (existing.consistencyScore + successConsistency + resultConsistency).coerceIn(0f, 1f)
    }
    
    private fun calculateOverallScore(
        successRate: Float,
        consistency: Float,
        avgTime: Float
    ): Float {
        val timeScore = when {
            avgTime < 1000 -> 1f
            avgTime < 3000 -> 0.8f
            avgTime < 5000 -> 0.6f
            avgTime < 10000 -> 0.4f
            else -> 0.2f
        }
        
        return (successRate * 0.5f + consistency * 0.3f + timeScore * 0.2f)
    }
    
    // ==========================================
    // ADVANCED LEARNING METHODS
    // ==========================================
    
    /**
     * Learn from a scraping failure - records the failure pattern to avoid repeating
     * This is the core of the self-improvement capability
     */
    fun learnFromFailure(
        domain: String,
        errorType: String,
        errorMessage: String?,
        strategy: ScrapingStrategy,
        selector: String?,
        url: String
    ) {
        val existing = failureKnowledge[domain]?.toMutableList() ?: mutableListOf()
        
        // Add new failure record
        existing.add(FailureRecord(
            errorType = errorType,
            errorMessage = errorMessage,
            strategy = strategy,
            selector = selector,
            url = url
        ))
        
        // Keep only recent failures
        failureKnowledge[domain] = existing.takeLast(MAX_FAILURE_RECORDS)
        
        // Update strategy effectiveness
        val stats = strategyEffectiveness[strategy.name] ?: StrategyStats(strategy)
        strategyEffectiveness[strategy.name] = stats.copy(
            failureCount = stats.failureCount + 1,
            domains = stats.domains.apply { add(domain) }
        )
        
        // Update site structure knowledge
        val siteKnowledge = siteStructureCache[domain] ?: SiteStructureKnowledge(domain)
        siteStructureCache[domain] = siteKnowledge.copy(
            failureCount = siteKnowledge.failureCount + 1,
            lastUpdated = System.currentTimeMillis()
        )
        
        // Update global learning state
        globalLearningState = globalLearningState.copy(
            totalInteractions = globalLearningState.totalInteractions + 1,
            lastLearningTime = System.currentTimeMillis()
        )
        
        updateAIState()
    }
    
    /**
     * Learn from a scraping success - reinforces working patterns
     */
    fun learnFromSuccess(
        domain: String,
        strategy: ScrapingStrategy,
        resultSelector: String?,
        titleSelector: String?,
        thumbnailSelector: String?,
        resultCount: Int,
        responseTime: Long
    ) {
        // Update site structure knowledge
        val existing = siteStructureCache[domain] ?: SiteStructureKnowledge(domain)
        val newConfidence = ((existing.confidence * existing.successCount) + 1f) / (existing.successCount + 1)
        
        siteStructureCache[domain] = existing.copy(
            resultSelector = resultSelector ?: existing.resultSelector,
            titleSelector = titleSelector ?: existing.titleSelector,
            thumbnailSelector = thumbnailSelector ?: existing.thumbnailSelector,
            successCount = existing.successCount + 1,
            confidence = newConfidence.coerceAtMost(1f),
            lastUpdated = System.currentTimeMillis()
        )
        
        // Update selector knowledge
        if (resultSelector != null) {
            updateSelectorKnowledge(domain, "result", resultSelector, true)
        }
        if (titleSelector != null) {
            updateSelectorKnowledge(domain, "title", titleSelector, true)
        }
        if (thumbnailSelector != null) {
            updateSelectorKnowledge(domain, "thumbnail", thumbnailSelector, true)
        }
        
        // Update strategy effectiveness
        val stats = strategyEffectiveness[strategy.name] ?: StrategyStats(strategy)
        val newAvgTime = if (stats.successCount == 0) responseTime 
            else (stats.avgResponseTime + responseTime) / 2
        
        strategyEffectiveness[strategy.name] = stats.copy(
            successCount = stats.successCount + 1,
            avgResponseTime = newAvgTime,
            domains = stats.domains.apply { add(domain) }
        )
        
        // Update global learning state - GROW INTELLIGENCE
        val newIntelligence = (globalLearningState.intelligenceScore + INTELLIGENCE_GROWTH_RATE)
            .coerceAtMost(1f)
        
        globalLearningState = globalLearningState.copy(
            totalInteractions = globalLearningState.totalInteractions + 1,
            successfulLearnings = globalLearningState.successfulLearnings + 1,
            intelligenceScore = newIntelligence,
            knownDomains = siteStructureCache.size,
            learnedPatterns = learnedPatterns.values.sumOf { it.size },
            lastLearningTime = System.currentTimeMillis()
        )
        
        updateAIState()
    }
    
    /**
     * Learn a successful failure recovery - remembers how to fix specific failures
     */
    fun learnRecovery(
        domain: String,
        originalError: String,
        recoveryMethod: String
    ) {
        val failures = failureKnowledge[domain] ?: return
        
        // Find matching failure and mark as recovered
        val updatedFailures = failures.map { failure ->
            if (failure.errorType == originalError && !failure.wasRecovered) {
                failure.copy(recovery = recoveryMethod, wasRecovered = true)
            } else {
                failure
            }
        }
        
        failureKnowledge[domain] = updatedFailures
        
        // Update global learning state
        globalLearningState = globalLearningState.copy(
            recoveredFailures = globalLearningState.recoveredFailures + 1,
            intelligenceScore = (globalLearningState.intelligenceScore + INTELLIGENCE_GROWTH_RATE * 2)
                .coerceAtMost(1f)
        )
        
        updateAIState()
    }

    // ─── Endpoint Learning ──────────────────────────────────────────
    
    // Remembers which API endpoints worked for each domain
    private val endpointKnowledge = ConcurrentHashMap<String, EndpointKnowledge>()

    /**
     * Learn a working endpoint for a domain so future searches skip discovery.
     */
    fun learnEndpoint(
        domain: String,
        endpoint: String,
        strategy: ScrapingStrategy,
        resultCount: Int
    ) {
        val existing = endpointKnowledge[domain] ?: EndpointKnowledge(domain)
        val updated = existing.copy(
            workingEndpoints = (existing.workingEndpoints + EndpointRecord(
                endpoint = endpoint,
                strategy = strategy,
                successCount = 1,
                lastResultCount = resultCount,
                lastUsed = System.currentTimeMillis()
            )).distinctBy { it.endpoint }.takeLast(10),
            lastUpdated = System.currentTimeMillis()
        )
        endpointKnowledge[domain] = updated
    }

    /**
     * Get the best-known working endpoint for a domain (null if none learned).
     */
    fun getBestKnownEndpoint(domain: String): String? {
        val knowledge = endpointKnowledge[domain] ?: return null
        return knowledge.workingEndpoints
            .filter { System.currentTimeMillis() - it.lastUsed < 6 * 60 * 60 * 1000L } // 6 hours
            .maxByOrNull { it.successCount * it.lastResultCount }
            ?.endpoint
    }

    /**
     * Get a recommended strategy based on learned knowledge
     */
    fun getAdaptiveStrategy(domain: String): ScrapingStrategy {
        // Check site-specific knowledge first
        val siteKnowledge = siteStructureCache[domain]
        
        if (siteKnowledge != null) {
            // Use learned knowledge about this site
            if (siteKnowledge.requiresJavaScript) {
                return ScrapingStrategy.DYNAMIC_CONTENT
            }
            if (siteKnowledge.antiScrapingMeasures.isNotEmpty()) {
                return ScrapingStrategy.HEADLESS_BROWSER
            }
        }
        
        // Check failure history
        val failures = failureKnowledge[domain] ?: emptyList()
        val recentFailures = failures.filter { 
            System.currentTimeMillis() - it.timestamp < 24 * 60 * 60 * 1000 // Last 24 hours
        }
        
        // Avoid strategies that recently failed
        val failedStrategies = recentFailures.map { it.strategy }.toSet()
        
        // Check for recovered failures - use that strategy
        val recoveredFailure = failures.find { it.wasRecovered }
        if (recoveredFailure != null && recoveredFailure.recovery != null) {
            return when (recoveredFailure.recovery) {
                "HEADLESS_BROWSER" -> ScrapingStrategy.HEADLESS_BROWSER
                "DYNAMIC_CONTENT" -> ScrapingStrategy.DYNAMIC_CONTENT
                "API_BASED" -> ScrapingStrategy.API_BASED
                else -> ScrapingStrategy.HTML_PARSING
            }
        }
        
        // Use strategy effectiveness to choose
        val bestStrategy = strategyEffectiveness.entries
            .filter { it.key !in failedStrategies.map { s -> s.name } }
            .maxByOrNull { entry ->
                val stats = entry.value
                if (stats.successCount + stats.failureCount == 0) 0f
                else stats.successCount.toFloat() / (stats.successCount + stats.failureCount)
            }
        
        return bestStrategy?.value?.strategy ?: ScrapingStrategy.HTML_PARSING
    }
    
    /**
     * Get learned selectors for a domain
     */
    fun getLearnedSelectors(domain: String): SelectorKnowledge? {
        return selectorKnowledge[domain]
    }
    
    /**
     * Get the best result selector for a domain based on learning
     */
    fun getBestResultSelector(domain: String): String? {
        val knowledge = selectorKnowledge[domain] ?: return null
        return knowledge.resultSelectors
            .filter { it.score > 0.5f }
            .maxByOrNull { it.score }
            ?.selector
    }
    
    /**
     * Check if we should try a different approach based on learning
     */
    fun shouldTryAlternativeApproach(domain: String, currentAttempt: Int): AlternativeApproach? {
        val siteKnowledge = siteStructureCache[domain]
        val failures = failureKnowledge[domain] ?: emptyList()
        
        // Too many recent failures - suggest headless browser
        if (failures.count { System.currentTimeMillis() - it.timestamp < 3600000 } >= 3) {
            return AlternativeApproach(
                strategy = ScrapingStrategy.HEADLESS_BROWSER,
                reason = "Multiple recent failures detected",
                confidence = 0.8f
            )
        }
        
        // Check if site requires JavaScript based on learning
        if (siteKnowledge?.requiresJavaScript == true) {
            return AlternativeApproach(
                strategy = ScrapingStrategy.DYNAMIC_CONTENT,
                reason = "Site requires JavaScript based on learned knowledge",
                confidence = siteKnowledge.confidence
            )
        }
        
        return null
    }
    
    /**
     * Get current intelligence level
     */
    fun getIntelligenceLevel(): Float {
        return globalLearningState.intelligenceScore
    }
    
    /**
     * Get statistics about learning
     */
    fun getLearningStats(): LearningStats {
        return LearningStats(
            totalInteractions = globalLearningState.totalInteractions,
            successfulLearnings = globalLearningState.successfulLearnings,
            intelligenceScore = globalLearningState.intelligenceScore,
            knownDomains = siteStructureCache.size,
            learnedPatterns = learnedPatterns.values.sumOf { it.size },
            recoveredFailures = globalLearningState.recoveredFailures,
            totalFailureRecords = failureKnowledge.values.sumOf { it.size }
        )
    }
    
    /**
     * Update selector knowledge based on success/failure
     */
    private fun updateSelectorKnowledge(
        domain: String,
        type: String,
        selector: String,
        success: Boolean
    ) {
        val existing = selectorKnowledge[domain] ?: SelectorKnowledge(domain)
        
        val selectors = when (type) {
            "result" -> existing.resultSelectors
            "title" -> existing.titleSelectors
            "thumbnail" -> existing.thumbnailSelectors
            else -> existing.linkSelectors
        }.toMutableList()
        
        val existingSelector = selectors.find { it.selector == selector }
        if (existingSelector != null) {
            selectors.remove(existingSelector)
            selectors.add(existingSelector.copy(
                successCount = if (success) existingSelector.successCount + 1 else existingSelector.successCount,
                failureCount = if (!success) existingSelector.failureCount + 1 else existingSelector.failureCount,
                lastUsed = System.currentTimeMillis()
            ))
        } else {
            selectors.add(SelectorScore(
                selector = selector,
                successCount = if (success) 1 else 0,
                failureCount = if (!success) 1 else 0
            ))
        }
        
        // Keep top performers
        val sortedSelectors = selectors.sortedByDescending { it.score }.take(10)
        
        selectorKnowledge[domain] = when (type) {
            "result" -> existing.copy(resultSelectors = sortedSelectors)
            "title" -> existing.copy(titleSelectors = sortedSelectors)
            "thumbnail" -> existing.copy(thumbnailSelectors = sortedSelectors)
            else -> existing.copy(linkSelectors = sortedSelectors)
        }
    }
    
    /**
     * Update AI state for UI display
     */
    private fun updateAIState() {
        _aiState.value = AIState(
            isProcessing = false,
            lastDecision = "Learning from interaction",
            confidence = globalLearningState.intelligenceScore,
            intelligenceLevel = globalLearningState.intelligenceScore,
            totalLearned = globalLearningState.totalInteractions
        )
    }
}

/**
 * Alternative approach suggestion from AI
 */
data class AlternativeApproach(
    val strategy: ScrapingStrategy,
    val reason: String,
    val confidence: Float
)

/**
 * Learning statistics for display
 */
data class LearningStats(
    val totalInteractions: Int,
    val successfulLearnings: Int,
    val intelligenceScore: Float,
    val knownDomains: Int,
    val learnedPatterns: Int,
    val recoveredFailures: Int,
    val totalFailureRecords: Int
)

// Data classes

data class ProviderAIScore(
    val successRate: Float = 0.5f,
    val failureRate: Float = 0.5f,
    val avgResultCount: Float = 0f,
    val avgResponseTime: Float = 3000f,
    val consistencyScore: Float = 0.5f,
    val totalAttempts: Int = 0,
    val lastAttemptTime: Long = 0,
    val lastFailureRecent: Boolean = false,
    val overallScore: Float = 0.5f
)

data class LearnedPattern(
    val type: PatternType,
    val selector: String,
    val confidence: Float,
    val usageCount: Int = 1,
    val lastUsed: Long = System.currentTimeMillis()
)

data class FailurePrediction(
    val likelihood: Float,
    val reason: String?,
    val recommendation: String
)

data class RetryDecision(
    val shouldRetry: Boolean,
    val delay: Long = 0,
    val reason: String,
    val alternativeStrategy: ScrapingStrategy? = null
)

data class QualityOption(
    val quality: String,
    val url: String,
    val fileSize: Long? = null,
    val isAvailable: Boolean = true
)

data class AIState(
    val isProcessing: Boolean = false,
    val lastDecision: String = "",
    val confidence: Float = 0f,
    val intelligenceLevel: Float = 0f,
    val totalLearned: Int = 0
)

/**
 * Knowledge about a site's structure learned over time
 */
data class SiteStructureKnowledge(
    val domain: String,
    val searchUrlPattern: String? = null,
    val resultSelector: String? = null,
    val titleSelector: String? = null,
    val thumbnailSelector: String? = null,
    val paginationSelector: String? = null,
    val requiresJavaScript: Boolean = false,
    val requiresProxy: Boolean = false,
    val bestUserAgent: String? = null,
    val antiScrapingMeasures: List<String> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis(),
    val confidence: Float = 0.5f,
    val successCount: Int = 0,
    val failureCount: Int = 0
)

/**
 * Record of a failure for learning purposes
 */
data class FailureRecord(
    val timestamp: Long = System.currentTimeMillis(),
    val errorType: String,
    val errorMessage: String?,
    val strategy: ScrapingStrategy,
    val selector: String?,
    val url: String,
    val recovery: String? = null,
    val wasRecovered: Boolean = false
)

/**
 * Knowledge about what selectors work for a site
 */
data class SelectorKnowledge(
    val domain: String,
    val resultSelectors: List<SelectorScore> = emptyList(),
    val titleSelectors: List<SelectorScore> = emptyList(),
    val thumbnailSelectors: List<SelectorScore> = emptyList(),
    val linkSelectors: List<SelectorScore> = emptyList()
)

data class SelectorScore(
    val selector: String,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastUsed: Long = System.currentTimeMillis()
) {
    val score: Float get() = if (successCount + failureCount == 0) 0.5f 
        else successCount.toFloat() / (successCount + failureCount)
}

/**
 * Statistics about strategy effectiveness
 */
data class StrategyStats(
    val strategy: ScrapingStrategy,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val avgResponseTime: Long = 0,
    val domains: MutableSet<String> = mutableSetOf()
)

/**
 * Global learning state tracking overall AI growth
 */
data class GlobalLearningState(
    val totalInteractions: Int = 0,
    val successfulLearnings: Int = 0,
    val intelligenceScore: Float = 0.1f, // Grows with experience
    val knownDomains: Int = 0,
    val learnedPatterns: Int = 0,
    val adaptedStrategies: Int = 0,
    val recoveredFailures: Int = 0,
    val lastLearningTime: Long = System.currentTimeMillis()
)

/**
 * Knowledge about working API endpoints per domain
 */
data class EndpointKnowledge(
    val domain: String,
    val workingEndpoints: List<EndpointRecord> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
)

data class EndpointRecord(
    val endpoint: String,
    val strategy: ScrapingStrategy,
    val successCount: Int = 0,
    val lastResultCount: Int = 0,
    val lastUsed: Long = System.currentTimeMillis()
)
