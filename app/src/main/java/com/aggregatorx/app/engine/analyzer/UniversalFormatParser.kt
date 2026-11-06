package com.aggregatorx.app.engine.analyzer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Universal Format Parser - Multi-Format Website Data Extractor
 * 
 * Handles diverse website formats including:
 * - Traditional HTML sites with various DOM structures
 * - JSON-based APIs and embedded JSON data
 * - Server-side rendered SPA frameworks (Next.js, Nuxt.js)
 * - Data attributes and meta tags
 * - Microdata and structured data (JSON-LD, OpenGraph, Schema.org)
 * - XML/RSS feeds
 * - Custom data formats
 * 
 * Automatically detects the format and extracts content accordingly.
 */
@Singleton
class UniversalFormatParser @Inject constructor() {
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
        coerceInputValues = true
    }
    
    companion object {
        // Framework detection patterns
        private val NEXTJS_PATTERN = Regex("__NEXT_DATA__|_next/static|/_next/")
        private val NUXTJS_PATTERN = Regex("__NUXT__|_nuxt/|window\\.__NUXT__")
        private val REACT_PATTERN = Regex("data-reactroot|__REACT_DEVTOOLS|reactProps")
        private val VUE_PATTERN = Regex("data-v-|__VUE__|v-app")
        private val ANGULAR_PATTERN = Regex("ng-app|ng-controller|\\[ng-")
        
        // JSON-LD types of interest
        private val MEDIA_SCHEMA_TYPES = listOf(
            "Movie", "TVSeries", "TVEpisode", "VideoObject", "MediaObject",
            "Article", "SearchResultsPage", "ItemList", "WebPage"
        )

        // Pre-compiled patterns that were rebuilt on every call below.
        // Regex instances are immutable and only read, so sharing is equivalent.
        private val NUXT_WINDOW_PATTERN = Regex("window\\.__NUXT__\\s*=\\s*(\\{.+\\})")
        private val NUXT_FALLBACK_PATTERN = Regex("__NUXT__\\s*=\\s*(\\{.+\\})")
        private val SPA_STATE_PATTERNS = listOf(
            Regex("window\\.__INITIAL_STATE__\\s*=\\s*(\\{.+?\\});"),
            Regex("window\\.__PRELOADED_STATE__\\s*=\\s*(\\{.+?\\});"),
            Regex("window\\.\\w+State\\s*=\\s*(\\{.+?\\});")
        )
        private val EMBEDDED_JSON_PATTERNS = listOf(
            Regex("var\\s+(videos?|movies?|items?|results?|data)\\s*=\\s*(\\[\\{.+?\\}\\]);", RegexOption.DOT_MATCHES_ALL),
            Regex("const\\s+(videos?|movies?|items?|results?|data)\\s*=\\s*(\\[\\{.+?\\}\\]);", RegexOption.DOT_MATCHES_ALL),
            Regex("\"(videos?|movies?|items?|results?)\"\\s*:\\s*(\\[\\{.+?\\}\\])", RegexOption.DOT_MATCHES_ALL)
        )
        private val NUMBER_REGEX = Regex("\\d+\\.?\\d*")
        private val DURATION_REGEX = Regex("(\\d{1,2}:\\d{2}(?::\\d{2})?)")
        private val YEAR_REGEX = Regex("\\b(19|20)\\d{2}\\b")
        private val RATING_VALUE_REGEX = Regex("(\\d\\.\\d|\\d{1,2})")
    }
    
    /**
     * Main parsing entry point - auto-detects format and extracts data
     */
    suspend fun parseContent(
        document: Document, 
        url: String
    ): UniversalParseResult = withContext(Dispatchers.IO) {
        
        // Detect format
        val format = detectFormat(document, url)
        
        // Extract based on detected format
        val results = mutableListOf<ExtractedContent>()
        val metadata = mutableMapOf<String, String>()
        
        // Always try multiple extraction methods for comprehensive results
        
        // 1. Framework-specific extraction
        when (format) {
            DataFormat.NEXTJS -> extractNextJsData(document)?.let { results.addAll(it) }
            DataFormat.NUXTJS -> extractNuxtJsData(document)?.let { results.addAll(it) }
            DataFormat.SPA_REACT, DataFormat.SPA_VUE, DataFormat.SPA_ANGULAR -> 
                extractSPAData(document)?.let { results.addAll(it) }
            else -> {}
        }
        
        // 2. JSON-LD and structured data
        extractJsonLdData(document)?.let { results.addAll(it) }
        
        // 3. OpenGraph and meta tags
        extractMetaTags(document, metadata)
        
        // 4. Embedded JSON objects
        extractEmbeddedJson(document)?.let { results.addAll(it) }
        
        // 5. Data attributes
        extractDataAttributes(document)?.let { results.addAll(it) }
        
        // 6. Traditional HTML parsing (as fallback or supplement)
        extractTraditionalHtml(document)?.let { results.addAll(it) }
        
        // 7. RSS/XML if detected
        if (format == DataFormat.RSS || format == DataFormat.XML) {
            extractXmlData(document)?.let { results.addAll(it) }
        }
        
        // Deduplicate and score results
        val uniqueResults = deduplicateResults(results)
        
        UniversalParseResult(
            format = format,
            items = uniqueResults,
            metadata = metadata,
            confidence = calculateOverallConfidence(uniqueResults),
            extractionMethods = determineUsedMethods(results),
            rawDataSnapshots = captureRawSnapshots(document)
        )
    }
    
    /**
     * Detect the primary data format of the page
     */
    private fun detectFormat(document: Document, url: String): DataFormat {
        val html = document.html()
        
        return when {
            // RSS/Atom feeds
            document.select("rss, feed, channel").isNotEmpty() -> DataFormat.RSS
            url.endsWith(".xml") || url.contains("/feed") -> DataFormat.XML
            
            // Framework detection
            NEXTJS_PATTERN.containsMatchIn(html) -> DataFormat.NEXTJS
            NUXTJS_PATTERN.containsMatchIn(html) -> DataFormat.NUXTJS
            REACT_PATTERN.containsMatchIn(html) -> DataFormat.SPA_REACT
            VUE_PATTERN.containsMatchIn(html) -> DataFormat.SPA_VUE
            ANGULAR_PATTERN.containsMatchIn(html) -> DataFormat.SPA_ANGULAR
            
            // JSON-heavy pages
            document.select("script[type='application/ld+json']").isNotEmpty() -> DataFormat.JSON_LD
            html.contains("application/json") -> DataFormat.JSON_EMBEDDED
            
            // API responses
            html.trim().startsWith("{") || html.trim().startsWith("[") -> DataFormat.JSON_API
            
            // Standard HTML
            else -> DataFormat.HTML_STANDARD
        }
    }
    
    /**
     * Extract data from Next.js __NEXT_DATA__ script
     */
    private fun extractNextJsData(document: Document): List<ExtractedContent>? {
        val script = document.select("script#__NEXT_DATA__").firstOrNull() 
            ?: return null
        
        return try {
            val jsonElement = json.parseToJsonElement(script.html())
            val props = jsonElement.jsonObject["props"]?.jsonObject
            val pageProps = props?.get("pageProps")?.jsonObject
            
            extractContentFromJsonObject(pageProps, "NextJS")
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Extract data from Nuxt.js window.__NUXT__
     */
    private fun extractNuxtJsData(document: Document): List<ExtractedContent>? {
        val scripts = document.select("script")
        
        for (script in scripts) {
            val content = script.html()
            if (content.contains("__NUXT__") || content.contains("window.__NUXT__")) {
                // Extract the JSON part
                val jsonMatch = NUXT_WINDOW_PATTERN.find(content)
                    ?: NUXT_FALLBACK_PATTERN.find(content)
                
                jsonMatch?.let {
                    return try {
                        val jsonElement = json.parseToJsonElement(it.groupValues[1])
                        extractContentFromJsonObject(jsonElement.jsonObject, "NuxtJS")
                    } catch (e: Exception) { null }
                }
            }
        }
        return null
    }
    
    /**
     * Extract data from generic SPA frameworks
     */
    private fun extractSPAData(document: Document): List<ExtractedContent>? {
        val results = mutableListOf<ExtractedContent>()
        
        // Look for common state patterns
        val scripts = document.select("script")
        for (script in scripts) {
            val content = script.html()
            for (pattern in SPA_STATE_PATTERNS) {
                pattern.find(content)?.let { match ->
                    try {
                        val jsonElement = json.parseToJsonElement(match.groupValues[1])
                        extractContentFromJsonObject(jsonElement.jsonObject, "SPA")?.let {
                            results.addAll(it)
                        }
                    } catch (e: Exception) { /* Continue */ }
                }
            }
        }
        
        return results.takeIf { it.isNotEmpty() }
    }
    
    /**
     * Extract JSON-LD structured data
     */
    private fun extractJsonLdData(document: Document): List<ExtractedContent>? {
        val results = mutableListOf<ExtractedContent>()
        
        document.select("script[type='application/ld+json']").forEach { script ->
            try {
                val jsonElement = json.parseToJsonElement(script.html())
                
                // Handle both single objects and arrays
                val items = when (jsonElement) {
                    is JsonArray -> jsonElement.toList()
                    is JsonObject -> listOf(jsonElement)
                    else -> emptyList()
                }
                
                items.filterIsInstance<JsonObject>().forEach { item ->
                    val type = item["@type"]?.jsonPrimitive?.content
                    val title = extractJsonString(item, "name", "headline", "title")
                    val url = extractJsonString(item, "url", "@id")
                    
                    if (type in MEDIA_SCHEMA_TYPES && title != null && url != null) {
                        results.add(ExtractedContent(
                            source = "JSON-LD:$type",
                            title = title,
                            url = url,
                            description = extractJsonString(item, "description", "abstract"),
                            thumbnail = extractJsonString(item, "image", "thumbnailUrl", "poster"),
                            duration = extractJsonString(item, "duration"),
                            year = extractJsonString(item, "datePublished", "dateCreated")?.take(4),
                            rating = extractJsonString(item, "aggregateRating")?.let {
                                NUMBER_REGEX.find(it)?.value
                            },
                            contentType = mapSchemaToContentType(type),
                            rawJson = item.toString(),
                            confidence = 0.9f
                        ))
                    }
                }
            } catch (e: Exception) { /* Continue */ }
        }
        
        return results.takeIf { it.isNotEmpty() }
    }
    
    /**
     * Extract OpenGraph and other meta tags
     */
    private fun extractMetaTags(document: Document, metadata: MutableMap<String, String>) {
        // OpenGraph tags
        document.select("meta[property^='og:']").forEach { meta ->
            val property = meta.attr("property").removePrefix("og:")
            val content = meta.attr("content")
            if (content.isNotEmpty()) {
                metadata["og:$property"] = content
            }
        }
        
        // Twitter cards
        document.select("meta[name^='twitter:']").forEach { meta ->
            val name = meta.attr("name").removePrefix("twitter:")
            val content = meta.attr("content")
            if (content.isNotEmpty()) {
                metadata["twitter:$name"] = content
            }
        }
        
        // Standard meta tags
        document.select("meta[name='description'], meta[name='keywords'], meta[name='author']").forEach { meta ->
            val name = meta.attr("name")
            val content = meta.attr("content")
            if (content.isNotEmpty()) {
                metadata[name] = content
            }
        }
    }
    
    /**
     * Extract embedded JSON objects from scripts
     */
    private fun extractEmbeddedJson(document: Document): List<ExtractedContent>? {
        val results = mutableListOf<ExtractedContent>()
        
        // Common variable patterns for embedded data
        document.select("script").forEach { script ->
            val content = script.html()

            EMBEDDED_JSON_PATTERNS.forEach { pattern ->
                pattern.findAll(content).forEach { match ->
                    try {
                        val jsonArray = json.parseToJsonElement(match.groupValues[2])
                        if (jsonArray is JsonArray) {
                            jsonArray.filterIsInstance<JsonObject>().forEach { item ->
                                extractContentFromSingleJson(item, "EmbeddedJSON")?.let {
                                    results.add(it)
                                }
                            }
                        }
                    } catch (e: Exception) { /* Continue */ }
                }
            }
        }
        
        return results.takeIf { it.isNotEmpty() }
    }
    
    /**
     * Extract data from HTML data-* attributes
     */
    private fun extractDataAttributes(document: Document): List<ExtractedContent>? {
        val results = mutableListOf<ExtractedContent>()
        
        // Elements with data attributes that might contain content info
        val candidates = document.select("[data-title], [data-name], [data-video], [data-movie], [data-id]")
        
        candidates.forEach { element ->
            val attrs = element.attributes()
            val dataAttrs = attrs.filter { it.key.startsWith("data-") }
                .associate { it.key.removePrefix("data-") to it.value }
            
            if (dataAttrs.isNotEmpty()) {
                val title = dataAttrs["title"] ?: dataAttrs["name"] ?: dataAttrs["video-title"]
                val url = element.attr("href").ifEmpty { dataAttrs["url"] ?: dataAttrs["link"] }
                
                if (!title.isNullOrEmpty() && url?.isNotEmpty() == true) {
                    results.add(ExtractedContent(
                        source = "DataAttributes",
                        title = title,
                        url = url,
                        description = dataAttrs["description"],
                        thumbnail = dataAttrs["poster"] ?: dataAttrs["thumb"] ?: dataAttrs["image"],
                        duration = dataAttrs["duration"] ?: dataAttrs["length"],
                        year = dataAttrs["year"] ?: dataAttrs["release"],
                        rating = dataAttrs["rating"] ?: dataAttrs["score"],
                        quality = dataAttrs["quality"],
                        confidence = 0.75f
                    ))
                }
            }
        }
        
        return results.takeIf { it.isNotEmpty() }
    }
    
    /**
     * Traditional HTML parsing for content extraction
     */
    private fun extractTraditionalHtml(document: Document): List<ExtractedContent>? {
        val results = mutableListOf<ExtractedContent>()
        
        // Multiple selector strategies for different site layouts
        val itemSelectors = listOf(
            // Specific media selectors
            ".video-item, .movie-item, .video-card, .movie-card",
            ".result-item, .search-result, .item",
            "article.video, article.movie, article.item",
            ".post, .entry, .card",
            // Grid/list items
            ".grid-item, .list-item, .col-item",
            // Table rows (for torrent-style sites)
            "table.results tr, table.torrents tr",
            "tbody tr[data-id], tbody tr.result"
        )
        
        for (selector in itemSelectors) {
            try {
                val elements = document.select(selector)
                if (elements.size >= 2) {
                    elements.forEach { element ->
                        extractFromHtmlElement(element)?.let { results.add(it) }
                    }
                    if (results.isNotEmpty()) break
                }
            } catch (e: Exception) { /* Try next selector */ }
        }
        
        return results.takeIf { it.isNotEmpty() }
    }
    
    /**
     * Extract content from a single HTML element
     */
    private fun extractFromHtmlElement(element: Element): ExtractedContent? {
        // Find title - try multiple selectors
        val title = element.select("h1, h2, h3, h4, .title, [class*='title'], a[title]")
            .firstOrNull()?.text()
            ?: element.select("a").maxByOrNull { it.text().length }?.text()
            ?: return null
        
        if (title.length < 2) return null
        
        // Find URL
        val url = element.select("a[href]").firstOrNull()?.attr("href")
            ?: return null
        
        // Find thumbnail
        val thumbnail = element.select("img").firstOrNull()?.let { img ->
            img.attr("src").ifEmpty { 
                img.attr("data-src").ifEmpty { 
                    img.attr("data-lazy-src") 
                }
            }
        }
        
        // Find duration
        val duration = element.select("[class*='duration'], [class*='time'], .length, .runtime")
            .firstOrNull()?.text()
            ?: DURATION_REGEX.find(element.text())?.value
        
        // Find quality
        val quality = element.select("[class*='quality'], .resolution, .badge")
            .firstOrNull()?.text()
            ?: listOf("4K", "2160p", "1080p", "720p", "480p", "HD")
                .firstOrNull { element.text().contains(it, ignoreCase = true) }
        
        // Find year
        val year = YEAR_REGEX.find(element.text())?.value

        // Find rating
        val rating = element.select("[class*='rating'], [class*='imdb'], .score, .stars")
            .firstOrNull()?.text()
            ?.let { RATING_VALUE_REGEX.find(it)?.value }
        
        // Find description
        val description = element.select(".description, .desc, .synopsis, p")
            .firstOrNull()?.text()?.take(300)
        
        return ExtractedContent(
            source = "HTML",
            title = title.trim(),
            url = url,
            description = description,
            thumbnail = thumbnail,
            duration = duration,
            year = year,
            rating = rating,
            quality = quality,
            confidence = 0.65f
        )
    }
    
    /**
     * Extract XML/RSS feed data
     */
    private fun extractXmlData(document: Document): List<ExtractedContent>? {
        val results = mutableListOf<ExtractedContent>()
        
        // RSS items
        document.select("item, entry").forEach { item ->
            val title = item.select("title").firstOrNull()?.text()
            val link = item.select("link").firstOrNull()?.text()
                ?: item.select("link").firstOrNull()?.attr("href")
            
            if (!title.isNullOrEmpty() && !link.isNullOrEmpty()) {
                results.add(ExtractedContent(
                    source = "RSS/XML",
                    title = title,
                    url = link,
                    description = item.select("description, summary, content").firstOrNull()?.text(),
                    thumbnail = item.select("enclosure[type^='image'], media|thumbnail, media|content")
                        .firstOrNull()?.attr("url"),
                    year = item.select("pubDate, published, updated").firstOrNull()?.text()?.take(4),
                    confidence = 0.8f
                ))
            }
        }
        
        return results.takeIf { it.isNotEmpty() }
    }
    
    // ==========================================
    // Helper Methods
    // ==========================================
    
    /**
     * Extract content items from a JSON object recursively
     */
    private fun extractContentFromJsonObject(
        obj: JsonObject?, 
        source: String
    ): List<ExtractedContent>? {
        if (obj == null) return null
        
        val results = mutableListOf<ExtractedContent>()
        
        // Look for arrays that might contain content items
        val arrayKeys = listOf(
            "videos", "movies", "items", "results", "data", "content",
            "episodes", "series", "shows", "posts", "entries"
        )
        
        fun searchObject(current: JsonObject, depth: Int = 0) {
            if (depth > 5) return // Prevent infinite recursion
            
            for ((key, value) in current) {
                when {
                    key.lowercase() in arrayKeys && value is JsonArray -> {
                        value.filterIsInstance<JsonObject>().forEach { item ->
                            extractContentFromSingleJson(item, source)?.let { results.add(it) }
                        }
                    }
                    value is JsonObject -> searchObject(value, depth + 1)
                    value is JsonArray -> {
                        value.filterIsInstance<JsonObject>().forEach { item ->
                            // Check if this looks like content item
                            if (looksLikeContentItem(item)) {
                                extractContentFromSingleJson(item, source)?.let { results.add(it) }
                            } else {
                                searchObject(item, depth + 1)
                            }
                        }
                    }
                }
            }
        }
        
        searchObject(obj)
        return results.takeIf { it.isNotEmpty() }
    }
    
    /**
     * Check if a JSON object looks like a content item
     */
    private fun looksLikeContentItem(obj: JsonObject): Boolean {
        val keys = obj.keys.map { it.lowercase() }
        val contentKeys = listOf("title", "name", "url", "link", "id", "video", "movie")
        return contentKeys.count { it in keys } >= 2
    }
    
    /**
     * Extract a single content item from JSON
     */
    private fun extractContentFromSingleJson(obj: JsonObject, source: String): ExtractedContent? {
        val title = extractJsonString(obj, "title", "name", "headline", "videoTitle", "movieTitle")
            ?: return null
        
        val url = extractJsonString(obj, "url", "link", "href", "slug", "id")?.let {
            if (it.startsWith("http")) it else "/$it"
        } ?: return null
        
        return ExtractedContent(
            source = source,
            title = title,
            url = url,
            description = extractJsonString(obj, "description", "desc", "synopsis", "overview"),
            thumbnail = extractJsonString(obj, "thumbnail", "thumb", "poster", "image", "img", "cover"),
            duration = extractJsonString(obj, "duration", "length", "runtime"),
            year = extractJsonString(obj, "year", "release", "date", "releaseDate")?.take(4),
            rating = extractJsonString(obj, "rating", "score", "imdb", "imdbRating"),
            quality = extractJsonString(obj, "quality", "resolution"),
            rawJson = obj.toString(),
            confidence = 0.85f
        )
    }
    
    /**
     * Extract string from JSON trying multiple keys
     */
    private fun extractJsonString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            obj[key]?.let { value ->
                return when (value) {
                    is JsonPrimitive -> value.content
                    is JsonObject -> value.toString()
                    else -> null
                }
            }
        }
        return null
    }
    
    /**
     * Map Schema.org type to ContentType
     */
    private fun mapSchemaToContentType(schemaType: String?): ContentType {
        return when (schemaType) {
            "Movie" -> ContentType.MOVIE
            "TVSeries" -> ContentType.SERIES
            "TVEpisode" -> ContentType.EPISODE
            "VideoObject", "MediaObject" -> ContentType.VIDEO
            else -> ContentType.VIDEO
        }
    }
    
    /**
     * Deduplicate results based on URL/title similarity
     */
    private fun deduplicateResults(results: List<ExtractedContent>): List<ExtractedContent> {
        val seen = mutableSetOf<String>()
        return results.filter { item ->
            val key = "${item.url}|${item.title.lowercase().take(50)}"
            if (key in seen) {
                false
            } else {
                seen.add(key)
                true
            }
        }.sortedByDescending { it.confidence }
    }
    
    /**
     * Calculate overall extraction confidence
     */
    private fun calculateOverallConfidence(results: List<ExtractedContent>): Float {
        if (results.isEmpty()) return 0f
        return results.map { it.confidence }.average().toFloat()
    }
    
    /**
     * Determine which extraction methods yielded results
     */
    private fun determineUsedMethods(results: List<ExtractedContent>): List<String> {
        return results.map { it.source }.distinct()
    }
    
    /**
     * Capture raw data snapshots for debugging
     */
    private fun captureRawSnapshots(document: Document): Map<String, String> {
        return mapOf(
            "jsonLd" to document.select("script[type='application/ld+json']")
                .map { it.html() }.joinToString("\n").take(5000),
            "metaTags" to document.select("meta[property^='og:'], meta[name]")
                .map { "${it.attr("property")}${it.attr("name")}=${it.attr("content")}" }
                .joinToString("\n")
        )
    }
}

// ==========================================
// Data Classes
// ==========================================

enum class DataFormat {
    HTML_STANDARD,
    JSON_LD,
    JSON_EMBEDDED,
    JSON_API,
    NEXTJS,
    NUXTJS,
    SPA_REACT,
    SPA_VUE,
    SPA_ANGULAR,
    RSS,
    XML
}

@Serializable
data class UniversalParseResult(
    val format: DataFormat,
    val items: List<ExtractedContent>,
    val metadata: Map<String, String>,
    val confidence: Float,
    val extractionMethods: List<String>,
    val rawDataSnapshots: Map<String, String> = emptyMap()
)

@Serializable
data class ExtractedContent(
    val source: String,
    val title: String,
    val url: String,
    val description: String? = null,
    val thumbnail: String? = null,
    val duration: String? = null,
    val year: String? = null,
    val rating: String? = null,
    val quality: String? = null,
    val contentType: ContentType = ContentType.VIDEO,
    val rawJson: String? = null,
    val confidence: Float = 0.5f
)
