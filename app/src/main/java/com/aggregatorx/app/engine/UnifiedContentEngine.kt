package com.aggregatorx.app.engine

import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.engine.analyzer.*
import com.aggregatorx.app.engine.util.EngineUtils
import com.aggregatorx.app.engine.media.*
import com.aggregatorx.app.engine.network.*
import com.aggregatorx.app.engine.scraper.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AggregatorX Unified Engine - Intelligent Content Aggregation System
 * 
 * Seamlessly integrates all subsystems:
 * - ProxyVPNEngine: Netherlands proxy for geo-restricted content
 * - VideoStreamResolver: Multi-source video playback with fallback chain
 * - SmartContentClassifier: AI-like category vs content detection
 * - UniversalFormatParser: Multi-format website data extraction
 * - SiteAnalyzerEngine: Deep site analysis and pattern recognition
 * 
 * Provides a unified API for discovering, analyzing, and playing content
 * from any supported website with intelligent error handling and recovery.
 */
@Singleton
class UnifiedContentEngine @Inject constructor(
    private val proxyVPNEngine: ProxyVPNEngine,
    private val videoStreamResolver: VideoStreamResolver,
    private val smartContentClassifier: SmartContentClassifier,
    private val universalFormatParser: UniversalFormatParser,
    private val siteAnalyzerEngine: SiteAnalyzerEngine
) {
    
    companion object {
        private val USER_AGENT = EngineUtils.DEFAULT_USER_AGENT
        private const val TIMEOUT = 30000
    }
    
    // State flows for UI updates
    private val _engineState = MutableStateFlow(EngineState.IDLE)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()
    
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    
    /**
     * Initialize the engine and all subsystems
     */
    suspend fun initialize(): InitializationResult = withContext(Dispatchers.IO) {
        _engineState.value = EngineState.INITIALIZING
        
        try {
            // Initialize proxy system
            val proxyResult = try {
                proxyVPNEngine.initialize()
                ProxyInitResult(true, proxyVPNEngine.getCurrentProxy()?.toString())
            } catch (e: Exception) {
                ProxyInitResult(false, null, e.message)
            }
            
            _engineState.value = EngineState.READY
            
            InitializationResult(
                success = true,
                proxyReady = proxyResult.success,
                proxyInfo = proxyResult.proxyAddress,
                message = buildInitMessage(proxyResult)
            )
        } catch (e: Exception) {
            _engineState.value = EngineState.ERROR
            _lastError.value = e.message
            
            InitializationResult(
                success = false,
                message = "Engine initialization failed: ${e.message}"
            )
        }
    }
    
    /**
     * Analyze a website and extract all available content
     * This is the main entry point for content discovery
     */
    suspend fun analyzeAndExtractContent(
        url: String,
        providerId: String = "custom",
        useProxy: Boolean = true
    ): ContentExtractionResult = withContext(Dispatchers.IO) {
        _engineState.value = EngineState.ANALYZING
        
        try {
            // Step 1: Fetch the page (with proxy if needed)
            val document = fetchDocument(url, useProxy)
                ?: return@withContext ContentExtractionResult(
                    success = false,
                    error = "Failed to fetch page: $url"
                )
            
            // Step 2: Perform parallel analysis
            val (siteAnalysis, pageClassification, parseResult) = coroutineScope {
                val analysisDeferred = async { siteAnalyzerEngine.analyzeSite(url, providerId) }
                val classificationDeferred = async { smartContentClassifier.classifyPageContent(document) }
                val parseDeferred = async { universalFormatParser.parseContent(document, url) }
                
                Triple(
                    analysisDeferred.await(),
                    classificationDeferred.await(),
                    parseDeferred.await()
                )
            }
            
            // Step 3: Merge and deduplicate results
            val mergedContent = mergeExtractionResults(
                pageClassification,
                parseResult,
                siteAnalysis
            )
            
            // Step 4: Determine best extraction strategy for future requests
            val extractionStrategy = determineExtractionStrategy(
                parseResult.format,
                pageClassification.pageType,
                parseResult.extractionMethods
            )
            
            _engineState.value = EngineState.READY
            
            ContentExtractionResult(
                success = true,
                items = mergedContent,
                categories = pageClassification.categoryItems.map { 
                    CategoryInfo(it.text, it.url, it.count)
                },
                pageType = pageClassification.pageType.name,
                dataFormat = parseResult.format.name,
                confidence = calculateOverallConfidence(
                    parseResult.confidence,
                    pageClassification.mainResultContainer?.confidence ?: 0f
                ),
                extractionStrategy = extractionStrategy,
                siteAnalysis = siteAnalysis,
                metadata = parseResult.metadata,
                usedProxy = useProxy && proxyVPNEngine.getCurrentProxy() != null
            )
            
        } catch (e: Exception) {
            _engineState.value = EngineState.ERROR
            _lastError.value = e.message
            
            ContentExtractionResult(
                success = false,
                error = "Content extraction failed: ${e.message}"
            )
        }
    }
    
    /**
     * Search for content on a website
     */
    suspend fun searchContent(
        baseUrl: String,
        query: String,
        providerId: String = "custom",
        useProxy: Boolean = true
    ): ContentExtractionResult = withContext(Dispatchers.IO) {
        _engineState.value = EngineState.SEARCHING
        
        try {
            // First, analyze the site to find search endpoint
            val siteAnalysis = siteAnalyzerEngine.analyzeSite(baseUrl, providerId)
            
            // Build search URL
            val searchUrl = buildSearchUrl(baseUrl, query, siteAnalysis)
            
            // Extract content from search results
            val result = analyzeAndExtractContent(searchUrl, providerId, useProxy)
            
            _engineState.value = EngineState.READY
            result.copy(searchQuery = query)
            
        } catch (e: Exception) {
            _engineState.value = EngineState.ERROR
            _lastError.value = e.message
            
            ContentExtractionResult(
                success = false,
                error = "Search failed: ${e.message}",
                searchQuery = query
            )
        }
    }
    
    /**
     * Resolve and prepare a video for playback
     */
    suspend fun prepareVideoPlayback(
        pageUrl: String,
        useProxy: Boolean = true
    ): VideoPlaybackResult = withContext(Dispatchers.IO) {
        _engineState.value = EngineState.RESOLVING_VIDEO
        
        try {
            val result = videoStreamResolver.resolveVideoStream(
                pageUrl = pageUrl,
                useProxy = useProxy,
                preferHighQuality = true
            )
            
            _engineState.value = EngineState.READY
            
            if (result.success && result.streamUrl != null) {
                VideoPlaybackResult(
                    success = true,
                    streamUrl = result.streamUrl,
                    streamType = result.streamType?.name ?: "DIRECT",
                    quality = result.quality ?: "Unknown",
                    format = result.format ?: "mp4",
                    headers = result.headers ?: emptyMap(),
                    usedProxy = result.usedProxy != null,
                    proxyInfo = result.usedProxy,
                    isValidated = result.isValidated,
                    estimatedBitrate = result.estimatedBitrate?.toLong()
                )
            } else {
                VideoPlaybackResult(
                    success = false,
                    error = result.error ?: "Video resolution failed",
                    suggestedRecovery = result.suggestedRecovery?.name
                )
            }
            
        } catch (e: Exception) {
            _engineState.value = EngineState.ERROR
            _lastError.value = e.message
            
            VideoPlaybackResult(
                success = false,
                error = "Video playback preparation failed: ${e.message}"
            )
        }
    }
    
    /**
     * Recover from playback error with automatic retry strategies
     */
    suspend fun recoverFromPlaybackError(
        pageUrl: String,
        errorMessage: String
    ): VideoPlaybackResult = withContext(Dispatchers.IO) {
        _engineState.value = EngineState.RECOVERING
        
        // Determine recovery strategy based on error
        val strategy = determineRecoveryStrategy(errorMessage)
        
        val result = when (strategy) {
            RecoveryAction.USE_PROXY -> {
                // Force proxy usage
                prepareVideoPlayback(pageUrl, useProxy = true)
            }
            RecoveryAction.TRY_ALTERNATE_QUALITY -> {
                // Try with different quality preference
                videoStreamResolver.resolveVideoStream(
                    pageUrl = pageUrl,
                    useProxy = true,
                    preferHighQuality = false
                ).let { VideoPlaybackResult.fromStreamResult(it) }
            }
            RecoveryAction.ROTATE_PROXY -> {
                // Get a new proxy and retry
                proxyVPNEngine.rotateProxy()
                prepareVideoPlayback(pageUrl, useProxy = true)
            }
            RecoveryAction.RETRY_WITH_DELAY -> {
                delay(2000)
                prepareVideoPlayback(pageUrl, useProxy = true)
            }
            RecoveryAction.GIVE_UP -> {
                VideoPlaybackResult(
                    success = false,
                    error = "All recovery strategies exhausted",
                    suggestedRecovery = "Try a different source"
                )
            }
        }
        
        _engineState.value = if (result.success) EngineState.READY else EngineState.ERROR
        result
    }
    
    // ==========================================
    // Private Helper Methods
    // ==========================================
    
    /**
     * Fetch document with optional proxy
     */
    private suspend fun fetchDocument(url: String, useProxy: Boolean): Document? {
        return try {
            if (useProxy && proxyVPNEngine.getCurrentProxy() != null) {
                proxyVPNEngine.fetchDocumentWithProxy(url)
            } else {
                Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .get()
            }
        } catch (e: Exception) {
            // Fallback to direct connection
            try {
                Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT)
                    .get()
            } catch (e2: Exception) {
                null
            }
        }
    }
    
    /**
     * Merge results from multiple extraction methods
     */
    private fun mergeExtractionResults(
        classification: PageClassification,
        parseResult: UniversalParseResult,
        siteAnalysis: SiteAnalysis
    ): List<ContentItem> {
        val items = mutableMapOf<String, ContentItem>()
        
        // Add items from classification (HTML parsing)
        classification.resultItems.forEach { item ->
            val key = "${item.url}|${item.title.lowercase().take(30)}"
            items[key] = ContentItem(
                title = item.title,
                url = item.url,
                thumbnail = item.thumbnail,
                duration = item.duration,
                quality = item.quality,
                year = item.year,
                rating = item.rating,
                contentType = item.type.name,
                source = "HTMLClassifier",
                confidence = 0.7f
            )
        }
        
        // Add/update with items from universal parser (usually higher confidence)
        parseResult.items.forEach { item ->
            val key = "${item.url}|${item.title.lowercase().take(30)}"
            val existing = items[key]
            
            if (existing == null || item.confidence > existing.confidence) {
                items[key] = ContentItem(
                    title = item.title,
                    url = item.url,
                    thumbnail = item.thumbnail,
                    description = item.description,
                    duration = item.duration,
                    quality = item.quality,
                    year = item.year,
                    rating = item.rating,
                    contentType = item.contentType.name,
                    source = item.source,
                    confidence = item.confidence
                )
            }
        }
        
        return items.values.sortedByDescending { it.confidence }
    }
    
    /**
     * Determine optimal extraction strategy for this site
     */
    private fun determineExtractionStrategy(
        format: DataFormat,
        pageType: PageType,
        usedMethods: List<String>
    ): ExtractionStrategy {
        return when {
            format == DataFormat.JSON_API || format == DataFormat.JSON_LD -> 
                ExtractionStrategy.API_FIRST
            
            format == DataFormat.NEXTJS || format == DataFormat.NUXTJS ->
                ExtractionStrategy.FRAMEWORK_DATA
            
            usedMethods.any { it.contains("JSON") } && usedMethods.any { it.contains("HTML") } ->
                ExtractionStrategy.HYBRID
            
            else -> ExtractionStrategy.HTML_PARSING
        }
    }
    
    /**
     * Build search URL based on site analysis
     */
    private fun buildSearchUrl(baseUrl: String, query: String, analysis: SiteAnalysis): String {
        // Use detected search form/endpoint if available
        return when {
            analysis.searchFormSelector != null -> {
                // Build from form action
                val searchPath = analysis.searchFormSelector
                    ?.substringAfter("action=")
                    ?.substringBefore("]")
                    ?.trim('\'', '"')
                    ?: "/search"
                
                "$baseUrl$searchPath?q=${query.replace(" ", "+")}"
            }
            analysis.hasAPI && analysis.apiEndpoints.contains("/api/search") -> {
                "$baseUrl/api/search?q=${query.replace(" ", "+")}"
            }
            else -> {
                // Common patterns
                val searchPaths = listOf(
                    "/search?q=", "/search?query=", "/search/", "/?s=", "/find/"
                )
                "$baseUrl${searchPaths.first()}${query.replace(" ", "+")}"
            }
        }
    }
    
    /**
     * Calculate overall confidence from multiple sources
     */
    private fun calculateOverallConfidence(vararg confidences: Float): Float {
        val validConfidences = confidences.filter { it > 0f }
        return if (validConfidences.isEmpty()) 0f
        else validConfidences.average().toFloat()
    }
    
    /**
     * Determine recovery strategy from error message
     */
    private fun determineRecoveryStrategy(error: String): RecoveryAction {
        val errorLower = error.lowercase()
        return when {
            errorLower.contains("403") || errorLower.contains("geo") || 
            errorLower.contains("blocked") -> RecoveryAction.USE_PROXY
            
            errorLower.contains("quality") || errorLower.contains("format") ->
                RecoveryAction.TRY_ALTERNATE_QUALITY
            
            errorLower.contains("timeout") || errorLower.contains("connection") ->
                RecoveryAction.RETRY_WITH_DELAY
            
            errorLower.contains("proxy") -> RecoveryAction.ROTATE_PROXY
            
            else -> RecoveryAction.RETRY_WITH_DELAY
        }
    }
    
    /**
     * Build initialization message
     */
    private fun buildInitMessage(proxyResult: ProxyInitResult): String {
        return buildString {
            append("AggregatorX Engine initialized. ")
            if (proxyResult.success) {
                append("Netherlands proxy active: ${proxyResult.proxyAddress}")
            } else {
                append("Proxy unavailable: ${proxyResult.error ?: "Unknown error"}. Using direct connections.")
            }
        }
    }
}

