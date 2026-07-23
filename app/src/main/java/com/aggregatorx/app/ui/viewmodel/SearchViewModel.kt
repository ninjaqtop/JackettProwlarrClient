package com.aggregatorx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.data.repository.AggregatorRepository
import com.aggregatorx.app.engine.media.*
import com.aggregatorx.app.engine.ml.ProviderPaginationManager
import com.aggregatorx.app.engine.ml.ResultNormalizer
import com.aggregatorx.app.engine.token.TokenManager
import com.aggregatorx.app.engine.util.EngineUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Result returned by extraction to forward playable URL + HTTP headers.
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

    private val _tokenResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val tokenResults: StateFlow<List<SearchResult>> = _tokenResults.asStateFlow()

    private val _myAiResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val myAiResults: StateFlow<List<SearchResult>> = _myAiResults.asStateFlow()

    private val _isDiscoveryPaused = MutableStateFlow(false)
    val isDiscoveryPaused: StateFlow<Boolean> = _isDiscoveryPaused.asStateFlow()

    private val _providerPages = MutableStateFlow<Map<String, Int>>(emptyMap())
    val providerPages: StateFlow<Map<String, Int>> = _providerPages.asStateFlow()
    private val _providerFetchPages = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _loadingProviderIds = MutableStateFlow<Set<String>>(emptySet())
    val loadingProviderIds: StateFlow<Set<String>> = _loadingProviderIds.asStateFlow()

    private companion object {
        const val PROVIDER_PAGE_SIZE = 50
    }

    // ── MISSION CONTROL: Session Tracking ───────────────────────────────
    private val sessionSeenUrls = mutableSetOf<String>()
    private val videoPreviewCache = java.util.concurrent.ConcurrentHashMap<String, VideoPreviewResult>()
    private var currentSearchJob: Job? = null

    init {
        ProviderPaginationManager.configure { providerId, query, state ->
            val providerResult = repository.searchProviderPage(providerId, query, state.page - 1)
            if (providerResult == null) {
                emptyList()
            } else {
                ResultNormalizer.normalize(providerResult.provider.id, providerResult.provider.baseUrl, providerResult.results)
            }
        }
        viewModelScope.launch {
            repository.getRecentSearches().collect { searches ->
                _uiState.update { it.copy(recentSearches = searches) }
            }
        }
        viewModelScope.launch { _likedUrls.value = repository.getAllLikedUrls() }
    }

    // ── SEARCH & DISCOVERY ──────────────────────────────────────────────

    fun search(isLoadMore: Boolean = false) {
        val query = _uiState.value.query.trim()
        if (query.isEmpty() || _isDiscoveryPaused.value) return

        currentSearchJob?.cancel()

        currentSearchJob = viewModelScope.launch {
            if (!isLoadMore) {
                sessionSeenUrls.clear()
                repository.clearSearchCache()
                videoPreviewCache.clear()
                _providerResults.value = emptyList()
                _tokenResults.value = emptyList()
                _myAiResults.value = emptyList()
                _providerPages.value = emptyMap()
                _providerFetchPages.value = emptyMap()
            }

            _uiState.update { it.copy(isSearching = true, currentSearchQuery = query, error = null) }
            val currentResults = if (isLoadMore) _providerResults.value.toMutableList() else mutableListOf()

            // Calls Repository using the new pages parameter fix
            repository.searchAllProviders(query, pages = _providerFetchPages.value)
                .catch { e -> if (currentResults.isEmpty()) _uiState.update { it.copy(error = e.message) } }
                .collect { providerResult ->
                    val normalizedResults = ResultNormalizer.normalize(
                        providerId = providerResult.provider.id,
                        providerBaseUrl = providerResult.provider.baseUrl,
                        results = providerResult.results
                    )
                    ProviderPaginationManager.markFetched(providerResult.provider.id, normalizedResults.size)
                    val normalizedProviderResult = providerResult.copy(
                        results = normalizedResults,
                        totalResults = normalizedResults.size,
                        hasMore = normalizedResults.size >= PROVIDER_PAGE_SIZE
                    )
                    // Session-level de-duplication
                    val uniqueNewOnes = normalizedProviderResult.results.filter { sessionSeenUrls.add(it.url) }
                    
                    if (uniqueNewOnes.isNotEmpty()) {
                        val filteredResult = normalizedProviderResult.copy(results = uniqueNewOnes)
                        val existingIndex = currentResults.indexOfFirst { it.provider.id == normalizedProviderResult.provider.id }
                        if (existingIndex >= 0) {
                            val existing = currentResults[existingIndex]
                            currentResults[existingIndex] = existing.copy(
                                results = (existing.results + uniqueNewOnes).distinctBy { it.url },
                                searchTime = normalizedProviderResult.searchTime,
                                success = normalizedProviderResult.success,
                                errorMessage = normalizedProviderResult.errorMessage,
                                totalResults = existing.results.size + uniqueNewOnes.size,
                                hasMore = normalizedProviderResult.hasMore,
                                nextPageUrl = normalizedProviderResult.nextPageUrl
                            )
                        } else {
                            currentResults.add(filteredResult)
                        }
                        _providerResults.value = currentResults.toList()

                        try {
                            val aggregated = repository.aggregateSearchResults(query, currentResults)
                            _uiState.update { it.copy(
                                aggregatedResults = aggregated,
                                totalResults = sessionSeenUrls.size,
                                successfulProviders = aggregated.successfulProviders,
                                failedProviders = aggregated.failedProviders
                            ) }
                        } catch (_: Exception) {}
                    }
                }

            _uiState.update { it.copy(isSearching = false, searchCompleted = true) }

            // PASS 2: Preference Ranking (AI Tab)
            launch {
                val likedDomains = _likedUrls.value.mapNotNull { 
                    try { java.net.URI(it).host } catch (_: Exception) { null } 
                }.toSet()

                val aiRanked = currentResults.flatMap { it.results }
                    .map { r ->
                        val host = try { java.net.URI(r.url).host } catch (_: Exception) { "" }
                        val boost = if (host in likedDomains) 50f else 0f
                        r.copy(relevanceScore = r.relevanceScore + boost)
                    }
                    .filter { it.relevanceScore > 40f }
                    .sortedByDescending { it.relevanceScore }
                _myAiResults.value = aiRanked.take(50)
            }

            // PASS 3: Related discovery (Token Tab)
            launch {
                val tokensFound = mutableListOf<SearchResult>()
                currentResults.filter { it.success }.forEach { p ->
                    try {
                        tokenManager.replayTokensForSearch(p.provider.baseUrl, query).forEach { url ->
                            if (!sessionSeenUrls.contains(url)) {
                                tokensFound.add(SearchResult(
                                    providerId = p.provider.id,
                                    providerName = "${p.provider.name} [TOKEN]",
                                    title = "Discovered: ${url.takeLast(30)}",
                                    url = url,
                                    relevanceScore = 60f
                                ))
                                sessionSeenUrls.add(url)
                            }
                        }
                    } catch (_: Exception) {}
                }
                _tokenResults.value = tokensFound.distinctBy { it.url }
            }
        }
    }

    // ── PAGINATION & CONTROLS ──────────────────────────────────────────

    fun nextProviderPage(providerId: String) {
        val current = _providerPages.value[providerId] ?: 0
        val loadedCount = _providerResults.value
            .firstOrNull { it.provider.id == providerId }
            ?.results
            ?.size ?: 0

        if (loadedCount > (current + 1) * PROVIDER_PAGE_SIZE) {
            _providerPages.update { it + (providerId to current + 1) }
        } else {
            val query = _uiState.value.currentSearchQuery.ifBlank { _uiState.value.query }.trim()
            if (query.isBlank()) return
            viewModelScope.launch {
                _loadingProviderIds.update { it + providerId }
                ProviderPaginationManager.fetchMoreResults(providerId, query).collect { fetched ->
                    val existingIndex = _providerResults.value.indexOfFirst { it.provider.id == providerId }
                    if (existingIndex >= 0 && fetched.isNotEmpty()) {
                        val next = _providerResults.value.toMutableList()
                        val existing = next[existingIndex]
                        val unique = fetched.filter { sessionSeenUrls.add(it.url) }
                        next[existingIndex] = existing.copy(
                            results = (existing.results + unique).distinctBy { it.url },
                            totalResults = existing.results.size + unique.size,
                            hasMore = unique.isNotEmpty()
                        )
                        _providerResults.value = next
                    } else if (existingIndex >= 0) {
                        val next = _providerResults.value.toMutableList()
                        next[existingIndex] = next[existingIndex].copy(hasMore = false)
                        _providerResults.value = next
                    }
                }
                _providerPages.update { it + (providerId to current + 1) }
                _loadingProviderIds.update { it - providerId }
            }
        }
    }

    fun prevProviderPage(providerId: String) {
        _providerPages.update { pages ->
            val current = pages[providerId] ?: 0
            if (current <= 0) pages - providerId else pages + (providerId to current - 1)
        }
    }

    fun refreshProvider(providerId: String) {
        ProviderPaginationManager.reset(providerId)
        val query = _uiState.value.currentSearchQuery.ifBlank { _uiState.value.query }.trim()
        _providerPages.update { it - providerId }
        _providerFetchPages.update { it - providerId }
        val removedUrls = _providerResults.value
            .firstOrNull { it.provider.id == providerId }
            ?.results
            ?.map { it.url }
            .orEmpty()
        sessionSeenUrls.removeAll(removedUrls.toSet())
        _providerResults.value = _providerResults.value.filterNot { it.provider.id == providerId }
        if (query.isBlank()) return
        viewModelScope.launch {
            _loadingProviderIds.update { it + providerId }
            repository.searchProviderPage(providerId, query, 0)?.let { providerResult ->
                val normalized = ResultNormalizer.normalize(providerResult.provider.id, providerResult.provider.baseUrl, providerResult.results)
                _providerResults.update { current ->
                    current.filterNot { it.provider.id == providerId } + providerResult.copy(
                        results = normalized,
                        totalResults = normalized.size,
                        hasMore = normalized.size >= PROVIDER_PAGE_SIZE
                    )
                }
                normalized.forEach { sessionSeenUrls.add(it.url) }
            }
            _loadingProviderIds.update { it - providerId }
        }
    }

    fun searchFromHistory(query: String) {
        updateQuery(query)
        search(isLoadMore = false)
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            repository.clearSearchHistory()
        }
    }

    fun panicRefresh() {
        currentSearchJob?.cancel()
        search(isLoadMore = false)
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
    }

    fun toggleDiscoveryPause() {
        val nowPaused = !_isDiscoveryPaused.value
        _isDiscoveryPaused.value = nowPaused
        if (nowPaused) {
            currentSearchJob?.cancel()
            _uiState.update { it.copy(isSearching = false) }
        } else {
            search(isLoadMore = true)
        }
    }

    fun toggleLike(result: SearchResult) {
        viewModelScope.launch {
            val nowLiked = repository.toggleLike(result)
            _likedUrls.update { urls -> if (nowLiked) urls + result.url else urls - result.url }
        }
    }

    fun updateQuery(query: String) { _uiState.update { it.copy(query = query) } }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    fun clearSearch() {
        _uiState.update { it.copy(query = "", aggregatedResults = null, searchCompleted = false, error = null) }
        _providerResults.value = emptyList()
        sessionSeenUrls.clear()
        videoPreviewCache.clear()
    }

    // ── VIDEO EXTRACTION CHAIN ──────────────────────────────────────────

    suspend fun extractVideoForPreview(pageUrl: String): VideoPreviewResult? {
        videoPreviewCache[pageUrl]?.let { if (isLikelyMediaUrl(it.videoUrl)) return it }

        return try {
            val adv = advancedExtractor.extract(pageUrl)
            if (adv.success && !adv.videoUrl.isNullOrEmpty() && isLikelyMediaUrl(adv.videoUrl)) {
                return cacheAndReturn(pageUrl, adv.videoUrl)
            }

            val fast = videoExtractor.extractVideoUrlForPreview(pageUrl)
            if (!fast.isNullOrEmpty() && isLikelyMediaUrl(fast)) {
                return cacheAndReturn(pageUrl, fast)
            }

            val resolved = videoStreamResolver.resolveVideoStream(pageUrl)
            if (resolved.success && !resolved.streamUrl.isNullOrEmpty() && isLikelyMediaUrl(resolved.streamUrl)) {
                val res = VideoPreviewResult(resolved.streamUrl, resolved.headers ?: buildPlaybackHeaders(pageUrl))
                videoPreviewCache[pageUrl] = res
                return res
            }

            if (isLikelyMediaUrl(pageUrl)) cacheAndReturn(pageUrl, pageUrl) else null
        } catch (e: Exception) { if (isLikelyMediaUrl(pageUrl)) cacheAndReturn(pageUrl, pageUrl) else null }
    }

    private fun cacheAndReturn(pageUrl: String, videoUrl: String): VideoPreviewResult {
        val res = VideoPreviewResult(videoUrl, buildPlaybackHeaders(pageUrl))
        videoPreviewCache[pageUrl] = res
        return res
    }

    private fun isLikelyMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(".mp4", ".m3u8", ".mpd", ".webm", "videoplayback", "akamaized", "cdn").any { lower.contains(it) }
    }

    private fun buildPlaybackHeaders(pageUrl: String): Map<String, String> {
        val origin = try {
            val uri = android.net.Uri.parse(pageUrl)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) { pageUrl }
        return mapOf("User-Agent" to EngineUtils.DEFAULT_USER_AGENT, "Referer" to "$origin/", "Origin" to origin)
    }

    fun extractVideoUrl(result: SearchResult) {
        viewModelScope.launch {
            _videoExtractionState.value = VideoExtractionState.Extracting(result.title)
            try {
                val adv = advancedExtractor.extract(result.url)
                if (adv.success && !adv.videoUrl.isNullOrEmpty()) {
                    _videoExtractionState.value = VideoExtractionState.Success(
                        videoUrl = adv.videoUrl, title = result.title, 
                        quality = adv.quality, isStream = adv.isStream, 
                        headers = buildPlaybackHeaders(result.url)
                    )
                    return@launch
                }
                _videoExtractionState.value = VideoExtractionState.Error("Extraction failed. Try browser.")
            } catch (e: Exception) { _videoExtractionState.value = VideoExtractionState.Error(e.message ?: "Error") }
        }
    }

    // ── DOWNLOADS ───────────────────────────────────────────────────────

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

    fun cancelDownload(id: String) = downloadManager.cancelDownload(id)
    fun resetVideoState() { _videoExtractionState.value = VideoExtractionState.Idle }
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
        val videoUrl: String, val title: String, 
        val quality: String?, val isStream: Boolean, 
        val headers: Map<String, String> = emptyMap()
    ) : VideoExtractionState()
    data class Error(val message: String) : VideoExtractionState()
}
