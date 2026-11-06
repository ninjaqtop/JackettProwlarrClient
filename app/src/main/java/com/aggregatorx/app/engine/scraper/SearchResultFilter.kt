package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.data.model.SearchResult
import com.aggregatorx.app.engine.nlp.ProcessedQuery
import java.util.regex.Pattern

object SearchResultFilter {
    private const val MIN_TITLE_LENGTH = 3
    private const val MIN_RELEVANCE_SCORE = 5f

    private val genericCategoryNames = setOf(
        "action", "comedy", "drama", "horror", "thriller", "romance",
        "sci-fi", "scifi", "documentary", "animation", "anime", "sports", "news",
        "music", "kids", "family", "adventure", "fantasy", "crime",
        "mystery", "western", "war", "history", "biography", "all movies",
        "all videos", "all", "trending", "popular", "latest", "new releases",
        "new", "top rated", "most viewed", "recommended", "featured", "picks",
        "staff picks", "editor's choice", "recent", "upcoming", "now playing",
        "in theaters", "box office", "top", "best", "hot", "fresh", "hd", "4k",
        "1080p", "720p", "bluray", "dvd", "tv shows", "movies", "series", "episodes"
    )

    private val durationPatterns = listOf(
        Pattern.compile("^\\d{1,2}:\\d{2}:\\d{2}$"),
        Pattern.compile("^\\d{1,2}h[\\s]?\\d{1,2}m?$"),
        Pattern.compile("^\\d{1,3}[\\s]?min(utes?)?$"),
        Pattern.compile("^\\d{1,2}[\\s]?sec(onds?)?$"),
        Pattern.compile("^\\d{1,2}:\\d{2}$"),
        Pattern.compile("^\\d{1,3}[\\s]?hours?$"),
        Pattern.compile("^\\d+h\\d+m$"),
        Pattern.compile("^PT\\d+H\\d+M\\d+S$")
    )

    private val categoryTitlePatterns = listOf(
        Pattern.compile("^\\w+\\s+-\\s*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^\\w+\\s+category$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^browse\\s+\\w+$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^all\\s+\\w+$", Pattern.CASE_INSENSITIVE)
    )

