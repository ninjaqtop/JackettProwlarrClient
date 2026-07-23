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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
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
    private val autoFillJobs = ConcurrentHashMap<String, Job>()
    private val autoFillSemaphore = Semaphore(2)

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
        viewModelScope.launch {
            repository.getEnabledProviders().collect { providers ->
                if (!_uiState.value.isSearching && !_uiState.value.searchCompleted) {
                    _providerResults.value = providers.map { provider ->
                        ProviderSearchResults(
                            provider = provider,
                            results = emptyList(),
                            searchTime = 0L,
                            success = false,
                            errorMessage = "Ready to search",
                            status = ProviderSearchStatus.READY
                        )
                    }
                }
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
                autoFillJobs.values.forEach(Job::cancel)
                autoFillJobs.clear()
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
            val currentResults = if (isLoadMore) {
                _providerResults.value.toMutableList()
            } else {
                repository.getEnabledProvidersSnapshot().map { provider ->
                    ProviderSearchResults(
                        provider = provider,
                        results = emptyList(),
                        searchTime = 0L,
                        success = false,
                        errorMessage = null,
                        status = ProviderSearchStatus.SEARCHING
                    )
                }.toMutableList()
            }
            _providerResults.value = currentResults.toList()
            if (!isLoadMore) currentResults.forEach { ProviderPaginationManager.reset(it.provider.id) }
            updateSearchStats(currentResults)

            try {
                // Calls Repository using the new pages parameter fix
                repository.searchAllProviders(query, pages = _providerFetchPages.value)
                    .catch { e -> if (currentResults.isEmpty()) _uiState.update { it.copy(error = e.message) } }
                    .collect { providerResult ->
                        try {
                            val normalizedResults = safeNormalize(providerResult)
                            ProviderPaginationManager.markFetched(providerResult.provider.id, normalizedResults.size)
                            val terminalStatus = when {
                                normalizedResults.isNotEmpty() -> ProviderSearchStatus.RESULTS
                                providerResult.status == ProviderSearchStatus.TIMED_OUT -> ProviderSearchStatus.TIMED_OUT
                                providerResult.success -> ProviderSearchStatus.EMPTY
                                else -> ProviderSearchStatus.FAILED
                            }
                            val normalizedProviderResult = providerResult.copy(
                                results = normalizedResults,
                                totalResults = normalizedResults.size,
                                hasMore = providerResult.hasMore,
                                success = normalizedResults.isNotEmpty(),
                                errorMessage = providerResult.errorMessage
                                    ?: if (normalizedResults.isEmpty()) "No parseable results found" else null,
                                status = terminalStatus
                            )
                            normalizedResults.forEach { sessionSeenUrls.add(it.url) }
                            upsertProviderResult(currentResults, normalizedProviderResult)
                            _providerResults.value = currentResults.toList()
                            updateSearchStats(currentResults)
                            if (terminalStatus == ProviderSearchStatus.RESULTS && normalizedResults.size < PROVIDER_PAGE_SIZE) {
                                scheduleAutoFill(providerResult.provider.id, query)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            val failed = providerResult.copy(
                                results = emptyList(),
                                success = false,
                                errorMessage = e.message ?: e.javaClass.simpleName,
                                status = ProviderSearchStatus.FAILED
                            )
                            upsertProviderResult(currentResults, failed)
                            _providerResults.value = currentResults.toList()
                            updateSearchStats(currentResults)
                        }
                    }
                currentResults.replaceAll { result ->
                    if (result.status == ProviderSearchStatus.SEARCHING) {
                        result.copy(
                            success = false,
                            errorMessage = "Search ended before this provider responded",
                            status = ProviderSearchStatus.FAILED
                        )
                    } else result
                }
                _providerResults.value = currentResults.toList()
                finalizeAggregatedState(query, currentResults)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(error = e.message ?: "Search failed") }
            } finally {
                _uiState.update { it.copy(isSearching = false, searchCompleted = true) }
            }

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
                try {
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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _uiState.update { it.copy(error = "Load more failed: ${e.message ?: e.javaClass.simpleName}") }
                } finally {
                    _loadingProviderIds.update { it - providerId }
                }
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
        if (query.isBlank()) return
        viewModelScope.launch {
            _loadingProviderIds.update { it + providerId }
            try {
                repository.searchProviderPage(providerId, query, 0)?.let { providerResult ->
                    val normalized = safeNormalize(providerResult)
                    ProviderPaginationManager.markFetched(providerId, normalized.size)
                    val status = when {
                        normalized.isNotEmpty() -> ProviderSearchStatus.RESULTS
                        providerResult.status == ProviderSearchStatus.TIMED_OUT -> ProviderSearchStatus.TIMED_OUT
                        providerResult.success -> ProviderSearchStatus.EMPTY
                        else -> ProviderSearchStatus.FAILED
                    }
                    _providerResults.update { current ->
                        current.filterNot { it.provider.id == providerId } + providerResult.copy(
                            results = normalized,
                            totalResults = normalized.size,
                            hasMore = providerResult.hasMore,
                            success = normalized.isNotEmpty(),
                            status = status,
                            errorMessage = providerResult.errorMessage
                                ?: if (normalized.isEmpty()) "No parseable results found" else null
                        )
                    }
                    normalized.forEach { sessionSeenUrls.add(it.url) }
                    updateSearchStats(_providerResults.value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _providerResults.update { current ->
                    current.map { providerResult ->
                        if (providerResult.provider.id == providerId) {
                            providerResult.copy(
                                results = emptyList(),
                                success = false,
                                errorMessage = e.message ?: e.javaClass.simpleName,
                                status = ProviderSearchStatus.FAILED
                            )
                        } else providerResult
                    }
                }
                _uiState.update { it.copy(error = "Provider refresh failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _loadingProviderIds.update { it - providerId }
            }
        }
    }

    private suspend fun safeNormalize(providerResult: ProviderSearchResults): List<SearchResult> {
        val base = providerResult.results.filter { it.url.isNotBlank() && it.title.isNotBlank() }
        return runCatching {
            ResultNormalizer.normalize(
                providerId = providerResult.provider.id,
                providerBaseUrl = providerResult.provider.baseUrl,
                results = base
            )
        }.getOrElse {
            base.distinctBy { result -> result.url }.take(PROVIDER_PAGE_SIZE)
        }
    }

    private fun updateSearchStats(currentResults: List<ProviderSearchResults>) {
        _uiState.update { state ->
            state.copy(
                totalResults = sessionSeenUrls.size,
                successfulProviders = currentResults.count { it.status == ProviderSearchStatus.RESULTS },
                failedProviders = currentResults.count {
                    it.status == ProviderSearchStatus.FAILED || it.status == ProviderSearchStatus.TIMED_OUT
                }
            )
        }
    }

    private fun upsertProviderResult(
        currentResults: MutableList<ProviderSearchResults>,
        providerResult: ProviderSearchResults
    ) {
        val existingIndex = currentResults.indexOfFirst { it.provider.id == providerResult.provider.id }
        if (existingIndex >= 0) currentResults[existingIndex] = providerResult else currentResults.add(providerResult)
    }

    private fun scheduleAutoFill(providerId: String, query: String) {
        if (autoFillJobs[providerId]?.isActive == true) return
        val job = viewModelScope.launch {
            autoFillSemaphore.withPermit {
                _loadingProviderIds.update { it + providerId }
                try {
                    repeat(2) {
                        val currentProvider = _providerResults.value.firstOrNull { it.provider.id == providerId }
                            ?: return@repeat
                        if (currentProvider.results.size >= PROVIDER_PAGE_SIZE || !currentProvider.hasMore) return@repeat

                        val fetched = ProviderPaginationManager.fetchMoreResults(providerId, query).first()
                        val unique = fetched.filter { newResult ->
                            currentProvider.results.none { it.url == newResult.url }
                        }
                        if (unique.isEmpty()) {
                            _providerResults.update { current ->
                                current.map { result ->
                                    if (result.provider.id == providerId) result.copy(hasMore = false) else result
                                }
                            }
                            return@repeat
                        }
                        unique.forEach { sessionSeenUrls.add(it.url) }
                        _providerResults.update { current ->
                            current.map { result ->
                                if (result.provider.id == providerId) {
                                    val combined = (result.results + unique).distinctBy { it.url }
                                    result.copy(
                                        results = combined,
                                        totalResults = combined.size,
                                        success = true,
                                        status = ProviderSearchStatus.RESULTS,
                                        hasMore = unique.isNotEmpty()
                                    )
                                } else result
                            }
                        }
                        updateSearchStats(_providerResults.value)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Initial results stay visible if background pagination fails.
                } finally {
                    _loadingProviderIds.update { it - providerId }
                }
            }
        }
        autoFillJobs[providerId] = job
        job.invokeOnCompletion { autoFillJobs.remove(providerId, job) }
    }

    private suspend fun finalizeAggregatedState(query: String, currentResults: List<ProviderSearchResults>) {
        runCatching {
            val aggregated = repository.aggregateSearchResults(query, currentResults)
            _uiState.update { it.copy(
                aggregatedResults = aggregated,
                totalResults = sessionSeenUrls.size,
                successfulProviders = currentResults.count { result -> result.status == ProviderSearchStatus.RESULTS },
                failedProviders = currentResults.count { result ->
                    result.status == ProviderSearchStatus.FAILED || result.status == ProviderSearchStatus.TIMED_OUT
                }
            ) }
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
        _providerResults.update { current ->
            current.map { result ->
                result.copy(
                    results = emptyList(),
                    searchTime = 0L,
                    success = false,
                    errorMessage = "Ready to search",
                    totalResults = 0,
                    hasMore = false,
                    status = ProviderSearchStatus.READY
                )
            }
        }
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
                val resolved = withTimeoutOrNull(35_000L) { extractVideoForPreview(result.url) }
                if (resolved != null && isLikelyMediaUrl(resolved.videoUrl)) {
                    _videoExtractionState.value = VideoExtractionState.Success(
                        videoUrl = resolved.videoUrl,
                        title = result.title,
                        quality = result.quality,
                        isStream = resolved.videoUrl.contains(".m3u8", true) || resolved.videoUrl.contains(".mpd", true),
                        headers = resolved.headers.ifEmpty { buildPlaybackHeaders(result.url) }
                    )
                    return@launch
                }
                _videoExtractionState.value = VideoExtractionState.Error(
                    "No direct playable stream was found. Open this result In App instead."
                )
            } catch (e: Exception) {
                _videoExtractionState.value = VideoExtractionState.Error(e.message ?: "Video extraction failed")
            }
        }
    }

    // ── DOWNLOADS ───────────────────────────────────────────────────────

    fun downloadResult(result: SearchResult) {
        viewModelScope.launch {
            try {
                val resolved = withTimeoutOrNull(35_000L) { extractVideoForPreview(result.url) }
                if (resolved != null && isLikelyMediaUrl(resolved.videoUrl)) {
                    downloadManager.downloadDirect(resolved.videoUrl, result.title, resolved.headers, result.url)
                } else {
                    _uiState.update {
                        it.copy(error = "Download unavailable: this page did not expose a downloadable media stream")
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Download failed: ${e.message ?: e.javaClass.simpleName}") }
            }
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
