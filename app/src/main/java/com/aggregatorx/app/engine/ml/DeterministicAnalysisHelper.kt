package com.aggregatorx.app.engine.ml

import com.aggregatorx.app.data.model.SearchResult
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

object DeterministicAnalysisHelper {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun init() = Unit

    fun analyzeResults(rawData: String, providerId: String): Flow<AnalysisResult> = flow {
        val results = try {
            extractResultsFromJson(rawData, providerId)
        } catch (e: Exception) {
            emptyList()
        }
        val jsonOutput = buildJsonObject {
            put("results", JsonArray(results.map { result ->
                buildJsonObject {
                    put("title", JsonPrimitive(result.title))
                    put("url", JsonPrimitive(result.url))
                    result.description?.let { put("description", JsonPrimitive(it)) }
                    result.thumbnailUrl?.let { put("thumbnail", JsonPrimitive(it)) }
                    result.category?.let { put("category", JsonPrimitive(it)) }
                    result.duration?.let { put("duration", JsonPrimitive(it)) }
                    result.quality?.let { put("quality", JsonPrimitive(it)) }
                    put("confidence", JsonPrimitive(0.85f))
                }
            }))
            put("issues", JsonArray(emptyList()))
            put("pagination", buildJsonObject {
                put("type", JsonPrimitive("page"))
                put("field", JsonPrimitive("page"))
                put("nextValue", JsonPrimitive(""))
            })
        }.toString()
        emit(AnalysisResult(AnalysisMode.RESULT_PARSING, providerId, jsonOutput, 0.85f, false))
    }

    fun fixCategory(resultItem: String, providerId: String): Flow<AnalysisResult> = flow {
        val result = try {
            json.decodeFromString<SearchResult>(resultItem)
        } catch (e: Exception) {
            null
        }
        val correctedCategory = result?.category?.let { category ->
            val genericCategories = setOf(
                "action", "comedy", "drama", "horror", "thriller", "romance", "sci-fi",
                "documentary", "animation", "anime", "sports", "news", "music", "kids",
                "family", "adventure", "fantasy", "crime", "mystery", "western", "war",
                "history", "biography", "all movies", "all videos", "trending", "popular",
                "latest", "new releases", "top rated", "most viewed", "recommended"
            )
            if (category.lowercase(Locale.ROOT) !in genericCategories) category else null
        }
        val jsonOutput = buildJsonObject {
            put("original_category", JsonPrimitive(result?.category ?: ""))
            put("corrected_category", JsonPrimitive(correctedCategory ?: ""))
            put("confidence", JsonPrimitive(0.9f))
            put("reasoning", JsonPrimitive("deterministic category filtering"))
        }.toString()
        emit(AnalysisResult(AnalysisMode.CATEGORY_FIX, providerId, jsonOutput, 0.9f, false))
    }

