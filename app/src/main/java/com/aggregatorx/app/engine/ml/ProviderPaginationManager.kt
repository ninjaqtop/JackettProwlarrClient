package com.aggregatorx.app.engine.ml

import com.aggregatorx.app.data.model.SearchResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap

data class ProviderPaginationState(
    val page: Int = 1,
    val offset: Int = 0,
    val cursor: String = "",
    val token: String = "",
    val hasMore: Boolean = true
)

object ProviderPaginationManager {
    private val states = ConcurrentHashMap<String, ProviderPaginationState>()

    fun init() = Unit

    fun reset(providerId: String) {
        states.remove(providerId)
    }

    fun current(providerId: String): ProviderPaginationState =
        states.getOrPut(providerId) { ProviderPaginationState() }

    fun markFetched(providerId: String, count: Int) {
        val current = current(providerId)
        states[providerId] = current.copy(page = current.page + 1, offset = current.offset + count, hasMore = count > 0)
    }

    fun fetchMoreResults(providerId: String, query: String): Flow<List<SearchResult>> = flow {
        emit(emptyList())
    }
}
