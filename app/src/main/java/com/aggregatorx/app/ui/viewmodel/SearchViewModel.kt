package com.aggregatorx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.data.repository.AggregatorRepository
import com.aggregatorx.app.engine.media.AdvancedVideoExtractorEngine
import com.aggregatorx.app.engine.media.DownloadManager
import com.aggregatorx.app.engine.media.DownloadState
import com.aggregatorx.app.engine.media.VideoExtractorEngine
import com.aggregatorx.app.engine.media.VideoExtractionResult
import com.aggregatorx.app.engine.media.VideoStreamResolver
import com.aggregatorx.app.engine.token.TokenManager
import com.aggregatorx.app.engine.util.EngineUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lightweight result for video extraction.
 * Forwards both the playable URL and the specific HTTP headers (Referer/Origin)
 * required by the source CDN to prevent 403 Forbidden errors.
 */
data class VideoPreviewResult(
    val videoUrl: String,
    val headers: Map<String, String> = emptyMap()
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AggregatorRepository,
    private val videoExtractor: VideoExtractorEngine,
    private val advancedExtractor: AdvancedVideoExtractorEngine,
    private val videoStreamResolver: VideoStreamResolver,
    private val downloadManager: DownloadManager,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _providerResults = MutableStateFlow<List<ProviderSearchResults>>(emptyList())
    val providerResults: StateFlow<List<ProviderSearchResults>> = _providerResults.asStateFlow()

    private val _videoExtractionState = MutableStateFlow<VideoExtractionState>(VideoExtractionState.Idle)
    val videoExtractionState: StateFlow<VideoExtractionState> = _videoExtractionState.asStateFlow()

    val downloads: StateFlow<Map<String, DownloadState>> = downloadManager.downloads

    private val _likedUrls = MutableStateFlow<Set<String>>(emptySet())
    val likedUrls: StateFlow<Set<String>> = _likedUrls.asStateFlow()

    private val _isDiscoveryPaused = MutableStateFlow(false)
    val isDiscoveryPaused: StateFlow<Boolean> = _isDiscoveryPaused.asStateFlow()

    private val _providerPages = MutableStateFlow<Map<String, Int>>(emptyMap())
    val providerPages: StateFlow<Map<String, Int>> = _providerPages.asStateFlow()

    private val _tokenResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val tokenResults: StateFlow<List<SearchResult>> = _tokenResults.asStateFlow()

    private val _myAiResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val myAiResults: StateFlow<List<SearchResult>> = _myAiResults.asStateFlow()

    private val videoPreviewCache = java.util.concurrent.ConcurrentHashMap<String, VideoPreviewResult>()
    private var currentSearchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.getRecentSearches().collect { searches ->
                _uiState.update { it.copy(recentSearches = searches) }
            }
        }
        viewModelScope.launch {
            _likedUrls.value = repository.getAllLikedUrls()
        }
    }

    /**
     * PANIC REFRESH: Hard-resets the ViewModel state and clears memory.
     * Use this when the app experiences lag or high battery drain during heavy scraping.
     */
    fun panicRefresh() {
        currentSearchJob?.cancel()
        videoPreviewCache.clear()
        _providerResults.value = emptyList()
        _providerPages.value = emptyMap()
        _uiState.update {
            it.copy(
                isSearching = false,
                searchCompleted = false,
                totalResults = 0,
                successfulProviders = 0,
                failedProviders = 0,
                aggregatedResults = null,
                error = null
            )
        }
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
        
        val query = _uiState.value.currentSearchQuery
        if (query.isNotEmpty()) {
            _uiState.update { it.copy(query = query) }
            search()
        }
    }

    /**
     * Toggles discovery pause. Suspending the job stops background data injection,
     * allowing the user to scroll results without UI jumps.
     */
    fun toggleDiscoveryPause() {
        val nowPaused = !_isDiscoveryPaused.value
        _isDiscoveryPaused.value = nowPaused
        if (nowPaused) {
            currentSearchJob?.cancel()
            currentSearchJob = null
            _uiState.update { it.copy(isSearching = false) }
        } else {
            val query = _uiState.value.currentSearchQuery
            if (query.isNotEmpty()) {
                _uiState.update { it.copy(query = query) }
                search()
            }
        }
    }

    fun nextProviderPage(providerId: String) {
        _providerPages.update { pages ->
            val current = pages[providerId] ?: 0
            pages + (providerId to current + 1)
        }
    }

    fun prevProviderPage(providerId: String) {
        _providerPages.update { pages ->
            val current = pages[providerId] ?: 0
            if (current > 0) pages + (providerId to current - 1) else pages
        }
    }

    fun refreshProvider(providerId: String) {
        _providerPages.update { pages -> pages + (providerId to 0) }
    }

    fun toggleLike(result: SearchResult) {
        viewModelScope.launch {
            val nowLiked = repository.toggleLike(result)
            _likedUrls.update { urls ->
                if (nowLiked) urls + result.url else urls - result.url
            }
        }
    }
    
    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
    }
    
    /**
     * Primary search entry point. 
     * Runs the concurrent multi-provider scrape followed by AI-ranking passes.
     */
    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty() || _isDiscoveryPaused.value) return

        currentSearchJob?.cancel()

        currentSearchJob = viewModelScope.launch {
            repository.clearSearchCache()
            videoPreviewCache.clear()
            _providerPages.value  = emptyMap()
            _providerResults.value = emptyList()
            _tokenResults.value    = emptyList()
            _myAiResults.value     = emptyList()

            _uiState.update {
                it.copy(
                    isSearching         = true,
                    searchCompleted     = false,
                    currentSearchQuery  = query,
                    totalResults        = 0,
                    successfulProviders = 0,
                    failedProviders     = 0,
                    aggregatedResults   = null,
                    error               = null
                )
            }
            
            val results = mutableListOf<ProviderSearchResults>()
            
            repository.searchAllProviders(query)
                .catch { e ->
                    if (results.isEmpty()) {
                        _uiState.update { it.copy(error = e.message ?: "Search failed", isSearching = false) }
                    }
                }
                .collect { providerResult ->
                    results.add(providerResult)
                    _providerResults.value = results.toList()
                    
                    try {
                        val aggregated = repository.aggregateSearchResults(query, results)
                        _uiState.update { 
                            it.copy(
                                aggregatedResults = aggregated,
                                totalResults = aggregated.totalResults,
                                successfulProviders = aggregated.successfulProviders,
                                failedProviders = aggregated.failedProviders,
                                error = null
                            ) 
                        }
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(
                                totalResults = results.sumOf { r -> r.results.size },
                                successfulProviders = results.count { r -> r.success },
                                failedProviders = results.count { r -> !r.success }
                            )
                        }
                    }
                }
            
            _uiState.update { it.copy(isSearching = false, searchCompleted = true) }

            // PASS 1: Token Discovery (Background)
            launch {
                val tokenFound = mutableListOf<SearchResult>()
                results.filter { it.success }.forEach { providerResult ->
                    try {
                        val discovered = tokenManager.replayTokensForSearch(providerResult.provider.baseUrl, query)
                        discovered.take(15).forEach { url ->
                            tokenFound.add(SearchResult(
                                providerId = providerResult.provider.id,
                                providerName = "${providerResult.provider.name} [TOKEN]",
                                title = "Deep Link: ${url.takeLast(30)}",
                                url = url,
                                relevanceScore = 80f
                            ))
                        }
                    } catch (_: Exception) {}
                }
                if (tokenFound.isNotEmpty()) _tokenResults.value = tokenFound.distinctBy { it.url }
            }

            // PASS 2: MY AI Preference Ranking (Background)
            launch {
                val likedSet = _likedUrls.value
                if (likedSet.isEmpty()) return@launch
                
                val likedDomains = likedSet.mapNotNull { url ->
                    try { java.net.URI(url).host } catch (_: Exception) { null }
                }.toSet()

                val aiRanked = results.flatMap { it.results }
                    .map { r ->
                        val domain = try { java.net.URI(r.url).host } catch (_: Exception) { "" }
                        val boost = if (domain in likedDomains) 40f else 0f
                        r.copy(relevanceScore = r.relevanceScore + boost)
                    }
                    .sortedByDescending { it.relevanceScore }
                    .take(60)
                _myAiResults.value = aiRanked
            }
        }
    }
    
    fun clearSearch() {
        _uiState.update { it.copy(query = "", aggregatedResults = null, searchCompleted = false, error = null) }
        _providerResults.value = emptyList()
        videoPreviewCache.clear()
    }
    
    fun clearError() { _uiState.update { it.copy(error = null) } }
    
    fun clearSearchHistory() { viewModelScope.launch { repository.clearSearchHistory() } }
    
    fun searchFromHistory(query: String) {
        _uiState.update { it.copy(query = query) }
        search()
    }
    
    /**
     * Resolves a page URL into a playable stream using a prioritized chain.
     * Prevents feeding HTML to the player via isLikelyMediaUrl safety checks.
     */
    suspend fun extractVideoForPreview(pageUrl: String): VideoPreviewResult? {
        videoPreviewCache[pageUrl]?.let { cached ->
            if (isLikelyMediaUrl(cached.videoUrl)) return cached
            else videoPreviewCache.remove(pageUrl)
        }

        return try {
            // Priority 1: Advanced Extraction (Pattern Matching & De-obfuscation)
            val advResult = advancedExtractor.extract(pageUrl)
            if (advResult.success && !advResult.videoUrl.isNullOrEmpty() && isLikelyMediaUrl(advResult.videoUrl)) {
                return cacheAndReturn(pageUrl, advResult.videoUrl)
            }

            // Priority 2: Fast Path (DOM parsing)
            val fastUrl = videoExtractor.extractVideoUrlForPreview(pageUrl)
            if (!fastUrl.isNullOrEmpty() && isLikelyMediaUrl(fastUrl)) {
                return cacheAndReturn(pageUrl, fastUrl)
            }

            // Priority 3: Full Resolution (Stream Resolvers & Proxies)
            val resolved = videoStreamResolver.resolveVideoStream(pageUrl)
            if (resolved.success && !resolved.streamUrl.isNullOrEmpty() && isLikelyMediaUrl(resolved.streamUrl)) {
                val res = VideoPreviewResult(resolved.streamUrl, resolved.headers ?: buildPlaybackHeaders(pageUrl))
                videoPreviewCache[pageUrl] = res
                return res
            }

            // Fallback: Direct probe
            if (isLikelyMediaUrl(pageUrl)) return cacheAndReturn(pageUrl, pageUrl)

            null
        } catch (e: Exception) {
            if (isLikelyMediaUrl(pageUrl)) cacheAndReturn(pageUrl, pageUrl) else null
        }
    }

    private fun cacheAndReturn(pageUrl: String, videoUrl: String): VideoPreviewResult {
        val result = VideoPreviewResult(videoUrl, buildPlaybackHeaders(pageUrl))
        videoPreviewCache[pageUrl] = result
        return result
    }

    private fun isLikelyMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        val indicators = listOf(
            ".mp4", ".m3u8", ".mpd", ".webm", ".mkv", ".ts", "/hls/", "/dash/", 
            "videoplayback", "googlevideo", "akamaized", "cdn", "dood.", "streamtape"
        )
        return indicators.any { lower.contains(it) }
    }

    suspend fun extractVideoUrlForPreview(pageUrl: String): String? = extractVideoForPreview(pageUrl)?.videoUrl

    private fun buildPlaybackHeaders(pageUrl: String): Map<String, String> {
        val origin = try {
            val uri = android.net.Uri.parse(pageUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) { pageUrl }
        return mapOf(
            "User-Agent" to EngineUtils.DEFAULT_USER_AGENT,
            "Referer" to "$origin/",
            "Origin" to origin
        )
    }
    
    /**
     * Comprehensive video extraction for the UI Watch action.
     */
    fun extractVideoUrl(result: SearchResult) {
        viewModelScope.launch {
            _videoExtractionState.value = VideoExtractionState.Extracting(result.title)
            try {
                val adv = advancedExtractor.extract(result.url)
                if (adv.success && !adv.videoUrl.isNullOrEmpty()) {
                    _videoExtractionState.value = VideoExtractionState.Success(
                        videoUrl = adv.videoUrl,
                        title = result.title,
                        quality = adv.quality,
                        isStream = adv.isStream,
                        headers = buildPlaybackHeaders(result.url)
                    )
                    return@launch
                }

                val res = videoStreamResolver.resolveVideoStream(result.url)
                if (res.success && !res.streamUrl.isNullOrEmpty()) {
                    _videoExtractionState.value = VideoExtractionState.Success(
                        videoUrl = res.streamUrl,
                        title = result.title,
                        quality = res.quality,
                        isStream = res.streamType.name in listOf("HLS", "DASH"),
                        headers = res.headers ?: buildPlaybackHeaders(result.url)
                    )
                    return@launch
                }

                _videoExtractionState.value = VideoExtractionState.Error("Extraction failed. Try opening in external browser.")
            } catch (e: Exception) {
                _videoExtractionState.value = VideoExtractionState.Error(e.message ?: "Extraction failed")
            }
        }
    }
    
    fun resetVideoState() { _videoExtractionState.value = VideoExtractionState.Idle }
    
    fun downloadResult(result: SearchResult) {
        viewModelScope.launch {
            try { downloadManager.downloadFromPage(result.url, result.title) } 
            catch (e: Exception) { _uiState.update { it.copy(error = "Download failed: ${e.message}") } }
        }
    }
    
    fun downloadVideoUrl(videoUrl: String, title: String) {
        viewModelScope.launch {
            try { downloadManager.downloadDirect(videoUrl, title) } 
            catch (e: Exception) { _uiState.update { it.copy(error = "Download failed: ${e.message}") } }
        }
    }
    
    fun cancelDownload(downloadId: String) { downloadManager.cancelDownload(downloadId) }
}

data class SearchUiState(
    val query: String = "",
    val currentSearchQuery: String = "",
    val isSearching: Boolean = false,
    val searchCompleted: Boolean = false,
    val aggregatedResults: AggregatedSearchResults? = null,
    val totalResults: Int = 0,
    val successfulProviders: Int = 0,
    val failedProviders: Int = 0,
    val recentSearches: List<SearchHistoryEntry> = emptyList(),
    val error: String? = null
)

sealed class VideoExtractionState {
    object Idle : VideoExtractionState()
    data class Extracting(val title: String) : VideoExtractionState()
    data class Success(
        val videoUrl: String,
        val title: String,
        val quality: String?,
        val isStream: Boolean,
        val headers: Map<String, String> = emptyMap()
    ) : VideoExtractionState()
    data class Error(val message: String) : VideoExtractionState()
}
