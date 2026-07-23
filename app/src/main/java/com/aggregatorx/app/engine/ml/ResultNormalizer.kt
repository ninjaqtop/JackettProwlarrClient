package com.aggregatorx.app.engine.ml

import com.aggregatorx.app.data.memory.ProviderMemoryStore
import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ResultNormalizer {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun normalize(providerId: String, providerBaseUrl: String, results: List<SearchResult>): List<SearchResult> {
        ProviderMemoryStore.getProviderContext(providerId)
        return results
            .map { result ->
                val repairedUrl = EngineUtils.normalizeUrl(result.url, providerBaseUrl)
                if (repairedUrl != result.url) {
                    ProviderMemoryStore.saveCorrection(providerId, "url", result.url, repairedUrl, 0.9f)
                }
                result.copy(url = repairedUrl)
            }
            .distinctBy { it.url }
            .also {
                if (it.isNotEmpty()) {
                    ProviderMemoryStore.updateProviderSchema(providerId, json.encodeToString(it.take(3)), 0.85f)
                }
            }
    }
}
