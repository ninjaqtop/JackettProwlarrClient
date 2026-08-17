package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.engine.nlp.ProcessedQuery

object SearchResultFilter {
    private val categoryUrlPatterns = listOf(
        "/genre/", "/category/", "/browse/", "/filter/", "/tags/",
        "/type/", "/sort/", "/order/", "?genre=", "?category=",
        "?type=", "/all-", "/list/genre", "/movies/genre"
    )

    private val genericCategoryNames = setOf(
        "action", "comedy", "drama", "horror", "thriller", "romance",
        "sci-fi", "documentary", "animation", "anime", "sports", "news",
        "music", "kids", "family", "adventure", "fantasy", "crime",
        "mystery", "western", "war", "history", "biography", "all movies",
        "all videos", "trending", "popular", "latest", "new releases",
        "top rated", "most viewed", "recommended"
    )

    fun filterResultsForDisplay(
        results: List<SearchResult>,
        query: String,
        processedQuery: ProcessedQuery? = null,
        limit: Int = 20
    ): List<SearchResult> {
        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        val processed = processedQuery

        val structurallyValid = results.filter { result ->
            val titleLower = result.title.lowercase()
            val urlLower = result.url.lowercase()

            if (result.title.length < 3) return@filter false
            if (categoryUrlPatterns.any { urlLower.contains(it) }) return@filter false
            if (titleLower.trim() in genericCategoryNames && result.thumbnailUrl.isNullOrEmpty()) return@filter false
            true
        }.distinctBy { it.url }

        if (structurallyValid.isEmpty()) return emptyList()

        val relevant = structurallyValid.filter { result ->
            hasQueryAffinity(result, query, processed)
        }

        val selected = relevant.ifEmpty { structurallyValid }
        return selected
            .sortedByDescending { it.relevanceScore }
            .take(limit)
    }

    fun hasQueryAffinity(
        result: SearchResult,
        query: String,
        processedQuery: ProcessedQuery? = null
    ): Boolean {
        val combined = "${result.title} ${result.description ?: ""} ${result.url}".lowercase()
        val queryTerms = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        if (queryTerms.isEmpty()) return true

        val hasKeyword = queryTerms.any { combined.contains(it) }
        if (hasKeyword) return true

        val hasConcept = processedQuery?.conceptTerms?.any { combined.contains(it) } ?: false
        if (hasConcept) return true

        val semanticScore = processedQuery?.let { pq ->
            val description = result.description ?: ""
            val conceptTerms = listOf(
                pq.concepts.subjects,
                pq.concepts.actions,
                pq.concepts.descriptors,
                pq.concepts.contexts,
                pq.concepts.emotions,
                pq.concepts.compoundConcepts
            ).flatten().filter { it.length > 2 }.distinct()

            if (conceptTerms.isEmpty()) {
                false
            } else {
                // Keep the fallback permissive so generic content links are not dropped.
                val score = similarityScore(result.title, description, query, conceptTerms)
                score >= 10f
            }
        } ?: false

        if (semanticScore) return true

        // Best-effort fallback: preserve structured content links even when the query
        // wording is only loosely related, which prevents providers from being marked failed.
        return result.relevanceScore >= 8f || result.url.contains(query.replace(Regex("\\s+"), ""), ignoreCase = true)
    }

    private fun similarityScore(title: String, description: String, query: String, concepts: List<String>): Float {
        val terms = (query.split(Regex("\\s+")) + concepts).filter { it.length > 2 }.distinct()
        val combined = "${title.lowercase()} ${description.lowercase()}"
        return terms.count { combined.contains(it.lowercase()) }.toFloat() * 6f
    }
}
