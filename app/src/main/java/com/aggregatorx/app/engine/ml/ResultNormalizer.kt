package com.aggregatorx.app.engine.ml

import com.aggregatorx.app.data.memory.ProviderMemoryStore
import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.engine.scraper.SearchResultFilter
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder

object ResultNormalizer {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun normalize(providerId: String, providerBaseUrl: String, results: List<SearchResult>): List<SearchResult> {
        val categoryMappings = loadCategoryMappings(providerId)
        return results
            .mapNotNull { result -> normalizeLocal(providerId, providerBaseUrl, result, categoryMappings) }
            .filter { it.title.length >= 3 }
            .filterNot { isGenericCategoryTitle(it) }
            .filterNot { isDurationOnlyTitle(it) }
            .distinctBy { it.url }
            .take(50)
    }

    private suspend fun loadCategoryMappings(providerId: String): Map<String, String> {
        val schema = runCatching { ProviderMemoryStore.getSchema(providerId) }.getOrNull() ?: return emptyMap()
        return runCatching {
            json.parseToJsonElement(schema.categoryMappings)
                .jsonObject
                .mapValues { (_, value) -> value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
    }

    private fun normalizeLocal(
        providerId: String,
        providerBaseUrl: String,
        result: SearchResult,
        categoryMappings: Map<String, String>
    ): SearchResult? {
        val title = result.title.trim()
        if (title.length < 3 || result.url.isBlank()) return null

        val normalizedUrl = EngineUtils.normalizeUrl(result.url, providerBaseUrl)
        if (normalizedUrl.isBlank() || normalizedUrl.contains("javascript:", ignoreCase = true)) return null

        val resolvedTitle = if (isDurationOnlyTitle(result) || isGenericCategoryTitle(result)) {
            extractTitleFromUrl(normalizedUrl) ?: return null
        } else {
            title
        }

        if (resolvedTitle.length < 3 ||
            SearchResultFilter.isLikelyDuration(resolvedTitle) ||
            SearchResultFilter.isLikelyCategory(resolvedTitle)
        ) return null

        if (normalizedUrl != result.url) {
            scope.launch {
                runCatching { ProviderMemoryStore.saveCorrection(providerId, "url", result.url, normalizedUrl, 0.92f) }
            }
        }

        val normalizedThumb = result.thumbnailUrl
            ?.takeIf { it.isNotBlank() && !it.startsWith("data:", ignoreCase = true) }
            ?.let { EngineUtils.normalizeUrl(it, providerBaseUrl) }

        val mappedCategory = result.category?.let { categoryMappings[it] ?: it }
        val correctedCategory = mappedCategory?.takeIf { !SearchResultFilter.isLikelyCategory(it) }
        return result.copy(
            title = resolvedTitle,
            url = normalizedUrl,
            thumbnailUrl = normalizedThumb,
            category = correctedCategory
        )
    }

    private fun isDurationOnlyTitle(result: SearchResult): Boolean {
        return SearchResultFilter.isLikelyDuration(result.title)
    }

    private fun isGenericCategoryTitle(result: SearchResult): Boolean {
        if (SearchResultFilter.isLikelyCategory(result.title)) return true
        val categoryUrlPatterns = listOf(
            "/genre/", "/category/", "/browse/", "/filter/", "/tags/",
            "/type/", "/sort/", "/order/", "?genre=", "?category=",
            "?type=", "/all-", "/list/genre", "/movies/genre"
        )
        return categoryUrlPatterns.any { result.url.lowercase().contains(it) }
    }

    private fun extractTitleFromUrl(url: String): String? {
        return runCatching {
            val rawPath = URI(url).rawPath ?: ""
            val trimmedPath = rawPath.trimEnd('/')
            if (trimmedPath.isBlank()) return@runCatching null

            val genericSegments = setOf(
                "watch", "video", "videos", "movie", "movies", "detail", "details",
                "view", "play", "embed", "download", "item", "result", "search",
                "index", "home", "page", "channel", "category", "genre"
            )
            // PORTING-UNCERTAIN: the jadx loop over the reversed segments is duplicated/
            // mangled; interpreted as firstOrNull with this predicate (see porting report).
            val candidate = trimmedPath.split('/').asReversed().firstOrNull { segment ->
                val cleaned = Regex("(?i)\\.(html?|php|aspx?)$")
                    .replace(URLDecoder.decode(segment, "UTF-8"), "")
                    .trim()
                cleaned.isNotBlank() && cleaned.lowercase() !in genericSegments
            } ?: return@runCatching null

            val title = URLDecoder.decode(candidate, "UTF-8")
                .replace(Regex("(?i)\\.(html?|php|aspx?)$"), "")
                .replace(Regex("[-_]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            val validTitle = if (title.length >= 3 &&
                title.any { it.isLetter() } &&
                !SearchResultFilter.isLikelyDuration(title) &&
                !SearchResultFilter.isLikelyCategory(title)
            ) title else null

            validTitle?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }.getOrNull()
    }

    suspend fun normalizeResponse(providerId: String, providerBaseUrl: String, rawResponse: String): List<SearchResult> {
        val results = try {
            extractResultsFromJson(rawResponse, providerId, providerBaseUrl)
        } catch (e: Exception) {
            emptyList()
        }
        return normalize(providerId, providerBaseUrl, results)
    }

    private fun extractResultsFromJson(rawResponse: String, providerId: String, baseUrl: String): List<SearchResult> {
        return try {
            val root = json.parseToJsonElement(rawResponse)
            val results = mutableListOf<SearchResult>()

            fun JsonObject.stringValue(vararg keys: String): String? {
                return keys.firstNotNullOfOrNull { key -> (this[key] as? JsonPrimitive)?.content }
            }

            fun processElement(element: JsonElement, depth: Int = 0) {
                if (depth > 10) return
                if (element is JsonArray) {
                    element.forEach { processElement(it, depth + 1) }
                    return
                }
                if (element !is JsonObject) return

                val title = element.stringValue("title") ?: ""
                val rawUrl = element.stringValue("url", "link", "href")
                if (title.isNotBlank() && !rawUrl.isNullOrBlank()) {
                    val normalizedUrl = EngineUtils.normalizeUrl(rawUrl, baseUrl)
                    if (!normalizedUrl.startsWith("http")) return
                    results.add(
                        SearchResult(
                            providerId = providerId,
                            providerName = providerId,
                            title = title,
                            url = normalizedUrl,
                            description = element.stringValue("description"),
                            thumbnailUrl = element.stringValue("thumbnail", "image"),
                            category = element.stringValue("category"),
                            duration = element.stringValue("duration"),
                            quality = element.stringValue("quality"),
                            relevanceScore = (element.stringValue("confidence")?.toFloatOrNull() ?: 0.81f) * 100f
                        )
                    )
                }
                element.values.forEach { processElement(it, depth + 1) }
            }

            processElement(root)
            results.distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun isFresh(providerId: String, newResults: List<SearchResult>, oldResults: List<SearchResult>): Boolean {
        if (newResults.isEmpty() && oldResults.isEmpty()) return false
        if (newResults.isEmpty() || oldResults.isEmpty()) return true
        val newUrls = newResults.map { it.url }.toSet()
        val oldUrls = oldResults.map { it.url }.toSet()
        return (newUrls - oldUrls).size.toFloat() / maxOf(newUrls.size, 1) >= 0.5f
    }

    suspend fun diffFresh(providerId: String, newResults: List<SearchResult>, oldResults: List<SearchResult>): Boolean {
        return isFresh(providerId, newResults, oldResults)
    }
}
