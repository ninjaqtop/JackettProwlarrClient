package com.aggregatorx.app.engine.scraper

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * Item-vs-category signals learned for one provider at config time (spec §4,
 * result intelligence gate (a)) and applied at display time (gate (b)).
 *
 * Learned from the verification probe's own extracted links: which first path
 * segments the site's item/detail pages live under, and which segments its
 * category / tag / author / listing links use. Stored in
 * ScrapingConfig.classificationData so the display filter works from the
 * provider's very first search.
 */
@Serializable
data class ClassificationData(
    /** First path segments that item/detail URLs live under (e.g. "video", "watch"). */
    val itemPathPrefixes: List<String> = emptyList(),
    /** First path segments seen only on category/listing links (e.g. "genre", "tag"). */
    val categoryPathSegments: List<String> = emptyList(),
    val itemSampleCount: Int = 0,
    val categorySampleCount: Int = 0,
    val learnedAt: Long = 0L
)

object ResultClassification {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Segments that name a listing anywhere in the path on virtually every site. */
    val STRONG_CATEGORY_SEGMENTS = setOf(
        "category", "categories", "genre", "genres", "tag", "tags", "author", "authors"
    )

    /** Segments that name a listing when they lead the path. */
    val LEADING_CATEGORY_SEGMENTS = setOf(
        "browse", "filter", "sort", "topics", "topic", "archive", "archives", "page", "search"
    )

    fun parse(raw: String?): ClassificationData? =
        raw?.takeIf { it.isNotBlank() && it != "{}" }
            ?.let { runCatching { json.decodeFromString<ClassificationData>(it) }.getOrNull() }

    fun encode(data: ClassificationData): String = json.encodeToString(data)

    private fun segments(url: String): List<String> = runCatching {
        URI(url).path.orEmpty().lowercase().split('/').filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** Static URL test used during verification classification. */
    fun isCategoryLikeUrl(url: String): Boolean {
        val segs = segments(url)
        if (segs.isEmpty()) return true // the homepage / site root is navigation, not an item
        return segs.first() in LEADING_CATEGORY_SEGMENTS || segs.any { it in STRONG_CATEGORY_SEGMENTS }
    }

    /**
     * Learn signals from a verified probe page's (title, url) pairs, given the
     * verifier's classification of each pair.
     */
    fun learn(pairs: List<Pair<String, String>>, isCategory: (String, String) -> Boolean): ClassificationData {
        val itemFirst = mutableMapOf<String, Int>()
        val catFirst = mutableMapOf<String, Int>()
        var items = 0
        var cats = 0
        for ((title, url) in pairs) {
            val first = segments(url).firstOrNull() ?: continue
            if (isCategory(title, url)) {
                cats++
                catFirst[first] = (catFirst[first] ?: 0) + 1
            } else {
                items++
                itemFirst[first] = (itemFirst[first] ?: 0) + 1
            }
        }
        val itemPrefixes = itemFirst.filter { (_, n) -> n >= 2 && n * 10 >= items * 2 }.keys
        val categorySegments = catFirst.keys.filter { it !in itemFirst }
        return ClassificationData(
            itemPathPrefixes = itemPrefixes.sorted(),
            categoryPathSegments = categorySegments.sorted(),
            itemSampleCount = items,
            categorySampleCount = cats,
            learnedAt = System.currentTimeMillis()
        )
    }

    /**
     * Display-time gate: true when [url] is a category/listing/navigation link
     * for this provider. Only ever removes results — it never adds or re-ranks,
     * and it runs after (never instead of) the query-affinity display filter.
     */
    fun isCategoryResult(url: String, data: ClassificationData?): Boolean {
        val segs = segments(url)
        if (segs.isEmpty()) return true
        val first = segs.first()
        if (data != null && first in data.itemPathPrefixes) return false
        if (data != null && first in data.categoryPathSegments) return true
        return first in LEADING_CATEGORY_SEGMENTS || segs.any { it in STRONG_CATEGORY_SEGMENTS }
    }
}