    private val MATCH_STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "in", "on", "at", "to", "for", "of",
        "with", "by", "from", "is", "it", "this", "that", "are", "was", "were",
        "be", "been", "being", "have", "has", "had", "do", "does", "did",
        "will", "would", "could", "should", "may", "might", "can", "shall",
        "not", "no", "so", "if", "then"
    )

    fun filterResultsForDisplay(
        results: List<SearchResult>,
        query: String,
        processedQuery: ProcessedQuery? = null,
        limit: Int = 20
    ): List<SearchResult> {
        val structurallyValid = results
            .filter { isStructurallyValid(it, query) }
            .distinctBy { it.url }

        if (structurallyValid.isEmpty()) return emptyList()

        val relevant = structurallyValid.filter { result ->
            hasQueryAffinity(result, query, processedQuery)
        }

        val selected = relevant.ifEmpty {
            structurallyValid.filter { it.relevanceScore >= MIN_RELEVANCE_SCORE }
        }
        return selected
            .sortedByDescending { it.relevanceScore }
            .take(limit)
    }

    fun filterAndCleanResults(
        results: List<SearchResult>,
        query: String,
        processedQuery: ProcessedQuery? = null
    ): List<SearchResult> {
        return results
            .filter { isStructurallyValid(it, query) }
            .distinctBy { it.url }
    }

    fun hasQueryAffinity(
        result: SearchResult,
        query: String,
        processedQuery: ProcessedQuery? = null
    ): Boolean {
        val contentTokens = tokenize("${result.title} ${result.description ?: ""}")

        val queryVariants = (listOf(query) + (processedQuery?.searchQueries ?: emptyList()))
            .map { tokenize(it) }
            .filter { it.isNotEmpty() }

        if (queryVariants.isEmpty()) return true

        if (queryVariants.any { countMatchedTerms(it, contentTokens) >= minimumMatchedTerms(it.size) }) {
            return true
        }

        val conceptTokens = processedQuery?.let { pq ->
            val allConceptTerms = pq.conceptTerms + listOf(
                pq.concepts.subjects,
                pq.concepts.actions,
                pq.concepts.descriptors,
                pq.concepts.contexts,
                pq.concepts.emotions,
                pq.concepts.compoundConcepts
            ).flatten()
            allConceptTerms.flatMap { tokenize(it) }.distinct()
        }.orEmpty()

        if (conceptTokens.isNotEmpty() && countMatchedTerms(conceptTokens, contentTokens) >= 1) {
            return true
        }

        return result.relevanceScore >= MIN_RELEVANCE_SCORE &&
            queryVariants.any { countMatchedTerms(it, contentTokens) >= 1 }
    }

    fun isLikelyCategory(title: String): Boolean {
        val normalized = title.lowercase().trim()
        if (normalized in genericCategoryNames) return true
        // PORTING-UNCERTAIN: jadx renders the pattern loop without a break and with a
        // trailing reset of the result flag; interpreted as any{} (see porting report).
        return categoryTitlePatterns.any { it.matcher(normalized).matches() }
    }

    fun isLikelyDuration(title: String): Boolean {
        val normalized = title.lowercase().replace(Regex("\\s+"), " ").trim()
        if (durationPatterns.any { it.matcher(normalized).matches() }) return true

        val timePattern = Regex("(?<![\\p{L}\\p{N}])(?:\\d{1,2}:)?\\d{1,3}\\s*:\\s*\\d{2}(?::\\d{2})?(?![\\p{L}\\p{N}])")
        val hoursPattern = Regex(
            "(?<![\\p{L}\\p{N}])\\d{1,2}\\s*(?:h|hours?)\\s*\\d{1,2}\\s*(?:m|min(?:utes?)?)?(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE
        )
        if (!timePattern.containsMatchIn(normalized) && !hoursPattern.containsMatchIn(normalized)) {
            return false
        }

        val remainder = normalized
            .replace(timePattern, " ")
            .replace(hoursPattern, " ")
            .replace(Regex("\\b(?:sd|hd|fhd|uhd|4k|8k|\\d{3,4}p|runtime|duration|length)\\b"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

        if (remainder.isNotEmpty()) {
            val tokens = remainder.split(' ')
            // PORTING-UNCERTAIN: jadx renders this token loop with the same accumulator
            // anomaly as isLikelyCategory; interpreted as all{} (see porting report).
            if (!tokens.all { token -> token.length <= 2 && token.all { it.isDigit() } }) {
                return false
            }
        }
        return true
    }

    private fun isStructurallyValid(result: SearchResult, query: String): Boolean {
        if (result.title.length < MIN_TITLE_LENGTH) return false

        val urlLower = result.url.lowercase()
        val titleLower = result.title.lowercase().trim()

        if (isLikelyCategory(titleLower) || isDurationOnlyTitle(result)) return false

        val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        if (queryWords.isNotEmpty()) {
            val urlMatchesQuery = queryWords.any { urlLower.contains(it) }
            val titleMatchesQuery = queryWords.any { titleLower.contains(it) }
            if (urlMatchesQuery && !titleMatchesQuery) {
                if (result.thumbnailUrl.isNullOrEmpty()) return false
            }
        }
        return true
    }

    private fun isDurationOnlyTitle(result: SearchResult): Boolean {
        val title = result.title.lowercase().trim()
        return durationPatterns.any { it.matcher(title).matches() }
    }

    private fun tokenize(value: String): List<String> {
        return Regex("[\\p{L}\\p{N}]+")
            .findAll(value.lowercase())
            .map { it.value }
            .filter { it.length >= 2 && it !in MATCH_STOP_WORDS }
            .distinct()
            .toList()
    }

    private fun minimumMatchedTerms(termCount: Int): Int {
        return if (termCount <= 3) 1 else maxOf(2, (termCount + 3) / 4)
    }

    private fun countMatchedTerms(terms: List<String>, contentTokens: List<String>): Int {
        return terms.distinct().count { term ->
            contentTokens.any { token ->
                token == term ||
                    (term.length >= 4 && token.startsWith(term) && token.length - term.length <= 2)
            }
        }
    }
}
