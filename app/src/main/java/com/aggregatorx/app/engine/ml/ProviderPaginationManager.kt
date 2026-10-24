package com.aggregatorx.app.engine.ml

import com.aggregatorx.app.data.model.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

data class ProviderPaginationState(
    /** Next page number to fetch (1-based). Page 1 is the initial search. */
    val page: Int = 1,
    val offset: Int = 0,
    val cursor: String = "",
    val token: String = "",
    val hasMore: Boolean = true,
    /** The provider site's own next-page URL, discovered on the last fetched
     *  page. Preferred over any guessed page-number URL when present. */
    val nextPageUrl: String? = null
)

/** One fetched page plus the pagination truth that came with it. */
data class ProviderPageFetch(
    val results: List<SearchResult>,
    val hasMore: Boolean,
    val nextPageUrl: String? = null
)

object ProviderPaginationManager {
    private val states = ConcurrentHashMap<String, ProviderPaginationState>()
    @Volatile
    private var fetcher: (suspend (String, String, ProviderPaginationState) -> ProviderPageFetch?)? = null

    fun init() = Unit

    fun configure(fetchMore: suspend (String, String, ProviderPaginationState) -> ProviderPageFetch?) {
        fetcher = fetchMore
    }

    fun reset(providerId: String) {
        states.remove(providerId)
    }

    fun current(providerId: String): ProviderPaginationState =
        states.getOrPut(providerId) { ProviderPaginationState() }

    /**
     * Record a page that was fetched outside [fetchMoreResults] (the initial
     * search or a provider refresh). Advances the next-page counter and stores
     * the real exhaustion state / next-page URL the engine discovered, instead
     * of assuming more pages exist whenever something was returned.
     */
    fun markFetched(providerId: String, count: Int, hasMore: Boolean = count > 0, nextPageUrl: String? = null) {
        val current = current(providerId)
        states[providerId] = current.copy(
            page = current.page + 1,
            offset = current.offset + count,
            hasMore = hasMore,
            nextPageUrl = nextPageUrl
        )
    }

    fun fetchMoreResults(providerId: String, query: String): Flow<ProviderPageFetch> = flow {
        val state = current(providerId)
        if (!state.hasMore) {
            emit(ProviderPageFetch(emptyList(), hasMore = false, nextPageUrl = null))
            return@flow
        }
        val fetched = fetcher?.invoke(providerId, query, state)
            ?: ProviderPageFetch(emptyList(), hasMore = false, nextPageUrl = null)
        // Only advance when the fetch actually returned a page of results, and
        // never claim more pages when the fetched page was empty or the engine
        // reported the provider exhausted.
        states[providerId] = state.copy(
            page = if (fetched.results.isNotEmpty()) state.page + 1 else state.page,
            offset = state.offset + fetched.results.size,
            hasMore = fetched.hasMore && fetched.results.isNotEmpty(),
            nextPageUrl = fetched.nextPageUrl
        )
        emit(fetched)
    }
}
