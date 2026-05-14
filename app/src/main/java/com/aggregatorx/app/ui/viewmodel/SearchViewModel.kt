package com.aggregatorx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.data.repository.AggregatorRepository
import com.aggregatorx.app.engine.media.*
import com.aggregatorx.app.engine.token.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    private val _likedUrls = MutableStateFlow<Set<String>>(emptySet())
    val likedUrls: StateFlow<Set<String>> = _likedUrls.asStateFlow()

    private val _tokenResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val tokenResults: StateFlow<List<SearchResult>> = _tokenResults.asStateFlow()

    private val _myAiResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val myAiResults: StateFlow<List<SearchResult>> = _myAiResults.asStateFlow()

    private val _isDiscoveryPaused = MutableStateFlow(false)
    val isDiscoveryPaused: StateFlow<Boolean> = _isDiscoveryPaused.asStateFlow()

    private val _providerPages = MutableStateFlow<Map<String, Int>>(emptyMap())

    // --- THE SESSION SEEN SET ---
    // Prevents duplicates across the three tabs and pagination loops.
    private val sessionSeenUrls = mutableSetOf<String>()
    private val videoPreviewCache = java.util.concurrent.ConcurrentHashMap<String, VideoPreviewResult>()
    private var currentSearchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.getRecentSearches().collect { searches ->
                _uiState.update { it.copy(recentSearches = searches) }
            }
        }
        viewModelScope.launch { _likedUrls.value = repository.getAllLikedUrls() }
    }

    fun search(isLoadMore: Boolean = false) {
        val query = _uiState.value.query.trim()
        if (query.isEmpty() || _isDiscoveryPaused.value) return

        currentSearchJob?.cancel()

        currentSearchJob = viewModelScope.launch {
            // --- LOOP 1: INITIALIZATION ---
            if (!isLoadMore) {
                sessionSeenUrls.clear()
                repository.clearSearchCache()
                videoPreviewCache.clear()
                _providerResults.value = emptyList()
                _tokenResults.value = emptyList()
                _myAiResults.value = emptyList()
            }

            _uiState.update { it.copy(isSearching = true, currentSearchQuery = query, error = null) }
            val currentResults = if (isLoadMore) _providerResults.value.toMutableList() else mutableListOf()

            // --- LOOP 2: ACTUAL RESULTS (Provider Scrape) ---
            repository.searchAllProviders(query, pages = _providerPages.value)
                .catch { e -> if (currentResults.isEmpty()) _uiState.update { it.copy(error = e.message) } }
                .collect { providerResult ->
                    // Filter out already seen URLs (across tabs/pages)
                    val uniqueNewOnes = providerResult.results.filter { sessionSeenUrls.add(it.url) }
                    
                    if (uniqueNewOnes.isNotEmpty()) {
                        val filteredResult = providerResult.copy(results = uniqueNewOnes)
                        currentResults.add(filteredResult)
                        _providerResults.value = currentResults.toList()

                        try {
                            val aggregated = repository.aggregateSearchResults(query, currentResults)
                            _uiState.update { it.copy(
                                aggregatedResults = aggregated,
                                totalResults = sessionSeenUrls.size,
                                successfulProviders = aggregated.successfulProviders
                            ) }
                        } catch (_: Exception) {}
                    }
                }

            _uiState.update { it.copy(isSearching = false, searchCompleted = true) }

            // --- LOOP 3: PREFERENCE RANKING (AI Tab) ---
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

            // --- LOOP 4: RELATED DISCOVERY (Token Tab) ---
            launch {
                val tokensFound = mutableListOf<SearchResult>()
                currentResults.filter { it.success }.forEach { p ->
                    try {
                        tokenManager.replayTokensForSearch(p.provider.baseUrl, query).forEach { url ->
                            // ONLY add if not already in Actual Results or AI tab
                            if (!sessionSeenUrls.contains(url)) {
                                tokensFound.add(SearchResult(
                                    providerId = p.provider.id,
                                    providerName = "${p.provider.name} (Related)",
                                    title = "Related: ${url.takeLast(30)}",
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

    fun nextProviderPage(providerId: String) {
        _providerPages.update { pages ->
            val current = pages[providerId] ?: 0
            pages + (providerId to current + 1)
        }
        search(isLoadMore = true)
    }

    fun panicRefresh() {
        _providerPages.value = emptyMap()
        search(isLoadMore = false)
        @Suppress("ExplicitGarbageCollectionCall")
        System.gc()
    }

    fun toggleDiscoveryPause() {
        _isDiscoveryPaused.update { !it }
        if (!_isDiscoveryPaused.value) search(isLoadMore = true) else currentSearchJob?.cancel()
    }

    // ... Rest of your ToggleLike, Video Extraction, and Download methods remain identical ...
}

data class SearchUiState(
    val query: String = "",
    val currentSearchQuery: String = "",
    val isSearching: Boolean = false,
    val searchCompleted: Boolean = false,
    val aggregatedResults: AggregatedSearchResults? = null,
    val totalResults: Int = 0,
    val successfulProviders: Int = 0,
    val recentSearches: List<SearchHistoryEntry> = emptyList(),
    val error: String? = null
)