    fun validateSavedData(data: String, providerId: String): Flow<AnalysisResult> = flow {
        val isValid = try {
            when (val element = json.parseToJsonElement(data)) {
                is JsonObject -> "results" in element || "issues" in element || "problems" in element
                is JsonArray -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
        val jsonOutput = buildJsonObject {
            put("valid", JsonPrimitive(isValid))
            put(
                "problems",
                JsonArray(if (!isValid) listOf(JsonPrimitive("Invalid JSON structure")) else emptyList())
            )
            put("suggested_fixes", JsonArray(emptyList()))
        }.toString()
        emit(AnalysisResult(AnalysisMode.DATA_VALIDATION, providerId, jsonOutput, if (isValid) 0.95f else 0.5f, false))
    }

    fun checkFreshness(newData: String, oldData: String): Flow<AnalysisResult> = flow {
        val isFresh = newData != oldData
        val jsonOutput = buildJsonObject {
            put("is_fresh", JsonPrimitive(isFresh))
            put("confidence", JsonPrimitive(if (isFresh) 1.0f else 0.0f))
            put("diff_summary", JsonPrimitive(if (isFresh) "New data differs from old" else "Data unchanged"))
        }.toString()
        emit(AnalysisResult(AnalysisMode.FRESHNESS_CHECK, "global", jsonOutput, if (isFresh) 1.0f else 0.0f, false))
    }

    fun diffResults(newData: String, oldData: String, providerId: String): Flow<AnalysisResult> = flow {
        val newResults = try {
            extractResultsFromJson(newData, providerId)
        } catch (e: Exception) {
            emptyList()
        }
        val oldResults = try {
            extractResultsFromJson(oldData, providerId)
        } catch (e: Exception) {
            emptyList()
        }
        val newUrls = newResults.map { it.url }.toSet()
        val oldUrls = oldResults.map { it.url }.toSet()
        val changedItems = newUrls - oldUrls
        val unchangedItems = newUrls intersect oldUrls
        val isFresh = changedItems.isNotEmpty()
        val jsonOutput = buildJsonObject {
            put("is_fresh", JsonPrimitive(isFresh))
            put("changed_items", JsonArray(changedItems.map { JsonPrimitive(it) }))
            put("unchanged_items", JsonArray(unchangedItems.map { JsonPrimitive(it) }))
            put("freshness_score", JsonPrimitive(if (isFresh) 1.0f else 0.0f))
        }.toString()
        emit(AnalysisResult(AnalysisMode.RESULT_DIFF, providerId, jsonOutput, if (isFresh) 1.0f else 0.0f, false))
    }

    fun repairUrls(resultItem: String, providerId: String): Flow<AnalysisResult> = flow {
        val result = try {
            json.decodeFromString<SearchResult>(resultItem)
        } catch (e: Exception) {
            null
        }
        val repairedUrl = result?.url?.let { url ->
            var cleaned = url.trim()
            if (cleaned.startsWith("javascript:", ignoreCase = true)) {
                null
            } else {
                if (!cleaned.startsWith("http")) {
                    cleaned = "https://" + cleaned
                }
                cleaned.substringBefore('?').substringBefore('#')
            }
        }
        val repairedResult = result?.let { it.copy(url = repairedUrl ?: it.url) }
        val jsonOutput = buildJsonObject {
            put("repaired_result", if (repairedResult != null) {
                buildJsonObject {
                    put("title", JsonPrimitive(repairedResult.title))
                    put("url", JsonPrimitive(repairedResult.url))
                    repairedResult.description?.let { put("description", JsonPrimitive(it)) }
                    repairedResult.thumbnailUrl?.let { put("thumbnail", JsonPrimitive(it)) }
                }
            } else {
                JsonPrimitive("")
            })
            put("flagged_links", JsonArray(emptyList()))
            put("issues", JsonArray(emptyList()))
        }.toString()
        emit(AnalysisResult(AnalysisMode.URL_REPAIR, providerId, jsonOutput, 0.9f, false))
    }

    fun checkResponseQuality(responseBody: String): Flow<AnalysisResult> = flow {
        val isValid = try {
            val lower = responseBody.lowercase(Locale.ROOT)
            val errorIndicators = listOf(
                "404", "not found", "error", "exception", "cloudflare", "just a moment",
                "checking your browser", "enable javascript", "captcha", "login", "sign in",
                "access denied", "forbidden", "blocked"
            )
            !errorIndicators.any { lower.contains(it) }
        } catch (e: Exception) {
            false
        }
        val qualityScore = if (isValid) 0.9f else 0.2f
        val jsonOutput = buildJsonObject {
            put("is_valid", JsonPrimitive(isValid))
            put("quality_score", JsonPrimitive(qualityScore))
            put("reason", JsonPrimitive(if (isValid) "Response appears valid" else "Response contains error indicators"))
        }.toString()
        emit(AnalysisResult(AnalysisMode.RESPONSE_QUALITY, "global", jsonOutput, qualityScore, false))
    }

    fun analyzePagination(responseBody: String, providerId: String): Flow<AnalysisResult> = flow {
        val lower = responseBody.lowercase(Locale.ROOT)
        val hasPagination = listOf(
            "page=", "paged=", "/page/", "?page", "&page", "next", "previous",
            "page 1", "page 2", "older", "newer", "prev", "next"
        ).any { lower.contains(it) }
        val paginationType = when {
            lower.contains("?page=") || lower.contains("&page=") -> "query_param"
            lower.contains("/page/") -> "path"
            else -> "unknown"
        }
        val jsonOutput = buildJsonObject {
            put("pagination_type", JsonPrimitive(paginationType))
            put("next_value", JsonPrimitive(""))
            put("has_more", JsonPrimitive(hasPagination))
            put("confidence", JsonPrimitive(if (hasPagination) 0.85f else 0.2f))
        }.toString()
        emit(AnalysisResult(AnalysisMode.PAGINATION_ANALYSIS, providerId, jsonOutput, if (hasPagination) 0.85f else 0.2f, false))
    }

    private fun extractResultsFromJson(rawData: String, providerId: String): List<SearchResult> {
        fun processElement(providerId: String, results: MutableList<SearchResult>, element: JsonElement, depth: Int = 0) {
            if (depth > 10) return
            when (element) {
                is JsonArray -> {
                    for (child in element) {
                        processElement(providerId, results, child, depth + 1)
                    }
                }
                is JsonObject -> {
                    val title = (element["title"] as? JsonPrimitive)?.content ?: ""
                    val url = (element["url"] as? JsonPrimitive)?.content
                        ?: (element["link"] as? JsonPrimitive)?.content
                        ?: (element["href"] as? JsonPrimitive)?.content
                    if (title.isNotBlank() && !url.isNullOrBlank()) {
                        val description = (element["description"] as? JsonPrimitive)?.content
                        val thumbnail = (element["thumbnail"] as? JsonPrimitive)?.content
                            ?: (element["image"] as? JsonPrimitive)?.content
                        val category = (element["category"] as? JsonPrimitive)?.content
                        val duration = (element["duration"] as? JsonPrimitive)?.content
                        val quality = (element["quality"] as? JsonPrimitive)?.content
                        results.add(
                            SearchResult(
                                providerId = providerId,
                                providerName = providerId,
                                title = title,
                                url = url,
                                description = description,
                                thumbnailUrl = thumbnail,
                                category = category,
                                duration = duration,
                                quality = quality,
                                relevanceScore = 85.0f
                            )
                        )
                    }
                    for (value in element.values) {
                        processElement(providerId, results, value, depth + 1)
                    }
                }
                else -> Unit
            }
        }
        return try {
            val rootElement = json.parseToJsonElement(rawData)
            val results = mutableListOf<SearchResult>()
            processElement(providerId, results, rootElement)
            results.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
