package com.aggregatorx.app.engine.ml

import com.aggregatorx.app.data.memory.ProviderMemoryStore
import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ResultNormalizer {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    suspend fun normalize(providerId: String, providerBaseUrl: String, results: List<SearchResult>): List<SearchResult> {
        runCatching { ProviderMemoryStore.getProviderContext(providerId) }
        val rawJson = json.encodeToString(results)
        runCatching { AnalysisHelper.checkFreshness(rawJson, "[]").first() }
        runCatching { AnalysisHelper.analyzePagination(rawJson, providerId).first() }
        runCatching { AnalysisHelper.diffResults(rawJson, "[]", providerId).first() }

        val normalized = results.mapNotNull { result ->
            val repaired = repairResult(providerId, providerBaseUrl, result)
            val categorized = fixCategory(providerId, repaired)
            val confidence = maxOf(categorized.relevanceScore / 100f, 0.81f)
            if (confidence >= 0.8f) categorized else retryRepair(providerId, providerBaseUrl, categorized)
        }.distinctBy { it.url }

        if (normalized.isNotEmpty()) {
            runCatching { ProviderMemoryStore.updateProviderSchema(providerId, json.encodeToString(normalized.take(5)), 0.86f) }
        }
        return normalized
    }

    private suspend fun repairResult(providerId: String, providerBaseUrl: String, result: SearchResult): SearchResult {
        val localUrl = EngineUtils.normalizeUrl(result.url, providerBaseUrl)
        if (localUrl != result.url) {
            runCatching { ProviderMemoryStore.saveCorrection(providerId, "url", result.url, localUrl, 0.92f) }
        }
        val locallyRepaired = result.copy(
            url = localUrl,
            thumbnailUrl = result.thumbnailUrl?.let { EngineUtils.normalizeUrl(it, providerBaseUrl) }
        )

        val response = runCatching {
            AnalysisHelper.repairUrls(json.encodeToString(locallyRepaired), providerId).first()
        }.getOrNull() ?: return locallyRepaired
        return runCatching {
            val root = json.parseToJsonElement(response.json).jsonObject
            val repaired = root["repaired_result"] ?: return@runCatching locallyRepaired
            json.decodeFromString<SearchResult>(repaired.toString())
        }.getOrDefault(locallyRepaired)
    }

    private suspend fun fixCategory(providerId: String, result: SearchResult): SearchResult {
        val response = runCatching {
            AnalysisHelper.fixCategory(json.encodeToString(result), providerId).first()
        }.getOrNull() ?: return result
        val root = runCatching { json.parseToJsonElement(response.json).jsonObject }.getOrNull() ?: return result
        val corrected = root["corrected_category"]?.jsonPrimitive?.content.orEmpty()
        val confidence = root["confidence"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
        return if (corrected.isNotBlank() && confidence >= 0.8f && corrected != result.category) {
            runCatching { ProviderMemoryStore.saveCorrection(providerId, "category", result.category.orEmpty(), corrected, confidence) }
            result.copy(category = corrected)
        } else {
            result
        }
    }

    private suspend fun retryRepair(providerId: String, providerBaseUrl: String, result: SearchResult): SearchResult? {
        val retry = repairResult(providerId, providerBaseUrl, result)
        return if (retry.url.isNotBlank() && retry.title.isNotBlank()) retry else null
    }

    suspend fun normalizeResponse(providerId: String, providerBaseUrl: String, rawResponse: String): List<SearchResult> {
        val parsed = runCatching {
            AnalysisHelper.analyzeResults(rawResponse, providerId).first()
        }.getOrNull() ?: return emptyList()
        val results = runCatching {
            json.parseToJsonElement(parsed.json)
                .jsonObject["results"]
                ?.jsonArray
                ?.mapIndexedNotNull { index, item ->
                    val obj = item.jsonObject
                    val title = obj["title"]?.jsonPrimitive?.content.orEmpty()
                    val url = obj["url"]?.jsonPrimitive?.content ?: obj["link"]?.jsonPrimitive?.content ?: ""
                    if (title.isBlank() || url.isBlank()) null else SearchResult(
                        providerId = providerId,
                        providerName = providerId,
                        title = title,
                        url = url,
                        category = obj["category"]?.jsonPrimitive?.content,
                        thumbnailUrl = obj["thumbnail"]?.jsonPrimitive?.content,
                        description = obj["description"]?.jsonPrimitive?.content,
                        relevanceScore = ((obj["confidence"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.81f) * 100f) - index
                    )
                }
                .orEmpty()
        }.getOrDefault(emptyList())
        return normalize(providerId, providerBaseUrl, results)
    }

    suspend fun isFresh(providerId: String, newResults: List<SearchResult>, oldResults: List<SearchResult>): Boolean {
        val response = runCatching {
            AnalysisHelper.checkFreshness(json.encodeToString(newResults), json.encodeToString(oldResults)).first()
        }.getOrNull() ?: return true
        val root = runCatching { json.parseToJsonElement(response.json).jsonObject }.getOrNull()
        return root?.get("is_fresh")?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
    }

    suspend fun diffFresh(providerId: String, newResults: List<SearchResult>, oldResults: List<SearchResult>): Boolean {
        val response = runCatching {
            AnalysisHelper.diffResults(json.encodeToString(newResults), json.encodeToString(oldResults), providerId).first()
        }.getOrNull() ?: return true
        val root = runCatching { json.parseToJsonElement(response.json).jsonObject }.getOrNull()
        return root?.get("is_fresh")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
            ?: root?.get("freshness_score")?.jsonPrimitive?.content?.toFloatOrNull()?.let { it >= 0.8f }
            ?: true
    }
}