// ==========================================
// Data Classes
// ==========================================

enum class EngineState {
    IDLE,
    INITIALIZING,
    READY,
    ANALYZING,
    SEARCHING,
    RESOLVING_VIDEO,
    RECOVERING,
    ERROR
}

enum class RecoveryAction {
    USE_PROXY,
    TRY_ALTERNATE_QUALITY,
    ROTATE_PROXY,
    RETRY_WITH_DELAY,
    GIVE_UP
}

enum class ExtractionStrategy {
    HTML_PARSING,
    API_FIRST,
    FRAMEWORK_DATA,
    HYBRID
}

data class InitializationResult(
    val success: Boolean,
    val proxyReady: Boolean = false,
    val proxyInfo: String? = null,
    val message: String
)

data class ProxyInitResult(
    val success: Boolean,
    val proxyAddress: String?,
    val error: String? = null
)

data class ContentExtractionResult(
    val success: Boolean,
    val items: List<ContentItem> = emptyList(),
    val categories: List<CategoryInfo> = emptyList(),
    val pageType: String? = null,
    val dataFormat: String? = null,
    val confidence: Float = 0f,
    val extractionStrategy: ExtractionStrategy? = null,
    val siteAnalysis: SiteAnalysis? = null,
    val metadata: Map<String, String> = emptyMap(),
    val usedProxy: Boolean = false,
    val searchQuery: String? = null,
    val error: String? = null
)

data class ContentItem(
    val title: String,
    val url: String,
    val thumbnail: String? = null,
    val description: String? = null,
    val duration: String? = null,
    val quality: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val contentType: String = "VIDEO",
    val source: String = "Unknown",
    val confidence: Float = 0.5f
)

data class CategoryInfo(
    val name: String,
    val url: String,
    val count: Int? = null
)

data class VideoPlaybackResult(
    val success: Boolean,
    val streamUrl: String? = null,
    val streamType: String? = null,
    val quality: String? = null,
    val format: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val usedProxy: Boolean = false,
    val proxyInfo: String? = null,
    val isValidated: Boolean = false,
    val estimatedBitrate: Long? = null,
    val error: String? = null,
    val suggestedRecovery: String? = null
) {
    companion object {
        fun fromStreamResult(result: VideoStreamResult): VideoPlaybackResult {
            return VideoPlaybackResult(
                success = result.success,
                streamUrl = result.streamUrl,
                streamType = result.streamType?.name,
                quality = result.quality,
                format = result.format,
                headers = result.headers ?: emptyMap(),
                usedProxy = result.usedProxy != null,
                proxyInfo = result.usedProxy,
                isValidated = result.isValidated,
                estimatedBitrate = result.estimatedBitrate?.toLong(),
                error = result.error,
                suggestedRecovery = result.suggestedRecovery?.name
            )
        }
    }
}
